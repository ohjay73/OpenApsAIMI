package app.aaps.plugins.aps.openAPSAIMI.advisor.oref

/**
 * Local OREF-style analysis (features + outcomes). Optional bundled ONNX models add risk scores on-device.
 */
data class OrefAnalysisReport(
    val windowDays: Int,
    val mergedRowCount: Int,
    val validOutcomeRows: Int,
    /** % time BG < 70 on merged CGM timeline */
    val timeBelow70Pct: Double?,
    /** % time BG > 180 */
    val timeAbove180Pct: Double?,
    /** % time 70–180 */
    val timeInRange70180Pct: Double?,
    /** Fraction of 4h windows with any BG < 70 (labelled rows only) */
    val actualHypo4hPct: Double?,
    /** Fraction of 4h windows with any BG > 180 */
    val actualHyper4hPct: Double?,
    val priority: OrefGlycemicPriority,
    val mlStatus: OrefMlStatus,
    /** Feature name → % missing (0–100) on merged rows */
    val featureMissingPct: Map<String, Double>,
    val hints: List<String>,
    /** Mean raw hypo model output (0–100% probability scale) over aligned rows, if ONNX ran */
    val meanRawHypoRiskPct: Double? = null,
    /** Mean decile-calibrated hypo risk (0–100%), if calibration fit */
    val meanCalHypoRiskPct: Double? = null,
    val meanRawHyperRiskPct: Double? = null,
    val meanCalHyperRiskPct: Double? = null,
    /** Mean predicted BG change (model units, typically mg/dL / horizon used in training) */
    val meanBgChangePred: Double? = null,
    /** Extra detail for [OrefMlStatus.LOAD_FAILED] */
    val mlErrorDetail: String? = null,
) {

    fun toPromptSection(): String = buildString {
        append("--- OREF-STYLE LOCAL ANALYSIS (on-device, no Nightscout) ---\n")
        append("Window: ${windowDays}d | APS-CGM aligned rows: $mergedRowCount | Valid4h outcomes: $validOutcomeRows\n")
        timeInRange70180Pct?.let { append("CGM TIR 70-180 (merged timeline): ${"%.1f".format(it)}%\n") }
        timeBelow70Pct?.let { append("CGM TBR <70: ${"%.1f".format(it)}%\n") }
        timeAbove180Pct?.let { append("CGM TAR >180: ${"%.1f".format(it)}%\n") }
        actualHypo4hPct?.let { append("4h hypo exposure (labelled windows): ${"%.1f".format(it)}%\n") }
        actualHyper4hPct?.let { append("4h hyper exposure (labelled windows): ${"%.1f".format(it)}%\n") }
        append("Priority heuristic: ${priority.name}\n")
        append("ML: ${mlStatus.userMessage}\n")
        mlErrorDetail?.let { append("ML detail: $it\n") }
        if (meanRawHypoRiskPct != null || meanCalHypoRiskPct != null) {
            meanRawHypoRiskPct?.let { append("ONNX hypo risk (raw mean): ${"%.1f".format(it)}%\n") }
            meanCalHypoRiskPct?.let { append("ONNX hypo risk (calibrated mean): ${"%.1f".format(it)}%\n") }
        }
        if (meanRawHyperRiskPct != null || meanCalHyperRiskPct != null) {
            meanRawHyperRiskPct?.let { append("ONNX hyper risk (raw mean): ${"%.1f".format(it)}%\n") }
            meanCalHyperRiskPct?.let { append("ONNX hyper risk (calibrated mean): ${"%.1f".format(it)}%\n") }
        }
        meanBgChangePred?.let { append("ONNX mean predicted BG change: ${"%.2f".format(it)}\n") }
        if (hints.isNotEmpty()) {
            append("Hints:\n")
            hints.forEach { append("- $it\n") }
        }
        val worstMissing = featureMissingPct.entries.filter { it.value > 30.0 }.sortedByDescending { it.value }.take(5)
        if (worstMissing.isNotEmpty()) {
            append("Sparse features (>30% missing): ")
            append(worstMissing.joinToString { "${it.key}=${"%.0f".format(it.value)}%" })
            append("\n")
        }
        if (mlStatus == OrefMlStatus.NOT_BUNDLED) {
            append("NOTE: Add hypo_lgbm.onnx, hyper_lgbm.onnx, bg_change_lgbm.onnx under assets/oref/ for local risk scores.\n")
        }
    }
}

enum class OrefGlycemicPriority {
    HYPO,
    HYPER,
    BOTH,
    BALANCED,
    WELL_CONTROLLED,
}

enum class OrefMlStatus(val userMessage: String) {
    NOT_BUNDLED("Risk scores: ONNX models not in assets (optional)."),
    LOAD_FAILED("Risk scores: ONNX load/inference failed."),
    OK("Risk scores: on-device ONNX OK."),
}
