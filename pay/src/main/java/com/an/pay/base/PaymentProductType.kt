package com.an.pay.base

/**
 * 支付商品类型枚举，描述商品是一次性购买还是订阅。
 */
enum class PaymentProductType {
    INAPP, // 一次性商品
    SUBS   // 订阅商品
}