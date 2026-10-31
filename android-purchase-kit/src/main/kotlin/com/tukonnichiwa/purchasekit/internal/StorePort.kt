package com.tukonnichiwa.purchasekit.internal

import com.tukonnichiwa.purchasekit.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** The core owns this contract; SDK objects and Activities never enter it. */
internal interface StorePort {
    val connection: StateFlow<ConnectionState>
    val updates: Flow<PurchaseKitResult<List<PurchaseRecord>>>
    suspend fun connect(): PurchaseKitResult<Unit>
    suspend fun queryCatalog(): PurchaseKitResult<Catalog>
    suspend fun queryPurchases(): PurchaseKitResult<List<PurchaseRecord>>
    suspend fun acknowledge(token: String): PurchaseKitResult<Unit>
    suspend fun consume(token: String): PurchaseKitResult<Unit>
    fun close()
}
