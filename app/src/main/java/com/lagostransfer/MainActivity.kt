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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

val nairaFmt by lazy { NumberFormat.getNumberInstance(Locale("en", "NG")) }
fun naira(v: Long): String = "₦" + nairaFmt.format(v)

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
    val vm: AppViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    val accounts = vm.accounts
    var selected by remember { mutableStateOf<String?>(null) }
    val isSyncing = vm.isSyncing

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
                        NavigationDrawerItem(
                            label = { Text("@${acc.username}" + if (vm.isRunning(acc.username)) " •" else "") },
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
                        IconButton(onClick = { scope.launch { drawerState.open() } }) { Text("☰", style = MaterialTheme.typography.titleLarge) }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHost) }
        ) { pad ->
            Box(Modifier.padding(pad)) {
                when (selected) {
                    null -> MasterPage(vm, snackbarHost, onSelect = { selected = it })
                    "history" -> HistoryPage()
                    else -> {
                        val acc = accounts.find { it.username == selected }
                        if (acc == null) MasterPage(vm, snackbarHost, onSelect = { selected = it })
                        else AccountPage(acc, snackbarHost, vm)
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
fun MasterPage(vm: AppViewModel, snacks: SnackbarHostState, onSelect: (String) -> Unit) {
    val ctx = LocalContext.current
    val accounts = vm.accounts
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
                                        } catch (e: Exception) { snacks.showSnackbar(friendlyError(e)) }
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
                    val u = username.trim()
                    if (accounts.any { it.username == u }) {
                        scope.launch { snacks.showSnackbar("Account @$u already added") }
                        return@Button
                    }
                    busy = true
                    scope.launch {
                        val err = vm.addAccount(u, password, pin.ifBlank { null })
                        if (err != null) {
                            snacks.showSnackbar(err)
                        } else {
                            val bal = vm.accounts.find { it.username == u }?.balance
                            snacks.showSnackbar("Logged in @$u" + (bal?.let { " - ${naira(it.money)}" } ?: ""))
                            username = ""; password = ""; pin = ""
                        }
                        busy = false
                    }
                }
            ) { if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Log in and add") }
        }
        item { Divider() }
        item { Text("Accounts (${accounts.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
        if (accounts.isEmpty()) {
            item { Text("No accounts yet. Add one above. Each account sends on its own clock.", style = MaterialTheme.typography.bodySmall) }
        } else {
            items(accounts, key = { it.username }) { acc ->
                val ui = vm.uiMap[acc.username]
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("@${acc.username}", fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (vm.isRunning(acc.username)) Badge { Text("SENDING") }
                                Badge { Text(if (acc.enabled) "ON" else "OFF") }
                            }
                        }
                        Text(
                            acc.balance?.let { "${naira(it.money)}  -  Can send ${it.canSend?.let { c -> naira(c) } ?: "unknown"}" } ?: "Tap refresh for balance",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (ui != null && ui.busy) {
                            Text(ui.status.ifBlank { "Sending..." }, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                            Text("${ui.sent}/${ui.total} sent", style = MaterialTheme.typography.labelSmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onSelect(acc.username) }) { Text("Open") }
                            OutlinedButton(onClick = { vm.refreshBalance(acc.username) }) { Text("Refresh") }
                            TextButton(onClick = { vm.removeAccount(acc.username) }) { Text("Log out") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AccountPage(acc: SessionAccount, snacks: SnackbarHostState, vm: AppViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui = vm.uiMap[acc.username]
    if (ui == null) {
        Text("Loading account...")
        return
    }
    var recipient by remember { mutableStateOf("") }
    var resolved by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("10000000000") }
    var times by remember { mutableStateOf("1") }
    var note by remember { mutableStateOf("") }

    LaunchedEffect(acc.username) {
        vm.refreshBalance(acc.username)
        vm.loadIncoming(acc.username)
    }

    val amt = amount.toLongOrNull() ?: 0L
    val t = times.toIntOrNull() ?: 0
    val fee = LagosClient.feeFor(amt)
    val canSendNow = amt in LagosClient.MIN_SEND..LagosClient.MAX_SEND && t in 1..999 && recipient.isNotBlank()

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("@${acc.username}", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                    Text(ui.balanceText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                    Text("Balance", style = MaterialTheme.typography.labelSmall)
                    Divider()
                    Text("Can send today: ${ui.canSendText}", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Transfers")
                        Switch(checked = acc.enabled, onCheckedChange = { acc.enabled = it })
                        Text(if (acc.enabled) "Enabled" else "Paused", fontWeight = FontWeight.SemiBold)
                    }
                    OutlinedButton(enabled = !ui.refreshing, onClick = { vm.refreshBalance(acc.username) }) {
                        if (ui.refreshing) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Refresh balance")
                    }
                }
            }
        }
        if (ui.incoming.isNotEmpty()) {
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Waiting to be claimed", fontWeight = FontWeight.Bold)
                        Text(
                            "${ui.incoming.size} transfer(s) - ${naira(ui.incoming.sumOf { it.amount })} is held by the server until it is claimed.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        ui.incoming.take(5).forEach { t2 ->
                            Text("@${t2.who} sent ${naira(t2.amount)}", style = MaterialTheme.typography.labelSmall)
                        }
                        Button(onClick = {
                            vm.claimIncoming(acc.username) { got ->
                                scope.launch {
                                    if (got > 0) snacks.showSnackbar("Claimed ${naira(got)}") else snacks.showSnackbar("Nothing to claim")
                                }
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Claim incoming money") }
                    }
                }
            }
        }
        item {
            Text("Send money", fontWeight = FontWeight.Bold)
            Text("One recipient per run - 32s between sends - a lost connection is checked with the server before any retry", style = MaterialTheme.typography.labelSmall)
        }
        item {
            OutlinedTextField(recipient, { recipient = it.trim(); resolved = "" }, label = { Text("Recipient username") }, placeholder = { Text("e.g. adewale") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = recipient.isNotBlank() && !ui.busy,
                    onClick = {
                        scope.launch {
                            try {
                                when (val l = acc.client.resolveUsername(recipient)) {
                                    is Lookup.Found -> { resolved = l.id; snacks.showSnackbar("Found @$recipient") }
                                    Lookup.NotFound -> { resolved = ""; snacks.showSnackbar("No user @$recipient") }
                                    is Lookup.Failed -> { resolved = ""; snacks.showSnackbar(l.reason) }
                                }
                            } catch (e: Exception) { snacks.showSnackbar(friendlyError(e)) }
                        }
                    }
                ) { Text("Check") }
                Text(
                    text = if (resolved.isBlank()) "" else "Found @$recipient",
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
            Text(
                when {
                    amt == 0L -> ""
                    amt < LagosClient.MIN_SEND -> "Minimum ${naira(LagosClient.MIN_SEND)}"
                    amt > LagosClient.MAX_SEND -> "Maximum ${naira(LagosClient.MAX_SEND)}"
                    else -> "Will send ${naira(amt)} per transfer (${naira(fee)} fee, ${naira(amt + fee)} total)"
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
            if (t > 0 && amt >= LagosClient.MIN_SEND) {
                Text("Total ${naira(amt * t)} + ${naira(fee * t)} fees - ${(t - 1) * 32}s", style = MaterialTheme.typography.labelSmall)
            }
        }
        item {
            OutlinedTextField(note, { note = it }, label = { Text("Note (optional, 3+ characters)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        item {
            if (ui.busy) Text(ui.status, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    enabled = !ui.busy && acc.enabled && canSendNow,
                    modifier = Modifier.weight(1f),
                    onClick = { vm.startSend(acc.username, recipient, amt, t, note.ifBlank { null }) }
                ) {
                    Text(if (ui.busy) "Sending ${ui.sent}/${ui.total}" else "Send ${if (t > 0) "${t}x " else ""}${if (amt > 0) naira(amt) else ""} to @$recipient".trim())
                }
                if (ui.busy) {
                    OutlinedButton(onClick = { vm.stopSend(acc.username) }) { Text("Stop") }
                }
            }
        }
        if (ui.lastError.isNotBlank()) {
            item { Text(ui.lastError, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold) }
        }
        item { Divider() }
        item { Text("Activity", fontWeight = FontWeight.SemiBold) }
        items(ui.log) { Text(it, style = MaterialTheme.typography.bodySmall) }
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
