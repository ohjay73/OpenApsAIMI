package app.aaps.plugins.aps.openAPSAIMI.advisor.meal

/**
 * Builds the user text sent to vision LLMs and hardens free-form context so it cannot
 * break the surrounding request JSON or inject instruction-like delimiters.
 */
object MealVisionUserPrompt {

    private const val MAX_USER_CONTEXT_CHARS = 600

    fun sanitizeUserContextForPrompt(raw: String): String {
        val stripped = MealAdvisorResponseSanitizer.sanitizeModelText(raw, MAX_USER_CONTEXT_CHARS)
        return stripped
            .replace('"', '\'')
            .replace('\\', '/')
            .trim()
    }

    fun buildAnalysisUserPrompt(userDescription: String): String {
        val trimmed = userDescription.trim()
        if (trimmed.isEmpty()) {
            return "Analyze this meal image and return JSON only according to the required schema."
        }
        val safe = sanitizeUserContextForPrompt(trimmed)
        return "User description: \"$safe\". Analyze this meal image and return JSON only according to the required schema."
    }
}
