package com.an.pay


import android.app.Activity
import android.util.Log


class PaymentManager private constructor() {
    private lateinit var paymentProvider: PaymentProvider
    private var isInitialized = false
    private val pendingPayments = mutableListOf<Runnable>()
    private var subscriptionStatus = SubscriptionStatus.NOT_INITIALIZED

    fun setPaymentProvider(provider: PaymentProvider) {
        paymentProvider = provider
        initializeProvider()
    }

    private fun initializeProvider() {
        paymentProvider.initialize(object : InitializationCallback {
            override fun onInitialized(success: Boolean) {
                isInitialized = success
                if (isInitialized) {
                    processPendingPayments()
                } else {
                    handleInitializationFailure()
                }
            }
        })
    }

    private fun processPendingPayments() {
        while (pendingPayments.isNotEmpty()) {
            pendingPayments.removeFirstOrNull()?.run()
        }
    }

    private fun handleInitializationFailure() {
        // 这里可以选择记录日志或者通知用户初始化失败
        Log.e(TAG, "Payment provider initialization failed.")
        while (pendingPayments.isNotEmpty()) {
            pendingPayments.removeFirstOrNull()?.run()
        }
    }

    fun checkSubscriptionStatus(callback: (SubscriptionStatus) -> Unit) {
        val paymentTask = Runnable {
            paymentProvider.checkSubscriptionStatus(object : SubscriptionStatusCallback {
                override fun onStatusReceived(isSubscribed: Boolean) {
                    subscriptionStatus = if (isSubscribed)
                        SubscriptionStatus.SUBSCRIBED
                    else
                        SubscriptionStatus.NOT_SUBSCRIBED
                    callback.invoke(subscriptionStatus)
                }

                override fun onStatusError(paymentErrorCode: PaymentErrorCode) {
                    Log.d(TAG, "checkSubscriptionStatus error=$paymentErrorCode")
                    callback.invoke(subscriptionStatus)
                }
            })
        }
        if (isInitialized) {
            paymentTask.run()
        } else {
            pendingPayments.add(paymentTask)
        }
    }


    fun makePayment(
        activity: Activity,
        productId: String,
        offerId: String,
        callback: PaymentCallback
    ) {
        val paymentTask = Runnable {
            paymentProvider.makePayment(activity, productId, offerId, callback)
        }
        if (isInitialized) {
            paymentTask.run()
        } else {
            pendingPayments.add(paymentTask)
        }
    }

    fun queryProducts(
        productIds: List<String>,
        callback: QueryProductsCallback
    ) {
        val queryTask = Runnable {
            paymentProvider.queryProducts(productIds, callback)
        }
        if (isInitialized) {
            queryTask.run()
        } else {
            pendingPayments.add(queryTask)
        }
    }

    fun queryPurchases(productType: PaymentProductType, callback: QueryPurchasesCallback) {
        val queryTask = Runnable {
            paymentProvider.queryPurchases(productType, callback)
        }
        if (isInitialized) {
            queryTask.run()
        } else {
            pendingPayments.add(queryTask)
        }
    }

    companion object {
        const val TAG = "PaymentManager"

        @Volatile
        private var INSTANCE: PaymentManager? = null

        fun getInstance(): PaymentManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: PaymentManager().also { INSTANCE = it }
            }
    }
}

enum class SubscriptionStatus {
    NOT_INITIALIZED,
    SUBSCRIBED,
    NOT_SUBSCRIBED
}

enum class PaymentPurchaseState {
    UNSPECIFIED_STATE, //未指明的状态
    PURCHASED,         //已支付
    PENDING            //创建订单，但未支付
}

enum class PaymentProductType {
    INAPP, //一次性
    SUBS   //订阅
}

enum class PaymentErrorCode {
    UNABLE_LAUNCH,  //无法启动支付
    USER_CANCELED,  //用户取消
    OTHER           //其他问题
}

interface PaymentProductDetails {
    fun getProductId(): String
    fun getTitle(): String
    fun getDescription(): String
    fun getProductType(): PaymentProductType
}

interface PaymentPurchaseDetails {
    fun getOrderId(): String
    fun getPurchaseState(): PaymentPurchaseState
    fun getProducts(): List<String>
}


interface PaymentProvider {
    fun initialize(callback: InitializationCallback)

    fun checkSubscriptionStatus(callback: SubscriptionStatusCallback)

    fun queryProducts(
        productIds: List<String>,
        callback: QueryProductsCallback
    )

    fun queryPurchases(
        paymentProductType: PaymentProductType,
        callback: QueryPurchasesCallback
    )

    fun makePayment(
        activity: Activity,
        productId: String,
        offerId: String,
        callback: PaymentCallback
    )
}


interface InitializationCallback {
    fun onInitialized(success: Boolean)
}

interface SubscriptionStatusCallback {
    fun onStatusReceived(isSubscribed: Boolean)
    fun onStatusError(paymentErrorCode: PaymentErrorCode)
}

interface QueryProductsCallback {
    fun onQuerySuccess(products: List<PaymentProductDetails>)
    fun onQueryFailure()
}

interface QueryPurchasesCallback {
    fun onQuerySuccess(products: List<PaymentPurchaseDetails>)
    fun onQueryFailure()
}

interface PaymentCallback {
    fun onSuccess()
    fun onFailure(errorCode: PaymentErrorCode)
}
