package jspectrumanalyzer.iq;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Locale;

/** Manual replay probe: argument is an unsigned 8-bit stereo-IQ WAV at GPS L1. */
public final class GpsL1WavReplayTest {
	public static void main(String[] args) throws Exception {
		if (args.length != 1) throw new IllegalArgumentException("WAV file path required");
		File file = new File(args[0]);
		try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
			int sampleRateHz = readIntLe(input, 24);
			long dataOffset = findDataOffset(input), dataBytes = findDataLength(input);
			long durationSeconds = dataBytes / Math.max(1L, sampleRateHz * 2L);
			long[] seconds = { 0L, durationSeconds / 4L, durationSeconds / 2L, durationSeconds * 3L / 4L,
					Math.max(0L, durationSeconds - 1L) };
			GpsL1SignalAnalyzer analyzer = new GpsL1SignalAnalyzer();
			int bytes = Math.max(2, (int) Math.round(sampleRateHz / 1000d) * 2 * 20);
			for (long second : seconds) {
				byte[] iq = new byte[bytes]; input.seek(dataOffset + second * sampleRateHz * 2L); input.readFully(iq);
				for (int n = 0; n < iq.length; n++) iq[n] = (byte) ((iq[n] & 0xff) - 128);
				GpsL1SignalAnalyzer.Result result = analyzer.analyze(GpsL1SignalAnalyzer.L1_FREQUENCY_HZ,
						sampleRateHz, iq, iq.length);
				System.out.println(String.format(Locale.US, "%ds: %s", second, result.state));
				for (GpsL1SignalAnalyzer.Satellite satellite : result.satellites)
					System.out.println(String.format(Locale.US, "  G%02d %s  doppler %+d Hz  coherence %.1f%%  track %.0f%%",
							satellite.prn, satellite.trackingLocked ? "tracked" : satellite.acquired ? "acquired" : "candidate",
							satellite.dopplerHz, satellite.coherence * 100d, satellite.trackingStability * 100d));
			}
		}
	}

	private static int readIntLe(RandomAccessFile input, long offset) throws IOException {
		input.seek(offset); return Integer.reverseBytes(input.readInt());
	}
	private static long findDataOffset(RandomAccessFile input) throws IOException {
		long position = 12L;
		while (position + 8L <= input.length()) { input.seek(position); int id = input.readInt(); int size = Integer.reverseBytes(input.readInt());
			if (id == 0x64617461) return position + 8L; position += 8L + (size & 0xffffffffL) + (size & 1); }
		throw new IOException("WAV data chunk not found");
	}
	private static long findDataLength(RandomAccessFile input) throws IOException {
		long position = 12L;
		while (position + 8L <= input.length()) { input.seek(position); int id = input.readInt(); int size = Integer.reverseBytes(input.readInt());
			if (id == 0x64617461) return size & 0xffffffffL; position += 8L + (size & 0xffffffffL) + (size & 1); }
		throw new IOException("WAV data chunk not found");
	}
}
