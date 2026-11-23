package io.github.sfsmoon.animatuna

import org.junit.Assert.assertEquals
import org.junit.Test

import io.github.sfsmoon.animatuna.tuning.HomeFlight
import io.github.sfsmoon.animatuna.tuning.RectSpring

class HomeFlightTest {

    private val from = RectSpring.Frame(100f, 300f, 1100f, 2500f, 40f)
    private val icon = RectSpring.Frame(68f, 2382f, 325f, 2639f, 60f)
    private val flight = HomeFlight(from)

    @Test
    fun theSpringsAloneDrawTheWindowEarlyInTheLaunchersFlight() {
        val springs = RectSpring.Frame(150f, 700f, 900f, 2300f, 45f)
        assertEquals(springs, flight.shown(springs, icon, HomeFlight.PULL_FROM))
    }

    @Test
    fun theWindowIsOnItsIconWhenTheLaunchersFlightEnds() {
        val springs = RectSpring.Frame(150f, 700f, 900f, 2300f, 45f)
        assertEquals(icon, flight.shown(springs, icon, 1f))
    }

    @Test
    fun progressFollowsTheWindowsOwnWidth() {
        assertEquals(0f, flight.progress(from, icon), 1e-4f)
        assertEquals(1f, flight.progress(icon, icon), 1e-4f)
        val halfway = RectSpring.Frame(0f, 0f, (from.width + icon.width) / 2f, 10f, 0f)
        assertEquals(0.5f, flight.progress(halfway, icon), 1e-4f)
    }

    @Test
    fun theWindowFadesByItsOwnProgress() {
        assertEquals(1f, HomeFlight.windowAlpha(0f, 0.9f), 1e-4f)
        assertEquals(0.5f, HomeFlight.windowAlpha(0.45f, 0.9f), 1e-4f)
        assertEquals(0f, HomeFlight.windowAlpha(0.95f, 0.9f), 1e-4f)
    }
}
