package jspectrumanalyzer.iq;

/** Deterministic UMTS cell-confirmation checks; no SDR is required. */
public final class UmtsSignalAnalyzerTest {
	private UmtsSignalAnalyzerTest() { }

	public static void main(String[] args) {
		assertTrue(!candidate(0.0649d).isConfirmedCell(),
				"a weak CPICH search candidate must not enter the cell table");
		assertTrue(!candidate(0.20d).isConfirmedCell(),
				"CPICH correlation alone must not confirm a cell");

		UmtsSignalAnalyzer.Candidate crcConfirmed = candidate(0.060d)
				.withBch(new UmtsBchDecoder.BchData(true, new int[246], 0, 1d, 1));
		assertTrue(crcConfirmed.isConfirmedCell(),
				"a CRC-valid BCH must confirm a cell");
		System.out.println("UMTS analyzer tests passed");
	}

	private static UmtsSignalAnalyzer.Candidate candidate(double correlation) {
		return new UmtsSignalAnalyzer.Candidate(17, 2, 0, 0, correlation,
				-12d, -70d, 1, 0d);
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
