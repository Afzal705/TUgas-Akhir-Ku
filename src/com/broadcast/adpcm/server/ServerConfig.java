package com.broadcast.adpcm.server;

import com.broadcast.adpcm.audio.AudioConfig;

/**
 * ServerConfig - Konfigurasi server broadcast (port, bitrate, frame, dll).
 */
public final class ServerConfig {

    // Default network configuration
    public static final int DEFAULT_UDP_PORT = 50005;
    public static final int DEFAULT_TTL = 64;
    public static final String DEFAULT_MULTICAST_ADDRESS = "239.1.2.3";
    public static final boolean DEFAULT_USE_MULTICAST = false;
    public static final boolean DEFAULT_USE_BROADCAST = true;

    // Default audio configuration (G.726 32 kbps)
    public static final int DEFAULT_SAMPLE_RATE = 8000;
    public static final int DEFAULT_BIT_DEPTH = 16;
    public static final int DEFAULT_CHANNELS = 1;
    public static final int DEFAULT_FRAME_SIZE_MS = 10;

    // Default server behavior
    public static final int DEFAULT_PACKET_QUEUE_SIZE = 1000;
    public static final int DEFAULT_SEND_INTERVAL_MS = 10;
    public static final boolean DEFAULT_SEND_HEARTBEAT = true;
    public static final int DEFAULT_HEARTBEAT_INTERVAL_MS = 5000;
    public static final boolean DEFAULT_ENABLE_CHECKSUM = true;
    public static final boolean DEFAULT_ENABLE_COMPRESSION = false;

    /** Pilihan algoritma kompresi. DPCM = metode pembanding (Sub-bab 3.3.3). */
    public enum CodecType { ADPCM_G726, DPCM }

    /** Pilihan sumber audio. FILE = pengujian terkontrol (Skenario A, input identik antar sesi). */
    public enum AudioSourceType { MICROPHONE, FILE }

    public static final CodecType DEFAULT_CODEC_TYPE = CodecType.ADPCM_G726;
    public static final AudioSourceType DEFAULT_AUDIO_SOURCE_TYPE = AudioSourceType.MICROPHONE;

    // Network settings
    private int udpPort;
    private int ttl;
    private String multicastAddress;
    private boolean useMulticast;
    private boolean useBroadcast;

    // Audio settings
    private AudioConfig audioConfig;
    private CodecType codecType;
    private AudioSourceType audioSourceType;
    private String audioFilePath;

    // Server behavior
    private int packetQueueSize;
    private int sendIntervalMs;
    private boolean sendHeartbeat;
    private int heartbeatIntervalMs;
    private boolean enableChecksum;
    private boolean enableCompression;

    // Control flags
    private boolean verboseLogging;
    private boolean saveRawPCM;      // For debugging
    private boolean saveADPCM;        // For debugging
    private String pcmOutputPath;
    private String adpcmOutputPath;

    private ServerConfig() {
        // Use builder
    }

    /**
     * Get default configuration.
     */
    public static ServerConfig getDefault() {
        return new Builder().build();
    }

    /**
     * Get configuration for multicast mode.
     */
    public static ServerConfig getMulticastConfig() {
        return new Builder()
            .useMulticast(true)
            .multicastAddress(DEFAULT_MULTICAST_ADDRESS)
            .useBroadcast(false)
            .build();
    }

    /**
     * Get configuration for broadcast mode.
     */
    public static ServerConfig getBroadcastConfig() {
        return new Builder()
            .useMulticast(false)
            .useBroadcast(true)
            .build();
    }

    /**
     * Get configuration for unicast mode.
     */
    public static ServerConfig getUnicastConfig() {
        return new Builder()
            .useMulticast(false)
            .useBroadcast(false)
            .build();
    }

    /**
     * Validate configuration.
     */
    public boolean validate() {
        if (udpPort < 1024 || udpPort > 65535) {
            System.err.println("Invalid UDP port: " + udpPort);
            return false;
        }

        if (sendIntervalMs < 1 || sendIntervalMs > 100) {
            System.err.println("Invalid send interval: " + sendIntervalMs);
            return false;
        }

        if (packetQueueSize < 10 || packetQueueSize > 10000) {
            System.err.println("Invalid queue size: " + packetQueueSize);
            return false;
        }

        if (useMulticast && (multicastAddress == null || multicastAddress.isEmpty())) {
            System.err.println("Multicast address required for multicast mode");
            return false;
        }

        if (audioSourceType == AudioSourceType.FILE && (audioFilePath == null || audioFilePath.isEmpty())) {
            System.err.println("Audio file path required when audioSourceType = FILE");
            return false;
        }

        return true;
    }

    /**
     * Get estimated bandwidth in kbps.
     */
    public int getEstimatedBandwidthKbps() {
        // G.726 32 kbps + UDP/IP overhead
        int adpcmBitrate = audioConfig.getAdpcmBitRate(); // 32 kbps
        int overhead = 28 * 8 * (1000 / sendIntervalMs); // UDP/IP header overhead (28 bytes)
        return (adpcmBitrate + overhead) / 1000;
    }

    /**
     * Builder pattern untuk ServerConfig.
     */
    public static class Builder {
        private final ServerConfig config;

        public Builder() {
            config = new ServerConfig();

            // Set defaults
            config.udpPort = DEFAULT_UDP_PORT;
            config.ttl = DEFAULT_TTL;
            config.multicastAddress = DEFAULT_MULTICAST_ADDRESS;
            config.useMulticast = DEFAULT_USE_MULTICAST;
            config.useBroadcast = DEFAULT_USE_BROADCAST;
            config.audioConfig = AudioConfig.getDefaultConfig();
            config.codecType = DEFAULT_CODEC_TYPE;
            config.audioSourceType = DEFAULT_AUDIO_SOURCE_TYPE;
            config.audioFilePath = null;
            config.packetQueueSize = DEFAULT_PACKET_QUEUE_SIZE;
            config.sendIntervalMs = DEFAULT_SEND_INTERVAL_MS;
            config.sendHeartbeat = DEFAULT_SEND_HEARTBEAT;
            config.heartbeatIntervalMs = DEFAULT_HEARTBEAT_INTERVAL_MS;
            config.enableChecksum = DEFAULT_ENABLE_CHECKSUM;
            config.enableCompression = DEFAULT_ENABLE_COMPRESSION;
            config.verboseLogging = false;
            config.saveRawPCM = false;
            config.saveADPCM = false;
        }

        public Builder udpPort(int port) {
            config.udpPort = port;
            return this;
        }

        public Builder ttl(int ttl) {
            config.ttl = ttl;
            return this;
        }

        public Builder multicastAddress(String address) {
            config.multicastAddress = address;
            return this;
        }

        public Builder useMulticast(boolean use) {
            config.useMulticast = use;
            return this;
        }

        public Builder useBroadcast(boolean use) {
            config.useBroadcast = use;
            return this;
        }

        public Builder audioConfig(AudioConfig audioConfig) {
            config.audioConfig = audioConfig;
            return this;
        }

        /** Pilih algoritma kompresi: ADPCM_G726 (default) atau DPCM (pembanding). */
        public Builder codecType(CodecType type) {
            config.codecType = type;
            return this;
        }

        /** Pilih sumber audio: MICROPHONE (default, live) atau FILE (terkontrol). */
        public Builder audioSourceType(AudioSourceType type) {
            config.audioSourceType = type;
            return this;
        }

        /** Wajib diisi jika ingin memakai FILE. Path ke file PCM 16-bit mono, otomatis set audioSourceType = FILE. */
        public Builder audioFilePath(String path) {
            config.audioFilePath = path;
            config.audioSourceType = AudioSourceType.FILE;
            return this;
        }

        public Builder sampleRate(int sampleRate) {
            config.audioConfig = new AudioConfig(sampleRate,
                config.audioConfig.getBitDepth(),
                config.audioConfig.getChannels(),
                config.audioConfig.getFrameSizeMs());
            return this;
        }

        public Builder frameSizeMs(int frameSizeMs) {
            config.audioConfig = new AudioConfig(
                config.audioConfig.getSampleRate(),
                config.audioConfig.getBitDepth(),
                config.audioConfig.getChannels(),
                frameSizeMs);
            return this;
        }

        public Builder packetQueueSize(int size) {
            config.packetQueueSize = size;
            return this;
        }

        public Builder sendIntervalMs(int interval) {
            config.sendIntervalMs = interval;
            return this;
        }

        public Builder sendHeartbeat(boolean send) {
            config.sendHeartbeat = send;
            return this;
        }

        public Builder heartbeatIntervalMs(int interval) {
            config.heartbeatIntervalMs = interval;
            return this;
        }

        public Builder enableChecksum(boolean enable) {
            config.enableChecksum = enable;
            return this;
        }

        public Builder enableCompression(boolean enable) {
            config.enableCompression = enable;
            return this;
        }

        public Builder verboseLogging(boolean verbose) {
            config.verboseLogging = verbose;
            return this;
        }

        public Builder saveRawPCM(String path) {
            config.saveRawPCM = true;
            config.pcmOutputPath = path;
            return this;
        }

        public Builder saveADPCM(String path) {
            config.saveADPCM = true;
            config.adpcmOutputPath = path;
            return this;
        }

        public ServerConfig build() {
            if (!config.validate()) {
                throw new IllegalStateException("Invalid server configuration");
            }
            return config;
        }
    }

    // Getters
    public int getUdpPort() { return udpPort; }
    public int getTtl() { return ttl; }
    public String getMulticastAddress() { return multicastAddress; }
    public boolean isUseMulticast() { return useMulticast; }
    public boolean isUseBroadcast() { return useBroadcast; }
    public AudioConfig getAudioConfig() { return audioConfig; }
    public CodecType getCodecType() { return codecType; }
    public AudioSourceType getAudioSourceType() { return audioSourceType; }
    public String getAudioFilePath() { return audioFilePath; }
    public int getPacketQueueSize() { return packetQueueSize; }
    public int getSendIntervalMs() { return sendIntervalMs; }
    public boolean isSendHeartbeat() { return sendHeartbeat; }
    public int getHeartbeatIntervalMs() { return heartbeatIntervalMs; }
    public boolean isEnableChecksum() { return enableChecksum; }
    public boolean isEnableCompression() { return enableCompression; }
    public boolean isVerboseLogging() { return verboseLogging; }
    public boolean isSaveRawPCM() { return saveRawPCM; }
    public boolean isSaveADPCM() { return saveADPCM; }
    public String getPcmOutputPath() { return pcmOutputPath; }
    public String getAdpcmOutputPath() { return adpcmOutputPath; }

    @Override
    public String toString() {
        return String.format(
            "ServerConfig[port=%d, mode=%s, codec=%s, source=%s, audio=%s, queue=%d, interval=%dms, bandwidth=%dkbps]",
            udpPort,
            useMulticast ? "MULTICAST" : (useBroadcast ? "BROADCAST" : "UNICAST"),
            codecType,
            audioSourceType,
            audioConfig,
            packetQueueSize,
            sendIntervalMs,
            getEstimatedBandwidthKbps()
        );
    }
}