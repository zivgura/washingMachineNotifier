package com.ziv.washingmachine

import android.content.Context
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import org.jtransforms.fft.DoubleFFT_1D
import org.apache.commons.math3.complex.Complex
import org.apache.commons.math3.transform.FastFourierTransformer
import org.apache.commons.math3.transform.DftNormalization
import org.apache.commons.math3.transform.TransformType

object AudioUtils {
    // Audio fingerprinting parameters
    private const val FINGERPRINT_WINDOW_SIZE = 2048
    private const val FINGERPRINT_HOP_SIZE = 512
    private const val FINGERPRINT_FREQ_BINS = 32
    private const val FINGERPRINT_TIME_FRAMES = 30
    private const val FINGERPRINT_MATCH_THRESHOLD = 0.85
    
    // Load PCM data from a WAV file in res/raw
    fun loadWavResource(context: Context, resId: Int): ShortArray {
        val inputStream: InputStream = context.resources.openRawResource(resId)
        
        // Read WAV header
        val header = ByteArray(44)
        inputStream.read(header)
        
        // Parse WAV header
        val sampleRate = (header[24].toInt() and 0xFF) or 
                        ((header[25].toInt() and 0xFF) shl 8) or
                        ((header[26].toInt() and 0xFF) shl 16) or
                        ((header[27].toInt() and 0xFF) shl 24)
        
        val numChannels = (header[22].toInt() and 0xFF) or ((header[23].toInt() and 0xFF) shl 8)
        val bitsPerSample = (header[34].toInt() and 0xFF) or ((header[35].toInt() and 0xFF) shl 8)
        
        println("WAV Info: sampleRate=$sampleRate, channels=$numChannels, bitsPerSample=$bitsPerSample")
        
        // Read PCM data
        val pcmData = inputStream.readBytes()
        inputStream.close()
        
        // Convert to mono if stereo
        val shortBuffer = if (numChannels == 2) {
            // Stereo to mono conversion (average left and right channels)
            val monoSize = pcmData.size / 4 // 2 channels * 2 bytes per sample
            val monoBuffer = ShortArray(monoSize)
            for (i in 0 until monoSize) {
                val left = ((pcmData[4 * i + 1].toInt() and 0xFF) shl 8) or (pcmData[4 * i].toInt() and 0xFF)
                val right = ((pcmData[4 * i + 3].toInt() and 0xFF) shl 8) or (pcmData[4 * i + 2].toInt() and 0xFF)
                monoBuffer[i] = ((left + right) / 2).toShort()
            }
            monoBuffer
        } else {
            // Mono - direct conversion
            val shortBuffer = ShortArray(pcmData.size / 2)
            for (i in shortBuffer.indices) {
                shortBuffer[i] = (((pcmData[2 * i + 1].toInt() and 0xFF) shl 8) or (pcmData[2 * i].toInt() and 0xFF)).toShort()
            }
            shortBuffer
        }
        
        return shortBuffer
    }

    // Generate audio fingerprint from PCM data
    fun generateAudioFingerprint(pcm: ShortArray, sampleRate: Int): AudioFingerprint {
        val fingerprints = mutableListOf<DoubleArray>()
        var offset = 0
        
        while (offset + FINGERPRINT_WINDOW_SIZE <= pcm.size && fingerprints.size < FINGERPRINT_TIME_FRAMES) {
            val window = pcm.sliceArray(offset until offset + FINGERPRINT_WINDOW_SIZE)
            val fft = computeFFT(window)
            val fingerprint = extractFingerprintFromFFT(fft, sampleRate)
            fingerprints.add(fingerprint)
            offset += FINGERPRINT_HOP_SIZE
        }
        
        return AudioFingerprint(fingerprints, sampleRate)
    }
    
    // Extract fingerprint features from FFT
    private fun extractFingerprintFromFFT(fft: DoubleArray, sampleRate: Int): DoubleArray {
        val fingerprint = DoubleArray(FINGERPRINT_FREQ_BINS)
        
        // Group frequency bins into fingerprint bands
        val binSize = fft.size / FINGERPRINT_FREQ_BINS
        
        for (i in 0 until FINGERPRINT_FREQ_BINS) {
            val startBin = i * binSize
            val endBin = minOf((i + 1) * binSize, fft.size)
            
            // Calculate energy in this frequency band
            var energy = 0.0
            for (j in startBin until endBin) {
                energy += fft[j] * fft[j]
            }
            
            // Convert to dB scale and normalize
            fingerprint[i] = if (energy > 0) 10 * log10(energy) else -100.0
        }
        
        return fingerprint
    }
    
    // Compare two audio fingerprints
    fun compareFingerprints(fingerprint1: AudioFingerprint, fingerprint2: AudioFingerprint): Double {
        if (fingerprint1.fingerprints.isEmpty() || fingerprint2.fingerprints.isEmpty()) {
            return 0.0
        }
        
        var totalSimilarity = 0.0
        var comparisons = 0
        
        // Compare each time frame
        for (i in 0 until minOf(fingerprint1.fingerprints.size, fingerprint2.fingerprints.size)) {
            val similarity = compareFingerprintFrame(fingerprint1.fingerprints[i], fingerprint2.fingerprints[i])
            totalSimilarity += similarity
            comparisons++
        }
        
        return if (comparisons > 0) totalSimilarity / comparisons else 0.0
    }
    
    // Compare individual fingerprint frames
    private fun compareFingerprintFrame(frame1: DoubleArray, frame2: DoubleArray): Double {
        if (frame1.size != frame2.size) return 0.0
        
        var sumSquaredDiff = 0.0
        var maxDiff = 0.0
        
        for (i in frame1.indices) {
            val diff = abs(frame1[i] - frame2[i])
            sumSquaredDiff += diff * diff
            maxDiff = max(maxDiff, diff)
        }
        
        // Calculate similarity (0-1 scale)
        val rmsDiff = sqrt(sumSquaredDiff / frame1.size)
        val similarity = max(0.0, 1.0 - (rmsDiff / 100.0)) // Normalize by expected range
        
        return similarity
    }
    
    // Check if audio matches fingerprint
    fun audioMatchesFingerprint(livePcm: ShortArray, referenceFingerprint: AudioFingerprint, sampleRate: Int): Boolean {
        val liveFingerprint = generateAudioFingerprint(livePcm, sampleRate)
        val similarity = compareFingerprints(referenceFingerprint, liveFingerprint)
        return similarity >= FINGERPRINT_MATCH_THRESHOLD
    }
    
    // Real FFT using JTransform (preferred)
    fun computeFFT(pcm: ShortArray): DoubleArray {
        return try {
            computeFFTJTransform(pcm)
        } catch (e: Exception) {
            // Fallback to Apache Commons Math
            computeFFTCommons(pcm)
        }
    }

    // FFT implementation using JTransform
    private fun computeFFTJTransform(pcm: ShortArray): DoubleArray {
        val n = pcm.size
        val fftData = DoubleArray(n)
        for (i in 0 until n) {
            fftData[i] = pcm[i].toDouble()
        }
        val fft = DoubleFFT_1D(n.toLong())
        fft.realForward(fftData)
        // Return the magnitude spectrum (first n/2 bins)
        val magnitudes = DoubleArray(n / 2)
        for (i in 0 until n / 2) {
            val real = fftData[2 * i]
            val imag = fftData[2 * i + 1]
            magnitudes[i] = sqrt(real * real + imag * imag)
        }
        return magnitudes
    }

    // FFT implementation using Apache Commons Math
    private fun computeFFTCommons(pcm: ShortArray): DoubleArray {
        val n = pcm.size
        val transformer = FastFourierTransformer(DftNormalization.STANDARD)
        
        // Convert to Complex array
        val complexData = Array(n) { i ->
            Complex(pcm[i].toDouble(), 0.0)
        }
        
        // Perform FFT
        val fftResult = transformer.transform(complexData, TransformType.FORWARD)
        
        // Return magnitudes
        val magnitudes = DoubleArray(n / 2)
        for (i in 0 until n / 2) {
            magnitudes[i] = fftResult[i].abs()
        }
        return magnitudes
    }

    // Get dominant frequencies from FFT result with improved filtering
    fun getDominantFrequencies(fft: DoubleArray, sampleRate: Int, topN: Int = 5): List<Double> {
        val magnitudes = fft.map { abs(it) }
        
        // Find the maximum magnitude for normalization
        val maxMagnitude = magnitudes.maxOrNull() ?: 1.0
        
        // Filter out very low magnitude components (noise)
        val threshold = maxMagnitude * 0.1 // Only consider frequencies with >10% of max magnitude
        
        return magnitudes
            .withIndex()
            .filter { it.value > threshold }
            .sortedByDescending { it.value }
            .take(topN)
            .map { (index, _) -> 
                // More accurate frequency calculation
                index * sampleRate.toDouble() / fft.size 
            }
            .filter { freq -> 
                // Filter out very low frequencies (likely noise) and very high frequencies
                freq > 50.0 && freq < sampleRate / 2.2 
            }
    }
}

// Data class to hold audio fingerprint
data class AudioFingerprint(
    val fingerprints: List<DoubleArray>, // Time-frequency fingerprint frames
    val sampleRate: Int
) 