package com.agentic.browser

import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.agentic.browser.ui.CyberpunkBrowserScreen

class MainActivity : ComponentActivity() {
    private val viewModel: BrowserAgentViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WebView.setWebContentsDebuggingEnabled(true)
        viewModel.loadExistingModelIfAvailable()
        setContent {
            val webViewState = remember { mutableStateOf<WebView?>(null) }
            val uiState by viewModel.uiState.collectAsState()
            CyberpunkBrowserScreen(
                uiState = uiState,
                bridge = viewModel.bridge,
                webViewState = webViewState,
                onCommandChange = viewModel::updateCommand,
                onModelUrlChange = viewModel::updateModelUrl,
                onExecute = { webViewState.value?.let { viewModel.executeAgent(it) } },
                onGo = { webViewState.value?.let { viewModel.navigate(it) } },
                onE2EDdg = { webViewState.value?.let { viewModel.runE2EDuckDuckGo(it) } },
                onDownloadModel = { viewModel.downloadModel(uiState.modelUrlInput) },
                onCancelDownload = viewModel::cancelModelDownload,
                onClearLogs = viewModel::clearLogs,
                onResumeAfterHuman = { webViewState.value?.let { viewModel.resumeAfterHuman(it) } },
                onClearContext = viewModel::clearContextMemory,
                onClearRoomMemory = viewModel::clearRoomMemory,
                onDeleteModel = viewModel::deleteCurrentModel,
                onReExtractDom = { webViewState.value?.let { viewModel.reExtractDom(it) } },
                onImportLocalModel = viewModel::importLocalModel
            )
        }
    }
}
