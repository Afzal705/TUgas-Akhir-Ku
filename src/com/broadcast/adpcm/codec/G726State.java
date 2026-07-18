package com.broadcast.adpcm.codec;

/**
 * G726State.java
 *
 * Menyimpan seluruh variabel state internal algoritma ADPCM G.726.
 *
 * State ini harus IDENTIK antara encoder dan decoder sepanjang sesi
 * (prinsip implicit synchronization ITU-T G.726). Setiap instance
 * mewakili satu saluran audio independen.
 *
 * Format internal dq[] dan sr[] (format floating-point Sun g72x):
 *   bit [5:0]  = mantissa (range 32-63, nilai 32 = representasi nol)
 *   bit [9:6]  = eksponent (0-15)
 *   bit [10]   = tanda (0=positif, 1=negatif)
 *
 * PENTING: nilai awal dq[] dan sr[] adalah 32, BUKAN 0.
 *   floatConv(0) = 32 dalam format internal ini.
 *   Jika diisi 0, fmult() akan menghasilkan nilai salah
 *   karena mantissa 0 tidak valid dalam format ini.
 */
public final class G726State {

    // =========================================================
    // Blok 7 — Scale Factor Adaptation
    // =========================================================

    /**
     * yu — Fast quantizer scale factor.
     * Diperbarui setiap sampel menggunakan WI_TABLE.
     * Reaktif terhadap perubahan amplitudo sinyal.
     * Range: 544 – 5120. Nilai awal: 544 (YU_MIN).
     */
    public int yu;

    /**
     * yl — Slow quantizer scale factor.
     * Konvergen lambat menuju 64 × yu.
     * Nilai awal: 34816 (= 544 × 64 = YU_MIN × 64).
     * Disimpan sebagai long karena bisa mencapai nilai besar.
     */
    public long yl;

    // =========================================================
    // Blok 8 — Adaptation Speed Control
    // =========================================================

    /**
     * ap — Adaptation speed parameter.
     * 0 = mode lambat (tone/data), 256 = mode cepat (speech).
     * Nilai awal: 0.
     */
    public int ap;

    /**
     * dms — Short-term average of F(I).
     * Rata-rata jangka pendek dari FI_TABLE[I].
     * Nilai awal: 0.
     */
    public int dms;

    /**
     * dml — Long-term average of F(I).
     * Rata-rata jangka panjang dari FI_TABLE[I].
     * Nilai awal: 0.
     */
    public int dml;

    // =========================================================
    // Blok 9 — Tone and Transition Detector
    // =========================================================

    /**
     * td — Tone detect flag.
     * 1 = nada terdeteksi (paksa mode lambat), 0 = speech normal.
     * Nilai awal: 0.
     */
    public int td;

    // =========================================================
    // Blok 6 — Adaptive Predictor
    // =========================================================

    /**
     * a[] — Koefisien filter poles (IIR / recursive).
     * a[0] = a1, a[1] = a2. Format Q15.
     * Range: a[0] dalam [-12288, 12288], a[1] dalam [-32576, 32576].
     * Nilai awal: {0, 0}.
     */
    public int[] a;

    /**
     * b[] — Koefisien filter zeros (FIR / non-recursive).
     * b[0]..b[5] = b1..b6. Format Q15.
     * Range: [-32768, 32767] masing-masing.
     * Nilai awal: {0, 0, 0, 0, 0, 0}.
     */
    public int[] b;

    /**
     * sr[] — Riwayat sinyal rekonstruksi dalam format internal floating-point.
     * sr[0] = sr(k-1), sr[1] = sr(k-2).
     *
     * *** NILAI AWAL: 32 (BUKAN 0) ***
     * 32 = floatConv(0) = representasi nol dalam format internal.
     * Nilai 0 akan menyebabkan fmult() menghasilkan hasil salah.
     */
    public int[] sr;

    /**
     * dq[] — Riwayat selisih terkuantisasi dalam format internal floating-point.
     * dq[0]=dq(k-1), dq[1]=dq(k-2), ..., dq[5]=dq(k-6).
     *
     * *** NILAI AWAL: 32 (BUKAN 0) ***
     * Alasan sama dengan sr[].
     */
    public int[] dq;

    /**
     * pk[] — Riwayat tanda sinyal rekonstruksi.
     * pk[0] = tanda sr(k-1), pk[1] = tanda sr(k-2).
     * Nilai: 0 atau 1. Nilai awal: {0, 0}.
     */
    public int[] pk;

    // =========================================================
    // Constructor
    // =========================================================

    /**
     * Inisialisasi state dengan nilai awal standar ITU-T G.726.
     */
    public G726State() {
        reset();
    }

    // =========================================================
    // Public Methods
    // =========================================================

    /**
     * Reset semua state ke kondisi awal standar G.726.
     * Wajib dipanggil saat memulai sesi encoding baru.
     */
    public void reset() {
        // Scale factor adaptation
        this.yu  = G726Tables.YU_MIN;   // 544
        this.yl  = G726Tables.YL_INIT;  // 34816

        // Speed control
        this.ap  = 0;
        this.dms = 0;
        this.dml = 0;

        // Tone detector
        this.td  = 0;

        // Predictor coefficients — poles
        this.a = new int[]{0, 0};

        // Predictor coefficients — zeros
        this.b = new int[]{0, 0, 0, 0, 0, 0};

        // Polarity history
        this.pk = new int[]{0, 0};

        // Reconstructed signal history
        // NILAI AWAL 32, BUKAN 0 — floatConv(0) = 32
        this.sr = new int[]{32, 32};

        // Quantized difference history
        // NILAI AWAL 32, BUKAN 0 — floatConv(0) = 32
        this.dq = new int[]{32, 32, 32, 32, 32, 32};
    }

    /**
     * Copy state dari instance lain.
     * Berguna untuk keperluan diagnostik atau backup state.
     *
     * @param source state sumber yang akan disalin
     */
    public void copyFrom(G726State source) {
        this.yu  = source.yu;
        this.yl  = source.yl;
        this.ap  = source.ap;
        this.dms = source.dms;
        this.dml = source.dml;
        this.td  = source.td;

        System.arraycopy(source.a,  0, this.a,  0, 2);
        System.arraycopy(source.b,  0, this.b,  0, 6);
        System.arraycopy(source.pk, 0, this.pk, 0, 2);
        System.arraycopy(source.sr, 0, this.sr, 0, 2);
        System.arraycopy(source.dq, 0, this.dq, 0, 6);
    }

    /**
     * Validasi bahwa semua nilai state dalam range yang valid.
     * Berguna untuk debugging dan deteksi desinkronisasi.
     *
     * @return true jika semua nilai valid
     */
    // ✅ SESUDAH
    public boolean isValid() {
        // Cek scale factor
        if (yu < G726Tables.YU_MIN || yu > G726Tables.YU_MAX) return false;

        // Cek adaptation speed
        if (ap < G726Tables.AP_MIN || ap > G726Tables.AP_MAX) return false;

        // Cek tone flag
        if (td != 0 && td != 1) return false;

        // Cek koefisien poles a[]
        // a[0] dibatasi ±12288, a[1] dibatasi ±32576
        if (Math.abs(a[0]) > 12288) return false;
        if (Math.abs(a[1]) > 32576) return false;

        // Cek koefisien zeros b[]
        // b[] adalah Q15, range ±32767
        for (int i = 0; i < 6; i++) {
            if (Math.abs(b[i]) > 32767) return false;
        }

        // Cek dq[] — format internal floating-point
        // Struktur valid: bit[10]=tanda, bit[9:6]=exp(0-15), bit[5:0]=mantissa(32-63)
        // Nilai valid: 0x020–0x3FF (positif) atau 0x420–0x7FF (negatif)
        // Nilai 32 (0x020) adalah representasi nol yang valid
        for (int i = 0; i < 6; i++) {
            int val     = dq[i];
            int sign    = (val >> 10) & 1;       // bit 10: tanda
            int exp     = (val >> 6) & 0xF;      // bit 9-6: eksponent (0-15)
            int mant    = val & 0x3F;             // bit 5-0: mantissa (32-63)
            int invalid = val & ~0x7FF;           // bit di atas 10 harus 0

            if (invalid != 0) return false;       // ada bit tidak valid
            if (mant < 32 || mant > 63) return false; // mantissa di luar range
        }

        // Cek sr[] — format internal floating-point, sama dengan dq[]
        for (int i = 0; i < 2; i++) {
            int val     = sr[i];
            int mant    = val & 0x3F;
            int invalid = val & ~0x7FF;

            if (invalid != 0) return false;
            if (mant < 32 || mant > 63) return false;
        }

        // Cek pk[] — hanya boleh 0 atau 1
        if (pk[0] != 0 && pk[0] != 1) return false;
        if (pk[1] != 0 && pk[1] != 1) return false;

        return true;
    }

    @Override
    public String toString() {
        return String.format(
            "G726State[yu=%d, yl=%d, ap=%d, dms=%d, dml=%d, td=%d, a=[%d,%d]]",
            yu, yl, ap, dms, dml, td, a[0], a[1]
        );
    }
}