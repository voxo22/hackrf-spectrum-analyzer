package jspectrumanalyzer.iq;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IQReplayFile implements AutoCloseable {
	public enum Type {
		WAV, RAW
	}

	private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)?");
	private static final Pattern RAW_SAMPLE_RATE_PATTERN = Pattern.compile(
			"(?i)(?:^|[ _-])(\\d+(?:\\.\\d+)?)(k)?$");
	private static final Pattern RECORDING_TIMESTAMP_PATTERN = Pattern.compile(
			"(\\d{4}-\\d{2}-\\d{2} \\d{2}-\\d{2}-\\d{2})(?=\\.[^.]+$)");
	private static final DateTimeFormatter RECORDING_TIMESTAMP_FORMAT =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss");

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
		if (name.endsWith(".pcm") || name.endsWith(".cu8") || name.endsWith(".cs8")) {
			return openRaw(file);
		}
		throw new IOException("Unsupported IQ file extension; expected .wav, .pcm, .cu8, or .cs8");
	}

	public static long getRecordingStartEpochMillis(File file) {
		Matcher matcher = RECORDING_TIMESTAMP_PATTERN.matcher(file.getName());
		if (matcher.find()) {
			try {
				return LocalDateTime.parse(matcher.group(1), RECORDING_TIMESTAMP_FORMAT)
						.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
			} catch (RuntimeException ignored) {
				// Fall back to the file timestamp for recordings with an invalid date in their name.
			}
		}
		return file.lastModified();
	}

	private static IQReplayFile openWav(File file) throws IOException {
		long centerHz = parseCenterFrequency(file.getName());
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
			boolean unsigned8Bit = bitsPerSample == 8 && looksLikeUnsigned8BitPcm(input, dataOffset, dataLength);
			return new IQReplayFile(file, input, Type.WAV, centerHz, sampleRateHz, dataOffset, dataLength,
					bitsPerSample, unsigned8Bit);
		} catch (IOException | RuntimeException e) {
			input.close();
			throw e;
		}
	}

	private static IQReplayFile openRaw(File file) throws IOException {
		FilenameFrequency center = parseCenterFrequencyToken(file.getName());
		int sampleRateHz = parseRawSampleRate(file.getName(), center);
		RandomAccessFile input = new RandomAccessFile(file, "r");
		try {
			int bitsPerComponent = rawBitsPerComponent(file.getName());
			boolean unsigned8Bit = bitsPerComponent == 8
					&& file.getName().toLowerCase(Locale.ROOT).endsWith(".cu8");
			return new IQReplayFile(file, input, Type.RAW, center.frequencyHz, sampleRateHz, 0,
					input.length(), bitsPerComponent, unsigned8Bit);
		} catch (IOException | RuntimeException e) {
			input.close();
			throw e;
		}
	}

	private static boolean looksLikeUnsigned8BitPcm(RandomAccessFile input, long dataOffset, long dataLength)
			throws IOException {
		int count = (int)Math.min(131_072L, Math.max(0L, dataLength));
		if (count <= 0) {
			return true;
		}
		byte[] buffer = new byte[count];
		long previous = input.getFilePointer();
		input.seek(dataOffset);
		input.readFully(buffer);
		input.seek(previous);
		long signedAbs = 0, unsignedAbs = 0;
		for (byte value : buffer) {
			int unsigned = value & 0xff;
			signedAbs += Math.abs((int)value);
			unsignedAbs += Math.abs(unsigned - 128);
		}
		/* IQ WAVs recorded by this application carry signed HackRF bytes in an
		 * 8-bit PCM container. Standard 8-bit WAV PCM is unsigned and sits around
		 * 128, so compare both possible zero points instead of trusting the header. */
		return unsignedAbs <= signedAbs;
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

	public synchronized boolean canReadSignedBlockWithoutLoop(int destinationBytes) {
		if (destinationBytes < 2) {
			return false;
		}
		int requested = destinationBytes & ~1;
		long sourceBytes = (requested / 2L) * bytesPerComponent * 2L;
		return sourceBytes <= dataLength;
	}

	public synchronized boolean wouldLoopOnNextRead(int destinationBytes) {
		if (destinationBytes < 2) {
			return false;
		}
		int requested = destinationBytes & ~1;
		long sourceBytes = (requested / 2L) * bytesPerComponent * 2L;
		return dataPosition + sourceBytes > dataLength;
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

	public long getTotalIqSamples() {
		return dataLength / (bytesPerComponent * 2L);
	}

	public synchronized long getPositionMillis() {
		return Math.round(dataPosition * 1000d / (sampleRateHz * bytesPerComponent * 2d));
	}

	@Override
	public synchronized void close() throws IOException {
		input.close();
	}

	static long parseCenterFrequency(String name) throws IOException {
		return parseCenterFrequencyToken(name).frequencyHz;
	}

	private static FilenameFrequency parseCenterFrequencyToken(String name) throws IOException {
		String stem = fileStem(name);
		Matcher number = NUMBER_PATTERN.matcher(stem);
		int start = -1;
		while (number.find()) {
			/* Digits attached to a text identifier (g001, device2, ch16b...)
			 * are not standalone numeric tokens and do not participate in the
			 * frequency ordering rule. */
			if (number.start() == 0 || isFilenameDelimiter(stem.charAt(number.start() - 1))) {
				start = number.start();
				break;
			}
		}
		if (start < 0) {
			throw new IOException("IQ filename must contain a standalone center frequency, starting "
					+ "the name or separated from text by a space, underscore, or hyphen "
					+ "(for example 868.3MHz)");
		}
		int end = number.end();
		String unit = frequencyUnitAt(stem, end);
		int tokenEnd = end + unit.length();
		if (tokenEnd < stem.length() && !isFilenameDelimiter(stem.charAt(tokenEnd))) {
			throw new IOException("Center frequency may be followed only by M, k, MHz, kHz, or Hz; "
					+ "without a unit it is interpreted as MHz");
		}
		return new FilenameFrequency(parseScaledValue(number.group(), unit.length() == 0 ? "MHz" : unit),
				start, tokenEnd);
	}

	static int parseRawSampleRate(String name) throws IOException {
		return parseRawSampleRate(name, parseCenterFrequencyToken(name));
	}

	private static int parseRawSampleRate(String name, FilenameFrequency center) throws IOException {
		String stem = fileStem(name);
		Matcher matcher = RAW_SAMPLE_RATE_PATTERN.matcher(stem);
		if (!matcher.find()) {
			throw new IOException("PCM/CU8/CS8 filename must end with the sample rate before the extension "
					+ "(for example _2.4.pcm, _1000k.cu8, or _500k.cs8)");
		}
		if (matcher.start(1) >= center.start && matcher.start(1) < center.end) {
			throw new IOException("PCM/CU8/CS8 filename requires a separate sample rate after the center frequency");
		}
		double multiplier = matcher.group(2) == null ? 1_000_000d : 1_000d;
		double scaled = Double.parseDouble(matcher.group(1)) * multiplier;
		if (!Double.isFinite(scaled) || scaled <= 0d || scaled > Integer.MAX_VALUE) {
			throw new IOException("Invalid PCM/CU8/CS8 sample rate; use Ms/s, or append k for ks/s");
		}
		return (int)Math.round(scaled);
	}

	static int rawBitsPerComponent(String name) {
		return name.toLowerCase(Locale.ROOT).contains("16b") ? 16 : 8;
	}

	private static String fileStem(String name) {
		int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(0, dot) : name;
	}

	private static boolean isFilenameDelimiter(char value) {
		return value == ' ' || value == '_' || value == '-';
	}

	private static String frequencyUnitAt(String value, int offset) {
		String[] units = { "MHz", "kHz", "Hz", "M", "k" };
		for (String unit : units)
			if (value.regionMatches(true, offset, unit, 0, unit.length())) return unit;
		return "";
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

	private static final class FilenameFrequency {
		final long frequencyHz;
		final int start, end;
		FilenameFrequency(long frequencyHz, int start, int end) {
			this.frequencyHz = frequencyHz;
			this.start = start;
			this.end = end;
		}
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
