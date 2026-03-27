package com.kit.pay.sample

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kit.pay.PaymentManager
import com.kit.pay.base.InitializationCallback
import com.kit.pay.base.PaymentCallback
import com.kit.pay.base.PaymentConfig
import com.kit.pay.base.PaymentCode
import com.kit.pay.base.PaymentProductType
import com.kit.pay.base.PaymentPurchaseDetails
import com.kit.pay.base.QueryPurchasesCallback
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
        // 1. 加载配置
        val config = loadPaymentConfig()
        val paymentProvider = GoogleBillingProvider(app)

        // 2. 初始化 SDK
        PaymentManager.getInstance().init(
            provider = paymentProvider,
            config = config,
            callback = object : InitializationCallback {
                override fun onSuccess() {
                    Log.d(TAG, "SDK 初始化并数据同步成功")
                    // 数据已就绪，可以直接查询
                    actionCheckVipStatus()
                }

                override fun onFailure(errorCode: PaymentCode) {
                    Log.e(TAG, "SDK 初始化失败：$errorCode")
                }
            }
        )
    }


    private fun loadPaymentConfig(): PaymentConfig {
        return PaymentConfig(
            subsProducts = listOf(Constants.SUBS_PRODUCT_MONTH, Constants.SUBS_PRODUCT_YEAR),
            consumableProducts = listOf(Constants.OTP_GAME_SKIN_3DAY),
            nonConsumableProducts = listOf(Constants.OTP_GAME_SKIN_PERMANENT)
        )
    }

    /**检查用户是否拥有特定的订阅权益**/
    fun actionCheckVipStatus() {
        PaymentManager.getInstance().queryPurchases(PaymentProductType.SUBS,object :
            QueryPurchasesCallback{
            override fun onQuerySuccess(products: List<PaymentPurchaseDetails>) {
                viewModelScope.launch {
                    // 检查月订阅或年订阅是否有一个已购
                    val isVip = isProductPurchased(Constants.SUBS_PRODUCT_MONTH) ||
                            isProductPurchased(Constants.SUBS_PRODUCT_YEAR)
                    _isSubscriber.emit(isVip)
                }
            }

            override fun onQueryFailure(errorCode: PaymentCode) {
                TODO("Not yet implemented")
            }
        })

    }

    /**查询可订阅的商品列表**/
    fun actionLoadSubProductList() {
        // 直接从 PaymentManager 获取缓存的商品详情
        val products = PaymentManager.getInstance().getProducts()
        if (products.isNotEmpty()) {
            _productList.value = products.map { it.getProductId() }
            Log.d(TAG, "从缓存加载商品列表成功：${products.size}个商品")
        } else {
            Log.w(TAG, "缓存商品列表为空，请检查初始化状态")
        }
    }

    /**检查是否拥有某项权益**/
    fun isProductPurchased(productId: String): Boolean {
        return PaymentManager.getInstance().isEntitled(productId)
    }

    /**恢复未完成的订单（应用启动时调用）**/
    fun actionRecoverUnfinishedOrders() {
        if (isOrdersRecovered) return

        Log.d(TAG, "开始恢复未完成订单...")

        // 恢复订阅商品
        PaymentManager.getInstance().recoverUnfinishedOrders(
            productType = PaymentProductType.SUBS,
            onOrderRecovered = { products ->
                // 注意：这里不再需要手动发放，PurchaseHandler 会自动处理
                Log.d(TAG, "订阅商品已恢复并自动触发发放: $products")
            },
            onComplete = {
                Log.d(TAG, "订阅商品恢复完成")
            }
        )

        // 恢复一次性商品
        PaymentManager.getInstance().recoverUnfinishedOrders(
            productType = PaymentProductType.INAPP,
            onOrderRecovered = { products ->
                // 注意：这里不再需要手动发放，PurchaseHandler 会自动处理
                Log.d(TAG, "一次性商品已恢复并自动触发发放: $products")
            },
            onComplete = {
                Log.d(TAG, "一次性商品恢复完成")
                isOrdersRecovered = true
            }
        )
    }

    /**
     * 清理资源，防止内存泄露。
     * 在 Activity onDestroy 时调用。
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up resources")
        PaymentManager.getInstance().cleanup()
    }

    /**发起支付**/
    fun actionPurchase(productId: String, offerId: String = "") {
        PaymentManager.getInstance().makePayment(
            activity = (app as? android.app.Activity) ?: return,
            productId = productId,
            offerId = offerId,
            callback = object : PaymentCallback {
                override fun onSuccess() {
                    Log.d(TAG, "支付成功")
                    // 支付成功后会触发 handlePurchasesUpdated，自动确认/消费订单
                }

                override fun onFailure(errorCode: com.kit.pay.base.PaymentCode) {
                    Log.e(TAG, "支付失败：$errorCode")
                }
            }
        )
    }

    companion object {
        const val TAG = "MainViewModel"
    }
}