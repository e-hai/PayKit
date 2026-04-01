package com.kit.pay.sample

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.kit.pay.base.PaymentProductDetails
import com.kit.pay.base.PaymentProductType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
        
        // 设置当前 Activity，用于发起支付
        mainViewModel.setCurrentActivity(this)
        
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
                    val selectedProduct by mainViewModel.selectedProduct.collectAsState()
                    
                    when (val state = uiState) {
                        is MainViewModel.UiState.Loading -> SplashScreenContent()
                        is MainViewModel.UiState.IsVip -> MainContent(
                            isVip = true,
                            productList = productList,
                            selectedProduct = selectedProduct,
                            onProductSelect = { product ->
                                mainViewModel.selectProduct(product)
                            },
                            onPurchaseClick = { productId, offerId ->
                                if (offerId.isNotEmpty()) {
                                    mainViewModel.handleFeatureAction("purchase_subs_$productId")
                                } else {
                                    // 根据商品类型判断
                                    mainViewModel.handleFeatureAction(if (productId.contains("SUBS")) "purchase_subs_month" else "purchase_consumable")
                                }
                            },
                            onClearSelection = {
                                mainViewModel.clearSelectedProduct()
                            },
                            onFeatureClick = { action ->
                                mainViewModel.handleFeatureAction(action)
                            }
                        )
                        is MainViewModel.UiState.IsNotVip -> MainContent(
                            isVip = false,
                            productList = productList,
                            selectedProduct = selectedProduct,
                            onProductSelect = { product ->
                                mainViewModel.selectProduct(product)
                            },
                            onPurchaseClick = { productId, offerId ->
                                if (offerId.isNotEmpty()) {
                                    mainViewModel.handleFeatureAction("purchase_subs_$productId")
                                } else {
                                    // 根据商品类型判断
                                    mainViewModel.handleFeatureAction(if (productId.contains("SUBS")) "purchase_subs_month" else "purchase_consumable")
                                }
                            },
                            onClearSelection = {
                                mainViewModel.clearSelectedProduct()
                            },
                            onFeatureClick = { action ->
                                mainViewModel.handleFeatureAction(action)
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
                        mainViewModel.actionCheckVipStatus()
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
        // 清理 Activity 引用，防止内存泄漏
        mainViewModel.setCurrentActivity(null)
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
    productList: List<PaymentProductDetails> = emptyList(),
    selectedProduct: PaymentProductDetails? = null,
    onProductSelect: (PaymentProductDetails) -> Unit = {},
    onPurchaseClick: (String, String) -> Unit = { _, _ -> },
    onClearSelection: () -> Unit = {},
    onFeatureClick: (String) -> Unit = {}
) {
    var showProductList by remember { mutableStateOf(false) }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF5F5F5))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Top
        ) {
            // 顶部状态栏
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isVip) Color(0xFF03DAC6) else Color(0xFFBB86FC)
                )
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (isVip) "✓ VIP 用户" else "普通用户",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = if (isVip) "感谢您的支持！" else "升级 VIP 解锁更多功能",
                        fontSize = 14.sp,
                        color = Color.Black.copy(alpha = 0.7f)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // 快捷购买按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onPurchaseClick("subs_month", Constants.BASIC_MONTHLY_PLAN) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("包月订阅")
                }
                
                Button(
                    onClick = { onPurchaseClick("subs_year", Constants.BASIC_YEARLY_PLAN) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("包年订阅")
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onPurchaseClick(Constants.OTP_GAME_SKIN_3DAY, "") },
                    modifier = Modifier.weight(1f),
                    enabled = !isVip
                ) {
                    Text("消耗商品")
                }
                
                Button(
                    onClick = { onPurchaseClick(Constants.OTP_GAME_SKIN_PERMANENT, "") },
                    modifier = Modifier.weight(1f),
                    enabled = !isVip
                ) {
                    Text("永久商品")
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // 商品列表按钮
            Button(
                onClick = { 
                    showProductList = !showProductList
                    if (showProductList) {
                        onFeatureClick("query_products")
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (showProductList) "隐藏商品列表" else "显示商品列表")
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 商品列表详情
            if (showProductList && productList.isNotEmpty()) {
                Text(
                    text = "商品列表 (${productList.size}个)",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // 可滚动列表
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(productList.size) { index ->
                        val product = productList[index]
                        ProductCard(
                            product = product,
                            isSelected = selectedProduct?.productId == product.productId,
                            onSelect = { onProductSelect(product) },
                            onPurchase = { productId, offerId ->
                                onPurchaseClick(productId, offerId)
                            }
                        )
                    }
                }
                
                // 如果选中了商品，显示购买按钮
                selectedProduct?.let { product ->
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFE3F2FD)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = "已选：${product.title}",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { 
                                        onPurchaseClick(product.productId, Constants.BASIC_MONTHLY_PLAN)
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("购买")
                                }
                                
                                OutlinedButton(
                                    onClick = { onClearSelection() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("取消选择")
                                }
                            }
                        }
                    }
                }
            } else if (showProductList) {
                // 空状态提示
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无商品数据\n请先点击《商品查询》按钮",
                        textAlign = TextAlign.Center,
                        color = Color.Gray
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 功能列表
            Text(
                text = "管理功能",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 示例功能项
            val features = listOf(
                "商品查询" to "query_products",
                "发起支付" to "make_payment",
                "订单恢复" to "recover_orders",
                "权益管理" to "manage_entitlements"
            )
            
            features.forEach { (feature, action) ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.White
                    ),
                    onClick = { onFeatureClick(action) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = feature,
                            fontSize = 16.sp,
                            color = Color.Black
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/**
 * 商品卡片组件
 */
@Composable
fun ProductCard(
    product: PaymentProductDetails,
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
                        text = "类型：${when (product.productType) {
                            PaymentProductType.SUBS -> "订阅商品"
                            PaymentProductType.INAPP -> "一次性商品"
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
