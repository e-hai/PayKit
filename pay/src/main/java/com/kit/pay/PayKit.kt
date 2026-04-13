package com.kit.pay

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.kit.pay.billing.BillingAbstract
import com.kit.pay.billing.GoogleBillingWrapper
import com.kit.pay.billing.PayKitPurchasesUpdatedListener
import com.kit.pay.caching.DeviceCache
import com.kit.pay.interfaces.*
import com.kit.pay.models.*
import com.kit.pay.subscriber.CustomerInfoHelper
import com.kit.pay.utils.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PayKit 核心单例类。
 * 
 * 负责管理支付流程的各个方面，包括：
 * - 商品查询和管理
 * - 支付发起和回调处理
 * - 订单确认和权益同步
 * - 用户权益状态监听
 * 
 * 使用示例：
 * ```kotlin
 * // 初始化
 * PayKit.configure(context, configuration)
 * 
 * // 获取单例
 * val payKit = PayKit.shared
 * ```
 */
class PayKit private constructor(
    private val applicationContext: Context,
    private val applicationScope: CoroutineScope,
    private val configuration: PayKitConfiguration
) : PayKitPurchasesUpdatedListener {

    private val deviceCache = DeviceCache(applicationContext)
    private val customerInfoHelper = CustomerInfoHelper(configuration)
    private val billingWrapper: BillingAbstract = GoogleBillingWrapper(applicationContext)

    private val _isConfigured = AtomicBoolean(true)
    private var updateListener: UpdatedCustomerInfoListener? = null

    // 用于暂存由 Activity 发起的购买回调
    private var activePurchaseCallback: PurchaseCallback? = null

    init {
        billingWrapper.purchasesUpdatedListener = this
    }

    /**
     * 初始化 SDK 内部组件。
     * 
     * 执行以下操作：
     * 1. 从缓存中加载用户权益信息（如果有）
     * 2. 连接 Google Play Billing 服务
     * 3. 连接成功后自动同步购买记录
     * 
     * 此方法在 [configure] 中自动调用，无需手动调用。
     */
    private fun initialize() {
        // 先快速吐出缓存，实现 "无等待/弱网" 秒加载逻辑
        val cached = deviceCache.getCachedCustomerInfo()
        if (cached != null) {
            LogUtil.d("[PayKit] 缓存命中，直接返回 =$cached")
            updateListener?.onReceived(cached)
        }

        billingWrapper.startConnection(
            onConnected = {
                // 连接成功，执行本地小票扫描
                applicationScope.launch {
                    syncPurchases()
                }
            },
            onError = { error ->
                // 连接失败，静默失败即可，业务通过 getCustomerInfo 会获知
            }
        )
    }

    /**
     * 同步用户的购买记录并更新权益状态。
     * 
     * 执行流程：
     * 1. 并行查询订阅商品和一次性商品的购买记录
     * 2. 对未确认的订单进行确认或消耗
     * 3. 计算最新的用户权益信息
     * 4. 缓存权益信息并通知监听器
     * 
     * @return 最新的用户权益信息，如果查询失败则返回缓存的信息
     */
    private suspend fun syncPurchases(): CustomerInfo? {
        val cachedInfo = deviceCache.getCachedCustomerInfo()

        // 并行查询 SUBS 和 INAPP 两种类型的购买记录
        val subsResult = billingWrapper.queryPurchasesAsync(ProductType.SUBS)
        val inappResult = billingWrapper.queryPurchasesAsync(ProductType.INAPP)

        // 合并两个结果
        val allTransactions = mutableListOf<StoreTransaction>()

        subsResult.onSuccess { transactions ->
            allTransactions.addAll(transactions)
        }

        inappResult.onSuccess { transactions ->
            allTransactions.addAll(transactions)
        }

        // 如果两个都失败，返回缓存
        if (subsResult.isFailure && inappResult.isFailure) {
            return cachedInfo
        }

        // 处理所有的回执动作，没确认的去确认
        for (txn in allTransactions) {
            if (!txn.isAcknowledged) {
                val isConsumable =
                    txn.productIds.any { configuration.consumableProductIds.contains(it) }
                billingWrapper.consumeAndAcknowledge(txn, isConsumable)
            }
        }

        // 当所有发货处理完后，结算最新 CustomerInfo
        val newInfo = customerInfoHelper.computeCustomerInfo(cachedInfo, allTransactions)
        deviceCache.cacheCustomerInfo(newInfo)
        updateListener?.onReceived(newInfo)

        return newInfo
    }

    // ==========================================
    // Public Facing APIs 
    // ==========================================

    /**
     * 设置用户权益状态变化监听器。
     * 
     * 当用户的订阅状态或购买记录发生变化时，会通过此监听器通知。
     * 建议在应用启动时设置，以实时响应用户权益变化。
     * 
     * @param listener 权益状态变化监听器，传 null 可取消监听
     * 
     * 使用示例：
     * ```kotlin
     * PayKit.shared.setUpdatedCustomerInfoListener(object : UpdatedCustomerInfoListener {
     *     override fun onReceived(customerInfo: CustomerInfo) {
     *         // 更新 UI，显示 VIP 状态等
     *     }
     * })
     * ```
     */
    fun setUpdatedCustomerInfoListener(listener: UpdatedCustomerInfoListener?) {
        this.updateListener = listener
    }

    /**
     * 获取当前用户的权益信息。
     * 
     * 此方法会：
     * 1. 查询 Google Play 的购买记录
     * 2. 确认未完成的订单
     * 3. 返回最新的权益状态
     * 
     * 建议在以下场景调用：
     * - 应用启动时检查用户权益
     * - 进入付费功能页面时验证权限
     * - 用户点击"恢复购买"按钮时
     * 
     * @return 用户权益信息，如果查询失败则返回 null
     * 
     * 使用示例：
     * ```kotlin
     * viewModelScope.launch {
     *     val customerInfo = PayKit.shared.getCustomerInfo()
     *     if (customerInfo != null) {
     *         val isVip = customerInfo.activeSubscriptions.contains("sub_monthly")
     *         // 根据权益状态更新 UI
     *     }
     * }
     * ```
     */
    suspend fun getCustomerInfo(): CustomerInfo? = withContext(Dispatchers.IO) {
        return@withContext syncPurchases()
    }

    /**
     * 查询指定商品 ID 的详细信息。
     * 
     * 根据配置自动识别商品类型（订阅/消耗型/非消耗型），
     * 并返回包含价格、标题、描述等完整信息的商品列表。
     * 
     * @param productIds 要查询的商品 ID 集合
     * @return 查询结果，成功时包含商品列表，失败时包含错误信息
     * 
     * 使用示例：
     * ```kotlin
     * viewModelScope.launch {
     *     val result = PayKit.shared.getProducts(setOf("sub_monthly", "coins_100"))
     *     result.onSuccess { products ->
     *         products.forEach { product ->
     *             Log.d("PayKit", "${product.productId}: ${product.price}")
     *         }
     *     }.onFailure { error ->
     *         Log.e("PayKit", "查询失败: ${error.message}")
     *     }
     * }
     * ```
     */
    suspend fun getProducts(productIds: Set<String>): Result<List<StoreProduct>> =
        withContext(Dispatchers.IO) {
            // 根据配置将商品 ID 分为订阅商品和一次性商品两类
            val subsIds = productIds.filter { configuration.subsProductIds.contains(it) }
            val inappIds = productIds.filter {
                configuration.consumableProductIds.contains(it) ||
                        configuration.nonConsumableProductIds.contains(it)
            }

            val allProducts = mutableListOf<StoreProduct>()

            // 分别查询订阅商品和一次性商品
            if (subsIds.isNotEmpty()) {
                val subsResult = billingWrapper.queryProductDetailsAsync(
                    ProductType.SUBS,
                    subsIds.toSet()
                )
                if (subsResult.isFailure) {
                    return@withContext Result.failure(subsResult.exceptionOrNull()!!)
                }
                allProducts.addAll(subsResult.getOrNull() ?: emptyList())
            }

            if (inappIds.isNotEmpty()) {
                val inappResult = billingWrapper.queryProductDetailsAsync(
                    ProductType.INAPP,
                    inappIds.toSet()
                )
                if (inappResult.isFailure) {
                    return@withContext Result.failure(inappResult.exceptionOrNull()!!)
                }
                allProducts.addAll(inappResult.getOrNull() ?: emptyList())
            }

            return@withContext Result.success(allProducts)
        }

    /**
     * 发起支付流程。
     * 
     * 此方法会：
     * 1. 启动 Google Play 支付界面
     * 2. 等待用户完成支付
     * 3. 通过回调通知支付结果
     * 
     * 注意：
     * - 支付成功后，SDK 会自动确认订单
     * - 订阅商品需要提供 subscriptionToken
     * - 支付结果通过 [PurchaseCallback] 返回
     * 
     * @param activity 当前 Activity，用于启动支付界面
     * @param storeProduct 要购买的商品信息
     * @param callback 支付结果回调
     * 
     * 使用示例：
     * ```kotlin
     * PayKit.shared.purchase(activity, product, object : PurchaseCallback {
     *     override fun onCompleted(transaction: StoreTransaction, customerInfo: CustomerInfo) {
     *         Log.d("PayKit", "支付成功: ${transaction.orderId}")
     *         // 发放权益
     *     }
     *
     *     override fun onError(error: PayKitError, userCancelled: Boolean) {
     *         if (userCancelled) {
     *             Log.d("PayKit", "用户取消")
     *         } else {
     *             Log.e("PayKit", "支付失败: ${error.message}")
     *         }
     *     }
     * })
     * ```
     */
    fun purchase(
        activity: WeakReference<Activity>,
        storeProduct: StoreProduct,
        callback: PurchaseCallback
    ) {
        this.activePurchaseCallback = callback

        applicationScope.launch {
            val result = billingWrapper.makePurchaseAsync(activity, storeProduct)
            result.onFailure { error ->
                // 启动购买流程失败
                this@PayKit.activePurchaseCallback?.onError(
                    PayKitError(
                        ErrorCode.STORE_PROBLEM,
                        error.message ?: "Unknown error"
                    ),
                    false
                )
                this@PayKit.activePurchaseCallback = null
            }
        }
    }

    // ==========================================
    // Implements PayKitPurchasesUpdatedListener
    // ==========================================
    override fun onPurchasesUpdated(successfulPurchases: List<StoreTransaction>) {
        // 由于有单笔回调，走一波完全同步
        applicationScope.launch {
            val latestInfo = syncPurchases()
            val callback = activePurchaseCallback

            if (callback != null && successfulPurchases.isNotEmpty() && latestInfo != null) {
                callback.onCompleted(successfulPurchases.first(), latestInfo)
                activePurchaseCallback = null
            }
        }
    }

    override fun onPurchasesFailedToUpdate(error: PayKitError, userCancelled: Boolean) {
        activePurchaseCallback?.onError(error, userCancelled)
        activePurchaseCallback = null
    }

    companion object {
        @Volatile
        private var sharedInstance: PayKit? = null

        val shared: PayKit
            get() = sharedInstance ?: throw IllegalStateException("PayKit is not configured.")

        fun configure(
            context: Context,
            configuration: PayKitConfiguration
        ) {
            if (sharedInstance == null) {
                synchronized(this) {
                    if (sharedInstance == null) {
                        val applicationContext = context.applicationContext
                        val applicationScope = ProcessLifecycleOwner.get().lifecycleScope
                        val instance = PayKit(applicationContext, applicationScope, configuration)
                        sharedInstance = instance
                        instance.initialize()
                    }
                }
            }
        }
    }
}
