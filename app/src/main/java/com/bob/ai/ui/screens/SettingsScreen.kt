package com.bob.ai.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bob.ai.BobApp
import com.bob.ai.di.AppContainer
import com.bob.ai.domain.ProviderType
import com.bob.ai.domain.modelsFor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as BobApp).container }
    val viewModel: SettingsViewModel = viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(container) as T
    })
    val settings by viewModel.settings.collectAsState()
    val providers by viewModel.providers.collectAsState()
    var workspaceName by remember {
        mutableStateOf(viewModel.workspaceDisplayName())
    }
    var hasWorkspace by remember { mutableStateOf(viewModel.hasWorkspace()) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: SecurityException) {
                // Permission could not be persisted; still record the choice.
            }
            val name = DocumentFile.fromTreeUri(context, uri)?.name
            viewModel.setWorkspace(uri.toString(), name)
            workspaceName = name
            hasWorkspace = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cài đặt") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Quay lại")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // ---- Providers ----
            Text("Nhà cung cấp AI", style = MaterialTheme.typography.titleLarge)
            Text(
                "API key được mã hóa và chỉ lưu trên máy của bạn.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            )
            ProviderType.values().forEach { type ->
                ProviderCard(
                    type = type,
                    apiKey = viewModel.configFor(type)?.apiKey ?: "",
                    baseUrl = viewModel.configFor(type)?.baseUrl ?: "",
                    modelId = viewModel.configFor(type)?.modelId
                        ?: modelsFor(type).first().id,
                    onApiKeyChange = { viewModel.setApiKey(type, it) },
                    onBaseUrlChange = { viewModel.setBaseUrl(type, it) },
                    onModelChange = { viewModel.setModelId(type, it) }
                )
                Spacer(Modifier.height(12.dp))
            }

            // ---- Default provider ----
            Text(
                "Nhà cung cấp mặc định",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
            var defaultExpanded by remember { mutableStateOf(false) }
            val defaultLabel = ProviderType.values()
                .firstOrNull { it.name.lowercase() == settings.defaultProviderId }
                ?.displayName() ?: "Chưa chọn"
            OutlinedTextField(
                value = defaultLabel,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clickable { defaultExpanded = true },
                trailingIcon = {
                    IconButton(onClick = { defaultExpanded = true }) {
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = "Chọn")
                    }
                }
            )
            DropdownMenu(
                expanded = defaultExpanded,
                onDismissRequest = { defaultExpanded = false }
            ) {
                ProviderType.values().forEach { type ->
                    DropdownMenuItem(
                        text = { Text(type.displayName()) },
                        onClick = {
                            viewModel.setDefaultProvider(type.name.lowercase())
                            defaultExpanded = false
                        }
                    )
                }
            }

            // ---- System prompt ----
            Text(
                "Prompt hệ thống mặc định",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 20.dp)
            )
            OutlinedTextField(
                value = settings.defaultSystemPrompt,
                onValueChange = { viewModel.setSystemPrompt(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                minLines = 3,
                placeholder = { Text("Ví dụ: Bạn là trợ lý hữu ích, trả lời bằng tiếng Việt.") }
            )

            // ---- Theme ----
            Text(
                "Giao diện",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 20.dp)
            )
            listOf(
                "Sáng" to false,
                "Tối" to true,
                "Theo hệ thống" to null
            ).forEach { (label, value) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.setTheme(value) }
                ) {
                    RadioButton(
                        selected = settings.darkTheme == value,
                        onClick = { viewModel.setTheme(value) }
                    )
                    Text(label)
                }
            }

            // ---- Search API key ----
            Text(
                "API key tìm kiếm web",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 20.dp)
            )
            Text(
                "Dùng cho công cụ tìm kiếm web. Để trống nếu không dùng.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            var searchKeyVisible by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = settings.searchApiKey,
                onValueChange = { viewModel.setSearchApiKey(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                singleLine = true,
                visualTransformation = if (searchKeyVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { searchKeyVisible = !searchKeyVisible }) {
                        Icon(
                            if (searchKeyVisible) Icons.Filled.VisibilityOff
                            else Icons.Filled.Visibility,
                            contentDescription = if (searchKeyVisible) "Ẩn" else "Hiện"
                        )
                    }
                }
            )

            // ---- Workspace ----
            Text(
                "Thư mục workspace",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 20.dp)
            )
            Text(
                text = if (hasWorkspace) "Thư mục hiện tại: ${workspaceName ?: "(không rõ tên)"}"
                else "Chưa chọn thư mục workspace. Các công cụ file sẽ bị tắt cho đến khi bạn chọn.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
            Row(Modifier.padding(top = 8.dp)) {
                Button(onClick = { folderPicker.launch(null) }) {
                    Text("Chọn thư mục")
                }
                if (hasWorkspace) {
                    OutlinedButton(
                        onClick = {
                            viewModel.clearWorkspace()
                            hasWorkspace = false
                            workspaceName = null
                        },
                        modifier = Modifier.padding(start = 8.dp)
                    ) { Text("Gỡ") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ProviderCard(
    type: ProviderType,
    apiKey: String,
    baseUrl: String,
    modelId: String,
    onApiKeyChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onModelChange: (String) -> Unit
) {
    var keyVisible by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    val models = modelsFor(type)
    val modelLabel = models.firstOrNull { it.id == modelId }?.displayName ?: modelId

    Column {
        Text(type.displayName(), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            label = { Text("API key") },
            singleLine = true,
            visualTransformation = if (keyVisible) VisualTransformation.None
            else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { keyVisible = !keyVisible }) {
                    Icon(
                        if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (keyVisible) "Ẩn API key" else "Hiện API key"
                    )
                }
            }
        )
        if (type == ProviderType.CUSTOM) {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = onBaseUrlChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                label = { Text("Base URL") },
                singleLine = true,
                placeholder = { Text("https://api.example.com/v1") }
            )
        }
        OutlinedTextField(
            value = modelLabel,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clickable { modelExpanded = true },
            label = { Text("Mô hình") },
            trailingIcon = {
                IconButton(onClick = { modelExpanded = true }) {
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = "Chọn mô hình")
                }
            }
        )
        DropdownMenu(
            expanded = modelExpanded,
            onDismissRequest = { modelExpanded = false }
        ) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model.displayName) },
                    onClick = {
                        onModelChange(model.id)
                        modelExpanded = false
                    }
                )
            }
        }
        Text(
            text = type.helpText(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}
