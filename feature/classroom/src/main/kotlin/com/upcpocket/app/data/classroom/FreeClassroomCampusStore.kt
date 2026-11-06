package com.upcpocket.app.data.classroom

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * 只记住上次选择的校区。
 *
 * 日期和节次不持久化：每次真正进入空闲教室页都重新初始化为上海时区今天 + 上午，
 * 避免把上次的日期或时段误当成新的查询条件。
 */
interface FreeClassroomCampusStore {
    suspend fun read(): FreeClassroomCampus?

    suspend fun write(campus: FreeClassroomCampus)
}

/** 未接入持久层时的空实现，供独立预览与测试使用。 */
object NoOpFreeClassroomCampusStore : FreeClassroomCampusStore {
    override suspend fun read(): FreeClassroomCampus? = null

    override suspend fun write(campus: FreeClassroomCampus) = Unit
}

private val Context.classroomCampusDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "classroom_prefs")

/**
 * 校区偏好只落在本模块私有的 DataStore，键名与文件不暴露给宿主或其它插件。
 *
 * 只保存枚举名；读取到未知值时按“没有保存过”处理，仍使用页面默认校区。
 */
class DataStoreFreeClassroomCampusStore internal constructor(
    private val store: DataStore<Preferences>,
) : FreeClassroomCampusStore {

    constructor(context: Context) : this(context.applicationContext.classroomCampusDataStore)

    override suspend fun read(): FreeClassroomCampus? {
        val raw = store.data.first()[KEY_LAST_CAMPUS] ?: return null
        return FreeClassroomCampus.entries.firstOrNull { it.name == raw }
    }

    override suspend fun write(campus: FreeClassroomCampus) {
        store.edit { prefs -> prefs[KEY_LAST_CAMPUS] = campus.name }
    }

    private companion object {
        val KEY_LAST_CAMPUS = stringPreferencesKey("last_campus")
    }
}
