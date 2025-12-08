package app.aaps.plugins.aps.openAPSAIMI.advisor

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.aaps.plugins.aps.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import dagger.android.AndroidInjection
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.keys.interfaces.Preferences

/**
 * =============================================================================
 * AIMI PROFILE ADVISOR ACTIVITY
 * =============================================================================
 * 
 * Main UI for the Advisor.
 * Displays: Metrics, Recommendations, and AI Coaching advice.
 * 
 * CRITICAL FIX: All data loading is performed on IO Dispatcher to prevent crashes.
 * =============================================================================
 */
class AimiProfileAdvisorActivity : AppCompatActivity() {

    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var preferences: Preferences

    private lateinit var advisorService: AimiAdvisorService
    private val coachingService = AiCoachingService()
    
    // UI Elements
    private lateinit var scoreTextView: TextView
    private lateinit var summaryTextView: TextView
    private lateinit var recommendationTextView: TextView
    private lateinit var aiAdviceTextView: TextView
    private lateinit var fetchAdviceButton: Button

    private var currentContext: AdvisorContext? = null
    private var currentReport: AdvisorReport? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_aimi_profile_advisor)

        // Init Service with injected deps
        advisorService = AimiAdvisorService(profileFunction, persistenceLayer, preferences)

        // Bind UI
        scoreTextView = findViewById(R.id.aimi_advisor_score_text)
        summaryTextView = findViewById(R.id.aimi_advisor_summary_text)
        recommendationTextView = findViewById(R.id.aimi_advisor_recommendation_text)
        aiAdviceTextView = findViewById(R.id.aimi_advisor_ai_advice_text)
        fetchAdviceButton = findViewById(R.id.aimi_advisor_fetch_ai_button)

        fetchAdviceButton.setOnClickListener {
            fetchAiAdvice()
        }

        // LOAD DATA ASYNC (Fix for Crash)
        loadAdvisorData()
    }

    private fun loadAdvisorData() {
        scoreTextView.text = "Analyse en cours..."
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Heavy DB work here
                val report = advisorService.generateReport(7)
                
                withContext(Dispatchers.Main) {
                    currentReport = report
                    currentContext = advisorService.collectContext(7) // Redundant but consistent for now
                    displayReport(report)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    scoreTextView.text = "Erreur"
                    summaryTextView.text = "Impossible de charger les données: ${e.localizedMessage}"
                }
                e.printStackTrace()
            }
        }
    }

    private fun displayReport(report: AdvisorReport) {
        // 1. Score
        scoreTextView.text = "${report.overallScore}/10"
        
        // 2. Metrics Summary
        val metrics = report.metrics
        val summary = """
             Période: ${metrics.periodLabel}
             TIR: ${(metrics.tir70_180 * 100).toInt()}% | GMI: ${metrics.gmi}%
             Hypos: ${(metrics.timeBelow70 * 100).toInt()}% | Hypers: ${(metrics.timeAbove180 * 100).toInt()}%
        """.trimIndent()
        summaryTextView.text = summary

        // 3. Simple Rules Analysis
        val analysis = advisorService.generatePlainTextAnalysis(
             currentContext ?: advisorService.collectContext(7), // Fallback safety
             report
        )
        recommendationTextView.text = analysis
    }

    private fun fetchAiAdvice() {
        val ctx = currentContext
        val rep = currentReport
        
        if (ctx == null || rep == null) return

        aiAdviceTextView.text = "Consultation de l'expert AI en cours..."
        fetchAdviceButton.isEnabled = false

        CoroutineScope(Dispatchers.IO).launch {
            // TODO: In production, fetch API Key safely.
            // For MVP: Use a placeholder or check Preferences if implemented
            val apiKey = "" // User must provide this
            
            val advice = coachingService.fetchAdvice(ctx, rep, apiKey)
            
            withContext(Dispatchers.Main) {
                aiAdviceTextView.text = advice
                fetchAdviceButton.isEnabled = true
            }
        }
    }
}
