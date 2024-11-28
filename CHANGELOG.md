# 更新日志

## [未发布] - 根据文档完善项目逻辑

### 新增功能

#### 1. 订单恢复机制
- **PaymentManager.recoverUnfinishedOrders()** - 新增订单恢复方法
  - 自动查询并处理应用重启后未确认/未消费的订单
  - 支持订阅商品、一次性非消耗商品、一次性消耗商品的自动处理
  - 提供订单恢复进度回调
  - 处理待支付（PENDING）状态的订单

#### 2. 手动订单操作方法
- **GoogleBillingProvider.acknowledgePurchaseByToken()** - 手动确认订单
  - 适用于应用重启后恢复未确认的订阅和非消耗商品
  - 通过 purchaseToken 直接操作，无需重新查询 Purchase 对象
  
- **GoogleBillingProvider.consumePurchaseByToken()** - 手动消费订单
  - 适用于应用重启后恢复未消费的一次性消耗商品
  - 提供独立的消费接口

#### 3. 增强的购买详情
- **GooglePurchaseDetails** 新增字段：
  - `purchaseToken` - 购买令牌，用于后续确认或消费操作
  - `isAcknowledged` - 订单确认状态，标识是否已确认
  - 公开属性访问权限，便于外部使用

#### 4. 示例代码完善
- **MainViewModel** 新增方法：
  - `actionLoadSubProductList()` - 加载商品列表的完整实现
  - `actionRecoverUnfinishedOrders()` - 订单恢复的示例调用
  - `actionPurchase()` - 发起支付的完整实现
  - `_productList` - 商品列表状态管理

- **MainActivity** 初始化流程完善：
  - 添加商品列表加载调用
  - 添加订单恢复调用
  - 完整的启动初始化流程

### 改进内容

#### 1. queryPurchases 逻辑优化
- **修改前**：查询时自动调用 handlePurchase 处理订单
- **修改后**：查询仅返回购买详情，不自动处理订单
- **原因**：符合文档要求，将订单处理决策权交给业务层

#### 2. 文档与代码一致性
- 确保代码实现完全符合 README 文档描述的流程
- 补充了文档中提到的所有关键场景的实现
- 添加了详细的注释说明

### 修复问题

1. **编译错误修复**
   - 修复了 Kotlin 属性访问权限问题
   - 修复了并发访问 mutable 属性的智能转换问题
   - 统一使用 getter 方法访问接口属性

2. **逻辑错误修复**
   - 修正了订单查询时的自动确认逻辑
   - 修复了配置信息获取方式

### 技术细节

#### 线程安全处理
```kotlin
val provider = paymentProvider  // 缓存到局部变量避免并发问题
if (provider is GoogleBillingProvider) {
    // 安全使用 provider
}
```

#### 反射获取配置
```kotlin
private fun getBillingConfig(provider: GoogleBillingProvider): GoogleBillingConfig {
    try {
        val field = provider.javaClass.getDeclaredField("config")
        field.isAccessible = true
        return field.get(provider) as GoogleBillingConfig
    } catch (e: Exception) {
        Log.e(TAG, "获取配置失败", e)
        return GoogleBillingConfig(emptyList(), emptyList(), emptyList())
    }
}
```

### 使用示例

#### 应用启动时恢复订单
```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    // 初始化 SDK
    mainViewModel.init()
    
    // 检查订阅状态
    mainViewModel.actionCheckSubscriber()
    
    // 加载商品列表
    mainViewModel.actionLoadSubProductList()
    
    // 恢复未完成的订单（重要！）
    mainViewModel.actionRecoverUnfinishedOrders()
}
```

#### 手动处理特定类型商品
```kotlin
// 恢复订阅商品
PaymentManager.getInstance().recoverUnfinishedOrders(
    productType = PaymentProductType.SUBS,
    onOrderRecovered = { products ->
        // 发放订阅权益
    },
    onComplete = {
        Log.d(TAG, "订阅商品恢复完成")
    }
)

// 恢复一次性商品
PaymentManager.getInstance().recoverUnfinishedOrders(
    productType = PaymentProductType.INAPP,
    onOrderRecovered = { products ->
        // 发放商品权益
    },
    onComplete = {
        Log.d(TAG, "一次性商品恢复完成")
    }
)
```

### API 变更

#### 新增公共 API
- `PaymentManager.recoverUnfinishedOrders()`
- `GoogleBillingProvider.acknowledgePurchaseByToken()`
- `GoogleBillingProvider.consumePurchaseByToken()`
- `GooglePurchaseDetails.getPurchaseToken()`
- `GooglePurchaseDetails.isAcknowledged()`

#### 行为变更
- `queryPurchases()` 不再自动确认/消费订单
- 订单确认/消费逻辑移至 `recoverUnfinishedOrders()` 和支付成功回调

### 迁移指南

如果您的项目已经在使用此 SDK，需要进行以下调整：

1. **在应用启动时调用订单恢复**：
   ```kotlin
   PaymentManager.getInstance().recoverUnfinishedOrders(
       productType = PaymentProductType.SUBS,
       onOrderRecovered = { /* 发放权益 */ },
       onComplete = { /* 恢复完成 */ }
   )
   ```

2. **如果之前依赖 queryPurchases 自动确认**：
   - 改为手动调用 `acknowledgePurchaseByToken()` 或 `consumePurchaseByToken()`
   - 或使用新的 `recoverUnfinishedOrders()` 方法

### 测试建议

1. **正常支付流程测试**
   - 测试订阅商品的购买和自动确认
   - 测试一次性非消耗商品的购买和确认
   - 测试一次性消耗商品的购买和消费

2. **异常场景测试**
   - 模拟应用被强退后重启，验证订单恢复
   - 测试 PENDING 状态订单的处理
   - 测试跨设备购买的恢复

3. **边界条件测试**
   - 测试空商品列表
   - 测试网络断开时的表现
   - 测试快速重复点击

### 注意事项

1. **必须调用**：应用每次启动时必须调用 `recoverUnfinishedOrders()` 以确保用户权益
2. **幂等性**：所有新增方法都支持多次调用，不会产生副作用
3. **异步处理**：所有操作都是异步的，需要通过回调获取结果
4. **配置一致**：确保 GoogleBillingConfig 中的商品 ID 与实际购买的商品一致

### 后续计划

- [ ] 添加单元测试
- [ ] 添加集成测试示例
- [ ] 完善错误处理和重试机制
- [ ] 添加更多日志输出便于调试
- [ ] 考虑支持其他支付平台（如 Amazon In-App Purchasing）
