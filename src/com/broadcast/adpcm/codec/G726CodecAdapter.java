package com.broadcast.adpcm.codec;

/**
 * G726CodecAdapter.java
 *
 * Membungkus G726Codec (stateless, static) + G726State (state per-sesi)
 * ke antarmuka AudioCodec, tanpa mengubah G726Codec/G726State yang sudah
 * divalidasi terhadap trace Bab 3.
 */
public final class G726CodecAdapter implements AudioCodec {

    private final G726State state = new G726State();

    @Override
    public int encode(int pcm16) {
        return G726Codec.encode(pcm16, state);
    }

    @Override
    public int getBitsPerSample() {
        return 4;
    }

    @Override
    public String getName() {
        return "ADPCM-G726";
    }
}