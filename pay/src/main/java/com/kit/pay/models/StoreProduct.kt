package com.kit.pay.models

/**
 * 代表来自底层商店的可购买项。在 Google Play 中它其实是包含一个特定 Offer 的 ProductDetails 的概念封装。
 */
data class StoreProduct(
    val productId: String,
    val type: ProductType,
    val title: String,
    val description: String,
    /** 展示用价格：订阅优先为正价（试用后阶段），一次性为商品价 */
    val price: String,
    val priceAmountMicros: Long,
    val priceCurrencyCode: String,

    /** Google offerToken：订阅必填；一次性多 offer 时亦需带上以买对方案 */
    val subscriptionToken: String? = null,

    /** 订阅 base plan ID（如 monthly / yearly）；一次性商品一般为 null */
    val basePlanId: String? = null,

    /** 优惠 / 购买选项 ID；基础价可能为 null */
    val offerId: String? = null,

    /** 首个定价阶段价格为 0 时视为含免费试用 */
    val hasFreeTrial: Boolean = false,

    /** 免费试用阶段的计费周期（ISO 8601，如 P1W）；无试用为 null */
    val freeTrialPeriod: String? = null,

    /** 底层 ProductDetails，购买用；不可序列化依赖 */
    @Transient val nativeProductDetails: Any? = null
)

enum class ProductType {
    SUBS, INAPP
}
