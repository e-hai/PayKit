package com.kit.pay.base

/**
 * 查询购买记录结果封装类
 */
data class QueryPurchasesResult(
    val purchases: List<PaymentPurchaseDetails> = emptyList(),
    val errorCode: PaymentCode? = null
)

/**
 * 查询购买记录结果回调接口，用于通知购买记录查询的结果。
 * 使用协程风格，通过 Result 类型返回结果
 */
fun interface QueryPurchasesCallback {
    suspend operator fun invoke(): QueryPurchasesResult
}