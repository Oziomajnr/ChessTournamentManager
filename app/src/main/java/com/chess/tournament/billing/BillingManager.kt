package com.chess.tournament.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetailsResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class BillingManager(context: Context) {

    companion object {
        const val PRODUCT_LIFETIME = "pro_lifetime"
        const val PRODUCT_MONTHLY = "pro_monthly"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _hasLifetime = MutableStateFlow(false)
    val hasLifetime: StateFlow<Boolean> = _hasLifetime

    private val _hasSubscription = MutableStateFlow(false)
    val hasSubscription: StateFlow<Boolean> = _hasSubscription

    private val _connectionReady = MutableStateFlow(false)

    private var lifetimeProductDetails: com.android.billingclient.api.ProductDetails? = null
    private var monthlyProductDetails: com.android.billingclient.api.ProductDetails? = null

    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            scope.launch { handlePurchases(purchases) }
        }
    }

    private val billingClient = BillingClient.newBuilder(context)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .enablePrepaidPlans()
                .build()
        )
        .build()

    fun connect() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    _connectionReady.value = true
                    scope.launch {
                        queryExistingPurchases()
                        queryProductDetails()
                    }
                }
            }

            override fun onBillingServiceDisconnected() {
                _connectionReady.value = false
            }
        })
    }

    private suspend fun queryExistingPurchases() {
        val inAppResult = billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )
        val subResult = billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )

        handlePurchases(inAppResult.purchasesList)
        handlePurchases(subResult.purchasesList)
    }

    private suspend fun queryProductDetails() {
        val lifetimeParams = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_LIFETIME)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            ).build()

        val monthlyParams = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(PRODUCT_MONTHLY)
                        .setProductType(BillingClient.ProductType.SUBS)
                        .build()
                )
            ).build()

        val lifetimeResult: ProductDetailsResult = billingClient.queryProductDetails(lifetimeParams)
        lifetimeProductDetails = lifetimeResult.productDetailsList?.firstOrNull()

        val monthlyResult: ProductDetailsResult = billingClient.queryProductDetails(monthlyParams)
        monthlyProductDetails = monthlyResult.productDetailsList?.firstOrNull()
    }

    private suspend fun handlePurchases(purchases: List<Purchase>) {
        for (purchase in purchases) {
            if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                if (!purchase.isAcknowledged) {
                    val ackParams = com.android.billingclient.api.AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.purchaseToken)
                        .build()
                    billingClient.acknowledgePurchase(ackParams)
                }

                if (purchase.products.contains(PRODUCT_LIFETIME)) {
                    _hasLifetime.value = true
                }
                if (purchase.products.contains(PRODUCT_MONTHLY)) {
                    _hasSubscription.value = true
                }
            }
        }
    }

    fun launchLifetimePurchase(activity: Activity): Boolean {
        val details = lifetimeProductDetails ?: return false
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build()
                )
            ).build()
        billingClient.launchBillingFlow(activity, flowParams)
        return true
    }

    fun launchSubscriptionPurchase(activity: Activity): Boolean {
        val details = monthlyProductDetails ?: return false
        val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: return false
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .setOfferToken(offerToken)
                        .build()
                )
            ).build()
        billingClient.launchBillingFlow(activity, flowParams)
        return true
    }

    fun getLifetimePrice(): String? =
        lifetimeProductDetails?.oneTimePurchaseOfferDetails?.formattedPrice

    fun getMonthlyPrice(): String? =
        monthlyProductDetails?.subscriptionOfferDetails?.firstOrNull()
            ?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice

    fun restorePurchases() {
        scope.launch { queryExistingPurchases() }
    }

    fun destroy() {
        if (billingClient.isReady) billingClient.endConnection()
    }
}
