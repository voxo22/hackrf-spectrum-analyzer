package jspectrumanalyzer.iq;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IQReplayFile implements AutoCloseable {
	public enum Type {
		WAV, RAW
	}

	private static final Pattern WAV_CENTER_PATTERN = Pattern.compile(
			"(?i)(?:^|[^0-9.])(\\d+(?:\\.\\d+)?)\\s*(kHz|Hz)(?:[^a-z]|$)");
	private static final Pattern RAW_CENTER_PATTERN = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)(kHz|Hz)");
	private static final Pattern RAW_CENTER_PREFIX_PATTERN = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)_");
	private static final Pattern RAW_BANDWIDTH_PATTERN = Pattern.compile("(?i)_([0-9]+(?:\\.[0-9]+)?)(k)?\\.pcm$");

	private final File file;
	private final RandomAccessFile input;
	private final Type type;
	private final long centerFrequencyHz;
	private final int sampleRateHz;
	private final long dataOffset;
	private final long dataLength;
	private final int bytesPerComponent;
	private final boolean unsigned8Bit;
	private byte[] sourceBuffer = new byte[0];
	private long dataPosition;

	private IQReplayFile(File file, RandomAccessFile input, Type type, long centerFrequencyHz, int sampleRateHz,
			long dataOffset, long dataLength, int bitsPerComponent, boolean unsigned8Bit) throws IOException {
		this.file = file;
		this.input = input;
		this.type = type;
		this.centerFrequencyHz = centerFrequencyHz;
		this.sampleRateHz = sampleRateHz;
		this.dataOffset = dataOffset;
		this.bytesPerComponent = bitsPerComponent / 8;
		this.unsigned8Bit = unsigned8Bit;
		int bytesPerIqSample = this.bytesPerComponent * 2;
		this.dataLength = dataLength - dataLength % bytesPerIqSample;
		if (this.dataLength < bytesPerIqSample) {
			throw new IOException("IQ file contains no complete I/Q samples");
		}
		seekToDataByte(0);
	}

	public static IQReplayFile open(File file) throws IOException {
		String name = file.getName().toLowerCase(Locale.ROOT);
		if (name.endsWith(".wav")) {
			return openWav(file);
		}
		if (name.endsWith(".pcm")) {
			return openRaw(file);
		}
		throw new IOException("Unsupported IQ file extension");
	}

	private static IQReplayFile openWav(File file) throws IOException {
		RandomAccessFile input = new RandomAccessFile(file, "r");
		try {
			if (!"RIFF".equals(readAscii(input, 4))) {
				throw new IOException("Invalid WAV RIFF header");
			}
			readLeUnsignedInt(input);
			if (!"WAVE".equals(readAscii(input, 4))) {
				throw new IOException("Invalid WAV format");
			}

			int audioFormat = -1;
			int channels = -1;
			int sampleRateHz = -1;
			int bitsPerSample = -1;
			long dataOffset = -1;
			long dataLength = -1;
			while (input.getFilePointer() + 8 <= input.length()) {
				String chunkId = readAscii(input, 4);
				long chunkLength = readLeUnsignedInt(input);
				long chunkDataOffset = input.getFilePointer();
				if ("fmt ".equals(chunkId)) {
					if (chunkLength < 16) {
						throw new IOException("Invalid WAV fmt chunk");
					}
					audioFormat = readLeUnsignedShort(input);
					channels = readLeUnsignedShort(input);
					sampleRateHz = (int) readLeUnsignedInt(input);
					readLeUnsignedInt(input);
					readLeUnsignedShort(input);
					bitsPerSample = readLeUnsignedShort(input);
				} else if ("data".equals(chunkId)) {
					dataOffset = chunkDataOffset;
					dataLength = Math.min(chunkLength, input.length() - chunkDataOffset);
				}
				long next = chunkDataOffset + chunkLength + (chunkLength & 1L);
				if (next < chunkDataOffset || next > input.length()) {
					break;
				}
				input.seek(next);
			}
			if (audioFormat != 1 || channels != 2 || (bitsPerSample != 8 && bitsPerSample != 16)
					|| sampleRateHz <= 0 || dataOffset < 0) {
				throw new IOException("WAV must be stereo 8-bit or 16-bit PCM I/Q");
			}
			long centerHz = parseWavCenterFrequency(file.getName());
			return new IQReplayFile(file, input, Type.WAV, centerHz, sampleRateHz, dataOffset, dataLength,
					bitsPerSample, bitsPerSample == 8);
		} catch (IOException | RuntimeException e) {
			input.close();
			throw e;
		}
	}

	private static IQReplayFile openRaw(File file) throws IOException {
		long centerHz = parseRawCenterFrequency(file.getName());
		long bandwidthHz = parseRawBandwidth(file.getName());
		if (bandwidthHz <= 0 || bandwidthHz > Integer.MAX_VALUE) {
			throw new IOException("PCM filename must end with _<MHz> or _<kHz>k");
		}
		RandomAccessFile input = new RandomAccessFile(file, "r");
		try {
			int bitsPerComponent = file.getName().toLowerCase(Locale.ROOT).contains("16b") ? 16 : 8;
			return new IQReplayFile(file, input, Type.RAW, centerHz, (int) bandwidthHz, 0, input.length(),
					bitsPerComponent, false);
		} catch (IOException | RuntimeException e) {
			input.close();
			throw e;
		}
	}

	public synchronized int readLoopedSigned(byte[] destination) throws IOException {
		if (destination == null || destination.length < 2) {
			return 0;
		}
		int requested = destination.length & ~1;
		if (bytesPerComponent == 2) {
			return readLooped16Bit(destination, requested);
		}
		int written = 0;
		while (written < requested) {
			if (dataPosition >= dataLength) {
				seekToDataByte(0);
			}
			int chunk = (int) Math.min(requested - written, dataLength - dataPosition);
			int read = input.read(destination, written, chunk);
			if (read < 0) {
				seekToDataByte(0);
				continue;
			}
			if (unsigned8Bit) {
				for (int i = written; i < written + read; i++) {
					destination[i] = (byte) ((destination[i] & 0xff) - 128);
				}
			}
			written += read;
			dataPosition += read;
		}
		return written;
	}

	private int readLooped16Bit(byte[] destination, int requested) throws IOException {
		int samples = requested / 2;
		int sourceBytes = samples * 4;
		if (sourceBuffer.length < sourceBytes) {
			sourceBuffer = new byte[sourceBytes];
		}
		int sourceWritten = 0;
		while (sourceWritten < sourceBytes) {
			if (dataPosition >= dataLength) {
				seekToDataByte(0);
			}
			int chunk = (int) Math.min(sourceBytes - sourceWritten, dataLength - dataPosition);
			int read = input.read(sourceBuffer, sourceWritten, chunk);
			if (read < 0) {
				seekToDataByte(0);
				continue;
			}
			sourceWritten += read;
			dataPosition += read;
		}
		for (int sample = 0; sample < samples; sample++) {
			int source = sample * 4;
			destination[sample * 2] = sourceBuffer[source + 1];
			destination[sample * 2 + 1] = sourceBuffer[source + 3];
		}
		return requested;
	}

	public synchronized void seekMillis(long positionMillis) throws IOException {
		int bytesPerIqSample = bytesPerComponent * 2;
		long bytePosition = Math.round(Math.max(0, positionMillis) * sampleRateHz * bytesPerIqSample / 1000d);
		seekToDataByte(Math.min(dataLength - bytesPerIqSample,
				bytePosition - bytePosition % bytesPerIqSample));
	}

	private void seekToDataByte(long bytePosition) throws IOException {
		int bytesPerIqSample = bytesPerComponent * 2;
		long alignedPosition = bytePosition - bytePosition % bytesPerIqSample;
		dataPosition = Math.max(0, Math.min(dataLength, alignedPosition));
		input.seek(dataOffset + dataPosition);
	}

	public Type getType() {
		return type;
	}

	public File getFile() {
		return file;
	}

	public long getCenterFrequencyHz() {
		return centerFrequencyHz;
	}

	public int getSampleRateHz() {
		return sampleRateHz;
	}

	public long getDurationMillis() {
		return Math.max(1, Math.round(dataLength * 1000d / (sampleRateHz * bytesPerComponent * 2d)));
	}

	public synchronized long getPositionMillis() {
		return Math.round(dataPosition * 1000d / (sampleRateHz * bytesPerComponent * 2d));
	}

	@Override
	public synchronized void close() throws IOException {
		input.close();
	}

	private static long parseWavCenterFrequency(String name) throws IOException {
		Matcher matcher = WAV_CENTER_PATTERN.matcher(name);
		if (!matcher.find()) {
			throw new IOException("IQ filename must contain center frequency with Hz units");
		}
		return parseScaledValue(matcher.group(1), matcher.group(2));
	}

	private static long parseRawCenterFrequency(String name) throws IOException {
		Matcher matcher = RAW_CENTER_PATTERN.matcher(name);
		if (matcher.find()) {
			return parseScaledValue(matcher.group(1), matcher.group(2));
		}
		matcher = RAW_CENTER_PREFIX_PATTERN.matcher(name);
		if (matcher.find()) {
			return parseScaledValue(matcher.group(1), "MHz");
		}
		throw new IOException("PCM filename must contain Hz/kHz center frequency or start with <MHz>_");
	}

	private static long parseRawBandwidth(String name) throws IOException {
		Matcher matcher = RAW_BANDWIDTH_PATTERN.matcher(name);
		if (!matcher.find()) {
			throw new IOException("PCM filename must end with _<MHz> or _<kHz>k");
		}
		double multiplier = matcher.group(2) == null ? 1_000_000d : 1_000d;
		double bandwidthHz = Double.parseDouble(matcher.group(1)) * multiplier;
		if (!Double.isFinite(bandwidthHz) || bandwidthHz <= 0 || bandwidthHz > Long.MAX_VALUE) {
			throw new IOException("Invalid PCM bandwidth in filename");
		}
		return Math.round(bandwidthHz);
	}

	private static long parseScaledValue(String value, String unit) throws IOException {
		double multiplier = 1d;
		String normalized = unit == null ? "" : unit.toLowerCase(Locale.ROOT);
		if (normalized.startsWith("g")) {
			multiplier = 1_000_000_000d;
		} else if (normalized.startsWith("m")) {
			multiplier = 1_000_000d;
		} else if (normalized.startsWith("k")) {
			multiplier = 1_000d;
		}
		double scaled = Double.parseDouble(value) * multiplier;
		if (!Double.isFinite(scaled) || scaled <= 0 || scaled > Long.MAX_VALUE) {
			throw new IOException("Invalid frequency value in filename");
		}
		return Math.round(scaled);
	}

	private static String readAscii(RandomAccessFile input, int length) throws IOException {
		byte[] bytes = new byte[length];
		input.readFully(bytes);
		return new String(bytes, "US-ASCII");
	}

	private static int readLeUnsignedShort(RandomAccessFile input) throws IOException {
		int low = input.read();
		int high = input.read();
		if ((low | high) < 0) {
			throw new EOFException();
		}
		return low | (high << 8);
	}

	private static long readLeUnsignedInt(RandomAccessFile input) throws IOException {
		long low = readLeUnsignedShort(input);
		long high = readLeUnsignedShort(input);
		return low | (high << 16);
	}
}
