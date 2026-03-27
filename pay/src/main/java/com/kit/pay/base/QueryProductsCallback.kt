package com.kit.pay.base


/**
 * 查询商品结果回调接口，用于通知商品查询的结果。
 */
interface QueryProductsCallback {

    /**
     * 当商品查询完成时调用。
     *
     * @param products 成功获取到的商品详情列表。
     * @param unfetchedProductIds 未获取到的商品 ID 列表（例如 ID 不存在或区域限制）。
     */
    fun onQuerySuccess(
        products: List<PaymentProductDetails>,
        unfetchedProductIds: List<String> = emptyList()
    )

    /**
     * 当商品查询流程发生底层错误时调用。
     */
    fun onQueryFailure(errorCode: PaymentCode)
}