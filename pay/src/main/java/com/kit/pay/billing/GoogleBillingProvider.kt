package com.kit.pay.billing

import android.app.Activity
import android.app.Application
import android.util.Log
import java.lang.ref.WeakReference
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
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.consumePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.kit.pay.base.InitializationCallback
import com.kit.pay.base.MakePaymentResult
import com.kit.pay.base.PaymentAcknowledgeResult
import com.kit.pay.base.PaymentCallback
import com.kit.pay.base.PaymentCode
import com.kit.pay.base.PaymentConsumeResult
import com.kit.pay.base.PaymentProductDetails
import com.kit.pay.base.PaymentProductType
import com.kit.pay.base.PaymentProvider
import com.kit.pay.base.PaymentPurchaseDetails
import com.kit.pay.base.PaymentPurchaseState
import com.kit.pay.base.PurchaseCallback
import com.kit.pay.base.QueryProductsResult
import com.kit.pay.base.QueryPurchasesResult
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
     * 连接成功后会调用 `initializationCallback.onSuccess()`。
     * 连接失败则会调用 `initializationCallback.onFailure()`。
     *
     * @param callback 初始化结果回调。
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

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingResponseCode.OK) {
                    LogUtil.d("google play billing 初始化成功")
                    callback.onSuccess()
                } else {
                    LogUtil.e("google play billing 初始化失败")
                    cleanup()
                    callback.onFailure(fromBillingResponseCode(billingResult.responseCode))
                }
            }

            override fun onBillingServiceDisconnected() {
                LogUtil.e("google play billing 已经断开连接")
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
        LogUtil.d("有交易订单更新=${billingResult.responseCode} ${billingResult.debugMessage} ${purchases?.size}")
        purchaseListener?.onUpdate(
            fromBillingResponseCode(billingResult.responseCode),
            mapToPaymentPurchaseList(purchases ?: emptyList())
        )
    }

    /**
     * 查询多个商品的详细信息。
     *
     * @param products 商品 ID 和类型列表。
     * @return 查询结果。
     */
    override suspend fun queryProducts(
        products: List<Pair<String, PaymentProductType>>
    ): QueryProductsResult {
        // 构造查询参数列表
        val queryProductList = products.map { (productId, productType) ->
            val billingProductType = productType.toBillingProductType()
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(billingProductType)
                .build()
        }

        // 创建查询商品的请求参数
        val queryProductDetailsParams = QueryProductDetailsParams.newBuilder()
            .setProductList(queryProductList)
            .build()

        // 发起商品查询请求（新版 API 是同步的）
        val productDetailsResult = billingClient.queryProductDetails(queryProductDetailsParams)
        val billingResult = productDetailsResult.billingResult
        val productDetailsList = productDetailsResult.productDetailsList

        return if (billingResult.responseCode == BillingResponseCode.OK) {
            // 1. 处理成功获取到的商品
            val fetchedProductIds = mutableSetOf<String>()
            val resultProductList = productDetailsList?.map { product ->
                logProductDetails(product)  // 打印商品详情到日志
                fetchedProductIds.add(product.productId)
                val productType = if (product.productType == BillingClient.ProductType.SUBS) {
                    PaymentProductType.SUBS
                } else {
                    PaymentProductType.INAPP
                }
                PaymentProductDetails(
                    productId = product.productId,
                    title = product.title,
                    description = product.description,
                    productType = productType
                )
            } ?: emptyList()

            // 2. 处理未获取到的商品 (UnfetchedProduct)
            // 通过对比请求和响应，找出未获取到的商品
            val unfetchedProductIds = products
                .filter { (productId, _) -> productId !in fetchedProductIds }
                .onEach { (productId, productType) ->
                    // 记录未获取到的商品信息
                    LogUtil.w("未获取到的商品 - ID:$productId 类型:${if (productType == PaymentProductType.SUBS) "subs" else "inapp"} 状态:PRODUCT_NOT_FOUND")
                }
                .map { it.first }

            // 返回查询结果，包括成功和失败的部分
            if (unfetchedProductIds.isNotEmpty()) {
                LogUtil.w("有 ${unfetchedProductIds.size} 个商品未找到：$unfetchedProductIds")
            }
            LogUtil.d("查询商品成功")
            QueryProductsResult(
                products = resultProductList,
                unfetchedProductIds = unfetchedProductIds
            )
        } else {
            LogUtil.e("查询商品失败：${billingResult.responseCode} ${billingResult.debugMessage}")
            QueryProductsResult(
                errorCode = fromBillingResponseCode(
                    billingResult.responseCode
                )
            )
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
        LogUtil.d(
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
     * 发起支付流程，包括一次性购买和订阅购买。
     *
     * @param activityRef 当前活动的 Activity 的弱引用（防止内存泄漏）。
     * @param key 支付标识符，用于标识当前支付操作。
     * @param productType 产品类型。
     * @param productId 产品 ID。
     * @param offerId 订阅优惠 ID。
     * @return 支付结果。
     */
    override suspend fun makePayment(
        activityRef: WeakReference<Activity>,
        key: String,
        productType: PaymentProductType,
        productId: String,
        offerId: String
    ): MakePaymentResult {

        LogUtil.d("makePayment 先查询商品最新详情: $productId")
        val billingProductType = productType.toBillingProductType()

        // 构造查询参数
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(billingProductType)
                .build()
        )
        val productDetailsParams = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        // 使用同步 API 查询商品详情
        val productDetailsResult = billingClient.queryProductDetails(productDetailsParams)
        val billingResult = productDetailsResult.billingResult
        val productDetails = productDetailsResult.productDetailsList?.firstOrNull()

        if (billingResult.responseCode != BillingResponseCode.OK) {
            LogUtil.e("查询商品详情失败：${billingResult.debugMessage}")
            return MakePaymentResult(
                isSuccess = false,
                errorCode = fromBillingResponseCode(billingResult.responseCode)
            )
        }

        if (productDetails == null) {
            LogUtil.e("未找到商品详情")
            return MakePaymentResult(
                isSuccess = false,
                errorCode = PaymentCode.ITEM_UNAVAILABLE
            )
        }

        // 根据商品类型构建支付参数
        val productDetailsParamsList = if (productType == PaymentProductType.SUBS) {
            // 订阅商品：需要指定优惠方案
            val offerToken = if (offerId.isNotEmpty()) {
                productDetails.subscriptionOfferDetails?.find { it.offerId == offerId }?.offerToken
                    ?: productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken
            } else {
                productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken
            }

            offerToken?.let { token ->
                listOf(
                    ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        .setOfferToken(token)
                        .build()
                )
            } ?: emptyList()
        } else {
            // 一次性商品
            productDetails.oneTimePurchaseOfferDetails?.let {
                listOf(
                    ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        .build()
                )
            } ?: emptyList()
        }

        if (productDetailsParamsList.isEmpty()) {
            LogUtil.e("未找到可用的优惠方案")
            return MakePaymentResult(
                isSuccess = false,
                errorCode = PaymentCode.ITEM_UNAVAILABLE
            )
        }

        // 发起支付流程
        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        // 从弱引用中获取 Activity
        val activity = activityRef.get() ?: run {
            LogUtil.e("Activity 已被回收，无法发起支付")
            return MakePaymentResult(
                isSuccess = false,
                errorCode = PaymentCode.ERROR
            )
        }

        val responseCode = billingClient.launchBillingFlow(
            activity,
            billingFlowParams
        ).responseCode

        return if (responseCode == BillingResponseCode.OK) {
            MakePaymentResult(isSuccess = true)
        } else {
            MakePaymentResult(
                isSuccess = false,
                errorCode = fromBillingResponseCode(responseCode)
            )
        }
    }


    /**
     * 查询用户的购买记录。
     *
     * @param productType 商品类型。
     * @return 查询结果。
     */
    override suspend fun queryPurchases(productType: PaymentProductType): QueryPurchasesResult {
        val billingProductType = productType.toBillingProductType()

        val queryPurchasesParams = QueryPurchasesParams.newBuilder()
            .setProductType(billingProductType)
            .build()

        val purchasesResult = billingClient.queryPurchasesAsync(queryPurchasesParams)
        val billingResult = purchasesResult.billingResult
        val purchaseList = purchasesResult.purchasesList
        return if (billingResult.responseCode == BillingResponseCode.OK) {
            LogUtil.d("$productType 查询购买记录成功")
            QueryPurchasesResult(
                purchases = mapToPaymentPurchaseList(
                    purchaseList
                )
            )
        } else {
            LogUtil.e("$productType 查询购买记录失败：${billingResult.debugMessage}")
            QueryPurchasesResult(
                errorCode = fromBillingResponseCode(
                    billingResult.responseCode
                )
            )
        }
    }

    private fun logPurchases(purchaseDetailsList: List<PaymentPurchaseDetails>) {
        purchaseDetailsList.forEach {
            LogUtil.d("purchase: ${it.orderId} ${it.products} ${it.purchaseState} ${it.purchaseToken} ${it.isAcknowledged}")
        }
    }

    /**
     * 确认订阅或非消耗商品订单。
     *
     * @return 确认结果。
     */
    override suspend fun acknowledgePurchase(purchaseToken: String): PaymentAcknowledgeResult {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchaseToken)
            .build()

        // 新版 API 是同步的，返回 BillingResult
        val billingResult = billingClient.acknowledgePurchase(params)

        return if (billingResult.responseCode == BillingResponseCode.OK) {
            LogUtil.d("确认订单成功")
            PaymentAcknowledgeResult(
                isSuccess = true,
            )
        } else {
            LogUtil.e("确认订单失败：${billingResult.debugMessage}")
            PaymentAcknowledgeResult(
                isSuccess = false,
                errorCode = fromBillingResponseCode(billingResult.responseCode),
                message = billingResult.debugMessage
            )
        }
    }

    /**
     * 消费消耗型商品订单。
     *
     * @return 消费结果。
     */
    override suspend fun consumePurchase(purchaseToken: String): PaymentConsumeResult {
        val consumeParams = ConsumeParams.newBuilder()
            .setPurchaseToken(purchaseToken)
            .build()

        // 新版 API 是同步的，返回 ConsumeResult
        val consumeResult = billingClient.consumePurchase(consumeParams)

        return if (consumeResult.billingResult.responseCode == BillingResponseCode.OK) {
            LogUtil.d("消费商品成功")
            PaymentConsumeResult(
                isSuccess = true,
                purchaseToken = consumeResult.purchaseToken
            )
        } else {
            LogUtil.e("消费商品失败：${consumeResult.billingResult.debugMessage}")
            PaymentConsumeResult(
                isSuccess = false,
                errorCode = fromBillingResponseCode(consumeResult.billingResult.responseCode),
                message = consumeResult.billingResult.debugMessage
            )
        }
    }

    /**
     * 把结算库返回的购买详情映射为 PaymentPurchaseDetails
     * **/
    private fun mapToPaymentPurchaseDetails(purchase: Purchase): PaymentPurchaseDetails {
        val paymentPurchaseState = when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> PaymentPurchaseState.PURCHASED
            Purchase.PurchaseState.PENDING -> PaymentPurchaseState.PENDING
            else -> PaymentPurchaseState.UNSPECIFIED_STATE
        }
        return PaymentPurchaseDetails(
            key = purchase.accountIdentifiers?.obfuscatedAccountId ?: "",
            orderId = purchase.orderId ?: "",
            purchaseState = paymentPurchaseState,
            products = purchase.products,
            purchaseToken = purchase.purchaseToken,
            isAcknowledged = purchase.isAcknowledged
        )
    }

    private fun mapToPaymentPurchaseList(purchases: List<Purchase>): List<PaymentPurchaseDetails> {
        return purchases.map { mapToPaymentPurchaseDetails(it) }.apply {
            logPurchases(this)
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

        LogUtil.d("主动断开 BillingClient 连接")
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




