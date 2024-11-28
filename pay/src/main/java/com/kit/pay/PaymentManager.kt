package com.kit.pay


import android.app.Activity
import android.util.Log
import com.kit.pay.base.InitializationCallback
import com.kit.pay.base.PaymentCallback
import com.kit.pay.base.PaymentErrorCode
import com.kit.pay.base.PaymentProductType
import com.kit.pay.base.PaymentProvider
import com.kit.pay.base.QueryProductsCallback
import com.kit.pay.base.QueryPurchasesCallback
import com.kit.pay.base.SubscriptionStatus
import com.kit.pay.base.SubscriptionStatusCallback


/**
 * 管理支付逻辑的单例类。负责初始化支付提供者并处理支付、订阅状态查询、商品查询等操作。
 * 支持延迟任务队列，在初始化完成后处理待处理任务。
 */
class PaymentManager private constructor() {

    // 当前使用的支付提供者
    private lateinit var paymentProvider: PaymentProvider

    // 是否已成功初始化支付提供者
    @Volatile
    private var isInitialized = false

    // 存储待处理的任务队列（在初始化完成之前需要执行的操作）
    private val pendingPayments = mutableListOf<Runnable>()

    // 订阅状态，默认为未初始化状态
    private var subscriptionStatus = SubscriptionStatus.NOT_INITIALIZED

    /**
     * 设置支付提供者并触发初始化。
     *
     * @param provider 实现了 PaymentProvider 接口的支付提供者。
     */
    fun setPaymentProvider(provider: PaymentProvider) {
        paymentProvider = provider
        initializeProvider()
    }

    /**
     * 初始化支付提供者。
     * 初始化完成后回调会通知初始化是否成功，并根据结果处理逻辑。
     */
    private fun initializeProvider() {
        isInitialized = false
        paymentProvider.initialize(object : InitializationCallback {
            override fun onSuccess() {
                isInitialized = true
                // 初始化成功，处理待处理任务
                processPendingPayments()
            }

            override fun onFailure(errorCode: PaymentErrorCode) {
                isInitialized = false
                // 初始化失败的处理逻辑
                handleInitializationFailure()
            }
        })
    }

    /**
     * 处理待处理的支付任务队列。
     */
    private fun processPendingPayments() {
        while (pendingPayments.isNotEmpty()) {
            pendingPayments.removeFirstOrNull()?.run()
        }
    }

    /**
     * 初始化失败时的处理逻辑。
     * 可以记录日志、通知用户或清空任务队列。
     */
    private fun handleInitializationFailure() {
        Log.e(TAG, "Payment provider initialization failed.")
        while (pendingPayments.isNotEmpty()) {
            pendingPayments.removeFirstOrNull()?.run()
        }
    }

    /**
     * 检查订阅状态。
     *
     * @param callback 回调返回当前的订阅状态。
     */
    fun checkSubscriptionStatus(callback: (SubscriptionStatus) -> Unit) {
        val paymentTask = Runnable {
            paymentProvider.checkSubscriptionStatus(object : SubscriptionStatusCallback {
                override fun onStatusReceived(isSubscribed: Boolean) {
                    // 根据订阅结果更新订阅状态
                    subscriptionStatus = if (isSubscribed)
                        SubscriptionStatus.SUBSCRIBED
                    else
                        SubscriptionStatus.NOT_SUBSCRIBED
                    callback.invoke(subscriptionStatus)
                }

                override fun onStatusError(errorCode: PaymentErrorCode) {
                    Log.d(TAG, "checkSubscriptionStatus error=$errorCode")
                    callback.invoke(subscriptionStatus)
                }
            })
        }

        // 如果已初始化，直接运行任务；否则加入待处理任务队列
        if (isInitialized) {
            paymentTask.run()
        } else {
            pendingPayments.add(paymentTask)
        }
    }

    /**
     * 发起支付请求。
     *
     * @param activity 用于支付流程的 Activity。
     * @param productId 商品 ID。
     * @param offerId 优惠方案 ID。
     * @param callback 支付结果回调。
     */
    fun makePayment(
        activity: Activity,
        productId: String,
        offerId: String,
        callback: PaymentCallback
    ) {
        val paymentTask = Runnable {
            paymentProvider.makePayment(activity, productId, offerId, callback)
        }

        // 如果已初始化，直接运行任务；否则加入待处理任务队列
        if (isInitialized) {
            paymentTask.run()
        } else {
            pendingPayments.add(paymentTask)
        }
    }

    /**
     * 查询商品信息。
     *
     * @param productIds 商品 ID 列表。
     * @param callback 查询结果回调。
     */
    fun queryProducts(
        productIds: List<String>,
        callback: QueryProductsCallback
    ) {
        val queryTask = Runnable {
            paymentProvider.queryProducts(productIds, callback)
        }

        // 如果已初始化，直接运行任务；否则加入待处理任务队列
        if (isInitialized) {
            queryTask.run()
        } else {
            pendingPayments.add(queryTask)
        }
    }

    /**
     * 查询已购买的商品。
     *
     * @param productType 商品类型（如订阅或一次性商品）。
     * @param callback 查询结果回调。
     */
    fun queryPurchases(productType: PaymentProductType, callback: QueryPurchasesCallback) {
        val queryTask = Runnable {
            paymentProvider.queryPurchases(productType, callback)
        }

        // 如果已初始化，直接运行任务；否则加入待处理任务队列
        if (isInitialized) {
            queryTask.run()
        } else {
            pendingPayments.add(queryTask)
        }
    }

    companion object {
        const val TAG = "PaymentManager"

        // 单例实例，使用 @Volatile 保证线程安全
        @Volatile
        private var INSTANCE: PaymentManager? = null

        /**
         * 获取 PaymentManager 单例实例。
         *
         * @return PaymentManager 实例。
         */
        fun getInstance(): PaymentManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: PaymentManager().also { INSTANCE = it }
            }
    }
}


























