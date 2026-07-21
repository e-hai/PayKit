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
     * 将底层上报的订单进行解析、组装成最终版的 CustomerInfo。
     *
     * - 所有交易（含 PENDING、未确认）写入 [CustomerInfo.allPurchaseRecords]
     * - 仅 `PURCHASED` 且已 acknowledge/consume 成功的订单计入活跃权益
     */
    fun computeCustomerInfo(
        cachedInfo: CustomerInfo?,
        validTransactions: List<StoreTransaction>
    ): CustomerInfo {

        val activeSubscriptions = mutableSetOf<String>()
        val nonConsumablePurchases = mutableSetOf<String>()

        val mergedRecordsMap = LinkedHashMap<String, StoreTransaction>()
        cachedInfo?.allPurchaseRecords?.forEach {
            mergedRecordsMap[it.purchaseToken] = it
        }

        for (txn in validTransactions) {
            mergedRecordsMap[txn.purchaseToken] = txn

            // 未支付完成，或尚未确认成功：不发权益
            if (txn.purchaseState != PurchaseState.PURCHASED || !txn.isAcknowledged) {
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
            allPurchaseRecords = mergedRecordsMap.values.toList()
        )
    }
}
