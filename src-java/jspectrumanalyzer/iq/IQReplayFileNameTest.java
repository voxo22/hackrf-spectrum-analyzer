package jspectrumanalyzer.iq;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Regression tests for the shared WAV/PCM/CU8/CS8 filename contract. */
public final class IQReplayFileNameTest {
	public static void main(String[] args) throws Exception {
		frequency("capture_868.3_test.wav", 868_300_000L);
		frequency("capture-868.3M-2026.wav", 868_300_000L);
		frequency("capture 868300k test.wav", 868_300_000L);
		frequency("capture_868300kHz_test.wav", 868_300_000L);
		frequency("capture_868300000Hz_test.wav", 868_300_000L);
		frequency("868.95MHz_16b_2.4.pcm", 868_950_000L);
		frequency("g001_TCH_868.9M_14_250k.cu8", 868_900_000L);
		frequency("device2band7_868.3_2.pcm", 868_300_000L);
		frequency("# IQ 3430000000Hz BW-20M 2026-10-05 16-34-26.wav", 3_430_000_000L);

		rate("capture_868.3_2.4.pcm", 2_400_000);
		rate("capture-868.3MHz-1000k.cu8", 1_000_000);
		rate("g001_TCH_868.9M_14_250k.cu8", 250_000);
		frequency("433735000Hz_500k.cs8", 433_735_000L);
		rate("433735000Hz_500k.cs8", 500_000);
		frequency("capture16b_868.3_2.pcm", 868_300_000L);
		rate("capture16b_868.3_2.pcm", 2_000_000);
		if (IQReplayFile.rawBitsPerComponent("capture_868.3_16b_2.pcm") != 16)
			throw new AssertionError("16b marker was ignored");
		if (IQReplayFile.rawBitsPerComponent("capture_868.3_2.cu8") != 8)
			throw new AssertionError("Raw default is not 8-bit");

		error("capture_without_numbers.wav", "center frequency");
		error("capture16b_without_frequency.wav", "standalone center frequency");
		error("capture_868foo_2.pcm", "followed only");
		rateError("capture_868.3MHz.pcm", "sample rate");
		rateError("868.3.pcm", "separate sample rate");
		rateError("capture_868.3MHz_2M.pcm", "sample rate");

		verifyCu8UnsignedReplay();
		verifyCs8SignedReplay();
		System.out.println("IQReplayFileNameTest OK: unified WAV/PCM/CU8/CS8 filename rules");
	}

	private static void frequency(String name, long expected) throws Exception {
		long actual = IQReplayFile.parseCenterFrequency(name);
		if (actual != expected) throw new AssertionError(name + ": " + actual + " != " + expected);
	}

	private static void rate(String name, int expected) throws Exception {
		int actual = IQReplayFile.parseRawSampleRate(name);
		if (actual != expected) throw new AssertionError(name + ": " + actual + " != " + expected);
	}

	private static void error(String name, String text) throws Exception {
		try { IQReplayFile.parseCenterFrequency(name); }
		catch (IOException expected) {
			if (expected.getMessage().toLowerCase().contains(text.toLowerCase())) return;
			throw new AssertionError("Unexpected message: " + expected.getMessage());
		}
		throw new AssertionError("Expected filename error for " + name);
	}

	private static void rateError(String name, String text) throws Exception {
		try { IQReplayFile.parseRawSampleRate(name); }
		catch (IOException expected) {
			if (expected.getMessage().toLowerCase().contains(text.toLowerCase())) return;
			throw new AssertionError("Unexpected message: " + expected.getMessage());
		}
		throw new AssertionError("Expected sample-rate error for " + name);
	}

	private static void verifyCu8UnsignedReplay() throws Exception {
		Path directory = Files.createTempDirectory("iq-replay-name-test-");
		Path path = directory.resolve("capture_868.3MHz_1000k.cu8");
		try {
			Files.write(path, new byte[] { 0, (byte)128, (byte)255, 64 });
			try (IQReplayFile replay = IQReplayFile.open(path.toFile())) {
				if (replay.getCenterFrequencyHz() != 868_300_000L || replay.getSampleRateHz() != 1_000_000)
					throw new AssertionError("CU8 metadata was parsed incorrectly");
				byte[] signed = new byte[4]; replay.readLoopedSigned(signed);
				byte[] expected = { -128, 0, 127, -64 };
				for (int n=0;n<signed.length;n++) if (signed[n] != expected[n])
					throw new AssertionError("CU8 unsigned conversion failed at byte " + n);
			}
		} finally {
			Files.deleteIfExists(path);
			Files.deleteIfExists(directory);
		}
	}

	private static void verifyCs8SignedReplay() throws Exception {
		Path directory = Files.createTempDirectory("iq-replay-cs8-test-");
		Path path = directory.resolve("433735000Hz_500k.cs8");
		try {
			byte[] expected = { -128, 0, 127, -64 };
			Files.write(path, expected);
			try (IQReplayFile replay = IQReplayFile.open(path.toFile())) {
				byte[] signed = new byte[4]; replay.readLoopedSigned(signed);
				for (int n=0;n<signed.length;n++) if (signed[n] != expected[n])
					throw new AssertionError("CS8 signed replay changed byte " + n);
			}
		} finally {
			Files.deleteIfExists(path);
			Files.deleteIfExists(directory);
		}
	}
}
