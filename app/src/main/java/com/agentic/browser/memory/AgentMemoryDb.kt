package com.agentic.browser.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ExtractedFact::class], version = 1, exportSchema = false)
abstract class AgentMemoryDb : RoomDatabase() {
    abstract fun extractedFactDao(): ExtractedFactDao

    companion object {
        @Volatile private var instance: AgentMemoryDb? = null

        fun getInstance(context: Context): AgentMemoryDb {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AgentMemoryDb::class.java,
                    "agent_memory.db"
                ).fallbackToDestructiveMigration(dropAllTables = true).build().also { instance = it }
            }
        }
    }
}
