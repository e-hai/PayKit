package com.an.pay.base

import android.app.Activity
/**
 * 支付提供者接口，定义支付服务的核心操作。
 */
interface PaymentProvider {

    /**
     * 初始化支付提供者。
     *
     * @param callback 初始化结果回调。
     */
    fun initialize(callback: InitializationCallback)


    /**
     * 查询指定商品的详情信息。
     *
     * @param productIds 商品 ID 列表。
     * @param callback 查询结果回调。
     */
    fun queryProducts(
        productIds: List<String>,
        callback: QueryProductsCallback
    )

    /**
     * 查询用户已购买的商品信息。
     *
     * @param paymentProductType 商品类型（一次性或订阅）。
     * @param callback 查询结果回调。
     */
    fun queryPurchases(
        paymentProductType: PaymentProductType,
        callback: QueryPurchasesCallback
    )

    /**
     * 检查用户的订阅状态。
     *
     * @param callback 订阅状态结果回调。
     */
    fun checkSubscriptionStatus(callback: SubscriptionStatusCallback)

    /**
     * 发起支付流程。
     *
     * @param activity 当前操作所在的 Activity。
     * @param productId 商品 ID。
     * @param offerId 优惠方案 ID。
     * @param callback 支付结果回调。
     */
    fun makePayment(
        activity: Activity,
        productId: String,
        offerId: String,
        callback: PaymentCallback
    )
}