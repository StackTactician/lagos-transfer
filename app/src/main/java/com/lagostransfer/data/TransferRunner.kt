package com.lagostransfer.data

import kotlinx.coroutines.delay
import kotlin.math.max

/** Splits the recipient field into single usernames - comma, semicolon or space. */
fun parseRecipients(text: String): List<String> =
    text.split(Regex("[,;\\s]+")).map { it.trim() }.filter { it.isNotEmpty() }

sealed class RunStep {
    data class Status(val text: String) : RunStep()
    data class Sent(val index: Int, val id: String?, val fee: Long) : RunStep()
    data class Skipped(val index: Int, val reason: String) : RunStep()
    /** fatal = the whole account must stop, not just this recipient. */
    data class Halted(val reason: String, val fatal: Boolean = false) : RunStep()
    data class Balance(val money: Long) : RunStep()
}

/**
 * Send loop for one account. Pure logic - no Android types - so the behaviour
 * (cooldown, retry, reconciliation) is testable on its own.
 *
 * The server has no idempotency on POST /api/send, so a blind retry after a
 * dropped connection can pay the receiver twice. Every ambiguous failure is
 * therefore checked against the server ledger before anything is sent again.
 */
/** Sleeps in one second slices and wakes up early when the run is stopped. */
suspend fun realSleep(ms: Long, stop: () -> Boolean): Boolean {
    var left = ms
    while (left > 0) {
        if (stop()) return true
        val slice = if (left > 1_000L) 1_000L else left
        delay(slice)
        left -= slice
    }
    return stop()
}

class TransferRunner(
    private val api: TransferApi,
    private val sleeper: suspend (Long, () -> Boolean) -> Boolean = ::realSleep
) {

    /** Ids of our transfers to this recipient for this amount - set difference tells us what landed. */
    private suspend fun outgoingIds(recipient: String, amount: Long): Set<String> {
        val list = api.ledger()
        return list.filter { it.sent && it.who.equals(recipient, ignoreCase = true) && it.amount == amount }
            .map { it.id }
            .filter { it.isNotBlank() }
            .toSet()
    }

    /** Waits out the server cooldown of this account. */
    private suspend fun waitForCooldown(emit: suspend (RunStep) -> Unit, stop: () -> Boolean) {
        val last = api.lastSendAtMillis()
        if (last <= 0L) return
        val wait = last + LagosClient.SEND_GAP_MS - api.now()
        if (wait <= 0L) return
        emit(RunStep.Status("Waiting ${(wait + 999) / 1000}s"))
        sleeper(wait, stop)
    }

    suspend fun run(
        recipient: String,
        amount: Long,
        times: Int,
        note: String?,
        stop: () -> Boolean,
        paused: () -> Boolean,
        emit: suspend (RunStep) -> Unit
    ) {
        val target = recipient.trim()
        if (target.isEmpty()) { emit(RunStep.Halted("No recipient given")); return }
        if (amount < LagosClient.MIN_SEND) { emit(RunStep.Halted("Minimum send is ${LagosClient.MIN_SEND}")); return }
        if (amount > LagosClient.MAX_SEND) { emit(RunStep.Halted("Maximum send is ${LagosClient.MAX_SEND}")); return }
        if (times < 1) { emit(RunStep.Halted("Nothing to send")); return }
        if (!note.isNullOrBlank() && note.trim().length < LagosClient.MIN_NOTE_LEN) {
            emit(RunStep.Halted("Note needs at least ${LagosClient.MIN_NOTE_LEN} characters")); return
        }

        // Resolve the recipient once - an empty search result is not proof of absence.
        var toId: String? = null
        var resolveTries = 0
        while (toId == null && resolveTries < 3) {
            resolveTries++
            if (stop()) { emit(RunStep.Halted("Stopped")); return }
            when (val l = api.resolveUsername(target)) {
                is Lookup.Found -> toId = l.id
                Lookup.NotFound -> { emit(RunStep.Halted("No user @$target")); return }
                is Lookup.Failed -> {
                    emit(RunStep.Status("Looking up the recipient - trying again"))
                    delay(3_000L)
                }
            }
        }
        if (toId == null) { emit(RunStep.Halted("Could not resolve @$target")); return }

        var sent = 0
        var index = 0
        var balanceTries = 0
        while (index < times) {
            if (stop()) { emit(RunStep.Halted("Stopped after $sent of $times")); return }
            while (paused() && !stop()) { delay(1_000L) }
            if (stop()) { emit(RunStep.Halted("Stopped after $sent of $times")); return }

            index++
            val total = times

            // Server truth on money before every attempt.
            val balance = try {
                api.getBalance()
            } catch (e: Exception) {
                balanceTries++
                emit(RunStep.Status("Could not check the balance - trying again"))
                if (balanceTries >= 3) {
                    emit(RunStep.Halted("Server not reachable - ${friendlyError(e)}"))
                    return
                }
                sleeper(5_000L, stop)
                index--
                continue
            }
            balanceTries = 0
            emit(RunStep.Balance(balance.money))
            val fee = LagosClient.feeFor(amount)
            if (!balance.hasGame) {
                emit(RunStep.Halted("This account has no game state yet - open Lagos Life once first", true))
                return
            }
            if (balance.money < amount + fee) {
                emit(RunStep.Halted("Not enough funds - have ${balance.money}, need ${amount + fee}", true))
                return
            }

            var attempt = 0
            var settled = false
            var unknown = false
            while (attempt < 6 && !settled && !unknown) {
                attempt++
                if (stop()) { emit(RunStep.Halted("Stopped after $sent of $total")); return }

                // Snapshot the ledger so an ambiguous failure can be reconciled.
                val before = try {
                    outgoingIds(target, amount)
                } catch (e: Exception) {
                    emit(RunStep.Status("Could not read the transfer history"))
                    null
                }

                waitForCooldown(emit, stop)
                if (stop()) { emit(RunStep.Halted("Stopped after $sent of $total")); return }

                emit(RunStep.Status("Sending $index of $total"))
                val result = try {
                    api.sendMoney(toId, amount, note?.takeIf { it.isNotBlank() })
                } catch (e: Exception) {
                    SendResult(ok = false, http = 0, error = e.message ?: "Network error", ambiguous = true)
                }

                when {
                    result.ok -> {
                        settled = true
                        sent++
                        emit(RunStep.Sent(index, result.id, result.fee))
                    }

                    result.errorCode == "pin" -> {
                        emit(RunStep.Halted("This account has a transaction PIN - add the PIN in the account, then send again", true))
                        return
                    }

                    result.errorCode == "pin_wrong" -> {
                        emit(RunStep.Halted("Wrong transaction PIN - sending locks for 15 minutes after a few tries. Fix the PIN first.", true))
                        return
                    }

                    result.http == 429 -> {
                        emit(RunStep.Status("Server is busy - waiting ${LagosClient.RATE_LIMIT_WAIT_MS / 1000}s and trying the same send again"))
                        sleeper(LagosClient.RATE_LIMIT_WAIT_MS, stop)
                    }

                    result.http == 401 -> {
                        emit(RunStep.Halted("Session expired - log in again from Master", true))
                        return
                    }

                    result.http == 403 -> {
                        emit(RunStep.Halted(result.error ?: "The server is holding part of this balance", true))
                        return
                    }

                    result.http == 404 -> {
                        emit(RunStep.Halted(result.error ?: "Recipient not found on the server"))
                        return
                    }

                    result.ambiguous -> {
                        // The connection died. Did the money move? Ask the server.
                        val after = try {
                            outgoingIds(target, amount)
                        } catch (e: Exception) {
                            null
                        }
                        if (before == null || after == null) {
                            unknown = true
                            emit(RunStep.Status("Connection dropped and the server could not be asked - not sending again"))
                        } else if ((after - before).isNotEmpty()) {
                            settled = true
                            sent++
                            emit(RunStep.Sent(index, null, fee))
                            emit(RunStep.Status("Connection dropped - the server says it went through"))
                        } else {
                            emit(RunStep.Status("Connection dropped - trying again"))
                            sleeper(5_000L, stop)
                        }
                    }

                    else -> {
                        val reason = result.error ?: "Send failed (HTTP ${result.http})"
                        val hard = reason.contains("limit", true) || reason.contains("fund", true) ||
                            reason.contains("wallet", true) || reason.contains("at least", true) ||
                            reason.contains("up to", true) || reason.contains("frozen", true)
                        emit(RunStep.Skipped(index, reason))
                        if (hard) { emit(RunStep.Halted(reason)); return }
                        settled = true // counted as handled, move on
                    }
                }
            }

            if (unknown) {
                emit(RunStep.Skipped(index, "Unknown - check the server history before sending again"))
                return
            }
            if (!settled) {
                emit(RunStep.Skipped(index, "Send did not go through - the server kept refusing it"))
            }

            if (index < times && !stop()) {
                emit(RunStep.Status("Next send in ${LagosClient.SEND_GAP_MS / 1000}s"))
                sleeper(LagosClient.SEND_GAP_MS, stop)
            }
        }
        emit(RunStep.Status("Done - $sent of $times sent"))
    }
}

/**
 * Serves several recipients one after another: the full run of recipient one,
 * then the same amount for recipient two, and so on.
 */
/** One recipient and how many sends are still owed to it. */
data class RecipientRun(val name: String, val times: Int)

suspend fun runRecipients(
    api: TransferApi,
    recipients: List<RecipientRun>,
    amount: Long,
    note: String?,
    sleeper: suspend (Long, () -> Boolean) -> Boolean = ::realSleep,
    stop: () -> Boolean,
    paused: () -> Boolean,
    emit: suspend (String, RunStep) -> Unit,
    onStart: suspend (Int, String) -> Unit = { _, _ -> }
) {
    var index = 0
    for (target in recipients) {
        if (stop()) return
        index++
        val name = target.name
        val times = target.times
        if (times <= 0) continue
        val tag = if (recipients.size > 1) "[$index/${recipients.size}] " else ""
        var fatal = false
        val runner = TransferRunner(api, sleeper)
        onStart(index, name)
        runner.run(
            recipient = name,
            amount = amount,
            times = times,
            note = note,
            stop = stop,
            paused = paused,
            emit = { step ->
                when (step) {
                    is RunStep.Status -> emit(name, RunStep.Status(tag + step.text))
                    is RunStep.Skipped -> emit(name, RunStep.Skipped(step.index, tag + step.reason))
                    is RunStep.Halted -> {
                        fatal = step.fatal
                        emit(name, RunStep.Halted(tag + step.reason, step.fatal))
                    }
                    else -> emit(name, step)
                }
            }
        )
        if (fatal) return
    }
}
