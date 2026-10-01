package com.spendcore.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SpendCoreApp() }
    }
}

@Composable
fun SpendCoreApp() {
    val context = LocalContext.current.applicationContext
    val state = remember(context) { AppState(context) }
    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(title = {
                    Column {
                        Text("SpendCore", fontWeight = FontWeight.Bold)
                        Text("v0.4 · cards sync", style = MaterialTheme.typography.labelSmall)
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
                        Triple("Tarjetas", Icons.Default.CreditCard, 4),
                        Triple("Reglas", Icons.Default.Rule, 5),
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
                    4 -> CardsScreen(state)
                    else -> RulesScreen(state)
                }
            }
        }
    }
}
