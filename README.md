# Payment SDK 集成指南

## 📖 简介

Payment SDK 是一个专为 Android 应用设计的支付结算库，目前仅支持 Google Play 结算系统。该 SDK 提供了完整的支付流程管理，包括商品查询、支付发起、订单确认、订阅状态检查等功能。

**核心特性：**
- ✅ 完整的支付流程封装
- ✅ 自动处理订单确认和权益发放
- ✅ 支持订阅商品、一次性消耗商品、一次性非消耗商品
- ✅ 内置订单恢复机制，防止掉单
- ✅ 线程安全，支持多模块并发调用
- ✅ 符合 Google Play 最新政策（欧盟个性化报价）

## 🏗️ 核心架构

### 1. PaymentManager - 支付管理器（单例）

**职责：** 管理支付逻辑的核心类，提供统一的 API 接口

**主要功能：**
- 初始化支付提供者
- 商品查询和管理
- 支付发起和回调处理
- 订单恢复和权益同步
- 订阅状态检查

**关键方法：**
```kotlin
// 初始化 SDK
fun init(provider: PaymentProvider, config: PaymentConfig, orderUpdateListener: PaymentCallback, initCallback: InitializationCallback)

// 查询商品详情（suspend 函数）
suspend fun queryProducts(productIds: List<String>): QueryProductsResult

// 发起支付（suspend 函数）
suspend fun makePayment(activity: Activity, key: String, productId: String, offerId: String): MakePaymentResult

// 恢复未完成订单（suspend 函数）
suspend fun recoverUnfinishedOrders(productType: PaymentProductType, onOrderRecovered: (purchase) -> Unit = {})

// 检查是否拥有某项权益
fun isEntitled(productId: String): Boolean
```

### 2. PaymentProvider - 支付提供者接口

**职责：** 定义支付服务的核心操作，支持扩展不同的支付平台

**当前实现：** `GoogleBillingProvider`（Google Play 结算）

**核心方法：**
```kotlin
interface PaymentProvider {
    fun initialize(callback: InitializationCallback)
    fun setPurchaseListener(listener: PurchaseCallback)
    suspend fun queryProducts(products: List<Pair<String, PaymentProductType>>): QueryProductsResult
    suspend fun makePayment(activityRef: WeakReference<Activity>, key: String, productType: PaymentProductType, productId: String, offerId: String): MakePaymentResult
    suspend fun acknowledgePurchase(purchaseToken: String): PaymentAcknowledgeResult
    suspend fun consumePurchase(purchaseToken: String): PaymentConsumeResult
    suspend fun queryPurchases(productType: PaymentProductType): QueryPurchasesResult
    fun cleanup()
}
```

### 3. GoogleBillingProvider - Google Play 结算实现

**职责：** 实现与 Google Play Billing Library 的交互

**核心功能：**
- 连接管理和自动重连
- 购买监听和回调处理
- 商品详情查询
- 订单确认和消费
- 待处理订单支持

**配置参数：**
```kotlin
class GoogleBillingProvider(private val app: Application) : PaymentProvider {
    // 内部使用 BillingClient 8.3.0
    // 启用待处理购买支持
    // 启用自动服务重连
}
```

### 4. PaymentConfig - 商品配置类

**职责：** 配置各类商品的 ID 列表，用于识别商品类型

**使用示例：**
```kotlin
val config = PaymentConfig(
    subsProducts = listOf("SUBS_PRODUCT_MONTH", "SUBS_PRODUCT_YEAR"),      // 订阅商品
    consumableProducts = listOf("OTP_GAME_SKIN_3DAY"),                      // 一次性消耗商品
    nonConsumableProducts = listOf("OTP_GAME_SKIN_PERMANENT")               // 一次性非消耗商品
)
```

### 5. 数据模型详解

#### **商品详情（PaymentProductDetails）**
```kotlin
data class PaymentProductDetails(
    val productId: String,           // 商品 ID
    val title: String,               // 商品标题
    val description: String,         // 商品描述
    val productType: PaymentProductType  // 商品类型
)
```

#### **订单详情（PaymentPurchaseDetails）**
```kotlin
data class PaymentPurchaseDetails(
    val key: String,                 // 订单标识（开发者传入的用户 ID）
    val orderId: String,             // Google Play 订单 ID
    val purchaseState: PaymentPurchaseState,  // 购买状态
    val products: List<String>,      // 商品 ID 列表
    val purchaseToken: String,       // 购买令牌（确认/消费必备）
    val isAcknowledged: Boolean      // 是否已确认
)
```

#### **商品类型枚举**
```kotlin
enum class PaymentProductType {
    SUBS,    // 订阅商品（包月/包年）
    INAPP    // 一次性商品（消耗型/非消耗型）
}
```

#### **购买状态枚举**
```kotlin
enum class PaymentPurchaseState {
    UNSPECIFIED_STATE,  // 未指明状态
    PURCHASED,          // 已支付
    PENDING             // 待处理（延迟支付）
}
```

#### **错误码枚举**
```kotlin
enum class PaymentCode {
    OK,                      // 成功
    ITEM_ALREADY_OWNED,      // 商品已购买
    ERROR,                   // 一般错误
    SERVICE_DISCONNECTED,    // 服务断开
    SERVICE_UNAVAILABLE,     // 服务不可用
    BILLING_UNAVAILABLE,     // 计费不可用
    DEVELOPER_ERROR,         // 开发者错误
    ITEM_UNAVAILABLE,        // 商品不可用
    FEATURE_NOT_SUPPORTED,   // 功能不支持
    ITEM_NOT_OWNED,          // 商品未购买
    USER_CANCELED            // 用户取消
}
```

---

## 🚀 快速集成

### 步骤 1：添加 Gradle 依赖

在 `build.gradle` 中添加：

```gradle
dependencies {
    // Google Billing Library
    def billing_version = "8.3.0"
    implementation "com.android.billingclient:billing:$billing_version"
    implementation "com.android.billingclient:billing-ktx:$billing_version"
}
```

### 步骤 2：在 Google Play Console 配置商品

**2.1 创建商品**

登录 [Google Play Console](https://play.google.com/console)，进入「获利」>「商品管理」：

**订阅商品（Subscriptions）：**
- 创建订阅组（如 "Premium Membership"）
- 在订阅组内创建基础方案（Base Plan），如 "Monthly", "Yearly"
- 可选：创建优惠方案（Offer），如 "Free Trial", "Introductory Price"

**一次性商品（In-app Products）：**
- 创建 Managed Product
- 设置价格和销售地区

**2.2 记录商品 ID**

在代码中配置商品 ID 映射：

```kotlin
val config = PaymentConfig(
    subsProducts = listOf("SUBS_PRODUCT_MONTH", "SUBS_PRODUCT_YEAR"),      // 订阅商品
    consumableProducts = listOf("OTP_GAME_SKIN_3DAY"),                     // 一次性消耗商品
    nonConsumableProducts = listOf("OTP_GAME_SKIN_PERMANENT")              // 一次性非消耗商品
)
```

### 步骤 3：初始化支付管理器

**在 Application 或 MainActivity 中初始化：**

```kotlin
class MainViewModel(private val app: Application) : AndroidViewModel(app) {

    fun init() {
        // 1. 加载配置
        val config = PaymentConfig(
            subsProducts = listOf(Constants.SUBS_PRODUCT_MONTH, Constants.SUBS_PRODUCT_YEAR),
            consumableProducts = listOf(Constants.OTP_GAME_SKIN_3DAY),
            nonConsumableProducts = listOf(Constants.OTP_GAME_SKIN_PERMANENT)
        )
        
        // 2. 创建支付提供者
        val paymentProvider = GoogleBillingProvider(app)
        
        // 3. 初始化 SDK（推荐使用初始化回调 + 全局订单监听器）
        PaymentManager.init(
            provider = paymentProvider,
            config = config,
            orderUpdateListener = object : PaymentCallback {
                override fun onSuccess(purchaseDetails: PaymentPurchaseDetails) {
                    Log.d(TAG, "订单支付成功 - 订单号：${purchaseDetails.orderId}")
                    // 支付成功且已自动确认，可以立即发放权益
                    actionCheckVipStatus()
                }

                override fun onConfirmFailed(purchaseDetails: PaymentPurchaseDetails) {
                    Log.e(TAG, "订单支付成功但确认失败 - 订单号：${purchaseDetails.orderId}")
                    // 用户已付款，但确认操作失败
                    // SDK 会在后台继续尝试确认，无需手动重试
                }

                override fun onPending(purchaseDetails: PaymentPurchaseDetails) {
                    Log.d(TAG, "订单支付待处理 - 订单号：${purchaseDetails.orderId}")
                    // 用户使用了延迟支付方式，等待支付平台确认
                }

                override fun onUserCancel(purchaseDetails: PaymentPurchaseDetails) {
                    Log.d(TAG, "用户取消了支付 - 订单号：${purchaseDetails.orderId}")
                }

                override fun onFailure(errorCode: PaymentCode, purchaseDetails: PaymentPurchaseDetails?) {
                    Log.e(TAG, "订单支付失败 - 错误码：$errorCode")
                }
            },
            initCallback = object : InitializationCallback {
                override fun onSuccess() {
                    Log.d(TAG, "SDK 初始化成功")
                    // 初始化成功后，恢复未完成订单并检查 VIP 状态
                    actionRecoverUnfinishedOrders()
                    actionCheckVipStatus()
                }

                override fun onFailure(errorCode: PaymentCode) {
                    Log.e(TAG, "SDK 初始化失败：$errorCode")
                    // 即使初始化失败，也尝试检查本地 VIP 状态
                    checkLocalVipStatus()
                }
            }
        )
    }
}
```

---

## 💡 使用指南

### 场景 1：查询商品详情并展示

从服务端或 Firebase 获取商品 ID 列表后，查询商品详细信息：

```kotlin
viewModelScope.launch {
    try {
        val result = PaymentManager.queryProducts(
            productIds = listOf("SUBS_PRODUCT_MONTH", "OTP_GAME_SKIN_PERMANENT")
        )
        
        // 处理查询到的商品
        result.products.forEach { product ->
            Log.d(TAG, "商品 - ID: ${product.productId}")
            Log.d(TAG, "标题：${product.title}")
            Log.d(TAG, "描述：${product.description}")
            Log.d(TAG, "类型：${product.productType}")
            
            when (product.productType) {
                PaymentProductType.SUBS -> {
                    // 订阅商品：显示价格周期（如 $9.99/月）
                    showSubscriptionProduct(product)
                }
                PaymentProductType.INAPP -> {
                    // 一次性商品：显示固定价格
                    showOneTimeProduct(product)
                }
            }
        }
        
        // 处理未找到的商品
        if (result.unfetchedProductIds.isNotEmpty()) {
            Log.w(TAG, "以下商品未找到：${result.unfetchedProductIds}")
            // 可能原因：
            // 1. 商品 ID 配置错误
            // 2. 商品在 Google Play 后台未上架
            // 3. 应用签名与 Google Play 不一致
        }
    } catch (e: Exception) {
        Log.e(TAG, "查询商品异常：${e.message}")
    }
}
```

**快捷方法：**
```kotlin
viewModelScope.launch {
    // 查询所有订阅商品
    val subsResult = PaymentManager.querySubsProducts()
    
    // 查询所有一次性消耗商品
    val consumableResult = PaymentManager.queryConsumableProducts()
    
    // 查询所有一次性非消耗商品
    val nonConsumableResult = PaymentManager.queryNonConsumableProducts()
}
```

### 场景 2：发起支付（订阅/一次性商品）

用户选择商品后，发起支付流程：

```kotlin
@OptIn(ExperimentalUuidApi::class)
fun purchaseProduct(activity: Activity, productId: String) {
    viewModelScope.launch {
        try {
            // 生成订单 key，用于区分不同订单
            val orderKey = Uuid.generateV4().toHexString()
            
            val result = PaymentManager.makePayment(
                activity = activity,
                key = orderKey,
                productId = productId,
                offerId = "basicmonthly"  // 订阅优惠方案 ID，一次性商品可传空串
            )
            
            if (!result.isSuccess) {
                Log.e(TAG, "支付失败：${result.errorCode}")
                // 根据错误码提示用户
            }
        } catch (e: Exception) {
            Log.e(TAG, "支付异常：${e.message}")
        }
    }
}
```

**注意：** 支付结果通过全局 `orderUpdateListener` 回调通知，参考初始化部分的示例。

**完整示例：不同类型的商品购买**
```kotlin
// 购买包月订阅
actionPurchase(activity, Constants.SUBS_PRODUCT_MONTH, Constants.BASIC_MONTHLY_PLAN)

// 购买包年订阅
actionPurchase(activity, Constants.SUBS_PRODUCT_YEAR, Constants.BASIC_YEARLY_PLAN)

// 购买一次性消耗商品（如游戏金币）
actionPurchase(activity, Constants.OTP_GAME_SKIN_3DAY)

// 购买一次性非消耗商品（如永久皮肤）
actionPurchase(activity, Constants.OTP_GAME_SKIN_PERMANENT)
```

### 场景 3：检查用户权益状态

在应用启动或订阅页面加载时检查用户权益状态：

```kotlin
// 检查是否拥有某项商品的权益
fun isProductPurchased(productId: String): Boolean {
    return PaymentManager.getInstance().isEntitled(productId)
}

// 检查订阅状态
val isVip = isProductPurchased(Constants.SUBS_PRODUCT_MONTH) ||
            isProductPurchased(Constants.SUBS_PRODUCT_YEAR)

if (isVip) {
    // 用户已订阅，解锁 VIP 功能
    unlockVipFeatures()
} else {
    // 用户未订阅，引导购买
    showUpgradePrompt()
}
```

**获取所有活跃权益：**
```kotlin
// 获取所有活跃的订阅
val activeSubscriptions = PaymentManager.getInstance().getActiveSubscriptions()

// 获取所有活跃的非消耗商品
val activeNonConsumables = PaymentManager.getInstance().getActiveNonConsumables()
```

### 场景 4：恢复未完成订单（防止掉单）

**重要：** 每次应用启动时，必须调用此方法恢复未完成的订单，确保用户权益不丢失。

```kotlin
fun actionRecoverUnfinishedOrders() {
    viewModelScope.launch {
        try {
            // 恢复订阅商品
            PaymentManager.recoverUnfinishedOrders(
                productType = PaymentProductType.SUBS,
                onOrderRecovered = { purchase ->
                    Log.d(TAG, "订阅商品已恢复：${purchase.products}, 订单号：${purchase.orderId}")
                    // 可根据业务需要处理恢复的订单
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
        } catch (e: Exception) {
            Log.e(TAG, "恢复订单异常：${e.message}")
        }
    }
}
```

**为什么需要恢复订单？**
- 用户在支付过程中应用被强退
- 网络问题导致确认失败
- 跨设备购买需要同步权益
- Google Play 补发历史订单

**最佳实践：**
- 在 `Application.onCreate()` 或 `MainActivity.onCreate()` 中调用
- 在 SDK 初始化成功后立即调用
- 提供“恢复购买”按钮供用户手动触发

---

## 订单处理流程

### 正常支付流程

1. 用户选择商品 → 发起支付
2. 用户在 Google Billing 界面完成支付
3. 返回应用，等待 `handlePurchasesUpdated` 回调
4. SDK 根据商品类型自动处理：
   - **订阅商品**：调用 `acknowledgePurchase` 确认订单
   - **一次性非消耗商品**：调用 `acknowledgePurchase` 确认订单
   - **一次性消耗商品**：调用 `consumePurchase` 消耗订单
5. 成功后赋予用户相应权益

### 异常场景处理

#### 场景 1：应用被强退或未启动

当用户在 Billing 界面完成支付但应用被系统强退时：

```kotlin
viewModelScope.launch {
    try {
        // 查询未完成的订单
        val result = PaymentManager.queryPurchases(PaymentProductType.SUBS)
        
        result.purchases.forEach { purchase ->
            when (purchase.purchaseState) {
                PaymentPurchaseState.PENDING -> {
                    // 引导用户继续支付或展示"等待支付确认"
                }
                PaymentPurchaseState.PURCHASED -> {
                    // 检查是否需要确认订单
                    // 订阅和非消耗商品需要调用 acknowledgePurchase
                    // 消耗商品直接发放权益
                }
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "查询订单异常：${e.message}")
    }
}
```

#### 场景 2：待支付订单（PENDING）

对于预付费计划等场景，订单可能处于待支付状态：

```kotlin
if (purchaseState == PaymentPurchaseState.PENDING) {
    // 展示"等待支付确认"页面
    // 定期轮询订单状态或监听支付更新
}
```

#### 场景 3：已支付但未确认的订单

对于订阅和一次性非消耗商品，检查 `isAcknowledged` 状态：

```kotlin
// SDK 已自动处理 acknowledgePurchase
// 如需手动处理，参考以下逻辑：
if (purchaseState == PaymentPurchaseState.PURCHASED && !isAcknowledged) {
    // 调用 acknowledgePurchase 确认订单
    // 避免用户失去商品权益
}
```

---

## 商品类型说明

### 订阅商品 (SUBS)
- **特点**：周期性扣费，如包月、包年
- **配置**：需在 Google Play Console 创建订阅计划
- **处理**：必须调用 `acknowledgePurchase` 确认订单
- **示例**：`SUBS_PRODUCT_MONTH`, `SUBS_PRODUCT_YEAR`

### 一次性非消耗商品 (INAPP - Non-consumable)
- **特点**：永久拥有，不可重复购买
- **处理**：必须调用 `acknowledgePurchase` 确认订单
- **示例**：`OTP_GAME_SKIN_PERMANENT`（永久皮肤）

### 一次性消耗商品 (INAPP - Consumable)
- **特点**：消费后可再次购买
- **处理**：调用 `consumePurchase` 消耗订单
- **注意**：只有消耗后才能再次购买
- **示例**：`OTP_GAME_SKIN_3DAY`（3 天使用权）

---

## 错误处理

### 常见错误码及处理策略

| 错误码 | 说明 | 建议处理方式 |
|--------|------|-------------|
| `ERROR` | 一般错误 | 记录日志，提示用户重试 |
| `ITEM_ALREADY_OWNED` | 商品已购买 | 无需再次购买，直接发放权益 |
| `SERVICE_DISCONNECTED` | 服务断开 | 自动重试（最多 3 次） |
| `SERVICE_UNAVAILABLE` | 服务不可用 | 稍后重试 |
| `BILLING_UNAVAILABLE` | 计费不可用 | 检查设备/地区支持 |
| `DEVELOPER_ERROR` | 开发者错误 | 检查配置和调用顺序 |
| `ITEM_UNAVAILABLE` | 商品不可用 | 商品已下架或不适用 |
| `FEATURE_NOT_SUPPORTED` | 功能不支持 | 降级处理 |
| `ITEM_NOT_OWNED` | 商品未购买 | 引导用户购买 |
| `USER_CANCELED` | 用户取消 | 记录取消行为，可适时推荐 |

---

## 最佳实践

### 1. 初始化处理
- **推荐使用初始化回调 + 全局订单监听器**：感知 SDK 状态，确保在可用后再调用其他方法
- **处理初始化失败**：提供重试机制或友好的错误提示
- **示例**：
```kotlin
PaymentManager.init(
    provider = paymentProvider,
    config = config,
    orderUpdateListener = object : PaymentCallback {
        override fun onSuccess(purchaseDetails: PaymentPurchaseDetails) {
            // SDK 已就绪，可以安全调用其他方法
            actionRecoverUnfinishedOrders()
        }
        
        override fun onFailure(errorCode: PaymentCode, purchaseDetails: PaymentPurchaseDetails?) {
            // 处理失败，比如显示错误或重试
        }
    },
    initCallback = object : InitializationCallback {
        override fun onSuccess() {
            // SDK 初始化成功
        }
        
        override fun onFailure(errorCode: PaymentCode) {
            // 处理初始化失败
        }
    }
)
```

### 2. 商品缓存管理
- 从服务端或 Firebase 缓存商品 ID 列表
- 定期刷新缓存，确保价格和可用性信息最新
- 使用 `queryProducts` 获取实时价格和详情

### 3. 订单恢复
- 每次启动应用时调用 `queryPurchases` 恢复未确认的订单
- 特别是订阅商品和非消耗商品，避免用户失去权益

### 4. 用户体验优化
- 在支付前明确展示商品信息和价格
- 处理 `PENDING` 状态时给予用户清晰反馈
- 对用户取消支付的场景，提供友好的重试入口

### 5. 安全验证
- 建议在服务端验证购买凭证（purchaseToken）
- 对于高价值商品，实施额外的安全校验

---

## 注意事项

1. **初始化回调**：强烈建议使用初始化回调，确保 SDK 就绪后再调用其他方法
2. **测试环境**：在 Google Play Console 中设置测试账号和测试商品
3. **商品 ID 匹配**：确保代码中的商品 ID 与 Google Play Console 中创建的完全一致
4. **欧盟政策**：SDK 已启用个性化报价（`setIsOfferPersonalized(true)`），符合欧盟法规
5. **自动重连**：`enableAutoServiceReconnection()` 已启用，无需手动处理服务断开
6. **线程安全**：`PaymentManager` 使用 `@Volatile` 保证线程安全
7. **pending 购买支持**：已启用挂起购买和预付费计划支持
8. **简化设计**：移除了任务队列，API 更简洁易用

---

## 扩展开发

### 自定义支付提供者

如需支持其他支付平台，可实现 `PaymentProvider` 接口：

```kotlin
class CustomPaymentProvider : PaymentProvider {
    override fun initialize(callback: InitializationCallback) {
        // 实现初始化逻辑
    }
    
    override fun queryProducts(productIds: List<String>, callback: QueryProductsCallback) {
        // 实现商品查询
    }
    
    // ... 实现其他接口方法
}

// 使用自定义提供者
PaymentManager.getInstance().setPaymentProvider(CustomPaymentProvider())
```

---

## 版本信息

- **Billing Library**: 8.3.0
- **最低 SDK**: API 21 (Android 5.0)
- **编译 SDK**: 34
- **语言**: Kotlin

---

## 常见问题

### Q1: 如何处理跨设备购买？
A: Google Play 会自动同步购买记录。使用相同的 Google 账号登录的设备可以通过 `queryPurchases` 恢复购买。

### Q2: 订阅过期后如何处理？
A: 定期调用 `checkSubscriptionStatus` 检查订阅状态，过期后及时调整用户权限。

### Q3: 为什么我的商品查询返回空列表？
A: 检查以下几点：
- 商品 ID 是否正确
- 应用签名是否与 Google Play Console 一致
- 测试账号是否配置正确
- 商品是否已上架发布
