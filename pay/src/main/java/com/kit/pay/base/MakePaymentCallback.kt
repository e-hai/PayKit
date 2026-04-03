package com.kit.pay.base

/**
 * 发起支付结果封装类
 */
data class MakePaymentResult(
    val isSuccess: Boolean,
    val errorCode: PaymentCode? = null
)
