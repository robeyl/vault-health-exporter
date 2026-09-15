package com.vaulthealth.exporter.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vaulthealth.core.model.VheJson
import com.vaulthealth.core.token.TokenErrorKind
import com.vaulthealth.exporter.domain.PendingRouteConsent
import com.vaulthealth.exporter.domain.ScheduleCadence
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vault_prefs")

data class VaultPrefsState(
    val treeUri: String?,
    val cadence: ScheduleCadence,
    val changeToken: String?,
    val tokenError: TokenErrorKind,
    val lastSnapshotAt: String?,
    val lastSnapshotFile: String?,
    val lastDeltaAt: String?,
    val lastDeltaFile: String?,
    val pendingRoutes: List<PendingRouteConsent>,
    val lastMessage: String?,
)

/**
 * All private state lives here: the chosen vault tree URI, schedule, change token and
 * the pending route-consent prompts. Never exported, never backed up.
 */
class VaultPrefs(private val context: Context) {

    private object Keys {
        val VAULT_URI = stringPreferencesKey("vault_tree_uri")
        val SCHEDULE = stringPreferencesKey("schedule_cadence")
        val CHANGE_TOKEN = stringPreferencesKey("change_token")
        val TOKEN_ERROR = stringPreferencesKey("token_error")
        val LAST_SNAPSHOT_AT = stringPreferencesKey("last_snapshot_at")
        val LAST_SNAPSHOT_FILE = stringPreferencesKey("last_snapshot_file")
        val LAST_DELTA_AT = stringPreferencesKey("last_delta_at")
        val LAST_DELTA_FILE = stringPreferencesKey("last_delta_file")
        val PENDING_ROUTES = stringPreferencesKey("pending_route_consent")
        val LAST_MESSAGE = stringPreferencesKey("last_message")
    }

    val state: Flow<VaultPrefsState> = context.dataStore.data.map { prefs ->
        VaultPrefsState(
            treeUri = prefs[Keys.VAULT_URI],
            cadence = prefs[Keys.SCHEDULE]?.let { runCatching { ScheduleCadence.valueOf(it) }.getOrNull() }
                ?: ScheduleCadence.NONE,
            changeToken = prefs[Keys.CHANGE_TOKEN],
            tokenError = prefs[Keys.TOKEN_ERROR]?.let { runCatching { TokenErrorKind.valueOf(it) }.getOrNull() }
                ?: TokenErrorKind.NONE,
            lastSnapshotAt = prefs[Keys.LAST_SNAPSHOT_AT],
            lastSnapshotFile = prefs[Keys.LAST_SNAPSHOT_FILE],
            lastDeltaAt = prefs[Keys.LAST_DELTA_AT],
            lastDeltaFile = prefs[Keys.LAST_DELTA_FILE],
            pendingRoutes = prefs[Keys.PENDING_ROUTES]?.let(::decodeRoutes) ?: emptyList(),
            lastMessage = prefs[Keys.LAST_MESSAGE],
        )
    }

    suspend fun snapshot(): VaultPrefsState = state.first()

    suspend fun setVaultUri(uri: String) = context.dataStore.edit { it[Keys.VAULT_URI] = uri }

    suspend fun setCadence(cadence: ScheduleCadence) =
        context.dataStore.edit { it[Keys.SCHEDULE] = cadence.name }

    suspend fun setChangeToken(token: String?) = context.dataStore.edit { prefs ->
        if (token == null) prefs.remove(Keys.CHANGE_TOKEN) else prefs[Keys.CHANGE_TOKEN] = token
    }

    suspend fun setTokenError(kind: TokenErrorKind) =
        context.dataStore.edit { it[Keys.TOKEN_ERROR] = kind.name }

    suspend fun recordSnapshot(at: String, fileName: String) = context.dataStore.edit {
        it[Keys.LAST_SNAPSHOT_AT] = at
        it[Keys.LAST_SNAPSHOT_FILE] = fileName
    }

    suspend fun recordDelta(at: String, fileName: String) = context.dataStore.edit {
        it[Keys.LAST_DELTA_AT] = at
        it[Keys.LAST_DELTA_FILE] = fileName
    }

    suspend fun setLastMessage(message: String) =
        context.dataStore.edit { it[Keys.LAST_MESSAGE] = message }

    suspend fun setPendingRoutes(routes: List<PendingRouteConsent>) = context.dataStore.edit {
        it[Keys.PENDING_ROUTES] = encodeRoutes(routes)
    }

    private fun encodeRoutes(routes: List<PendingRouteConsent>): String =
        VheJson.codec.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(PendingRouteConsent.serializer()),
            routes,
        )

    private fun decodeRoutes(raw: String): List<PendingRouteConsent> = runCatching {
        VheJson.codec.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(PendingRouteConsent.serializer()),
            raw,
        )
    }.getOrDefault(emptyList())
}
