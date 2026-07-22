package com.kit.pay.billing;

import com.android.billingclient.api.BillingFlowParams;

/**
 * Java bridge: Kotlin cannot read {@code @IntDef} annotation int constants as expressions.
 */
final class BillingReplacementModes {
    private BillingReplacementModes() {}

    static final int WITH_TIME_PRORATION =
            BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams
                    .ReplacementMode.WITH_TIME_PRORATION;
    static final int CHARGE_PRORATED_PRICE =
            BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams
                    .ReplacementMode.CHARGE_PRORATED_PRICE;
    static final int WITHOUT_PRORATION =
            BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams
                    .ReplacementMode.WITHOUT_PRORATION;
    static final int CHARGE_FULL_PRICE =
            BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams
                    .ReplacementMode.CHARGE_FULL_PRICE;
    static final int DEFERRED =
            BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams
                    .ReplacementMode.DEFERRED;
}
