package app.aaps.plugins.main.general.overview.graphData

import app.aaps.core.interfaces.overview.OverviewData
import com.jjoe64.graphview.GraphView
import kotlin.math.abs

private const val LIVE_EDGE_TOLERANCE_MS = 120_000.0

/**
 * True when the viewport’s right edge still tracks [OverviewData.endTime] (“live”) so [GraphData.formatAxis]
 * may reset X; false after the user panned/zoomed away from the live edge.
 */
fun viewportShouldFollowLiveRange(graph: GraphView, overviewData: OverviewData): Boolean {
    val vp = graph.viewport
    if (!vp.isXAxisBoundsManual) return true
    val vpMax = vp.getMaxX(false)
    if (vpMax < 1_000_000_000_000.0) return true
    val liveEnd = overviewData.endTime.toDouble()
    return abs(vpMax - liveEnd) < LIVE_EDGE_TOLERANCE_MS
}
