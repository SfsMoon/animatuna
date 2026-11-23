package io.github.sfsmoon.animatuna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import io.github.sfsmoon.animatuna.tuning.SpringResource
import io.github.sfsmoon.animatuna.tuning.Tuning

class TuningTest {

    @Test
    fun stockTuningChangesNothing() {
        val stock = Tuning(springHome = false)
        assertEquals(1f, stock.openDurationFactor)
        assertEquals(1f, stock.closeDurationFactor)
        assertEquals(1f, stock.homeSpringStiffnessFactor)
        assertNull(stock.homeSpringDampingRatio)
        SpringResource.entries.forEach { assertEquals(450f, it.tune(450f, stock)) }
    }

    @Test
    fun everySliderOffersPixelLaunchersOwn() {
        assertTrue(Tuning.STOCK_PERCENT in Tuning.DURATION_PERCENTS)
        assertTrue(Tuning.STOCK_PERCENT in Tuning.STIFFNESS_PERCENTS)
        assertEquals(Tuning.STOCK_DAMPING, Tuning.DAMPINGS.first())
    }

    @Test
    fun springResourcesAreKnownByName() {
        assertEquals(SpringResource.STIFFNESS, SpringResource.of("swipe_up_rect_x_stiffness"))
        assertEquals(SpringResource.STIFFNESS, SpringResource.of("swipe_up_rect_scale_stiffness_v2"))
        assertEquals(SpringResource.DAMPING, SpringResource.of("swipe_up_rect_y_damping_ratio"))
        assertEquals(SpringResource.OTHER, SpringResource.of("swipe_up_max_velocity_fall_off_factor"))
        assertEquals(SpringResource.OTHER, SpringResource.of("closing_window_trans_y"))
    }

    @Test
    fun springHomeTakesColorOsSpringsAndTheSlidersStillAdjustThem() {
        val colorOs = Tuning()
        assertEquals(220f, SpringResource.STIFFNESS.tune(450f, colorOs), 1e-3f)
        assertEquals(0.85f, SpringResource.DAMPING.tune(0.965f, colorOs), 1e-6f)
        val adjusted = Tuning(homeSpringStiffnessPercent = 150, homeSpringDamping = 100)
        assertEquals(330f, SpringResource.STIFFNESS.tune(450f, adjusted), 1e-3f)
        assertEquals(1f, SpringResource.DAMPING.tune(0.965f, adjusted), 1e-6f)
    }

    @Test
    fun chosenSpringReplacesPixelLaunchersOwn() {
        val tuning = Tuning(springHome = false, homeSpringStiffnessPercent = 200, homeSpringDamping = 60)
        assertEquals(900f, SpringResource.STIFFNESS.tune(450f, tuning))
        assertEquals(0.6f, SpringResource.DAMPING.tune(0.965f, tuning), 1e-6f)
    }
}
