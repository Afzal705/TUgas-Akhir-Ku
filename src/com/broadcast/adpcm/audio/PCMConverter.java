package com.broadcast.adpcm.audio;

/**
 * PCMConverter - Konversi dan normalisasi data audio ke format PCM 16-bit.
 * Mendukung berbagai format input: 8-bit, 16-bit, little/big endian, mono/stereo.
 */
public final class PCMConverter {
    
    private final AudioConfig config;
    
    public PCMConverter(AudioConfig config) {
        this.config = config;
    }
    
    /**
     * Konversi raw audio bytes ke PCM 16-bit samples (mono).
     * Output: array of 16-bit PCM samples (range: -32768 to 32767)
     */
    public short[] toPcm16(byte[] audioData, int offset, int length) {
        if (audioData == null) {
            throw new IllegalArgumentException("Audio data cannot be null");
        }
        
        if (offset < 0 || length < 0 || offset + length > audioData.length) {
            throw new IllegalArgumentException("Invalid offset or length");
        }
        
        int sampleCount = getSampleCount(length);
        short[] pcmSamples = new short[sampleCount];
        
        if (config.getChannels() == 1) {
            // Mono processing
            processMono(audioData, offset, length, pcmSamples);
        } else {
            // Stereo to mono conversion
            processStereoToMono(audioData, offset, length, pcmSamples);
        }
        
        return pcmSamples;
    }
    
    /**
     * Overload - Konversi seluruh array.
     */
    public short[] toPcm16(byte[] audioData) {
        return toPcm16(audioData, 0, audioData.length);
    }
    
    /**
     * Proses mono audio.
     */
    private void processMono(byte[] audioData, int offset, int length, short[] pcmSamples) {
        int bytesPerSample = config.getBitDepth() / 8;
        
        for (int i = 0; i < pcmSamples.length; i++) {
            int sampleOffset = offset + (i * bytesPerSample);
            
            if (config.getBitDepth() == 16) {
                // 16-bit PCM (little endian by default)
                pcmSamples[i] = (short) ((audioData[sampleOffset + 1] << 8) | 
                                        (audioData[sampleOffset] & 0xFF));
            } else if (config.getBitDepth() == 8) {
                // 8-bit PCM (unsigned to signed conversion)
                int unsigned = audioData[sampleOffset] & 0xFF;
                pcmSamples[i] = (short) ((unsigned - 128) << 8);
            } else if (config.getBitDepth() == 24) {
                // 24-bit PCM to 16-bit (truncate LSB)
                int sample24 = ((audioData[sampleOffset + 2] & 0xFF) << 16) |
                              ((audioData[sampleOffset + 1] & 0xFF) << 8) |
                              (audioData[sampleOffset] & 0xFF);
                pcmSamples[i] = (short) (sample24 >> 8);
            } else if (config.getBitDepth() == 32) {
                // 32-bit PCM to 16-bit
                int sample32 = ((audioData[sampleOffset + 3] & 0xFF) << 24) |
                              ((audioData[sampleOffset + 2] & 0xFF) << 16) |
                              ((audioData[sampleOffset + 1] & 0xFF) << 8) |
                              (audioData[sampleOffset] & 0xFF);
                pcmSamples[i] = (short) (sample32 >> 16);
            }
        }
    }
    
    /**
     * Proses stereo audio ke mono dengan averaging.
     */
    private void processStereoToMono(byte[] audioData, int offset, int length, short[] pcmSamples) {
        int bytesPerSample = config.getBitDepth() / 8;
        int frameSize = bytesPerSample * 2; // Left + Right
        
        for (int i = 0; i < pcmSamples.length; i++) {
            int leftOffset = offset + (i * frameSize);
            int rightOffset = leftOffset + bytesPerSample;
            
            int left = 0, right = 0;
            
            if (config.getBitDepth() == 16) {
                left = (short) ((audioData[leftOffset + 1] << 8) | (audioData[leftOffset] & 0xFF));
                right = (short) ((audioData[rightOffset + 1] << 8) | (audioData[rightOffset] & 0xFF));
            } else if (config.getBitDepth() == 8) {
                left = ((audioData[leftOffset] & 0xFF) - 128) << 8;
                right = ((audioData[rightOffset] & 0xFF) - 128) << 8;
            } else if (config.getBitDepth() == 24) {
                left = ((audioData[leftOffset + 2] & 0xFF) << 16) |
                       ((audioData[leftOffset + 1] & 0xFF) << 8) |
                       (audioData[leftOffset] & 0xFF);
                right = ((audioData[rightOffset + 2] & 0xFF) << 16) |
                        ((audioData[rightOffset + 1] & 0xFF) << 8) |
                        (audioData[rightOffset] & 0xFF);
                left >>= 8;
                right >>= 8;
            } else { // 32-bit
                left = ((audioData[leftOffset + 3] & 0xFF) << 24) |
                       ((audioData[leftOffset + 2] & 0xFF) << 16) |
                       ((audioData[leftOffset + 1] & 0xFF) << 8) |
                       (audioData[leftOffset] & 0xFF);
                right = ((audioData[rightOffset + 3] & 0xFF) << 24) |
                        ((audioData[rightOffset + 2] & 0xFF) << 16) |
                        ((audioData[rightOffset + 1] & 0xFF) << 8) |
                        (audioData[rightOffset] & 0xFF);
                left >>= 16;
                right >>= 16;
            }
            
            // Average left and right channels
            int mono = (left + right) / 2;
            pcmSamples[i] = (short) Math.max(-32768, Math.min(32767, mono));
        }
    }
    
    /**
     * Normalisasi PCM samples ke range optimal untuk G.726.
     * G.726 bekerja optimal dengan range penuh 16-bit (-32768 to 32767).
     */
    public short[] normalize(short[] samples) {
        if (samples == null || samples.length == 0) {
            return samples;
        }
        
        // Find peak amplitude
        int peak = 0;
        for (short sample : samples) {
            int abs = Math.abs(sample);
            if (abs > peak) {
                peak = abs;
            }
        }
        
        // If peak is already near maximum, no normalization needed
        if (peak >= 30000 || peak == 0) {
            return samples;
        }
        
        // Calculate gain factor (avoid division by zero)
        double gain = 32767.0 / peak;
        
        // Apply gain
        short[] normalized = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            int normalizedSample = (int) (samples[i] * gain);
            normalized[i] = (short) Math.max(-32768, Math.min(32767, normalizedSample));
        }
        
        return normalized;
    }
    
    /**
     * Apply soft clipping untuk menghindari distorsi pada peak.
     */
    public short[] applySoftClipping(short[] samples) {
        short[] clipped = new short[samples.length];
        
        for (int i = 0; i < samples.length; i++) {
            double x = samples[i] / 32768.0;
            // Soft clip: y = x - (x^3)/3 for |x| < 1
            double y = x - (Math.pow(x, 3) / 3.0);
            clipped[i] = (short) (y * 32767);
        }
        
        return clipped;
    }
    
    /**
     * Resample sederhana (linear interpolation) untuk mengubah sample rate.
     * Catatan: Ini adalah resampling dasar, untuk produksi gunakan library yang lebih baik.
     */
    public short[] resample(short[] input, int fromRate, int toRate) {
        if (fromRate == toRate) {
            return input;
        }
        
        double ratio = (double) toRate / fromRate;
        int outputLength = (int) (input.length * ratio);
        short[] output = new short[outputLength];
        
        for (int i = 0; i < outputLength; i++) {
            double srcPos = i / ratio;
            int srcIndex = (int) srcPos;
            
            if (srcIndex >= input.length - 1) {
                output[i] = input[input.length - 1];
                continue;
            }
            
            double fraction = srcPos - srcIndex;
            int sample1 = input[srcIndex];
            int sample2 = input[srcIndex + 1];
            output[i] = (short) (sample1 + (sample2 - sample1) * fraction);
        }
        
        return output;
    }
    
    /**
     * Hitung jumlah samples yang akan dihasilkan dari byte array.
     */
    private int getSampleCount(int byteCount) {
        int bytesPerSample = config.getBitDepth() / 8;
        int samplesPerChannel = byteCount / (bytesPerSample * config.getChannels());
        
        if (config.getChannels() == 1) {
            return samplesPerChannel;
        } else {
            return samplesPerChannel; // Stereo to mono = samples per channel
        }
    }
    
    /**
     * Deteksi format audio dari raw bytes (simple heuristic).
     */
    public static String detectAudioFormat(byte[] audioData) {
        if (audioData == null || audioData.length < 4) {
            return "unknown";
        }
        
        // Check for WAV header
        if (audioData[0] == 'R' && audioData[1] == 'I' && 
            audioData[2] == 'F' && audioData[3] == 'F') {
            return "wav";
        }
        
        // Check for raw PCM pattern (heuristic)
        int zeroCount = 0;
        for (int i = 0; i < Math.min(100, audioData.length); i++) {
            if (audioData[i] == 0) zeroCount++;
        }
        
        if (zeroCount > 30) {
            return "pcm_likely";
        }
        
        return "raw";
    }
    
    /**
     * Print debug info tentang samples.
     */
    public static void printSampleInfo(short[] samples, String label) {
        if (samples == null || samples.length == 0) {
            System.out.println(label + ": empty");
            return;
        }
        
        int min = samples[0];
        int max = samples[0];
        long sum = 0;
        
        for (short s : samples) {
            if (s < min) min = s;
            if (s > max) max = s;
            sum += s;
        }
        
        double avg = (double) sum / samples.length;
        double rms = Math.sqrt((double) sum * sum / samples.length);
        
        System.out.printf("%s: samples=%d, min=%d, max=%d, avg=%.2f, rms=%.2f%n",
                         label, samples.length, min, max, avg, rms);
    }
}