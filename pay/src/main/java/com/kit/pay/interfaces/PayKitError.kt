package com.kit.pay.interfaces

/**
 * 包装 SDK 内产生的所有错误。
 */
data class PayKitError(
    val code: ErrorCode,
    override val message: String
) : Throwable()

enum class ErrorCode {
    STORE_PROBLEM,           // 原厂商店报错或断连
    PURCHASE_CANCELLED,      // 用户主动取消
    PURCHASE_NOT_ALLOWED,    // 该设备不支持支付
    PRODUCT_NOT_AVAILABLE,   // 找不到发售商品
    NETWORK_ERROR,           // 网络问题
    UNKNOWN
}
