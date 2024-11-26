package com.kit.pay.sample

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kit.pay.PaymentManager
import com.kit.pay.base.SubscriptionStatus
import com.kit.pay.billing.GoogleBillingConfig
import com.kit.pay.billing.GoogleBillingProvider
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(private val app: Application) : AndroidViewModel(app) {

    private val _isSubscriber = MutableSharedFlow<Boolean>()
    val isSubscriber = _isSubscriber.asSharedFlow()


    fun init() {
        val config = GoogleBillingConfig(
            subsProducts = listOf(Constants.SUBS_PRODUCT_MONTH, Constants.SUBS_PRODUCT_YEAR),
            otpConsumerProducts = listOf(Constants.OTP_GAME_SKIN_3DAY),
            otpNonConsumerProducts = listOf(Constants.OTP_GAME_SKIN_PERMANENT)
        )
        val paymentProvider = GoogleBillingProvider(app.applicationContext, config)
        PaymentManager.getInstance().setPaymentProvider(paymentProvider)
    }

    /**判断是否为订阅用户**/
    fun actionCheckSubscriber() {
        PaymentManager.getInstance().checkSubscriptionStatus {
            viewModelScope.launch {
                when (it) {
                    SubscriptionStatus.SUBSCRIBED -> _isSubscriber.emit(true)
                    else -> _isSubscriber.emit(false)
                }
            }
        }
    }

    /**查询可订阅的商品列表**/
    fun actionLoadSubProductList() {
    }


    companion object {
        const val TAG = "MainViewModel"
    }
}