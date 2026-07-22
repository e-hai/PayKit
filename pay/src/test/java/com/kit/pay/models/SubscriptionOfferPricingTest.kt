package com.kit.pay.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionOfferPricingTest {

    @Test
    fun `empty phases returns empty result`() {
        val result = SubscriptionOfferPricing.fromPhases(emptyList())
        assertEquals("", result.price)
        assertEquals(0L, result.priceAmountMicros)
        assertFalse(result.hasFreeTrial)
        assertNull(result.freeTrialPeriod)
    }

    @Test
    fun `free trial then paid uses paid as display price`() {
        val result = SubscriptionOfferPricing.fromPhases(
            listOf(
                SubscriptionOfferPricing.Phase(0L, "Free", "USD", "P1W"),
                SubscriptionOfferPricing.Phase(9_990_000L, "$9.99", "USD", "P1M")
            )
        )
        assertTrue(result.hasFreeTrial)
        assertEquals("P1W", result.freeTrialPeriod)
        assertEquals("$9.99", result.price)
        assertEquals(9_990_000L, result.priceAmountMicros)
        assertEquals("USD", result.priceCurrencyCode)
    }

    @Test
    fun `paid only offer has no trial`() {
        val result = SubscriptionOfferPricing.fromPhases(
            listOf(
                SubscriptionOfferPricing.Phase(4_990_000L, "$4.99", "USD", "P1M")
            )
        )
        assertFalse(result.hasFreeTrial)
        assertNull(result.freeTrialPeriod)
        assertEquals("$4.99", result.price)
    }
}
