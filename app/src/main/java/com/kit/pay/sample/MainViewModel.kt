package com.kit.pay.sample

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kit.pay.PaymentManager
import com.kit.pay.base.PaymentProductType
import com.kit.pay.base.SubscriptionStatus
import com.kit.pay.billing.GoogleBillingConfig
import com.kit.pay.billing.GoogleBillingProvider
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(private val app: Application) : AndroidViewModel(app) {

    private val _isSubscriber = MutableSharedFlow<Boolean>()
    val isSubscriber = _isSubscriber.asSharedFlow()

    private val _productList = MutableStateFlow<List<String>>(emptyList())
    val productList = _productList.asStateFlow()

    private var isOrdersRecovered = false

    fun init() {
        val config = GoogleBillingConfig(
            subsProducts = listOf(Constants.SUBS_PRODUCT_MONTH, Constants.SUBS_PRODUCT_YEAR),
            otpConsumerProducts = listOf(Constants.OTP_GAME_SKIN_3DAY),
            otpNonConsumerProducts = listOf(Constants.OTP_GAME_SKIN_PERMANENT)
        )
        val paymentProvider = GoogleBillingProvider(app.applicationContext, config)
        
        // 传入可选的初始化回调
        PaymentManager.getInstance().setPaymentProvider(paymentProvider, object : com.kit.pay.base.InitializationCallback {
            override fun onSuccess() {
                Log.d(TAG, "SDK 初始化成功，可以开始使用")
                // 初始化成功后再调用其他方法
                actionRecoverUnfinishedOrders()
            }

            override fun onFailure(errorCode: com.kit.pay.base.PaymentErrorCode) {
                Log.e(TAG, "SDK 初始化失败：$errorCode")
                // 处理初始化失败，比如提示用户或重试
            }
        })
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
        val productIds = listOf(Constants.SUBS_PRODUCT_MONTH, Constants.SUBS_PRODUCT_YEAR)
        PaymentManager.getInstance().queryProducts(productIds, object : com.kit.pay.base.QueryProductsCallback {
            override fun onQuerySuccess(products: List<com.kit.pay.base.PaymentProductDetails>) {
                // 缓存商品详情，用于展示
                _productList.value = products.map { it.getProductId() }
                Log.d(TAG, "加载商品列表成功：${products.size}个商品")
            }

            override fun onQueryFailure(errorCode: com.kit.pay.base.PaymentErrorCode) {
                Log.e(TAG, "加载商品列表失败：$errorCode")
            }
        })
    }

    /**恢复未完成的订单（应用启动时调用）**/
    fun actionRecoverUnfinishedOrders() {
        if (isOrdersRecovered) return
        
        Log.d(TAG, "开始恢复未完成订单...")
        
        // 恢复订阅商品
        PaymentManager.getInstance().recoverUnfinishedOrders(
            productType = PaymentProductType.SUBS,
            onOrderRecovered = { products ->
                Log.d(TAG, "恢复订阅商品：$products")
                // 发放商品权益
            },
            onComplete = {
                Log.d(TAG, "订阅商品恢复完成")
            }
        )

        // 恢复一次性商品
        PaymentManager.getInstance().recoverUnfinishedOrders(
            productType = PaymentProductType.INAPP,
            onOrderRecovered = { products ->
                Log.d(TAG, "恢复一次性商品：$products")
                // 发放商品权益
            },
            onComplete = {
                Log.d(TAG, "一次性商品恢复完成")
                isOrdersRecovered = true
            }
        )
    }

    /**发起支付**/
    fun actionPurchase(productId: String, offerId: String = "") {
        PaymentManager.getInstance().makePayment(
            activity = (app as? android.app.Activity) ?: return,
            productId = productId,
            offerId = offerId,
            callback = object : com.kit.pay.base.PaymentCallback {
                override fun onSuccess() {
                    Log.d(TAG, "支付成功")
                    // 支付成功后会触发 handlePurchasesUpdated，自动确认/消费订单
                }

                override fun onFailure(errorCode: com.kit.pay.base.PaymentErrorCode) {
                    Log.e(TAG, "支付失败：$errorCode")
                }
            }
        )
    }

    companion object {
        const val TAG = "MainViewModel"
    }
}