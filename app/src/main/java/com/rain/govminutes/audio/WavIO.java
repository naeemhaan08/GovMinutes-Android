package com.rain.govminutes.audio;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class WavIO {
    private WavIO() {}
    public static void writeHeader(RandomAccessFile out, int sampleRate, int channels, long pcmBytes) throws IOException { int bits = 16; long byteRate = (long) sampleRate * channels * bits / 8; out.seek(0); out.writeBytes("RIFF"); writeLE32(out, 36 + pcmBytes); out.writeBytes("WAVEfmt "); writeLE32(out, 16); writeLE16(out, 1); writeLE16(out, channels); writeLE32(out, sampleRate); writeLE32(out, byteRate); writeLE16(out, channels * bits / 8); writeLE16(out, bits); out.writeBytes("data"); writeLE32(out, pcmBytes); }
    public static short[] readPcm16(File wav) throws IOException { try (RandomAccessFile raf = new RandomAccessFile(wav, "r")) { if (raf.length() < 44) throw new IOException("Invalid WAV"); raf.seek(44); int count = (int) ((raf.length() - 44) / 2); byte[] b = new byte[count * 2]; raf.readFully(b); short[] s = new short[count]; ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN); for (int i = 0; i < count; i++) s[i] = bb.getShort(); return s; } }
    public static void writePcm16(File wav, int sampleRate, short[] samples) throws IOException { try (RandomAccessFile raf = new RandomAccessFile(wav, "rw")) { raf.setLength(0); writeHeader(raf, sampleRate, 1, (long) samples.length * 2); ByteBuffer bb = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN); for (short s : samples) bb.putShort(s); raf.write(bb.array()); } }
    private static void writeLE16(RandomAccessFile f, long v) throws IOException { f.write((int)(v & 0xff)); f.write((int)((v >> 8) & 0xff)); }
    private static void writeLE32(RandomAccessFile f, long v) throws IOException { f.write((int)(v & 0xff)); f.write((int)((v >> 8) & 0xff)); f.write((int)((v >> 16) & 0xff)); f.write((int)((v >> 24) & 0xff)); }
}
