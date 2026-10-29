package com.upcpocket.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 本地数据库。
 *
 * 轻量版只保存用户自己的数据：番茄钟的标签与专注记录。
 *
 * 课表功能已整体移除，但 [SemesterEntity] 等五张旧表**仍声明在 entities 里**：
 * 它们与专注表同库，直接删表会改变 Room 的 schema 校验值，让已安装用户的数据库
 * 在升级后无法打开。这些表没有 DAO、没有任何读写路径，只用于保持 version = 1
 * 的结构不变 —— 详见 `LegacyScheduleTables.kt` 顶部说明。
 *
 * ⚠️ 包名是 `com.upcpocket.app`，与上游郑州大学版本是不同应用标识，两者数据不互通。
 */
@Database(
    entities = [
        // 停用的课表旧表（仅保结构，不读写）。顺序与旧版本严格一致。
        SemesterEntity::class,
        CourseEntity::class,
        CourseSessionEntity::class,
        PeriodEntity::class,
        // 仍在使用的本机数据
        FocusTagEntity::class,
        FocusSessionEntity::class,
        DisplayOverrideEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun focusTagDao(): FocusTagDao
    abstract fun focusSessionDao(): FocusSessionDao

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
