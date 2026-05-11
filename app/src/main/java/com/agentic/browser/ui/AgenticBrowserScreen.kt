package com.agentic.browser.ui

import android.webkit.WebView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.agentic.browser.AgentBrowserViewModel
import com.agentic.browser.web.AgenticWebViewFactory

private val VoidBlack = Color(0xFF0A0A0A)
private val PanelBlack = Color(0xFF111111)
private val NeonGreen = Color(0xFF39FF14)
private val NeonCyan = Color(0xFF00F5FF)
private val SoftText = Color(0xFFE6FFF8)
private val MutedText = Color(0xFF8AA6A3)

@Composable
fun AgenticBrowserScreen(
    viewModel: AgentBrowserViewModel,
    webViewState: MutableState<WebView?>,
    onRunAgent: () -> Unit,
    onRunE2E: () -> Unit
) {
    val context = LocalContext.current
    val prompt by viewModel.prompt.collectAsState()
    val statusLines by viewModel.statusLines.collectAsState()
    val logState = rememberLazyListState()
    LaunchedEffect(statusLines.size) {
        if (statusLines.isNotEmpty()) logState.animateScrollToItem(statusLines.lastIndex)
    }
    Column(Modifier.fillMaxSize().background(VoidBlack).padding(12.dp)) {
        Box(Modifier.fillMaxWidth().weight(.70f).shadow(18.dp, RoundedCornerShape(24.dp), ambientColor = NeonCyan, spotColor = NeonGreen).border(1.dp, Brush.linearGradient(listOf(NeonCyan, NeonGreen)), RoundedCornerShape(24.dp)).background(PanelBlack, RoundedCornerShape(24.dp)).padding(2.dp)) {
            AndroidView(factory = {
                AgenticWebViewFactory(context, viewModel.bridge, viewModel::setStatus).create().also { webViewState.value = it }
            }, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth().weight(.30f).border(BorderStroke(1.dp, Brush.linearGradient(listOf(NeonGreen, NeonCyan))), RoundedCornerShape(28.dp)), shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = Color(0xE60F0F0F))) {
            Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("AGENTIC BROWSER", color = NeonGreen, fontSize = 13.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
                    Spacer(Modifier.weight(1f))
                    Text("LiteRT-LM / ON-DEVICE", color = NeonCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BentoPanel("PROMPT", Modifier.weight(1.2f)) {
                        BasicTextField(value = prompt, onValueChange = viewModel::updatePrompt, textStyle = TextStyle(color = SoftText, fontSize = 15.sp, fontFamily = FontFamily.Monospace), modifier = Modifier.fillMaxWidth().weight(1f).border(1.dp, Brush.linearGradient(listOf(NeonGreen, NeonCyan)), RoundedCornerShape(16.dp)).background(Color(0xFF050505), RoundedCornerShape(16.dp)).padding(14.dp), decorationBox = { inner -> if(prompt.isBlank()) Text("Search or command...", color = MutedText, fontFamily = FontFamily.Monospace); inner() })
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CyberButton("RUN", onRunAgent)
                            CyberButton("E2E DDG", onRunE2E)
                            CyberButton("GO") { webViewState.value?.let { AgenticWebViewFactory(context, viewModel.bridge, viewModel::setStatus).also { f -> it.loadUrl(f.normalizeUrl(prompt)) } } }
                        }
                    }
                    BentoPanel("STATUS", Modifier.weight(1f)) {
                        LazyColumn(
                            state = logState,
                            modifier = Modifier.fillMaxSize().border(
                                1.dp,
                                Color(0x3339FF14),
                                RoundedCornerShape(12.dp)
                            ).padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(statusLines.takeLast(20)) { line ->
                                TerminalLogText(line)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun BentoPanel(title: String, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) { Column(modifier.fillMaxHeight().border(1.dp, Color(0x6639FF14), RoundedCornerShape(20.dp)).background(Color(0xFF080808), RoundedCornerShape(20.dp)).padding(12.dp)) { Text(title, color = NeonCyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp); Spacer(Modifier.height(8.dp)); content() } }
@Composable private fun CyberButton(text: String, onClick: () -> Unit) { OutlinedButton(onClick, colors = ButtonDefaults.outlinedButtonColors(contentColor = NeonGreen, containerColor = Color.Transparent), border = BorderStroke(1.dp, NeonGreen), shape = RoundedCornerShape(14.dp)) { Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp) } }
@Composable private fun TerminalLogText(text: String) { Text("> $text", color = SoftText, fontSize = 10.sp, lineHeight = 13.sp, fontFamily = FontFamily.Monospace) }
