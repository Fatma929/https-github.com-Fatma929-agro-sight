package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sql_field_audits")
data class SqlFieldAuditEntity(
    @PrimaryKey val id: String,
    val farmerId: String,
    val farmerName: String,
    val whatsappPhone: String,
    val gmailAddress: String,
    val nationalId: String,
    val fieldName: String,
    val cropType: String,
    val areaFeddans: Float,
    val soilPh: Float,
    val soilEce: Float,
    val et0MmDay: Float,
    val grossM3PerFeddan: Float,
    val valveRunHours: Float,
    val ndviMean: Float,
    val ndreMean: Float,
    val psriMean: Float,
    val detectedRiskName: String,
    val recordedAt: String
)

@Dao
interface SqlFieldAuditDao {
    @Query("SELECT * FROM sql_field_audits ORDER BY recordedAt DESC LIMIT 50")
    fun observeAllAudits(): Flow<List<SqlFieldAuditEntity>>

    @Query("SELECT COUNT(*) FROM sql_field_audits")
    suspend fun getAuditCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAudit(entity: SqlFieldAuditEntity)

    @Query("DELETE FROM sql_field_audits WHERE id = :id")
    suspend fun deleteAuditById(id: String)
}

@Database(
    entities = [SqlFieldAuditEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AgroSightRoomDatabase : RoomDatabase() {
    abstract fun sqlFieldAuditDao(): SqlFieldAuditDao

    companion object {
        @Volatile
        private var INSTANCE: AgroSightRoomDatabase? = null

        fun getInstance(context: Context): AgroSightRoomDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AgroSightRoomDatabase::class.java,
                    "agrosight_relational_sql.db"
                )
                    .fallbackToDestructiveMigration(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
