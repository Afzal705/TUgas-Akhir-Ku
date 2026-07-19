package com.broadcast.adpcm.util;

/**
 * ADPCMUtils - Utility perhitungan terkait ADPCM (bit ops, scaling, dll).
 * Menyediakan fungsi-fungsi bantu untuk manipulasi bit ADPCM 4-bit.
 */
public final class ADPCMUtils {

    // Bit masks for ADPCM operations
    public static final int ADPCM_BITS_PER_SAMPLE  = 4;
    public static final int ADPCM_SAMPLES_PER_BYTE = 2;
    public static final int ADPCM_MAX_VALUE         = 0x0F;
    public static final int ADPCM_MIN_VALUE         = 0x00;

    // PCM range constants
    public static final int PCM_MAX_16BIT = 32767;
    public static final int PCM_MIN_16BIT = -32768;
    public static final int PCM_RANGE     = 65536;

    // Scalar constants for fixed-point math
    public static final int FIXED_POINT_SCALE = 16384; // 2^14
    public static final int FIXED_POINT_MASK  = 0x3FFF;

    private ADPCMUtils() {
        // Utility class - no instantiation
    }

    // =========================================================================
    // PACK / UNPACK
    // =========================================================================

    /**
     * Pack two 4-bit ADPCM samples into one byte.
     * @param highNibble First sample (placed in high nibble)
     * @param lowNibble  Second sample (placed in low nibble)
     * @return Packed byte
     */
    public static byte packSamples(int highNibble, int lowNibble) {
        return (byte) (((highNibble & 0x0F) << 4) | (lowNibble & 0x0F));
    }

    /**
     * Extract high nibble (first sample) from packed byte.
     * @param packed Byte containing two ADPCM samples
     * @return High nibble (first sample)
     */
    public static int unpackHighNibble(byte packed) {
        return (packed >> 4) & 0x0F;
    }

    /**
     * Extract low nibble (second sample) from packed byte.
     * @param packed Byte containing two ADPCM samples
     * @return Low nibble (second sample)
     */
    public static int unpackLowNibble(byte packed) {
        return packed & 0x0F;
    }

    /**
     * Unpack an array of ADPCM bytes into integer samples.
     * @param packed Array of packed ADPCM bytes
     * @return Array of ADPCM samples (each 0-15)
     */
    public static int[] unpackBytes(byte[] packed) {
        if (packed == null) return new int[0];

        int[] samples = new int[packed.length * ADPCM_SAMPLES_PER_BYTE];
        for (int i = 0; i < packed.length; i++) {
            samples[i * 2]     = unpackHighNibble(packed[i]);
            samples[i * 2 + 1] = unpackLowNibble(packed[i]);
        }
        return samples;
    }

    /**
     * Pack ADPCM samples into bytes.
     * @param samples Array of ADPCM samples (each 0-15)
     * @return Array of packed bytes
     */
    public static byte[] packSamples(int[] samples) {
        if (samples == null) return new byte[0];

        int byteCount  = (samples.length + 1) / ADPCM_SAMPLES_PER_BYTE;
        byte[] packed  = new byte[byteCount];

        for (int i = 0; i < samples.length; i += 2) {
            int high    = samples[i] & 0x0F;
            int low     = (i + 1 < samples.length) ? (samples[i + 1] & 0x0F) : 0;
            packed[i/2] = (byte) ((high << 4) | low);
        }
        return packed;
    }

    // =========================================================================
    // ADPCM — INVERSE QUANTIZER (Logika No.2 — DIPERBAIKI)
    // =========================================================================

    /**
     * Konversi satu kode ADPCM 4-bit ke nilai PCM (aproksimasi inverse quantizer).
     *
     * PERBAIKAN:
     *   Tabel sebelumnya {-56,-48,-40,-32,-24,-16,-8,0,...} SALAH.
     *   Nilai yang benar adalah DQLN_TABLE standar ITU-T G.726 Table 4:
     *   {-2048, -4, 135, 213, 273, 323, 373, 425, 425, 373, 323, 273, 213, 135, -4, -2048}
     *
     *   Tabel ini merepresentasikan log2(magnitude selisih) dalam format Q7,
     *   bukan nilai linear langsung.
     *
     * Cara kerja (sesuai Blok 4 ITU-T G.726):
     *   1. De-normalisasi : dql = DQLN[I] + (stepSize >> 2)
     *   2. Antilog        : dq  = (128 + (dql & 127)) << 7 >> (14 - (dql>>7 & 15))
     *   3. Tanda          : terapkan dari bit-3 kode I
     *
     * @param adpcmSample kode ADPCM 4-bit (0-15)
     * @param stepSize    step size saat ini (yu dari G726State)
     * @return            aproksimasi nilai PCM hasil rekonstruksi
     */
    public static int adpcmToPcm(int adpcmSample, int stepSize) {
        // ✅ DIPERBAIKI: DQLN_TABLE standar ITU-T G.726 Table 4
        // Referensi: Table 4/G.726 — nilai log domain output inverse quantizer
        int[] DQLN_TABLE = {
            -2048, -4, 135, 213, 273, 323, 373, 425,
              425, 373, 323, 273, 213, 135,  -4, -2048
        };

        // Bit-3 dari I menentukan tanda (0=positif, 1=negatif)
        int sign = (adpcmSample & 8) != 0 ? -1 : 1;

        // De-normalisasi: dql = DQLN[I] + (stepSize >> 2)
        int dql = DQLN_TABLE[adpcmSample] + (stepSize >> 2);

        // Jika log negatif, magnitude sangat kecil → kembalikan 0
        if (dql < 0) return 0;

        // Antilog: konversi domain log → linear
        int dex = (dql >> 7) & 0x0F;       // integer exponent
        int dqt = 128 + (dql & 0x7F);      // normalized mantissa [128-255]
        int dq  = (dqt << 7) >> (14 - dex); // magnitude linear

        return sign * dq;
    }

    // =========================================================================
    // ADPCM — SCALE FACTOR ADAPTATION (Logika No.3 — DIPERBAIKI)
    // =========================================================================

    /**
     * Hitung step size baru berdasarkan kode ADPCM dan step size saat ini.
     *
     * PERBAIKAN:
     *   Tabel sebelumnya {0,1,2,3,4,5,6,7,7,6,5,4,3,2,1,0} SALAH.
     *   Nilai yang benar adalah WI_TABLE standar ITU-T G.726 Table 3:
     *   {-12, 18, 41, 64, 112, 198, 355, 1122, 1122, 355, 198, 112, 64, 41, 18, -12}
     *
     *   Interpretasi nilai WI per kode I:
     *     I=0, I=15  → -12   (granular terkecil, step size TURUN)
     *     I=1, I=14  →  18
     *     I=2, I=13  →  41
     *     I=3, I=12  →  64
     *     I=4, I=11  → 112
     *     I=5, I=10  → 198
     *     I=6, I=9   → 355
     *     I=7, I=8   → 1122  (overload terbesar, step size NAIK agresif)
     *
     * Formula (sesuai Blok 7 ITU-T G.726):
     *   yu_baru = yu_lama + (WI[I] - yu_lama) >> 5
     *   (moving average dengan konstanta waktu 32 sampel)
     *
     * @param adpcmSample kode ADPCM 4-bit (0-15)
     * @param currentStep step size saat ini (yu)
     * @return            step size baru setelah adaptasi
     */
    public static int adaptStepSize(int adpcmSample, int currentStep) {
        // ✅ DIPERBAIKI: WI_TABLE standar ITU-T G.726 Table 3
        // Referensi: Table 3/G.726 — scale factor adaptation weights
        int[] WI_TABLE = {
             -12,   18,  41,  64, 112, 198,  355, 1122,
            1122,  355, 198, 112,  64,  41,   18,  -12
        };

        int wi      = WI_TABLE[adpcmSample];
        int newStep = currentStep + ((wi - currentStep) >> 5);

        // Clamp ke range valid sesuai standar G.726
        return Math.max(544, Math.min(5120, newStep));
    }

    // =========================================================================
    // KONVERSI LOGARITMIK
    // =========================================================================

    /**
     * Konversi nilai PCM linear ke format logaritmik internal G.726.
     * @param pcmValue nilai PCM 16-bit
     * @return representasi log (format floating-point internal Sun g72x)
     */
    public static int linearToLog(int pcmValue) {
        int magnitude = Math.abs(pcmValue);
        int exponent  = 0;

        if (magnitude > 0) {
            while (magnitude > 0) {
                magnitude >>= 1;
                exponent++;
            }
            exponent--;
        }

        return ((pcmValue < 0) ? 0x400 : 0)
             | (exponent << 6)
             | ((Math.abs(pcmValue) >> Math.max(0, exponent - 1)) & 0x1F);
    }

    /**
     * Konversi format logaritmik internal G.726 kembali ke nilai PCM linear.
     * @param logValue representasi log (format floating-point internal)
     * @return nilai PCM linear
     */
    public static int logToLinear(int logValue) {
        int sign     = (logValue & 0x400) != 0 ? -1 : 1;
        int exponent = (logValue >> 6) & 0x0F;
        int mantissa = logValue & 0x3F;

        int linear = (exponent == 0) ? mantissa
                                     : ((0x20 | mantissa) << (exponent - 1));
        return sign * linear;
    }

    // =========================================================================
    // METRIK KUALITAS AUDIO
    // =========================================================================

    /**
     * Hitung Signal-to-Noise Ratio (SNR) dalam dB.
     * @param original sinyal PCM asli
     * @param decoded  sinyal PCM hasil decode
     * @return SNR dalam desibel
     */
    public static double calculateSNR(short[] original, short[] decoded) {
        if (original == null || decoded == null || original.length != decoded.length) return 0;

        double signalPower = 0;
        double noisePower  = 0;

        for (int i = 0; i < original.length; i++) {
            signalPower += (double) original[i] * original[i];
            double error = original[i] - decoded[i];
            noisePower  += error * error;
        }

        if (noisePower == 0) return Double.POSITIVE_INFINITY;
        return 10 * Math.log10(signalPower / noisePower);
    }

    /**
     * Hitung Mean Squared Error (MSE).
     * @param original sinyal PCM asli
     * @param decoded  sinyal PCM hasil decode
     * @return nilai MSE
     */
    public static double calculateMSE(short[] original, short[] decoded) {
        if (original == null || decoded == null || original.length != decoded.length) return 0;

        double mse = 0;
        for (int i = 0; i < original.length; i++) {
            double error = original[i] - decoded[i];
            mse += error * error;
        }
        return mse / original.length;
    }

    // =========================================================================
    // PEMROSESAN SINYAL
    // =========================================================================

    /**
     * Terapkan Hamming window pada sampel PCM (dimodifikasi in-place).
     * @param samples sampel input
     */
    public static void applyHammingWindow(short[] samples) {
        for (int i = 0; i < samples.length; i++) {
            double hamming = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (samples.length - 1));
            samples[i] = (short) (samples[i] * hamming);
        }
    }

    /**
     * Hitung rata-rata amplitudo absolut sampel PCM.
     * @param samples sampel PCM
     * @return rata-rata amplitudo absolut
     */
    public static double calculateAverageAmplitude(short[] samples) {
        if (samples == null || samples.length == 0) return 0;

        long sum = 0;
        for (short s : samples) sum += Math.abs(s);
        return (double) sum / samples.length;
    }

    /**
     * Hitung amplitudo puncak sampel PCM.
     * @param samples sampel PCM
     * @return amplitudo puncak
     */
    public static int calculatePeakAmplitude(short[] samples) {
        if (samples == null || samples.length == 0) return 0;

        int peak = 0;
        for (short s : samples) {
            int abs = Math.abs(s);
            if (abs > peak) peak = abs;
        }
        return peak;
    }

    /**
     * Deteksi frame senyap berdasarkan threshold dB.
     * @param samples     sampel PCM
     * @param thresholddB batas kebisingan dalam dB (misal: -40 dB)
     * @return true jika frame dianggap senyap
     */
    public static boolean isSilentFrame(short[] samples, double thresholddB) {
        double avgAmplitude = calculateAverageAmplitude(samples);
        double db           = 20 * Math.log10((avgAmplitude + 1) / 32768.0);
        return db < thresholddB;
    }

    /**
     * Terapkan gain pada sampel PCM.
     * @param samples sampel input
     * @param gain    faktor gain (misal: 1.2 untuk +20%)
     * @return array baru dengan gain diterapkan
     */
    public static short[] applyGain(short[] samples, double gain) {
        if (samples == null) return null;

        short[] result = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            int amplified = (int) (samples[i] * gain);
            result[i] = (short) Math.max(PCM_MIN_16BIT, Math.min(PCM_MAX_16BIT, amplified));
        }
        return result;
    }

    // =========================================================================
    // STEREO UTILITY
    // =========================================================================

    /**
     * Interleave dua channel mono menjadi stereo.
     * @param left  sampel channel kiri
     * @param right sampel channel kanan
     * @return sampel stereo yang sudah di-interleave
     */
    public static short[] interleaveStereo(short[] left, short[] right) {
        if (left == null || right == null) return null;

        int minLength   = Math.min(left.length, right.length);
        short[] stereo  = new short[minLength * 2];

        for (int i = 0; i < minLength; i++) {
            stereo[i * 2]     = left[i];
            stereo[i * 2 + 1] = right[i];
        }
        return stereo;
    }

    /**
     * Pisahkan sampel stereo menjadi dua array mono.
     * @param stereo sampel stereo yang sudah di-interleave
     * @return array dua elemen: [left, right]
     */
    public static short[][] deinterleaveStereo(short[] stereo) {
        if (stereo == null) return null;

        int sampleCount = stereo.length / 2;
        short[] left    = new short[sampleCount];
        short[] right   = new short[sampleCount];

        for (int i = 0; i < sampleCount; i++) {
            left[i]  = stereo[i * 2];
            right[i] = stereo[i * 2 + 1];
        }
        return new short[][] { left, right };
    }

    // =========================================================================
    // DEBUGGING
    // =========================================================================

    /**
     * Konversi byte array ke string hex untuk debugging.
     * @param data      byte array input
     * @param maxLength jumlah byte maksimum yang ditampilkan
     * @return representasi hex
     */
    public static String toHexString(byte[] data, int maxLength) {
        if (data == null) return "null";

        int length     = Math.min(data.length, maxLength);
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < length; i++) {
            if      (i > 0 && i % 16 == 0) sb.append('\n');
            else if (i > 0 && i %  8 == 0) sb.append(' ');
            sb.append(String.format("%02X ", data[i] & 0xFF));
        }

        if (data.length > maxLength) {
            sb.append("... (").append(data.length).append(" bytes total)");
        }
        return sb.toString();
    }
}