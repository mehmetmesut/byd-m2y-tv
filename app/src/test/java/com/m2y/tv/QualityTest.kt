package com.m2y.tv

import com.m2y.tv.play.MAX_HEIGHT
import com.m2y.tv.play.qualityChoices
import com.m2y.tv.play.qualityLabel
import com.m2y.tv.play.videoCap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityTest {
    @Test
    fun automaticAndOutOfRangeCapsStayAtTheScreenHeight() {
        assertEquals(MAX_HEIGHT, videoCap(0))
        assertEquals(MAX_HEIGHT, videoCap(2160))
        assertEquals(720, videoCap(720))
        assertEquals(480, videoCap(480))
    }

    @Test
    fun choicesAreDistinctHeightsUpTo1080HighestFirst() {
        // 4K variant dropped, duplicate bitrates of one height collapse, unknown (-1/0) heights ignored
        assertEquals(listOf(1080, 720, 360), qualityChoices(listOf(360, 2160, 720, 1080, 720, -1, 0)))
        // a single height (or only too-high variants) means nothing to choose
        assertTrue(qualityChoices(listOf(720, 720)).size < 2)
        assertTrue(qualityChoices(listOf(2160)).isEmpty())
    }

    @Test
    fun labels() {
        assertEquals("Otomatik", qualityLabel(0))
        assertEquals("720p", qualityLabel(720))
    }
}
