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
import kotlin.math.pow
import org.jtransforms.fft.DoubleFFT_1D
import org.apache.commons.math3.complex.Complex
import org.apache.commons.math3.transform.FastFourierTransformer
import org.apache.commons.math3.transform.DftNormalization
import org.apache.commons.math3.transform.TransformType
import android.util.Log

object AudioUtils {
    // Audio fingerprinting parameters
    private const val FINGERPRINT_WINDOW_SIZE = 2048
    private const val FINGERPRINT_HOP_SIZE = 512
    private const val FINGERPRINT_FREQ_BINS = 32
    private const val FINGERPRINT_TIME_FRAMES = 30
    private const val FINGERPRINT_MATCH_THRESHOLD = 0.85
    
    // Enhanced fingerprinting parameters
    private const val MFCC_COEFFICIENTS = 13
    private const val MEL_FILTER_BANKS = 26
    private const val SPECTRAL_CENTROID_WEIGHT = 0.3
    private const val SPECTRAL_ROLLOFF_WEIGHT = 0.2
    private const val SPECTRAL_FLUX_WEIGHT = 0.1
    
    // Adaptive threshold parameters
    private const val ADAPTIVE_THRESHOLD_WINDOW = 50
    private const val ADAPTIVE_THRESHOLD_MULTIPLIER = 1.2
    
    // Multi-format audio support
    private const val SUPPORTED_AUDIO_FORMATS = listOf("wav", "m4a", "mp3", "aac")
    
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

    // Load audio data from various formats
    fun loadAudioResource(context: Context, resId: Int, format: String = "auto"): ShortArray {
        return when (format.lowercase()) {
            "wav" -> loadWavResource(context, resId)
            "m4a" -> loadM4AResource(context, resId)
            "auto" -> loadAudioResourceAuto(context, resId)
            else -> throw IllegalArgumentException("Unsupported audio format: $format")
        }
    }
    
    // Auto-detect and load audio resource
    private fun loadAudioResourceAuto(context: Context, resId: Int): ShortArray {
        val resourceName = context.resources.getResourceEntryName(resId)
        val extension = resourceName.substringAfterLast('.', "").lowercase()
        
        return when (extension) {
            "wav" -> loadWavResource(context, resId)
            "m4a" -> loadM4AResource(context, resId)
            else -> {
                // Try to detect format by reading file header
                try {
                    loadWavResource(context, resId)
                } catch (e: Exception) {
                    try {
                        loadM4AResource(context, resId)
                    } catch (e2: Exception) {
                        throw IllegalArgumentException("Could not determine audio format for resource: $resourceName")
                    }
                }
            }
        }
    }
    
    // Load M4A audio data using MediaExtractor
    fun loadM4AResource(context: Context, resId: Int): ShortArray {
        val inputStream = context.resources.openRawResource(resId)
        val tempFile = java.io.File.createTempFile("audio_", ".m4a", context.cacheDir)
        
        try {
            // Copy resource to temp file
            tempFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }
            
            return loadM4AFile(tempFile.absolutePath)
        } finally {
            tempFile.delete()
            inputStream.close()
        }
    }
    
    // Load M4A file using MediaExtractor
    private fun loadM4AFile(filePath: String): ShortArray {
        val extractor = android.media.MediaExtractor()
        extractor.setDataSource(filePath)
        
        // Find audio track
        var audioTrackIndex = -1
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(android.media.MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) {
                audioTrackIndex = i
                break
            }
        }
        
        if (audioTrackIndex == -1) {
            throw IllegalArgumentException("No audio track found in M4A file")
        }
        
        extractor.selectTrack(audioTrackIndex)
        val format = extractor.getTrackFormat(audioTrackIndex)
        val sampleRate = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
        
        // Create decoder
        val decoder = android.media.MediaCodec.createDecoderByType(format.getString(android.media.MediaFormat.KEY_MIME)!!)
        decoder.configure(format, null, null, 0)
        decoder.start()
        
        val audioData = mutableListOf<Short>()
        val bufferInfo = android.media.MediaCodec.BufferInfo()
        var isEOS = false
        
        while (!isEOS) {
            val inputBufferId = decoder.dequeueInputBuffer(10000)
            if (inputBufferId >= 0) {
                val inputBuffer = decoder.getInputBuffer(inputBufferId)
                val sampleSize = extractor.readSampleData(inputBuffer!!, 0)
                
                if (sampleSize < 0) {
                    decoder.queueInputBuffer(inputBufferId, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    isEOS = true
                } else {
                    decoder.queueInputBuffer(inputBufferId, 0, sampleSize, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
            
            val outputBufferId = decoder.dequeueOutputBuffer(bufferInfo, 10000)
            if (outputBufferId >= 0) {
                val outputBuffer = decoder.getOutputBuffer(outputBufferId)
                if (outputBuffer != null) {
                    val buffer = ByteArray(bufferInfo.size)
                    outputBuffer.get(buffer)
                    
                    // Convert to PCM samples
                    val samples = ShortArray(buffer.size / 2)
                    for (i in samples.indices) {
                        samples[i] = ((buffer[2 * i + 1].toInt() and 0xFF) shl 8 or (buffer[2 * i].toInt() and 0xFF)).toShort()
                    }
                    
                    audioData.addAll(samples.toList())
                }
                decoder.releaseOutputBuffer(outputBufferId, false)
            }
        }
        
        decoder.stop()
        decoder.release()
        extractor.release()
        
        return audioData.toShortArray()
    }
    
    // Enhanced audio preprocessing for better fingerprint quality
    fun preprocessAudioForFingerprinting(pcm: ShortArray, sampleRate: Int): ShortArray {
        // Apply high-pass filter to remove DC offset and low-frequency noise
        val filteredPcm = applyHighPassFilter(pcm, sampleRate, 50.0)
        
        // Apply normalization to ensure consistent volume levels
        val normalizedPcm = normalizeAudio(filteredPcm)
        
        // Apply windowing to reduce spectral leakage
        val windowedPcm = applyWindowing(normalizedPcm)
        
        return windowedPcm
    }
    
    // High-pass filter to remove DC offset and low-frequency noise
    private fun applyHighPassFilter(pcm: ShortArray, sampleRate: Int, cutoffFreq: Double): ShortArray {
        val filtered = ShortArray(pcm.size)
        val rc = 1.0 / (2.0 * Math.PI * cutoffFreq)
        val dt = 1.0 / sampleRate
        val alpha = rc / (rc + dt)
        
        // First-order high-pass filter
        var prevInput = 0.0
        var prevOutput = 0.0
        
        for (i in pcm.indices) {
            val input = pcm[i].toDouble()
            val output = alpha * (prevOutput + input - prevInput)
            filtered[i] = output.toInt().toShort()
            prevInput = input
            prevOutput = output
        }
        
        return filtered
    }
    
    // Normalize audio to consistent volume levels
    private fun normalizeAudio(pcm: ShortArray): ShortArray {
        val maxAmplitude = pcm.map { abs(it.toDouble()) }.maxOrNull() ?: 1.0
        if (maxAmplitude == 0.0) return pcm
        
        val scaleFactor = 0.8 * Short.MAX_VALUE / maxAmplitude
        return pcm.map { (it.toDouble() * scaleFactor).toInt().toShort() }.toShortArray()
    }
    
    // Apply windowing to reduce spectral leakage
    private fun applyWindowing(pcm: ShortArray): ShortArray {
        val windowed = ShortArray(pcm.size)
        for (i in pcm.indices) {
            // Hann window
            val window = 0.5 * (1.0 - cos(2.0 * PI * i / (pcm.size - 1)))
            windowed[i] = (pcm[i].toDouble() * window).toInt().toShort()
        }
        return windowed
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

    // Enhanced fingerprint generation with multiple features
    fun generateEnhancedAudioFingerprint(pcm: ShortArray, sampleRate: Int): EnhancedAudioFingerprint {
        val fingerprints = mutableListOf<EnhancedFingerprintFrame>()
        var offset = 0
        
        while (offset + FINGERPRINT_WINDOW_SIZE <= pcm.size && fingerprints.size < FINGERPRINT_TIME_FRAMES) {
            val window = pcm.sliceArray(offset until offset + FINGERPRINT_WINDOW_SIZE)
            val fft = computeFFT(window)
            val fingerprint = extractEnhancedFingerprintFromFFT(fft, sampleRate)
            fingerprints.add(fingerprint)
            offset += FINGERPRINT_HOP_SIZE
        }
        
        return EnhancedAudioFingerprint(fingerprints, sampleRate)
    }
    
    // Extract enhanced fingerprint features from FFT
    private fun extractEnhancedFingerprintFromFFT(fft: DoubleArray, sampleRate: Int): EnhancedFingerprintFrame {
        val energyBands = extractEnergyBands(fft)
        val mfcc = computeMFCC(fft, sampleRate)
        val spectralFeatures = computeSpectralFeatures(fft, sampleRate)
        
        return EnhancedFingerprintFrame(
            energyBands = energyBands,
            mfcc = mfcc,
            spectralCentroid = spectralFeatures.centroid,
            spectralRolloff = spectralFeatures.rolloff,
            spectralFlux = spectralFeatures.flux
        )
    }
    
    // Extract energy bands (improved version)
    private fun extractEnergyBands(fft: DoubleArray): DoubleArray {
        val fingerprint = DoubleArray(FINGERPRINT_FREQ_BINS)
        
        // Use mel-scale frequency bands for better perceptual analysis
        val melBands = createMelFilterBands(fft.size)
        
        for (i in 0 until FINGERPRINT_FREQ_BINS) {
            val startBin = melBands[i].first
            val endBin = melBands[i].second
            
            // Calculate weighted energy in this frequency band
            var energy = 0.0
            var weightSum = 0.0
            
            for (j in startBin until endBin) {
                if (j < fft.size) {
                    val weight = 1.0 - (j - startBin).toDouble() / (endBin - startBin)
                    energy += fft[j] * fft[j] * weight
                    weightSum += weight
                }
            }
            
            // Convert to dB scale with improved normalization
            fingerprint[i] = if (energy > 0 && weightSum > 0) {
                10 * log10(energy / weightSum)
            } else {
                -100.0
            }
        }
        
        return fingerprint
    }
    
    // Create mel-scale filter bands for better frequency analysis
    private fun createMelFilterBands(fftSize: Int): List<Pair<Int, Int>> {
        val bands = mutableListOf<Pair<Int, Int>>()
        val melLow = 0.0
        val melHigh = 2595 * log10(1 + 8000 / 700.0) // 8000 Hz upper limit
        
        for (i in 0 until FINGERPRINT_FREQ_BINS) {
            val melPoint = melLow + (melHigh - melLow) * i / (FINGERPRINT_FREQ_BINS - 1)
            val freqPoint = 700 * (10.0.pow(melPoint / 2595) - 1)
            val binPoint = (freqPoint * fftSize / 44100).toInt()
            
            val startBin = if (i == 0) 0 else bands.last().second
            val endBin = minOf(binPoint, fftSize)
            
            bands.add(Pair(startBin, endBin))
        }
        
        return bands
    }
    
    // Create mel filter banks for MFCC computation
    private fun createMelFilterBanks(fftSize: Int): List<Pair<Int, Int>> {
        val bands = mutableListOf<Pair<Int, Int>>()
        val melLow = 0.0
        val melHigh = 2595 * log10(1 + 8000 / 700.0) // 8000 Hz upper limit
        
        for (i in 0 until MEL_FILTER_BANKS) {
            val melPoint = melLow + (melHigh - melLow) * i / (MEL_FILTER_BANKS - 1)
            val freqPoint = 700 * (10.0.pow(melPoint / 2595) - 1)
            val binPoint = (freqPoint * fftSize / 44100).toInt()
            
            val startBin = if (i == 0) 0 else bands.last().second
            val endBin = minOf(binPoint, fftSize)
            
            bands.add(Pair(startBin, endBin))
        }
        
        return bands
    }
    
    // Compute MFCC coefficients for better frequency analysis
    private fun computeMFCC(fft: DoubleArray, sampleRate: Int): DoubleArray {
        val mfcc = DoubleArray(MFCC_COEFFICIENTS)
        
        // Apply mel filter banks
        val melFiltered = applyMelFilterBanks(fft)
        
        // Apply log
        val logMel = melFiltered.map { if (it > 0) log10(it) else -10.0 }.toDoubleArray()
        
        // Apply DCT (simplified version)
        for (i in 0 until MFCC_COEFFICIENTS) {
            var sum = 0.0
            for (j in logMel.indices) {
                sum += logMel[j] * cos(PI * i * (2 * j + 1) / (2 * logMel.size))
            }
            mfcc[i] = sum
        }
        
        return mfcc
    }
    
    // Apply mel filter banks
    private fun applyMelFilterBanks(fft: DoubleArray): DoubleArray {
        val melBands = createMelFilterBanks(fft.size)
        val melFiltered = DoubleArray(FINGERPRINT_FREQ_BINS)
        
        for (i in melBands.indices) {
            val (startBin, endBin) = melBands[i]
            var energy = 0.0
            
            for (j in startBin until endBin) {
                if (j < fft.size) {
                    energy += fft[j] * fft[j]
                }
            }
            
            melFiltered[i] = energy
        }
        
        return melFiltered
    }
    
    // Compute spectral features for enhanced analysis
    private fun computeSpectralFeatures(fft: DoubleArray, sampleRate: Int): SpectralFeatures {
        val magnitudes = fft.map { abs(it) }
        val maxMagnitude = magnitudes.maxOrNull() ?: 1.0
        
        // Spectral Centroid (center of mass of the spectrum)
        var weightedSum = 0.0
        var magnitudeSum = 0.0
        for (i in magnitudes.indices) {
            val frequency = i * sampleRate.toDouble() / fft.size
            weightedSum += frequency * magnitudes[i]
            magnitudeSum += magnitudes[i]
        }
        val centroid = if (magnitudeSum > 0) weightedSum / magnitudeSum else 0.0
        
        // Spectral Rolloff (frequency below which 85% of energy is contained)
        val totalEnergy = magnitudes.sum()
        val targetEnergy = totalEnergy * 0.85
        var cumulativeEnergy = 0.0
        var rolloff = 0.0
        for (i in magnitudes.indices) {
            cumulativeEnergy += magnitudes[i]
            if (cumulativeEnergy >= targetEnergy) {
                rolloff = i * sampleRate.toDouble() / fft.size
                break
            }
        }
        
        // Spectral Flux (rate of change of spectrum)
        val flux = if (magnitudes.size > 1) {
            magnitudes.zipWithNext().sumOf { (curr, next) -> (next - curr).pow(2) }
        } else {
            0.0
        }
        
        return SpectralFeatures(centroid, rolloff, flux)
    }
    
    // Enhanced fingerprint comparison with multiple features
    fun compareEnhancedFingerprints(fingerprint1: EnhancedAudioFingerprint, fingerprint2: EnhancedAudioFingerprint): Double {
        if (fingerprint1.fingerprints.isEmpty() || fingerprint2.fingerprints.isEmpty()) {
            return 0.0
        }
        
        var totalSimilarity = 0.0
        var comparisons = 0
        
        // Compare each time frame with enhanced features
        for (i in 0 until minOf(fingerprint1.fingerprints.size, fingerprint2.fingerprints.size)) {
            val similarity = compareEnhancedFingerprintFrame(
                fingerprint1.fingerprints[i], 
                fingerprint2.fingerprints[i]
            )
            totalSimilarity += similarity
            comparisons++
        }
        
        return if (comparisons > 0) totalSimilarity / comparisons else 0.0
    }
    
    // Compare individual enhanced fingerprint frames
    private fun compareEnhancedFingerprintFrame(frame1: EnhancedFingerprintFrame, frame2: EnhancedFingerprintFrame): Double {
        // Compare energy bands (40% weight)
        val energySimilarity = compareEnergyBands(frame1.energyBands, frame2.energyBands)
        
        // Compare MFCC coefficients (30% weight)
        val mfccSimilarity = compareMFCC(frame1.mfcc, frame2.mfcc)
        
        // Compare spectral features (30% weight)
        val spectralSimilarity = compareSpectralFeatures(frame1, frame2)
        
        // Weighted combination
        return energySimilarity * 0.4 + mfccSimilarity * 0.3 + spectralSimilarity * 0.3
    }
    
    // Compare energy bands with improved similarity calculation
    private fun compareEnergyBands(bands1: DoubleArray, bands2: DoubleArray): Double {
        if (bands1.size != bands2.size) return 0.0
        
        var sumSquaredDiff = 0.0
        var maxDiff = 0.0
        
        for (i in bands1.indices) {
            val diff = abs(bands1[i] - bands2[i])
            sumSquaredDiff += diff * diff
            maxDiff = max(maxDiff, diff)
        }
        
        // Improved normalization based on typical dB ranges
        val rmsDiff = sqrt(sumSquaredDiff / bands1.size)
        val similarity = max(0.0, 1.0 - (rmsDiff / 50.0)) // Normalize by typical dB range
        
        return similarity
    }
    
    // Compare MFCC coefficients
    private fun compareMFCC(mfcc1: DoubleArray, mfcc2: DoubleArray): Double {
        if (mfcc1.size != mfcc2.size) return 0.0
        
        var sumSquaredDiff = 0.0
        
        for (i in mfcc1.indices) {
            val diff = abs(mfcc1[i] - mfcc2[i])
            sumSquaredDiff += diff * diff
        }
        
        val rmsDiff = sqrt(sumSquaredDiff / mfcc1.size)
        val similarity = max(0.0, 1.0 - (rmsDiff / 10.0)) // MFCC typically in smaller range
        
        return similarity
    }
    
    // Compare spectral features
    private fun compareSpectralFeatures(frame1: EnhancedFingerprintFrame, frame2: EnhancedFingerprintFrame): Double {
        val centroidDiff = abs(frame1.spectralCentroid - frame2.spectralCentroid) / max(frame1.spectralCentroid, frame2.spectralCentroid, 1.0)
        val rolloffDiff = abs(frame1.spectralRolloff - frame2.spectralRolloff) / max(frame1.spectralRolloff, frame2.spectralRolloff, 1.0)
        val fluxDiff = abs(frame1.spectralFlux - frame2.spectralFlux) / max(frame1.spectralFlux, frame2.spectralFlux, 1.0)
        
        val centroidSimilarity = max(0.0, 1.0 - centroidDiff)
        val rolloffSimilarity = max(0.0, 1.0 - rolloffDiff)
        val fluxSimilarity = max(0.0, 1.0 - fluxDiff)
        
        return (centroidSimilarity + rolloffSimilarity + fluxSimilarity) / 3.0
    }
    
    // Adaptive threshold calculation based on recent similarity scores
    fun calculateAdaptiveThreshold(recentSimilarities: List<Double>): Double {
        if (recentSimilarities.size < 10) return FINGERPRINT_MATCH_THRESHOLD
        
        val sorted = recentSimilarities.sorted()
        val median = sorted[sorted.size / 2]
        val q75 = sorted[(sorted.size * 3) / 4]
        val iqr = q75 - sorted[sorted.size / 4]
        
        // Adaptive threshold based on recent performance
        val adaptiveThreshold = median + (iqr * ADAPTIVE_THRESHOLD_MULTIPLIER)
        
        // Clamp to reasonable range
        return max(0.7, min(0.95, adaptiveThreshold))
    }

    // Generate high-quality fingerprint from multiple audio sources
    fun generateHighQualityFingerprint(context: Context, resIds: List<Int>): EnhancedAudioFingerprint {
        val allFingerprints = mutableListOf<EnhancedFingerprintFrame>()
        
        for (resId in resIds) {
            try {
                val pcm = loadAudioResource(context, resId)
                val preprocessedPcm = preprocessAudioForFingerprinting(pcm, 44100)
                val fingerprint = generateEnhancedAudioFingerprint(preprocessedPcm, 44100)
                allFingerprints.addAll(fingerprint.fingerprints)
            } catch (e: Exception) {
                Log.e("AudioUtils", "Failed to process audio resource $resId: ${e.message}")
            }
        }
        
        // Create composite fingerprint from multiple sources
        return createCompositeFingerprint(allFingerprints, 44100)
    }
    
    // Create composite fingerprint from multiple audio samples
    private fun createCompositeFingerprint(fingerprints: List<EnhancedFingerprintFrame>, sampleRate: Int): EnhancedAudioFingerprint {
        if (fingerprints.isEmpty()) {
            throw IllegalArgumentException("No valid fingerprints to create composite")
        }
        
        // Calculate average fingerprint frame
        val avgFrame = calculateAverageFingerprintFrame(fingerprints)
        
        return EnhancedAudioFingerprint(listOf(avgFrame), sampleRate)
    }
    
    // Calculate average fingerprint frame from multiple samples
    private fun calculateAverageFingerprintFrame(fingerprints: List<EnhancedFingerprintFrame>): EnhancedFingerprintFrame {
        val numFingerprints = fingerprints.size
        
        // Average energy bands
        val avgEnergyBands = DoubleArray(FINGERPRINT_FREQ_BINS)
        for (i in 0 until FINGERPRINT_FREQ_BINS) {
            avgEnergyBands[i] = fingerprints.map { it.energyBands[i] }.average()
        }
        
        // Average MFCC coefficients
        val avgMfcc = DoubleArray(MFCC_COEFFICIENTS)
        for (i in 0 until MFCC_COEFFICIENTS) {
            avgMfcc[i] = fingerprints.map { it.mfcc[i] }.average()
        }
        
        // Average spectral features
        val avgCentroid = fingerprints.map { it.spectralCentroid }.average()
        val avgRolloff = fingerprints.map { it.spectralRolloff }.average()
        val avgFlux = fingerprints.map { it.spectralFlux }.average()
        
        return EnhancedFingerprintFrame(
            energyBands = avgEnergyBands,
            mfcc = avgMfcc,
            spectralCentroid = avgCentroid,
            spectralRolloff = avgRolloff,
            spectralFlux = avgFlux
        )
    }
    
    // Enhanced fingerprint comparison with quality assessment
    fun compareFingerprintsWithQuality(fingerprint1: EnhancedAudioFingerprint, fingerprint2: EnhancedAudioFingerprint): FingerprintComparisonResult {
        val similarity = compareEnhancedFingerprints(fingerprint1, fingerprint2)
        val quality = assessFingerprintQuality(fingerprint1, fingerprint2)
        
        return FingerprintComparisonResult(
            similarity = similarity,
            quality = quality,
            isMatch = similarity >= FINGERPRINT_MATCH_THRESHOLD
        )
    }
    
    // Assess fingerprint comparison quality
    private fun assessFingerprintQuality(fingerprint1: EnhancedAudioFingerprint, fingerprint2: EnhancedAudioFingerprint): Double {
        if (fingerprint1.fingerprints.isEmpty() || fingerprint2.fingerprints.isEmpty()) {
            return 0.0
        }
        
        val frame1 = fingerprint1.fingerprints[0]
        val frame2 = fingerprint2.fingerprints[0]
        
        // Assess spectral feature consistency
        val centroidConsistency = 1.0 - min(abs(frame1.spectralCentroid - frame2.spectralCentroid) / max(frame1.spectralCentroid, frame2.spectralCentroid, 1.0), 1.0)
        val rolloffConsistency = 1.0 - min(abs(frame1.spectralRolloff - frame2.spectralRolloff) / max(frame1.spectralRolloff, frame2.spectralRolloff, 1.0), 1.0)
        
        // Assess energy distribution quality
        val energyQuality = assessEnergyDistributionQuality(frame1.energyBands, frame2.energyBands)
        
        // Combined quality score
        return (centroidConsistency + rolloffConsistency + energyQuality) / 3.0
    }
    
    // Assess energy distribution quality
    private fun assessEnergyDistributionQuality(bands1: DoubleArray, bands2: DoubleArray): Double {
        val energy1 = bands1.sum()
        val energy2 = bands2.sum()
        
        if (energy1 == 0.0 || energy2 == 0.0) return 0.0
        
        // Check if energy is well distributed across frequency bands
        val distribution1 = bands1.map { it / energy1 }
        val distribution2 = bands2.map { it / energy2 }
        
        val distributionSimilarity = distribution1.zip(distribution2).map { (a, b) -> 1.0 - abs(a - b) }.average()
        
        return distributionSimilarity
    }
}

// Data class to hold audio fingerprint
data class AudioFingerprint(
    val fingerprints: List<DoubleArray>, // Time-frequency fingerprint frames
    val sampleRate: Int
) 

// Enhanced data classes for improved fingerprinting
data class EnhancedAudioFingerprint(
    val fingerprints: List<EnhancedFingerprintFrame>,
    val sampleRate: Int
)

data class EnhancedFingerprintFrame(
    val energyBands: DoubleArray,
    val mfcc: DoubleArray,
    val spectralCentroid: Double,
    val spectralRolloff: Double,
    val spectralFlux: Double
)

data class SpectralFeatures(
    val centroid: Double,
    val rolloff: Double,
    val flux: Double
)

// New data class for fingerprint comparison results
data class FingerprintComparisonResult(
    val similarity: Double,
    val quality: Double,
    val isMatch: Boolean
) 