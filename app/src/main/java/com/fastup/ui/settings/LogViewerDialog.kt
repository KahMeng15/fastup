package com.fastup.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fastup.ui.theme.*
import com.fastup.util.AppLogger
import com.fastup.util.LogLevel
import kotlinx.coroutines.launch

@Composable
fun LogViewerDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val logs by AppLogger.logs.collectAsState()
    var selectedFilter by remember { mutableStateOf<LogLevel?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredLogs = remember(logs, selectedFilter, searchQuery) {
        logs.filter { entry ->
            (selectedFilter == null || entry.level == selectedFilter) &&
            (searchQuery.isBlank() || entry.message.contains(searchQuery, ignoreCase = true) || entry.tag.contains(searchQuery, ignoreCase = true))
        }
    }

    val listState = rememberLazyListState()

    // Auto-scroll to bottom on new log if already near bottom
    LaunchedEffect(filteredLogs.size) {
        if (filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.size - 1)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = Slate900,
            border = androidx.compose.foundation.BorderStroke(1.dp, Slate700)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Technical Logs",
                            color = Slate100,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${filteredLogs.size} of ${logs.size} entries",
                            color = Slate400,
                            fontSize = 12.sp
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Copy All Logs
                        IconButton(
                            onClick = {
                                if (logs.isEmpty()) {
                                    Toast.makeText(context, "No logs to copy", Toast.LENGTH_SHORT).show()
                                    return@IconButton
                                }
                                val text = logs.joinToString("\n") { "[${it.formattedTime}] [${it.level.name}] [${it.tag}] ${it.message}" }
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("FastUp Logs", text))
                                Toast.makeText(context, "Copied all logs to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy Logs", tint = Slate300)
                        }

                        // Clear Logs
                        IconButton(
                            onClick = {
                                AppLogger.clear()
                                Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Clear Logs", tint = Rose500)
                        }

                        // Close Dialog
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = Slate300)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Filters and Search
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Filter Chips
                    FilterChip(
                        selected = selectedFilter == null,
                        onClick = { selectedFilter = null },
                        label = { Text("All", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Blue500.copy(alpha = 0.3f),
                            selectedLabelColor = Blue400,
                            containerColor = Slate800,
                            labelColor = Slate400
                        )
                    )
                    FilterChip(
                        selected = selectedFilter == LogLevel.INFO,
                        onClick = { selectedFilter = if (selectedFilter == LogLevel.INFO) null else LogLevel.INFO },
                        label = { Text("INFO", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Emerald500.copy(alpha = 0.3f),
                            selectedLabelColor = Emerald400,
                            containerColor = Slate800,
                            labelColor = Slate400
                        )
                    )
                    FilterChip(
                        selected = selectedFilter == LogLevel.WARN,
                        onClick = { selectedFilter = if (selectedFilter == LogLevel.WARN) null else LogLevel.WARN },
                        label = { Text("WARN", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Amber500.copy(alpha = 0.3f),
                            selectedLabelColor = Amber400,
                            containerColor = Slate800,
                            labelColor = Slate400
                        )
                    )
                    FilterChip(
                        selected = selectedFilter == LogLevel.ERROR,
                        onClick = { selectedFilter = if (selectedFilter == LogLevel.ERROR) null else LogLevel.ERROR },
                        label = { Text("ERROR", fontSize = 11.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Rose500.copy(alpha = 0.3f),
                            selectedLabelColor = Rose400,
                            containerColor = Slate800,
                            labelColor = Slate400
                        )
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Filter logs (e.g. upload, ping, camera)...", fontSize = 12.sp, color = Slate500) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Slate600,
                        unfocusedBorderColor = Slate700,
                        focusedTextColor = Slate100,
                        unfocusedTextColor = Slate100,
                        focusedContainerColor = Slate800,
                        unfocusedContainerColor = Slate800
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Log output box
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF0F172A),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Slate800)
                ) {
                    if (filteredLogs.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (logs.isEmpty()) "No logs captured yet." else "No logs match the current filter.",
                                color = Slate500,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        SelectionContainer {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(filteredLogs, key = { it.id }) { item ->
                                    val levelColor = when (item.level) {
                                        LogLevel.DEBUG -> Slate400
                                        LogLevel.INFO -> Emerald400
                                        LogLevel.WARN -> Amber400
                                        LogLevel.ERROR -> Rose400
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Text(
                                            text = item.formattedTime,
                                            color = Slate500,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Normal
                                        )
                                        Text(
                                            text = item.level.name.take(4).padEnd(4),
                                            color = levelColor,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "[${item.tag}]",
                                            color = Slate400,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = item.message,
                                            color = Slate200,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
