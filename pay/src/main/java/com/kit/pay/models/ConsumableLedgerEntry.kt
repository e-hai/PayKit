package com.kit.pay.models

/**
 * 消耗品履约账本条目。
 *
 * Google 在 consume 成功后不再返回该单；本地落盘用于：
 * - 已发货后 consume 失败时重试 consume
 * - 避免同一 purchaseToken 重复通知发货
 */
data class ConsumableLedgerEntry(
    val purchaseToken: String,
    val productIds: List<String>,
    val orderId: String,
    val purchaseTime: Long,
    val status: ConsumableLedgerStatus,
    val updatedAt: Long = System.currentTimeMillis()
)

enum class ConsumableLedgerStatus {
    /** 宿主已应发货 / 已发货，等待 Google consume 成功 */
    FULFILLED_PENDING_CONSUME
}
