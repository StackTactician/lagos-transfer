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

/** A run that was going on when the app was closed. */
data class RunPlan(
    val username: String,
    val recipients: List<String>,
    val times: Int,
    val amount: Long,
    val note: String?,
    val sent: List<Int>
) {
    val done: Int get() = sent.sum()
    val total: Int get() = times * recipients.size
    val finished: Boolean get() = done >= total
}

/** What happened when an account was added. */
sealed class AddOutcome {
    object Ok : AddOutcome()

    /** Password was accepted; the server wants a code before the session exists. */
    data class NeedCode(val method: String) : AddOutcome()

    data class Error(val message: String) : AddOutcome()
}

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
    var resume by mutableStateOf<RunPlan?>(null)

    /** The send form belongs to the account, not to the page - so it never mixes. */
    var recipient by mutableStateOf("")
    var resolved by mutableStateOf("")
    var amount by mutableStateOf("10000000000")
    var times by mutableStateOf("1")
    var note by mutableStateOf("")
    var lastError by mutableStateOf("")

    /** App-method two-step, per account. Nothing here happens without a tap. */
    var twoStepOn by mutableStateOf(false)
    var twoStepMethod by mutableStateOf<String?>(null)
    var twoStepBusy by mutableStateOf(false)
    var twoStepSetup by mutableStateOf<AppTwoStepStart?>(null)
    var hasSecret by mutableStateOf(false)
    var backupCodes by mutableStateOf(listOf<String>())
    var twoStepNote by mutableStateOf("")
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
                        val secret = withContext(Dispatchers.IO) { SecureStore.loadTotpSecret(app, u) }
                        val cli = LagosClient(u, pp, pin, secret)
                        val cookies = withContext(Dispatchers.IO) { SecureStore.loadCookies(app, u) }
                        if (cookies.isNotEmpty()) cli.restoreCookies(cookies)
                        val acc = SessionAccount(u, pp, pin, cli)
                        cli.onAuthLost = {
                            acc.enabled = false
                            getUi(u).lastError = if (cli.needsSecondFactor)
                                "Session expired - this account wants a code. Log in again from Master."
                            else "Session lost - log in again from Master"
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
            loadInterruptedRun()
        }
    }

    /** Refreshes the balance of every account at once. */
    fun refreshAll(onDone: (Int, Int) -> Unit) {
        val list = accounts.toList()
        if (list.isEmpty()) { onDone(0, 0); return }
        viewModelScope.launch {
            val results = list.map { acc ->
                async {
                    try {
                        if (!acc.client.hasSession()) acc.client.login()
                        val b = acc.client.getBalance()
                        acc.balance = b
                        val s = getUi(acc.username)
                        s.balanceText = naira(b.money)
                        s.canSendText = b.canSend?.let { naira(it) } ?: "-"
                        withContext(Dispatchers.IO) { SecureStore.saveCookies(app, acc.username, acc.client.exportCookies()) }
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
            }.map { it.await() }
            onDone(results.count { it }, list.size)
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

    /** Adds an account and stores it encrypted. */
    suspend fun addAccount(
        username: String,
        password: String,
        pin: String?,
        code: String? = null,
        secret: String? = null
    ): AddOutcome {
        val u = username.trim()
        if (u.isEmpty() || password.isEmpty()) return AddOutcome.Error("Username and password are needed")
        if (accounts.any { it.username == u }) return AddOutcome.Error("Account @$u already added")
        val stored = withContext(Dispatchers.IO) { SecureStore.loadTotpSecret(app, u) }
        val pasted = secret?.trim()?.replace(" ", "")?.replace("-", "")?.uppercase()
            ?.takeIf { it.isNotBlank() && Totp.isPlausibleSecret(it) }
        val cli = LagosClient(u, password, pin, stored ?: pasted)
        val loggedIn = cli.login() || (!code.isNullOrBlank() && cli.loginWithCode(code))
        if (!loggedIn) {
            // Never blame the password when the server is asking for a code.
            return if (cli.needsSecondFactor) {
                AddOutcome.NeedCode(cli.secondFactorMethod() ?: "app")
            } else {
                AddOutcome.Error("Could not log in - check username and password")
            }
        }
        val acc = SessionAccount(u, password, pin, cli)
        cli.onAuthLost = {
            acc.enabled = false
            getUi(u).lastError = if (cli.needsSecondFactor)
                "Session expired - this account wants a code. Log in again from Master."
            else "Session lost - log in again from Master"
        }
        try { acc.balance = cli.getBalance() } catch (_: Exception) {}
        withContext(Dispatchers.IO) {
            SecureStore.saveAccount(app, u, password, pin)
            SecureStore.saveCookies(app, u, cli.exportCookies())
            // Only keep a secret we just proved with a successful login.
            if (stored == null && pasted != null) SecureStore.saveTotpSecret(app, u, pasted)
        }
        accounts.add(acc)
        val s = getUi(u)
        s.balanceText = acc.balance?.let { naira(it.money) } ?: "-"
        s.canSendText = acc.balance?.canSend?.let { naira(it) } ?: "-"
        loadIncoming(u)
        return AddOutcome.Ok
    }

    fun removeAccount(username: String) {
        stopSend(username)
        viewModelScope.launch(Dispatchers.IO) { SecureStore.removeAccount(app, username) }
        accounts.removeAll { it.username == username }
        uiMap.remove(username)
    }

    // ---- App-method two-step -------------------------------------------------
    // Always: one account, started by a tap, never replacing a factor that is
    // already on, secret kept only in this device's Keystore-backed store.

    fun refreshTwoStepState(username: String) {
        val acc = accounts.find { it.username == username } ?: return
        val s = getUi(username)
        viewModelScope.launch {
            s.hasSecret = withContext(Dispatchers.IO) { SecureStore.hasTotpSecret(app, username) }
            try {
                val st = acc.client.twoStepState()
                if (!st.unknown) {
                    s.twoStepOn = st.on
                    s.twoStepMethod = st.method
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Step 1: mint a secret. Shows it to the user; nothing is confirmed yet. */
    fun startAppTwoStep(username: String, password: String, onDone: (String?) -> Unit) {
        val acc = accounts.find { it.username == username } ?: return onDone("Account not found")
        val s = getUi(username)
        viewModelScope.launch {
            s.twoStepBusy = true
            try {
                if (password.isBlank()) { onDone("Your password is needed to set this up"); return@launch }
                val st = acc.client.twoStepState()
                if (st.on) {
                    onDone("Two-step is already on for @$username. This app will not replace an existing one.")
                    return@launch
                }
                val start = acc.client.startAppTwoStep(password)
                s.twoStepSetup = start
                s.backupCodes = emptyList()
                s.twoStepNote = "Add the secret to your authenticator, then confirm below"
                onDone(null)
            } catch (e: Exception) {
                onDone(friendlyError(e))
            } finally {
                s.twoStepBusy = false
            }
        }
    }

    /** Step 2: confirm with a code from that secret, then store it locally. */
    fun confirmAppTwoStep(username: String, atMillis: Long = System.currentTimeMillis(), onDone: (String?) -> Unit) {
        val acc = accounts.find { it.username == username } ?: return onDone("Account not found")
        val s = getUi(username)
        val setup = s.twoStepSetup ?: return onDone("Start setup first")
        viewModelScope.launch {
            s.twoStepBusy = true
            try {
                val done = acc.client.confirmAppTwoStep(setup.secret, atMillis)
                withContext(Dispatchers.IO) {
                    SecureStore.saveTotpSecret(app, username, setup.secret)
                    SecureStore.saveBackupCodes(app, username, done.backupCodes)
                }
                acc.client.adoptTotpSecret(setup.secret)
                s.backupCodes = done.backupCodes
                s.twoStepSetup = null
                s.twoStepOn = true
                s.hasSecret = true
                s.twoStepMethod = "app"
                s.twoStepNote = "Two-step is on for @$username"
                onDone(null)
            } catch (e: Exception) {
                // Half-enrolled is worse than not enrolled: say so plainly.
                onDone(friendlyError(e) + " - if this keeps failing, the account may be left with two-step switched on but unconfirmed. Keep the secret and try Confirm again.")
            } finally {
                s.twoStepBusy = false
            }
        }
    }

    fun dismissBackupCodes(username: String) {
        getUi(username).backupCodes = emptyList()
    }

    /**
     * Attach a secret the owner already has (their authenticator app shows it,
     * or they kept it from setup). This is the only way an account that is
     * already enrolled can be answered automatically afterwards.
     */
    fun attachTotpSecret(username: String, secret: String, onDone: (String?) -> Unit) {
        val acc = accounts.find { it.username == username } ?: return onDone("Account not found")
        val clean = secret.trim().replace(" ", "").replace("-", "").uppercase()
        if (!Totp.isPlausibleSecret(clean)) return onDone("That does not look like a base32 secret")
        viewModelScope.launch {
            try {
                // Prove it with a real login before saving: a login only succeeds
                // when the server accepts the code this secret produced.
                acc.client.adoptTotpSecret(clean)
                if (!acc.client.login()) {
                    acc.client.adoptTotpSecret(null)
                    onDone("The server did not accept a code from that secret")
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    SecureStore.saveTotpSecret(app, username, clean)
                    SecureStore.saveCookies(app, username, acc.client.exportCookies())
                }
                val s = getUi(username)
                s.twoStepOn = true
                s.hasSecret = true
                s.twoStepNote = "Secret attached - codes are made on this phone now"
                onDone(null)
            } catch (e: Exception) {
                acc.client.adoptTotpSecret(null)
                onDone(friendlyError(e))
            }
        }
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
        runPlan(username, recipients, times, amount, note, List(recipients.size) { 0 })
    }

    /** Picks a run back up where the app left it. */
    fun resumeSend(username: String) {
        val plan = getUi(username).resume ?: return
        val remaining = plan.recipients.mapIndexed { i, _ -> plan.times - plan.sent.getOrElse(i) { 0 } }
        getUi(username).resume = null
        runPlan(username, plan.recipients, plan.times, plan.amount, plan.note, plan.sent, remaining)
    }

    fun discardResume(username: String) {
        getUi(username).resume = null
        viewModelScope.launch(Dispatchers.IO) { SecureStore.clearRun(app) }
    }

    private fun runPlan(
        username: String,
        recipients: List<String>,
        times: Int,
        amount: Long,
        note: String?,
        sentBefore: List<Int>,
        remainingOverride: List<Int>? = null
    ) {
        val acc = accounts.find { it.username == username } ?: return
        val s = getUi(username)
        if (jobs[username]?.isActive == true) return
        if (licence.locked) {
            s.lastError = "Trial finished - enter an activation code to keep sending"
            return
        }
        val sentCounts = sentBefore.toMutableList()
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
                val plan = RunPlan(username, recipients, times, amount, note, sentCounts.toList())
                withContext(Dispatchers.IO) { SecureStore.saveRun(app, planJson(plan)) }
                val runs = recipients.mapIndexed { i, name ->
                    val left = remainingOverride?.getOrElse(i) { times } ?: times
                    RecipientRun(name, left)
                }
                var currentIndex = 0
                runRecipients(
                    api = acc.client,
                    recipients = runs,
                    amount = amount,
                    note = note,
                    // the trial can run out mid batch - stop at the next send boundary
                    stop = { stopFlag.get() || License.state.locked },
                    paused = { !acc.enabled },
                    onStart = { index, _ -> currentIndex = index - 1 },
                    emit = { name, step ->
                        if (step is RunStep.Sent && currentIndex in sentCounts.indices) {
                            sentCounts[currentIndex] = sentCounts[currentIndex] + 1
                            val live = RunPlan(username, recipients, times, amount, note, sentCounts.toList())
                            withContext(Dispatchers.IO) { SecureStore.saveRun(app, planJson(live)) }
                        }
                        onStep(username, acc, db, step, name, amount, times)
                    }
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
                if (License.state.locked) {
                    getUi(username).lastError = "Trial finished - enter an activation code to keep sending"
                }
                // The keep alive service stays up while any other account is still sending.
                if (activeSends.decrementAndGet() <= 0) {
                    try { app.stopService(Intent(app, KeepAliveService::class.java)) } catch (_: Exception) {}
                }
                TransferNotify.clearAll(app, listOf(username))
                s.busy = false
                s.status = ""
                withContext(Dispatchers.IO) { SecureStore.clearRun(app) }
                if (s.log.isEmpty()) s.log = db2(username)
                stops.remove(username)
            }
        }
    }

    private fun planJson(plan: RunPlan): String = org.json.JSONObject().apply {
        put("u", plan.username)
        put("r", org.json.JSONArray(plan.recipients))
        put("t", plan.times)
        put("a", plan.amount)
        put("n", plan.note ?: "")
        put("s", org.json.JSONArray(plan.sent))
    }.toString()

    private fun planFrom(raw: String): RunPlan? = try {
        val j = org.json.JSONObject(raw)
        val names = mutableListOf<String>()
        val arr = j.optJSONArray("r") ?: org.json.JSONArray()
        for (i in 0 until arr.length()) names.add(arr.optString(i))
        val counts = mutableListOf<Int>()
        val sarr = j.optJSONArray("s") ?: org.json.JSONArray()
        for (i in 0 until sarr.length()) counts.add(sarr.optInt(i))
        RunPlan(
            username = j.optString("u"),
            recipients = names,
            times = j.optInt("t", 1),
            amount = j.optLong("a", 0L),
            note = j.optString("n").ifBlank { null },
            sent = counts
        )
    } catch (_: Exception) {
        null
    }

    /** Reads the run that was interrupted and offers it back. */
    private fun loadInterruptedRun() {
        viewModelScope.launch {
            val raw = withContext(Dispatchers.IO) { SecureStore.loadRun(app) } ?: return@launch
            val plan = planFrom(raw) ?: return@launch
            if (plan.finished) {
                withContext(Dispatchers.IO) { SecureStore.clearRun(app) }
                return@launch
            }
            getUi(plan.username).resume = plan
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
                val line = "${s.sent}/${s.total} · sent ${naira(amount)} to @$recipient" +
                    (if (step.id != null) "" else " (recovered)")
                s.log = listOf(line) + s.log.take(100)
                withContext(Dispatchers.IO) {
                    db.insert(username, recipient, amount, "sent", step.index, times - step.index, amount * (times - step.index))
                }
                TransferNotify.showProgress(
                    app, username, s.sent, s.total, amount,
                    "sent to @$recipient" + if (step.fee > 0) " · fee ${naira(step.fee)}" else ""
                )
            }
            is RunStep.Skipped -> {
                s.status = "Failed ${step.index} - ${step.reason}"
                s.log = listOf("${step.index}/${times} · could not send ${naira(amount)} to @$recipient - ${step.reason}") + s.log.take(100)
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
