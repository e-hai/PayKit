package com.kit.pay.interfaces

import com.kit.pay.models.CustomerInfo
import com.kit.pay.models.StoreTransaction

/**
 * 单次购买结果回调。
 *
 * - [onCompleted]：已支付（PURCHASED），可发货
 * - [onPending]：待支付确认，勿发货；默认空实现
 * - [onError]：失败或用户取消
 */
interface PurchaseCallback {
    fun onCompleted(storeTransaction: StoreTransaction, customerInfo: CustomerInfo)

    /**
     * 支付待确认（如现金/银行转账）。不要发放权益。
     * 付清后通过 [com.kit.pay.PayKit.syncPurchases] / 启动同步即可补单。
     */
    fun onPending(storeTransaction: StoreTransaction) {}

    fun onError(error: PayKitError, userCancelled: Boolean)
}

/**
 * 用户权益状态变化监听器。
 * 当订阅状态或购买记录发生变化时回调。
 */
interface UpdatedCustomerInfoListener {
    fun onReceived(customerInfo: CustomerInfo)
}
