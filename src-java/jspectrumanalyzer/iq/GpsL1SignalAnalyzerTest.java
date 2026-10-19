package jspectrumanalyzer.iq;

/** Small deterministic check for the GPS L1 C/A acquisition correlation. */
public final class GpsL1SignalAnalyzerTest {
	public static void main(String[] args) {
		int sampleRateHz = 2_600_000, samples = sampleRateHz / 1000, prn = 7, dopplerHz = 1_500, codeOffset = 173;
		int[] code = GpsL1SignalAnalyzer.caCode(prn);
		byte[] iq = new byte[samples * 2];
		for (int n = 0; n < samples; n++) {
			int chip = ((int) ((long) n * 1_023_000L / sampleRateHz) + codeOffset) % 1023;
			double phase = 2d * Math.PI * dopplerHz * n / sampleRateHz;
			iq[2 * n] = (byte) Math.round(45d * code[chip] * Math.cos(phase));
			iq[2 * n + 1] = (byte) Math.round(45d * code[chip] * Math.sin(phase));
		}
		GpsL1SignalAnalyzer.Result result = new GpsL1SignalAnalyzer().analyze(
				GpsL1SignalAnalyzer.L1_FREQUENCY_HZ, sampleRateHz, iq, iq.length);
		if (result.acquiredCount < 1 || result.satellites.get(0).prn != prn) {
			StringBuilder candidates = new StringBuilder();
			for (GpsL1SignalAnalyzer.Satellite candidate : result.satellites)
				candidates.append(candidate.prn).append('/').append(candidate.dopplerHz).append('/').append(candidate.peakToNoise).append('/').append(candidate.coherence).append(' ');
			throw new AssertionError("Expected PRN " + prn + ", got " + result.state + " / " + candidates);
		}
		if (Math.abs(result.satellites.get(0).dopplerHz - dopplerHz) > 500)
			throw new AssertionError("Unexpected Doppler " + result.satellites.get(0).dopplerHz);
		GpsL1SignalAnalyzer.Result outOfBand = new GpsL1SignalAnalyzer().analyze(
				1_500_000_000L, sampleRateHz, iq, iq.length);
		if (outOfBand.acquiredCount != 0) throw new AssertionError("Out-of-band input must not acquire GPS");
		System.out.println("GPS L1 C/A acquisition test passed: " + result.satellites.get(0).prn);
	}
}
