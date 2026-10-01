package com.spendcore.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID


data class Business(val id: String, val name: String)
data class Customer(val id: String, val businessId: String, val name: String)
data class SpendOrder(
    val id: String,
    val businessId: String,
    val customerId: String,
    val orderRef: String,
    val paidCents: Long,
    val purchaseLimitCents: Long,
    val usedCents: Long = 0,
    val reservedCents: Long = 0,
    val status: String = "ACTIVE"
) {
    val feeCents: Long get() = (paidCents - purchaseLimitCents).coerceAtLeast(0)
    val remainingCents: Long get() = (purchaseLimitCents - usedCents - reservedCents).coerceAtLeast(0)
}

data class Purchase(
    val id: String,
    val orderId: String,
    val businessId: String,
    val customerId: String,
    val merchant: String,
    val description: String,
    val amountCents: Long,
    val status: String,
    val cardLast4: String,
    val createdAt: Long = System.currentTimeMillis(),
    val provider: String = "MOCK",
    val providerRef: String = "",
    val cardStatus: String = "READY",
    val expiration: String = ""
)

class AppState(private val context: Context) {
    private val prefs = context.getSharedPreferences("spendcore_v2", Context.MODE_PRIVATE)
    var selectedTab by mutableIntStateOf(0)
    var selectedBusinessId by mutableStateOf<String?>(null)
    var apiBaseUrl by mutableStateOf(prefs.getString("api_base_url", "") ?: "")
    val businesses = mutableStateListOf<Business>()
    val customers = mutableStateListOf<Customer>()
    val orders = mutableStateListOf<SpendOrder>()
    val purchases = mutableStateListOf<Purchase>()
    val allowedMerchants = mutableStateListOf("Amazon", "Walmart", "Shein", "Temu")

    init {
        load()
        if (selectedBusinessId == null) selectedBusinessId = businesses.firstOrNull()?.id
    }

    fun setApiBaseUrl(value: String) {
        apiBaseUrl = value.trim().trimEnd('/')
        prefs.edit().putString("api_base_url", apiBaseUrl).apply()
    }

    fun remoteBusinessId(localBusinessId: String): String? = prefs.getString("remote_business_$localBusinessId", null)
    fun remoteBudgetId(localOrderId: String): String? = prefs.getString("remote_budget_$localOrderId", null)
    fun setRemoteBusinessId(localBusinessId: String, remoteId: String) {
        prefs.edit().putString("remote_business_$localBusinessId", remoteId).apply()
    }
    fun setRemoteBudgetId(localOrderId: String, remoteId: String) {
        prefs.edit().putString("remote_budget_$localOrderId", remoteId).apply()
    }

    fun addBusiness(name: String): Business? {
        val clean = name.trim(); if (clean.isBlank()) return null
        val item = Business(id("BUS"), clean)
        businesses.add(item)
        if (selectedBusinessId == null) selectedBusinessId = item.id
        save(); return item
    }

    fun updateBusiness(id: String, name: String): Boolean {
        val i = businesses.indexOfFirst { it.id == id }; val clean = name.trim()
        if (i < 0 || clean.isBlank()) return false
        businesses[i] = businesses[i].copy(name = clean); save(); return true
    }

    fun deleteBusiness(id: String) {
        purchases.removeAll { it.businessId == id }
        orders.removeAll { it.businessId == id }
        customers.removeAll { it.businessId == id }
        businesses.removeAll { it.id == id }
        prefs.edit().remove("remote_business_$id").apply()
        if (selectedBusinessId == id) selectedBusinessId = businesses.firstOrNull()?.id
        save()
    }

    fun addCustomer(businessId: String, name: String): Customer? {
        if (businesses.none { it.id == businessId }) return null
        val clean = name.trim(); if (clean.isBlank()) return null
        val item = Customer(id("CUS"), businessId, clean)
        customers.add(item); save(); return item
    }

    fun updateCustomer(id: String, name: String): Boolean {
        val i = customers.indexOfFirst { it.id == id }; val clean = name.trim()
        if (i < 0 || clean.isBlank()) return false
        customers[i] = customers[i].copy(name = clean); save(); return true
    }

    fun deleteCustomer(id: String) {
        val orderIds = orders.filter { it.customerId == id }.map { it.id }
        orderIds.forEach { prefs.edit().remove("remote_budget_$it").apply() }
        purchases.removeAll { it.customerId == id || it.orderId in orderIds }
        orders.removeAll { it.customerId == id }
        customers.removeAll { it.id == id }
        save()
    }

    fun addOrder(customerId: String, paidCents: Long, limitCents: Long): SpendOrder? {
        val customer = customers.firstOrNull { it.id == customerId } ?: return null
        if (paidCents <= 0 || limitCents <= 0 || paidCents < limitCents) return null
        val order = SpendOrder(id("ORD"), customer.businessId, customer.id, nextOrderRef(), paidCents, limitCents)
        orders.add(0, order); save(); return order
    }

    fun updateOrder(id: String, paidCents: Long, limitCents: Long): String {
        val i = orders.indexOfFirst { it.id == id }; if (i < 0) return "Orden no encontrada"
        val order = orders[i]
        if (paidCents <= 0 || limitCents <= 0) return "Los montos deben ser mayores que cero"
        if (paidCents < limitCents) return "El pago no puede ser menor que el máximo autorizado"
        if (limitCents < order.usedCents + order.reservedCents) return "El máximo no puede quedar por debajo de lo usado/reservado"
        orders[i] = order.copy(paidCents = paidCents, purchaseLimitCents = limitCents); save(); return "Orden actualizada"
    }

    fun setOrderActive(id: String, active: Boolean) {
        val i = orders.indexOfFirst { it.id == id }; if (i < 0) return
        orders[i] = orders[i].copy(status = if (active) "ACTIVE" else "PAUSED"); save()
    }

    fun deleteOrder(id: String) {
        purchases.removeAll { it.orderId == id }; orders.removeAll { it.id == id }
        prefs.edit().remove("remote_budget_$id").apply(); save()
    }

    fun authorizePurchase(orderId: String, merchant: String, description: String, amountCents: Long): String {
        val oi = orders.indexOfFirst { it.id == orderId }; if (oi < 0) return "Orden no encontrada"
        val order = orders[oi]
        if (order.status != "ACTIVE") return "La orden no está activa"
        if (amountCents <= 0) return "Monto inválido"
        if (amountCents > order.remainingCents) return "Compra rechazada: excede el presupuesto restante"
        val clean = merchant.trim(); if (clean.isBlank()) return "Escribe el comercio"
        if (!merchantPermitted(clean)) return "Compra rechazada: comercio no permitido"
        orders[oi] = order.copy(reservedCents = order.reservedCents + amountCents)
        val purchase = Purchase(id("PUR"), order.id, order.businessId, order.customerId, clean, description.trim(), amountCents, "AUTHORIZED", "0000")
        purchases.add(0, purchase); save(); return "Autorizada"
    }

    fun attachRemoteCard(purchaseId: String, provider: String, providerRef: String, last4: String, cardStatus: String, expiration: String?) {
        val i = purchases.indexOfFirst { it.id == purchaseId }; if (i < 0) return
        purchases[i] = purchases[i].copy(
            provider = provider,
            providerRef = providerRef,
            cardLast4 = last4,
            cardStatus = cardStatus,
            expiration = expiration.orEmpty()
        )
        save()
    }

    fun updatePurchase(id: String, merchant: String, description: String, amountCents: Long): String {
        val pi = purchases.indexOfFirst { it.id == id }; if (pi < 0) return "Compra no encontrada"
        val p = purchases[pi]
        if (p.providerRef.isNotBlank()) return "La compra ya está vinculada a una tarjeta del servidor"
        if (p.status != "AUTHORIZED") {
            purchases[pi] = p.copy(description = description.trim()); save(); return "Descripción actualizada"
        }
        val clean = merchant.trim(); if (clean.isBlank() || amountCents <= 0) return "Revisa comercio y monto"
        if (!merchantPermitted(clean)) return "Comercio no permitido"
        val oi = orders.indexOfFirst { it.id == p.orderId }; if (oi < 0) return "Orden no encontrada"
        val order = orders[oi]
        if (amountCents > order.remainingCents + p.amountCents) return "El nuevo monto excede el presupuesto"
        orders[oi] = order.copy(reservedCents = (order.reservedCents - p.amountCents + amountCents).coerceAtLeast(0))
        purchases[pi] = p.copy(merchant = clean, description = description.trim(), amountCents = amountCents); save(); return "Compra actualizada"
    }

    fun capturePurchase(id: String) {
        val pi = purchases.indexOfFirst { it.id == id }; if (pi < 0) return
        val p = purchases[pi]; if (p.status != "AUTHORIZED") return
        val oi = orders.indexOfFirst { it.id == p.orderId }; if (oi < 0) return
        val o = orders[oi]
        orders[oi] = o.copy(reservedCents = (o.reservedCents - p.amountCents).coerceAtLeast(0), usedCents = o.usedCents + p.amountCents)
        purchases[pi] = p.copy(status = "CAPTURED", cardStatus = if (p.providerRef.isBlank()) "CLOSED" else p.cardStatus); save()
    }

    fun cancelPurchase(id: String) {
        val pi = purchases.indexOfFirst { it.id == id }; if (pi < 0) return
        val p = purchases[pi]; if (p.status != "AUTHORIZED") return
        val oi = orders.indexOfFirst { it.id == p.orderId }
        if (oi >= 0) orders[oi] = orders[oi].copy(reservedCents = (orders[oi].reservedCents - p.amountCents).coerceAtLeast(0))
        purchases[pi] = p.copy(status = "CANCELLED", cardStatus = "CANCELLED"); save()
    }

    fun refundPurchase(id: String) {
        val pi = purchases.indexOfFirst { it.id == id }; if (pi < 0) return
        val p = purchases[pi]; if (p.status != "CAPTURED") return
        val oi = orders.indexOfFirst { it.id == p.orderId }
        if (oi >= 0) orders[oi] = orders[oi].copy(usedCents = (orders[oi].usedCents - p.amountCents).coerceAtLeast(0))
        purchases[pi] = p.copy(status = "REFUNDED"); save()
    }

    fun deletePurchase(id: String) {
        val pi = purchases.indexOfFirst { it.id == id }; if (pi < 0) return
        val p = purchases[pi]
        if (p.providerRef.isNotBlank()) return
        val oi = orders.indexOfFirst { it.id == p.orderId }
        if (oi >= 0) {
            val o = orders[oi]
            orders[oi] = when (p.status) {
                "AUTHORIZED" -> o.copy(reservedCents = (o.reservedCents - p.amountCents).coerceAtLeast(0))
                "CAPTURED" -> o.copy(usedCents = (o.usedCents - p.amountCents).coerceAtLeast(0))
                else -> o
            }
        }
        purchases.removeAt(pi); save()
    }

    fun addAllowedMerchant(name: String) { val c = name.trim(); if (c.isNotBlank() && allowedMerchants.none { it.equals(c, true) }) { allowedMerchants.add(c); save() } }
    fun updateAllowedMerchant(old: String, new: String): Boolean {
        val c = new.trim(); val i = allowedMerchants.indexOf(old)
        if (i < 0 || c.isBlank() || allowedMerchants.any { !it.equals(old, true) && it.equals(c, true) }) return false
        allowedMerchants[i] = c; save(); return true
    }
    fun removeAllowedMerchant(name: String) { allowedMerchants.remove(name); save() }

    fun businessName(id: String) = businesses.firstOrNull { it.id == id }?.name ?: "Negocio"
    fun customerName(id: String) = customers.firstOrNull { it.id == id }?.name ?: "Cliente"
    fun orderById(id: String) = orders.firstOrNull { it.id == id }
    fun businessCascadeCount(id: String) = Triple(customers.count { it.businessId == id }, orders.count { it.businessId == id }, purchases.count { it.businessId == id })
    fun customerCascadeCount(id: String) = Pair(orders.count { it.customerId == id }, purchases.count { it.customerId == id })

    private fun merchantPermitted(name: String) = allowedMerchants.any { name.contains(it, true) || it.contains(name, true) }
    private fun nextOrderRef(): String { var n = orders.size + 1; while (orders.any { it.orderRef == "SC-${n.toString().padStart(4, '0')}" }) n++; return "SC-${n.toString().padStart(4, '0')}" }
    private fun id(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8).uppercase()}"

    private fun save() {
        val root = JSONObject()
        root.put("businesses", JSONArray().apply { businesses.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
        root.put("customers", JSONArray().apply { customers.forEach { put(JSONObject().put("id", it.id).put("businessId", it.businessId).put("name", it.name)) } })
        root.put("orders", JSONArray().apply { orders.forEach { put(JSONObject().put("id", it.id).put("businessId", it.businessId).put("customerId", it.customerId).put("orderRef", it.orderRef).put("paidCents", it.paidCents).put("purchaseLimitCents", it.purchaseLimitCents).put("usedCents", it.usedCents).put("reservedCents", it.reservedCents).put("status", it.status)) } })
        root.put("purchases", JSONArray().apply { purchases.forEach { put(JSONObject().put("id", it.id).put("orderId", it.orderId).put("businessId", it.businessId).put("customerId", it.customerId).put("merchant", it.merchant).put("description", it.description).put("amountCents", it.amountCents).put("status", it.status).put("cardLast4", it.cardLast4).put("createdAt", it.createdAt).put("provider", it.provider).put("providerRef", it.providerRef).put("cardStatus", it.cardStatus).put("expiration", it.expiration)) } })
        root.put("allowedMerchants", JSONArray().apply { allowedMerchants.forEach { put(it) } })
        prefs.edit().putString("state", root.toString()).apply()
    }

    private fun load() {
        val raw = prefs.getString("state", null) ?: return
        runCatching {
            val root = JSONObject(raw)
            businesses.clear(); customers.clear(); orders.clear(); purchases.clear(); allowedMerchants.clear()
            root.optJSONArray("businesses")?.forEachObject { businesses.add(Business(it.getString("id"), it.getString("name"))) }
            root.optJSONArray("customers")?.forEachObject { customers.add(Customer(it.getString("id"), it.getString("businessId"), it.getString("name"))) }
            root.optJSONArray("orders")?.forEachObject { orders.add(SpendOrder(it.getString("id"), it.getString("businessId"), it.getString("customerId"), it.getString("orderRef"), it.getLong("paidCents"), it.getLong("purchaseLimitCents"), it.optLong("usedCents"), it.optLong("reservedCents"), it.optString("status", "ACTIVE"))) }
            root.optJSONArray("purchases")?.forEachObject { purchases.add(Purchase(it.getString("id"), it.getString("orderId"), it.getString("businessId"), it.getString("customerId"), it.getString("merchant"), it.optString("description"), it.getLong("amountCents"), it.getString("status"), it.optString("cardLast4", "0000"), it.optLong("createdAt", System.currentTimeMillis()), it.optString("provider", "MOCK"), it.optString("providerRef", ""), it.optString("cardStatus", "READY"), it.optString("expiration", ""))) }
            root.optJSONArray("allowedMerchants")?.let { a -> for (i in 0 until a.length()) allowedMerchants.add(a.getString(i)) }
            if (allowedMerchants.isEmpty()) allowedMerchants.addAll(listOf("Amazon", "Walmart", "Shein", "Temu"))
        }
    }
}

private inline fun JSONArray.forEachObject(block: (JSONObject) -> Unit) { for (i in 0 until length()) block(getJSONObject(i)) }
