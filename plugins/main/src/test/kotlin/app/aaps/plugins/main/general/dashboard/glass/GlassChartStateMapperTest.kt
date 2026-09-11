package app.aaps.plugins.main.general.dashboard.glass

import app.aaps.core.interfaces.overview.graph.BgDataPoint
import app.aaps.core.interfaces.overview.graph.BgRange
import app.aaps.core.interfaces.overview.graph.BgType
import app.aaps.core.interfaces.overview.graph.BolusGraphPoint
import app.aaps.core.interfaces.overview.graph.BolusType
import app.aaps.core.interfaces.overview.graph.CarbsGraphPoint
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.ui.compose.overview.graphs.ChartConfig
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Regression tests for [buildGlassChartState] — the mapper from raw
 * `app.aaps.ui.compose.overview.graphs.GraphViewModel` flow data into the Glass skin's
 * [GlassChartState]. Covers the window filter, the progress mapping at the window edges, the
 * unit-space passthrough of `chartConfig.lowMark`/`.highMark` (must NOT be run through
 * `mgdlToChartY` a second time), and treatment merging.
 */
class GlassChartStateMapperTest {

    private val identity: (Double) -> Double = { it }

    private fun bg(timestamp: Long, value: Double) =
        BgDataPoint(timestamp = timestamp, value = value, range = BgRange.IN_RANGE, type = BgType.BUCKETED)

    @Test
    fun `reading before the window start is excluded`() {
        val nowEpochMs = 100_000_000L
        val rangeHours = 6
        val windowStart = nowEpochMs - rangeHours * 3_600_000L

        val result = buildGlassChartState(
            rangeHours = rangeHours,
            nowEpochMs = nowEpochMs,
            bgReadings = listOf(bg(windowStart - 1L, 120.0)),
            iobPoints = emptyList(),
            boluses = emptyList(),
            carbs = emptyList(),
            chartConfig = ChartConfig(highMark = 180.0, lowMark = 70.0),
            mgdlToChartY = identity,
            pumpStatusText = "",
            predictions = emptyList(),
        )

        assertThat(result.bgReadings).isEmpty()
    }

    @Test
    fun `reading at nowEpochMs maps to progress 1 and reading at windowStart maps to progress 0`() {
        val nowEpochMs = 100_000_000L
        val rangeHours = 6
        val windowStart = nowEpochMs - rangeHours * 3_600_000L

        val result = buildGlassChartState(
            rangeHours = rangeHours,
            nowEpochMs = nowEpochMs,
            bgReadings = listOf(bg(windowStart, 90.0), bg(nowEpochMs, 150.0)),
            iobPoints = emptyList(),
            boluses = emptyList(),
            carbs = emptyList(),
            chartConfig = ChartConfig(highMark = 180.0, lowMark = 70.0),
            mgdlToChartY = identity,
            pumpStatusText = "",
            predictions = emptyList(),
        )

        assertThat(result.bgReadings).hasSize(2)
        assertThat(result.bgReadings[0].progress).isEqualTo(0f)
        assertThat(result.bgReadings[1].progress).isEqualTo(1f)
    }

    @Test
    fun `lowLine and highLine pass through chartConfig unconverted, not run through mgdlToChartY again`() {
        val nowEpochMs = 100_000_000L
        // A conversion that would be obviously wrong if applied to lowMark/highMark a second time.
        val suspiciousConverter: (Double) -> Double = { it / 18.0 }

        val result = buildGlassChartState(
            rangeHours = 6,
            nowEpochMs = nowEpochMs,
            bgReadings = emptyList(),
            iobPoints = emptyList(),
            boluses = emptyList(),
            carbs = emptyList(),
            chartConfig = ChartConfig(highMark = 180.0, lowMark = 70.0),
            mgdlToChartY = suspiciousConverter,
            pumpStatusText = "",
            predictions = emptyList(),
        )

        assertThat(result.lowLine).isEqualTo(70f)
        assertThat(result.highLine).isEqualTo(180f)
    }

    @Test
    fun `bg reading value is run through mgdlToChartY, not passed through as raw mg-dL`() {
        val nowEpochMs = 100_000_000L
        val rangeHours = 6
        // mmol/L-style converter: distinct from identity so a dropped conversion would fail this test.
        val mgdlToMmol: (Double) -> Double = { it / 18.0 }

        val result = buildGlassChartState(
            rangeHours = rangeHours,
            nowEpochMs = nowEpochMs,
            bgReadings = listOf(bg(nowEpochMs, 180.0)),
            iobPoints = emptyList(),
            boluses = emptyList(),
            carbs = emptyList(),
            chartConfig = ChartConfig(highMark = 180.0, lowMark = 70.0),
            mgdlToChartY = mgdlToMmol,
            pumpStatusText = "",
            predictions = emptyList(),
        )

        assertThat(result.bgReadings).hasSize(1)
        assertThat(result.bgReadings[0].value).isEqualTo(10f)
        assertThat(result.currentBgValue).isEqualTo(10f)
    }

    @Test
    fun `a bolus and a carb in the same window both appear in treatments with correct isCarb`() {
        val nowEpochMs = 100_000_000L
        val rangeHours = 6
        val windowStart = nowEpochMs - rangeHours * 3_600_000L
        val bolusTime = windowStart + 1_000_000L
        val carbTime = windowStart + 2_000_000L

        val result = buildGlassChartState(
            rangeHours = rangeHours,
            nowEpochMs = nowEpochMs,
            bgReadings = emptyList(),
            iobPoints = emptyList(),
            boluses = listOf(
                BolusGraphPoint(timestamp = bolusTime, amount = 1.5, bolusType = BolusType.NORMAL, isValid = true, label = "1.50 U"),
            ),
            carbs = listOf(
                CarbsGraphPoint(timestamp = carbTime, amount = 45.0, isValid = true, label = "45 g"),
            ),
            chartConfig = ChartConfig(highMark = 180.0, lowMark = 70.0),
            mgdlToChartY = identity,
            pumpStatusText = "",
            predictions = emptyList(),
        )

        assertThat(result.treatments).hasSize(2)
        val bolusPoint = result.treatments.first { it.label == "1.50 U" }
        val carbPoint = result.treatments.first { it.label == "45 g" }
        assertThat(bolusPoint.isCarb).isFalse()
        assertThat(carbPoint.isCarb).isTrue()
    }

    @Test
    fun `empty input lists produce an empty GlassChartState without throwing`() {
        val result = buildGlassChartState(
            rangeHours = 6,
            nowEpochMs = 100_000_000L,
            bgReadings = emptyList(),
            iobPoints = emptyList(),
            boluses = emptyList(),
            carbs = emptyList(),
            chartConfig = ChartConfig(highMark = 180.0, lowMark = 70.0),
            mgdlToChartY = identity,
            pumpStatusText = "",
            predictions = emptyList(),
        )

        assertThat(result.bgReadings).isEmpty()
        assertThat(result.iobReadings).isEmpty()
        assertThat(result.treatments).isEmpty()
        assertThat(result.currentBgValue).isEqualTo(0f)
        assertThat(result.currentIob).isEqualTo(0f)
    }

    @Test
    fun `axisMinValue and axisHeadroom are converted through mgdlToChartY, not left as raw mg-dL constants`() {
        val mgdlToMmol: (Double) -> Double = { it / 18.0 }

        val result = buildGlassChartState(
            rangeHours = 6,
            nowEpochMs = 100_000_000L,
            bgReadings = emptyList(),
            iobPoints = emptyList(),
            boluses = emptyList(),
            carbs = emptyList(),
            chartConfig = ChartConfig(highMark = 180.0, lowMark = 70.0),
            mgdlToChartY = mgdlToMmol,
            pumpStatusText = "",
            predictions = emptyList(),
        )

        assertThat(result.axisMinValue).isWithin(0.01f).of((30.0 / 18.0).toFloat())
        assertThat(result.axisHeadroom).isWithin(0.01f).of((40.0 / 18.0).toFloat())
    }

    @Test
    fun `iob points outside the window are excluded, matching the bg reading filter`() {
        val nowEpochMs = 100_000_000L
        val rangeHours = 6
        val windowStart = nowEpochMs - rangeHours * 3_600_000L

        val result = buildGlassChartState(
            rangeHours = rangeHours,
            nowEpochMs = nowEpochMs,
            bgReadings = emptyList(),
            iobPoints = listOf(
                GraphDataPoint(timestamp = windowStart - 1L, value = 3.0),
                GraphDataPoint(timestamp = windowStart, value = 2.0),
            ),
            boluses = emptyList(),
            carbs = emptyList(),
            chartConfig = ChartConfig(highMark = 180.0, lowMark = 70.0),
            mgdlToChartY = identity,
            pumpStatusText = "",
            predictions = emptyList(),
        )

        assertThat(result.iobReadings).hasSize(1)
        assertThat(result.currentIob).isEqualTo(2f)
    }

    @Test
    fun `predictions outside the 2-hour horizon are excluded, and progress is 0 at now, 1 at horizon end`() {
        val nowEpochMs = 100_000_000L
        val rangeHours = 6

        val result = buildGlassChartState(
            rangeHours = rangeHours,
            nowEpochMs = nowEpochMs,
            bgReadings = emptyList(),
            iobPoints = emptyList(),
            boluses = emptyList(),
            carbs = emptyList(),
            chartConfig = ChartConfig(highMark = 180.0, lowMark = 70.0),
            mgdlToChartY = identity,
            pumpStatusText = "",
            predictions = listOf(
                BgDataPoint(timestamp = nowEpochMs, value = 120.0, range = BgRange.IN_RANGE, type = BgType.IOB_PREDICTION),
                BgDataPoint(timestamp = nowEpochMs + 3_600_000L, value = 100.0, range = BgRange.IN_RANGE, type = BgType.IOB_PREDICTION),
                BgDataPoint(timestamp = nowEpochMs + 2 * 3_600_000L, value = 90.0, range = BgRange.IN_RANGE, type = BgType.IOB_PREDICTION),
                BgDataPoint(timestamp = nowEpochMs + 3 * 3_600_000L, value = 80.0, range = BgRange.IN_RANGE, type = BgType.IOB_PREDICTION),
            ),
        )

        assertThat(result.predictions).hasSize(3)
        assertThat(result.predictions[0].progress).isEqualTo(0f)
        assertThat(result.predictions[2].progress).isEqualTo(1f)
        assertThat(result.historyFraction).isWithin(0.001f).of(6f / 8f)
    }

    @Test
    fun `unrecognized BgType predictions are silently dropped, not crashed on`() {
        val nowEpochMs = 100_000_000L

        val result = buildGlassChartState(
            rangeHours = 6,
            nowEpochMs = nowEpochMs,
            bgReadings = emptyList(),
            iobPoints = emptyList(),
            boluses = emptyList(),
            carbs = emptyList(),
            chartConfig = ChartConfig(highMark = 180.0, lowMark = 70.0),
            mgdlToChartY = identity,
            pumpStatusText = "",
            predictions = listOf(
                BgDataPoint(timestamp = nowEpochMs, value = 120.0, range = BgRange.IN_RANGE, type = BgType.REGULAR),
                BgDataPoint(timestamp = nowEpochMs, value = 120.0, range = BgRange.IN_RANGE, type = BgType.BUCKETED),
            ),
        )

        assertThat(result.predictions).isEmpty()
    }
}
