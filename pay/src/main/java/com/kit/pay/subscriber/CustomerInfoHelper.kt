package com.kit.pay.subscriber

import com.kit.pay.models.CustomerInfo
import com.kit.pay.models.PayKitConfiguration
import com.kit.pay.models.PurchaseState
import com.kit.pay.models.StoreTransaction

/**
 * 处理本地化权益的核心逻辑中枢。在本地无后端情况下，依据 Config 将 Purchase 归纳整理。
 */
class CustomerInfoHelper(
    private val config: PayKitConfiguration
) {

    /**
     * 用**当前商店查询结果**组装 [CustomerInfo]（不做无限历史合并）。
     *
     * - [CustomerInfo.allPurchaseRecords] = 本次查询快照
     * - 活跃权益仅来自 `PURCHASED` 且已 acknowledge 的订阅 / 非消耗
     * - [CustomerInfo.unfulfilledConsumables] = 已支付、未 consume、且不在 [fulfilledConsumableTokens] 中的消耗品
     */
    fun computeCustomerInfo(
        storeTransactions: List<StoreTransaction>,
        fulfilledConsumableTokens: Set<String> = emptySet()
    ): CustomerInfo {
        val activeSubscriptions = mutableSetOf<String>()
        val nonConsumablePurchases = mutableSetOf<String>()
        val unfulfilledConsumables = mutableListOf<StoreTransaction>()

        for (txn in storeTransactions) {
            if (txn.purchaseState != PurchaseState.PURCHASED) {
                continue
            }

            val isConsumable =
                txn.productIds.any { config.consumableProductIds.contains(it) }

            if (isConsumable) {
                // 消耗品不进 active* 集合；未进履约账本的需宿主发货
                if (!txn.isAcknowledged &&
                    txn.purchaseToken !in fulfilledConsumableTokens
                ) {
                    unfulfilledConsumables.add(txn)
                }
                continue
            }

            if (!txn.isAcknowledged) {
                continue
            }

            for (productId in txn.productIds) {
                if (config.subsProductIds.contains(productId)) {
                    activeSubscriptions.add(productId)
                } else if (config.nonConsumableProductIds.contains(productId)) {
                    nonConsumablePurchases.add(productId)
                }
            }
        }

        return CustomerInfo(
            activeSubscriptions = activeSubscriptions,
            nonConsumablePurchases = nonConsumablePurchases,
            allPurchaseRecords = storeTransactions,
            unfulfilledConsumables = unfulfilledConsumables
        )
    }

    fun isConsumable(transaction: StoreTransaction): Boolean =
        transaction.productIds.any { config.consumableProductIds.contains(it) }
}
