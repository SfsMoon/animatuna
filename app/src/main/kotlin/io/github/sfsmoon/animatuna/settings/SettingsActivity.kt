package io.github.sfsmoon.animatuna.settings

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

import io.github.sfsmoon.animatuna.R
import io.github.sfsmoon.animatuna.tuning.Tuning
import io.github.sfsmoon.animatuna.tuning.TuningStore

/**
 * The module's one screen: what Pixel Launcher's window animations do. Choices
 * are stored through the Xposed framework, where the launcher reads them at
 * its next animation; without the framework the screen says so and stores nothing.
 */
class SettingsActivity : Activity() {

    private lateinit var column: LinearLayout
    private var store: TuningStore? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (PADDING_DP * resources.displayMetrics.density).toInt()
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(column)
        })
        note(getString(R.string.waiting_for_framework))
        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                runOnUiThread { show(TuningStore(service.getRemotePreferences(TuningStore.GROUP))) }
            }

            override fun onServiceDied(service: XposedService) {
                runOnUiThread {
                    store = null
                    column.removeAllViews()
                    note(getString(R.string.framework_gone))
                }
            }
        })
    }

    private fun show(store: TuningStore) {
        this.store = store
        column.removeAllViews()
        val tuning = store.read()
        slider(R.string.open_duration, Tuning.DURATION_PERCENTS, tuning.openDurationPercent, ::percent) { t, v -> t.copy(openDurationPercent = v) }
        slider(R.string.close_duration, Tuning.DURATION_PERCENTS, tuning.closeDurationPercent, ::percent) { t, v -> t.copy(closeDurationPercent = v) }
        slider(R.string.home_spring_stiffness, Tuning.STIFFNESS_PERCENTS, tuning.homeSpringStiffnessPercent, ::percent) { t, v -> t.copy(homeSpringStiffnessPercent = v) }
        slider(R.string.home_spring_damping, Tuning.DAMPINGS, tuning.homeSpringDamping, ::damping) { t, v -> t.copy(homeSpringDamping = v) }
        note(getString(R.string.restart_hint))
    }

    private fun slider(label: Int, steps: List<Int>, current: Int, describe: (Int) -> String, apply: (Tuning, Int) -> Tuning) {
        val title = TextView(this).apply { setPadding(0, gap(), 0, 0) }
        fun caption(value: Int) {
            title.text = getString(R.string.slider_caption, getString(label), describe(value))
        }
        val start = steps.indexOf(current).takeIf { it >= 0 } ?: steps.indexOf(Tuning.STOCK_PERCENT).coerceAtLeast(0)
        caption(steps[start])
        column.addView(title)
        column.addView(SeekBar(this).apply {
            max = steps.lastIndex
            progress = start
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, step: Int, fromUser: Boolean) {
                    caption(steps[step])
                    if (fromUser) change { apply(it, steps[step]) }
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        })
    }

    private fun change(edit: (Tuning) -> Tuning) {
        val store = store ?: return
        store.write(edit(store.read()))
    }

    private fun percent(value: Int): String = getString(R.string.percent, value)

    private fun damping(value: Int): String =
        if (value == Tuning.STOCK_DAMPING) getString(R.string.stock) else getString(R.string.ratio, value / 100f)

    private fun note(text: String) {
        column.addView(TextView(this).apply {
            this.text = text
            alpha = NOTE_ALPHA
            gravity = Gravity.START
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        })
    }

    private fun gap(): Int = (GAP_DP * resources.displayMetrics.density).toInt()

    private companion object {
        const val PADDING_DP = 20
        const val GAP_DP = 20
        const val NOTE_ALPHA = 0.7f
    }
}
