package com.lagostransfer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lagostransfer.data.*
import com.lagostransfer.ui.theme.LagosTransferTheme
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

private fun naira(v: Long): String = "₦" + NumberFormat.getNumberInstance(Locale("en", "NG")).format(v)

data class SessionAccount(
    val username: String,
    val password: String,
    val pin: String?,
    val client: LagosClient,
    var balance: Balance? = null,
    var enabled: Boolean = true
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LagosTransferTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App()
                }
            }
        }
    }
}

@Composable
fun App() {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val accounts = remember { mutableStateListOf<SessionAccount>() }
    var selected by remember { mutableStateOf<String?>(null) } // null = master

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(16.dp))
                Text("  LAGOS TRANSFER", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 16.dp))
                Text("  Transfer only", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp))
                Divider(Modifier.padding(vertical = 12.dp))
                NavigationDrawerItem(
                    label = { Text("Master — Accounts") },
                    selected = selected == null,
                    onClick = { selected = null; scope.launch { drawerState.close() } }
                )
                Divider(Modifier.padding(vertical = 8.dp))
                if (accounts.isEmpty()) {
                    Text("  No accounts yet", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
                } else {
                    accounts.forEach { acc ->
                        NavigationDrawerItem(
                            label = { Text("@${acc.username}") },
                            selected = selected == acc.username,
                            onClick = { selected = acc.username; scope.launch { drawerState.close() } }
                        )
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(if (selected == null) "Master" else "@$selected", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Text("☰", style = MaterialTheme.typography.titleLarge)
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHost) }
        ) { pad ->
            Box(Modifier.padding(pad)) {
                if (selected == null) {
                    MasterPage(accounts, snackbarHost, onSelect = { selected = it })
                } else {
                    val acc = accounts.find { it.username == selected }
                    if (acc == null) MasterPage(accounts, snackbarHost, onSelect = { selected = it })
                    else AccountPage(acc, snackbarHost)
                }
            }
        }
    }
}

@Composable
fun MasterPage(accounts: MutableList<SessionAccount>, snacks: SnackbarHostState, onSelect: (String) -> Unit) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("Add account", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(username, { username = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(password, { password = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(pin, { pin = it }, label = { Text("PIN (if you set one)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation())
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = !busy && username.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (accounts.any { it.username == username.trim() }) {
                        scope.launch { snacks.showSnackbar("Account @${username.trim()} already added") }
                        return@Button
                    }
                    busy = true
                    scope.launch {
                        val acc = SessionAccount(username.trim(), password, pin.ifBlank { null }, LagosClient())
                        val ok = try { acc.client.login(com.lagostransfer.data.Account(acc.username, acc.password, acc.pin)) } catch (e: Exception) { false }
                        if (!ok) {
                            snacks.showSnackbar("Could not log in @${acc.username} — check username/password")
                            busy = false
                            return@launch
                        }
                        try {
                            acc.balance = acc.client.getBalance()
                            snacks.showSnackbar("Logged in @${acc.username} — ${naira(acc.balance!!.money)}")
                        } catch (_: Exception) {
                            snacks.showSnackbar("Logged in @${acc.username}")
                        }
                        accounts.add(acc)
                        username = ""; password = ""; pin = ""
                        busy = false
                    }
                }
            ) { Text(if (busy) "Logging in…" else "Log in & add") }
        }
        item { Divider() }
        item { Text("Accounts (${accounts.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
        if (accounts.isEmpty()) {
            item { Text("No accounts yet. Add one above. Each account gets its own tab in the menu.", style = MaterialTheme.typography.bodySmall) }
        } else {
            items(accounts, key = { it.username }) { acc ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("@${acc.username}", fontWeight = FontWeight.Bold)
                            Badge { Text(if (acc.enabled) "ON" else "OFF") }
                        }
                        Text(
                            acc.balance?.let { "${naira(it.money)}  ·  Can send ${it.canSend?.let { c -> naira(c) } ?: "—"}" } ?: "Tap to refresh balance",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onSelect(acc.username) }) { Text("Open") }
                            OutlinedButton(onClick = {
                                scope.launch {
                                    try {
                                        acc.balance = acc.client.getBalance()
                                        snacks.showSnackbar("@${acc.username} — ${naira(acc.balance!!.money)}")
                                    } catch (e: Exception) { snacks.showSnackbar("Could not refresh @${acc.username}") }
                                }
                            }) { Text("Refresh") }
                            TextButton(onClick = { accounts.remove(acc) }) { Text("Log out") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AccountPage(acc: SessionAccount, snacks: SnackbarHostState) {
    var bulk by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf(listOf<String>()) }
    val scope = rememberCoroutineScope()
    var balanceText by remember { mutableStateOf(acc.balance?.let { "${naira(it.money)}" } ?: "—") }
    var canSendText by remember { mutableStateOf(acc.balance?.canSend?.let { naira(it) } ?: "—") }

    fun add(s: String) { log = listOf(s) + log.take(80) }

    LaunchedEffect(acc.username) {
        try {
            val b = acc.client.getBalance()
            acc.balance = b
            balanceText = naira(b.money)
            canSendText = b.canSend?.let { naira(it) } ?: "—"
        } catch (_: Exception) {}
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("@${acc.username}", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                    Text(balanceText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                    Text("Balance", style = MaterialTheme.typography.labelSmall)
                    Divider()
                    Text("Can send today: $canSendText", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Transfers")
                        Switch(checked = acc.enabled, onCheckedChange = { acc.enabled = it })
                        Text(if (acc.enabled) "Enabled" else "Paused", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(onClick = {
                        scope.launch {
                            try {
                                val b = acc.client.getBalance()
                                acc.balance = b
                                balanceText = naira(b.money)
                                canSendText = b.canSend?.let { naira(it) } ?: "—"
                                snacks.showSnackbar("Balance ${balanceText}")
                            } catch (e: Exception) { snacks.showSnackbar("Could not refresh") }
                        }
                    }) { Text("Refresh balance") }
                }
            }
        }
        item {
            Text("Send money", fontWeight = FontWeight.Bold)
            Text("Each transfer is for this account only. Add one recipient per line, or use Amount + Note below.", style = MaterialTheme.typography.labelSmall)
        }
        item {
            OutlinedTextField(
                value = amount,
                onValueChange = { v -> amount = v.filter { it.isDigit() }.take(13) },
                label = { Text("Amount (e.g. 5000)") },
                prefix = { Text("₦") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            if (amount.isNotBlank()) {
                val amt = amount.toLongOrNull() ?: 0
                if (amt > 0) Text("Will send ${naira(amt)} ${if (amt < 100) "— minimum ₦100" else if (amt > 10_000_000_000) "— max ₦10,000,000,000" else ""}", style = MaterialTheme.typography.labelSmall)
            }
        }
        item {
            OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        item {
            OutlinedTextField(
                bulk, { bulk = it },
                label = { Text("Recipients — one username or ID per line") },
                placeholder = { Text("ade3cd3578d0433aa2ea\njohn_doe") },
                modifier = Modifier.fillMaxWidth().height(110.dp)
            )
        }
        item {
            Button(
                enabled = !busy && acc.enabled && bulk.isNotBlank() && (amount.toLongOrNull() ?: 0) in 100..10_000_000_000,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (!acc.enabled) { scope.launch { snacks.showSnackbar("Transfers paused for @${acc.username}") }; return@Button }
                    val recipients = bulk.lines().map { it.trim() }.filter { it.isNotBlank() }
                    val amt = amount.toLongOrNull() ?: return@Button
                    if (recipients.isEmpty()) return@Button
                    busy = true
                    scope.launch {
                        var okCount = 0
                        for (to in recipients) {
                            try {
                                val r = acc.client.sendMoney(to, amt, note.ifBlank { null }, acc.pin)
                                if (r.optBoolean("ok", false)) {
                                    okCount++
                                    val fee = r.optLong("fee", 0)
                                    add("✓ ${naira(amt)} → $to ${if (fee>0) "(fee ${naira(fee)})" else ""}")
                                } else {
                                    val msg = r.optString("error").ifBlank { "Could not send" }
                                    val friendly = when {
                                        msg.contains("fund", true) -> "Not enough funds (needs ${naira(amt)} + fee)"
                                        msg.contains("limit", true) -> "Daily limit reached — try tomorrow"
                                        msg.contains("frozen", true) -> "Account frozen — try later"
                                        msg.contains("pin", true) -> "PIN required or wrong"
                                        else -> msg.take(80)
                                    }
                                    add("✗ $to — $friendly")
                                }
                            } catch (e: Exception) { add("✗ $to — ${e.message?.take(60) ?: "error"}") }
                        }
                        try {
                            val b = acc.client.getBalance()
                            acc.balance = b
                            balanceText = naira(b.money)
                            canSendText = b.canSend?.let { naira(it) } ?: "—"
                        } catch (_: Exception) {}
                        snacks.showSnackbar("Done — $okCount of ${recipients.size} sent from @${acc.username}")
                        busy = false
                    }
                }
            ) { Text(if (busy) "Sending…" else if (!acc.enabled) "Paused" else "Send ${bulk.lines().count { it.isNotBlank() }} transfer(s)") }
        }
        item { Divider() }
        item { Text("Activity", fontWeight = FontWeight.SemiBold) }
        items(log) { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
