package com.kit.pay.models

/**
 * 代表来自底层商店的可购买项。在 Google Play 中它其实是包含一个特定 Offer 的 ProductDetails 的概念封装。
 */
data class StoreProduct(
    val productId: String,
    val type: ProductType,
    val title: String,
    val description: String,
    val price: String,             // 格式化后的价格（例如 "$9.99"，多为首个定价阶段）
    val priceAmountMicros: Long,
    val priceCurrencyCode: String,

    /** 订阅必填：对应 Google offerToken */
    val subscriptionToken: String? = null,

    /** 订阅 base plan ID（如 monthly / yearly） */
    val basePlanId: String? = null,

    /** 订阅优惠 ID；基础价 offer 可能为 null */
    val offerId: String? = null,

    /** 首个定价阶段价格为 0 时视为含免费试用 */
    val hasFreeTrial: Boolean = false,

    /** 底层 ProductDetails，购买用；不可序列化依赖 */
    @Transient val nativeProductDetails: Any? = null
)

enum class ProductType {
    SUBS, INAPP
}
