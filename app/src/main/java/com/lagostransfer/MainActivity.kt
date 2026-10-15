package com.lagostransfer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lagostransfer.data.*
import com.lagostransfer.ui.theme.LagosTransferTheme
import kotlinx.coroutines.delay
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
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        setContent {
            LagosTransferTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val accounts = remember { mutableStateListOf<SessionAccount>() }
    var selected by remember { mutableStateOf<String?>(null) }

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
    val ctx = LocalContext.current
    var recipient by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("10000000000") }
    var times by remember { mutableStateOf("1") }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf(listOf<String>()) }
    val scope = rememberCoroutineScope()
    var balanceText by remember { mutableStateOf(acc.balance?.let { naira(it.money) } ?: "—") }
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
                                snacks.showSnackbar("Balance $balanceText")
                            } catch (e: Exception) { snacks.showSnackbar("Could not refresh") }
                        }
                    }) { Text("Refresh balance") }
                }
            }
        }
        item {
            Text("Send money", fontWeight = FontWeight.Bold)
            Text("Single recipient · 32s between sends · stacked notifications", style = MaterialTheme.typography.labelSmall)
        }
        item {
            OutlinedTextField(recipient, { recipient = it.trim() }, label = { Text("Recipient username") }, placeholder = { Text("e.g. adewale") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        item {
            OutlinedTextField(
                value = amount,
                onValueChange = { v -> amount = v.filter { it.isDigit() }.take(13) },
                label = { Text("Amount") },
                prefix = { Text("₦") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            val amt = amount.toLongOrNull() ?: 0
            Text(
                when {
                    amt == 0L -> ""
                    amt < 100 -> "Minimum ₦100"
                    amt > 10_000_000_000 -> "Maximum ₦10,000,000,000"
                    else -> "Will send ${naira(amt)} per transfer"
                },
                style = MaterialTheme.typography.labelSmall
            )
        }
        item {
            OutlinedTextField(
                value = times,
                onValueChange = { v -> times = v.filter { it.isDigit() }.take(3) },
                label = { Text("How many times") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            val t = times.toIntOrNull() ?: 0
            val amt = amount.toLongOrNull() ?: 0
            if (t > 0 && amt in 100..10_000_000_000) {
                Text("Total ${naira(amt * t)} + fees · ${(t - 1) * 32}s total wait", style = MaterialTheme.typography.labelSmall)
            }
        }
        item {
            OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        item {
            val amt = amount.toLongOrNull() ?: 0
            val t = times.toIntOrNull() ?: 0
            val canSend = amt in 100..10_000_000_000 && t in 1..999 && recipient.isNotBlank()
            Button(
                enabled = !busy && acc.enabled && canSend,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (!acc.enabled) { scope.launch { snacks.showSnackbar("Transfers paused for @${acc.username}") }; return@Button }
                    busy = true
                    scope.launch {
                        snacks.showSnackbar("Resolving @$recipient…")
                        val toId = try { acc.client.resolveUsername(recipient) } catch (e: Exception) { null }
                        if (toId == null) {
                            snacks.showSnackbar("Could not find @$recipient")
                            add("✗ @$recipient not found")
                            busy = false
                            return@launch
                        }
                        TransferNotify.ensureChannel(ctx)
                        var okCount = 0
                        for (i in 1..t) {
                            if (!acc.enabled) { add("⏸ paused at $i/$t"); break }
                            TransferNotify.showProgress(ctx, acc.username, i, t, amt, "Sending ${i}/${t}")
                            snacks.showSnackbar("Sending ${i}/${t} → @$recipient ${naira(amt)}")
                            try {
                                val r = acc.client.sendMoney(toId, amt, note.ifBlank { null }, acc.pin)
                                if (r.optBoolean("ok", false)) {
                                    okCount++
                                    add("✓ ${i}/${t} ${naira(amt)} → @$recipient")
                                } else {
                                    val msg = r.optString("error").ifBlank { "Could not send" }
                                    val friendly = when {
                                        msg.contains("fund", true) -> "Not enough funds"
                                        msg.contains("limit", true) -> "Daily limit reached"
                                        msg.contains("frozen", true) -> "Account frozen"
                                        msg.contains("pin", true) -> "PIN wrong"
                                        else -> msg.take(80)
                                    }
                                    add("✗ ${i}/${t} — $friendly")
                                    TransferNotify.showError(ctx, acc.username, friendly)
                                }
                            } catch (e: Exception) {
                                add("✗ ${i}/${t} — ${e.message?.take(60) ?: "error"}")
                            }
                            if (i < t) delay(32_000)
                        }
                        try {
                            val b = acc.client.getBalance()
                            acc.balance = b
                            balanceText = naira(b.money)
                            canSendText = b.canSend?.let { naira(it) } ?: "—"
                        } catch (_: Exception) {}
                        TransferNotify.showDone(ctx, acc.username, okCount, t)
                        snacks.showSnackbar("Done — $okCount/$t sent from @${acc.username} to @$recipient")
                        busy = false
                    }
                }
            ) {
                val tInt = times.toIntOrNull() ?: 0
                Text(if (busy) "Sending… 32s gap" else if (!acc.enabled) "Paused" else "Send ${if (tInt>0) "${tInt}× " else ""}${if (amt>0) naira(amt) else ""} to @$recipient".trim())
            }
        }
        item { Divider() }
        item { Text("Activity", fontWeight = FontWeight.SemiBold) }
        items(log) { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
