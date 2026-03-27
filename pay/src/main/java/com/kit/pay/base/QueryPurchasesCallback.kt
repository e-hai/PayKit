package com.kit.pay.base

/**
 * 查询购买记录结果回调接口，用于通知购买记录查询的结果。
 */
interface QueryPurchasesCallback {

    /**
     * 当购买记录查询成功时调用。
     *
     * @param products 包含查询结果的购买详情列表。
     */
    fun onQuerySuccess(products: List<PaymentPurchaseDetails>)

    /**
     * 当购买记录查询失败时调用。
     */
    fun onQueryFailure(errorCode: PaymentCode)
}