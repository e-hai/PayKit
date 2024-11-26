package com.kit.pay.billing

import com.kit.pay.base.PaymentPurchaseDetails
import com.kit.pay.base.PaymentPurchaseState


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
