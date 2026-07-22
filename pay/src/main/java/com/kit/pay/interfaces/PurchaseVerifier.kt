package com.kit.pay.interfaces

import com.kit.pay.models.StoreTransaction

/**
 * 购买验证扩展点，便于后续接入服务端验单。
 *
 * 默认 [LocalPurchaseVerifier]：不做网络校验，保持纯本地行为。
 * 宿主可实现本接口，在发货 / consume 前把 [StoreTransaction.purchaseToken]
 *（以及可选的 [StoreTransaction.signature] / [StoreTransaction.originalJson]）交给服务端，
 * 由服务端调用 Google Play Developer API 验单后再返回成功。
 *
 * 调用时机（失败则不会进入 [PurchaseCallback.onCompleted]，也不会 consume）：
 * - 购买成功回调路径：sync 之后、`onCompleted` 之前
 * - [com.kit.pay.PayKit.markConsumableFulfilled]：consume 之前
 */
fun interface PurchaseVerifier {
    /**
     * @return [Result.success] 表示可继续发货 / consume；
     * [Result.failure] 建议携带 [PayKitError]，否则会包装为 [ErrorCode.VERIFICATION_FAILED]
     */
    suspend fun verify(transaction: StoreTransaction): Result<Unit>
}

/**
 * 默认实现：直接通过，等价于当前「无服务端验单」行为。
 */
object LocalPurchaseVerifier : PurchaseVerifier {
    override suspend fun verify(transaction: StoreTransaction): Result<Unit> =
        Result.success(Unit)
}
