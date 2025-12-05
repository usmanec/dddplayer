package top.rootu.dddplayer.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Dao
interface VideoSettingsDao {
    @Query("SELECT * FROM video_settings WHERE uri = :uri")
    suspend fun getSettings(uri: String): VideoSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSettings(settings: VideoSettings)

    @Query("DELETE FROM video_settings WHERE lastUpdated < :timestamp")
    suspend fun deleteOldSettings(timestamp: Long)
}

@Database(entities = [VideoSettings::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoSettingsDao(): VideoSettingsDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java, "ddd_player_db"
                )
                    .fallbackToDestructiveMigration(false)
                    .build().also { instance = it }
            }
        }
    }
}