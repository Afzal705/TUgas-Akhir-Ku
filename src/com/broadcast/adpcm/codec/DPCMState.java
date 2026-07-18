package com.broadcast.adpcm.codec;

/**
 * DPCMState.java
 *
 * Menyimpan state internal algoritma DPCM (Differential Pulse Code Modulation)
 * sebagai metode pembanding (baseline) terhadap ADPCM G.726, sesuai Sub-bab 3.3.3
 * draf skripsi.
 *
 * Berbeda dengan G726State, DPCM tidak memiliki adaptive predictor maupun
 * adaptive quantizer - hanya menyimpan satu nilai prediksi (xHat) yang
 * diperbarui secara linear setiap sampel. Step size (delta) bersifat TETAP
 * sehingga tidak perlu disimpan di state ini (lihat DPCMCodec.STEP_SIZE).
 *
 * State ini harus IDENTIK antara encoder dan decoder sepanjang sesi
 * (prinsip implicit synchronization), agar hasil rekonstruksi valid.
 */
public final class DPCMState {

    /**
     * xHat - nilai prediksi sampel berikutnya, x-hat(k).
     * Kondisi awal: x-hat(1) = 0, sesuai Sub-bab 3.3.3.
     */
    public int xHat;

    public DPCMState() {
        this.xHat = 0;
    }

    /** Reset state ke kondisi awal (xHat = 0). Berguna untuk mulai ulang per sesi. */
    public void reset() {
        this.xHat = 0;
    }
}