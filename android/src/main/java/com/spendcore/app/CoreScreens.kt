package com.spendcore.app

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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

@Composable
fun DashboardScreen(state: AppState) {
    val visible = state.orders.filter { state.selectedBusinessId == null || it.businessId == state.selectedBusinessId }
    val authorized = visible.sumOf { it.purchaseLimitCents }
    val used = visible.sumOf { it.usedCents }
    val reserved = visible.sumOf { it.reservedCents }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Control de compras", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Los presupuestos son límites internos de compra, no saldos transferibles del cliente.")
        }
        if (state.businesses.isEmpty()) item { EmptyCard("Empieza creando tu primer negocio.") }
        else {
            item { BusinessSelector(state) }
            item { MetricCard("Autorizado", money(authorized), Icons.Default.Speed) }
            item { MetricCard("Usado", money(used), Icons.Default.ReceiptLong) }
            item { MetricCard("Reservado", money(reserved), Icons.Default.LockClock) }
            item { MetricCard("Disponible", money((authorized - used - reserved).coerceAtLeast(0)), Icons.Default.VerifiedUser) }
            item { Text(if (state.apiBaseUrl.isBlank()) "Servidor: no configurado" else "Servidor: ${state.apiBaseUrl}", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
fun BusinessSelector(state: AppState) {
    if (state.businesses.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val current = state.businesses.firstOrNull { it.id == state.selectedBusinessId } ?: state.businesses.first()
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Business, null); Spacer(Modifier.width(8.dp)); Text(current.name, Modifier.weight(1f)); Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.businesses.forEach { b -> DropdownMenuItem(text = { Text(b.name) }, onClick = { state.selectedBusinessId = b.id; expanded = false }) }
        }
    }
}

@Composable
fun BusinessesScreen(state: AppState) {
    var addBusiness by remember { mutableStateOf(false) }
    var addCustomerTo by remember { mutableStateOf<Business?>(null) }
    var editBusiness by remember { mutableStateOf<Business?>(null) }
    var deleteBusiness by remember { mutableStateOf<Business?>(null) }
    var editCustomer by remember { mutableStateOf<Customer?>(null) }
    var deleteCustomer by remember { mutableStateOf<Customer?>(null) }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Negocios y clientes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Puedes editar o eliminar registros creados por error.")
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
                        IconButton(onClick = { editBusiness = business }) { Icon(Icons.Default.Edit, "Editar") }
                        IconButton(onClick = { deleteBusiness = business }) { Icon(Icons.Default.DeleteOutline, "Borrar") }
                    }
                    clients.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Person, null); Spacer(Modifier.width(8.dp)); Text(c.name, Modifier.weight(1f))
                            IconButton(onClick = { editCustomer = c }) { Icon(Icons.Default.Edit, "Editar cliente") }
                            IconButton(onClick = { deleteCustomer = c }) { Icon(Icons.Default.DeleteOutline, "Borrar cliente") }
                        }
                    }
                    OutlinedButton(onClick = { addCustomerTo = business }) { Icon(Icons.Default.PersonAdd, null); Spacer(Modifier.width(6.dp)); Text("Añadir cliente") }
                }
            }
        }
    }

    if (addBusiness) NameDialog("Nuevo negocio", "Nombre", "", { addBusiness = false }) { state.addBusiness(it); addBusiness = false }
    addCustomerTo?.let { b -> NameDialog("Nuevo cliente · ${b.name}", "Nombre", "", { addCustomerTo = null }) { state.addCustomer(b.id, it); addCustomerTo = null } }
    editBusiness?.let { b -> NameDialog("Editar negocio", "Nombre", b.name, { editBusiness = null }) { state.updateBusiness(b.id, it); editBusiness = null } }
    editCustomer?.let { c -> NameDialog("Editar cliente", "Nombre", c.name, { editCustomer = null }) { state.updateCustomer(c.id, it); editCustomer = null } }
    deleteBusiness?.let { b ->
        val (c, o, p) = state.businessCascadeCount(b.id)
        ConfirmDeleteDialog("Borrar ${b.name}", "También se borrarán $c cliente(s), $o orden(es) y $p compra(s).", { deleteBusiness = null }) { state.deleteBusiness(b.id); deleteBusiness = null }
    }
    deleteCustomer?.let { c ->
        val (o, p) = state.customerCascadeCount(c.id)
        ConfirmDeleteDialog("Borrar ${c.name}", "También se borrarán $o orden(es) y $p compra(s).", { deleteCustomer = null }) { state.deleteCustomer(c.id); deleteCustomer = null }
    }
}

@Composable
fun OrdersScreen(state: AppState) {
    var showNew by remember { mutableStateOf(false) }
    var newPurchase by remember { mutableStateOf<SpendOrder?>(null) }
    var editOrder by remember { mutableStateOf<SpendOrder?>(null) }
    var deleteOrder by remember { mutableStateOf<SpendOrder?>(null) }
    val visible = state.orders.filter { state.selectedBusinessId == null || it.businessId == state.selectedBusinessId }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Órdenes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Ejemplo: cliente paga $630 y autorizas hasta $600.")
                }
                FilledTonalIconButton(onClick = { showNew = true }, enabled = state.customers.isNotEmpty()) { Icon(Icons.Default.Add, "Nueva orden") }
            }
        }
        if (state.businesses.isNotEmpty()) item { BusinessSelector(state) }
        if (visible.isEmpty()) item { EmptyCard("No hay órdenes para este negocio.") }
        items(visible, key = { it.id }) { order ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(order.orderRef, fontWeight = FontWeight.Bold); Text(state.customerName(order.customerId)) }
                        AssistChip(onClick = {}, label = { Text(order.status) })
                        IconButton(onClick = { editOrder = order }) { Icon(Icons.Default.Edit, "Editar") }
                        IconButton(onClick = { deleteOrder = order }) { Icon(Icons.Default.DeleteOutline, "Borrar") }
                    }
                    LinearProgressIndicator(progress = { (order.usedCents + order.reservedCents).toFloat() / order.purchaseLimitCents.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                    Text("Cliente pagó: ${money(order.paidCents)}")
                    Text("Máximo mercancía: ${money(order.purchaseLimitCents)}")
                    Text("Servicio/diferencia: ${money(order.feeCents)}")
                    Text("Usado ${money(order.usedCents)} · Reservado ${money(order.reservedCents)}")
                    Text("Disponible: ${money(order.remainingCents)}", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { newPurchase = order }, enabled = order.status == "ACTIVE" && order.remainingCents > 0, modifier = Modifier.weight(1f)) { Icon(Icons.Default.CreditCard, null); Spacer(Modifier.width(5.dp)); Text("Nueva compra") }
                        OutlinedButton(onClick = { state.setOrderActive(order.id, order.status != "ACTIVE") }) { Text(if (order.status == "ACTIVE") "Pausar" else "Reactivar") }
                    }
                }
            }
        }
    }
    if (showNew) NewOrderDialog(state) { showNew = false }
    newPurchase?.let { NewPurchaseDialog(state, it) { newPurchase = null } }
    editOrder?.let { EditOrderDialog(state, it) { editOrder = null } }
    deleteOrder?.let { o -> ConfirmDeleteDialog("Borrar ${o.orderRef}", "Se borrarán también sus compras MOCK/locales.", { deleteOrder = null }) { state.deleteOrder(o.id); deleteOrder = null } }
}

@Composable
fun PurchasesScreen(state: AppState) {
    var edit by remember { mutableStateOf<Purchase?>(null) }
    var delete by remember { mutableStateOf<Purchase?>(null) }
    val visible = state.purchases.filter { state.selectedBusinessId == null || it.businessId == state.selectedBusinessId }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Compras", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Autoriza aquí; emite/sincroniza la tarjeta desde la pestaña Tarjetas.")
        }
        if (state.businesses.isNotEmpty()) item { BusinessSelector(state) }
        if (visible.isEmpty()) item { EmptyCard("No hay compras todavía.") }
        items(visible, key = { it.id }) { p ->
            val order = state.orderById(p.orderId)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(p.merchant, fontWeight = FontWeight.Bold); Text("${state.customerName(p.customerId)} · ${order?.orderRef ?: p.orderId}") }
                        Text(money(p.amountCents), fontWeight = FontWeight.Bold)
                        IconButton(onClick = { edit = p }, enabled = p.providerRef.isBlank()) { Icon(Icons.Default.Edit, "Editar") }
                        IconButton(onClick = { delete = p }, enabled = p.providerRef.isBlank()) { Icon(Icons.Default.DeleteOutline, "Borrar") }
                    }
                    if (p.description.isNotBlank()) Text(p.description)
                    Text("${p.provider} · •••• ${p.cardLast4} · ${p.cardStatus}", style = MaterialTheme.typography.bodySmall)
                    Text("${formatDate(p.createdAt)} · ${p.status}", style = MaterialTheme.typography.labelMedium)
                    if (p.providerRef.isBlank()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (p.status) {
                                "AUTHORIZED" -> { Button(onClick = { state.capturePurchase(p.id) }) { Text("Capturar MOCK") }; OutlinedButton(onClick = { state.cancelPurchase(p.id) }) { Text("Cancelar") } }
                                "CAPTURED" -> OutlinedButton(onClick = { state.refundPurchase(p.id) }) { Text("Reembolso MOCK") }
                            }
                        }
                    } else Text("Sincronizada con servidor. Los cambios reales llegan por webhook.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    edit?.let { EditPurchaseDialog(state, it) { edit = null } }
    delete?.let { p -> ConfirmDeleteDialog("Borrar compra", "Se eliminará ${p.merchant} por ${money(p.amountCents)}.", { delete = null }) { state.deletePurchase(p.id); delete = null } }
}

@Composable
fun RulesScreen(state: AppState) {
    var add by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<String?>(null) }
    var delete by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Reglas", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("Comercios permitidos para nuevas compras.") }; FilledTonalIconButton(onClick = { add = true }) { Icon(Icons.Default.Add, "Añadir") } } }
        items(state.allowedMerchants.toList()) { m -> ListItem(headlineContent = { Text(m) }, leadingContent = { Icon(Icons.Default.CheckCircle, null) }, trailingContent = { Row { IconButton(onClick = { edit = m }) { Icon(Icons.Default.Edit, "Editar") }; IconButton(onClick = { delete = m }) { Icon(Icons.Default.DeleteOutline, "Borrar") } } }) }
        item { HorizontalDivider() }
        item { RuleLine("ATM / efectivo", false) }
        item { RuleLine("P2P / transferencias", false) }
        item { RuleLine("Depósitos externos", false) }
        item { RuleLine("Tarjeta temporal por compra", true) }
    }
    if (add) NameDialog("Permitir comercio", "Nombre", "", { add = false }) { state.addAllowedMerchant(it); add = false }
    edit?.let { old -> NameDialog("Editar comercio", "Nombre", old, { edit = null }) { state.updateAllowedMerchant(old, it); edit = null } }
    delete?.let { m -> ConfirmDeleteDialog("Eliminar comercio", "Se quitará $m de la lista.", { delete = null }) { state.removeAllowedMerchant(m); delete = null } }
}

@Composable
private fun NewOrderDialog(state: AppState, onDismiss: () -> Unit) {
    val available = state.customers.filter { state.selectedBusinessId == null || it.businessId == state.selectedBusinessId }
    var customerId by remember { mutableStateOf(available.firstOrNull()?.id ?: "") }
    var paid by remember { mutableStateOf("630.00") }
    var limit by remember { mutableStateOf("600.00") }
    var menu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Nueva orden") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box { OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) { Text(available.firstOrNull { it.id == customerId }?.name ?: "Cliente", Modifier.weight(1f)); Icon(Icons.Default.ArrowDropDown, null) }; DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) { available.forEach { c -> DropdownMenuItem(text = { Text(c.name) }, onClick = { customerId = c.id; menu = false }) } } }
            MoneyField("Pago del cliente", paid) { paid = it }; MoneyField("Máximo autorizado", limit) { limit = it }
            val p = parseMoney(paid); val l = parseMoney(limit); if (p != null && l != null && p >= l) Text("Servicio/diferencia: ${money(p - l)}")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { Button(onClick = { val p = parseMoney(paid); val l = parseMoney(limit); if (p == null || l == null || customerId.isBlank()) error = "Revisa los datos" else if (state.addOrder(customerId, p, l) == null) error = "No se pudo crear" else onDismiss() }) { Text("Crear") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable
private fun EditOrderDialog(state: AppState, order: SpendOrder, onDismiss: () -> Unit) {
    var paid by remember(order.id) { mutableStateOf(decimal(order.paidCents)) }; var limit by remember(order.id) { mutableStateOf(decimal(order.purchaseLimitCents)) }; var msg by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Editar ${order.orderRef}") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { MoneyField("Pago", paid) { paid = it }; MoneyField("Máximo", limit) { limit = it }; msg?.let { Text(it) } } }, confirmButton = { Button(onClick = { val p = parseMoney(paid); val l = parseMoney(limit); msg = if (p == null || l == null) "Revisa montos" else state.updateOrder(order.id, p, l); if (msg == "Orden actualizada") onDismiss() }) { Text("Guardar") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable
private fun NewPurchaseDialog(state: AppState, order: SpendOrder, onDismiss: () -> Unit) {
    var merchant by remember { mutableStateOf(state.allowedMerchants.firstOrNull().orEmpty()) }; var description by remember { mutableStateOf("") }; var amount by remember { mutableStateOf("") }; var menu by remember { mutableStateOf(false) }; var msg by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Nueva compra · ${order.orderRef}") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("Disponible ${money(order.remainingCents)}"); MerchantSelector(state.allowedMerchants, merchant, { merchant = it }, menu) { menu = it }; OutlinedTextField(description, { description = it }, label = { Text("Producto / descripción") }, modifier = Modifier.fillMaxWidth()); MoneyField("Total", amount) { amount = it }; msg?.let { Text(it, color = MaterialTheme.colorScheme.error) } } }, confirmButton = { Button(onClick = { val a = parseMoney(amount); if (a == null) msg = "Monto inválido" else { val r = state.authorizePurchase(order.id, merchant, description, a); if (r == "Autorizada") onDismiss() else msg = r } }) { Text("Autorizar") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable
private fun EditPurchaseDialog(state: AppState, purchase: Purchase, onDismiss: () -> Unit) {
    var merchant by remember(purchase.id) { mutableStateOf(purchase.merchant) }; var description by remember(purchase.id) { mutableStateOf(purchase.description) }; var amount by remember(purchase.id) { mutableStateOf(decimal(purchase.amountCents)) }; var menu by remember { mutableStateOf(false) }; var msg by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Editar compra") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { MerchantSelector(state.allowedMerchants, merchant, { merchant = it }, menu) { menu = it }; OutlinedTextField(description, { description = it }, label = { Text("Descripción") }, modifier = Modifier.fillMaxWidth()); MoneyField("Monto", amount) { amount = it }; msg?.let { Text(it) } } }, confirmButton = { Button(onClick = { val a = parseMoney(amount); msg = if (a == null) "Monto inválido" else state.updatePurchase(purchase.id, merchant, description, a); if (msg?.contains("actualizada") == true) onDismiss() }) { Text("Guardar") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable
private fun MerchantSelector(merchants: List<String>, current: String, onSelect: (String) -> Unit, expanded: Boolean, onExpanded: (Boolean) -> Unit) {
    Box { OutlinedButton(onClick = { onExpanded(true) }, modifier = Modifier.fillMaxWidth()) { Text(if (current.isBlank()) "Seleccionar comercio" else current, Modifier.weight(1f)); Icon(Icons.Default.ArrowDropDown, null) }; DropdownMenu(expanded = expanded, onDismissRequest = { onExpanded(false) }) { merchants.forEach { m -> DropdownMenuItem(text = { Text(m) }, onClick = { onSelect(m); onExpanded(false) }) } } }
}

@Composable
fun NameDialog(title: String, label: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { OutlinedTextField(value, { value = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth()) }, confirmButton = { Button(onClick = { onSave(value.trim()) }, enabled = value.isNotBlank()) { Text("Guardar") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable
fun ConfirmDeleteDialog(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Default.Warning, null) }, title = { Text(title) }, text = { Text(message) }, confirmButton = { Button(onClick = onConfirm) { Text("Borrar") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}

@Composable
fun MoneyField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = { n -> if (n.matches(Regex("^\\d{0,8}([.]\\d{0,2})?$"))) onValueChange(n) }, label = { Text(label) }, prefix = { Text("$") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun MetricCard(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector) { Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null); Spacer(Modifier.width(14.dp)); Column { Text(label); Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) } } } }
@Composable fun EmptyCard(text: String) { Card(Modifier.fillMaxWidth()) { Text(text, Modifier.padding(18.dp)) } }
@Composable private fun RuleLine(name: String, allowed: Boolean) { ListItem(headlineContent = { Text(name) }, trailingContent = { Icon(if (allowed) Icons.Default.Check else Icons.Default.Block, null) }) }

fun parseMoney(value: String): Long? = value.toDoubleOrNull()?.let { (it * 100.0).roundToLong() }?.takeIf { it > 0 }
fun money(cents: Long): String = "$" + String.format(Locale.US, "%.2f", cents / 100.0)
fun decimal(cents: Long): String = String.format(Locale.US, "%.2f", cents / 100.0)
fun formatDate(timestamp: Long): String = SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(timestamp))
