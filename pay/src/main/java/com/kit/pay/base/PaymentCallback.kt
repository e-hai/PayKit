package com.kit.pay.base

/**
 * 支付结果回调接口，用于通知支付流程的结果。
 * 所有回调方法都会返回订单数据，开发者通过订单中的 key 来判断是哪个订单
 */
interface PaymentCallback {

    /**
     * 当支付成功且自动确认成功时调用。
     * 该方法表示支付流程已经完成，用户已成功购买并获得权益。
     * 
     * @param purchaseDetails 支付购买的详细信息，包含订单 key 等数据
     */
    fun onSuccess(purchaseDetails: PaymentPurchaseDetails)

    /**
     * 当支付成功但自动确认失败时调用。
     * 这种情况表示用户已经付款，但由于网络问题或其他原因导致确认操作失败。
     * 此时应该由开发者重新确认订单
     *
     * @param purchaseDetails 支付购买的详细信息，包含订单 key 等数据
     */
    fun onConfirmFailed(purchaseDetails: PaymentPurchaseDetails)
    
    /**
     * 当支付处于待处理状态时调用。
     * 这种情况通常发生在用户使用延迟支付方式（如线下转账、信用卡分期、银行转账等）时。
     * 支付平台已创建订单，但尚未收到实际款项，需要等待支付平台确认到账。
     *
     * @param purchaseDetails 支付购买的详细信息，包含订单 key 等数据
     */
    fun onPending(purchaseDetails: PaymentPurchaseDetails)

    /**
     * 当用户主动取消支付时调用。
     * 该方法表示用户在支付流程中主动退出或取消了操作。
     * 
     * @param purchaseDetails 支付购买的详细信息，包含订单 key 等数据
     */
    fun onUserCancel(purchaseDetails: PaymentPurchaseDetails)

    /**
     * 当支付失败时调用（不包括用户主动取消的情况）。
     *
     * @param errorCode 表示支付失败的错误代码，提供详细的错误信息。
     *                  常见错误代码可能包括：
     *                  - SERVICE_DISCONNECTED: 网络连接失败
     *                  - SERVICE_UNAVAILABLE: 支付提供者的服务不可用
     *                  - ERROR: 一般错误
     *                  - DEVELOPER_ERROR: 开发者错误
     *                  - BILLING_UNAVAILABLE: 计费不可用
     *                  - ITEM_UNAVAILABLE: 商品不可用
     *                  - FEATURE_NOT_SUPPORTED: 特性不支持
     *                  - ITEM_ALREADY_OWNED: 商品已购买
     *                  - ITEM_NOT_OWNED: 商品未购买
     * @param purchaseDetails 支付购买的详细信息，包含订单 key 等数据
     */
    fun onFailure(errorCode: PaymentCode, purchaseDetails: PaymentPurchaseDetails?)
}
