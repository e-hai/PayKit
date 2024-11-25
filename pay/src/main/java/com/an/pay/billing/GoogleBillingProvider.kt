package com.an.pay.billing

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.an.pay.base.InitializationCallback
import com.an.pay.base.PaymentCallback
import com.an.pay.base.PaymentErrorCode
import com.an.pay.base.PaymentProductType
import com.an.pay.base.PaymentProvider
import com.an.pay.base.PaymentPurchaseState
import com.an.pay.base.QueryProductsCallback
import com.an.pay.base.QueryPurchasesCallback
import com.an.pay.base.SubscriptionStatusCallback
import com.an.pay.billing.BillingResponse.Companion.fromBillingResponseCode
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingFlowParams.ProductDetailsParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams


class GoogleBillingProvider(private val context: Context, private val config: GoogleBillingConfig) :
    PaymentProvider {
    private lateinit var billingClient: BillingClient  // Google Billing Client实例，用于与Google Play进行通信
    private lateinit var initializationCallback: InitializationCallback  // 初始化回调，用于通知初始化结果
    private var retryCount = 0  // 重试计数器，用于处理连接失败时的重试机制
    private val maxRetryCount = 3  // 最大重试次数
    private val retryDelay = 2000L // 重试的时间间隔（单位：毫秒）
    private var paymentCallback: PaymentCallback? = null  // 支付回调，用于通知支付状态

    /**
     * 初始化支付提供者，建立与Google Play的连接。
     * 连接成功后会调用`initializationCallback.onInitialized(true)`。
     * 连接失败则会启动重试机制，最多重试3次。
     *
     * @param callback 初始化回调函数。
     */
    override fun initialize(callback: InitializationCallback) {
        initializationCallback = callback  // 将回调函数存储，以便后续通知
        billingClient = BillingClient.newBuilder(context)  // 创建BillingClient实例
            .setListener { billingResult, purchases ->
                handlePurchasesUpdated(billingResult, purchases)  // 处理购买更新事件
            }
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enablePrepaidPlans()   // 启用挂起购买的支持
                    .enableOneTimeProducts()// 启用一次性商品的支持
                    .build()
            )
            .build()

        startConnectionWithRetry()  // 开始连接Google Billing服务，并处理可能的连接重试
    }

    /**
     * 检查用户是否订阅了指定的产品。
     *
     * @param callback 回调函数，用于返回订阅状态。
     */
    override fun checkSubscriptionStatus(callback: SubscriptionStatusCallback) {
        // 异步查询用户的购买记录
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)  // 只查询订阅类型的产品
                .build()
        ) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                // 如果查询成功，遍历购买记录，检查是否存在已购买的订阅
                var isSubscribed = false
                purchases.forEach {
                    if (it.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        isSubscribed = true  // 找到有效的订阅
                    }
                }
                callback.onStatusReceived(isSubscribed)  // 回调返回订阅状态
            } else {
                callback.onStatusError(fromBillingResponseCode(billingResult.responseCode))  // 如果查询失败，返回错误状态
            }
        }
    }

    /**
     * 启动与Google Billing的连接。如果连接失败，则根据重试机制进行重试。
     */
    private fun startConnectionWithRetry() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    retryCount = 0  // 连接成功后重置重试计数器
                    initializationCallback.onSuccess()  // 通知初始化成功
                    Log.w(TAG, "Initialization Success")
                } else {
                    handleInitializationFailure()  // 如果连接失败，则进行重试
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Initialization Disconnected")
                handleInitializationFailure()  // 如果连接断开，则进行重试
            }
        })
    }

    /**
     * 如果初始化连接失败，则会尝试进行重试。最多重试3次，每次重试的间隔为2秒。
     *
     * 在最大重试次数内，每次重试会延迟2秒。如果重试超过最大次数仍未成功，则回调初始化失败。
     */
    private fun handleInitializationFailure() {
        if (retryCount < maxRetryCount) {
            retryCount++  // 增加重试计数
            Log.w(TAG, "Initialization failed. Retrying... ($retryCount/$maxRetryCount)")
            // 延迟2秒后重试
            Handler(Looper.getMainLooper()).postDelayed(
                { startConnectionWithRetry() }, retryDelay
            )
        } else {
            initializationCallback.onFailure(PaymentErrorCode.SERVICE_DISCONNECTED)  // 如果超过最大重试次数，回调初始化失败
        }
    }

    /**
     * 查询多个商品的详细信息。
     *
     * @param productIds 商品ID列表。
     * @param callback 查询回调，返回查询结果。
     */
    override fun queryProducts(
        productIds: List<String>,
        callback: QueryProductsCallback
    ) {
        // 构造查询参数列表，每个商品需要指定其类型（订阅或一次性购买）
        val productList = productIds.map { product ->
            val billingProductType = if (productIsSubs(product)) {
                BillingClient.ProductType.SUBS  // 如果是订阅商品，则设置为SUBS类型
            } else {
                BillingClient.ProductType.INAPP  // 否则设置为INAPP类型
            }
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(product)
                .setProductType(billingProductType)
                .build()
        }

        // 创建查询商品的请求参数
        val productDetailsParams = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        // 发起商品查询请求
        billingClient.queryProductDetailsAsync(productDetailsParams) { billingResult, productDetailsList ->
            val response = BillingResponse(billingResult.responseCode)
            if (response.isOk && productDetailsList.isNotEmpty()) {
                // 查询成功，返回商品详情
                val paymentProductDetailsList = productDetailsList.map {
                    logProductDetails(it)  // 打印商品详情到日志
                    val paymentProductType = if (it.productType == BillingClient.ProductType.SUBS) {
                        PaymentProductType.SUBS  // 如果是订阅商品，使用SUBS类型
                    } else {
                        PaymentProductType.INAPP  // 否则使用INAPP类型
                    }
                    GoogleProductDetails(
                        it.productId,
                        it.title,
                        it.description,
                        paymentProductType
                    )
                }
                callback.onQuerySuccess(paymentProductDetailsList)  // 返回查询成功的结果
            } else {
                Log.e(
                    TAG,
                    "onProductDetailsResponse: ${response.code} ${billingResult.debugMessage}"
                )
                callback.onQueryFailure(fromBillingResponseCode(billingResult.responseCode))  // 查询失败，返回失败状态
            }
        }
    }

    /**
     * 打印商品的详细信息到日志中，帮助开发者调试。
     *
     * @param productDetails 商品详情对象。
     */
    private fun logProductDetails(productDetails: ProductDetails?) {
        productDetails ?: return
        Log.d(
            TAG,
            "商品- ID:${productDetails.productId} " +
                    "类型:${productDetails.productType} " +
                    "名称:${productDetails.name} " +
                    "标题:${productDetails.title} " +
                    "简介:${productDetails.description}"
        )
        productDetails.subscriptionOfferDetails?.forEach { sub ->
            Log.d(
                TAG,
                "订阅商品- 基础方案ID:${sub.basePlanId} " +
                        "优惠ID:${sub.offerId} " +
                        "优惠token:${sub.offerToken}"
            )
            sub.offerTags.forEach { tag ->
                Log.d(TAG, "优惠标签- $tag")
            }
            //关于有效期单位（billingPeriod），遵循 ISO 8601格式： P1W 代表一周, P1M 代表一个月, P3M 代表三个月, P6M 代表6个月,  P1Y 代表一年.
            //例如，对于FormattedPrice$6.99和billingPeriod P1M，如果billingCycleCount为2，则用户将收取6.99美元/月的费用，为期2个月。
            sub.pricingPhases.pricingPhaseList.forEach { pricing ->
                Log.d(
                    TAG,
                    "定价阶段- 价格:${pricing.priceAmountMicros} " +
                            "货币代号:${pricing.priceCurrencyCode} " +
                            "格式化价格(货币符号+价格):${pricing.formattedPrice} " +
                            "有效期数：${pricing.billingCycleCount} 有效期单位：${pricing.billingPeriod}" +
                            "计费的循环模式：${pricing.recurrenceMode}"
                )
            }
        }
        productDetails.oneTimePurchaseOfferDetails?.apply {
            Log.d(
                TAG,
                "一次性商品- 价格:${priceAmountMicros} " +
                        "货币代号:${priceCurrencyCode} " +
                        "格式化价格(货币符号+价格):${formattedPrice}"
            )
        }
    }


    /**
     * 发起支付流程，包括一次性购买和订阅购买。
     *
     * @param activity 当前活动的Activity对象，用于发起支付。
     * @param productId 产品ID。
     * @param offerId 订阅优惠ID。
     * @param callback 支付回调，用于返回支付结果。
     */
    override fun makePayment(
        activity: Activity,
        productId: String,
        offerId: String,
        callback: PaymentCallback
    ) {
        this.paymentCallback = callback
        val billingProductType = if (productIsSubs(productId)) {
            BillingClient.ProductType.SUBS
        } else {
            BillingClient.ProductType.INAPP
        }
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(billingProductType)
                .build()
        )
        val productDetailsParams = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()
        billingClient.queryProductDetailsAsync(productDetailsParams) { billingResult, productDetailsList ->
            val response = BillingResponse(billingResult.responseCode)
            when {
                response.isOk -> {
                    val productDetailsParamsList = productDetailsList.map { productDetails ->
                        if (productDetails.productType == BillingClient.ProductType.SUBS) {
                            val offerToken = productDetails.subscriptionOfferDetails
                                ?.find { it.offerId == offerId }
                                ?.offerToken
                                ?: ""
                            ProductDetailsParams.newBuilder()
                                .setProductDetails(productDetails)
                                .setOfferToken(offerToken)
                                .build()
                        } else {
                            ProductDetailsParams.newBuilder()
                                .setProductDetails(productDetails)
                                .build()
                        }
                    }

                    val billingFlowParams = BillingFlowParams.newBuilder()
                        .setProductDetailsParamsList(productDetailsParamsList)
                        .setIsOfferPersonalized(true)  //欧盟政策
                        .build()
                    val responseCode = billingClient.launchBillingFlow(
                        (context as Activity),
                        billingFlowParams
                    ).responseCode
                    Log.d(TAG, "launchBillingFlow: BillingResponse $responseCode")
                    if (responseCode != BillingClient.BillingResponseCode.OK) {
                        paymentCallback?.onFailure(fromBillingResponseCode(billingResult.responseCode))
                    }
                }

                else -> {
                    paymentCallback?.onFailure(fromBillingResponseCode(billingResult.responseCode))
                }
            }
        }
    }

    /**
     * 处理支付结果更新事件，确认支付状态，并根据支付状态调用相应的回调。
     *
     * @param billingResult 支付结果。
     * @param purchases 购买的商品列表。
     */
    private fun handlePurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                handlePurchase(purchase) {
                    if (null == it) {
                        paymentCallback?.onFailure(PaymentErrorCode.ERROR)
                    } else {
                        paymentCallback?.onSuccess()
                        paymentCallback = null
                    }
                }
            }
        } else {
            paymentCallback?.onFailure(fromBillingResponseCode(billingResult.responseCode))
        }
    }

    override fun queryPurchases(
        paymentProductType: PaymentProductType,
        callback: QueryPurchasesCallback
    ) {
        val productType = if (paymentProductType == PaymentProductType.SUBS) {
            BillingClient.ProductType.SUBS
        } else {
            BillingClient.ProductType.INAPP
        }
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(productType)
                .build()
        ) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                for (purchase in purchases) {
                    handlePurchase(purchase) {
                    }
                }
                val paymentPurchaseDetailsList = purchases.filterNotNull().map {
                    val paymentPurchaseState = when (it.purchaseState) {
                        Purchase.PurchaseState.PURCHASED -> PaymentPurchaseState.PURCHASED
                        Purchase.PurchaseState.PENDING -> PaymentPurchaseState.PENDING
                        else -> PaymentPurchaseState.UNSPECIFIED_STATE
                    }
                    GooglePurchaseDetails(it.orderId ?: "", paymentPurchaseState, it.products)
                }
                callback.onQuerySuccess(paymentPurchaseDetailsList)
            } else {
                callback.onQueryFailure(fromBillingResponseCode(billingResult.responseCode))
            }
        }
    }

    /**
     * 处理购买状态，确认支付状态并执行相关操作，如确认购买、恢复订阅等。
     *
     * @param purchase 购买对象。
     * @param callback 回调函数，用于返回支付成功或失败的状态。
     */
    private fun handlePurchase(
        purchase: Purchase,
        callback: (response: BillingResponse?) -> Unit
    ) {
        // 实现购买结果处理逻辑，例如验证购买，提供相应商品等
        if (purchaseIsSubs(purchase)) {
            acknowledgePurchase(purchase, callback)
        } else if (purchaseIsOtpNonConsumable(purchase)) {
            acknowledgePurchase(purchase, callback)
        } else if (purchaseIsOtpConsumable(purchase)) {
            consumablePurchase(purchase, callback)
        }
    }

    private fun consumablePurchase(
        purchase: Purchase,
        callback: (response: BillingResponse?) -> Unit
    ) {
        val consumeParams = ConsumeParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.consumeAsync(consumeParams) { billingResult, purchaseToken ->
            val response = BillingResponse(billingResult.responseCode)
            when {
                response.isOk -> {
                    Log.d(TAG, "consumeAsync: 成功消耗该商品 - token: $purchaseToken")
                    callback.invoke(response)
                }

                else -> {
                    callback.invoke(null)
                }
            }
        }
    }


    private fun acknowledgePurchase(
        purchase: Purchase,
        callback: (response: BillingResponse?) -> Unit
    ) {
        val purchaseToken = purchase.purchaseToken
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchaseToken)
            .build()
        billingClient.acknowledgePurchase(params) { billingResult ->
            val response = BillingResponse(billingResult.responseCode)
            when {
                response.isOk -> {
                    Log.i(TAG, "acknowledgePurchase：成功验证该商品 - token: $purchaseToken")
                    callback.invoke(response)

                }

                else -> {
                    callback.invoke(null)
                }
            }
        }
    }


    /**
     * 判断商品是否为订阅类型。
     *
     * @param product 商品ID。
     * @return 如果是订阅类型，返回true；否则返回false。
     */
    private fun productIsSubs(product: String): Boolean {
        return config.subsProducts.contains(product)
    }

    private fun productIsOtpConsumer(product: String): Boolean {
        return config.otpConsumerProducts.contains(product)
    }

    private fun productIsOtpNonConsumer(product: String): Boolean {
        return config.otpNonConsumerProducts.contains(product)
    }

    /**
     * 判断该订单是否为订阅
     * **/
    private fun purchaseIsSubs(purchase: Purchase): Boolean {
        return purchase.products.any { product ->
            product in config.subsProducts
        }
    }

    /**
     * 判断该订单是否为一次性商品-消耗型
     * **/
    private fun purchaseIsOtpConsumable(purchase: Purchase): Boolean {
        return purchase.products.any { product ->
            product in config.otpConsumerProducts
        }
    }

    /**
     * 判断该订单是否为一次性商品-非消耗型
     * **/
    private fun purchaseIsOtpNonConsumable(purchase: Purchase): Boolean {
        return purchase.products.any { product ->
            product in config.otpNonConsumerProducts
        }
    }

    companion object {
        private const val TAG = "GoogleBillingProvider"
    }
}

/**
 * 把结算库返回的结果，根据code归纳成几种状态
 * **/
internal class BillingResponse(val code: Int) {

    // 判断响应码是否表示操作成功
    val isOk: Boolean
        get() = code == BillingClient.BillingResponseCode.OK  // 如果返回的响应码是 OK (0)，表示操作成功。

    // 判断响应码是否表示可以优雅失败的情况（例如用户已购买该商品）
    val canFailGracefully: Boolean
        get() = code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED  // 如果返回的是 ITEM_ALREADY_OWNED (7)，表示用户已经拥有该商品，这并不算作错误，系统可以优雅地处理这种情况。

    // 判断响应码是否表示可恢复的错误（例如服务断开或一般错误）
    val isRecoverableError: Boolean
        get() = code in setOf(
            BillingClient.BillingResponseCode.ERROR,  // 一般错误（1），可能是由于未知原因导致的错误。
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED  // 服务断开（2），表示与计费服务的连接断开，通常可以重试。
        )

    // 判断响应码是否表示不可恢复的错误（例如服务不可用、开发者错误）
    val isNonrecoverableError: Boolean
        get() = code in setOf(
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,  // 服务不可用（3），表示计费服务不可用，通常是临时性问题。
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,  // 计费不可用（4），表示计费系统不可用。
            BillingClient.BillingResponseCode.DEVELOPER_ERROR  // 开发者错误（5），通常是开发者配置错误，需修复代码。
        )

    // 判断响应码是否表示严重的故障，通常是用户行为导致的失败或商品问题
    val isTerribleFailure: Boolean
        get() = code in setOf(
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE,  // 商品不可用（6），表示商品已下架或不可购买。
            BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED,  // 功能不支持（8），表示当前设备或地区不支持该功能。
            BillingClient.BillingResponseCode.ITEM_NOT_OWNED,  // 商品未拥有（9），表示用户没有购买该商品。
            BillingClient.BillingResponseCode.USER_CANCELED  // 用户取消（10），表示用户手动取消了支付或购买操作。
        )

    companion object {
        // 根据BillingClient的响应码返回对应的PaymentErrorCode
        fun fromBillingResponseCode(code: Int): PaymentErrorCode {
            return when (code) {
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> PaymentErrorCode.ITEM_ALREADY_OWNED
                BillingClient.BillingResponseCode.ERROR -> PaymentErrorCode.ERROR
                BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> PaymentErrorCode.SERVICE_DISCONNECTED
                BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> PaymentErrorCode.SERVICE_UNAVAILABLE
                BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> PaymentErrorCode.BILLING_UNAVAILABLE
                BillingClient.BillingResponseCode.DEVELOPER_ERROR -> PaymentErrorCode.DEVELOPER_ERROR
                BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> PaymentErrorCode.ITEM_UNAVAILABLE
                BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED -> PaymentErrorCode.FEATURE_NOT_SUPPORTED
                BillingClient.BillingResponseCode.ITEM_NOT_OWNED -> PaymentErrorCode.ITEM_NOT_OWNED
                BillingClient.BillingResponseCode.USER_CANCELED -> PaymentErrorCode.USER_CANCELED
                else -> PaymentErrorCode.ERROR
            }
        }
    }
}




