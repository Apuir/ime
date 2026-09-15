package com.ninthsoft.ime.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration

@Database(entities = [CandidateSorting::class, ClipboardRecord::class, CandidatePrefer::class, PhraseRecord::class], version = 9, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun candidateSortingDao(): CandidateSortingDao

    abstract fun clipboardDao(): ClipboardDao

    abstract fun candidatePreferDao(): CandidatePreferDao

    abstract fun phraseDao(): PhraseDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ime_database"
                )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_8_9,
                )
                .fallbackToDestructiveMigration().build().also { INSTANCE = it }
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

        private val MIGRATION_3_4: Migration = Migration(
            startVersion = 3,
            endVersion = 4,
        ) { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `candidate_prefers` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `text` TEXT NOT NULL,
                    `context` TEXT NOT NULL,
                    `click_count` INTEGER NOT NULL DEFAULT 1,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL
                )
                """
            )
        }

        private val MIGRATION_4_5: Migration = Migration(
            startVersion = 4,
            endVersion = 5,
        ) { db ->
            db.execSQL("DROP TABLE IF EXISTS `candidate_prefers`")
            db.execSQL(
                """
                CREATE TABLE `candidate_prefers` (
                    `text` TEXT NOT NULL PRIMARY KEY,
                    `context` TEXT NOT NULL,
                    `click_count` INTEGER NOT NULL DEFAULT 1,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL
                )
                """
            )
        }

        private val MIGRATION_5_6: Migration = Migration(
            startVersion = 5,
            endVersion = 6,
        ) { db ->
            db.execSQL("ALTER TABLE `clipboard_records` ADD COLUMN `deletedAt` INTEGER NOT NULL DEFAULT 0")
        }

        private val MIGRATION_6_7: Migration = Migration(
            startVersion = 6,
            endVersion = 7,
        ) { db ->
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `phrase_records` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `text` TEXT NOT NULL,
                    `label` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL
                )
                """
            )
        }

        /**
         * v8 → v9：给 `candidate_prefers` 加负反馈字段（误选降权用）。
         *
         * ⚠️ 这里**必须**有迁移。本库启用了 `fallbackToDestructiveMigration()`，
         * 一旦缺迁移就会被静默清库 —— 用户的剪贴板历史、常用语、候选排序记录全丢。
         * （v7 → v8 就没有迁移，那次是上游 `danjian/ime` 加的 `candidate_sorting_v2`；
         * 本次不动它，但升级到 v8 的那一批数据已经丢过了。）
         */
        private val MIGRATION_8_9: Migration = Migration(
            startVersion = 8,
            endVersion = 9,
        ) { db ->
            db.execSQL(
                "ALTER TABLE `candidate_prefers` ADD COLUMN `bad_count` INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "ALTER TABLE `candidate_prefers` ADD COLUMN `last_bad_at` INTEGER NOT NULL DEFAULT 0"
            )
        }
    }
}