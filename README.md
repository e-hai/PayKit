# PayKit - Google Play 支付 SDK

## 📖 简介

PayKit 是一个轻量级的 Android 支付结算库，封装了 Google Play Billing Library，提供简洁的协程 API。

**核心特性：**
- ✅ 完整的支付流程封装（查询、购买、确认）
- ✅ Kotlin 协程支持，无回调地狱
- ✅ 自动处理订单确认和权益同步
- ✅ 支持订阅商品、消耗型商品、非消耗型商品
- ✅ 内置订单恢复机制，防止掉单
- ✅ 线程安全，支持多模块并发调用

---

## 🚀 快速集成

### 步骤 1：添加依赖

在 `build.gradle` 中添加：

```gradle
dependencies {
    // Google Billing Library
    implementation "com.android.billingclient:billing:8.3.0"
    implementation "com.android.billingclient:billing-ktx:8.3.0"
    
    // PayKit SDK (本地模块或远程依赖)
    implementation project(':pay')
}
```

### 步骤 2：配置商品 ID

在 Google Play Console 创建商品后，记录商品 ID：

```kotlin
// Constants.kt
object Constants {
    // 订阅商品
    const val SUBS_PRODUCT_MONTH = "sub_monthly"
    const val SUBS_PRODUCT_YEAR = "sub_yearly"
    
    // 消耗型商品（可重复购买）
    const val CONSUMABLE_COINS_100 = "coins_100"
    
    // 非消耗型商品（永久拥有）
    const val NON_CONSUMABLE_PREMIUM = "premium_unlock"
}
```

### 步骤 3：初始化 SDK

在 Application 或 Activity 中初始化：

```kotlin
class MainViewModel(private val app: Application) : AndroidViewModel(app) {

    fun init() {
        // 1. 配置商品类型
        val configuration = PayKitConfiguration(
            subsProductIds = setOf(
                Constants.SUBS_PRODUCT_MONTH,
                Constants.SUBS_PRODUCT_YEAR
            ),
            consumableProductIds = setOf(
                Constants.CONSUMABLE_COINS_100
            ),
            nonConsumableProductIds = setOf(
                Constants.NON_CONSUMABLE_PREMIUM
            )
        )

        // 2. 初始化 SDK
        PayKit.configure(app, configuration)
        
        // 3. 监听权益状态变化（可选）
        PayKit.shared.setUpdatedCustomerInfoListener(object : UpdatedCustomerInfoListener {
            override fun onReceived(customerInfo: CustomerInfo) {
                Log.d("PayKit", "权益状态更新: ${customerInfo.activeSubscriptions}")
                updateUiWithCustomerInfo(customerInfo)
            }
        })
    }
    
    private fun updateUiWithCustomerInfo(info: CustomerInfo) {
        val isVip = info.activeSubscriptions.contains(Constants.SUBS_PRODUCT_MONTH) || 
                    info.activeSubscriptions.contains(Constants.SUBS_PRODUCT_YEAR)
        // 更新 UI
    }
}
```

---

## 💡 使用指南

### 场景 1：查询商品详情

```kotlin
viewModelScope.launch {
    try {
        val productIds = setOf(
            Constants.SUBS_PRODUCT_MONTH,
            Constants.CONSUMABLE_COINS_100
        )
        
        val result = PayKit.shared.getProducts(productIds)
        
        result.onSuccess { products ->
            products.forEach { product ->
                Log.d("PayKit", "商品: ${product.productId}")
                Log.d("PayKit", "价格: ${product.price}")
                Log.d("PayKit", "标题: ${product.title}")
            }
        }.onFailure { error ->
            Log.e("PayKit", "查询失败: ${error.message}")
        }
    } catch (e: Exception) {
        Log.e("PayKit", "查询异常: ${e.message}")
    }
}
```

### 场景 2：发起支付

```kotlin
fun purchaseProduct(activity: Activity, productId: String) {
    viewModelScope.launch {
        try {
            // 1. 查询商品详情
            val result = PayKit.shared.getProducts(setOf(productId))
            
            result.onSuccess { products ->
                val product = products.firstOrNull()
                if (product != null) {
                    // 2. 发起支付
                    PayKit.shared.purchase(activity, product, object : PurchaseCallback {
                        override fun onCompleted(
                            storeTransaction: StoreTransaction,
                            customerInfo: CustomerInfo
                        ) {
                            Log.d("PayKit", "支付成功: ${storeTransaction.orderId}")
                            // 支付成功，SDK 已自动确认订单
                            // 可以立即发放权益
                        }

                        override fun onError(error: PayKitError, userCancelled: Boolean) {
                            if (userCancelled) {
                                Log.d("PayKit", "用户取消支付")
                            } else {
                                Log.e("PayKit", "支付失败: ${error.message}")
                            }
                        }
                    })
                }
            }.onFailure { error ->
                Log.e("PayKit", "查询商品失败: ${error.message}")
            }
        } catch (e: Exception) {
            Log.e("PayKit", "支付异常: ${e.message}")
        }
    }
}
```

### 场景 3：检查用户权益

```kotlin
// 方法 1：主动查询（推荐在应用启动时调用）
viewModelScope.launch {
    val customerInfo = PayKit.shared.getCustomerInfo()
    if (customerInfo != null) {
        val isVip = customerInfo.activeSubscriptions.contains(Constants.SUBS_PRODUCT_MONTH)
        Log.d("PayKit", "是否 VIP: $isVip")
    }
}

// 方法 2：通过监听器自动接收更新（已在初始化时设置）
// 当权益状态变化时，UpdatedCustomerInfoListener 会自动回调
```

### 场景 4：恢复未完成订单

**重要：** 每次应用启动时调用，防止掉单。

```kotlin
fun recoverUnfinishedOrders() {
    viewModelScope.launch {
        try {
            // getCustomerInfo 内部会自动查询并确认未完成的订单
            val customerInfo = PayKit.shared.getCustomerInfo()
            
            if (customerInfo != null) {
                Log.d("PayKit", "订单恢复完成")
                Log.d("PayKit", "活跃订阅: ${customerInfo.activeSubscriptions}")
                Log.d("PayKit", "已购商品: ${customerInfo.nonSubscriptionTransactions}")
            } else {
                Log.e("PayKit", "订单恢复失败")
            }
        } catch (e: Exception) {
            Log.e("PayKit", "恢复订单异常: ${e.message}")
        }
    }
}
```

**最佳实践：**
- 在 `Application.onCreate()` 或 `MainActivity.onCreate()` 中调用
- 在 SDK 初始化完成后立即调用
- 提供"恢复购买"按钮供用户手动触发

---

## 🏗️ 核心 API

### PayKit 单例

```kotlin
// 获取单例
val payKit = PayKit.shared

// 主要方法
suspend fun getProducts(productIds: Set<String>): Result<List<StoreProduct>>
suspend fun getCustomerInfo(): CustomerInfo?
fun purchase(activity: Activity, storeProduct: StoreProduct, callback: PurchaseCallback)
fun setUpdatedCustomerInfoListener(listener: UpdatedCustomerInfoListener?)
```

### 数据模型

#### CustomerInfo - 用户权益信息
```kotlin
data class CustomerInfo(
    val activeSubscriptions: Set<String>,           // 活跃的订阅商品 ID
    val nonSubscriptionTransactions: List<StoreTransaction>  // 非订阅交易列表
)
```

#### StoreProduct - 商品详情
```kotlin
data class StoreProduct(
    val productId: String,          // 商品 ID
    val type: ProductType,          // 商品类型
    val title: String,              // 标题
    val description: String,        // 描述
    val price: String,              // 格式化价格（如 "$9.99"）
    val priceAmountMicros: Long,    // 价格（微单位）
    val priceCurrencyCode: String,  // 货币代码（如 "USD"）
    val subscriptionToken: String?  // 订阅令牌（仅订阅商品）
)
```

#### StoreTransaction - 交易记录
```kotlin
data class StoreTransaction(
    val orderId: String,            // 订单 ID
    val productIds: List<String>,   // 商品 ID 列表
    val purchaseTime: Long,         // 购买时间戳
    val purchaseToken: String,      // 购买令牌
    val isAcknowledged: Boolean     // 是否已确认
)
```

### 枚举类型

#### ProductType - 商品类型
```kotlin
enum class ProductType {
    SUBS,   // 订阅商品
    INAPP   // 一次性商品
}
```

#### ErrorCode - 错误码
```kotlin
enum class ErrorCode {
    OK,                      // 成功
    PURCHASE_CANCELLED,      // 用户取消
    PRODUCT_NOT_AVAILABLE,   // 商品不可用
    NETWORK_ERROR,           // 网络错误
    STORE_PROBLEM            // 商店问题
}
```

---

## 📋 商品类型说明

### 订阅商品 (SUBS)
- **特点**：周期性扣费（包月/包年）
- **配置**：在 Google Play Console 创建订阅计划
- **处理**：SDK 自动调用 `acknowledgePurchase` 确认
- **示例**：会员订阅、高级功能解锁

### 消耗型商品 (INAPP - Consumable)
- **特点**：消费后可再次购买
- **处理**：SDK 自动调用 `consumePurchase` 消耗
- **示例**：游戏金币、临时道具

### 非消耗型商品 (INAPP - Non-consumable)
- **特点**：永久拥有，不可重复购买
- **处理**：SDK 自动调用 `acknowledgePurchase` 确认
- **示例**：永久皮肤、去广告

---

## ⚠️ 注意事项

1. **测试环境**：
   - 在 Google Play Console 配置测试账号
   - 使用内部测试轨道发布 APK
   - 确保商品已上架（即使是草稿状态也可测试）

2. **商品 ID 匹配**：
   - 代码中的商品 ID 必须与 Google Play Console 完全一致
   - 区分大小写

3. **订单确认**：
   - SDK 会自动确认所有订单，无需手动处理
   - 订阅和非消耗商品调用 `acknowledgePurchase`
   - 消耗商品调用 `consumePurchase`

4. **线程安全**：
   - 所有 suspend 函数应在协程中调用
   - 推荐使用 `viewModelScope.launch`

5. **欧盟政策**：
   - SDK 已启用个性化报价支持
   - 符合欧盟数字服务法案要求

---

## 🔧 常见问题

### Q1: 查询商品返回空列表？
**A:** 检查以下几点：
- 商品 ID 是否正确
- 应用签名是否与 Google Play Console 一致
- 测试账号是否配置正确
- 商品是否已创建（即使是草稿状态）

### Q2: 如何处理跨设备购买？
**A:** Google Play 会自动同步购买记录。使用相同 Google 账号登录的设备可以通过 `getCustomerInfo()` 恢复购买。

### Q3: 订阅过期后如何处理？
**A:** 定期调用 `getCustomerInfo()` 检查订阅状态，SDK 会自动更新 `activeSubscriptions`。

### Q4: 支付成功后如何发放权益？
**A:** 在 `PurchaseCallback.onCompleted` 中立即发放权益，SDK 已确保订单已确认。

### Q5: 为什么需要恢复订单？
**A:** 防止以下场景导致掉单：
- 用户在支付过程中应用被强退
- 网络问题导致确认失败
- 跨设备购买需要同步权益

---

## 📦 版本信息

- **Billing Library**: 8.3.0
- **最低 SDK**: API 21 (Android 5.0)
- **语言**: Kotlin
- **架构**: MVVM + Coroutines

---

## 📞 技术支持

如有问题，请查看日志输出或联系开发团队。

**关键日志标签：**
- `PayKit`: SDK 核心日志
- `GoogleBillingWrapper`: Google Play 交互日志
