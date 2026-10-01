package com.spendcore.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToLong

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SpendCoreApp(applicationContext) }
    }
}

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
    val feePercent: Double get() = if (purchaseLimitCents > 0) feeCents * 100.0 / purchaseLimitCents else 0.0
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
    val createdAt: Long = System.currentTimeMillis()
)

class AppState(private val context: Context) {
    private val prefs = context.getSharedPreferences("spendcore_v2", Context.MODE_PRIVATE)
    var selectedTab by mutableIntStateOf(0)
    var selectedBusinessId by mutableStateOf<String?>(null)
    val businesses = mutableStateListOf<Business>()
    val customers = mutableStateListOf<Customer>()
    val orders = mutableStateListOf<SpendOrder>()
    val purchases = mutableStateListOf<Purchase>()
    val allowedMerchants = mutableStateListOf("Amazon", "Walmart", "Shein", "Temu")

    init {
        load()
        if (selectedBusinessId == null) selectedBusinessId = businesses.firstOrNull()?.id
    }

    fun addBusiness(name: String): Business {
        val business = Business(id("BUS"), name.trim())
        businesses.add(business)
        if (selectedBusinessId == null) selectedBusinessId = business.id
        save()
        return business
    }

    fun addCustomer(businessId: String, name: String): Customer {
        val customer = Customer(id("CUS"), businessId, name.trim())
        customers.add(customer)
        save()
        return customer
    }

    fun addOrder(customerId: String, paidCents: Long, limitCents: Long): SpendOrder? {
        val customer = customers.firstOrNull { it.id == customerId } ?: return null
        if (paidCents <= 0 || limitCents <= 0 || paidCents < limitCents) return null
        val order = SpendOrder(
            id = id("ORD"),
            businessId = customer.businessId,
            customerId = customer.id,
            orderRef = nextOrderRef(),
            paidCents = paidCents,
            purchaseLimitCents = limitCents
        )
        orders.add(0, order)
        save()
        return order
    }

    fun authorizePurchase(orderId: String, merchant: String, description: String, amountCents: Long): String {
        val index = orders.indexOfFirst { it.id == orderId }
        if (index < 0) return "Orden no encontrada"
        val order = orders[index]
        if (order.status != "ACTIVE") return "La orden no está activa"
        if (amountCents <= 0) return "El monto debe ser mayor que cero"
        if (amountCents > order.remainingCents) return "Compra rechazada: excede el presupuesto restante"
        if (merchant.trim().isBlank()) return "Escribe el comercio"

        val normalized = merchant.trim()
        val permitted = allowedMerchants.any { normalized.contains(it, ignoreCase = true) || it.contains(normalized, ignoreCase = true) }
        if (!permitted) return "Compra rechazada: comercio no permitido por las reglas"

        orders[index] = order.copy(reservedCents = order.reservedCents + amountCents)
        purchases.add(0, Purchase(
            id = id("PUR"),
            orderId = order.id,
            businessId = order.businessId,
            customerId = order.customerId,
            merchant = normalized,
            description = description.trim(),
            amountCents = amountCents,
            status = "AUTHORIZED",
            cardLast4 = (1000..9999).random().toString()
        ))
        save()
        return "Autorizada. Tarjeta virtual temporal •••• ${purchases.first().cardLast4}"
    }

    fun capturePurchase(purchaseId: String) {
        val pIndex = purchases.indexOfFirst { it.id == purchaseId }
        if (pIndex < 0) return
        val purchase = purchases[pIndex]
        if (purchase.status != "AUTHORIZED") return
        val oIndex = orders.indexOfFirst { it.id == purchase.orderId }
        if (oIndex < 0) return
        val order = orders[oIndex]
        orders[oIndex] = order.copy(
            reservedCents = (order.reservedCents - purchase.amountCents).coerceAtLeast(0),
            usedCents = order.usedCents + purchase.amountCents
        )
        purchases[pIndex] = purchase.copy(status = "CAPTURED")
        save()
    }

    fun cancelPurchase(purchaseId: String) {
        val pIndex = purchases.indexOfFirst { it.id == purchaseId }
        if (pIndex < 0) return
        val purchase = purchases[pIndex]
        if (purchase.status != "AUTHORIZED") return
        val oIndex = orders.indexOfFirst { it.id == purchase.orderId }
        if (oIndex >= 0) {
            val order = orders[oIndex]
            orders[oIndex] = order.copy(reservedCents = (order.reservedCents - purchase.amountCents).coerceAtLeast(0))
        }
        purchases[pIndex] = purchase.copy(status = "CANCELLED")
        save()
    }

    fun refundPurchase(purchaseId: String) {
        val pIndex = purchases.indexOfFirst { it.id == purchaseId }
        if (pIndex < 0) return
        val purchase = purchases[pIndex]
        if (purchase.status != "CAPTURED") return
        val oIndex = orders.indexOfFirst { it.id == purchase.orderId }
        if (oIndex >= 0) {
            val order = orders[oIndex]
            orders[oIndex] = order.copy(usedCents = (order.usedCents - purchase.amountCents).coerceAtLeast(0))
        }
        purchases[pIndex] = purchase.copy(status = "REFUNDED")
        save()
    }

    fun addAllowedMerchant(name: String) {
        val clean = name.trim()
        if (clean.isNotBlank() && allowedMerchants.none { it.equals(clean, true) }) {
            allowedMerchants.add(clean)
            save()
        }
    }

    fun removeAllowedMerchant(name: String) {
        allowedMerchants.remove(name)
        save()
    }

    fun businessName(id: String): String = businesses.firstOrNull { it.id == id }?.name ?: "Negocio"
    fun customerName(id: String): String = customers.firstOrNull { it.id == id }?.name ?: "Cliente"
    fun orderById(id: String): SpendOrder? = orders.firstOrNull { it.id == id }

    private fun nextOrderRef(): String = "SC-${(orders.size + 1).toString().padStart(4, '0')}"
    private fun id(prefix: String): String = "$prefix-${UUID.randomUUID().toString().take(8).uppercase()}"

    private fun save() {
        val root = JSONObject()
        root.put("businesses", JSONArray().apply { businesses.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
        root.put("customers", JSONArray().apply { customers.forEach { put(JSONObject().put("id", it.id).put("businessId", it.businessId).put("name", it.name)) } })
        root.put("orders", JSONArray().apply { orders.forEach {
            put(JSONObject()
                .put("id", it.id).put("businessId", it.businessId).put("customerId", it.customerId)
                .put("orderRef", it.orderRef).put("paidCents", it.paidCents).put("purchaseLimitCents", it.purchaseLimitCents)
                .put("usedCents", it.usedCents).put("reservedCents", it.reservedCents).put("status", it.status))
        } })
        root.put("purchases", JSONArray().apply { purchases.forEach {
            put(JSONObject()
                .put("id", it.id).put("orderId", it.orderId).put("businessId", it.businessId).put("customerId", it.customerId)
                .put("merchant", it.merchant).put("description", it.description).put("amountCents", it.amountCents)
                .put("status", it.status).put("cardLast4", it.cardLast4).put("createdAt", it.createdAt))
        } })
        root.put("allowedMerchants", JSONArray().apply { allowedMerchants.forEach { put(it) } })
        prefs.edit().putString("state", root.toString()).apply()
    }

    private fun load() {
        val raw = prefs.getString("state", null) ?: return
        runCatching {
            val root = JSONObject(raw)
            businesses.clear()
            customers.clear()
            orders.clear()
            purchases.clear()
            allowedMerchants.clear()
            root.optJSONArray("businesses")?.forEachObject { businesses.add(Business(it.getString("id"), it.getString("name"))) }
            root.optJSONArray("customers")?.forEachObject { customers.add(Customer(it.getString("id"), it.getString("businessId"), it.getString("name"))) }
            root.optJSONArray("orders")?.forEachObject {
                orders.add(SpendOrder(
                    it.getString("id"), it.getString("businessId"), it.getString("customerId"), it.getString("orderRef"),
                    it.getLong("paidCents"), it.getLong("purchaseLimitCents"), it.optLong("usedCents"), it.optLong("reservedCents"), it.optString("status", "ACTIVE")
                ))
            }
            root.optJSONArray("purchases")?.forEachObject {
                purchases.add(Purchase(
                    it.getString("id"), it.getString("orderId"), it.getString("businessId"), it.getString("customerId"),
                    it.getString("merchant"), it.optString("description"), it.getLong("amountCents"), it.getString("status"),
                    it.optString("cardLast4", "0000"), it.optLong("createdAt", System.currentTimeMillis())
                ))
            }
            root.optJSONArray("allowedMerchants")?.let { array -> for (i in 0 until array.length()) allowedMerchants.add(array.getString(i)) }
            if (allowedMerchants.isEmpty()) allowedMerchants.addAll(listOf("Amazon", "Walmart", "Shein", "Temu"))
        }
    }
}

private inline fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
    for (i in 0 until length()) block(getJSONObject(i))
}

@Composable
fun SpendCoreApp(context: Context) {
    val state = remember { AppState(context) }
    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("SpendCore", fontWeight = FontWeight.Bold)
                            Text("v0.2 · MOCK", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    val tabs = listOf(
                        Triple("Inicio", Icons.Default.Dashboard, 0),
                        Triple("Negocios", Icons.Default.Business, 1),
                        Triple("Órdenes", Icons.Default.ReceiptLong, 2),
                        Triple("Compras", Icons.Default.ShoppingCart, 3),
                        Triple("Reglas", Icons.Default.Rule, 4)
                    )
                    tabs.forEach { (label, icon, index) ->
                        NavigationBarItem(
                            selected = state.selectedTab == index,
                            onClick = { state.selectedTab = index },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label, maxLines = 1) }
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (state.selectedTab) {
                    0 -> DashboardScreen(state)
                    1 -> BusinessesScreen(state)
                    2 -> OrdersScreen(state)
                    3 -> PurchasesScreen(state)
                    else -> RulesScreen(state)
                }
            }
        }
    }
}

@Composable
private fun DashboardScreen(state: AppState) {
    val orders = state.orders.filter { state.selectedBusinessId == null || it.businessId == state.selectedBusinessId }
    val authorized = orders.sumOf { it.purchaseLimitCents }
    val used = orders.sumOf { it.usedCents }
    val reserved = orders.sumOf { it.reservedCents }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Control de compras", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Los presupuestos son límites internos de autorización del negocio, no saldos bancarios del cliente.")
        }
        if (state.businesses.isEmpty()) {
            item { EmptyCard("Empieza creando tu primer negocio en la pestaña Negocios.") }
        } else {
            item { BusinessSelector(state) }
            item { MetricCard("Autorizado", money(authorized), Icons.Default.Speed) }
            item { MetricCard("Usado", money(used), Icons.Default.ReceiptLong) }
            item { MetricCard("Reservado", money(reserved), Icons.Default.LockClock) }
            item { MetricCard("Disponible para compras", money((authorized - used - reserved).coerceAtLeast(0)), Icons.Default.VerifiedUser) }
            item { Text("Provider: MOCK · las tarjetas son simuladas hasta conectar un issuer real.", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun BusinessSelector(state: AppState) {
    var expanded by remember { mutableStateOf(false) }
    val current = state.businesses.firstOrNull { it.id == state.selectedBusinessId } ?: state.businesses.first()
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Business, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(current.name, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.businesses.forEach { business ->
                DropdownMenuItem(
                    text = { Text(business.name) },
                    onClick = {
                        state.selectedBusinessId = business.id
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun BusinessesScreen(state: AppState) {
    var showBusinessDialog by remember { mutableStateOf(false) }
    var customerBusiness by remember { mutableStateOf<Business?>(null) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Negocios y clientes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Cada negocio mantiene sus propias órdenes y límites.")
                }
                FilledTonalIconButton(onClick = { showBusinessDialog = true }) { Icon(Icons.Default.Add, contentDescription = "Añadir negocio") }
            }
        }
        if (state.businesses.isEmpty()) item { EmptyCard("No hay negocios todavía.") }
        items(state.businesses, key = { it.id }) { business ->
            val clients = state.customers.filter { it.businessId == business.id }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(business.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${clients.size} cliente(s)")
                    clients.forEach { client ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Person, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(client.name)
                        }
                    }
                    OutlinedButton(onClick = { customerBusiness = business }) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Añadir cliente")
                    }
                }
            }
        }
    }
    if (showBusinessDialog) NameDialog(
        title = "Nuevo negocio",
        label = "Nombre del negocio",
        onDismiss = { showBusinessDialog = false },
        onSave = {
            state.addBusiness(it)
            showBusinessDialog = false
        }
    )
    customerBusiness?.let { business ->
        NameDialog(
            title = "Nuevo cliente · ${business.name}",
            label = "Nombre del cliente",
            onDismiss = { customerBusiness = null },
            onSave = {
                state.addCustomer(business.id, it)
                customerBusiness = null
            }
        )
    }
}

@Composable
private fun OrdersScreen(state: AppState) {
    var showNew by remember { mutableStateOf(false) }
    var purchaseOrder by remember { mutableStateOf<SpendOrder?>(null) }
    val visible = state.orders.filter { state.selectedBusinessId == null || it.businessId == state.selectedBusinessId }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Órdenes de servicio", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Ejemplo: cliente paga $630 y autorizas hasta $600 en mercancía.")
                }
                FilledTonalIconButton(onClick = { showNew = true }, enabled = state.customers.isNotEmpty()) { Icon(Icons.Default.Add, contentDescription = "Nueva orden") }
            }
        }
        if (state.businesses.isNotEmpty()) item { BusinessSelector(state) }
        if (visible.isEmpty()) item { EmptyCard(if (state.customers.isEmpty()) "Primero crea un negocio y un cliente." else "No hay órdenes para este negocio.") }
        items(visible, key = { it.id }) { order ->
            OrderCard(state, order, onNewPurchase = { purchaseOrder = order })
        }
    }
    if (showNew) NewOrderDialog(state, onDismiss = { showNew = false })
    purchaseOrder?.let { order ->
        NewPurchaseDialog(state, order, onDismiss = { purchaseOrder = null })
    }
}

@Composable
private fun OrderCard(state: AppState, order: SpendOrder, onNewPurchase: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(order.orderRef, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(state.customerName(order.customerId))
                }
                AssistChip(onClick = {}, label = { Text(order.status) })
            }
            LinearProgressIndicator(
                progress = { (order.usedCents + order.reservedCents).toFloat() / order.purchaseLimitCents.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth()
            )
            Text("Cliente pagó: ${money(order.paidCents)}")
            Text("Máximo mercancía: ${money(order.purchaseLimitCents)}")
            Text("Servicio/diferencia: ${money(order.feeCents)} (${String.format(Locale.US, "%.2f", order.feePercent)}%)")
            Text("Usado ${money(order.usedCents)} · Reservado ${money(order.reservedCents)}")
            Text("Disponible para compras: ${money(order.remainingCents)}", fontWeight = FontWeight.Bold)
            Button(onClick = onNewPurchase, enabled = order.remainingCents > 0) {
                Icon(Icons.Default.CreditCard, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Nueva compra / tarjeta temporal")
            }
        }
    }
}

@Composable
private fun PurchasesScreen(state: AppState) {
    val visible = state.purchases.filter { state.selectedBusinessId == null || it.businessId == state.selectedBusinessId }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Compras y tarjetas", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Cada compra autorizada crea una tarjeta virtual simulada de propósito único.")
        }
        if (state.businesses.isNotEmpty()) item { BusinessSelector(state) }
        if (visible.isEmpty()) item { EmptyCard("No hay compras todavía. Créala desde una orden.") }
        items(visible, key = { it.id }) { purchase ->
            val order = state.orderById(purchase.orderId)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(purchase.merchant, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text("${state.customerName(purchase.customerId)} · ${order?.orderRef ?: purchase.orderId}")
                        }
                        Text(money(purchase.amountCents), fontWeight = FontWeight.Bold)
                    }
                    if (purchase.description.isNotBlank()) Text(purchase.description)
                    Text("Tarjeta virtual •••• ${purchase.cardLast4} · single-use", style = MaterialTheme.typography.bodySmall)
                    Text("${formatDate(purchase.createdAt)} · ${purchase.status}", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (purchase.status) {
                            "AUTHORIZED" -> {
                                Button(onClick = { state.capturePurchase(purchase.id) }) { Text("Capturar") }
                                OutlinedButton(onClick = { state.cancelPurchase(purchase.id) }) { Text("Cancelar") }
                            }
                            "CAPTURED" -> OutlinedButton(onClick = { state.refundPurchase(purchase.id) }) {
                                Icon(Icons.Default.Undo, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text("Reembolso")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RulesScreen(state: AppState) {
    var showAdd by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Reglas de gasto", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("En MOCK se valida por nombre del comercio; un issuer real usaría MCC/merchant controls.")
                }
                FilledTonalIconButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, contentDescription = "Añadir comercio") }
            }
        }
        items(state.allowedMerchants.toList()) { merchant ->
            ListItem(
                headlineContent = { Text(merchant) },
                supportingContent = { Text("Comercio permitido") },
                leadingContent = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
                trailingContent = {
                    IconButton(onClick = { state.removeAllowedMerchant(merchant) }) { Icon(Icons.Default.DeleteOutline, contentDescription = "Eliminar") }
                }
            )
        }
        item { HorizontalDivider() }
        item { RuleLine("ATM / retiro de efectivo", false) }
        item { RuleLine("P2P / transferencias de dinero", false) }
        item { RuleLine("Depósitos externos", false) }
        item { RuleLine("Transferir presupuesto a otro cliente", false) }
        item { RuleLine("Tarjetas temporales por compra", true) }
        item { Text("Importante: estas restricciones son de producto. La clasificación regulatoria real depende del flujo de fondos, contratos e issuer del programa.", style = MaterialTheme.typography.bodySmall) }
    }
    if (showAdd) NameDialog(
        title = "Permitir comercio",
        label = "Nombre del comercio",
        onDismiss = { showAdd = false },
        onSave = {
            state.addAllowedMerchant(it)
            showAdd = false
        }
    )
}

@Composable
private fun NewOrderDialog(state: AppState, onDismiss: () -> Unit) {
    val availableCustomers = state.customers.filter { state.selectedBusinessId == null || it.businessId == state.selectedBusinessId }
    var customerId by remember { mutableStateOf(availableCustomers.firstOrNull()?.id ?: "") }
    var paid by remember { mutableStateOf("630.00") }
    var limit by remember { mutableStateOf("600.00") }
    var error by remember { mutableStateOf<String?>(null) }
    var customerMenu by remember { mutableStateOf(false) }
    val selectedCustomer = availableCustomers.firstOrNull { it.id == customerId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nueva orden de servicio") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box {
                    OutlinedButton(onClick = { customerMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(selectedCustomer?.name ?: "Seleccionar cliente", modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = customerMenu, onDismissRequest = { customerMenu = false }) {
                        availableCustomers.forEach { c ->
                            DropdownMenuItem(text = { Text(c.name) }, onClick = { customerId = c.id; customerMenu = false })
                        }
                    }
                }
                MoneyField("Pago del cliente", paid) { paid = it }
                MoneyField("Máximo autorizado para mercancía", limit) { limit = it }
                val paidCents = parseMoney(paid)
                val limitCents = parseMoney(limit)
                if (paidCents != null && limitCents != null && paidCents >= limitCents) {
                    val fee = paidCents - limitCents
                    val pct = if (limitCents > 0) fee * 100.0 / limitCents else 0.0
                    Text("Servicio/diferencia: ${money(fee)} · ${String.format(Locale.US, "%.2f", pct)}%")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val paidCents = parseMoney(paid)
                val limitCents = parseMoney(limit)
                when {
                    customerId.isBlank() -> error = "Selecciona un cliente"
                    paidCents == null || limitCents == null -> error = "Revisa los montos"
                    paidCents < limitCents -> error = "El pago no puede ser menor que el máximo autorizado"
                    state.addOrder(customerId, paidCents, limitCents) == null -> error = "No se pudo crear la orden"
                    else -> onDismiss()
                }
            }) { Text("Crear orden") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun NewPurchaseDialog(state: AppState, order: SpendOrder, onDismiss: () -> Unit) {
    var merchant by remember { mutableStateOf(state.allowedMerchants.firstOrNull() ?: "") }
    var description by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var merchantMenu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nueva compra · ${order.orderRef}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${state.customerName(order.customerId)} · Disponible ${money(order.remainingCents)}")
                Box {
                    OutlinedButton(onClick = { merchantMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (merchant.isBlank()) "Seleccionar comercio" else merchant, modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = merchantMenu, onDismissRequest = { merchantMenu = false }) {
                        state.allowedMerchants.forEach { m ->
                            DropdownMenuItem(text = { Text(m) }, onClick = { merchant = m; merchantMenu = false })
                        }
                    }
                }
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Producto / descripción") }, modifier = Modifier.fillMaxWidth())
                MoneyField("Total final de compra", amount) { amount = it }
                Text("SpendCore verificará el límite antes de crear la tarjeta temporal.", style = MaterialTheme.typography.bodySmall)
                message?.let { Text(it, color = if (it.startsWith("Autorizada")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val cents = parseMoney(amount)
                if (cents == null) message = "Monto inválido"
                else {
                    val result = state.authorizePurchase(order.id, merchant, description, cents)
                    message = result
                    if (result.startsWith("Autorizada")) onDismiss()
                }
            }) { Text("Autorizar y crear tarjeta") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun NameDialog(title: String, label: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { Button(onClick = { if (value.isNotBlank()) onSave(value) }, enabled = value.isNotBlank()) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun MoneyField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { next -> if (next.matches(Regex("^\\d{0,8}([.]\\d{0,2})?$"))) onValueChange(next) },
        label = { Text(label) },
        prefix = { Text("$") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun MetricCard(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelLarge)
                Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun EmptyCard(text: String) {
    Card(Modifier.fillMaxWidth()) { Text(text, Modifier.padding(18.dp)) }
}

@Composable
private fun RuleLine(name: String, allowed: Boolean) {
    ListItem(
        headlineContent = { Text(name) },
        trailingContent = { Icon(if (allowed) Icons.Default.Check else Icons.Default.Block, contentDescription = null) }
    )
}

private fun parseMoney(value: String): Long? = value.toDoubleOrNull()?.let { (it * 100.0).roundToLong() }?.takeIf { it > 0 }
private fun money(cents: Long): String = "$" + String.format(Locale.US, "%.2f", cents / 100.0)
private fun formatDate(timestamp: Long): String = SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(timestamp))
