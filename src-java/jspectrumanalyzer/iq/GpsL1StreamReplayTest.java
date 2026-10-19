package jspectrumanalyzer.iq;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Manual end-to-end replay of seven seconds of a signed 8-bit GPS L1 WAV. */
public final class GpsL1StreamReplayTest {
	public static void main(String[] args) throws Exception {
		if (args.length < 1 || args.length > 3) throw new IllegalArgumentException("WAV file path, optional 20 ms chunk count and start second required");
		try (RandomAccessFile input = new RandomAccessFile(new File(args[0]), "r")) {
			int rateHz = readIntLe(input, 24);
			long dataOffset = findDataOffset(input), dataLength = findDataLength(input);
			int bytesPerChunk = Math.max(2, (int) Math.round(rateHz / 1000d) * 2 * 20);
			long maximumChunks = args.length == 2 ? Long.parseLong(args[1]) : 350L;
			if (args.length == 3) maximumChunks = Long.parseLong(args[1]);
			long startChunk = args.length == 3 ? Math.max(0L, Math.round(Double.parseDouble(args[2]) * 50d)) : 0L;
			long chunks = Math.min((dataLength / bytesPerChunk) - startChunk, maximumChunks);
			if (chunks < 2) throw new IOException("WAV is shorter than 40 ms");
			GpsL1SignalAnalyzer analyzer = new GpsL1SignalAnalyzer();
			byte[] last = null;
			for (int chunk = 0; chunk < chunks; chunk++) {
				byte[] iq = new byte[bytesPerChunk]; input.seek(dataOffset + (startChunk + (long) chunk) * bytesPerChunk); input.readFully(iq);
				for (int sample = 0; sample < iq.length; sample++) iq[sample] = (byte) ((iq[sample] & 0xff) - 128);
				analyzer.acceptStream(GpsL1SignalAnalyzer.L1_FREQUENCY_HZ, rateHz, iq, iq.length);
				/* Match the live worker's half-second acquisition cadence. */
				if (chunk % 25 == 0) {
					GpsL1SignalAnalyzer.Result sample = analyzer.analyze(GpsL1SignalAnalyzer.L1_FREQUENCY_HZ, rateHz, iq, iq.length);
					if (chunk > 0 && chunk % 250 == 0 && sample.position.converged)
						System.out.println(String.format(java.util.Locale.US, "At +%.0f s: %s %.7f %.7f %.1f m PDOP %.1f",
								chunk / 50d, sample.position.state, sample.position.latitude, sample.position.longitude,
								sample.position.altitude, sample.position.pdop));
				}
				last = iq;
			}
			GpsL1SignalAnalyzer.Result result = analyzer.analyze(GpsL1SignalAnalyzer.L1_FREQUENCY_HZ, rateHz, last, last.length);
			System.out.println(result.state);
			System.out.println("UTC: " + result.utc);
			System.out.println("GPS time: " + result.gpsTime);
			for (GpsL1StreamTracker.Observation observation : analyzer.observations())
				System.out.println(String.format(java.util.Locale.US, "Observation G%02d tow %.9f phase %d quality %.3f", observation.prn,
						observation.satelliteTime, observation.bitPhase, observation.bitQuality));
			GpsL1PositionSolver.Solution fix = result.position;
			System.out.println(String.format(java.util.Locale.US, "Position: %s lat %.8f lon %.8f alt %.1f m PDOP %.1f RMS %.1f m",
					fix.state, fix.latitude, fix.longitude, fix.altitude, fix.pdop, fix.residualRms));
			for (GpsL1SignalAnalyzer.Satellite satellite : result.satellites) {
				GpsL1NavigationData.Snapshot nav = satellite.navigation;
				System.out.println("G" + satellite.prn + " " + satellite.navState + " health "
						+ (nav == null ? "--" : nav.healthText()) + " ephemeris " + (nav == null ? "--" : nav.ephemerisText())
						+ (nav == null || nav.ephemeris == null ? "" : " IODE " + nav.ephemeris.iode
						+ " toe " + nav.ephemeris.toeSeconds + " sqrtA " + nav.ephemeris.sqrtA + " e " + nav.ephemeris.eccentricity));
			}
		}
	}

	private static int readIntLe(RandomAccessFile input, long offset) throws IOException { input.seek(offset); return Integer.reverseBytes(input.readInt()); }
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
