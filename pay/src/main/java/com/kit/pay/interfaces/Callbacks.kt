package com.kit.pay.interfaces

import com.kit.pay.models.CustomerInfo
import com.kit.pay.models.StoreProduct
import com.kit.pay.models.StoreTransaction

interface InitializationCallback {
    fun onSuccess(customerInfo: CustomerInfo)
    fun onError(error: PayKitError)
}

interface PurchaseCallback {
    fun onCompleted(storeTransaction: StoreTransaction, customerInfo: CustomerInfo)
    fun onError(error: PayKitError, userCancelled: Boolean)
}

/**
 * 对应 RevenueCat 的 PurchaserInfoUpdatedListener / UpdatedCustomerInfoListener
 */
interface UpdatedCustomerInfoListener {
    fun onReceived(customerInfo: CustomerInfo)
}
