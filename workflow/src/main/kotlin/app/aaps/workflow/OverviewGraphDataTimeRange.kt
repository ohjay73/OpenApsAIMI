package app.aaps.workflow

import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.overview.OverviewData
import kotlin.math.min

/**
 * Earliest X for graph series so horizontal pan can move before the visible window while staying
 * within the same DB load window as [PrepareBgDataWorker] (see [Constants.GRAPH_TIME_RANGE_HOURS]).
 */
internal fun overviewGraphDataFromTime(overviewData: OverviewData): Long =
    min(overviewData.fromTime, overviewData.toTime - T.hours(Constants.GRAPH_TIME_RANGE_HOURS.toLong()).msecs())
