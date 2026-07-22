package com.kit.pay.models

import com.kit.pay.interfaces.LocalPurchaseVerifier
import com.kit.pay.interfaces.PurchaseVerifier

/**
 * SDK 初始化配置。
 *
 * 商品类型（订阅 / 消耗 / 非消耗）必须由宿主声明，补单时才能正确
 * 选择 acknowledge 或 consume。
 *
 * @param isOfferPersonalizedDefault 欧盟个性化报价默认值。
 * @param purchaseVerifier 验单扩展；默认 [LocalPurchaseVerifier]（不联网）。
 * 接入服务端时传入自定义实现即可，无需改 SDK 发货主流程。
 */
data class PayKitConfiguration(
    val subsProductIds: Set<String> = emptySet(),
    val consumableProductIds: Set<String> = emptySet(),
    val nonConsumableProductIds: Set<String> = emptySet(),
    val isOfferPersonalizedDefault: Boolean = false,
    val purchaseVerifier: PurchaseVerifier = LocalPurchaseVerifier
)
