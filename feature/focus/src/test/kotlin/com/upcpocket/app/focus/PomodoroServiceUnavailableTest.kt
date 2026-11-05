package com.upcpocket.app.focus

import android.app.Service
import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 系统可能因旧通知/旧任务而创建 PomodoroService；此时 focus 插件可能已被停用
 * 或未注册。服务必须明确拒绝启动，而不是借缺失能力强制初始化数据库。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class PomodoroServiceUnavailableTest {

    @Before
    fun clearBridge() {
        FocusRuntime.clearForTest()
    }

    @After
    fun clearBridgeAfter() {
        FocusRuntime.clearForTest()
    }

    @Test
    fun `legacy service entry stops without an active focus plugin`() {
        val controller = Robolectric.buildService(PomodoroService::class.java)
        val service = controller.create().get()

        val result = service.onStartCommand(
            Intent(service, PomodoroService::class.java).setAction(PomodoroService.ACTION_SYNC),
            0,
            1,
        )

        assertEquals(Service.START_NOT_STICKY, result)
        controller.destroy()
    }
}
