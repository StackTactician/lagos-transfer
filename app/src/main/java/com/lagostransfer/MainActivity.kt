package com.lagostransfer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lagostransfer.data.*
import com.lagostransfer.ui.theme.LagosTransferTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LagosTransferTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    TransferScreen()
                }
            }
        }
    }
}

@Composable
fun TransferScreen() {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var transfersEnabled by remember { mutableStateOf(true) }
    var balanceText by remember { mutableStateOf("—") }
    var log by remember { mutableStateOf(listOf<String>()) }
    var bulk by remember { mutableStateOf("ade3cd3578d0433aa2ea:5000:thanks\n") }
    val scope = rememberCoroutineScope()
    val client = remember { LagosClient() }

    fun add(s: String) { log = listOf(s) + log.take(100) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("LAGOS TRANSFER", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
        Text("Transfer only · Read balance · No tamper", style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(username, { username = it }, label = { Text("username") }, modifier = Modifier.weight(1f))
            OutlinedTextField(password, { password = it }, label = { Text("password") }, modifier = Modifier.weight(1f))
        }
        OutlinedTextField(pin, { pin = it }, label = { Text("PIN (if set)") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    val ok = client.login(Account(username, password, pin.ifBlank { null }))
                    add(if (ok) "✓ login @$username" else "✗ login failed")
                    if (ok) {
                        val b = try { client.getBalance() } catch (e: Exception) { add("balance err: ${e.message}"); return@launch }
                        balanceText = "₦${b.money} · canSend ${b.canSend ?: "—"}"
                    }
                }
            }) { Text("Login") }
            OutlinedButton(onClick = {
                scope.launch {
                    try { val b = client.getBalance(); balanceText = "₦${b.money} · canSend ${b.canSend ?: "—"}"; add("balance $balanceText") }
                    catch (e: Exception) { add("err ${e.message}") }
                }
            }) { Text("Balance") }
        }
        Text(balanceText, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Transfers"); Switch(checked = transfersEnabled, onCheckedChange = { transfersEnabled = it })
            Text(if (transfersEnabled) "ON" else "OFF", fontWeight = FontWeight.Bold)
        }
        Divider()
        Text("Bulk sends — one per line:  userId:amount:note  (amount 100–10B)", style = MaterialTheme.typography.labelSmall)
        OutlinedTextField(bulk, { bulk = it }, modifier = Modifier.fillMaxWidth().height(110.dp))
        Button(
            enabled = transfersEnabled,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (!transfersEnabled) { add("blocked: transfers OFF"); return@Button }
                val jobs = bulk.lines().mapNotNull { l ->
                    val p = l.split(":", limit = 3); if (p.size < 2) null else Triple(p[0].trim(), p[1].trim().toLongOrNull() ?: return@mapNotNull null, p.getOrNull(2)?.trim())
                }
                if (jobs.isEmpty()) { add("no jobs"); return@Button }
                scope.launch {
                    jobs.forEach { (to, amt, note) ->
                        if (amt < 100 || amt > 10_000_000_000) { add("✗ $to bad amount $amt"); return@forEach }
                        try {
                            val r = client.sendMoney(to, amt, note, pin.ifBlank { null })
                            val ok = r.optBoolean("ok", false)
                            add(if (ok) "✓ sent ₦$amt → $to" else "✗ $to ${r.optString("error").take(80)} [${r.optInt("_http")}]")
                        } catch (e: Exception) { add("✗ $to ${e.message}") }
                        kotlinx.coroutines.delay(350)
                    }
                }
            }
        ) { Text(if (transfersEnabled) "Send all (parallel per account)" else "Transfers disabled") }
        Divider()
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(log) { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
