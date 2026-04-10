package app.aaps.plugins.aps.openAPSAIMI.advisor.oref

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OrefReasonParserTest {

    @Test
    fun parse_extracts_minGuardBG_mgdl() {
        val r = OrefReasonParser.parse("foo minGuardBG: 95 bar")
        assertThat(r.minGuardBG).isEqualTo(95.0)
    }

    @Test
    fun parseCrFromReason() {
        assertThat(OrefReasonParser.parseCrFromReason("CR: 12")).isEqualTo(12.0)
    }
}
