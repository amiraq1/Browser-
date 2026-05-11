package com.agentic.browser.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AgentMemoryRepository(private val dao: ExtractedFactDao) {
    suspend fun saveFact(keyName: String, value: String, sourceUrl: String) = withContext(Dispatchers.IO) {
        val cleanKey = keyName.trim().take(64)
        val cleanValue = value.trim().take(2_000)
        if (cleanKey.isBlank() || cleanValue.isBlank()) return@withContext
        dao.insertFact(
            ExtractedFact(
                keyName = cleanKey,
                value = cleanValue,
                timestamp = System.currentTimeMillis(),
                sourceUrl = sourceUrl.trim().take(300)
            )
        )
    }

    suspend fun buildMemoryContext(userTask: String, limit: Int = 4): String? = withContext(Dispatchers.IO) {
        val q = userTask.trim()
        val facts = if (q.isBlank()) {
            dao.getRecentFacts(limit)
        } else {
            val tokens = Regex("[A-Za-z0-9_]{3,}")
                .findAll(q.lowercase())
                .map { it.value }
                .distinct()
                .take(4)
                .toList()
            val merged = linkedMapOf<Long, ExtractedFact>()
            if (tokens.isEmpty()) {
                dao.searchFacts(q.take(120), limit).forEach { merged[it.id] = it }
            } else {
                tokens.forEach { token ->
                    dao.searchFacts(token, limit).forEach { fact ->
                        if (merged.size < limit) merged[fact.id] = fact
                    }
                }
            }
            if (merged.isEmpty()) dao.getRecentFacts(limit) else merged.values.toList()
        }
        if (facts.isEmpty()) return@withContext null
        val block = buildString {
            append("<MEMORY>\n")
            facts.take(limit).forEach { fact ->
                append(fact.keyName).append(": ").append(fact.value.take(180)).append('\n')
                append("source_url: ").append(fact.sourceUrl.take(180)).append('\n')
            }
            append("</MEMORY>")
        }
        block
    }

    suspend fun clearAllFacts() = withContext(Dispatchers.IO) {
        dao.deleteAllFacts()
    }
}
