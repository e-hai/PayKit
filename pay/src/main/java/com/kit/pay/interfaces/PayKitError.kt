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
    PURCHASE_PENDING,        // 支付待确认（PENDING），尚未完成，勿发货
    PURCHASE_IN_PROGRESS,    // 已有一笔购买进行中，拒绝并发发起
    PURCHASE_NOT_ALLOWED,    // 该设备不支持支付 / Billing 不可用
    PRODUCT_NOT_AVAILABLE,   // 找不到发售商品
    ITEM_ALREADY_OWNED,      // 商品已拥有（需走升降级或恢复）
    VERIFICATION_FAILED,     // 扩展验单失败（如服务端拒收）
    NETWORK_ERROR,           // 网络问题
    UNKNOWN
}
