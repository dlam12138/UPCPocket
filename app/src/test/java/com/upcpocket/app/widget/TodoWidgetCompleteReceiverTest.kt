package com.upcpocket.app.widget

import android.app.Application
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class TodoWidgetCompleteReceiverTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val receiver = TodoWidgetCompleteReceiver()
    private val taskId = "d2719e0c-54b0-4eab-9836-5e35e0281234"

    @Test
    fun `checked completion binds widget task revision and target in private intent`() {
        val intent = WidgetIntents.todoCompletionIntent(context, 19, taskId, 8)
            .putExtra(RemoteViews.EXTRA_CHECKED, true)

        assertEquals(context.packageName, intent.component?.packageName)
        assertEquals(TodoWidgetCompleteReceiver::class.java.name, intent.component?.className)
        assertEquals(context.packageName, intent.`package`)
        assertTrue(WidgetIntents.isTodoCompletionAction(intent.action))
        assertEquals(
            TodoWidgetCompleteReceiver.CompletionRequest(19, taskId, 8),
            receiver.parseRequest(context, intent),
        )
    }

    @Test
    fun `false or malformed completion response is rejected`() {
        val base = WidgetIntents.todoCompletionIntent(context, 19, taskId, 8)
        assertNull(receiver.parseRequest(context, base))
        assertNull(
            receiver.parseRequest(
                context,
                WidgetIntents.todoCompletionIntent(context, 19, taskId, 8)
                    .setData(android.net.Uri.parse("upcpocket://todo-widget/complete/19/d2719e0c-54b0-4eab-9836-5e35e028123/8/true"))
                    .putExtra(RemoteViews.EXTRA_CHECKED, true),
            ),
        )
        assertNull(
            receiver.parseRequest(
                context,
                WidgetIntents.todoCompletionIntent(context, 19, taskId, 8)
                    .setData(android.net.Uri.parse("upcpocket://todo-widget/complete/19/$taskId/8/false"))
                    .putExtra(RemoteViews.EXTRA_CHECKED, true),
            ),
        )
    }
}
