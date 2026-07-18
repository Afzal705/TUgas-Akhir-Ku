package com.broadcast.adpcm.codec;

/**
 * DPCMCodec.java
 *
 * Implementasi encoder/decoder DPCM (Differential Pulse Code Modulation)
 * dengan step size TETAP (fixed step size), digunakan sebagai metode
 * pembanding (baseline) terhadap ADPCM G.726 pada penelitian ini
 * (lihat Sub-bab 3.3.3 draf skripsi).
 *
 * Berbeda dengan ADPCM yang menyesuaikan step size secara dinamis
 * (lihat G726Codec + G726State), DPCM di sini menggunakan step size
 * konstan delta yang ditetapkan di awal dan tidak berubah selama proses.
 *
 * Persamaan yang diimplementasikan (identik dengan Sub-bab 3.3.3):
 *   d(k)    = x(k) - xHat(k)
 *   I(k)    = round( d(k) / delta )
 *   dq(k)   = I(k) * delta
 *   xHat(k+1) = xHat(k) + dq(k)
 *
 * CATATAN PENTING - keputusan desain yang belum eksplisit di Bab 3:
 * Pada ilustrasi manual Sub-bab 3.3.3, I(k) dibiarkan sebagai bilangan bulat
 * tak terbatas bit (contoh: I(1) = 6). Di implementasi ini, I(k) DIBATASI ke
 * rentang 4-bit signed [-8, 7] agar ukuran payload DPCM sebanding dengan
 * ADPCM G.726 (4 bit/sampel, mode 32 kbps) - supaya perbandingan efisiensi
 * payload di Sub-bab 3.4.3 tetap adil dan setara (bit rate sama, bukan cuma
 * kondisi jaringan yang sama). Saturasi ini justru relevan secara akademis:
 * inilah bentuk konkret dari "slope overload" yang sudah dibahas di Sub-bab
 * 3.3.3 sebagai keterbatasan fundamental DPCM.
 */
public final class DPCMCodec {

    /**
     * Step size tetap (delta), nilai awal sesuai Sub-bab 3.3.3 draf skripsi,
     * dipilih berdasarkan rentang amplitudo dataset ILUSTRATIF (~3.242).
     *
     * TODO PENTING: nilai ini perlu dikalibrasi ulang terhadap rentang
     * amplitudo audio AKTUAL (hasil rekaman live speech 8 kHz), bukan
     * dataset ilustratif Tabel 3.1, sebelum eksperimen Bab 4 dijalankan.
     * Rentang audio nyata bisa jauh berbeda dari dataset 16-sampel di Bab 3.
     */
    public static final int STEP_SIZE = 500;

    // Rentang kode 4-bit signed, menyamakan payload dengan ADPCM G.726 32 kbps
    private static final int CODE_MIN = -8;
    private static final int CODE_MAX = 7;

    /**
     * Mengodekan satu sampel PCM 16-bit menjadi kode DPCM.
     *
     * @param pcm16 sampel PCM 16-bit signed input [-32768, 32767]
     * @param state state DPCM (menyimpan xHat) yang diperbarui setiap sampel
     * @return kode DPCM 4-bit signed, rentang [-8, 7]
     */
    public static int encode(int pcm16, DPCMState state) {
        // d(k) = x(k) - xHat(k)
        int d = pcm16 - state.xHat;

        // I(k) = round( d(k) / delta )
        int I = Math.round((float) d / STEP_SIZE);

        // Saturasi ke rentang 4-bit signed (lihat catatan desain di atas)
        I = Math.max(CODE_MIN, Math.min(CODE_MAX, I));

        // dq(k) = I(k) * delta
        int dq = I * STEP_SIZE;

        // xHat(k+1) = xHat(k) + dq(k)
        state.xHat = state.xHat + dq;

        return I;
    }

    /**
     * Mendekodekan satu kode DPCM kembali menjadi sampel PCM 16-bit hasil
     * rekonstruksi. Logika ini identik dengan jalur feedback pada encode() -
     * WAJIB dipanggil dengan urutan sampel yang sama persis dengan encoder
     * agar state tetap sinkron (belum dipakai sekarang karena client belum
     * dibuat, tapi disiapkan agar simetris dengan encode()).
     *
     * @param code  kode DPCM 4-bit signed, rentang [-8, 7]
     * @param state state DPCM (menyimpan xHat) yang diperbarui setiap sampel
     * @return sampel PCM 16-bit hasil rekonstruksi
     */
    public static int decode(int code, DPCMState state) {
        int dq = code * STEP_SIZE;
        state.xHat = state.xHat + dq;
        return Math.max(-32768, Math.min(32767, state.xHat));
    }

    private DPCMCodec() {}
}