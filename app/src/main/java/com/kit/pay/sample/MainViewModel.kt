package com.kit.pay.sample

import android.app.Activity
import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kit.pay.PaymentManager
import com.kit.pay.base.InitializationCallback
import com.kit.pay.base.PaymentCallback
import com.kit.pay.base.PaymentConfig
import com.kit.pay.base.PaymentCode
import com.kit.pay.base.PaymentProductDetails
import com.kit.pay.base.PaymentProductType
import com.kit.pay.base.PaymentPurchaseDetails
import com.kit.pay.base.QueryProductsCallback
import com.kit.pay.base.QueryPurchasesCallback
import com.kit.pay.billing.GoogleBillingProvider
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class MainViewModel(private val app: Application) : AndroidViewModel(app) {

    // UI 状态管理
    sealed class UiState {
        object Loading : UiState()
        object IsVip : UiState()
        object IsNotVip : UiState()
    }
    
    private val _uiState = MutableStateFlow<UiState>(UiState.Loading)
    val uiState = _uiState.asStateFlow()

    // 错误信息 Flow
    private val _errorMessages = MutableSharedFlow<String>()
    val errorMessages = _errorMessages.asSharedFlow()

    private val _isSubscriber = MutableSharedFlow<Boolean>()
    val isSubscriber = _isSubscriber.asSharedFlow()

    private val _productList = MutableStateFlow<List<PaymentProductDetails>>(emptyList())
    val productList = _productList.asStateFlow()

    // 当前选中的商品
    private val _selectedProduct = MutableStateFlow<PaymentProductDetails?>(null)
    val selectedProduct = _selectedProduct.asStateFlow()

    // 优惠方案列表（用于订阅商品选择）
    private val _offerOptions = MutableStateFlow<List<String>>(emptyList())
    val offerOptions = _offerOptions.asStateFlow()

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
                    Log.d(TAG, "SDK 初始化成功")
                    // 先恢复未完成订单，确保权益列表已同步
                    actionRecoverUnfinishedOrders()
                    // 然后再检查 VIP 状态
                    // 注意：actionRecoverUnfinishedOrders 完成后会自动调用 actionCheckVipStatus
                }

                override fun onFailure(errorCode: PaymentCode) {
                    Log.e(TAG, "SDK 初始化失败：$errorCode")
                    // 即使初始化失败，也尝试检查本地 VIP 状态
                    checkLocalVipStatus()
                }
            }
        )
    }

    /**加载配置**/
    private fun loadPaymentConfig(): PaymentConfig {
        return PaymentConfig(
            subsProducts = listOf(Constants.SUBS_PRODUCT_MONTH, Constants.SUBS_PRODUCT_YEAR),
            consumableProducts = listOf(Constants.OTP_GAME_SKIN_3DAY),
            nonConsumableProducts = listOf(Constants.OTP_GAME_SKIN_PERMANENT)
        )
    }

    /**检查用户是否拥有特定的订阅权益**/
    fun actionCheckVipStatus() {
        // 此时权益列表应该已经同步完成，直接检查本地状态即可
        checkLocalVipStatus()
    }

    /**
     * 检查本地权益状态（仅检查内存中的权益列表）
     * 前提：确保已经调用过 recoverUnfinishedOrders 或支付回调已完成
     */
    private fun checkLocalVipStatus() {
        viewModelScope.launch {
            val isVip = isProductPurchased(Constants.SUBS_PRODUCT_MONTH) ||
                    isProductPurchased(Constants.SUBS_PRODUCT_YEAR)
            _isSubscriber.emit(isVip)
            
            // 更新 UI 状态
            _uiState.value = if (isVip) UiState.IsVip else UiState.IsNotVip
        }
    }

    /**查询可订阅的商品列表**/
    fun actionLoadSubProductList() {
        viewModelScope.launch {
            // 先加载本地配置中的商品 ID
            val productIds = listOf(
                Constants.SUBS_PRODUCT_MONTH,
                Constants.SUBS_PRODUCT_YEAR
            )
            
            PaymentManager.getInstance().queryProducts(
                productIds = productIds,
                callback = object : QueryProductsCallback {
                    override fun onQuerySuccess(
                        products: List<PaymentProductDetails>,
                        unfetchedProductIds: List<String>
                    ) {
                        Log.d(TAG, "查询到 ${products.size} 个商品，${unfetchedProductIds.size} 个商品未找到")
                        
                        // 处理查询到的商品
                        products.forEach { product ->
                            Log.d(TAG, "商品 - ID: ${product.productId}, " +
                                    "标题：${product.title}, " +
                                    "描述：${product.description}, " +
                                    "类型：${product.productType}")
                            
                            // 根据商品类型展示不同的信息
                            when (product.productType) {
                                PaymentProductType.SUBS -> {
                                    // 订阅商品：可以显示价格周期（如 $9.99/月）
                                    Log.d(TAG, "订阅商品：${product.title}")
                                }
                                PaymentProductType.INAPP -> {
                                    // 一次性商品：显示固定价格
                                    Log.d(TAG, "一次性商品：${product.title}")
                                }
                            }
                        }
                        
                        // 处理未找到的商品
                        if (unfetchedProductIds.isNotEmpty()) {
                            Log.w(TAG, "以下商品未找到：$unfetchedProductIds")
                            // 可能的原因：
                            // 1. 商品 ID 配置错误
                            // 2. 商品在 Google Play 后台未上架
                            // 3. 应用签名与 Google Play 不一致（测试环境常见问题）
                        }
                        
                        // 更新 UI 显示商品列表
                        _productList.value = products
                    }

                    override fun onQueryFailure(errorCode: PaymentCode) {
                        Log.e(TAG, "查询商品失败：$errorCode")
                        val errorMessage = getErrorMessage(errorCode, "查询商品")
                        showError(errorMessage)
                    }
                }
            )
        }
    }

    /**查询所有订阅商品（使用快捷方法）**/
    fun actionLoadAllSubsProducts() {
        PaymentManager.getInstance().querySubsProducts(
            callback = object : QueryProductsCallback {
                override fun onQuerySuccess(
                    products: List<PaymentProductDetails>,
                    unfetchedProductIds: List<String>
                ) {
                    Log.d(TAG, "查询到 ${products.size} 个订阅商品")
                    products.forEach { product ->
                        Log.d(TAG, "订阅商品 - ${product.productId}: ${product.title}")
                    }
                }

                override fun onQueryFailure(errorCode: PaymentCode) {
                    Log.e(TAG, "查询订阅商品失败：$errorCode")
                }
            }
        )
    }

    /**查询所有一次性商品（使用快捷方法）**/
    fun actionLoadAllConsumableProducts() {
        PaymentManager.getInstance().queryConsumableProducts(
            callback = object : QueryProductsCallback {
                override fun onQuerySuccess(
                    products: List<PaymentProductDetails>,
                    unfetchedProductIds: List<String>
                ) {
                    Log.d(TAG, "查询到 ${products.size} 个一次性商品")
                    products.forEach { product ->
                        Log.d(TAG, "一次性商品 - ${product.productId}: ${product.title}")
                    }
                }

                override fun onQueryFailure(errorCode: PaymentCode) {
                    Log.e(TAG, "查询一次性商品失败：$errorCode")
                }
            }
        )
    }

    /**恢复未完成订单**/
    fun actionRecoverUnfinishedOrders() {
        if (isOrdersRecovered) {
            Log.d(TAG, "订单已恢复过，跳过")
            showToast("订单已恢复过")
            return
        }
    
        Log.d(TAG, "开始恢复未完成订单...")
    
        // 恢复订阅商品
        PaymentManager.getInstance().recoverUnfinishedOrders(
            productType = PaymentProductType.SUBS,
            onOrderRecovered = { purchase ->
                Log.d(TAG, "订阅商品已恢复：${purchase.products}, 订单号：${purchase.orderId}")
            },
            onComplete = {
                Log.d(TAG, "订阅商品恢复完成")
                actionCheckVipStatus()
            }
        )
    
        // 恢复一次性商品
        PaymentManager.getInstance().recoverUnfinishedOrders(
            productType = PaymentProductType.INAPP,
            onOrderRecovered = { purchase ->
                Log.d(TAG, "一次性商品已恢复：${purchase.products}, 订单号：${purchase.orderId}")
            },
            onComplete = {
                Log.d(TAG, "一次性商品恢复完成")
                isOrdersRecovered = true
            }
        )
    }

    /**检查是否拥有某项权益**/
    fun isProductPurchased(productId: String): Boolean {
        return PaymentManager.getInstance().isEntitled(productId)
    }

    /**选择商品**/
    fun selectProduct(product: PaymentProductDetails) {
        viewModelScope.launch {
            _selectedProduct.value = product
            
            // 如果是订阅商品，加载优惠方案选项
            if (product.productType == PaymentProductType.SUBS) {
                loadOfferOptions(product.productId)
            }
        }
    }
    
    /**加载订阅商品的优惠方案选项**/
    private fun loadOfferOptions(productId: String) {
        viewModelScope.launch {
            PaymentManager.getInstance().queryProducts(
                productIds = listOf(productId),
                callback = object : QueryProductsCallback {
                    override fun onQuerySuccess(
                        products: List<PaymentProductDetails>,
                        unfetchedProductIds: List<String>
                    ) {
                        // 这里可以从 ProductDetails 中获取 subscriptionOfferDetails
                        // 但由于 PaymentProductDetails 是简化版，实际应用中需要直接从 BillingClient 获取
                        // 暂时使用预设的优惠方案
                        _offerOptions.value = when (productId) {
                            Constants.SUBS_PRODUCT_MONTH -> listOf(Constants.BASIC_MONTHLY_PLAN, Constants.PREMIUM_MONTHLY_PLAN)
                            Constants.SUBS_PRODUCT_YEAR -> listOf(Constants.BASIC_YEARLY_PLAN, Constants.PREMIUM_YEARLY_PLAN)
                            else -> emptyList()
                        }
                    }

                    override fun onQueryFailure(errorCode: PaymentCode) {
                        Log.e(TAG, "加载优惠方案失败：$errorCode")
                    }
                }
            )
        }
    }
    
    /**清除选中的商品**/
    fun clearSelectedProduct() {
        viewModelScope.launch {
            _selectedProduct.value = null
            _offerOptions.value = emptyList()
        }
    }
    
    /**处理功能点击事件**/
    fun handleFeatureAction(action: String) {
        when (action) {
            "query_products" -> {
                actionLoadSubProductList()
                showToast("正在查询商品...")
            }
            "make_payment" -> {
                // TODO: 需要传入 Activity 才能发起支付，这里仅做演示
                Log.d(TAG, "点击发起支付，需要在实际场景中传入 Activity")
                showToast("发起支付功能待实现")
            }
            "recover_orders" -> {
                actionRecoverUnfinishedOrders()
                showToast("正在恢复订单...")
            }
            "manage_entitlements" -> {
                actionCheckVipStatus()
                showToast("正在检查权益状态...")
            }
            "purchase_subs_month" -> {
                // 购买包月订阅
                val activity = getActivity()
                if (activity != null) {
                    actionPurchase(activity, Constants.SUBS_PRODUCT_MONTH, Constants.BASIC_MONTHLY_PLAN)
                } else {
                    showToast("Activity 不可用")
                }
            }
            "purchase_subs_year" -> {
                // 购买包年订阅
                val activity = getActivity()
                if (activity != null) {
                    actionPurchase(activity, Constants.SUBS_PRODUCT_YEAR, Constants.BASIC_YEARLY_PLAN)
                } else {
                    showToast("Activity 不可用")
                }
            }
            "purchase_consumable" -> {
                // 购买一次性消耗商品
                val activity = getActivity()
                if (activity != null) {
                    actionPurchase(activity, Constants.OTP_GAME_SKIN_3DAY)
                } else {
                    showToast("Activity 不可用")
                }
            }
            "purchase_non_consumable" -> {
                // 购买一次性非消耗商品
                val activity = getActivity()
                if (activity != null) {
                    actionPurchase(activity, Constants.OTP_GAME_SKIN_PERMANENT)
                } else {
                    showToast("Activity 不可用")
                }
            }
            else -> Log.w(TAG, "未知功能：$action")
        }
    }
    
    /**获取当前 Activity（用于发起支付）**/
    private var currentActivity: Activity? = null
    
    fun setCurrentActivity(activity: Activity?) {
        currentActivity = activity
    }
    
    private fun getActivity(): Activity? {
        return currentActivity
    }
    
    private fun showToast(message: String) {
        viewModelScope.launch {
            Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
        }
    }
    
    /**显示错误信息**/
    private fun showError(message: String) {
        viewModelScope.launch {
            _errorMessages.emit(message)
        }
    }
    
    /**获取友好的错误提示信息**/
    private fun getErrorMessage(errorCode: PaymentCode, operation: String): String {
        return when (errorCode) {
            PaymentCode.SERVICE_UNAVAILABLE -> "${operation}失败：支付服务暂时不可用，请稍后重试"
            PaymentCode.SERVICE_DISCONNECTED -> "${operation}失败：网络连接失败，请检查网络后重试"
            PaymentCode.BILLING_UNAVAILABLE -> "${operation}失败：Google Play 服务不可用"
            PaymentCode.DEVELOPER_ERROR -> "${operation}失败：开发者配置错误，请联系管理员"
            PaymentCode.ITEM_UNAVAILABLE -> "${operation}失败：商品不可用"
            PaymentCode.USER_CANCELED -> "${operation}已取消"
            else -> "${operation}失败：未知错误 ($errorCode)"
        }
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
    @OptIn(ExperimentalUuidApi::class)
    fun actionPurchase(activity: Activity, productId: String, offerId: String = "") {
        PaymentManager.getInstance().makePayment(
            activity = activity,
            key = Uuid.generateV4().toHexString(),
            productId = productId,
            offerId = offerId,
            callback = object : PaymentCallback {
                override fun onSuccess(purchaseDetails: PaymentPurchaseDetails) {
                    Log.d(TAG, "支付成功 - 订单号：${purchaseDetails.orderId}, 商品：${purchaseDetails.products}")
                    // 支付成功且已自动确认，可以立即发放权益
                    // 例如：解锁 VIP 功能、添加游戏道具等
                    viewModelScope.launch {
                        // 刷新 UI 状态
                        actionCheckVipStatus()
                    }
                }

                override fun onConfirmFailed(
                    purchaseDetails: PaymentPurchaseDetails
                ) {
                    Log.e(TAG, "支付成功但确认失败 - 订单号：${purchaseDetails.orderId}")
                    // 用户已付款，但确认操作失败
                    // 提示用户："支付已成功，系统正在处理中，请稍后查看权益到账情况"
                    // SDK 会在后台继续尝试确认，无需手动重试
                }

                override fun onPending(purchaseDetails: PaymentPurchaseDetails) {
                    Log.d(TAG, "支付待处理 - 订单号：${purchaseDetails.orderId}")
                    // 用户使用了延迟支付方式（如银行转账、信用卡分期等）
                    // 提示用户："支付申请已提交，等待支付平台确认后发放权益"
                    // 当支付平台确认后，会通过 setPurchaseListener 自动通知
                }

                override fun onUserCancel() {
                    Log.d(TAG, "用户取消了支付")
                    // 用户在支付流程中主动取消
                    // 提示用户："已取消支付，如需购买请重新下单"
                }

                override fun onFailure(errorCode: PaymentCode) {
                    Log.e(TAG, "支付失败 - 错误码：$errorCode")
                    // 支付失败（网络问题、服务错误等）
                    // 根据错误码提示用户：
                    // - SERVICE_DISCONNECTED: "网络连接失败，请检查网络后重试"
                    // - SERVICE_UNAVAILABLE: "支付服务暂时不可用，请稍后重试"
                    // - ITEM_UNAVAILABLE: "商品已下架或不可用"
                    // - ERROR: "支付失败，请重试"
                }
            }
        )
    }

    companion object {
        const val TAG = "MainViewModel"
    }
}