package com.an.pay.sample

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.an.pay.CountDownTimer
import com.an.pay.PayManager
import com.an.pay.billing.Subscriber
import com.android.billingclient.api.ProductDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.time.Duration

class MainViewModel(val app: Application) : AndroidViewModel(app) {

    private val _isSubscriber = MutableSharedFlow<Boolean>()
    val isSubscriber = _isSubscriber.asSharedFlow()


    fun init() {
        PayManager.getInstance().init(app) {
            Log.d("App", "初始化完成")
            actionCheckSubscriber()
            actionLoadSubProductList()
        }
    }

    /**判断是否为订阅用户**/
    fun actionCheckSubscriber() {
        Log.d(TAG, "订阅用户=${PayManager.getInstance().isSubs()}")
    }

    /**查询可订阅的商品列表**/
    fun actionLoadSubProductList() {
        PayManager.getInstance().queryProducts(object : PayManager.ProductListener {
            override fun onSuccess(data: List<ProductDetails>) {
                    Log.d(TAG, "查询商品=$data")
            }

            override fun onFail() {
                Log.d(TAG, "查询商品失败")
            }
        })
    }


    companion object {
        const val TAG = "MainViewModel"
    }
}