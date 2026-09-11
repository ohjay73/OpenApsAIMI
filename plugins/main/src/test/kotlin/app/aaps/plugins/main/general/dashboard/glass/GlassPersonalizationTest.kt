package app.aaps.plugins.main.general.dashboard.glass

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class GlassPersonalizationTest {

    @Test
    fun `serialize then parse round-trips a selection`() {
        val selection = setOf(GlassPillId.IOB, GlassPillId.LAST_CARBS)
        val serialized = serializeSelectedGlassPills(selection)
        assertThat(parseSelectedGlassPills(serialized).toSet()).isEqualTo(selection)
    }

    @Test
    fun `parse drops unknown or malformed entries without crashing`() {
        val result = parseSelectedGlassPills("IOB,NOT_A_REAL_PILL,,TARGET")
        assertThat(result).containsExactly(GlassPillId.IOB, GlassPillId.TARGET)
    }

    @Test
    fun `parse of empty string returns an empty list`() {
        assertThat(parseSelectedGlassPills("")).isEmpty()
    }
}
