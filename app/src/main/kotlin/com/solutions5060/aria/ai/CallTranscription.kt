package com.solutions5060.aria.ai

import android.content.Context
import android.util.Log
import com.solutions5060.aria.service.SipEngineHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.aria_mobile.AiCallInsight

/**
 * Drives on-device transcription across a call's lifetime.
 *
 * Capture is opt-in and off by default. Tapping call audio is a recording, so
 * the decision belongs to the user rather than to whether a model happens to be
 * installed — [isEnabled] gates every entry point here, and a device with a
 * model downloaded but the toggle off captures nothing.
 *
 * Audio never crosses the FFI boundary: the tap lives in the Rust media layer,
 * and this object only passes call ids in and reads finished text back out.
 */
object CallTranscription {
    private const val TAG = "CallTranscription"
    private const val PREFS = "aria_prefs"
    private const val KEY_ENABLED = "ai_transcribe_calls"

    /** Call ids captured this session, so a stop can never run without a start. */
    private val capturing = mutableSetOf<String>()

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** True when this build has the models compiled in and a speech model is installed. */
    suspend fun isReady(context: Context): Boolean = withContext(Dispatchers.IO) {
        val engine = SipEngineHolder.engine ?: return@withContext false
        if (!engine.aiAvailable()) return@withContext false
        try {
            engine.aiInit(context.filesDir.resolve("ai").absolutePath)
            engine.aiModels().any { it.kind == "stt" && it.installed }
        } catch (e: Exception) {
            Log.w(TAG, "AI readiness probe failed: ${e.message}")
            false
        }
    }

    /**
     * Begin capturing, if the user asked for it and a model is actually present.
     * Every failure path here is non-fatal: a call must connect whether or not
     * transcription can run.
     */
    suspend fun onCallStarted(context: Context, callId: String) {
        if (!isEnabled(context)) return
        if (capturing.contains(callId)) return
        if (!isReady(context)) {
            Log.i(TAG, "Transcription on but no speech model installed; skipping $callId")
            return
        }
        try {
            withContext(Dispatchers.IO) { SipEngineHolder.engine?.aiStartCapture(callId) }
            capturing.add(callId)
            Log.i(TAG, "Capturing audio for $callId")
        } catch (e: Exception) {
            Log.w(TAG, "Could not start capture for $callId: ${e.message}")
        }
    }

    /**
     * Stop capturing and transcribe. Returns null when nothing was captured, so
     * the caller can tell "no transcript" from "empty transcript".
     *
     * Transcription is slower than the call teardown it follows, so this is a
     * suspending call the UI runs off the main thread.
     */
    suspend fun onCallEnded(context: Context, callId: String): AiCallInsight? {
        if (!capturing.remove(callId)) return null
        val engine = SipEngineHolder.engine ?: return null
        return try {
            withContext(Dispatchers.IO) {
                engine.aiStopCapture(callId)
                engine.aiTranscribe(callId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Transcription failed for $callId: ${e.message}")
            // Drop the audio rather than leaving it on disk after a failure.
            try {
                withContext(Dispatchers.IO) { engine.aiDiscardCapture(callId) }
            } catch (_: Exception) {}
            null
        }
    }

    /** Throw away captured audio without transcribing it. */
    suspend fun discard(callId: String) {
        if (!capturing.remove(callId)) return
        try {
            withContext(Dispatchers.IO) { SipEngineHolder.engine?.aiDiscardCapture(callId) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not discard capture for $callId: ${e.message}")
        }
    }
}
