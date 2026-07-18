package main.java.com.broadcast.adpcm.server;

import main.java.com.broadcast.adpcm.audio.AudioConfig;
import main.java.com.broadcast.adpcm.audio.PCMConverter;
import main.java.com.broadcast.adpcm.codec.G726Codec;
import main.java.com.broadcast.adpcm.codec.G726State;
import main.java.*;
import main.java.com.broadcast.adpcm.network.packet.SimplePacketFormatter;
import main.java.com.broadcast.adpcm.network.udp.UDPBroadcastSender;
import main.java.com.broadcast.adpcm.util.AppLogger;

import javax.sound.sampled.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BroadcastEngine - Pipeline utama: ambil audio → encode → bungkus paket → kirim.
 */
public final class BroadcastEngine {

    // ==================== AUDIO FORMAT CONSTANTS ====================
    private static final int TARGET_SAMPLE_RATE  = 8000;
    private static final int TARGET_BIT_DEPTH    = 16;
    private static final int TARGET_CHANNELS     = 1;
    private static final boolean LITTLE_ENDIAN   = false; // false = little-endian di AudioFormat
    private static final boolean SIGNED          = true;

    // Sync Tone Constants
    private static final int SYNC_TONE_FREQUENCY  = 1000;  // 1000 Hz
    private static final int SYNC_TONE_DURATION_MS = 200;  // 200 ms

    // Network Constants
    private static final int UDP_PORT = 50005;

    private final ServerConfig config;
    private final AudioConfig audioConfig;
    private final PCMConverter pcmConverter;
    private final G726State encoderState;
    private final UDPBroadcastSender sender;

    // Threading components
    // ✅ PERBAIKAN: ganti executorService (3 thread) → encodingExecutor (1 thread)
    // Encoding HARUS sequential karena G726State tidak thread-safe
    private ExecutorService encodingExecutor;
    private ScheduledExecutorService scheduledExecutor;
    private BlockingQueue<byte[]> packetQueue;

    // Audio capture
    private TargetDataLine microphoneLine;
    private Thread captureThread;

    // Control flags
    private final AtomicBoolean isRunning     = new AtomicBoolean(false);
    private final AtomicBoolean isCapturing   = new AtomicBoolean(false);
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
        this.encoderState = new G726State();

        try {
            if (config.isUseMulticast()) {
                this.sender = new UDPBroadcastSender(
                    config.getMulticastAddress(),
                    UDP_PORT,
                    null,
                    config.getTtl()
                );
            } else {
                this.sender = new UDPBroadcastSender(UDP_PORT);
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

        // ✅ PERBAIKAN: Single thread executor — menjamin encoding sequential
        // G726State tidak thread-safe, satu thread saja yang boleh akses encoderState
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
        AppLogger.info("Audio format: " + TARGET_SAMPLE_RATE + " Hz, "
            + TARGET_BIT_DEPTH + "-bit, " + TARGET_CHANNELS + " channel (PCM LINEAR)");
        AppLogger.info("UDP port: " + UDP_PORT);
    }

    // =========================================================================
    // AUDIO CAPTURE
    // =========================================================================

    private void startAudioCapture() throws LineUnavailableException {
        AppLogger.info("Initializing audio capture...");

        AudioFormat format = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            TARGET_SAMPLE_RATE,
            TARGET_BIT_DEPTH,
            TARGET_CHANNELS,
            TARGET_BIT_DEPTH / 8,
            TARGET_SAMPLE_RATE,
            LITTLE_ENDIAN   // ✅ false = little-endian
        );

        AppLogger.info("Requested audio format: " + format);
        AppLogger.info("Encoding type: " + format.getEncoding());

        if (format.getEncoding() != AudioFormat.Encoding.PCM_SIGNED &&
            format.getEncoding() != AudioFormat.Encoding.PCM_UNSIGNED) {
            String msg = "FATAL: Format encoding adalah " + format.getEncoding()
                       + ", BUKAN PCM LINEAR!";
            AppLogger.error(msg);
            throw new LineUnavailableException(msg);
        }

        // ✅ PERBAIKAN: info diperbarui jika format berubah
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

        if (!AudioSystem.isLineSupported(info)) {
            AppLogger.warn("Format tidak didukung langsung, mencari format kompatibel...");
            format = findCompatibleFormat(info);
            if (format == null) {
                throw new LineUnavailableException("No compatible audio format found.");
            }
            info = new DataLine.Info(TargetDataLine.class, format); // ✅ info diperbarui
            AppLogger.info("Menggunakan format kompatibel: " + format);
        }

        microphoneLine = (TargetDataLine) AudioSystem.getLine(info);
        microphoneLine.open(format);
        microphoneLine.start();

        isCapturing.set(true);

        captureThread = new Thread(this::captureLoop, "Audio-Capture");
        captureThread.setDaemon(true);
        captureThread.start();

        AppLogger.info("Audio capture started successfully!");
        AppLogger.info("  - Encoding   : " + format.getEncoding());
        AppLogger.info("  - Sample rate: " + format.getSampleRate() + " Hz");
        AppLogger.info("  - Bit depth  : " + format.getSampleSizeInBits() + "-bit");
        AppLogger.info("  - Channels   : " + format.getChannels());
        AppLogger.info("  - Frame size : " + format.getFrameSize() + " bytes");
        AppLogger.info("  - Little-endian: " + !format.isBigEndian());
    }

    private AudioFormat findCompatibleFormat(DataLine.Info preferredInfo) {
        AudioFormat[] supportedFormats = preferredInfo.getFormats();
        AudioFormat bestMatch = null;
        int bestScore = -1;

        for (AudioFormat fmt : supportedFormats) {
            int score = 0;

            if (fmt.getEncoding() == AudioFormat.Encoding.PCM_SIGNED)        score += 100;
            else if (fmt.getEncoding() == AudioFormat.Encoding.PCM_UNSIGNED) score += 80;
            else continue;

            if (fmt.getSampleRate() == TARGET_SAMPLE_RATE)                   score += 50;
            else if (fmt.getSampleRate() == 16000 || fmt.getSampleRate() == 44100) score += 20;

            if (fmt.getSampleSizeInBits() == TARGET_BIT_DEPTH)               score += 40;
            else if (fmt.getSampleSizeInBits() == 8)                         score += 10;

            if (fmt.getChannels() == TARGET_CHANNELS)                        score += 30;

            if (score > bestScore) {
                bestScore = score;
                bestMatch = fmt;
            }
        }

        if (bestMatch != null) AppLogger.info("Found compatible format: " + bestMatch);
        return bestMatch;
    }

    // =========================================================================
    // SYNC TONE
    // =========================================================================

    private short[] generateSyncTone() {
        int numSamples = (TARGET_SAMPLE_RATE * SYNC_TONE_DURATION_MS) / 1000;
        short[] syncTone = new short[numSamples];
        double angleStep = 2.0 * Math.PI * SYNC_TONE_FREQUENCY / TARGET_SAMPLE_RATE;

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

        G726State syncState   = new G726State();
        int samplesPerFrame   = audioConfig.getSamplesPerFrame();
        int frames            = (syncTonePCM.length + samplesPerFrame - 1) / samplesPerFrame;

        for (int frame = 0; frame < frames; frame++) {
            int frameStart     = frame * samplesPerFrame;
            int frameEnd       = Math.min(frameStart + samplesPerFrame, syncTonePCM.length);
            int samplesInFrame = frameEnd - frameStart;

            byte[] adpcmData = new byte[(samplesInFrame + 1) / 2];
            int adpcmOffset  = 0;

            for (int i = frameStart; i < frameEnd; i += 2) {
                int adpcm1 = G726Codec.encode(syncTonePCM[i], syncState);
                int adpcm2 = (i + 1 < frameEnd)
                           ? G726Codec.encode(syncTonePCM[i + 1], syncState) : 0;
                adpcmData[adpcmOffset++] = (byte) ((adpcm1 << 4) | (adpcm2 & 0x0F));
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

    /**
     * ✅ PERBAIKAN: captureLoop submit ke encodingExecutor (single thread),
     * bukan executorService (3 thread). Menjamin encoding sequential.
     */
    private void captureLoop() {
        int frameSizeBytes = audioConfig.getBytesPerFrame();
        byte[] buffer      = new byte[frameSizeBytes];

        AppLogger.info("Capture loop started, frame size: " + frameSizeBytes + " bytes");

        while (isCapturing.get() && isRunning.get()) {
            int bytesRead = microphoneLine.read(buffer, 0, buffer.length);

            if (bytesRead > 0) {
                final byte[] audioChunk = new byte[bytesRead];
                System.arraycopy(buffer, 0, audioChunk, 0, bytesRead);

                // ✅ Single thread executor — encoding selalu sequential
                // Tidak ada race condition pada encoderState
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
            // pcmSamples         = pcmConverter.normalize(pcmSamples);
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
                    int adpcm1  = G726Codec.encode(sample1, encoderState);

                    int adpcm2 = 0;
                    if (i + 1 < audioConfig.getSamplesPerFrame()) {
                        int sample2 = pcmSamples[frameStart + i + 1];
                        adpcm2      = G726Codec.encode(sample2, encoderState);
                    }

                    adpcmData[adpcmOffset++] = (byte) ((adpcm1 << 4) | (adpcm2 & 0x0F));
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
        long uptime  = System.currentTimeMillis() - startTime;
        double fps   = (framesProcessed.get() * 1000.0) / uptime;
        double pps   = (packetsSent.get() * 1000.0) / uptime;

        AppLogger.info(String.format(
            "Stats: uptime=%ds, frames=%d (%.1f fps), packets=%d (%.1f pps), queue=%d",
            uptime / 1000, framesProcessed.get(), fps,
            packetsSent.get(), pps, packetQueue.size()
        ));
    }

    // =========================================================================
    // STOP
    // =========================================================================

    /**
     * ✅ PERBAIKAN: shutdown encodingExecutor (bukan executorService).
     * Tunggu frame terakhir selesai di-encode sebelum menutup resource.
     */
    public void stop() {
        if (!isRunning.get()) return;

        AppLogger.info("Stopping BroadcastEngine...");
        isRunning.set(false);
        isCapturing.set(false);

        if (microphoneLine != null) {
            microphoneLine.stop();
            microphoneLine.close();
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

        // ✅ PERBAIKAN: ambil size sebelum close
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

    public boolean isRunning()        { return isRunning.get();        }
    public int getFramesProcessed()   { return framesProcessed.get();  }
    public int getPacketsSent()       { return packetsSent.get();      }
    public int getQueueSize()         { return packetQueue.size();     }
}