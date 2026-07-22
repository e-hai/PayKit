package com.kit.pay.interfaces

import com.kit.pay.models.PurchaseState
import com.kit.pay.models.StoreTransaction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class PurchaseVerifierTest {

    @Test
    fun `local verifier always succeeds`() = runBlocking {
        val result = LocalPurchaseVerifier.verify(
            StoreTransaction(
                orderId = "o1",
                productIds = listOf("coins"),
                purchaseTime = 1L,
                purchaseToken = "token",
                isAcknowledged = false,
                purchaseState = PurchaseState.PURCHASED
            )
        )
        assertTrue(result.isSuccess)
    }

    @Test
    fun `custom verifier can reject`() = runBlocking {
        val verifier = PurchaseVerifier {
            Result.failure(PayKitError(ErrorCode.VERIFICATION_FAILED, "server rejected"))
        }
        val result = verifier.verify(
            StoreTransaction(
                orderId = "o1",
                productIds = listOf("coins"),
                purchaseTime = 1L,
                purchaseToken = "token",
                isAcknowledged = false,
                purchaseState = PurchaseState.PURCHASED
            )
        )
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull() as PayKitError
        assertEquals(ErrorCode.VERIFICATION_FAILED, err.code)
    }
}
