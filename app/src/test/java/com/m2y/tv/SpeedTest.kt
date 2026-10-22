package com.m2y.tv

import com.m2y.tv.play.nextSpeed
import com.m2y.tv.play.speedLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedTest {
    @Test
    fun speedsCycleFromNormalAndWrap() {
        var s = 1f
        val seen = (1..5).map { nextSpeed(s).also { s = it } }
        assertEquals(listOf(1.25f, 1.5f, 0.5f, 0.75f, 1f), seen)
        assertEquals(1f, nextSpeed(3f)) // unknown value falls back to normal
    }

    @Test
    fun labelsUseTurkishDecimalComma() {
        assertEquals(listOf("0,5x", "0,75x", "1x", "1,25x", "1,5x"), listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f).map(::speedLabel))
    }
}
