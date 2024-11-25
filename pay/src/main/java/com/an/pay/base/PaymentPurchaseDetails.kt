package com.an.pay.base

/**
 * 购买详情接口，描述用户已完成的购买信息。
 */
interface PaymentPurchaseDetails {

    /**
     * 获取订单 ID。
     *
     * @return 订单的唯一标识符。
     */
    fun getOrderId(): String

    /**
     * 获取购买状态。
     *
     * @return 购买状态（成功、待处理或已取消）。
     */
    fun getPurchaseState(): PaymentPurchaseState

    /**
     * 获取此购买包含的商品 ID 列表。
     *
     * @return 商品 ID 列表。
     */
    fun getProducts(): List<String>
}