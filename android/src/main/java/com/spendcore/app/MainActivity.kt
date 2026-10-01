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

    fun addBusiness(name: String): Business? {
        val clean = name.trim()
        if (clean.isBlank()) return null
        val business = Business(id("BUS"), clean)
        businesses.add(business)
        if (selectedBusinessId == null) selectedBusinessId = business.id
        save()
        return business
    }

    fun updateBusiness(businessId: String, name: String): Boolean {
        val clean = name.trim()
        val index = businesses.indexOfFirst { it.id == businessId }
        if (index < 0 || clean.isBlank()) return false
        businesses[index] = businesses[index].copy(name = clean)
        save()
        return true
    }

    fun deleteBusiness(businessId: String) {
        purchases.removeAll { it.businessId == businessId }
        orders.removeAll { it.businessId == businessId }
        customers.removeAll { it.businessId == businessId }
        businesses.removeAll { it.id == businessId }
        if (selectedBusinessId == businessId) selectedBusinessId = businesses.firstOrNull()?.id
        save()
    }

    fun addCustomer(businessId: String, name: String): Customer? {
        if (businesses.none { it.id == businessId }) return null
        val clean = name.trim()
        if (clean.isBlank()) return null
        val customer = Customer(id("CUS"), businessId, clean)
        customers.add(customer)
        save()
        return customer
    }

    fun updateCustomer(customerId: String, name: String): Boolean {
        val clean = name.trim()
        val index = customers.indexOfFirst { it.id == customerId }
        if (index < 0 || clean.isBlank()) return false
        customers[index] = customers[index].copy(name = clean)
        save()
        return true
    }

    fun deleteCustomer(customerId: String) {
        val orderIds = orders.filter { it.customerId == customerId }.map { it.id }.toSet()
        purchases.removeAll { it.customerId == customerId || it.orderId in orderIds }
        orders.removeAll { it.customerId == customerId }
        customers.removeAll { it.id == customerId }
        save()
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

    fun updateOrder(orderId: String, paidCents: Long, limitCents: Long): String {
        val index = orders.indexOfFirst { it.id == orderId }
        if (index < 0) return "Orden no encontrada"
        val order = orders[index]
        if (paidCents <= 0 || limitCents <= 0) return "Los montos deben ser mayores que cero"
        if (paidCents < limitCents) return "El pago no puede ser menor que el máximo autorizado"
        if (limitCents < order.usedCents + order.reservedCents) {
            return "El máximo no puede quedar por debajo de lo ya usado o reservado"
        }
        orders[index] = order.copy(paidCents = paidCents, purchaseLimitCents = limitCents)
        save()
        return "Orden actualizada"
    }

    fun setOrderActive(orderId: String, active: Boolean) {
        val index = orders.indexOfFirst { it.id == orderId }
        if (index < 0) return
        orders[index] = orders[index].copy(status = if (active) "ACTIVE" else "PAUSED")
        save()
    }

    fun deleteOrder(orderId: String) {
        purchases.removeAll { it.orderId == orderId }
        orders.removeAll { it.id == orderId }
        save()
    }

    fun authorizePurchase(orderId: String, merchant: String, description: String, amountCents: Long): String {
        val index = orders.indexOfFirst { it.id == orderId }
        if (index < 0) return "Orden no encontrada"
        val order = orders[index]
        if (order.status != "ACTIVE") return "La orden no está activa"
        if (amountCents <= 0) return "El monto debe ser mayor que cero"
        if (amountCents > order.remainingCents) return "Compra rechazada: excede el presupuesto restante"
        val normalized = merchant.trim()
        if (normalized.isBlank()) return "Escribe el comercio"
        if (!merchantPermitted(normalized)) return "Compra rechazada: comercio no permitido por las reglas"

        orders[index] = order.copy(reservedCents = order.reservedCents + amountCents)
        val purchase = Purchase(
            id = id("PUR"),
            orderId = order.id,
            businessId = order.businessId,
            customerId = order.customerId,
            merchant = normalized,
            description = description.trim(),
            amountCents = amountCents,
            status = "AUTHORIZED",
            cardLast4 = (1000..9999).random().toString()
        )
        purchases.add(0, purchase)
        save()
        return "Autorizada. Tarjeta virtual temporal •••• ${purchase.cardLast4}"
    }

    fun updatePurchase(purchaseId: String, merchant: String, description: String, amountCents: Long): String {
        val pIndex = purchases.indexOfFirst { it.id == purchaseId }
        if (pIndex < 0) return "Compra no encontrada"
        val purchase = purchases[pIndex]
        val normalized = merchant.trim()
        if (normalized.isBlank()) return "Escribe el comercio"

        if (purchase.status != "AUTHORIZED") {
            purchases[pIndex] = purchase.copy(description = description.trim())
            save()
            return "Descripción actualizada. Una compra procesada no puede cambiar monto o comercio."
        }

        if (amountCents <= 0) return "El monto debe ser mayor que cero"
        if (!merchantPermitted(normalized)) return "Comercio no permitido por las reglas"
        val oIndex = orders.indexOfFirst { it.id == purchase.orderId }
        if (oIndex < 0) return "Orden no encontrada"
        val order = orders[oIndex]
        val availableIncludingThisPurchase = order.remainingCents + purchase.amountCents
        if (amountCents > availableIncludingThisPurchase) return "El nuevo monto excede el presupuesto disponible"

        orders[oIndex] = order.copy(
            reservedCents = (order.reservedCents - purchase.amountCents + amountCents).coerceAtLeast(0)
        )
        purchases[pIndex] = purchase.copy(
            merchant = normalized,
            description = description.trim(),
            amountCents = amountCents
        )
        save()
        return "Compra actualizada"
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

    fun deletePurchase(purchaseId: String) {
        val pIndex = purchases.indexOfFirst { it.id == purchaseId }
        if (pIndex < 0) return
        val purchase = purchases[pIndex]
        val oIndex = orders.indexOfFirst { it.id == purchase.orderId }
        if (oIndex >= 0) {
            val order = orders[oIndex]
            orders[oIndex] = when (purchase.status) {
                "AUTHORIZED" -> order.copy(reservedCents = (order.reservedCents - purchase.amountCents).coerceAtLeast(0))
                "CAPTURED" -> order.copy(usedCents = (order.usedCents - purchase.amountCents).coerceAtLeast(0))
                else -> order
            }
        }
        purchases.removeAt(pIndex)
        save()
    }

    fun addAllowedMerchant(name: String) {
        val clean = name.trim()
        if (clean.isNotBlank() && allowedMerchants.none { it.equals(clean, true) }) {
            allowedMerchants.add(clean)
            save()
        }
    }

    fun updateAllowedMerchant(old: String, new: String): Boolean {
        val clean = new.trim()
        val index = allowedMerchants.indexOf(old)
        if (index < 0 || clean.isBlank()) return false
        if (allowedMerchants.any { !it.equals(old, true) && it.equals(clean, true) }) return false
        allowedMerchants[index] = clean
        save()
        return true
    }

    fun removeAllowedMerchant(name: String) {
        allowedMerchants.remove(name)
        save()
    }

    fun businessName(id: String): String = businesses.firstOrNull { it.id == id }?.name ?: "Negocio"
    fun customerName(id: String): String = customers.firstOrNull { it.id == id }?.name ?: "Cliente"
    fun orderById(id: String): SpendOrder? = orders.firstOrNull { it.id == id }
    fun customerCascadeCount(customerId: String): Pair<Int, Int> {
        val customerOrders = orders.count { it.customerId == customerId }
        val customerPurchases = purchases.count { it.customerId == customerId }
        return customerOrders to customerPurchases
    }
    fun businessCascadeCount(businessId: String): Triple<Int, Int, Int> = Triple(
        customers.count { it.businessId == businessId },
        orders.count { it.businessId == businessId },
        purchases.count { it.businessId == businessId }
    )

    private fun merchantPermitted(name: String): Boolean = allowedMerchants.any {
        name.contains(it, ignoreCase = true) || it.contains(name, ignoreCase = true)
    }

    private fun nextOrderRef(): String {
        var n = orders.size + 1
        while (orders.any { it.orderRef == "SC-${n.toString().padStart(4, '0')}" }) n++
        return "SC-${n.toString().padStart(4, '0')}"
    }

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
            businesses.clear(); customers.clear(); orders.clear(); purchases.clear(); allowedMerchants.clear()
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
                TopAppBar(title = {
                    Column {
                        Text("SpendCore", fontWeight = FontWeight.Bold)
                        Text("v0.3 · MOCK", style = MaterialTheme.typography.labelSmall)
                    }
                })
            },
            bottomBar = {
                NavigationBar {
                    listOf(
                        Triple("Inicio", Icons.Default.Dashboard, 0),
                        Triple("Negocios", Icons.Default.Business, 1),
                        Triple("Órdenes", Icons.Default.ReceiptLong, 2),
                        Triple("Compras", Icons.Default.ShoppingCart, 3),
                        Triple("Reglas", Icons.Default.Rule, 4)
                    ).forEach { (label, icon, index) ->
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
    val visible = state.orders.filter { state.selectedBusinessId == null || it.businessId == state.selectedBusinessId }
    val authorized = visible.sumOf { it.purchaseLimitCents }
    val used = visible.sumOf { it.usedCents }
    val reserved = visible.sumOf { it.reservedCents }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Control de compras", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Presupuestos internos del negocio; no son saldos bancarios transferibles del cliente.")
        }
        if (state.businesses.isEmpty()) item { EmptyCard("Empieza creando tu primer negocio.") }
        else {
            item { BusinessSelector(state) }
            item { MetricCard("Autorizado", money(authorized), Icons.Default.Speed) }
            item { MetricCard("Usado", money(used), Icons.Default.ReceiptLong) }
            item { MetricCard("Reservado", money(reserved), Icons.Default.LockClock) }
            item { MetricCard("Disponible para compras", money((authorized - used - reserved).coerceAtLeast(0)), Icons.Default.VerifiedUser) }
            item { Text("Provider: MOCK · ninguna tarjeta real se emite todavía.", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun BusinessSelector(state: AppState) {
    if (state.businesses.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val current = state.businesses.firstOrNull { it.id == state.selectedBusinessId } ?: state.businesses.first()
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Business, null); Spacer(Modifier.width(8.dp)); Text(current.name, Modifier.weight(1f)); Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.businesses.forEach { business ->
                DropdownMenuItem(text = { Text(business.name) }, onClick = { state.selectedBusinessId = business.id; expanded = false })
            }
        }
    }
}

@Composable
private fun BusinessesScreen(state: AppState) {
    var addBusiness by remember { mutableStateOf(false) }
    var addCustomerTo by remember { mutableStateOf<Business?>(null) }
    var editBusiness by remember { mutableStateOf<Business?>(null) }
    var deleteBusiness by remember { mutableStateOf<Business?>(null) }
    var editCustomer by remember { mutableStateOf<Customer?>(null) }
    var deleteCustomer by remember { mutableStateOf<Customer?>(null) }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Negocios y clientes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Puedes corregir o eliminar registros creados por error.")
                }
                FilledTonalIconButton(onClick = { addBusiness = true }) { Icon(Icons.Default.Add, "Añadir negocio") }
            }
        }
        if (state.businesses.isEmpty()) item { EmptyCard("No hay negocios todavía.") }
        items(state.businesses, key = { it.id }) { business ->
            val clients = state.customers.filter { it.businessId == business.id }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(business.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("${clients.size} cliente(s)")
                        }
                        IconButton(onClick = { editBusiness = business }) { Icon(Icons.Default.Edit, "Editar negocio") }
                        IconButton(onClick = { deleteBusiness = business }) { Icon(Icons.Default.DeleteOutline, "Borrar negocio") }
                    }
                    clients.forEach { client ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Person, null); Spacer(Modifier.width(8.dp)); Text(client.name, Modifier.weight(1f))
                            IconButton(onClick = { editCustomer = client }) { Icon(Icons.Default.Edit, "Editar cliente") }
                            IconButton(onClick = { deleteCustomer = client }) { Icon(Icons.Default.DeleteOutline, "Borrar cliente") }
                        }
                    }
                    OutlinedButton(onClick = { addCustomerTo = business }) {
                        Icon(Icons.Default.PersonAdd, null); Spacer(Modifier.width(6.dp)); Text("Añadir cliente")
                    }
                }
            }
        }
    }

    if (addBusiness) NameDialog("Nuevo negocio", "Nombre del negocio", "", { addBusiness = false }) {
        state.addBusiness(it); addBusiness = false
    }
    addCustomerTo?.let { business ->
        NameDialog("Nuevo cliente · ${business.name}", "Nombre del cliente", "", { addCustomerTo = null }) {
            state.addCustomer(business.id, it); addCustomerTo = null
        }
    }
    editBusiness?.let { business ->
        NameDialog("Editar negocio", "Nombre del negocio", business.name, { editBusiness = null }) {
            state.updateBusiness(business.id, it); editBusiness = null
        }
    }
    editCustomer?.let { customer ->
        NameDialog("Editar cliente", "Nombre del cliente", customer.name, { editCustomer = null }) {
            state.updateCustomer(customer.id, it); editCustomer = null
        }
    }
    deleteBusiness?.let { business ->
        val (clients, orders, purchases) = state.businessCascadeCount(business.id)
        ConfirmDeleteDialog(
            title = "Borrar ${business.name}",
            message = "También se borrarán $clients cliente(s), $orders orden(es) y $purchases compra(s) asociadas. Esta acción no se puede deshacer.",
            onDismiss = { deleteBusiness = null },
            onConfirm = { state.deleteBusiness(business.id); deleteBusiness = null }
        )
    }
    deleteCustomer?.let { customer ->
        val (orders, purchases) = state.customerCascadeCount(customer.id)
        ConfirmDeleteDialog(
            title = "Borrar ${customer.name}",
            message = "También se borrarán $orders orden(es) y $purchases compra(s) asociadas.",
            onDismiss = { deleteCustomer = null },
            onConfirm = { state.deleteCustomer(customer.id); deleteCustomer = null }
        )
    }
}

@Composable
private fun OrdersScreen(state: AppState) {
    var showNew by remember { mutableStateOf(false) }
    var purchaseOrder by remember { mutableStateOf<SpendOrder?>(null) }
    var editOrder by remember { mutableStateOf<SpendOrder?>(null) }
    var deleteOrder by remember { mutableStateOf<SpendOrder?>(null) }
    val visible = state.orders.filter { state.selectedBusinessId == null || it.businessId == state.selectedBusinessId }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Órdenes de servicio", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Ejemplo: cliente paga $630 y autorizas hasta $600.")
                }
                FilledTonalIconButton(onClick = { showNew = true }, enabled = state.customers.isNotEmpty()) { Icon(Icons.Default.Add, "Nueva orden") }
            }
        }
        if (state.businesses.isNotEmpty()) item { BusinessSelector(state) }
        if (visible.isEmpty()) item { EmptyCard(if (state.customers.isEmpty()) "Primero crea un negocio y cliente." else "No hay órdenes para este negocio.") }
        items(visible, key = { it.id }) { order ->
            OrderCard(
                state = state,
                order = order,
                onNewPurchase = { purchaseOrder = order },
                onEdit = { editOrder = order },
                onDelete = { deleteOrder = order }
            )
        }
    }
    if (showNew) NewOrderDialog(state) { showNew = false }
    purchaseOrder?.let { NewPurchaseDialog(state, it) { purchaseOrder = null } }
    editOrder?.let { EditOrderDialog(state, it) { editOrder = null } }
    deleteOrder?.let { order ->
        val count = state.purchases.count { it.orderId == order.id }
        ConfirmDeleteDialog(
            "Borrar ${order.orderRef}",
            "También se borrarán $count compra(s) asociadas a esta orden.",
            { deleteOrder = null },
            { state.deleteOrder(order.id); deleteOrder = null }
        )
    }
}

@Composable
private fun OrderCard(state: AppState, order: SpendOrder, onNewPurchase: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(order.orderRef, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(state.customerName(order.customerId))
                }
                AssistChip(onClick = {}, label = { Text(order.status) })
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Editar orden") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.DeleteOutline, "Borrar orden") }
            }
            LinearProgressIndicator(
                progress = { (order.usedCents + order.reservedCents).toFloat() / order.purchaseLimitCents.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth()
            )
            Text("Cliente pagó: ${money(order.paidCents)}")
            Text("Máximo mercancía: ${money(order.purchaseLimitCents)}")
            Text("Servicio/diferencia: ${money(order.feeCents)} (${String.format(Locale.US, "%.2f", order.feePercent)}%)")
            Text("Usado ${money(order.usedCents)} · Reservado ${money(order.reservedCents)}")
            Text("Disponible: ${money(order.remainingCents)}", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onNewPurchase, enabled = order.status == "ACTIVE" && order.remainingCents > 0, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.CreditCard, null); Spacer(Modifier.width(5.dp)); Text("Nueva compra")
                }
                OutlinedButton(onClick = { state.setOrderActive(order.id, order.status != "ACTIVE") }) {
                    Text(if (order.status == "ACTIVE") "Pausar" else "Reactivar")
                }
            }
        }
    }
}

@Composable
private fun PurchasesScreen(state: AppState) {
    var editPurchase by remember { mutableStateOf<Purchase?>(null) }
    var deletePurchase by remember { mutableStateOf<Purchase?>(null) }
    val visible = state.purchases.filter { state.selectedBusinessId == null || it.businessId == state.selectedBusinessId }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Compras y tarjetas", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Cada autorización crea una tarjeta virtual simulada de propósito único.")
        }
        if (state.businesses.isNotEmpty()) item { BusinessSelector(state) }
        if (visible.isEmpty()) item { EmptyCard("No hay compras todavía.") }
        items(visible, key = { it.id }) { purchase ->
            val order = state.orderById(purchase.orderId)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(purchase.merchant, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text("${state.customerName(purchase.customerId)} · ${order?.orderRef ?: purchase.orderId}")
                        }
                        Text(money(purchase.amountCents), fontWeight = FontWeight.Bold)
                        IconButton(onClick = { editPurchase = purchase }) { Icon(Icons.Default.Edit, "Editar compra") }
                        IconButton(onClick = { deletePurchase = purchase }) { Icon(Icons.Default.DeleteOutline, "Borrar compra") }
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
                                Icon(Icons.Default.Undo, null); Spacer(Modifier.width(4.dp)); Text("Reembolso")
                            }
                        }
                    }
                }
            }
        }
    }
    editPurchase?.let { EditPurchaseDialog(state, it) { editPurchase = null } }
    deletePurchase?.let { purchase ->
        ConfirmDeleteDialog(
            "Borrar compra",
            "Se eliminará ${purchase.merchant} por ${money(purchase.amountCents)}. En MOCK SpendCore ajustará automáticamente lo reservado/usado.",
            { deletePurchase = null },
            { state.deletePurchase(purchase.id); deletePurchase = null }
        )
    }
}

@Composable
private fun RulesScreen(state: AppState) {
    var showAdd by remember { mutableStateOf(false) }
    var editMerchant by remember { mutableStateOf<String?>(null) }
    var deleteMerchant by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Reglas de gasto", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("En MOCK se valida por nombre; un issuer real usaría merchant/MCC controls.")
                }
                FilledTonalIconButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, "Añadir comercio") }
            }
        }
        items(state.allowedMerchants.toList()) { merchant ->
            ListItem(
                headlineContent = { Text(merchant) },
                supportingContent = { Text("Comercio permitido") },
                leadingContent = { Icon(Icons.Default.CheckCircle, null) },
                trailingContent = {
                    Row {
                        IconButton(onClick = { editMerchant = merchant }) { Icon(Icons.Default.Edit, "Editar") }
                        IconButton(onClick = { deleteMerchant = merchant }) { Icon(Icons.Default.DeleteOutline, "Eliminar") }
                    }
                }
            )
        }
        item { HorizontalDivider() }
        item { RuleLine("ATM / retiro de efectivo", false) }
        item { RuleLine("P2P / transferencias de dinero", false) }
        item { RuleLine("Depósitos externos", false) }
        item { RuleLine("Transferir presupuesto a otro cliente", false) }
        item { RuleLine("Tarjetas temporales por compra", true) }
        item { Text("Las operaciones reales requerirán issuer/proveedor aprobado; en producción las transacciones financieras se auditan en vez de borrarse.", style = MaterialTheme.typography.bodySmall) }
    }
    if (showAdd) NameDialog("Permitir comercio", "Nombre del comercio", "", { showAdd = false }) { state.addAllowedMerchant(it); showAdd = false }
    editMerchant?.let { old ->
        NameDialog("Editar comercio", "Nombre del comercio", old, { editMerchant = null }) {
            state.updateAllowedMerchant(old, it); editMerchant = null
        }
    }
    deleteMerchant?.let { merchant ->
        ConfirmDeleteDialog("Eliminar comercio", "Se quitará $merchant de la lista de comercios permitidos.", { deleteMerchant = null }) {
            state.removeAllowedMerchant(merchant); deleteMerchant = null
        }
    }
}

@Composable
private fun NewOrderDialog(state: AppState, onDismiss: () -> Unit) {
    val available = state.customers.filter { state.selectedBusinessId == null || it.businessId == state.selectedBusinessId }
    var customerId by remember { mutableStateOf(available.firstOrNull()?.id ?: "") }
    var paid by remember { mutableStateOf("630.00") }
    var limit by remember { mutableStateOf("600.00") }
    var error by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    val selected = available.firstOrNull { it.id == customerId }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nueva orden de servicio") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box {
                    OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(selected?.name ?: "Seleccionar cliente", Modifier.weight(1f)); Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        available.forEach { c -> DropdownMenuItem(text = { Text(c.name) }, onClick = { customerId = c.id; menu = false }) }
                    }
                }
                MoneyField("Pago del cliente", paid) { paid = it }
                MoneyField("Máximo autorizado para mercancía", limit) { limit = it }
                val pc = parseMoney(paid); val lc = parseMoney(limit)
                if (pc != null && lc != null && pc >= lc) Text("Servicio/diferencia: ${money(pc - lc)}")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val pc = parseMoney(paid); val lc = parseMoney(limit)
                when {
                    customerId.isBlank() -> error = "Selecciona un cliente"
                    pc == null || lc == null -> error = "Revisa los montos"
                    pc < lc -> error = "El pago no puede ser menor que el máximo"
                    state.addOrder(customerId, pc, lc) == null -> error = "No se pudo crear la orden"
                    else -> onDismiss()
                }
            }) { Text("Crear orden") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun EditOrderDialog(state: AppState, order: SpendOrder, onDismiss: () -> Unit) {
    var paid by remember(order.id) { mutableStateOf(decimal(order.paidCents)) }
    var limit by remember(order.id) { mutableStateOf(decimal(order.purchaseLimitCents)) }
    var message by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar ${order.orderRef}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MoneyField("Pago del cliente", paid) { paid = it }
                MoneyField("Máximo autorizado", limit) { limit = it }
                Text("Ya usado/reservado: ${money(order.usedCents + order.reservedCents)}", style = MaterialTheme.typography.bodySmall)
                message?.let { Text(it, color = if (it == "Orden actualizada") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val pc = parseMoney(paid); val lc = parseMoney(limit)
                if (pc == null || lc == null) message = "Revisa los montos"
                else {
                    message = state.updateOrder(order.id, pc, lc)
                    if (message == "Orden actualizada") onDismiss()
                }
            }) { Text("Guardar") }
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
    var menu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nueva compra · ${order.orderRef}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${state.customerName(order.customerId)} · Disponible ${money(order.remainingCents)}")
                MerchantSelector(state.allowedMerchants, merchant, { merchant = it }, menu, { menu = it })
                OutlinedTextField(description, { description = it }, label = { Text("Producto / descripción") }, modifier = Modifier.fillMaxWidth())
                MoneyField("Total final de compra", amount) { amount = it }
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
private fun EditPurchaseDialog(state: AppState, purchase: Purchase, onDismiss: () -> Unit) {
    var merchant by remember(purchase.id) { mutableStateOf(purchase.merchant) }
    var description by remember(purchase.id) { mutableStateOf(purchase.description) }
    var amount by remember(purchase.id) { mutableStateOf(decimal(purchase.amountCents)) }
    var message by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    val editableFinancials = purchase.status == "AUTHORIZED"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar compra") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (editableFinancials) MerchantSelector(state.allowedMerchants, merchant, { merchant = it }, menu, { menu = it })
                else OutlinedTextField(merchant, {}, label = { Text("Comercio") }, enabled = false, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it }, label = { Text("Descripción") }, modifier = Modifier.fillMaxWidth())
                MoneyField("Monto", amount, enabled = editableFinancials) { amount = it }
                if (!editableFinancials) Text("Al estar procesada, solo se puede corregir la descripción.", style = MaterialTheme.typography.bodySmall)
                message?.let { Text(it, color = if (it.contains("actualizada")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val cents = parseMoney(amount)
                if (cents == null) message = "Monto inválido"
                else {
                    message = state.updatePurchase(purchase.id, merchant, description, cents)
                    if (message!!.contains("actualizada")) onDismiss()
                }
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun MerchantSelector(merchants: List<String>, current: String, onSelect: (String) -> Unit, expanded: Boolean, onExpanded: (Boolean) -> Unit) {
    Box {
        OutlinedButton(onClick = { onExpanded(true) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (current.isBlank()) "Seleccionar comercio" else current, Modifier.weight(1f)); Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpanded(false) }) {
            merchants.forEach { m -> DropdownMenuItem(text = { Text(m) }, onClick = { onSelect(m); onExpanded(false) }) }
        }
    }
}

@Composable
private fun NameDialog(title: String, label: String, initialValue: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { Button(onClick = { onSave(value.trim()) }, enabled = value.isNotBlank()) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun ConfirmDeleteDialog(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Warning, null) },
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { Button(onClick = onConfirm) { Text("Borrar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun MoneyField(label: String, value: String, enabled: Boolean = true, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { next -> if (next.matches(Regex("^\\d{0,8}([.]\\d{0,2})?$"))) onValueChange(next) },
        label = { Text(label) },
        prefix = { Text("$") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun MetricCard(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null); Spacer(Modifier.width(14.dp)); Column {
                Text(label, style = MaterialTheme.typography.labelLarge)
                Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable private fun EmptyCard(text: String) { Card(Modifier.fillMaxWidth()) { Text(text, Modifier.padding(18.dp)) } }
@Composable private fun RuleLine(name: String, allowed: Boolean) {
    ListItem(headlineContent = { Text(name) }, trailingContent = { Icon(if (allowed) Icons.Default.Check else Icons.Default.Block, null) })
}

private fun parseMoney(value: String): Long? = value.toDoubleOrNull()?.let { (it * 100.0).roundToLong() }?.takeIf { it > 0 }
private fun money(cents: Long): String = "$" + String.format(Locale.US, "%.2f", cents / 100.0)
private fun decimal(cents: Long): String = String.format(Locale.US, "%.2f", cents / 100.0)
private fun formatDate(timestamp: Long): String = SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(timestamp))
