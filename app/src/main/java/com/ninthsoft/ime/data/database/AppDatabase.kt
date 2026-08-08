package com.ninthsoft.ime.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration

@Database(entities = [CandidateSorting::class, ClipboardRecord::class], version = 3, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun candidateSortingDao(): CandidateSortingDao

    abstract fun clipboardDao(): ClipboardDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ime_database"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
            }
        }

        private val MIGRATION_1_2: Migration = Migration(
            startVersion = 1,
            endVersion = 2,
        ) { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `clipboard_records` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`text` TEXT NOT NULL, " +
                    "`timestamp` INTEGER NOT NULL, " +
                    "`cloud` INTEGER NOT NULL)"
            )
        }

        private val MIGRATION_2_3: Migration = Migration(
            startVersion = 2,
            endVersion = 3,
        ) { db ->
            db.execSQL("ALTER TABLE `clipboard_records` ADD COLUMN `deleted` INTEGER NOT NULL DEFAULT 0")
        }
    }
}