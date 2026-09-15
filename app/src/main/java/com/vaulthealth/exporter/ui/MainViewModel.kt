package com.vaulthealth.exporter.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vaulthealth.core.daterange.DateRange
import com.vaulthealth.core.naming.VaultPaths
import com.vaulthealth.core.token.TokenIssue
import com.vaulthealth.core.token.TokenPolicy
import com.vaulthealth.exporter.AppContainer
import com.vaulthealth.exporter.domain.DeltaRun
import com.vaulthealth.exporter.domain.PendingRouteConsent
import com.vaulthealth.exporter.domain.ScheduleCadence
import com.vaulthealth.exporter.domain.WriteOutcome
import com.vaulthealth.exporter.hc.HealthPermissions
import com.vaulthealth.exporter.hc.RecordMapper
import com.vaulthealth.exporter.hc.SdkAvailability
import com.vaulthealth.exporter.storage.VaultPrefsState
import com.vaulthealth.exporter.storage.db.ExportRecordEntity
import com.vaulthealth.exporter.work.WorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

data class PermissionStatus(
    val label: String,
    val permission: String,
    val granted: Boolean,
    val special: Boolean = false,
)

data class UiState(
    val availability: SdkAvailability = SdkAvailability.NOT_SUPPORTED,
    val vaultPath: String? = null,
    val vaultWritable: Boolean = false,
    val cadence: ScheduleCadence = ScheduleCadence.NONE,
    val permissions: List<PermissionStatus> = emptyList(),
    val historyGranted: Boolean = false,
    val backgroundGranted: Boolean = false,
    val changeTokenPresent: Boolean = false,
    val tokenWarning: String? = null,
    val lastSnapshotAt: String? = null,
    val lastSnapshotFile: String? = null,
    val lastDeltaAt: String? = null,
    val lastDeltaFile: String? = null,
    val pendingRoutes: List<PendingRouteConsent> = emptyList(),
    val history: List<ExportRecordEntity> = emptyList(),
    val status: String = "",
    val busy: Boolean = false,
)

private data class Flags(
    val activityRecognition: Boolean,
    val vaultWritable: Boolean,
)

private data class LocalState(
    val status: String,
    val busy: Boolean,
    val availability: SdkAvailability,
    val granted: Set<String>,
    val flags: Flags,
)

class MainViewModel(
    private val app: Application,
    private val container: AppContainer,
) : ViewModel() {

    private val status = MutableStateFlow("")
    private val busy = MutableStateFlow(false)
    private val availability = MutableStateFlow(SdkAvailability.NOT_SUPPORTED)
    private val granted = MutableStateFlow<Set<String>>(emptySet())
    private val activityRecognition = MutableStateFlow(false)
    private val vaultWritable = MutableStateFlow(false)

    private val flags = combine(activityRecognition, vaultWritable) { activity, writable ->
        Flags(activity, writable)
    }

    private val local = combine(status, busy, availability, granted, flags) {
            statusText, isBusy, sdk, grants, flagState ->
        LocalState(statusText, isBusy, sdk, grants, flagState)
    }

    val state: StateFlow<UiState> = combine(container.prefs.state, container.history.observeAll(), local) {
            prefs, history, mine ->
        buildState(prefs, history, mine)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        availability.value = container.gateway.availability()
        refreshPermissions()
    }

    fun refreshPermissions() {
        viewModelScope.launch {
            availability.value = container.gateway.availability()
            activityRecognition.value = ContextCompat.checkSelfPermission(
                app,
                Manifest.permission.ACTIVITY_RECOGNITION,
            ) == PackageManager.PERMISSION_GRANTED

            val client = container.gateway.clientOrNull()
            granted.value = if (client == null) {
                emptySet()
            } else {
                try {
                    client.permissionController.getGrantedPermissions()
                } catch (t: Throwable) {
                    emptySet()
                }
            }

            val raw = container.prefs.snapshot().treeUri
            vaultWritable.value = raw != null && withContext(Dispatchers.IO) {
                container.writer.canWrite(Uri.parse(raw))
            }
        }
    }

    fun onFolderSelected(uri: Uri) {
        viewModelScope.launch {
            container.prefs.setVaultUri(uri.toString())
            val failures = withContext(Dispatchers.IO) {
                container.writer.ensureDirectories(
                    uri,
                    listOf(VaultPaths.SNAPSHOTS, VaultPaths.DELTAS, VaultPaths.SUMMARIES),
                )
            }
            vaultWritable.value = withContext(Dispatchers.IO) { container.writer.canWrite(uri) }
            status.value = if (failures.isEmpty()) {
                "Vault folders ready. Scheduled exports stay offline."
            } else {
                "Could not create folders: ${failures.joinToString()}"
            }
        }
    }

    fun onPermissionsResult(result: Set<String>) {
        granted.value = result
        refreshPermissions()
        status.value = "${result.size} Health Connect permissions granted"
    }

    fun setCadence(cadence: ScheduleCadence) {
        viewModelScope.launch {
            container.prefs.setCadence(cadence)
            WorkScheduler.apply(app, cadence)
            status.value = when (cadence) {
                ScheduleCadence.NONE -> "Scheduled export disabled"
                ScheduleCadence.DAILY -> "Scheduled export enabled: daily"
                ScheduleCadence.WEEKLY -> "Scheduled export enabled: weekly"
            }
        }
    }

    fun runSnapshot(startDay: LocalDate, endDay: LocalDate) {
        viewModelScope.launch {
            if (endDay.isBefore(startDay)) {
                status.value = "End date must not be before start date"
                return@launch
            }
            val uri = vaultUriOrReport() ?: return@launch
            busy.value = true
            try {
                val zone = ZoneId.systemDefault()
                val range = DateRange.ofLocalDates(startDay, endDay, zone)
                val result = withContext(Dispatchers.IO) {
                    container.snapshotEngine.run(
                        treeUri = uri,
                        range = range,
                        zone = zone,
                        allowRoutes = true,
                    ) { progress -> status.value = progress }
                }
                status.value = result.message + checksumNote(result.outcomes)
            } catch (t: Throwable) {
                status.value = "Snapshot failed: ${describeError(t)}"
            } finally {
                busy.value = false
                refreshPermissions()
            }
        }
    }

    fun exportNow() {
        viewModelScope.launch {
            val uri = vaultUriOrReport() ?: return@launch
            busy.value = true
            try {
                val run = withContext(Dispatchers.IO) {
                    container.deltaEngine.run(uri, ZoneId.systemDefault(), allowRoutes = false)
                }
                status.value = when (run) {
                    is DeltaRun.Completed ->
                        "Delta ${run.fileName}: ${run.upserts} upserts, ${run.deletions} deletions, ${run.summaries} summaries"

                    DeltaRun.NoChanges -> "No new Health Connect changes"
                    is DeltaRun.NeedsSnapshot -> run.prompt
                    is DeltaRun.Failed -> "Export failed: ${run.reason}"
                }
            } catch (t: Throwable) {
                status.value = "Export failed: ${describeError(t)}"
            } finally {
                busy.value = false
            }
        }
    }

    fun onRouteGranted(sessionId: String, route: ExerciseRoute?) {
        if (route == null) {
            status.value = "Route access was not granted for $sessionId"
            return
        }
        viewModelScope.launch {
            val uri = vaultUriOrReport() ?: return@launch
            try {
                val run = withContext(Dispatchers.IO) {
                    val session = container.gateway.readOne(ExerciseSessionRecord::class, sessionId)
                    val record = session?.let { RecordMapper.map(it, allowRoutes = true) }
                    if (record == null) {
                        null
                    } else {
                        container.deltaEngine.writeRoutePatch(uri, record)
                    }
                }
                if (run == null) {
                    status.value = "Could not re-read session $sessionId"
                    return@launch
                }
                if (run is DeltaRun.Completed) {
                    val remaining = container.prefs.snapshot().pendingRoutes
                        .filterNot { it.sessionId == sessionId }
                    container.prefs.setPendingRoutes(remaining)
                    status.value = "Route patch ${run.fileName} written"
                } else {
                    status.value = "Route patch failed for $sessionId"
                }
            } catch (t: Throwable) {
                status.value = "Route patch failed: ${t.message ?: t::class.java.simpleName}"
            }
        }
    }

    /** Reads the folder directly from private storage rather than a possibly-uncollected flow. */
    private suspend fun vaultUriOrReport(): Uri? {
        val raw = container.prefs.snapshot().treeUri
        if (raw == null) {
            status.value = "Select your vault folder first"
            return null
        }
        return Uri.parse(raw)
    }

    private fun buildState(
        prefs: VaultPrefsState,
        history: List<ExportRecordEntity>,
        mine: LocalState,
    ): UiState {
        val permissionStatuses = HealthPermissions.slots.map { slot ->
            PermissionStatus(
                label = slot.recordType.wireName,
                permission = slot.permission,
                granted = slot.permission in mine.granted,
            )
        }
        return UiState(
            availability = mine.availability,
            vaultPath = prefs.treeUri,
            vaultWritable = mine.flags.vaultWritable,
            cadence = prefs.cadence,
            permissions = permissionStatuses,
            historyGranted = HealthPermissions.READ_HISTORY in mine.granted,
            backgroundGranted = HealthPermissions.READ_BACKGROUND in mine.granted,
            changeTokenPresent = !prefs.changeToken.isNullOrBlank(),
            tokenWarning = TokenPolicy.promptFor(TokenPolicy.issueFor(prefs.tokenError))
                ?: if (prefs.changeToken.isNullOrBlank()) {
                    TokenPolicy.promptFor(TokenIssue.MISSING)
                } else {
                    null
                },
            lastSnapshotAt = prefs.lastSnapshotAt,
            lastSnapshotFile = prefs.lastSnapshotFile,
            lastDeltaAt = prefs.lastDeltaAt,
            lastDeltaFile = prefs.lastDeltaFile,
            pendingRoutes = prefs.pendingRoutes,
            history = history.take(20),
            status = mine.status.ifBlank { prefs.lastMessage ?: "" },
            busy = mine.busy,
        )
    }

    private fun describeError(t: Throwable): String {
        val message = t.message ?: t::class.java.simpleName
        val looksLikePermission = message.contains("permission", ignoreCase = true) ||
            message.contains("SecurityException", ignoreCase = true)
        return if (looksLikePermission) {
            "Health Connect access is missing. Tap \"Permissions\" above and grant read access, " +
                "then try again. ($message)"
        } else {
            message
        }
    }

    private fun checksumNote(outcomes: List<WriteOutcome>): String {        val written = outcomes.filterIsInstance<WriteOutcome.Written>()
        if (written.isEmpty()) return ""
        val verified = written.count { it.verified }
        return " • checksums verified for $verified/${written.size} files"
    }

    companion object {
        fun factory(app: Application, container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MainViewModel(app, container) as T
            }
    }
}
