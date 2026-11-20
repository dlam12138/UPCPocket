package com.upcpocket.app.core

import com.upcpocket.app.campusmap.CampusId
import com.upcpocket.app.data.classroom.FreeClassroomCampus
import com.upcpocket.app.data.classroom.FreeClassroomPreferredCampus
import com.upcpocket.app.data.library.LibraryCampus
import com.upcpocket.app.data.library.LibraryCampusPreference
import com.upcpocket.app.data.library.LibraryCampusPreferenceProvider
import com.upcpocket.app.data.power.PowerCampus
import com.upcpocket.app.data.power.PowerCampusPreference
import com.upcpocket.app.data.power.PowerCampusPreferenceProvider
import com.upcpocket.app.data.prefs.PreferredCampus
import com.upcpocket.app.data.prefs.UiPrefsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 宿主对「常用校区」的唯一映射点。
 *
 * feature 只拿到各自的窄接口和枚举，不认识 `UiPrefsStore` 或全局偏好；页面内的
 * 临时切换也不会写回这里。映射只改变展示范围，不改变任何学校请求参数或登录范围。
 *
 * DataStore 读取完成前这些 Flow 不会发射；各页面自行区分“未读取”和“全部校区”。
 */
internal fun UiPrefsStore.classroomPreferredCampus(): FreeClassroomPreferredCampus =
    FreeClassroomPreferredCampus {
        when (preferredCampusFlow().first()) {
            PreferredCampus.ALL -> null
            PreferredCampus.TANGDAOWAN -> FreeClassroomCampus.TANGDAOWAN
            PreferredCampus.GUZHENKOU -> FreeClassroomCampus.GUZHENKOU
        }
    }

internal fun UiPrefsStore.libraryCampusPreference(): LibraryCampusPreferenceProvider =
    LibraryCampusPreferenceProvider {
        preferredCampusFlow().map { preferred ->
            when (preferred) {
                PreferredCampus.ALL -> LibraryCampusPreference.All
                PreferredCampus.TANGDAOWAN ->
                    LibraryCampusPreference.Selected(LibraryCampus.TANGDAOWAN)
                PreferredCampus.GUZHENKOU ->
                    LibraryCampusPreference.Selected(LibraryCampus.GUZHENKOU)
            }
        }
    }

internal fun UiPrefsStore.powerCampusPreference(): PowerCampusPreferenceProvider =
    PowerCampusPreferenceProvider {
        preferredCampusFlow().map { preferred ->
            when (preferred) {
                PreferredCampus.ALL -> PowerCampusPreference.All
                PreferredCampus.TANGDAOWAN ->
                    PowerCampusPreference.Selected(PowerCampus.TANGDAOWAN)
                PreferredCampus.GUZHENKOU ->
                    PowerCampusPreference.Selected(PowerCampus.GUZHENKOU)
            }
        }
    }

/** 只读常用校区；只在 DataStore 真实读取后发射。 */
internal fun UiPrefsStore.preferredCampusFlow() =
    flow.map { it.preferredCampus }

internal suspend fun UiPrefsStore.initialCampusMapCampus(): CampusId =
    when (preferredCampusFlow().first()) {
        PreferredCampus.GUZHENKOU -> CampusId.GZK
        PreferredCampus.ALL, PreferredCampus.TANGDAOWAN -> CampusId.TDW
    }
