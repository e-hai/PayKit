package com.kit.pay.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import com.kit.pay.interfaces.ErrorCode
import com.kit.pay.interfaces.PayKitError
import com.kit.pay.models.ProductType
import com.kit.pay.models.StoreProduct
import com.kit.pay.models.StoreTransaction
import com.kit.pay.utils.LogUtil
import kotlinx.coroutines.delay
import java.lang.ref.WeakReference

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
            LogUtil.d("google play billing 已经连接")
            onConnected()
            return
        }

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    LogUtil.d("google play billing 连接成功")
                    onConnected()
                } else {
                    LogUtil.d("google play billing 连接失败：${billingResult.toPayKitError()}")
                    onError(billingResult.toPayKitError())
                }
            }

            override fun onBillingServiceDisconnected() {
                LogUtil.e("google play billing 已经断开连接")
                //已开启 enableAutoServiceReconnection() 自动重新建立连接，因此该方法留空，无需再实现重连逻辑
            }
        })
    }

    /**
     * 确保连接可用，如果未连接则等待连接完成
     */
    private suspend fun ensureConnected(): Boolean {
        if (billingClient.isReady) {
            return true
        }

        LogUtil.d("检测到连接不可用，等待连接...")

        // 最多等待 10 秒
        var waitTime = 0
        val maxWaitTime = 10000
        val checkInterval = 500

        while (waitTime < maxWaitTime) {
            if (billingClient.isReady) {
                LogUtil.d("连接已恢复")
                return true
            }

            // 如果未在连接中，主动发起连接
            LogUtil.d("主动发起连接请求")
            startConnection(
                onConnected = {},
                onError = {}
            )

            delay(checkInterval.toLong())
            waitTime += checkInterval
        }

        LogUtil.e("等待连接超时")
        return false
    }

    override suspend fun queryProductDetailsAsync(
        productType: ProductType,
        productIds: Set<String>
    ): Result<List<StoreProduct>> {
        // 确保连接可用
        if (!ensureConnected()) {
            LogUtil.e("google play billing 重新连接失败")
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
            LogUtil.d("google play billing 查询商品成功")
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
            LogUtil.e("google play billing 查询商品失败：${billingResult.toPayKitError()}")
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
        LogUtil.d(
            "商品- ID:${productDetails.productId} " +
                    "类型:${productDetails.productType} " +
                    "名称:${productDetails.name} " +
                    "标题:${productDetails.title} " +
                    "简介:${productDetails.description}"
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
        LogUtil.d(
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

            LogUtil.d(
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
        LogUtil.d("├─ 定价阶段 (共 ${sub.pricingPhases.pricingPhaseList.size} 个):")

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

            LogUtil.d(
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

        LogUtil.d("└─────────────────────────────────────\n")
    }

    private fun logOneTimePurchaseOfferDetails(oneTime: ProductDetails.OneTimePurchaseOfferDetails) {
        LogUtil.d(
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
        LogUtil.d(
            "未获取到的商品- ID:${productId} " + "类型:${productType}"
        )
    }

    override suspend fun makePurchaseAsync(
        activity: WeakReference<Activity>,
        storeProduct: StoreProduct
    ): Result<Unit> {
        // 确保连接可用
        if (!ensureConnected()) {
            LogUtil.e("google play billing 重新连接失败")
        }
        val pDetail = storeProduct.nativeProductDetails as? ProductDetails
            ?: return Result.failure(
                Exception(
                    PayKitError(
                        ErrorCode.PRODUCT_NOT_AVAILABLE,
                        "Cached Product details not found"
                    ).message
                )
            )

        val gType = if (storeProduct.type == ProductType.SUBS)
            BillingClient.ProductType.SUBS
        else
            BillingClient.ProductType.INAPP

        val productDetailsParamsList = if (gType == BillingClient.ProductType.SUBS) {
            val token = storeProduct.subscriptionToken ?: return Result.failure(
                Exception(
                    PayKitError(
                        ErrorCode.PRODUCT_NOT_AVAILABLE,
                        "Missing offer token for subs"
                    ).message
                )
            )
            listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(pDetail)
                    .setOfferToken(token)
                    .build()
            )
        } else {
            listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(pDetail)
                    .build()
            )
        }

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()
        val activity = activity.get() ?: return Result.failure(
            Exception(
                PayKitError(
                    ErrorCode.UNKNOWN,
                    "Activity not found"
                ).message
            )
        )
        val response = billingClient.launchBillingFlow(activity, flowParams)

        return if (response.responseCode != BillingClient.BillingResponseCode.OK) {
            Result.failure(response.toPayKitError())
        } else {
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
            val transactions = purchases.map { it.toStoreTransaction() }
            purchasesUpdatedListener?.onPurchasesUpdated(transactions)
        } else {
            val isCancelled =
                billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED
            purchasesUpdatedListener?.onPurchasesFailedToUpdate(
                billingResult.toPayKitError(),
                isCancelled
            )
        }
    }

    override suspend fun queryPurchasesAsync(
        productType: ProductType
    ): Result<List<StoreTransaction>> {
        // 确保连接可用
        if (!ensureConnected()) {
            LogUtil.e("google play billing 重新连接失败")
        }
        val queryParams = QueryPurchasesParams.newBuilder()
            .setProductType(
                if (productType == ProductType.SUBS)
                    BillingClient.ProductType.INAPP
                else
                    BillingClient.ProductType.SUBS
            )
            .build()

        val result = billingClient.queryPurchasesAsync(queryParams)
        val billingResult = result.billingResult
        val list = result.purchasesList

        return if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            logPurchases(list)
            val allTransactions = list.map { it.toStoreTransaction() }
            Result.success(allTransactions)
        } else {
            Result.failure(billingResult.toPayKitError())
        }
    }

    private fun logPurchases(purchaseDetailsList: List<Purchase>) {
        purchaseDetailsList.forEach {
            LogUtil.d("purchase: ${it.orderId} ${it.products} ${it.purchaseState} ${it.purchaseToken} ${it.isAcknowledged}")
        }
    }

    override suspend fun consumeAndAcknowledge(
        transaction: StoreTransaction,
        isConsumable: Boolean
    ): Result<Unit> {
        // 确保连接可用
        if (!ensureConnected()) {
            LogUtil.e("google play billing 重新连接失败")
        }

        if (transaction.isAcknowledged) {
            return Result.success(Unit)
        }

        if (isConsumable) {
            val consumeParams =
                ConsumeParams.newBuilder().setPurchaseToken(transaction.purchaseToken).build()
            val billingResult = billingClient.consumePurchase(consumeParams).billingResult
            return if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                Result.success(Unit)
            } else {
                Result.failure(billingResult.toPayKitError())
            }
        } else {
            val ackParams =
                AcknowledgePurchaseParams.newBuilder().setPurchaseToken(transaction.purchaseToken)
                    .build()
            val billingResult = billingClient.acknowledgePurchase(ackParams)
            return if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                Result.success(Unit)
            } else {
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
    when (this.responseCode) {
        BillingClient.BillingResponseCode.SERVICE_TIMEOUT -> {
            LogUtil.d("服务超时: 表示服务请求超时。这通常发生在网络连接中断或设备处于低电量状态时")
        }

        BillingClient.BillingResponseCode.USER_CANCELED -> {
            LogUtil.d("用户取消: 表示用户取消了购买或订阅操作。这通常发生在用户在购买流程中主动退出时")
        }

        BillingClient.BillingResponseCode.ITEM_NOT_OWNED -> {
            LogUtil.d("商品未购买: 当请求的商品并未购买时返回此错误码。常见于查询未完成购买的商品或查询用户尚未购买的商品")
        }

        BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED -> {
            LogUtil.d("特性不支持: 当设备或应用不支持请求的特性时返回此错误码。例如，设备不支持某些高级功能")
        }

        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
            LogUtil.d("商品已购买: 当用户尝试购买一个已经拥有的商品时，会返回此错误码。通常不需要再进行购买操作")
        }

        BillingClient.BillingResponseCode.DEVELOPER_ERROR -> {
            LogUtil.d("开发者错误: 当请求出现开发者错误时（例如请求参数错误、调用顺序错误等），将返回此错误码")
        }

        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> {
            LogUtil.d("服务断开: 表示与结算服务的连接断开。这通常是暂时性的网络问题，可以稍后重试")
        }

        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> {
            LogUtil.d("服务不可用: 当结算服务不可用时，可能是由于服务器问题或网络连接不佳，通常是临时的，可以稍后重试")
        }

        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> {
            LogUtil.d("计费不可用: 表示当前设备无法使用计费服务，可能因为设备或地区的限制，或者该设备不支持结算服务")
        }

        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> {
            LogUtil.d("商品不可用: 当请求的商品不可用时返回。可能是该商品已被下架或不适用于当前设备")
        }

        BillingClient.BillingResponseCode.ERROR -> {
            LogUtil.d("一般错误: 这表示发生了一个通用错误。通常这种情况需要开发人员进一步调查")
        }

        BillingClient.BillingResponseCode.OK -> {
            LogUtil.d("操作成功")
        }

        BillingClient.BillingResponseCode.NETWORK_ERROR -> {
            LogUtil.d("网络错误: 表示与结算服务之间的网络连接失败。这通常是由于网络问题导致的，可以稍后重试")
        }

        else -> {
            LogUtil.d("未知错误=${this.responseCode} ${this.debugMessage}")
        }
    }

    val code = when (this.responseCode) {
        BillingClient.BillingResponseCode.USER_CANCELED -> ErrorCode.PURCHASE_CANCELLED

        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> ErrorCode.PRODUCT_NOT_AVAILABLE

        BillingClient.BillingResponseCode.NETWORK_ERROR,
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> ErrorCode.NETWORK_ERROR

        else -> ErrorCode.STORE_PROBLEM
    }
    return PayKitError(code, this.debugMessage)
}

private fun ProductDetails.toStoreProducts(type: ProductType): List<StoreProduct> {
    val list = mutableListOf<StoreProduct>()
    if (type == ProductType.INAPP) {
        val details = this.oneTimePurchaseOfferDetails
        if (details != null) {
            list.add(
                StoreProduct(
                    productId = this.productId,
                    type = type,
                    title = this.title,
                    description = this.description,
                    price = details.formattedPrice,
                    priceAmountMicros = details.priceAmountMicros,
                    priceCurrencyCode = details.priceCurrencyCode,
                    nativeProductDetails = this
                )
            )
        }
    } else {
        this.subscriptionOfferDetails?.forEach { subOffer ->
            val phase = subOffer.pricingPhases.pricingPhaseList.firstOrNull()
            list.add(
                StoreProduct(
                    productId = this.productId,
                    type = type,
                    title = this.title,
                    description = this.description,
                    price = phase?.formattedPrice ?: "",
                    priceAmountMicros = phase?.priceAmountMicros ?: 0,
                    priceCurrencyCode = phase?.priceCurrencyCode ?: "",
                    subscriptionToken = subOffer.offerToken,
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
        isAcknowledged = this.isAcknowledged
    )
}
