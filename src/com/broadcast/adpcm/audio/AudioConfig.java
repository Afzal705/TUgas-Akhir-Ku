package com.broadcast.adpcm.audio;

/**
 * AudioConfig - Konfigurasi parameter audio untuk sistem broadcast.
 * Memastikan format audio sesuai dengan requirement G.726 32 kbps.
 */
public final class AudioConfig {
    
    // Standard audio parameters untuk G.726 32 kbps
    public static final int DEFAULT_SAMPLE_RATE = 8000;      // 8 kHz (standard telephony)
    public static final int DEFAULT_BIT_DEPTH = 16;           // 16-bit PCM
    public static final int DEFAULT_CHANNELS = 1;              // Mono
    public static final int DEFAULT_FRAME_MS = 10;             // 10ms per frame
    
    // G.726 32 kbps specific
    public static final int ADPCM_BITS_PER_SAMPLE = 4;         // 4-bit ADPCM
    public static final int SAMPLES_PER_FRAME = 80;            // 8000 * 0.01 = 80 samples
    public static final int ADPCM_BYTES_PER_FRAME = 40;        // 80 * 4 / 8 = 40 bytes
    
    // Instance variables
    private final int sampleRate;
    private final int bitDepth;
    private final int channels;
    private final int frameSizeMs;
    private final int samplesPerFrame;
    private final int bytesPerFrame;
    
    /**
     * Constructor dengan parameter custom.
     */
    public AudioConfig(int sampleRate, int bitDepth, int channels, int frameSizeMs) {
        validateParameters(sampleRate, bitDepth, channels, frameSizeMs);
        
        this.sampleRate = sampleRate;
        this.bitDepth = bitDepth;
        this.channels = channels;
        this.frameSizeMs = frameSizeMs;
        this.samplesPerFrame = (sampleRate * frameSizeMs) / 1000;
        this.bytesPerFrame = (samplesPerFrame * bitDepth * channels) / 8;
    }
    
    /**
     * Factory method - Default configuration untuk G.726 32 kbps.
     */
    public static AudioConfig getDefaultConfig() {
        return new AudioConfig(DEFAULT_SAMPLE_RATE, DEFAULT_BIT_DEPTH, 
                              DEFAULT_CHANNELS, DEFAULT_FRAME_MS);
    }
    
    /**
     * Factory method - Custom sample rate.
     */
    public static AudioConfig createWithSampleRate(int sampleRate) {
        return new AudioConfig(sampleRate, DEFAULT_BIT_DEPTH, 
                              DEFAULT_CHANNELS, DEFAULT_FRAME_MS);
    }
    
    /**
     * Validasi parameter audio.
     */
    private void validateParameters(int sampleRate, int bitDepth, int channels, int frameSizeMs) {
        if (sampleRate <= 0 || sampleRate > 48000) {
            throw new IllegalArgumentException("Invalid sample rate: " + sampleRate);
        }
        
        if (bitDepth != 8 && bitDepth != 16 && bitDepth != 24 && bitDepth != 32) {
            throw new IllegalArgumentException("Invalid bit depth: " + bitDepth);
        }
        
        if (channels <= 0 || channels > 2) {
            throw new IllegalArgumentException("Invalid channels: " + channels);
        }
        
        if (frameSizeMs <= 0 || frameSizeMs > 100) {
            throw new IllegalArgumentException("Invalid frame size: " + frameSizeMs + "ms");
        }
        
        // Check that samples per frame is integer
        int samplesPerFrame = (sampleRate * frameSizeMs) / 1000;
        if (samplesPerFrame * 1000 != sampleRate * frameSizeMs) {
            throw new IllegalArgumentException(
                String.format("Frame size %dms is not integer multiple of sample rate %dHz", 
                             frameSizeMs, sampleRate)
            );
        }
        
        // G.726 specific requirement
        if (sampleRate != DEFAULT_SAMPLE_RATE) {
            System.err.println("Warning: G.726 is optimized for 8kHz sample rate. Current: " + sampleRate + "Hz");
        }
        
        if (channels != 1) {
            System.err.println("Warning: Multi-channel not optimized for G.726. Current channels: " + channels);
        }
    }
    
    /**
     * Hitung jumlah sample untuk durasi tertentu.
     */
    public int getSamplesForDuration(int durationMs) {
        return (sampleRate * durationMs) / 1000;
    }
    
    /**
     * Hitung jumlah frame untuk durasi tertentu.
     */
    public int getFramesForDuration(int durationMs) {
        return (durationMs + frameSizeMs - 1) / frameSizeMs;
    }
    
    /**
     * Konversi sample count ke byte count (PCM).
     */
    public int samplesToBytes(int sampleCount) {
        return sampleCount * channels * (bitDepth / 8);
    }
    
    /**
     * Konversi byte count ke sample count (PCM).
     */
    public int bytesToSamples(int byteCount) {
        return byteCount / (channels * (bitDepth / 8));
    }
    
    // Getters
    public int getSampleRate() { return sampleRate; }
    public int getBitDepth() { return bitDepth; }
    public int getChannels() { return channels; }
    public int getFrameSizeMs() { return frameSizeMs; }
    public int getSamplesPerFrame() { return samplesPerFrame; }
    public int getBytesPerFrame() { return bytesPerFrame; }
    public int getPcmFrameBytes() { return bytesPerFrame; }
    
    public int getAdpcmBytesPerFrame() {
        return (samplesPerFrame * ADPCM_BITS_PER_SAMPLE) / 8;
    }
    
    public int getAdpcmBitRate() {
        return sampleRate * ADPCM_BITS_PER_SAMPLE;
    }
    
    @Override
    public String toString() {
        return String.format(
            "AudioConfig[sampleRate=%dHz, bitDepth=%dbit, channels=%d, frameSize=%dms, " +
            "samples/frame=%d, PCM=%dbytes, ADPCM=%dbytes, bitrate=%dkbps]",
            sampleRate, bitDepth, channels, frameSizeMs, samplesPerFrame, 
            bytesPerFrame, getAdpcmBytesPerFrame(), getAdpcmBitRate() / 1000
        );
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof AudioConfig)) return false;
        AudioConfig other = (AudioConfig) obj;
        return sampleRate == other.sampleRate &&
               bitDepth == other.bitDepth &&
               channels == other.channels &&
               frameSizeMs == other.frameSizeMs;
    }
    
    @Override
    public int hashCode() {
        return sampleRate * 31 + bitDepth * 17 + channels * 13 + frameSizeMs;
    }
}