package com.vaulthealth.exporter.storage.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "exports")
data class ExportRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val kind: String,
    val dirPath: String,
    val recordCount: Int,
    val upserts: Int,
    val deletions: Int,
    val summaries: Int,
    val sha256: String?,
    val verified: Boolean,
    val createdAt: String,
    val status: String,
    val contentId: String = "",
)

@Dao
interface ExportHistoryDao {
    @Insert
    suspend fun insert(entity: ExportRecordEntity): Long

    @Query("SELECT * FROM exports ORDER BY id DESC")
    fun observeAll(): Flow<List<ExportRecordEntity>>

    @Query("SELECT * FROM exports ORDER BY id DESC LIMIT 1")
    suspend fun latest(): ExportRecordEntity?

    @Query("SELECT COUNT(*) FROM exports WHERE kind = :kind AND contentId = :contentId AND status = 'written'")
    suspend fun countByContent(kind: String, contentId: String): Int
}

@Database(entities = [ExportRecordEntity::class], version = 2, exportSchema = false)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun exports(): ExportHistoryDao

    companion object {
        fun build(context: Context): VaultDatabase =
            Room.databaseBuilder(context, VaultDatabase::class.java, "vault_health_exporter.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
