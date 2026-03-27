package com.kit.pay.base

interface PurchaseCallback {
    fun onUpdate(code: PaymentCode, purchaseDetailList: List<PaymentPurchaseDetails>)
}