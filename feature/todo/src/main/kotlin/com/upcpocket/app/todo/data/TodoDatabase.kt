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
    entities = [
        TodoTaskEntity::class,
        TodoTaskOrderEntity::class,
        TodoOrderStateEntity::class,
        PersonalScheduleEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class TodoDatabase : RoomDatabase() {

    abstract fun taskDao(): TodoTaskDao

    abstract fun orderDao(): TodoOrderDao

    abstract fun personalScheduleDao(): PersonalScheduleDao

    companion object {
        const val DB_NAME = "upcpocket-todo.db"

        @Volatile
        private var instance: TodoDatabase? = null

        fun get(context: Context): TodoDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                TodoDatabase::class.java,
                DB_NAME,
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
                .also { instance = it }
        }

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `todo_tasks` ADD COLUMN `dueAt` INTEGER")
            }
        }

        /**
         * 已有任务保持原字段不变；排序记录按 taskId 按需创建，缺省为普通/无位次。
         * 单行设置写入日期模式与未初始化的下一手动位次。
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `todo_task_order` (
                        `taskId` TEXT NOT NULL,
                        `importance` TEXT NOT NULL,
                        `manualRank` INTEGER,
                        PRIMARY KEY(`taskId`)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `todo_order_state` (
                        `id` INTEGER NOT NULL,
                        `mode` TEXT NOT NULL,
                        `nextManualRank` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "INSERT OR IGNORE INTO `todo_order_state` (`id`, `mode`, `nextManualRank`) " +
                        "VALUES (0, 'DATE', 0)",
                )
            }
        }

        /**
         * 只新增个人日程表与窗口索引；既有任务、截止、排序记录和 revision 原样保留。
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `personal_schedules` (
                        `id` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `startAt` INTEGER NOT NULL,
                        `endAt` INTEGER NOT NULL,
                        `location` TEXT,
                        `note` TEXT,
                        `revision` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_personal_schedules_startAt` " +
                        "ON `personal_schedules` (`startAt`)",
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_personal_schedules_endAt` " +
                        "ON `personal_schedules` (`endAt`)",
                )
            }
        }
    }
}
