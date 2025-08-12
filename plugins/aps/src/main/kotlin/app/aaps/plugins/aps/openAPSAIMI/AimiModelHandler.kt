package app.aaps.plugins.aps.openAPSAIMI

import android.os.Environment
import android.util.Log
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Gestion centralisée des modèles TFLite AIMI (main & UAM).
 * - Interpréteurs persistants (lazy, thread-safe)
 * - Cache résultat (clé SHA-256 stable)
 * - Nettoyage des features (NaN/Inf -> 0)
 * - Fermeture propre via closeInterpreters()
 */
object AimiModelHandler {

    private const val TAG = "AIMI-Model"

    // --- Chemins modèles (même logique que dans ton code actuel) ---
    private val externalDir = File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS")
    private val modelFile = File(externalDir, "ml/model.tflite")
    private val modelFileUAM = File(externalDir, "ml/modelUAM.tflite")

    @Volatile private var mainModelFile: File? = defaultMainModelFile()
    @Volatile private var uamModelFile: File?  = defaultUamModelFile()

    // --- Interpréteurs TFLite (lazy/persistants) ---
    @Volatile private var interpreterMeal: Interpreter? = null
    @Volatile private var interpreterUAM: Interpreter?  = null

    // --- Locks pour sécurité thread ---
    private val lockMeal = Any()
    private val lockUam  = Any()

    // --- Cache SMB ---
    private val smbCache: Cache<String, Float> = CacheBuilder.newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(30, TimeUnit.MINUTES)
        .build()

    // ==================== API publique ====================

    /** Optionnel: reconfigurer explicitement (sinon utilise les defaults ci-dessus). */
    fun configure(main: File?, uam: File?) {
        mainModelFile = main ?: defaultMainModelFile()
        uamModelFile  = uam  ?: defaultUamModelFile()
        closeInterpreters()
        smbCache.invalidateAll()
        Log.i(TAG, "Configured model files: main=${mainModelFile?.absolutePath}, uam=${uamModelFile?.absolutePath}")
    }

    /**
     * Prédit un SMB à partir des features construites par l'appelant.
     * @param cob             COB, sert aussi à choisir le modèle
     * @param lastCarbAgeMin  âge des derniers glucides (min), sert aussi à choisir le modèle
     * @param featuresBuilder lambda qui reçoit "main" ou "UAM" et retourne le FloatArray d'inputs
     */
    fun predictSmb(
        cob: Double,
        lastCarbAgeMin: Int,
        featuresBuilder: (modelName: String) -> FloatArray
    ): Float {
        val (modelName, interpreter) = selectModel(cob, lastCarbAgeMin)
        if (interpreter == null) {
            Log.w(TAG, "No model available (name=$modelName). Returning 0.")
            return 0f
        }

        val rawInputs = try {
            featuresBuilder(modelName)
        } catch (e: Throwable) {
            Log.e(TAG, "featuresBuilder failed: ${e.message}")
            return 0f
        }
        if (rawInputs.isEmpty()) return 0f

        val inputs = sanitize(rawInputs)
        val key = cacheKey(modelName, inputs)

        smbCache.getIfPresent(key)?.let { cached ->
            if (isUsable(cached)) {
                Log.d(TAG, "SMB cache HIT ($modelName) -> $cached")
                return cached
            } else {
                Log.w(TAG, "SMB cache HIT but unusable ($modelName) -> $cached; recompute")
            }
        }

        val raw = try {
            runModel(interpreter, inputs)
        } catch (e: Throwable) {
            Log.e(TAG, "TFLite run failed ($modelName): ${e.message}")
            return 0f
        }

        val result = if (isUsable(raw)) round4(raw) else 0f
        if (isUsable(result)) {
            smbCache.put(key, result)
            Log.d(TAG, "SMB cache PUT ($modelName) -> $result")
        } else {
            Log.w(TAG, "Unusable SMB result ($modelName): $raw (stored? no)")
        }
        return max(0f, result)
    }

    /** À appeler sur stop/disable du plugin ou changement de prefs AIMI. */
    @Synchronized
    fun closeInterpreters() {
        try {
            synchronized(lockMeal) {
                interpreterMeal?.close()
                interpreterMeal = null
            }
            synchronized(lockUam) {
                interpreterUAM?.close()
                interpreterUAM = null
            }
            Log.i(TAG, "Interpreters closed")
        } catch (e: Throwable) {
            Log.w(TAG, "Error closing interpreters: ${e.message}")
        }
    }

    fun clearCache() {
        smbCache.invalidateAll()
        Log.i(TAG, "SMB cache cleared")
    }

    // ==================== Sélection du modèle ====================

    private fun selectModel(cob: Double, lastCarbAgeMin: Int): Pair<String, Interpreter?> {
        val main = mainModelFile
        val uam  = uamModelFile

        // Modèle "main" si COB > 0 et repas récent
        if (cob > 0.0 && lastCarbAgeMin < 240 && main?.exists() == true) {
            return "main" to getMealInterpreter(main)
        }

        // Sinon UAM si dispo
        if (uam?.exists() == true) {
            return "UAM" to getUamInterpreter(uam)
        }

        return "none" to null
    }

    // ==================== Interpréteurs (lazy) ====================

    private fun getMealInterpreter(file: File): Interpreter? {
        interpreterMeal?.let { return it }
        synchronized(lockMeal) {
            interpreterMeal?.let { return it }
            return try {
                val mapped = loadModel(file)
                Interpreter(mapped).also { interpreterMeal = it }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to init MEAL model: ${e.message}")
                null
            }
        }
    }

    private fun getUamInterpreter(file: File): Interpreter? {
        interpreterUAM?.let { return it }
        synchronized(lockUam) {
            interpreterUAM?.let { return it }
            return try {
                val mapped = loadModel(file)
                Interpreter(mapped).also { interpreterUAM = it }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to init UAM model: ${e.message}")
                null
            }
        }
    }

    // ==================== Exécution modèle ====================

    private fun runModel(interpreter: Interpreter, features: FloatArray): Float {
        val input = arrayOf(features)                 // 1 x N
        val out = Array(1) { FloatArray(1) }         // 1 x 1
        interpreter.run(input, out)
        return out[0][0]
    }

    // ==================== Utilitaires ====================

    private fun defaultMainModelFile(): File? = if (modelFile.exists()) modelFile else null
    private fun defaultUamModelFile(): File?  = if (modelFileUAM.exists()) modelFileUAM else null

    private fun loadModel(file: File): MappedByteBuffer {
        FileInputStream(file).use { fis ->
            val ch = fis.channel
            return ch.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
        }
    }

    private fun sanitize(values: FloatArray): FloatArray {
        val out = FloatArray(values.size)
        for (i in values.indices) {
            val v = values[i]
            out[i] = if (v.isFinite() && !v.isNaN()) v else 0f
        }
        return out
    }

    private fun isUsable(v: Float): Boolean = v.isFinite() && !v.isNaN()

    private fun round4(v: Float): Float = (v * 10000f).toInt() / 10000f.toFloat()

    private fun cacheKey(modelName: String, inputs: FloatArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(modelName.toByteArray(StandardCharsets.UTF_8))
        val bb = ByteBuffer.allocate(inputs.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        inputs.forEach { bb.putFloat(it) }
        md.update(bb.array())
        return modelName + "_" + md.digest().joinToString("") { "%02x".format(it) }
    }

    // Extensions pratiques (si besoin côté appelant)
    fun Double.cleanF(): Float = if (this.isFinite()) this.toFloat() else 0f
    fun Float.cleanF(): Float  = if (this.isFinite()) this else 0f
}
