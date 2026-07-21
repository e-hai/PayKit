package com.kit.pay.utils

import android.util.Log

/**
 * SDK 统一日志。
 *
 * Logcat 过滤建议：
 * - `PayKit`：查看全部 SDK 日志（子 tag 均以 PayKit 开头）
 * - `PayKit-Billing`：仅 Billing / 商店交互
 * - `PayKit-Sample`：示例 App（Demo 使用）
 *
 * 消息约定：`action key=value key=value`，便于全文检索。
 */
object LogUtil {

    const val TAG = "PayKit"
    const val TAG_BILLING = "PayKit-Billing"

    /** 运行时开关；默认开启，宿主可在 release 关闭 */
    @JvmField
    var enabled: Boolean = true

    fun d(msg: String) = d(TAG, msg)

    fun d(tag: String, msg: String) {
        if (enabled) Log.d(tag, msg)
    }

    fun i(msg: String) = i(TAG, msg)

    fun i(tag: String, msg: String) {
        if (enabled) Log.i(tag, msg)
    }

    fun w(msg: String) = w(TAG, msg)

    fun w(tag: String, msg: String) {
        if (enabled) Log.w(tag, msg)
    }

    fun e(msg: String) = e(TAG, msg)

    fun e(tag: String, msg: String) {
        if (enabled) Log.e(tag, msg)
    }
}
