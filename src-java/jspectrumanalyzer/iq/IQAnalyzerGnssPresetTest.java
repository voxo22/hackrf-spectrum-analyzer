package jspectrumanalyzer.iq;

/** Regression for signal-analyzer selections which automatically tune RF. */
public final class IQAnalyzerGnssPresetTest {
	public static void main(String[] args) {
		long gnss = IQAnalyzerApp.signalAnalyzerAutoCenterHz(SignalQualityTesterFrame.GNSS_MODE);
		if (gnss != 1_575_420_000L) throw new AssertionError("GNSS L1 must tune 1575.420 MHz, got " + gnss);
		if (IQAnalyzerApp.signalAnalyzerAutoCenterHz("Wi-Fi") != 0L
				|| IQAnalyzerApp.signalAnalyzerAutoCenterHz(null) != 0L)
			throw new AssertionError("Unrelated analyzers must not retune RF");
		System.out.println("GNSS L1 analyzer auto-center preset test passed");
	}
}
