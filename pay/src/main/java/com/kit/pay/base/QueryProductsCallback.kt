package com.kit.pay.base

/**
 * 查询商品结果回调接口，用于通知商品查询的结果。
 */
interface QueryProductsCallback {

    /**
     * 当商品查询成功时调用。
     *
     * @param products 包含查询结果的商品详情列表。
     */
    fun onQuerySuccess(products: List<PaymentProductDetails>)

    /**
     * 当商品查询失败时调用。
     */
    fun  onQueryFailure(errorCode: PaymentErrorCode)
}