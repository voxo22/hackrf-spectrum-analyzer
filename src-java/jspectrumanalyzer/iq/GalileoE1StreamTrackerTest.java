package jspectrumanalyzer.iq;

import java.util.Collections;

/** End-to-end synthetic E1-B tracking, symbol recovery, FEC and CRC test. */
public final class GalileoE1StreamTrackerTest {
	private static final int RATE = GalileoE1SignalAnalyzer.ACQUISITION_RATE_HZ;
	private static final int CODE_SAMPLES = 16384;
	private GalileoE1StreamTrackerTest() { }

	public static void main(String[] args) {
		checkShiftedCodeTracking();
		checkTowAnchor();
		int[] symbols = pageSymbols();
		GalileoE1StreamTracker tracker = new GalileoE1StreamTracker();
		int acquisitionSamples = RATE / 500 + 2;
		byte[] first = signal(symbols, 0, acquisitionSamples);
		tracker.accept(GalileoE1SignalAnalyzer.E1_FREQUENCY_HZ, RATE, first, first.length);
		GalileoE1SignalAnalyzer.Satellite acquired = new GalileoE1SignalAnalyzer.Satellite(
				1, 0, 0, 0d, 100d, 1d, 50d, true);
		tracker.updateAcquisition(GalileoE1SignalAnalyzer.E1_FREQUENCY_HZ, RATE,
				Collections.singletonList(acquired), acquisitionSamples);

		long totalSamples = (long) symbols.length * CODE_SAMPLES;
		long position = acquisitionSamples;
		while (position < totalSamples) {
			int count = (int) Math.min(131072L, totalSamples - position);
			byte[] chunk = signal(symbols, position, count);
			tracker.accept(GalileoE1SignalAnalyzer.E1_FREQUENCY_HZ, RATE, chunk, chunk.length);
			position += count;
		}
		GalileoE1StreamTracker.Tracking tracking = tracker.tracking(1);
		require(tracking.locked, "Galileo prompt tracking lock");
		require(tracking.symbols >= 500, "Galileo 250-symbol/s recovery");
		require(tracking.page.valid, "Galileo I/NAV CRC-valid vertical page");
		require(tracking.page.wordType == 4, "Galileo I/NAV word type through RF tracker");
		require(tracking.navigation.wordCount == 1 && "1/4 words".equals(tracking.navigation.ephemerisText()),
				"CRC-valid word enters Galileo ephemeris assembler");
		System.out.println("GalileoE1StreamTrackerTest: OK");
	}

	private static void checkTowAnchor() {
		long start = GalileoE1StreamTracker.pageStartEpoch(725L, 2L);
		require(start == 227L, "I/NAV vertical-page start epoch");
		double atDecode = GalileoE1StreamTracker.satelliteTime(345678, 727L, start, 0d);
		near(atDecode, 345680d, 1e-12, "TOW must refer to page start");
		double fractional = GalileoE1StreamTracker.satelliteTime(345678, 727L, start, 1023d);
		near(fractional, 345680.0005d, 1e-12, "fractional E1 code phase");
	}

	private static void checkShiftedCodeTracking() {
		int shift = 1237, acquisitionSamples = RATE / 500 + 2;
		GalileoE1StreamTracker tracker = new GalileoE1StreamTracker();
		byte[] first = shiftedSignal(0L, acquisitionSamples, shift);
		tracker.accept(GalileoE1SignalAnalyzer.E1_FREQUENCY_HZ, RATE, first, first.length);
		GalileoE1SignalAnalyzer.Satellite acquired = new GalileoE1SignalAnalyzer.Satellite(
				1, 0, shift, shift * 4092d / CODE_SAMPLES, 100d, 1d, 50d, true);
		tracker.updateAcquisition(GalileoE1SignalAnalyzer.E1_FREQUENCY_HZ, RATE,
				Collections.singletonList(acquired), acquisitionSamples);
		long total = 80L * CODE_SAMPLES, position = acquisitionSamples;
		while (position < total) {
			int count = (int) Math.min(131072L, total - position);
			byte[] chunk = shiftedSignal(position, count, shift);
			tracker.accept(GalileoE1SignalAnalyzer.E1_FREQUENCY_HZ, RATE, chunk, chunk.length);
			position += count;
		}
		GalileoE1StreamTracker.Tracking tracking = tracker.tracking(1);
		require(tracking.locked && tracking.stability > .5d,
				"non-zero Galileo acquisition code phase must enter prompt lock: " + tracking.stability);
	}

	private static int[] pageSymbols() {
		int[] even = new int[120], odd = new int[120]; even[0] = 0; odd[0] = 1;
		int[] word = new int[128]; word[3] = 1; word[21] = 1; // type 4, SVID 1
		for (int index = 0; index < 112; index++) even[index + 2] = word[index];
		for (int index = 0; index < 16; index++) odd[index + 2] = word[index + 112];
		for (int index = 18; index < 82; index++) odd[index] = (index * 5 + 1) & 1;
		int[] protectedBits = new int[196];
		System.arraycopy(even, 0, protectedBits, 0, 114); System.arraycopy(odd, 0, protectedBits, 114, 82);
		int crc = GalileoInavDecoder.crc24(protectedBits);
		for (int index = 0; index < 24; index++) odd[index + 82] = (crc >>> (23 - index)) & 1;
		int[] result = new int[501]; result[0] = 0;
		appendPart(result, 1, even); appendPart(result, 251, odd); return result;
	}

	private static void appendPart(int[] target, int offset, int[] clear) {
		String sync = "0101100000";
		for (int index = 0; index < 10; index++) target[offset + index] = sync.charAt(index) - '0';
		int[] encoded = new int[240], interleaved = new int[240]; int state = 0;
		for (int index = 0; index < 120; index++) {
			int register = state | (clear[index] << 6); state = register >>> 1;
			encoded[index * 2] = Integer.bitCount(register & 0171) & 1;
			encoded[index * 2 + 1] = (Integer.bitCount(register & 0133) & 1) ^ 1;
		}
		for (int row = 0; row < 8; row++) for (int column = 0; column < 30; column++)
			interleaved[row * 30 + column] = encoded[column * 8 + row];
		System.arraycopy(interleaved, 0, target, offset + 10, 240);
	}

	private static byte[] signal(int[] symbols, long firstSample, int samples) {
		byte[] iq = new byte[samples * 2];
		for (int index = 0; index < samples; index++) {
			long absolute = firstSample + index;
			int symbol = symbols[(int) (absolute / CODE_SAMPLES)];
			double value = GalileoE1SignalAnalyzer.bocReplicaSample(1, (int) (absolute % CODE_SAMPLES))
					* (symbol == 0 ? 70d : -70d);
			iq[index * 2] = (byte) Math.round(value); iq[index * 2 + 1] = 0;
		}
		return iq;
	}

	private static byte[] shiftedSignal(long firstSample, int samples, int shift) {
		byte[] iq = new byte[samples * 2];
		for (int index = 0; index < samples; index++) {
			long absolute = firstSample + index;
			double value = GalileoE1SignalAnalyzer.bocReplicaSample(1,
					(int) ((absolute - shift) % CODE_SAMPLES)) * 70d;
			iq[index * 2] = (byte) Math.round(value);
		}
		return iq;
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
	private static void near(double actual, double expected, double tolerance, String message) {
		if (Math.abs(actual - expected) > tolerance)
			throw new AssertionError(message + ": " + actual + " != " + expected);
	}
}
