package jspectrumanalyzer.iq;

import java.util.Locale;

/** Deterministic B1C primary-code, BCH NAV and FFT acquisition regression. */
public final class BeiDouB1cSignalAnalyzerTest {
	public static void main(String[] args) {
		checkOfficialCodeEdges(); checkSubframe1Decoder(); checkLdpcAndFullFrame(); checkAcquisition();
		System.out.println("BeiDou B1C code, BCH/LDPC navigation and acquisition tests passed");
	}

	private static void checkLdpcAndFullFrame() {
		double[] longLlr = new double[1200], shortLlr = new double[528];
		java.util.Arrays.fill(longLlr, -4d); java.util.Arrays.fill(shortLlr, -4d);
		longLlr[17] = .4d; shortLlr[29] = .4d;
		int[] longWord = BeiDouB1cLdpc.decodeLong(longLlr), shortWord = BeiDouB1cLdpc.decodeShort(shortLlr);
		if (longWord == null || shortWord == null || countOnes(longWord) != 0 || countOnes(shortWord) != 0)
			throw new AssertionError("B-CNAV1 GF(64) LDPC did not repair a one-bit error");

		double[] frame = new double[1800], sf1 = BeiDouB1cNavDecoder.encodeSubframe1(19, 73);
		System.arraycopy(sf1, 0, frame, 0, sf1.length);
		for (int input = 0; input < 1728; input++) frame[72 + input] = 1d; // all-zero LDPC codewords
		BeiDouB1cNavigationData.FullFrame decoded = BeiDouB1cNavigationData.decode(frame, 19, 100L);
		if (!decoded.valid || !decoded.sf2CrcValid || !decoded.sf3CrcValid || decoded.ephemeris == null
				|| decoded.prn != 19 || decoded.soh != 73)
			throw new AssertionError("Complete B-CNAV1 frame did not decode");
		BeiDouB1cNavDecoder streaming = new BeiDouB1cNavDecoder(19);
		for (double symbol : frame) streaming.acceptSoft(symbol);
		if (!streaming.navigation().hasValidatedFrame() || streaming.navigation().ephemeris == null)
			throw new AssertionError("Streaming B-CNAV1 frame assembly did not publish ephemeris");
	}
	private static int countOnes(int[] bits) { int count = 0; for (int bit : bits) count += bit; return count; }

	private static void checkOfficialCodeEdges() {
		int[] data = BeiDouB1cCodes.primaryCode(1, false), pilot = BeiDouB1cCodes.primaryCode(1, true);
		if (!"53773116".equals(octal(data, 0)) || !"42711657".equals(octal(data, data.length - 24)))
			throw new AssertionError("B1C data PRN 1 does not match ICD Table 5-2");
		if (!"71676756".equals(octal(pilot, 0)) || !"13053205".equals(octal(pilot, pilot.length - 24)))
			throw new AssertionError("B1C pilot PRN 1 does not match ICD Table 5-3");
	}

	private static void checkSubframe1Decoder() {
		double[] encoded = BeiDouB1cNavDecoder.encodeSubframe1(19, 73);
		encoded[4] *= -.2d; encoded[35] *= -.25d;
		BeiDouB1cNavDecoder.Frame frame = BeiDouB1cNavDecoder.decode(encoded, 19, 1234L);
		if (!frame.valid || frame.prn != 19 || frame.soh != 73 || frame.secondsOfHour != 1314
				|| frame.frameStartSymbol != 1234L)
			throw new AssertionError("B-CNAV1 BCH Subframe 1 did not decode");
		for (int index = 0; index < encoded.length; index++) encoded[index] = -encoded[index];
		if (!BeiDouB1cNavDecoder.decode(encoded, 19, 0L).valid)
			throw new AssertionError("B-CNAV1 polarity ambiguity was not handled");
		BeiDouB1cNavDecoder streaming = new BeiDouB1cNavDecoder(19);
		for (double symbol : BeiDouB1cNavDecoder.encodeSubframe1(19, 73)) streaming.acceptSoft(symbol);
		if (!streaming.latestFrame().valid || streaming.latestFrame().soh != 73)
			throw new AssertionError("First streaming B-CNAV1 BCH frame was not decoded");
	}

	private static void checkAcquisition() {
		int prn = 19, doppler = 1200, shift = 4321, codeSamples = BeiDouB1cSignalAnalyzer.ACQUISITION_SAMPLES;
		int inputSamples = codeSamples + 2;
		int[] code = BeiDouB1cCodes.primaryCode(prn, true); byte[] iq = new byte[inputSamples * 2];
		for (int n = 0; n < inputSamples; n++) {
			int sample = (n + shift) & (codeSamples - 1);
			int chip = (int) ((long) sample * BeiDouB1cCodes.CODE_CHIPS / codeSamples);
			int half = (int) ((long) sample * BeiDouB1cCodes.CODE_CHIPS * 2L / codeSamples) & 1;
			double value = 70d * code[chip] * (half == 0 ? 1d : -1d);
			double angle = 2d * Math.PI * doppler * n / BeiDouB1cSignalAnalyzer.ACQUISITION_RATE_HZ;
			iq[n * 2] = (byte) Math.round(value * Math.cos(angle));
			iq[n * 2 + 1] = (byte) Math.round(value * Math.sin(angle));
		}
		BeiDouB1cSignalAnalyzer.Result result = new BeiDouB1cSignalAnalyzer().analyzePrnRange(
				BeiDouB1cSignalAnalyzer.B1C_FREQUENCY_HZ, BeiDouB1cSignalAnalyzer.ACQUISITION_RATE_HZ,
				iq, iq.length, prn, 1);
		if (result.acquiredCount != 1 || result.satellites.isEmpty()
				|| result.satellites.get(0).prn != prn
				|| Math.abs(result.satellites.get(0).dopplerHz - doppler) > 100)
			throw new AssertionError("Synthetic BeiDou B1C acquisition failed: " + result.state);
	}

	private static String octal(int[] code, int offset) {
		int value = 0; for (int index = 0; index < 24; index++) value = (value << 1) | (code[offset + index] < 0 ? 1 : 0);
		return String.format(Locale.US, "%08o", value);
	}
}
