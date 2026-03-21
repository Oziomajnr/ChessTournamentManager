package com.chess.tournament.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.chess.tournament.db.dao.PairingDao
import com.chess.tournament.db.dao.PlayerDao
import com.chess.tournament.db.dao.TournamentDao
import com.chess.tournament.db.entity.Pairing
import com.chess.tournament.db.entity.Player
import com.chess.tournament.db.entity.Tournament
import com.chess.tournament.db.entity.TournamentPlayer

@Database(
    entities = [
        Player::class,
        Tournament::class,
        TournamentPlayer::class,
        Pairing::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun playerDao(): PlayerDao
    abstract fun tournamentDao(): TournamentDao
    abstract fun pairingDao(): PairingDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tournaments ADD COLUMN shareCode TEXT DEFAULT NULL")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "chess_tournament.db"
                )
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
