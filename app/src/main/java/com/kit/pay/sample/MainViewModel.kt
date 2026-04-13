package com.kit.pay.sample

import android.app.Activity
import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kit.pay.PayKit
import com.kit.pay.interfaces.InitializationCallback
import com.kit.pay.interfaces.PayKitError
import com.kit.pay.interfaces.PurchaseCallback
import com.kit.pay.interfaces.UpdatedCustomerInfoListener
import com.kit.pay.models.CustomerInfo
import com.kit.pay.models.PayKitConfiguration
import com.kit.pay.models.ProductType
import com.kit.pay.models.StoreProduct
import com.kit.pay.models.StoreTransaction
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

class MainViewModel(private val app: Application) : AndroidViewModel(app) {

    sealed class UiState {
        object Loading : UiState()
        object IsVip : UiState()
        object IsNotVip : UiState()
    }

    data class ProductItem(
        val product: StoreProduct?,
        val productId: String,
        val productType: ProductType,
        val isSuccess: Boolean,
        val errorMessage: String? = null
    )

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState = _uiState.asStateFlow()

    private val _errorMessages = MutableSharedFlow<String>()
    val errorMessages = _errorMessages.asSharedFlow()

    private val _isSubscriber = MutableSharedFlow<Boolean>()
    val isSubscriber = _isSubscriber.asSharedFlow()

    private val _productList = MutableStateFlow<List<StoreProduct>>(emptyList())
    val productList = _productList.asStateFlow()

    private val _subsProducts = MutableStateFlow<List<ProductItem>>(emptyList())
    val subsProducts: StateFlow<List<ProductItem>> = _subsProducts.asStateFlow()

    private val _consumableProducts = MutableStateFlow<List<ProductItem>>(emptyList())
    val consumableProducts: StateFlow<List<ProductItem>> = _consumableProducts.asStateFlow()

    private val _nonConsumableProducts = MutableStateFlow<List<ProductItem>>(emptyList())
    val nonConsumableProducts: StateFlow<List<ProductItem>> = _nonConsumableProducts.asStateFlow()

    fun init() {
        val configuration = PayKitConfiguration(
            subsProductIds = setOf(Constants.SUBS_PRODUCT_MONTH, Constants.SUBS_PRODUCT_YEAR),
            consumableProductIds = setOf(Constants.OTP_GAME_SKIN_3DAY),
            nonConsumableProductIds = setOf(Constants.OTP_GAME_SKIN_PERMANENT)
        )

        PayKit.configure(app, configuration)

        PayKit.shared.setUpdatedCustomerInfoListener(object : UpdatedCustomerInfoListener {
            override fun onReceived(customerInfo: CustomerInfo) {
                Log.d(TAG, "收到权益状态自动更新回调！")
                updateUiWithCustomerInfo(customerInfo)
            }
        })

        // 在 Configure 之后，可以主动随时拉取一次当前状态（类似 RC 的机制）
        // 如果 SDK 后台此时已经刷完数据，就会走内存/闪电查询；如果还没，则会等待初始化。
        viewModelScope.launch {
            val customerInfo = PayKit.shared.getCustomerInfo()
            if (null == customerInfo) {
                Log.e(TAG, "PayKit 获取异常网络警告：")
            } else {
                Log.d(TAG, "PayKit getCustomerInfo 确认获取环境完毕!")

                updateUiWithCustomerInfo(customerInfo)
            }
        }
    }

    private fun updateUiWithCustomerInfo(info: CustomerInfo) {
        val isVip = info.activeSubscriptions.contains(Constants.SUBS_PRODUCT_MONTH) ||
                info.activeSubscriptions.contains(Constants.SUBS_PRODUCT_YEAR)
        viewModelScope.launch {
            _isSubscriber.emit(isVip)
            _uiState.value = if (isVip) UiState.IsVip else UiState.IsNotVip
        }
    }

    fun actionLoadSubProductList() {
        val productIds = setOf(
            Constants.SUBS_PRODUCT_MONTH,
            Constants.SUBS_PRODUCT_YEAR
        )
        viewModelScope.launch {
            try {
                val result = PayKit.shared.getProducts(productIds)
                result.onSuccess { storeProducts ->
                    _productList.value = storeProducts
                }.onFailure { error ->
                    showError("查询商品失败：${error.message}")
                }
            } catch (e: Exception) {
                showError("查询商品失败：${e.message}")
            }
        }
    }

    fun actionRecoverUnfinishedOrders() {
        Log.d(TAG, "开始恢复未完成订单... 对于 RC 架构，只需要调用一次 getCustomerInfo")

        viewModelScope.launch {
            val customerInfo = PayKit.shared.getCustomerInfo()
            if (null == customerInfo) {
                showError("同步失败：")

            } else {
                showToast("同步完成")
            }
        }
    }

    fun querySubsProducts(activity: Activity) {
        val products = listOf(
            Constants.SUBS_PRODUCT_MONTH to ProductType.SUBS,
            Constants.SUBS_PRODUCT_YEAR to ProductType.SUBS
        )
        queryProductsByType(products, _subsProducts, "订阅商品")
    }

    fun queryConsumableProducts(activity: Activity) {
        val products = listOf(
            Constants.OTP_GAME_SKIN_3DAY to ProductType.INAPP
        )
        queryProductsByType(products, _consumableProducts, "消耗商品")
    }

    fun queryNonConsumableProducts(activity: Activity) {
        val products = listOf(
            Constants.OTP_GAME_SKIN_PERMANENT to ProductType.INAPP
        )
        queryProductsByType(products, _nonConsumableProducts, "非消耗商品")
    }

    private fun queryProductsByType(
        products: List<Pair<String, ProductType>>,
        stateFlow: MutableStateFlow<List<ProductItem>>,
        typeName: String
    ) {
        viewModelScope.launch {
            val productIds = products.map { it.first }.toSet()

            try {
                val result = PayKit.shared.getProducts(productIds)
                result.onSuccess { storeProducts ->
                    val fetchedIds = storeProducts.map { it.productId }.toSet()
                    val successItems = storeProducts.map { product ->
                        ProductItem(product, product.productId, product.type, true)
                    }
                    val failedItems = products
                        .filter { (productId, _) -> productId !in fetchedIds }
                        .map { (productId, productType) ->
                            ProductItem(null, productId, productType, false, "未找到商品")
                        }
                    stateFlow.value = successItems + failedItems
                }.onFailure { error ->
                    stateFlow.value = products.map { (productId, productType) ->
                        ProductItem(
                            null,
                            productId,
                            productType,
                            false,
                            "查询异常：${error.message}"
                        )
                    }
                }
            } catch (e: Exception) {
                stateFlow.value = products.map { (productId, productType) ->
                    ProductItem(null, productId, productType, false, "查询异常：${e.message}")
                }
            }
        }
    }

    fun recoverOrdersWithToast() {
        actionRecoverUnfinishedOrders()
        showToast("正在恢复订单...")
    }

    fun checkEntitlementsWithToast() {
        viewModelScope.launch {
            val customerInfo = PayKit.shared.getCustomerInfo()
            if (customerInfo != null) {
                val isVip =
                    customerInfo.activeSubscriptions.contains(Constants.SUBS_PRODUCT_MONTH) ||
                            customerInfo.activeSubscriptions.contains(Constants.SUBS_PRODUCT_YEAR)
                showToast("当前是否有权益: $isVip")
            } else {
                showToast("检查失败：")
            }
        }
    }


    fun purchaseProduct(activity: WeakReference<Activity>, productId: String, offerId: String = "") {
        actionPurchase(activity, productId, offerId)
    }

    private fun showToast(message: String) {
        viewModelScope.launch {
            Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showError(message: String) {
        viewModelScope.launch {
            _errorMessages.emit(message)
        }
    }

    /**基于 Callback 发起支付**/
    fun actionPurchase(activity: WeakReference<Activity>, productId: String, offerId: String = "") {
        // 由于 RevenueCat 需要 StoreProduct 对象，我们这里粗略先查一下然后再买。
        // （如果在你的 UI 层已经有了 StoreProduct 对象，可以直接传进来，节省这一次查询）。
        viewModelScope.launch {
            try {
                val result = PayKit.shared.getProducts(setOf(productId))
                result.onSuccess { storeProducts ->
                    // 如果传入了特有 offerId 尝试找出对应那个 token 的 sku
                    val targetProduct = storeProducts.find {
                        it.productId == productId && it.subscriptionToken?.contains(offerId) ?: true
                    }
                    val productToBuy = targetProduct ?: storeProducts.firstOrNull()

                    if (productToBuy != null) {
                        PayKit.shared.purchase(activity, productToBuy, object : PurchaseCallback {
                            override fun onCompleted(
                                storeTransaction: StoreTransaction,
                                customerInfo: CustomerInfo
                            ) {
                                Log.d(TAG, "支付成功！订单号: ${storeTransaction.orderId}")
                                showToast("支付成功")
                            }

                            override fun onError(error: PayKitError, userCancelled: Boolean) {
                                if (userCancelled) {
                                    Log.d(TAG, "用户主动取消了支付")
                                } else {
                                    Log.e(TAG, "支付报错: ${error.message} - code: ${error.code}")
                                    showError("支付异常：${error.message} (${error.code})")
                                }
                            }
                        })
                    } else {
                        showError("找不对此商品，无法购买")
                    }
                }.onFailure { error ->
                    showError("拉取发货参数失败：${error.message}")
                }
            } catch (e: Exception) {
                showError("拉取发货参数失败：${e.message}")
            }
        }
    }

    companion object {
        const val TAG = "MainViewModel"
    }
}