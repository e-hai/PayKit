package com.kit.pay.models

/**
 * SDK 初始化配置。
 * 因为我们是纯本地无后端，必须让开发者初始化时告诉 SDK：
 * 哪些是订阅，哪些是消耗品，哪些是非消耗品，以防补单时无法分辨发货模型。
 *
 * @param isOfferPersonalizedDefault 欧盟个性化报价默认值。若价格经自动化决策对用户个性化，
 * 应在购买时声明；也可在单次 [com.kit.pay.PayKit.purchase] 中覆盖。
 */
data class PayKitConfiguration(
    val subsProductIds: Set<String> = emptySet(),
    val consumableProductIds: Set<String> = emptySet(),
    val nonConsumableProductIds: Set<String> = emptySet(),
    val isOfferPersonalizedDefault: Boolean = false
)
