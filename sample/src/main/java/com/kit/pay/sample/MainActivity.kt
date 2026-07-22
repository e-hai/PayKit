package com.kit.pay.sample

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
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
                    primary = DemoPalette.Teal,
                    secondary = DemoPalette.Ink,
                    background = DemoPalette.Canvas,
                    surface = DemoPalette.Surface,
                    onPrimary = Color.White,
                    onSecondary = Color.White,
                    onBackground = DemoPalette.Ink,
                    onSurface = DemoPalette.Ink
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DemoPalette.Canvas
                ) {
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
                            onManageSubscriptions = { openManageSubscriptions() },
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

    /** 跳转 Google Play 订阅管理页（取消 / 换档）。 */
    private fun openManageSubscriptions() {
        val uri = Uri.parse(
            "https://play.google.com/store/account/subscriptions?package=$packageName"
        )
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        }.onFailure { e ->
            Log.e(TAG, "openManageSubscriptions fail", e)
            Toast.makeText(this, "无法打开 Google Play 订阅管理", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "PayKit-Sample"
    }
}

private object DemoPalette {
    val Canvas = Color(0xFFEEF2EF)
    val Surface = Color(0xFFFBFCFB)
    val Ink = Color(0xFF14201B)
    val Muted = Color(0xFF5B6B63)
    val Line = Color(0xFFD5DDD8)
    val Teal = Color(0xFF0F766E)
    val TealDeep = Color(0xFF115E59)
    val Amber = Color(0xFFB45309)
    val Emerald = Color(0xFF047857)
    val Danger = Color(0xFFB42318)
    val SoftMint = Color(0xFFE7F6F1)
    val SoftSand = Color(0xFFF3EDE4)
}

@Composable
fun SplashScreenContent() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF0F3D38), DemoPalette.TealDeep, Color(0xFF1A3A32))
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "PAYKIT",
                fontSize = 42.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.SansSerif,
                color = Color.White,
                letterSpacing = 4.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Google Play Billing Sample",
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.72f)
            )
            Spacer(modifier = Modifier.height(36.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = Color(0xFFA7F3D0),
                strokeWidth = 2.5.dp
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "正在同步购买记录",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.6f)
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
    onManageSubscriptions: () -> Unit,
    onQuerySubs: () -> Unit,
    onQueryConsumable: () -> Unit,
    onQueryNonConsumable: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFE8EFEA), DemoPalette.Canvas, Color(0xFFE6EBE7))
                )
            )
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item { TopBar() }
            item { MembershipHero(entitlement = entitlement) }
            item {
                SectionPanel(title = "订阅", subtitle = "Free · Plus · Pro") {
                    QueryAction(
                        label = if (querying) "查询中…" else "刷新订阅商品",
                        enabled = !querying,
                        onClick = onQuerySubs
                    )
                    if (subsProducts.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(14.dp))
                        SubscriptionOffersSection(
                            products = subsProducts,
                            onPurchase = onPurchase
                        )
                    } else {
                        Spacer(modifier = Modifier.height(10.dp))
                        EmptyHint("查询后按档位与计费周期展示可用方案")
                    }
                }
            }
            item {
                SectionPanel(title = "一次性商品", subtitle = "消耗 / 非消耗") {
                    QueryAction(
                        label = if (querying) "查询中…" else "查询消耗商品",
                        enabled = !querying,
                        onClick = onQueryConsumable
                    )
                    if (consumableProducts.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        ProductHorizontalList(
                            title = "消耗",
                            products = consumableProducts,
                            onPurchase = onPurchase
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    QueryAction(
                        label = if (querying) "查询中…" else "查询非消耗商品",
                        enabled = !querying,
                        onClick = onQueryNonConsumable,
                        outlined = true
                    )
                    if (nonConsumableProducts.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        ProductHorizontalList(
                            title = "非消耗",
                            products = nonConsumableProducts,
                            onPurchase = onPurchase
                        )
                    }
                }
            }
            item {
                SectionPanel(title = "账户", subtitle = "恢复与同步") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onRestore,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            border = BorderStroke(1.dp, DemoPalette.Line),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = DemoPalette.Ink)
                        ) {
                            Text("恢复购买", fontWeight = FontWeight.SemiBold)
                        }
                        Button(
                            onClick = onCheckEntitlements,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = DemoPalette.Ink)
                        ) {
                            Text("强制同步", fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = onManageSubscriptions,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        border = BorderStroke(1.dp, DemoPalette.Line),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = DemoPalette.Teal)
                    ) {
                        Text("管理订阅（Google Play）", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun TopBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = "PayKit",
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                color = DemoPalette.Ink,
                letterSpacing = (-0.5).sp
            )
            Text(
                text = "Billing demo",
                fontSize = 13.sp,
                color = DemoPalette.Muted
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(DemoPalette.SoftMint)
                .border(1.dp, Color(0xFFB7E4D6), RoundedCornerShape(999.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = "SAMPLE",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = DemoPalette.TealDeep,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
private fun MembershipHero(entitlement: MainViewModel.EntitlementUi) {
    val tier = entitlement.tier
    val heroBrush = when (tier) {
        MainViewModel.Tier.Free -> Brush.linearGradient(
            listOf(Color(0xFF1F2A27), Color(0xFF2F4039))
        )
        MainViewModel.Tier.Plus -> Brush.linearGradient(
            listOf(Color(0xFF0F5C56), Color(0xFF147A70))
        )
        MainViewModel.Tier.Pro -> Brush.linearGradient(
            listOf(Color(0xFF0B3D32), Color(0xFF146B4F))
        )
    }
    val accent = when (tier) {
        MainViewModel.Tier.Free -> Color(0xFFCBD5D1)
        MainViewModel.Tier.Plus -> Color(0xFF99F6E4)
        MainViewModel.Tier.Pro -> Color(0xFFFDE68A)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(heroBrush)
            .padding(22.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.12f))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "CURRENT PLAN",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.75f),
                        letterSpacing = 1.2.sp
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                if (entitlement.pendingCount > 0) {
                    Text(
                        text = "待确认 ${entitlement.pendingCount}",
                        fontSize = 12.sp,
                        color = Color(0xFFFECACA)
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            AnimatedContent(
                targetState = tier,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "tier"
            ) { current ->
                Text(
                    text = current.name,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Black,
                    color = accent,
                    letterSpacing = (-1).sp
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = when (tier) {
                    MainViewModel.Tier.Free -> "免费档 · 升级 Plus 或 Pro 解锁更多权益"
                    MainViewModel.Tier.Plus -> "已开通 Plus · 可升级至 Pro"
                    MainViewModel.Tier.Pro -> "已开通 Pro · 全部权益已解锁"
                },
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.82f)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroStat(label = "订阅", value = entitlement.activeSubs.size.toString())
                HeroStat(label = "非消耗", value = entitlement.nonConsumables.size.toString())
            }
            if (entitlement.activeSubs.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = entitlement.activeSubs.joinToString(" · "),
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun HeroStat(label: String, value: String) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(text = value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text(text = label, fontSize = 11.sp, color = Color.White.copy(alpha = 0.6f))
    }
}

@Composable
private fun SectionPanel(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(DemoPalette.Surface)
            .border(1.dp, DemoPalette.Line.copy(alpha = 0.8f), RoundedCornerShape(24.dp))
            .padding(18.dp)
    ) {
        Text(
            text = title,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = DemoPalette.Ink
        )
        Text(
            text = subtitle,
            fontSize = 12.sp,
            color = DemoPalette.Muted,
            modifier = Modifier.padding(top = 2.dp, bottom = 14.dp)
        )
        content()
    }
}

@Composable
private fun QueryAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    outlined: Boolean = false
) {
    if (outlined) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            border = BorderStroke(1.dp, DemoPalette.Line),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = DemoPalette.Ink)
        ) {
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    } else {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = DemoPalette.Teal,
                disabledContainerColor = DemoPalette.Teal.copy(alpha = 0.45f)
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = DemoPalette.Muted,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DemoPalette.SoftSand)
            .padding(14.dp)
    )
}

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
            text = "付费档",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = DemoPalette.Muted
        )
        Spacer(modifier = Modifier.height(8.dp))
        TierSegmentedControl(
            selected = paidTier,
            onSelect = { paidTier = it }
        )

        if (availableBasePlanIds.isNotEmpty()) {
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "计费周期",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = DemoPalette.Muted
            )
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(availableBasePlanIds, key = { it }) { basePlanId ->
                    PeriodChip(
                        label = basePlanLabel(basePlanId),
                        selected = basePlanId == activeBasePlanId,
                        onClick = { selectedBasePlanId = basePlanId }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (primary == null) {
            EmptyHint(
                if (availableBasePlanIds.isEmpty()) {
                    "${paidTier.name} 暂无可用计费周期"
                } else {
                    "当前周期暂无可用方案"
                }
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
                        text = if (expanded) "收起其他方案" else "其他方案（${others.size}）",
                        color = DemoPalette.Teal,
                        fontWeight = FontWeight.SemiBold
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
                    color = DemoPalette.Danger
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
private fun TierSegmentedControl(
    selected: PaidTier,
    onSelect: (PaidTier) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFEAEEEC))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        PaidTier.entries.forEach { tier ->
            val isSelected = tier == selected
            val bg by animateColorAsState(
                if (isSelected) DemoPalette.Ink else Color.Transparent,
                label = "tierBg"
            )
            val fg by animateColorAsState(
                if (isSelected) Color.White else DemoPalette.Muted,
                label = "tierFg"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bg)
                    .clickable { onSelect(tier) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = tier.name,
                    fontWeight = FontWeight.Bold,
                    color = fg,
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
private fun PeriodChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bg by animateColorAsState(
        if (selected) DemoPalette.SoftMint else Color.White,
        label = "periodBg"
    )
    val border by animateColorAsState(
        if (selected) DemoPalette.Teal else DemoPalette.Line,
        label = "periodBorder"
    )
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) DemoPalette.TealDeep else DemoPalette.Ink,
            fontSize = 13.sp
        )
    }
}

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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFFF7FBFA), DemoPalette.SoftMint)
                )
            )
            .border(1.5.dp, DemoPalette.Teal.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (isTrial) Color(0xFFFEF3C7) else DemoPalette.SoftMint)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (isTrial) "FREE TRIAL" else "${tier.name.uppercase()} PLAN",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isTrial) DemoPalette.Amber else DemoPalette.TealDeep,
                    letterSpacing = 0.8.sp
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = product.title.ifBlank { item.productId },
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = DemoPalette.Ink
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = when {
                isTrial && product.price.isNotBlank() -> "试用后 ${product.price}"
                else -> product.price.ifBlank { "价格未知" }
            },
            fontSize = 28.sp,
            fontWeight = FontWeight.Black,
            color = DemoPalette.TealDeep
        )
        if (isTrial && !product.freeTrialPeriod.isNullOrBlank()) {
            Text(
                text = "试用周期 ${product.freeTrialPeriod}",
                fontSize = 12.sp,
                color = DemoPalette.Muted
            )
        }
        if (!product.basePlanId.isNullOrBlank() || !product.offerId.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = buildString {
                    product.basePlanId?.let { append(it) }
                    if (!product.basePlanId.isNullOrBlank() && !product.offerId.isNullOrBlank()) {
                        append(" · ")
                    }
                    product.offerId?.let { append(it) }
                },
                fontSize = 11.sp,
                color = DemoPalette.Muted
            )
        }
        if (product.description.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = product.description,
                fontSize = 13.sp,
                color = DemoPalette.Muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { onPurchase(item) },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = DemoPalette.Teal),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = if (isTrial) "开始 ${tier.name} 免费试用" else "订阅 ${tier.name}",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
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
            text = "$title · ${products.size}",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = DemoPalette.Muted
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
    val ok = productItem.isSuccess
    Column(
        modifier = Modifier
            .then(if (compact) Modifier.fillMaxWidth() else Modifier.width(210.dp))
            .height(if (compact) 128.dp else 168.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (ok) Color.White else Color(0xFFFFF5F5))
            .border(
                1.dp,
                if (ok) DemoPalette.Line else Color(0xFFFECACA),
                RoundedCornerShape(18.dp)
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = when {
                    !ok -> "失败"
                    compact -> "备选"
                    else -> "可购"
                },
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (ok) DemoPalette.TealDeep else DemoPalette.Danger
            )
            Text(
                text = productItem.productType.name,
                fontSize = 10.sp,
                color = DemoPalette.Muted
            )
        }
        Column {
            Text(
                text = product?.title?.ifBlank { productItem.productId } ?: productItem.productId,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = DemoPalette.Ink
            )
            if (ok && product != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = product.price.ifBlank { "价格未知" },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = DemoPalette.Teal
                )
            } else if (!ok) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = productItem.errorMessage ?: "未知错误",
                    fontSize = 11.sp,
                    color = DemoPalette.Danger,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (ok) {
            Button(
                onClick = { onPurchase(productItem) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp),
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DemoPalette.Ink),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("购买", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
