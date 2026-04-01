package com.kit.pay


import android.app.Activity
import com.kit.pay.base.InitializationCallback
import com.kit.pay.base.MakePaymentCallback
import com.kit.pay.base.PaymentCallback
import com.kit.pay.base.PaymentConfig
import com.kit.pay.base.PaymentCode
import com.kit.pay.base.PaymentProductDetails
import com.kit.pay.base.PaymentProductType
import com.kit.pay.base.PaymentProvider
import com.kit.pay.base.PaymentPurchaseDetails
import com.kit.pay.base.PaymentPurchaseState
import com.kit.pay.base.PurchaseCallback
import com.kit.pay.base.QueryProductsCallback
import com.kit.pay.base.QueryPurchasesCallback
import com.kit.pay.utils.LogUtil


/**
 * 管理支付逻辑的单例类。负责初始化支付提供者并处理支付、订阅状态查询、商品查询等操作。
 */
class PaymentManager private constructor() {


    // 当前使用的支付提供者
    private var paymentProvider: PaymentProvider? = null

    // 商品配置
    private var paymentConfig: PaymentConfig? = null

    // 当前有效的权益 (按商品类型分类管理)
    // 订阅商品权益
    private val subsEntitlements = mutableSetOf<String>()

    // 一次性非消耗商品权益（永久有效）
    private val nonConsumableEntitlements = mutableSetOf<String>()

    // 一次性可消耗商品库存（记录已购买但未消费的商品）
    private val consumableInventory = mutableSetOf<String>()

    // 等待订单返回映射表：key -> PaymentCallback
    private val waitingPayments = mutableMapOf<String, PaymentCallback>()

    // 是否已成功初始化支付提供者
    @Volatile
    private var isInitialized = false

    private var initCallback: InitializationCallback? = null

    /**
     * 获取商品配置（简化访问）
     */
    private fun getPaymentConfig(): PaymentConfig {
        return paymentConfig ?: throw IllegalStateException("PaymentConfig not initialized")
    }

    /**
     * 初始化支付管理器并同步当前状态。
     *
     * @param provider 实现了 PaymentProvider 接口的支付提供者。
     * @param config 商品映射配置。
     * @param callback 初始化结果回调。
     */
    fun init(
        provider: PaymentProvider,
        config: PaymentConfig,
        callback: InitializationCallback
    ) {
        // 清理旧资源
        cleanup()

        this.paymentProvider = provider
        this.paymentConfig = config
        this.initCallback = callback

        // 设置提供者的购买监听器，由提供者主动通知，时机不定，因为订单可在设备外部产生（网页，另一台设备同一个账号，推广链接）
        provider.setPurchaseListener(object : PurchaseCallback {
            override fun onUpdate(
                code: PaymentCode,
                purchaseDetailList: List<PaymentPurchaseDetails>
            ) {
                handleIncomingPurchase(code, purchaseDetailList)
            }
        })

        provider.initialize(object : InitializationCallback {
            override fun onSuccess() {
                LogUtil.d("Payment provider initialized successfully")
                isInitialized = true
                callback.onSuccess()
            }

            override fun onFailure(errorCode: PaymentCode) {
                LogUtil.e("Payment provider initialization failed: $errorCode")
                isInitialized = false
                callback.onFailure(errorCode)
            }
        })
    }


    /**
     * 查询商品信息并自动刷新本地缓存。
     *
     * @param productIds 商品 ID 列表。
     * @param callback 查询结果回调。
     */
    fun queryProducts(
        productIds: List<String>,
        callback: QueryProductsCallback
    ) {
        LogUtil.d("查询商品详情: ${productIds.size} 个商品")
        val provider = paymentProvider
        val config = paymentConfig
        if (!isInitialized || provider == null || config == null) {
            LogUtil.e("PaymentManager 未初始化，请先初始化")
            callback.onQueryFailure(PaymentCode.SERVICE_UNAVAILABLE)
            return
        }

        // 映射商品 ID 到类型
        val productsWithType = productIds.map { id ->
            id to config.getProductType(id)
        }

        provider.queryProducts(productsWithType, object : QueryProductsCallback {
            override fun onQuerySuccess(
                products: List<PaymentProductDetails>,
                unfetchedProductIds: List<String>
            ) {
                callback.onQuerySuccess(products, unfetchedProductIds)
            }

            override fun onQueryFailure(errorCode: PaymentCode) {
                callback.onQueryFailure(errorCode)
            }
        })
    }


    /**
     * 查询所有的商品详情。
     */
    fun queryAllProducts(callback: QueryProductsCallback) {
        val products = paymentConfig?.getAllProductIds() ?: run {
            LogUtil.e("配置中暂无商品")
            callback.onQueryFailure(PaymentCode.SERVICE_UNAVAILABLE)
            return
        }
        queryProducts(products, callback)
    }

    fun querySubsProducts(callback: QueryProductsCallback) {
        val products = paymentConfig?.subsProducts ?: run {
            LogUtil.e("配置中暂无订阅商品")
            callback.onQueryFailure(PaymentCode.SERVICE_UNAVAILABLE)
            return
        }
        queryProducts(products, callback)
    }

    fun queryConsumableProducts(callback: QueryProductsCallback) {
        val products = paymentConfig?.consumableProducts ?: run {
            LogUtil.e("配置中暂无一次性消耗商品")
            callback.onQueryFailure(PaymentCode.SERVICE_UNAVAILABLE)
            return
        }
        queryProducts(products, callback)
    }

    fun queryNonConsumableProducts(callback: QueryProductsCallback) {
        val products = paymentConfig?.nonConsumableProducts ?: run {
            LogUtil.e("配置中暂无一次性非消耗商品")
            callback.onQueryFailure(PaymentCode.SERVICE_UNAVAILABLE)
            return
        }
        queryProducts(products, callback)
    }

    /**
     * 查询已有的订单。
     *
     * 触发时机：开发者主动调用查询接口
     * 典型用途：
     * - 应用启动时恢复订单
     * - 用户点击"恢复购买"按钮
     * - 检查订阅状态
     *
     * @param productType 商品类型（如订阅或一次性商品）。
     * @param callback 查询结果回调。
     * @throws IllegalStateException 如果尚未初始化
     */
    private fun queryPurchases(productType: PaymentProductType, callback: QueryPurchasesCallback) {
        val provider = paymentProvider
        if (!isInitialized || provider == null) {
            LogUtil.e("PaymentManager not initialized, please call setPaymentProvider first")
            callback.onQueryFailure(PaymentCode.SERVICE_UNAVAILABLE)
            return
        }

        provider.queryPurchases(productType, callback)
    }

    /**
     * 订阅订单查询。
     */
    fun querySubsPurchases(callback: QueryPurchasesCallback) {
        queryPurchases(PaymentProductType.SUBS, callback)
    }

    /**
     * 一次性订单查询。
     */
    fun queryInAppPurchases(callback: QueryPurchasesCallback) {
        queryPurchases(PaymentProductType.INAPP, callback)
    }


    /**
     * 检查是否拥有指定商品的权益。
     * 对于订阅和非消耗商品，检查是否在权益列表中。
     * 对于可消耗商品，检查是否有可用库存。
     */
    fun isEntitled(productId: String): Boolean {
        val config = getPaymentConfig()
        return when {
            config.isSubs(productId) -> subsEntitlements.contains(productId)
            config.isNonConsumable(productId) -> nonConsumableEntitlements.contains(productId)
            config.isConsumable(productId) -> hasConsumableInventory(productId)
            else -> false
        }
    }

    /**
     * 检查是否有可消耗商品的库存。
     */
    private fun hasConsumableInventory(productId: String): Boolean {
        return consumableInventory.any {
            it.contains(productId)
        }
    }

    /**
     * 获取所有活跃的订阅权益。
     */
    fun getActiveSubscriptions(): Set<String> = subsEntitlements.toSet()

    /**
     * 获取所有活跃的非消耗商品权益。
     */
    fun getActiveNonConsumables(): Set<String> = nonConsumableEntitlements.toSet()


    /**
     * 检查是否拥有指定商品的权益（兼容旧版本，等同于 isEntitled）。
     */
    fun isProductPurchased(productId: String): Boolean = isEntitled(productId)


    /**
     * 处理新发现的购买订单（包括支付成功后的回调和启动时的补单）。
     * 根据商品类型分别处理：
     * - 订阅商品：激活或更新订阅权益
     * - 非消耗商品：添加永久权益
     * - 可消耗商品：添加到库存，等待消费
     *
     * 触发时机：支付平台主动通知购买状态更新
     * 特点：
     * - SDK 自动处理确认和权益发放
     * - 关联 waitingPayments 中的回调
     * - 对开发者透明，无需手动操作
     */
    private fun handleIncomingPurchase(
        code: PaymentCode,
        purchaseDetailList: List<PaymentPurchaseDetails>
    ) {
        val provider = paymentProvider
        if (!isInitialized || provider == null) {
            LogUtil.e("PaymentManager not initialized, please call setPaymentProvider first")
            return
        }
        when (code) {
            PaymentCode.OK -> {
                LogUtil.d("生成订单成功=${purchaseDetailList.size}")
                purchaseDetailList.forEach { purchase ->
                    if (purchase.purchaseState == PaymentPurchaseState.PURCHASED) {
                        val config = getPaymentConfig()
                        val productId = purchase.products.first()
                        val isConsumable = config.isConsumable(productId)
                        val isNonConsumable = config.isNonConsumable(productId)
                        val isSubs = config.isSubs(productId)

                        when {
                            // 订阅商品
                            isSubs -> {
                                if (purchase.isAcknowledged) {
                                    LogUtil.d("订阅商品已确认，激活权益：$productId")
                                    subsEntitlements.add(productId)
                                    synchronized(waitingPayments) {
                                        waitingPayments.remove(purchase.key)
                                            ?.onSuccess(purchase)
                                    }
                                } else {
                                    LogUtil.d("订阅商品未确认，开始确认：$productId")
                                    provider.acknowledgePurchase(purchase.purchaseToken) { isSuccess ->
                                        if (isSuccess) {
                                            LogUtil.d("订阅商品确认成功，激活权益：$productId")
                                            subsEntitlements.add(productId)
                                            synchronized(waitingPayments) {
                                                waitingPayments.remove(purchase.key)
                                                    ?.onSuccess(purchase)
                                            }
                                        } else {
                                            LogUtil.e("订阅商品确认失败：$productId")
                                            // 通知回调：支付成功但确认失败
                                            synchronized(waitingPayments) {
                                                waitingPayments.remove(purchase.key)
                                                    ?.onConfirmFailed(purchase)
                                            }
                                        }
                                    }
                                }
                            }

                            // 非消耗商品（永久有效）
                            isNonConsumable -> {
                                if (purchase.isAcknowledged) {
                                    LogUtil.d("非消耗商品已确认，激活永久权益：$productId")
                                    nonConsumableEntitlements.add(productId)
                                    synchronized(waitingPayments) {
                                        waitingPayments.remove(purchase.key)
                                            ?.onSuccess(purchase)
                                    }
                                } else {
                                    LogUtil.d("非消耗商品未确认，开始确认：$productId")
                                    provider.acknowledgePurchase(purchase.purchaseToken) { isSuccess ->
                                        if (isSuccess) {
                                            LogUtil.d("非消耗商品确认成功，激活永久权益：$productId")
                                            nonConsumableEntitlements.add(productId)
                                            synchronized(waitingPayments) {
                                                waitingPayments.remove(purchase.key)
                                                    ?.onSuccess(purchase)
                                            }
                                        } else {
                                            LogUtil.e("非消耗商品确认失败：$productId")
                                            // 通知回调：支付成功但确认失败
                                            synchronized(waitingPayments) {
                                                waitingPayments.remove(purchase.key)
                                                    ?.onConfirmFailed(purchase)
                                            }
                                        }
                                    }
                                }
                            }

                            // 可消耗商品（添加到库存，等待消费）
                            isConsumable -> {
                                if (purchase.isAcknowledged) {
                                    LogUtil.d("可消耗商品已确认，添加到库存：$productId")
                                    consumableInventory.add(productId)
                                    synchronized(waitingPayments) {
                                        waitingPayments.remove(purchase.key)
                                            ?.onSuccess(purchase)
                                    }
                                }else{
                                    LogUtil.d("可消耗商品未确认，开始确认：$productId")
                                    provider.acknowledgePurchase(purchase.purchaseToken) { isSuccess ->
                                        if (isSuccess) {
                                            LogUtil.d("可消耗商品确认成功，添加到库存：$productId")
                                            consumableInventory.add(productId)
                                            synchronized(waitingPayments) {
                                                waitingPayments.remove(purchase.key)
                                                    ?.onSuccess(purchase)
                                            }
                                        } else {
                                            LogUtil.e("可消耗商品确认失败：$productId")
                                            // 通知回调：支付成功但确认失败
                                            synchronized(waitingPayments) {
                                                waitingPayments.remove(purchase.key)
                                                    ?.onConfirmFailed(purchase)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                    } else if (purchase.purchaseState == PaymentPurchaseState.PENDING) {
                        LogUtil.d("线下、信用卡等延迟支付，即用户已经付款，但是这些渠道还没把钱转到 google play billing 等订阅平台，需提示用户正在等待打款成功")
                        // 通知回调：支付待处理
                        synchronized(waitingPayments) {
                            waitingPayments.remove(purchase.key)?.onPending(purchase)
                        }
                    } else {
                        LogUtil.d("订单的购买状态未明或在某些测试场景下状态无效，可以记录并忽略，极少出现")
                        synchronized(waitingPayments) {
                            waitingPayments.remove(purchase.key)?.onFailure(PaymentCode.ERROR)
                        }
                    }
                }
            }

            PaymentCode.USER_CANCELED -> {
                LogUtil.d("用户取消了支付")
                purchaseDetailList.forEach { purchase ->
                    synchronized(waitingPayments) {
                        waitingPayments.remove(purchase.key)?.onUserCancel()
                    }
                }
            }

            else -> {
                LogUtil.e("网络错误等等问题导致支付失败：$code")
                purchaseDetailList.forEach { purchase ->
                    synchronized(waitingPayments) {
                        waitingPayments.remove(purchase.key)?.onFailure(code)
                    }
                }
            }
        }
    }


    /**
     * 发起支付请求。为了保证支付流程的简洁，一种类型的商品对应一个订单
     *
     * @param activity 用于支付流程的 Activity。
     * @param key 标识符
     * @param productId 商品 ID。
     * @param offerId 优惠方案 ID。
     * @param callback 支付结果回调。
     */
    fun makePayment(
        activity: Activity,
        key: String,
        productId: String,
        offerId: String,
        callback: PaymentCallback
    ) {
        val provider = paymentProvider
        val config = paymentConfig
        if (!isInitialized || provider == null || config == null) {
            LogUtil.e("PaymentManager not initialized, please call init first")
            callback.onFailure(PaymentCode.SERVICE_UNAVAILABLE)
            return
        }

        // 自动识别商品类型
        val productType = config.getProductType(productId)

        // 将回调存入待处理队列
        synchronized(waitingPayments) {
            waitingPayments[key] = callback
        }

        // 发起支付请求
        provider.makePayment(
            activity, key, productType, productId, offerId,
            object : MakePaymentCallback {
                override fun onSuccess() {
                    // 底层拉起支付流程成功（ launchBillingFlow 返回 OK ），但真正的购买结果通过 handleIncomingPurchase 异步返回
                    LogUtil.d("支付平台，拉起成功: $key")
                }

                override fun onFailure(errorCode: PaymentCode) {
                    // 底层拉起支付流程失败
                    LogUtil.e("支付平台，拉起失败: $key, error: $errorCode")
                    synchronized(waitingPayments) {
                        waitingPayments.remove(key)
                    }
                    callback.onFailure(errorCode)
                }
            })
    }

    fun removePaymentCallback(key: String) {
        synchronized(waitingPayments) {
            waitingPayments.remove(key)
        }
    }


    /**
     * 恢复未完成的订单（应用重启后调用）。
     * 查询所有未确认/未消费的订单并自动处理确认和权益发放。
     *
     * @param productType 商品类型。
     * @param onOrderRecovered 业务层回调，仅用于通知哪些商品被恢复了（每个订单确认成功后触发）。
     * @param onComplete 所有订单恢复完成后的回调（所有确认操作都已完成）。
     */
    fun recoverUnfinishedOrders(
        productType: PaymentProductType,
        onOrderRecovered: (purchase: PaymentPurchaseDetails) -> Unit = {},
        onComplete: () -> Unit = {}
    ) {
        val provider = paymentProvider
        if (!isInitialized || provider == null) {
            LogUtil.e("PaymentManager not initialized, please call init first")
            onComplete()
            return
        }

        provider.queryPurchases(productType, object : QueryPurchasesCallback {
            override fun onQuerySuccess(purchases: List<PaymentPurchaseDetails>) {
                if (purchases.isEmpty()) {
                    onComplete()
                    return
                }

                // 统计需要处理的订单数量
                var processedCount = 0
                val totalToProcess = purchases.size

                // 遍历所有订单进行处理
                purchases.forEach { purchase ->
                    if (purchase.purchaseState == PaymentPurchaseState.PURCHASED) {
                        val productId = purchase.products.first()
                        val config = getPaymentConfig()

                        when {
                            // 订阅商品：需要检查确认状态
                            config.isSubs(productId) -> {
                                if (purchase.isAcknowledged) {
                                    LogUtil.d("恢复订阅 - 已确认，激活权益：$productId")
                                    subsEntitlements.addAll(purchase.products)
                                    onOrderRecovered(purchase)
                                    processedCount++
                                } else {
                                    LogUtil.d("恢复订阅 - 未确认，开始确认：$productId")
                                    provider.acknowledgePurchase(purchase.purchaseToken) { isSuccess ->
                                        if (isSuccess) {
                                            LogUtil.d("恢复订阅 - 确认成功，激活权益：$productId")
                                            subsEntitlements.addAll(purchase.products)
                                            onOrderRecovered(purchase)
                                        } else {
                                            LogUtil.e("恢复订阅 - 确认失败：$productId")
                                        }
                                        processedCount++
                                        if (processedCount >= totalToProcess) {
                                            LogUtil.d("恢复订单 - 全部完成，共处理 ${purchases.size} 个订单")
                                            onComplete()
                                        }
                                    }
                                }
                            }

                            // 非消耗商品（永久）：需要检查确认状态
                            config.isNonConsumable(productId) -> {
                                if (purchase.isAcknowledged) {
                                    LogUtil.d("恢复非消耗商品 - 已确认，激活永久权益：$productId")
                                    nonConsumableEntitlements.addAll(purchase.products)
                                    onOrderRecovered(purchase)
                                    processedCount++

                                } else {
                                    LogUtil.d("恢复非消耗商品 - 未确认，开始确认：$productId")
                                    provider.acknowledgePurchase(purchase.purchaseToken) { isSuccess ->
                                        if (isSuccess) {
                                            LogUtil.d("恢复非消耗商品 - 确认成功，激活永久权益：$productId")
                                            nonConsumableEntitlements.addAll(purchase.products)
                                            onOrderRecovered(purchase)
                                        } else {
                                            LogUtil.e("恢复非消耗商品 - 确认失败：$productId")
                                        }
                                        processedCount++
                                        if (processedCount >= totalToProcess) {
                                            LogUtil.d("恢复订单 - 全部完成，共处理 ${purchases.size} 个订单")
                                            onComplete()
                                        }
                                    }
                                }
                            }

                            // 可消耗商品：确认后会被消费，不会出现在订单中
                            // 能查询到说明还未消费，直接添加到库存
                            config.isConsumable(productId) -> {
                                if (purchase.isAcknowledged){
                                    LogUtil.d("恢复可消耗商品 - 已确认，添加到库存：$productId")
                                    consumableInventory.addAll(purchase.products)
                                    onOrderRecovered(purchase)
                                    processedCount++
                                }else{
                                    LogUtil.d("恢复可消耗商品 - 未消费，添加到库存：$productId")
                                    provider.consumePurchase(purchase.purchaseToken){isSuccess ->
                                        if (isSuccess) {
                                            LogUtil.d("恢复可消耗商品 - 消费成功，添加到库存：$productId")
                                            consumableInventory.addAll(purchase.products)
                                            onOrderRecovered(purchase)
                                        } else {
                                            LogUtil.e("恢复可消耗商品 - 消费失败：$productId")
                                        }
                                        processedCount++
                                        if (processedCount >= totalToProcess) {
                                            LogUtil.d("恢复订单 - 全部完成，共处理 ${purchases.size} 个订单")
                                            onComplete()
                                        }
                                    }
                                }
                            }
                        }
                    } else if (purchase.purchaseState == PaymentPurchaseState.PENDING) {
                        // PENDING 状态的订单，暂时不处理，等待后续支付平台通知
                        LogUtil.d("恢复订单 - 待处理状态，跳过：${purchase.products.first()}")
                        processedCount++
                        if (processedCount >= totalToProcess) {
                            onComplete()
                        }
                    } else {
                        // 其他无效状态，跳过
                        LogUtil.d("恢复订单 - 无效状态，跳过：${purchase.products.first()}")
                        processedCount++
                        if (processedCount >= totalToProcess) {
                            onComplete()
                        }
                    }

                    // 检查是否所有订单都处理完成
                    if (processedCount >= totalToProcess) {
                        LogUtil.d("恢复订单 - 全部完成，共处理 ${purchases.size} 个订单")
                        onComplete()
                    }
                }
            }

            override fun onQueryFailure(errorCode: PaymentCode) {
                LogUtil.e("恢复订单失败：$errorCode")
                onComplete()
            }
        })
    }

    /**
     * 清理资源，防止内存泄露。
     * 在应用退出或不再需要支付功能时调用（如 Application.onTerminate 或 MainActivity.onDestroy）。
     */
    fun cleanup() {
        // 清理缓存和状态
        subsEntitlements.clear()
        nonConsumableEntitlements.clear()
        consumableInventory.clear()

        // 清理当前支付提供者的资源
        paymentProvider?.cleanup()
        paymentProvider = null
        isInitialized = false

        LogUtil.d("PaymentManager cleaned up")
    }

    companion object {
        const val TAG = "PaymentManager"

        // 单例实例，使用 @Volatile 保证线程安全
        @Volatile
        private var INSTANCE: PaymentManager? = null

        /**
         * 获取 PaymentManager 单例实例。
         *
         * @return PaymentManager 实例。
         */
        fun getInstance(): PaymentManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: PaymentManager().also { INSTANCE = it }
            }
    }
}


























