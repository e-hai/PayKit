package com.kit.pay.sample

import android.app.Activity
import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kit.pay.PayKit
import com.kit.pay.interfaces.PayKitError
import com.kit.pay.interfaces.PurchaseCallback
import com.kit.pay.interfaces.UpdatedCustomerInfoListener
import com.kit.pay.models.CustomerInfo
import com.kit.pay.models.PayKitConfiguration
import com.kit.pay.models.ProductType
import com.kit.pay.models.StoreProduct
import com.kit.pay.models.StoreTransaction
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    sealed class UiState {
        data object Loading : UiState()
        data object Ready : UiState()
    }

    data class ProductItem(
        val product: StoreProduct?,
        val productId: String,
        val productType: ProductType,
        val isSuccess: Boolean,
        val errorMessage: String? = null
    )

    data class EntitlementUi(
        /** 档位优先级：Free < Plus < Pro */
        val tier: Tier = Tier.Free,
        val activeSubs: Set<String> = emptySet(),
        val nonConsumables: Set<String> = emptySet(),
        val pendingCount: Int = 0
    ) {
        val isPaid: Boolean get() = tier != Tier.Free
    }

    enum class Tier { Free, Plus, Pro }

    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _entitlement = MutableStateFlow(EntitlementUi())
    val entitlement: StateFlow<EntitlementUi> = _entitlement.asStateFlow()

    private val _errorMessages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errorMessages = _errorMessages.asSharedFlow()

    private val _subsProducts = MutableStateFlow<List<ProductItem>>(emptyList())
    val subsProducts: StateFlow<List<ProductItem>> = _subsProducts.asStateFlow()

    private val _consumableProducts = MutableStateFlow<List<ProductItem>>(emptyList())
    val consumableProducts: StateFlow<List<ProductItem>> = _consumableProducts.asStateFlow()

    private val _nonConsumableProducts = MutableStateFlow<List<ProductItem>>(emptyList())
    val nonConsumableProducts: StateFlow<List<ProductItem>> = _nonConsumableProducts.asStateFlow()

    private val _querying = MutableStateFlow(false)
    val querying: StateFlow<Boolean> = _querying.asStateFlow()

    fun init() {
        PayKit.configure(
            getApplication(),
            PayKitConfiguration(
                subsProductIds = Constants.ALL_SUBS_IDS,
                consumableProductIds = Constants.ALL_CONSUMABLE_IDS,
                nonConsumableProductIds = Constants.ALL_NON_CONSUMABLE_IDS
            )
        )

        PayKit.shared.setUpdatedCustomerInfoListener(object : UpdatedCustomerInfoListener {
            override fun onReceived(customerInfo: CustomerInfo) {
                Log.d(TAG, "customerInfo updated pending=${customerInfo.pendingPurchases.size}")
                applyCustomerInfo(customerInfo)
            }
        })

        viewModelScope.launch {
            // 秒开：只读本地缓存。商店同步由 SDK 连接成功后自动 sync，经 Listener 回推，避免双查。
            val cached = PayKit.shared.getCustomerInfo(forceSync = false)
            if (cached != null) {
                applyCustomerInfo(cached)
            } else {
                Log.d(TAG, "no cache, wait for auto sync via listener")
            }

            // 连接/同步失败时 Listener 可能永远不来：超时进入主页，可手动「恢复购买」
            delay(8_000)
            if (_uiState.value is UiState.Loading) {
                Log.w(TAG, "auto sync timeout, enter Ready for manual restore")
                _uiState.value = UiState.Ready
                showError("尚未同步到权益，可点击「恢复购买」重试")
            }
        }
    }

    private fun applyCustomerInfo(info: CustomerInfo) {
        val tier = when {
            Constants.SUBS_PRO in info.activeSubscriptions -> Tier.Pro
            Constants.SUBS_PLUS in info.activeSubscriptions -> Tier.Plus
            else -> Tier.Free
        }
        _entitlement.value = EntitlementUi(
            tier = tier,
            activeSubs = info.activeSubscriptions,
            nonConsumables = info.nonConsumablePurchases,
            pendingCount = info.pendingPurchases.size
        )
        _uiState.value = UiState.Ready
    }

    fun querySubsProducts() {
        // 一个 product 对应一个付费档；每条 StoreProduct 对应一个 base plan × offer 组合
        queryProductsByType(
            products = listOf(
                Constants.SUBS_PLUS to ProductType.SUBS,
                Constants.SUBS_PRO to ProductType.SUBS
            ),
            stateFlow = _subsProducts
        )
    }

    fun queryConsumableProducts() {
        queryProductsByType(
            products = listOf(Constants.OTP_GAME_SKIN_3DAY to ProductType.INAPP),
            stateFlow = _consumableProducts
        )
    }

    fun queryNonConsumableProducts() {
        queryProductsByType(
            products = listOf(Constants.OTP_GAME_SKIN_PERMANENT to ProductType.INAPP),
            stateFlow = _nonConsumableProducts
        )
    }

    private fun queryProductsByType(
        products: List<Pair<String, ProductType>>,
        stateFlow: MutableStateFlow<List<ProductItem>>
    ) {
        viewModelScope.launch {
            _querying.value = true
            val productIds = products.map { it.first }.toSet()
            try {
                PayKit.shared.getProducts(productIds)
                    .onSuccess { storeProducts ->
                        val fetchedIds = storeProducts.map { it.productId }.toSet()
                        // 每个 StoreProduct 已带对应 offerToken，列表里可直接购买
                        val successItems = storeProducts.map { product ->
                            ProductItem(product, product.productId, product.type, true)
                        }
                        val failedItems = products
                            .filter { (id, _) -> id !in fetchedIds }
                            .map { (id, type) ->
                                ProductItem(null, id, type, false, "未找到商品")
                            }
                        stateFlow.value = successItems + failedItems
                        if (successItems.isEmpty()) {
                            showToast("未查到商品，请检查 Play Console 配置")
                        } else {
                            showToast("查询成功：${successItems.size} 个商品/优惠")
                        }
                    }
                    .onFailure { error ->
                        stateFlow.value = products.map { (id, type) ->
                            ProductItem(null, id, type, false, error.message)
                        }
                        showError("查询失败：${error.message}")
                    }
            } catch (e: Exception) {
                stateFlow.value = products.map { (id, type) ->
                    ProductItem(null, id, type, false, e.message)
                }
                showError("查询异常：${e.message}")
            } finally {
                _querying.value = false
            }
        }
    }

    fun restorePurchases() {
        showToast("正在恢复购买…")
        viewModelScope.launch {
            PayKit.shared.restorePurchases()
                .onSuccess { info ->
                    applyCustomerInfo(info)
                    val pending = info.pendingPurchases.size
                    showToast(
                        if (pending > 0) "恢复完成，待确认订单 $pending 笔"
                        else "恢复完成"
                    )
                }
                .onFailure { error ->
                    showError("恢复失败：${error.message}")
                }
        }
    }

    fun checkEntitlements() {
        viewModelScope.launch {
            val info = PayKit.shared.getCustomerInfo(forceSync = true)
            if (info == null) {
                showToast("检查失败")
                return@launch
            }
            applyCustomerInfo(info)
            showToast(
                "档位=${_entitlement.value.tier} " +
                    "订阅=${info.activeSubscriptions.size} " +
                    "非消耗=${info.nonConsumablePurchases.size} " +
                    "待确认=${info.pendingPurchases.size}"
            )
        }
    }

    /**
     * 优先用列表里已有的 [StoreProduct] 购买（含正确 offerToken），避免二次查询与错误匹配。
     */
    fun purchase(activity: Activity, item: ProductItem) {
        val product = item.product
        if (product == null) {
            showError("商品不可用：${item.productId}")
            return
        }
        purchaseStoreProduct(activity, product)
    }

    fun purchaseStoreProduct(activity: Activity, product: StoreProduct) {
        Log.d(
            TAG,
            "purchase productId=${product.productId} type=${product.type} " +
                "hasOfferToken=${!product.subscriptionToken.isNullOrEmpty()}"
        )
        PayKit.shared.purchase(activity, product, object : PurchaseCallback {
            override fun onCompleted(
                storeTransaction: StoreTransaction,
                customerInfo: CustomerInfo
            ) {
                Log.d(TAG, "purchase completed orderId=${storeTransaction.orderId}")
                applyCustomerInfo(customerInfo)
                showToast("支付成功 ${storeTransaction.orderId}")
            }

            override fun onPending(storeTransaction: StoreTransaction) {
                Log.d(TAG, "purchase pending orderId=${storeTransaction.orderId}")
                showToast("支付待确认，完成后将自动到账")
                // 同步一下以便 pending 出现在权益视图
                viewModelScope.launch {
                    PayKit.shared.getCustomerInfo(forceSync = true)?.let { applyCustomerInfo(it) }
                }
            }

            override fun onError(error: PayKitError, userCancelled: Boolean) {
                if (userCancelled) {
                    Log.d(TAG, "purchase cancelled")
                    showToast("已取消支付")
                } else {
                    Log.e(TAG, "purchase error code=${error.code} msg=${error.message}")
                    showError("支付失败：${error.message} (${error.code})")
                }
            }
        })
    }

    private fun showToast(message: String) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
    }

    private fun showError(message: String) {
        viewModelScope.launch {
            _errorMessages.emit(message)
        }
    }

    companion object {
        const val TAG = "PayKit-Sample"
    }
}
