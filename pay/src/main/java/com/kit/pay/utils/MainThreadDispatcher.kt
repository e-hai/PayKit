package com.kit.pay.utils

import android.os.Handler
import android.os.Looper
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

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

    /** 在主线程执行 [block]，并在其返回后恢复协程（用于发货后再 consume）。 */
    suspend fun run(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        suspendCancellableCoroutine { cont ->
            val runnable = Runnable {
                try {
                    block()
                    if (cont.isActive) cont.resume(Unit)
                } catch (t: Throwable) {
                    cont.resumeWith(Result.failure(t))
                }
            }
            cont.invokeOnCancellation { handler.removeCallbacks(runnable) }
            handler.post(runnable)
        }
    }
}
