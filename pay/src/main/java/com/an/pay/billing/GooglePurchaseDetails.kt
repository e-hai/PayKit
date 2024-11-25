package com.an.pay.billing

import com.an.pay.base.PaymentPurchaseDetails
import com.an.pay.base.PaymentPurchaseState


data class GooglePurchaseDetails(
    private val orderId: String,
    private val purchaseState: PaymentPurchaseState,
    private val products: List<String>
) : PaymentPurchaseDetails {

    override fun getOrderId(): String {
        return orderId
    }

    override fun getPurchaseState(): PaymentPurchaseState {
        return purchaseState
    }

    override fun getProducts(): List<String> {
        return products
    }
}
