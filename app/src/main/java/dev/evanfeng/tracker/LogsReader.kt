package dev.evanfeng.tracker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class LogsReader {
    private val _logsFlow = MutableSharedFlow<String>(extraBufferCapacity = 100)
    val logsFlow = _logsFlow.asSharedFlow()

    suspend fun fetchInitialLogs(limit: Int = 25): List<String> = withContext(Dispatchers.IO) {
        val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", limit.toString()))
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val results = reader.readLines()
        reader.close()
        process.destroy()
        results
    }
}
