package com.kit.pay.billing


data class GoogleBillingConfig(
    val subsProducts: List<String>,         //订阅商品ID
    val otpConsumerProducts: List<String>,  //一次性消耗商品ID
    val otpNonConsumerProducts: List<String>//一次性非消耗商品ID
)
