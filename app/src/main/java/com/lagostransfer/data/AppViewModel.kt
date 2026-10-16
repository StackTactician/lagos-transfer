package com.lagostransfer.data

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lagostransfer.SessionAccount
import com.lagostransfer.naira
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class UiState {
    var busy by mutableStateOf(false)
    var status by mutableStateOf("")
    var refreshing by mutableStateOf(false)
    var balanceText by mutableStateOf("-")
    var canSendText by mutableStateOf("-")
    var log by mutableStateOf(listOf<String>())
    var job: Job? by mutableStateOf(null)
    val stop = AtomicBoolean(false)
}

class AppViewModel(private val application: Application) : AndroidViewModel(application) {
    val accounts = mutableStateListOf<SessionAccount>()
    val uiMap = mutableStateMapOf<String, UiState>()
    var isSyncing by mutableStateOf(true)
    var serviceOn by mutableStateOf(false)
    fun getUi(u: String) = uiMap.getOrPut(u) { UiState() }

    init {
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { SecureStore.loadAccounts(application) }
                val jobs = saved.mapNotNull { triple ->
                    val u = triple.first; val pp = triple.second; val pin = triple.third
                    if (accounts.any { it.username == u }) null else {
                        val cli = LagosClient(u, pp, pin)
                        val cookies = withContext(Dispatchers.IO) { SecureStore.loadCookies(application, u) }
                        if (cookies.isNotEmpty()) cli.restoreCookies(cookies)
                        val acc = SessionAccount(u, pp, pin, cli)
                        accounts.add(acc)
                        val s = getUi(u)
                        async(Dispatchers.IO) {
                            val ok = if (cli.hasSession()) try { withContext(Dispatchers.IO) { cli.getBalance() }; true } catch (_: Exception) { false } else false
                            if (!ok) { val re = withContext(Dispatchers.IO) { cli.login() }; if (re) withContext(Dispatchers.IO) { SecureStore.saveCookies(application, u, cli.exportCookies()) } }
                            try { acc.balance = withContext(Dispatchers.IO) { cli.getBalance() } } catch (_: Exception) {}
                            s.balanceText = acc.balance?.let { naira(it.money) } ?: "-"
                            s.canSendText = acc.balance?.canSend?.let { naira(it) } ?: "-"
                            s.log = withContext(Dispatchers.IO) { LogDb(application).all(u) }
                            withContext(Dispatchers.IO) { SecureStore.saveCookies(application, u, cli.exportCookies()) }
                        }
                    }
                }
                jobs.forEach { try { it.await() } catch (_: Exception) {} }
            } catch (_: Exception) {} finally { isSyncing = false }
        }
    }
}
