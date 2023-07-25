package com.an.pay

object Constants {

    //Product IDs
    const val BASIC_PRODUCT = "basic_subscription"
    const val PREMIUM_PRODUCT = "premium_subscription"
    const val ONE_TIME_PRODUCT = "otp"

    //Tags
    const val BASIC_MONTHLY_PLAN = "basicmonthly"
    const val BASIC_YEARLY_PLAN = "basicyearly"
    const val PREMIUM_MONTHLY_PLAN = "premiummonthly"
    const val PREMIUM_YEARLY_PLAN = "premiumyearly"
    const val BASIC_PREPAID_PLAN_TAG = "prepaidbasic"
    const val PREMIUM_PREPAID_PLAN_TAG = "prepaidpremium"


    const val PLAY_STORE_SUBSCRIPTION_URL = "https://play.google.com/store/account/subscriptions"
    const val PLAY_STORE_SUBSCRIPTION_DEEPLINK_URL =
        "https://play.google.com/store/account/subscriptions?product=%s&package=%s"
    const val PLAY_STORE_APP_DETAILS_URL = "https://play.google.com/store/apps/details?id=%s"
}