package com.vaulthealth.exporter

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.contracts.ExerciseRouteRequestContract
import com.vaulthealth.exporter.hc.HealthPermissions
import com.vaulthealth.exporter.ui.HomeScreen
import com.vaulthealth.exporter.ui.MainViewModel
import com.vaulthealth.exporter.ui.theme.VaultHealthTheme
import com.vaulthealth.exporter.work.WorkScheduler

class MainActivity : ComponentActivity() {

    private val container get() = (application as VaultHealthApp).container
    private val viewModel: MainViewModel by viewModels {
        MainViewModel.factory(application, container)
    }

    private var routeSessionId: String? = null

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri ?: return@registerForActivityResult
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            viewModel.onFolderSelected(uri)
        }

    private val healthPermissionLauncher =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            viewModel.onPermissionsResult(granted)
        }

    private val activityRecognitionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            viewModel.refreshPermissions()
        }

    /**
     * Foreground-only route consent. Android shows the Health Connect grant sheet for the given
     * exercise session; only after approval do we read and export its route points.
     */
    private val routeLauncher =
        registerForActivityResult(ExerciseRouteRequestContract()) { route ->
            val sessionId = routeSessionId
            routeSessionId = null
            if (sessionId != null) viewModel.onRouteGranted(sessionId, route)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WorkScheduler.enqueueCatchUp(this)

        setContent {
            VaultHealthTheme {
                val state by viewModel.state.collectAsState()
                HomeScreen(
                    state = state,
                    onSelectFolder = { folderPicker.launch(null) },
                    onRequestPermissions = {
                        healthPermissionLauncher.launch(HealthPermissions.readPermissions)
                        activityRecognitionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                    },
                    onRequestHistory = {
                        healthPermissionLauncher.launch(setOf(HealthPermissions.READ_HISTORY))
                    },
                    onRequestBackground = {
                        healthPermissionLauncher.launch(setOf(HealthPermissions.READ_BACKGROUND))
                    },
                    onGrantRoute = { sessionId ->
                        routeSessionId = sessionId
                        routeLauncher.launch(sessionId)
                    },
                    onSnapshotPreset = { days -> viewModel.runSnapshot(java.time.LocalDate.now().minusDays(days - 1L), java.time.LocalDate.now()) },
                    onSnapshotCustom = { start, end -> viewModel.runSnapshot(start, end) },
                    onExportNow = { viewModel.exportNow() },
                    onSetCadence = { viewModel.setCadence(it) },
                    onRefresh = { viewModel.refreshPermissions() },
                )
            }
        }
    }
}
