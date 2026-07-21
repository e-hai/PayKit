package com.kit.pay.models

/**
 * 用户权益核心类。
 * 包含计算好的最终状态：活跃权益集合与购买记录。
 */
data class CustomerInfo(
    val activeSubscriptions: Set<String> = emptySet(),
    val nonConsumablePurchases: Set<String> = emptySet(),
    /** 历史/当前记录（含 PENDING；消耗品消耗后仍可能留在本地合并历史中） */
    val allPurchaseRecords: List<StoreTransaction> = emptyList()
) {
    /** 待支付确认的订单，勿据此发货 */
    val pendingPurchases: List<StoreTransaction>
        get() = allPurchaseRecords.filter { it.purchaseState == PurchaseState.PENDING }

    /** 已支付成功的订单记录 */
    val purchasedRecords: List<StoreTransaction>
        get() = allPurchaseRecords.filter { it.purchaseState == PurchaseState.PURCHASED }
}
