package com.kit.pay.sample

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * PayKit Demo：初始化 SDK、查询商品、购买、恢复购买、查看权益。
 */
class MainActivity : ComponentActivity() {

    private val mainViewModel by viewModels<MainViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        mainViewModel.init()
        observeErrors()

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF4F46E5),
                    secondary = Color(0xFF0D9488),
                    background = Color(0xFFF8FAFC),
                    surface = Color.White,
                    onPrimary = Color.White,
                    onSecondary = Color.White,
                    onBackground = Color(0xFF0F172A),
                    onSurface = Color(0xFF0F172A)
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val uiState by mainViewModel.uiState.collectAsState()
                    val entitlement by mainViewModel.entitlement.collectAsState()
                    val subsProducts by mainViewModel.subsProducts.collectAsState()
                    val consumableProducts by mainViewModel.consumableProducts.collectAsState()
                    val nonConsumableProducts by mainViewModel.nonConsumableProducts.collectAsState()
                    val querying by mainViewModel.querying.collectAsState()

                    when (uiState) {
                        is MainViewModel.UiState.Loading -> SplashScreenContent()
                        is MainViewModel.UiState.Ready -> MainContent(
                            entitlement = entitlement,
                            querying = querying,
                            subsProducts = subsProducts,
                            consumableProducts = consumableProducts,
                            nonConsumableProducts = nonConsumableProducts,
                            onPurchase = { item ->
                                mainViewModel.purchase(this@MainActivity, item)
                            },
                            onRestore = { mainViewModel.restorePurchases() },
                            onCheckEntitlements = { mainViewModel.checkEntitlements() },
                            onQuerySubs = { mainViewModel.querySubsProducts() },
                            onQueryConsumable = { mainViewModel.queryConsumableProducts() },
                            onQueryNonConsumable = { mainViewModel.queryNonConsumableProducts() }
                        )
                    }
                }
            }
        }
    }

    private fun observeErrors() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainViewModel.errorMessages.collectLatest { message ->
                    Log.e(TAG, "error=$message")
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    companion object {
        private const val TAG = "PayKit-Sample"
    }
}

@Composable
fun SplashScreenContent() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF4F46E5), Color(0xFF312E81))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Pay",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.height(28.dp))
            Text(
                text = "PayKit Sample",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "查询 · 购买 · 恢复 · 权益",
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.8f)
            )
            Spacer(modifier = Modifier.height(40.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(36.dp),
                color = Color.White,
                strokeWidth = 3.dp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "正在同步购买记录…",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
fun MainContent(
    entitlement: MainViewModel.EntitlementUi,
    querying: Boolean,
    subsProducts: List<MainViewModel.ProductItem>,
    consumableProducts: List<MainViewModel.ProductItem>,
    nonConsumableProducts: List<MainViewModel.ProductItem>,
    onPurchase: (MainViewModel.ProductItem) -> Unit,
    onRestore: () -> Unit,
    onCheckEntitlements: () -> Unit,
    onQuerySubs: () -> Unit,
    onQueryConsumable: () -> Unit,
    onQueryNonConsumable: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF8FAFC))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            StatusHeader(entitlement = entitlement)
        }

        item { SectionTitle("查询商品") }

        item {
            QueryButton(
                text = if (querying) "查询中…" else "查询订阅商品",
                enabled = !querying,
                onClick = onQuerySubs
            )
        }
        if (subsProducts.isNotEmpty()) {
            item {
                SubscriptionOffersSection(
                    products = subsProducts,
                    onPurchase = onPurchase
                )
            }
        }

        item {
            QueryButton(
                text = if (querying) "查询中…" else "查询消耗商品",
                enabled = !querying,
                onClick = onQueryConsumable
            )
        }
        if (consumableProducts.isNotEmpty()) {
            item {
                ProductHorizontalList(
                    title = "消耗商品",
                    products = consumableProducts,
                    onPurchase = onPurchase
                )
            }
        }

        item {
            QueryButton(
                text = if (querying) "查询中…" else "查询非消耗商品",
                enabled = !querying,
                onClick = onQueryNonConsumable
            )
        }
        if (nonConsumableProducts.isNotEmpty()) {
            item {
                ProductHorizontalList(
                    title = "非消耗商品",
                    products = nonConsumableProducts,
                    onPurchase = onPurchase
                )
            }
        }

        item { SectionTitle("管理") }

        item {
            FeatureButton(
                text = "恢复购买",
                onClick = onRestore,
                backgroundColor = Color(0xFF2563EB)
            )
        }
        item {
            FeatureButton(
                text = "检查权益（强制同步）",
                onClick = onCheckEntitlements,
                backgroundColor = Color(0xFF7C3AED)
            )
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun StatusHeader(entitlement: MainViewModel.EntitlementUi) {
    val tier = entitlement.tier
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(168.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (tier) {
                MainViewModel.Tier.Free -> Color(0xFF4F46E5)
                MainViewModel.Tier.Plus -> Color(0xFF0D9488)
                MainViewModel.Tier.Pro -> Color(0xFF059669)
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = tier.name,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when (tier) {
                    MainViewModel.Tier.Free -> "免费档 · 升级 Plus 或 Pro 解锁更多权益"
                    MainViewModel.Tier.Plus -> "已开通 Plus · 可升级至 Pro"
                    MainViewModel.Tier.Pro -> "已开通 Pro 会员"
                },
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.9f)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = buildString {
                    append("非消耗 ${entitlement.nonConsumables.size}")
                    if (entitlement.pendingCount > 0) {
                        append(" · 待确认 ${entitlement.pendingCount}")
                    }
                },
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.75f)
            )
            if (entitlement.activeSubs.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = entitlement.activeSubs.joinToString(),
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.75f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Free + Plus + Pro 模型下的订阅购买区：档位/周期切换，主 CTA 优先试用。
 */
@Composable
fun SubscriptionOffersSection(
    products: List<MainViewModel.ProductItem>,
    onPurchase: (MainViewModel.ProductItem) -> Unit
) {
    var paidTier by remember(products) { mutableStateOf(PaidTier.Plus) }
    var selectedBasePlanId by remember(products, paidTier) { mutableStateOf<String?>(null) }

    val availableBasePlanIds = remember(products, paidTier) {
        products.asSequence()
            .filter { it.productId == paidTier.productId && it.isSuccess }
            .mapNotNull { it.product?.basePlanId }
            .distinct()
            .sortedBy(::basePlanOrder)
            .toList()
    }
    val activeBasePlanId = selectedBasePlanId
        ?.takeIf { it in availableBasePlanIds }
        ?: availableBasePlanIds.firstOrNull()
    var expanded by remember(products, paidTier, activeBasePlanId) { mutableStateOf(false) }

    val periodOffers = remember(products, paidTier, activeBasePlanId) {
        products.filter {
            it.productId == paidTier.productId &&
                it.isSuccess &&
                it.product?.basePlanId == activeBasePlanId
        }
    }
    val (primary, others) = remember(periodOffers) { pickPrimaryOffer(periodOffers) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "选择付费档",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF334155)
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PeriodChip(
                label = "Plus",
                selected = paidTier == PaidTier.Plus,
                onClick = { paidTier = PaidTier.Plus },
                modifier = Modifier.weight(1f)
            )
            PeriodChip(
                label = "Pro",
                selected = paidTier == PaidTier.Pro,
                onClick = { paidTier = PaidTier.Pro },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(availableBasePlanIds, key = { it }) { basePlanId ->
                PeriodChip(
                    label = basePlanLabel(basePlanId),
                    selected = basePlanId == activeBasePlanId,
                    onClick = { selectedBasePlanId = basePlanId },
                    modifier = Modifier.width(104.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (primary == null) {
            Text(
                text = if (availableBasePlanIds.isEmpty()) {
                    "${paidTier.name} 暂无可用计费周期"
                } else {
                    "当前周期暂无可用方案"
                },
                fontSize = 13.sp,
                color = Color(0xFF64748B)
            )
        } else {
            PrimaryOfferCard(
                item = primary,
                tier = paidTier,
                onPurchase = onPurchase
            )

            if (others.isNotEmpty()) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (expanded) {
                            "收起其他方案"
                        } else {
                            "查看其他方案（${others.size}）"
                        },
                        fontSize = 14.sp,
                        color = Color(0xFF4F46E5)
                    )
                }
                if (expanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        others.forEach { item ->
                            ProductCardWithStatus(
                                productItem = item,
                                onPurchase = onPurchase,
                                compact = true
                            )
                        }
                    }
                }
            }
        }

        val failed = products.filter { it.productId == paidTier.productId && !it.isSuccess }
        if (failed.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            failed.forEach { item ->
                Text(
                    text = "${item.productId}：${item.errorMessage ?: "查询失败"}",
                    fontSize = 12.sp,
                    color = Color(0xFFB91C1C)
                )
            }
        }
    }
}

private enum class PaidTier(val productId: String) {
    Plus(Constants.SUBS_PLUS),
    Pro(Constants.SUBS_PRO)
}

private fun basePlanLabel(basePlanId: String): String = when (basePlanId) {
    Constants.PLUS_MONTHLY, Constants.PRO_MONTHLY -> "月付"
    Constants.PLUS_QUARTERLY -> "季付"
    Constants.PLUS_YEARLY, Constants.PRO_YEARLY -> "年付"
    else -> basePlanId
}

private fun basePlanOrder(basePlanId: String): Int = when (basePlanId) {
    Constants.PLUS_MONTHLY, Constants.PRO_MONTHLY -> 0
    Constants.PLUS_QUARTERLY -> 1
    Constants.PLUS_YEARLY, Constants.PRO_YEARLY -> 2
    else -> 100
}

@Composable
private fun PeriodChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Color(0xFF4F46E5) else Color(0xFFE2E8F0),
            contentColor = if (selected) Color.White else Color(0xFF334155)
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Text(text = label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

/** 同周期内：优先免费试用 offer，否则取价格最高的默认价。 */
private fun pickPrimaryOffer(
    offers: List<MainViewModel.ProductItem>
): Pair<MainViewModel.ProductItem?, List<MainViewModel.ProductItem>> {
    if (offers.isEmpty()) return null to emptyList()
    val trial = offers.firstOrNull { it.product?.hasFreeTrial == true }
    val primary = trial ?: offers.maxByOrNull { it.product?.priceAmountMicros ?: 0L }!!
    val others = offers.filterNot { item ->
        item.productId == primary.productId &&
            item.product?.subscriptionToken == primary.product?.subscriptionToken
    }
    return primary to others
}

@Composable
private fun PrimaryOfferCard(
    item: MainViewModel.ProductItem,
    tier: PaidTier,
    onPurchase: (MainViewModel.ProductItem) -> Unit
) {
    val product = item.product!!
    val isTrial = product.hasFreeTrial
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFEEF2FF)),
        border = BorderStroke(2.dp, Color(0xFF4F46E5)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (isTrial) "含免费试用" else "${tier.name} 方案",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF4F46E5)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = product.title.ifBlank { item.productId },
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0F172A)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = product.price.ifBlank { "价格未知" },
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF4F46E5)
            )
            if (!product.basePlanId.isNullOrBlank() || !product.offerId.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = buildString {
                        product.basePlanId?.let { append("plan=$it") }
                        if (!product.basePlanId.isNullOrBlank() && !product.offerId.isNullOrBlank()) {
                            append(" · ")
                        }
                        product.offerId?.let { append("offer=$it") }
                    },
                    fontSize = 11.sp,
                    color = Color(0xFF64748B)
                )
            }
            if (product.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = product.description,
                    fontSize = 12.sp,
                    color = Color(0xFF64748B),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { onPurchase(item) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5))
            ) {
                Text(
                    text = if (isTrial) "开始 ${tier.name} 免费试用" else "订阅 ${tier.name}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun ProductHorizontalList(
    title: String,
    products: List<MainViewModel.ProductItem>,
    onPurchase: (MainViewModel.ProductItem) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$title（${products.size}）",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF334155)
        )
        Spacer(modifier = Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(products, key = { "${it.productId}-${it.product?.subscriptionToken}" }) { item ->
                ProductCardWithStatus(productItem = item, onPurchase = onPurchase)
            }
        }
    }
}

@Composable
fun ProductCardWithStatus(
    productItem: MainViewModel.ProductItem,
    onPurchase: (MainViewModel.ProductItem) -> Unit,
    compact: Boolean = false
) {
    val product = productItem.product
    Card(
        modifier = Modifier
            .then(if (compact) Modifier.fillMaxWidth() else Modifier.width(220.dp))
            .height(if (compact) 120.dp else 168.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (productItem.isSuccess) Color(0xFFECFDF5) else Color(0xFFFEF2F2)
        ),
        border = BorderStroke(
            1.dp,
            if (productItem.isSuccess) Color(0xFF34D399) else Color(0xFFF87171)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = when {
                        !productItem.isSuccess -> "失败"
                        compact -> "其他方案"
                        else -> "可购买"
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (productItem.isSuccess) Color(0xFF047857) else Color(0xFFB91C1C)
                )
                Text(
                    text = productItem.productType.name,
                    fontSize = 10.sp,
                    color = Color(0xFF64748B)
                )
            }

            Column {
                Text(
                    text = product?.title?.ifBlank { productItem.productId } ?: productItem.productId,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = Color(0xFF0F172A)
                )
                if (productItem.isSuccess && product != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = product.price.ifBlank { "价格未知" },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4F46E5)
                    )
                } else if (!productItem.isSuccess) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = productItem.errorMessage ?: "未知错误",
                        fontSize = 11.sp,
                        color = Color(0xFFB91C1C),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (productItem.isSuccess) {
                Button(
                    onClick = { onPurchase(productItem) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("购买", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
fun SectionTitle(title: String) {
    Text(
        text = title,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF0F172A),
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
fun QueryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4F46E5)),
        shape = MaterialTheme.shapes.medium
    ) {
        Text(text = text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun FeatureButton(
    text: String,
    onClick: () -> Unit,
    backgroundColor: Color
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = backgroundColor),
        shape = MaterialTheme.shapes.medium
    ) {
        Text(text = text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
