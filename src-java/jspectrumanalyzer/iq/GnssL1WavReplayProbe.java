package jspectrumanalyzer.iq;

import java.io.File;
import java.util.Locale;

/** Offline acquisition probe for a stereo 8-bit/16-bit GNSS IQ WAV recording. */
public final class GnssL1WavReplayProbe {
	private static final int GPS_BANDWIDTH_HZ = 2_100_000;
	private static final int GPS_TARGET_RATE_HZ = 2_200_000;
	private GnssL1WavReplayProbe() { }

	public static void main(String[] args) throws Exception {
		if (args.length < 1 || args.length > 2 || (args.length == 2
				&& !"--positions-only".equals(args[1]) && !"--track-only".equals(args[1])))
			throw new IllegalArgumentException("GNSS IQ WAV path required; optional: --positions-only or --track-only");
		try (IQReplayFile replay = IQReplayFile.open(new File(args[0]))) {
			long duration = replay.getDurationMillis();
			int rate = replay.getSampleRateHz();
			System.out.println(String.format(Locale.US, "%s: center %.6f MHz, %.3f MS/s, %.3f s",
					replay.getFile().getName(), replay.getCenterFrequencyHz() / 1e6, rate / 1e6, duration / 1000d));
			long[] positions = positions(duration);
			boolean positionsOnly = args.length == 2 && "--positions-only".equals(args[1]);
			boolean trackOnly = args.length == 2 && "--track-only".equals(args[1]);
			if (!trackOnly) for (long position : positions) analyzePosition(replay, position);
			if (duration >= 60_000L && !positionsOnly)
				trackGalileoProgressively(replay, positions[0], 25, 50_000L);
		}
	}

	private static void analyzePosition(IQReplayFile replay, long positionMillis) throws Exception {
		int rate = replay.getSampleRateHz();
		int bytes = Math.max(2, (int) Math.round(rate * .025d) * 2);
		byte[] iq = new byte[bytes]; replay.seekMillis(positionMillis); replay.readLoopedSigned(iq);
		double sumI = 0d, sumQ = 0d, sumSquares = 0d; int peak = 0, clipped = 0;
		for (int index = 0; index < iq.length; index += 2) {
			int i = iq[index], q = iq[index + 1]; sumI += i; sumQ += q; sumSquares += i * i + q * q;
			peak = Math.max(peak, Math.max(Math.abs(i), Math.abs(q)));
			if (Math.abs(i) >= 127 || Math.abs(q) >= 127) clipped++;
		}
		int samples = iq.length / 2;
		double rms = Math.sqrt(sumSquares / Math.max(1d, samples * 2d));

		byte[] gpsIq = iq; int gpsRate = rate; long gpsCenter = replay.getCenterFrequencyHz();
		if (rate > 4_096_000) {
			IQSubchannelExtractor.Result extracted = IQSubchannelExtractor.extract(iq, iq.length, rate,
					GpsL1SignalAnalyzer.L1_FREQUENCY_HZ - replay.getCenterFrequencyHz(),
					GPS_BANDWIDTH_HZ, GPS_TARGET_RATE_HZ);
			gpsIq = quantize(extracted); gpsRate = (int) Math.round(extracted.sampleRateHz);
			gpsCenter = GpsL1SignalAnalyzer.L1_FREQUENCY_HZ;
		}
		GpsL1SignalAnalyzer.Result gps = new GpsL1SignalAnalyzer().analyze(gpsCenter, gpsRate, gpsIq, gpsIq.length);
		GalileoE1SignalAnalyzer.Result galileo = new GalileoE1SignalAnalyzer().analyzeSvidRange(
				replay.getCenterFrequencyHz(), rate, iq, iq.length, 1, 36);
		System.out.println(String.format(Locale.US,
				"@%6.2fs IQ rms %6.1f dBFS peak %3d DC %+.2f/%+.2f clip %.4f%% | GPS %d | Galileo %d",
				positionMillis / 1000d, 20d * Math.log10(Math.max(1e-12d, rms / 128d)), peak,
				sumI / samples, sumQ / samples, clipped * 100d / samples, gps.acquiredCount, galileo.acquiredCount));
		int gpsShown = 0;
		for (GpsL1SignalAnalyzer.Satellite satellite : gps.candidates) {
			if (gpsShown++ >= 6) break;
			System.out.println(String.format(Locale.US, "  G%02d %s doppler %+5d Hz coherence %5.1f%% C/N0 %5.1f",
					satellite.prn, satellite.acquired ? "ACQ" : "candidate", satellite.dopplerHz,
					satellite.coherence * 100d, satellite.cNoDbHz));
		}
		int galileoShown = 0;
		for (GalileoE1SignalAnalyzer.Satellite satellite : galileo.candidates) {
			if (galileoShown++ >= 8) break;
			System.out.println(String.format(Locale.US, "  E%02d %s doppler %+5d Hz phase %7.1f chips B/C/combined %4.1f/%4.1f/%4.1f%% C/N0 %5.1f",
					satellite.svid, satellite.acquired ? "ACQ" : "candidate", satellite.dopplerHz,
					satellite.codePhaseChips, satellite.dataCoherence * 100d,
					satellite.pilotCoherence * 100d, satellite.coherence * 100d, satellite.cNoDbHz));
		}
	}

	private static void trackGalileoProgressively(IQReplayFile replay, long startMillis, int svid, long durationMillis) throws Exception {
		int rate = replay.getSampleRateHz(), chunkMillis = 20;
		byte[] iq = new byte[(int) Math.round(rate * chunkMillis / 1000d) * 2];
		replay.seekMillis(startMillis);
		GalileoE1SignalAnalyzer analyzer = new GalileoE1SignalAnalyzer();
		GalileoE1SignalAnalyzer.Result result = null;
		GalileoE1SignalAnalyzer.Satellite initial = null; boolean phaseDiagnosticPending = false;
		String lastNavState = null;
		int chunks = (int) (durationMillis / chunkMillis);
		for (int chunk = 0; chunk < chunks; chunk++) {
			replay.readLoopedSigned(iq);
			analyzer.acceptStream(replay.getCenterFrequencyHz(), rate, iq, iq.length);
			if (chunk % 25 == 0) {
				result = analyzer.analyze(replay.getCenterFrequencyHz(), rate, iq, iq.length);
				GalileoE1SignalAnalyzer.Satellite state = find(result, svid);
				if (initial == null && state != null && state.acquired) {
					initial = state; phaseDiagnosticPending = true;
				}
				if (state != null && (chunk % 100 == 0 || !state.navState.equals(lastNavState))) {
					System.out.println(String.format(Locale.US,
							"  E%02d +%.1fs acq=%s doppler=%+d phase=%.1f stability=%.2f%% symbols=%d %s",
							svid, chunk * chunkMillis / 1000d, state.acquired, state.dopplerHz,
							state.codePhaseChips, state.trackingStability * 100d, state.trackingSymbols, state.navState));
				}
				if (state != null) lastNavState = state.navState;
			} else if (phaseDiagnosticPending) {
				printPromptPhaseDiagnostic(iq, rate, initial); phaseDiagnosticPending = false;
			}
		}
		result = analyzer.analyze(replay.getCenterFrequencyHz(), rate, iq, iq.length);
		GalileoE1SignalAnalyzer.Satellite satellite = find(result, svid);
		System.out.println(String.format(Locale.US,
				"Progressive live-style E%02d from %.2fs for %.1fs: %s, symbols %d, stability %.1f%%, %s",
				svid, startMillis / 1000d, durationMillis / 1000d,
				satellite == null ? "no candidate" : satellite.trackingLocked ? "TRACKED" : satellite.acquired ? "ACQ only" : "candidate only",
				satellite == null ? 0L : satellite.trackingSymbols,
				satellite == null ? 0d : satellite.trackingStability * 100d,
				satellite == null ? "--" : satellite.navState));
		if (satellite != null && satellite.navigation.ephemeris != null) {
			GalileoNavigationData.Ephemeris ephemeris = satellite.navigation.ephemeris;
			System.out.println(String.format(Locale.US,
					"  ephemeris E%02d IODnav %d Toe/Toc %d/%d sqrt(A) %.3f e %.8f SISA %d; %s",
					ephemeris.svid, ephemeris.iodNav, ephemeris.toeSeconds, ephemeris.tocSeconds,
					ephemeris.sqrtA, ephemeris.eccentricity, ephemeris.sisaIndex,
					satellite.navigation.healthText()));
		}
		for (GalileoE1StreamTracker.Observation observation : result.observations)
			System.out.println(String.format(Locale.US,
					"  ranging E%02d GST week/TOW %d / %.3f s, receive epoch %.3f s",
					observation.svid, observation.weekModulo4096,
					(observation.satelliteTime % 604800d + 604800d) % 604800d,
					observation.receptionSeconds));
		GpsL1PositionSolver.Solution galileoPosition = GalileoE1Positioning.solve(result.observations);
		System.out.println("  Galileo ranging/PVT: " + galileoPosition.state);
	}

	private static GalileoE1SignalAnalyzer.Satellite find(GalileoE1SignalAnalyzer.Result result, int svid) {
		if (result != null) for (GalileoE1SignalAnalyzer.Satellite satellite : result.satellites)
			if (satellite.svid == svid) return satellite;
		return null;
	}

	private static void printPromptPhaseDiagnostic(byte[] iq, int rate, GalileoE1SignalAnalyzer.Satellite satellite) {
		int acquisitionSamples = (int) Math.ceil(8192d * rate / GalileoE1SignalAnalyzer.ACQUISITION_RATE_HZ) + 2;
		double scale = 8184d / 16384d, step = 2_046_000d / rate;
		double normal = wrapHalfChips(-satellite.codePhaseSamples * scale + acquisitionSamples * step);
		double opposite = wrapHalfChips(satellite.codePhaseSamples * scale + acquisitionSamples * step);
		double[] a = bestPrompt(iq, rate, satellite.svid, satellite.dopplerHz, normal);
		double[] b = bestPrompt(iq, rate, satellite.svid, satellite.dopplerHz, opposite);
		System.out.println(String.format(Locale.US,
				"  phase diagnostic: normal %.2f%% at %+.0f half-chips; opposite %.2f%% at %+.0f",
				a[0] * 100d, a[1], b[0] * 100d, b[1]));
	}

	private static double[] bestPrompt(byte[] iq, int rate, int svid, int doppler, double basePhase) {
		int[] code = GalileoE1SignalAnalyzer.primaryCode(svid);
		double best = 0d, bestOffset = 0d;
		for (int offset = -120; offset <= 120; offset++) {
			double phase = wrapHalfChips(basePhase + offset), re = 0d, im = 0d, absolute = 0d;
			double carrierStep = -2d * Math.PI * doppler / rate;
			int samples = Math.min(iq.length / 2, (int) Math.round(rate * .004d));
			for (int n = 0; n < samples; n++) {
				int half = Math.min(8183, Math.max(0, (int) phase));
				double replica = code[half >>> 1] * ((half & 1) == 0 ? 1d : -1d);
				double angle = carrierStep * n, cosine = Math.cos(angle), sine = Math.sin(angle);
				double mixedRe = iq[n * 2] * cosine - iq[n * 2 + 1] * sine;
				double mixedIm = iq[n * 2] * sine + iq[n * 2 + 1] * cosine;
				re += replica * mixedRe; im += replica * mixedIm;
				absolute += Math.abs(mixedRe) + Math.abs(mixedIm);
				phase = wrapHalfChips(phase + 2_046_000d / rate);
			}
			double coherence = Math.hypot(re, im) / Math.max(1d, absolute);
			if (coherence > best) { best = coherence; bestOffset = offset; }
		}
		return new double[] { best, bestOffset };
	}

	private static double wrapHalfChips(double value) {
		while (value >= 8184d) value -= 8184d;
		while (value < 0d) value += 8184d;
		return value;
	}

	private static byte[] quantize(IQSubchannelExtractor.Result extracted) {
		byte[] output = new byte[extracted.samples * 2];
		double scale = 127d * Math.sqrt(extracted.decimation);
		for (int index = 0; index < output.length; index++) {
			int value = (int) Math.round(extracted.iq[index] * scale);
			output[index] = (byte) Math.max(-127, Math.min(127, value));
		}
		return output;
	}

	private static long[] positions(long durationMillis) {
		long margin = Math.min(5_000L, Math.max(0L, durationMillis / 10L));
		long span = Math.max(0L, durationMillis - margin * 2L - 30L);
		return new long[] { margin, margin + span / 4L, margin + span / 2L,
				margin + span * 3L / 4L, margin + span };
	}
}
