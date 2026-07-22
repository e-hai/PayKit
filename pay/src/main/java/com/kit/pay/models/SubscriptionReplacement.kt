package com.kit.pay.models

/**
 * 订阅升降级 / 同商品换档（base plan）时的替换参数。
 *
 * 对应 Billing 8.x 的
 * [com.android.billingclient.api.BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams]
 * + [com.android.billingclient.api.BillingFlowParams.SubscriptionUpdateParams.setOldPurchaseToken]。
 *
 * @param oldProductId 将被替换的旧订阅 productId（如 Plus → Pro 时传 Plus 的 ID）
 * @param oldPurchaseToken 旧订阅的 purchaseToken
 * @param replacementMode 分摊 / 扣费模式；默认时间按比例（升级常用）
 */
data class SubscriptionReplacement(
    val oldProductId: String,
    val oldPurchaseToken: String,
    val replacementMode: SubscriptionReplacementMode = SubscriptionReplacementMode.WITH_TIME_PRORATION
)

/**
 * 订阅替换模式（PayKit 封装，映射到 Google ReplacementMode）。
 */
enum class SubscriptionReplacementMode {
    /** 立即替换，按价差调整剩余时长（升级常用默认） */
    WITH_TIME_PRORATION,

    /** 立即按差价收费 */
    CHARGE_PRORATED_PRICE,

    /** 立即替换，不调整时间与差价，下个周期起按新价 */
    WITHOUT_PRORATION,

    /** 立即按新方案全价收费 */
    CHARGE_FULL_PRICE,

    /** 当前周期结束再生效 */
    DEFERRED
}
