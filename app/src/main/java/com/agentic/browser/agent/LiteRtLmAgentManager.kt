package com.agentic.browser.agent

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalApi::class)
class LiteRtLmAgentManager(
    context: Context,
    private val modelPath: String,
    private val backendMode: BackendMode = BackendMode.CPU,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : Closeable {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val mutex = Mutex()
    private var engine: Engine? = null

    suspend fun initialize() = withContext(dispatcher) {
        mutex.withLock {
            if (engine != null) return@withLock
            val backend = when (backendMode) {
                BackendMode.CPU -> Backend.CPU()
                BackendMode.GPU -> Backend.GPU()
            }
            engine = Engine(
                EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    cacheDir = appContext.cacheDir.absolutePath
                )
            ).also { it.initialize() }
        }
    }

    suspend fun planWithPrompt(prompt: String): String = withContext(dispatcher) {
        check(!closed.get()) { "Agent is closed" }
        initialize()
        mutex.withLock {
            val e = engine ?: error("Engine not initialized")
            e.createConversation().use { conversation ->
                runCatching {
                    val response = conversation.sendMessage(prompt, emptyMap())
                    conversation.renderMessageIntoString(response, emptyMap()).trim()
                }.getOrElse {
                    "{\"action\":\"finish\",\"message\":\"${it.message?.take(120) ?: "inference failed"}\"}"
                }
            }
        }
    }

    suspend fun plan(dom: String, userCommand: String): String {
        val fallbackPrompt = buildString {
            append(SYSTEM_PROMPT).append('\n')
            append("CURRENT_DOM:\n").append(dom.take(10_000)).append('\n')
            append("USER_TASK:\n").append(userCommand.take(1_000)).append('\n')
            append("OUTPUT:\n")
        }
        return planWithPrompt(fallbackPrompt)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) runCatching { engine?.close(); engine = null }
    }
}

enum class BackendMode { CPU, GPU }
