# PayKit – AGENTS.md

给 AI Agent（Cursor / Codex / Copilot 等）用的项目说明：快速理解结构、约定与安全改动边界。

## What This Project Is

轻量级 Android Google Play 支付 SDK（`:pay`）+ Compose 示例 App（`:app`）。

封装 [Google Play Billing Library](https://developer.android.com/google/play/billing) **8.3.0**，对外提供简洁、**纯本地无后端**的协程 API：

- 商品查询 / 发起购买 / 自动 acknowledge 或 consume
- 启动时同步购买记录（补单）
- `CustomerInfo` 本地权益计算 + SharedPreferences 缓存

**关键设计决策**：无服务端验单；商品类型（订阅 / 消耗 / 非消耗）必须由宿主在 `PayKitConfiguration` 中声明，补单时才能正确选择 `acknowledgePurchase` vs `consumePurchase`。

## Project Layout

```
PayKit/
├── pay/                          # SDK 库（namespace: com.kit.pay）
│   ├── consumer-rules.pro            # 宿主 minify 时自动应用的 keep 规则
│   └── src/main/java/com/kit/pay/
│       ├── PayKit.kt                 # [Entry Point] 单例门面
│       ├── billing/
│       │   ├── BillingAbstract.kt    # 支付抽象层 + PurchasesUpdatedListener
│       │   └── GoogleBillingWrapper.kt  # BillingClient 实现
│       ├── caching/DeviceCache.kt    # CustomerInfo 本地持久化
│       ├── subscriber/CustomerInfoHelper.kt  # 订单 → 权益归类
│       ├── models/                   # PayKitConfiguration / CustomerInfo / StoreProduct / StoreTransaction
│       ├── interfaces/               # PurchaseCallback / UpdatedCustomerInfoListener / PayKitError
│       └── utils/LogUtil.kt
├── app/                          # 示例 App（namespace: com.kit.pay.sample）
│   └── src/main/java/com/kit/pay/sample/
│       ├── App.kt
│       ├── MainActivity.kt       # Compose Demo UI
│       ├── MainViewModel.kt      # 集成示例：configure / getProducts / purchase
│       └── Constants.kt          # Demo 商品 ID（Free/Plus/Pro 月年）
├── gradle/libs.versions.toml     # 版本目录（Billing / AGP / Kotlin）
├── README.md                     # 宿主集成文档（面向人）
├── GOOGLE_PLAY_BILLING_DETAILS.md # Billing 参数详解（调试用）
├── AGENTS.md                     # 本文件
└── settings.gradle.kts           # include :app, :pay
```

本地集成：

```kotlin
implementation(project(":pay"))
```

JitPack：`jitpack.yml` 只发布 `:pay`；`pay/build.gradle.kts` 使用 `maven-publish`，坐标 `com.github.e-hai:PayKit:<tag>`。

## Build Toolchain

全部使用 **Kotlin DSL**（`.gradle.kts`），版本集中在 `gradle/libs.versions.toml`。

| Tool | Version |
|------|---------|
| Gradle | 8.13 |
| AGP | 8.13.2 |
| Kotlin | 2.3.20 |
| JDK | 17 |
| Billing Library | 8.3.0 |
| `:pay` minSdk / compileSdk | 21 / 36 |
| `:app` minSdk / compileSdk | 23 / 36 |

- `:pay`：`android.library` + `kotlin.android`
- `:app`：`android.application` + `kotlin.android` + Compose Compiler 插件
- 依赖用 Version Catalog 的 `libs.bundles.pay` / `libs.bundles.app`

## Architecture

```
Host App / ViewModel
        │
        ▼
   PayKit (singleton)
        │
   ┌────┼────────────────────┐
   ▼    ▼                    ▼
DeviceCache  CustomerInfoHelper  BillingAbstract
(本地缓存)    (权益计算)          └─ GoogleBillingWrapper
                                      └─ BillingClient
```

### Core Data Flow

1. **Init**：`PayKit.configure(context, configuration)` → `startConnection` → `syncPurchasesInternal()`（本地缓存由 `getCustomerInfo(forceSync = false)` 按需读取）
2. **Sync / 恢复**：`syncPurchases()` / `restorePurchases()` → 查 `SUBS` + `INAPP` → **仅 `PURCHASED`** acknowledge/consume → 更新 `CustomerInfo`（PENDING 进历史、不进活跃权益）
3. **Query**：`getProducts(ids)` 按配置拆分 SUBS / INAPP，调用 `queryProductDetails`
4. **Purchase**：`purchase(...)` → `launchBillingFlow` → `onPurchasesUpdated` → sync → `onCompleted` / `onPending` / `onError`
5. **查权益**：启动用 `getCustomerInfo(forceSync = false)` + Listener（SDK 连上后会自动 sync）；订单视图用 `pendingPurchases` / `getPendingPurchases()` 等

### Product Type Mapping（易错）

| 配置字段 | Google ProductType | 确认方式 |
|----------|-------------------|----------|
| `subsProductIds` | `SUBS` | `acknowledgePurchase` |
| `consumableProductIds` | `INAPP` | `consumePurchase` |
| `nonConsumableProductIds` | `INAPP` | `acknowledgePurchase` |

`queryPurchasesAsync` / `queryProductDetailsAsync` 中 **SUBS ↔ SUBS、INAPP ↔ INAPP**，禁止写反。

## Public API（稳定面）

包名：`com.kit.pay`。改签名或语义前需评估破坏性。

```kotlin
// 初始化（只生效一次）
PayKit.configure(context: Context, configuration: PayKitConfiguration)

// 单例
PayKit.shared

suspend fun getProducts(productIds: Set<String>): Result<List<StoreProduct>>
suspend fun getCustomerInfo(forceSync: Boolean = true): CustomerInfo?
suspend fun syncPurchases(): Result<CustomerInfo>
suspend fun restorePurchases(): Result<CustomerInfo>   // 同 syncPurchases，产品语义
suspend fun getPendingPurchases(): List<StoreTransaction>
suspend fun getPurchaseHistory(): List<StoreTransaction>
fun findTransaction(purchaseToken: String): StoreTransaction?
fun findActiveSubscription(productId: String): StoreTransaction?

fun purchase(
    activity: Activity,              // 对外收 Activity；内部再包 WeakReference
    storeProduct: StoreProduct,
    callback: PurchaseCallback,      // 主线程回调
    isOfferPersonalized: Boolean = configuration.isOfferPersonalizedDefault,
    subscriptionReplacement: SubscriptionReplacement? = null  // Plus↔Pro / 换档
)

fun setUpdatedCustomerInfoListener(listener: UpdatedCustomerInfoListener?)  // 主线程回调
```

### 关键模型

```kotlin
data class PayKitConfiguration(
    val subsProductIds: Set<String> = emptySet(),
    val consumableProductIds: Set<String> = emptySet(),
    val nonConsumableProductIds: Set<String> = emptySet(),
    val isOfferPersonalizedDefault: Boolean = false  // 欧盟个性化报价默认
)

data class SubscriptionReplacement(
    val oldProductId: String,
    val oldPurchaseToken: String,
    val replacementMode: SubscriptionReplacementMode = WITH_TIME_PRORATION
)

data class CustomerInfo(
    val activeSubscriptions: Set<String> = emptySet(),
    val nonConsumablePurchases: Set<String> = emptySet(),
    val allPurchaseRecords: List<StoreTransaction> = emptyList()
) {
    val pendingPurchases: List<StoreTransaction>   // 计算属性
    val purchasedRecords: List<StoreTransaction>   // 计算属性
}

data class StoreProduct(
    val productId: String,
    val type: ProductType,           // SUBS | INAPP
    val title: String,
    val description: String,
    val price: String,
    val priceAmountMicros: Long,
    val priceCurrencyCode: String,
    val subscriptionToken: String? = null,   // 订阅必填 offerToken
    val basePlanId: String? = null,          // 订阅 base plan
    val offerId: String? = null,             // 优惠 ID；基础价可能为 null
    val hasFreeTrial: Boolean = false,       // 首阶段 priceAmountMicros==0
    @Transient val nativeProductDetails: Any? = null  // 底层 ProductDetails，购买用
)
```

`StoreTransaction` 含 `purchaseState: PurchaseState`（`PURCHASED` / `PENDING` / `UNSPECIFIED`）。

### Demo 订阅模型

Demo（`:app`）采用 Google Play 推荐层级：**一个 product = 一个权益档**。

```
Product [subs_pro]              → Pro 权益
 ├─ Base Plan [pro-monthly]     月订阅
 │    └─ Offer  免费试用 / 优惠
 └─ Base Plan [pro-yearly]      年订阅
Product [subs_plus]             → Plus 权益
 ├─ Base Plan [plus-monthly]    月订阅
 ├─ Base Plan [plus-quarterly]  季度订阅
 └─ Base Plan [plus-yearly]     年订阅
```

- 档位顺序为 **Free < Plus < Pro**；同时持有 Plus 和 Pro 时展示 Pro。
- Free 无 Play SKU；Plus / Pro 各对应一个 product，计费周期是各 product 下的 base plan（不同档位可拥有不同周期）。
- `getProducts(setOf(SUBS_PLUS, SUBS_PRO))` 会把每个 `base plan × offer` 展开为一条 `StoreProduct`。
- Demo 先按 `productId` 切换档位，再根据查询结果动态展示该档位实际存在的 `basePlanId`；同周期主 CTA 优先 `hasFreeTrial`。
- product ID / base plan ID 必须与 Play Console 一致（见 `Constants.kt`）。

### Callbacks

| 接口 | 用途 |
|------|------|
| `PurchaseCallback` | `onCompleted` 发货；`onPending` 待确认勿发货；`onError` 失败/取消（**主线程**） |
| `UpdatedCustomerInfoListener` | 权益变化（同步完成、购买后等）（**主线程**） |

错误码见 `ErrorCode`：`STORE_PROBLEM` / `PURCHASE_CANCELLED` / `PURCHASE_PENDING` / `PRODUCT_NOT_AVAILABLE` / `NETWORK_ERROR` / `UNKNOWN` 等。

## Logging

统一经 `LogUtil` 输出，**tag 均以 `PayKit` 开头**，便于 Logcat 过滤。

| Tag | 来源 | 过滤用途 |
|-----|------|----------|
| `PayKit` | `PayKit.kt` 等核心流程 | 同步 / 购买 / 缓存 |
| `PayKit-Billing` | `GoogleBillingWrapper` | 商店连接、查询、确认 |
| `PayKit-Sample` | Demo App | 示例 UI / ViewModel |

Logcat：
- 过滤 `PayKit` → 全部 SDK + Sample（子串匹配）
- 过滤 `PayKit-Billing` → 仅 Billing 层

消息格式：`action key=value key=value`，例如：

```
syncPurchases done success=true activeSubs=1 pending=0 records=2
purchase completed orderId=GPA.xxx products=[sub_monthly]
acknowledge fail orderId=... code=6 msg=...
```

常用检索词：`syncPurchases` / `purchase` / `connect` / `queryPurchases` / `acknowledge` / `consume`。

运行时关闭：`LogUtil.enabled = false`。

## Naming Conventions

| 名称 | 说明 |
|------|------|
| `PayKit` | 对外单例门面 |
| `CustomerInfo` | 本地权益快照，非服务端用户实体 |
| `StoreProduct` / `StoreTransaction` | 商店商品 / 交易封装 |
| `BillingAbstract` | 可扩展其他商店；当前仅 Google |
| `DeviceCache` | SharedPreferences，key `PayKit_DeviceCache` |

公开类型以 `PayKit*` / 商店语义命名为主；不要随意重命名公开 API。

## Important Notes for Agents

1. **先读再改**：改 Billing 逻辑前对照 `GOOGLE_PLAY_BILLING_DETAILS.md` 与官方 Billing 8.x API。
2. **保持公开 API 稳定**：`PayKit.purchase(Activity, ...)` 对外必须是 `Activity`；`WeakReference` 仅限 Billing 层内部。
3. **不要擅自「修复」`activePurchaseCallback` 的单回调设计**，除非用户明确要求（当前刻意只支持单笔进行中购买）。
4. **无服务端校验**：不要在 SDK 内假装已有收据验签；若增加服务端能力，应明确新 API 与配置，避免 silently 改变现有行为。
5. **订阅权益偏粗**：`activeSubscriptions` 目前 = Google 当前购买列表中的订阅 ID，未建模宽限期 / 账号保留 / 过期时间；扩展时勿破坏现有字段语义。
6. **配置必须完整**：新增商品相关逻辑时，始终以 `PayKitConfiguration` 三分法为准，禁止仅凭 `ProductType.INAPP` 判断是否消耗。
7. **版本变更**：改 Billing / AGP / Kotlin 版本时只改 `gradle/libs.versions.toml`，并同步 README「版本信息」。
8. **文档同步**：改公开 API 签名时，必须同步更新 `README.md` 与本文件对应段落。
9. **不要提交**：`local.properties`、密钥、真实 Play Console 商品私密配置。
10. **避免编辑** `*/build/` 生成物。
11. **禁止修改 Demo `applicationId`**：必须保持 `com.google.play.billing.samples.onetimepurchases`；与 `namespace`（`com.kit.pay.sample`）不同是刻意的，勿「统一包名」。

## Implementation Caveats

- `configure` 双重检查锁，重复调用不会重建实例；改配置需进程级重新初始化（当前无 `reset` API）。
- `getCustomerInfo()` / `syncPurchases()` 在 IO 调度；**公开回调已切主线程**（`MainThreadDispatcher`），Demo/宿主可直接更新 UI。
- 订阅购买缺少 `subscriptionToken`（offerToken）会启动失败。
- `StoreProduct.nativeProductDetails` 为 `@Transient`，不可依赖序列化还原后再购买。
- **订单状态**：只对 `PurchaseState.PURCHASED` 且 acknowledge/consume **成功** 的订单发权益；`PENDING` 走 `PurchaseCallback.onPending`；确认失败时 `syncPurchases`/`restorePurchases` 返回 `Result.failure`，购买回调走 `onError`，下次同步会重试确认。
- **Billing 连接**：`ensureConnected` 失败时各 Billing 操作直接 `Result.failure`，不再继续调用。
- **同步查询**：`syncPurchasesInternal` 中 SUBS / INAPP 使用 `async` 并行查询；**任一侧失败**都不会用半份订单重算权益（回退缓存或 failure），避免清空另一侧活跃权益。
- **恢复购买**：Google 无独立 Restore API；`restorePurchases()` ≡ `syncPurchases()`，仅产品命名不同。
- **欧盟个性化报价**：通过 `BillingFlowParams.setIsOfferPersonalized`；配置 `isOfferPersonalizedDefault` 或 `purchase(..., isOfferPersonalized = true)`。未个性化时保持 `false`。
- **订阅升降级**：已有订阅时须传 `SubscriptionReplacement`（旧 productId + purchaseToken）；Billing 8 使用 `SubscriptionProductReplacementParams` + `setOldPurchaseToken`。Demo 在 Plus↔Pro / 同档换周期时自动填充。
- Demo `applicationId` 固定为 `com.google.play.billing.samples.onetimepurchases`（官方 Billing sample 包名，用于对接既有 Play Console 商品）；`namespace` 为 `com.kit.pay.sample`。**禁止修改 `applicationId`**，二者本就可以不同，勿假设必须一致。
- 真机 / 内测轨道验证支付；模拟器通常无法完整走 Google Play 结算。

## Testing / Build

当前无单元测试模块。常用命令：

```bash
./gradlew :pay:assembleDebug
./gradlew :app:assembleDebug
```

## Related Docs

| 文件 | 读者 | 内容 |
|------|------|------|
| `README.md` | 宿主开发者 | 集成步骤、场景示例、FAQ |
| `GOOGLE_PLAY_BILLING_DETAILS.md` | 调试 / Agent | ProductDetails / Purchase 字段说明 |
| `AGENTS.md` | AI Agent | 结构、约定、改动红线 |
