package jspectrumanalyzer.iq;

import java.io.File;
import java.util.Locale;

/** Streaming performance and decode probe for the DSSS analyzer. */
final class WifiDsssStreamProbe {
	private WifiDsssStreamProbe() { }

	public static void main(String[] args) throws Exception {
		if (args.length == 0) return;
		long requestedMillis = args.length > 1 ? Long.parseLong(args[1]) : Long.MAX_VALUE;
		try (IQReplayFile replay = IQReplayFile.open(new File(args[0]))) {
			int rate = replay.getSampleRateHz();
			long duration = Math.min(requestedMillis, replay.getDurationMillis());
			long remaining = duration * rate / 1000L;
			int blockSamples = rate / 100;
			WifiDsssAnalyzer analyzer = new WifiDsssAnalyzer();
			WifiDsssAnalyzer.Result result = WifiDsssAnalyzer.Result.empty("searching");
			long processed = 0, previousDecoded = 0, analyzeNanos = 0;
			while (remaining > 0) {
				int samples = (int)Math.min(blockSamples, remaining);
				byte[] iq = new byte[samples * 2];
				replay.readLoopedSigned(iq);
				long started = System.nanoTime();
				result = analyzer.analyze(replay.getCenterFrequencyHz(), iq, iq.length, rate);
				analyzeNanos += System.nanoTime() - started;
				if (result.decodedPackets != previousDecoded) {
					System.out.println(String.format(Locale.US,
							"%8.1f ms decoded %d/%d: %s, %s, SSID '%s', channel %d, %s, rates %s, Barker %.1f%%",
							processed * 1000d / rate, result.decodedPackets, result.detectedPackets,
							result.state, result.frameType, result.ssid, result.channel,
							result.security, result.supportedRates,
							result.barkerCorrelation * 100d));
					previousDecoded = result.decodedPackets;
				}
				processed += samples;
				remaining -= samples;
			}
			System.out.println(String.format(Locale.US,
					"DSSS final: decoded %d/%d, analyzer CPU %.3f s (%.2fx real time)",
					result.decodedPackets, result.detectedPackets, analyzeNanos / 1e9,
					processed / (double)rate / Math.max(1e-9, analyzeNanos / 1e9)));
		}
	}
}
