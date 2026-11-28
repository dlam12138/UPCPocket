package com.upcpocket.app.floorplan

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 视口只保存图面坐标中心、缩放和已处理的定位代次；旋转或画布尺寸变化后按当前
 * fitScale 重新换算平移并约束。这里覆盖换算边界和保存恢复，不做设备矩阵测试。
 */
class FloorPlanViewportTest {

    @Test
    fun `a size change keeps the image centered while the whole drawing fits`() {
        val center = clampViewportCenter(
            center = Offset(10f, 10f),
            totalScale = 0.5f,
            viewportWidth = 600f,
            viewportHeight = 400f,
            imageWidth = 650f,
            imageHeight = 420f,
        )

        assertEquals(325f, center.x, 0.001f)
        assertEquals(210f, center.y, 0.001f)
    }

    @Test
    fun `zoomed image edges bound the remembered center but keep an inner center`() {
        val totalScale = 2f

        assertEquals(
            Offset(150f, 100f),
            clampViewportCenter(Offset(0f, 0f), totalScale, 600f, 400f, 650f, 420f),
        )
        assertEquals(
            Offset(500f, 320f),
            clampViewportCenter(Offset(999f, 999f), totalScale, 600f, 400f, 650f, 420f),
        )
        assertEquals(
            Offset(300f, 200f),
            clampViewportCenter(Offset(300f, 200f), totalScale, 600f, 400f, 650f, 420f),
        )
    }

    @Test
    fun `a temporary narrow viewport only affects the drawn center`() {
        val saved = Offset(200f, 150f)

        // 横屏或键盘下某一轴被夹到图心，只用于绘制；恢复原尺寸后仍从保存的图面锚点换算。
        val temporary = clampViewportCenter(saved, 2f, 1400f, 100f, 650f, 420f)
        assertEquals(325f, temporary.x, 0.001f)
        assertEquals(150f, temporary.y, 0.001f)

        assertEquals(
            saved,
            clampViewportCenter(saved, 2f, 600f, 400f, 650f, 420f),
        )
    }

    @Test
    fun `saved state restores zoom image center and handled focus revision`() {
        val state = FloorPlanViewportState(
            zoom = 2.4f,
            centerX = 123.5f,
            centerY = 456.5f,
            handledFocusRevision = 7,
        )
        val scope = object : SaverScope {
            override fun canBeSaved(value: Any): Boolean = true
        }
        val saver = floorPlanViewportStateSaver("sheet-v2:a-3:650x420")
        val saved = with(saver) {
            scope.save(state)
        }
        assertNotNull(saved)

        val restored = requireNotNull(saver.restore(requireNotNull(saved)))
        assertEquals(state.zoom, restored.zoom, 0f)
        assertEquals(state.centerX, restored.centerX, 0f)
        assertEquals(state.centerY, restored.centerY, 0f)
        assertEquals(state.handledFocusRevision, restored.handledFocusRevision)
    }

    @Test
    fun `restoration only reuses a viewport saved for the same drawing`() {
        val scope = object : SaverScope {
            override fun canBeSaved(value: Any): Boolean = true
        }
        val state = FloorPlanViewportState(
            zoom = 2.4f,
            centerX = 123.5f,
            centerY = 456.5f,
            handledFocusRevision = 7,
        )
        val singleSheetSaver = floorPlanViewportStateSaver("sheet-v2:a-3:650x420")
        val sharedPageSaver = floorPlanViewportStateSaver("page-5:650x420")
        val singleSheetSave = with(singleSheetSaver) { scope.save(state) }
        val sharedPageSave = with(sharedPageSaver) { scope.save(state) }

        assertNull(sharedPageSaver.restore(requireNotNull(singleSheetSave)))
        assertNull(sharedPageSaver.restore(listOf(2.4f, 123.5f, 456.5f, 7)))
        assertNotNull(sharedPageSaver.restore(requireNotNull(sharedPageSave)))
    }
}
