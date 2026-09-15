package com.vaulthealth.exporter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vaulthealth.exporter.domain.ScheduleCadence
import com.vaulthealth.exporter.hc.SdkAvailability
import java.time.LocalDate

@Composable
fun HomeScreen(
    state: UiState,
    onSelectFolder: () -> Unit,
    onRequestPermissions: () -> Unit,
    onRequestHistory: () -> Unit,
    onRequestBackground: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    onGrantRoute: (String) -> Unit,
    onSnapshotPreset: (Int) -> Unit,
    onSnapshotAllTime: () -> Unit,
    onSnapshotCustom: (LocalDate, LocalDate) -> Unit,
    onExportNow: () -> Unit,
    onSetCadence: (ScheduleCadence) -> Unit,
    onSetWatcher: (Int) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Vault Health Exporter", style = MaterialTheme.typography.titleLarge)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Line(
                    "Health Connect",
                    when (state.availability) {
                        SdkAvailability.AVAILABLE -> "ready"
                        SdkAvailability.UPDATE_REQUIRED -> "needs update"
                        SdkAvailability.NOT_SUPPORTED -> "unavailable"
                    },
                )
                Line("Snapshot", shortName(state.lastSnapshotFile))
                Line("Delta", shortName(state.lastDeltaFile))
                if (state.tokenWarning != null && !state.changeTokenPresent) {
                    Text("Snapshot required first", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.fillMaxWidth(0.62f)) {
                        Line("Folder", state.vaultName ?: "none")
                        Line("Writable", if (state.vaultWritable) "yes" else "no")
                    }
                    OutlinedButton(onClick = onSelectFolder) { Text("Change") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Data types ${state.grantedCount}/${state.totalCount}",
                        Modifier.fillMaxWidth(0.55f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Button(onClick = onRequestPermissions) { Text("Grant") }
                }
                if (!state.historyGranted) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("History", Modifier.fillMaxWidth(0.55f))
                        OutlinedButton(onClick = onRequestHistory) { Text("Grant") }
                    }
                }
                if (!state.backgroundGranted) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Background", Modifier.fillMaxWidth(0.55f))
                        OutlinedButton(onClick = onRequestBackground) { Text("Grant") }
                    }
                }
                if (state.missingTypes.isNotEmpty()) {
                    Text(
                        "Missing: ${state.missingTypes.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(onClick = onOpenHealthConnect) { Text("Open Health Connect") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onSnapshotPreset(30) }) { Text("30d") }
                    Button(onClick = { onSnapshotPreset(90) }) { Text("90d") }
                    Button(onClick = { onSnapshotPreset(365) }) { Text("365d") }
                    Button(onClick = onSnapshotAllTime) { Text("All time") }
                }
                CustomRange(onSnapshotCustom)
                Button(onClick = onExportNow, modifier = Modifier.fillMaxWidth()) { Text("Export now") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CadenceChip("Off", state.cadence == ScheduleCadence.NONE) { onSetCadence(ScheduleCadence.NONE) }
                    CadenceChip("Daily", state.cadence == ScheduleCadence.DAILY) { onSetCadence(ScheduleCadence.DAILY) }
                    CadenceChip("Weekly", state.cadence == ScheduleCadence.WEEKLY) { onSetCadence(ScheduleCadence.WEEKLY) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Watch", Modifier.fillMaxWidth(0.18f))
                    CadenceChip("Off", state.watcherSeconds == 0) { onSetWatcher(0) }
                    CadenceChip("1m", state.watcherSeconds == 60) { onSetWatcher(60) }
                    CadenceChip("5m", state.watcherSeconds == 300) { onSetWatcher(300) }
                    CadenceChip("15m", state.watcherSeconds == 900) { onSetWatcher(900) }
                }
            }
        }

        if (state.routeImportSessionId != null) {
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "GPS routes" + if (state.pendingRoutes.isNotEmpty()) " (${state.pendingRoutes.size})" else "",
                        Modifier.fillMaxWidth(0.55f),
                    )
                    Button(onClick = { onGrantRoute(state.routeImportSessionId) }) { Text("Import") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Recent", style = MaterialTheme.typography.titleSmall)
                if (state.history.isEmpty()) {
                    Text("none", style = MaterialTheme.typography.bodySmall)
                } else {
                    state.history.forEach { entry ->
                        Text(
                            "${entry.kind} · ${shortName(entry.fileName)} · ${entry.recordCount}" +
                                if (entry.verified) " · ok" else " · unverified",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRefresh) { Text("Refresh") }
            if (state.status.isNotBlank()) {
                Text(
                    state.status,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row {
        Text(label, Modifier.fillMaxWidth(0.35f), style = MaterialTheme.typography.bodyMedium)
        Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CadenceChip(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(text) })
}

private fun shortName(fileName: String?): String {
    if (fileName.isNullOrBlank()) return "none"
    return fileName.removePrefix("health-connect-").removePrefix("health-connect").trimStart('-')
        .ifBlank { fileName }
}

@Composable
private fun CustomRange(onSnapshotCustom: (LocalDate, LocalDate) -> Unit) {
    var start by remember { mutableStateOf("") }
    var end by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = start,
            onValueChange = { start = it },
            label = { Text("From") },
            placeholder = { Text("YYYY-MM-DD") },
            singleLine = true,
            isError = error,
            modifier = Modifier.fillMaxWidth(0.4f),
        )
        OutlinedTextField(
            value = end,
            onValueChange = { end = it },
            label = { Text("To") },
            placeholder = { Text("YYYY-MM-DD") },
            singleLine = true,
            isError = error,
            modifier = Modifier.fillMaxWidth(0.4f),
        )
        Button(onClick = {
            error = try {
                onSnapshotCustom(LocalDate.parse(start), LocalDate.parse(end))
                false
            } catch (t: Throwable) {
                true
            }
        }) { Text("Run") }
    }
}
