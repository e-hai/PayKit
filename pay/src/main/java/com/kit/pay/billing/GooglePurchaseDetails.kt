package com.kit.pay.billing

import com.kit.pay.base.PaymentPurchaseDetails
import com.kit.pay.base.PaymentPurchaseState


data class GooglePurchaseDetails(
    private val orderId: String,
    private val purchaseState: PaymentPurchaseState,
    private val products: List<String>,
    private val purchaseToken: String = "",
    private val isAcknowledged: Boolean = false
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

    /**
     * 获取购买令牌，用于确认或消费订单
     */
    fun getPurchaseToken(): String {
        return purchaseToken
    }

    /**
     * 检查订单是否已确认（针对订阅和一次性非消耗商品）
     */
    fun isAcknowledged(): Boolean {
        return isAcknowledged
    }
}
