package com.hyperisland.root.audio

import android.media.audiofx.Visualizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Captures the device's global audio mix via [Visualizer] (session 0) and turns
 * it into two things the Island UI actually wants:
 *
 * 1. [amplitude] — a smoothed 0..1 loudness value, updated on every capture
 *    frame (~20-23ms at the max supported rate). Drives [EqualizerBars] bar
 *    heights directly instead of a canned loop.
 * 2. [beatPhase] / [bpm] — a simple energy-based onset detector: we track a
 *    short-term vs long-term energy envelope of the waveform, flag a "beat"
 *    when short-term energy spikes above the rolling average by a threshold
 *    (with a refractory window so we don't double-trigger on one transient),
 *    then derive BPM from the smoothed inter-beat interval. [beatPhase] ramps
 *    0→1 between beats so a caller can drive a wave/animation phase directly
 *    off it instead of free-running time.
 *
 * This is intentionally simple (no FFT, no genre-aware tempo tracking) — it's
 * a "feels alive and roughly in time" driver for a status-bar-sized visual,
 * not a music analysis library. Good enough for percussive/EDM-ish material;
 * soft ambient tracks will under-trigger, which is fine (fallback animations
 * cover that case in the composables).
 *
 * Requires RECORD_AUDIO at runtime (Visualizer capture is gated on it even
 * though we never touch the mic) and only functions while some app has an
 * active playback session for it to attach to.
 */
object AudioPulseEngine {

    private const val CAPTURE_SIZE = 1024

    // Beat detector tuning
    private const val ENERGY_HISTORY = 43 // ~1s of history at ~23ms/frame
    private const val BEAT_THRESHOLD_MULT = 1.35f
    private const val MIN_BEAT_INTERVAL_MS = 250L // refractory ~240bpm ceiling
    private const val MAX_BEAT_INTERVAL_MS = 2000L // ~30bpm floor before we give up
    private const val BPM_SMOOTHING = 0.25f

    private var visualizer: Visualizer? = null
    private var running = false

    private val _amplitude = MutableStateFlow(0f)
    /** Smoothed 0..1 loudness of the current audio mix. 0 when nothing is captured. */
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _beatPhase = MutableStateFlow(0f)
    /** 0..1 ramp between detected beats; snaps to 0 the instant a beat fires. */
    val beatPhase: StateFlow<Float> = _beatPhase.asStateFlow()

    private val _bpm = MutableStateFlow(0f)
    /** Smoothed estimated BPM, 0 until at least two consistent beats have been seen. */
    val bpm: StateFlow<Float> = _bpm.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    /** True once a Visualizer is actually attached and receiving frames. */
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val energyHistory = FloatArray(ENERGY_HISTORY)
    private var historyIndex = 0
    private var historyFilled = 0
    private var lastBeatAtMs = 0L
    private var lastFrameAtMs = 0L

    /**
     * Attempts to start capturing the global mix (audio session 0). Safe to
     * call repeatedly. Caller must hold RECORD_AUDIO before calling this —
     * we don't check permissions here, only catch the SecurityException.
     *
     * @return true if capture actually started.
     */
    fun start(): Boolean {
        if (running) return true
        return try {
            val viz = Visualizer(0).apply {
                val maxRate = Visualizer.getMaxCaptureRate()
                captureSize = CAPTURE_SIZE.coerceAtMost(Visualizer.getCaptureSizeRange()[1])
                    .coerceAtLeast(Visualizer.getCaptureSizeRange()[0])
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            visualizer: Visualizer?,
                            waveform: ByteArray?,
                            samplingRate: Int
                        ) {
                            waveform ?: return
                            onFrame(waveform)
                        }

                        override fun onFftDataCapture(
                            visualizer: Visualizer?,
                            fft: ByteArray?,
                            samplingRate: Int
                        ) {
                            // Not used — waveform RMS is enough for amplitude/beat.
                        }
                    },
                    maxRate,
                    /* waveform = */ true,
                    /* fft = */ false
                )
                enabled = true
            }
            visualizer = viz
            running = true
            _isCapturing.value = true
            resetBeatState()
            true
        } catch (_: SecurityException) {
            // RECORD_AUDIO not granted.
            _isCapturing.value = false
            false
        } catch (_: RuntimeException) {
            // No audio session / Visualizer unsupported on this device.
            _isCapturing.value = false
            false
        }
    }

    fun stop() {
        running = false
        _isCapturing.value = false
        try {
            visualizer?.enabled = false
            visualizer?.release()
        } catch (_: Exception) {
        }
        visualizer = null
        _amplitude.value = 0f
        _beatPhase.value = 0f
        _bpm.value = 0f
        resetBeatState()
    }

    private fun resetBeatState() {
        historyIndex = 0
        historyFilled = 0
        lastBeatAtMs = 0L
        lastFrameAtMs = 0L
        java.util.Arrays.fill(energyHistory, 0f)
    }

    private fun onFrame(waveform: ByteArray) {
        val now = System.currentTimeMillis()

        // ---- RMS amplitude (0..1) ----
        var sumSquares = 0.0
        for (b in waveform) {
            // Unsigned 8-bit PCM centered at 128.
            val centered = (b.toInt() and 0xFF) - 128
            sumSquares += (centered * centered).toDouble()
        }
        val rms = kotlin.math.sqrt(sumSquares / waveform.size).toFloat() / 128f
        val normalized = rms.coerceIn(0f, 1f)

        // Light exponential smoothing so bars don't flicker frame-to-frame.
        val prevAmp = _amplitude.value
        val smoothingUp = 0.55f  // react fast to onsets
        val smoothingDown = 0.20f // decay a bit slower, reads as "falling" not "cutting"
        val smoothed = if (normalized > prevAmp) {
            prevAmp + (normalized - prevAmp) * smoothingUp
        } else {
            prevAmp + (normalized - prevAmp) * smoothingDown
        }
        _amplitude.value = smoothed.coerceIn(0f, 1f)

        // ---- Beat / BPM detection off the same energy signal ----
        val instantEnergy = normalized * normalized
        val avgEnergy = if (historyFilled > 0) {
            var sum = 0f
            for (i in 0 until historyFilled) sum += energyHistory[i]
            sum / historyFilled
        } else 0f

        energyHistory[historyIndex] = instantEnergy
        historyIndex = (historyIndex + 1) % ENERGY_HISTORY
        historyFilled = min(historyFilled + 1, ENERGY_HISTORY)

        val sinceLastBeat = now - lastBeatAtMs
        val isOnset = historyFilled >= ENERGY_HISTORY / 2 &&
            instantEnergy > avgEnergy * BEAT_THRESHOLD_MULT &&
            instantEnergy > 0.002f && // ignore near-silence noise
            sinceLastBeat >= MIN_BEAT_INTERVAL_MS

        if (isOnset) {
            if (lastBeatAtMs > 0L && sinceLastBeat in MIN_BEAT_INTERVAL_MS..MAX_BEAT_INTERVAL_MS) {
                val instantBpm = 60_000f / sinceLastBeat
                val prevBpm = _bpm.value
                _bpm.value = if (prevBpm <= 0f) {
                    instantBpm
                } else {
                    prevBpm + (instantBpm - prevBpm) * BPM_SMOOTHING
                }
            }
            lastBeatAtMs = now
            _beatPhase.value = 0f
        } else if (lastBeatAtMs > 0L) {
            // Ramp phase 0..1 across the expected beat interval (falls back to a
            // fixed guess until we have a real BPM estimate).
            val intervalMs = if (_bpm.value > 0f) 60_000f / _bpm.value else 600f
            val elapsed = (now - lastBeatAtMs).toFloat()
            _beatPhase.value = min(elapsed / intervalMs, 1f)
        }

        lastFrameAtMs = now
    }
}
