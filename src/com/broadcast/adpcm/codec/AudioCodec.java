package com.broadcast.adpcm.codec;

/**
 * AudioCodec.java
 *
 * Abstraksi codec kompresi suara agar BroadcastEngine tidak perlu tahu
 * apakah yang dipakai ADPCM G.726 atau DPCM (pembanding, Sub-bab 3.3.3) -
 * cukup panggil encode() secara generik.
 *
 * Setiap implementasi menyimpan state internalnya sendiri (G726State atau
 * DPCMState), sehingga satu instance = satu sesi encoding yang stateful
 * dan TIDAK thread-safe (harus dipanggil dari satu thread saja).
 */
public interface AudioCodec {

    /**
     * @param pcm16 sampel PCM 16-bit signed [-32768, 32767]
     * @return kode hasil kompresi
     */
    int encode(int pcm16);

    /** Jumlah bit per sampel hasil kompresi (dipakai untuk packing byte). */
    int getBitsPerSample();

    /** Nama algoritma, untuk logging/penamaan file output. */
    String getName();
}