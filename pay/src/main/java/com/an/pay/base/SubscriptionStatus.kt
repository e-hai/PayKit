package com.an.pay.base

/**
 * 订阅状态枚举，描述用户的订阅状态。
 */
enum class SubscriptionStatus {
    NOT_INITIALIZED, // 未初始化，订阅状态尚未查询
    SUBSCRIBED,      // 已订阅，用户当前拥有有效的订阅
    NOT_SUBSCRIBED   // 未订阅，用户当前没有订阅或订阅已过期
}