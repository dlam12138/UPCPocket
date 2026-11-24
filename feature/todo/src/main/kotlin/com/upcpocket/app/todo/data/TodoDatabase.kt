package com.upcpocket.app.todo.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 本机待办数据库与学校账号数据分离；换号清理不会删除这个文件。
 */
@Database(
    entities = [TodoTaskEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class TodoDatabase : RoomDatabase() {

    abstract fun taskDao(): TodoTaskDao

    companion object {
        const val DB_NAME = "upcpocket-todo.db"

        @Volatile
        private var instance: TodoDatabase? = null

        fun get(context: Context): TodoDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                TodoDatabase::class.java,
                DB_NAME,
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
        }

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `todo_tasks` ADD COLUMN `dueAt` INTEGER")
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `todo_tasks` ADD COLUMN `pinnedAt` INTEGER")
                database.execSQL("ALTER TABLE `todo_tasks` ADD COLUMN `sortOrder` INTEGER")
            }
        }
    }
}
