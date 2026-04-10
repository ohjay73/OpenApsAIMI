package app.aaps.plugins.aps.openAPSAIMI.advisor.oref

import android.content.Context
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.plugins.aps.openAPSAIMI.advisor.AimiProfileSnapshot
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds OREF-aligned feature rows from **local** AAPS data: [GV] + [APSResult] history.
 * No Nightscout; no Python runtime.
 */
class OrefLocalPipeline(
    private val persistenceLayer: PersistenceLayer,
) {

    suspend fun run(
        profileSnapshot: AimiProfileSnapshot,
        windowDays: Long = 30L,
        assetContext: Context? = null,
    ): OrefAnalysisReport = withContext(Dispatchers.Default) {
        val end = System.currentTimeMillis()
        val start = end - T.days(windowDays).msecs()

        val gvList = persistenceLayer.getBgReadingsDataFromTimeToTime(start, end, ascending = true)
            .filter { it.value > 20 && it.isValid }

        val apsList = persistenceLayer.getApsResults(start, end).sortedBy { it.date }

        if (gvList.size < 50 || apsList.size < 5) {
            return@withContext OrefAnalysisReport(
                windowDays = windowDays.toInt(),
                mergedRowCount = 0,
                validOutcomeRows = 0,
                timeBelow70Pct = null,
                timeAbove180Pct = null,
                timeInRange70180Pct = null,
                actualHypo4hPct = null,
                actualHyper4hPct = null,
                priority = OrefGlycemicPriority.BALANCED,
                mlStatus = OrefMlStatus.NOT_BUNDLED,
                featureMissingPct = emptyMap(),
                hints = listOf("Insufficient local data (need more CGM and APS history)."),
            )
        }

        val sortedGv = gvList.sortedBy { it.timestamp }
        val ts = LongArray(sortedGv.size) { sortedGv[it].timestamp }
        val mgdl = DoubleArray(sortedGv.size) { sortedGv[it].value }

        var lastAps: APSResult? = null
        var apsPtr = 0
        val slices = ArrayList<Triple<Int, DoubleArray, Long>>(sortedGv.size / 2)

        for (i in sortedGv.indices) {
            val gv = sortedGv[i]
            while (apsPtr < apsList.size && apsList[apsPtr].date <= gv.timestamp) {
                lastAps = apsList[apsPtr]
                apsPtr++
            }
            val aps = lastAps ?: continue
            if (gv.timestamp - aps.date > MERGE_TOLERANCE_MS) continue
            val feats = OrefFeatureBuilder.buildRow(gv, aps, profileSnapshot) ?: continue
            slices += Triple(i, feats, gv.timestamp)
        }

        if (slices.size < 50) {
            return@withContext OrefAnalysisReport(
                windowDays = windowDays.toInt(),
                mergedRowCount = slices.size,
                validOutcomeRows = 0,
                timeBelow70Pct = pctBelow(sortedGv, 70.0),
                timeAbove180Pct = pctAbove(sortedGv, 180.0),
                timeInRange70180Pct = tir(sortedGv),
                actualHypo4hPct = null,
                actualHyper4hPct = null,
                priority = classifyPriorityFromTir(pctBelow(sortedGv, 70.0), pctAbove(sortedGv, 180.0), tir(sortedGv)),
                mlStatus = OrefMlStatus.NOT_BUNDLED,
                featureMissingPct = emptyMap(),
                hints = listOf("Few APS-aligned CGM rows (${slices.size}). Sync/history retention may be limited."),
            )
        }

        var maxIob = 1.0
        val iobIdx = OrefModelFeatures.NAMES.indexOf("iob_iob")
        for ((_, f, _) in slices) {
            val v = f.getOrNull(iobIdx) ?: continue
            if (v.isFinite() && v > maxIob) maxIob = v
        }
        for ((_, f, _) in slices) {
            OrefFeatureBuilder.applyDerivedMaxIob(f, maxIob)
        }

        val missingPct = computeMissingRates(slices.map { it.second })

        val outcomePerSlice = slices.map { (gvIndex, _, _) ->
            OrefOutcomeComputer.outcomeAtIndex(ts, mgdl, gvIndex)
        }

        var hypoSum = 0.0
        var hyperSum = 0.0
        var labelled = 0
        for (o in outcomePerSlice) {
            val hLow = o.hypo4h
            val hHigh = o.hyper4h
            if (hLow != null && hHigh != null) {
                hypoSum += hLow
                hyperSum += hHigh
                labelled++
            }
        }

        val actualHypo = if (labelled > 0) hypoSum / labelled * 100.0 else null
        val actualHyper = if (labelled > 0) hyperSum / labelled * 100.0 else null

        val tbr = pctBelow(sortedGv, 70.0)
        val tar = pctAbove(sortedGv, 180.0)
        val tir = tir(sortedGv)
        val priority = classifyPriorityFromTir(tbr, tar, tir)

        val hints = buildHints(slices, profileSnapshot)

        val onnx = computeOnnxSummaries(assetContext, slices, outcomePerSlice)

        OrefAnalysisReport(
            windowDays = windowDays.toInt(),
            mergedRowCount = slices.size,
            validOutcomeRows = labelled,
            timeBelow70Pct = tbr,
            timeAbove180Pct = tar,
            timeInRange70180Pct = tir,
            actualHypo4hPct = actualHypo,
            actualHyper4hPct = actualHyper,
            priority = priority,
            mlStatus = onnx.mlStatus,
            featureMissingPct = missingPct,
            hints = hints,
            meanRawHypoRiskPct = onnx.meanRawHypoPct,
            meanCalHypoRiskPct = onnx.meanCalHypoPct,
            meanRawHyperRiskPct = onnx.meanRawHyperPct,
            meanCalHyperRiskPct = onnx.meanCalHyperPct,
            meanBgChangePred = onnx.meanBgChange,
            mlErrorDetail = onnx.mlErrorDetail,
        )
    }

    private data class OnnxSummaries(
        val mlStatus: OrefMlStatus,
        val meanRawHypoPct: Double? = null,
        val meanCalHypoPct: Double? = null,
        val meanRawHyperPct: Double? = null,
        val meanCalHyperPct: Double? = null,
        val meanBgChange: Double? = null,
        val mlErrorDetail: String? = null,
    )

    private fun computeOnnxSummaries(
        assetContext: Context?,
        slices: List<Triple<Int, DoubleArray, Long>>,
        outcomePerSlice: List<OrefOutcomeComputer.Outcome>,
    ): OnnxSummaries {
        if (assetContext == null) {
            return OnnxSummaries(OrefMlStatus.NOT_BUNDLED)
        }
        if (!OrefOnnxScorer.assetModelsPresent(assetContext)) {
            return OnnxSummaries(OrefMlStatus.NOT_BUNDLED)
        }
        val scorer = OrefOnnxScorer.tryCreate(assetContext)
            ?: return OnnxSummaries(
                OrefMlStatus.LOAD_FAILED,
                mlErrorDetail = "Could not create ONNX sessions (check model format).",
            )
        try {
            val matrix = slicesToOnnxMatrix(slices)
            val hypoRawF = predictBatched(matrix, ONNX_BATCH) { scorer.predictHypoProba(it) }
            val hyperRawF = predictBatched(matrix, ONNX_BATCH) { scorer.predictHyperProba(it) }
            val bgChF = predictBatched(matrix, ONNX_BATCH) { scorer.predictBgChange(it) }

            val hypoRaw = DoubleArray(slices.size) { hypoRawF[it].toDouble().coerceIn(0.0, 1.0) }
            val hyperRaw = DoubleArray(slices.size) { hyperRawF[it].toDouble().coerceIn(0.0, 1.0) }

            val hypoR = ArrayList<Double>()
            val hypoY = ArrayList<Double>()
            val hyperR = ArrayList<Double>()
            val hyperY = ArrayList<Double>()
            for (i in slices.indices) {
                val o = outcomePerSlice[i]
                o.hypo4h?.let { y ->
                    hypoR.add(hypoRaw[i])
                    hypoY.add(y)
                }
                o.hyper4h?.let { y ->
                    hyperR.add(hyperRaw[i])
                    hyperY.add(y)
                }
            }
            val hypoCalibrator = OrefDecileCalibrator.fitOrNull(hypoR.toDoubleArray(), hypoY.toDoubleArray())
            val hyperCalibrator = OrefDecileCalibrator.fitOrNull(hyperR.toDoubleArray(), hyperY.toDoubleArray())

            val hypoCal = DoubleArray(slices.size) { i ->
                val p = hypoRaw[i]
                hypoCalibrator?.apply(p) ?: p
            }
            val hyperCal = DoubleArray(slices.size) { i ->
                val p = hyperRaw[i]
                hyperCalibrator?.apply(p) ?: p
            }

            val meanRawHypo = hypoRaw.average() * 100.0
            val meanCalHypo = hypoCal.average() * 100.0
            val meanRawHyper = hyperRaw.average() * 100.0
            val meanCalHyper = hyperCal.average() * 100.0
            val meanBg = if (bgChF.isNotEmpty()) bgChF.average() else null

            return OnnxSummaries(
                mlStatus = OrefMlStatus.OK,
                meanRawHypoPct = meanRawHypo,
                meanCalHypoPct = meanCalHypo,
                meanRawHyperPct = meanRawHyper,
                meanCalHyperPct = meanCalHyper,
                meanBgChange = meanBg,
            )
        } catch (t: Throwable) {
            return OnnxSummaries(
                OrefMlStatus.LOAD_FAILED,
                mlErrorDetail = t.message ?: t.javaClass.simpleName,
            )
        } finally {
            scorer.close()
        }
    }

    private fun slicesToOnnxMatrix(slices: List<Triple<Int, DoubleArray, Long>>): Array<FloatArray> {
        val f = OrefModelFeatures.COUNT
        return Array(slices.size) { si ->
            val row = slices[si].second
            FloatArray(f) { j ->
                val v = row.getOrNull(j) ?: Double.NaN
                if (v.isFinite()) v.toFloat() else 0f
            }
        }
    }

    private fun predictBatched(
        matrix: Array<FloatArray>,
        batchSize: Int,
        predict: (Array<FloatArray>) -> FloatArray,
    ): FloatArray {
        val n = matrix.size
        if (n == 0) return floatArrayOf()
        val out = FloatArray(n)
        var off = 0
        while (off < n) {
            val end = min(off + batchSize, n)
            val chunk = Array(end - off) { k -> matrix[off + k] }
            val p = predict(chunk)
            p.copyInto(out, destinationOffset = off, startIndex = 0, endIndex = p.size)
            off = end
        }
        return out
    }

    private fun buildHints(
        slices: List<Triple<Int, DoubleArray, Long>>,
        profile: AimiProfileSnapshot,
    ): List<String> {
        val hints = mutableListOf<String>()
        val dynIdx = OrefModelFeatures.NAMES.indexOf("has_dynisf")
        val smbIdx = OrefModelFeatures.NAMES.indexOf("has_smb")
        if (dynIdx >= 0) {
            val meanDyn = slices.mapNotNull { it.second.getOrNull(dynIdx) }.filter { it.isFinite() }.average().let { if (it.isNaN()) 0.0 else it }
            if (meanDyn > 0.5) hints.add("Dynamic ISF appears active on most aligned loops (~${"%.0f".format(meanDyn * 100)}%).")
        }
        if (smbIdx >= 0) {
            val meanSmb = slices.mapNotNull { it.second.getOrNull(smbIdx) }.filter { it.isFinite() }.average().let { if (it.isNaN()) 0.0 else it }
            hints.add("SMB-flagged loops ~${"%.0f".format(meanSmb * 100)}% of aligned rows.")
        }
        hints.add("Profile snapshot: ISF ~${profile.isf.toInt()} mg/dL/U, CR ~${"%.1f".format(profile.icRatio)} g/U, target ~${profile.targetBg.toInt()} mg/dL.")
        return hints
    }

    private fun computeMissingRates(rows: List<DoubleArray>): Map<String, Double> {
        if (rows.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Double>()
        for (fi in OrefModelFeatures.NAMES.indices) {
            val name = OrefModelFeatures.NAMES[fi]
            var miss = 0
            for (r in rows) {
                val v = r.getOrNull(fi) ?: Double.NaN
                if (!v.isFinite()) miss++
            }
            out[name] = miss * 100.0 / rows.size
        }
        return out
    }

    private fun classifyPriorityFromTir(tbr: Double?, tar: Double?, tir: Double?): OrefGlycemicPriority {
        val tb = tbr ?: return OrefGlycemicPriority.BALANCED
        val ta = tar ?: return OrefGlycemicPriority.BALANCED
        val tr = tir ?: return OrefGlycemicPriority.BALANCED
        return when {
            tb > 5 && ta < 15 -> OrefGlycemicPriority.HYPO
            ta > 15 && tb < 5 -> OrefGlycemicPriority.HYPER
            tb > 5 && ta > 15 -> OrefGlycemicPriority.BOTH
            tb < 2 && ta < 8 && tr > 90 -> OrefGlycemicPriority.WELL_CONTROLLED
            else -> OrefGlycemicPriority.BALANCED
        }
    }

    private fun pctBelow(gv: List<app.aaps.core.data.model.GV>, thr: Double): Double {
        if (gv.isEmpty()) return 0.0
        return gv.count { it.value < thr } * 100.0 / gv.size
    }

    private fun pctAbove(gv: List<app.aaps.core.data.model.GV>, thr: Double): Double {
        if (gv.isEmpty()) return 0.0
        return gv.count { it.value > thr } * 100.0 / gv.size
    }

    private fun tir(gv: List<app.aaps.core.data.model.GV>): Double {
        if (gv.isEmpty()) return 0.0
        return gv.count { it.value in 70.0..180.0 } * 100.0 / gv.size
    }

    companion object {
        private const val MERGE_TOLERANCE_MS = 600_000L
        private const val ONNX_BATCH = 256
    }
}

private fun DoubleArray.getOrNull(i: Int): Double? = if (i in indices) this[i] else null
