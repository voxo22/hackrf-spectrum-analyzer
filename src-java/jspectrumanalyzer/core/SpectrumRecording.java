package jspectrumanalyzer.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class SpectrumRecording implements Closeable {
	private static final int MAGIC = 0x48535231; // HSR1
	private static final int VERSION = 2;
	private static final float DEFAULT_MIN_DB = -150f;
	private static final float DEFAULT_DB_STEP = 0.55f;

	public static class Header {
		public final int freqStartMHz;
		public final int freqStopMHz;
		public final float fftBinHz;
		public final int freqShift;
		public final int binCount;
		public final float minDb;
		public final float dbStep;
		public final String ranges;
		public final long startEpochMillis;

		private Header(int freqStartMHz, int freqStopMHz, float fftBinHz, int freqShift, int binCount,
				float minDb, float dbStep, String ranges, long startEpochMillis) {
			this.freqStartMHz = freqStartMHz;
			this.freqStopMHz = freqStopMHz;
			this.fftBinHz = fftBinHz;
			this.freqShift = freqShift;
			this.binCount = binCount;
			this.minDb = minDb;
			this.dbStep = dbStep;
			this.ranges = ranges == null ? "" : ranges;
			this.startEpochMillis = startEpochMillis;
		}
	}

	public static class Frame {
		public final long timeOffsetMillis;
		public final float[] spectrum;

		private Frame(long timeOffsetMillis, float[] spectrum) {
			this.timeOffsetMillis = timeOffsetMillis;
			this.spectrum = spectrum;
		}
	}

	public static class Reader implements Closeable {
		private final DataInputStream in;
		private final Header header;

		public Reader(File file) throws IOException {
			this.in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(new FileInputStream(file))));
			int magic = in.readInt();
			int version = in.readInt();
			if (magic != MAGIC || (version != 1 && version != VERSION)) {
				throw new IOException("Unsupported spectrum recording format");
			}
			int freqStartMHz = in.readInt();
			int freqStopMHz = in.readInt();
			float fftBinHz = in.readFloat();
			int freqShift = in.readInt();
			int binCount = in.readInt();
			float minDb = in.readFloat();
			float dbStep = in.readFloat();
			String ranges = in.readUTF();
			long startEpochMillis = version >= 2 ? in.readLong() : 0;
			header = new Header(freqStartMHz, freqStopMHz, fftBinHz, freqShift, binCount,
					minDb, dbStep, ranges, startEpochMillis);
		}

		public Header getHeader() {
			return header;
		}

		public Frame readFrame() throws IOException {
			try {
				long timeOffsetMillis = in.readLong();
				float[] spectrum = new float[header.binCount];
				for (int i = 0; i < spectrum.length; i++) {
					int quantized = in.readUnsignedByte();
					spectrum[i] = header.minDb + quantized * header.dbStep;
				}
				return new Frame(timeOffsetMillis, spectrum);
			} catch (EOFException eof) {
				return null;
			}
		}

		@Override
		public void close() throws IOException {
			in.close();
		}
	}

	private final DataOutputStream out;
	private final long startedMillis;
	private final float minDb;
	private final float dbStep;
	private final int binCount;

	public SpectrumRecording(File file, DatasetSpectrum spectrum, String ranges) throws IOException {
		this.out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(file))));
		this.startedMillis = System.currentTimeMillis();
		this.minDb = DEFAULT_MIN_DB;
		this.dbStep = DEFAULT_DB_STEP;
		this.binCount = spectrum.spectrumLength();
		out.writeInt(MAGIC);
		out.writeInt(VERSION);
		out.writeInt(spectrum.getFreqStartMHz());
		out.writeInt(spectrum.getFreqStopMHz());
		out.writeFloat(spectrum.getFFTBinSizeHz());
		out.writeInt(spectrum.getFreqShift());
		out.writeInt(binCount);
		out.writeFloat(minDb);
		out.writeFloat(dbStep);
		out.writeUTF(ranges == null ? "" : ranges);
		out.writeLong(startedMillis);
	}

	public synchronized void writeFrame(DatasetSpectrum spectrum) throws IOException {
		float[] values = spectrum.getSpectrumArray();
		if (values.length != binCount) {
			throw new IOException("Spectrum size changed during recording");
		}
		out.writeLong(System.currentTimeMillis() - startedMillis);
		for (int i = 0; i < values.length; i++) {
			int quantized = Math.round((values[i] - minDb) / dbStep);
			if (quantized < 0)
				quantized = 0;
			if (quantized > 255)
				quantized = 255;
			out.writeByte(quantized);
		}
	}

	@Override
	public synchronized void close() throws IOException {
		out.close();
	}
}
