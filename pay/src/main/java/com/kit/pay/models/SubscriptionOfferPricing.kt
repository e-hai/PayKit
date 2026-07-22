package com.kit.pay.models

/**
 * 从订阅 offer 的定价阶段列表推导展示价与试用信息。
 *
 * - [price] / [priceAmountMicros]：优先取首个 **非 0** 阶段（试用后正价）；若全为 0 则用末阶段
 * - [hasFreeTrial]：首阶段价格为 0
 * - [freeTrialPeriod]：试用阶段的 billingPeriod（如 `P1W`）
 */
object SubscriptionOfferPricing {

    data class Phase(
        val priceAmountMicros: Long,
        val formattedPrice: String,
        val priceCurrencyCode: String,
        val billingPeriod: String
    )

    data class Result(
        val price: String,
        val priceAmountMicros: Long,
        val priceCurrencyCode: String,
        val hasFreeTrial: Boolean,
        val freeTrialPeriod: String?
    )

    fun fromPhases(phases: List<Phase>): Result {
        if (phases.isEmpty()) {
            return Result(
                price = "",
                priceAmountMicros = 0L,
                priceCurrencyCode = "",
                hasFreeTrial = false,
                freeTrialPeriod = null
            )
        }
        val first = phases.first()
        val hasFreeTrial = first.priceAmountMicros == 0L
        val recurring = phases.firstOrNull { it.priceAmountMicros > 0L } ?: phases.last()
        return Result(
            price = recurring.formattedPrice,
            priceAmountMicros = recurring.priceAmountMicros,
            priceCurrencyCode = recurring.priceCurrencyCode,
            hasFreeTrial = hasFreeTrial,
            freeTrialPeriod = if (hasFreeTrial) first.billingPeriod.ifBlank { null } else null
        )
    }
}
