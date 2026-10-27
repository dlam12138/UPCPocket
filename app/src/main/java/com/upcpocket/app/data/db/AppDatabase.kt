package com.upcpocket.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 本地数据库。
 *
 * UPC Pocket 的 M1 原型**只保存用户自己的数据**：本地学期、自定义日程、节次作息、
 * 番茄钟记录与课表显示覆盖。没有校园账号、没有令牌、没有电费或校园卡流水，
 * 也没有任何"同步状态"表 —— 本应用不存在需要同步的远端。
 *
 * ⚠️ 包名已从 `com.zzupocket.app` 改为 `com.upcpocket.app`，它是全新的应用标识：
 * 郑州大学原版的数据库不会、也不应该迁移过来（两者数据语义不同）。所以这里从
 * version = 1 重新开始，不保留原项目的 v1–v10 迁移链。
 */
@Database(
    entities = [
        SemesterEntity::class,
        CourseEntity::class,
        CourseSessionEntity::class,
        PeriodEntity::class,
        FocusTagEntity::class,
        FocusSessionEntity::class,
        DisplayOverrideEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun semesterDao(): SemesterDao
    abstract fun courseDao(): CourseDao
    abstract fun courseSessionDao(): CourseSessionDao
    abstract fun periodDao(): PeriodDao
    abstract fun focusTagDao(): FocusTagDao
    abstract fun focusSessionDao(): FocusSessionDao
    abstract fun displayOverrideDao(): DisplayOverrideDao

    companion object {
        private const val DB_NAME = "upcpocket.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DB_NAME,
            ).build().also { instance = it }
        }
    }
}
