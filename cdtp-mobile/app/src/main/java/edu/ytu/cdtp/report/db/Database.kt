package edu.ytu.cdtp.report.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "test_records", indices = [Index(value = ["idx"], unique = true)])
data class TestRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Zincirdeki sıra numarası (1, 2, 3, …) */
    val idx: Long,
    val createdAtMs: Long,
    val taskType: String,
    val taskTitle: String,
    val passed: Boolean,
    val payloadJson: String,
    val prevHash: String,
    val recordHash: String,
)

/**
 * Yalnız INSERT ve SELECT. Uygulamada güncelleme/silme yolu yoktur (Plan 7.8).
 */
@Dao
interface TestRecordDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(e: TestRecordEntity): Long

    @Query("SELECT * FROM test_records ORDER BY id DESC")
    fun observeAll(): Flow<List<TestRecordEntity>>

    @Query("SELECT * FROM test_records ORDER BY id ASC")
    suspend fun allAscending(): List<TestRecordEntity>

    @Query("SELECT * FROM test_records WHERE id = :id")
    suspend fun byId(id: Long): TestRecordEntity?

    @Query("SELECT * FROM test_records ORDER BY id DESC LIMIT 1")
    suspend fun last(): TestRecordEntity?
}

@Database(entities = [TestRecordEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun records(): TestRecordDao

    companion object {
        fun build(ctx: Context) = Room.databaseBuilder(ctx, AppDatabase::class.java, "cdtp_records.db").build()
    }
}
