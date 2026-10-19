package jspectrumanalyzer.iq;

/** Small deterministic parity and preamble test; it needs no RF recording. */
public final class GpsL1LnavDecoderTest {
	public static void main(String[] args) {
		int[] frame = makeSubframe(3, 345_600);
		GpsL1LnavDecoder.Result result = GpsL1LnavDecoder.decodeSubframe(frame);
		if (!result.valid || result.subframeId != 3 || result.timeOfWeekSeconds != 345_600)
			throw new AssertionError("LNAV frame was not accepted");
		for (int bit = 0; bit < frame.length; bit++) frame[bit] ^= 1;
		if (!GpsL1LnavDecoder.decodeSubframe(frame).valid)
			throw new AssertionError("LNAV Costas-loop polarity was not accepted");
		for (int bit = 0; bit < frame.length; bit++) frame[bit] ^= 1;
		frame[167] ^= 1;
		if (GpsL1LnavDecoder.decodeSubframe(frame).valid)
			throw new AssertionError("LNAV parity error was accepted");
		if (GpsL1LnavDecoder.decodeSubframe(makeSubframe(1, 604800)).valid)
			throw new AssertionError("Out-of-range GPS week time was accepted");
		System.out.println("GPS LNAV parity test passed");
	}

	static int[] makeSubframe(int subframeId, int timeOfWeekSeconds) {
		return makeSubframe(subframeId, timeOfWeekSeconds, new int[10][24]);
	}

	static int[] makeSubframe(int subframeId, int timeOfWeekSeconds, int[][] dataWords) {
		int[] output = new int[GpsL1LnavDecoder.BITS_PER_SUBFRAME];
		int previousD29 = 0, previousD30 = 0;
		for (int word = 0; word < GpsL1LnavDecoder.WORDS_PER_SUBFRAME; word++) {
			int[] data = dataWords[word].clone();
			if (word == 0) put(data, 0, 8, 0x8b);
			if (word == 1) {
				put(data, 0, 17, timeOfWeekSeconds / 6);
				put(data, 19, 3, subframeId);
			}
			int[] raw = new int[30];
			for (int bit = 0; bit < 24; bit++) raw[bit] = data[bit] ^ previousD30;
			int[] parity = parity(data, previousD29, previousD30);
			for (int bit = 0; bit < 6; bit++) raw[24 + bit] = parity[bit];
			System.arraycopy(raw, 0, output, word * 30, 30);
			previousD29 = raw[28]; previousD30 = raw[29];
		}
		return output;
	}

	private static int[] parity(int[] d, int d29, int d30) {
		return new int[] {
			xor(d29, bits(d,1,2,3,5,6,10,11,12,13,14,17,18,20,23)), xor(d30,bits(d,2,3,4,6,7,11,12,13,14,15,18,19,21,24)),
			xor(d29,bits(d,1,3,4,5,7,8,12,13,14,15,16,19,20,22)), xor(d30,bits(d,2,4,5,6,8,9,13,14,15,16,17,20,21,23)),
			xor(d30,bits(d,1,3,5,6,7,9,10,14,15,16,17,18,21,22,24)), xor(d29,bits(d,3,5,6,8,9,10,11,13,15,19,22,23,24)) };
	}
	private static int bits(int[] d, int... p) { int value = 0; for (int bit : p) value ^= d[bit - 1]; return value; }
	private static int xor(int a, int b) { return a ^ b; }
	private static void put(int[] bits, int start, int count, int value) {
		for (int bit = count - 1; bit >= 0; bit--) { bits[start + bit] = value & 1; value >>>= 1; }
	}
}
