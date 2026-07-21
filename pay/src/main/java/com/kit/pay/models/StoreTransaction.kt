package com.kit.pay.models

/**
 * Google Play 订单支付状态。
 *
 * 对应 [com.android.billingclient.api.Purchase.PurchaseState]。
 */
enum class PurchaseState {
    /** 已支付成功，可确认/消耗并发放权益 */
    PURCHASED,
    /** 待处理（如现金/银行转账未完成），不可发货、不可 acknowledge */
    PENDING,
    /** 未指定/无效状态 */
    UNSPECIFIED
}

/**
 * 一笔购买交易的封装。
 */
data class StoreTransaction(
    val orderId: String,
    val productIds: List<String>,
    val purchaseTime: Long,
    val purchaseToken: String,
    val isAcknowledged: Boolean,
    val purchaseState: PurchaseState = PurchaseState.PURCHASED
)
