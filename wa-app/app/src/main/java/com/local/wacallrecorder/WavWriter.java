package com.local.wacallrecorder;

import java.io.File;
import java.io.RandomAccessFile;

public final class WavWriter {
    private final RandomAccessFile raf;
    private final int sampleRate;
    private final short channels;
    private final short bitsPerSample;
    private long dataBytes = 0;

    public WavWriter(File file, int sampleRate, short channels, short bitsPerSample) throws Exception {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.bitsPerSample = bitsPerSample;
        this.raf = new RandomAccessFile(file, "rw");
        raf.setLength(0);
        writeHeader(0);
    }

    public void write(byte[] data, int offset, int length) throws Exception {
        raf.write(data, offset, length);
        dataBytes += length;
    }

    public long getDataBytes() { return dataBytes; }

    public void close() {
        try {
            raf.seek(0);
            writeHeader(dataBytes);
            raf.close();
        } catch (Exception ignored) {}
    }

    private void writeHeader(long pcmBytes) throws Exception {
        long byteRate = (long) sampleRate * channels * bitsPerSample / 8;
        short blockAlign = (short) (channels * bitsPerSample / 8);
        long riffSize = 36 + pcmBytes;

        raf.writeBytes("RIFF");
        writeLEInt((int) riffSize);
        raf.writeBytes("WAVE");
        raf.writeBytes("fmt ");
        writeLEInt(16);
        writeLEShort((short) 1);
        writeLEShort(channels);
        writeLEInt(sampleRate);
        writeLEInt((int) byteRate);
        writeLEShort(blockAlign);
        writeLEShort(bitsPerSample);
        raf.writeBytes("data");
        writeLEInt((int) pcmBytes);
    }

    private void writeLEInt(int v) throws Exception {
        raf.write(v & 0xff);
        raf.write((v >> 8) & 0xff);
        raf.write((v >> 16) & 0xff);
        raf.write((v >> 24) & 0xff);
    }

    private void writeLEShort(short v) throws Exception {
        raf.write(v & 0xff);
        raf.write((v >> 8) & 0xff);
    }
}
