package com.vaulthealth.exporter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import com.vaulthealth.exporter.hc.HealthPermissions
import com.vaulthealth.exporter.ui.HomeScreen
import com.vaulthealth.exporter.ui.MainViewModel
import com.vaulthealth.exporter.ui.theme.VaultHealthTheme
import com.vaulthealth.exporter.work.WorkScheduler

private const val ACTION_HEALTH_CONNECT_SETTINGS = "androidx.health.ACTION_HEALTH_CONNECT_SETTINGS"

class MainActivity : ComponentActivity() {

    private val container get() = (application as VaultHealthApp).container
    private val viewModel: MainViewModel by viewModels {
        MainViewModel.factory(application, container)
    }

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
            // IMPORTANT: only one activity-result contract may be launched at a time.
            // Launching the runtime-permission dialog alongside this one cancels it, which
            // means Health Connect never registers the app. Chain it instead.
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                activityRecognitionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            }
        }

    private val activityRecognitionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            viewModel.refreshPermissions()
        }

    /**
     * GPS: request the bulk route permission on its own. connect-client 1.1.0 rejects it when it
     * is bundled with the record permissions and closes the whole sheet, so it must be alone.
     */
    private val routePermissionLauncher =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            if (HealthPermissions.READ_EXERCISE_ROUTES in granted) {
                viewModel.importAllRoutes()
            } else {
                viewModel.onRoutePermissionDenied()
            }
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
                    // Health Connect record permissions on their own. Never combine two launches.
                    onRequestPermissions = {
                        healthPermissionLauncher.launch(HealthPermissions.readPermissions)
                    },
                    // Special grants must ride along with at least one record permission,
                    // otherwise Health Connect has nothing to show on the consent screen.
                    onRequestHistory = {
                        healthPermissionLauncher.launch(
                            HealthPermissions.readPermissions + HealthPermissions.READ_HISTORY,
                        )
                    },
                    onRequestBackground = {
                        healthPermissionLauncher.launch(
                            HealthPermissions.readPermissions + HealthPermissions.READ_BACKGROUND,
                        )
                    },
                    onOpenHealthConnect = { openHealthConnectSettings() },
                    onGrantRoute = { _ ->
                        // Requesting the route permission through connect-client 1.1.0 makes
                        // Health Connect flash and close, so only do it when it is actually
                        // missing; otherwise import straight away.
                        if (viewModel.state.value.routesGranted) {
                            viewModel.importAllRoutes()
                        } else {
                            routePermissionLauncher.launch(setOf(HealthPermissions.READ_EXERCISE_ROUTES))
                        }
                    },
                    onSnapshotPreset = { days ->
                        viewModel.runSnapshot(
                            java.time.LocalDate.now().minusDays(days - 1L),
                            java.time.LocalDate.now(),
                        )
                    },
                    onSnapshotCustom = { start, end -> viewModel.runSnapshot(start, end) },
                    onExportNow = { viewModel.exportNow() },
                    onSetCadence = { viewModel.setCadence(it) },
                    onRefresh = { viewModel.refreshPermissions() },
                )
            }
        }
    }

    private fun openHealthConnectSettings() {
        val intent = Intent(ACTION_HEALTH_CONNECT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { startActivity(intent) }.isSuccess
        if (!opened) {
            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { startActivity(fallback) }
        }
    }
}
