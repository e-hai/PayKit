package com.an.pay.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.an.pay.Constants
import com.an.pay.PayManager
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.*
import kotlin.math.pow

/**
 * 订阅商品简称：subs
 * 一次性商品简称：otp
 * **/
internal class BillingDataStorage private constructor(
    applicationContext: Context,
    private val subsProductIds: List<String>,
    private val otpProductIds: List<String>,
    private val externalScope: CoroutineScope
) : PurchasesUpdatedListener, BillingClientStateListener {


    /**
     * 新增订单监听
     * **/
    private val newPurchasesListeners = mutableListOf<PayManager.PurchasesListener>()

    fun addNewPurchasesListener(listener: PayManager.PurchasesListener) {
        newPurchasesListeners.add(listener)
    }

    fun removeNewPurchasesListener(listener: PayManager.PurchasesListener) {
        newPurchasesListeners.remove(listener)
    }

    /**
     * 是否是订阅用户
     * **/
    @Volatile
    var subscriber = Subscriber.LOADING

    private fun updateSubscriber() {
        subsPurchases.forEach {
            if (it.purchaseState == Purchase.PurchaseState.PURCHASED) {
                subscriber = Subscriber.YES
                return
            }
        }
        subscriber = Subscriber.NO
    }

    /**
     * Purchases are collectable. This list will be updated when the Billing Library
     * detects new or existing purchases.
     */
    private val subsPurchases: MutableList<Purchase> = mutableListOf()

    private val otpPurchases: MutableList<Purchase> = mutableListOf()


    /**
     * Instantiate a new BillingClient instance.
     */
    private val billingClient: BillingClient

    init {
        // Create a new BillingClient in onCreate().
        // Since the BillingClient can only be used once, we need to create a new instance
        // after ending the previous connection to the Google Play Store in onDestroy().
        billingClient = BillingClient.newBuilder(applicationContext)
            .setListener(this)
            .enablePendingPurchases() // Not used for subscriptions.
            .build()
        Log.d(TAG, "ON_CREATE: ${billingClient.isReady}")

        if (!billingClient.isReady) {
            Log.d(TAG, "BillingClient: Start connection...")
            billingClient.startConnection(this)
        }
    }


    override fun onBillingSetupFinished(billingResult: BillingResult) {
        val responseCode = billingResult.responseCode
        val debugMessage = billingResult.debugMessage
        Log.d(TAG, "onBillingSetupFinished: $responseCode $debugMessage")
        if (responseCode == BillingClient.BillingResponseCode.OK) {
            // The billing client is ready.
            // You can query product details and purchases here.
            querySubsPurchases()
            queryOtpPurchases()
        }
    }

    override fun onBillingServiceDisconnected() {
        Log.d(TAG, "onBillingServiceDisconnected")
        // TODO: Try connecting again with exponential backoff.
        billingClient.startConnection(this)
    }

    /**
     * 查询订阅商品列表
     */
    fun querySubscriptionProductDetails(listener: PayManager.ProductListener? = null) {
        Log.d(TAG, "querySubscriptionProductDetails")
        val params = QueryProductDetailsParams.newBuilder()
        val productList: MutableList<QueryProductDetailsParams.Product> = arrayListOf()
        for (product in subsProductIds) {
            productList.add(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(product)
                    .setProductType(BillingClient.ProductType.SUBS)
                    .build()
            )
        }

        val productDetailsParams = params.setProductList(productList)
        billingClient.queryProductDetailsAsync(
            productDetailsParams.build()
        ) { billingResult, productDetailsList ->
            val response = BillingResponse(billingResult.responseCode)
            val debugMessage = billingResult.debugMessage
            when {
                response.isOk -> {
                    val expectedProductDetailsCount = subsProductIds.size
                    if (productDetailsList.isEmpty()) {
                        Log.e(
                            TAG, "processProductDetails: " +
                                    "Expected ${expectedProductDetailsCount}, " +
                                    "Found null ProductDetails. " +
                                    "Check to see if the products you requested are correctly published " +
                                    "in the Google Play Console."
                        )
                    } else {
                        productDetailsList.forEach {
                            Log.d(
                                TAG,
                                "商品- ID:${it.productId} 类型:${it.productType} 名称:${it.name} 标题:${it.title} 简介:${it.description}"
                            )
                            it.subscriptionOfferDetails?.forEach { sub ->
                                Log.d(
                                    TAG,
                                    "订阅商品- 基础方案ID:${sub.basePlanId} 优惠ID:${sub.offerId} 优惠token:${sub.offerToken}"
                                )
                                sub.offerTags.forEach { tag ->
                                    Log.d(
                                        TAG,
                                        "优惠标签- $tag"
                                    )
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
                        }
                    }
                    listener?.onSuccess(productDetailsList)
                }
                else -> {
                    Log.e(TAG, "onProductDetailsResponse: ${response.code} $debugMessage")
                    listener?.onFail()
                }
            }
        }
    }

    /**
     * 查询一次性购买商品列表
     */
    fun queryOneTimeProductDetails(listener: PayManager.ProductListener? = null) {
        Log.d(TAG, "queryOneTimeProductDetails")
        val params = QueryProductDetailsParams.newBuilder()
        val productList = otpProductIds.map { product ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(product)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        }
        val productDetailsParams = params.setProductList(productList)
        billingClient.queryProductDetailsAsync(
            productDetailsParams.build()
        ) { billingResult, productDetailsList ->
            val response = BillingResponse(billingResult.responseCode)
            val debugMessage = billingResult.debugMessage
            when {
                response.isOk -> {
                    val expectedProductDetailsCount = otpProductIds.size
                    if (productDetailsList.isEmpty()) {
                        Log.e(
                            TAG, "processProductDetails: " +
                                    "Expected ${expectedProductDetailsCount}, " +
                                    "Found null ProductDetails. " +
                                    "Check to see if the products you requested are correctly published " +
                                    "in the Google Play Console."
                        )
                    } else {
                        productDetailsList.forEach {
                            Log.d(
                                TAG,
                                "商品- ID:${it.productId} 类型:${it.productType} 名称:${it.name} 标题:${it.title} 简介:${it.description}"
                            )
                            it.oneTimePurchaseOfferDetails?.apply {
                                Log.d(
                                    TAG,
                                    "一次性商品- 价格:${priceAmountMicros} 货币代号:${priceCurrencyCode} 格式化价格(货币符号+价格):${formattedPrice}"
                                )
                            }
                        }
                    }
                    listener?.onSuccess(productDetailsList)
                }
                else -> {
                    Log.e(TAG, "onProductDetailsResponse: ${response.code} $debugMessage")
                    listener?.onFail()
                }
            }
        }
    }


    /**
     * 查询订阅的订单列表
     */
    fun querySubsPurchases(listener: PayManager.PurchasesListener? = null) {
        if (subsPurchases.isNotEmpty()) {
            listener?.onSuccess(subsPurchases)
            return
        }
        if (!billingClient.isReady) {
            Log.e(TAG, "querySubsPurchases: BillingClient is not ready")
            billingClient.startConnection(this)
        }
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        ) { _, purchasesList ->
            externalScope.launch {
                processPurchases(purchasesList)
                withContext(Dispatchers.Main) {
                    listener?.onSuccess(subsPurchases)
                }
            }
        }
    }

    /**
     * 查询一次性的订单列表
     */
    fun queryOtpPurchases(listener: PayManager.PurchasesListener? = null) {
        if (otpPurchases.isNotEmpty()) {
            listener?.onSuccess(otpPurchases)
            return
        }
        if (!billingClient.isReady) {
            Log.e(TAG, "queryOtpPurchases: BillingClient is not ready")
            billingClient.startConnection(this)
        }
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        ) { _, purchasesList ->
            externalScope.launch {
                processPurchases(purchasesList)
                withContext(Dispatchers.Main) {
                    listener?.onSuccess(otpPurchases)
                }
            }
        }
    }


    /**
     * Called by the Billing Library when new purchases are detected.
     */
    override fun onPurchasesUpdated(
        billingResult: BillingResult,
        purchases: MutableList<Purchase>?
    ) {
        val response = BillingResponse(billingResult.responseCode)
        val debugMessage = billingResult.debugMessage
        Log.d(TAG, "onPurchasesUpdated: $response $debugMessage")
        when {
            response.isOk -> {
                if (purchases == null) {
                    Log.d(TAG, "onPurchasesUpdated: null purchase list")
                    newPurchasesListeners.forEach { it.onFail() }
                } else {
                    processPurchases(purchases)
                    newPurchasesListeners.forEach { it.onSuccess(purchases) }
                }
            }
            else -> {
                newPurchasesListeners.forEach { it.onFail() }
            }
        }
    }

    /**
     * Send purchase to StateFlow, which will trigger network call to verify the subscriptions
     * on the sever.
     */
    private fun processPurchases(purchasesList: List<Purchase>) {
        Log.d(TAG, "processPurchases: ${purchasesList.size} purchase(s)")
        //过滤订阅商品的订单
        purchasesList.filter { purchase ->
            purchase.products.any { product ->
                product in subsProductIds
            }
        }.forEach {
            if (!subsPurchases.contains(it)) {
                subsPurchases.add(it)
            }
        }.apply {
            updateSubscriber()
        }

        //过滤一次性商品的订单
        purchasesList.filter { purchase ->
            purchase.products.any { product ->
                product in otpProductIds
            }
        }.forEach {
            if (!otpPurchases.contains(it)) {
                otpPurchases.add(it)
            }
        }
        logAcknowledgementStatus(purchasesList)
    }


    /**
     * Log the number of purchases that are acknowledge and not acknowledged.
     *
     * https://developer.android.com/google/play/billing/billing_library_releases_notes#2_0_acknowledge
     *
     * When the purchase is first received, it will not be acknowledge.
     * This application sends the purchase token to the server for registration. After the
     * purchase token is registered to an account, the Android app acknowledges the purchase token.
     * The next time the purchase list is updated, it will contain acknowledged purchases.
     */
    private fun logAcknowledgementStatus(purchasesList: List<Purchase>) {
        var acknowledgedCounter = 0
        var unacknowledgedCounter = 0
        for (purchase in purchasesList) {
            if (purchase.isAcknowledged) {
                acknowledgedCounter++
            } else {
                unacknowledgedCounter++
                acknowledgePurchase(purchase.purchaseToken)
            }
        }
        Log.d(
            TAG,
            "logAcknowledgementStatus: acknowledged=$acknowledgedCounter " +
                    "unacknowledged=$unacknowledgedCounter"
        )
    }

    /**
     * Acknowledge a purchase.
     *
     * https://developer.android.com/google/play/billing/billing_library_releases_notes#2_0_acknowledge
     *
     * Apps should acknowledge the purchase after confirming that the purchase token
     * has been associated with a user. This app only acknowledges purchases after
     * successfully receiving the subscription data back from the server.
     *
     * Developers can choose to acknowledge purchases from a server using the
     * Google Play Developer API. The server has direct access to the user database,
     * so using the Google Play Developer API for acknowledgement might be more reliable.
     * TODO(134506821): Acknowledge purchases on the server.
     * TODO: Remove client side purchase acknowledgement after removing the associated tests.
     * If the purchase token is not acknowledged within 3 days,
     * then Google Play will automatically refund and revoke the purchase.
     * This behavior helps ensure that users are not charged for subscriptions unless the
     * user has successfully received access to the content.
     * This eliminates a category of issues where users complain to developers
     * that they paid for something that the app is not giving to them.
     */
    private fun acknowledgePurchase(purchaseToken: String) {
        externalScope.launch {
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchaseToken)
                .build()

            for (trial in 1..MAX_RETRY_ATTEMPT) {
                var response = BillingResponse(500)
                var bResult: BillingResult? = null
                billingClient.acknowledgePurchase(params) { billingResult ->
                    response = BillingResponse(billingResult.responseCode)
                    bResult = billingResult
                }

                when {
                    response.isOk -> {
                        Log.i(TAG, "Acknowledge success - token: $purchaseToken")
                    }
                    response.canFailGracefully -> {
                        // Ignore the error
                        Log.i(TAG, "Token $purchaseToken is already owned.")
                    }
                    response.isRecoverableError -> {
                        // Retry to ack because these errors may be recoverable.
                        val duration = 500L * 2.0.pow(trial).toLong()
                        delay(duration)
                        if (trial < MAX_RETRY_ATTEMPT) {
                            Log.w(
                                TAG,
                                "Retrying($trial) to acknowledge for token $purchaseToken - " +
                                        "code: ${bResult!!.responseCode}, message: " +
                                        bResult!!.debugMessage
                            )
                        }
                    }
                    response.isNonrecoverableError || response.isTerribleFailure -> {
                        Log.e(
                            TAG,
                            "Failed to acknowledge for token $purchaseToken - " +
                                    "code: ${bResult?.responseCode}, message: " +
                                    bResult?.debugMessage
                        )
                    }
                }
            }
            Log.e(TAG, "Failed to acknowledge the purchase!")
        }
    }


    /**
     * Launching the billing flow.
     *
     * Launching the UI to make a purchase requires a reference to the Activity.
     */
    fun launchBillingFlow(activity: Activity, params: BillingFlowParams): Int {
        if (!billingClient.isReady) {
            Log.e(TAG, "launchBillingFlow: BillingClient is not ready")
        }
        val billingResult = billingClient.launchBillingFlow(activity, params)
        val responseCode = billingResult.responseCode
        val debugMessage = billingResult.debugMessage
        Log.d(TAG, "launchBillingFlow: BillingResponse $responseCode $debugMessage")
        return responseCode
    }


    companion object {
        private const val TAG = "BillingLifecycle"
        private const val MAX_RETRY_ATTEMPT = 3


        @Volatile
        private var INSTANCE: BillingDataStorage? = null

        fun getInstance(
            applicationContext: Context,
            subsProductIds: List<String>,
            otpProductIds: List<String>,
            externalScope: CoroutineScope
        ): BillingDataStorage =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: BillingDataStorage(
                    applicationContext,
                    subsProductIds,
                    otpProductIds,
                    externalScope
                ).also { INSTANCE = it }
            }
    }
}

@JvmInline
private value class BillingResponse(val code: Int) {
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

enum class Subscriber {
    LOADING,
    YES,
    NO
}

