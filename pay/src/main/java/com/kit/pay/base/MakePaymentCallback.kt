package com.kit.pay.base

interface MakePaymentCallback {

    fun onSuccess()

    fun onFailure(errorCode: PaymentCode)
}