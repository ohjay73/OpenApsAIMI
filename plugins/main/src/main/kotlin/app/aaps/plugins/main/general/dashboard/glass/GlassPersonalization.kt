package app.aaps.plugins.main.general.dashboard.glass

import app.aaps.core.keys.StringNonKey
import app.aaps.plugins.main.R

/** One selectable compact pill for the Glass main screen's optional bottom row. */
enum class GlassPillId { IOB, TARGET, BASAL_RATE, LAST_BOLUS, LAST_CARBS }

data class GlassPillCatalogEntry(
    val id: GlassPillId,
    val labelRes: Int,
)

/**
 * Fixed, code-defined catalog of pills the user can pick from in the Personalize screen.
 * Order here is the order pills render in on the main screen row.
 */
val GLASS_PILL_CATALOG: List<GlassPillCatalogEntry> = listOf(
    GlassPillCatalogEntry(GlassPillId.IOB, R.string.dashboard_glass_iob_label),
    GlassPillCatalogEntry(GlassPillId.TARGET, R.string.dashboard_glass_target_label),
    GlassPillCatalogEntry(GlassPillId.BASAL_RATE, R.string.dashboard_glass_basal_t_label),
    GlassPillCatalogEntry(GlassPillId.LAST_BOLUS, R.string.dashboard_glass_last_bolus_label),
    GlassPillCatalogEntry(GlassPillId.LAST_CARBS, R.string.dashboard_glass_last_carbs_label),
)

/** Parses the comma-separated [StringNonKey.GlassSelectedPills] preference value into catalog IDs, dropping unknowns. */
fun parseSelectedGlassPills(rawValue: String): List<GlassPillId> =
    rawValue.split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { name -> runCatching { GlassPillId.valueOf(name) }.getOrNull() }

/** Serializes a selection back to the comma-separated preference format. */
fun serializeSelectedGlassPills(ids: Set<GlassPillId>): String =
    ids.joinToString(",") { it.name }
