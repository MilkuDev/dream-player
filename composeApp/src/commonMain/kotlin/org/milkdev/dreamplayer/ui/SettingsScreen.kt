package org.milkdev.dreamplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.milkdev.dreamplayer.app.AppTheme
import org.milkdev.dreamplayer.database.DailyPlaylistGenerationMode
import org.milkdev.dreamplayer.diagnostics.LogStorage
import org.milkdev.dreamplayer.extensions.ai.AiPlaylistModels
import org.milkdev.dreamplayer.extensions.ai.AiPlaylistPromptPresets
import org.milkdev.dreamplayer.extensions.ai.AiPlaylistProviders
import org.milkdev.dreamplayer.generated.resources.Res
import org.milkdev.dreamplayer.generated.resources.arrow_back
import org.milkdev.dreamplayer.library.LibrarySummary
import org.milkdev.dreamplayer.playback.SettingsUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsState: SettingsUiState,
    onBackClick: () -> Unit,
    onBlurToggle: (Boolean) -> Unit,
    onNightModeToggle: (Boolean) -> Unit,
    onDailyPlaylistModeChange: (DailyPlaylistGenerationMode) -> Unit,
    onAiPlaylistProviderChange: (String) -> Unit,
    onAiPlaylistModelChange: (String) -> Unit,
    onAiPlaylistPromptPresetChange: (String) -> Unit,
    onAiPlaylistCustomSystemPromptChange: (String) -> Unit,
    onAiPlaylistApiKeySave: (String) -> Unit,
    onAiPlaylistApiKeyClear: () -> Unit,
    onOpenAiDebugSettings: () -> Unit,
    onLastFmApiKeySave: (String) -> Unit,
    onLastFmApiKeyClear: () -> Unit,
    onLastFmApiTest: () -> Unit,
    onLastFmMetadataSync: () -> Unit,
    onMusicBrainzCoverSync: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    var showAboutDialog by remember { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            SettingsTopBar(
                title = "Настройки",
                onBackClick = onBackClick,
                scrollBehavior = scrollBehavior,
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(
                top = 16.dp,
                bottom = contentPadding.calculateBottomPadding() + 40.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SettingsToggleItem(
                    title = "Эффекты размытия",
                    subtitle = "Glassmorphism и размытие фона плеера",
                    checked = settingsState.isBlurEnabled,
                    onCheckedChange = onBlurToggle,
                )
            }
            item {
                SettingsToggleItem(
                    title = "Ночная тема",
                    subtitle = "Принудительно использовать темный интерфейс",
                    checked = settingsState.isForceNightMode,
                    onCheckedChange = onNightModeToggle,
                )
            }
            item {
                SettingsDailyPlaylistItem(
                    settingsState = settingsState,
                    onModeChange = onDailyPlaylistModeChange,
                    onProviderChange = onAiPlaylistProviderChange,
                    onModelChange = onAiPlaylistModelChange,
                    onPromptPresetChange = onAiPlaylistPromptPresetChange,
                    onCustomPromptChange = onAiPlaylistCustomSystemPromptChange,
                    onApiKeySave = onAiPlaylistApiKeySave,
                    onApiKeyClear = onAiPlaylistApiKeyClear,
                )
            }
            item {
                SettingsClickableItem(
                    title = "Дебаг AI API",
                    subtitle = "Проверки сети, ответа модели и служебные инструменты",
                    onClick = onOpenAiDebugSettings,
                )
            }
            item {
                SettingsLastFmItem(
                    settingsState = settingsState,
                    onApiKeySave = onLastFmApiKeySave,
                    onApiKeyClear = onLastFmApiKeyClear,
                    onApiTest = onLastFmApiTest,
                    onMetadataSync = onLastFmMetadataSync,
                    onMusicBrainzCoverSync = onMusicBrainzCoverSync,
                )
            }
            item {
                LogConsole()
            }
            item {
                NetworkTraceBlock()
            }
            item {
                SettingsClickableItem(
                    title = "О приложении",
                    subtitle = "Версия DreamPlayer и авторы",
                    onClick = { showAboutDialog = true },
                )
            }
        }
    }

    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }
}

@Composable
private fun NetworkTraceBlock() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSectionHeader(
                title = "Сетевые трейсы",
                subtitle = "Короткие request/response для метаданных и обложек",
            )
            LogConsole(
                title = "Last.fm: сеть",
                logsFlow = LogStorage.lastFmNetworkLogs,
                onClear = LogStorage::clearLastFmNetwork,
                consoleHeight = 220.dp,
                emptyText = "Сетевых запросов Last.fm пока нет",
            )
            LogConsole(
                title = "MusicBrainz / обложки: сеть",
                logsFlow = LogStorage.otherNetworkLogs,
                onClear = LogStorage::clearOtherNetwork,
                consoleHeight = 220.dp,
                emptyText = "Сетевых запросов MusicBrainz и обложек пока нет",
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiDebugSettingsScreen(
    settingsState: SettingsUiState,
    librarySummary: LibrarySummary = LibrarySummary(),
    onBackClick: () -> Unit,
    onModeChange: (DailyPlaylistGenerationMode) -> Unit,
    onProviderChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onPromptPresetChange: (String) -> Unit,
    onCustomPromptChange: (String) -> Unit,
    onApiKeySave: (String) -> Unit,
    onApiKeyClear: () -> Unit,
    onAllApiKeysClear: () -> Unit,
    onApiTest: () -> Unit,
    onResponseTest: () -> Unit,
    onForceGenerateDailyPlaylist: () -> Unit,
    onPromptSend: (String) -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    var promptInput by remember(settingsState.aiPlaylistProviderId) { mutableStateOf("") }
    var apiKeyInput by remember(settingsState.aiPlaylistProviderId) { mutableStateOf("") }
    var isEditingApiKey by remember(settingsState.aiPlaylistProviderId, settingsState.isAiPlaylistApiKeyConfigured) {
        mutableStateOf(false)
    }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val selectedModel = AiPlaylistModels.byApiModel(
        providerId = settingsState.aiPlaylistProviderId,
        apiModel = settingsState.aiPlaylistModel,
    )
    val aiFeature = settingsState.aiDailyPlaylistFeature
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            SettingsTopBar(
                title = "Дебаг AI API",
                onBackClick = onBackClick,
                scrollBehavior = scrollBehavior,
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(
                top = 16.dp,
                bottom = contentPadding.calculateBottomPadding() + 40.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SettingsSectionHeader(
                            title = "AI API",
                            subtitle = "Те же настройки, что в основном сценарии, плюс служебные проверки",
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            FilterChip(
                                selected = settingsState.dailyPlaylistGenerationMode == DailyPlaylistGenerationMode.LOCAL_DAILY,
                                onClick = { onModeChange(DailyPlaylistGenerationMode.LOCAL_DAILY) },
                                label = { Text("Локально") },
                                modifier = Modifier.weight(1f),
                            )
                            FilterChip(
                                selected = settingsState.dailyPlaylistGenerationMode == DailyPlaylistGenerationMode.AI_API,
                                onClick = { onModeChange(DailyPlaylistGenerationMode.AI_API) },
                                enabled = aiFeature.enabled,
                                label = { Text("AI") },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (!aiFeature.enabled) {
                            Text(
                                text = aiFeature.reason ?: "AI API отключен на этой платформе",
                                style = AppTheme.typography.snPro.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = "Сервис",
                            style = AppTheme.typography.snPro.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        AiProviderSelector(
                            selectedProviderId = settingsState.aiPlaylistProviderId,
                            onProviderChange = onProviderChange,
                        )
                        AiModelDropdown(
                            providerId = settingsState.aiPlaylistProviderId,
                            selectedModelId = settingsState.aiPlaylistModel,
                            onModelChange = onModelChange,
                        )
                        Text(
                            text = "Модель: ${selectedModel.displayName}\nAPI id: ${selectedModel.apiModel}",
                            style = AppTheme.typography.snPro.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        AiApiKeyEditor(
                            isConfigured = settingsState.isAiPlaylistApiKeyConfigured,
                            isEditing = isEditingApiKey,
                            apiKeyInput = apiKeyInput,
                            onApiKeyInputChange = { apiKeyInput = it },
                            onEditClick = { isEditingApiKey = true },
                            onCancelEdit = {
                                isEditingApiKey = false
                                apiKeyInput = ""
                            },
                            onSave = {
                                onApiKeySave(apiKeyInput)
                                apiKeyInput = ""
                                isEditingApiKey = false
                            },
                            onClear = {
                                onApiKeyClear()
                                apiKeyInput = ""
                                isEditingApiKey = false
                            },
                        )
                        Text(
                            text = "Стиль плейлиста",
                            style = AppTheme.typography.snPro.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        AiPromptPresetSelector(
                            selectedPresetId = settingsState.aiPlaylistPromptPresetId,
                            onPromptPresetChange = onPromptPresetChange,
                        )
                        if (AiPlaylistPromptPresets.byId(settingsState.aiPlaylistPromptPresetId).isCustom) {
                            AiCustomPromptField(
                                value = settingsState.aiPlaylistCustomSystemPrompt,
                                onValueChange = onCustomPromptChange,
                            )
                        }
                        Text(
                            text = "Статус: ключ выбранного сервиса " +
                                if (settingsState.isAiPlaylistApiKeyConfigured) "сохранен" else "не задан",
                            style = AppTheme.typography.snPro.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Библиотека: ${librarySummary.trackCount} треков. " +
                                "Текущий режим: ${if (settingsState.dailyPlaylistGenerationMode == DailyPlaylistGenerationMode.AI_API) "AI" else "локально"}.",
                            style = AppTheme.typography.snPro.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = onApiTest,
                            enabled = settingsState.isAiPlaylistApiKeyConfigured,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Проверить AI API")
                        }
                        OutlinedButton(
                            onClick = onResponseTest,
                            enabled = settingsState.isAiPlaylistApiKeyConfigured,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Проверить ответ модели")
                        }
                        OutlinedButton(
                            onClick = onAllApiKeysClear,
                            enabled = settingsState.isAnyAiPlaylistApiKeyConfigured,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Очистить все AI-ключи")
                        }
                        OutlinedButton(
                            onClick = onForceGenerateDailyPlaylist,
                            enabled = librarySummary.trackCount > 0,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Сгенерировать плейлист дня сейчас")
                        }
                        OutlinedTextField(
                            value = promptInput,
                            onValueChange = { promptInput = it },
                            label = { Text("Тестовый промпт") },
                            minLines = 3,
                            maxLines = 8,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { onPromptSend(promptInput) },
                            enabled = settingsState.isAiPlaylistApiKeyConfigured && promptInput.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Отправить промпт")
                        }
                        settingsState.aiPlaylistApiTestStatus?.takeIf { it.isNotBlank() }?.let { status ->
                            OutlinedButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(status))
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Скопировать вывод")
                            }
                            Text(
                                text = status,
                                style = AppTheme.typography.snPro.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsDailyPlaylistItem(
    settingsState: SettingsUiState,
    onModeChange: (DailyPlaylistGenerationMode) -> Unit,
    onProviderChange: (String) -> Unit,
    onModelChange: (String) -> Unit,
    onPromptPresetChange: (String) -> Unit,
    onCustomPromptChange: (String) -> Unit,
    onApiKeySave: (String) -> Unit,
    onApiKeyClear: () -> Unit,
) {
    var apiKeyInput by remember(settingsState.aiPlaylistProviderId) { mutableStateOf("") }
    var isEditingApiKey by remember(settingsState.aiPlaylistProviderId, settingsState.isAiPlaylistApiKeyConfigured) {
        mutableStateOf(false)
    }
    val aiFeature = settingsState.aiDailyPlaylistFeature
    val isAiMode = settingsState.dailyPlaylistGenerationMode == DailyPlaylistGenerationMode.AI_API

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSectionHeader(
                title = "Плейлист дня",
                subtitle = "Ежедневная локальная подборка или AI-рекомендация",
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                FilterChip(
                    selected = settingsState.dailyPlaylistGenerationMode == DailyPlaylistGenerationMode.LOCAL_DAILY,
                    onClick = { onModeChange(DailyPlaylistGenerationMode.LOCAL_DAILY) },
                    label = { Text("Локально") },
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = isAiMode,
                    onClick = { onModeChange(DailyPlaylistGenerationMode.AI_API) },
                    enabled = aiFeature.enabled,
                    label = { Text("AI") },
                    modifier = Modifier.weight(1f),
                )
            }

            if (!aiFeature.enabled) {
                Text(
                    text = aiFeature.reason ?: "AI API будет доступен позже",
                    style = AppTheme.typography.snPro.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (aiFeature.enabled && isAiMode) {
                Text(
                    text = "Сервис",
                    style = AppTheme.typography.snPro.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                AiProviderSelector(
                    selectedProviderId = settingsState.aiPlaylistProviderId,
                    onProviderChange = onProviderChange,
                )
                AiModelDropdown(
                    providerId = settingsState.aiPlaylistProviderId,
                    selectedModelId = settingsState.aiPlaylistModel,
                    onModelChange = onModelChange,
                )
                AiApiKeyEditor(
                    isConfigured = settingsState.isAiPlaylistApiKeyConfigured,
                    isEditing = isEditingApiKey,
                    apiKeyInput = apiKeyInput,
                    onApiKeyInputChange = { apiKeyInput = it },
                    onEditClick = { isEditingApiKey = true },
                    onCancelEdit = {
                        isEditingApiKey = false
                        apiKeyInput = ""
                    },
                    onSave = {
                        onApiKeySave(apiKeyInput)
                        apiKeyInput = ""
                        isEditingApiKey = false
                    },
                    onClear = {
                        onApiKeyClear()
                        apiKeyInput = ""
                        isEditingApiKey = false
                    },
                )
                Text(
                    text = "Стиль плейлиста",
                    style = AppTheme.typography.snPro.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                AiPromptPresetSelector(
                    selectedPresetId = settingsState.aiPlaylistPromptPresetId,
                    onPromptPresetChange = onPromptPresetChange,
                )
                if (AiPlaylistPromptPresets.byId(settingsState.aiPlaylistPromptPresetId).isCustom) {
                    AiCustomPromptField(
                        value = settingsState.aiPlaylistCustomSystemPrompt,
                        onValueChange = onCustomPromptChange,
                    )
                }
            }
        }
    }
}

@Composable
private fun AiProviderSelector(
    selectedProviderId: String,
    onProviderChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AiPlaylistProviders.all.forEach { provider ->
            FilterChip(
                selected = selectedProviderId == provider.id,
                onClick = { onProviderChange(provider.id) },
                label = { Text(provider.displayName) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AiModelDropdown(
    providerId: String,
    selectedModelId: String,
    onModelChange: (String) -> Unit,
) {
    var expanded by remember(providerId) { mutableStateOf(false) }
    val models = AiPlaylistModels.forProvider(providerId)
    val selectedModel = AiPlaylistModels.byApiModel(providerId, selectedModelId)

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = selectedModel.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("Выбрать")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model.displayName) },
                    onClick = {
                        expanded = false
                        onModelChange(model.apiModel)
                    },
                )
            }
        }
    }
}

@Composable
private fun AiApiKeyEditor(
    isConfigured: Boolean,
    isEditing: Boolean,
    apiKeyInput: String,
    onApiKeyInputChange: (String) -> Unit,
    onEditClick: () -> Unit,
    onCancelEdit: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    val showInput = !isConfigured || isEditing

    Text(
        text = if (isConfigured) {
            "API-ключ сохранен для выбранного сервиса"
        } else {
            "API-ключ для выбранного сервиса не задан"
        },
        style = AppTheme.typography.snPro.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (showInput) {
        OutlinedTextField(
            value = apiKeyInput,
            onValueChange = onApiKeyInputChange,
            label = { Text(if (isConfigured) "Новый API-ключ" else "API-ключ") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(
                onClick = onSave,
                enabled = apiKeyInput.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                Text(if (isConfigured) "Обновить" else "Сохранить")
            }
            if (isConfigured) {
                OutlinedButton(
                    onClick = onCancelEdit,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Отмена")
                }
            }
        }
    } else {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(
                onClick = onEditClick,
                modifier = Modifier.weight(1f),
            ) {
                Text("Изменить")
            }
            OutlinedButton(
                onClick = onClear,
                modifier = Modifier.weight(1f),
            ) {
                Text("Удалить")
            }
        }
    }
}

@Composable
private fun AiPromptPresetSelector(
    selectedPresetId: String,
    onPromptPresetChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AiPlaylistPromptPresets.all.forEach { preset ->
            FilterChip(
                selected = selectedPresetId == preset.id,
                onClick = { onPromptPresetChange(preset.id) },
                label = { Text(preset.displayName) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AiCustomPromptField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    var input by remember(value) { mutableStateOf(value) }
    val hasChanges = input != value

    OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        label = { Text("Свой стиль") },
        minLines = 3,
        maxLines = 8,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Button(
            onClick = { onValueChange(input) },
            enabled = hasChanges,
            modifier = Modifier.weight(1f),
        ) {
            Text("Сохранить стиль")
        }
        OutlinedButton(
            onClick = { input = value },
            enabled = hasChanges,
            modifier = Modifier.weight(1f),
        ) {
            Text("Сбросить")
        }
    }
}

@Composable
fun SettingsLastFmItem(
    settingsState: SettingsUiState,
    onApiKeySave: (String) -> Unit,
    onApiKeyClear: () -> Unit,
    onApiTest: () -> Unit,
    onMetadataSync: () -> Unit,
    onMusicBrainzCoverSync: () -> Unit,
) {
    var apiKeyInput by remember { mutableStateOf("") }
    val lastFm = settingsState.lastFmSettings

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSectionHeader(
                title = "Метаданные альбомов",
                subtitle = "Обложки через MusicBrainz, год и жанр через Last.fm",
            )

            if (lastFm.supportsSecrets) {
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    label = { Text("Last.fm API-ключ") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = if (lastFm.isApiKeyConfigured) {
                        "Ключ Last.fm сохранен"
                    } else {
                        "Ключ Last.fm не задан, будут синхронизироваться только обложки"
                    },
                    style = AppTheme.typography.snPro.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Button(
                        onClick = {
                            onApiKeySave(apiKeyInput)
                            apiKeyInput = ""
                        },
                        enabled = apiKeyInput.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Сохранить")
                    }
                    OutlinedButton(
                        onClick = onApiKeyClear,
                        enabled = lastFm.isApiKeyConfigured,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Очистить")
                    }
                }

                OutlinedButton(
                    onClick = onApiTest,
                    enabled = lastFm.isApiKeyConfigured && !lastFm.isTestInProgress,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (lastFm.isTestInProgress) "Проверяю Last.fm..." else "Проверить Last.fm API")
                }
            } else {
                Text(
                    text = "На этой платформе Last.fm ключи не сохраняются, но обложки можно искать без ключа",
                    style = AppTheme.typography.snPro.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedButton(
                onClick = onMetadataSync,
                enabled = !lastFm.isMetadataSyncing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (lastFm.isMetadataSyncing) "Синхронизирую метаданные..." else "Синхронизировать метаданные")
            }

            OutlinedButton(
                onClick = onMusicBrainzCoverSync,
                enabled = !lastFm.isMetadataSyncing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (lastFm.isMetadataSyncing) "Проверяю обложки..." else "Проверить обложки MusicBrainz")
            }

            Text(
                text = "Очередь: обложки ${lastFm.coverPendingCount}, Last.fm ${lastFm.lastFmPendingCount}. " +
                    "Обработано: ${lastFm.processedCount}.",
                style = AppTheme.typography.snPro.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            lastFm.lastMetadataSyncMessage?.takeIf { it.isNotBlank() }?.let { message ->
                Text(
                    text = message,
                    style = AppTheme.typography.snPro.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            lastFm.testStatus?.toDisplayText()?.takeIf { it.isNotBlank() }?.let { status ->
                Text(
                    text = status,
                    style = AppTheme.typography.snPro.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun SettingsToggleItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        onClick = { onCheckedChange(!checked) },
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = AppTheme.typography.snPro.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = AppTheme.typography.snPro.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        }
    }
}

@Composable
fun SettingsClickableItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = AppTheme.typography.snPro.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = AppTheme.typography.snPro.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar(
    title: String,
    onBackClick: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                style = AppTheme.typography.snPro.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(
                    painter = painterResource(Res.drawable.arrow_back),
                    contentDescription = "Назад",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
        ),
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun SettingsSectionHeader(
    title: String,
    subtitle: String,
) {
    Column {
        Text(
            text = title,
            style = AppTheme.typography.snPro.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = subtitle,
            style = AppTheme.typography.snPro.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
