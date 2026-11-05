package com.upcpocket.app.debug

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import com.upcpocket.app.account.api.AccountGeneration
import com.upcpocket.app.account.api.SchoolAccountId
import com.upcpocket.app.data.ecard.CardBalanceBinding
import com.upcpocket.app.data.ecard.CardBindingAvailability
import com.upcpocket.app.data.ecard.CardBindingStatus
import com.upcpocket.app.data.ecard.DirectCardSnapshot
import com.upcpocket.app.data.power.PowerSnapshot
import com.upcpocket.app.timetable.TimetableWidgetCourse
import com.upcpocket.app.timetable.TimetableWidgetLayout
import com.upcpocket.app.timetable.TimetableWidgetRenderer
import com.upcpocket.app.timetable.TimetableWidgetSnapshot
import com.upcpocket.app.timetable.TimetableWidgetStatus
import com.upcpocket.app.widget.CardWidgetRenderer
import com.upcpocket.app.widget.PowerWidgetRenderer
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * DEBUG-ONLY exact-size fixture for the three real RemoteViews renderers.
 *
 * It never reads account state, the timetable service, or any cache. It exists so
 * 160/172/180/234 × 360 dp, 1.0/1.5 font and every real state can be measured and
 * screenshotted without a school login. Release does not contain this Activity.
 *
 * adb:
 *   am start -n com.upcpocket.app/.debug.WidgetPreviewActivity \
 *     --ei kind 0 --ei width 172 --ei height 234 --ef font 1.0 --ez dark false --ei scenario 0
 */
class WidgetPreviewActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val kind = intent.getIntExtra("kind", 0)
        val widthDp = intent.getIntExtra("width", 172)
        val heightDp = intent.getIntExtra("height", 234)
        val fontScale = intent.getFloatExtra("font", 1f)
        val dark = intent.getBooleanExtra("dark", false)
        val scenario = intent.getIntExtra("scenario", 0)

        val config = Configuration(resources.configuration).apply {
            this.fontScale = fontScale
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val context = createConfigurationContext(config)
        val density = context.resources.displayMetrics.density
        val widthPx = (widthDp * density).roundToInt()
        val heightPx = (heightDp * density).roundToInt()

        val root = FrameLayout(this).apply {
            setBackgroundColor(if (dark) Color.rgb(18, 19, 22) else Color.rgb(214, 219, 228))
        }
        val host = FrameLayout(this)
        host.layoutParams = FrameLayout.LayoutParams(widthPx, heightPx).apply {
            leftMargin = 24
            topMargin = 24
        }
        val views = when (kind) {
            1 -> cardViews(scenario, context)
            2 -> powerViews(scenario, context)
            else -> timetableViews(scenario, widthDp, heightDp, fontScale, context)
        }
        val widget = views.apply(context, host)
        host.addView(
            widget,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(host)

        val label = TextView(this).apply {
            text = "kind=$kind ${widthDp}×${heightDp}dp font=$fontScale dark=$dark scenario=$scenario"
            setTextColor(if (dark) Color.WHITE else Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.START
        }
        root.addView(
            label,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = 24
                topMargin = heightPx + 40
            },
        )
        setContentView(root)

        host.post {
            widget.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
            )
            widget.layout(0, 0, widthPx, heightPx)
            dump(widget, widthDp, heightDp, fontScale, dark, scenario)
        }
    }

    private fun timetableViews(
        scenario: Int,
        widthDp: Int,
        heightDp: Int,
        fontScale: Float,
        context: android.content.Context,
    ): RemoteViews {
        val zone = ZoneId.of("Asia/Shanghai")
        val today = LocalDate.now(zone)
        val now = LocalTime.now(zone).let { it.hour * 60 + it.minute }
        val normal = listOf(
            course("化工原理与传递过程综合实验（合成）", "卓越教学楼 B205 西侧联合实验区", now - 10, now + 50, 2),
            course("数据处理与实验设计方法研讨（合成）", "古镇口校区图书馆三层研讨空间", now + 60, now + 110, 5),
        )
        val long = listOf(
            course(
                "化工原理与传递过程综合实验（合成）超长课程名称用于两行压力测试",
                "卓越教学楼 B205 西侧联合实验区靠近东门的三层阶梯教室",
                now - 10,
                now + 50,
                2,
            ),
            course(
                "数据处理与实验设计方法研讨（合成）",
                "古镇口校区图书馆三层研讨空间",
                now + 60,
                now + 110,
                5,
            ),
        )
        val snapshot = when (scenario) {
            2 -> TimetableWidgetSnapshot(TimetableWidgetStatus.OK, today, emptyList())
            3 -> TimetableWidgetSnapshot(TimetableWidgetStatus.NEEDS_SYNC, today)
            4 -> TimetableWidgetSnapshot(TimetableWidgetStatus.NO_ACCOUNT, null)
            5 -> TimetableWidgetSnapshot(TimetableWidgetStatus.ACCOUNT_LOADING, null)
            6 -> TimetableWidgetSnapshot(TimetableWidgetStatus.CLEANUP, null)
            7 -> TimetableWidgetSnapshot(
                TimetableWidgetStatus.OK,
                today,
                listOf(course("中国近现代史纲要（合成）", "文理楼 C102", 0, 1, 11)),
                System.currentTimeMillis(),
            )
            8 -> TimetableWidgetSnapshot(TimetableWidgetStatus.OUTSIDE_TERM, today)
            else -> TimetableWidgetSnapshot(
                TimetableWidgetStatus.OK,
                today,
                if (scenario == 1) long else normal,
                System.currentTimeMillis(),
            )
        }
        return TimetableWidgetRenderer.render(
            context = context,
            snapshot = snapshot,
            layout = TimetableWidgetLayout.forSize(widthDp, heightDp, fontScale),
            refreshing = scenario == 9,
            failed = scenario == 10,
            openIntent = pending(context, 1),
            refreshIntent = pending(context, 2),
        )
    }

    private fun cardViews(scenario: Int, context: android.content.Context): RemoteViews {
        val binding = CardBalanceBinding(
            accountId = SchoolAccountId("acct_preview"),
            studentId = "2099000123",
            credentialGeneration = AccountGeneration(2),
        )
        val status = when (scenario) {
            3 -> CardBindingStatus(CardBindingAvailability.NO_ACCOUNT)
            4 -> CardBindingStatus(CardBindingAvailability.CLEANING)
            5 -> CardBindingStatus(CardBindingAvailability.LOADING)
            else -> CardBindingStatus(CardBindingAvailability.READY, binding)
        }
        val snapshot = when (scenario) {
            2, 3, 4, 5 -> null
            1 -> DirectCardSnapshot(
                accountId = binding.accountId.value,
                studentId = binding.studentId,
                credentialGeneration = 2,
                balanceCents = 1234567,
                transitionCents = 0,
                fetchedAt = System.currentTimeMillis(),
            )
            else -> DirectCardSnapshot(
                accountId = binding.accountId.value,
                studentId = binding.studentId,
                credentialGeneration = 2,
                balanceCents = 699,
                transitionCents = 0,
                fetchedAt = System.currentTimeMillis(),
            )
        }
        return CardWidgetRenderer(context).views(
            status = status,
            snapshot = snapshot,
            refreshing = scenario == 9,
            failed = scenario == 10,
            openApp = { pending(context, 1) },
            refresh = { pending(context, 2) },
        )
    }

    private fun powerViews(scenario: Int, context: android.content.Context): RemoteViews {
        val snapshot = when (scenario) {
            2 -> null
            1 -> PowerSnapshot(
                "唐岛湾校区 01号楼(1)",
                "1101",
                "12345.67",
                System.currentTimeMillis(),
            )
            else -> PowerSnapshot(
                "01号楼(1)",
                "1101",
                "17.34",
                System.currentTimeMillis(),
            )
        }
        return PowerWidgetRenderer(context).views(
            snapshot = snapshot,
            refreshing = scenario == 9,
            failed = scenario == 10,
            openApp = { pending(context, 1) },
            refresh = { pending(context, 2) },
        )
    }

    private fun course(
        name: String,
        location: String,
        start: Int,
        end: Int,
        slot: Int,
    ): TimetableWidgetCourse = TimetableWidgetCourse(
        name = name,
        location = location,
        timeLabel = clock(start) + "–" + clock(end),
        startMinute = start,
        endMinute = end,
        periodLabel = "第1–2节",
        colorSlot = slot,
    )

    private fun clock(minute: Int): String =
        "%02d:%02d".format((minute / 60) % 24, minute % 60)

    private fun pending(context: android.content.Context, code: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            code,
            Intent(context, WidgetPreviewActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun dump(
        widget: View,
        widthDp: Int,
        heightDp: Int,
        fontScale: Float,
        dark: Boolean,
        scenario: Int,
    ) {
        val report = StringBuilder(
            "size=${widthDp}x${heightDp} font=$fontScale dark=$dark scenario=$scenario root=${widget.width}x${widget.height}",
        )
        fun walk(view: View) {
            if (view is TextView) {
                val layout = view.layout ?: return
                val ellipsis = (0 until layout.lineCount).sumOf { layout.getEllipsisCount(it) }
                val name = runCatching {
                    resources.getResourceEntryName(view.id)
                }.getOrDefault("id=${view.id}")
                val text = view.text.toString().replace("\n", "\\n")
                report.append(" | ").append(name)
                    .append(" lines=").append(layout.lineCount)
                    .append(" ellipsis=").append(ellipsis)
                    .append(" layoutH=").append(layout.height)
                    .append(" box=").append(view.left).append(",").append(view.top)
                    .append(",").append(view.right).append(",").append(view.bottom)
                    .append(" clipped=").append(view.bottom > widget.height || view.top < 0)
                    .append(" vclip=").append(view.height < layout.height)
                    .append(" text=").append(text)
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) walk(view.getChildAt(index))
            }
        }
        walk(widget)
        Log.i("WidgetPreview", report.toString())
    }
}
