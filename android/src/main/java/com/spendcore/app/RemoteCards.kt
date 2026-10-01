package com.spendcore.app

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets


data class RemoteCard(
    val purchaseId: String,
    val customerRef: String,
    val orderRef: String,
    val merchant: String,
    val amountCents: Long,
    val provider: String,
    val providerRef: String,
    val last4: String,
    val cardStatus: String,
    val purchaseStatus: String,
    val expiration: String
)

data class ProviderInfo(
    val provider: String = "—",
    val configured: Boolean = false,
    val environment: String = "—",
    val message: String = "Servidor no conectado"
)

@Composable
fun CardsScreen(state: AppState) {
    var urlField by remember { mutableStateOf(state.apiBaseUrl) }
    var provider by remember { mutableStateOf(ProviderInfo()) }
    val cards = remember { mutableStateListOf<RemoteCard>() }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refresh(sync: Boolean = true) {
        val base = state.apiBaseUrl
        if (base.isBlank()) { message = "Configura primero la URL del servidor SpendCore"; return }
        busy = true; message = null
        Thread {
            runCatching {
                val p = parseProvider(ApiClient(base).request("GET", "/v1/provider"))
                val rawCards = if (sync) {
                    val response = JSONObject(ApiClient(base).request("POST", "/v1/cards/sync", JSONObject()))
                    response.optJSONArray("cards") ?: JSONArray(ApiClient(base).request("GET", "/v1/cards"))
                } else JSONArray(ApiClient(base).request("GET", "/v1/cards"))
                p to parseCards(rawCards)
            }.onSuccess { result ->
                Handler(Looper.getMainLooper()).post {
                    provider = result.first
                    cards.clear(); cards.addAll(result.second)
                    result.second.forEach { rc ->
                        val local = state.purchases.firstOrNull { it.id == rc.purchaseId }
                        if (local != null) state.attachRemoteCard(local.id, rc.provider, rc.providerRef, rc.last4, rc.cardStatus, rc.expiration)
                    }
                    message = "Sincronización completada: ${result.second.size} tarjeta(s)"
                    busy = false
                }
            }.onFailure { error ->
                Handler(Looper.getMainLooper()).post { message = "Error: ${error.message}"; busy = false }
            }
        }.start()
    }

    fun issue(purchase: Purchase) {
        if (state.apiBaseUrl.isBlank()) { message = "Configura primero la URL del servidor"; return }
        busy = true; message = "Creando tarjeta…"
        issueRemoteCard(state, purchase) { result ->
            result.onSuccess { card ->
                state.attachRemoteCard(purchase.id, card.provider, card.providerRef, card.last4, card.cardStatus, card.expiration)
                message = "Tarjeta ${card.provider} •••• ${card.last4} creada"
                busy = false
                refresh(sync = false)
            }.onFailure { error -> message = "No se pudo emitir: ${error.message}"; busy = false }
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Tarjetas", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Las credenciales del issuer permanecen en el servidor; Android solo recibe referencias seguras y estado.")
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = urlField,
                        onValueChange = { urlField = it },
                        label = { Text("URL de SpendCore API") },
                        placeholder = { Text("https://tu-servidor.example.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { state.saveApiBaseUrl(urlField); message = "Servidor guardado" }) { Text("Guardar") }
                        OutlinedButton(onClick = { state.saveApiBaseUrl(urlField); refresh() }, enabled = !busy) {
                            Icon(Icons.Default.Sync, null); Spacer(Modifier.width(5.dp)); Text("Sincronizar")
                        }
                    }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Provider: ${provider.provider} · ${provider.environment}", fontWeight = FontWeight.Bold)
                    Text(if (provider.configured) "🟢 Configurado" else "🟠 No configurado")
                    Text(provider.message, style = MaterialTheme.typography.bodySmall)
                    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        val pending = state.purchases.filter { it.status == "AUTHORIZED" && it.providerRef.isBlank() }
        if (pending.isNotEmpty()) {
            item { Text("Pendientes de emitir", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            items(pending, key = { "pending-${it.id}" }) { p ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${state.customerName(p.customerId)} · ${p.merchant}", fontWeight = FontWeight.Bold)
                        Text("${money(p.amountCents)} · ${state.orderById(p.orderId)?.orderRef ?: p.orderId}")
                        Button(onClick = { issue(p) }, enabled = !busy && state.apiBaseUrl.isNotBlank()) {
                            Icon(Icons.Default.CreditCard, null); Spacer(Modifier.width(6.dp)); Text("Emitir tarjeta en servidor")
                        }
                    }
                }
            }
        }

        item { Text("Sincronizadas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
        if (cards.isEmpty()) item { EmptyCard("Aún no hay tarjetas sincronizadas.") }
        items(cards, key = { it.providerRef.ifBlank { it.purchaseId } }) { card ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(card.customerRef.ifBlank { "Cliente" }, fontWeight = FontWeight.Bold)
                            Text("${card.merchant} · ${card.orderRef}")
                        }
                        Text(money(card.amountCents), fontWeight = FontWeight.Bold)
                    }
                    Text("${card.provider} · •••• ${card.last4}")
                    Text("Tarjeta: ${card.cardStatus} · Compra: ${card.purchaseStatus}")
                    if (card.expiration.isNotBlank()) Text("Expira: ${card.expiration}", style = MaterialTheme.typography.bodySmall)
                    Text("Ref: ${card.providerRef.take(18)}${if (card.providerRef.length > 18) "…" else ""}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item {
            Text(
                "SpendCore no guarda PAN ni CVV. Mostrar/compartir los datos completos requerirá el componente seguro del issuer.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun issueRemoteCard(state: AppState, purchase: Purchase, callback: (Result<RemoteCard>) -> Unit) {
    Thread {
        val result = runCatching {
            val api = ApiClient(state.apiBaseUrl)
            val localBusiness = state.businesses.firstOrNull { it.id == purchase.businessId } ?: error("Negocio local no encontrado")
            val order = state.orderById(purchase.orderId) ?: error("Orden local no encontrada")
            val customer = state.customers.firstOrNull { it.id == purchase.customerId } ?: error("Cliente local no encontrado")

            var remoteBusinessId = state.remoteBusinessId(localBusiness.id)
            if (remoteBusinessId.isNullOrBlank()) {
                val created = JSONObject(api.request("POST", "/v1/businesses", JSONObject().put("name", localBusiness.name).put("currency", "USD")))
                remoteBusinessId = created.getString("id")
                state.setRemoteBusinessId(localBusiness.id, remoteBusinessId)
            }

            var remoteBudgetId = state.remoteBudgetId(order.id)
            if (remoteBudgetId.isNullOrBlank()) {
                val rules = JSONObject()
                    .put("allowed_merchants", JSONArray(state.allowedMerchants.toList()))
                    .put("blocked_categories", JSONArray(listOf("ATM", "MONEY_TRANSFER", "CASH_EQUIVALENT", "CRYPTO")))
                    .put("single_purpose_only", true)
                val payload = JSONObject()
                    .put("business_id", remoteBusinessId)
                    .put("customer_ref", customer.name)
                    .put("order_ref", order.orderRef)
                    .put("limit_cents", order.purchaseLimitCents)
                    .put("rules", rules)
                val created = JSONObject(api.request("POST", "/v1/budgets", payload))
                remoteBudgetId = created.getString("id")
                state.setRemoteBudgetId(order.id, remoteBudgetId)
            }

            val payload = JSONObject()
                .put("business_id", remoteBusinessId)
                .put("budget_id", remoteBudgetId)
                .put("merchant", purchase.merchant)
                .put("amount_cents", purchase.amountCents)
                .put("currency", "USD")
                .put("external_order_ref", purchase.id)
            val created = JSONObject(api.request("POST", "/v1/purchase-requests", payload))
            val card = created.optJSONObject("card_intent") ?: error(created.optString("decline_reason", "El servidor no devolvió tarjeta"))
            RemoteCard(
                purchaseId = purchase.id,
                customerRef = customer.name,
                orderRef = order.orderRef,
                merchant = purchase.merchant,
                amountCents = purchase.amountCents,
                provider = card.optString("provider", "UNKNOWN"),
                providerRef = card.getString("provider_ref"),
                last4 = card.optString("last4", "????"),
                cardStatus = card.optString("status", "READY"),
                purchaseStatus = created.optString("status", "APPROVED"),
                expiration = card.optString("expiration", "")
            )
        }
        Handler(Looper.getMainLooper()).post { callback(result) }
    }.start()
}

private fun parseProvider(raw: String): ProviderInfo {
    val o = JSONObject(raw)
    return ProviderInfo(o.optString("provider", "—"), o.optBoolean("configured", false), o.optString("environment", "—"), o.optString("message", ""))
}

private fun parseCards(array: JSONArray): List<RemoteCard> = buildList {
    for (i in 0 until array.length()) {
        val o = array.getJSONObject(i)
        add(RemoteCard(
            purchaseId = o.optString("purchase_id"),
            customerRef = o.optString("customer_ref"),
            orderRef = o.optString("order_ref"),
            merchant = o.optString("merchant"),
            amountCents = o.optLong("amount_cents"),
            provider = o.optString("provider"),
            providerRef = o.optString("provider_ref"),
            last4 = o.optString("last4", "????"),
            cardStatus = o.optString("card_status"),
            purchaseStatus = o.optString("purchase_status"),
            expiration = o.optString("expiration")
        ))
    }
}

private class ApiClient(baseUrl: String) {
    private val base = baseUrl.trim().trimEnd('/')

    fun request(method: String, path: String, body: JSONObject? = null): String {
        require(base.startsWith("http://") || base.startsWith("https://")) { "URL inválida" }
        val conn = URL(base + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 12_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Content-Type", "application/json")
        if (method != "GET" && body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.use { BufferedReader(InputStreamReader(it)).readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) {
            val detail = runCatching { JSONObject(text).optString("detail", text) }.getOrDefault(text)
            error("HTTP $code: $detail")
        }
        return text.ifBlank { "{}" }
    }
}
