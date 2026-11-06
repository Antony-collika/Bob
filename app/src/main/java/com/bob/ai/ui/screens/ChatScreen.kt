package com.bob.ai.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.bob.ai.BobApp
import com.bob.ai.di.AppContainer
import com.bob.ai.domain.Attachment
import com.bob.ai.domain.ChatMessage
import com.bob.ai.domain.Role
import com.bob.ai.ui.components.MarkdownText
import com.bob.ai.ui.components.MessageBubble
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    scrollToMessageId: String? = null,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as BobApp).container }
    val viewModel: ChatViewModel = viewModel(
        key = conversationId,
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ChatViewModel(container, conversationId) as T
        }
    )

    val messages by viewModel.messages.collectAsState()
    val conversation by viewModel.conversation.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()
    val streamingText by viewModel.streamingText.collectAsState()
    val pendingApproval by viewModel.pendingApproval.collectAsState()
    val branchPrompt by viewModel.branchPrompt.collectAsState()
    val deletedTurn by viewModel.deletedTurn.collectAsState()
    val navigateTo by viewModel.navigateTo.collectAsState()
    val workspaceAvailable by viewModel.workspaceAvailable.collectAsState()

    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var inputText by remember { mutableStateOf("") }
    val attachments = remember { mutableStateListOf<Attachment>() }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    // ---- In-conversation search (spec §16) ----
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var currentMatch by remember { mutableStateOf(0) }
    val matchIndices by remember(messages, searchQuery) {
        derivedStateOf {
            if (searchQuery.isBlank()) emptyList()
            else messages.mapIndexedNotNull { index, msg ->
                if (msg.content.contains(searchQuery, ignoreCase = true)) index else null
            }
        }
    }

    // ---- Launchers ----
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris.forEach { uri ->
            attachments.add(
                Attachment(
                    id = UUID.randomUUID().toString(),
                    uri = uri.toString(),
                    mimeType = "image/*"
                )
            )
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            cameraUri?.let { uri ->
                attachments.add(
                    Attachment(
                        id = UUID.randomUUID().toString(),
                        uri = uri.toString(),
                        mimeType = "image/jpeg"
                    )
                )
            }
        }
        cameraUri = null
    }
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: SecurityException) {
                // Continue; the workspace is still recorded.
            }
            val name = DocumentFile.fromTreeUri(context, uri)?.name
            scope.launch {
                container.workspaceManager.setWorkspace(uri.toString(), name)
                viewModel.refreshWorkspace()
            }
        }
    }

    // ---- Navigation events (branch) ----
    LaunchedEffect(navigateTo) {
        val target = navigateTo
        if (target != null) {
            viewModel.consumeNavigation()
            onOpenConversation(target)
        }
    }

    // ---- Scroll to a message on entry (from global search, spec §16) ----
    var scrollToConsumed by remember { mutableStateOf(false) }
    LaunchedEffect(scrollToMessageId, messages.size) {
        if (!scrollToConsumed && scrollToMessageId != null && messages.isNotEmpty()) {
            val index = messages.indexOfFirst { it.id == scrollToMessageId }
            if (index >= 0) {
                listState.scrollToItem(index)
                scrollToConsumed = true
            }
        }
    }

    // ---- Auto-scroll to bottom on new messages unless the user scrolled up ----
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || last.index >= info.totalItemsCount - 2
        }
    }
    val totalItems = messages.size + if (streamingText.isNotEmpty() || isBusy) 1 else 0
    LaunchedEffect(messages.size, streamingText, isBusy) {
        if (atBottom && totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    // ---- Jump between in-conversation search matches ----
    LaunchedEffect(currentMatch, searchQuery) {
        if (searchQuery.isNotBlank() && matchIndices.isNotEmpty()) {
            val index = matchIndices[currentMatch.coerceIn(matchIndices.indices)]
            listState.scrollToItem(index)
        }
    }

    // ---- Delete-turn undo snackbar (spec §9) ----
    LaunchedEffect(deletedTurn) {
        if (deletedTurn != null) {
            val result = snackbarHostState.showSnackbar(
                message = "Đã xóa lượt",
                actionLabel = "Hoàn tác",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.undoDeleteTurn()
            } else {
                viewModel.clearDeletedTurn()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = conversation?.title ?: "Trò chuyện",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1
                        )
                        if (!conversation?.modelId.isNullOrBlank()) {
                            Text(
                                text = conversation!!.modelId,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Quay lại")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        searchActive = !searchActive
                        if (!searchActive) searchQuery = ""
                    }) {
                        Icon(Icons.Filled.Search, contentDescription = "Tìm trong cuộc trò chuyện")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (searchActive) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = {
                            searchQuery = it
                            currentMatch = 0
                        },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Tìm trong cuộc trò chuyện…") },
                        singleLine = true
                    )
                    IconButton(
                        onClick = {
                            if (matchIndices.isNotEmpty()) {
                                currentMatch =
                                    (currentMatch - 1 + matchIndices.size) % matchIndices.size
                            }
                        },
                        enabled = matchIndices.isNotEmpty()
                    ) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Kết quả trước")
                    }
                    IconButton(
                        onClick = {
                            if (matchIndices.isNotEmpty()) {
                                currentMatch = (currentMatch + 1) % matchIndices.size
                            }
                        },
                        enabled = matchIndices.isNotEmpty()
                    ) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Kết quả sau")
                    }
                    Text(
                        text = if (searchQuery.isBlank()) "" else
                            "${if (matchIndices.isEmpty()) 0 else currentMatch + 1}/${matchIndices.size}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    IconButton(onClick = {
                        searchActive = false
                        searchQuery = ""
                    }) {
                        Icon(Icons.Filled.Close, contentDescription = "Đóng tìm kiếm")
                    }
                }
            }

            // Workspace banner (spec §10): file tools are blocked without one.
            if (!workspaceAvailable) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Chưa chọn thư mục workspace — các công cụ file đang bị tắt, các công cụ khác vẫn dùng bình thường.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = { folderPicker.launch(null) }) {
                            Text("Chọn thư mục")
                        }
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                itemsIndexed(messages, key = { _, msg -> msg.id }) { _, message ->
                    MessageBubble(
                        message = message,
                        busy = isBusy,
                        highlight = searchQuery,
                        onRegenerate = { viewModel.regenerate(it) },
                        onDelete = { viewModel.deleteTurn(it) },
                        onBranch = { viewModel.branchFromMessage(it) },
                        onSaveEdit = { msg, text -> viewModel.saveEdit(msg, text) },
                        onSaveEditAndRegenerate = { msg, text ->
                            viewModel.saveEditAndRegenerate(msg, text)
                        }
                    )
                }
                if (streamingText.isNotEmpty()) {
                    item(key = "streaming") {
                        StreamingBubble(text = streamingText, highlight = searchQuery)
                    }
                } else if (isBusy) {
                    item(key = "thinking") {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Text(
                                "AI đang suy nghĩ…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
                if (messages.isEmpty() && !isBusy) {
                    item(key = "empty") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Nhập câu hỏi bên dưới để bắt đầu.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Attachment previews.
            if (attachments.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    attachments.forEach { attachment ->
                        Box {
                            AsyncImage(
                                model = attachment.uri,
                                contentDescription = "Ảnh đính kèm",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                            IconButton(
                                onClick = { attachments.remove(attachment) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(24.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Gỡ ảnh",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            // Input row.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                IconButton(
                    onClick = { galleryLauncher.launch("image/*") },
                    enabled = !isBusy
                ) {
                    Icon(Icons.Filled.Image, contentDescription = "Gửi ảnh từ thư viện")
                }
                IconButton(
                    onClick = {
                        val imagesDir = File(context.cacheDir, "images").apply { mkdirs() }
                        val file = File(imagesDir, "bob_${System.currentTimeMillis()}.jpg")
                        val uri = FileProvider.getUriForFile(
                            context,
                            "com.bob.ai.fileprovider",
                            file
                        )
                        cameraUri = uri
                        cameraLauncher.launch(uri)
                    },
                    enabled = !isBusy
                ) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = "Chụp ảnh")
                }
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Nhập tin nhắn…") },
                    minLines = 1,
                    maxLines = 5,
                    shape = RoundedCornerShape(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                if (isBusy) {
                    IconButton(onClick = { viewModel.stopGeneration() }) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = "Dừng",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            viewModel.sendMessage(inputText, attachments.toList())
                            inputText = ""
                            attachments.clear()
                        },
                        enabled = inputText.isNotBlank() || attachments.isNotEmpty()
                    ) {
                        Icon(Icons.Filled.Send, contentDescription = "Gửi")
                    }
                }
            }
        }
    }

    // ---- Tool approval dialog (spec §15) ----
    val approval = pendingApproval
    if (approval != null) {
        AlertDialog(
            onDismissRequest = { viewModel.declinePending() },
            title = { Text("Cho phép công cụ?") },
            text = {
                Text(
                    "AI muốn gọi công cụ \"${approval.nameUsed}\" với tham số:\n${approval.argumentsJson}\n\nBạn có cho phép chạy không?"
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.approvePending() }) { Text("Cho phép") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.declinePending() }) { Text("Từ chối") }
            }
        )
    }

    // ---- Branch prompt (spec §8) ----
    if (branchPrompt != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissBranchPrompt() },
            title = { Text("Tạo nhánh khác?") },
            text = {
                Text(
                    "Phía sau lượt này còn các lượt khác. Tạo nhánh mới để giữ nguyên cuộc hiện tại, " +
                        "hay tạo lại ngay tại đây (các lượt phía sau sẽ bị xóa)?"
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.branchAndRegenerateConfirmed() }) {
                    Text("Tạo nhánh mới")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.regenerateInPlaceConfirmed() }) {
                    Text("Tạo lại tại đây")
                }
            }
        )
    }
}

@Composable
private fun StreamingBubble(text: String, highlight: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = 4.dp,
                bottomEnd = 16.dp
            ),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                MarkdownText(text = text, highlight = highlight)
                Spacer(Modifier.height(4.dp))
                CircularProgressIndicator(modifier = Modifier.size(14.dp))
            }
        }
    }
}
