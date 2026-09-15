package com.vaulthealth.exporter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vaulthealth.exporter.ui.theme.VaultHealthTheme

/**
 * Health Connect requires the app to expose an activity that handles
 * `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE`; if it cannot resolve one, it refuses to
 * open the consent screen ("App should support rationale intent, finishing!").
 *
 * This is a dedicated activity rather than an extra intent-filter on the launcher activity.
 */
class RationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VaultHealthTheme {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                ) {
                    Text(
                        text = "Vault Health Exporter",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = getString(R.string.permissions_rationale),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}
