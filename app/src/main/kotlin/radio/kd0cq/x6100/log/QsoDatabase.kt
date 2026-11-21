// OpenX6X00-Android - open companion app for the Xiegu X6100 running OpenX6X00
// Copyright (C) 2026 Chris Fink KD0CQ (theMadCatter)
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The local copy of QSOs. The radio's day log is a separate write (`logq`). This table is
// what "worked before", the map and the ADIF export read, including after the radio is off.

package radio.kd0cq.x6100.log

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

@Entity(tableName = "qsos")
data class QsoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val call: String,
    val freqHz: Int,
    val mode: String,
    val timeUtcMs: Long,
    val rstSent: String,
    val rstRcvd: String,
    val name: String,
    val qth: String,
    val grid: String,
    val country: String,
    val radioLogId: Long,
)

fun QsoEntity.toQso(): Qso = Qso(call, freqHz, mode, timeUtcMs, rstSent, rstRcvd, name, qth, grid, country, radioLogId)

fun Qso.toEntity(): QsoEntity = QsoEntity(
    call = call, freqHz = freqHz, mode = mode, timeUtcMs = timeUtcMs,
    rstSent = rstSent, rstRcvd = rstRcvd, name = name, qth = qth, grid = grid,
    country = country, radioLogId = radioLogId,
)

@Dao
interface QsoDao {
    @Query("SELECT * FROM qsos ORDER BY timeUtcMs DESC")
    fun all(): Flow<List<QsoEntity>>

    @Query("SELECT * FROM qsos ORDER BY timeUtcMs DESC")
    suspend fun list(): List<QsoEntity>

    @Insert
    suspend fun insert(row: QsoEntity): Long
}

@Database(entities = [QsoEntity::class], version = 1, exportSchema = false)
abstract class QsoDatabase : RoomDatabase() {
    abstract fun qsos(): QsoDao

    companion object {
        fun open(context: Context): QsoDatabase =
            Room.databaseBuilder(context.applicationContext, QsoDatabase::class.java, "qso-log.db").build()
    }
}
