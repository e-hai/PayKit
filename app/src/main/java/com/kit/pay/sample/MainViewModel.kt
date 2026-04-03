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
import com.kit.pay.base.PaymentCode
import com.kit.pay.base.PaymentConfig
import com.kit.pay.base.PaymentProductDetails
import com.kit.pay.base.PaymentProductType
import com.kit.pay.base.PaymentPurchaseDetails
import com.kit.pay.billing.GoogleBillingProvider
import com.kit.pay.utils.LogUtil
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class MainViewModel(private val app: Application) : AndroidViewModel(app) {

    // UI 状态管理
    sealed class UiState {
        object Loading : UiState()
        object IsVip : UiState()
        object IsNotVip : UiState()
    }
    
    // 商品项（包含成功和失败状态）
    data class ProductItem(
        val product: PaymentProductDetails?,
        val productId: String,
        val productType: PaymentProductType,
        val isSuccess: Boolean,
        val errorMessage: String? = null
    )
    
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

    // 订阅商品（成功 + 失败）
    private val _subsProducts = MutableStateFlow<List<ProductItem>>(emptyList())
    val subsProducts: StateFlow<List<ProductItem>> = _subsProducts.asStateFlow()

    // 消耗商品（成功 + 失败）
    private val _consumableProducts = MutableStateFlow<List<ProductItem>>(emptyList())
    val consumableProducts: StateFlow<List<ProductItem>> = _consumableProducts.asStateFlow()

    // 非消耗商品（成功 + 失败）
    private val _nonConsumableProducts = MutableStateFlow<List<ProductItem>>(emptyList())
    val nonConsumableProducts: StateFlow<List<ProductItem>> = _nonConsumableProducts.asStateFlow()

    private var isOrdersRecovered = false

    fun init() {
        // 1. 加载配置
        val config = loadPaymentConfig()
        val paymentProvider = GoogleBillingProvider(app)

        // 2. 初始化 SDK，并设置全局订单更新监听器
        PaymentManager.init(
            provider = paymentProvider,
            config = config,
            orderUpdateListener = object : PaymentCallback {
                override fun onSuccess(purchaseDetails: PaymentPurchaseDetails) {
                    Log.d(TAG, "订单支付成功 - 订单号：${purchaseDetails.orderId}, 商品：${purchaseDetails.products}, key: ${purchaseDetails.key}")
                    // 支付成功且已自动确认，可以立即发放权益
                    // 例如：解锁 VIP 功能、添加游戏道具等
                    viewModelScope.launch {
                        // 刷新 UI 状态
                        actionCheckVipStatus()
                    }
                }

                override fun onConfirmFailed(purchaseDetails: PaymentPurchaseDetails) {
                    Log.e(TAG, "订单支付成功但确认失败 - 订单号：${purchaseDetails.orderId}, key: ${purchaseDetails.key}")
                    // 用户已付款，但确认操作失败
                    // 提示用户："支付已成功，系统正在处理中，请稍后查看权益到账情况"
                    // SDK 会在后台继续尝试确认，无需手动重试
                }

                override fun onPending(purchaseDetails: PaymentPurchaseDetails) {
                    Log.d(TAG, "订单支付待处理 - 订单号：${purchaseDetails.orderId}, key: ${purchaseDetails.key}")
                    // 用户使用了延迟支付方式（如银行转账、信用卡分期等）
                    // 提示用户："支付申请已提交，等待支付平台确认后发放权益"
                    // 当支付平台确认后，会通过全局监听器自动通知
                }

                override fun onUserCancel(purchaseDetails: PaymentPurchaseDetails) {
                    Log.d(TAG, "用户取消了支付 - 订单号：${purchaseDetails.orderId}, key: ${purchaseDetails.key}")
                    // 用户在支付流程中主动取消
                    // 提示用户："已取消支付，如需购买请重新下单"
                }

                override fun onFailure(errorCode: PaymentCode, purchaseDetails: PaymentPurchaseDetails?) {
                    Log.e(TAG, "订单支付失败 - 错误码：$errorCode, key: ${purchaseDetails?.key}")
                    // 支付失败（网络问题、服务错误等）
                    // 根据错误码提示用户：
                    // - SERVICE_DISCONNECTED: "网络连接失败，请检查网络后重试"
                    // - SERVICE_UNAVAILABLE: "支付服务暂时不可用，请稍后重试"
                    // - ITEM_UNAVAILABLE: "商品已下架或不可用"
                    // - ERROR: "支付失败，请重试"
                }
            },
            initCallback = object : InitializationCallback {
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
            
            try {
                val result = PaymentManager.queryProducts(productIds)
                
                Log.d(TAG, "查询到 ${result.products.size} 个商品，${result.unfetchedProductIds.size} 个商品未找到")
                
                // 处理查询到的商品
                result.products.forEach { product ->
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
                if (result.unfetchedProductIds.isNotEmpty()) {
                    Log.w(TAG, "以下商品未找到：${result.unfetchedProductIds}")
                    // 可能的原因：
                    // 1. 商品 ID 配置错误
                    // 2. 商品在 Google Play 后台未上架
                    // 3. 应用签名与 Google Play 不一致（测试环境常见问题）
                }
                
                // 更新 UI 显示商品列表
                _productList.value = result.products
            } catch (e: Exception) {
                Log.e(TAG, "查询商品异常：${e.message}")
                showError("查询商品失败：${e.message}")
            }
        }
    }

    /**查询所有订阅商品（使用快捷方法）**/
    fun actionLoadAllSubsProducts() {
        viewModelScope.launch {
            try {
                val result = PaymentManager.querySubsProducts()
                
                Log.d(TAG, "查询到 ${result.products.size} 个订阅商品")
                result.products.forEach { product ->
                    Log.d(TAG, "订阅商品 - ${product.productId}: ${product.title}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "查询订阅商品失败：${e.message}")
            }
        }
    }

    /**查询所有一次性商品（使用快捷方法）**/
    fun actionLoadAllConsumableProducts() {
        viewModelScope.launch {
            try {
                val result = PaymentManager.queryConsumableProducts()
                
                Log.d(TAG, "查询到 ${result.products.size} 个一次性商品")
                result.products.forEach { product ->
                    Log.d(TAG, "一次性商品 - ${product.productId}: ${product.title}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "查询一次性商品失败：${e.message}")
            }
        }
    }

    /**恢复未完成订单**/
    fun actionRecoverUnfinishedOrders() {
        if (isOrdersRecovered) {
            Log.d(TAG, "订单已恢复过，跳过")
            showToast("订单已恢复过")
            return
        }
    
        Log.d(TAG, "开始恢复未完成订单...")
    
        viewModelScope.launch {
            try {
                // 恢复订阅商品
                PaymentManager.recoverUnfinishedOrders(
                    productType = PaymentProductType.SUBS,
                    onOrderRecovered = { purchase ->
                        Log.d(TAG, "订阅商品已恢复：${purchase.products}, 订单号：${purchase.orderId}")
                    }
                )
                
                Log.d(TAG, "订阅商品恢复完成")
                actionCheckVipStatus()
                
                // 恢复一次性商品
                PaymentManager.recoverUnfinishedOrders(
                    productType = PaymentProductType.INAPP,
                    onOrderRecovered = { purchase ->
                        Log.d(TAG, "一次性商品已恢复：${purchase.products}, 订单号：${purchase.orderId}")
                    }
                )
                
                Log.d(TAG, "一次性商品恢复完成")
                isOrdersRecovered = true
            } catch (e: Exception) {
                Log.e(TAG, "恢复订单失败：${e.message}")
                showError("恢复订单失败：${e.message}")
            }
        }
    }

    /**检查是否拥有某项权益**/
    fun isProductPurchased(productId: String): Boolean {
        return PaymentManager.isEntitled(productId)
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
            try {
                val result = PaymentManager.queryProducts(listOf(productId))
                
                // 这里可以从 ProductDetails 中获取 subscriptionOfferDetails
                // 但由于 PaymentProductDetails 是简化版，实际应用中需要直接从 BillingClient 获取
                // 暂时使用预设的优惠方案
                _offerOptions.value = when (productId) {
                    Constants.SUBS_PRODUCT_MONTH -> listOf(Constants.BASIC_MONTHLY_PLAN, Constants.PREMIUM_MONTHLY_PLAN)
                    Constants.SUBS_PRODUCT_YEAR -> listOf(Constants.BASIC_YEARLY_PLAN, Constants.PREMIUM_YEARLY_PLAN)
                    else -> emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载优惠方案失败：${e.message}")
            }
        }
    }
    
    /**清除选中的商品**/
    fun clearSelectedProduct() {
        viewModelScope.launch {
            _selectedProduct.value = null
            _offerOptions.value = emptyList()
        }
    }
    
    /**查询商品并显示 Toast**/
    fun queryProductsWithToast() {
        actionLoadSubProductList()
        showToast("正在查询商品...")
    }
    
    /**查询订阅商品**/
    fun querySubsProducts(activity: Activity) {
        viewModelScope.launch {
            val products = listOf(
                Constants.SUBS_PRODUCT_MONTH to PaymentProductType.SUBS,
                Constants.SUBS_PRODUCT_YEAR to PaymentProductType.SUBS
            )
            queryProductsByType(products, _subsProducts, "订阅商品")
        }
    }
    
    /**查询消耗商品**/
    fun queryConsumableProducts(activity: Activity) {
        viewModelScope.launch {
            val products = listOf(
                Constants.OTP_GAME_SKIN_3DAY to PaymentProductType.INAPP
            )
            queryProductsByType(products, _consumableProducts, "消耗商品")
        }
    }
    
    /**查询非消耗商品**/
    fun queryNonConsumableProducts(activity: Activity) {
        viewModelScope.launch {
            val products = listOf(
                Constants.OTP_GAME_SKIN_PERMANENT to PaymentProductType.INAPP
            )
            queryProductsByType(products, _nonConsumableProducts, "非消耗商品")
        }
    }
    
    /**根据类型查询商品**/
    private suspend fun queryProductsByType(
        products: List<Pair<String, PaymentProductType>>,
        stateFlow: MutableStateFlow<List<ProductItem>>,
        typeName: String
    ) {
        try {
            val productIds = products.map { it.first }
            val result = PaymentManager.queryProducts(productIds)
            
            if (result.errorCode != null) {
                Log.e(TAG, "$typeName 查询失败：${result.errorCode}")
                // 全部失败
                stateFlow.value = products.map { (productId, productType) ->
                    ProductItem(
                        product = null,
                        productId = productId,
                        productType = productType,
                        isSuccess = false,
                        errorMessage = "查询失败：${result.errorCode}"
                    )
                }
            } else {
                // 成功的商品
                val successItems = result.products.map { product ->
                    ProductItem(
                        product = product,
                        productId = product.productId,
                        productType = product.productType,
                        isSuccess = true
                    )
                }
                
                // 失败的商品（通过对比请求和响应）
                val fetchedIds = result.products.map { it.productId }.toSet()
                val failedItems = products
                    .filter { (productId, _) -> productId !in fetchedIds }
                    .map { (productId, productType) ->
                        ProductItem(
                            product = null,
                            productId = productId,
                            productType = productType,
                            isSuccess = false,
                            errorMessage = "未找到商品"
                        )
                    }
                
                stateFlow.value = successItems.plus(failedItems)
                LogUtil.d("$typeName 查询成功：成功${successItems.size}个，失败${failedItems.size}个")
            }
        } catch (e: Exception) {
            Log.e(TAG, "$typeName 查询异常：${e.message}")
            stateFlow.value = products.map { (productId, productType) ->
                ProductItem(
                    product = null,
                    productId = productId,
                    productType = productType,
                    isSuccess = false,
                    errorMessage = "查询异常：${e.message}"
                )
            }
        }
    }
    
    /**恢复订单并显示 Toast**/
    fun recoverOrdersWithToast() {
        actionRecoverUnfinishedOrders()
        showToast("正在恢复订单...")
    }
    
    /**检查权益并显示 Toast**/
    fun checkEntitlementsWithToast() {
        actionCheckVipStatus()
        showToast("正在检查权益状态...")
    }
    
    /**购买包月订阅**/
    fun purchaseSubsMonth(activity: Activity) {
        actionPurchase(activity, Constants.SUBS_PRODUCT_MONTH, Constants.BASIC_MONTHLY_PLAN)
    }
    
    /**购买包年订阅**/
    fun purchaseSubsYear(activity: Activity) {
        actionPurchase(activity, Constants.SUBS_PRODUCT_YEAR, Constants.BASIC_YEARLY_PLAN)
    }
    
    /**购买一次性消耗商品**/
    fun purchaseConsumable(activity: Activity) {
        actionPurchase(activity, Constants.OTP_GAME_SKIN_3DAY)
    }
    
    /**购买一次性非消耗商品**/
    fun purchaseNonConsumable(activity: Activity) {
        actionPurchase(activity, Constants.OTP_GAME_SKIN_PERMANENT)
    }
    
    /**根据商品 ID 和优惠 ID 发起购买**/
    fun purchaseProduct(activity: Activity, productId: String, offerId: String = "") {
        // 根据商品类型选择正确的优惠方案
        val finalOfferId = when {
            productId == Constants.SUBS_PRODUCT_MONTH && offerId.isEmpty() -> Constants.BASIC_MONTHLY_PLAN
            productId == Constants.SUBS_PRODUCT_YEAR && offerId.isEmpty() -> Constants.BASIC_YEARLY_PLAN
            else -> offerId
        }
        actionPurchase(activity, productId, finalOfferId)
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
        PaymentManager.cleanup()
    }

    /**发起支付**/
    @OptIn(ExperimentalUuidApi::class)
    fun actionPurchase(activity: Activity, productId: String, offerId: String = "") {
        // 使用弱引用包装 Activity，防止内存泄漏
        val activityRef = WeakReference(activity)
        
        viewModelScope.launch {
            try {
                // 从弱引用中获取 Activity，如果已被回收则取消操作
                val currentActivity = activityRef.get() ?: run {
                    Log.w(TAG, "Activity 已被回收，取消支付操作")
                    return@launch
                }
                
                // 生成订单 key，用于区分不同订单
                val orderKey = Uuid.generateV4().toHexString()
                
                val result = PaymentManager.makePayment(
                    activity = currentActivity,
                    key = orderKey,
                    productId = productId,
                    offerId = offerId
                )
                
                if (!result.isSuccess) {
                    Log.e(TAG, "支付失败：${result.errorCode}")
                    showError("支付失败：${result.errorCode}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "支付异常：${e.message}")
                showError("支付异常：${e.message}")
            }
        }
    }

    companion object {
        const val TAG = "MainViewModel"
    }
}