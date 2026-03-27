package com.kit.pay.base

import kotlin.collections.List

/**
 * 支付配置类，定义系统中所有商品的 ID 及其对应的业务类型。
 * 它是 PaymentManager 的核心业务规则，与具体的支付平台（Google/Amazon等）无关。
 */
data class PaymentConfig(
    val subsProducts: List<String> = emptyList(),         // 订阅商品 ID 列表
    val consumableProducts: List<String> = emptyList(),    // 一次性可消耗商品 ID 列表 (如：金币、点数)
    val nonConsumableProducts: List<String> = emptyList()  // 一次性非消耗商品 ID 列表 (如：永久皮肤、去除广告)
) {
    /**
     * 判断商品是否为订阅类型。
     */
    fun isSubs(productId: String): Boolean = subsProducts.contains(productId)

    /**
     * 判断商品是否为消耗型一次性商品。
     */
    fun isConsumable(productId: String): Boolean = consumableProducts.contains(productId)

    /**
     * 判断商品是否为非消耗型一次性商品。
     */
    fun isNonConsumable(productId: String): Boolean = nonConsumableProducts.contains(productId)
    
    /**
     * 获取所有定义的商品 ID。
     */
    fun getAllProductIds(): List<String> = subsProducts + consumableProducts + nonConsumableProducts
    
    /**
     * 获取指定商品的支付类型（INAPP 或 SUBS）。
     */
    fun getProductType(productId: String): PaymentProductType {
        return if (isSubs(productId)) PaymentProductType.SUBS else PaymentProductType.INAPP
    }
}
