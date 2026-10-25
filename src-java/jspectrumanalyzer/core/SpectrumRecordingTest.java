package jspectrumanalyzer.core;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/** Deterministic HSR3 chunking, recovery, seek, and legacy compatibility checks. */
public final class SpectrumRecordingTest {
	private static final byte[] CHUNK_MAGIC = new byte[] {
			'H', 'S', 'R', '3', 'C', 'H', 'N', 'K', 'S', 'P', 'E', 'C', 'T', 'R', 'U', 'M'
	};

	public static void main(String[] args) throws Exception {
		testChunkedRoundTripAndSeek();
		testHardCropRecovery();
		testLegacyFallback();
		System.out.println("Spectrum recording tests passed");
	}

	private static void testChunkedRoundTripAndSeek() throws Exception {
		File file = File.createTempFile("spectrum-recording-", ".hsr");
		try {
			DatasetSpectrum spectrum = spectrum();
			try (SpectrumRecording recording = new SpectrumRecording(file, spectrum, "100-101", 3_000)) {
				for (int frame = 0; frame < 12; frame++) {
					fill(spectrum, -110f + frame);
					recording.writeFrameAt(spectrum, frame * 1_000L);
				}
			}

			try (SpectrumRecording.Reader reader = new SpectrumRecording.Reader(file)) {
				assertEquals(11_000, reader.getDurationMillis(), "HSR3 indexed duration");
				assertEquals(10, reader.getHeader().binCount, "HSR3 bin count");
				SpectrumRecording.Frame sought = reader.seekToTime(7_500);
				assertEquals(8_000, sought.timeOffsetMillis, "HSR3 seek result");
				assertNear(-102f, sought.spectrum[0], .3f, "HSR3 quantized value");
				assertEquals(9_000, reader.readFrame().timeOffsetMillis, "HSR3 frame after seek");
			}

			try (SpectrumRecording.Reader reader = new SpectrumRecording.Reader(file)) {
				int count = 0;
				long last = -1;
				SpectrumRecording.Frame frame;
				while ((frame = reader.readFrame()) != null) {
					count++;
					last = frame.timeOffsetMillis;
				}
				assertEquals(12, count, "HSR3 frame count");
				assertEquals(11_000, last, "HSR3 last frame");
			}
		} finally {
			Files.deleteIfExists(file.toPath());
		}
	}

	private static void testHardCropRecovery() throws Exception {
		File source = File.createTempFile("spectrum-source-", ".hsr");
		File tailCropped = File.createTempFile("spectrum-tail-cropped-", ".hsr");
		File cropped = File.createTempFile("spectrum-cropped-", ".hsr");
		try {
			DatasetSpectrum spectrum = spectrum();
			long originalEpoch;
			try (SpectrumRecording recording = new SpectrumRecording(source, spectrum, "100-101", 3_000)) {
				for (int frame = 0; frame < 12; frame++) {
					recording.writeFrameAt(spectrum, frame * 1_000L);
				}
			}
			try (SpectrumRecording.Reader reader = new SpectrumRecording.Reader(source)) {
				originalEpoch = reader.getHeader().startEpochMillis;
			}

			byte[] complete = Files.readAllBytes(source.toPath());
			List<Integer> markers = findAll(complete, CHUNK_MAGIC);
			assertEquals(4, markers.size(), "HSR3 chunk count");
			int from = markers.get(1) - 5; // Begin in arbitrary garbage immediately before chunk two.
			int finalMarker = markers.get(3);
			int finalHeaderLength = ByteBuffer.wrap(complete, finalMarker + 20, 4).getInt();
			int finalCompressedLength = ByteBuffer.wrap(complete, finalMarker + 24, 4).getInt();
			int finalPayload = finalMarker + 28 + finalHeaderLength;
			int to = finalPayload + finalCompressedLength - 5; // Cut through the final GZIP trailer.
			Files.write(tailCropped.toPath(), Arrays.copyOfRange(complete, 0, to));

			try (SpectrumRecording.Reader reader = new SpectrumRecording.Reader(tailCropped)) {
				assertEquals(8_000, reader.getDurationMillis(), "tail-cropped duration without footer index");
				assertEquals(originalEpoch, reader.getHeader().startEpochMillis,
						"tail-cropped absolute start");
				assertEquals(7_000, reader.seekToTime(6_500).timeOffsetMillis,
						"tail-cropped reconstructed seek index");
			}

			Files.write(cropped.toPath(), Arrays.copyOfRange(complete, from, to));

			try (SpectrumRecording.Reader reader = new SpectrumRecording.Reader(cropped)) {
				assertEquals(5_000, reader.getDurationMillis(), "hard-cropped duration");
				assertEquals(originalEpoch + 3_000, reader.getHeader().startEpochMillis,
						"hard-cropped absolute start");
				int count = 0;
				long last = -1;
				SpectrumRecording.Frame frame;
				while ((frame = reader.readFrame()) != null) {
					if (count == 0)
						assertEquals(0, frame.timeOffsetMillis, "hard-cropped first frame");
					count++;
					last = frame.timeOffsetMillis;
				}
				assertEquals(6, count, "hard-cropped complete frame count");
				assertEquals(5_000, last, "hard-cropped last frame");
			}
		} finally {
			Files.deleteIfExists(source.toPath());
			Files.deleteIfExists(tailCropped.toPath());
			Files.deleteIfExists(cropped.toPath());
		}
	}

	private static void testLegacyFallback() throws Exception {
		File file = File.createTempFile("spectrum-legacy-", ".hsr");
		try {
			try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
					new GZIPOutputStream(new FileOutputStream(file))))) {
				out.writeInt(0x48535231);
				out.writeInt(2);
				out.writeInt(100);
				out.writeInt(101);
				out.writeFloat(100_000f);
				out.writeInt(2);
				out.writeInt(10);
				out.writeFloat(-150f);
				out.writeFloat(.55f);
				out.writeUTF("100-101");
				out.writeLong(1_700_000_000_000L);
				for (int frame = 0; frame < 3; frame++) {
					out.writeLong(frame * 1_000L);
					for (int bin = 0; bin < 10; bin++)
						out.writeByte(80 + frame);
				}
			}

			try (SpectrumRecording.Reader reader = new SpectrumRecording.Reader(file)) {
				assertEquals(-1, reader.getDurationMillis(), "legacy duration fallback marker");
				assertEquals(2_000, reader.seekToTime(1_500).timeOffsetMillis, "legacy sequential seek");
				assertTrue(reader.readFrame() == null, "legacy EOF after seek");
			}
		} finally {
			Files.deleteIfExists(file.toPath());
		}
	}

	private static DatasetSpectrum spectrum() {
		return new DatasetSpectrum(100_000f, 100, 101, -120f, 2);
	}

	private static void fill(DatasetSpectrum spectrum, float value) {
		Arrays.fill(spectrum.getSpectrumArray(), value);
	}

	private static List<Integer> findAll(byte[] bytes, byte[] pattern) {
		List<Integer> positions = new ArrayList<Integer>();
		for (int at = 0; at + pattern.length <= bytes.length; at++) {
			int index = 0;
			while (index < pattern.length && bytes[at + index] == pattern[index])
				index++;
			if (index == pattern.length)
				positions.add(at);
		}
		return positions;
	}

	private static void assertNear(float expected, float actual, float tolerance, String message) {
		if (Math.abs(expected - actual) > tolerance)
			throw new AssertionError(message + ": expected " + expected + ", got " + actual);
	}

	private static void assertEquals(long expected, long actual, String message) {
		if (expected != actual)
			throw new AssertionError(message + ": expected " + expected + ", got " + actual);
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition)
			throw new AssertionError(message);
	}
}
