package com.agentic.browser.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMemoryRepositoryTest {

    @Test
    fun insertsFacts() = runBlocking {
        val dao = FakeExtractedFactDao()
        val repo = AgentMemoryRepository(dao)

        repo.saveFact("company", "OpenAI", "https://example.com")

        assertEquals(1, dao.storage.size)
        assertEquals("company", dao.storage.first().keyName)
    }

    @Test
    fun searchesFacts() = runBlocking {
        val dao = FakeExtractedFactDao()
        dao.insertFact(ExtractedFact(keyName = "search_term", value = "MediaPipe LLM", timestamp = 1L, sourceUrl = "u"))
        dao.insertFact(ExtractedFact(keyName = "other", value = "something else", timestamp = 2L, sourceUrl = "u"))
        val repo = AgentMemoryRepository(dao)

        val block = repo.buildMemoryContext("MediaPipe", limit = 3)

        assertNotNull(block)
        assertTrue(block!!.contains("MediaPipe LLM"))
    }

    @Test
    fun trimsMemoryOutput() = runBlocking {
        val dao = FakeExtractedFactDao()
        val longValue = "x".repeat(400)
        dao.insertFact(ExtractedFact(keyName = "k", value = longValue, timestamp = 1L, sourceUrl = "u"))
        val repo = AgentMemoryRepository(dao)

        val block = repo.buildMemoryContext("k", limit = 1)

        assertNotNull(block)
        assertTrue(block!!.length < 400)
    }
}

private class FakeExtractedFactDao : ExtractedFactDao {
    val storage = mutableListOf<ExtractedFact>()
    private var nextId = 1L

    override suspend fun insertFact(fact: ExtractedFact) {
        val withId = if (fact.id == 0L) fact.copy(id = nextId++) else fact
        storage += withId
    }

    override suspend fun searchFacts(query: String, limit: Int): List<ExtractedFact> {
        val q = query.lowercase()
        return storage
            .filter { it.keyName.lowercase().contains(q) || it.value.lowercase().contains(q) }
            .sortedByDescending { it.timestamp }
            .take(limit)
    }

    override suspend fun getRecentFacts(limit: Int): List<ExtractedFact> {
        return storage.sortedByDescending { it.timestamp }.take(limit)
    }

    override suspend fun deleteAllFacts() {
        storage.clear()
    }

    override suspend fun deleteFact(id: Long) {
        storage.removeAll { it.id == id }
    }
}
