package org.milkdev.dreamplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement.SpaceBetween
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.milkdev.dreamplayer.app.LocalSceneExecutionPolicy
import org.milkdev.dreamplayer.diagnostics.LogEntry
import org.milkdev.dreamplayer.diagnostics.LogStorage

@Composable
fun LogConsole(
    modifier: Modifier = Modifier,
    title: String = "Terminal Output",
    logsFlow: StateFlow<List<LogEntry>> = LogStorage.logs,
    onClear: () -> Unit = LogStorage::clear,
    consoleHeight: Dp = 300.dp,
    emptyText: String = "Пока пусто",
) {
    val executionPolicy = LocalSceneExecutionPolicy.current
    var logs by remember(logsFlow) { mutableStateOf(logsFlow.value) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(
        logsFlow,
        executionPolicy.allowsDiagnosticCollection,
        executionPolicy.authorityEpoch,
    ) {
        if (!executionPolicy.allowsDiagnosticCollection) return@LaunchedEffect
        logs = logsFlow.value
        var observedTailId = logs.lastOrNull()?.sequenceId
        logsFlow.collectLatest { nextLogs ->
            val wasFollowingTail =
                logs.isEmpty() ||
                    listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index == logs.lastIndex
            val nextTailId = nextLogs.lastOrNull()?.sequenceId
            logs = nextLogs
            if (
                wasFollowingTail &&
                nextLogs.isNotEmpty() &&
                nextTailId != observedTailId
            ) {
                listState.animateScrollToItem(nextLogs.lastIndex)
            }
            observedTailId = nextTailId
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(consoleHeight)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF000000))
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = Color.LightGray,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            Row {
                TextButton(
                    enabled = executionPolicy.allowsInputAndSemantics,
                    onClick = onClear,
                ) {
                    Text("Очистить", color = Color.Red, fontSize = 12.sp)
                }
                TextButton(
                    onClick = {
                        val allLogs = logs.joinToString("\n") { entry -> entry.message }
                        val clipEntry = buildTextClipEntry(allLogs)
                        scope.launch {
                            clipboard.setClipEntry(clipEntry)
                        }
                    },
                    enabled = executionPolicy.allowsInputAndSemantics,
                ) {
                    Text("Копировать", color = Color(0xFF01FFE1), fontSize = 12.sp)
                }
            }
        }

        HorizontalDivider(color = Color.DarkGray, thickness = 1.dp)

        SelectionContainer(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
            LazyColumn(state = listState) {
                if (logs.isEmpty()) {
                    item {
                        Text(
                            text = emptyText,
                            color = Color.Gray,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
                items(
                    items = logs,
                    key = { entry -> entry.sequenceId },
                ) { logEntry ->
                    Text(
                        text = logEntry.message,
                        color = Color(0xFF00D500),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}
