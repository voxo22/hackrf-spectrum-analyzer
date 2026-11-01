package jspectrumanalyzer.iq;

/**
 * TETRA full-slot signalling-channel (SCH/F) lower-MAC decoder.
 *
	 * <p>The type-5 bits from the two data fields of an NDB1 are concatenated,
 * descrambled with the serving-cell code, block de-interleaved, rate-2/3
 * de-punctured and decoded with the TETRA 16-state rate-1/4 convolutional
 * code.  A successful CRC exposes the 268 type-1 bits consumed by MAC.</p>
 */
final class TetraSchfDecoder {
	private static final int BLOCK1_OFFSET = 14;
	private static final int BLOCK2_OFFSET = 282;
	private static final int HALF_BITS = 216;
	private static final int FULL_CODED_BITS = 432;
	private static final int FULL_INPUT_BITS = 288;
	private static final int FULL_PAYLOAD_BITS = 268;
	private static final int HALF_CODED_BITS = 216;
	private static final int HALF_INPUT_BITS = 144;
	private static final int HALF_PAYLOAD_BITS = 124;
	private static final int STATES = 16;
	private static final int[] PUNCTURE_KEEP = { 1, 1, 0, 0, 1, 0, 0, 0 };
	private static final int[] GENERATORS = { 0x13, 0x1d, 0x17, 0x1b };
	private static final int[] SCRAMBLE_TAPS =
			{ 32, 26, 23, 22, 16, 12, 11, 10, 8, 7, 5, 4, 2, 1 };

	private TetraSchfDecoder() { }

	static long scramblerInitial(TetraBschDecoder.SystemInfo info) {
		if (info == null) return 0L;
		long base = (info.colourCode & 0x3fL)
				| ((info.mnc & 0x3fffL) << 6)
				| ((info.mcc & 0x3ffL) << 20);
		return ((base << 2) | 3L) & 0xffffffffL;
	}

	static DecodeAttempt analyzeBurst(TetraDecoderCore.Burst burst,
			TetraBschDecoder.SystemInfo info) {
		if (burst == null || burst.type != TetraDecoderCore.BurstType.NDB1
				|| burst.bits == null || burst.bits.length < BLOCK2_OFFSET + HALF_BITS
				|| info == null) return null;
		DecodeAttempt best = null;
		/* A whole-symbol displacement can remain while the timing loop acquires.
		 * Search the same small neighbourhood used by the BSCH decoder. */
		for (int shift = -8; shift <= 8; shift += 2) {
			if (BLOCK1_OFFSET + shift < 0
					|| BLOCK2_OFFSET + shift + HALF_BITS > burst.bits.length) continue;
			float[] coded = new float[FULL_CODED_BITS];
			copyReliability(burst, BLOCK1_OFFSET + shift, coded, 0, HALF_BITS);
			copyReliability(burst, BLOCK2_OFFSET + shift, coded, HALF_BITS, HALF_BITS);
			DecodeAttempt attempt = analyzeBlock(coded, scramblerInitial(info), shift,
					103, FULL_INPUT_BITS, FULL_PAYLOAD_BITS);
			if (attempt.crcOk) return attempt;
			if (best == null || attempt.metric < best.metric) best = attempt;
		}
		return best;
	}

	/** Decode either 124-bit SCH/HD block carried by an NDB2 half-slot. */
	static DecodeAttempt analyzeHalfBurst(TetraDecoderCore.Burst burst,
			TetraBschDecoder.SystemInfo info, int half) {
		if (burst == null || burst.type != TetraDecoderCore.BurstType.NDB2
				|| burst.bits == null || burst.bits.length < BLOCK2_OFFSET + HALF_BITS
				|| info == null || half < 0 || half > 1) return null;
		int blockOffset = half == 0 ? BLOCK1_OFFSET : BLOCK2_OFFSET;
		DecodeAttempt best = null;
		for (int shift = -8; shift <= 8; shift += 2) {
			if (blockOffset + shift < 0
					|| blockOffset + shift + HALF_BITS > burst.bits.length) continue;
			float[] coded = new float[HALF_CODED_BITS];
			copyReliability(burst, blockOffset + shift, coded, 0, HALF_BITS);
			DecodeAttempt attempt = analyzeBlock(coded, scramblerInitial(info), shift,
					101, HALF_INPUT_BITS, HALF_PAYLOAD_BITS);
			if (attempt.crcOk) return attempt;
			if (best == null || attempt.metric < best.metric) best = attempt;
		}
		return best;
	}

	private static void copyReliability(TetraDecoderCore.Burst burst, int source,
			float[] destination, int offset, int length) {
		if (burst.bitReliability != null
				&& burst.bitReliability.length >= source + length) {
			System.arraycopy(burst.bitReliability, source, destination, offset, length);
			return;
		}
		for (int n = 0; n < length; n++)
			destination[offset + n] = burst.bits[source + n] == 0 ? 1f : -1f;
	}

	private static DecodeAttempt analyzeBlock(float[] coded, long scrambling,
			int alignmentShift, int interleaveMultiplier, int inputBits, int payloadBits) {
		float[] type4 = coded.clone();
		descramble(type4, scrambling);
		float[] type3 = new float[coded.length];
		for (int k = 1; k <= coded.length; k++) {
			int j = 1 + (interleaveMultiplier * k) % coded.length;
			type3[k - 1] = type4[j - 1];
		}

		float[] mother = new float[inputBits * 4];
		for (int n = 0; n < mother.length; n++) mother[n] = Float.NaN;
		int source = 0;
		for (int n = 0; n < mother.length; n++)
			if (PUNCTURE_KEEP[n & 7] != 0) mother[n] = type3[source++];

		ViterbiResult decoded = viterbi(mother, inputBits);
		int crc = crc16(decoded.bits, payloadBits + 16);
		int[] payload = new int[payloadBits];
		System.arraycopy(decoded.bits, 0, payload, 0, payloadBits);
		return new DecodeAttempt(crc == 0x1d0f, crc, decoded.metric,
				alignmentShift, payload);
	}

	private static void descramble(float[] reliability, long initial) {
		long lfsr = initial & 0xffffffffL;
		for (int n = 0; n < reliability.length; n++) {
			int feedback = 0;
			for (int tap : SCRAMBLE_TAPS)
				feedback ^= (int)((lfsr >>> (32 - tap)) & 1L);
			if (feedback != 0) reliability[n] = -reliability[n];
			lfsr = (lfsr >>> 1) | ((long)feedback << 31);
		}
	}

	private static ViterbiResult viterbi(float[] coded, int inputBits) {
		double[] metric = new double[STATES], nextMetric = new double[STATES];
		byte[][] traceback = new byte[inputBits][STATES];
		for (int state = 0; state < STATES; state++) metric[state] = Double.POSITIVE_INFINITY;
		metric[0] = 0d;
		for (int time = 0; time < inputBits; time++) {
			for (int state = 0; state < STATES; state++)
				nextMetric[state] = Double.POSITIVE_INFINITY;
			for (int previous = 0; previous < STATES; previous++) {
				if (Double.isInfinite(metric[previous])) continue;
				for (int bit = 0; bit <= 1; bit++) {
					int next = ((previous << 1) | bit) & 0x0f;
					double candidate = metric[previous]
							+ branchMetric(encoderOutput(previous, bit), coded, time * 4);
					if (candidate < nextMetric[next]) {
						nextMetric[next] = candidate;
						traceback[time][next] = (byte)previous;
					}
				}
			}
			double[] swap = metric; metric = nextMetric; nextMetric = swap;
		}
		/* The four tail bits terminate the encoder in state zero. */
		int state = 0;
		double bestMetric = metric[state];
		int[] decoded = new int[inputBits];
		for (int time = inputBits - 1; time >= 0; time--) {
			decoded[time] = state & 1;
			state = traceback[time][state] & 0xff;
		}
		return new ViterbiResult(decoded, bestMetric);
	}

	private static double branchMetric(int expected, float[] received, int offset) {
		double cost = 0d;
		for (int n = 0; n < 4; n++) {
			float llr = received[offset + n];
			if (Float.isNaN(llr)) continue;
			int bit = (expected >>> n) & 1;
			cost += bit == 0 ? Math.max(0d, -llr) : Math.max(0d, llr);
		}
		return cost;
	}

	private static int encoderOutput(int state, int bit) {
		int shiftRegister = (state << 1) | bit;
		int output = 0;
		for (int n = 0; n < GENERATORS.length; n++)
			output |= (Integer.bitCount(shiftRegister & GENERATORS[n]) & 1) << n;
		return output;
	}

	private static int crc16(int[] bits, int length) {
		int crc = 0xffff;
		for (int n = 0; n < length; n++) {
			int feedback = bits[n] ^ ((crc >>> 15) & 1);
			crc = (crc << 1) & 0xffff;
			if (feedback != 0) crc ^= 0x1021;
		}
		return crc;
	}

	static final class DecodeAttempt {
		final boolean crcOk;
		final int crc;
		final double metric;
		final int alignmentShift;
		final int[] payloadBits;

		DecodeAttempt(boolean crcOk, int crc, double metric, int alignmentShift,
				int[] payloadBits) {
			this.crcOk = crcOk;
			this.crc = crc;
			this.metric = metric;
			this.alignmentShift = alignmentShift;
			this.payloadBits = payloadBits;
		}

		String payloadHex() {
			StringBuilder text = new StringBuilder((payloadBits.length + 3) / 4);
			for (int offset = 0; offset < payloadBits.length; offset += 4) {
				int value = 0;
				for (int n = 0; n < 4; n++)
					value = (value << 1) | (offset + n < payloadBits.length
							? payloadBits[offset + n] : 0);
				text.append(Character.toUpperCase(Character.forDigit(value, 16)));
			}
			return text.toString();
		}
	}

	private static final class ViterbiResult {
		final int[] bits;
		final double metric;
		ViterbiResult(int[] bits, double metric) { this.bits = bits; this.metric = metric; }
	}
}
