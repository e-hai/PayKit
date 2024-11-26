package com.kit.pay.base

/**
 * 商品详情接口，定义支付商品的基础信息。
 */
interface PaymentProductDetails {

    /**
     * 获取商品的唯一标识符。
     *
     * @return 商品 ID
     */
    fun getProductId(): String

    /**
     * 获取商品的标题。
     *
     * @return 商品标题
     */
    fun getTitle(): String

    /**
     * 获取商品的描述信息。
     *
     * @return 商品描述
     */
    fun getDescription(): String

    /**
     * 获取商品的类型。
     *
     * @return 商品类型（一次性商品或订阅商品）
     */
    fun getProductType(): PaymentProductType
}