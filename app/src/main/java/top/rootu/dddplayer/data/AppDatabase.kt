package top.rootu.dddplayer.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Dao
interface VideoSettingsDao {
    @Query("SELECT * FROM video_settings WHERE uri = :uri")
    suspend fun getSettings(uri: String): VideoSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSettings(settings: VideoSettings)

    @Query("DELETE FROM video_settings WHERE lastUpdated < :timestamp")
    suspend fun deleteOldSettings(timestamp: Long)
}

@Database(entities = [VideoSettings::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoSettingsDao(): VideoSettingsDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        // Описываем миграцию: добавляем две новые колонки типа INTEGER (Long в Kotlin)
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE video_settings ADD COLUMN lastPosition INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE video_settings ADD COLUMN duration INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE video_settings ADD COLUMN audioTrackId TEXT")
                db.execSQL("ALTER TABLE video_settings ADD COLUMN subtitleTrackId TEXT")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java, "ddd_player_db"
                )
                    .addMigrations(MIGRATION_1_2) // Добавляем миграцию
                    .fallbackToDestructiveMigrationOnDowngrade(true) // На случай отката версий
                    .build().also { instance = it }
            }
        }
    }
}