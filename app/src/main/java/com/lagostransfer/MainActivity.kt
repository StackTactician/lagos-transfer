package com.lagostransfer

import android.Manifest
import android.content.Intent
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.lagostransfer.data.*
import com.lagostransfer.ui.theme.LagosTransferTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

private val nairaFmt by lazy { NumberFormat.getNumberInstance(Locale("en", "NG")) }
private fun naira(v: Long): String = "₦" + nairaFmt.format(v)

private fun friendly(e: Exception): String {
    val m = e.message ?: ""
    return when {
        "Unable to resolve host" in m || "No address" in m -> "No internet - check connection"
        "timeout" in m.lowercase() -> "Network timeout - retrying"
        m.contains("401") || m.contains("Session expired") -> "Session expired - logging in again"
        m.contains("429") -> "Too many tries - waiting"
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
    var isSyncing by remember { mutableStateOf(true) }
    val ctxApp = LocalContext.current

    LaunchedEffect(Unit) {
        try {
            val saved = SecureStore.loadAccounts(ctxApp)
            val deferred = saved.map { (u, pp, pin) ->
                if (accounts.any { it.username == u }) return@map null
                val cli = LagosClient(u, pp, pin)
                val cookies = SecureStore.loadCookies(ctxApp, u)
                if (cookies.isNotEmpty()) cli.restoreCookies(cookies)
                val acc = SessionAccount(u, pp, pin, cli)
                accounts.add(acc)
                async(Dispatchers.IO) {
                    val ok = if (cli.hasSession()) runCatching { cli.getBalance(); true }.getOrDefault(false) else false
                    if (!ok) { if (cli.login()) SecureStore.saveCookies(ctxApp, u, cli.exportCookies()) }
                    runCatching { acc.balance = cli.getBalance() }
                    SecureStore.saveCookies(ctxApp, u, cli.exportCookies())
                }
            }.filterNotNull()
            deferred.forEach { try { it.await() } catch (_: Exception) {} }
        } catch (_: Exception) {} finally { isSyncing = false }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(12.dp))
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(id = com.lagostransfer.R.drawable.splash_icon),
                    contentDescription = "Eko Aza logo", modifier = Modifier.size(64.dp).padding(start = 16.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text("  EKO AZA", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 16.dp))
                Text("  Transfer only", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp))
                Divider(Modifier.padding(vertical = 12.dp))
                NavigationDrawerItem(label = { Text("Master - Accounts") }, selected = selected == null, onClick = { selected = null; scope.launch { drawerState.close() } })
                NavigationDrawerItem(label = { Text("Transfer History") }, selected = selected == "history", onClick = { selected = "history"; scope.launch { drawerState.close() } })
                Divider(Modifier.padding(vertical = 8.dp))
                if (accounts.isEmpty()) {
                    Text("  No accounts yet", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
                } else {
                    accounts.forEach { acc ->
                        NavigationDrawerItem(label = { Text("@${acc.username}") }, selected = selected == acc.username, onClick = { selected = acc.username; scope.launch { drawerState.close() } })
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
                        IconButton(onClick = { scope.launch { drawerState.open() } }) { Text("☰", style = MaterialTheme.typography.titleLarge) }
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
                if (isSyncing) {
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
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
    var progress by remember { mutableStateOf(0) }
    var apkFile by remember { mutableStateOf<java.io.File?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            val info = UpdateChecker.latest()
            updateInfo = info
            isUpToDate = info?.let { UpdateChecker.isUpToDate(ctx, it.updatedAt) } ?: false
        } catch (_: Exception) {}
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
                        Text("Long transfers die if the phone sleeps. Allow to run in background.", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { KeepAlive.requestIgnoreBattery(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Allow background") }
                    }
                }
            }
        }
        if (updateInfo != null) {
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (isUpToDate) "App is up to date" else "Update available", fontWeight = FontWeight.Bold)
                        Text(if (isUpToDate) "You are on the latest version." else "Tap to download and install the latest version.", style = MaterialTheme.typography.bodySmall)
                        if (!isUpToDate) {
                            if (apkFile == null) {
                                Button(enabled = !updating, onClick = {
                                    scope.launch {
                                        updating = true
                                        progress = 0
                                        try {
                                            val dmId = UpdateChecker.enqueue(ctx, updateInfo!!.url)
                                            while (true) {
                                                val (pct, status) = UpdateChecker.queryProgress(ctx, dmId)
                                                progress = pct
                                                if (status == android.app.DownloadManager.STATUS_SUCCESSFUL) {
                                                    val file = UpdateChecker.fileForId(ctx, dmId)
                                                    if (file != null && file.exists()) apkFile = file else snacks.showSnackbar("Download failed")
                                                    break
                                                } else if (status == android.app.DownloadManager.STATUS_FAILED) {
                                                    snacks.showSnackbar("Download failed"); break
                                                }
                                                delay(500)
                                            }
                                        } catch (e: Exception) { snacks.showSnackbar(friendly(e as Exception)) }
                                        updating = false
                                    }
                                }) { Text(if (updating) "Downloading" else "Update now") }
                                if (updating) {
                                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp))
                                    Text("${progress}%", style = MaterialTheme.typography.labelSmall)
                                }
                            } else {
                                Text("Downloaded - ready to install", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
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
                        val cli = LagosClient(username.trim(), password, pin.ifBlank { null })
                        val ok = try { cli.login() } catch (e: Exception) { false }
                        if (!ok) {
                            snacks.showSnackbar("Could not log in - check username and password")
                            busy = false
                            return@launch
                        }
                        val acc = SessionAccount(username.trim(), password, pin.ifBlank { null }, cli)
                        var balText = ""
                        try {
                            acc.balance = cli.getBalance()
                            balText = " - ${naira(acc.balance!!.money)}"
                        } catch (_: Exception) {}
                        SecureStore.saveAccount(ctx, acc.username, acc.password, acc.pin)
                        SecureStore.saveCookies(ctx, acc.username, cli.exportCookies())
                        accounts.add(acc)
                        snacks.showSnackbar("Logged in @${acc.username}$balText")
                        username = ""; password = ""; pin = ""
                        busy = false
                    }
                }
            ) { if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Log in and add") }
        }
        item { Divider() }
        item { Text("Accounts (${accounts.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
        if (accounts.isEmpty()) {
            item { Text("No accounts yet. Add one above. Each account gets its own tab.", style = MaterialTheme.typography.bodySmall) }
        } else {
            items(accounts, key = { it.username }) { acc ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("@${acc.username}", fontWeight = FontWeight.Bold)
                            Badge { Text(if (acc.enabled) "ON" else "OFF") }
                        }
                        Text(
                            acc.balance?.let { "${naira(it.money)}  -  Can send ${it.canSend?.let { c -> naira(c) } ?: "unknown"}" } ?: "Tap refresh for balance",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onSelect(acc.username) }) { Text("Open") }
                            OutlinedButton(onClick = {
                                scope.launch {
                                    try {
                                        acc.balance = acc.client.getBalance()
                                        SecureStore.saveCookies(ctx, acc.username, acc.client.exportCookies())
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
    val scope = rememberCoroutineScope()
    var recipient by remember { mutableStateOf("") }
    var resolvedName by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("10000000000") }
    var times by remember { mutableStateOf("1") }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var currentJob by remember { mutableStateOf<Job?>(null) }
    val stopFlag = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var log by remember { mutableStateOf(db.all(acc.username)) }
    var balanceText by remember { mutableStateOf(acc.balance?.let { naira(it.money) } ?: "-") }
    var canSendText by remember { mutableStateOf(acc.balance?.canSend?.let { naira(it) } ?: "-") }
    var statusText by remember { mutableStateOf("") }

    fun add(s: String) { log = listOf(s) + log.take(100) }

    acc.client.onAuthLost = {
        scope.launch {
            if (acc.client.login()) {
                SecureStore.saveCookies(ctx, acc.username, acc.client.exportCookies())
                snacks.showSnackbar("Session restored")
            } else {
                acc.enabled = false
                snacks.showSnackbar("Session lost - log in again from Master")
            }
        }
    }

    LaunchedEffect(acc.username) {
        if (acc.balance == null) {
            refreshing = true
            try {
                if (!acc.client.hasSession()) acc.client.login()
                acc.balance = acc.client.getBalance()
                SecureStore.saveCookies(ctx, acc.username, acc.client.exportCookies())
            } catch (_: Exception) {}
            refreshing = false
        }
        balanceText = acc.balance?.let { naira(it.money) } ?: "-"
        canSendText = acc.balance?.canSend?.let { naira(it) } ?: "-"
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
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Transfers")
                        Switch(checked = acc.enabled, onCheckedChange = { acc.enabled = it })
                        Text(if (acc.enabled) "Enabled" else "Paused", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(enabled = !refreshing, onClick = {
                        scope.launch {
                            refreshing = true
                            try {
                                if (!acc.client.hasSession()) acc.client.login()
                                val b = acc.client.getBalance()
                                acc.balance = b
                                balanceText = naira(b.money)
                                canSendText = b.canSend?.let { naira(it) } ?: "-"
                                SecureStore.saveCookies(ctx, acc.username, acc.client.exportCookies())
                            } catch (e: Exception) { snacks.showSnackbar(friendly(e as Exception)) } finally { refreshing = false }
                        }
                    }) { if (refreshing) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Refresh balance") }
                }
            }
        }
        item {
            Text("Send money", fontWeight = FontWeight.Bold)
            Text("Single recipient - 32s between sends - retries on drop", style = MaterialTheme.typography.labelSmall)
        }
        item {
            OutlinedTextField(recipient, { recipient = it.trim(); resolvedName = "" }, label = { Text("Recipient username") }, placeholder = { Text("e.g. adewale") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = recipient.isNotBlank() && !busy,
                    onClick = {
                        scope.launch {
                            try {
                                val id = acc.client.resolveUsername(recipient)
                                if (id == null) { resolvedName = ""; snacks.showSnackbar("No user @$recipient") }
                                else { resolvedName = id }
                            } catch (e: Exception) { snacks.showSnackbar(friendly(e as Exception)) }
                        }
                    }
                ) { Text("Check") }
                Text(
                    text = if (resolvedName.isBlank()) "" else "Found @$recipient",
                    style = MaterialTheme.typography.labelSmall
                )
            }
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
                Text("Total ${naira(amt * t)} + fees - ${(t - 1) * 32}s", style = MaterialTheme.typography.labelSmall)
            }
        }
        item {
            OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        item {
            val amt = amount.toLongOrNull() ?: 0
            val t = times.toIntOrNull() ?: 0
            val canSendNow = amt in 100..10_000_000_000 && t in 1..999 && recipient.isNotBlank()
            if (busy) Text(statusText, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    enabled = !busy && acc.enabled && canSendNow,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        stopFlag.set(false)
                        log = emptyList()
                        busy = true
                        val job = scope.launch {
                            try { ContextCompat.startForegroundService(ctx, Intent(ctx, KeepAliveService::class.java)) } catch (_: Exception) {}
                            try {
                                val toId = try { acc.client.resolveUsername(recipient) } catch (_: Exception) { null }
                                if (toId == null) {
                                    add("No user @$recipient - check spelling")
                                    db.insert(acc.username, recipient, amt, "not_found", 0, t, amt.toLong() * t)
                                    snacks.showSnackbar("No user @$recipient")
                                    return@launch
                                }
                                TransferNotify.ensureChannel(ctx)
                                var okCount = 0
                                var netFails = 0
                                for (i in 1..t) {
                                    if (stopFlag.get()) { add("Stopped at $i/$t"); break }
                                    if (!acc.enabled) { add("Paused at $i/$t"); break }
                                    statusText = "Sending $i of $t"
                                    add("Sending $i/$t ${naira(amt)} to @$recipient")
                                    TransferNotify.showProgress(ctx, acc.username, i, t, amt, "Sending $i/$t")

                                    var curBalance = try { acc.client.getBalance() } catch (_: Exception) { null }
                                    if (curBalance != null) {
                                        balanceText = naira(curBalance.money)
                                        canSendText = curBalance.canSend?.let { naira(it) } ?: "-"
                                        acc.balance = curBalance
                                        SecureStore.saveCookies(ctx, acc.username, acc.client.exportCookies())
                                        if (curBalance.money < amt) {
                                            val left = t - i + 1
                                            db.insert(acc.username, recipient, amt, "insufficient", i - 1, left, amt.toLong() * left)
                                            add("Not enough funds - have ${naira(curBalance.money)}")
                                            TransferNotify.showError(ctx, acc.username, "Not enough funds")
                                            break
                                        }
                                    }

                                    var result: org.json.JSONObject? = null
                                    var errMsg: String? = null
                                    var attempt = 0
                                    val maxRetry = 3
                                    while (attempt < maxRetry) {
                                        if (stopFlag.get()) break
                                        attempt++
                                        try {
                                            result = acc.client.sendMoney(toId, amt, note.ifBlank { null })
                                            errMsg = null
                                            break
                                        } catch (e: Exception) {
                                            errMsg = friendly(e)
                                            if (e is ApiException && e.code == 401) {
                                                val relogin = acc.client.login()
                                                SecureStore.saveCookies(ctx, acc.username, acc.client.exportCookies())
                                                if (!relogin) { acc.enabled = false; snacks.showSnackbar("Session lost - log in again") }
                                            }
                                            if (attempt < maxRetry) {
                                                statusText = "Retry $attempt/$maxRetry - $errMsg"
                                                add("Network drop - retry $attempt/$maxRetry")
                                                TransferNotify.showProgress(ctx, acc.username, i, t, amt, "Retry $attempt/$maxRetry")
                                                delay(5_000L * attempt)
                                            }
                                        }
                                    }

                                    if (stopFlag.get()) { add("Stopped at $i/$t"); break }

                                    if (errMsg != null) {
                                        netFails++
                                        val left = t - i
                                        db.insert(acc.username, recipient, amt, "failed", i - 1, left + 1, amt.toLong() * (left + 1))
                                        add("Failed $i/$t after $maxRetry retries - $errMsg")
                                        TransferNotify.showError(ctx, acc.username, errMsg)
                                        if (netFails >= 3) {
                                            add("Too many failures - stopping")
                                            snacks.showSnackbar("Stopped after repeated network failures")
                                            break
                                        }
                                    } else if (result != null) {
                                        if (result.optBoolean("ok", false)) {
                                            okCount++
                                            val left = t - i
                                            db.insert(acc.username, recipient, amt, "sent", i, left, amt.toLong() * left)
                                            add("Sent $i/$t ${naira(amt)} to @$recipient - $left left")
                                            netFails = 0
                                        } else {
                                            val msg = result.optString("error").ifBlank { "Could not send" }
                                            val nice = when {
                                                msg.contains("fund", true) -> "Not enough funds"
                                                msg.contains("limit", true) -> "Daily limit reached"
                                                msg.contains("frozen", true) -> "Account frozen"
                                                msg.contains("pin", true) -> "PIN wrong"
                                                else -> msg.take(80)
                                            }
                                            val left = t - i
                                            db.insert(acc.username, recipient, amt, nice, i - 1, left + 1, amt.toLong() * (left + 1))
                                            add("Failed $i/$t - $nice")
                                            TransferNotify.showError(ctx, acc.username, nice)
                                            if (msg.contains("limit", true) || msg.contains("frozen", true)) break
                                        }
                                    }
                                    log = db.all(acc.username)
                                    if (i < t && !stopFlag.get()) {
                                        add("Waiting 32s - next $i/$t done")
                                        delay(32_000)
                                    }
                                }
                                try {
                                    val b = acc.client.getBalance()
                                    acc.balance = b
                                    balanceText = naira(b.money)
                                    canSendText = b.canSend?.let { naira(it) } ?: "-"
                                    SecureStore.saveCookies(ctx, acc.username, acc.client.exportCookies())
                                } catch (_: Exception) {}
                                TransferNotify.showDone(ctx, acc.username, okCount, t)
                                snacks.showSnackbar("Done - $okCount of $t sent to @$recipient")
                            } catch (e: Exception) {
                                add("Error - ${friendly(e as Exception)}")
                                snacks.showSnackbar(friendly(e as Exception))
                            } finally {
                                try { ctx.stopService(Intent(ctx, KeepAliveService::class.java)) } catch (_: Exception) {}
                                TransferNotify.clearAll(ctx, listOf(acc.username))
                                busy = false
                                statusText = ""
                            }
                        }
                        currentJob = job
                    }
                ) {
                    val tInt = times.toIntOrNull() ?: 0
                    Text(if (busy) "Sending - 32s gap" else "Send ${if (tInt > 0) "${tInt}x " else ""}${if (amt > 0) naira(amt) else ""} to @$recipient".trim())
                }
                if (busy) {
                    OutlinedButton(onClick = {
                        stopFlag.set(true)
                        currentJob?.cancel()
                        busy = false
                        add("Stopped by user")
                        try { ctx.stopService(Intent(ctx, KeepAliveService::class.java)) } catch (_: Exception) {}
                    }) { Text("Stop") }
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

    fun refresh() {
        scope.launch {
            loading = true
            entries = withContext(Dispatchers.IO) { db.history(search.ifBlank { null }, filter) }
            loading = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Transfer History", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
        Text("All transfers saved on this phone.", style = MaterialTheme.typography.labelSmall)
        OutlinedTextField(search, { search = it }, label = { Text("Search") }, placeholder = { Text("username, account") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "sent", "failed", "not_found").forEach { f ->
                FilterChip(selected = filter == f, onClick = { filter = f; refresh() }, label = { Text(f) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { refresh() }) { if (loading) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Search") }
            TextButton(onClick = { search = ""; filter = "All"; refresh() }) { Text("Clear") }
        }
        if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
        Divider()
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            if (entries.isEmpty()) item { Text("No transfers yet.", style = MaterialTheme.typography.bodySmall) }
            items(entries) { e ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("@${e.account} to @${e.recipient} - ${naira(e.amount)} - ${e.status}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                        Text("${e.transferred} sent, ${e.remaining} left, ${naira(e.amountLeft)} left - ${java.text.SimpleDateFormat("MMM dd HH:mm", Locale.getDefault()).format(java.util.Date(e.ts))}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
