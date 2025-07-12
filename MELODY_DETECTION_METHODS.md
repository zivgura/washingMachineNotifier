# Melody Detection Methods for Washing Machine Notification System

## Overview
This document tracks all melody detection approaches implemented and tested for the washing machine notification system. The goal is to detect when a washing machine completes its cycle by recognizing the completion tune/sound.

## Current Implementation Status

### ✅ **Implemented Methods**

#### 1. **FFT Frequency Analysis** (Traditional)
- **Status**: ✅ Implemented
- **File**: `AudioDetectionService.kt` - `detectWithFFT()`
- **How it works**: 
  - Performs FFT on audio windows (2048 samples)
  - Extracts dominant frequencies using `AudioUtils.getDominantFrequencies()`
  - Compares frequency patterns between reference and live audio
  - Requires frequency matches within tolerance (default: 100Hz)
- **Pros**: 
  - Good for tonal sounds
  - Fast computation
  - Well-understood algorithm
- **Cons**: 
  - Sensitive to background noise
  - Frequency variations can cause false negatives
  - Doesn't account for timing/rhythm
- **Parameters**: 
  - `frequencyTolerance`: 100Hz (adjustable)
  - `minMatchesPerWindow`: 3 (adjustable)
- **Performance**: ⭐⭐⭐ (3/5)

#### 2. **Cross-Correlation Detection**
- **Status**: ✅ Implemented
- **File**: `AudioDetectionService.kt` - `detectWithCrossCorrelation()`
- **How it works**:
  - Creates normalized template from reference sound
  - Computes cross-correlation between live audio and template
  - Detects similar waveforms regardless of exact frequency
  - Uses correlation threshold (0.7)
- **Pros**:
  - Robust against background noise
  - Detects similar waveforms even with frequency shifts
  - Good for pattern matching
- **Cons**:
  - Computationally intensive
  - Sensitive to template quality
  - May miss partial matches
- **Parameters**:
  - Correlation threshold: 0.7
- **Performance**: ⭐⭐⭐⭐ (4/5)

#### 3. **Spectral Profile Analysis**
- **Status**: ✅ Implemented
- **File**: `AudioDetectionService.kt` - `detectWithSpectralSimilarity()`
- **How it works**:
  - Analyzes overall spectral characteristics
  - Compares average spectral energy across frequency bands
  - Uses first quarter of FFT spectrum
  - Calculates similarity based on spectral averages
- **Pros**:
  - Good for detecting similar sound "signatures"
  - Less sensitive to exact frequency matches
  - Fast computation
- **Cons**:
  - May miss fine-grained details
  - Sensitive to overall volume changes
- **Parameters**:
  - Similarity threshold: 0.6
- **Performance**: ⭐⭐⭐ (3/5)

#### 4. **Amplitude Pattern Matching**
- **Status**: ✅ Implemented
- **File**: `AudioDetectionService.kt` - `detectWithAmplitudePattern()`
- **How it works**:
  - Analyzes amplitude envelope and RMS energy patterns
  - Detects similar volume/energy characteristics
  - Compares RMS values between reference and live audio
- **Pros**:
  - Good for detecting similar sound dynamics
  - Works well with rhythmic patterns
  - Simple and fast
- **Cons**:
  - Very sensitive to volume changes
  - May trigger on any loud sound
  - Doesn't distinguish between different sounds at same volume
- **Parameters**:
  - Similarity threshold: 0.5
- **Performance**: ⭐⭐ (2/5)

#### 5. **Hybrid Detection System**
- **Status**: ✅ Implemented (Default)
- **File**: `AudioDetectionService.kt` - Main detection loop
- **How it works**:
  - Combines multiple detection methods
  - Requires at least 2 methods to agree for positive detection
  - Configurable method selection
- **Pros**:
  - Maximum reliability
  - Reduces false positives
  - Configurable approach
- **Cons**:
  - More complex
  - May miss some valid detections
- **Parameters**:
  - Minimum agreeing methods: 2
- **Performance**: ⭐⭐⭐⭐⭐ (5/5)

### 🔄 **Methods Under Development**

#### 6. **Temporal Pattern Recognition**
- **Status**: 🔄 Planned
- **Description**: Analyze timing and rhythm patterns in the melody
- **Implementation**: Not started
- **Expected Pros**: Better for rhythmic completion sounds
- **Expected Cons**: Complex implementation

#### 7. **Machine Learning Approach**
- **Status**: 🔄 Planned
- **Description**: Train a neural network on washing machine completion sounds
- **Implementation**: Not started
- **Expected Pros**: Highly accurate with enough training data
- **Expected Cons**: Requires significant training data and computational resources

### ❌ **Methods Tried and Abandoned**

#### 8. **Simple Amplitude Threshold**
- **Status**: ❌ Abandoned
- **Reason**: Too many false positives from any loud sound
- **Replaced by**: Amplitude Pattern Matching

#### 9. **Single Frequency Detection**
- **Status**: ❌ Abandoned
- **Reason**: Too sensitive to noise and frequency variations
- **Replaced by**: FFT Frequency Analysis with tolerance

## Configuration and Calibration

### Current Default Settings
```kotlin
frequencyTolerance = 100.0        // Hz
minMatchesPerWindow = 3           // frequencies
amplitudeThreshold = 140.0        // amplitude units
detectionMethod = "hybrid"        // detection approach
requiredConsecutiveMatches = 5    // consecutive detections needed
detectionCooldownMs = 10000L      // 10 seconds between detections
```

### Calibration Interface
- **File**: `CalibrationActivity.kt`
- **Layout**: `activity_calibration.xml`
- **Features**:
  - Real-time detection method selection
  - Parameter adjustment with sliders
  - Live audio analysis display
  - Method-specific result display
  - Settings persistence

## Performance Metrics

### Detection Accuracy (Estimated)
1. **Hybrid Detection**: 85-90%
2. **Cross-Correlation**: 80-85%
3. **FFT Frequency Analysis**: 70-75%
4. **Spectral Profile**: 65-70%
5. **Amplitude Pattern**: 50-60%

### Computational Complexity
1. **Amplitude Pattern**: O(n) - Fastest
2. **Spectral Profile**: O(n log n) - Fast
3. **FFT Frequency**: O(n log n) - Fast
4. **Cross-Correlation**: O(n²) - Slow
5. **Hybrid**: O(n²) - Slowest (but most accurate)

## Testing and Validation

### Test Scenarios
1. **Clean Environment**: Washing machine in quiet room
2. **Noisy Environment**: Background noise, TV, conversations
3. **Distance Testing**: Different distances from washing machine
4. **Volume Variations**: Different washing machine volumes
5. **Similar Sounds**: Other electronic beeps and tones

### Current Test Results
- **Best Performance**: Hybrid detection in clean environment
- **Worst Performance**: Single method detection in noisy environment
- **Most Reliable**: Cross-correlation for waveform matching
- **Fastest**: Amplitude pattern for quick screening

## Future Improvements

### Short-term (Next 2 weeks)
1. **Temporal Pattern Recognition**: Implement rhythm analysis
2. **Adaptive Thresholds**: Dynamic parameter adjustment
3. **Noise Filtering**: Pre-processing to reduce background noise
4. **Multiple Reference Sounds**: Support for different washing machine models

### Medium-term (Next month)
1. **Machine Learning Integration**: Basic ML model for sound classification
2. **Cloud-based Training**: Upload sounds for model improvement
3. **Advanced Filtering**: Spectral subtraction and noise reduction
4. **User Feedback Loop**: Learn from false positives/negatives

### Long-term (Next 3 months)
1. **Deep Learning Model**: Convolutional Neural Network for audio classification
2. **Edge Computing**: On-device ML inference
3. **Multi-sensor Fusion**: Combine audio with vibration detection
4. **Smart Calibration**: Automatic parameter optimization

## Troubleshooting Guide

### Common Issues

#### Issue: Too Many False Positives
**Solutions**:
1. Increase `requiredConsecutiveMatches` (default: 5)
2. Use "Hybrid" detection method
3. Increase `amplitudeThreshold`
4. Reduce `frequencyTolerance`

#### Issue: Missing Detections
**Solutions**:
1. Decrease `requiredConsecutiveMatches`
2. Use "Cross-Correlation" method
3. Decrease `amplitudeThreshold`
4. Increase `frequencyTolerance`

#### Issue: High CPU Usage
**Solutions**:
1. Use "FFT Only" or "Spectral" methods
2. Increase detection cooldown
3. Reduce window size (affects accuracy)

### Debug Information
- **Log Tag**: "AudioDetection"
- **Key Log Messages**:
  - `"FFT detection: X/Y frequencies matched"`
  - `"Cross-correlation detection: max correlation = X"`
  - `"Spectral detection: similarity = X"`
  - `"Amplitude detection: similarity = X"`

## Code Structure

### Key Files
- `AudioDetectionService.kt`: Main detection service
- `CalibrationActivity.kt`: Calibration interface
- `AudioUtils.kt`: FFT and audio processing utilities
- `activity_calibration.xml`: Calibration UI layout

### Key Classes and Methods
```kotlin
// Main detection methods
detectWithFFT(window: ShortArray): Boolean
detectWithCrossCorrelation(window: ShortArray): Boolean
detectWithSpectralSimilarity(window: ShortArray): Boolean
detectWithAmplitudePattern(window: ShortArray): Boolean

// Utility methods
computeCrossCorrelation(signal1: List<Double>, signal2: List<Double>): List<Double>
analyzeAmplitudePattern(pcm: ShortArray): List<Double>
analyzeSpectralProfile(pcm: ShortArray): List<Double>
createCrossCorrelationTemplate(pcm: ShortArray): List<Double>
```

## Conclusion

The current hybrid detection system provides the best balance of accuracy and reliability. The combination of multiple detection methods significantly reduces false positives while maintaining high sensitivity to the target washing machine completion sound.

**Recommended Approach**: Use the hybrid detection method with the calibration tool to fine-tune parameters for your specific washing machine and environment.

**Next Priority**: Implement temporal pattern recognition to improve detection of rhythmic completion sounds. 