package com.broadcast.adpcm.audio;

import com.broadcast.adpcm.util.AppLogger;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * FileAudioSource.java
 *
 * Membaca audio dari file PCM mentah (16-bit signed, little-endian, mono,
 * sample rate sesuai AudioConfig) - BUKAN dari mikrofon. Dipakai untuk
 * pengujian terkontrol (Skenario A), di mana sinyal input harus identik
 * di setiap sesi pengujian agar variabel suara manusia tidak ikut
 * memengaruhi hasil pengukuran QoS/kualitas audio.
 *
 * read() di-pacing (throttle) agar berjalan pada kecepatan real-time yang
 * sama seperti perekaman aslinya, bukan dibaca secepat CPU bisa - sehingga
 * karakteristik transmisi UDP (jitter, delay antar paket) tetap otentik.
 *
 * Format file yang diharapkan SAMA PERSIS dengan yang ditulis
 * BroadcastEngine ke pcmOutputPath (lihat ServerConfig.saveRawPCM) -
 * artinya file hasil rekaman live-mic bisa langsung dipakai ulang di sini.
 */
public final class FileAudioSource implements AudioSource {

    private final String filePath;
    private final AudioConfig config;
    private BufferedInputStream input;
    private long bytesReadSoFar;
    private long startTimeNanos;

    public FileAudioSource(String filePath, AudioConfig config) {
        this.filePath = filePath;
        this.config = config;
    }

    @Override
    public void open() throws IOException {
        AppLogger.info("Membuka file audio uji: " + filePath);
        input = new BufferedInputStream(new FileInputStream(filePath));
        bytesReadSoFar = 0;
        startTimeNanos = System.nanoTime();
    }

    @Override
    public int read(byte[] buffer) throws IOException {
        int totalRead = 0;
        while (totalRead < buffer.length) {
            int n = input.read(buffer, totalRead, buffer.length - totalRead);
            if (n == -1) break;
            totalRead += n;
        }

        if (totalRead == 0) {
            return -1; // end of file
        }

        bytesReadSoFar += totalRead;
        pacingSleep();

        return totalRead;
    }

    /**
     * Hitung berapa lama seharusnya waktu berlalu untuk jumlah byte yang
     * sudah dibaca sejauh ini (berdasarkan sample rate asli), lalu tidur
     * selisihnya jika pembacaan file lebih cepat dari itu.
     */
    private void pacingSleep() {
        int bytesPerSample = config.getBitDepth() / 8;
        double bytesPerSecond = config.getSampleRate() * bytesPerSample * config.getChannels();

        long expectedElapsedNanos = (long) ((bytesReadSoFar / bytesPerSecond) * 1_000_000_000L);
        long actualElapsedNanos = System.nanoTime() - startTimeNanos;
        long sleepNanos = expectedElapsedNanos - actualElapsedNanos;

        if (sleepNanos > 0) {
            try {
                Thread.sleep(sleepNanos / 1_000_000L, (int) (sleepNanos % 1_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() {
        try {
            if (input != null) input.close();
        } catch (IOException e) {
            AppLogger.error("Gagal menutup file audio uji", e);
        }
    }

    @Override
    public boolean isFinite() {
        return true;
    }

    @Override
    public String getDescription() {
        return "File audio uji: " + filePath;
    }
}