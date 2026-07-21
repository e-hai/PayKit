package com.kit.pay.sample

/**
 * Demo 商品与订阅模型：Free + Plus + Pro + 多计费周期 + 试用。
 *
 * 采用 Google Play 推荐的订阅层级：**一个 product = 一个权益档**。
 *
 * ```
 * Product   [SUBS_PLUS]           → Plus 权益
 *  ├─ Base Plan [PLUS_MONTHLY]    月订阅（计费周期）
 *  │    └─ Offer  免费试用 / 优惠
 *  ├─ Base Plan [PLUS_QUARTERLY]  季度订阅
 *  └─ Base Plan [PLUS_YEARLY]     年订阅
 * Product   [SUBS_PRO]            → Pro 权益（Plus 的升级档）
 *  ├─ Base Plan [PRO_MONTHLY]     月订阅
 *  └─ Base Plan [PRO_YEARLY]      年订阅
 *       └─ Offer  ...
 * ```
 *
 * - Free：无 Play 商品；未订阅 Plus / Pro 时的默认权益
 * - Plus：[SUBS_PLUS] 在订（月/季/年 base plan 任一）
 * - Pro：[SUBS_PRO] 在订（月或年 base plan 任一），优先级高于 Plus
 * - 试用：Console 在 base plan 下配置的免费试用 offer（StoreProduct.hasFreeTrial）
 *
 * product ID 与 base plan ID 均需与 Google Play Console 配置一致。
 */
object Constants {
    /** 一个 product 对应一个付费档；计费周期为 product 下的 base plan */
    const val SUBS_PLUS = "subs_plus"
    const val SUBS_PRO = "subs_pro"

    /** Plus / Pro 商品下的 base plan ID（需与 Play Console 一致） */
    const val PLUS_MONTHLY = "plus-monthly"
    const val PLUS_QUARTERLY = "plus-quarterly"
    const val PLUS_YEARLY = "plus-yearly"
    const val PRO_MONTHLY = "pro-monthly"
    const val PRO_YEARLY = "pro-yearly"

    const val OTP_GAME_SKIN_3DAY = "consumable_product_01"
    const val OTP_GAME_SKIN_PERMANENT = "one_time_product_01"

    /** 所有付费档对应的订阅商品 */
    val ALL_SUBS_IDS = setOf(SUBS_PLUS, SUBS_PRO)
    val ALL_CONSUMABLE_IDS = setOf(OTP_GAME_SKIN_3DAY)
    val ALL_NON_CONSUMABLE_IDS = setOf(OTP_GAME_SKIN_PERMANENT)
}
