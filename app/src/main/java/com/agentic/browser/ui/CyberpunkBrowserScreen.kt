package com.agentic.browser.ui

import android.net.Uri
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.agentic.browser.BrowserAgentUiState
import com.agentic.browser.UpdateCheckOutcome
import com.agentic.browser.model.ModelDownloadState
import com.agentic.browser.model.ModelImportState
import com.agentic.browser.web.AgentBridge
import com.agentic.browser.web.AgenticWebViewFactory

private val BgBlack = Color(0xFF080808)
private val PanelBlack = Color(0xFF101010)
private val TerminalBlack = Color(0xFF0D0D0D)
private val NeonGreen = Color(0xFF39FF14)
private val NeonCyan = Color(0xFF00F5FF)
private val WarningAmber = Color(0xFFFFB347)
private val WarningRed = Color(0xFFFF4D4D)
private val SoftText = Color(0xFFDAFFF2)
private val MutedText = Color(0xFF88A69F)

@Composable
fun CyberpunkBrowserScreen(
    uiState: BrowserAgentUiState,
    bridge: AgentBridge,
    webViewState: MutableState<WebView?>,
    onCommandChange: (String) -> Unit,
    onModelUrlChange: (String) -> Unit,
    onExecute: () -> Unit,
    onGo: () -> Unit,
    onE2EDdg: () -> Unit,
    onDownloadModel: () -> Unit,
    onCancelDownload: () -> Unit,
    onClearLogs: () -> Unit,
    onResumeAfterHuman: () -> Unit,
    onClearContext: () -> Unit,
    onClearRoomMemory: () -> Unit,
    onDeleteModel: () -> Unit,
    onReExtractDom: () -> Unit,
    onImportLocalModel: (Uri) -> Unit,
    onCheckUpdates: () -> Unit,
    onOpenRelease: (String) -> Unit
) {
    val context = LocalContext.current
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onImportLocalModel(uri)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBlack)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        WebViewPanel(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.60f),
            bridge = bridge,
            webViewState = webViewState,
            context = context
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.40f),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TerminalPanel(
                modifier = Modifier.weight(0.40f),
                logLines = uiState.terminalLines
            )
            CommandCenterPanel(
                modifier = Modifier.weight(0.60f),
                command = uiState.command,
                modelUrl = uiState.modelUrlInput,
                modelState = uiState.modelState,
                modelImportState = uiState.modelImportState,
                isModelReady = uiState.isModelReady,
                isFirstRunModelSetup = uiState.isFirstRunModelSetup,
                contextUsagePercent = uiState.contextUsagePercent,
                currentUrl = uiState.currentUrl,
                isBusy = uiState.isAgentBusy,
                isAwaitingHuman = uiState.isAwaitingHuman,
                humanReason = uiState.humanFallbackReason,
                onCommandChange = onCommandChange,
                onModelUrlChange = onModelUrlChange,
                onExecute = onExecute,
                onGo = onGo,
                onE2EDdg = onE2EDdg,
                onDownloadModel = onDownloadModel,
                onCancelDownload = onCancelDownload,
                onClearLogs = onClearLogs,
                onResumeAfterHuman = onResumeAfterHuman,
                onClearContext = onClearContext,
                onClearRoomMemory = onClearRoomMemory,
                onDeleteModel = onDeleteModel,
                onReExtractDom = onReExtractDom,
                onImportLocalModelLaunch = { importLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                updateState = uiState.updateState,
                onCheckUpdates = onCheckUpdates,
                onOpenRelease = onOpenRelease
            )
        }
    }
}

@Composable
private fun WebViewPanel(
    modifier: Modifier,
    bridge: AgentBridge,
    webViewState: MutableState<WebView?>,
    context: android.content.Context
) {
    PanelShell(modifier = modifier, title = "TARGET") {
        AndroidView(
            factory = {
                AgenticWebViewFactory(context, bridge) {}.create().also { webViewState.value = it }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun TerminalPanel(modifier: Modifier, logLines: List<String>) {
    val listState = rememberLazyListState()
    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) listState.animateScrollToItem(logLines.lastIndex)
    }

    PanelShell(modifier = modifier, title = "TERMINAL") {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(TerminalBlack, RoundedCornerShape(12.dp))
                .border(1.dp, Color(0x5539FF14), RoundedCornerShape(12.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(logLines.takeLast(50)) { line ->
                Text(
                    text = "› $line",
                    color = SoftText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 13.sp
                )
            }
        }
    }
}

@Composable
private fun CommandCenterPanel(
    modifier: Modifier,
    command: String,
    modelUrl: String,
    modelState: ModelDownloadState,
    modelImportState: ModelImportState,
    isModelReady: Boolean,
    isFirstRunModelSetup: Boolean,
    contextUsagePercent: Int,
    currentUrl: String?,
    isBusy: Boolean,
    isAwaitingHuman: Boolean,
    humanReason: String?,
    onCommandChange: (String) -> Unit,
    onModelUrlChange: (String) -> Unit,
    onExecute: () -> Unit,
    onGo: () -> Unit,
    onE2EDdg: () -> Unit,
    onDownloadModel: () -> Unit,
    onCancelDownload: () -> Unit,
    onClearLogs: () -> Unit,
    onResumeAfterHuman: () -> Unit,
    onClearContext: () -> Unit,
    onClearRoomMemory: () -> Unit,
    onDeleteModel: () -> Unit,
    onReExtractDom: () -> Unit,
    onImportLocalModelLaunch: () -> Unit,
    updateState: UpdateCheckOutcome,
    onCheckUpdates: () -> Unit,
    onOpenRelease: (String) -> Unit
) {
    val panelColors = if (isAwaitingHuman) listOf(WarningRed, WarningAmber) else listOf(NeonCyan, NeonGreen)
    val panelTitleColor = if (isAwaitingHuman) WarningAmber else NeonGreen

    PanelShell(
        modifier = modifier,
        title = "COMMAND CENTER",
        borderColors = panelColors,
        titleColor = panelTitleColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val agentState = when {
                isAwaitingHuman -> "paused"
                isBusy -> "busy"
                else -> "idle"
            }
            Text(
                text = "Agent: $agentState",
                color = MutedText,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
            Text(
                text = "URL: ${(currentUrl ?: "about:blank").take(80)}",
                color = MutedText,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
            if (currentUrl.isNullOrBlank()) {
                Text(
                    text = "DOM not ready",
                    color = WarningAmber,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
            }

            if (isAwaitingHuman) {
                HumanFallbackBanner(humanReason.orEmpty())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CyberButton("Resume", onResumeAfterHuman, enabled = !isBusy, color = WarningAmber)
                    CyberButton("Clear Logs", onClearLogs, enabled = !isBusy, color = WarningAmber)
                }
            }

            if (!isModelReady) {
                val msg = if (isFirstRunModelSetup) {
                    "No model loaded. Enter a model URL and download to start."
                } else {
                    "Model not ready. Validate or download a model."
                }
                Text(
                    text = msg,
                    color = WarningAmber,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
            }

            Label("Command")
            InputField(command, "Type browser task...", onCommandChange)
            if (command.isBlank()) {
                Text(
                    text = "Waiting for command",
                    color = MutedText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
            }

            Spacer(Modifier.height(4.dp))
            Label("Model URL")
            InputField(modelUrl, "https://.../model.litertlm", onModelUrlChange)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val isDownloading = modelState is ModelDownloadState.Downloading
                CyberButton("Download", onDownloadModel, enabled = !isDownloading && !isBusy)
                CyberButton("Cancel", onCancelDownload, enabled = isDownloading)
            }
            ActionGrid(
                isModelReady = isModelReady,
                isBusy = isBusy,
                isAwaitingHuman = isAwaitingHuman,
                onGo = onGo,
                onExecute = onExecute,
                onE2EDdg = onE2EDdg,
                onImportLocalModelLaunch = onImportLocalModelLaunch,
                onClearLogs = onClearLogs,
                onClearContext = onClearContext,
                onClearRoomMemory = onClearRoomMemory,
                onReExtractDom = onReExtractDom,
                onDeleteModel = onDeleteModel,
                updateState = updateState,
                onCheckUpdates = onCheckUpdates,
                onOpenRelease = onOpenRelease
            )

            Spacer(Modifier.height(4.dp))
            ModelStatusIndicator(modelState, modelImportState)
            ContextHealthIndicator(contextUsagePercent)
        }
    }
}

@Composable
private fun ActionGrid(
    isModelReady: Boolean,
    isBusy: Boolean,
    isAwaitingHuman: Boolean,
    onGo: () -> Unit,
    onExecute: () -> Unit,
    onE2EDdg: () -> Unit,
    onImportLocalModelLaunch: () -> Unit,
    onClearLogs: () -> Unit,
    onClearContext: () -> Unit,
    onClearRoomMemory: () -> Unit,
    onReExtractDom: () -> Unit,
    onDeleteModel: () -> Unit,
    updateState: UpdateCheckOutcome,
    onCheckUpdates: () -> Unit,
    onOpenRelease: (String) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CyberButton("Go", onGo, enabled = !isBusy, modifier = Modifier.weight(1f))
        CyberButton("Execute", onExecute, enabled = isModelReady && !isBusy && !isAwaitingHuman, modifier = Modifier.weight(1f))
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CyberButton("E2E DDG", onE2EDdg, enabled = !isBusy && !isAwaitingHuman, modifier = Modifier.weight(1f))
        CyberButton("Import Local Model", onImportLocalModelLaunch, enabled = !isBusy, modifier = Modifier.weight(1f))
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CyberButton("Clear Logs", onClearLogs, enabled = !isBusy, modifier = Modifier.weight(1f))
        CyberButton("Clear Ctx", onClearContext, enabled = !isBusy, modifier = Modifier.weight(1f))
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CyberButton("Clear Room", onClearRoomMemory, enabled = !isBusy, modifier = Modifier.weight(1f))
        CyberButton("Re-DOM", onReExtractDom, enabled = !isBusy, modifier = Modifier.weight(1f))
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CyberButton("Delete Model", onDeleteModel, enabled = !isBusy, modifier = Modifier.weight(1f))
        val isChecking = updateState is UpdateCheckOutcome.Checking
        CyberButton(
            text = if (isChecking) "Checking..." else "Check Updates",
            onClick = onCheckUpdates,
            enabled = !isChecking,
            color = NeonCyan,
            modifier = Modifier.weight(1f)
        )
    }
    UpdateStatusRow(updateState = updateState, onOpenRelease = onOpenRelease)
}

@Composable
private fun UpdateStatusRow(
    updateState: UpdateCheckOutcome,
    onOpenRelease: (String) -> Unit
) {
    when (updateState) {
        is UpdateCheckOutcome.Idle -> Unit
        is UpdateCheckOutcome.Checking -> Text(
            text = "Update: checking GitHub releases",
            color = NeonCyan,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )
        is UpdateCheckOutcome.UpToDate -> Text(
            text = "Update: up-to-date (${updateState.currentVersion})",
            color = NeonGreen,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )
        is UpdateCheckOutcome.Available -> {
            Text(
                text = "Update available: ${updateState.latestVersion}",
                color = WarningAmber,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
            if (updateState.url.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CyberButton(
                        text = "Open Release",
                        onClick = { onOpenRelease(updateState.url) },
                        color = WarningAmber,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
        is UpdateCheckOutcome.Error -> Text(
            text = "Update error: ${updateState.message.take(80)}",
            color = WarningRed,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun HumanFallbackBanner(reason: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF120B08), RoundedCornerShape(12.dp))
            .border(1.dp, WarningAmber, RoundedCornerShape(12.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "AGENT PAUSED",
            color = WarningAmber,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp
        )
        Text(
            text = reason.ifBlank { "Human intervention required." },
            color = SoftText,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            lineHeight = 13.sp
        )
        Text(
            text = "Complete the required action in the WebView, then tap Resume.",
            color = MutedText,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun ModelStatusIndicator(state: ModelDownloadState, importState: ModelImportState) {
    val text = when {
        importState is ModelImportState.Importing -> {
            val pct = if (importState.progressPercent >= 0) "${importState.progressPercent}%" else "..."
            "Model: Importing $pct"
        }
        importState is ModelImportState.Validating -> "Model: Validating imported file"
        importState is ModelImportState.Ready -> "Model: Recovered"
        importState is ModelImportState.Error -> "Model: Import error - ${importState.message.take(80)}"
        else -> when (state) {
            ModelDownloadState.Idle -> "Model: Not ready"
            is ModelDownloadState.Downloading -> {
                val pct = if (state.progressPercent >= 0) "${state.progressPercent}%" else "..."
                "Model: Downloading $pct"
            }
            ModelDownloadState.Extracting -> "Model: Extracting"
            is ModelDownloadState.Ready -> "Model: Ready"
            is ModelDownloadState.Error -> "Model: Error - ${state.message.take(80)}"
        }
    }

    Text(
        text = text,
        color = if (state is ModelDownloadState.Error || importState is ModelImportState.Error) WarningRed else NeonCyan,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp
    )

    if (state is ModelDownloadState.Downloading && state.progressPercent >= 0) {
        LinearProgressIndicator(
            progress = { state.progressPercent / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = NeonGreen,
            trackColor = Color(0xFF202020)
        )
    }
    if (importState is ModelImportState.Importing && importState.progressPercent >= 0) {
        LinearProgressIndicator(
            progress = { importState.progressPercent / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = NeonCyan,
            trackColor = Color(0xFF202020)
        )
    }
}

@Composable
private fun ContextHealthIndicator(percent: Int) {
    Text(
        text = "Estimated Context Usage: $percent%",
        color = MutedText,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp
    )
    LinearProgressIndicator(
        progress = { (percent.coerceIn(0, 100)) / 100f },
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp),
        color = if (percent < 75) NeonCyan else WarningAmber,
        trackColor = Color(0xFF202020)
    )
}

@Composable
private fun PanelShell(
    modifier: Modifier,
    title: String,
    borderColors: List<Color> = listOf(NeonCyan, NeonGreen),
    titleColor: Color = NeonGreen,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .border(
                border = BorderStroke(
                    1.dp,
                    Brush.linearGradient(borderColors)
                ),
                shape = RoundedCornerShape(16.dp)
            )
            .background(PanelBlack, RoundedCornerShape(16.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            color = titleColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            letterSpacing = 1.2.sp
        )
        Box(modifier = Modifier.fillMaxSize()) { content() }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text = text,
        color = NeonCyan,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp
    )
}

@Composable
private fun InputField(value: String, hint: String, onChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        textStyle = TextStyle(color = SoftText, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF090909), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0x5539FF14), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        decorationBox = { inner ->
            if (value.isBlank()) {
                Text(
                    text = hint,
                    color = MutedText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
            inner()
        }
    )
}

@Composable
private fun CyberButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    color: Color = NeonGreen,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor = color,
            disabledContentColor = Color(0xFF4C5A57)
        ),
        border = BorderStroke(1.dp, if (enabled) color else Color(0xFF2D3A37)),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.widthIn(min = 84.dp)
    ) {
        Text(text = text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}
