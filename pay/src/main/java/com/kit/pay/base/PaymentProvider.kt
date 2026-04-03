package com.kit.pay.base

import android.app.Activity
import java.lang.ref.WeakReference

/**
 * 消费消耗型商品结果封装类
 */
data class PaymentConsumeResult(
    val isSuccess: Boolean,
    val errorCode: PaymentCode? = null,
    val purchaseToken: String? = null,
    val message: String? = null
)

/**
 * 确认订单结果封装类
 */
data class PaymentAcknowledgeResult(
    val isSuccess: Boolean,
    val errorCode: PaymentCode? = null,
    val message: String? = null
)

/**
 * 支付提供者接口，定义支付服务的核心操作。
 * 使用协程风格，所有异步操作都通过 suspend 函数返回结果
 */
interface PaymentProvider {

    /**
     * 初始化支付提供者。
     *
     * @param callback 初始化结果回调。
     */
    fun initialize(callback: InitializationCallback)

    /**
     * 设置购买更新监听器，用于上报新发现的购买订单。
     *
     * @param listener 购买订单回调。
     */
    fun setPurchaseListener(listener: PurchaseCallback)

    /**
     * 查询指定商品的详情信息。
     *
     * @param products 商品列表，包含 ID 和类型。
     * @return 查询结果。
     */
    suspend fun queryProducts(
        products: List<Pair<String, PaymentProductType>>
    ): QueryProductsResult

    /**
     * 查询用户已购买的商品信息。
     *
     * @param productType 商品类型（一次性或订阅）。
     * @return 查询结果。
     */
    suspend fun queryPurchases(
        productType: PaymentProductType
    ): QueryPurchasesResult

    /**
     * 发起支付流程。
     *
     * @param activityRef 当前操作所在的 Activity 的弱引用（防止内存泄漏）。
     * @param key 订单标识，用于跟踪支付结果。（google play billing 不提供，得开发者自身生成）
     * @param productId 商品 ID。
     * @param productType 商品类型。
     * @param offerId 优惠方案 ID。
     * @return 支付结果。
     */
    suspend fun makePayment(
        activityRef: WeakReference<Activity>,
        key: String,
        productType: PaymentProductType,
        productId: String,
        offerId: String
    ): MakePaymentResult

    /**
     * 确认订阅或非消耗商品订单。
     *
     * @return 确认结果。
     */
    suspend fun acknowledgePurchase(purchaseToken: String): PaymentAcknowledgeResult

    /**
     * 消费消耗型商品订单。
     *
     * @return 消费结果。
     */
    suspend fun consumePurchase(purchaseToken: String): PaymentConsumeResult

    /**
     * 清理资源，防止内存泄漏。
     * **/
    fun cleanup()
}