package com.kit.pay.subscriber

import com.kit.pay.models.PayKitConfiguration
import com.kit.pay.models.PurchaseState
import com.kit.pay.models.StoreTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerInfoHelperTest {

    private val helper = CustomerInfoHelper(
        PayKitConfiguration(
            subsProductIds = setOf("subs_plus", "subs_pro"),
            consumableProductIds = setOf("coins"),
            nonConsumableProductIds = setOf("skin")
        )
    )

    @Test
    fun `only purchased and acknowledged subs become active`() {
        val info = helper.computeCustomerInfo(
            storeTransactions = listOf(
                txn("t1", listOf("subs_plus"), PurchaseState.PURCHASED, acknowledged = true),
                txn("t2", listOf("subs_pro"), PurchaseState.PENDING, acknowledged = false),
                txn("t3", listOf("skin"), PurchaseState.PURCHASED, acknowledged = true)
            )
        )
        assertEquals(setOf("subs_plus"), info.activeSubscriptions)
        assertEquals(setOf("skin"), info.nonConsumablePurchases)
        assertEquals(1, info.pendingPurchases.size)
        assertEquals(3, info.allPurchaseRecords.size)
    }

    @Test
    fun `uses store snapshot without merging old cache history`() {
        val info = helper.computeCustomerInfo(
            storeTransactions = listOf(
                txn("new", listOf("subs_pro"), PurchaseState.PURCHASED, acknowledged = true)
            )
        )
        assertEquals(1, info.allPurchaseRecords.size)
        assertEquals(setOf("subs_pro"), info.activeSubscriptions)
    }

    @Test
    fun `unfulfilled consumables listed until ledger marks fulfilled`() {
        val coins = txn("c1", listOf("coins"), PurchaseState.PURCHASED, acknowledged = false)
        val pending = helper.computeCustomerInfo(
            storeTransactions = listOf(coins),
            fulfilledConsumableTokens = emptySet()
        )
        assertEquals(1, pending.unfulfilledConsumables.size)

        val fulfilled = helper.computeCustomerInfo(
            storeTransactions = listOf(coins),
            fulfilledConsumableTokens = setOf("c1")
        )
        assertTrue(fulfilled.unfulfilledConsumables.isEmpty())
    }

    private fun txn(
        token: String,
        products: List<String>,
        state: PurchaseState,
        acknowledged: Boolean
    ) = StoreTransaction(
        orderId = "o-$token",
        productIds = products,
        purchaseTime = 1L,
        purchaseToken = token,
        isAcknowledged = acknowledged,
        purchaseState = state,
        isAutoRenewing = products.any { it.startsWith("subs_") },
        isSuspended = false
    )
}
