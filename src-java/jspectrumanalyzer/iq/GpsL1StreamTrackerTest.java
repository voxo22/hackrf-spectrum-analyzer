package jspectrumanalyzer.iq;

import java.util.Collections;
import java.util.Random;

/** End-to-end noiseless PRN, carrier and LNAV frame regression test. */
public final class GpsL1StreamTrackerTest {
	public static void main(String[] args) {
		testAcquisitionDelay();
		testNoiseOnly();
		testDopplerReacquisition();
		testLostChannelReplacement();
		testAcquisitionThroughLnav();
		testContinuousEphemeris();
		int rate = 2_600_000, samplesPerMs = rate / 1000, prn = 7;
		int[] code = GpsL1SignalAnalyzer.caCode(prn);
		int[] frame = GpsL1LnavDecoderTest.makeSubframe(3, 345_600);
		GpsL1StreamTracker tracker = new GpsL1StreamTracker();
		GpsL1SignalAnalyzer.Satellite satellite = new GpsL1SignalAnalyzer.Satellite(prn, 0, 0, 0,
				0d, 100d, 1d, 50d, 20, 1d, true, true);
		for (int ms = 0; ms < 6_150; ms++) {
			byte[] iq = new byte[samplesPerMs * 2];
			int bitIndex = (ms - 100) / 20;
			int dataSign = ms >= 100 && bitIndex < frame.length && frame[bitIndex] == 0 ? -1 : 1;
			for (int sample = 0; sample < samplesPerMs; sample++) {
				int chip = (int) ((long) sample * 1_023_000L / rate);
				iq[sample * 2] = (byte) (50 * code[chip] * dataSign);
			}
			tracker.accept(GpsL1SignalAnalyzer.L1_FREQUENCY_HZ, rate, iq, iq.length);
			if (ms == 0) tracker.updateAcquisition(GpsL1SignalAnalyzer.L1_FREQUENCY_HZ, rate, Collections.singletonList(satellite));
		}
		String state = tracker.navState(prn);
		if (!"LNAV SF 3".equals(state)) throw new AssertionError("Noiseless tracker failed: " + state);
		System.out.println("GPS continuous IQ to LNAV subframe test passed");
	}

	private static void testLostChannelReplacement() {
		int rate = 1000;
		long center = GpsL1SignalAnalyzer.L1_FREQUENCY_HZ;
		GpsL1StreamTracker tracker = new GpsL1StreamTracker();
		java.util.List<GpsL1SignalAnalyzer.Satellite> satellites = new java.util.ArrayList<GpsL1SignalAnalyzer.Satellite>();
		for (int prn = 1; prn <= 7; prn++) satellites.add(new GpsL1SignalAnalyzer.Satellite(prn, 0, 0, 0,
				0d, 100d, 1d, 50d, 20, 1d, true, true));
		tracker.accept(center, rate, new byte[2], 2);
		tracker.updateAcquisition(center, rate, satellites.subList(0, 6));
		tracker.updateAcquisition(center, rate, Collections.singletonList(satellites.get(6)));
		if (!"Not tracked".equals(tracker.navState(7))) throw new AssertionError("Channel limit was not enforced");
		byte[] silence = new byte[32_000];
		tracker.accept(center, rate, silence, silence.length);
		tracker.updateAcquisition(center, rate, Collections.singletonList(satellites.get(0)));
		tracker.accept(center, rate, silence, silence.length);
		tracker.updateAcquisition(center, rate, java.util.Arrays.asList(satellites.get(0), satellites.get(6)));
		if (!"Not tracked".equals(tracker.navState(2)) || "Not tracked".equals(tracker.navState(1)) || "Not tracked".equals(tracker.navState(7)))
			throw new AssertionError("Lost channel was not replaced or a current channel was discarded");
		System.out.println("GPS lost tracking channel replacement test passed");
	}

	private static void testDopplerReacquisition() {
		int rate = 2_600_000, samplesPerMs = rate / 1000, prn = 7;
		int[] code = GpsL1SignalAnalyzer.caCode(prn);
		GpsL1SignalAnalyzer analyzer = new GpsL1SignalAnalyzer();
		GpsL1SignalAnalyzer.Result result = null;
		for (int iteration = 0; iteration <= 10; iteration++) {
			int doppler = iteration == 0 ? -4000 : 4000;
			byte[] iq = new byte[samplesPerMs * 40];
			for (int sample = 0; sample < iq.length / 2; sample++) {
				int chip = (int) ((long) (sample % samplesPerMs) * 1_023_000L / rate);
				double phase = 2d * Math.PI * doppler * sample / rate;
				iq[sample * 2] = (byte) Math.round(50 * code[chip] * Math.cos(phase));
				iq[sample * 2 + 1] = (byte) Math.round(50 * code[chip] * Math.sin(phase));
			}
			result = analyzer.analyze(GpsL1SignalAnalyzer.L1_FREQUENCY_HZ, rate, iq, iq.length);
		}
		boolean reacquired = false;
		for (GpsL1SignalAnalyzer.Satellite sat : result.satellites)
			if (sat.prn == prn && sat.acquired && Math.abs(sat.acquisitionDopplerHz - 4000) <= 500) reacquired = true;
		if (!reacquired) throw new AssertionError("Periodic full Doppler search failed to reacquire a shifted PRN");
		System.out.println("GPS periodic full Doppler reacquisition test passed");
	}

	private static void testContinuousEphemeris() {
		int rate = 2_600_000, samplesPerMs = rate / 1000, prn = 7;
		int[] code = GpsL1SignalAnalyzer.caCode(prn);
		int[][] frames = { GpsL1LnavDecoderTest.makeSubframe(1, 345600), GpsL1LnavDecoderTest.makeSubframe(2, 345606),
				GpsL1LnavDecoderTest.makeSubframe(3, 345612) };
		GpsL1StreamTracker tracker = new GpsL1StreamTracker();
		GpsL1SignalAnalyzer.Satellite satellite = new GpsL1SignalAnalyzer.Satellite(prn, 0, 0, 0, 0d, 100d, 1d, 50d, 20, 1d, true, true);
		for (int ms = 0; ms < 18_150; ms++) {
			byte[] iq = new byte[samplesPerMs * 2];
			int index = (ms - 100) / 20;
			int dataSign = ms >= 100 && index < 900 && frames[index / 300][index % 300] == 0 ? -1 : 1;
			for (int sample = 0; sample < samplesPerMs; sample++) {
				int chip = (int) ((long) sample * 1_023_000L / rate);
				iq[sample * 2] = (byte) (50 * code[chip] * dataSign);
			}
			tracker.accept(GpsL1SignalAnalyzer.L1_FREQUENCY_HZ, rate, iq, iq.length);
			if (ms == 0) tracker.updateAcquisition(GpsL1SignalAnalyzer.L1_FREQUENCY_HZ, rate, Collections.singletonList(satellite));
		}
		GpsL1NavigationData.Snapshot nav = tracker.navigation(prn);
		if (nav == null || nav.ephemeris == null || !"LNAV SF 3".equals(tracker.navState(prn)))
			throw new AssertionError("Continuous SF 1/2/3 not assembled: " + (nav == null ? "no channel" : nav.ephemerisText()));
		java.util.List<GpsL1StreamTracker.Observation> observations = tracker.observations();
		if (observations.size() != 1 || Math.abs(observations.get(0).satelliteTime - 345612.050d) > .0015d)
			throw new AssertionError("Continuous LNAV timing is wrong: " + (observations.isEmpty() ? "no observation" : observations.get(0).satelliteTime));
		for (double phase : new double[] { 1022.8d, .1d }) {
			double before = tracker.observations().get(0).satelliteTime;
			GpsL1SignalAnalyzer.Satellite correction = new GpsL1SignalAnalyzer.Satellite(prn, 0, 0,
					(int) Math.round(phase * rate / 1_023_000d) % samplesPerMs, phase, 100d, 1d, 50d, 20, 1d, true, true);
			tracker.updateAcquisition(GpsL1SignalAnalyzer.L1_FREQUENCY_HZ, rate, Collections.singletonList(correction));
			double after = tracker.observations().get(0).satelliteTime;
			if (Math.abs(after - before) > .0001d) throw new AssertionError("Code-phase correction caused a ranging epoch jump");
		}
		System.out.println("GPS continuous IQ to three-subframe ephemeris test passed");
	}

	private static void testNoiseOnly() {
		int rate = 2_600_000;
		byte[] iq = new byte[(rate / 1000) * 40];
		Random random = new Random(12345L);
		for (int i = 0; i < iq.length; i++) iq[i] = (byte) (random.nextInt(101) - 50);
		GpsL1SignalAnalyzer.Result result = new GpsL1SignalAnalyzer().analyze(GpsL1SignalAnalyzer.L1_FREQUENCY_HZ, rate, iq, iq.length);
		if (result.acquiredCount != 0) throw new AssertionError("Noise-only input acquired " + result.acquiredCount + " PRNs");
		System.out.println("GPS noise-only acquisition test passed");
	}

	private static void testAcquisitionDelay() {
		int rate = 2_600_000, samplesPerMs = rate / 1000, prn = 7;
		int[] code = GpsL1SignalAnalyzer.caCode(prn);
		int[] offsets = { 0, 1, 173, 1299, 2599 };
		for (int offset : offsets) {
			byte[] iq = new byte[samplesPerMs * 40];
			for (int sample = 0; sample < iq.length / 2; sample++) {
				int chip = (int) (((long) (sample % samplesPerMs) + offset) * 1_023_000L / rate) % 1023;
				iq[sample * 2] = (byte) (50 * code[chip]);
			}
			GpsL1SignalAnalyzer.Result result = new GpsL1SignalAnalyzer().analyze(GpsL1SignalAnalyzer.L1_FREQUENCY_HZ, rate, iq, iq.length);
			GpsL1SignalAnalyzer.Satellite sat = result.satellites.get(0);
			int error = Math.abs(sat.codePhaseSamples - offset);
			error = Math.min(error, samplesPerMs - error);
			if (sat.prn != prn || error > 2 || !sat.trackingLocked)
				throw new AssertionError("Expected code delay " + offset + ", got PRN " + sat.prn + " delay " + sat.codePhaseSamples);
		}
		System.out.println("GPS acquisition delay test passed at five circular phases");
		for (double offset : new double[] { .3d, 173.2d, 173.7d, 2599.8d }) {
			byte[] iq = new byte[samplesPerMs * 40];
			for (int sample = 0; sample < iq.length / 2; sample++) {
				int chip = (int) (((sample % samplesPerMs) + offset) * 1_023_000d / rate) % 1023;
				iq[sample * 2] = (byte) (50 * code[chip]);
			}
			GpsL1SignalAnalyzer.Satellite sat = new GpsL1SignalAnalyzer().analyze(GpsL1SignalAnalyzer.L1_FREQUENCY_HZ, rate, iq, iq.length).satellites.get(0);
			double actual = sat.codePhaseChips * rate / 1_023_000d, error = Math.abs(actual - offset);
			error = Math.min(error, samplesPerMs - error);
			if (sat.prn != prn || error > .8d) throw new AssertionError("Fractional delay " + offset + " measured " + actual);
		}
		System.out.println("GPS fractional acquisition phase test passed");
	}

	private static void testAcquisitionThroughLnav() {
		int rate = 2_600_000, samplesPerMs = rate / 1000, prn = 7, offset = 173, doppler = 1500;
		int[] code = GpsL1SignalAnalyzer.caCode(prn), frame = GpsL1LnavDecoderTest.makeSubframe(3, 345_600);
		GpsL1SignalAnalyzer analyzer = new GpsL1SignalAnalyzer();
		byte[][] recent = new byte[20][];
		double step = 2d * Math.PI * doppler / rate, stepRe = Math.cos(step), stepIm = Math.sin(step);
		double carrierRe = 1d, carrierIm = 0d;
		for (int ms = 0; ms < 6_150; ms++) {
			byte[] iq = new byte[samplesPerMs * 2];
			int bitIndex = (ms - 100) / 20;
			int dataSign = ms >= 100 && bitIndex < frame.length && frame[bitIndex] == 0 ? -1 : 1;
			for (int sample = 0; sample < samplesPerMs; sample++) {
				int chip = (int) (((long) sample + offset) * 1_023_000L / rate) % 1023;
				int amplitude = 50 * code[chip] * dataSign;
				iq[sample * 2] = (byte) Math.round(amplitude * carrierRe);
				iq[sample * 2 + 1] = (byte) Math.round(amplitude * carrierIm);
				double nextRe = carrierRe * stepRe - carrierIm * stepIm;
				carrierIm = carrierRe * stepIm + carrierIm * stepRe; carrierRe = nextRe;
			}
			recent[ms % 20] = iq;
			analyzer.acceptStream(GpsL1SignalAnalyzer.L1_FREQUENCY_HZ, rate, iq, iq.length);
			if (ms == 19) {
				byte[] history = joinHistory(recent, ms);
				analyzer.analyze(GpsL1SignalAnalyzer.L1_FREQUENCY_HZ, rate, history, history.length);
			}
		}
		byte[] history = joinHistory(recent, 6_149);
		GpsL1SignalAnalyzer.Result result = analyzer.analyze(GpsL1SignalAnalyzer.L1_FREQUENCY_HZ, rate, history, history.length);
		for (GpsL1SignalAnalyzer.Satellite satellite : result.satellites)
			if (satellite.prn == prn && "LNAV SF 3".equals(satellite.navState)) {
				System.out.println("GPS acquisition-to-LNAV test passed with code offset and Doppler"); return;
			}
		throw new AssertionError("GPS acquisition-to-LNAV test did not decode: " + result.state);
	}

	private static byte[] joinHistory(byte[][] recent, int lastMs) {
		byte[] result = new byte[recent[0].length * recent.length];
		for (int ms = 0; ms < recent.length; ms++)
			System.arraycopy(recent[(lastMs + 1 + ms) % recent.length], 0, result, ms * recent[0].length, recent[0].length);
		return result;
	}
}
