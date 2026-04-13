package com.kit.pay.models

import com.kit.pay.models.StoreTransaction

/**
 * 等同于 RevenueCat 的核心类 CustomerInfo。
 * 它包含所有计算好的最终状态：该设备拥有的权益集合，购买记录集合。
 */
data class CustomerInfo(
    val activeSubscriptions: Set<String> = emptySet(),
    val nonConsumablePurchases: Set<String> = emptySet(),
    // 包含历史所有已记录的原始凭证（含断网恢复且被本地消费掉的消耗品）
    val allPurchaseRecords: List<StoreTransaction> = emptyList()
)
