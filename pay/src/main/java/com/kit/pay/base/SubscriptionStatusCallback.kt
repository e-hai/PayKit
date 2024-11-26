package com.kit.pay.base

/**
 * 订阅状态查询结果回调接口，用于通知订阅状态的查询结果。
 */
interface SubscriptionStatusCallback {

    /**
     * 当订阅状态查询成功时调用。
     *
     * @param isSubscribed 一个布尔值，表示用户是否拥有有效订阅。
     *                     - `true`：用户已订阅。
     *                     - `false`：用户未订阅。
     */
    fun onStatusReceived(isSubscribed: Boolean)

    /**
     * 当订阅状态查询失败时调用。
     *
     * @param errorCode 表示失败原因的错误代码。
     */
    fun onStatusError(errorCode: PaymentErrorCode)
}