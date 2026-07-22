package com.kit.pay.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import com.kit.pay.interfaces.ErrorCode
import com.kit.pay.interfaces.PayKitError
import com.kit.pay.models.ProductType
import com.kit.pay.models.PurchaseState
import com.kit.pay.models.StoreProduct
import com.kit.pay.models.StoreTransaction
import com.kit.pay.models.SubscriptionOfferPricing
import com.kit.pay.models.SubscriptionReplacement
import com.kit.pay.models.SubscriptionReplacementMode
import com.kit.pay.utils.LogUtil
import kotlinx.coroutines.delay
import java.lang.ref.WeakReference
import kotlin.time.Duration.Companion.milliseconds

/**
 * Google Play Billing 服务封装实现。
 * 
 * 负责与 Google Play Billing Library 交互，包括：
 * - 连接管理
 * - 商品查询
 * - 购买流程
 * - 订单确认和消耗
 * 
 * 此类是 [BillingAbstract] 的具体实现，提供了 Google Play 平台的支付能力。
 */
class GoogleBillingWrapper(applicationContext: Context) : BillingAbstract(),
    PurchasesUpdatedListener {

    private companion object {
        private val TAG = LogUtil.TAG_BILLING
    }

    private fun logD(msg: String) = LogUtil.d(TAG, msg)
    private fun logE(msg: String) = LogUtil.e(TAG, msg)
    private fun logW(msg: String) = LogUtil.w(TAG, msg)

    private val billingClient: BillingClient = BillingClient.newBuilder(applicationContext)
        .enablePendingPurchases(    //启用对“待处理购买交易”的支持，即用户未完成支付的订单
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts() // 必须：启用一次性商品支持
                .enablePrepaidPlans()    // 可选：启用预付费方案支持，如果应用内有预付费型订阅（非自动续订），可开启
                .build()
        )
        .enableAutoServiceReconnection()  // 内部自动重连
        .setListener(this)
        .build()

    override fun startConnection(onConnected: () -> Unit, onError: (PayKitError) -> Unit) {
        if (billingClient.isReady) {
            logD("connect skip reason=already_ready")
            onConnected()
            return
        }

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    logD("connect success")
                    onConnected()
                } else {
                    logE("connect fail code=${billingResult.responseCode} msg=${billingResult.debugMessage}")
                    onError(billingResult.toPayKitError())
                }
            }

            override fun onBillingServiceDisconnected() {
                logE("connect disconnected autoReconnect=true")
                //已开启 enableAutoServiceReconnection() 自动重新建立连接，因此该方法留空，无需再实现重连逻辑
            }
        })
    }

    /**
     * 确保连接可用；未连接时尝试重连并等待，超时返回 false。
     */
    private suspend fun ensureConnected(): Boolean {
        if (billingClient.isReady) {
            return true
        }

        logD("ensureConnected start waitMs=10000")
        startConnection(
            onConnected = {},
            onError = {}
        )

        var waitTime = 0
        val maxWaitTime = 10000
        val checkInterval = 500

        while (waitTime < maxWaitTime) {
            if (billingClient.isReady) {
                logD("ensureConnected success")
                return true
            }

            delay(checkInterval.toLong().milliseconds)
            waitTime += checkInterval
        }

        logE("ensureConnected timeout waitMs=10000")
        return false
    }

    private fun notConnectedError(): PayKitError {
        return PayKitError(ErrorCode.STORE_PROBLEM, "Google Play Billing not connected")
    }

    override suspend fun queryProductDetailsAsync(
        productType: ProductType,
        productIds: Set<String>
    ): Result<List<StoreProduct>> {
        if (!ensureConnected()) {
            logE("queryProductDetails abort reason=not_connected type=$productType ids=$productIds")
            return Result.failure(notConnectedError())
        }
        val queryParams = QueryProductDetailsParams.newBuilder().setProductList(
            productIds.map { id ->
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(id)
                    .setProductType(
                        if (productType == ProductType.SUBS)
                            BillingClient.ProductType.SUBS
                        else
                            BillingClient.ProductType.INAPP
                    )
                    .build()
            }
        ).build()

        val result = billingClient.queryProductDetails(queryParams)
        val billingResult = result.billingResult
        val productDetailsList = result.productDetailsList

        val unfetchedProductList = productIds.filter { id ->
            productDetailsList?.any { it.productId == id } == false
        }

        return if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            logD("queryProductDetails success type=$productType requested=${productIds.size} fetched=${productDetailsList?.size ?: 0} unfetched=${unfetchedProductList.size}")
            val allStoreProducts = mutableListOf<StoreProduct>()

            // 1. 处理已获取到的商品
            productDetailsList?.forEach { detail ->
                logProductDetails(detail)
                allStoreProducts.addAll(detail.toStoreProducts(productType))
            }

            // 2. 处理未获取到的商品 (UnfetchedProduct)
            unfetchedProductList.forEach { unfetched ->
                // 记录未获取到的商品信息
                logUnfetchedProduct(unfetched, productType)
            }

            Result.success(allStoreProducts)
        } else {
            logE("queryProductDetails fail type=$productType code=${billingResult.responseCode} msg=${billingResult.debugMessage}")
            Result.failure(billingResult.toPayKitError())
        }
    }

    /**
     * 打印商品的详细信息到日志中，帮助开发者调试。
     *
     * @param productDetails 商品详情对象。
     */
    private fun logProductDetails(productDetails: ProductDetails?) {
        productDetails ?: return
        logD(
            "productDetail id=${productDetails.productId} " +
                "type=${productDetails.productType} " +
                "name=${productDetails.name} " +
                "title=${productDetails.title}"
        )

        // 订阅商品详情
        productDetails.subscriptionOfferDetails?.forEach { sub ->
            logSubscriptionOfferDetails(sub)
        }

        // 仅一次性商品，新版 API (Billing Library 5.0+)，支持多个优惠方案
        // 适用于：电影租赁、预售商品、限时优惠等特殊场景
        productDetails.oneTimePurchaseOfferDetailsList?.forEach { oneTime ->
            logOneTimePurchaseOfferDetails(oneTime)
        }

        // 仅一次性商品，旧版 API，看源码等同于productDetails.oneTimePurchaseOfferDetailsList?.first()，
        // 一般来说直接用oneTimePurchaseOfferDetailsList即可
        productDetails.oneTimePurchaseOfferDetails?.let {
            logOneTimePurchaseOfferDetails(it)
        }
    }

    private fun logSubscriptionOfferDetails(sub: ProductDetails.SubscriptionOfferDetails) {
        logD(
            """
            
            ┌─────────────────────────────────────┐
            │ 订阅商品优惠方案                        
            ├─────────────────────────────────────┤
            ├─ 基础信息:
            │  ├─ 基础方案 ID: ${sub.basePlanId}
            │  ├─ 优惠 ID: ${sub.offerId ?: "无"}
            │  ├─ 优惠 Token: ${sub.offerToken} ⭐支付必备
            │  └─ 优惠标签：${sub.offerTags.joinToString(", ").ifEmpty { "无" }}
            """.trimIndent()
        )

        // 分期付款计划详情
        sub.installmentPlanDetails?.let { installment ->
            val totalMonths = installment.installmentPlanCommitmentPaymentsCount
            val remainingMonths = installment.subsequentInstallmentPlanCommitmentPaymentsCount
            val paidMonths = totalMonths - remainingMonths

            logD(
                """
                ├─ 分期付款计划:
                │  ├─ 总分期期数：$totalMonths 个月
                │  ├─ 剩余期数：$remainingMonths 个月
                │  ├─ 已付期数：$paidMonths 个月
                │  └─ 说明：用户承诺支付$totalMonths 个月，目前还剩$remainingMonths 个月未付
                """.trimIndent()
            )
        }

        // 定价阶段详情
        logD("├─ 定价阶段 (共 ${sub.pricingPhases.pricingPhaseList.size} 个):")

        //关于有效期单位（billingPeriod），遵循 ISO 8601 格式： P1W 代表一周，P1M 代表一个月，P3M 代表三个月，P6M 代表 6 个月，P1Y 代表一年.
        //例如，对于 FormattedPrice$6.99 和 billingPeriod P1M，如果 billingCycleCount 为 2，则用户将收取 6.99 美元/月的费用，为期 2 个月。
        sub.pricingPhases.pricingPhaseList.forEachIndexed { index, pricing ->
            val recurrenceModeText = when (pricing.recurrenceMode) {
                1 -> "无限循环 (正常订阅)"
                2 -> "有限循环 (${pricing.billingCycleCount}次)"
                3 -> "非循环 (一次性)"
                else -> "未知模式 (${pricing.recurrenceMode})"
            }

            val phaseDescription = when {
                pricing.priceAmountMicros == 0L -> "【免费试用】"
                pricing.billingCycleCount > 0 -> "【优惠期：${pricing.billingCycleCount}个周期后恢复原价】"
                pricing.recurrenceMode == 1 -> "【正常订阅价格】"
                else -> ""
            }

            logD(
                """
                │  阶段 #${index + 1} $phaseDescription
                │  ├─ 价格 (微美元): ${pricing.priceAmountMicros}
                │  ├─ 货币代号：${pricing.priceCurrencyCode}
                │  ├─ 格式化价格：${pricing.formattedPrice}
                │  ├─ 计费周期：${pricing.billingPeriod} (ISO 8601 格式)
                │  ├─ 周期数量：${pricing.billingCycleCount}
                │  └─ 循环模式：$recurrenceModeText
                """.trimIndent()
            )
        }

        logD("└─────────────────────────────────────\n")
    }

    private fun logOneTimePurchaseOfferDetails(oneTime: ProductDetails.OneTimePurchaseOfferDetails) {
        logD(
            """
                        
                ┌─────────────────────────────────────┐
                │ 一次性商品优惠方案                        
                ├─────────────────────────────────────┤
                ├─ 价格信息:
                │  ├─ 当前价格 (微美元): ${oneTime.priceAmountMicros}
                │  ├─ 货币代号：${oneTime.priceCurrencyCode}
                │  └─ 格式化价格：${oneTime.formattedPrice}
                ├─ 优惠标识:
                │  ├─ 优惠 ID: ${oneTime.offerId ?: "无"}
                │  ├─ 优惠 Token: ${oneTime.offerToken} ⭐支付必备
                │  └─ 优惠标签：${oneTime.offerTags?.joinToString(", ") ?: "无"}
                ├─ 折扣信息:
                │  ├─ 折扣金额：${oneTime.discountDisplayInfo?.discountAmount ?: "无"} 微美元
                │  ├─ 折扣比例：${oneTime.discountDisplayInfo?.percentageDiscount ?: "无"}%
                │  └─ 原价：${oneTime.fullPriceMicros ?: "无"} 微美元
                ├─ 限购信息:
                │  ├─ 最大购买数量：${oneTime.limitedQuantityInfo?.maximumQuantity ?: "无限制"}
                │  └─ 剩余可购数量：${oneTime.limitedQuantityInfo?.remainingQuantity ?: "未知"}
                ├─ 预售信息:
                │  ├─ 预售释放时间：${
                oneTime.preorderDetails?.preorderReleaseTimeMillis?.let {
                    java.util.Date(
                        it
                    ).toString()
                } ?: "非预售"
            }
                │  └─ 预售结束时间：${
                oneTime.preorderDetails?.preorderPresaleEndTimeMillis?.let {
                    java.util.Date(
                        it
                    ).toString()
                } ?: "无"
            }
                ├─ 租赁信息 (如适用):
                │  ├─ 租赁周期：${oneTime.rentalDetails?.rentalPeriod ?: "不适用"}
                │  └─ 租赁过期周期：${oneTime.rentalDetails?.rentalExpirationPeriod ?: "不适用"}
                ├─ 有效时间窗口:
                │  ├─ 开始时间：${
                oneTime.validTimeWindow?.startTimeMillis?.let {
                    java.util.Date(it).toString()
                } ?: "无限制"
            }
                │  └─ 结束时间：${
                oneTime.validTimeWindow?.endTimeMillis?.let {
                    java.util.Date(it).toString()
                } ?: "无限制"
            }
                └─ 购买选项 ID: ${oneTime.purchaseOptionId ?: "无"}
                """.trimIndent()
        )
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
    private fun logUnfetchedProduct(productId: String, productType: ProductType) {
        logD("queryProductDetails unfetched id=$productId type=$productType")
    }

    override suspend fun makePurchaseAsync(
        activity: WeakReference<Activity>,
        storeProduct: StoreProduct,
        isOfferPersonalized: Boolean,
        subscriptionReplacement: SubscriptionReplacement?
    ): Result<Unit> {
        if (!ensureConnected()) {
            logE("makePurchase abort reason=not_connected productId=${storeProduct.productId}")
            return Result.failure(notConnectedError())
        }
        val pDetail = storeProduct.nativeProductDetails as? ProductDetails
            ?: return Result.failure(
                PayKitError(
                    ErrorCode.PRODUCT_NOT_AVAILABLE,
                    "Cached Product details not found"
                )
            )

        val gType = if (storeProduct.type == ProductType.SUBS)
            BillingClient.ProductType.SUBS
        else
            BillingClient.ProductType.INAPP

        if (subscriptionReplacement != null && gType != BillingClient.ProductType.SUBS) {
            return Result.failure(
                PayKitError(
                    ErrorCode.STORE_PROBLEM,
                    "subscriptionReplacement is only valid for SUBS products"
                )
            )
        }

        val productDetailsParamsList = if (gType == BillingClient.ProductType.SUBS) {
            val token = storeProduct.subscriptionToken ?: return Result.failure(
                PayKitError(
                    ErrorCode.PRODUCT_NOT_AVAILABLE,
                    "Missing offer token for subs"
                )
            )
            val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(pDetail)
                .setOfferToken(token)
            if (subscriptionReplacement != null) {
                productParams.setSubscriptionProductReplacementParams(
                    BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams
                        .newBuilder()
                        .setOldProductId(subscriptionReplacement.oldProductId)
                        .setReplacementMode(subscriptionReplacement.replacementMode.toBillingMode())
                        .build()
                )
            }
            listOf(productParams.build())
        } else {
            val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(pDetail)
            val offerToken = storeProduct.subscriptionToken
            if (!offerToken.isNullOrBlank()) {
                productParams.setOfferToken(offerToken)
            } else if (!storeProduct.offerId.isNullOrBlank()) {
                // 多 offer 场景下缺少 token 无法保证买对方案
                return Result.failure(
                    PayKitError(
                        ErrorCode.PRODUCT_NOT_AVAILABLE,
                        "Missing offer token for in-app offerId=${storeProduct.offerId}"
                    )
                )
            }
            listOf(productParams.build())
        }

        val flowBuilder = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .setIsOfferPersonalized(isOfferPersonalized)
        if (subscriptionReplacement != null) {
            flowBuilder.setSubscriptionUpdateParams(
                BillingFlowParams.SubscriptionUpdateParams.newBuilder()
                    .setOldPurchaseToken(subscriptionReplacement.oldPurchaseToken)
                    .build()
            )
        }
        val flowParams = flowBuilder.build()
        val activity = activity.get() ?: return Result.failure(
            PayKitError(
                ErrorCode.UNKNOWN,
                "Activity not found"
            )
        )
        val response = billingClient.launchBillingFlow(activity, flowParams)

        return if (response.responseCode != BillingClient.BillingResponseCode.OK) {
            logE(
                "launchBillingFlow fail productId=${storeProduct.productId} " +
                    "code=${response.responseCode} msg=${response.debugMessage} " +
                    "replace=${subscriptionReplacement?.oldProductId}"
            )
            Result.failure(response.toPayKitError())
        } else {
            logD(
                "launchBillingFlow success productId=${storeProduct.productId} " +
                    "type=${storeProduct.type} personalized=$isOfferPersonalized " +
                    "replace=${subscriptionReplacement?.oldProductId}"
            )
            Result.success(Unit)
        }
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
    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            logD("onPurchasesUpdated success count=${purchases.size}")
            val transactions = purchases.map { it.toStoreTransaction() }
            purchasesUpdatedListener?.onPurchasesUpdated(transactions)
        } else {
            val isCancelled =
                billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED
            logE("onPurchasesUpdated fail code=${billingResult.responseCode} cancelled=$isCancelled msg=${billingResult.debugMessage}")
            purchasesUpdatedListener?.onPurchasesFailedToUpdate(
                billingResult.toPayKitError(),
                isCancelled
            )
        }
    }

    override suspend fun queryPurchasesAsync(
        productType: ProductType
    ): Result<List<StoreTransaction>> {
        if (!ensureConnected()) {
            logE("queryPurchases abort reason=not_connected type=$productType")
            return Result.failure(notConnectedError())
        }
        val queryParams = QueryPurchasesParams.newBuilder()
            .setProductType(
                if (productType == ProductType.SUBS)
                    BillingClient.ProductType.SUBS
                else
                    BillingClient.ProductType.INAPP
            )
            .build()

        val result = billingClient.queryPurchasesAsync(queryParams)
        val billingResult = result.billingResult
        val list = result.purchasesList

        return if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            logD("queryPurchases success type=$productType count=${list.size}")
            logPurchases(list)
            val allTransactions = list.map { it.toStoreTransaction() }
            Result.success(allTransactions)
        } else {
            logE("queryPurchases fail type=$productType code=${billingResult.responseCode} msg=${billingResult.debugMessage}")
            Result.failure(billingResult.toPayKitError())
        }
    }

    private fun logPurchases(purchaseDetailsList: List<Purchase>) {
        purchaseDetailsList.forEach {
            logD("purchase orderId=${it.orderId} products=${it.products} state=${it.purchaseState} acknowledged=${it.isAcknowledged} token=${it.purchaseToken}")
        }
    }

    override suspend fun consumeAndAcknowledge(
        transaction: StoreTransaction,
        isConsumable: Boolean
    ): Result<Unit> {
        if (!ensureConnected()) {
            logE("consumeAndAcknowledge abort reason=not_connected orderId=${transaction.orderId}")
            return Result.failure(notConnectedError())
        }

        if (transaction.isAcknowledged) {
            return Result.success(Unit)
        }

        if (isConsumable) {
            val consumeParams =
                ConsumeParams.newBuilder().setPurchaseToken(transaction.purchaseToken).build()
            val billingResult = billingClient.consumePurchase(consumeParams).billingResult
            return if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                logD("consume success orderId=${transaction.orderId} products=${transaction.productIds}")
                Result.success(Unit)
            } else {
                logE("consume fail orderId=${transaction.orderId} code=${billingResult.responseCode} msg=${billingResult.debugMessage}")
                Result.failure(billingResult.toPayKitError())
            }
        } else {
            val ackParams =
                AcknowledgePurchaseParams.newBuilder().setPurchaseToken(transaction.purchaseToken)
                    .build()
            val billingResult = billingClient.acknowledgePurchase(ackParams)
            return if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                logD("acknowledge success orderId=${transaction.orderId} products=${transaction.productIds}")
                Result.success(Unit)
            } else {
                logE("acknowledge fail orderId=${transaction.orderId} code=${billingResult.responseCode} msg=${billingResult.debugMessage}")
                Result.failure(billingResult.toPayKitError())
            }
        }
    }

    override fun endConnection() {
        if (billingClient.isReady) {
            billingClient.endConnection()
        }
    }
}

// ================= Extension Mappers =================
private fun BillingResult.toPayKitError(): PayKitError {
    val code = when (this.responseCode) {
        BillingClient.BillingResponseCode.USER_CANCELED -> ErrorCode.PURCHASE_CANCELLED

        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> ErrorCode.PRODUCT_NOT_AVAILABLE

        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
        BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED -> ErrorCode.PURCHASE_NOT_ALLOWED

        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> ErrorCode.ITEM_ALREADY_OWNED

        BillingClient.BillingResponseCode.NETWORK_ERROR,
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> ErrorCode.NETWORK_ERROR

        BillingClient.BillingResponseCode.ERROR,
        BillingClient.BillingResponseCode.DEVELOPER_ERROR,
        BillingClient.BillingResponseCode.ITEM_NOT_OWNED -> ErrorCode.STORE_PROBLEM

        else -> ErrorCode.STORE_PROBLEM
    }
    LogUtil.d(
        LogUtil.TAG_BILLING,
        "billingResult code=${this.responseCode} payKitCode=$code msg=${this.debugMessage}"
    )
    return PayKitError(code, this.debugMessage)
}

private fun ProductDetails.toStoreProducts(type: ProductType): List<StoreProduct> {
    val list = mutableListOf<StoreProduct>()
    if (type == ProductType.INAPP) {
        // 优先多 offer 列表；否则回退旧版单一 offer
        val offers = oneTimePurchaseOfferDetailsList
            ?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(oneTimePurchaseOfferDetails)
        offers.forEach { details ->
            list.add(
                StoreProduct(
                    productId = this.productId,
                    type = type,
                    title = this.title,
                    description = this.description,
                    price = details.formattedPrice,
                    priceAmountMicros = details.priceAmountMicros,
                    priceCurrencyCode = details.priceCurrencyCode,
                    subscriptionToken = details.offerToken,
                    offerId = details.offerId ?: details.purchaseOptionId,
                    nativeProductDetails = this
                )
            )
        }
    } else {
        this.subscriptionOfferDetails?.forEach { subOffer ->
            val pricing = SubscriptionOfferPricing.fromPhases(
                subOffer.pricingPhases.pricingPhaseList.map { phase ->
                    SubscriptionOfferPricing.Phase(
                        priceAmountMicros = phase.priceAmountMicros,
                        formattedPrice = phase.formattedPrice,
                        priceCurrencyCode = phase.priceCurrencyCode,
                        billingPeriod = phase.billingPeriod
                    )
                }
            )
            list.add(
                StoreProduct(
                    productId = this.productId,
                    type = type,
                    title = this.title,
                    description = this.description,
                    price = pricing.price,
                    priceAmountMicros = pricing.priceAmountMicros,
                    priceCurrencyCode = pricing.priceCurrencyCode,
                    subscriptionToken = subOffer.offerToken,
                    basePlanId = subOffer.basePlanId,
                    offerId = subOffer.offerId,
                    hasFreeTrial = pricing.hasFreeTrial,
                    freeTrialPeriod = pricing.freeTrialPeriod,
                    nativeProductDetails = this
                )
            )
        }
    }
    return list
}

private fun Purchase.toStoreTransaction(): StoreTransaction {
    return StoreTransaction(
        orderId = this.orderId ?: "",
        productIds = this.products,
        purchaseTime = this.purchaseTime,
        purchaseToken = this.purchaseToken,
        isAcknowledged = this.isAcknowledged,
        purchaseState = when (this.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> PurchaseState.PURCHASED
            Purchase.PurchaseState.PENDING -> PurchaseState.PENDING
            else -> PurchaseState.UNSPECIFIED
        },
        isAutoRenewing = this.isAutoRenewing,
        isSuspended = this.isSuspended,
        signature = this.signature.orEmpty(),
        originalJson = this.originalJson.orEmpty()
    )
}

private fun SubscriptionReplacementMode.toBillingMode(): Int {
    return when (this) {
        SubscriptionReplacementMode.WITH_TIME_PRORATION ->
            BillingReplacementModes.WITH_TIME_PRORATION
        SubscriptionReplacementMode.CHARGE_PRORATED_PRICE ->
            BillingReplacementModes.CHARGE_PRORATED_PRICE
        SubscriptionReplacementMode.WITHOUT_PRORATION ->
            BillingReplacementModes.WITHOUT_PRORATION
        SubscriptionReplacementMode.CHARGE_FULL_PRICE ->
            BillingReplacementModes.CHARGE_FULL_PRICE
        SubscriptionReplacementMode.DEFERRED ->
            BillingReplacementModes.DEFERRED
    }
}
