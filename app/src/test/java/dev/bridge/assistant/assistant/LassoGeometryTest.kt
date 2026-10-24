package dev.bridge.assistant.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class LassoGeometryTest {
    @Test fun mapsCenterLetterboxedBitmapToSourceCoordinates() {
        val selection = LassoGeometry.resolve(
            viewWidth = 100, viewHeight = 100, bitmapWidth = 200, bitmapHeight = 100,
            points = listOf(LassoPoint(25f, 30f), LassoPoint(75f, 30f), LassoPoint(75f, 70f), LassoPoint(25f, 70f))
        )
        assertNotNull(selection)
        assertEquals(LassoRect(50, 10, 150, 90), selection!!.crop)
    }

    @Test fun clampsCropAtBitmapEdges() {
        val selection = LassoGeometry.resolve(
            viewWidth = 200, viewHeight = 100, bitmapWidth = 200, bitmapHeight = 100,
            points = listOf(LassoPoint(0f, 0f), LassoPoint(40f, 0f), LassoPoint(40f, 40f), LassoPoint(0f, 40f))
        )
        assertNotNull(selection)
        assertEquals(LassoRect(0, 0, 40, 40), selection!!.crop)
    }

    @Test fun rejectsEmptyRepeatedAndTinySelections() {
        assertNull(LassoGeometry.resolve(100, 100, 200, 100, emptyList()))
        assertNull(LassoGeometry.resolve(100, 100, 200, 100, listOf(LassoPoint(20f, 20f), LassoPoint(20f, 20f), LassoPoint(20f, 20f))))
        assertNull(LassoGeometry.resolve(100, 100, 200, 100, listOf(LassoPoint(40f, 40f), LassoPoint(45f, 40f), LassoPoint(45f, 45f), LassoPoint(40f, 45f))))
        assertNull(LassoGeometry.resolve(0, 100, 200, 100, listOf(LassoPoint(0f, 0f), LassoPoint(40f, 0f), LassoPoint(40f, 40f))))
    }
}
