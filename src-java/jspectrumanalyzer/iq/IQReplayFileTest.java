package jspectrumanalyzer.iq;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public final class IQReplayFileTest {
	private IQReplayFileTest() {
	}

	public static void main(String[] args) throws Exception {
		File directory = new File(System.getProperty("java.io.tmpdir"), "hackrf-iq-replay-test-" + System.nanoTime());
		if (!directory.mkdirs()) {
			throw new IOException("Cannot create test directory");
		}
		try {
			testWav(directory);
			testWav16(directory);
			testRaw(directory);
			testRaw16(directory);
			testRawCenterPrefix(directory);
			testChannelProcessorContinuity();
			testWideChannelOutputCount();
			testRingBufferPartialWrite();
			testFftChannelProcessor();
			testSpectrum();
			System.out.println("IQ replay tests passed");
		} finally {
			deleteChildren(directory);
			directory.delete();
		}
	}

	private static void testChannelProcessorContinuity() {
		int sampleRateHz = 2_000_000;
		int outputRateHz = 250_000;
		int samples = sampleRateHz / 10;
		byte[] input = new byte[samples * 2];
		for (int i = 0; i < samples; i++) {
			input[i * 2] = (byte) (i * 17);
			input[i * 2 + 1] = (byte) (i * 29);
		}
		IQChannelProcessor oneBlock = new IQChannelProcessor(sampleRateHz, 125_000, 200_000, outputRateHz);
		byte[] completeOutput = new byte[input.length];
		int completeLength = oneBlock.process(input, input.length, completeOutput);

		IQChannelProcessor splitBlocks = new IQChannelProcessor(sampleRateHz, 125_000, 200_000, outputRateHz);
		byte[] splitOutput = new byte[input.length];
		int splitLength = 0;
		int blockBytes = sampleRateHz / 50 * 2;
		for (int offset = 0; offset < input.length; offset += blockBytes) {
			int length = Math.min(blockBytes, input.length - offset);
			byte[] block = new byte[length];
			System.arraycopy(input, offset, block, 0, length);
			byte[] output = new byte[length];
			int written = splitBlocks.process(block, length, output);
			System.arraycopy(output, 0, splitOutput, splitLength, written);
			splitLength += written;
		}
		assertEquals(completeLength, splitLength, "Channel processor split length");
		for (int i = 0; i < completeLength; i++) {
			assertEquals(completeOutput[i], splitOutput[i], "Channel processor continuity " + i);
		}
	}

	private static void testWideChannelOutputCount() {
		int sampleRateHz = 20_000_000;
		int outputRateHz = 4_000_000;
		int samples = sampleRateHz / 50;
		byte[] input = new byte[samples * 2];
		IQChannelProcessor processor = new IQChannelProcessor(sampleRateHz, 0, 2_000_000, outputRateHz);
		byte[] output = new byte[input.length];
		int writtenSamples = processor.process(input, input.length, output) / 2;
		int validSamples = samples - 64;
		int expectedSamples = (validSamples + processor.getDecimation() - 1) / processor.getDecimation();
		assertEquals(expectedSamples, writtenSamples, "Wide channel output count");
	}

	private static void testRingBufferPartialWrite() {
		IQRingBuffer buffer = new IQRingBuffer(16);
		byte[] input = { 1, 2, 3, 4, 99, 99, 99, 99 };
		buffer.write(input, 4);
		byte[] output = new byte[8];
		int read = buffer.readLatest(output, output.length);
		assertEquals(4, read, "Ring buffer partial length");
		for (int i = 0; i < read; i++) {
			assertEquals(input[i], output[i], "Ring buffer partial sample " + i);
		}
	}

	private static void testFftChannelProcessor() {
		if (!IQFftChannelProcessor.isAvailable()) {
			System.out.println("FFTW channelizer unavailable; FIR fallback will be used");
			return;
		}
		int sampleRateHz = 20_000_000;
		int samples = sampleRateHz / 50;
		byte[] input = new byte[samples * 2];
		for (int sample = 0; sample < samples; sample++) {
			double phase = 2d * Math.PI * 500_000d * sample / sampleRateHz;
			input[sample * 2] = (byte) Math.round(Math.cos(phase) * 100d);
			input[sample * 2 + 1] = (byte) Math.round(Math.sin(phase) * 100d);
		}

		byte[] completeOutput = new byte[input.length];
		byte[] splitOutput = new byte[input.length];
		int completeLength;
		int splitLength = 0;
		long started = System.nanoTime();
		try (IQFftChannelProcessor complete = new IQFftChannelProcessor(sampleRateHz, 0, 2_000_000, 2_500_000);
				IQFftChannelProcessor split = new IQFftChannelProcessor(sampleRateHz, 0, 2_000_000, 2_500_000)) {
			completeLength = complete.process(input, input.length, completeOutput);
			int blockBytes = samples / 4 * 2;
			for (int offset = 0; offset < input.length; offset += blockBytes) {
				int length = Math.min(blockBytes, input.length - offset);
				byte[] block = new byte[length];
				System.arraycopy(input, offset, block, 0, length);
				byte[] output = new byte[length];
				int written = split.process(block, length, output);
				System.arraycopy(output, 0, splitOutput, splitLength, written);
				splitLength += written;
			}
		}
		double elapsedMillis = (System.nanoTime() - started) / 1_000_000d;
		assertEquals(completeLength, splitLength, "FFT channel processor split length");
		long outputEnergy = 0;
		for (int i = 0; i < completeLength; i++) {
			assertEquals(completeOutput[i], splitOutput[i], "FFT channel processor continuity " + i);
			outputEnergy += Math.abs(completeOutput[i]);
		}
		if (outputEnergy == 0) {
			throw new AssertionError("FFT channel processor produced no signal");
		}
		double blockMillis;
		double shiftedBlockMillis;
		try (IQFftChannelProcessor benchmark = new IQFftChannelProcessor(sampleRateHz, 0, 2_000_000, 2_500_000)) {
			byte[] output = new byte[input.length];
			benchmark.process(input, input.length, output);
			long benchmarkStarted = System.nanoTime();
			for (int iteration = 0; iteration < 10; iteration++) {
				benchmark.process(input, input.length, output);
			}
			blockMillis = (System.nanoTime() - benchmarkStarted) / 1_000_000d / 10d;
		}
		try (IQFftChannelProcessor benchmark = new IQFftChannelProcessor(sampleRateHz, 3_000_000, 2_000_000,
				2_500_000)) {
			byte[] output = new byte[input.length];
			benchmark.process(input, input.length, output);
			long benchmarkStarted = System.nanoTime();
			for (int iteration = 0; iteration < 10; iteration++) {
				benchmark.process(input, input.length, output);
			}
			shiftedBlockMillis = (System.nanoTime() - benchmarkStarted) / 1_000_000d / 10d;
		}
		System.out.println(String.format(
				"FFTW channelizer setup test %.1f ms, steady 20 ms block %.2f ms centered / %.2f ms shifted",
				elapsedMillis, blockMillis, shiftedBlockMillis));
	}

	private static void testRawCenterPrefix(File directory) throws Exception {
		File file = new File(directory, "433.92_capture_500k.pcm");
		byte[] samples = { 1, -1, 2, -2 };
		try (FileOutputStream output = new FileOutputStream(file)) {
			output.write(samples);
		}
		try (IQReplayFile replay = IQReplayFile.open(file)) {
			assertEquals(433_920_000L, replay.getCenterFrequencyHz(), "RAW MHz prefix center");
			assertEquals(500_000L, replay.getSampleRateHz(), "RAW prefix bandwidth");
		}
	}

	private static void testWav(File directory) throws Exception {
		File file = new File(directory, "# IQ 951800000Hz BW-2M test.wav");
		byte[] samples = { 0, (byte) 128, (byte) 255, 64 };
		try (FileOutputStream output = new FileOutputStream(file)) {
			writeAscii(output, "RIFF");
			writeLeInt(output, 36 + samples.length);
			writeAscii(output, "WAVEfmt ");
			writeLeInt(output, 16);
			writeLeShort(output, 1);
			writeLeShort(output, 2);
			writeLeInt(output, 2_000_000);
			writeLeInt(output, 4_000_000);
			writeLeShort(output, 2);
			writeLeShort(output, 8);
			writeAscii(output, "data");
			writeLeInt(output, samples.length);
			output.write(samples);
		}
		try (IQReplayFile replay = IQReplayFile.open(file)) {
			assertEquals(951_800_000L, replay.getCenterFrequencyHz(), "WAV center");
			assertEquals(2_000_000L, replay.getSampleRateHz(), "WAV rate");
			byte[] actual = new byte[8];
			replay.readLoopedSigned(actual);
			byte[] expected = { -128, 0, 127, -64, -128, 0, 127, -64 };
			for (int i = 0; i < expected.length; i++) {
				assertEquals(expected[i], actual[i], "WAV sample " + i);
			}
		}
	}

	private static void testWav16(File directory) throws Exception {
		File file = new File(directory, "capture_951800kHz_16bit.wav");
		byte[] samples = {
				0x00, (byte) 0x80, (byte) 0xff, (byte) 0xff,
				(byte) 0xff, 0x7f, 0x00, 0x40
		};
		try (FileOutputStream output = new FileOutputStream(file)) {
			writeAscii(output, "RIFF");
			writeLeInt(output, 36 + samples.length);
			writeAscii(output, "WAVEfmt ");
			writeLeInt(output, 16);
			writeLeShort(output, 1);
			writeLeShort(output, 2);
			writeLeInt(output, 1_000_000);
			writeLeInt(output, 4_000_000);
			writeLeShort(output, 4);
			writeLeShort(output, 16);
			writeAscii(output, "data");
			writeLeInt(output, samples.length);
			output.write(samples);
		}
		try (IQReplayFile replay = IQReplayFile.open(file)) {
			assertEquals(951_800_000L, replay.getCenterFrequencyHz(), "WAV16 center");
			assertEquals(1_000_000L, replay.getSampleRateHz(), "WAV16 rate");
			byte[] actual = new byte[8];
			replay.readLoopedSigned(actual);
			byte[] expected = { -128, -1, 127, 64, -128, -1, 127, 64 };
			for (int i = 0; i < expected.length; i++) {
				assertEquals(expected[i], actual[i], "WAV16 sample " + i);
			}
		}
	}

	private static void testRaw(File directory) throws Exception {
		File file = new File(directory, "capture_433920kHz_250k.pcm");
		byte[] samples = { -3, 4, 5, -6 };
		try (FileOutputStream output = new FileOutputStream(file)) {
			output.write(samples);
		}
		try (IQReplayFile replay = IQReplayFile.open(file)) {
			assertEquals(433_920_000L, replay.getCenterFrequencyHz(), "RAW center");
			assertEquals(250_000L, replay.getSampleRateHz(), "RAW rate");
			byte[] actual = new byte[6];
			replay.readLoopedSigned(actual);
			byte[] expected = { -3, 4, 5, -6, -3, 4 };
			for (int i = 0; i < expected.length; i++) {
				assertEquals(expected[i], actual[i], "RAW sample " + i);
			}
		}
		File mhzFile = new File(directory, "capture_951800000Hz_2.pcm");
		try (FileOutputStream output = new FileOutputStream(mhzFile)) {
			output.write(samples);
		}
		try (IQReplayFile replay = IQReplayFile.open(mhzFile)) {
			assertEquals(951_800_000L, replay.getCenterFrequencyHz(), "RAW Hz center");
			assertEquals(2_000_000L, replay.getSampleRateHz(), "RAW MHz bandwidth");
		}
	}

	private static void testRaw16(File directory) throws Exception {
		File file = new File(directory, "capture_433920kHz_16b_500k.pcm");
		byte[] samples = {
				0x00, (byte) 0x80, 0x00, 0x00,
				(byte) 0xff, 0x7f, 0x00, (byte) 0xc0
		};
		try (FileOutputStream output = new FileOutputStream(file)) {
			output.write(samples);
		}
		try (IQReplayFile replay = IQReplayFile.open(file)) {
			assertEquals(433_920_000L, replay.getCenterFrequencyHz(), "RAW16 center");
			assertEquals(500_000L, replay.getSampleRateHz(), "RAW16 bandwidth");
			byte[] actual = new byte[8];
			replay.readLoopedSigned(actual);
			byte[] expected = { -128, 0, 127, -64, -128, 0, 127, -64 };
			for (int i = 0; i < expected.length; i++) {
				assertEquals(expected[i], actual[i], "RAW16 sample " + i);
			}
		}
	}

	private static void testSpectrum() {
		assertEquals(32, IQSpectrumFrame.chooseSize(95_238, 3_000), "narrow IQ replay FFT size");
		int size = 1024;
		byte[] iq = new byte[size * 2];
		int toneBin = 83;
		for (int i = 0; i < size; i++) {
			double phase = 2d * Math.PI * toneBin * i / size;
			iq[i * 2] = (byte) Math.round(Math.cos(phase) * 100d);
			iq[i * 2 + 1] = (byte) Math.round(Math.sin(phase) * 100d);
		}
		float[] spectrum = new IQSpectrumFrame(size).compute(iq, 0);
		int peak = 0;
		for (int i = 1; i < spectrum.length; i++) {
			if (spectrum[i] > spectrum[peak]) {
				peak = i;
			}
		}
		assertEquals(size / 2 + toneBin, peak, "FFT peak");
	}

	private static void assertEquals(long expected, long actual, String message) {
		if (expected != actual) {
			throw new AssertionError(message + ": expected " + expected + ", got " + actual);
		}
	}

	private static void writeAscii(FileOutputStream output, String text) throws IOException {
		output.write(text.getBytes("US-ASCII"));
	}

	private static void writeLeShort(FileOutputStream output, int value) throws IOException {
		output.write(value & 0xff);
		output.write((value >>> 8) & 0xff);
	}

	private static void writeLeInt(FileOutputStream output, int value) throws IOException {
		writeLeShort(output, value);
		writeLeShort(output, value >>> 16);
	}

	private static void deleteChildren(File directory) {
		File[] files = directory.listFiles();
		if (files == null) {
			return;
		}
		for (File file : files) {
			file.delete();
		}
	}
}
