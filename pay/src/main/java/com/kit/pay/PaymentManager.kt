package com.kit.pay


import android.app.Activity
import com.kit.pay.base.InitializationCallback
import com.kit.pay.base.MakePaymentCallback
import com.kit.pay.base.PaymentCallback
import com.kit.pay.base.PaymentConfig
import com.kit.pay.base.PaymentCode
import com.kit.pay.base.PaymentProductDetails
import com.kit.pay.base.PaymentProductType
import com.kit.pay.base.PaymentProvider
import com.kit.pay.base.PaymentPurchaseDetails
import com.kit.pay.base.PaymentPurchaseState
import com.kit.pay.base.PurchaseCallback
import com.kit.pay.base.QueryProductsCallback
import com.kit.pay.base.QueryPurchasesCallback
import com.kit.pay.billing.GooglePurchaseDetails
import com.kit.pay.utils.LogUtil


/**
 * 管理支付逻辑的单例类。负责初始化支付提供者并处理支付、订阅状态查询、商品查询等操作。
 */
class PaymentManager private constructor() {


    // 当前使用的支付提供者
    private var paymentProvider: PaymentProvider? = null

    // 商品配置
    private var paymentConfig: PaymentConfig? = null

    // 当前有效的权益 (按商品类型分类管理)
    // 订阅商品权益
    private val subsEntitlements = mutableSetOf<String>()

    // 一次性非消耗商品权益（永久有效）
    private val nonConsumableEntitlements = mutableSetOf<String>()

    // 一次性可消耗商品库存（记录已购买但未消费的商品）
    private val consumableInventory = mutableSetOf<String>()

    // 等待订单返回映射表：key -> PaymentCallback
    private val waitingPayments = mutableMapOf<String, PaymentCallback>()

    // 是否已成功初始化支付提供者
    @Volatile
    private var isInitialized = false

    private var initCallback: InitializationCallback? = null

    /**
     * 获取商品配置（简化访问）
     */
    private fun getPaymentConfig(): PaymentConfig {
        return paymentConfig ?: throw IllegalStateException("PaymentConfig not initialized")
    }

    /**
     * 初始化支付管理器并同步当前状态。
     *
     * @param provider 实现了 PaymentProvider 接口的支付提供者。
     * @param config 商品映射配置。
     * @param callback 初始化结果回调。
     */
    fun init(
        provider: PaymentProvider,
        config: PaymentConfig,
        callback: InitializationCallback
    ) {
        // 清理旧资源
        cleanup()

        this.paymentProvider = provider
        this.paymentConfig = config
        this.initCallback = callback

        // 设置提供者的购买监听器，由提供者主动通知，时机不定，因为订单可在设备外部产生（网页，另一台设备同一个账号，推广链接）
        provider.setPurchaseListener(object : PurchaseCallback {
            override fun onUpdate(
                code: PaymentCode,
                purchaseDetailList: List<PaymentPurchaseDetails>
            ) {
                handleIncomingPurchase(code, purchaseDetailList)
            }
        })

        provider.initialize(object : InitializationCallback {
            override fun onSuccess() {
                LogUtil.d("Payment provider initialized successfully")
                isInitialized = true
                callback.onSuccess()
            }

            override fun onFailure(errorCode: PaymentCode) {
                LogUtil.e("Payment provider initialization failed: $errorCode")
                isInitialized = false
                callback.onFailure(errorCode)
            }
        })
    }


    /**
     * 查询商品信息并自动刷新本地缓存。
     *
     * @param productIds 商品 ID 列表。
     * @param callback 查询结果回调。
     */
    fun queryProducts(
        productIds: List<String>,
        callback: QueryProductsCallback
    ) {
        LogUtil.d("查询商品详情: ${productIds.size} 个商品")
        val provider = paymentProvider
        val config = paymentConfig
        if (!isInitialized || provider == null || config == null) {
            LogUtil.e("PaymentManager 未初始化，请先初始化")
            callback.onQueryFailure(PaymentCode.SERVICE_UNAVAILABLE)
            return
        }

        // 映射商品 ID 到类型
        val productsWithType = productIds.map { id ->
            id to config.getProductType(id)
        }

        provider.queryProducts(productsWithType, object : QueryProductsCallback {
            override fun onQuerySuccess(
                products: List<PaymentProductDetails>,
                unfetchedProductIds: List<String>
            ) {
                callback.onQuerySuccess(products, unfetchedProductIds)
            }

            override fun onQueryFailure(errorCode: PaymentCode) {
                callback.onQueryFailure(errorCode)
            }
        })
    }


    /**
     * 查询所有的商品详情。
     */
    fun queryAllProducts(callback: QueryProductsCallback) {
        val products = paymentConfig?.getAllProductIds() ?: run {
            LogUtil.e("配置中暂无商品")
            callback.onQueryFailure(PaymentCode.SERVICE_UNAVAILABLE)
            return
        }
        queryProducts(products, callback)
    }

    fun querySubsProducts(callback: QueryProductsCallback) {
        val products = paymentConfig?.subsProducts ?: run {
            LogUtil.e("配置中暂无订阅商品")
            callback.onQueryFailure(PaymentCode.SERVICE_UNAVAILABLE)
            return
        }
        queryProducts(products, callback)
    }

    fun queryInAppProducts(callback: QueryProductsCallback) {
        val products = paymentConfig?.consumableProducts ?: run {
            LogUtil.e("配置中暂无一次性商品")
            callback.onQueryFailure(PaymentCode.SERVICE_UNAVAILABLE)
            return
        }
        queryProducts(products, callback)
    }

    /**
     * 查询已购买的商品。
     *
     * @param productType 商品类型（如订阅或一次性商品）。
     * @param callback 查询结果回调。
     * @throws IllegalStateException 如果尚未初始化
     */
    fun queryPurchases(productType: PaymentProductType, callback: QueryPurchasesCallback) {
        val provider = paymentProvider
        if (!isInitialized || provider == null) {
            LogUtil.e("PaymentManager not initialized, please call setPaymentProvider first")
            callback.onQueryFailure(PaymentCode.SERVICE_UNAVAILABLE)
            return
        }

        provider.queryPurchases(productType, callback)
    }


    /**
     * 检查是否拥有指定商品的权益。
     * 对于订阅和非消耗商品，检查是否在权益列表中。
     * 对于可消耗商品，检查是否有可用库存。
     */
    fun isEntitled(productId: String): Boolean {
        val config = getPaymentConfig()
        return when {
            config.isSubs(productId) -> subsEntitlements.contains(productId)
            config.isNonConsumable(productId) -> nonConsumableEntitlements.contains(productId)
            config.isConsumable(productId) -> hasConsumableInventory(productId)
            else -> false
        }
    }

    /**
     * 检查是否有可消耗商品的库存。
     */
    private fun hasConsumableInventory(productId: String): Boolean {
        return consumableInventory.any {
            it.contains(productId)
        }
    }

    /**
     * 获取所有活跃的订阅权益。
     */
    fun getActiveSubscriptions(): Set<String> = subsEntitlements.toSet()

    /**
     * 获取所有活跃的非消耗商品权益。
     */
    fun getActiveNonConsumables(): Set<String> = nonConsumableEntitlements.toSet()


    /**
     * 检查是否拥有指定商品的权益（兼容旧版本，等同于 isEntitled）。
     */
    fun isProductPurchased(productId: String): Boolean = isEntitled(productId)


    /**
     * 处理新发现的购买订单（包括支付成功后的回调和启动时的补单）。
     * 根据商品类型分别处理：
     * - 订阅商品：激活或更新订阅权益
     * - 非消耗商品：添加永久权益
     * - 可消耗商品：添加到库存，等待消费
     */
    private fun handleIncomingPurchase(
        code: PaymentCode,
        purchaseDetailList: List<PaymentPurchaseDetails>
    ) {
        val provider = paymentProvider
        if (!isInitialized || provider == null) {
            LogUtil.e("PaymentManager not initialized, please call setPaymentProvider first")
            return
        }
        when (code) {
            PaymentCode.OK -> {
                LogUtil.d("生成订单成功=${purchaseDetailList.size}")
                purchaseDetailList.forEach { purchase ->
                    if (purchase.getPurchaseState() == PaymentPurchaseState.PURCHASED) {
                        val config = getPaymentConfig()
                        val productId = purchase.getProducts().first()
                        val isConsumable = config.isConsumable(productId)
                        val isNonConsumable = config.isNonConsumable(productId)
                        val isSubs = config.isSubs(productId)

                        when {
                            // 订阅商品
                            isSubs -> {
                                if (purchase.isAcknowledged()) {
                                    LogUtil.d("订阅商品已确认，激活权益：$productId")
                                    subsEntitlements.add(productId)
                                } else {
                                    LogUtil.d("订阅商品未确认，开始确认：$productId")
                                    provider.acknowledgePurchase(purchase.getPurchaseToken()) { isSuccess ->
                                        if (isSuccess) {
                                            LogUtil.d("订阅商品确认成功，激活权益：$productId")
                                            subsEntitlements.add(productId)
                                        } else {
                                            LogUtil.e("订阅商品确认失败：$productId")
                                        }
                                    }
                                }
                            }

                            // 非消耗商品（永久有效）
                            isNonConsumable -> {
                                if (purchase.isAcknowledged()) {
                                    LogUtil.d("非消耗商品已确认，激活永久权益：$productId")
                                    nonConsumableEntitlements.add(productId)
                                } else {
                                    LogUtil.d("非消耗商品未确认，开始确认：$productId")
                                    provider.acknowledgePurchase(purchase.getPurchaseToken()) { isSuccess ->
                                        if (isSuccess) {
                                            LogUtil.d("非消耗商品确认成功，激活永久权益：$productId")
                                            nonConsumableEntitlements.add(productId)
                                        } else {
                                            LogUtil.e("非消耗商品确认失败：$productId")
                                        }
                                    }
                                }
                            }

                            // 可消耗商品（添加到库存，等待消费）
                            isConsumable -> {
                                LogUtil.d("可消耗商品未确认，开始确认：$productId")
                                provider.acknowledgePurchase(purchase.getPurchaseToken()) { isSuccess ->
                                    if (isSuccess) {
                                        LogUtil.d("可消耗商品确认成功，添加到库存：$productId")
                                        consumableInventory.add(productId)
                                    } else {
                                        LogUtil.e("可消耗商品确认失败：$productId")
                                    }
                                }
                            }
                        }

                    } else if (purchase.getPurchaseState() == PaymentPurchaseState.PENDING) {
                        LogUtil.d("用户还没支付，提示用户")
                    } else {
                        LogUtil.d("订单状态未知，提示用户")
                    }

                    // 只清理近期订单的回调
                    synchronized(waitingPayments) {
                        waitingPayments.remove(purchase.getKey())?.onSuccess(purchase)
                    }
                }
            }

            else -> {
                LogUtil.e("生成订单失败，用户取消或者其他问题：$code")
                purchaseDetailList.forEach { purchase ->
                    synchronized(waitingPayments) {
                        waitingPayments.remove(purchase.getKey())?.onFailure(code)
                    }
                }
            }
        }
    }

    /**
     * 处理主动发起的订单（立即确认并发放权益）
     */
    private fun processActivePurchase(
        purchase: PaymentPurchaseDetails,
        provider: PaymentProvider
    ) {

    }


    /**
     * 发起支付请求。为了保证支付流程的简洁，一种类型的商品对应一个订单
     *
     * @param activity 用于支付流程的 Activity。
     * @param key 标识符
     * @param productId 商品 ID。
     * @param offerId 优惠方案 ID。
     * @param callback 支付结果回调。
     */
    fun makePayment(
        activity: Activity,
        key: String,
        productId: String,
        offerId: String,
        callback: PaymentCallback
    ) {
        val provider = paymentProvider
        val config = paymentConfig
        if (!isInitialized || provider == null || config == null) {
            LogUtil.e("PaymentManager not initialized, please call init first")
            callback.onFailure(PaymentCode.SERVICE_UNAVAILABLE)
            return
        }

        // 自动识别商品类型
        val productType = config.getProductType(productId)

        // 将回调存入待处理队列
        synchronized(waitingPayments) {
            waitingPayments[key] = callback
        }

        // 发起支付请求
        provider.makePayment(
            activity, key, productType, productId, offerId,
            object : MakePaymentCallback {
                override fun onSuccess() {
                    // 底层拉起支付流程成功（ launchBillingFlow 返回 OK ），但真正的购买结果通过 handleIncomingPurchase 异步返回
                    LogUtil.d("支付平台，拉起成功: $key")
                }

                override fun onFailure(errorCode: PaymentCode) {
                    // 底层拉起支付流程失败
                    LogUtil.e("支付平台，拉起失败: $key, error: $errorCode")
                    synchronized(waitingPayments) {
                        waitingPayments.remove(key)
                    }
                    callback.onFailure(errorCode)
                }
            })
    }

    fun removePaymentCallback(key: String) {
        synchronized(waitingPayments) {
            waitingPayments.remove(key)
        }
    }


    /**
     * 恢复未完成的订单（应用重启后调用）。
     * 查询所有未确认/未消费的订单并由 PaymentManager 自动处理确认和发放。
     *
     * @param productType 商品类型。
     * @param onOrderRecovered 业务层回调，仅用于通知哪些商品被恢复了。
     * @param onComplete 所有订单恢复完成后的回调。
     */
    fun recoverUnfinishedOrders(
        productType: PaymentProductType,
        onOrderRecovered: (products: List<String>) -> Unit = {},
        onComplete: () -> Unit = {}
    ) {
        val provider = paymentProvider
        if (!isInitialized || provider == null) {
            LogUtil.e("PaymentManager not initialized, please call init first")
            onComplete()
            return
        }

        queryPurchases(productType, object : QueryPurchasesCallback {
            override fun onQuerySuccess(purchases: List<PaymentPurchaseDetails>) {
                if (purchases.isEmpty()) {
                    onComplete()
                    return
                }

                purchases.forEach { purchase ->
                    if (purchase.getPurchaseState() == PaymentPurchaseState.PURCHASED) {
                        onOrderRecovered(purchase.getProducts())
                    }
                }
                onComplete()
            }

            override fun onQueryFailure(errorCode: PaymentCode) {
                LogUtil.e("恢复订单失败：$errorCode")
                onComplete()
            }
        })
    }

    /**
     * 清理资源，防止内存泄露。
     * 在应用退出或不再需要支付功能时调用（如 Application.onTerminate 或 MainActivity.onDestroy）。
     */
    fun cleanup() {
        // 清理缓存和状态
        subsEntitlements.clear()
        nonConsumableEntitlements.clear()
        consumableInventory.clear()

        // 清理当前支付提供者的资源
        paymentProvider?.cleanup()
        paymentProvider = null
        isInitialized = false

        LogUtil.d("PaymentManager cleaned up")
    }

    companion object {
        const val TAG = "PaymentManager"

        // 单例实例，使用 @Volatile 保证线程安全
        @Volatile
        private var INSTANCE: PaymentManager? = null

        /**
         * 获取 PaymentManager 单例实例。
         *
         * @return PaymentManager 实例。
         */
        fun getInstance(): PaymentManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: PaymentManager().also { INSTANCE = it }
            }
    }
}


























