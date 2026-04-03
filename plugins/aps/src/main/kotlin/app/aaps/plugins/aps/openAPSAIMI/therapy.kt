package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.db.PersistenceLayer
import java.util.Calendar
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class Therapy(private val persistenceLayer: PersistenceLayer) {

    var sleepTime = false
    var sportTime = false
    var snackTime = false
    var lowCarbTime = false
    var highCarbTime = false
    var mealTime = false
    var bfastTime = false
    var lunchTime = false
    var dinnerTime = false
    var fastingTime = false
    var stopTime = false
    var calibrationTime = false
    var deleteEventDate: String? = null
    var deleteTime = false

    fun updateStatesBasedOnTherapyEvents() {
        runBlocking(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val fromTime = now - TimeUnit.DAYS.toMillis(1)
            val events = persistenceLayer.getTherapyEventDataFromTime(fromTime, ascending = true)

            stopTime = findActivestopEvents(events, now)
            if (!stopTime) {
                sleepTime = findActiveSleepEvents(events, now)
                sportTime = findActiveSportEvents(events, now)
                snackTime = findActiveSnackEvents(events, now)
                lowCarbTime = findActiveLowCarbEvents(events, now)
                highCarbTime = findActiveHighCarbEvents(events, now)
                mealTime = findActiveMealEvents(events, now)
                bfastTime = findActivebfastEvents(events, now)
                lunchTime = findActiveLunchEvents(events, now)
                dinnerTime = findActiveDinnerEvents(events, now)
                fastingTime = findActiveFastingEvents(events, now)
                calibrationTime = isCalibrationEvent(events, now)

                deleteTime = findActivedeleteEvents(events, now)
                val deleteNote = events.find {
                    it.type == TE.Type.NOTE && it.note?.contains("delete", ignoreCase = true) == true
                }?.note
                deleteEventDate = extractDateFromDeleteEvent(deleteNote)
            } else {
                resetAllStates()
                clearActiveEvent("sleep")
                clearActiveEvent("sport")
                clearActiveEvent("snack")
                clearActiveEvent("lowcarb")
                clearActiveEvent("highcarb")
                clearActiveEvent("meal")
                clearActiveEvent("bfast")
                clearActiveEvent("lunch")
                clearActiveEvent("dinner")
                clearActiveEvent("fasting")
                clearActiveEvent("delete")
            }
        }
    }

    private suspend fun clearActiveEvent(noteKeyword: String) {
        persistenceLayer.deleteLastEventMatchingKeyword(noteKeyword)
    }

    private fun resetAllStates() {
        sleepTime = false
        sportTime = false
        snackTime = false
        lowCarbTime = false
        highCarbTime = false
        mealTime = false
        bfastTime = false
        lunchTime = false
        dinnerTime = false
        fastingTime = false
        deleteTime = false
    }

    private fun findActiveSleepEvents(events: List<TE>, now: Long): Boolean =
        events.filter { it.type == TE.Type.NOTE }
            .any { event ->
                event.note?.contains("sleep", ignoreCase = true) == true &&
                    now <= (event.timestamp + event.duration)
            }

    private fun isCalibrationEvent(events: List<TE>, now: Long): Boolean {
        val cutoff = now - TimeUnit.MINUTES.toMillis(15)
        return events.filter { it.type == TE.Type.FINGER_STICK_BG_VALUE && it.timestamp >= cutoff }
            .any { event -> now <= (event.timestamp + event.duration) }
    }

    private fun extractDateFromDeleteEvent(note: String?): String? {
        val deletePattern = Pattern.compile("delete (\\d{2}/\\d{2}/\\d{4})", Pattern.CASE_INSENSITIVE)
        val matcher = deletePattern.matcher(note ?: "")
        return if (matcher.find()) {
            matcher.group(1)
        } else {
            null
        }
    }

    private fun findActiveSportEvents(events: List<TE>, now: Long): Boolean =
        events.filter { it.type == TE.Type.NOTE }
            .any { event ->
                val note = event.note?.lowercase() ?: ""
                val containsSport = note.contains("sport", ignoreCase = true)
                val isWalking = note.contains("marche", ignoreCase = true) || note.contains("walk", ignoreCase = true)
                (containsSport && !isWalking) &&
                    now <= (event.timestamp + event.duration)
            }

    private fun findActiveSnackEvents(events: List<TE>, now: Long): Boolean =
        events.filter { it.type == TE.Type.NOTE }
            .any { event ->
                event.note?.contains("snack", ignoreCase = true) == true &&
                    now <= (event.timestamp + event.duration)
            }

    private fun findActiveLowCarbEvents(events: List<TE>, now: Long): Boolean =
        events.filter { it.type == TE.Type.NOTE }
            .any { event ->
                event.note?.contains("lowcarb", ignoreCase = true) == true &&
                    now <= (event.timestamp + event.duration)
            }

    private fun findActiveHighCarbEvents(events: List<TE>, now: Long): Boolean =
        events.filter { it.type == TE.Type.NOTE }
            .any { event ->
                val note = event.note ?: ""
                (note.contains("highcarb", ignoreCase = true) || note.contains("high carb", ignoreCase = true)) &&
                    now <= (event.timestamp + event.duration)
            }

    private fun findActiveMealEvents(events: List<TE>, now: Long): Boolean =
        events.filter { it.type == TE.Type.NOTE }
            .any { event ->
                event.note?.contains("meal", ignoreCase = true) == true &&
                    now <= (event.timestamp + event.duration)
            }

    private fun findActivebfastEvents(events: List<TE>, now: Long): Boolean =
        events.filter { it.type == TE.Type.NOTE }
            .any { event ->
                val note = event.note ?: ""
                (note.contains("bfast", ignoreCase = true) || note.contains("breakfast", ignoreCase = true)) &&
                    now <= (event.timestamp + event.duration)
            }

    private fun findActiveLunchEvents(events: List<TE>, now: Long): Boolean =
        events.filter { it.type == TE.Type.NOTE }
            .any { event ->
                event.note?.contains("lunch", ignoreCase = true) == true &&
                    now <= (event.timestamp + event.duration)
            }

    private fun findActivedeleteEvents(events: List<TE>, now: Long): Boolean =
        events.filter { it.type == TE.Type.NOTE }
            .any { event ->
                event.note?.contains("delete", ignoreCase = true) == true &&
                    now <= (event.timestamp + event.duration)
            }

    private fun findActiveDinnerEvents(events: List<TE>, now: Long): Boolean =
        events.filter { it.type == TE.Type.NOTE }
            .any { event ->
                event.note?.contains("dinner", ignoreCase = true) == true &&
                    now <= (event.timestamp + event.duration)
            }

    private fun findActiveFastingEvents(events: List<TE>, now: Long): Boolean =
        events.filter { it.type == TE.Type.NOTE }
            .any { event ->
                event.note?.contains("fasting", ignoreCase = true) == true &&
                    now <= (event.timestamp + event.duration)
            }

    private fun findActivestopEvents(events: List<TE>, now: Long): Boolean =
        events.filter { it.type == TE.Type.NOTE }
            .any { event ->
                event.note?.contains("stop", ignoreCase = true) == true &&
                    now <= (event.timestamp + event.duration)
            }

    fun getTimeElapsedSinceLastEvent(keyword: String): Long {
        return runBlocking(Dispatchers.IO) {
            val fromTime = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(60)
            val events = persistenceLayer.getTherapyEventDataFromTime(fromTime, TE.Type.NOTE, ascending = true)
            val lastEvent = events.filter { it.note?.contains(keyword, ignoreCase = true) == true }
                .maxByOrNull { it.timestamp }
            lastEvent?.let { (System.currentTimeMillis() - it.timestamp) / 60000 } ?: -1L
        }
    }
}
