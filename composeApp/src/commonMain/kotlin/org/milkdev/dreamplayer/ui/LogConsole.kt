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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.milkdev.dreamplayer.diagnostics.LogStorage

@Composable
fun LogConsole(
    modifier: Modifier = Modifier,
    title: String = "Terminal Output",
    logsFlow: StateFlow<List<String>> = LogStorage.logs,
    onClear: () -> Unit = LogStorage::clear,
    consoleHeight: Dp = 300.dp,
    emptyText: String = "Пока пусто",
) {
    val logs by logsFlow.collectAsState()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
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
                TextButton(onClick = onClear) {
                    Text("Очистить", color = Color.Red, fontSize = 12.sp)
                }
                TextButton(
                    onClick = {
                        val allLogs = logs.joinToString("\n")
                        val clipEntry = buildTextClipEntry(allLogs)
                        scope.launch {
                            clipboard.setClipEntry(clipEntry)
                        }
                    }
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
                items(logs) { logMessage ->
                    Text(
                        text = logMessage,
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
