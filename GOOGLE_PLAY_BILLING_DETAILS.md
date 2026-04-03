# Google Play 订阅商品与订单详情参数详解

本文档详细说明 Google Play Billing Library 中的商品详情（ProductDetails）和订单详情（Purchase）的所有参数，并提供实际示例。

---

## 目录

- [一、商品详情参数（ProductDetails）](#一商品详情参数 productdetails)
  - [1.1 基本信息](#11-基本信息)
  - [1.2 订阅商品结构](#12-订阅商品结构)
  - [1.3 一次性商品结构](#13-一次性商品结构)
  - [1.4 完整示例](#14-完整示例)
- [二、订单详情参数（Purchase）](#二订单详情参数 purchase)
  - [2.1 基本信息](#21-基本信息)
  - [2.2 订单状态](#22-订单状态)
  - [2.3 用户账户信息](#23-用户账户信息)
  - [2.4 完整示例](#24-完整示例)
- [三、关键要点总结](#三关键要点总结)

---

## 一、商品详情参数（ProductDetails）

### 1.1 基本信息

```kotlin
data class ProductDetails(
    val productId: String,              // 商品 ID
    val productType: String,            // 商品类型："subs" 或 "inapp"
    val title: String,                  // 商品标题
    val name: String,                   // 商品名称
    val description: String             // 商品描述
)
```

**示例：**
```kotlin
productId = "premium_monthly"          // 高级版包月订阅
productType = "subs"                   // 订阅商品
title = "高级版会员 - 月度订阅"
name = "Premium Monthly"
description = "解锁所有高级功能，每月自动续费"
```

---

### 1.2 订阅商品结构

订阅商品包含 `subscriptionOfferDetails` 列表，每个优惠方案包含多个定价阶段。

#### **SubscriptionOfferDetails 结构**

```kotlin
data class SubscriptionOfferDetails(
    val basePlanId: String,                    // 基础方案 ID
    val offerId: String?,                      // 优惠 ID（可选）
    val offerToken: String,                    // 优惠 Token（发起支付必备）
    val offerTags: List<String>,               // 优惠标签列表
    val pricingPhases: PricingPhases           // 定价阶段
)
```

#### **PricingPhases 定价阶段**

```kotlin
data class PricingPhases(
    val pricingPhaseList: List<PricingPhase>   // 定价阶段列表
)

data class PricingPhase(
    val priceAmountMicros: Long,               // 价格（微美元）
    val priceCurrencyCode: String,             // 货币代码
    val formattedPrice: String,                // 格式化价格（如 "$9.99"）
    val billingPeriod: String,                 // 计费周期（ISO 8601 格式）
    val billingCycleCount: Int?,               // 计费周期数（null=无限）
    val recurrenceMode: Int                    // 循环模式
)
```

#### **循环模式（RecurrenceMode）**

```kotlin
INFINITE_RECURRING = 1    // 无限循环（正常订阅）
FINITE_RECURRING = 2      // 有限循环（固定次数后停止）
NON_RECURRING = 3         // 非循环（一次性）
```

#### **优惠标签（OfferTags）常见值**

```kotlin
"free_trial"        // 免费试用
"introductory"      // introductory 优惠
"conversion"        // 转换优惠（挽留优惠）
"upgrade"           // 升级优惠
"downgrade"         // 降级优惠
"promotional"       // 促销活动
```

---

### 1.3 一次性商品结构

一次性商品包含 `oneTimePurchaseOfferDetailsList` 列表。

#### **OneTimePurchaseOfferDetails 结构**

```kotlin
data class OneTimePurchaseOfferDetails(
    val priceAmountMicros: Long,           // 价格（微美元）
    val priceCurrencyCode: String,         // 货币代码
    val formattedPrice: String,            // 格式化价格
    val offerId: String?,                  // 优惠 ID
    val offerToken: String,                // 优惠 Token
    val offerTags: List<String>?,          // 优惠标签
    
    // 折扣信息
    val discountDisplayInfo: DiscountDisplayInfo?,
    
    // 限购信息
    val limitedQuantityInfo: LimitedQuantityInfo?,
    
    // 预售信息
    val preorderDetails: PreorderDetails?,
    
    // 租赁信息（视频租赁等）
    val rentalDetails: RentalDetails?,
    
    // 有效时间窗口
    val validTimeWindow: ValidTimeWindow?
)
```

---

### 1.4 完整示例

#### **示例 1：订阅商品（首月免费 + 正常订阅）**

```kotlin
ProductDetails(
    productId = "premium_monthly",
    productType = "subs",
    title = "高级版会员 - 月度订阅",
    name = "Premium Monthly",
    description = "解锁所有高级功能",
    
    subscriptionOfferDetails = [
        SubscriptionOfferDetails(
            basePlanId = "monthly_base",
            offerId = "free_trial_1month",
            offerToken = "abc123xyz_token",
            offerTags = ["free_trial", "promotional"],
            
            pricingPhases = PricingPhases(
                pricingPhaseList = [
                    // 阶段 1：首月免费试用
                    PricingPhase(
                        priceAmountMicros = 0,              // 免费
                        priceCurrencyCode = "USD",
                        formattedPrice = "$0.00",
                        billingPeriod = "P1M",              // 1 个月
                        billingCycleCount = 1,              // 仅 1 次
                        recurrenceMode = FINITE_RECURRING   // 有限循环
                    ),
                    
                    // 阶段 2：正常订阅价格
                    PricingPhase(
                        priceAmountMicros = 9990000,        // $9.99
                        priceCurrencyCode = "USD",
                        formattedPrice = "$9.99",
                        billingPeriod = "P1M",              // 1 个月
                        billingCycleCount = null,           // 无限循环
                        recurrenceMode = INFINITE_RECURRING // 无限循环
                    )
                ]
            )
        )
    ]
)
```

#### **示例 2：包年订阅（直接年付）**

```kotlin
ProductDetails(
    productId = "premium_yearly",
    productType = "subs",
    title = "高级版会员 - 年度订阅",
    name = "Premium Yearly",
    description = "解锁所有高级功能，年付更优惠",
    
    subscriptionOfferDetails = [
        SubscriptionOfferDetails(
            basePlanId = "yearly_base",
            offerId = null,                     // 无特殊优惠
            offerToken = "xyz789_token",
            offerTags = [],
            
            pricingPhases = PricingPhases(
                pricingPhaseList = [
                    PricingPhase(
                        priceAmountMicros = 99990000,     // $99.99
                        priceCurrencyCode = "USD",
                        formattedPrice = "$99.99",
                        billingPeriod = "P1Y",            // 1 年
                        billingCycleCount = null,         // 无限循环
                        recurrenceMode = INFINITE_RECURRING
                    )
                ]
            )
        )
    ]
)
```

#### **示例 3：一次性消耗商品（游戏金币）**

```kotlin
ProductDetails(
    productId = "gold_coins_100",
    productType = "inapp",
    title = "100 金币",
    name = "Gold Coins 100",
    description = "用于购买游戏道具",
    
    oneTimePurchaseOfferDetailsList = [
        OneTimePurchaseOfferDetails(
            priceAmountMicros = 1990000,          // $1.99
            priceCurrencyCode = "USD",
            formattedPrice = "$1.99",
            offerId = "summer_sale",
            offerToken = "gold100_token",
            offerTags = ["seasonal_promotion"],
            
            // 折扣信息
            discountDisplayInfo = DiscountDisplayInfo(
                discountAmount = 500000,          // 优惠$0.50
                percentageDiscount = 20.0         // 8 折优惠
            ),
            
            // 原价
            fullPriceMicros = 2490000,            // $2.49
            
            // 限购信息
            limitedQuantityInfo = LimitedQuantityInfo(
                maximumQuantity = 10,             // 最多买 10 个
                remainingQuantity = 5             // 还剩 5 个可买
            )
        )
    ]
)
```

---

## 二、订单详情参数（Purchase）

### 2.1 基本信息

```kotlin
data class Purchase(
    val orderId: String,                       // 订单 ID（唯一标识）
    val products: List<String>,                // 商品 ID 列表
    val purchaseTime: Long,                    // 购买时间戳（毫秒）
    val purchaseState: Int,                    // 购买状态
    val purchaseToken: String,                 // 购买令牌（确认/消费必备）
    val isAcknowledged: Boolean,               // 是否已确认
    val signature: String,                     // 订单签名
    val originalJson: String                   // 原始 JSON 数据
)
```

---

### 2.2 订单状态（PurchaseState）

```kotlin
PURCHASED = 0     // 已支付（成功完成交易）
PENDING = 1       // 待处理（等待支付确认）
UNSPECIFIED_STATE = 2  // 未指定状态（无效）
```

---

### 2.3 用户账户信息

```kotlin
data class AccountIdentifiers(
    val obfuscatedAccountId: String?,    // 混淆后的账号 ID（开发者传入）
    val obfuscatedProfileId: String?,    // 混淆后的个人资料 ID
    val buyerAccount: String?            // 买家邮箱（仅部分情况可见）
)
```

---

### 2.4 完整示例

#### **示例 1：成功的订阅订单**

```kotlin
Purchase(
    orderId = "GPA.1234-5678-9012-34567",
    products = ["premium_monthly"],
    purchaseTime = 1712000000000L,           // 2024-04-01 12:00:00
    purchaseState = PURCHASED,               // 已支付
    purchaseToken = "purchase_token_abc123",
    isAcknowledged = false,                  // 尚未确认（需要调用 acknowledgePurchase）
    signature = "base64_signature_string...",
    originalJson = "{...}",
    
    accountIdentifiers = AccountIdentifiers(
        obfuscatedAccountId = "user_12345",  // 开发者传入的用户 ID
        obfuscatedProfileId = null,
        buyerAccount = null                  // 通常不可见
    )
)
```

#### **示例 2：待处理的订单（延迟支付）**

```kotlin
Purchase(
    orderId = "GPA.9876-5432-1098-76543",
    products = ["premium_yearly"],
    purchaseTime = 1712000000000L,
    purchaseState = PENDING,                 // 待处理（如银行转账中）
    purchaseToken = "pending_token_xyz",
    isAcknowledged = false,
    signature = "base64_signature...",
    originalJson = "{...}",
    
    accountIdentifiers = AccountIdentifiers(
        obfuscatedAccountId = "user_67890",
        obfuscatedProfileId = null,
        buyerAccount = null
    )
)
```

---

## 三、关键要点总结

### ✅ 商品详情关键点

1. **offerToken 是发起支付的必备参数**
   - 订阅商品：从 `subscriptionOfferDetails` 获取
   - 一次性商品：从 `oneTimePurchaseOfferDetails` 获取

2. **定价阶段可能包含多个 Phase**
   - Phase 1：免费试用期（$0, P1M, billingCycleCount=1）
   - Phase 2：正常价格（$9.99, P1M, billingCycleCount=null）

3. **优惠标签用于识别促销类型**
   - `"free_trial"` - 免费试用
   - `"introductory"` - introductory 优惠

### ✅ 订单详情关键点

1. **isAcknowledged 必须判断**
   - `true`：已确认，直接发放权益
   - `false`：需要调用 `acknowledgePurchase` 确认

2. **purchaseState 决定处理方式**
   - `PURCHASED`：已支付成功
   - `PENDING`：待处理，等待支付平台确认

3. **purchaseToken 是确认/消费的凭证**
   - 确认订阅：`acknowledgePurchase(purchaseToken)`
   - 消耗商品：`consumePurchase(purchaseToken)`

4. **obfuscatedAccountId 用于关联用户**
   - 在 `makePayment` 时传入用户 ID
   - 在回调中可通过此字段识别是哪个用户的订单

---

## 四、常见问题

### Q1: 为什么查询到的商品没有价格信息？
**A:** 可能原因：
- 商品在 Google Play 后台未上架
- 应用签名与 Google Play 不一致
- 商品 ID 配置错误
- 测试环境未正确配置

### Q2: 什么时候需要调用 acknowledgePurchase？
**A:** 
- 订阅商品：必须调用（3 天内不确认会被退款）
- 一次性非消耗商品：必须调用
- 一次性消耗商品：调用 `consumePurchase` 而不是 `acknowledgePurchase`

### Q3: 如何处理 PENDING 状态的订单？
**A:**
- 不要立即发放权益
- 提示用户"支付申请已提交，等待确认"
- 等待支付平台回调通知（会自动触发 `onPurchasesUpdated`）

### Q4: 如何识别用户取消支付？
**A:**
- `BillingResult.responseCode == USER_CANCELED`
- 此时 `purchases` 列表通常为 null 或空

---

**文档版本**: v1.1  
**适用版本**: Google Play Billing Library 8.3.0+
**最后更新**: 2026-04-03
