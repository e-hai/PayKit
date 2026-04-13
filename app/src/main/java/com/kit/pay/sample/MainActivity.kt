package com.kit.pay.sample

import android.app.Activity
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kit.pay.models.ProductType
import com.kit.pay.models.StoreProduct
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * 主 Activity（使用 Compose 实现所有页面）
 * 
 * 功能：
 * 1. 开屏页 - 显示品牌标识和加载动画
 * 2. 初始化支付 SDK
 * 3. 恢复未完成订单
 * 4. 检查 VIP 状态
 * 5. 根据 VIP 状态显示不同的主页面
 */
class MainActivity : ComponentActivity() {

    private val mainViewModel by viewModels<MainViewModel>()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        // 安装启动屏（Android 12+）
        installSplashScreen()
        
        // 启用全面屏
        enableEdgeToEdge()
        
        super.onCreate(savedInstanceState)
        
        // 初始化 SDK 并监听 VIP 状态
        initSdkAndObserveVipStatus()
        
        // 监听错误信息
        observeErrorMessages()
        
        // 设置 Compose UI
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF6200EE),
                    secondary = Color(0xFF03DAC6),
                    background = Color(0xFFFFFBFE),
                    surface = Color(0xFFFFFBFE),
                    onPrimary = Color.White,
                    onSecondary = Color.Black,
                    onBackground = Color.Black,
                    onSurface = Color.Black
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // 收集 ViewModel 中的 UI 状态
                    val uiState by mainViewModel.uiState.collectAsState()
                    val productList by mainViewModel.productList.collectAsState()
                    // UI state collectors
                    val subsProducts by mainViewModel.subsProducts.collectAsState()
                    val consumableProducts by mainViewModel.consumableProducts.collectAsState()
                    val nonConsumableProducts by mainViewModel.nonConsumableProducts.collectAsState()
                    
                    when (val state = uiState) {
                        is MainViewModel.UiState.Loading -> SplashScreenContent()
                        is MainViewModel.UiState.IsVip -> MainContent(
                            isVip = true,
                            subsProducts = subsProducts,
                            consumableProducts = consumableProducts,
                            nonConsumableProducts = nonConsumableProducts,
                            onPurchaseClick = { productId, offerId ->
                                // 直接调用购买函数，传入当前 Activity
                                mainViewModel.purchaseProduct(WeakReference(this@MainActivity as Activity), productId, offerId)
                            },
                            onFeatureClick = { action ->
                                // 根据 action 直接调用对应函数
                                when (action) {
                                    "query_products" -> Log.d(TAG, "Not implemented in current UI flow")
                                    "recover_orders" -> mainViewModel.recoverOrdersWithToast()
                                    "manage_entitlements" -> mainViewModel.checkEntitlementsWithToast()
                                    else -> Log.w(TAG, "未知功能：$action")
                                }
                            },
                            onQuerySubsClick = {
                                mainViewModel.querySubsProducts(this@MainActivity)
                            },
                            onQueryConsumableClick = {
                                mainViewModel.queryConsumableProducts(this@MainActivity)
                            },
                            onQueryNonConsumableClick = {
                                mainViewModel.queryNonConsumableProducts(this@MainActivity)
                            }
                        )
                        is MainViewModel.UiState.IsNotVip -> MainContent(
                            isVip = false,
                            subsProducts = subsProducts,
                            consumableProducts = consumableProducts,
                            nonConsumableProducts = nonConsumableProducts,
                            onPurchaseClick = { productId, offerId ->
                                // 直接调用购买函数，传入当前 Activity
                                mainViewModel.purchaseProduct(WeakReference(this@MainActivity), productId, offerId)
                            },
                            onFeatureClick = { action ->
                                // 根据 action 直接调用对应函数
                                when (action) {
                                    "query_products" -> Log.d(TAG, "Not implemented in current UI flow")
                                    "recover_orders" -> mainViewModel.recoverOrdersWithToast()
                                    "manage_entitlements" -> mainViewModel.checkEntitlementsWithToast()
                                    else -> Log.w(TAG, "未知功能：$action")
                                }
                            },
                            onQuerySubsClick = {
                                mainViewModel.querySubsProducts(this@MainActivity)
                            },
                            onQueryConsumableClick = {
                                mainViewModel.queryConsumableProducts(this@MainActivity)
                            },
                            onQueryNonConsumableClick = {
                                mainViewModel.queryNonConsumableProducts(this@MainActivity)
                            }
                        )
                    }
                }
            }
        }
    }
    
    /**
     * 初始化 SDK 并监听 VIP 状态
     */
    private fun initSdkAndObserveVipStatus() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                // 收集 VIP 状态变化（用于其他逻辑）
                launch {
                    mainViewModel.isSubscriber.collect { isVip ->
                        Log.d(TAG, "VIP 状态更新：$isVip")
                    }
                }
                
                // 初始化 SDK（会自动触发订单恢复和 VIP 状态检查）
                launch {
                    mainViewModel.init()
                    
                    // 等待一段时间确保 SDK 初始化完成
                    delay(3000)
                    
                    // 如果超过 3 秒还没收到 VIP 状态，主动检查一次
                    if (mainViewModel.uiState.value is MainViewModel.UiState.Loading) {
                        Log.d(TAG, "SDK 初始化超时，主动检查 VIP 状态")
                        mainViewModel.checkEntitlementsWithToast()
                    }
                }
            }
        }
    }
    
    /**
     * 监听错误信息并显示
     */
    private fun observeErrorMessages() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainViewModel.errorMessages.collectLatest { errorMessage ->
                    Log.e(TAG, "错误：$errorMessage")
                    // TODO: 这里可以使用 Snackbar 或 Dialog 显示错误
                    // 目前使用 Toast 简单提示
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        errorMessage,
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // 无需清理 Activity，因为不再持有引用
    }
    
    companion object {
        private const val TAG = "MainActivity"
    }
}

/**
 * 开屏页内容
 */
@Composable
fun SplashScreenContent() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF6200EE),
                        Color(0xFF3700B3)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 品牌 Logo（用圆形代替）
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Pay",
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // 应用名称
            Text(
                text = "支付示例",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 副标题
            Text(
                text = "安全 · 便捷 · 高效",
                fontSize = 16.sp,
                color = Color.White.copy(alpha = 0.8f)
            )
            
            Spacer(modifier = Modifier.height(48.dp))
            
            // 加载指示器
            CircularProgressIndicator(
                modifier = Modifier.size(40.dp),
                color = Color.White,
                strokeWidth = 3.dp
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "正在初始化...",
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * 主页面内容
 */
@Composable
fun MainContent(
    isVip: Boolean,
    onPurchaseClick: (String, String) -> Unit = { _, _ -> },
    onFeatureClick: (String) -> Unit = {},
    subsProducts: List<MainViewModel.ProductItem> = emptyList(),
    consumableProducts: List<MainViewModel.ProductItem> = emptyList(),
    nonConsumableProducts: List<MainViewModel.ProductItem> = emptyList(),
    onQuerySubsClick: () -> Unit = {},
    onQueryConsumableClick: () -> Unit = {},
    onQueryNonConsumableClick: () -> Unit = {}
) {
    var showProductList by remember { mutableStateOf(false) }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8F9FA))
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 顶部状态栏
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isVip) {
                            Color(0xFF10B981)  // 翡翠绿
                        } else {
                            Color(0xFF6366F1)  // 靛蓝色
                        }
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    shape = MaterialTheme.shapes.large
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            // VIP 图标
                            Text(
                                text = if (isVip) "👑" else "✨",
                                fontSize = 48.sp
                            )
                            
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            Text(
                                text = if (isVip) "VIP 尊贵用户" else "普通会员",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            
                            Spacer(modifier = Modifier.height(6.dp))
                            
                            Text(
                                text = if (isVip) "感谢支持，享受专属权益" else "升级 VIP，解锁更多功能",
                                fontSize = 14.sp,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                        }
                    }
                }
            }
            
            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
            
            // 查询商品区域
            item {
                SectionTitle(title = "💎 查询商品")
            }
            
            item {
                // 订阅商品查询按钮
                QueryButton(
                    text = "查询订阅商品",
                    onClick = { onQuerySubsClick() },
                    icon = "📦"
                )
            }
            
            item {
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            item {
                // 订阅商品横向列表
                if (subsProducts.isNotEmpty()) {
                    ProductHorizontalList(
                        products = subsProducts,
                        title = "订阅商品",
                        onPurchaseClick = onPurchaseClick
                    )
                }
            }
            
            item {
                Spacer(modifier = Modifier.height(12.dp))
            }
            
            item {
                // 消耗商品查询按钮
                QueryButton(
                    text = "查询消耗商品",
                    onClick = { onQueryConsumableClick() },
                    icon = "⚡"
                )
            }
            
            item {
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            item {
                // 消耗商品横向列表
                if (consumableProducts.isNotEmpty()) {
                    ProductHorizontalList(
                        products = consumableProducts,
                        title = "消耗商品",
                        onPurchaseClick = onPurchaseClick
                    )
                }
            }
            
            item {
                Spacer(modifier = Modifier.height(12.dp))
            }
            
            item {
                // 非消耗商品查询按钮
                QueryButton(
                    text = "查询非消耗商品",
                    onClick = { onQueryNonConsumableClick() },
                    icon = "🎁"
                )
            }
            
            item {
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            item {
                // 非消耗商品横向列表
                if (nonConsumableProducts.isNotEmpty()) {
                    ProductHorizontalList(
                        products = nonConsumableProducts,
                        title = "非消耗商品",
                        onPurchaseClick = onPurchaseClick
                    )
                }
            }
            
            // 功能区域
            item {
                SectionTitle(title = "⚙️ 管理功能")
            }
            
            item {
                FeatureButton(
                    text = "恢复未完成订单",
                    onClick = { onFeatureClick("recover_orders") },
                    icon = "🔄",
                    backgroundColor = Color(0xFF3B82F6)
                )
            }
            
            item {
                FeatureButton(
                    text = "检查权益状态",
                    onClick = { onFeatureClick("manage_entitlements") },
                    icon = "✅",
                    backgroundColor = Color(0xFF8B5CF6)
                )
            }
        }
    }
}

/**
 * 商品卡片组件
 */
@Composable
fun ProductCard(
    product: StoreProduct,
    isSelected: Boolean = false,
    onSelect: () -> Unit = {},
    onPurchase: (String, String) -> Unit = { _, _ -> }
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Color(0xFFE3F2FD) else Color.White
        ),
        onClick = { onSelect() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = product.title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Text(
                        text = product.description,
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "类型：${when (product.type) {
                            ProductType.SUBS -> "订阅商品"
                            ProductType.INAPP -> "一次性商品"
                            else -> "未知"
                        }}",
                        fontSize = 12.sp,
                        color = Color.Blue
                    )
                }
                
                // 购买按钮
                Button(
                    onClick = { 
                        // 默认使用基础方案
                        val offerId = when {
                            product.productId.contains("MONTH") -> Constants.BASIC_MONTHLY_PLAN
                            product.productId.contains("YEAR") -> Constants.BASIC_YEARLY_PLAN
                            else -> ""
                        }
                        onPurchase(product.productId, offerId)
                    }
                ) {
                    Text("购买")
                }
            }
        }
    }
}

/**
 * 横向滑动商品列表（支持成功和失败状态）
 */
@Composable
fun ProductHorizontalList(
    products: List<MainViewModel.ProductItem>,
    title: String,
    onPurchaseClick: (String, String) -> Unit = { _, _ -> }
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "$title (${products.size}个)",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.Black
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // 横向滑动列表
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(products.size) { index ->
                val item = products[index]
                ProductCardWithStatus(
                    productItem = item,
                    onPurchase = { productId, offerId ->
                        // 如果是成功的商品，发起购买
                        if (item.isSuccess) {
                            onPurchaseClick(productId, offerId)
                        }
                    }
                )
            }
        }
    }
}

/**
 * 带状态的商品卡片（成功/失败）
 */
@Composable
fun ProductCardWithStatus(
    productItem: MainViewModel.ProductItem,
    onPurchase: (String, String) -> Unit
) {
    Card(
        modifier = Modifier
            .width(200.dp)
            .height(140.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (productItem.isSuccess) {
                Color(0xFFE8F5E9)  // 成功 - 浅绿色
            } else {
                Color(0xFFFFEBEE)  // 失败 - 浅红色
            }
        ),
        border = BorderStroke(
            2.dp,
            if (productItem.isSuccess) Color(0xFF4CAF50) else Color(0xFFF44336)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 顶部：状态标识
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (productItem.isSuccess) "✓ 成功" else "✗ 失败",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (productItem.isSuccess) Color(0xFF2E7D32) else Color(0xFFC62828)
                )
                
                Text(
                    text = productItem.productType.name,
                    fontSize = 10.sp,
                    color = Color.Gray
                )
            }
            
            // 中间：商品信息
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = productItem.productId,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    color = Color.Black
                )
                
                if (!productItem.isSuccess) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = productItem.errorMessage ?: "未知错误",
                        fontSize = 10.sp,
                        color = Color.Red,
                        maxLines = 2
                    )
                }
            }
            
            // 底部：购买按钮
            if (productItem.isSuccess) {
                Button(
                    onClick = { 
                        val offerId = when {
                            productItem.productId.contains("MONTH") -> Constants.BASIC_MONTHLY_PLAN
                            productItem.productId.contains("YEAR") -> Constants.BASIC_YEARLY_PLAN
                            else -> ""
                        }
                        onPurchase(productItem.productId, offerId)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("购买", fontSize = 12.sp)
                }
            }
        }
    }
}

/**
 * 章节标题
 */
@Composable
fun SectionTitle(title: String) {
    Text(
        text = title,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF1F2937),
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

/**
 * 查询按钮
 */
@Composable
fun QueryButton(
    text: String,
    onClick: () -> Unit,
    icon: String
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF6366F1)
        ),
        shape = MaterialTheme.shapes.medium,
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
    ) {
        Text(
            text = "$icon  $text",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * 功能按钮
 */
@Composable
fun FeatureButton(
    text: String,
    onClick: () -> Unit,
    icon: String,
    backgroundColor: Color
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = backgroundColor
        ),
        shape = MaterialTheme.shapes.medium,
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = icon,
                fontSize = 20.sp
            )
            Text(
                text = text,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
