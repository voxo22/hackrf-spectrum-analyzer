package jspectrumanalyzer.iq;

import java.io.File;
import java.util.Locale;

/** One-position offline B1C acquisition probe used to validate real IQ recordings. */
public final class BeiDouB1cWavReplayProbe {
	private static final int PRNS_PER_BATCH = 7;
	private BeiDouB1cWavReplayProbe() { }

	public static void main(String[] args) throws Exception {
		if (args.length < 1 || args.length > 3)
			throw new IllegalArgumentException(
					"BeiDou B1C IQ WAV path required; optional position and tracking duration in seconds");
		double seconds = args.length == 2 ? Double.parseDouble(args[1]) : 5d;
		if (args.length == 3) seconds = Double.parseDouble(args[1]);
		try (IQReplayFile replay = IQReplayFile.open(new File(args[0]))) {
			int rate = replay.getSampleRateHz();
			int samples = (int) Math.ceil((double) BeiDouB1cSignalAnalyzer.ACQUISITION_SAMPLES
					* rate / BeiDouB1cSignalAnalyzer.ACQUISITION_RATE_HZ) + 2;
			byte[] iq = new byte[samples * 2];
			replay.seekMillis(Math.max(0L, Math.round(seconds * 1000d)));
			replay.readLoopedSigned(iq);
			System.out.println(String.format(Locale.US,
					"%s @ %.3f s: center %.6f MHz, %.3f MS/s, scanning B1C PRNs 1..63",
					replay.getFile().getName(), seconds, replay.getCenterFrequencyHz() / 1e6, rate / 1e6));
			BeiDouB1cSignalAnalyzer analyzer = new BeiDouB1cSignalAnalyzer();
			analyzer.acceptStream(replay.getCenterFrequencyHz(), rate, iq, iq.length);
			BeiDouB1cSignalAnalyzer.Result result = null;
			for (int first = 1; first <= BeiDouB1cCodes.PRN_COUNT; first += PRNS_PER_BATCH) {
				int count = Math.min(PRNS_PER_BATCH, BeiDouB1cCodes.PRN_COUNT - first + 1);
				long started = System.nanoTime();
				result = analyzer.analyzePrnRange(replay.getCenterFrequencyHz(), rate, iq, iq.length, first, count);
				System.out.println(String.format(Locale.US, "  C%02d..C%02d: %.2f s, acquired total %d",
						first, first + count - 1, (System.nanoTime() - started) / 1e9, result.acquiredCount));
			}
			if (result == null) return;
			System.out.println(result.state);
			int shown = 0;
			for (BeiDouB1cSignalAnalyzer.Satellite satellite : result.candidates) {
				if (shown++ >= 12) break;
				System.out.println(String.format(Locale.US,
						"  C%02d %-9s doppler %+5d Hz phase %8.2f chips peak/noise %6.1f coherence %5.2f%% C/N0 %5.1f",
						satellite.prn, satellite.acquired ? "ACQUIRED" : "candidate", satellite.dopplerHz,
						satellite.codePhaseChips, satellite.peakToNoise,
						satellite.coherence * 100d, satellite.cNoDbHz));
			}
			if (args.length == 3) track(replay, analyzer, Double.parseDouble(args[2]));
		}
	}

	private static void track(IQReplayFile replay, BeiDouB1cSignalAnalyzer analyzer, double durationSeconds)
			throws Exception {
		int rate = replay.getSampleRateHz(), chunkSamples = Math.max(1, rate / 50);
		byte[] iq = new byte[chunkSamples * 2]; int chunks = (int) Math.ceil(durationSeconds * 50d);
		BeiDouB1cSignalAnalyzer.Result result = null;
		long started = System.nanoTime();
		for (int chunk = 0; chunk < chunks; chunk++) {
			replay.readLoopedSigned(iq); analyzer.acceptStream(replay.getCenterFrequencyHz(), rate, iq, iq.length);
			if (chunk % 250 == 249) result = analyzer.analyzePrnRange(
					replay.getCenterFrequencyHz(), rate, iq, iq.length, 1, 0);
		}
		System.out.println(String.format(Locale.US, "Tracked %.1f s in %.2f s CPU/wall time:",
				durationSeconds, (System.nanoTime() - started) / 1e9));
		if (result == null) return;
		for (BeiDouB1cSignalAnalyzer.Satellite satellite : result.satellites)
			System.out.println(String.format(Locale.US,
					"  C%02d lock=%s symbols=%d stability=%5.2f%% %s",
					satellite.prn, satellite.trackingLocked, satellite.trackingSymbols,
					satellite.trackingStability * 100d, satellite.navState));
	}
}
