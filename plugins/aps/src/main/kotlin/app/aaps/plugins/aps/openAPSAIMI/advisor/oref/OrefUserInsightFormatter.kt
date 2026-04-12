package app.aaps.plugins.aps.openAPSAIMI.advisor.oref

import android.content.Context
import app.aaps.plugins.aps.R

/**
 * Plain-language paragraphs for the Advisor UI (English strings in [R.string]).
 */
object OrefUserInsightFormatter {

    fun buildParagraph(context: Context, o: OrefAnalysisReport): String {
        val lines = mutableListOf<String>()
        when (o.dataSufficiency) {
            OrefDataSufficiency.INSUFFICIENT ->
                lines += context.getString(R.string.aimi_adv_oref_user_data_insufficient)
            OrefDataSufficiency.LIMITED ->
                lines += context.getString(R.string.aimi_adv_oref_user_data_limited)
            OrefDataSufficiency.GOOD ->
                lines += context.getString(R.string.aimi_adv_oref_user_data_good)
        }
        lines += context.getString(R.string.aimi_adv_oref_user_priority, o.priority.name)
        when (o.personalMlStatus) {
            OrefPersonalMlStatus.OFF ->
                lines += context.getString(R.string.aimi_adv_oref_user_personal_off)
            OrefPersonalMlStatus.INSUFFICIENT_DATA ->
                lines += context.getString(R.string.aimi_adv_oref_user_personal_training)
            OrefPersonalMlStatus.TRAIN_FAILED ->
                lines += context.getString(R.string.aimi_adv_oref_user_personal_failed)
            OrefPersonalMlStatus.TRAINED_AND_USED -> {
                val a = o.personalMeanHypoSignalPct?.toInt() ?: 0
                val b = o.personalMeanHyperSignalPct?.toInt() ?: 0
                lines += context.getString(R.string.aimi_adv_oref_user_personal_ok, a, b)
            }
        }
        return lines.joinToString("\n\n")
    }
}
