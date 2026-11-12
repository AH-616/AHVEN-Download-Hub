package com.aven.downloader.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.aven.downloader.engine.DownloadKind
import kotlinx.coroutines.flow.Flow

class Converters {
    @TypeConverter fun statusToString(s: DownloadStatus): String = s.name
    @TypeConverter fun stringToStatus(s: String): DownloadStatus = DownloadStatus.valueOf(s)
    @TypeConverter fun kindToString(k: DownloadKind): String = k.name
    @TypeConverter fun stringToKind(s: String): DownloadKind = DownloadKind.valueOf(s)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status = 'QUEUED' ORDER BY createdAt ASC, id ASC")
    suspend fun queued(): List<DownloadEntity>

    @Insert
    suspend fun insert(e: DownloadEntity): Long

    @Query("UPDATE downloads SET status = :status, errorSummary = :summary, errorDetails = :details, statusDetail = :detail, speedBps = 0, etaSec = 0 WHERE id = :id")
    suspend fun setStatus(id: Long, status: DownloadStatus, summary: String?, details: String?, detail: String?)

    @Query("UPDATE downloads SET progress = :progress, totalBytes = :total, speedBps = :speed, etaSec = :eta, statusDetail = :detail WHERE id = :id")
    suspend fun setProgress(id: Long, progress: Float, total: Long, speed: Long, eta: Long, detail: String?)

    @Query("UPDATE downloads SET status = 'COMPLETED', progress = 100, outputUri = :uri, outputName = :name, sizeBytes = :size, finishedAt = :finishedAt, statusDetail = :detail, errorSummary = NULL, errorDetails = NULL, speedBps = 0, etaSec = 0 WHERE id = :id")
    suspend fun setCompleted(id: Long, uri: String, name: String, size: Long, finishedAt: Long, detail: String?)

    @Query("UPDATE downloads SET status = 'QUEUED', errorSummary = NULL, errorDetails = NULL, statusDetail = NULL WHERE id = :id")
    suspend fun requeue(id: Long)

    @Query("UPDATE downloads SET status = 'QUEUED', progress = 0, totalBytes = 0, errorSummary = NULL, errorDetails = NULL, statusDetail = NULL, createdAt = :now WHERE id = :id")
    suspend fun restart(id: Long, now: Long)

    @Query("UPDATE downloads SET fileSource = :source WHERE id = :id")
    suspend fun setSource(id: Long, source: String)

    @Query("UPDATE downloads SET status = 'PAUSED', statusDetail = 'Interrupted — tap resume', speedBps = 0, etaSec = 0 WHERE status = 'RUNNING'")
    suspend fun markRunningInterrupted()

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM downloads WHERE status = 'COMPLETED'")
    suspend fun clearCompleted()

    @Query("DELETE FROM downloads WHERE status IN ('FAILED', 'CANCELED')")
    suspend fun clearFailed()
}

@Database(entities = [DownloadEntity::class], version = 3, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloads(): DownloadDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "aven.db")
                .addMigrations(object : androidx.room.migration.Migration(1, 2) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE downloads ADD COLUMN audioLang TEXT NOT NULL DEFAULT ''")
                    }
                }, object : androidx.room.migration.Migration(2, 3) {
                    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE downloads ADD COLUMN pinned TEXT NOT NULL DEFAULT ''")
                        db.execSQL("ALTER TABLE downloads ADD COLUMN fileSource TEXT NOT NULL DEFAULT ''")
                    }
                })
                .build()
    }
}
