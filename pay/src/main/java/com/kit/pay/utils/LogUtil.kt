package com.kit.pay.utils

import android.util.Log

object LogUtil {
    private const val TAG = "LogUtil"
    private const val isDebug = true


    fun d( msg: String) {
        if (isDebug) {
            Log.d(TAG, msg)
        }
    }

    fun e( msg: String) {
        if (isDebug) {
            Log.e(TAG, msg)
        }
    }

    fun i(msg: String) {
        if (isDebug) {
            Log.i(TAG, msg)
        }
    }

    fun w(msg: String){
        if (isDebug) {
            Log.w(TAG, msg)
        }
    }
}