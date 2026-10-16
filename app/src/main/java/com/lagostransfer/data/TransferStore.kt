package com.lagostransfer.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import com.lagostransfer.SessionAccount

data class JobState(
    var busy: Boolean = false,
    var status: String = "",
    var job: Job? = null,
    val stop: AtomicBoolean = AtomicBoolean(false),
    var log: List<String> = emptyList()
)

object TransferStore {
    val accounts = mutableStateListOf<SessionAccount>()
    val ui = mutableStateMapOf<String, JobState>()
    var isSyncing by mutableStateOf(true)
    var serviceOn by mutableStateOf(false)
    fun getState(u: String) = ui.getOrPut(u) { JobState() }
}
