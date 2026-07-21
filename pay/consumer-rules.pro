# PayKit consumer ProGuard / R8 rules
# Applied automatically when a host app depends on this AAR with minify enabled.

# Public API & models (reflection / serialization-safe surface)
-keep class com.kit.pay.PayKit { *; }
-keep class com.kit.pay.models.** { *; }
-keep class com.kit.pay.interfaces.** { *; }

# Callbacks implemented by host apps
-keepclassmembers class * implements com.kit.pay.interfaces.PurchaseCallback { *; }
-keepclassmembers class * implements com.kit.pay.interfaces.UpdatedCustomerInfoListener { *; }

# Google Play Billing (keep Parcelable / callback types used across process)
-keep class com.android.billingclient.api.** { *; }
-dontwarn com.android.billingclient.api.**
