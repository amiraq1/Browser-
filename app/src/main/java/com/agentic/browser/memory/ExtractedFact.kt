package com.agentic.browser.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "extracted_facts")
data class ExtractedFact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val keyName: String,
    val value: String,
    val timestamp: Long,
    val sourceUrl: String
)
