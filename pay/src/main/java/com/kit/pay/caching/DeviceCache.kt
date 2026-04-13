package com.kit.pay.caching

import android.content.Context
import android.content.SharedPreferences
import com.kit.pay.models.CustomerInfo
import com.kit.pay.models.StoreTransaction
import org.json.JSONArray
import org.json.JSONObject

/**
 * 等同于 RevenueCat 的 DeviceCache。
 * 负责本地状态持久化，实现弱网环境秒开发放权益的刚需。
 */
class DeviceCache(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "PayKit_DeviceCache",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_CUSTOMER_INFO = "cached_customer_info"
    }

    /**
     * 将当前计算好的状态直接压缩进硬盘
     */
    fun cacheCustomerInfo(info: CustomerInfo) {
        val rootObj = JSONObject()
        
        // Serialize active subscriptions
        val subsArray = JSONArray()
        info.activeSubscriptions.forEach { subsArray.put(it) }
        rootObj.put("activeSubscriptions", subsArray)
        
        // Serialize non consumable
        val inappArray = JSONArray()
        info.nonConsumablePurchases.forEach { inappArray.put(it) }
        rootObj.put("nonConsumablePurchases", inappArray)
        
        // Serialize all purchase records
        val recordsArray = JSONArray()
        info.allPurchaseRecords.forEach { txn ->
            val txnObj = JSONObject()
            txnObj.put("orderId", txn.orderId)
            
            val idsArray = JSONArray()
            txn.productIds.forEach { idsArray.put(it) }
            txnObj.put("productIds", idsArray)
            
            txnObj.put("purchaseTime", txn.purchaseTime)
            txnObj.put("purchaseToken", txn.purchaseToken)
            txnObj.put("isAcknowledged", txn.isAcknowledged)
            recordsArray.put(txnObj)
        }
        rootObj.put("allPurchaseRecords", recordsArray)

        prefs.edit().putString(KEY_CUSTOMER_INFO, rootObj.toString()).apply()
    }

    /**
     * 读取并还原 CustomerInfo
     */
    fun getCachedCustomerInfo(): CustomerInfo? {
        val jsonString = prefs.getString(KEY_CUSTOMER_INFO, null) ?: return null
        return try {
            val rootObj = JSONObject(jsonString)
            
            val activeSubscriptions = mutableSetOf<String>()
            val subsArray = rootObj.optJSONArray("activeSubscriptions")
            if (subsArray != null) {
                for (i in 0 until subsArray.length()) {
                    activeSubscriptions.add(subsArray.getString(i))
                }
            }
            
            val nonConsumablePurchases = mutableSetOf<String>()
            val inappArray = rootObj.optJSONArray("nonConsumablePurchases")
            if (inappArray != null) {
                for (i in 0 until inappArray.length()) {
                    nonConsumablePurchases.add(inappArray.getString(i))
                }
            }
            
            val allPurchaseRecords = mutableListOf<StoreTransaction>()
            val recordsArray = rootObj.optJSONArray("allPurchaseRecords")
            if (recordsArray != null) {
                for (i in 0 until recordsArray.length()) {
                    val txnObj = recordsArray.getJSONObject(i)
                    
                    val pIds = mutableListOf<String>()
                    val idsArray = txnObj.optJSONArray("productIds")
                    if (idsArray != null) {
                        for (j in 0 until idsArray.length()) {
                            pIds.add(idsArray.getString(j))
                        }
                    }
                    
                    allPurchaseRecords.add(
                        StoreTransaction(
                            orderId = txnObj.optString("orderId", ""),
                            productIds = pIds,
                            purchaseTime = txnObj.optLong("purchaseTime", 0L),
                            purchaseToken = txnObj.optString("purchaseToken", ""),
                            isAcknowledged = txnObj.optBoolean("isAcknowledged", true)
                        )
                    )
                }
            }
            
            CustomerInfo(
                activeSubscriptions = activeSubscriptions,
                nonConsumablePurchases = nonConsumablePurchases,
                allPurchaseRecords = allPurchaseRecords
            )
        } catch (e: Exception) {
            null
        }
    }

    fun clearCache() {
        prefs.edit().clear().apply()
    }
}
