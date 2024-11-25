package com.an.pay.base

/**
 * 购买状态枚举，描述购买订单的当前状态。
 */
enum class PaymentPurchaseState {
    UNSPECIFIED_STATE, // 未指明的状态，可能表示未知或无效的订单状态
    PURCHASED,         // 已支付，用户成功完成支付流程
    PENDING            // 待处理，订单已创建但尚未支付或确认
}