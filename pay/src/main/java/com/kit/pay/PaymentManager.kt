package com.kit.pay


import android.app.Activity
import android.util.Log
import com.kit.pay.base.InitializationCallback
import com.kit.pay.base.PaymentCallback
import com.kit.pay.base.PaymentErrorCode
import com.kit.pay.base.PaymentProductType
import com.kit.pay.base.PaymentProvider
import com.kit.pay.base.QueryProductsCallback
import com.kit.pay.base.QueryPurchasesCallback
import com.kit.pay.base.SubscriptionStatus
import com.kit.pay.base.SubscriptionStatusCallback


/**
 * 管理支付逻辑的单例类。负责初始化支付提供者并处理支付、订阅状态查询、商品查询等操作。
 */
class PaymentManager private constructor() {

    // 当前使用的支付提供者
    private var paymentProvider: PaymentProvider? = null

    // 是否已成功初始化支付提供者
    @Volatile
    private var isInitialized = false

    // 初始化回调（可选）
    private var initializationCallback: InitializationCallback? = null

    /**
     * 设置支付提供者并触发初始化。
     *
     * @param provider 实现了 PaymentProvider 接口的支付提供者。
     * @param callback 可选的初始化结果回调，用于通知开发者初始化状态。
     */
    fun setPaymentProvider(provider: PaymentProvider, callback: InitializationCallback? = null) {
        paymentProvider = provider
        initializationCallback = callback
        initializeProvider()
    }

    /**
     * 初始化支付提供者。
     * 初始化完成后回调会通知初始化是否成功。
     */
    private fun initializeProvider() {
        isInitialized = false
        paymentProvider?.initialize(object : InitializationCallback {
            override fun onSuccess() {
                isInitialized = true
                Log.d(TAG, "Payment provider initialized successfully")
                initializationCallback?.onSuccess()
            }

            override fun onFailure(errorCode: PaymentErrorCode) {
                isInitialized = false
                Log.e(TAG, "Payment provider initialization failed: $errorCode")
                initializationCallback?.onFailure(errorCode)
            }
        })
    }

    /**
     * 检查订阅状态。
     *
     * @param callback 回调返回当前的订阅状态。
     * @throws IllegalStateException 如果尚未初始化
     */
    fun checkSubscriptionStatus(callback: (SubscriptionStatus) -> Unit) {
        val provider = paymentProvider
        if (!isInitialized || provider == null) {
            Log.w(TAG, "PaymentManager not initialized, please call setPaymentProvider first")
            callback(SubscriptionStatus.NOT_INITIALIZED)
            return
        }
        
        provider.checkSubscriptionStatus(object : SubscriptionStatusCallback {
            override fun onStatusReceived(isSubscribed: Boolean) {
                val status = if (isSubscribed) 
                    SubscriptionStatus.SUBSCRIBED 
                else 
                    SubscriptionStatus.NOT_SUBSCRIBED
                callback(status)
            }

            override fun onStatusError(errorCode: PaymentErrorCode) {
                Log.d(TAG, "checkSubscriptionStatus error=$errorCode")
                callback(SubscriptionStatus.NOT_SUBSCRIBED)
            }
        })
    }

    /**
     * 发起支付请求。
     *
     * @param activity 用于支付流程的 Activity。
     * @param productId 商品 ID。
     * @param offerId 优惠方案 ID。
     * @param callback 支付结果回调。
     * @throws IllegalStateException 如果尚未初始化
     */
    fun makePayment(
        activity: Activity,
        productId: String,
        offerId: String,
        callback: PaymentCallback
    ) {
        val provider = paymentProvider
        if (!isInitialized || provider == null) {
            Log.e(TAG, "PaymentManager not initialized, please call setPaymentProvider first")
            callback.onFailure(PaymentErrorCode.SERVICE_UNAVAILABLE)
            return
        }
        
        provider.makePayment(activity, productId, offerId, callback)
    }

    /**
     * 查询商品信息。
     *
     * @param productIds 商品 ID 列表。
     * @param callback 查询结果回调。
     * @throws IllegalStateException 如果尚未初始化
     */
    fun queryProducts(
        productIds: List<String>,
        callback: QueryProductsCallback
    ) {
        val provider = paymentProvider
        if (!isInitialized || provider == null) {
            Log.e(TAG, "PaymentManager not initialized, please call setPaymentProvider first")
            callback.onQueryFailure(PaymentErrorCode.SERVICE_UNAVAILABLE)
            return
        }
        
        provider.queryProducts(productIds, callback)
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
            Log.e(TAG, "PaymentManager not initialized, please call setPaymentProvider first")
            callback.onQueryFailure(PaymentErrorCode.SERVICE_UNAVAILABLE)
            return
        }
        
        provider.queryPurchases(productType, callback)
    }

    /**
     * 恢复未完成的订单（应用重启后调用）。
     * 查询所有未确认/未消费的订单并自动处理。
     *
     * @param productType 商品类型。
     * @param onOrderRecovered 单个订单恢复后的回调（参数：商品 ID 列表）。
     * @param onComplete 所有订单恢复完成后的回调。
     * @throws IllegalStateException 如果尚未初始化
     */
    fun recoverUnfinishedOrders(
        productType: PaymentProductType,
        onOrderRecovered: (products: List<String>) -> Unit = {},
        onComplete: () -> Unit = {}
    ) {
        val provider = paymentProvider
        if (!isInitialized || provider == null) {
            Log.e(TAG, "PaymentManager not initialized, please call setPaymentProvider first")
            onComplete()
            return
        }
        
        queryPurchases(productType, object : QueryPurchasesCallback {
            override fun onQuerySuccess(purchases: List<com.kit.pay.base.PaymentPurchaseDetails>) {
                if (purchases.isEmpty()) {
                    onComplete()
                    return
                }

                var processedCount = 0
                purchases.forEach { purchase ->
                    when (purchase.getPurchaseState()) {
                        com.kit.pay.base.PaymentPurchaseState.PENDING -> {
                            // 待支付订单，引导用户继续支付
                            Log.d(TAG, "发现待支付订单：${purchase.getOrderId()}")
                            // 这里可以通知业务层展示"等待支付确认"页面
                        }
                        com.kit.pay.base.PaymentPurchaseState.PURCHASED -> {
                            // 已支付但未确认/未消费的订单
                            if (provider is com.kit.pay.billing.GoogleBillingProvider) {
                                val config = getBillingConfig(provider)
                                when {
                                    // 订阅商品和一次性非消耗商品需要确认
                                    purchase.getProducts().any { it in config.subsProducts } ||
                                    purchase.getProducts().any { it in config.otpNonConsumerProducts } -> {
                                        if (purchase is com.kit.pay.billing.GooglePurchaseDetails && !purchase.isAcknowledged()) {
                                            Log.d(TAG, "恢复未确认订单：${purchase.getOrderId()}")
                                            provider.acknowledgePurchaseByToken(purchase.getPurchaseToken()) { success ->
                                                if (success) {
                                                    onOrderRecovered(purchase.getProducts())
                                                }
                                            }
                                        }
                                    }
                                    // 一次性消耗商品需要消费
                                    purchase.getProducts().any { it in config.otpConsumerProducts } -> {
                                        if (purchase is com.kit.pay.billing.GooglePurchaseDetails) {
                                            Log.d(TAG, "恢复未消费订单：${purchase.getOrderId()}")
                                            provider.consumePurchaseByToken(purchase.getPurchaseToken()) { success ->
                                                if (success) {
                                                    onOrderRecovered(purchase.getProducts())
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        com.kit.pay.base.PaymentPurchaseState.UNSPECIFIED_STATE -> {
                            Log.w(TAG, "未知订单状态：${purchase.getPurchaseState()}")
                        }
                    }
                    processedCount++
                }

                // 等待异步处理完成
                if (processedCount == purchases.size) {
                    onComplete()
                }
            }

            override fun onQueryFailure(errorCode: com.kit.pay.base.PaymentErrorCode) {
                Log.e(TAG, "恢复订单失败：$errorCode")
                onComplete()
            }
        })
    }

    /**
     * 通过反射获取 GoogleBillingProvider 的配置
     */
    private fun getBillingConfig(provider: com.kit.pay.billing.GoogleBillingProvider): com.kit.pay.billing.GoogleBillingConfig {
        try {
            val field = provider.javaClass.getDeclaredField("config")
            field.isAccessible = true
            return field.get(provider) as com.kit.pay.billing.GoogleBillingConfig
        } catch (e: Exception) {
            Log.e(TAG, "获取配置失败", e)
            // 返回空配置
            return com.kit.pay.billing.GoogleBillingConfig(emptyList(), emptyList(), emptyList())
        }
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


























