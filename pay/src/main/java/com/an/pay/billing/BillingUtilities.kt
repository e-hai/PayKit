package com.an.pay.billing

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase

const val PLAY_STORE_SUBSCRIPTION_URL = "https://play.google.com/store/account/subscriptions"
const val PLAY_STORE_SUBSCRIPTION_DEEPLINK_URL =
    "https://play.google.com/store/account/subscriptions?product=%s&package=%s"
const val PLAY_STORE_APP_DETAILS_URL = "https://play.google.com/store/apps/details?id=%s"


/**
 * 根据提供的商品ID，若存在于已购商品列表中，则返回该商品的购买信息
 */
internal fun purchaseForProduct(purchases: List<Purchase>?, product: String): Purchase? {
    purchases?.let {
        for (purchase in it) {
            if (purchase.products[0] == product) {
                return purchase
            }
        }
    }
    return null
}


fun deviceHasGooglePlaySubscription(purchases: List<Purchase>?, product: String) =
    purchaseForProduct(purchases, product) != null


fun filterPricingPhase(productDetails: ProductDetails): List<ProductDetails.PricingPhase>? {
    val pricingPhaseList =
        productDetails.subscriptionOfferDetails?.get(0)?.pricingPhases?.pricingPhaseList
    if (pricingPhaseList?.isNotEmpty() == true) {
        return pricingPhaseList.filter {
            it.priceAmountMicros > 0L
        }
    }
    return pricingPhaseList
}

fun gotoMarket(context: Context, marketUri: String) {
    val intent = Intent(Intent.ACTION_VIEW)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    intent.data = Uri.parse(marketUri) //跳转到应用市场，非Google Play市场一般情况也实现了这个接口
    //存在手机里没安装应用市场的情况，跳转会包异常，做一个接收判断
    if (intent.resolveActivity(context.packageManager) != null) { //可以接收
        context.startActivity(intent)
    }
}

/**
 * 跳转到google play，并打开应用详情页
 * **/
fun gotoPlayStoreAppDetails(context: Context) {
    gotoMarket(context, PLAY_STORE_APP_DETAILS_URL)
}

/**
 * 跳转到google play，并打开订阅管理页
 * **/
fun gotoPlayStoreSubscription(context: Context) {
    gotoMarket(context, PLAY_STORE_SUBSCRIPTION_URL)
}

/**
 * 跳转到google play，并打开对应订阅商品页
 * **/
fun gotoPlayStoreSubscription(context: Context, product: String) {
    val marketUri =
        String.format(PLAY_STORE_SUBSCRIPTION_DEEPLINK_URL, product, context.packageName)
    gotoMarket(context, marketUri)
}