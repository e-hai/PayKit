package com.kit.pay

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.kit.pay.billing.BillingAbstract
import com.kit.pay.billing.GoogleBillingWrapper
import com.kit.pay.billing.PayKitPurchasesUpdatedListener
import com.kit.pay.caching.ConsumableLedger
import com.kit.pay.caching.DeviceCache
import com.kit.pay.interfaces.*
import com.kit.pay.models.*
import com.kit.pay.subscriber.CustomerInfoHelper
import com.kit.pay.utils.LogUtil
import com.kit.pay.utils.MainThreadDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * PayKit 核心单例类。
 *
 * 负责管理支付流程的各个方面，包括：
 * - 商品查询和管理
 * - 支付发起和回调处理
 * - 订单确认和权益同步
 * - 用户权益状态监听
 *
 * 使用示例：
 * ```kotlin
 * PayKit.configure(context, configuration)
 * val payKit = PayKit.shared
 * ```
 */
class PayKit private constructor(
    private val applicationContext: Context,
    private val applicationScope: CoroutineScope,
    private val configuration: PayKitConfiguration
) : PayKitPurchasesUpdatedListener {

    private val deviceCache = DeviceCache(applicationContext)
    private val consumableLedger = ConsumableLedger(applicationContext)
    private val customerInfoHelper = CustomerInfoHelper(configuration)
    private val billingWrapper: BillingAbstract = GoogleBillingWrapper(applicationContext)
    private var updateListener: UpdatedCustomerInfoListener? = null

    @Volatile
    private var purchaseVerifier: PurchaseVerifier = configuration.purchaseVerifier

    /** 保护 [activePurchaseCallback] 的读写，避免并发 purchase 互相覆盖 */
    private val purchaseLock = Any()

    // 用于暂存由 Activity 发起的购买回调
    private var activePurchaseCallback: PurchaseCallback? = null

    init {
        billingWrapper.purchasesUpdatedListener = this
    }

    /**
     * 初始化 SDK 内部组件：连接 Billing → 自动 [syncPurchasesInternal]。
     * 本地缓存由宿主通过 [getCustomerInfo]（`forceSync = false`）按需读取。
     */
    private fun initialize() {
        billingWrapper.startConnection(
            onConnected = {
                LogUtil.d("init connected startSync=true")
                applicationScope.launch {
                    syncPurchasesInternal()
                }
            },
            onError = { error ->
                LogUtil.e("init connect fail code=${error.code} msg=${error.message}")
            }
        )
    }

    private fun dispatchCustomerInfo(info: CustomerInfo) {
        val listener = updateListener ?: return
        MainThreadDispatcher.post { listener.onReceived(info) }
    }

    /**
     * 取出当前购买回调并在主线程执行；保证只投递一次。
     */
    private fun dispatchPurchaseCallback(block: (PurchaseCallback) -> Unit) {
        val callback = synchronized(purchaseLock) {
            val cb = activePurchaseCallback ?: return
            activePurchaseCallback = null
            cb
        }
        MainThreadDispatcher.post { block(callback) }
    }

    /**
     * 向 Google 同步购买记录：确认未处理的 PURCHASED、更新缓存与监听器。
     *
     * - SUBS / INAPP 并行查询
     * - 订阅 / 非消耗：acknowledge；失败不计入权益且返回 failure
     * - 消耗品：仅当履约账本已标记「已发货待 consume」时才 consume；新单留给宿主发货
     */
    private suspend fun syncPurchasesInternal(): Result<CustomerInfo> = coroutineScope {
        LogUtil.d("syncPurchases start")
        val cachedInfo = deviceCache.getCachedCustomerInfo()

        val subsDeferred = async { billingWrapper.queryPurchasesAsync(ProductType.SUBS) }
        val inappDeferred = async { billingWrapper.queryPurchasesAsync(ProductType.INAPP) }
        val subsResult = subsDeferred.await()
        val inappResult = inappDeferred.await()

        val allTransactions = mutableListOf<StoreTransaction>()
        subsResult.onSuccess { allTransactions.addAll(it) }
        inappResult.onSuccess { allTransactions.addAll(it) }

        LogUtil.d(
            "syncPurchases queried " +
                "subsOk=${subsResult.isSuccess} inappOk=${inappResult.isSuccess} " +
                "count=${allTransactions.size}"
        )

        if (subsResult.isFailure || inappResult.isFailure) {
            val error = subsResult.exceptionOrNull()
                ?: inappResult.exceptionOrNull()
                ?: PayKitError(ErrorCode.STORE_PROBLEM, "Failed to query purchases")
            LogUtil.e(
                "syncPurchases queryFail " +
                    "subsOk=${subsResult.isSuccess} inappOk=${inappResult.isSuccess} " +
                    "fallbackCache=${cachedInfo != null} msg=${error.message}"
            )
            return@coroutineScope if (cachedInfo != null) {
                Result.success(cachedInfo)
            } else {
                Result.failure(error)
            }
        }

        val transactionsForCompute = allTransactions.toMutableList()
        var acknowledgeFailure: Throwable? = null

        val purchasedTransactions = allTransactions.filter {
            it.purchaseState == PurchaseState.PURCHASED
        }
        for (txn in purchasedTransactions) {
            val isConsumable = customerInfoHelper.isConsumable(txn)
            if (isConsumable) {
                if (!consumableLedger.isFulfilledPendingConsume(txn.purchaseToken)) {
                    // 待宿主发货：不在此处 consume
                    continue
                }
                val consumeResult = billingWrapper.consumeAndAcknowledge(txn, isConsumable = true)
                if (consumeResult.isSuccess) {
                    consumableLedger.remove(txn.purchaseToken)
                    val index = transactionsForCompute.indexOfFirst {
                        it.purchaseToken == txn.purchaseToken
                    }
                    if (index >= 0) {
                        transactionsForCompute[index] = txn.copy(isAcknowledged = true)
                    }
                    LogUtil.d(
                        "syncPurchases consumeOk orderId=${txn.orderId} " +
                            "products=${txn.productIds}"
                    )
                } else {
                    val error = consumeResult.exceptionOrNull()
                        ?: PayKitError(ErrorCode.STORE_PROBLEM, "Consume failed")
                    LogUtil.e(
                        "syncPurchases consumeFail orderId=${txn.orderId} " +
                            "products=${txn.productIds} msg=${error.message}"
                    )
                    if (acknowledgeFailure == null) {
                        acknowledgeFailure = error
                    }
                }
                continue
            }

            if (txn.isAcknowledged) {
                continue
            }
            val ackResult = billingWrapper.consumeAndAcknowledge(txn, isConsumable = false)
            if (ackResult.isSuccess) {
                val index = transactionsForCompute.indexOfFirst {
                    it.purchaseToken == txn.purchaseToken
                }
                if (index >= 0) {
                    transactionsForCompute[index] = txn.copy(isAcknowledged = true)
                }
            } else {
                val error = ackResult.exceptionOrNull()
                    ?: PayKitError(ErrorCode.STORE_PROBLEM, "Acknowledge failed")
                LogUtil.e(
                    "syncPurchases ackFail orderId=${txn.orderId} " +
                        "products=${txn.productIds} msg=${error.message}"
                )
                if (acknowledgeFailure == null) {
                    acknowledgeFailure = error
                }
            }
        }

        // 已 consume 成功的消耗品不再出现在 Google 列表；从快照中去掉已确认消耗项更干净
        val snapshot = transactionsForCompute.filterNot {
            customerInfoHelper.isConsumable(it) && it.isAcknowledged
        }
        val fulfilledTokens = consumableLedger.getAll()
            .map { it.purchaseToken }
            .toSet()
        val newInfo = customerInfoHelper.computeCustomerInfo(snapshot, fulfilledTokens)
        deviceCache.cacheCustomerInfo(newInfo)
        dispatchCustomerInfo(newInfo)

        return@coroutineScope if (acknowledgeFailure != null) {
            LogUtil.e(
                "syncPurchases done success=false " +
                    "activeSubs=${newInfo.activeSubscriptions.size} " +
                    "unfulfilledConsumables=${newInfo.unfulfilledConsumables.size} " +
                    "pending=${newInfo.pendingPurchases.size} " +
                    "msg=${acknowledgeFailure.message}"
            )
            Result.failure(
                PayKitError(
                    ErrorCode.STORE_PROBLEM,
                    "Failed to acknowledge/consume one or more purchases: ${acknowledgeFailure.message}"
                )
            )
        } else {
            LogUtil.d(
                "syncPurchases done success=true " +
                    "activeSubs=${newInfo.activeSubscriptions.size} " +
                    "nonConsumables=${newInfo.nonConsumablePurchases.size} " +
                    "unfulfilledConsumables=${newInfo.unfulfilledConsumables.size} " +
                    "ledger=${fulfilledTokens.size} " +
                    "records=${newInfo.allPurchaseRecords.size}"
            )
            Result.success(newInfo)
        }
    }

    // ==========================================
    // Public Facing APIs
    // ==========================================

    fun setUpdatedCustomerInfoListener(listener: UpdatedCustomerInfoListener?) {
        this.updateListener = listener
    }

    /**
     * 设置 / 替换验单扩展。默认 [LocalPurchaseVerifier]。
     * 传入自定义实现即可对接服务端，无需改购买主流程。
     */
    fun setPurchaseVerifier(verifier: PurchaseVerifier) {
        this.purchaseVerifier = verifier
        LogUtil.d("setPurchaseVerifier impl=${verifier::class.java.simpleName}")
    }

    private suspend fun verifyPurchase(transaction: StoreTransaction): PayKitError? {
        val result = purchaseVerifier.verify(transaction)
        if (result.isSuccess) return null
        val err = result.exceptionOrNull()
        val payKitError = err as? PayKitError
            ?: PayKitError(
                ErrorCode.VERIFICATION_FAILED,
                err?.message ?: "Purchase verification failed"
            )
        LogUtil.e(
            "verifyPurchase fail orderId=${transaction.orderId} " +
                "products=${transaction.productIds} code=${payKitError.code} msg=${payKitError.message}"
        )
        return payKitError
    }

    /**
     * 获取用户权益。
     *
     * @param forceSync `true`（默认）时向 Google 同步后再返回；`false` 仅读本地缓存（可能为 null）
     */
    suspend fun getCustomerInfo(forceSync: Boolean = true): CustomerInfo? =
        withContext(Dispatchers.IO) {
            if (!forceSync) {
                return@withContext deviceCache.getCachedCustomerInfo()
            }
            return@withContext syncPurchasesInternal().getOrElse { error ->
                LogUtil.e("getCustomerInfo syncFail msg=${error.message}")
                deviceCache.getCachedCustomerInfo()
            }
        }

    /**
     * 同步购买记录（补单 / 刷新权益）。
     *
     * 适合：应用启动后主动刷新、支付 PENDING 之后再查、诊断掉单。
     * Google 无独立「恢复」API，本方法即向商店重新查询并更新本地状态。
     */
    suspend fun syncPurchases(): Result<CustomerInfo> = withContext(Dispatchers.IO) {
        syncPurchasesInternal()
    }

    /**
     * 恢复购买。
     *
     * 产品语义上的「恢复购买」按钮应调用本方法；实现与 [syncPurchases] 相同。
     * 可找回：当前有效订阅、未消耗的非消耗品 / 未 consume 的消耗品。
     * 已消耗的消耗型商品无法通过恢复买回。
     */
    suspend fun restorePurchases(): Result<CustomerInfo> = syncPurchases()

    /**
     * 当前待确认订单（会先 [syncPurchases]）。
     */
    suspend fun getPendingPurchases(): List<StoreTransaction> =
        getCustomerInfo(forceSync = true)?.pendingPurchases.orEmpty()

    /**
     * 当前商店可见的购买快照（先 sync）。不含已成功 consume 的消耗品。
     */
    suspend fun getPurchaseHistory(): List<StoreTransaction> =
        getCustomerInfo(forceSync = true)?.allPurchaseRecords.orEmpty()

    /**
     * 从本地缓存按 purchaseToken 查找交易（不同步商店）。
     */
    fun findTransaction(purchaseToken: String): StoreTransaction? {
        return deviceCache.getCachedCustomerInfo()
            ?.allPurchaseRecords
            ?.find { it.purchaseToken == purchaseToken }
    }

    /**
     * 履约账本中「已发货、待 consume」的消耗品（落盘）。
     */
    fun getConsumablesPendingConsume(): List<ConsumableLedgerEntry> =
        consumableLedger.getAll()

    /**
     * 宿主发货后调用：先经 [PurchaseVerifier] 验单，再写入履约账本并尝试 consume。
     *
     * - 验单失败：不记账本、不 consume
     * - consume 成功：从账本移除，并刷新 [CustomerInfo]
     * - consume 失败：保留账本条目，下次 [syncPurchases] 会重试
     */
    suspend fun markConsumableFulfilled(purchaseToken: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            val txn = findTransaction(purchaseToken)
                ?: return@withContext Result.failure(
                    PayKitError(
                        ErrorCode.PRODUCT_NOT_AVAILABLE,
                        "No local transaction for token"
                    )
                )
            if (!customerInfoHelper.isConsumable(txn)) {
                return@withContext Result.failure(
                    PayKitError(
                        ErrorCode.STORE_PROBLEM,
                        "Not a consumable product: ${txn.productIds}"
                    )
                )
            }
            verifyPurchase(txn)?.let { return@withContext Result.failure(it) }

            consumableLedger.markFulfilledPendingConsume(txn)
            LogUtil.d(
                "markConsumableFulfilled token=${purchaseToken.take(8)}… " +
                    "products=${txn.productIds}"
            )
            val consumeResult = billingWrapper.consumeAndAcknowledge(txn, isConsumable = true)
            if (consumeResult.isSuccess) {
                consumableLedger.remove(purchaseToken)
                syncPurchasesInternal()
                Result.success(Unit)
            } else {
                val error = consumeResult.exceptionOrNull()
                    ?: PayKitError(ErrorCode.STORE_PROBLEM, "Consume failed")
                LogUtil.e("markConsumableFulfilled consumeFail msg=${error.message}")
                Result.failure(
                    error as? PayKitError
                        ?: PayKitError(ErrorCode.STORE_PROBLEM, error.message ?: "Consume failed")
                )
            }
        }

    /**
     * 查询指定商品 ID 的详细信息。
     */
    suspend fun getProducts(productIds: Set<String>): Result<List<StoreProduct>> =
        withContext(Dispatchers.IO) {
            val subsIds = productIds.filter { configuration.subsProductIds.contains(it) }
            val inappIds = productIds.filter {
                configuration.consumableProductIds.contains(it) ||
                        configuration.nonConsumableProductIds.contains(it)
            }
            val unknownIds = productIds - subsIds.toSet() - inappIds.toSet()
            if (unknownIds.isNotEmpty()) {
                LogUtil.w("getProducts unknownIds=$unknownIds notInConfiguration=true")
            }
            if (productIds.isNotEmpty() && subsIds.isEmpty() && inappIds.isEmpty()) {
                return@withContext Result.failure(
                    PayKitError(
                        ErrorCode.PRODUCT_NOT_AVAILABLE,
                        "productIds not declared in PayKitConfiguration: $productIds"
                    )
                )
            }

            val allProducts = mutableListOf<StoreProduct>()

            if (subsIds.isNotEmpty()) {
                val subsResult = billingWrapper.queryProductDetailsAsync(
                    ProductType.SUBS,
                    subsIds.toSet()
                )
                if (subsResult.isFailure) {
                    return@withContext Result.failure(subsResult.exceptionOrNull()!!)
                }
                allProducts.addAll(subsResult.getOrNull() ?: emptyList())
            }

            if (inappIds.isNotEmpty()) {
                val inappResult = billingWrapper.queryProductDetailsAsync(
                    ProductType.INAPP,
                    inappIds.toSet()
                )
                if (inappResult.isFailure) {
                    return@withContext Result.failure(inappResult.exceptionOrNull()!!)
                }
                allProducts.addAll(inappResult.getOrNull() ?: emptyList())
            }

            return@withContext Result.success(allProducts)
        }

    /**
     * 从本地缓存查找某订阅 product 当前活跃订单（不同步商店）。
     * 用于构建 [SubscriptionReplacement]。
     */
    fun findActiveSubscription(productId: String): StoreTransaction? {
        val info = deviceCache.getCachedCustomerInfo() ?: return null
        if (productId !in info.activeSubscriptions) return null
        return info.purchasedRecords
            .filter { productId in it.productIds && it.isAcknowledged }
            .maxByOrNull { it.purchaseTime }
    }

    /**
     * 发起支付。
     *
     * 回调（[PurchaseCallback]）一律在**主线程**投递。
     * 同一时刻只允许一笔进行中的购买；若已有进行中购买，新请求会立刻
     * [PurchaseCallback.onError]（[ErrorCode.PURCHASE_IN_PROGRESS]），不会顶掉前一次回调。
     *
     * @param isOfferPersonalized 是否披露个性化价格（欧盟消费者保护要求）。
     * 默认取 [PayKitConfiguration.isOfferPersonalizedDefault]。
     * @param subscriptionReplacement 订阅升降级 / 同商品换档时传入；新购为 null。
     */
    fun purchase(
        activity: Activity,
        storeProduct: StoreProduct,
        callback: PurchaseCallback,
        isOfferPersonalized: Boolean = configuration.isOfferPersonalizedDefault,
        subscriptionReplacement: SubscriptionReplacement? = null
    ) {
        synchronized(purchaseLock) {
            if (activePurchaseCallback != null) {
                LogUtil.w(
                    "purchase rejected inProgress=true productId=${storeProduct.productId}"
                )
                MainThreadDispatcher.post {
                    callback.onError(
                        PayKitError(
                            ErrorCode.PURCHASE_IN_PROGRESS,
                            "Another purchase is already in progress"
                        ),
                        userCancelled = false
                    )
                }
                return
            }
            this.activePurchaseCallback = callback
        }
        LogUtil.d(
            "purchase start productId=${storeProduct.productId} type=${storeProduct.type} " +
                "personalized=$isOfferPersonalized " +
                "replace=${subscriptionReplacement?.oldProductId}"
        )

        applicationScope.launch {
            val result = billingWrapper.makePurchaseAsync(
                WeakReference(activity),
                storeProduct,
                isOfferPersonalized,
                subscriptionReplacement
            )
            result.onFailure { error ->
                LogUtil.e(
                    "purchase launchFail productId=${storeProduct.productId} msg=${error.message}"
                )
                dispatchPurchaseCallback { cb ->
                    cb.onError(
                        error as? PayKitError
                            ?: PayKitError(
                                ErrorCode.STORE_PROBLEM,
                                error.message ?: "Unknown error"
                            ),
                        false
                    )
                }
            }
        }
    }

    // ==========================================
    // Implements PayKitPurchasesUpdatedListener
    // ==========================================
    override fun onPurchasesUpdated(successfulPurchases: List<StoreTransaction>) {
        applicationScope.launch {
            val syncResult = syncPurchasesInternal()
            var latestInfo = syncResult.getOrNull()
            if (activePurchaseCallback == null) return@launch

            val purchased = successfulPurchases.filter {
                it.purchaseState == PurchaseState.PURCHASED
            }
            if (purchased.isNotEmpty()) {
                if (latestInfo != null) {
                    val primary = purchased.first()
                    verifyPurchase(primary)?.let { error ->
                        dispatchPurchaseCallback { cb ->
                            cb.onError(error, userCancelled = false)
                        }
                        return@launch
                    }

                    // 验单通过后再记履约账本 → onCompleted（宿主发货）→ consume
                    val consumables = purchased.filter { customerInfoHelper.isConsumable(it) }
                    for (txn in consumables) {
                        if (!consumableLedger.isFulfilledPendingConsume(txn.purchaseToken)) {
                            consumableLedger.markFulfilledPendingConsume(txn)
                        }
                    }
                    if (consumables.isNotEmpty()) {
                        val fulfilled = consumableLedger.getAll().map { it.purchaseToken }.toSet()
                        latestInfo = customerInfoHelper.computeCustomerInfo(
                            latestInfo.allPurchaseRecords,
                            fulfilled
                        )
                        deviceCache.cacheCustomerInfo(latestInfo)
                    }

                    LogUtil.d(
                        "purchase completed orderId=${primary.orderId} " +
                            "products=${primary.productIds}"
                    )
                    val infoForCallback = latestInfo
                    val callback = synchronized(purchaseLock) {
                        val cb = activePurchaseCallback
                        activePurchaseCallback = null
                        cb
                    }
                    if (callback != null) {
                        MainThreadDispatcher.run {
                            callback.onCompleted(primary, infoForCallback)
                        }
                    }

                    // 发货回调返回后再 consume；失败留在账本供下次 sync 重试
                    for (txn in consumables) {
                        val consumeResult =
                            billingWrapper.consumeAndAcknowledge(txn, isConsumable = true)
                        if (consumeResult.isSuccess) {
                            consumableLedger.remove(txn.purchaseToken)
                            LogUtil.d(
                                "purchase consumeOk orderId=${txn.orderId} " +
                                    "products=${txn.productIds}"
                            )
                        } else {
                            LogUtil.e(
                                "purchase consumeFail orderId=${txn.orderId} " +
                                    "msg=${consumeResult.exceptionOrNull()?.message}"
                            )
                        }
                    }
                    if (consumables.isNotEmpty()) {
                        syncPurchasesInternal()
                    }
                } else {
                    val error = syncResult.exceptionOrNull() as? PayKitError
                        ?: PayKitError(
                            ErrorCode.STORE_PROBLEM,
                            syncResult.exceptionOrNull()?.message
                                ?: "Sync failed after purchase"
                        )
                    LogUtil.e(
                        "purchase syncFailAfterPay orderId=${purchased.first().orderId} " +
                            "code=${error.code} msg=${error.message}"
                    )
                    dispatchPurchaseCallback { cb ->
                        cb.onError(error, userCancelled = false)
                    }
                }
                return@launch
            }

            val pending = successfulPurchases.filter {
                it.purchaseState == PurchaseState.PENDING
            }
            if (pending.isNotEmpty()) {
                LogUtil.d(
                    "purchase pending orderId=${pending.first().orderId} " +
                        "products=${pending.first().productIds}"
                )
                dispatchPurchaseCallback { cb ->
                    cb.onPending(pending.first())
                }
                return@launch
            }

            LogUtil.e("purchase updateEmpty count=${successfulPurchases.size}")
            dispatchPurchaseCallback { cb ->
                cb.onError(
                    PayKitError(ErrorCode.UNKNOWN, "No purchased transaction in update"),
                    userCancelled = false
                )
            }
        }
    }

    override fun onPurchasesFailedToUpdate(error: PayKitError, userCancelled: Boolean) {
        LogUtil.e(
            "purchase failed code=${error.code} cancelled=$userCancelled msg=${error.message}"
        )
        dispatchPurchaseCallback { cb ->
            cb.onError(error, userCancelled)
        }
    }

    companion object {
        @Volatile
        private var sharedInstance: PayKit? = null

        val shared: PayKit
            get() = sharedInstance ?: throw IllegalStateException("PayKit is not configured.")

        /**
         * 初始化 SDK（进程内只生效一次，除非先 [reset]）。
         */
        fun configure(
            context: Context,
            configuration: PayKitConfiguration
        ) {
            if (sharedInstance == null) {
                synchronized(this) {
                    if (sharedInstance == null) {
                        val applicationContext = context.applicationContext
                        val applicationScope = ProcessLifecycleOwner.get().lifecycleScope
                        val instance = PayKit(applicationContext, applicationScope, configuration)
                        sharedInstance = instance
                        instance.initialize()
                    } else {
                        LogUtil.w("configure ignored alreadyConfigured=true use reset() first")
                    }
                }
            } else {
                LogUtil.w("configure ignored alreadyConfigured=true use reset() first")
            }
        }

        /**
         * 断开 Billing、清空单例与本地权益缓存，便于测试或更换 [PayKitConfiguration]。
         * 之后需再次 [configure]。
         *
         * @param clearCache 是否清除权益缓存与消耗品履约账本（默认 true）
         */
        fun reset(clearCache: Boolean = true) {
            synchronized(this) {
                val instance = sharedInstance ?: return
                synchronized(instance.purchaseLock) {
                    instance.activePurchaseCallback = null
                }
                instance.updateListener = null
                runCatching { instance.billingWrapper.endConnection() }
                if (clearCache) {
                    runCatching { instance.deviceCache.clearCache() }
                    runCatching { instance.consumableLedger.clear() }
                }
                sharedInstance = null
                LogUtil.d("reset done clearCache=$clearCache")
            }
        }
    }
}
