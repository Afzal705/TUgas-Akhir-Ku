package com.broadcast.adpcm.codec;
/**
 * G726Tables.java
 *
 * Tabel konstanta standar ITU-T G.726 untuk mode 32 kbps (4-bit ADPCM).
 *
 * Seluruh nilai mengacu langsung pada:
 *   ITU-T Recommendation G.726 (03/1991)
 *   "40, 32, 24 and 16 kbit/s Adaptive Differential Pulse Code Modulation"
 *
 * Referensi tabel per dokumen resmi:
 *   QTAB_32  → Table 2/G.726  (Quantizer decision levels, 32 kbps)
 *   DQLN_TABLE → Table 4/G.726 (Inverse quantizer output, log domain)
 *   WI_TABLE   → Table 3/G.726 (Scale factor adaptation weights)
 *   FI_TABLE   → Table 5/G.726 (Speed control flags)
 */
public final class G726Tables {

    // =========================================================
    // QTAB_32 — Tabel Ambang Batas Kuantisasi (Table 2/G.726)
    // =========================================================
    //
    // Digunakan di: Blok 3 — Adaptive Quantizer (fungsi quantize)
    //
    // Cara kerja:
    //   Setelah selisih d dinormalisasi menjadi dln (log domain),
    //   fungsi search() mencari indeks i pertama di mana dln < QTAB_32[i].
    //   Indeks i tersebut menjadi magnitude kode (0–7).
    //
    // 7 ambang batas membagi domain menjadi 8 wilayah:
    //   dln < -124          → i=0 (magnitude terkecil, zona granular)
    //   -124 ≤ dln < 80     → i=1
    //   80 ≤ dln < 178      → i=2
    //   178 ≤ dln < 246     → i=3
    //   246 ≤ dln < 300     → i=4
    //   300 ≤ dln < 349     → i=5
    //   349 ≤ dln < 400     → i=6
    //   dln ≥ 400           → i=7 (magnitude terbesar, zona overload)
    //
    // Referensi: Table 2/G.726, kolom "W(I) 32 kbit/s", baris quantizer levels
    public static final int[] QTAB_32 = {
        -124, 80, 178, 246, 300, 349, 400
    };
//catatan untuk guwe:
// nilai pada tabel QTAB_32 adalah nilai ambang batas dalam domain logaritmik (log2) yang digunakan untuk menentukan kode kuantisasi berdasarkan selisih sinyal yang telah dinormalisasi. Nilai-nilai ini tidak mewakili nilai amplitudo langsung, melainkan titik-titik di mana kode kuantisasi berubah dari satu tingkat ke tingkat berikutnya.
// Misalnya, jika selisih yang dinormalisasi (dln) adalah 50, maka kode kuantisasi akan menjadi 1 karena 50 berada di antara -124 dan 80. Jika dln adalah 200, maka kode kuantisasi akan menjadi 3 karena 200 berada di antara 178 dan 246. Nilai-nilai ini penting untuk memastikan bahwa proses kuantisasi mengikuti karakteristik sinyal suara dan menghasilkan kualitas audio yang sesuai dengan standar G.726 pada bitrate 32 kbps.
//nilai ini gk sama dengan yang ada di dalam dokumen resmi ITU-T g.726 
//dalam tabel 2/g.726 nilai nilai yang ada didalam tabel adalah nilai floating point biasa 
//namun dalam implementasi referensi (Sun Microsystems g72x.c) dan banyak implementasi lain, nilai-nilai tersebut diubah menjadi representasi logaritmik yang diskalakan (Q7 format) untuk efisiensi komputasi. Oleh karena itu, nilai-nilai dalam QTAB_32 adalah hasil konversi dari nilai ambang batas asli ke format logaritmik yang digunakan dalam proses kuantisasi G.726.
    // =========================================================
    // DQLN_TABLE — Output Inverse Quantizer, Domain Log (Table 4/G.726)
    // =========================================================
    //
    // Digunakan di: Blok 4 — Inverse Adaptive Quantizer (fungsi reconstruct)
    //
    // Format: nilai dalam skala log basis-2 × 128 (format Q7)
    //
    // 16 entry untuk kode I = 0 sampai 15:
    //   I = 0       → -2048  (magnitude ≈ 0, kode positif terkecil)
    //   I = 1       →    -4  (magnitude sangat kecil)
    //   I = 2       →   135
    //   I = 3       →   213
    //   I = 4       →   273
    //   I = 5       →   323
    //   I = 6       →   373
    //   I = 7       →   425  (magnitude terbesar, positif)
    //   I = 8       →   425  (magnitude terbesar, negatif — cermin I=7)
    //   I = 9       →   373
    //   I = 10      →   323
    //   I = 11      →   273
    //   I = 12      →   213
    //   I = 13      →   135
    //   I = 14      →    -4
    //   I = 15      → -2048  (magnitude ≈ 0, kode negatif terkecil)
    //
    // Pola simetri: DQLN_TABLE[i] == DQLN_TABLE[15-i]
    // Nilai -2048 merepresentasikan log2(0) = -∞ (magnitude = 0)
    //
    // Referensi: Table 4/G.726
    public static final int[] DQLN_TABLE = {
        -2048, -4, 135, 213, 273, 323, 373, 425,
          425, 373, 323, 273, 213, 135,  -4, -2048
    };
 
    // =========================================================
    // WI_TABLE — Bobot Adaptasi Scale Factor (Table 3/G.726)
    // =========================================================
    //
    // Digunakan di: Blok 7 — Quantizer Scale Factor Adaptation (fungsi update)
    //
    // Cara kerja:
    //   yu_baru = yu_lama + (WI_TABLE[I] - yu_lama) >> 5
    //   (moving average dengan konstanta waktu 32 sampel)
    //
    // Interpretasi nilai per kode I:
    //   I=0, I=15  → -12    (granular terkecil, step size TURUN agresif)
    //   I=1, I=14  →  18    (step size naik sedikit)
    //   I=2, I=13  →  41
    //   I=3, I=12  →  64
    //   I=4, I=11  → 112
    //   I=5, I=10  → 198
    //   I=6, I=9   → 355
    //   I=7, I=8   → 1122  (overload terbesar, step size NAIK agresif)
    //
    // Semakin besar nilai WI → semakin cepat step size membesar
    // Nilai negatif (-12) → step size mengecil (sinyal kecil/stabil)
    //
    // Referensi: Table 3/G.726, kolom "W(I) for 32 kbit/s"
    public static final int[] WI_TABLE = {
        -12,   18,  41,  64, 112, 198,  355, 1122,
        1122, 355, 198, 112,  64,  41,   18,  -12
    };

    // =========================================================
    // FI_TABLE — Flag Kecepatan Adaptasi (Table 5/G.726)
    // =========================================================
    //
    // Digunakan di: Blok 8 — Adaptation Speed Control (fungsi update)
    //
    // Cara kerja:
    //   dms_baru = dms_lama + (FI_TABLE[I] - dms_lama) >> 5  (short-term)
    //   dml_baru = dml_lama + (FI_TABLE[I] - dml_lama) >> 7  (long-term)
    //
    // Interpretasi nilai:
    //   I=0,1,2    → 0x000  (zona granular dalam: sinyal stabil)
    //   I=3,4,5    → 0x200  (zona tengah)
    //   I=6        → 0x600  (mendekati overload)
    //   I=7, I=8   → 0xE00  (zona overload: sinyal transisi cepat/speech)
    //   I=9        → 0x600
    //   I=10,11,12 → 0x200
    //   I=13,14,15 → 0x000
    //
    // Nilai besar → dms naik → ap naik → adaptasi lebih cepat (mode speech)
    // Nilai nol   → dms turun → ap turun → adaptasi lambat (mode tone/data)
    //
    // Referensi: Table 5/G.726, kolom "F(I) for 32 kbit/s"
    public static final int[] FI_TABLE = {
        0x000, 0x000, 0x000, 0x200, 0x200, 0x200, 0x600, 0xE00,
        0xE00, 0x600, 0x200, 0x200, 0x200, 0x000, 0x000, 0x000
    };

    // =========================================================
    // POWER2 — Tabel Pangkat Dua untuk Komputasi Logaritma
    // =========================================================
    //
    // Digunakan di: fungsi search() di dalam G726Codec
    //   untuk menghitung floor(log2(x)) secara efisien
    //   dengan pencarian linear: cari i di mana x < POWER2[i]
    //
    // 15 elemen: 2^0 = 1 sampai 2^14 = 16384
    // Bukan dari tabel ITU-T, tapi bagian standar implementasi referensi
    // (Sun Microsystems g72x.c, CCITT 1988)
    //
    // Catatan: hanya 15 elemen (bukan 16) karena nilai maksimum
    // yang perlu dihitung log2-nya dalam G.726 tidak melebihi 16383
    public static final int[] POWER2 = {
        1, 2, 4, 8, 16, 32, 64, 128,
        256, 512, 1024, 2048, 4096, 8192, 16384
    };

    // =========================================================
    // Konstanta Batas Nilai State
    // =========================================================

    /** Batas bawah step size yu (nilai minimum yang diizinkan) */
    public static final int YU_MIN = 544;

    /** Batas atas step size yu (nilai maksimum yang diizinkan) */
    public static final int YU_MAX = 5120;

    /** Nilai awal slow scale factor yl = YU_MIN × 64 = 34816 */
    public static final long YL_INIT = 34816L;

    /** Batas bawah adaptation speed ap */
    public static final int AP_MIN = 0;

    /** Batas atas adaptation speed ap */
    public static final int AP_MAX = 256;

    /** Ambang batas deteksi nada: a[1] < -11776 → tone detected */
    public static final int TONE_THRESHOLD = -11776;

    private G726Tables() {}
}