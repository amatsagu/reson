package com.amatsagu.reson.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amatsagu.reson.R
import com.amatsagu.reson.YtDlpState
import com.amatsagu.reson.data.local.DatabaseStats
import com.amatsagu.reson.data.local.ThemeColor
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/**
 * Settings Screen with 3 dedicated sections:
 * 1. App details (Engine, SQLite schema, download stats, listening time, update & self-repair)
 * 2. Data control (Export, Import, Clear history, Delete all with confirmation dialogs)
 * 3. User preferences (Theme color picker, Recommendations toggle, Language selection)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    ytDlpState: YtDlpState,
    databaseStats: DatabaseStats?,
    totalListeningTimeMs: Long,
    currentThemeColor: ThemeColor,
    showRecommended: Boolean,
    currentLanguage: String,
    onThemeColorSelected: (ThemeColor) -> Unit,
    onShowRecommendedChanged: (Boolean) -> Unit,
    onLanguageSelected: (String) -> Unit,
    onCheckUpdate: () -> Unit,
    onSelfRepair: () -> Unit,
    onExportDatabase: (OutputStream, (Boolean, String?) -> Unit) -> Unit,
    onImportDatabase: (InputStream, (Boolean, String?) -> Unit) -> Unit,
    onClearHistory: () -> Unit = {},
    onDeleteAllData: (() -> Unit) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // Dialog states
    var showSelfRepairDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var showImportConfirmDialog by remember { mutableStateOf(false) }

    val exportSuccessMsg = stringResource(R.string.settings_export_success)
    val exportFailedTemplate = stringResource(R.string.settings_export_failed)
    val importSuccessMsg = stringResource(R.string.settings_import_success)
    val importFailedTemplate = stringResource(R.string.settings_import_failed)
    val deleteSuccessMsg = stringResource(R.string.settings_delete_success)
    val historyClearedMsg = stringResource(R.string.settings_history_cleared)

    // Export file picker launcher
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            try {
                val outputStream = context.contentResolver.openOutputStream(uri)
                if (outputStream != null) {
                    onExportDatabase(outputStream) { success, error ->
                        val msg = if (success) {
                            exportSuccessMsg
                        } else {
                            String.format(exportFailedTemplate, error ?: "Unknown error")
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    String.format(exportFailedTemplate, e.message ?: ""),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // Import file picker launcher
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingImportUri = uri
            showImportConfirmDialog = true
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // =========================================================================
        // 1. App Details Card
        // =========================================================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.settings_app_details_title),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Engine version
                val engineVersionText = when (ytDlpState) {
                    is YtDlpState.Ready -> ytDlpState.version
                    is YtDlpState.Updating -> stringResource(R.string.status_updating)
                    is YtDlpState.Initializing -> stringResource(R.string.status_initializing)
                    is YtDlpState.Error -> stringResource(R.string.status_error, ytDlpState.message)
                    is YtDlpState.Uninitialized -> stringResource(R.string.status_uninitialized)
                }
                DetailRow(
                    label = stringResource(R.string.settings_engine_version),
                    value = engineVersionText
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Cache / DB version
                DetailRow(
                    label = stringResource(R.string.settings_cache_version),
                    value = "Reson Format 1.0"
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Downloaded videos count
                val downloadedCount = databaseStats?.downloadedVideoCount ?: 0L
                DetailRow(
                    label = stringResource(R.string.settings_downloaded_count),
                    value = "$downloadedCount videos"
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Cache size (sqlite data size + all downloaded locally files)
                val totalCacheBytes = databaseStats?.totalCacheSizeBytes ?: 0L
                DetailRow(
                    label = stringResource(R.string.settings_cache_size),
                    value = formatFileSize(totalCacheBytes)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Total listening time
                DetailRow(
                    label = stringResource(R.string.settings_total_listening_time),
                    value = formatListeningTime(totalListeningTimeMs)
                )

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(14.dp))

                // Action buttons: Check update & Self-repair (both styled same as OutlinedButtons without icons)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onCheckUpdate,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.action_check_update),
                            fontSize = 13.sp
                        )
                    }

                    OutlinedButton(
                        onClick = { showSelfRepairDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.action_self_repair),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        // =========================================================================
        // 2. Data Control Card
        // =========================================================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.settings_data_control_title),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.settings_data_control_subtitle),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Export Button
                OutlinedButton(
                    onClick = {
                        exportLauncher.launch("reson_backup_${System.currentTimeMillis()}.db")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FileUpload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.settings_export_data), fontSize = 14.sp)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Import Button
                OutlinedButton(
                    onClick = {
                        importLauncher.launch(arrayOf("*/*"))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.settings_import_data), fontSize = 14.sp)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Clear History Button (No confirmation prompt needed)
                OutlinedButton(
                    onClick = {
                        onClearHistory()
                        Toast.makeText(context, historyClearedMsg, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.settings_clear_history), fontSize = 14.sp)
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(12.dp))

                // Delete All Data Button (Bright Material Danger Red)
                val dangerRed = Color(0xFFE53935)
                Button(
                    onClick = { showDeleteConfirmDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = dangerRed,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteForever,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_delete_all_data),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }
        }

        // =========================================================================
        // 3. User Preferences Card
        // =========================================================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.settings_preferences_title),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.settings_preferences_subtitle),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 3a. Theme Color Choice
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Palette,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_theme_color),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // All 7 colors displayed across the card width without hidden horizontal scrolling
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ThemeColor.entries.forEach { colorOption ->
                        val isSelected = colorOption == currentThemeColor
                        ColorCircleItem(
                            themeColor = colorOption,
                            isSelected = isSelected,
                            onClick = { onThemeColorSelected(colorOption) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(14.dp))

                // 3b. Show Recommended Content Toggle (clean single row without description)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_show_recommended),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = showRecommended,
                        onCheckedChange = onShowRecommendedChanged,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(14.dp))

                // 3c. Language Choice
                Text(
                    text = stringResource(R.string.settings_language),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))

                var languageExpanded by remember { mutableStateOf(false) }
                val languageOptions = listOf("en" to "English (US)")

                ExposedDropdownMenuBox(
                    expanded = languageExpanded,
                    onExpandedChange = { languageExpanded = it },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = languageOptions.find { it.first == currentLanguage }?.second ?: "English (US)",
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                        shape = RoundedCornerShape(12.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = languageExpanded,
                        onDismissRequest = { languageExpanded = false }
                    ) {
                        languageOptions.forEach { (code, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    onLanguageSelected(code)
                                    languageExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    // =========================================================================
    // Confirmation Dialogs
    // =========================================================================

    // 1. Self-Repair Dialog
    if (showSelfRepairDialog) {
        AlertDialog(
            onDismissRequest = { showSelfRepairDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(text = stringResource(R.string.settings_self_repair_title))
            },
            text = {
                Text(
                    text = stringResource(R.string.settings_self_repair_desc),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSelfRepairDialog = false
                        onSelfRepair()
                        Toast.makeText(context, "Running self-repair...", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text(text = stringResource(R.string.settings_self_repair_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSelfRepairDialog = false }) {
                    Text(text = stringResource(R.string.settings_self_repair_cancel))
                }
            }
        )
    }

    // 2. Import Database Dialog
    if (showImportConfirmDialog && pendingImportUri != null) {
        val uri = pendingImportUri!!
        AlertDialog(
            onDismissRequest = {
                showImportConfirmDialog = false
                pendingImportUri = null
            },
            icon = {
                Icon(
                    imageVector = Icons.Default.FileDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(text = stringResource(R.string.settings_import_confirm_title))
            },
            text = {
                Text(
                    text = stringResource(R.string.settings_import_confirm_desc),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showImportConfirmDialog = false
                        try {
                            val inputStream = context.contentResolver.openInputStream(uri)
                            if (inputStream != null) {
                                onImportDatabase(inputStream) { success, error ->
                                    val msg = if (success) {
                                        importSuccessMsg
                                    } else {
                                        String.format(importFailedTemplate, error ?: "Corrupt or invalid DB")
                                    }
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                }
                            }
                        } catch (e: Exception) {
                            Toast.makeText(
                                context,
                                String.format(importFailedTemplate, e.message ?: ""),
                                Toast.LENGTH_SHORT
                            ).show()
                        } finally {
                            pendingImportUri = null
                        }
                    }
                ) {
                    Text(text = "Restore")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showImportConfirmDialog = false
                        pendingImportUri = null
                    }
                ) {
                    Text(text = stringResource(R.string.settings_self_repair_cancel))
                }
            }
        )
    }

    // 3. Delete All Data Confirmation Dialog
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.DeleteForever,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(text = stringResource(R.string.settings_delete_confirm_title))
            },
            text = {
                Text(
                    text = stringResource(R.string.settings_delete_confirm_desc),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        onDeleteAllData {
                            Toast.makeText(
                                context,
                                deleteSuccessMsg,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE53935),
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        text = stringResource(R.string.settings_delete_confirm_button),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text(text = stringResource(R.string.settings_delete_cancel))
                }
            }
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ColorCircleItem(
    themeColor: ThemeColor,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    if (themeColor.isSystem) MaterialTheme.colorScheme.surfaceVariant else themeColor.seedColor
                )
                .border(width = if (isSelected) 2.dp else 0.dp, color = borderColor, shape = CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (themeColor.isSystem) {
                Icon(
                    imageVector = Icons.Default.PhoneAndroid,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            } else if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = themeColor.displayName,
            fontSize = 10.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatListeningTime(totalMs: Long): String {
    if (totalMs <= 0L) return "0m"
    val totalSeconds = totalMs / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L

    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kilo = 1024.0
    val mega = kilo * 1024.0
    val giga = mega * 1024.0

    return when {
        bytes >= giga -> String.format(Locale.US, "%.2f GB", bytes / giga)
        bytes >= mega -> String.format(Locale.US, "%.1f MB", bytes / mega)
        bytes >= kilo -> String.format(Locale.US, "%.1f KB", bytes / kilo)
        else -> "$bytes B"
    }
}
