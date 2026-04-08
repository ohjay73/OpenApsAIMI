package app.aaps.core.graph.data

import kotlin.math.max
import kotlin.math.min

/**
 * In-range (target) band using the same fill rendering as [AreaGraphSeries], but clamps each
 * draw pass to the **visible viewport** in X.
 *
 * GraphView's [AreaGraphSeries] connects two points at the full chart `[fromTime…endTime]`. When
 * the viewport lies strictly inside that interval, the segment still spans both corners off-screen,
 * clipping expands it to the **full plot width** — the band looks “stuck” while scrolling (seen on
 * the dashboard glucose graph). Here, [getValues] only returns the intersection with the viewport
 * so the shading moves with the time axis.
 */
class ViewportClampedInRangeAreaSeries(
    private val rangeStartMs: Double,
    private val rangeEndMs: Double,
    private val lowLine: Double,
    private val highLine: Double,
) : AreaGraphSeries<DoubleDataPoint>(
    arrayOf(
        DoubleDataPoint(rangeStartMs, lowLine, highLine),
        DoubleDataPoint(rangeEndMs, lowLine, highLine),
    )
) {

    override fun getValues(from: Double, until: Double): Iterator<DoubleDataPoint> {
        val xStart = max(rangeStartMs, from)
        val xEnd = min(rangeEndMs, until)
        if (xStart >= xEnd) {
            return emptyList<DoubleDataPoint>().iterator()
        }
        return listOf(
            DoubleDataPoint(xStart, lowLine, highLine),
            DoubleDataPoint(xEnd, lowLine, highLine),
        ).iterator()
    }
}
