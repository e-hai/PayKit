package com.kit.pay.models

/**
 * 替代 RevenueCat 后端的配置文件。
 * 因为我们是纯本地，必须让开发者初始化时告诉 SDK：
 * 哪些是订阅，哪些是非消耗品。以防底层恢复订单时无法分辨发货模型。
 */
data class PayKitConfiguration(
    val subsProductIds: Set<String> = emptySet(),
    val consumableProductIds: Set<String> = emptySet(),
    val nonConsumableProductIds: Set<String> = emptySet()
)
