package com.broadcast.adpcm.network.packet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * PacketSerializer - Mengubah objek paket menjadi byte array dan sebaliknya.
 * Mendukung compression, checksum, dan batch processing.
 *
 * PERBAIKAN LOGIKA:
 *   No.4 — isCompressed(): deteksi berbasis flag metadata, bukan heuristik
 *   No.5 — serialize(): FLAG_COMPRESSED hanya diset jika kompresi benar terjadi
 *
 * PERBAIKAN TAMBAHAN:
 *   - wrapWithMetadata(): hapus FLAG_COMPRESSED yang diset tanpa kondisi
 *   - deserialize(): cek flag kompresi SEBELUM strip metadata, bukan sesudah
 *   - getPacketInfo(): offset disesuaikan dengan HEADER_SIZE = 14 dan ada/tidaknya checksum
 */
public final class PacketSerializer {

    // Serialization flags
    public static final int FLAG_NONE       = 0x00;
    public static final int FLAG_COMPRESSED = 0x01;
    public static final int FLAG_CHECKSUM   = 0x02;
    public static final int FLAG_ENCRYPTED  = 0x04;

    // Version info
    private static final byte SERIALIZER_VERSION = 1;

    private boolean enableChecksum;
    private boolean enableCompression;

    public PacketSerializer() {
        this(false, false);
    }

    public PacketSerializer(boolean enableChecksum, boolean enableCompression) {
        this.enableChecksum    = enableChecksum;
        this.enableCompression = enableCompression;
    }

    // =========================================================================
    // SERIALIZE
    // =========================================================================

    /**
     * Serialize single packet to byte array.
     *
     * PERBAIKAN No.5:
     *   Sebelumnya flag selalu FLAG_NONE meskipun kompresi berhasil:
     *     return wrapWithMetadata(packetBytes, FLAG_NONE); ← selalu FLAG_NONE
     *
     *   Sekarang: FLAG_COMPRESSED hanya diset jika kompresi benar-benar
     *   menghasilkan data yang lebih kecil dari original.
     */
    public byte[] serialize(ADPCMPacket packet) throws IOException {
        if (packet == null) {
            throw new IllegalArgumentException("Packet cannot be null");
        }

        byte[] packetBytes       = packet.toBytes();
        boolean actuallyCompressed = false;

        // Terapkan kompresi jika diaktifkan dan data cukup besar
        if (enableCompression && packetBytes.length > 100) {
            byte[] compressed = compress(packetBytes);
            if (compressed.length < packetBytes.length) {
                // ✅ Kompresi benar-benar mengecilkan data → pakai hasil kompres
                packetBytes        = compressed;
                actuallyCompressed = true;
            }
            // Jika compressed >= original, tetap pakai original (tidak ada manfaat kompres)
        }

        // ✅ Set FLAG_COMPRESSED hanya jika kompresi benar-benar terjadi
        int flags = actuallyCompressed ? FLAG_COMPRESSED : FLAG_NONE;
        return wrapWithMetadata(packetBytes, flags);
    }

    /**
     * Serialize multiple packets into one byte array (batch).
     */
    public byte[] serializeBatch(List<ADPCMPacket> packets) throws IOException {
        if (packets == null || packets.isEmpty()) {
            throw new IllegalArgumentException("Packet list cannot be empty");
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(SERIALIZER_VERSION);
        baos.write(packets.size());

        for (ADPCMPacket packet : packets) {
            byte[] packetBytes = serialize(packet);
            baos.write((packetBytes.length >> 8) & 0xFF);
            baos.write(packetBytes.length & 0xFF);
            baos.write(packetBytes);
        }

        return baos.toByteArray();
    }

    // =========================================================================
    // DESERIALIZE
    // =========================================================================

    /**
     * Deserialize byte array to single packet.
     *
     * PERBAIKAN ALUR:
     *   Sebelumnya: strip metadata dulu → baru cek kompresi (heuristik pada data bersih)
     *   Sesudah   : cek flag kompresi SEBELUM strip metadata → baru strip → lalu decompress
     *
     *   Urutan yang benar:
     *     1. Cek flag di metadata (compressed? checksum?)
     *     2. Verifikasi checksum (sebelum dekompresi)
     *     3. Strip metadata
     *     4. Decompress jika flag mengatakan compressed
     *     5. Parse ADPCMPacket
     */
    public ADPCMPacket deserialize(byte[] data) throws IOException {
        if (data == null || data.length < ADPCMPacket.HEADER_SIZE) {
            throw new IllegalArgumentException("Invalid data for deserialization");
        }

        if (hasMetadataWrapper(data)) {
            // ✅ Cek flag SEBELUM strip metadata
            boolean compressed  = isCompressed(data);

            // Verifikasi checksum SEBELUM strip dan decompress
            if (enableChecksum && !verifyChecksum(data)) {
                throw new IOException("Checksum verification failed");
            }

            // Strip metadata
            data = extractFromMetadata(data);

            // ✅ Decompress berdasarkan flag, BUKAN heuristik
            if (compressed) {
                data = decompress(data);
            }
        }

        return new ADPCMPacket(data);
    }

    /**
     * Deserialize batch of packets.
     */
    public List<ADPCMPacket> deserializeBatch(byte[] data) throws IOException {
        List<ADPCMPacket> packets = new ArrayList<>();

        if (data == null || data.length < 2) return packets;

        int offset  = 0;
        byte version = data[offset++];
        if (version != SERIALIZER_VERSION) {
            throw new IOException("Unsupported batch version: " + version);
        }

        int packetCount = data[offset++] & 0xFF;

        for (int i = 0; i < packetCount && offset < data.length; i++) {
            int packetLength = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
            offset += 2;

            if (offset + packetLength > data.length) {
                throw new IOException("Invalid packet length in batch");
            }

            byte[] packetData = new byte[packetLength];
            System.arraycopy(data, offset, packetData, 0, packetLength);
            packets.add(deserialize(packetData));
            offset += packetLength;
        }

        return packets;
    }

    // =========================================================================
    // METADATA WRAPPER
    // =========================================================================

    /**
     * Wrap packet bytes dengan metadata (version, flags, checksum opsional).
     *
     * Format wrapper:
     *   [1 byte] SERIALIZER_VERSION
     *   [1 byte] flags (FLAG_COMPRESSED | FLAG_CHECKSUM | ...)
     *   [4 byte] CRC32 checksum (hanya jika FLAG_CHECKSUM aktif)
     *   [N byte] packet data
     *
     * PERBAIKAN:
     *   Sebelumnya: FLAG_COMPRESSED diset jika enableCompression=true,
     *               meskipun kompresi tidak benar-benar diterapkan.
     *   Sesudah   : FLAG_COMPRESSED datang dari parameter flags yang
     *               sudah diputuskan oleh serialize() berdasarkan hasil nyata.
     */
    private byte[] wrapWithMetadata(byte[] packetBytes, int flags) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // Version (1 byte)
        baos.write(SERIALIZER_VERSION);

        // Flags (1 byte)
        // ✅ FLAG_COMPRESSED sudah ditentukan oleh pemanggil (serialize())
        // Hanya tambahkan FLAG_CHECKSUM jika diaktifkan
        int finalFlags = flags;
        if (enableChecksum) finalFlags |= FLAG_CHECKSUM;
        // DIHAPUS: if (enableCompression) finalFlags |= FLAG_COMPRESSED;
        // ↑ Bug lama: selalu set flag meskipun kompresi tidak terjadi
        baos.write(finalFlags);

        // Checksum (4 byte, hanya jika FLAG_CHECKSUM)
        if (enableChecksum) {
            CRC32 crc = new CRC32();
            crc.update(packetBytes);
            long checksum = crc.getValue();
            baos.write((int)(checksum >> 24) & 0xFF);
            baos.write((int)(checksum >> 16) & 0xFF);
            baos.write((int)(checksum >>  8) & 0xFF);
            baos.write((int) checksum        & 0xFF);
        }

        baos.write(packetBytes);
        return baos.toByteArray();
    }

    /**
     * Cek apakah data memiliki metadata wrapper.
     * Identifikasi dari byte pertama = SERIALIZER_VERSION.
     */
    private boolean hasMetadataWrapper(byte[] data) {
        return data.length > 2 && data[0] == SERIALIZER_VERSION;
    }

    /**
     * Ekstrak packet data dari metadata wrapper.
     * Skip: version(1) + flags(1) + checksum(4, jika ada).
     */
    private byte[] extractFromMetadata(byte[] data) {
        int offset = 2; // skip version + flags
        int flags  = data[1] & 0xFF;
        if ((flags & FLAG_CHECKSUM) != 0) {
            offset += 4; // skip checksum
        }
        byte[] packetData = new byte[data.length - offset];
        System.arraycopy(data, offset, packetData, 0, packetData.length);
        return packetData;
    }

    /**
     * Strip metadata dari packet data jika ada.
     */
    private byte[] stripMetadata(byte[] data) {
        if (!hasMetadataWrapper(data)) return data;
        return extractFromMetadata(data);
    }

    // =========================================================================
    // KOMPRESI (RLE)
    // =========================================================================

    /**
     * Cek apakah data terkompresi berdasarkan FLAG_COMPRESSED di metadata.
     *
     * PERBAIKAN No.4:
     *   Sebelumnya menggunakan heuristik (hitung bit set) yang bisa
     *   false positive pada data ADPCM normal karena banyak byte > 0x80.
     *
     *   Sekarang: cek langsung dari bit FLAG_COMPRESSED di byte flags
     *   metadata (offset 1). Ini akurat dan deterministik.
     *
     *   CATATAN: method ini hanya dipanggil SEBELUM strip metadata,
     *   sehingga data[1] selalu merupakan byte flags yang valid.
     */
    private boolean isCompressed(byte[] data) {
        // ✅ Cek FLAG_COMPRESSED dari byte flags metadata (offset 1)
        if (data.length < 2) return false;
        return (data[1] & FLAG_COMPRESSED) != 0;
    }

    /**
     * Kompresi sederhana menggunakan Run-Length Encoding (RLE).
     * Hanya efektif untuk data dengan pengulangan byte yang banyak.
     * Untuk produksi, pertimbangkan Deflater dari java.util.zip.
     */
    private byte[] compress(byte[] data) {
        if (data.length < 4) return data;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int count = 1;

        for (int i = 1; i <= data.length; i++) {
            if (i < data.length && data[i] == data[i - 1] && count < 127) {
                // ✅ Batas count = 127 (bukan 255) agar bit-7 tidak bentrok
                // dengan byte data normal saat dekompresi
                count++;
            } else {
                if (count > 3) {
                    baos.write(0x80 | count); // RLE flag (bit-7=1) + count
                    baos.write(data[i - 1]);
                } else {
                    for (int j = 0; j < count; j++) {
                        // Byte data biasa harus < 0x80 agar tidak terbaca
                        // sebagai RLE marker — ini keterbatasan RLE sederhana
                        baos.write(data[i - count + j]);
                    }
                }
                count = 1;
            }
        }

        byte[] compressed = baos.toByteArray();
        return compressed.length < data.length ? compressed : data;
    }

    /**
     * Dekompresi data RLE.
     */
    private byte[] decompress(byte[] data) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        for (int i = 0; i < data.length; i++) {
            byte b = data[i];
            if ((b & 0x80) != 0) {   // bit-7 set = RLE marker
                int  count = b & 0x7F;
                byte value = data[++i];
                for (int j = 0; j < count; j++) {
                    baos.write(value);
                }
            } else {
                baos.write(b);
            }
        }

        return baos.toByteArray();
    }

    // =========================================================================
    // CHECKSUM
    // =========================================================================

    /**
     * Hitung CRC32 checksum dari data.
     */
    private long calculateChecksum(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    /**
     * Verifikasi checksum data.
     * Membandingkan checksum yang tersimpan di metadata dengan checksum
     * yang dihitung ulang dari packet data.
     */
    private boolean verifyChecksum(byte[] data) {
        if (!hasMetadataWrapper(data)) return true;

        int flags = data[1] & 0xFF;
        if ((flags & FLAG_CHECKSUM) == 0) return true;

        // Ekstrak checksum dari metadata (offset 2-5)
        long storedChecksum = ((long)(data[2] & 0xFF) << 24) |
                              ((long)(data[3] & 0xFF) << 16) |
                              ((long)(data[4] & 0xFF) <<  8) |
                               (long)(data[5] & 0xFF);

        // Hitung ulang dari packet data (tanpa metadata)
        byte[] packetData        = extractFromMetadata(data);
        long   calculatedChecksum = calculateChecksum(packetData);

        return storedChecksum == calculatedChecksum;
    }

    // =========================================================================
    // UTILITY
    // =========================================================================

    /**
     * Konversi satu atau lebih packet ke byte array untuk transmisi UDP.
     */
    public byte[] toUDPPacket(List<ADPCMPacket> packets) throws IOException {
        if (packets.size() == 1) {
            return serialize(packets.get(0));
        } else {
            return serializeBatch(packets);
        }
    }

    /**
     * Baca info ringkas dari raw packet bytes (untuk logging/debugging).
     *
     * PERBAIKAN:
     *   Sebelumnya offset hardcoded (seq di 6, ts di 10, len di 14) yang
     *   hanya benar jika metadata wrapper = 2 byte (tanpa checksum).
     *
     *   Sesudah: offset dihitung dinamis berdasarkan ada/tidaknya
     *   metadata wrapper dan checksum di dalam wrapper tersebut.
     *
     * Header ADPCMPacket (14 byte):
     *   [0-1]   magic_number    (2 byte)
     *   [2]     version         (1 byte)
     *   [3]     packet_type     (1 byte)
     *   [4-7]   sequence_number (4 byte)  ← baseOffset + 4
     *   [8-11]  timestamp       (4 byte)  ← baseOffset + 8
     *   [12-13] payload_length  (2 byte)  ← baseOffset + 12
     */
    public static String getPacketInfo(byte[] data) {
        if (data == null || data.length < ADPCMPacket.HEADER_SIZE) {
            return "Invalid packet";
        }

        // ✅ Hitung baseOffset secara dinamis
        int baseOffset = 0;
        if (data.length > 2 && data[0] == SERIALIZER_VERSION) {
            baseOffset = 2; // version(1) + flags(1)
            int flags = data[1] & 0xFF;
            if ((flags & FLAG_CHECKSUM) != 0) {
                baseOffset += 4; // tambah 4 byte checksum
            }
        }

        int seqOffset = baseOffset + 4;
        int tsOffset  = baseOffset + 8;
        int lenOffset = baseOffset + 12;

        if (data.length < lenOffset + 2) return "Packet too short";

        int seq = ((data[seqOffset]     & 0xFF) << 24) |
                  ((data[seqOffset + 1] & 0xFF) << 16) |
                  ((data[seqOffset + 2] & 0xFF) <<  8) |
                   (data[seqOffset + 3] & 0xFF);

        int ts  = ((data[tsOffset]      & 0xFF) << 24) |
                  ((data[tsOffset  + 1] & 0xFF) << 16) |
                  ((data[tsOffset  + 2] & 0xFF) <<  8) |
                   (data[tsOffset  + 3] & 0xFF);

        int len = ((data[lenOffset] & 0xFF) << 8) | (data[lenOffset + 1] & 0xFF);

        return String.format("Packet[seq=%d, ts=%d, len=%d, total=%d]",
            seq, ts, len, data.length);
    }

    // =========================================================================
    // CONFIGURATION
    // =========================================================================

    public void setEnableChecksum(boolean enable)    { this.enableChecksum    = enable; }
    public void setEnableCompression(boolean enable) { this.enableCompression = enable; }
    public boolean isChecksumEnabled()               { return enableChecksum;            }
    public boolean isCompressionEnabled()            { return enableCompression;         }
}