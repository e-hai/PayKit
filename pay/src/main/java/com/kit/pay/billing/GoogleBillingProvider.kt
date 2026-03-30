package com.kit.pay.billing

import android.app.Activity
import android.app.Application
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
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
import com.android.billingclient.api.UnfetchedProduct
import com.kit.pay.base.InitializationCallback
import com.kit.pay.base.MakePaymentCallback
import com.kit.pay.base.PaymentCallback
import com.kit.pay.base.PaymentCode
import com.kit.pay.base.PaymentProductType
import com.kit.pay.base.PaymentProvider
import com.kit.pay.base.PaymentPurchaseState
import com.kit.pay.base.PurchaseCallback
import com.kit.pay.base.QueryProductsCallback
import com.kit.pay.base.QueryPurchasesCallback
import com.kit.pay.billing.BillingResponse.Companion.fromBillingResponseCode
import com.kit.pay.utils.LogUtil

/**
 * 将 PaymentProductType 转换为 BillingClient.ProductType
 */
private fun PaymentProductType.toBillingProductType(): String {
    return when (this) {
        PaymentProductType.SUBS -> BillingClient.ProductType.SUBS
        PaymentProductType.INAPP -> BillingClient.ProductType.INAPP
    }
}

class GoogleBillingProvider(
    private val app: Application
) : PaymentProvider {
    private lateinit var billingClient: BillingClient
    private var purchaseListener: PurchaseCallback? = null


    /**
     * 初始化支付提供者，建立与 Google Play 的连接。
     * 连接成功后会调用`initializationCallback.onInitialized(true)`。
     * 连接失败则会启动重试机制，最多重试 3 次。
     *
     * @param callback 初始化回调函数。
     */
    override fun initialize(callback: InitializationCallback) {
        // 使用 Application Context 创建 BillingClient，避免泄露
        billingClient = BillingClient.newBuilder(app)
            .setListener { billingResult, purchases ->
                handlePurchasesUpdated(billingResult, purchases)
            }
            .enablePendingPurchases(    //启用对“待处理购买交易”的支持，即用户未完成支付的订单
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts() // 必须：启用一次性商品支持
                    .enablePrepaidPlans()    // 可选：启用预付费方案支持，如果应用内有预付费型订阅（非自动续订），可开启
                    .build()
            )
            .enableAutoServiceReconnection()  // 内部自动重连
            .build()

        startConnectionWithRetry(callback)
    }


    /**
     * 启动与Google Billing的连接。如果连接失败，则根据重试机制进行重试。
     */
    private fun startConnectionWithRetry(callback: InitializationCallback) {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingResponseCode.OK) {
                    callback.onSuccess()  // 通知初始化成功
                    Log.w(TAG, "google play billing 初始化成功")
                } else {
                    LogUtil.e("google play billing 初始化失败")
                    callback.onFailure(fromBillingResponseCode(billingResult.responseCode))
                    cleanup()
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Initialization Disconnected")
                //已开启 enableAutoServiceReconnection() 自动重新建立连接，因此该方法留空，无需再实现重连逻辑
            }
        })
    }

    override fun setPurchaseListener(listener: PurchaseCallback) {
        this.purchaseListener = listener
    }

    /**
     * 1.在单次购买流程中，支付结果是二选一的，不会在同一个回调列表里既给你“成功的订单”又给你“取消的订单”。
     * 如果用户取消了当前购买：
     * BillingResult.getResponseCode() 会返回 USER_CANCELED。
     * List<Purchase> 通常为 null 或者 空列表。
     * 结论： 只要响应码不是 OK，你就不需要去遍历那个列表，直接提示用户“支付已取消”即可。
     * 如果用户支付成功：
     * BillingResult.getResponseCode() 返回 OK。
     * List<Purchase> 包含本次刚支付成功的订单，以及之前支付成功但尚未确认（Acknowledge）/消耗（Consume）的遗留订单。
     * 2. 为什么会有“多个交易”出现在列表里？
     * 你可能会疑惑：既然用户一次只能买一个，为什么给的是 List<Purchase> 列表？
     * 这主要是为了补单机制（Reliability）：
     * 未完成的旧订单： 如果用户上次买完后还没来得及发货，App 就崩溃了或者网络断了，那么当他下一次尝试购买任何东西触发 onPurchasesUpdated 时，Google 会把那个“已支付但未处理”的旧订单连同新订单一起塞进这个 List 发给你
     * 处理支付结果更新事件，确认支付状态，并根据支付状态调用相应的回调。
     *
     * @param billingResult 支付结果。
     * @param purchases 购买的商品列表。
     */
    private fun handlePurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        LogUtil.d("有交易订单更新=${billingResult.responseCode} ${billingResult.debugMessage}")
        purchaseListener?.onUpdate(
            fromBillingResponseCode(billingResult.responseCode),
            mapToPaymentPurchaseList(purchases ?: emptyList())
        )
    }

    /**
     * 查询多个商品的详细信息。
     *
     * @param products 商品 ID 和类型列表。
     * @param callback 查询回调，返回查询结果。
     */
    override fun queryProducts(
        products: List<Pair<String, PaymentProductType>>,
        callback: QueryProductsCallback
    ) {
        // 构造查询参数列表
        val productList = products.map { (productId, productType) ->
            val billingProductType = productType.toBillingProductType()
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(billingProductType)
                .build()
        }

        // 创建查询商品的请求参数
        val queryProductDetailsParams = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        // 发起商品查询请求
        billingClient.queryProductDetailsAsync(queryProductDetailsParams) { billingResult, queryProductDetailsResult ->
            val response = BillingResponse(billingResult.responseCode)
            if (response.isOk) {
                // 1. 处理成功获取到的商品
                val paymentProductDetailsList = queryProductDetailsResult.productDetailsList.map {
                    logProductDetails(it)  // 打印商品详情到日志
                    val paymentProductType = if (it.productType == BillingClient.ProductType.SUBS) {
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

                // 2. 处理未获取到的商品 (UnfetchedProduct)
                val unfetchedProductIds = queryProductDetailsResult.unfetchedProductList.map { unfetched ->
                    logUnfetchedProduct(unfetched)
                    unfetched.productId
                }

                // 返回查询结果，包括成功和失败的部分
                callback.onQuerySuccess(paymentProductDetailsList, unfetchedProductIds)
            } else {
                Log.e(
                    TAG,
                    "onProductDetailsResponse Error: ${response.code} ${billingResult.debugMessage}"
                )
                callback.onQueryFailure(fromBillingResponseCode(billingResult.responseCode))
            }
        }
    }


    /**
     * 未能根据商品ID和类型，获取到商品的信息
     * 根据官方文档 UnfetchedProduct.StatusCode 包含以下关键状态码：
     *
     * NO_ELIGIBLE_OFFER = 4‌：商品存在，但‌没有符合条件的购买选项或优惠‌。
     *  例如：订阅没有可用的基础计划（base plan）；一次性商品没有配置任何有效的购买选项。
     *
     * PRODUCT_NOT_FOUND = 3‌：商品未找到。
     *  可能原因：商品已被删除、从未创建、类型错误，或刚创建但尚未在 Google Play 后台完全传播。
     *
     * INVALID_PRODUCT_ID_FORMAT = 2‌：商品 ID 格式不合法（如订阅 ID 不符合 Google Play 的命名规范）‌
     *
     * UNKNOWN = 0‌：未知错误。
     * **/
    private fun logUnfetchedProduct(unfetchedProduct: UnfetchedProduct?) {
        unfetchedProduct ?: return
        Log.d(
            TAG,
            "未获取到的商品- ID:${unfetchedProduct.productId} " +
                    "类型:${unfetchedProduct.productType}" +
                    " 状态:${unfetchedProduct.statusCode}"
        )
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
     * @param key 支付标识符，用于标识当前支付操作。
     * @param productType 产品类型。
     * @param productId 产品ID。
     * @param offerId 订阅优惠ID。
     * @param callback 支付回调，用于返回支付结果。
     */
    override fun makePayment(
        activity: Activity,
        key: String,
        productType: PaymentProductType,
        productId: String,
        offerId: String,
        callback: MakePaymentCallback
    ) {
        val billingProductType = productType.toBillingProductType()

        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(billingProductType)
                .build()
        )
        val productDetailsParams = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()
        billingClient.queryProductDetailsAsync(productDetailsParams) { billingResult, queryProductDetailsResult ->
            val response = BillingResponse(billingResult.responseCode)
            when {
                response.isOk -> {
                    val productDetailsParamsList =
                        queryProductDetailsResult.productDetailsList.map { productDetails ->
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

                    //1. 核心商品参数 (ProductDetailsParams)
                    //  在结算库 5.0 及更高版本中，必须通过 setProductDetailsParamsList 方法设置。
                    //  ProductDetails: 通过 queryProductDetailsAsync 查询到的商品详情对象。它包含了商品的价格、标题、描述等信息。
                    //  OfferToken: 仅限订阅商品。订阅商品可能有多个基础方案（Base Plan）和优惠方案（Offer），offerToken 用于指定用户具体购买哪一个方案。
                    //2. 订阅升级/降级参数 (SubscriptionUpdateParams)
                    //  当用户想要更改现有订阅（例如从“月度会员”升级为“年度会员”）时，需要配置此参数：
                    //  OldPurchaseToken: 当前正在使用的旧订阅的购买令牌（Purchase Token）。Google Play 会利用它定位用户要替换的旧方案。
                    //  SubscriptionReplacementMode: 替换模式（旧版本称为 ProrationMode），定义如何处理剩余的订阅时长或金额。常见模式包括：
                    //  CHARGE_FULL_PRICE：立即按新价格扣款，新订阅立即生效。
                    //  WITH_TIME_PRORATION：按比例转换剩余时长。
                    //  DEFERRED：等到当前周期结束后再切换到新订阅。
                    //3. 用户标识参数 (Obfuscated Identifiers)
                    //  用于将购买交易与应用内的特定账号关联，防止恶意代充或掉单：
                    //  setObfuscatedAccountId: 混淆后的应用内用户账号 ID（建议使用 UUID 的哈希值）。
                    //  setObfuscatedProfileId: 混淆后的用户个人资料 ID（如果应用支持多角色，可填此项）。
                    //  注意：请勿在此处直接存放用户明文邮箱或 ID，需进行混淆处理。
                    //4. 开发者选项参数 (Developer Options)
                    //  场景意义：这主要用于配合 Google 的备选结算系统（Alternative Billing）或外部支付计划。
                    //  在这些计划下，开发者可以向用户提供除 Google Play 以外的第三方支付方式。
                    //  功能联动：通常需要与 DeveloperBillingOptionParams 等类配合使用，用以定义具体的支付详情
                    val billingFlowParams = BillingFlowParams.newBuilder()
//                        .enableDeveloperBillingOption()
                        .setObfuscatedAccountId(key) //使用用户账号ID的位置来透传订单标识
//                        .setObfuscatedProfileId()
//                        .setSubscriptionUpdateParams()
                        .setProductDetailsParamsList(productDetailsParamsList)
                        .setIsOfferPersonalized(true)  //欧盟政策
                        .build()
                    val billingResult = billingClient.launchBillingFlow(activity, billingFlowParams)
                    Log.d(TAG, "launchBillingFlow: 启动结果 $billingResult")
                }

                else -> {
                    Log.e(TAG, "launchBillingFlow: 匹配不到商品 ${response.code}")
                    callback.onFailure(fromBillingResponseCode(response.code))
                }
            }
        }
    }


    override fun queryPurchases(
        productType: PaymentProductType,
        callback: QueryPurchasesCallback
    ) {
        val billingProductType = productType.toBillingProductType()

        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(billingProductType)
                .build()
        ) { billingResult, purchases ->
            if (billingResult.responseCode == BillingResponseCode.OK) {
                // 查询时不自动处理订单，只返回购买详情供业务层判断
                val paymentPurchaseDetailsList = purchases.filterNotNull().map {
                    val paymentPurchaseState = when (it.purchaseState) {
                        Purchase.PurchaseState.PURCHASED -> PaymentPurchaseState.PURCHASED
                        Purchase.PurchaseState.PENDING -> PaymentPurchaseState.PENDING
                        else -> PaymentPurchaseState.UNSPECIFIED_STATE
                    }
                    GooglePurchaseDetails(
                        key = it.accountIdentifiers?.obfuscatedAccountId ?: "",
                        purchaseState = paymentPurchaseState,
                        products = it.products,
                        purchaseToken = it.purchaseToken,
                        isAcknowledged = it.isAcknowledged
                    )
                }
                callback.onQuerySuccess(paymentPurchaseDetailsList)
            } else {
                callback.onQueryFailure(fromBillingResponseCode(billingResult.responseCode))
            }
        }
    }

    /**
     * 确认订阅或一次性非消耗商品订单。
     *
     * @param purchaseToken 购买令牌
     * @param callback 确认结果回调
     */
    override fun acknowledgePurchase(
        purchaseToken: String,
        callback: (success: Boolean) -> Unit
    ) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchaseToken)
            .build()
        billingClient.acknowledgePurchase(params) { billingResult ->
            val response = BillingResponse(billingResult.responseCode)
            if (response.isOk) {
                Log.i(TAG, "acknowledgePurchase：成功确认该商品 - token: $purchaseToken")
                callback.invoke(true)
            } else {
                Log.e(TAG, "acknowledgePurchase 失败：${billingResult.debugMessage}")
                callback.invoke(false)
            }
        }
    }

    /**
     * 消费一次性消耗商品订单。
     *
     * @param purchaseToken 购买令牌
     * @param callback 消费结果回调
     */
    override fun consumePurchase(
        purchaseToken: String,
        callback: (success: Boolean) -> Unit
    ) {
        val consumeParams = ConsumeParams.newBuilder()
            .setPurchaseToken(purchaseToken)
            .build()
        billingClient.consumeAsync(consumeParams) { billingResult, purchaseToken ->
            val response = BillingResponse(billingResult.responseCode)
            if (response.isOk) {
                Log.d(TAG, "consumeAsync: 成功消耗该商品 - token: $purchaseToken")
                callback.invoke(true)
            } else {
                Log.e(TAG, "consumeAsync 失败：${billingResult.debugMessage}")
                callback.invoke(false)
            }
        }
    }

    /**
     * 清理资源，防止内存泄露。
     * 在不再需要 GoogleBillingProvider 时调用（如 Activity/Fragment onDestroy）。
     */
    override fun cleanup() {
        // 断开 BillingClient 连接
        if (::billingClient.isInitialized && billingClient.isReady) {
            billingClient.endConnection()
        }

        Log.d(TAG, "BillingClient cleaned up")
    }

    /**
     * 把结算库返回的购买详情映射为 PaymentPurchaseDetails
     * **/
    private fun mapToPaymentPurchaseDetails(purchase: Purchase): GooglePurchaseDetails {
        val paymentPurchaseState = when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> PaymentPurchaseState.PURCHASED
            Purchase.PurchaseState.PENDING -> PaymentPurchaseState.PENDING
            else -> PaymentPurchaseState.UNSPECIFIED_STATE
        }
        return GooglePurchaseDetails(
            key = purchase.accountIdentifiers?.obfuscatedAccountId ?: "",
            purchaseState = paymentPurchaseState,
            products = purchase.products,
            purchaseToken = purchase.purchaseToken,
            isAcknowledged = purchase.isAcknowledged
        )
    }

    private fun mapToPaymentPurchaseList(purchases: List<Purchase>): List<GooglePurchaseDetails> {
        return purchases.map { mapToPaymentPurchaseDetails(it) }
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
        get() = code == BillingResponseCode.OK  // 如果返回的响应码是 OK (0)，表示操作成功。

    // 判断响应码是否表示可以优雅失败的情况（例如用户已购买该商品）
    val canFailGracefully: Boolean
        get() = code == BillingResponseCode.ITEM_ALREADY_OWNED  // 如果返回的是 ITEM_ALREADY_OWNED (7)，表示用户已经拥有该商品，这并不算作错误，系统可以优雅地处理这种情况。

    // 判断响应码是否表示可恢复的错误（例如服务断开或一般错误）
    val isRecoverableError: Boolean
        get() = code in setOf(
            BillingResponseCode.ERROR,  // 一般错误（1），可能是由于未知原因导致的错误。
            BillingResponseCode.SERVICE_DISCONNECTED  // 服务断开（2），表示与计费服务的连接断开，通常可以重试。
        )

    // 判断响应码是否表示不可恢复的错误（例如服务不可用、开发者错误）
    val isNonrecoverableError: Boolean
        get() = code in setOf(
            BillingResponseCode.SERVICE_UNAVAILABLE,  // 服务不可用（3），表示计费服务不可用，通常是临时性问题。
            BillingResponseCode.BILLING_UNAVAILABLE,  // 计费不可用（4），表示计费系统不可用。
            BillingResponseCode.DEVELOPER_ERROR  // 开发者错误（5），通常是开发者配置错误，需修复代码。
        )

    // 判断响应码是否表示严重的故障，通常是用户行为导致的失败或商品问题
    val isTerribleFailure: Boolean
        get() = code in setOf(
            BillingResponseCode.ITEM_UNAVAILABLE,  // 商品不可用（6），表示商品已下架或不可购买。
            BillingResponseCode.FEATURE_NOT_SUPPORTED,  // 功能不支持（8），表示当前设备或地区不支持该功能。
            BillingResponseCode.ITEM_NOT_OWNED,  // 商品未拥有（9），表示用户没有购买该商品。
            BillingResponseCode.USER_CANCELED  // 用户取消（10），表示用户手动取消了支付或购买操作。
        )

    companion object {
        // 根据BillingClient的响应码返回对应的PaymentErrorCode
        fun fromBillingResponseCode(code: Int): PaymentCode {
            return when (code) {
                BillingResponseCode.OK -> PaymentCode.OK
                BillingResponseCode.ITEM_ALREADY_OWNED -> PaymentCode.ITEM_ALREADY_OWNED
                BillingResponseCode.ERROR -> PaymentCode.ERROR
                BillingResponseCode.SERVICE_DISCONNECTED -> PaymentCode.SERVICE_DISCONNECTED
                BillingResponseCode.SERVICE_UNAVAILABLE -> PaymentCode.SERVICE_UNAVAILABLE
                BillingResponseCode.BILLING_UNAVAILABLE -> PaymentCode.BILLING_UNAVAILABLE
                BillingResponseCode.DEVELOPER_ERROR -> PaymentCode.DEVELOPER_ERROR
                BillingResponseCode.ITEM_UNAVAILABLE -> PaymentCode.ITEM_UNAVAILABLE
                BillingResponseCode.FEATURE_NOT_SUPPORTED -> PaymentCode.FEATURE_NOT_SUPPORTED
                BillingResponseCode.ITEM_NOT_OWNED -> PaymentCode.ITEM_NOT_OWNED
                BillingResponseCode.USER_CANCELED -> PaymentCode.USER_CANCELED
                else -> PaymentCode.ERROR
            }
        }
    }
}




