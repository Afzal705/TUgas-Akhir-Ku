package com.broadcast.adpcm.server;

import com.broadcast.adpcm.audio.AudioConfig;
import com.broadcast.adpcm.audio.AudioSource;
import com.broadcast.adpcm.audio.FileAudioSource;
import com.broadcast.adpcm.audio.MicAudioSource;
import com.broadcast.adpcm.audio.PCMConverter;
import com.broadcast.adpcm.codec.AudioCodec;
import com.broadcast.adpcm.codec.DPCMCodecAdapter;
import com.broadcast.adpcm.codec.G726CodecAdapter;
import com.broadcast.adpcm.network.packet.SimplePacketFormatter;
import com.broadcast.adpcm.network.udp.UDPBroadcastSender;
import com.broadcast.adpcm.util.AppLogger;

import javax.sound.sampled.LineUnavailableException;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BroadcastEngine - Pipeline utama: ambil audio → encode → bungkus paket → kirim.
 *
 * Sumber audio (mic/file) dan algoritma kompresi (ADPCM G.726/DPCM) sekarang
 * dipilih lewat ServerConfig, bukan hardcoded - lihat createAudioSource()
 * dan createCodec() di bawah.
 */
public final class BroadcastEngine {

    // Sync Tone Constants
    private static final int SYNC_TONE_FREQUENCY   = 1000;  // 1000 Hz
    private static final int SYNC_TONE_DURATION_MS = 200;   // 200 ms

    private final ServerConfig config;
    private final AudioConfig audioConfig;
    private final PCMConverter pcmConverter;
    private final AudioCodec codec;
    private final UDPBroadcastSender sender;

    // Threading components
    // ✅ Single thread executor — encoding HARUS sequential karena state codec
    // (G726State/DPCMState) tidak thread-safe
    private ExecutorService encodingExecutor;
    private ScheduledExecutorService scheduledExecutor;
    private BlockingQueue<byte[]> packetQueue;

    // Audio capture
    private AudioSource audioSource;
    private Thread captureThread;

    // Control flags
    private final AtomicBoolean isRunning      = new AtomicBoolean(false);
    private final AtomicBoolean isCapturing    = new AtomicBoolean(false);
    private final AtomicInteger sequenceNumber = new AtomicInteger(0);

    // Statistics
    private final AtomicInteger framesProcessed = new AtomicInteger(0);
    private final AtomicInteger packetsSent     = new AtomicInteger(0);
    private long startTime;

    // Debug file output
    private FileOutputStream pcmFileOut;
    private FileOutputStream adpcmFileOut;
    private boolean syncToneWritten = false;

    // =========================================================================
    // CONSTRUCTOR
    // =========================================================================

    public BroadcastEngine(ServerConfig config) throws LineUnavailableException, IOException {
        this.config       = config;
        this.audioConfig  = config.getAudioConfig();
        this.pcmConverter = new PCMConverter(audioConfig);
        this.codec        = createCodec(config.getCodecType());
        AppLogger.info("Codec aktif: " + codec.getName());

        try {
            if (config.isUseMulticast()) {
                this.sender = new UDPBroadcastSender(
                    config.getMulticastAddress(),
                    config.getUdpPort(),
                    null,
                    config.getTtl()
                );
            } else {
                this.sender = new UDPBroadcastSender(config.getUdpPort());
            }
        } catch (Exception e) {
            throw new IOException("Failed to initialize UDP sender", e);
        }

        this.packetQueue = new LinkedBlockingQueue<>(config.getPacketQueueSize());

        if (config.isSaveRawPCM() && config.getPcmOutputPath() != null) {
            pcmFileOut = new FileOutputStream(config.getPcmOutputPath());
            AppLogger.info("PCM file opened: " + config.getPcmOutputPath());
        }

        if (config.isSaveADPCM() && config.getAdpcmOutputPath() != null) {
            adpcmFileOut = new FileOutputStream(config.getAdpcmOutputPath());
            AppLogger.info("ADPCM file opened: " + config.getAdpcmOutputPath());
        }
    }

    /**
     * Factory codec berdasarkan pilihan di ServerConfig. Menambah algoritma
     * baru cukup dengan menambah satu case di sini, tanpa mengubah logika
     * pipeline lain di kelas ini.
     */
    private static AudioCodec createCodec(ServerConfig.CodecType type) {
        switch (type) {
            case DPCM:
                return new DPCMCodecAdapter();
            case ADPCM_G726:
            default:
                return new G726CodecAdapter();
        }
    }

    /**
     * Factory AudioSource berdasarkan pilihan di ServerConfig. FILE dipakai
     * untuk pengujian terkontrol (input identik antar sesi - lihat diskusi
     * Skenario A), MICROPHONE untuk broadcast/demo langsung.
     */
    private static AudioSource createAudioSource(ServerConfig config) {
        if (config.getAudioSourceType() == ServerConfig.AudioSourceType.FILE) {
            return new FileAudioSource(config.getAudioFilePath(), config.getAudioConfig());
        }
        return new MicAudioSource(config.getAudioConfig(), true); // true = little-endian
    }

    // =========================================================================
    // START
    // =========================================================================

    public void start() throws LineUnavailableException {
        if (isRunning.get()) {
            AppLogger.warn("Engine already running");
            return;
        }

        isRunning.set(true);
        startTime = System.currentTimeMillis();

        // ✅ Single thread executor — menjamin encoding sequential
        encodingExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Audio-Encoder");
            t.setDaemon(true);
            return t;
        });

        scheduledExecutor = Executors.newSingleThreadScheduledExecutor();

        sender.start();

        try {
            processSyncTone();
            Thread.sleep(50);
        } catch (Exception e) {
            AppLogger.error("Failed to process sync tone", e);
        }

        startAudioCapture();
        startPacketSender();

        if (config.isSendHeartbeat()) {
            startHeartbeatSender();
        }

        startStatsLogger();

        AppLogger.info("BroadcastEngine started successfully");
        AppLogger.info("Audio format: " + audioConfig);
        AppLogger.info("Codec: " + codec.getName());
        AppLogger.info("UDP port: " + config.getUdpPort());
    }

    // =========================================================================
    // AUDIO CAPTURE
    // =========================================================================

    private void startAudioCapture() throws LineUnavailableException {
        audioSource = createAudioSource(config);

        try {
            audioSource.open();
        } catch (IOException e) {
            throw new LineUnavailableException("Gagal membuka sumber audio: " + e.getMessage());
        }

        AppLogger.info("Sumber audio aktif: " + audioSource.getDescription());

        isCapturing.set(true);

        captureThread = new Thread(this::captureLoop, "Audio-Capture");
        captureThread.setDaemon(true);
        captureThread.start();
    }

    // =========================================================================
    // SYNC TONE
    // =========================================================================

    private short[] generateSyncTone() {
        int sampleRate  = audioConfig.getSampleRate();
        int numSamples  = (sampleRate * SYNC_TONE_DURATION_MS) / 1000;
        short[] syncTone = new short[numSamples];
        double angleStep = 2.0 * Math.PI * SYNC_TONE_FREQUENCY / sampleRate;

        for (int i = 0; i < numSamples; i++) {
            double sample = Math.sin(angleStep * i) * 20000.0;
            syncTone[i] = (short) Math.max(-32768, Math.min(32767, Math.round(sample)));
        }

        AppLogger.info(String.format("Sync tone generated: %d Hz, %d ms, %d samples",
            SYNC_TONE_FREQUENCY, SYNC_TONE_DURATION_MS, numSamples));
        return syncTone;
    }

    private void processSyncTone() throws IOException {
        AppLogger.info("=== PROCESSING SYNC TONE ===");

        short[] syncTonePCM = generateSyncTone();

        if (pcmFileOut != null && !syncToneWritten) {
            byte[] syncToneBytes = new byte[syncTonePCM.length * 2];
            for (int i = 0; i < syncTonePCM.length; i++) {
                syncToneBytes[i * 2]     = (byte) (syncTonePCM[i] & 0xFF);
                syncToneBytes[i * 2 + 1] = (byte) ((syncTonePCM[i] >> 8) & 0xFF);
            }
            pcmFileOut.write(syncToneBytes);
            pcmFileOut.flush();
            syncToneWritten = true;
            AppLogger.info("Sync tone saved to PCM file: " + syncTonePCM.length + " samples");
        }

        // Sync tone dienkode dengan instance codec terpisah (bukan `codec` utama)
        // supaya state-nya tidak ikut tercampur ke sesi encoding audio sesungguhnya
        AudioCodec syncCodec  = createCodec(config.getCodecType());
        int samplesPerFrame   = audioConfig.getSamplesPerFrame();
        int frames            = (syncTonePCM.length + samplesPerFrame - 1) / samplesPerFrame;

        for (int frame = 0; frame < frames; frame++) {
            int frameStart     = frame * samplesPerFrame;
            int frameEnd       = Math.min(frameStart + samplesPerFrame, syncTonePCM.length);
            int samplesInFrame = frameEnd - frameStart;

            byte[] adpcmData = new byte[(samplesInFrame + 1) / 2];
            int adpcmOffset  = 0;

            for (int i = frameStart; i < frameEnd; i += 2) {
                int code1 = syncCodec.encode(syncTonePCM[i]);
                int code2 = (i + 1 < frameEnd)
                          ? syncCodec.encode(syncTonePCM[i + 1]) : 0;
                adpcmData[adpcmOffset++] = (byte) ((code1 << 4) | (code2 & 0x0F));
            }

            int seq            = sequenceNumber.getAndIncrement();
            long timestamp     = System.currentTimeMillis();
            byte[] packetData  = SimplePacketFormatter.buildPacket(timestamp, seq, adpcmData);
            sender.sendPacket(packetData, 0, packetData.length);
            packetsSent.incrementAndGet();
        }

        AppLogger.info("Sync tone transmitted: " + frames + " frames");
        AppLogger.info("=== SYNC TONE COMPLETE ===");
    }

    // =========================================================================
    // CAPTURE LOOP
    // =========================================================================

    private void captureLoop() {
        int frameSizeBytes = audioConfig.getBytesPerFrame();
        byte[] buffer       = new byte[frameSizeBytes];

        AppLogger.info("Capture loop started, frame size: " + frameSizeBytes + " bytes");

        while (isCapturing.get() && isRunning.get()) {
            int bytesRead;
            try {
                bytesRead = audioSource.read(buffer);
            } catch (IOException e) {
                AppLogger.error("Error reading from audio source", e);
                break;
            }

            if (bytesRead == -1) {
                // Sumber finite (FileAudioSource) sudah habis - hentikan otomatis
                AppLogger.info("Audio source reached end-of-stream, stopping capture");
                break;
            }

            if (bytesRead > 0) {
                final byte[] audioChunk = new byte[bytesRead];
                System.arraycopy(buffer, 0, audioChunk, 0, bytesRead);

                // ✅ Single thread executor — encoding selalu sequential
                // Tidak ada race condition pada state codec
                encodingExecutor.submit(() -> processAudioChunk(audioChunk));
            }
        }

        AppLogger.info("Capture loop ended");
    }

    // =========================================================================
    // PROCESS AUDIO CHUNK (dipanggil oleh encodingExecutor — 1 thread saja)
    // =========================================================================

    private void processAudioChunk(byte[] audioChunk) {
        try {
            short[] pcmSamples = pcmConverter.toPcm16(audioChunk);
            // Sengaja TIDAK dipanggil: normalize() melakukan auto-gain per-chunk (~10ms)
            // yang akan mengangkat amplitudo chunk pelan mendekati skala penuh. Ini
            // menghapus perbedaan energi sinyal antar Variasi 1/2/3 (Tabel 3.2, khususnya
            // Variasi 3 - bicara pelan) yang justru menjadi variabel yang diuji.
            // pcmSamples = pcmConverter.normalize(pcmSamples);

            if (pcmFileOut != null && syncToneWritten) {
                byte[] pcmBytes = new byte[pcmSamples.length * 2];
                for (int i = 0; i < pcmSamples.length; i++) {
                    pcmBytes[i * 2]     = (byte) (pcmSamples[i] & 0xFF);
                    pcmBytes[i * 2 + 1] = (byte) ((pcmSamples[i] >> 8) & 0xFF);
                }
                pcmFileOut.write(pcmBytes);
            }

            int framesPerChunk = pcmSamples.length / audioConfig.getSamplesPerFrame();
            byte[] adpcmData   = new byte[audioConfig.getAdpcmBytesPerFrame() * framesPerChunk];
            int adpcmOffset    = 0;

            for (int f = 0; f < framesPerChunk; f++) {
                int frameStart = f * audioConfig.getSamplesPerFrame();
                for (int i = 0; i < audioConfig.getSamplesPerFrame(); i += 2) {
                    int sample1 = pcmSamples[frameStart + i];
                    int code1   = codec.encode(sample1);

                    int code2 = 0;
                    if (i + 1 < audioConfig.getSamplesPerFrame()) {
                        int sample2 = pcmSamples[frameStart + i + 1];
                        code2       = codec.encode(sample2);
                    }

                    // Kode di-pack sebagai 2 nibble 4-bit per byte. Untuk DPCM
                    // (signed, rentang [-8,7]), byte cast di akhir otomatis
                    // membuang bit sign-extension di luar 8-bit rendah, sehingga
                    // nibble tetap merepresentasikan two's complement 4-bit yang
                    // benar tanpa masking tambahan. Decoder nanti WAJIB
                    // sign-extend nibble saat membaca kode DPCM.
                    adpcmData[adpcmOffset++] = (byte) ((code1 << 4) | (code2 & 0x0F));
                }
            }

            if (adpcmFileOut != null) {
                adpcmFileOut.write(adpcmData);
            }

            int seq           = sequenceNumber.getAndIncrement();
            long timestamp    = System.currentTimeMillis();
            byte[] packetData = SimplePacketFormatter.buildPacket(timestamp, seq, adpcmData);

            if (!packetQueue.offer(packetData)) {
                AppLogger.warn("Packet queue full, dropping packet seq=" + seq);
            }

            framesProcessed.incrementAndGet();

            if (config.isVerboseLogging() && framesProcessed.get() % 100 == 0) {
                AppLogger.debug("Processed " + framesProcessed.get() + " frames");
            }

        } catch (Exception e) {
            AppLogger.error("Error processing audio chunk", e);
        }
    }

    // =========================================================================
    // PACKET SENDER
    // =========================================================================

    private void startPacketSender() {
        Thread senderThread = new Thread(() -> {
            AppLogger.info("Packet sender thread started");

            while (isRunning.get()) {
                try {
                    byte[] packetData = packetQueue.poll(50, TimeUnit.MILLISECONDS);
                    if (packetData != null) {
                        if (sender.sendPacket(packetData, 0, packetData.length)) {
                            packetsSent.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    AppLogger.error("Error sending packet", e);
                }
            }

            AppLogger.info("Packet sender thread stopped");
        }, "Packet-Sender");

        senderThread.setDaemon(true);
        senderThread.start();
    }

    // =========================================================================
    // HEARTBEAT & STATS
    // =========================================================================

    private void startHeartbeatSender() {
        scheduledExecutor.scheduleAtFixedRate(() -> {
            if (isRunning.get()) {
                try {
                    int seq        = sequenceNumber.getAndIncrement();
                    long timestamp = System.currentTimeMillis();
                    byte[] packet  = SimplePacketFormatter.buildPacket(timestamp, seq, new byte[0]);
                    sender.sendPacket(packet, 0, packet.length);
                    if (config.isVerboseLogging()) AppLogger.debug("Heartbeat sent: seq=" + seq);
                } catch (Exception e) {
                    AppLogger.error("Error sending heartbeat", e);
                }
            }
        }, config.getHeartbeatIntervalMs(), config.getHeartbeatIntervalMs(), TimeUnit.MILLISECONDS);
    }

    private void startStatsLogger() {
        scheduledExecutor.scheduleAtFixedRate(() -> {
            if (isRunning.get()) printStats();
        }, 10, 10, TimeUnit.SECONDS);
    }

    private void printStats() {
        long uptime = System.currentTimeMillis() - startTime;
        double fps  = (framesProcessed.get() * 1000.0) / uptime;
        double pps  = (packetsSent.get() * 1000.0) / uptime;

        AppLogger.info(String.format(
            "Stats: uptime=%ds, frames=%d (%.1f fps), packets=%d (%.1f pps), queue=%d",
            uptime / 1000, framesProcessed.get(), fps,
            packetsSent.get(), pps, packetQueue.size()
        ));
    }

    // =========================================================================
    // STOP
    // =========================================================================

    public void stop() {
        if (!isRunning.get()) return;

        AppLogger.info("Stopping BroadcastEngine...");
        isRunning.set(false);
        isCapturing.set(false);

        if (audioSource != null) {
            audioSource.close();
        }

        if (captureThread != null) {
            captureThread.interrupt();
        }

        // ✅ Shutdown encodingExecutor — tunggu frame terakhir selesai
        if (encodingExecutor != null) {
            encodingExecutor.shutdown();
            try {
                encodingExecutor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (scheduledExecutor != null) {
            scheduledExecutor.shutdown();
        }

        sender.stop();

        try {
            if (pcmFileOut != null) {
                pcmFileOut.flush();
                long pcmFileSize = pcmFileOut.getChannel().size();
                pcmFileOut.close();
                AppLogger.info("PCM file closed. Total size: " + pcmFileSize + " bytes");
            }
            if (adpcmFileOut != null) {
                adpcmFileOut.flush();
                adpcmFileOut.close();
            }
        } catch (IOException e) {
            AppLogger.error("Error closing debug files", e);
        }

        printStats();
        AppLogger.info("BroadcastEngine stopped");
    }

    // =========================================================================
    // GETTERS
    // =========================================================================

    public boolean isRunning()      { return isRunning.get();       }
    public int getFramesProcessed() { return framesProcessed.get(); }
    public int getPacketsSent()     { return packetsSent.get();     }
    public int getQueueSize()       { return packetQueue.size();    }
}