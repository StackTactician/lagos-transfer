package com.lagostransfer.data

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
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
    var sent by mutableStateOf(0)
    var sentVolume by mutableStateOf(0L)
    var total by mutableStateOf(0)
    var incoming by mutableStateOf(listOf<Transfer>())
    var ledger by mutableStateOf(listOf<Transfer>())
    var ledgerNote by mutableStateOf("")

    /** The send form belongs to the account, not to the page - so it never mixes. */
    var recipient by mutableStateOf("")
    var resolved by mutableStateOf("")
    var amount by mutableStateOf("10000000000")
    var times by mutableStateOf("1")
    var note by mutableStateOf("")
    var lastError by mutableStateOf("")
}

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app: Application = application

    val accounts = mutableStateListOf<SessionAccount>()
    val uiMap = mutableStateMapOf<String, UiState>()
    var isSyncing by mutableStateOf(true)
    var licence by mutableStateOf(LicenceState("", 0L, false, false))
        private set

    private val jobs = mutableMapOf<String, Job>()
    private val stops = mutableMapOf<String, AtomicBoolean>()
    private val activeSends = java.util.concurrent.atomic.AtomicInteger(0)

    fun getUi(u: String) = uiMap.getOrPut(u) { UiState() }

    fun isRunning(u: String): Boolean = jobs[u]?.isActive == true

    init {
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) { SecureStore.loadAccounts(app) }
                val pending = saved.mapNotNull { triple ->
                    val u = triple.first; val pp = triple.second; val pin = triple.third
                    if (accounts.any { it.username == u }) null else {
                        val cli = LagosClient(u, pp, pin)
                        val cookies = withContext(Dispatchers.IO) { SecureStore.loadCookies(app, u) }
                        if (cookies.isNotEmpty()) cli.restoreCookies(cookies)
                        val acc = SessionAccount(u, pp, pin, cli)
                        cli.onAuthLost = {
                            acc.enabled = false
                            getUi(u).lastError = "Session lost - log in again from Master"
                        }
                        accounts.add(acc)
                        val s = getUi(u)
                        async(Dispatchers.IO) {
                            val ok = if (cli.hasSession()) {
                                try { cli.getBalance(); true } catch (_: Exception) { false }
                            } else false
                            if (!ok) {
                                if (cli.login()) {
                                    withContext(Dispatchers.IO) { SecureStore.saveCookies(app, u, cli.exportCookies()) }
                                }
                            }
                            try { acc.balance = cli.getBalance() } catch (_: Exception) {}
                            s.balanceText = acc.balance?.let { naira(it.money) } ?: "-"
                            s.canSendText = acc.balance?.canSend?.let { naira(it) } ?: "-"
                            s.log = withContext(Dispatchers.IO) { LogDb(app).all(u) }
                            withContext(Dispatchers.IO) { SecureStore.saveCookies(app, u, cli.exportCookies()) }
                        }
                    }
                }
                pending.forEach { try { it.await() } catch (_: Exception) {} }
            } catch (_: Exception) {
            } finally {
                isSyncing = false
            }
            reloadLicence()
        }
    }

    /** Reads the trial counter from the phone and asks the server about the licence. */
    fun reloadLicence(onDone: ((LicenceState) -> Unit)? = null) {
        viewModelScope.launch {
            licence = License.load(app)
            licence = License.refresh(app)
            onDone?.invoke(licence)
        }
    }

    /** Redeems an activation code. Returns an empty string on success. */
    fun redeem(code: String, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val err = License.redeem(app, code)
            licence = License.load(app)
            onDone(err)
        }
    }

    /** Adds an account and stores it encrypted. Returns an error message, or null on success. */
    suspend fun addAccount(username: String, password: String, pin: String?): String? {
        val u = username.trim()
        if (u.isEmpty() || password.isEmpty()) return "Username and password are needed"
        if (accounts.any { it.username == u }) return "Account @$u already added"
        val cli = LagosClient(u, password, pin)
        if (!cli.login()) return "Could not log in - check username and password"
        val acc = SessionAccount(u, password, pin, cli)
        cli.onAuthLost = {
            acc.enabled = false
            getUi(u).lastError = "Session lost - log in again from Master"
        }
        try { acc.balance = cli.getBalance() } catch (_: Exception) {}
        withContext(Dispatchers.IO) {
            SecureStore.saveAccount(app, u, password, pin)
            SecureStore.saveCookies(app, u, cli.exportCookies())
        }
        accounts.add(acc)
        val s = getUi(u)
        s.balanceText = acc.balance?.let { naira(it.money) } ?: "-"
        s.canSendText = acc.balance?.canSend?.let { naira(it) } ?: "-"
        loadIncoming(u)
        return null
    }

    fun removeAccount(username: String) {
        stopSend(username)
        viewModelScope.launch(Dispatchers.IO) { SecureStore.removeAccount(app, username) }
        accounts.removeAll { it.username == username }
        uiMap.remove(username)
    }

    fun refreshBalance(username: String, onError: ((String) -> Unit)? = null) {
        val acc = accounts.find { it.username == username } ?: return
        val s = getUi(username)
        viewModelScope.launch {
            s.refreshing = true
            try {
                if (!acc.client.hasSession()) acc.client.login()
                val b = acc.client.getBalance()
                acc.balance = b
                s.balanceText = naira(b.money)
                s.canSendText = b.canSend?.let { naira(it) } ?: "-"
                withContext(Dispatchers.IO) { SecureStore.saveCookies(app, username, acc.client.exportCookies()) }
            } catch (e: Exception) {
                s.lastError = friendlyError(e)
                onError?.invoke(s.lastError)
            } finally {
                s.refreshing = false
            }
        }
    }

    /** Loads the server ledger: what went out, what is waiting, what was claimed. */
    fun loadIncoming(username: String) {
        val acc = accounts.find { it.username == username } ?: return
        val s = getUi(username)
        viewModelScope.launch {
            try {
                val list = acc.client.ledger()
                s.ledger = list.take(30)
                s.incoming = list.filter { it.waiting }
                s.ledgerNote = "checked ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())} · ${list.size} on the server"
            } catch (e: Exception) {
                s.ledgerNote = "could not read the server ledger - ${friendlyError(e)}"
            }
        }
    }

    /** Claims every transfer waiting for this account. Returns how much was claimed. */
    fun claimIncoming(username: String, onDone: (Long, String) -> Unit) {
        val acc = accounts.find { it.username == username } ?: return
        val s = getUi(username)
        viewModelScope.launch {
            s.refreshing = true
            try {
                val got = acc.client.claimIncoming()
                val total = got.sumOf { it.amount }
                withContext(Dispatchers.IO) { SecureStore.saveCookies(app, username, acc.client.exportCookies()) }
                try {
                    acc.balance = acc.client.getBalance()
                    s.balanceText = naira(acc.balance!!.money)
                } catch (_: Exception) {}
                val list = acc.client.ledger()
                s.ledger = list.take(30)
                s.incoming = list.filter { it.waiting }
                onDone(total, "")
            } catch (e: Exception) {
                s.lastError = friendlyError(e)
                onDone(0L, s.lastError)
            } finally {
                s.refreshing = false
            }
        }
    }

    /**
     * Starts a send run for one account. Runs in the view model, so it keeps going
     * when the user leaves the page and several accounts can send at the same time.
     */
    fun startSend(username: String, recipient: String, amount: Long, times: Int, note: String?) {
        val acc = accounts.find { it.username == username } ?: return
        val s = getUi(username)
        if (jobs[username]?.isActive == true) return
        if (licence.locked) {
            getUi(username).lastError = "Trial finished - enter an activation code to keep sending"
            return
        }
        // One name, or a list - the list is served one recipient after the other.
        val recipients = parseRecipients(recipient)
        val stopFlag = AtomicBoolean(false)
        stops[username] = stopFlag
        s.busy = true
        s.log = emptyList()
        s.status = "Starting"
        s.sent = 0
        s.total = times * recipients.size
        s.lastError = ""

        jobs[username] = viewModelScope.launch {
            try {
                activeSends.incrementAndGet()
                try {
                    ContextCompat.startForegroundService(app, Intent(app, KeepAliveService::class.java))
                } catch (_: Exception) {
                }
                val db = LogDb(app)
                runRecipients(
                    api = acc.client,
                    recipients = recipients,
                    amount = amount,
                    times = times,
                    note = note,
                    stop = { stopFlag.get() },
                    paused = { !acc.enabled },
                    emit = { name, step -> onStep(username, acc, db, step, name, amount, times) }
                )
                try {
                    acc.balance = acc.client.getBalance()
                    s.balanceText = naira(acc.balance!!.money)
                    s.canSendText = acc.balance?.canSend?.let { naira(it) } ?: "-"
                    withContext(Dispatchers.IO) { SecureStore.saveCookies(app, username, acc.client.exportCookies()) }
                } catch (_: Exception) {
                }
                loadIncoming(username)
            } catch (e: Exception) {
                s.lastError = friendlyError(e)
                s.log = listOf("Error - ${friendlyError(e)}") + s.log
            } finally {
                // The keep alive service stays up while any other account is still sending.
                if (activeSends.decrementAndGet() <= 0) {
                    try { app.stopService(Intent(app, KeepAliveService::class.java)) } catch (_: Exception) {}
                }
                TransferNotify.clearAll(app, listOf(username))
                s.busy = false
                s.status = ""
                if (s.log.isEmpty()) s.log = db2(username)
                stops.remove(username)
            }
        }
    }

    private suspend fun db2(username: String): List<String> =
        withContext(Dispatchers.IO) { LogDb(app).all(username) }

    fun stopSend(username: String) {
        stops[username]?.set(true)
        getUi(username).status = "Stopping"
    }

    private suspend fun onStep(
        username: String,
        acc: SessionAccount,
        db: LogDb,
        step: RunStep,
        recipient: String,
        amount: Long,
        times: Int
    ) {
        val s = getUi(username)
        when (step) {
            is RunStep.Status -> {
                // status is for the line under the buttons, it is not activity
                s.status = step.text
            }
            is RunStep.Balance -> {
                acc.balance = Balance(step.money, acc.balance?.canSend, acc.balance?.hasGame ?: true)
                s.balanceText = naira(step.money)
            }
            is RunStep.Sent -> {
                licence = License.addSent(app, amount)
                s.sent = s.sent + 1
                s.sentVolume = s.sentVolume + amount
                s.total = s.total.coerceAtLeast(step.index)
                s.status = "Sent ${s.sent} of ${s.total}"
                val line = "${s.sent}/${s.total} · ${naira(amount)} → @$recipient" +
                    (if (step.id != null) "" else " (recovered)")
                s.log = listOf(line) + s.log.take(100)
                withContext(Dispatchers.IO) {
                    db.insert(username, recipient, amount, "sent", step.index, times - step.index, amount * (times - step.index))
                }
                TransferNotify.showProgress(
                    app, username, s.sent, s.total, amount,
                    "Sent ${s.sent}/${s.total}" + if (step.fee > 0) " · fee ${naira(step.fee)}" else ""
                )
            }
            is RunStep.Skipped -> {
                s.status = "Failed ${step.index} - ${step.reason}"
                s.log = listOf("${step.index}/${times} · @$recipient · ${step.reason}") + s.log.take(100)
                TransferNotify.showError(app, username, step.reason)
                withContext(Dispatchers.IO) {
                    db.insert(username, recipient, amount, "failed", step.index - 1, times - step.index, amount * (times - step.index))
                }
            }
            is RunStep.Halted -> {
                s.status = step.reason
                s.lastError = step.reason
                s.log = listOf("stopped · ${step.reason}") + s.log.take(100)
                TransferNotify.showError(app, username, step.reason)
                withContext(Dispatchers.IO) {
                    db.insert(username, recipient, amount, "failed", s.sent, times - s.sent, amount * (times - s.sent))
                }
            }
        }
    }
}

fun friendlyError(e: Throwable): String {
    val m = e.message ?: ""
    return when {
        "Unable to resolve host" in m || "No address" in m -> "No internet - check connection"
        "timeout" in m.lowercase() -> "Network timeout - retrying"
        m.contains("401") || m.contains("Session expired") -> "Session expired - logging in again"
        m.contains("429") -> "Too many tries - waiting"
        else -> m.take(80).ifBlank { "Something went wrong" }
    }
}
