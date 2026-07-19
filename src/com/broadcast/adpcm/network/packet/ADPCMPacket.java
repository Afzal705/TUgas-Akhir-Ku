package com.broadcast.adpcm.network.packet;

import java.util.Arrays;

/**
 * ADPCMPacket - Representasi struktur paket ADPCM untuk broadcast.
 * Format paket:
 * - Header (12 bytes):
 *   - magic_number (2 bytes): 0xAD 0x50 ("ADP")
 *   - version (1 byte): protocol version (1)
 *   - packet_type (1 byte): 0x00=audio, 0x01=control, 0x02=heartbeat
 *   - sequence_number (4 bytes): incremental sequence number
 *   - timestamp (4 bytes): timestamp in milliseconds
 *   - payload_length (2 bytes): length of ADPCM payload
 * - Payload (variable): ADPCM encoded data (max 65507 - 12 = 65495 bytes)
 */
public final class ADPCMPacket {
    
    // Constants
    public static final short MAGIC_NUMBER = (short) 0xAD50;  // "ADP"
    public static final byte PROTOCOL_VERSION = 1;
    
    // Header size in bytes
    public static final int HEADER_SIZE = 14;
    
    // Maximum payload size (max UDP packet 65507 - header)
    public static final int MAX_PAYLOAD_SIZE = 65507 - HEADER_SIZE;
    
    // Packet types
    public static final byte TYPE_AUDIO = 0x00;
    public static final byte TYPE_CONTROL = 0x01;
    public static final byte TYPE_HEARTBEAT = 0x02;
    public static final byte TYPE_SYNC = 0x03;
    
    // Control subtypes
    public static final byte CONTROL_START_STREAM = 0x10;
    public static final byte CONTROL_STOP_STREAM = 0x11;
    public static final byte CONTROL_PLAYBACK_SYNC = 0x12;
    
    // Packet fields
    private final short magicNumber;
    private final byte version;
    private final byte packetType;
    private final int sequenceNumber;
    private final int timestamp;
    private final int payloadLength;
    private final byte[] payload;
    
    // Optional control data
    private byte controlSubType;
    private byte[] controlData;
    
    /**
     * Constructor untuk audio packet.
     */
    public ADPCMPacket(int sequenceNumber, int timestamp, byte[] payload) {
        this(sequenceNumber, timestamp, payload, 0, payload != null ? payload.length : 0);
    }
    
    /**
     * Constructor untuk audio packet dengan offset.
     */
    public ADPCMPacket(int sequenceNumber, int timestamp, byte[] payload, int offset, int length) {
        this.magicNumber = MAGIC_NUMBER;
        this.version = PROTOCOL_VERSION;
        this.packetType = TYPE_AUDIO;
        this.sequenceNumber = sequenceNumber;
        this.timestamp = timestamp;
        this.payloadLength = length;
        this.payload = new byte[length];
        
        if (payload != null && length > 0) {
            System.arraycopy(payload, offset, this.payload, 0, length);
        }
        
        this.controlSubType = 0;
        this.controlData = null;
    }
    
    /**
     * Constructor untuk control packet.
     */
    public ADPCMPacket(int sequenceNumber, int timestamp, byte controlSubType, byte[] controlData) {
        this.magicNumber = MAGIC_NUMBER;
        this.version = PROTOCOL_VERSION;
        this.packetType = TYPE_CONTROL;
        this.sequenceNumber = sequenceNumber;
        this.timestamp = timestamp;
        this.controlSubType = controlSubType;
        this.controlData = controlData != null ? controlData.clone() : new byte[0];
        this.payloadLength = this.controlData.length + 1; // +1 for controlSubType
        this.payload = null;
    }
    
    /**
     * Constructor untuk heartbeat packet.
     */
    public ADPCMPacket(int sequenceNumber, int timestamp) {
        this.magicNumber = MAGIC_NUMBER;
        this.version = PROTOCOL_VERSION;
        this.packetType = TYPE_HEARTBEAT;
        this.sequenceNumber = sequenceNumber;
        this.timestamp = timestamp;
        this.payloadLength = 0;
        this.payload = null;
        this.controlSubType = 0;
        this.controlData = null;
    }
    
    /**
     * Create packet from raw bytes (deserialization).
     */
    public ADPCMPacket(byte[] rawData) throws IllegalArgumentException {
        if (rawData == null || rawData.length < HEADER_SIZE) {
            throw new IllegalArgumentException("Invalid packet data: too short");
        }
        
        // Parse header
        int offset = 0;
        
        // Magic number (2 bytes)
        this.magicNumber = (short) (((rawData[offset] & 0xFF) << 8) | (rawData[offset + 1] & 0xFF));
        offset += 2;
        
        if (magicNumber != MAGIC_NUMBER) {
            throw new IllegalArgumentException("Invalid magic number: " + String.format("0x%04X", magicNumber));
        }
        
        // Version (1 byte)
        this.version = rawData[offset++];
        
        if (version != PROTOCOL_VERSION) {
            throw new IllegalArgumentException("Unsupported protocol version: " + version);
        }
        
        // Packet type (1 byte)
        this.packetType = rawData[offset++];
        
        // Sequence number (4 bytes)
        this.sequenceNumber = ((rawData[offset] & 0xFF) << 24) |
                              ((rawData[offset + 1] & 0xFF) << 16) |
                              ((rawData[offset + 2] & 0xFF) << 8) |
                              (rawData[offset + 3] & 0xFF);
        offset += 4;
        
        // Timestamp (4 bytes)
        this.timestamp = ((rawData[offset] & 0xFF) << 24) |
                         ((rawData[offset + 1] & 0xFF) << 16) |
                         ((rawData[offset + 2] & 0xFF) << 8) |
                         (rawData[offset + 3] & 0xFF);
        offset += 4;
        
        // Payload length (2 bytes)
        this.payloadLength = ((rawData[offset] & 0xFF) << 8) | (rawData[offset + 1] & 0xFF);
        offset += 2;
        
        // Validate payload length
        if (offset + payloadLength > rawData.length) {
            throw new IllegalArgumentException("Invalid payload length: " + payloadLength);
        }
        
        // Parse payload based on packet type
        if (packetType == TYPE_AUDIO) {
            // Audio payload
            this.payload = new byte[payloadLength];
            System.arraycopy(rawData, offset, this.payload, 0, payloadLength);
            this.controlSubType = 0;
            this.controlData = null;
            
        } else if (packetType == TYPE_CONTROL) {
            // Control packet: first byte is controlSubType, rest is control data
            if (payloadLength < 1) {
                throw new IllegalArgumentException("Control packet too short");
            }
            this.controlSubType = rawData[offset];
            int dataLength = payloadLength - 1;
            this.controlData = new byte[dataLength];
            if (dataLength > 0) {
                System.arraycopy(rawData, offset + 1, this.controlData, 0, dataLength);
            }
            this.payload = null;
            
        } else if (packetType == TYPE_HEARTBEAT) {
            // Heartbeat has no payload
            this.payload = null;
            this.controlSubType = 0;
            this.controlData = null;
            
        } else {
            throw new IllegalArgumentException("Unknown packet type: " + packetType);
        }
    }
    
    /**
     * Serialize packet to byte array.
     */
    public byte[] toBytes() {
        int totalSize = HEADER_SIZE + getPayloadBytesLength();
        byte[] data = new byte[totalSize];
        int offset = 0;
        
        // Magic number (2 bytes)
        data[offset] = (byte) ((magicNumber >> 8) & 0xFF);
        data[offset + 1] = (byte) (magicNumber & 0xFF);
        offset += 2;
        
        // Version (1 byte)
        data[offset++] = version;
        
        // Packet type (1 byte)
        data[offset++] = packetType;
        
        // Sequence number (4 bytes)
        data[offset] = (byte) ((sequenceNumber >> 24) & 0xFF);
        data[offset + 1] = (byte) ((sequenceNumber >> 16) & 0xFF);
        data[offset + 2] = (byte) ((sequenceNumber >> 8) & 0xFF);
        data[offset + 3] = (byte) (sequenceNumber & 0xFF);
        offset += 4;
        
        // Timestamp (4 bytes)
        data[offset] = (byte) ((timestamp >> 24) & 0xFF);
        data[offset + 1] = (byte) ((timestamp >> 16) & 0xFF);
        data[offset + 2] = (byte) ((timestamp >> 8) & 0xFF);
        data[offset + 3] = (byte) (timestamp & 0xFF);
        offset += 4;
        
        // Payload length (2 bytes)
        int payloadBytesLen = getPayloadBytesLength();
        data[offset] = (byte) ((payloadBytesLen >> 8) & 0xFF);
        data[offset + 1] = (byte) (payloadBytesLen & 0xFF);
        offset += 2;
        
        // Payload
        if (packetType == TYPE_AUDIO && payload != null) {
            System.arraycopy(payload, 0, data, offset, payload.length);
        } else if (packetType == TYPE_CONTROL) {
            data[offset] = controlSubType;
            if (controlData != null && controlData.length > 0) {
                System.arraycopy(controlData, 0, data, offset + 1, controlData.length);
            }
        }
        
        return data;
    }
    
    /**
     * Get payload bytes length for serialization.
     */
    private int getPayloadBytesLength() {
        if (packetType == TYPE_AUDIO) {
            return payload != null ? payload.length : 0;
        } else if (packetType == TYPE_CONTROL) {
            return 1 + (controlData != null ? controlData.length : 0);
        } else {
            return 0;
        }
    }
    
    /**
     * Check if packet is valid.
     */
    public boolean isValid() {
        if (magicNumber != MAGIC_NUMBER) return false;
        if (version != PROTOCOL_VERSION) return false;
        if (packetType != TYPE_AUDIO && packetType != TYPE_CONTROL && packetType != TYPE_HEARTBEAT) {
            return false;
        }
        if (payloadLength > MAX_PAYLOAD_SIZE) return false;
        
        if (packetType == TYPE_AUDIO) {
            return payload != null && payload.length == payloadLength;
        } else if (packetType == TYPE_CONTROL) {
            return (controlData == null && payloadLength == 1) || 
                   (controlData != null && controlData.length == payloadLength - 1);
        }
        
        return true;
    }
    
    /**
     * Create start stream control packet.
     */
    public static ADPCMPacket createStartStreamPacket(int sequenceNumber, int timestamp, 
                                                       int sampleRate, int bitDepth, int channels) {
        byte[] controlData = new byte[12];
        // Sample rate (4 bytes)
        controlData[0] = (byte) ((sampleRate >> 24) & 0xFF);
        controlData[1] = (byte) ((sampleRate >> 16) & 0xFF);
        controlData[2] = (byte) ((sampleRate >> 8) & 0xFF);
        controlData[3] = (byte) (sampleRate & 0xFF);
        // Bit depth (4 bytes)
        controlData[4] = (byte) ((bitDepth >> 24) & 0xFF);
        controlData[5] = (byte) ((bitDepth >> 16) & 0xFF);
        controlData[6] = (byte) ((bitDepth >> 8) & 0xFF);
        controlData[7] = (byte) (bitDepth & 0xFF);
        // Channels (4 bytes)
        controlData[8] = (byte) ((channels >> 24) & 0xFF);
        controlData[9] = (byte) ((channels >> 16) & 0xFF);
        controlData[10] = (byte) ((channels >> 8) & 0xFF);
        controlData[11] = (byte) (channels & 0xFF);
        
        return new ADPCMPacket(sequenceNumber, timestamp, CONTROL_START_STREAM, controlData);
    }
    
    /**
     * Create stop stream control packet.
     */
    public static ADPCMPacket createStopStreamPacket(int sequenceNumber, int timestamp) {
        return new ADPCMPacket(sequenceNumber, timestamp, CONTROL_STOP_STREAM, null);
    }
    
    // Getters
    public short getMagicNumber() { return magicNumber; }
    public byte getVersion() { return version; }
    public byte getPacketType() { return packetType; }
    public int getSequenceNumber() { return sequenceNumber; }
    public int getTimestamp() { return timestamp; }
    public int getPayloadLength() { return payloadLength; }
    public byte[] getPayload() { return payload != null ? payload.clone() : null; }
    public byte getControlSubType() { return controlSubType; }
    public byte[] getControlData() { return controlData != null ? controlData.clone() : null; }
    
    public boolean isAudioPacket() { return packetType == TYPE_AUDIO; }
    public boolean isControlPacket() { return packetType == TYPE_CONTROL; }
    public boolean isHeartbeatPacket() { return packetType == TYPE_HEARTBEAT; }
    
    @Override
    public String toString() {
        return String.format(
            "ADPCMPacket[type=%s, seq=%d, ts=%d, len=%d]",
            packetType == TYPE_AUDIO ? "AUDIO" : 
            (packetType == TYPE_CONTROL ? "CTRL" : "HB"),
            sequenceNumber, timestamp, payloadLength
        );
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ADPCMPacket)) return false;
        ADPCMPacket other = (ADPCMPacket) obj;
        return sequenceNumber == other.sequenceNumber && 
               timestamp == other.timestamp &&
               packetType == other.packetType &&
               Arrays.equals(payload, other.payload);
    }
    
    @Override
    public int hashCode() {
        return sequenceNumber * 31 + timestamp;
    }
}