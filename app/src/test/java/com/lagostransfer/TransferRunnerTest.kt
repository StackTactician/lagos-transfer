package com.lagostransfer.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Behaviour of the send loop against a scripted server.
 * No network, no waiting - the clock and the sleeps are injected.
 */
class TransferRunnerTest {

    private class FakeApi(
        var balance: Long = 1_000_000L,
        var hasGame: Boolean = true,
        var lookupResult: Lookup? = null,
        val lookupFails: MutableSet<String> = mutableSetOf()
    ) : TransferApi {
        var nowMs: Long = 1_000_000L
        var lastSendAtMs: Long = 0L
        var ledgerBroken: Boolean = false
        val scripted = ArrayDeque<SendResult>()
        val calls = mutableListOf<Long>()          // amount of every sendMoney call
        val applied = mutableListOf<Pair<String, Long>>() // transfers the server really applied
        val appliedIds = mutableListOf<String>()
        val sentAt = mutableListOf<Long>()
        var applyThenDrop: Boolean = false        // server applies it, the connection dies

        override fun now(): Long = nowMs
        override fun lastSendAtMillis(): Long = lastSendAtMs
        override suspend fun resolveUsername(username: String): Lookup {
            if (username in lookupFails) return Lookup.Failed("lookup broke")
            return lookupResult ?: Lookup.Found("id-$username")
        }
        override suspend fun getBalance(): Balance = Balance(balance, null, hasGame)
        override suspend fun ledger(): List<Transfer> {
            if (ledgerBroken) throw IOException("history unavailable")
            return appliedIds.mapIndexed { i, id -> Transfer(id, applied[i].first, applied[i].second, true, false, "waiting", nowMs, null, null) }
        }
        override suspend fun claimIncoming(): List<Transfer> = emptyList()

        override suspend fun sendMoney(toId: String, amount: Long, note: String?): SendResult {
            calls.add(amount)
            val scriptedResult = if (scripted.isNotEmpty()) scripted.removeFirst() else null
            if (scriptedResult != null && scriptedResult.ambiguous) {
                if (applyThenDrop) {
                    applied.add(toId.removePrefix("id-") to amount)   // server applied it, the answer was lost
                    appliedIds.add("t${applied.size}")
                    sentAt.add(nowMs)
                    lastSendAtMs = nowMs
                }
                return scriptedResult
            }
            if (scriptedResult != null && !scriptedResult.ok) {
                return scriptedResult            // the server refused - no money moved
            }
            applied.add(toId.removePrefix("id-") to amount)
            appliedIds.add("t${applied.size}")
            sentAt.add(nowMs)
            lastSendAtMs = nowMs
            return scriptedResult ?: SendResult(ok = true, id = "t${applied.size}", amount = amount, fee = 10L, http = 200)
        }
    }

    private class Recorder {
        val statuses = mutableListOf<String>()
        val sent = mutableListOf<Int>()
        val skipped = mutableListOf<String>()
        val halted = mutableListOf<String>()
        fun on(step: RunStep) {
            when (step) {
                is RunStep.Status -> statuses.add(step.text)
                is RunStep.Balance -> Unit
                is RunStep.Sent -> sent.add(step.index)
                is RunStep.Skipped -> skipped.add(step.reason)
                is RunStep.Halted -> halted.add(step.reason)
            }
        }
    }

    private fun runner(api: FakeApi) = TransferRunner(api) { ms, stop ->
        api.nowMs += ms
        stop()
    }

    private fun run(
        api: FakeApi,
        recipient: String = "target",
        amount: Long = 1_000L,
        times: Int = 3,
        note: String? = null
    ): Recorder {
        val rec = Recorder()
        val r = runner(api)
        runBlocking {
            r.run(
                recipient = recipient,
                amount = amount,
                times = times,
                note = note,
                stop = { false },
                paused = { false },
                emit = { step -> rec.on(step) }
            )
        }
        return rec
    }

    @Test
    fun sendsEveryTime() {
        val api = FakeApi()
        val rec = run(api, times = 3)
        assertEquals(3, api.applied.size)
        assertEquals(3, rec.sent.size)
        assertTrue(rec.halted.isEmpty())
    }

    @Test
    fun keepsTheGapBetweenSends() {
        val api = FakeApi()
        run(api, times = 3)
        assertEquals(3, api.sentAt.size)
        val first = api.sentAt[1] - api.sentAt[0]
        val second = api.sentAt[2] - api.sentAt[1]
        assertTrue("gap was $first", first >= LagosClient.SEND_GAP_MS)
        assertTrue("gap was $second", second >= LagosClient.SEND_GAP_MS)
    }

    @Test
    fun droppedConnectionAfterTheServerAppliedItIsNotSentTwice() {
        val api = FakeApi()
        api.applyThenDrop = true
        api.scripted.add(SendResult(ok = false, http = 0, error = "connection reset", ambiguous = true))
        val rec = run(api, times = 1)
        assertEquals("the send must not be repeated", 1, api.calls.size)
        assertEquals(1, api.applied.size)
        assertEquals(1, rec.sent.size)
    }

    @Test
    fun droppedConnectionBeforeTheServerAppliedItIsRetriedOnce() {
        val api = FakeApi()
        api.applyThenDrop = false
        api.scripted.add(SendResult(ok = false, http = 0, error = "connection reset", ambiguous = true))
        val rec = run(api, times = 1)
        assertEquals("one call lost, one real send", 2, api.calls.size)
        assertEquals(1, api.applied.size)
        assertEquals(1, rec.sent.size)
    }

    @Test
    fun unknownStateIsNotResent() {
        val api = FakeApi()
        api.ledgerBroken = true
        api.scripted.add(SendResult(ok = false, http = 0, error = "connection reset", ambiguous = true))
        val rec = run(api, times = 1)
        assertEquals("no blind resend when the server cannot be asked", 1, api.calls.size)
        assertTrue(rec.skipped.any { it.contains("Unknown") })
    }

    @Test
    fun rateLimitIsWaitedOutAndTheSameSendIsRepeated() {
        val api = FakeApi()
        api.scripted.add(SendResult(ok = false, http = 429, error = "Too many tries"))
        val rec = run(api, times = 1)
        assertEquals(2, api.calls.size)
        assertEquals(1, api.applied.size)
        assertEquals(1, rec.sent.size)
    }

    @Test
    fun pinProblemStopsWithoutRetrying() {
        val api = FakeApi()
        api.scripted.add(SendResult(ok = false, http = 401, error = "Enter your transaction PIN to send", errorCode = "pin"))
        val rec = run(api, times = 1)
        assertEquals("a wrong pin burns lockout tries - never retry it", 1, api.calls.size)
        assertTrue(rec.halted.any { it.contains("PIN") })
    }

    @Test
    fun wrongPinStopsWithoutRetrying() {
        val api = FakeApi()
        api.scripted.add(SendResult(ok = false, http = 401, error = "Wrong PIN", errorCode = "pin_wrong"))
        val rec = run(api, times = 1)
        assertEquals(1, api.calls.size)
        assertTrue(rec.halted.any { it.contains("PIN") })
    }

    @Test
    fun fundsHoldStopsTheRun() {
        val api = FakeApi()
        api.scripted.add(SendResult(ok = false, http = 403, error = "of your balance can't be sent to other players yet"))
        val rec = run(api, times = 2)
        assertEquals(1, api.calls.size)
        assertTrue(rec.halted.isNotEmpty())
    }

    @Test
    fun notEnoughFundsStopsBeforeSending() {
        val api = FakeApi(balance = 500L)
        val rec = run(api, amount = 1_000L, times = 2)
        assertEquals(0, api.calls.size)
        assertTrue(rec.halted.any { it.contains("Not enough funds") })
    }

    @Test
    fun accountWithoutGameStateStopsBeforeSending() {
        val api = FakeApi(hasGame = false)
        val rec = run(api, times = 1)
        assertEquals(0, api.calls.size)
        assertTrue(rec.halted.isNotEmpty())
    }

    @Test
    fun unknownRecipientStopsTheRun() {
        val api = FakeApi()
        api.lookupResult = Lookup.NotFound
        val rec = run(api, times = 1)
        assertEquals(0, api.calls.size)
        assertTrue(rec.halted.any { it.contains("No user") })
    }

    @Test
    fun shortNoteStopsBeforeSending() {
        val api = FakeApi()
        val rec = run(api, times = 1, note = "hi")
        assertEquals(0, api.calls.size)
        assertTrue(rec.halted.any { it.contains("Note") })
    }

    @Test
    fun amountOutOfRangeStopsBeforeSending() {
        val api = FakeApi()
        val rec = run(api, amount = 50L, times = 1)
        assertEquals(0, api.calls.size)
        assertTrue(rec.halted.any { it.contains("Minimum") })
    }

    @Test
    fun twoAccountsCanSendAtTheSameTime() {
        val a = FakeApi()
        val b = FakeApi()
        runBlocking {
            val ra = async(Dispatchers.Default) {
                runner(a).run("target", 1_000L, 2, null, { false }, { false }, { })
            }
            val rb = async(Dispatchers.Default) {
                runner(b).run("target", 1_000L, 2, null, { false }, { false }, { })
            }
            ra.await()
            rb.await()
        }
        assertEquals(2, a.applied.size)
        assertEquals(2, b.applied.size)
    }

    @Test
    fun stopEndsTheRun() {
        val api = FakeApi()
        val rec = Recorder()
        val r = runner(api)
        var stop = false
        runBlocking {
            r.run(
                recipient = "target",
                amount = 1_000L,
                times = 5,
                note = null,
                stop = { stop },
                paused = { false },
                emit = { step ->
                    rec.on(step)
                    if (rec.sent.size >= 1) stop = true
                }
            )
        }
        assertEquals(1, api.applied.size)
        assertTrue(rec.halted.any { it.contains("Stopped") })
    }

    @Test
    fun parsesRecipientList() {
        assertEquals(listOf("adewale", "chidi"), parseRecipients("adewale, chidi"))
        assertEquals(listOf("a", "b", "c"), parseRecipients("a b,c"))
        assertEquals(listOf("adewale"), parseRecipients("  adewale  "))
        assertEquals(emptyList<String>(), parseRecipients("   "))
    }

    @Test
    fun servesRecipientsOneAfterAnother() {
        val api = FakeApi()
        val rec = Recorder()
        runBlocking {
            runRecipients(
                api, listOf("adewale", "chidi", "epe"), 1_000L, 2, null,
                { ms, stop -> api.nowMs += ms; stop() },
                { false }, { false }
            ) { _, step -> rec.on(step) }
        }
        assertEquals(
            listOf("adewale", "adewale", "chidi", "chidi", "epe", "epe"),
            api.applied.map { it.first }
        )
        assertEquals(6, rec.sent.size)
    }

    @Test
    fun fatalProblemStopsTheWholeList() {
        val api = FakeApi()
        api.scripted.add(SendResult(ok = false, http = 403, error = "balance can't be sent"))
        runBlocking {
            runRecipients(
                api, listOf("adewale", "chidi"), 1_000L, 1, null,
                { ms, stop -> api.nowMs += ms; stop() },
                { false }, { false }
            ) { _, _ -> }
        }
        assertEquals("the list must stop on an account level problem", 1, api.calls.size)
    }

    @Test
    fun unknownRecipientDoesNotStopTheList() {
        val api = FakeApi()
        api.lookupFails.add("ghost")
        runBlocking {
            runRecipients(
                api, listOf("ghost", "chidi"), 1_000L, 1, null,
                { ms, stop -> api.nowMs += ms; stop() },
                { false }, { false }
            ) { _, _ -> }
        }
        assertEquals(listOf("chidi"), api.applied.map { it.first })
    }
}
