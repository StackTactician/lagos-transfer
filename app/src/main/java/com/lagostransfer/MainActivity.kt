package com.lagostransfer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.lagostransfer.data.*
import com.lagostransfer.data.KeepAlive
import com.lagostransfer.ui.theme.LagosTransferTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

private val nairaFmt by lazy { NumberFormat.getNumberInstance(Locale("en", "NG")) }
private fun naira(v: Long): String = "₦" + nairaFmt.format(v)
private fun nairaAny(v: Long): String = naira(v)
private fun friendly(e: Exception): String {
    val m = e.message ?: ""
    return when {
        "Unable to resolve host" in m || "No address" in m -> "No internet - check connection"
        "timeout" in m.lowercase() -> "Network timeout - try again"
        "401" in m || "Unauthorized" in m -> "Wrong password or session expired"
        "429" in m -> "Too many tries - wait a bit"
        else -> m.take(80).ifBlank { "Something went wrong" }
    }
}

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
        installSplashScreen()
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
    val ctxApp = LocalContext.current
    LaunchedEffect(Unit) {
        try {
            val saved = SecureStore.loadAccounts(ctxApp)
            for ((u, pp, pin) in saved) {
                if (accounts.any { it.username == u }) continue
                val acc = SessionAccount(u, pp, pin, LagosClient())
                try { acc.client.login(com.lagostransfer.data.Account(u, pp, pin)) } catch (e: Exception) { /* silent */ }
                try { acc.balance = acc.client.getBalance() } catch (e: Exception) { /* silent */ }
                accounts.add(acc)
            }
        } catch (e: Exception) { /* silent */ }
    } // null=master, "history"=ledger

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(12.dp))
                Image(painter = painterResource(id = com.lagostransfer.R.drawable.splash_icon), contentDescription = "Eko Aza logo", modifier = Modifier.size(64.dp).padding(start = 16.dp))
                Spacer(Modifier.height(8.dp))
                Text("  EKO AZA", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 16.dp))
                Text("  Transfer only", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp))
                Divider(Modifier.padding(vertical = 12.dp))
                NavigationDrawerItem(
                    label = { Text("Master - Accounts") },
                    selected = selected == null,
                    onClick = { selected = null; scope.launch { drawerState.close() } }
                )
                NavigationDrawerItem(
                    label = { Text("Transfer History") },
                    selected = selected == "history",
                    onClick = { selected = "history"; scope.launch { drawerState.close() } }
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
                    title = { Text(when (selected) { null -> "Master"; "history" -> "Transfer History"; else -> "@$selected" }, fontWeight = FontWeight.SemiBold) },
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
                when (selected) {
                    null -> MasterPage(accounts, snackbarHost, onSelect = { selected = it })
                    "history" -> HistoryPage()
                    else -> {
                        val acc = accounts.find { it.username == selected }
                        if (acc == null) MasterPage(accounts, snackbarHost, onSelect = { selected = it })
                        else AccountPage(acc, snackbarHost)
                    }
                }
            }
        }
    }
}

@Composable
fun MasterPage(accounts: MutableList<SessionAccount>, snacks: SnackbarHostState, onSelect: (String) -> Unit) {
    val ctx = LocalContext.current
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateChecker.Info?>(null) }
    var updating by remember { mutableStateOf(false) }
    var isUpToDate by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            val info = UpdateChecker.latest()
            updateInfo = info
            isUpToDate = info?.let { UpdateChecker.isUpToDate(ctx, it.updatedAt) } ?: false
        } catch (e: Exception) { /* silent */ }
    }

    var keepAliveOk by remember { mutableStateOf(KeepAlive.isIgnoringBattery(ctx)) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) keepAliveOk = KeepAlive.isIgnoringBattery(ctx)
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!keepAliveOk) {
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Keep alive in background", fontWeight = FontWeight.Bold)
                        Text("Long transfers (32s × many) die if system kills app. Allow to run in background - strongest available.", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { KeepAlive.requestIgnoreBattery(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Allow background - persist powerfully") }
                    }
                }
            }
        }
        if (updateInfo != null) {
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (isUpToDate) "App is up to date" else "Update available - ${updateInfo!!.tag}", fontWeight = FontWeight.Bold)
                        Text(if (isUpToDate) "You are on the latest version." else "Tap to download and install the latest version.", style = MaterialTheme.typography.bodySmall)
                        var progress by remember { mutableStateOf(0) }
                        var apkFile by remember { mutableStateOf<java.io.File?>(null) }
                        if (!isUpToDate) {
                            if (apkFile == null) {
                                Button(enabled = !updating, onClick = {
                                    updating = true
                                    progress = 0
                                    scope.launch {
                                        try {
                                            val file = UpdateChecker.download(ctx, updateInfo!!.url) { p -> progress = p }
                                            if (file != null) { apkFile = file } else snacks.showSnackbar("Download failed")
                                        } catch (e: Exception) { snacks.showSnackbar("Update failed: ${e.message}") }
                                        updating = false
                                    }
                                }) { Text(if (updating) "Downloading…" else "Update now") }
                                if (updating) {
                                    Spacer(Modifier.height(6.dp))
                                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp))
                                    Text("${progress}%", style = MaterialTheme.typography.labelSmall)
                                }
                            } else {
                                Text("Downloaded - ready to install", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                                LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth().height(4.dp))
                                Spacer(Modifier.height(6.dp))
                                Button(onClick = { apkFile?.let { UpdateChecker.install(ctx, it) } }, modifier = Modifier.fillMaxWidth()) { Text("Install now") }
                            }
                        }
                    }
                }
            }
        }
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
                            snacks.showSnackbar("Could not log in @${acc.username} - check username/password")
                            busy = false
                            return@launch
                        }
                        try {
                            acc.balance = acc.client.getBalance()
                            snacks.showSnackbar("Logged in @${acc.username} - ${naira(acc.balance!!.money)}")
                        } catch (_: Exception) {
                            snacks.showSnackbar("Logged in @${acc.username}")
                        }
                        SecureStore.saveAccount(ctx, acc.username, acc.password, acc.pin)
                        accounts.add(acc)
                        username = ""; password = ""; pin = ""
                        busy = false
                    }
                }
            ) { if (busy) CircularProgressIndicator(modifier = androidx.compose.ui.Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Log in & add") }
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
                            acc.balance?.let { "${naira(it.money)}  ·  Can send ${it.canSend?.let { c -> naira(c) } ?: "-"}" } ?: "Tap to refresh balance",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onSelect(acc.username) }) { Text("Open") }
                            OutlinedButton(onClick = {
                                scope.launch {
                                    try {
                                        acc.balance = acc.client.getBalance()
                                        snacks.showSnackbar("@${acc.username} - ${naira(acc.balance!!.money)}")
                                    } catch (e: Exception) { snacks.showSnackbar(friendly(e as Exception)) }
                                }
                            }) { Text("Refresh") }
                            TextButton(onClick = { SecureStore.removeAccount(ctx, acc.username); accounts.remove(acc) }) { Text("Log out") }
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
    val db = remember { LogDb(ctx) }
    var refreshing by remember { mutableStateOf(false) }
    var recipient by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("10000000000") }
    var times by remember { mutableStateOf("1") }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var currentJob by remember { mutableStateOf<Job?>(null) }
    var log by remember { mutableStateOf(db.all(acc.username)) }
    val scope = rememberCoroutineScope()
    var balanceText by remember { mutableStateOf(acc.balance?.let { naira(it.money) } ?: "-") }
    var canSendText by remember { mutableStateOf(acc.balance?.canSend?.let { naira(it) } ?: "-") }

    fun add(s: String) { log = listOf(s) + log.take(100) }

    LaunchedEffect(acc.username) {
        try {
            val b = acc.client.getBalance()
            acc.balance = b
            balanceText = naira(b.money)
            canSendText = b.canSend?.let { naira(it) } ?: "-"
        } catch (e: Exception) { /* silent */ }
        log = db.all(acc.username)
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
                    OutlinedButton(enabled = !refreshing, onClick = {
                        scope.launch {
                            refreshing = true
                            try {
                                val b = acc.client.getBalance()
                                acc.balance = b
                                balanceText = naira(b.money)
                                canSendText = b.canSend?.let { naira(it) } ?: "-"
                            } catch (e: Exception) { }
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    enabled = !busy && acc.enabled && canSend,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (!acc.enabled) return@Button
                        log = emptyList()
                        busy = true
                        val job = scope.launch {
                            val toId = try { acc.client.resolveUsername(recipient) } catch (e: Exception) { add("✗ " + friendly(e as Exception)); null }
                            if (toId == null) {
                                val rem = t
                                db.insert(acc.username, recipient, amt, "not_found", 0, rem, amt * rem)
                                add("✗ @$recipient not found · 0/$t · ${naira(amt * rem)} left")
                                log = db.all(acc.username)
                                busy = false
                                return@launch
                            }
                            TransferNotify.ensureChannel(ctx)
                            try { ContextCompat.startForegroundService(ctx, Intent(ctx, KeepAliveService::class.java)) } catch (e: Exception) { /* silent */ }
                            var okCount = 0
                            for (i in 1..t) {
                                if (!acc.enabled) { add("⏸ paused at $i/$t"); break }
                                TransferNotify.showProgress(ctx, acc.username, i, t, amt, "Sending $i/$t to @$recipient")
                                var status = "fail"
                                try {
                                    val r = acc.client.sendMoney(toId, amt, note.ifBlank { null }, acc.pin)
                                    if (r.optBoolean("ok", false)) {
                                        okCount++
                                        status = "sent"
                                        val left = t - i
                                        val amtLeft = amt * left
                                        db.insert(acc.username, recipient, amt, status, i, left, amtLeft)
                                        add("✓ $i/$t ${naira(amt)} → @$recipient · $i sent, $left left, ${naira(amtLeft)} left")
                                    } else {
                                        val msg = r.optString("error").ifBlank { "Could not send" }
                                        val friendly = when {
                                            msg.contains("fund", true) -> "Not enough funds"
                                            msg.contains("limit", true) -> "Daily limit reached"
                                            msg.contains("frozen", true) -> "Account frozen"
                                            msg.contains("pin", true) -> "PIN wrong"
                                            else -> msg.take(80)
                                        }
                                        val left = t - i
                                        db.insert(acc.username, recipient, amt, friendly, i-1, left+1, amt*(left+1))
                                        add("✗ $i/$t - $friendly · ${i-1} sent, ${left+1} left")
                                        TransferNotify.showError(ctx, acc.username, friendly)
                                    }
                                } catch (e: Exception) {
                                    val left = t - i
                                    db.insert(acc.username, recipient, amt, "error", i-1, left+1, amt*(left+1))
                                    add("✗ $i/$t - ${e.message?.take(60) ?: "error"}")
                                }
                                log = db.all(acc.username)
                                if (i < t) delay(32_000)
                            }
                            try {
                                val b = acc.client.getBalance()
                                acc.balance = b
                                balanceText = naira(b.money)
                                canSendText = b.canSend?.let { naira(it) } ?: "-"
                            } catch (e: Exception) { /* silent */ }
                            try { ctx.stopService(Intent(ctx, KeepAliveService::class.java)) } catch (e: Exception) { /* silent */ }
                            TransferNotify.showDone(ctx, acc.username, okCount, t)
                            busy = false
                        }
                        currentJob = job
                        job.invokeOnCompletion { currentJob = null; busy = false }
                    }
                ) {
                    val tInt = times.toIntOrNull() ?: 0
                    Text(if (busy) "Sending… 32s gap" else if (!acc.enabled) "Paused" else "Send ${if (tInt>0) "${tInt}× " else ""}${if (amt>0) naira(amt) else ""} to @$recipient".trim())
                }
                if (busy) {
                    OutlinedButton(onClick = { currentJob?.cancel(); busy = false; add("■ Stopped by user") }) { Text("Stop") }
                }
            }
        }
        item { Divider() }
        item { Text("Activity", fontWeight = FontWeight.SemiBold) }
        items(log) { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun HistoryPage() {
    val ctx = LocalContext.current
    val db = remember { LogDb(ctx) }
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    var entries by remember { mutableStateOf(listOf<LogEntry>()) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun refresh() { loading = true; try { entries = db.history(search.ifBlank { null }, filter) } catch (e: Exception) { entries = emptyList() } finally { loading = false } }
    LaunchedEffect(Unit) { refresh() }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Transfer History", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
        Text("All transfers saved on this phone. Search by account or recipient.", style = MaterialTheme.typography.labelSmall)
        OutlinedTextField(search, { search = it }, label = { Text("Search") }, placeholder = { Text("username, account…") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All","sent","not_found","error").forEach { f ->
                FilterChip(selected = filter == f, onClick = { filter = f; refresh() }, label = { Text(f) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { refresh() }) { if (loading) CircularProgressIndicator(modifier = androidx.compose.ui.Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Search") }
            TextButton(onClick = { search = ""; filter = "All"; refresh() }) { Text("Clear") }
        }
        if (loading) { LinearProgressIndicator(modifier = androidx.compose.ui.Modifier.fillMaxWidth().height(4.dp)) }
        Divider()
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            if (entries.isEmpty()) item { Text("No transfers yet.", style = MaterialTheme.typography.bodySmall) }
            items(entries) { e ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("@${e.account} → @${e.recipient} · ${nairaAny(e.amount)} · ${e.status}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                        Text("${e.transferred} sent, ${e.remaining} left, ${nairaAny(e.amountLeft)} left · ${java.text.SimpleDateFormat("MMM dd HH:mm", Locale.getDefault()).format(java.util.Date(e.ts))}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
