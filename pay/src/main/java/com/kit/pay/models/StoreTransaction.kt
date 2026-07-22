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
 *
 * 订阅相关：[isAutoRenewing] / [isSuspended] 来自客户端 Purchase；
 * 精确到期时间、宽限期仍需服务端 Play Developer API / RTDN。
 */
data class StoreTransaction(
    val orderId: String,
    val productIds: List<String>,
    val purchaseTime: Long,
    val purchaseToken: String,
    val isAcknowledged: Boolean,
    val purchaseState: PurchaseState = PurchaseState.PURCHASED,
    /** 订阅是否仍自动续订；非订阅多为 false */
    val isAutoRenewing: Boolean = false,
    /** 订阅是否处于暂停（如账号保留相关）；非订阅多为 false */
    val isSuspended: Boolean = false,
    /**
     * Play 返回的订单签名（Base64）。服务端可用 [originalJson] + 本字段做本地签名校验；
     * 推荐仍以 Google Play Developer API 验 `purchaseToken` 为准。
     */
    val signature: String = "",
    /** Play 返回的原始购买 JSON，配合 [signature] 供服务端验签 */
    val originalJson: String = ""
)
