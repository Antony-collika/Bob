package com.bob.ai.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bob.ai.BobApp
import com.bob.ai.di.AppContainer
import com.bob.ai.domain.ToolCategory
import com.bob.ai.domain.ToolControl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as BobApp).container }
    val viewModel: ToolsViewModel = viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ToolsViewModel(container) as T
    })
    val tools by viewModel.tools.collectAsState()
    val selected by viewModel.selectedTool.collectAsState()
    val nameError by viewModel.nameError.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quản lý công cụ") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Quay lại")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ToolCategory.values().forEach { category ->
                val inCategory = tools.filter { it.definition.category == category }
                if (inCategory.isNotEmpty()) {
                    item(key = "header-${category.name}") {
                        Text(
                            text = category.displayName(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                    items(inCategory, key = { it.definition.uid }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.selectTool(item.definition.uid) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = item.effectiveName,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = item.definition.uid,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            AssistChip(
                                onClick = { viewModel.selectTool(item.definition.uid) },
                                label = { Text(item.setting.control.displayName()) }
                            )
                            Switch(
                                checked = item.setting.enabled,
                                onCheckedChange = {
                                    viewModel.setEnabled(item.definition.uid, it)
                                },
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    val item = selected
    if (item != null) {
        var customName by remember(item.definition.uid) {
            mutableStateOf(item.setting.customName ?: "")
        }
        var customDescription by remember(item.definition.uid) {
            mutableStateOf(item.setting.customDescription ?: "")
        }
        AlertDialog(
            onDismissRequest = { viewModel.selectTool(null) },
            title = { Text(item.effectiveName) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = "UID: ${item.definition.uid}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Mô tả gốc (chỉ xem): ${item.definition.description}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = customName,
                        onValueChange = {
                            customName = it
                            viewModel.setCustomName(item.definition.uid, it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Tên cho model") },
                        singleLine = true,
                        isError = nameError != null,
                        supportingText = nameError?.let { { Text(it) } }
                    )
                    OutlinedTextField(
                        value = customDescription,
                        onValueChange = {
                            customDescription = it
                            viewModel.setCustomDescription(item.definition.uid, it)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        label = { Text("Mô tả cho model") },
                        minLines = 2
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Mức kiểm soát",
                        style = MaterialTheme.typography.titleSmall
                    )
                    ToolControl.values().forEach { control ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setControl(item.definition.uid, control)
                                }
                        ) {
                            RadioButton(
                                selected = item.setting.control == control,
                                onClick = {
                                    viewModel.setControl(item.definition.uid, control)
                                }
                            )
                            Text(control.displayName())
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.selectTool(null) }) { Text("Xong") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.resetToDefaults(item.definition.uid) }) {
                    Text("Khôi phục mặc định")
                }
            }
        )
    }
}
