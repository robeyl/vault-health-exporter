package com.vaulthealth.exporter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
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
    onSnapshotCustom: (LocalDate, LocalDate) -> Unit,
    onExportNow: () -> Unit,
    onSetCadence: (ScheduleCadence) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Vault Health Exporter", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Fully offline: no account, no cloud, no analytics and no INTERNET permission.",
            style = MaterialTheme.typography.bodySmall,
        )

        Section("Status") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Health Connect: ", fontWeight = FontWeight.Bold)
                Text(
                    when (state.availability) {
                        SdkAvailability.AVAILABLE -> "available"
                        SdkAvailability.UPDATE_REQUIRED -> "provider update required"
                        SdkAvailability.NOT_SUPPORTED -> "not supported"
                    },
                )
                Spacer(Modifier.fillMaxWidth(0.05f))
                if (state.busy) CircularProgressIndicator(Modifier.height(16.dp).fillMaxWidth(0.1f))
            }
            Text("Last snapshot: ${state.lastSnapshotFile ?: "(none)"}")
            Text("  at ${state.lastSnapshotAt ?: "-"}")
            Text("Last delta: ${state.lastDeltaFile ?: "(none)"}")
            Text("  at ${state.lastDeltaAt ?: "-"}")
            Text("Change token stored: ${if (state.changeTokenPresent) "yes" else "no"}")
            state.tokenWarning?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }

        Section("Vault folder (Syncthing-synced)") {
            Text("Selected: ${state.vaultPath ?: "(none)"}", maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text("Writable: ${if (state.vaultWritable) "yes" else "no"}")
            Button(onClick = onSelectFolder) { Text("Select folder") }
        }

        Section("Health Connect permissions") {
            state.permissions.forEach { permission ->
                Row {
                    Text(if (permission.granted) "GRANTED  " else "MISSING  ", fontWeight = FontWeight.Bold)
                    Text("${permission.label}  (${permission.permission.substringAfterLast('.')})")
                }
            }
            HorizontalDivider()
            Text(if (state.historyGranted) "GRANTED  Read health data history" else "MISSING  Read health data history")
            Text(if (state.backgroundGranted) "GRANTED  Read health data in background" else "MISSING  Read health data in background")
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRequestPermissions) { Text("Permissions") }
                OutlinedButton(onClick = onRequestHistory) { Text("History") }
                OutlinedButton(onClick = onRequestBackground) { Text("Background") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRefresh) { Text("Refresh") }
                OutlinedButton(onClick = onOpenHealthConnect) { Text("Open Health Connect") }
            }
        }

        Section("Historical snapshot (one-time)") {
            Text("Writes one immutable NDJSON + .sha256 into snapshots/.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSnapshotPreset(30) }) { Text("30d") }
                Button(onClick = { onSnapshotPreset(90) }) { Text("90d") }
                Button(onClick = { onSnapshotPreset(365) }) { Text("365d") }
            }
            CustomRange(onSnapshotCustom)
        }

        Section("Ongoing incremental export") {
            Text("Uses Health Connect change tokens; writes immutable deltas with tombstones.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onExportNow) { Text("Export now") }
                Spacer(Modifier.fillMaxWidth(0.05f))
                FilterChip(
                    selected = state.cadence == ScheduleCadence.NONE,
                    onClick = { onSetCadence(ScheduleCadence.NONE) },
                    label = { Text("Off") },
                )
                FilterChip(
                    selected = state.cadence == ScheduleCadence.DAILY,
                    onClick = { onSetCadence(ScheduleCadence.DAILY) },
                    label = { Text("Daily") },
                )
                FilterChip(
                    selected = state.cadence == ScheduleCadence.WEEKLY,
                    onClick = { onSetCadence(ScheduleCadence.WEEKLY) },
                    label = { Text("Weekly") },
                )
            }
        }

        if (state.pendingRoutes.isNotEmpty()) {
            Section("Exercise routes needing consent") {
                Text(
                    "Routes are opt-in and only collected in the foreground.",
                    style = MaterialTheme.typography.bodySmall,
                )
                state.pendingRoutes.forEach { pending ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.fillMaxWidth(0.65f)) {
                            Text(pending.title, fontWeight = FontWeight.Bold)
                            Text(pending.startTime, style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = { onGrantRoute(pending.sessionId) }) { Text("Grant route access") }
                    }
                }
            }
        }

        Section("Export history") {
            if (state.history.isEmpty()) {
                Text("(none yet)")
            } else {
                state.history.forEach { entry ->
                    Text(
                        "${entry.kind}  ${entry.fileName}  records=${entry.recordCount}  verified=${entry.verified}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        if (state.status.isNotBlank()) {
            Text(state.status, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun CustomRange(onSnapshotCustom: (LocalDate, LocalDate) -> Unit) {
    var start by remember { mutableStateOf("") }
    var end by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = start,
            onValueChange = { start = it },
            label = { Text("Start YYYY-MM-DD") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(0.42f),
        )
        OutlinedTextField(
            value = end,
            onValueChange = { end = it },
            label = { Text("End YYYY-MM-DD") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(0.42f),
        )
        Button(onClick = {
            error = try {
                onSnapshotCustom(LocalDate.parse(start), LocalDate.parse(end))
                null
            } catch (t: Throwable) {
                "Enter dates as YYYY-MM-DD"
            }
        }) { Text("Run") }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
