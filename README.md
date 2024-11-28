# Payment SDK 集成指南

## 简介

Payment SDK 是一个专为 Android 应用设计的支付结算库，目前仅支持 Google Play结算系统。该 SDK 提供了完整的支付流程管理，包括商品查询、支付发起、订单确认、订阅状态检查等功能。

## 核心架构

### 主要组件

1. **PaymentManager** - 支付管理器（单例）
   - 管理支付逻辑的核心类
   - 支持延迟任务队列，确保初始化完成后再执行支付操作
   - 提供统一的 API 接口进行支付相关操作

2. **PaymentProvider** - 支付提供者接口
   - 定义了支付服务的核心操作
   - 当前实现：`GoogleBillingProvider`

3. **GoogleBillingProvider** - Google Play结算实现
   - 实现了与 Google Play Billing Library 的交互
   - 提供连接管理、重试机制、购买处理等功能
   - 自动处理不同商品类型的确认和消费逻辑

4. **GoogleBillingConfig** - 配置类
   - 配置订阅商品、一次性消耗商品、一次性非消耗商品的 ID 列表

### 数据模型

- **PaymentProductType**: 商品类型（订阅/一次性）
- **SubscriptionStatus**: 订阅状态（未初始化/已订阅/未订阅）
- **PaymentPurchaseState**: 购买状态（未指明/已支付/待处理）
- **PaymentErrorCode**: 错误代码枚举

---

## 集成流程

### 第一步：添加依赖

在 `build.gradle` 中添加：

```gradle
dependencies {
    // Google Billing Library
    def billing_version = "8.3.0"
    implementation "com.android.billingclient:billing:$billing_version"
    implementation "com.android.billingclient:billing-ktx:$billing_version"
}
```

### 第二步：配置商品信息

创建 `GoogleBillingConfig` 配置对象，定义各类商品 ID：

```kotlin
val config = GoogleBillingConfig(
    subsProducts = listOf("SUBS_PRODUCT_MONTH", "SUBS_PRODUCT_YEAR"),      // 订阅商品
    otpConsumerProducts = listOf("OTP_GAME_SKIN_3DAY"),                     // 一次性消耗商品
    otpNonConsumerProducts = listOf("OTP_GAME_SKIN_PERMANENT")              // 一次性非消耗商品
)
```

### 第三步：初始化支付管理器

在 Application 或 MainActivity 中初始化：

```kotlin
class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    fun init() {
        val config = GoogleBillingConfig(...)
        val paymentProvider = GoogleBillingProvider(app.applicationContext, config)
        
        // 推荐：传入初始化回调，感知 SDK 状态
        PaymentManager.getInstance().setPaymentProvider(paymentProvider, object : InitializationCallback {
            override fun onSuccess() {
                Log.d(TAG, "SDK 初始化成功")
                // 初始化成功后再调用其他方法
            }

            override fun onFailure(errorCode: PaymentErrorCode) {
                Log.e(TAG, "SDK 初始化失败：$errorCode")
                // 处理失败，比如重试或提示用户
            }
        })
    }
}
```

**简化用法**（无需回调）：

```kotlin
// 如果不需要感知初始化状态，可以省略 callback
PaymentManager.getInstance().setPaymentProvider(paymentProvider)
```

**重要变化**：
- ✅ **移除了任务队列**：不再需要理解复杂的延迟执行机制
- ✅ **暴露初始化回调**：开发者可以精确控制 SDK 使用时机
- ✅ **更清晰的错误处理**：初始化失败时立即通知
- ✅ **更早发现问题**：初始化阶段就能发现配置错误

---

## 使用指南

### 1. 查询商品详情

从服务端或 Firebase 获取商品 ID 列表后，查询商品详细信息：

```kotlin
PaymentManager.getInstance().queryProducts(
    productIds = listOf("SUBS_PRODUCT_MONTH", "OTP_GAME_SKIN_PERMANENT"),
    callback = object : QueryProductsCallback {
        override fun onQuerySuccess(products: List<PaymentProductDetails>) {
            // 展示商品详情（价格、描述等）
        }
        
        override fun onQueryFailure(errorCode: PaymentErrorCode) {
            // 处理查询失败
        }
    }
)
```

### 2. 检查订阅状态

在应用启动或订阅页面加载时检查用户订阅状态：

```kotlin
PaymentManager.getInstance().checkSubscriptionStatus { status ->
    when (status) {
        SubscriptionStatus.SUBSCRIBED -> // 用户已订阅
        SubscriptionStatus.NOT_SUBSCRIBED -> // 用户未订阅
        SubscriptionStatus.NOT_INITIALIZED -> // 初始化未完成
    }
}
```

### 3. 发起支付

用户选择商品后，发起支付流程：

```kotlin
PaymentManager.getInstance().makePayment(
    activity = this,
    productId = "SUBS_PRODUCT_MONTH",
    offerId = "basicmonthly",  // 订阅优惠方案 ID
    callback = object : PaymentCallback {
        override fun onSuccess() {
            // 支付成功，等待订单确认回调
        }
        
        override fun onFailure(errorCode: PaymentErrorCode) {
            // 支付失败处理
        }
    }
)
```

### 4. 查询已购买商品

查询用户已购买的订阅或一次性商品：

```kotlin
// 查询订阅商品
PaymentManager.getInstance().queryPurchases(
    productType = PaymentProductType.SUBS,
    callback = object : QueryPurchasesCallback {
        override fun onQuerySuccess(purchases: List<PaymentPurchaseDetails>) {
            // 处理已购买列表
        }
        
        override fun onQueryFailure(errorCode: PaymentErrorCode) {
            // 处理查询失败
        }
    }
)
```

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
// 在应用重启后，主动查询未完成的订单
PaymentManager.getInstance().queryPurchases(
    productType = PaymentProductType.SUBS,
    callback = object : QueryPurchasesCallback {
        override fun onQuerySuccess(purchases: List<PaymentPurchaseDetails>) {
            purchases.forEach { purchase ->
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
        }
    }
)
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
- **推荐使用初始化回调**：感知 SDK 状态，确保在可用后再调用其他方法
- **处理初始化失败**：提供重试机制或友好的错误提示
- **示例**：
```kotlin
PaymentManager.getInstance().setPaymentProvider(provider, object : InitializationCallback {
    override fun onSuccess() {
        // SDK 已就绪，可以安全调用所有方法
        actionRecoverUnfinishedOrders()
    }
    
    override fun onFailure(errorCode: PaymentErrorCode) {
        // 处理失败，比如显示错误或重试
    }
})
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
