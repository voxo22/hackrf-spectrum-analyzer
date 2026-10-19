package jspectrumanalyzer.iq;

/**
 * Validates the 50 bit/s GPS L1 C/A LNAV words after a receiver has recovered
 * a continuous, bit-synchronous stream.  This class intentionally does not
 * attempt bit timing or carrier tracking: accepting discontinuous analysis
 * windows here would make a preamble look credible when it is not.
 */
final class GpsL1LnavDecoder {
	static final int BITS_PER_WORD = 30;
	static final int WORDS_PER_SUBFRAME = 10;
	static final int BITS_PER_SUBFRAME = BITS_PER_WORD * WORDS_PER_SUBFRAME;
	private static final int PREAMBLE = 0x8b;
	private final int[] history = new int[BITS_PER_SUBFRAME];
	private int historyLength;
	private int historyStart;
	private Result latest = Result.waiting();

	/** Adds one hard-sliced, transmitted LNAV bit, in time order. */
	void acceptBit(int bit) {
		int normalized = bit & 1;
		if (historyLength < history.length) {
			history[(historyStart + historyLength) % history.length] = normalized;
			historyLength++;
		} else {
			history[historyStart] = normalized;
			historyStart = (historyStart + 1) % history.length;
		}
		if (historyLength == history.length) {
			/* A valid TLM preamble is either direct or complemented by the
			 * previous word's D30 bit (and carrier ambiguity). Avoid allocating
			 * and parity-checking 300 bits at every other bit position. */
			int header = 0;
			for (int bitIndex = 0; bitIndex < 8; bitIndex++)
				header = (header << 1) | history[(historyStart + bitIndex) % history.length];
			if (header != PREAMBLE && header != (PREAMBLE ^ 0xff)) return;
			int[] candidate = orderedHistory();
			Result decoded = decodeSubframe(candidate);
			if (decoded.valid) latest = decoded;
		}
	}

	void reset() { historyLength = 0; historyStart = 0; latest = Result.waiting(); }
	Result result() { return latest; }

	static Result decodeSubframe(int[] bits) {
		if (bits == null || bits.length != BITS_PER_SUBFRAME) return Result.invalid();
		Result direct = decodeSubframePolarity(bits);
		if (direct.valid) return direct;
		/* A Costas loop recovers BPSK with an unavoidable 180 degree ambiguity.
		 * Test the opposite hard-slice polarity as well; parity decides which one
		 * is the actual transmitted LNAV bit stream. */
		int[] inverted = new int[bits.length];
		for (int bit = 0; bit < bits.length; bit++) inverted[bit] = bits[bit] ^ 1;
		return decodeSubframePolarity(inverted);
	}

	private static Result decodeSubframePolarity(int[] bits) {
		for (int previous = 0; previous < 4; previous++) {
			int previousD29 = previous >>> 1;
			int previousD30 = previous & 1;
			int[][] dataWords = new int[WORDS_PER_SUBFRAME][24];
			boolean valid = true;
			for (int word = 0; word < WORDS_PER_SUBFRAME && valid; word++) {
				int offset = word * BITS_PER_WORD;
				for (int dataBit = 0; dataBit < 24; dataBit++)
					dataWords[word][dataBit] = (bits[offset + dataBit] & 1) ^ previousD30;
				int[] parity = parity(dataWords[word], previousD29, previousD30);
				for (int parityBit = 0; parityBit < 6; parityBit++)
					if (parity[parityBit] != (bits[offset + 24 + parityBit] & 1)) { valid = false; break; }
				previousD29 = bits[offset + 28] & 1;
				previousD30 = bits[offset + 29] & 1;
			}
			if (!valid || value(dataWords[0], 0, 8) != PREAMBLE) continue;
			int subframeId = value(dataWords[1], 19, 3);
			if (subframeId < 1 || subframeId > 5) continue;
			int zCount = value(dataWords[1], 0, 17);
			if (zCount >= 100800) continue; // 6-second units within one GPS week
			return new Result(true, subframeId, zCount * 6, dataWords);
		}
		return Result.invalid();
	}

	/* GPS IS-GPS-200 LNAV parity, D25 through D30.  Data is indexed D1..D24. */
	private static int[] parity(int[] d, int d29Star, int d30Star) {
		return new int[] {
			xor(d29Star, bits(d, 1,2,3,5,6,10,11,12,13,14,17,18,20,23)),
			xor(d30Star, bits(d, 2,3,4,6,7,11,12,13,14,15,18,19,21,24)),
			xor(d29Star, bits(d, 1,3,4,5,7,8,12,13,14,15,16,19,20,22)),
			xor(d30Star, bits(d, 2,4,5,6,8,9,13,14,15,16,17,20,21,23)),
			xor(d30Star, bits(d, 1,3,5,6,7,9,10,14,15,16,17,18,21,22,24)),
			xor(d29Star, bits(d, 3,5,6,8,9,10,11,13,15,19,22,23,24)) };
	}

	private static int bits(int[] data, int... positions) { int result = 0; for (int position : positions) result ^= data[position - 1]; return result; }
	private static int xor(int first, int second) { return first ^ second; }
	private static int value(int[] bits, int start, int count) {
		int result = 0; for (int bit = 0; bit < count; bit++) result = (result << 1) | bits[start + bit]; return result;
	}
	private int[] orderedHistory() {
		int[] ordered = new int[history.length];
		for (int bit = 0; bit < ordered.length; bit++) ordered[bit] = history[(historyStart + bit) % history.length];
		return ordered;
	}

	static final class Result {
		final boolean valid;
		final int subframeId;
		final int timeOfWeekSeconds;
		final int[][] dataWords;
		private Result(boolean valid, int subframeId, int timeOfWeekSeconds, int[][] dataWords) {
			this.valid = valid; this.subframeId = subframeId; this.timeOfWeekSeconds = timeOfWeekSeconds; this.dataWords = dataWords;
		}
		static Result waiting() { return new Result(false, 0, -1, null); }
		static Result invalid() { return new Result(false, 0, -1, null); }
	}
}
