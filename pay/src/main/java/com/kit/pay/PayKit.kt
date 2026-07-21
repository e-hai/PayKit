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
import com.kit.pay.utils.MainThreadDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

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
 * PayKit.configure(context, configuration)
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
    private var updateListener: UpdatedCustomerInfoListener? = null

    // 用于暂存由 Activity 发起的购买回调
    private var activePurchaseCallback: PurchaseCallback? = null

    init {
        billingWrapper.purchasesUpdatedListener = this
    }

    /**
     * 初始化 SDK 内部组件：连接 Billing → 自动 [syncPurchasesInternal]。
     * 本地缓存由宿主通过 [getCustomerInfo]（`forceSync = false`）按需读取。
     */
    private fun initialize() {
        billingWrapper.startConnection(
            onConnected = {
                LogUtil.d("init connected startSync=true")
                applicationScope.launch {
                    syncPurchasesInternal()
                }
            },
            onError = { error ->
                LogUtil.e("init connect fail code=${error.code} msg=${error.message}")
            }
        )
    }

    private fun dispatchCustomerInfo(info: CustomerInfo) {
        val listener = updateListener ?: return
        MainThreadDispatcher.post { listener.onReceived(info) }
    }

    /**
     * 取出当前购买回调并在主线程执行；保证只投递一次。
     */
    private fun dispatchPurchaseCallback(block: (PurchaseCallback) -> Unit) {
        val callback = activePurchaseCallback ?: return
        activePurchaseCallback = null
        MainThreadDispatcher.post { block(callback) }
    }

    /**
     * 向 Google 同步购买记录：确认未处理的 PURCHASED、更新缓存与监听器。
     *
     * - SUBS / INAPP 并行查询
     * - acknowledge / consume 失败的订单不计入活跃权益，且本方法返回 failure
     */
    private suspend fun syncPurchasesInternal(): Result<CustomerInfo> = coroutineScope {
        LogUtil.d("syncPurchases start")
        val cachedInfo = deviceCache.getCachedCustomerInfo()

        val subsDeferred = async { billingWrapper.queryPurchasesAsync(ProductType.SUBS) }
        val inappDeferred = async { billingWrapper.queryPurchasesAsync(ProductType.INAPP) }
        val subsResult = subsDeferred.await()
        val inappResult = inappDeferred.await()

        val allTransactions = mutableListOf<StoreTransaction>()
        subsResult.onSuccess { allTransactions.addAll(it) }
        inappResult.onSuccess { allTransactions.addAll(it) }

        LogUtil.d(
            "syncPurchases queried " +
                "subsOk=${subsResult.isSuccess} inappOk=${inappResult.isSuccess} " +
                "count=${allTransactions.size}"
        )

        if (subsResult.isFailure && inappResult.isFailure) {
            val error = subsResult.exceptionOrNull()
                ?: inappResult.exceptionOrNull()
                ?: PayKitError(ErrorCode.STORE_PROBLEM, "Failed to query purchases")
            LogUtil.e("syncPurchases queryFail fallbackCache=${cachedInfo != null} msg=${error.message}")
            return@coroutineScope if (cachedInfo != null) {
                Result.success(cachedInfo)
            } else {
                Result.failure(error)
            }
        }

        val transactionsForCompute = allTransactions.toMutableList()
        var acknowledgeFailure: Throwable? = null

        val purchasedTransactions = allTransactions.filter {
            it.purchaseState == PurchaseState.PURCHASED
        }
        for (txn in purchasedTransactions) {
            if (txn.isAcknowledged) {
                continue
            }
            val isConsumable =
                txn.productIds.any { configuration.consumableProductIds.contains(it) }
            val ackResult = billingWrapper.consumeAndAcknowledge(txn, isConsumable)
            if (ackResult.isSuccess) {
                val index = transactionsForCompute.indexOfFirst {
                    it.purchaseToken == txn.purchaseToken
                }
                if (index >= 0) {
                    transactionsForCompute[index] = txn.copy(isAcknowledged = true)
                }
            } else {
                val error = ackResult.exceptionOrNull()
                    ?: PayKitError(ErrorCode.STORE_PROBLEM, "Acknowledge/consume failed")
                LogUtil.e(
                    "syncPurchases ackFail orderId=${txn.orderId} " +
                        "products=${txn.productIds} consumable=$isConsumable msg=${error.message}"
                )
                if (acknowledgeFailure == null) {
                    acknowledgeFailure = error
                }
            }
        }

        val newInfo = customerInfoHelper.computeCustomerInfo(cachedInfo, transactionsForCompute)
        deviceCache.cacheCustomerInfo(newInfo)
        dispatchCustomerInfo(newInfo)

        return@coroutineScope if (acknowledgeFailure != null) {
            LogUtil.e(
                "syncPurchases done success=false " +
                    "activeSubs=${newInfo.activeSubscriptions.size} " +
                    "pending=${newInfo.pendingPurchases.size} " +
                    "msg=${acknowledgeFailure.message}"
            )
            Result.failure(
                PayKitError(
                    ErrorCode.STORE_PROBLEM,
                    "Failed to acknowledge/consume one or more purchases: ${acknowledgeFailure.message}"
                )
            )
        } else {
            LogUtil.d(
                "syncPurchases done success=true " +
                    "activeSubs=${newInfo.activeSubscriptions.size} " +
                    "nonConsumables=${newInfo.nonConsumablePurchases.size} " +
                    "pending=${newInfo.pendingPurchases.size} " +
                    "records=${newInfo.allPurchaseRecords.size}"
            )
            Result.success(newInfo)
        }
    }

    // ==========================================
    // Public Facing APIs
    // ==========================================

    fun setUpdatedCustomerInfoListener(listener: UpdatedCustomerInfoListener?) {
        this.updateListener = listener
    }

    /**
     * 获取用户权益。
     *
     * @param forceSync `true`（默认）时向 Google 同步后再返回；`false` 仅读本地缓存（可能为 null）
     */
    suspend fun getCustomerInfo(forceSync: Boolean = true): CustomerInfo? =
        withContext(Dispatchers.IO) {
            if (!forceSync) {
                return@withContext deviceCache.getCachedCustomerInfo()
            }
            return@withContext syncPurchasesInternal().getOrElse { error ->
                LogUtil.e("getCustomerInfo syncFail msg=${error.message}")
                deviceCache.getCachedCustomerInfo()
            }
        }

    /**
     * 同步购买记录（补单 / 刷新权益）。
     *
     * 适合：应用启动后主动刷新、支付 PENDING 之后再查、诊断掉单。
     * Google 无独立「恢复」API，本方法即向商店重新查询并更新本地状态。
     */
    suspend fun syncPurchases(): Result<CustomerInfo> = withContext(Dispatchers.IO) {
        syncPurchasesInternal()
    }

    /**
     * 恢复购买。
     *
     * 产品语义上的「恢复购买」按钮应调用本方法；实现与 [syncPurchases] 相同。
     * 可找回：当前有效订阅、未消耗的非消耗品 / 未 consume 的消耗品。
     * 已消耗的消耗型商品无法通过恢复买回。
     */
    suspend fun restorePurchases(): Result<CustomerInfo> = syncPurchases()

    /**
     * 当前待确认订单（会先 [syncPurchases]）。
     */
    suspend fun getPendingPurchases(): List<StoreTransaction> =
        getCustomerInfo(forceSync = true)?.pendingPurchases.orEmpty()

    /**
     * 本地合并后的购买历史（会先同步商店当前购买，再与缓存合并）。
     */
    suspend fun getPurchaseHistory(): List<StoreTransaction> =
        getCustomerInfo(forceSync = true)?.allPurchaseRecords.orEmpty()

    /**
     * 从本地缓存按 purchaseToken 查找交易（不同步商店）。
     */
    fun findTransaction(purchaseToken: String): StoreTransaction? {
        return deviceCache.getCachedCustomerInfo()
            ?.allPurchaseRecords
            ?.find { it.purchaseToken == purchaseToken }
    }

    /**
     * 查询指定商品 ID 的详细信息。
     */
    suspend fun getProducts(productIds: Set<String>): Result<List<StoreProduct>> =
        withContext(Dispatchers.IO) {
            val subsIds = productIds.filter { configuration.subsProductIds.contains(it) }
            val inappIds = productIds.filter {
                configuration.consumableProductIds.contains(it) ||
                        configuration.nonConsumableProductIds.contains(it)
            }

            val allProducts = mutableListOf<StoreProduct>()

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
     * 发起支付。
     *
     * 回调（[PurchaseCallback]）一律在**主线程**投递。
     *
     * @param isOfferPersonalized 是否披露个性化价格（欧盟消费者保护要求）。
     * 默认取 [PayKitConfiguration.isOfferPersonalizedDefault]。
     */
    fun purchase(
        activity: Activity,
        storeProduct: StoreProduct,
        callback: PurchaseCallback,
        isOfferPersonalized: Boolean = configuration.isOfferPersonalizedDefault
    ) {
        this.activePurchaseCallback = callback
        LogUtil.d(
            "purchase start productId=${storeProduct.productId} type=${storeProduct.type} " +
                "personalized=$isOfferPersonalized"
        )

        applicationScope.launch {
            val result = billingWrapper.makePurchaseAsync(
                WeakReference(activity),
                storeProduct,
                isOfferPersonalized
            )
            result.onFailure { error ->
                LogUtil.e(
                    "purchase launchFail productId=${storeProduct.productId} msg=${error.message}"
                )
                dispatchPurchaseCallback { cb ->
                    cb.onError(
                        error as? PayKitError
                            ?: PayKitError(
                                ErrorCode.STORE_PROBLEM,
                                error.message ?: "Unknown error"
                            ),
                        false
                    )
                }
            }
        }
    }

    // ==========================================
    // Implements PayKitPurchasesUpdatedListener
    // ==========================================
    override fun onPurchasesUpdated(successfulPurchases: List<StoreTransaction>) {
        applicationScope.launch {
            val syncResult = syncPurchasesInternal()
            val latestInfo = syncResult.getOrNull()
            if (activePurchaseCallback == null) return@launch

            val purchased = successfulPurchases.filter {
                it.purchaseState == PurchaseState.PURCHASED
            }
            if (purchased.isNotEmpty()) {
                if (latestInfo != null) {
                    LogUtil.d(
                        "purchase completed orderId=${purchased.first().orderId} " +
                            "products=${purchased.first().productIds}"
                    )
                    dispatchPurchaseCallback { cb ->
                        cb.onCompleted(purchased.first(), latestInfo)
                    }
                } else {
                    val error = syncResult.exceptionOrNull() as? PayKitError
                        ?: PayKitError(
                            ErrorCode.STORE_PROBLEM,
                            syncResult.exceptionOrNull()?.message
                                ?: "Sync failed after purchase"
                        )
                    LogUtil.e(
                        "purchase syncFailAfterPay orderId=${purchased.first().orderId} " +
                            "code=${error.code} msg=${error.message}"
                    )
                    dispatchPurchaseCallback { cb ->
                        cb.onError(error, userCancelled = false)
                    }
                }
                return@launch
            }

            val pending = successfulPurchases.filter {
                it.purchaseState == PurchaseState.PENDING
            }
            if (pending.isNotEmpty()) {
                LogUtil.d(
                    "purchase pending orderId=${pending.first().orderId} " +
                        "products=${pending.first().productIds}"
                )
                dispatchPurchaseCallback { cb ->
                    cb.onPending(pending.first())
                }
                return@launch
            }

            LogUtil.e("purchase updateEmpty count=${successfulPurchases.size}")
            dispatchPurchaseCallback { cb ->
                cb.onError(
                    PayKitError(ErrorCode.UNKNOWN, "No purchased transaction in update"),
                    userCancelled = false
                )
            }
        }
    }

    override fun onPurchasesFailedToUpdate(error: PayKitError, userCancelled: Boolean) {
        LogUtil.e(
            "purchase failed code=${error.code} cancelled=$userCancelled msg=${error.message}"
        )
        dispatchPurchaseCallback { cb ->
            cb.onError(error, userCancelled)
        }
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
