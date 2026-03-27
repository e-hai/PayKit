package com.kit.pay.base

/**
 * 支付结果回调接口，用于通知支付流程的结果。
 */
interface PaymentCallback {

    /**
     * 当支付成功时调用。
     * 该方法表示支付流程已经完成，用户成功购买了商品或订阅。
     */
    fun onSuccess()

    /**
     * 当支付失败时调用。
     *
     * @param errorCode 表示支付失败的错误代码，提供详细的错误信息。
     *                  常见错误代码可能包括：
     *                  - 网络连接失败
     *                  - 用户取消支付
     *                  - 支付提供者的服务错误
     *                  - 未知错误
     */
    fun onFailure(errorCode: PaymentCode)
}
