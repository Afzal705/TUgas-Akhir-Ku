package com.broadcast.adpcm.audio;

import javax.sound.sampled.LineUnavailableException;
import java.io.IOException;

/**
 * AudioSource.java
 *
 * Abstraksi sumber audio untuk BroadcastEngine. Tujuannya memisahkan
 * BroadcastEngine dari CARA audio diperoleh, sehingga mikrofon live dan
 * file PCM (untuk pengujian terkontrol - lihat diskusi Skenario A) bisa
 * dipakai bergantian tanpa mengubah logika capture->encode->kirim.
 *
 * KONTRAK PENTING: read() HARUS blocking dan ter-pacing sesuai kecepatan
 * sampling asli, persis seperti TargetDataLine.read() pada mikrofon fisik.
 * Ini menjaga karakteristik transmisi real-time (delay, jitter) tetap
 * otentik walau sumbernya file, bukan mic.
 */
public interface AudioSource {

    /** Membuka/menyiapkan sumber audio (buka line mic, atau buka file). */
    void open() throws LineUnavailableException, IOException;

    /**
     * Membaca satu chunk audio, blocking sampai buffer terisi penuh ATAU
     * sumber habis.
     *
     * @return jumlah byte yang berhasil dibaca; -1 jika sumber sudah habis
     *         (khusus sumber finite seperti file - mic tidak pernah habis)
     */
    int read(byte[] buffer) throws IOException;

    /** Menutup sumber audio dan melepas resource. */
    void close();

    /** True jika sumber ini bisa habis (file); false untuk mic. */
    boolean isFinite();

    /** Deskripsi untuk logging. */
    String getDescription();
}