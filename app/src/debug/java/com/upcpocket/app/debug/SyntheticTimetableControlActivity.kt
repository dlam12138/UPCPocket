package com.upcpocket.app.debug

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle

/**
 * DEBUG-ONLY control for the synthetic Launcher fixture.
 *
 * `--ei scenario 0..3` updates every synthetic widget; `--ez pin true` asks the
 * Launcher to pin a new 2×2 instance. It never reads or writes real account data.
 */
class SyntheticTimetableControlActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apply(intent, finishWhenDone = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        apply(intent, finishWhenDone = true)
    }

    private fun apply(intent: Intent, finishWhenDone: Boolean) {
        val scenario = intent.getIntExtra(EXTRA_SCENARIO, 0).coerceIn(0, 3)
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putInt(KEY_SCENARIO, scenario)
            .apply()
        SyntheticTimetableWidgetProvider.updateAll(
            this,
            SyntheticTimetableWidgetProvider::class.java,
        )
        SyntheticTimetableWidgetProvider.updateAll(
            this,
            SyntheticTimetableWideWidgetProvider::class.java,
        )

        if (intent.getBooleanExtra(EXTRA_PIN, false)) {
            val manager = AppWidgetManager.getInstance(this)
            val provider = if (intent.getBooleanExtra(EXTRA_WIDE, false)) {
                SyntheticTimetableWideWidgetProvider::class.java
            } else {
                SyntheticTimetableWidgetProvider::class.java
            }
            manager.requestPinAppWidget(ComponentName(this, provider), null, null)
        }
        if (finishWhenDone && !intent.getBooleanExtra(EXTRA_PIN, false)) {
            finish()
        }
    }

    companion object {
        private const val PREFS = "synthetic_timetable_widget"
        private const val KEY_SCENARIO = "scenario"
        private const val EXTRA_SCENARIO = "scenario"
        private const val EXTRA_PIN = "pin"
        private const val EXTRA_WIDE = "wide"
    }
}
