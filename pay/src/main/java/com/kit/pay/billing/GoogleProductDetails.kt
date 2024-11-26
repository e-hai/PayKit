package com.kit.pay.billing

import com.kit.pay.base.PaymentProductDetails
import com.kit.pay.base.PaymentProductType


class GoogleProductDetails(
    private val productId: String,
    private val title: String,
    private val description: String,
    private val productType: PaymentProductType,
) : PaymentProductDetails {


    override fun getProductId(): String {
        return productId
    }

    override fun getTitle(): String {
        return title
    }

    override fun getDescription(): String {
        return description
    }

    override fun getProductType(): PaymentProductType {
        return productType
    }
}