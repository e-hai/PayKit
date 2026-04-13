package com.kit.pay.models

/**
 * 等同于 RevenueCat 的 StoreProduct。
 * 代表来自底层的购买项。在 Google Play 中它其实是包含一个特定 Offer 的 ProductDetails 的概念封装。
 */
data class StoreProduct(
    val productId: String,
    val type: ProductType,
    val title: String,
    val description: String,
    val price: String,             // 格式化后的价格（例如 "$9.99"）
    val priceAmountMicros: Long,
    val priceCurrencyCode: String,
    
    // 如果是 Google 订阅，这代表具体的 offerId 与 basePlanId 结合体
    // 开发层直接传入 StoreProduct 发起购买即可，不用再手动穿透找 offerToken
    val subscriptionToken: String? = null,
    
    // 隐藏的底层原始凭证对象，用于直接发起购买，避免二次查询
    @Transient val nativeProductDetails: Any? = null
)

enum class ProductType {
    SUBS, INAPP
}
