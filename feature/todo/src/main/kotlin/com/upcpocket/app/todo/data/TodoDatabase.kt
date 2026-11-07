package com.upcpocket.app.todo.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 本机待办数据库与学校账号数据分离；换号清理不会删除这个文件。
 */
@Database(
    entities = [TodoTaskEntity::class],
    version = 1,
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
            ).build().also { instance = it }
        }
    }
}
