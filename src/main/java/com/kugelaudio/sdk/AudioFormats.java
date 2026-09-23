package com.kugelaudio.sdk;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Audio format utilities: WAV export, PCM16/G.711 conversion, duration calculation.
 */
public final class AudioFormats {

    private AudioFormats() {}

    /**
     * Writes PCM16 audio data as a WAV file.
     */
    public static void writePcm16Wav(Path outPath, byte[] pcm16, int sampleRate, short channels) throws IOException {
        if (outPath.getParent() != null) {
            Files.createDirectories(outPath.getParent());
        }
        int dataLen = pcm16.length;
        int byteRate = sampleRate * channels * 2;
        short blockAlign = (short) (channels * 2);
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes());
        header.putInt(36 + dataLen);
        header.put("WAVE".getBytes());
        header.put("fmt ".getBytes());
        header.putInt(16);
        header.putShort((short) 1);
        header.putShort(channels);
        header.putInt(sampleRate);
        header.putInt(byteRate);
        header.putShort(blockAlign);
        header.putShort((short) 16);
        header.put("data".getBytes());
        header.putInt(dataLen);
        try (OutputStream out = Files.newOutputStream(outPath)) {
            out.write(header.array());
            out.write(pcm16);
        }
    }

    /**
     * Calculates audio duration in milliseconds from PCM data.
     */
    public static int durationMs(byte[] pcm16, int sampleRate, int bitsPerSample, int channels) {
        if (pcm16 == null || sampleRate <= 0 || bitsPerSample <= 0 || channels <= 0) return 0;
        double bytesPerSecond = sampleRate * channels * (bitsPerSample / 8.0d);
        return (int) Math.round((pcm16.length / bytesPerSecond) * 1000.0d);
    }

    /**
     * Converts PCM16 (signed 16-bit little-endian) to u-law encoded audio.
     */
    public static byte[] pcm16ToUlaw(byte[] pcm16) {
        if (pcm16 == null || pcm16.length < 2) return new byte[0];
        byte[] ulaw = new byte[pcm16.length / 2];
        for (int i = 0, j = 0; j < ulaw.length; i += 2, j++) {
            short sample = (short) ((pcm16[i + 1] << 8) | (pcm16[i] & 0xFF));
            ulaw[j] = linearToUlaw(sample);
        }
        return ulaw;
    }

    /**
     * Converts u-law encoded audio to PCM16 (signed 16-bit little-endian).
     */
    public static byte[] ulawToPcm16(byte[] ulaw) {
        if (ulaw == null || ulaw.length == 0) return new byte[0];
        byte[] pcm16 = new byte[ulaw.length * 2];
        for (int i = 0, j = 0; i < ulaw.length; i++, j += 2) {
            short sample = ulawToLinear(ulaw[i]);
            pcm16[j] = (byte) (sample & 0xFF);
            pcm16[j + 1] = (byte) ((sample >>> 8) & 0xFF);
        }
        return pcm16;
    }

    /**
     * Converts PCM16 (signed 16-bit little-endian) to a-law encoded audio.
     */
    public static byte[] pcm16ToAlaw(byte[] pcm16) {
        if (pcm16 == null || pcm16.length < 2) return new byte[0];
        byte[] alaw = new byte[pcm16.length / 2];
        for (int i = 0, j = 0; j < alaw.length; i += 2, j++) {
            short sample = (short) ((pcm16[i + 1] << 8) | (pcm16[i] & 0xFF));
            alaw[j] = linearToAlaw(sample);
        }
        return alaw;
    }

    /**
     * Converts a-law encoded audio to PCM16 (signed 16-bit little-endian).
     */
    public static byte[] alawToPcm16(byte[] alaw) {
        if (alaw == null || alaw.length == 0) return new byte[0];
        byte[] pcm16 = new byte[alaw.length * 2];
        for (int i = 0, j = 0; i < alaw.length; i++, j += 2) {
            short sample = alawToLinear(alaw[i]);
            pcm16[j] = (byte) (sample & 0xFF);
            pcm16[j + 1] = (byte) ((sample >>> 8) & 0xFF);
        }
        return pcm16;
    }

    private static byte linearToUlaw(short sample) {
        final int BIAS = 0x84;
        final int CLIP = 32635;
        int s = sample;
        int sign = (s >> 8) & 0x80;
        if (sign != 0) s = -s;
        if (s > CLIP) s = CLIP;
        s += BIAS;
        int exponent = 7;
        for (int expMask = 0x4000; (s & expMask) == 0 && exponent > 0; exponent--, expMask >>= 1) {}
        int mantissa = (s >> (exponent + 3)) & 0x0F;
        int ulaw = ~(sign | (exponent << 4) | mantissa);
        return (byte) ulaw;
    }

    private static short ulawToLinear(byte ulawByte) {
        int u = ~ulawByte & 0xFF;
        int sign = u & 0x80;
        int exponent = (u >> 4) & 0x07;
        int mantissa = u & 0x0F;
        int sample = ((mantissa << 3) + 0x84) << exponent;
        sample -= 0x84;
        if (sign != 0) sample = -sample;
        return (short) sample;
    }

    private static byte linearToAlaw(short sample) {
        final int[] SEG_END = {0x1F, 0x3F, 0x7F, 0xFF, 0x1FF, 0x3FF, 0x7FF, 0xFFF};
        int pcm = sample >> 3;
        int mask = pcm >= 0 ? 0xD5 : 0x55;
        int magnitude = pcm >= 0 ? pcm : -pcm - 1;

        int segment = 0;
        while (segment < SEG_END.length && magnitude > SEG_END[segment]) {
            segment++;
        }

        int aval;
        if (segment >= 8) {
            aval = 0x7F;
        } else {
            int shift = segment < 2 ? 1 : segment;
            int mantissa = (magnitude >> shift) & 0x0F;
            aval = (segment << 4) | mantissa;
        }

        return (byte) ((aval ^ mask) & 0xFF);
    }

    private static short alawToLinear(byte alawByte) {
        int a = (alawByte ^ 0x55) & 0xFF;
        int segment = (a >> 4) & 0x07;
        int quantization = a & 0x0F;
        int sample = quantization << 4;

        if (segment == 0) {
            sample += 8;
        } else {
            sample += 0x108;
            if (segment > 1) {
                sample <<= segment - 1;
            }
        }

        return (short) ((a & 0x80) != 0 ? sample : -sample);
    }
}
