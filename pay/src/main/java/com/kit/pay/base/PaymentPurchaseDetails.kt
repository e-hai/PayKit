package com.kit.pay.base

/**
 * 购买详情数据类，描述用户已完成的购买信息。
 *
 * @param key 开发者自定义的支付请求标识（建议使用 UUID）
 * @param orderId 支付平台生成的订单 ID
 * @param purchaseState 购买状态（成功、待处理或已取消）
 * @param products 商品 ID 列表
 * @param purchaseToken 购买令牌，用于确认或消费订单
 * @param isAcknowledged 订单是否已确认（针对订阅和一次性非消耗商品）
 */
data class PaymentPurchaseDetails(
    val key: String,
    val orderId: String,
    val purchaseState: PaymentPurchaseState,
    val products: List<String>,
    val purchaseToken: String = "",
    val isAcknowledged: Boolean = false
)