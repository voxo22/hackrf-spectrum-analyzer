package jspectrumanalyzer.iq;

/**
 * Minimal TETRA BSCH lower-MAC decoder.
 *
 * <p>The coding chain follows ETSI EN 300 392-2 and the GPL-3.0-or-later
 * reference implementation in alphafox02/tetra-rtlsdr (tetra_decode.c):
 * descrambling, K=120 block de-interleaving, rate-2/3 de-puncturing,
 * 16-state rate-1/4 Viterbi decoding and CRC-16 verification.</p>
 */
final class TetraBschDecoder {
	private static final int STS_BIT_OFFSET = 214;
	private static final int BSCH_BITS = 120;
	private static final int DEPUNCTURED_BITS = 320;
	private static final int INPUT_BITS = 80;
	private static final int STATES = 16;
	private static final int ERASURE = -1;
	private static final int[] PUNCTURE_KEEP = { 1, 1, 0, 0, 1, 0, 0, 0 };
	private static final int[] GENERATORS = { 0x13, 0x1d, 0x17, 0x1b };

	private TetraBschDecoder() { }

	static SystemInfo decodeBurst(byte[] type5Bits) {
		DecodeAttempt attempt = analyzeBurst(type5Bits);
		return attempt == null ? null : attempt.systemInfo;
	}

	static DecodeAttempt analyzeBurst(byte[] type5Bits) {
		if (type5Bits == null || type5Bits.length < STS_BIT_OFFSET)
			return null;
		byte[] bsch = new byte[BSCH_BITS];
		System.arraycopy(type5Bits, STS_BIT_OFFSET - BSCH_BITS, bsch, 0, BSCH_BITS);
		return analyzeBlock(bsch);
	}

	static DecodeAttempt analyzeBurst(TetraDecoderCore.Burst burst) {
		if (burst == null || burst.bits == null || burst.bits.length < STS_BIT_OFFSET)
			return null;
		DecodeAttempt best = null;
		int nominal = STS_BIT_OFFSET - BSCH_BITS;
		/* Also inspect neighbouring dibit alignments. This is useful while the
		 * front-end timing loop is acquiring and costs very little for the rare
		 * synchronization burst. */
		for (int shift = -8; shift <= 8; shift += 2) {
			int offset = nominal + shift;
			if (offset < 0 || offset + BSCH_BITS > burst.bits.length) continue;
			byte[] bits = new byte[BSCH_BITS];
			float[] reliability = new float[BSCH_BITS];
			System.arraycopy(burst.bits, offset, bits, 0, BSCH_BITS);
			System.arraycopy(burst.bitReliability, offset, reliability, 0, BSCH_BITS);
			DecodeAttempt attempt = analyzeBlockSoft(bits, reliability);
			if (attempt.systemInfo != null) return attempt;
			if (best == null || attempt.metric < best.metric) best = attempt;
		}
		return best;
	}

	static SystemInfo decodeBlock(byte[] codedBits) {
		DecodeAttempt attempt = analyzeBlock(codedBits);
		return attempt == null ? null : attempt.systemInfo;
	}

	private static DecodeAttempt analyzeBlock(byte[] codedBits) {
		if (codedBits == null || codedBits.length < BSCH_BITS) return null;
		int[] work = new int[BSCH_BITS];
		for (int n = 0; n < work.length; n++) work[n] = codedBits[n] & 1;
		descramble(work, 3L);

		int[] deinterleaved = new int[BSCH_BITS];
		for (int k = 1; k <= BSCH_BITS; k++) {
			int j = 1 + (11 * k) % BSCH_BITS;
			deinterleaved[k - 1] = work[j - 1];
		}

		int[] depunctured = new int[DEPUNCTURED_BITS];
		int source = 0;
		for (int n = 0; n < depunctured.length; n++)
			depunctured[n] = PUNCTURE_KEEP[n & 7] != 0
					? deinterleaved[source++] : ERASURE;

		ViterbiResult decoded = viterbi(depunctured);
		int crc = crc16(decoded.bits, 76);
		if (crc != 0x1d0f) return new DecodeAttempt(null, crc, decoded.metric);
		SystemInfo info = new SystemInfo(bitsToInt(decoded.bits, 4, 6),
				bitsToInt(decoded.bits, 10, 2) + 1,
				bitsToInt(decoded.bits, 12, 5), bitsToInt(decoded.bits, 17, 6),
				bitsToInt(decoded.bits, 31, 10), bitsToInt(decoded.bits, 41, 14),
				decoded.metric);
		return new DecodeAttempt(info, crc, decoded.metric);
	}

	private static DecodeAttempt analyzeBlockSoft(byte[] codedBits, float[] reliability) {
		if (codedBits == null || reliability == null || codedBits.length < BSCH_BITS
				|| reliability.length < BSCH_BITS) return null;
		float[] work = new float[BSCH_BITS];
		System.arraycopy(reliability, 0, work, 0, BSCH_BITS);
		descrambleSoft(work, 3L);
		float[] deinterleaved = new float[BSCH_BITS];
		for (int k = 1; k <= BSCH_BITS; k++) {
			int j = 1 + (11 * k) % BSCH_BITS;
			deinterleaved[k - 1] = work[j - 1];
		}
		float[] depunctured = new float[DEPUNCTURED_BITS];
		int source = 0;
		for (int n = 0; n < depunctured.length; n++)
			depunctured[n] = PUNCTURE_KEEP[n & 7] != 0
					? deinterleaved[source++] : Float.NaN;
		SoftViterbiResult decoded = viterbiSoft(depunctured);
		int crc = crc16(decoded.bits, 76);
		int metric = (int)Math.round(decoded.metric * 100d);
		if (crc != 0x1d0f) return new DecodeAttempt(null, crc, metric);
		SystemInfo info = new SystemInfo(bitsToInt(decoded.bits, 4, 6),
				bitsToInt(decoded.bits, 10, 2) + 1,
				bitsToInt(decoded.bits, 12, 5), bitsToInt(decoded.bits, 17, 6),
				bitsToInt(decoded.bits, 31, 10), bitsToInt(decoded.bits, 41, 14), metric);
		return new DecodeAttempt(info, crc, metric);
	}

	private static void descrambleSoft(float[] reliability, long initial) {
		long lfsr = initial & 0xffffffffL;
		int[] taps = { 32, 26, 23, 22, 16, 12, 11, 10, 8, 7, 5, 4, 2, 1 };
		for (int n = 0; n < reliability.length; n++) {
			int feedback = 0;
			for (int tap : taps) feedback ^= (int)((lfsr >>> (32 - tap)) & 1L);
			if (feedback != 0) reliability[n] = -reliability[n];
			lfsr = (lfsr >>> 1) | ((long)feedback << 31);
		}
	}

	private static void descramble(int[] bits, long initial) {
		long lfsr = initial & 0xffffffffL;
		int[] taps = { 32, 26, 23, 22, 16, 12, 11, 10, 8, 7, 5, 4, 2, 1 };
		for (int n = 0; n < bits.length; n++) {
			int feedback = 0;
			for (int tap : taps) feedback ^= (int)((lfsr >>> (32 - tap)) & 1L);
			bits[n] ^= feedback;
			lfsr = (lfsr >>> 1) | ((long)feedback << 31);
		}
	}

	private static ViterbiResult viterbi(int[] coded) {
		final int unreachable = 1_000_000;
		int[] metric = new int[STATES], nextMetric = new int[STATES];
		byte[][] traceback = new byte[INPUT_BITS][STATES];
		for (int state = 0; state < STATES; state++) metric[state] = unreachable;
		metric[0] = 0;
		for (int time = 0; time < INPUT_BITS; time++) {
			for (int state = 0; state < STATES; state++) nextMetric[state] = unreachable;
			for (int previous = 0; previous < STATES; previous++) {
				if (metric[previous] >= unreachable) continue;
				for (int bit = 0; bit <= 1; bit++) {
					int next = ((previous << 1) | bit) & 0x0f;
					int expected = encoderOutput(previous, bit);
					int candidate = metric[previous]
							+ branchMetric(expected, coded, time * 4);
					if (candidate < nextMetric[next]) {
						nextMetric[next] = candidate;
						traceback[time][next] = (byte)previous;
					}
				}
			}
			int[] swap = metric; metric = nextMetric; nextMetric = swap;
		}
		int state = 0;
		for (int candidate = 1; candidate < STATES; candidate++)
			if (metric[candidate] < metric[state]) state = candidate;
		int bestMetric = metric[state];
		int[] decoded = new int[INPUT_BITS];
		for (int time = INPUT_BITS - 1; time >= 0; time--) {
			decoded[time] = state & 1;
			state = traceback[time][state] & 0xff;
		}
		return new ViterbiResult(decoded, bestMetric);
	}

	private static SoftViterbiResult viterbiSoft(float[] coded) {
		final double unreachable = Double.POSITIVE_INFINITY;
		double[] metric = new double[STATES], nextMetric = new double[STATES];
		byte[][] traceback = new byte[INPUT_BITS][STATES];
		for (int state = 0; state < STATES; state++) metric[state] = unreachable;
		metric[0] = 0d;
		for (int time = 0; time < INPUT_BITS; time++) {
			for (int state = 0; state < STATES; state++) nextMetric[state] = unreachable;
			for (int previous = 0; previous < STATES; previous++) {
				if (Double.isInfinite(metric[previous])) continue;
				for (int bit = 0; bit <= 1; bit++) {
					int next = ((previous << 1) | bit) & 0x0f;
					int expected = encoderOutput(previous, bit);
					double candidate = metric[previous]
							+ softBranchMetric(expected, coded, time * 4);
					if (candidate < nextMetric[next]) {
						nextMetric[next] = candidate;
						traceback[time][next] = (byte)previous;
					}
				}
			}
			double[] swap = metric; metric = nextMetric; nextMetric = swap;
		}
		int state = 0;
		for (int candidate = 1; candidate < STATES; candidate++)
			if (metric[candidate] < metric[state]) state = candidate;
		double bestMetric = metric[state];
		int[] decoded = new int[INPUT_BITS];
		for (int time = INPUT_BITS - 1; time >= 0; time--) {
			decoded[time] = state & 1;
			state = traceback[time][state] & 0xff;
		}
		return new SoftViterbiResult(decoded, bestMetric);
	}

	private static double softBranchMetric(int expected, float[] received, int offset) {
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

	private static int branchMetric(int expected, int[] received, int offset) {
		int distance = 0;
		for (int n = 0; n < 4; n++)
			if (received[offset + n] != ERASURE
					&& received[offset + n] != ((expected >>> n) & 1)) distance++;
		return distance;
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

	private static int bitsToInt(int[] bits, int start, int length) {
		int value = 0;
		for (int n = 0; n < length; n++) value = (value << 1) | bits[start + n];
		return value;
	}

	static final class SystemInfo {
		final int colourCode, timeslot, frame, multiframe, mcc, mnc, metric;

		SystemInfo(int colourCode, int timeslot, int frame, int multiframe,
				int mcc, int mnc, int metric) {
			this.colourCode = colourCode;
			this.timeslot = timeslot;
			this.frame = frame;
			this.multiframe = multiframe;
			this.mcc = mcc;
			this.mnc = mnc;
			this.metric = metric;
		}
	}

	static final class DecodeAttempt {
		final SystemInfo systemInfo;
		final int crc, metric;
		DecodeAttempt(SystemInfo systemInfo, int crc, int metric) {
			this.systemInfo = systemInfo;
			this.crc = crc;
			this.metric = metric;
		}
	}

	private static final class ViterbiResult {
		final int[] bits;
		final int metric;
		ViterbiResult(int[] bits, int metric) { this.bits = bits; this.metric = metric; }
	}

	private static final class SoftViterbiResult {
		final int[] bits;
		final double metric;
		SoftViterbiResult(int[] bits, double metric) { this.bits = bits; this.metric = metric; }
	}
}
