package main.java.com.broadcast.adpcm.network.packet;

/**
 * SimplePacketFormatter - Format paket sesuai spesifikasi skripsi:
 * 
 * STRUKTUR PAKET (12 byte header):
 * ┌─────────────────────────────────────────────────────────────┐
 * │ 0-7   : Server Send Timestamp (8 bytes, big-endian, ms)     │
 * │ 8-11  : Sequence Number (4 bytes, big-endian)               │
 * │ 12-end: ADPCM Audio Payload (hasil encoding G.726)          │
 * └─────────────────────────────────────────────────────────────┘
 * 
 * Total ukuran maksimum: 65507 bytes (batas UDP)
 */
public final class SimplePacketFormatter {
    
    public static final int HEADER_SIZE = 14;  // 8 + 4
    public static final int MAX_PAYLOAD = 65507 - HEADER_SIZE;
    
    private SimplePacketFormatter() {
        // Utility class - no instantiation
    }
    
    /**
     * Build packet dari komponen.
     * 
     * @param timestamp Server Send Timestamp (ms dari System.currentTimeMillis())
     * @param seqNum Sequence Number (32-bit, wrap around allowed)
     * @param adpcmPayload ADPCM encoded data (hasil G.726)
     * @return Raw packet bytes siap kirim via UDP
     */
    public static byte[] buildPacket(long timestamp, int seqNum, byte[] adpcmPayload) {
        if (adpcmPayload == null) {
            adpcmPayload = new byte[0];
        }
        
        if (adpcmPayload.length > MAX_PAYLOAD) {
            throw new IllegalArgumentException(
                "Payload too large: " + adpcmPayload.length + " > " + MAX_PAYLOAD
            );
        }
        
        byte[] packet = new byte[HEADER_SIZE + adpcmPayload.length];
        
        // ===== 8 byte Timestamp (big-endian, millisecond resolution) =====
        packet[0] = (byte) (timestamp >> 56);
        packet[1] = (byte) (timestamp >> 48);
        packet[2] = (byte) (timestamp >> 40);
        packet[3] = (byte) (timestamp >> 32);
        packet[4] = (byte) (timestamp >> 24);
        packet[5] = (byte) (timestamp >> 16);
        packet[6] = (byte) (timestamp >> 8);
        packet[7] = (byte) timestamp;
        
        // ===== 4 byte Sequence Number (big-endian) =====
        packet[8] = (byte) (seqNum >> 24);
        packet[9] = (byte) (seqNum >> 16);
        packet[10] = (byte) (seqNum >> 8);
        packet[11] = (byte) seqNum;
        
        // ===== ADPCM Payload =====
        System.arraycopy(adpcmPayload, 0, packet, HEADER_SIZE, adpcmPayload.length);
        
        return packet;
    }
    
    /**
     * Parse packet yang diterima (untuk client nanti).
     * 
     * @param packet Raw packet bytes
     * @return PacketInfo berisi timestamp, sequence number, dan payload
     */
    public static PacketInfo parsePacket(byte[] packet) {
        if (packet == null || packet.length < HEADER_SIZE) {
            throw new IllegalArgumentException("Invalid packet: too short");
        }
        
        // Extract timestamp
        long timestamp = 0;
        for (int i = 0; i < 8; i++) {
            timestamp = (timestamp << 8) | (packet[i] & 0xFF);
        }
        
        // Extract sequence number
        int seqNum = 0;
        for (int i = 8; i < 12; i++) {
            seqNum = (seqNum << 8) | (packet[i] & 0xFF);
        }
        
        // Extract payload
        byte[] payload = new byte[packet.length - HEADER_SIZE];
        System.arraycopy(packet, HEADER_SIZE, payload, 0, payload.length);
        
        return new PacketInfo(timestamp, seqNum, payload);
    }
    
    /**
     * Extract timestamp dari packet (tanpa parsing penuh, lebih cepat).
     */
    public static long extractTimestamp(byte[] packet) {
        if (packet.length < 8) return -1;
        long timestamp = 0;
        for (int i = 0; i < 8; i++) {
            timestamp = (timestamp << 8) | (packet[i] & 0xFF);
        }
        return timestamp;
    }
    
    /**
     * Extract sequence number dari packet (tanpa parsing penuh).
     */
    public static int extractSequenceNumber(byte[] packet) {
        if (packet.length < 12) return -1;
        int seqNum = 0;
        for (int i = 8; i < 12; i++) {
            seqNum = (seqNum << 8) | (packet[i] & 0xFF);
        }
        return seqNum;
    }
    
    /**
     * Get header size in bytes.
     */
    public static int getHeaderSize() {
        return HEADER_SIZE;
    }
    
    /**
     * Get maximum payload size.
     */
    public static int getMaxPayloadSize() {
        return MAX_PAYLOAD;
    }
    
    /**
     * PacketInfo - Hasil parsing packet.
     */
    public static class PacketInfo {
        public final long timestamp;
        public final int sequenceNumber;
        public final byte[] payload;
        
        public PacketInfo(long timestamp, int sequenceNumber, byte[] payload) {
            this.timestamp = timestamp;
            this.sequenceNumber = sequenceNumber;
            this.payload = payload;
        }
        
        @Override
        public String toString() {
            return String.format("PacketInfo[ts=%d, seq=%d, payloadLen=%d]",
                timestamp, sequenceNumber, payload.length);
        }
    }
}