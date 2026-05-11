# LiteRT-LM runtime classes (native-backed; avoid aggressive stripping in release).
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# Room schema and generated adapters.
-keep class com.agentic.browser.memory.ExtractedFact { *; }
-keep class com.agentic.browser.memory.ExtractedFactDao { *; }
-keep class com.agentic.browser.memory.AgentMemoryDb { *; }
-keep class * extends androidx.room.RoomDatabase
-keep class * implements androidx.room.RoomOpenHelper$Delegate
-keep class **_Impl { *; }

# WebView JS bridge methods used by JavaScriptInterface.
-keepclassmembers class com.agentic.browser.web.AgentBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Kotlin metadata/reflection safety when shrinking is enabled later.
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.reflect.**
