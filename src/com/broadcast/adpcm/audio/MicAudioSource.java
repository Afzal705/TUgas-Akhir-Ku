package com.broadcast.adpcm.audio;

import com.broadcast.adpcm.util.AppLogger;

import javax.sound.sampled.*;

/**
 * MicAudioSource.java
 *
 * Menangkap audio langsung dari mikrofon (TargetDataLine) - perilaku asli
 * BroadcastEngine sebelum refactor ini. Dipakai untuk broadcast live/demo.
 */
public final class MicAudioSource implements AudioSource {

    private final AudioConfig config;
    private final boolean littleEndian;
    private TargetDataLine line;

    public MicAudioSource(AudioConfig config, boolean littleEndian) {
        this.config = config;
        this.littleEndian = littleEndian;
    }

    @Override
    public void open() throws LineUnavailableException {
        AppLogger.info("Initializing audio capture from microphone...");

        AudioFormat format = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            config.getSampleRate(),
            config.getBitDepth(),
            config.getChannels(),
            config.getBitDepth() / 8,
            config.getSampleRate(),
            !littleEndian
        );

        if (format.getEncoding() != AudioFormat.Encoding.PCM_SIGNED &&
            format.getEncoding() != AudioFormat.Encoding.PCM_UNSIGNED) {
            throw new LineUnavailableException(
                "FATAL: Format encoding adalah " + format.getEncoding() + ", BUKAN PCM LINEAR!");
        }

        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

        if (!AudioSystem.isLineSupported(info)) {
            AppLogger.warn("Format tidak didukung langsung, mencari format kompatibel...");
            format = findCompatibleFormat(info);
            if (format == null) {
                throw new LineUnavailableException("No compatible audio format found.");
            }
            info = new DataLine.Info(TargetDataLine.class, format);
            AppLogger.info("Menggunakan format kompatibel: " + format);
        }

        line = (TargetDataLine) AudioSystem.getLine(info);
        line.open(format);
        line.start();

        AppLogger.info("Audio capture started successfully!");
        AppLogger.info("  - Sample rate: " + format.getSampleRate() + " Hz");
        AppLogger.info("  - Bit depth  : " + format.getSampleSizeInBits() + "-bit");
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

            if (fmt.getSampleRate() == config.getSampleRate())                    score += 50;
            else if (fmt.getSampleRate() == 16000 || fmt.getSampleRate() == 44100) score += 20;

            if (fmt.getSampleSizeInBits() == config.getBitDepth())          score += 40;
            else if (fmt.getSampleSizeInBits() == 8)                       score += 10;

            if (fmt.getChannels() == config.getChannels())                 score += 30;

            if (score > bestScore) {
                bestScore = score;
                bestMatch = fmt;
            }
        }
        return bestMatch;
    }

    @Override
    public int read(byte[] buffer) {
        return line.read(buffer, 0, buffer.length);
    }

    @Override
    public void close() {
        if (line != null) {
            line.stop();
            line.close();
        }
    }

    @Override
    public boolean isFinite() {
        return false;
    }

    @Override
    public String getDescription() {
        return "Microphone (live capture)";
    }
}