package com.an.pay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*

object CountDownTimer {

    /**
     * @param timeoutSeconds 结束时间
     * @param onTick time是当前时间，返回值是true代表提前结束倒计时
     * @param onCompletion 倒计时结束
     * **/
    suspend fun start(
        timeoutSeconds: Int = 5,
        onTick: (time: Int) -> Boolean,
        onCompletion: () -> Unit
    ) {
        (0..timeoutSeconds)
            .asSequence()
            .asFlow()
            .onEach {
                if (onTick.invoke(it)) {
                    throw RuntimeException("stop timer ")
                }
                delay(1000)
            }
            .flowOn(Dispatchers.Default)
            .onCompletion {
                onCompletion.invoke()
            }
            .catch {}
            .collect()
    }
}