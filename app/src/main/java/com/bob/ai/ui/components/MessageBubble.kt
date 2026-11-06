package com.bob.ai.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.bob.ai.domain.ChatMessage
import com.bob.ai.domain.Role

/**
 * One chat message (spec §6, §7, §9).
 *
 * - User messages: right-aligned, accent background.
 * - Assistant messages: surface bubble.
 * - Image attachments via Coil; "đã chỉnh sửa" label when [ChatMessage.edited];
 *   error text when [ChatMessage.error] != null.
 * - Actions via long-press or the menu button; all disabled while [busy].
 * - Editing is inline in place, with a full-screen expand option.
 *
 * Action availability by role:
 * - USER: Copy / Edit / Edit & Regenerate / Regenerate / Delete
 * - ASSISTANT: Copy / Edit / Branch / Delete
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: ChatMessage,
    busy: Boolean,
    modifier: Modifier = Modifier,
    highlight: String = "",
    onRegenerate: (ChatMessage) -> Unit = {},
    onDelete: (ChatMessage) -> Unit = {},
    onBranch: (ChatMessage) -> Unit = {},
    onSaveEdit: (ChatMessage, String) -> Unit = { _, _ -> },
    onSaveEditAndRegenerate: (ChatMessage, String) -> Unit = { _, _ -> }
) {
    val clipboard = LocalClipboardManager.current
    var showMenu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var editText by remember(message.id, message.content) { mutableStateOf(message.content) }
    var showFullEdit by remember { mutableStateOf(false) }
    val isUser = message.role == Role.USER

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // Image attachments (kept untouched when editing text only, spec §9).
        if (message.attachments.isNotEmpty()) {
            Row(
                horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                modifier = Modifier.fillMaxWidth()
            ) {
                message.attachments.forEach { attachment ->
                    AsyncImage(
                        model = attachment.uri,
                        contentDescription = attachment.fileName ?: "Ảnh đính kèm",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .padding(end = 4.dp, bottom = 4.dp)
                            .size(160.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top
        ) {
            if (!isUser) {
                Spacer(Modifier.width(2.dp))
            }
            Surface(
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp
                ),
                color = if (isUser) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (isUser) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { if (!busy) showMenu = true }
                    )
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    if (editing) {
                        OutlinedTextField(
                            value = editText,
                            onValueChange = { editText = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { showFullEdit = true }) {
                                Text("Toàn màn hình")
                            }
                            TextButton(onClick = {
                                editing = false
                                editText = message.content
                            }) { Text("Hủy") }
                            TextButton(
                                onClick = {
                                    editing = false
                                    onSaveEdit(message, editText)
                                },
                                enabled = editText.isNotBlank()
                            ) { Text("Lưu") }
                            if (isUser) {
                                TextButton(
                                    onClick = {
                                        editing = false
                                        onSaveEditAndRegenerate(message, editText)
                                    },
                                    enabled = editText.isNotBlank()
                                ) { Text("Lưu & tạo lại") }
                            }
                        }
                    } else if (message.content.isNotBlank()) {
                        if (isUser) {
                            Text(
                                text = withHighlightPlain(message.content, highlight),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        } else {
                            MarkdownText(
                                text = message.content,
                                highlight = highlight
                            )
                        }
                    }
                    if (message.error != null) {
                        Text(
                            text = message.error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    if (message.edited) {
                        Text(
                            text = "đã chỉnh sửa",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isUser) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f)
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
            IconButton(
                onClick = { showMenu = true },
                enabled = !busy,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "Tùy chọn tin nhắn",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Tool calls made while producing this assistant message (spec §15).
        if (!isUser && message.toolCalls.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                message.toolCalls.forEach { record ->
                    ToolCallCard(record = record)
                }
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("Sao chép") },
                onClick = {
                    showMenu = false
                    clipboard.setText(AnnotatedString(message.content))
                },
                enabled = !busy
            )
            DropdownMenuItem(
                text = { Text("Chỉnh sửa") },
                onClick = {
                    showMenu = false
                    editText = message.content
                    editing = true
                },
                enabled = !busy
            )
            if (isUser) {
                DropdownMenuItem(
                    text = { Text("Chỉnh sửa & tạo lại") },
                    onClick = {
                        showMenu = false
                        editText = message.content
                        editing = true
                    },
                    enabled = !busy
                )
                DropdownMenuItem(
                    text = { Text("Tạo lại câu trả lời") },
                    onClick = {
                        showMenu = false
                        onRegenerate(message)
                    },
                    enabled = !busy
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Tạo nhánh mới") },
                    onClick = {
                        showMenu = false
                        onBranch(message)
                    },
                    enabled = !busy
                )
            }
            DropdownMenuItem(
                text = { Text("Xóa lượt") },
                onClick = {
                    showMenu = false
                    onDelete(message)
                },
                enabled = !busy
            )
        }
    }

    if (showFullEdit) {
        Dialog(
            onDismissRequest = { showFullEdit = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = {
                            showFullEdit = false
                            editText = message.content
                        }) { Text("Hủy") }
                        Text(
                            "Chỉnh sửa tin nhắn",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Button(onClick = {
                            showFullEdit = false
                            editing = false
                            onSaveEdit(message, editText)
                        }, enabled = editText.isNotBlank()) { Text("Lưu") }
                    }
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(top = 12.dp)
                    )
                    if (isUser) {
                        Button(
                            onClick = {
                                showFullEdit = false
                                editing = false
                                onSaveEditAndRegenerate(message, editText)
                            },
                            enabled = editText.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                        ) { Text("Lưu & tạo lại câu trả lời") }
                    }
                }
            }
        }
    }
}

private fun withHighlightPlain(text: String, highlight: String): AnnotatedString {
    if (highlight.isBlank()) return AnnotatedString(text)
    val query = highlight.trim()
    return androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        var start = text.indexOf(query, ignoreCase = true)
        while (start != -1) {
            addStyle(
                androidx.compose.ui.text.SpanStyle(
                    background = androidx.compose.ui.graphics.Color(0xFFFFF176)
                ),
                start,
                (start + query.length).coerceAtMost(text.length)
            )
            start = text.indexOf(query, start + query.length, ignoreCase = true)
        }
    }
}
