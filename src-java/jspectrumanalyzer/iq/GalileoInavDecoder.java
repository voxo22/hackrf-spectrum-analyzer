package jspectrumanalyzer.iq;

import java.util.Arrays;

/**
 * Galileo I/NAV E1-B page-part synchronizer and soft-decision FEC decoder.
 * Each one-second part contains a 10-symbol clear sync pattern followed by a
 * 30 x 8 interleaved, rate-1/2 convolutionally encoded 120-bit block.
 */
final class GalileoInavDecoder {
	private static final String SYNC = "0101100000";
	private static final int SYNC_SYMBOLS = 10, CODED_SYMBOLS = 240, CLEAR_BITS = 120;
	private static final int G1 = 0171, G2 = 0133;
	private final double[] syncWindow = new double[SYNC_SYMBOLS];
	private final double[] coded = new double[CODED_SYMBOLS];
	private int syncCount, codedCount;
	private double polarity = 1d;
	private PagePart pendingEven;
	private Page latestPage = Page.waiting();
	private PagePart latestPart = PagePart.waiting();
	private long symbolsSeen;

	void reset() {
		syncCount = codedCount = 0; polarity = 1d; pendingEven = null;
		latestPage = Page.waiting(); latestPart = PagePart.waiting(); symbolsSeen = 0L;
	}

	void acceptSoft(double soft) {
		symbolsSeen++;
		if (codedCount > 0) {
			coded[codedCount - 1] = soft * polarity;
			codedCount++;
			if (codedCount <= CODED_SYMBOLS) return;
			latestPart = decodePart(coded);
			codedCount = 0; syncCount = 0;
			if (!latestPart.valid) return;
			if (!latestPart.odd) pendingEven = latestPart;
			else if (pendingEven != null && !pendingEven.pageTypeAlert
					&& !latestPart.pageTypeAlert) {
				latestPage = assemble(pendingEven, latestPart);
				pendingEven = null;
			}
			return;
		}

		if (syncCount < SYNC_SYMBOLS) syncWindow[syncCount++] = soft;
		else {
			System.arraycopy(syncWindow, 1, syncWindow, 0, SYNC_SYMBOLS - 1);
			syncWindow[SYNC_SYMBOLS - 1] = soft;
		}
		if (syncCount < SYNC_SYMBOLS) return;
		double correlation = 0d, energy = 0d;
		for (int index = 0; index < SYNC_SYMBOLS; index++) {
			double expected = SYNC.charAt(index) == '0' ? 1d : -1d;
			correlation += syncWindow[index] * expected;
			energy += Math.abs(syncWindow[index]);
		}
		if (energy > 0d && Math.abs(correlation) / energy >= .72d) {
			polarity = correlation >= 0d ? 1d : -1d;
			codedCount = 1;
		}
	}

	Page latestPage() { return latestPage; }
	PagePart latestPart() { return latestPart; }
	long symbolsSeen() { return symbolsSeen; }

	static PagePart decodePart(double[] interleaved) {
		if (interleaved == null || interleaved.length != CODED_SYMBOLS) return PagePart.waiting();
		double[] deinterleaved = new double[CODED_SYMBOLS];
		for (int row = 0; row < 8; row++)
			for (int column = 0; column < 30; column++)
				deinterleaved[column * 8 + row] = interleaved[row * 30 + column];
		int[] bits = viterbi(deinterleaved);
		if (bits == null) return PagePart.waiting();
		boolean tailValid = true;
		for (int index = 114; index < 120; index++) if (bits[index] != 0) tailValid = false;
		double quality = pathAgreement(bits, deinterleaved);
		return new PagePart(tailValid, bits[0] != 0, bits[1] != 0, bits, quality);
	}

	private static int[] viterbi(double[] symbols) {
		double[] metric = new double[64]; Arrays.fill(metric, Double.NEGATIVE_INFINITY); metric[0] = 0d;
		byte[][] previous = new byte[CLEAR_BITS][64], decisions = new byte[CLEAR_BITS][64];
		for (int time = 0; time < CLEAR_BITS; time++) {
			double[] next = new double[64]; Arrays.fill(next, Double.NEGATIVE_INFINITY);
			for (int state = 0; state < 64; state++) if (metric[state] > Double.NEGATIVE_INFINITY) {
				for (int bit = 0; bit <= 1; bit++) {
					int register = state | (bit << 6), target = register >>> 1;
					int first = Integer.bitCount(register & G1) & 1;
					int second = (Integer.bitCount(register & G2) & 1) ^ 1;
					double score = metric[state]
							+ (first == 0 ? symbols[time * 2] : -symbols[time * 2])
							+ (second == 0 ? symbols[time * 2 + 1] : -symbols[time * 2 + 1]);
					if (score > next[target]) {
						next[target] = score; previous[time][target] = (byte) state; decisions[time][target] = (byte) bit;
					}
				}
			}
			metric = next;
		}
		if (metric[0] == Double.NEGATIVE_INFINITY) return null;
		int[] result = new int[CLEAR_BITS]; int state = 0;
		for (int time = CLEAR_BITS - 1; time >= 0; time--) {
			result[time] = decisions[time][state]; state = previous[time][state] & 0xff;
		}
		return result;
	}

	private static double pathAgreement(int[] bits, double[] symbols) {
		int state = 0; double agreed = 0d, total = 0d;
		for (int time = 0; time < bits.length; time++) {
			int register = state | (bits[time] << 6); state = register >>> 1;
			int first = Integer.bitCount(register & G1) & 1;
			int second = (Integer.bitCount(register & G2) & 1) ^ 1;
			double a = symbols[time * 2], b = symbols[time * 2 + 1];
			agreed += (first == 0 ? a : -a) + (second == 0 ? b : -b);
			total += Math.abs(a) + Math.abs(b);
		}
		return total == 0d ? 0d : Math.max(0d, Math.min(1d, agreed / total));
	}

	private static Page assemble(PagePart even, PagePart odd) {
		int[] protectedBits = new int[196];
		System.arraycopy(even.bits, 0, protectedBits, 0, 114);
		System.arraycopy(odd.bits, 0, protectedBits, 114, 82);
		int expectedCrc = 0;
		for (int index = 82; index < 106; index++) expectedCrc = (expectedCrc << 1) | odd.bits[index];
		boolean crcValid = crc24(protectedBits) == expectedCrc;
		int[] word = new int[128];
		System.arraycopy(even.bits, 2, word, 0, 112);
		System.arraycopy(odd.bits, 2, word, 112, 16);
		int wordType = readUnsigned(word, 0, 6);
		return new Page(crcValid, wordType, word, even.quality, odd.quality);
	}

	static int crc24(int[] bits) {
		int[] work = new int[bits.length + 24];
		System.arraycopy(bits, 0, work, 0, bits.length);
		int polynomial = 0x1864cfb;
		for (int index = 0; index < bits.length; index++) if (work[index] != 0) {
			for (int bit = 0; bit <= 24; bit++) work[index + bit] ^= (polynomial >>> (24 - bit)) & 1;
		}
		int result = 0;
		for (int index = bits.length; index < work.length; index++) result = (result << 1) | work[index];
		return result;
	}

	private static int readUnsigned(int[] bits, int offset, int count) {
		int value = 0; for (int index = 0; index < count; index++) value = (value << 1) | bits[offset + index];
		return value;
	}

	static final class PagePart {
		final boolean valid, odd, pageTypeAlert; final int[] bits; final double quality;
		PagePart(boolean valid, boolean odd, boolean pageTypeAlert, int[] bits, double quality) {
			this.valid = valid; this.odd = odd; this.pageTypeAlert = pageTypeAlert;
			this.bits = bits == null ? new int[0] : bits.clone(); this.quality = quality;
		}
		static PagePart waiting() { return new PagePart(false, false, false, null, 0d); }
	}

	static final class Page {
		final boolean valid; final int wordType; final int[] word; final double evenQuality, oddQuality;
		Page(boolean valid, int wordType, int[] word, double evenQuality, double oddQuality) {
			this.valid = valid; this.wordType = wordType; this.word = word == null ? new int[0] : word.clone();
			this.evenQuality = evenQuality; this.oddQuality = oddQuality;
		}
		String healthText() {
			if (!valid || wordType != 5 || word.length < 73) return "--";
			int shs = readUnsigned(word, 69, 2), dvs = word[72];
			String health = shs == 0 ? "Signal OK" : shs == 1 ? "Out of service"
					: shs == 2 ? "EOM" : "Test";
			return health + (dvs == 0 ? " / data valid" : " / no guarantee");
		}
		static Page waiting() { return new Page(false, -1, null, 0d, 0d); }
	}
}
