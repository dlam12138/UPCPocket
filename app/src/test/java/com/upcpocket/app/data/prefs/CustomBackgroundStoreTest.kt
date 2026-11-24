package com.upcpocket.app.data.prefs

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CustomBackgroundStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun targetSizeAndSampleSizePreserveAspectRatioAndBoundMaxEdge() {
        assertEquals(Pair(1920, 1080), CustomBackgroundStore.computeTargetSize(3840, 2160, 1920))
        assertEquals(Pair(960, 1920), CustomBackgroundStore.computeTargetSize(2000, 4000, 1920))
        assertEquals(Pair(800, 600), CustomBackgroundStore.computeTargetSize(800, 600, 1920))
        assertEquals(Pair(0, 0), CustomBackgroundStore.computeTargetSize(0, 100, 1920))

        assertEquals(2, CustomBackgroundStore.computeSampleSize(4000, 3000, 1920))
        assertEquals(1, CustomBackgroundStore.computeSampleSize(1200, 800, 1920))
    }

    @Test
    fun importSaveLoadExtractAndClearLifecycle() = runBlocking {
        val dir = temp.newFolder("appearance")
        val store = CustomBackgroundStore(
            directory = dir,
            contentResolver = null,
            ioDispatcher = Dispatchers.Unconfined,
        )

        assertFalse(store.exists())
        assertNull(store.loadBitmap())
        assertEquals(0L, store.revision.value)

        val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(26, 115, 232))
        }
        val result = store.importFromBitmap(source)
        source.recycle()

        assertTrue("Expected success result", result is CustomBackgroundImportResult.Success)
        assertTrue(store.exists())
        assertNotNull(store.loadBitmap())
        assertTrue(store.extractSeedsFromSaved().isNotEmpty())
        assertEquals(1L, store.revision.value)

        // 普通换图：新图提交成功后才替换旧图，完成后不残留临时文件。
        val replacement = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(46, 125, 50))
        }
        val replaced = store.importFromBitmap(replacement)
        replacement.recycle()

        assertTrue("Expected replacement success", replaced is CustomBackgroundImportResult.Success)
        assertTrue(store.exists())
        assertNotNull(store.loadBitmap())
        assertEquals(2L, store.revision.value)
        assertTrue(
            "换图完成后不应残留临时文件",
            dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") },
        )

        assertTrue(store.clear())
        assertFalse(store.exists())
        assertNull(store.loadBitmap())
        assertEquals(3L, store.revision.value)
    }
}
