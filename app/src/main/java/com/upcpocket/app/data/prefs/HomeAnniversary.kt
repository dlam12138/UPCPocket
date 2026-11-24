package com.upcpocket.app.data.prefs

import java.time.LocalDate

/** 首页纪念日的计时方向；首页与设置页共用同一组枚举名。 */
enum class HomeAnniversaryMode {
    /** 从起始日起累计：起始日前倒数开始日，起始日显示第 1 天。 */
    COUNT_UP,

    /** 到目标日结束：目标日前倒数剩余天数，目标日显示今天。 */
    COUNT_DOWN;

    companion object {
        /** 缺键或未知值回退 [COUNT_DOWN]，不因历史脏数据阻止首页渲染。 */
        fun fromStoredValue(value: String?): HomeAnniversaryMode =
            entries.firstOrNull { it.name == value } ?: COUNT_DOWN
    }
}

/**
 * 本机首页纪念日偏好。
 *
 * [date] 是公历日历日期，不携带时区；首页按上海日期与它比较。存储层只写
 * [date] 的 epoch day。关闭 [visible] 只隐藏首页一行，标题、日期与模式继续保留。
 */
data class HomeAnniversarySettings(
    val title: String = "",
    val date: LocalDate? = null,
    val mode: HomeAnniversaryMode = HomeAnniversaryMode.COUNT_DOWN,
    val visible: Boolean = false,
)
