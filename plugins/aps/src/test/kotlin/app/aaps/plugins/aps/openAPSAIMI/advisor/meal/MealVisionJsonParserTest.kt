package app.aaps.plugins.aps.openAPSAIMI.advisor.meal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MealVisionJsonParserTest {

    @Test
    fun `parseModelContentToEstimation unwraps markdown fence`() {
        val raw = """
            ```json
            {"food_name":"Soup","visible_items":[],"uncertain_items":[],"carbs_g":{"estimate":20,"min":18,"max":25},"protein_g":{"estimate":5,"min":4,"max":6},"fat_g":{"estimate":8,"min":6,"max":10},"absorption_speed":"MIXED","glycemic_index":"MEDIUM","confidence":"HIGH","portion_confidence":"HIGH","hidden_carb_risk":"LOW","needs_manual_confirmation":false,"insulin_relevant_notes":[],"rationale":"ok"}
            ```
        """.trimIndent()
        val r = MealVisionJsonParser.parseModelContentToEstimation(raw)
        assertEquals("Soup", r.description)
        assertEquals(20.0, r.carbs.estimate, 0.01)
    }

    @Test
    fun `parseModelContentToEstimation returns secured error on garbage`() {
        val r = MealVisionJsonParser.parseModelContentToEstimation("not json at all {{{")
        assertEquals("Parse Error", r.description)
        assertTrue(r.needsManualConfirmation)
    }

    @Test
    fun `buildAnalysisUserPrompt escapes quotes in user context`() {
        val p = MealVisionUserPrompt.buildAnalysisUserPrompt("""He said "large" portion""")
        assertTrue(!p.contains("\"large\""))
        assertTrue(p.contains("'large'"))
    }
}
