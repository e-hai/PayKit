package com.kit.pay.base

/**
 * 商品详情数据类，包含支付商品的基础信息。
 *
 * @param productId 商品的唯一标识符
 * @param title 商品标题
 * @param description 商品描述信息
 * @param productType 商品类型（一次性商品或订阅商品）
 */
data class PaymentProductDetails(
    val productId: String,
    val title: String,
    val description: String,
    val productType: PaymentProductType
)