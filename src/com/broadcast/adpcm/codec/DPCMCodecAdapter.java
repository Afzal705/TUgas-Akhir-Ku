package com.broadcast.adpcm.codec;

/**
 * DPCMCodecAdapter.java
 *
 * Membungkus DPCMCodec + DPCMState ke antarmuka AudioCodec, sebagai
 * metode pembanding (baseline) sesuai Sub-bab 3.3.3.
 */
public final class DPCMCodecAdapter implements AudioCodec {

    private final DPCMState state = new DPCMState();

    @Override
    public int encode(int pcm16) {
        return DPCMCodec.encode(pcm16, state);
    }

    @Override
    public int getBitsPerSample() {
        return 4;
    }

    @Override
    public String getName() {
        return "DPCM";
    }
}