package jspectrumanalyzer.iq;

import java.util.Random;

/** Deterministic synthetic acquisition checks; runs without test libraries. */
public final class GalileoE1SignalAnalyzerTest {
	private GalileoE1SignalAnalyzerTest() { }

	public static void main(String[] args) {
		checkOfficialCodeMapping();
		checkSyntheticAcquisition();
		checkPilotAssistedAcquisition();
		checkAcquisitionToShiftedTracking();
		checkFiveMspsAndOffsetCenter();
		checkNoiseRejected();
		checkBandwidthGuard();
		System.out.println("GalileoE1SignalAnalyzerTest: OK");
	}

	private static void checkOfficialCodeMapping() {
		int[] code = GalileoE1SignalAnalyzer.primaryCode(1);
		require(code.length == 4092, "E1-B primary code length");
		// Official hexadecimal prefix F5: 1111 0101, with logic 1 mapped to -1.
		int[] expected = { -1, -1, -1, -1, 1, -1, 1, -1 };
		for (int index = 0; index < expected.length; index++)
			require(code[index] == expected[index], "official E1-B hexadecimal mapping at chip " + index);
		int[] pilot = GalileoE1SignalAnalyzer.pilotPrimaryCode(1);
		require(pilot.length == 4092, "E1-C primary code length");
		// Official hexadecimal prefix B3: 1011 0011, with logic 1 mapped to -1.
		int[] pilotExpected = { -1, 1, -1, -1, 1, 1, -1, -1 };
		for (int index = 0; index < pilotExpected.length; index++)
			require(pilot[index] == pilotExpected[index], "official E1-C hexadecimal mapping at chip " + index);
	}

	private static void checkSyntheticAcquisition() {
		int rate = GalileoE1SignalAnalyzer.ACQUISITION_RATE_HZ;
		int samples = rate / 500 + 2; // 2 ms plus interpolation look-ahead
		int svid = 1, codeShift = 1237, doppler = 1725;
		byte[] iq = new byte[samples * 2];
		Random random = new Random(0xE1B);
		for (int n = 0; n < samples; n++) {
			double code = GalileoE1SignalAnalyzer.bocReplicaSample(svid, n - codeShift);
			double phase = 2d * Math.PI * doppler * n / rate;
			int i = (int) Math.round(54d * code * Math.cos(phase) + random.nextGaussian() * 7d);
			int q = (int) Math.round(54d * code * Math.sin(phase) + random.nextGaussian() * 7d);
			iq[n * 2] = (byte) Math.max(-127, Math.min(127, i));
			iq[n * 2 + 1] = (byte) Math.max(-127, Math.min(127, q));
		}
		GalileoE1SignalAnalyzer analyzer = new GalileoE1SignalAnalyzer();
		GalileoE1SignalAnalyzer.Result result = analyzer.analyzeSvidRange(
				GalileoE1SignalAnalyzer.E1_FREQUENCY_HZ, rate, iq, iq.length, 1, 3);
		String diagnostics = result.state;
		for (GalileoE1SignalAnalyzer.Satellite item : result.satellites)
			diagnostics += " E" + item.svid + " coh=" + item.coherence + " doppler=" + item.dopplerHz;
		require(result.acquiredCount == 1, "one synthetic Galileo satellite acquired: " + diagnostics);
		require(result.candidates.size() == 3, "all searched Galileo candidates retained for diagnostics");
		GalileoE1SignalAnalyzer.Satellite satellite = result.satellites.get(0);
		require(satellite.svid == svid, "correct Galileo SVID");
		require(Math.abs(satellite.dopplerHz - doppler) <= 75, "Galileo fine Doppler bin");
		require(satellite.coherence > .7d, "strong Galileo acquisition coherence");
	}

	private static void checkPilotAssistedAcquisition() {
		int rate = GalileoE1SignalAnalyzer.ACQUISITION_RATE_HZ;
		int samples = rate / 500 + 2, svid = 2, codeShift = 911, doppler = -1325;
		byte[] iq = new byte[samples * 2];
		Random random = new Random(0xE1C);
		for (int n = 0; n < samples; n++) {
			double code = GalileoE1SignalAnalyzer.pilotBocReplicaSample(svid, n - codeShift);
			double phase = 2d * Math.PI * doppler * n / rate;
			int i = (int) Math.round(50d * code * Math.cos(phase) + random.nextGaussian() * 7d);
			int q = (int) Math.round(50d * code * Math.sin(phase) + random.nextGaussian() * 7d);
			iq[n * 2] = (byte) Math.max(-127, Math.min(127, i));
			iq[n * 2 + 1] = (byte) Math.max(-127, Math.min(127, q));
		}
		GalileoE1SignalAnalyzer.Result result = new GalileoE1SignalAnalyzer().analyzeSvidRange(
				GalileoE1SignalAnalyzer.E1_FREQUENCY_HZ, rate, iq, iq.length, svid, 1);
		require(result.acquiredCount == 1, "E1-C pilot must assist acquisition");
		GalileoE1SignalAnalyzer.Satellite satellite = result.satellites.get(0);
		require(satellite.svid == svid && satellite.pilotCoherence > .7d,
				"pilot correlation must identify the Galileo SVID");
		require(satellite.pilotCoherence > satellite.dataCoherence * 3d,
				"pilot-only test must be attributed to E1-C");
		require(Math.abs(satellite.dopplerHz - doppler) <= 75, "pilot-assisted fine Doppler bin");
	}

	private static void checkBandwidthGuard() {
		GalileoE1SignalAnalyzer.Result result = new GalileoE1SignalAnalyzer().analyzeSvidRange(
				GalileoE1SignalAnalyzer.E1_FREQUENCY_HZ, 2_200_000, new byte[20_000], 20_000, 1, 1);
		require(result.acquiredCount == 0 && result.state.contains("4.2 MS/s"), "wide E1 bandwidth guard");
	}

	private static void checkAcquisitionToShiftedTracking() {
		int rate = GalileoE1SignalAnalyzer.ACQUISITION_RATE_HZ, shift = 1237;
		int firstSamples = rate / 500 + 2, totalSamples = 80 * 16384;
		GalileoE1SignalAnalyzer analyzer = new GalileoE1SignalAnalyzer();
		byte[] first = shiftedSignal(0, firstSamples, shift);
		analyzer.acceptStream(GalileoE1SignalAnalyzer.E1_FREQUENCY_HZ, rate, first, first.length);
		GalileoE1SignalAnalyzer.Result acquired = analyzer.analyzeSvidRange(
				GalileoE1SignalAnalyzer.E1_FREQUENCY_HZ, rate, first, first.length, 1, 1);
		require(acquired.acquiredCount == 1, "shifted signal acquisition before tracking");
		int position = firstSamples;
		while (position < totalSamples) {
			int count = Math.min(131072, totalSamples - position);
			byte[] block = shiftedSignal(position, count, shift);
			analyzer.acceptStream(GalileoE1SignalAnalyzer.E1_FREQUENCY_HZ, rate, block, block.length);
			position += count;
		}
		byte[] tail = shiftedSignal(totalSamples - rate / 100, rate / 100, shift);
		GalileoE1SignalAnalyzer.Result result = analyzer.analyzeSvidRange(
				GalileoE1SignalAnalyzer.E1_FREQUENCY_HZ, rate, tail, tail.length, 1, 1);
		require(result.satellites.get(0).trackingLocked,
				"acquisition code phase must initialize shifted continuous tracker: "
						+ result.satellites.get(0).trackingStability);
	}

	private static byte[] shiftedSignal(int firstSample, int samples, int shift) {
		byte[] iq = new byte[samples * 2];
		for (int index = 0; index < samples; index++) {
			int absolute = firstSample + index;
			double code = GalileoE1SignalAnalyzer.bocReplicaSample(1, absolute - shift);
			iq[index * 2] = (byte) Math.round(code * 60d);
		}
		return iq;
	}

	private static void checkFiveMspsAndOffsetCenter() {
		int rate = 5_000_000, doppler = -2175, codeShift = 733;
		long center = GalileoE1SignalAnalyzer.E1_FREQUENCY_HZ - 200_000L;
		int samples = (int) Math.ceil(8192d * rate / GalileoE1SignalAnalyzer.ACQUISITION_RATE_HZ) + 2;
		byte[] iq = new byte[samples * 2];
		for (int n = 0; n < samples; n++) {
			int acquisitionSample = (int) Math.floor(n * (double) GalileoE1SignalAnalyzer.ACQUISITION_RATE_HZ / rate);
			double code = GalileoE1SignalAnalyzer.bocReplicaSample(1, acquisitionSample - codeShift);
			double phase = 2d * Math.PI * (200_000d + doppler) * n / rate;
			iq[n * 2] = (byte) Math.round(58d * code * Math.cos(phase));
			iq[n * 2 + 1] = (byte) Math.round(58d * code * Math.sin(phase));
		}
		GalileoE1SignalAnalyzer.Result result = new GalileoE1SignalAnalyzer().analyzeSvidRange(
				center, rate, iq, iq.length, 1, 1);
		require(result.acquiredCount == 1, "5 MS/s off-center Galileo acquisition");
		require(Math.abs(result.satellites.get(0).dopplerHz - doppler) <= 75,
				"5 MS/s off-center Doppler");
	}

	private static void checkNoiseRejected() {
		int rate = GalileoE1SignalAnalyzer.ACQUISITION_RATE_HZ;
		byte[] iq = new byte[(rate / 500 + 2) * 2];
		new Random(42L).nextBytes(iq);
		GalileoE1SignalAnalyzer.Result result = new GalileoE1SignalAnalyzer().analyzeSvidRange(
				GalileoE1SignalAnalyzer.E1_FREQUENCY_HZ, rate, iq, iq.length, 1, 36);
		require(result.acquiredCount == 0, "noise must not acquire a Galileo E1-B/C SVID");
		require(result.candidates.size() == 36, "full noise scan must expose all candidates for diagnostics");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
