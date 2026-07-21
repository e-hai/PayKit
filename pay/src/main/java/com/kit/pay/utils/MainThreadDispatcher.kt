package com.kit.pay.utils

import android.os.Handler
import android.os.Looper

/**
 * 将回调派发到主线程，便于宿主直接更新 UI。
 */
internal object MainThreadDispatcher {

    private val handler = Handler(Looper.getMainLooper())

    fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            handler.post(block)
        }
    }
}
