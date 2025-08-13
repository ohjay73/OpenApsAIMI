package app.aaps.plugins.aps.openAPSAIMI

import android.content.Context
import android.util.Log

object AimiModelProvider {

    private var modelHandler: AimiModelHandler? = null

    fun getInstance(context: Context): AimiModelHandler {
        if (modelHandler == null) {
            modelHandler = AimiModelHandler()
            modelHandler!!.initModelInterpreters(context.applicationContext)
            Log.d("AIMI", "✅ AimiModelHandler initialisé via singleton")
        }
        return modelHandler!!
    }

    fun release() {
        modelHandler?.releaseModelInterpreters()
        modelHandler = null
    }
}
