package com.an.pay.billing

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.an.pay.InitializationCallback
import com.an.pay.PaymentCallback
import com.an.pay.PaymentErrorCode
import com.an.pay.PaymentProductType
import com.an.pay.PaymentProvider
import com.an.pay.PaymentPurchaseDetails
import com.an.pay.PaymentPurchaseState
import com.an.pay.QueryProductsCallback
import com.an.pay.QueryPurchasesCallback
import com.an.pay.SubscriptionStatusCallback
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class GoogleBillingProvider(private val context: Context, private val config: GoogleBillingConfig) :
    PaymentProvider {
    private lateinit var billingClient: BillingClient
    private lateinit var initializationCallback: InitializationCallback
    private var retryCount = 0
    private val maxRetryCount = 3
    private val retryDelay = 2000L // 重试间隔 2 秒
    private var paymentCallback: PaymentCallback? = null


    override fun initialize(callback: InitializationCallback) {
        initializationCallback = callback
        billingClient = BillingClient.newBuilder(context)
            .setListener { billingResult, purchases ->
                handlePurchasesUpdated(billingResult, purchases)
            }
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().build())
            .build()

        startConnectionWithRetry()
    }

    override fun checkSubscriptionStatus(callback: SubscriptionStatusCallback) {
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        ) { billingResult, purchases ->
            Purchase.PurchaseState.PURCHASED
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                var isSubscribed = false
                purchases.forEach {
                    if (it.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        isSubscribed = true
                    }
                }
                callback.onStatusReceived(isSubscribed)
            } else {
                callback.onStatusError(PaymentErrorCode.OTHER)
            }
        }
    }


    private fun startConnectionWithRetry() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    retryCount = 0
                    initializationCallback.onInitialized(true)
                } else {
                    handleInitializationFailure()
                }
            }

            override fun onBillingServiceDisconnected() {
                handleInitializationFailure()
            }
        })
    }

    private fun handleInitializationFailure() {
        if (retryCount < maxRetryCount) {
            // 记录日志或者在UI上显示重试提示
            retryCount++
            Log.w(TAG, "Initialization failed. Retrying... ($retryCount/$maxRetryCount)")
            Handler(Looper.getMainLooper()).postDelayed(
                {
                    startConnectionWithRetry()
                }, retryDelay
            )
        } else {
            // 通知用户初始化失败
            initializationCallback.onInitialized(false)
        }
    }


    override fun queryProducts(
        productIds: List<String>,
        callback: QueryProductsCallback
    ) {
        val productList = productIds.map { product ->
            val billingProductType = if (productIsSubs(product)) {
                BillingClient.ProductType.SUBS
            } else {
                BillingClient.ProductType.INAPP
            }
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(product)
                .setProductType(billingProductType)
                .build()
        }

        val productDetailsParams = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()
        billingClient.queryProductDetailsAsync(productDetailsParams) { billingResult, productDetailsList ->
            val response = BillingResponse(billingResult.responseCode)
            val debugMessage = billingResult.debugMessage
            if (response.isOk && productDetailsList.isNotEmpty()) {
                val paymentProductDetailsList = productDetailsList.map {
                    logProductDetails(it)

                    val paymentProductType =
                        if (it.productType == BillingClient.ProductType.SUBS) {
                            PaymentProductType.SUBS
                        } else {
                            PaymentProductType.INAPP
                        }
                    GoogleProductDetails(
                        it.productId,
                        it.title,
                        it.description,
                        paymentProductType
                    )
                }
                callback.onQuerySuccess(paymentProductDetailsList)
            } else {
                Log.e(
                    TAG,
                    "onProductDetailsResponse: ${response.code} $debugMessage"
                )
                callback.onQueryFailure()
            }
        }
    }


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
                        paymentCallback?.onFailure(PaymentErrorCode.UNABLE_LAUNCH)
                    }
                }

                else -> {
                    paymentCallback?.onFailure(PaymentErrorCode.OTHER)
                }
            }
        }
    }

    private fun handlePurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                handlePurchase(purchase) {
                    if (null == it) {
                        paymentCallback?.onFailure(PaymentErrorCode.OTHER)
                    } else {
                        paymentCallback?.onSuccess()
                        paymentCallback = null
                    }
                }
            }
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            paymentCallback?.onFailure(PaymentErrorCode.USER_CANCELED)
        } else {
            paymentCallback?.onFailure(PaymentErrorCode.OTHER)
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
            Purchase.PurchaseState.PURCHASED
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
                callback.onQueryFailure()
            }
        }
    }

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
    val isOk: Boolean
        get() = code == BillingClient.BillingResponseCode.OK
    val canFailGracefully: Boolean
        get() = code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED
    val isRecoverableError: Boolean
        get() = code in setOf(
            BillingClient.BillingResponseCode.ERROR,
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
        )
    val isNonrecoverableError: Boolean
        get() = code in setOf(
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
            BillingClient.BillingResponseCode.DEVELOPER_ERROR,
        )
    val isTerribleFailure: Boolean
        get() = code in setOf(
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE,
            BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED,
            BillingClient.BillingResponseCode.ITEM_NOT_OWNED,
            BillingClient.BillingResponseCode.USER_CANCELED,
        )
}



