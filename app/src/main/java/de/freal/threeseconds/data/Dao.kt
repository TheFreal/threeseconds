package de.freal.threeseconds.data

import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(clip: Clip)

    @Query("SELECT * FROM clips ORDER BY recordedAt DESC")
    fun observeAll(): Flow<List<Clip>>

    @Query("SELECT * FROM clips WHERE day LIKE :monthPrefix || '%' ORDER BY recordedAt ASC")
    suspend fun forMonth(monthPrefix: String): List<Clip>

    @Query("SELECT DISTINCT substr(day, 1, 7) AS m FROM clips ORDER BY m DESC")
    fun observeMonths(): Flow<List<String>>

    @Query("SELECT * FROM clips WHERE day = :day ORDER BY recordedAt ASC")
    suspend fun forDay(day: String): List<Clip>

    @Query("SELECT COUNT(*) FROM clips WHERE day = :day")
    suspend fun countForDay(day: String): Int

    @Query("SELECT * FROM clips WHERE id = :id")
    suspend fun byId(id: String): Clip?

    @Query("DELETE FROM clips WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface DayLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(log: DayLog)

    @Query("SELECT * FROM day_log WHERE day = :day")
    suspend fun forDay(day: String): DayLog?

    @Query("SELECT * FROM day_log ORDER BY day DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<DayLog>

    @Query("SELECT * FROM day_log ORDER BY day DESC")
    fun observeAll(): Flow<List<DayLog>>
}

@Database(
    entities = [Clip::class, DayLog::class, Attempt::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun clips(): ClipDao
    abstract fun days(): DayLogDao
    abstract fun attempts(): AttemptDao
}
