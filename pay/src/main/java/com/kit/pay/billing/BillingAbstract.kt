package com.kit.pay.billing

import android.app.Activity
import com.kit.pay.interfaces.PayKitError
import com.kit.pay.models.ProductType
import com.kit.pay.models.StoreProduct
import com.kit.pay.models.StoreTransaction
import java.lang.ref.WeakReference

/**
 * 购买状态更新监听器。
 * 
 * 当用户的购买状态发生变化时（成功、失败、取消），会通过此监听器通知。
 */
interface PayKitPurchasesUpdatedListener {
    /**
     * 购买成功回调。
     * 
     * @param successfulPurchases 成功的购买记录列表
     */
    fun onPurchasesUpdated(successfulPurchases: List<StoreTransaction>)

    /**
     * 购买失败回调。
     * 
     * @param error 错误信息
     * @param userCancelled 是否为户主动取消
     */
    fun onPurchasesFailedToUpdate(error: PayKitError, userCancelled: Boolean)
}


/**
 * 支付服务抽象层。
 * 
 * 定义了与支付平台（如 Google Play）交互的核心接口，
 * 支持扩展不同的支付提供商实现。
 * 
 * 当前实现：[GoogleBillingWrapper]
 */
abstract class BillingAbstract {
    var purchasesUpdatedListener: PayKitPurchasesUpdatedListener? = null

    /**
     * 启动与支付服务的连接。
     * 
     * @param onConnected 连接成功回调
     * @param onError 连接失败回调
     */
    abstract fun startConnection(
        onConnected: () -> Unit,
        onError: (PayKitError) -> Unit
    )

    /**
     * 查询商品详细信息。
     * 
     * @param productType 商品类型（订阅/一次性）
     * @param productIds 要查询的商品 ID 集合
     * @return 查询结果，成功时包含商品列表，失败时包含错误信息
     */
    abstract suspend fun queryProductDetailsAsync(
        productType: ProductType,
        productIds: Set<String>
    ): Result<List<StoreProduct>>

    /**
     * 启动购买流程。
     * 
     * 此方法会启动支付界面，但不会等待支付完成。
     * 支付结果通过 [PayKitPurchasesUpdatedListener] 返回。
     * 
     * @param activity 当前 Activity
     * @param storeProduct 要购买的商品
     * @return 启动结果，成功表示界面已显示，失败表示启动失败
     */
    abstract suspend fun makePurchaseAsync(
        activity: WeakReference<Activity>,
        storeProduct: StoreProduct
    ): Result<Unit>

    /**
     * 查询用户的购买记录。
     * 
     * @param productType 商品类型（订阅/一次性）
     * @return 查询结果，成功时包含购买记录列表，失败时包含错误信息
     */
    abstract suspend fun queryPurchasesAsync(
        productType: ProductType
    ): Result<List<StoreTransaction>>

    /**
     * 确认或消耗购买记录。
     * 
     * - 对于消耗型商品：调用 consumePurchase
     * - 对于非消耗型商品和订阅：调用 acknowledgePurchase
     * 
     * @param transaction 购买记录
     * @param isConsumable 是否为消耗型商品
     * @return 操作结果，成功表示已确认/消耗，失败表示操作失败
     */
    abstract suspend fun consumeAndAcknowledge(
        transaction: StoreTransaction,
        isConsumable: Boolean
    ): Result<Unit>

    /**
     * 断开与支付服务的连接。
     * 
     * 在应用退出或不再需要支付功能时调用。
     */
    abstract fun endConnection()
}
