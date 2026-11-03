package com.upcpocket.app.classroom

import com.upcpocket.app.plugin.runtime.PluginRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ClassroomPluginTest {

    @Test
    fun `plugin declares and provides the free classroom repository`() {
        val plugin = ClassroomPlugin()

        val result = PluginRuntime()
            .register(plugin.descriptor) { plugin }
            .start()

        val active = result.plugins
        assertEquals("classroom", plugin.descriptor.id.value)
        assertEquals(setOf("classroom.free"), plugin.descriptor.capabilities)
        assertNotNull(active.capability(ClassroomCapabilities.free))
    }
}
