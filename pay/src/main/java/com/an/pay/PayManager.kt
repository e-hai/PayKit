package com.an.pay

import android.app.Application
import com.an.pay.billing.BillingDataStorage
import com.an.pay.billing.Subscriber
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PayManager private constructor(
    private val externalScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {


    private lateinit var billingDataStorage: BillingDataStorage

    fun init(app: Application, timeoutSeconds: Int = 5, onSetupFinish: () -> Unit) {
        billingDataStorage = BillingDataStorage.getInstance(
            app,
            LIST_OF_SUBSCRIPTION_PRODUCTS,
            LIST_OF_ONE_TIME_PRODUCTS,
            externalScope
        )
        externalScope.launch(Dispatchers.Main) {
            CountDownTimer.start(timeoutSeconds,
                {
                    billingDataStorage.subscriber != Subscriber.LOADING
                },
                {
                    onSetupFinish.invoke()
                })
        }
    }

    fun isSubs(): Boolean {
        return billingDataStorage.subscriber == Subscriber.YES
    }


    fun queryProducts(listener: ProductListener) {
        billingDataStorage.querySubscriptionProductDetails(listener)
    }

    fun queryPurchases(listener: PurchasesListener) {
        billingDataStorage.querySubsPurchases(listener)
    }


    /**
     * 订单监听
     * **/
    interface PurchasesListener {

        fun onSuccess(data: List<Purchase>)

        fun onFail()
    }

    /**
     * 商品监听
     * **/
    interface ProductListener {

        fun onSuccess(data: List<ProductDetails>)

        fun onFail()
    }

    companion object {
        private const val TAG = "PayManager"

        /**
         * 订阅商品
         * **/
        private val LIST_OF_SUBSCRIPTION_PRODUCTS = listOf(
            Constants.BASIC_PRODUCT,
            Constants.PREMIUM_PRODUCT,
        )

        /**
         * 一次性购买商品
         * **/
        private val LIST_OF_ONE_TIME_PRODUCTS = listOf(
            Constants.ONE_TIME_PRODUCT,
        )

        @Volatile
        private var INSTANCE: PayManager? = null

        fun getInstance(): PayManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: PayManager().also { INSTANCE = it }
            }
    }
}