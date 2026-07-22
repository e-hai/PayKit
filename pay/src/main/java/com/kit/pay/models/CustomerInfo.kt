package com.kit.pay.models

/**
 * 用户权益核心类。
 * 包含计算好的最终状态：活跃权益集合与当前商店可见订单。
 */
data class CustomerInfo(
    val activeSubscriptions: Set<String> = emptySet(),
    val nonConsumablePurchases: Set<String> = emptySet(),
    /**
     * 当前商店查询快照（订阅 / 非消耗 / 未 consume 的消耗 / PENDING）。
     * 不再无限合并历史；已 consume 的消耗品以 [ConsumableLedger] 为准跟踪。
     */
    val allPurchaseRecords: List<StoreTransaction> = emptyList(),
    /**
     * 消耗品：Google 仍可见、且账本尚未标记「已履约待 consume」。
     * 宿主应发货后调用 [com.kit.pay.PayKit.markConsumableFulfilled]。
     * 购买流程里 SDK 会在 [com.kit.pay.interfaces.PurchaseCallback.onCompleted] 前自动标记。
     */
    val unfulfilledConsumables: List<StoreTransaction> = emptyList()
) {
    /** 待支付确认的订单，勿据此发货 */
    val pendingPurchases: List<StoreTransaction>
        get() = allPurchaseRecords.filter { it.purchaseState == PurchaseState.PENDING }

    /** 已支付成功的订单记录（当前快照） */
    val purchasedRecords: List<StoreTransaction>
        get() = allPurchaseRecords.filter { it.purchaseState == PurchaseState.PURCHASED }
}
