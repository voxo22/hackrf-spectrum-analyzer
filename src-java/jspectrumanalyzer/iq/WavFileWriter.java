package jspectrumanalyzer.iq;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

class WavFileWriter implements Pcm16AudioSink, AutoCloseable {
	private final RandomAccessFile file;
	private final int channels;
	private final int sampleRateHz;
	private final int bitsPerSample;
	private long dataBytes = 0;
	private boolean closed = false;

	WavFileWriter(File outputFile, int sampleRateHz, int channels, int bitsPerSample) throws IOException {
		this.file = new RandomAccessFile(outputFile, "rw");
		this.file.setLength(0);
		this.sampleRateHz = sampleRateHz;
		this.channels = channels;
		this.bitsPerSample = bitsPerSample;
		writeHeader(0);
	}

	synchronized void write(byte[] data, int offset, int length) throws IOException {
		writePcm16(data, offset, length);
	}

	@Override
	public synchronized void writePcm16(byte[] data, int offset, int length) throws IOException {
		if (closed || data == null || length <= 0) {
			return;
		}
		file.write(data, offset, length);
		dataBytes += length;
	}

	synchronized long getDataBytes() {
		return dataBytes;
	}

	@Override
	public synchronized void close() throws IOException {
		if (closed) {
			return;
		}
		file.seek(0);
		writeHeader(dataBytes);
		file.close();
		closed = true;
	}

	private void writeHeader(long dataSize) throws IOException {
		int blockAlign = channels * bitsPerSample / 8;
		int byteRate = sampleRateHz * blockAlign;
		writeAscii("RIFF");
		writeLeInt((int) Math.min(0xffffffffL, 36L + dataSize));
		writeAscii("WAVE");
		writeAscii("fmt ");
		writeLeInt(16);
		writeLeShort(1);
		writeLeShort(channels);
		writeLeInt(sampleRateHz);
		writeLeInt(byteRate);
		writeLeShort(blockAlign);
		writeLeShort(bitsPerSample);
		writeAscii("data");
		writeLeInt((int) Math.min(0xffffffffL, dataSize));
	}

	private void writeAscii(String value) throws IOException {
		file.write(value.getBytes("US-ASCII"));
	}

	private void writeLeShort(int value) throws IOException {
		file.write(value & 0xff);
		file.write((value >>> 8) & 0xff);
	}

	private void writeLeInt(int value) throws IOException {
		file.write(value & 0xff);
		file.write((value >>> 8) & 0xff);
		file.write((value >>> 16) & 0xff);
		file.write((value >>> 24) & 0xff);
	}
}
