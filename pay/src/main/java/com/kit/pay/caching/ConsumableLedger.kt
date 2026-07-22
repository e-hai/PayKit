package com.kit.pay.caching

import android.content.Context
import android.content.SharedPreferences
import com.kit.pay.models.ConsumableLedgerEntry
import com.kit.pay.models.ConsumableLedgerStatus
import com.kit.pay.models.StoreTransaction
import org.json.JSONArray
import org.json.JSONObject

/**
 * 消耗品履约账本（仅落盘）。
 *
 * 订阅 / 非消耗权益仍由 [DeviceCache] 的 [com.kit.pay.models.CustomerInfo] 缓存；
 * 本账本只跟踪「已履约、待 consume」的消耗品 token。
 */
class ConsumableLedger(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun getAll(): List<ConsumableLedgerEntry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val productIds = mutableListOf<String>()
                    val ids = obj.optJSONArray("productIds")
                    if (ids != null) {
                        for (j in 0 until ids.length()) {
                            productIds.add(ids.getString(j))
                        }
                    }
                    add(
                        ConsumableLedgerEntry(
                            purchaseToken = obj.getString("purchaseToken"),
                            productIds = productIds,
                            orderId = obj.optString("orderId", ""),
                            purchaseTime = obj.optLong("purchaseTime", 0L),
                            status = runCatching {
                                ConsumableLedgerStatus.valueOf(obj.getString("status"))
                            }.getOrDefault(ConsumableLedgerStatus.FULFILLED_PENDING_CONSUME),
                            updatedAt = obj.optLong("updatedAt", 0L)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun find(purchaseToken: String): ConsumableLedgerEntry? =
        getAll().find { it.purchaseToken == purchaseToken }

    fun isFulfilledPendingConsume(purchaseToken: String): Boolean =
        find(purchaseToken)?.status == ConsumableLedgerStatus.FULFILLED_PENDING_CONSUME

    fun markFulfilledPendingConsume(transaction: StoreTransaction) {
        val entry = ConsumableLedgerEntry(
            purchaseToken = transaction.purchaseToken,
            productIds = transaction.productIds,
            orderId = transaction.orderId,
            purchaseTime = transaction.purchaseTime,
            status = ConsumableLedgerStatus.FULFILLED_PENDING_CONSUME
        )
        upsert(entry)
    }

    fun remove(purchaseToken: String) {
        val next = getAll().filterNot { it.purchaseToken == purchaseToken }
        saveAll(next)
    }

    fun clear() {
        prefs.edit().remove(KEY_ENTRIES).apply()
    }

    private fun upsert(entry: ConsumableLedgerEntry) {
        val map = LinkedHashMap<String, ConsumableLedgerEntry>()
        getAll().forEach { map[it.purchaseToken] = it }
        map[entry.purchaseToken] = entry
        saveAll(map.values.toList())
    }

    private fun saveAll(entries: List<ConsumableLedgerEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            val obj = JSONObject()
            obj.put("purchaseToken", entry.purchaseToken)
            val ids = JSONArray()
            entry.productIds.forEach { ids.put(it) }
            obj.put("productIds", ids)
            obj.put("orderId", entry.orderId)
            obj.put("purchaseTime", entry.purchaseTime)
            obj.put("status", entry.status.name)
            obj.put("updatedAt", entry.updatedAt)
            array.put(obj)
        }
        prefs.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "PayKit_ConsumableLedger"
        private const val KEY_ENTRIES = "entries"
    }
}
