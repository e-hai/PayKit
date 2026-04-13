package com.kit.pay.subscriber

import com.kit.pay.models.CustomerInfo
import com.kit.pay.models.PayKitConfiguration
import com.kit.pay.models.StoreTransaction

/**
 * 等同于 RevenueCat 的 Backend/DeviceCache Helper
 * 处理本地化权益的核心逻辑中枢。在本地无后端情况下，依据 Config 将 Purchase 归纳整理。
 */
class CustomerInfoHelper(
    private val config: PayKitConfiguration
) {

    /**
     * 将底层上报的订单进行解析、组装成最终版的 CustomerInfo
     */
    fun computeCustomerInfo(
        cachedInfo: CustomerInfo?,
        validTransactions: List<StoreTransaction>
    ): CustomerInfo {

        val activeSubscriptions = mutableSetOf<String>()
        val nonConsumablePurchases = mutableSetOf<String>()

        // 为了保存被 Google 消化而丢失返回的 Consumable 订单历史，合并缓存内已存在的凭证
        val mergedRecordsMap = LinkedHashMap<String, StoreTransaction>()
        cachedInfo?.allPurchaseRecords?.forEach { 
            mergedRecordsMap[it.purchaseToken] = it 
        }

        // 处理最新一波从 Google 拉取回来的记录（或新交易的回调）
        for (txn in validTransactions) {
            mergedRecordsMap[txn.purchaseToken] = txn
            
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
