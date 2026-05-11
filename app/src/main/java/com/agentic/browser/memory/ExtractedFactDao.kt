package com.agentic.browser.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ExtractedFactDao {
    @Insert
    suspend fun insertFact(fact: ExtractedFact)

    @Query(
        """
        SELECT * FROM extracted_facts
        WHERE keyName LIKE '%' || :query || '%'
           OR value LIKE '%' || :query || '%'
        ORDER BY timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun searchFacts(query: String, limit: Int): List<ExtractedFact>

    @Query(
        """
        SELECT * FROM extracted_facts
        ORDER BY timestamp DESC
        LIMIT :limit
        """
    )
    suspend fun getRecentFacts(limit: Int): List<ExtractedFact>

    @Query("DELETE FROM extracted_facts")
    suspend fun deleteAllFacts()

    @Query("DELETE FROM extracted_facts WHERE id = :id")
    suspend fun deleteFact(id: Long)
}
