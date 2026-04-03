package com.kit.pay.base

/**
 * 查询商品结果封装类
 */
data class QueryProductsResult(
    val products: List<PaymentProductDetails> = emptyList(),
    val unfetchedProductIds: List<String> = emptyList(),
    val errorCode: PaymentCode? = null
)

/**
 * 查询商品结果回调接口，用于通知商品查询的结果。
 * 使用协程风格，通过 Result 类型返回结果
 */
fun interface QueryProductsCallback {
    suspend operator fun invoke(): QueryProductsResult
}