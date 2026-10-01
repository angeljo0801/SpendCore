package com.spendcore.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SpendCoreApp() }
    }
}

data class Budget(
    val id: String,
    val customer: String,
    val orderRef: String,
    val limitCents: Long,
    var usedCents: Long = 0,
    var reservedCents: Long = 0
) {
    val remainingCents: Long get() = (limitCents - usedCents - reservedCents).coerceAtLeast(0)
}

data class Purchase(
    val id: String,
    val customer: String,
    val merchant: String,
    val amountCents: Long,
    val status: String,
    val cardLabel: String
)

class AppState {
    var selectedTab by mutableIntStateOf(0)
    val budgets = mutableStateListOf(
        Budget("BG-1001", "Juan", "AC-1048", 60000, 18532),
        Budget("BG-1002", "María", "AC-1052", 85000, 21999)
    )
    val purchases = mutableStateListOf(
        Purchase("P-1001", "Juan", "Amazon", 18532, "CAPTURED", "•••• 2041 · closed"),
        Purchase("P-1002", "María", "Walmart", 21999, "CAPTURED", "•••• 8831 · closed")
    )
    val allowedMerchants = mutableStateListOf("Amazon", "Walmart", "Shein", "Temu")

    fun approveDemoPurchase(budget: Budget, merchant: String, amountCents: Long): Boolean {
        if (amountCents <= 0 || amountCents > budget.remainingCents) return false
        if (merchant !in allowedMerchants) return false
        budget.reservedCents += amountCents
        val index = budgets.indexOfFirst { it.id == budget.id }
        if (index >= 0) budgets[index] = budget.copy(reservedCents = budget.reservedCents)
        purchases.add(0, Purchase(
            id = "P-${UUID.randomUUID().toString().take(8).uppercase()}",
            customer = budget.customer,
            merchant = merchant,
            amountCents = amountCents,
            status = "AUTHORIZED",
            cardLabel = "single-use · provider pending"
        ))
        return true
    }
}

@Composable
fun SpendCoreApp() {
    val state = remember { AppState() }
    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("SpendCore", fontWeight = FontWeight.Bold) }) },
            bottomBar = {
                NavigationBar {
                    listOf(
                        Triple("Dashboard", Icons.Default.Dashboard, 0),
                        Triple("Budgets", Icons.Default.AccountBalanceWallet, 1),
                        Triple("Purchases", Icons.Default.ShoppingCart, 2),
                        Triple("Rules", Icons.Default.Rule, 3)
                    ).forEach { (label, icon, index) ->
                        NavigationBarItem(
                            selected = state.selectedTab == index,
                            onClick = { state.selectedTab = index },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (state.selectedTab) {
                    0 -> DashboardScreen(state)
                    1 -> BudgetsScreen(state)
                    2 -> PurchasesScreen(state)
                    else -> RulesScreen(state)
                }
            }
        }
    }
}

@Composable
private fun DashboardScreen(state: AppState) {
    val authorized = state.budgets.sumOf { it.limitCents }
    val used = state.budgets.sumOf { it.usedCents }
    val reserved = state.budgets.sumOf { it.reservedCents }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("Business spend control", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { Text("These figures are internal purchase authority, not customer deposit balances.") }
        item { MetricCard("Authorized", money(authorized), Icons.Default.Speed) }
        item { MetricCard("Used", money(used), Icons.Default.ReceiptLong) }
        item { MetricCard("Reserved", money(reserved), Icons.Default.LockClock) }
        item { MetricCard("Remaining authority", money(authorized - used - reserved), Icons.Default.VerifiedUser) }
        item { Text("Provider mode: MOCK · no real cards or money movement", style = MaterialTheme.typography.bodyMedium) }
    }
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
private fun BudgetsScreen(state: AppState) {
    var message by remember { mutableStateOf<String?>(null) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("Purchase budgets", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { Text("A budget is a business authorization limit attached to a service/order. It is not a wallet.") }
        items(state.budgets, key = { it.id }) { budget ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(budget.customer, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Order ${budget.orderRef} · ${budget.id}")
                    LinearProgressIndicator(
                        progress = { (budget.usedCents + budget.reservedCents).toFloat() / budget.limitCents.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Limit ${money(budget.limitCents)} · Remaining ${money(budget.remainingCents)}")
                    Button(onClick = {
                        val amount = minOf(2500, budget.remainingCents)
                        message = if (state.approveDemoPurchase(budget, "Amazon", amount))
                            "Authorized ${money(amount)} for Amazon"
                        else "Request declined by rules"
                    }, enabled = budget.remainingCents > 0) {
                        Text("Test $25 purchase")
                    }
                }
            }
        }
        message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
    }
}

@Composable
private fun PurchasesScreen(state: AppState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Text("Purchase requests", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        items(state.purchases, key = { it.id }) { purchase ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(purchase.merchant, fontWeight = FontWeight.Bold)
                        Text("${purchase.customer} · ${purchase.id}")
                        Text(purchase.cardLabel, style = MaterialTheme.typography.bodySmall)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(money(purchase.amountCents), fontWeight = FontWeight.Bold)
                        Text(purchase.status, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun RulesScreen(state: AppState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Text("Spend rules", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { Text("Default policy") }
        items(state.allowedMerchants) { merchant ->
            ListItem(
                headlineContent = { Text(merchant) },
                supportingContent = { Text("Allowed merchant") },
                leadingContent = { Icon(Icons.Default.CheckCircle, contentDescription = null) }
            )
        }
        item { HorizontalDivider() }
        item { RuleLine("ATM / cash withdrawal", false) }
        item { RuleLine("P2P / money transfer", false) }
        item { RuleLine("External deposits", false) }
        item { RuleLine("Transfer budget to another customer", false) }
        item { RuleLine("Single-purpose purchase card intents", true) }
    }
}

@Composable
private fun RuleLine(name: String, allowed: Boolean) {
    ListItem(
        headlineContent = { Text(name) },
        trailingContent = {
            Icon(
                if (allowed) Icons.Default.Check else Icons.Default.Block,
                contentDescription = if (allowed) "Allowed" else "Blocked"
            )
        }
    )
}

private fun money(cents: Long): String = "$" + "%.2f".format(cents / 100.0)
