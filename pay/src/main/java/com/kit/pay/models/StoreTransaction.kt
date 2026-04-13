package com.kit.pay.models

/**
 * 对应 RevenueCat 的 StoreTransaction。
 * 代表着一笔成功发生的交易记录，隐藏了底下复杂的 JSON 或者签名状态。
 */
data class StoreTransaction(
    val orderId: String,
    val productIds: List<String>,
    val purchaseTime: Long,
    val purchaseToken: String,
    val isAcknowledged: Boolean
)
