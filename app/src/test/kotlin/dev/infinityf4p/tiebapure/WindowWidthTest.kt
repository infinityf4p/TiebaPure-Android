package dev.infinityf4p.tiebapure

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowWidthTest {
    @Test
    fun compactMediumAndExpandedBoundariesAreStable() {
        assertEquals(TiebaPureWindowWidth.Compact, windowWidthFor(599.dp))
        assertEquals(TiebaPureWindowWidth.Medium, windowWidthFor(600.dp))
        assertEquals(TiebaPureWindowWidth.Medium, windowWidthFor(839.dp))
        assertEquals(TiebaPureWindowWidth.Expanded, windowWidthFor(840.dp))
        assertEquals(TiebaPureWindowWidth.Medium, windowWidthFor(840.dp - 32.dp))
    }

    @Test
    fun expandedPanesMeetBothMinimumsAtTheBreakpoint() {
        val panes = expandedPaneWidthsFor(840.dp)

        assertEquals(320.dp, panes.list)
        assertTrue(panes.detail >= 440.dp)
        assertEquals(
            840.dp - ExpandedNavigationRailWidth - ExpandedPaneDividerWidth * 2,
            panes.list + panes.detail,
        )
    }

    @Test
    fun expandedListUsesFortyPercentUntilItReachesItsMaximum() {
        val regularWidth = 1_200.dp
        val regularAvailable =
            regularWidth - ExpandedNavigationRailWidth - ExpandedPaneDividerWidth * 2
        assertEquals(regularAvailable * 0.4f, expandedPaneWidthsFor(regularWidth).list)

        val widePanes = expandedPaneWidthsFor(2_000.dp)
        assertEquals(560.dp, widePanes.list)
        assertTrue(widePanes.detail >= 440.dp)
    }
}
