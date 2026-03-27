package com.kit.pay.base

/**
 * 用于通知初始化结果的回调接口。
 */
interface InitializationCallback {

    fun onSuccess()

    fun onFailure(errorCode: PaymentCode)
}
