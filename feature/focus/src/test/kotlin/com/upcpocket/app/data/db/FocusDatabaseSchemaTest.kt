package com.upcpocket.app.data.db

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 迁移前后 Room v1 的结构校验。
 *
 * `62e4b0998501030df70b77a392a012f5` 是 73ac700 基线（数据库仍在 app 模块时）
 * 由 Room KSP 导出的 identityHash。数据库类、实体包名、表、字段、索引和 version
 * 都没变，因此迁移到 feature 模块后必须得到同一个值。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class FocusDatabaseSchemaTest {

    @Test
    fun `database identity and legacy tables match pre-migration v1`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val identity = db.openHelper.readableDatabase.query(
                "SELECT identity_hash FROM room_master_table",
            ).use { cursor ->
                cursor.moveToFirst()
                cursor.getString(0)
            }
            assertEquals("62e4b0998501030df70b77a392a012f5", identity)

            val tables = db.openHelper.readableDatabase.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' " +
                    "AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata'",
            ).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
            assertEquals(
                setOf(
                    "semesters",
                    "courses",
                    "course_sessions",
                    "display_overrides",
                    "periods",
                    "focus_tags",
                    "focus_sessions",
                    "room_master_table",
                ),
                tables,
            )
        } finally {
            db.close()
        }
    }
}
