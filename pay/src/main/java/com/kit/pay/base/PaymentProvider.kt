package com.kit.pay.base

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
     * 设置购买更新监听器，用于上报新发现的购买订单。
     *
     * @param listener 购买订单回调。
     */
    fun setPurchaseListener(listener: PurchaseCallback)

    /**
     * 查询指定商品的详情信息。
     *
     * @param products 商品列表，包含 ID 和类型。
     * @param callback 查询结果回调。
     */
    fun queryProducts(
        products: List<Pair<String, PaymentProductType>>,
        callback: QueryProductsCallback
    )

    /**
     * 查询用户已购买的商品信息。
     *
     * @param productType 商品类型（一次性或订阅）。
     * @param callback 查询结果回调。
     */
    fun queryPurchases(
        productType: PaymentProductType,
        callback: QueryPurchasesCallback
    )

    /**
     * 发起支付流程。
     *
     * @param activity 当前操作所在的 Activity。
     * @param key 订单标识，用于跟踪支付结果。（google play billing不提供，得开发者自身生成）
     * @param productId 商品 ID。
     * @param productType 商品类型。
     * @param offerId 优惠方案 ID。
     * @param callback 支付结果回调。
     */
    fun makePayment(
        activity: Activity,
        key: String,
        productType: PaymentProductType,
        productId: String,
        offerId: String,
        callback: MakePaymentCallback
    )

    /**
     * 确认订阅或非消耗商品订单。
     */
    fun acknowledgePurchase(purchaseToken: String, callback: (Boolean) -> Unit)

    /**
     * 消费消耗型商品订单。
     */
    fun consumePurchase(purchaseToken: String, callback: (Boolean) -> Unit)

    /**
     * 清理资源，防止内存泄漏。
     * **/
    fun cleanup()
}