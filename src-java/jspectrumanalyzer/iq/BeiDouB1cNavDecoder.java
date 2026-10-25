package jspectrumanalyzer.iq;

/** Soft-decision synchronizer for the BCH-protected 72-symbol B-CNAV1 Subframe 1. */
final class BeiDouB1cNavDecoder {
	private static final int FRAME_SYMBOLS = 1800, SF1_SYMBOLS = 72;
	/* The 1:3 data/pilot power split makes the 72-symbol SF1 noticeably noisier
	 * than the pilot acquisition.  The expected PRN plus the SOH codebook margin
	 * keeps this threshold selective; SF2 CRC remains the final acceptance gate. */
	private static final double REQUIRED_CORRELATION = .60d;
	private static final double[][] PRN_CODEWORDS = codebook(21, 6, new int[] { 2, 4, 5, 6 });
	private static final double[][] SOH_CODEWORDS = codebook(51, 8, new int[] { 1, 4, 5, 6, 7, 8 });
	private final int expectedPrn;
	private final double[] window = new double[SF1_SYMBOLS];
	private final double[] frameHistory = new double[FRAME_SYMBOLS];
	private final BeiDouB1cNavigationData navigation = new BeiDouB1cNavigationData();
	private int count;
	private long symbolsSeen, lastValidSymbol = Long.MIN_VALUE, pendingFrameStart = Long.MIN_VALUE;
	private Frame latest = Frame.waiting();

	BeiDouB1cNavDecoder(int expectedPrn) { this.expectedPrn = expectedPrn; }

	void reset() {
		count = 0; symbolsSeen = 0L; lastValidSymbol = Long.MIN_VALUE; pendingFrameStart = Long.MIN_VALUE;
		latest = Frame.waiting(); navigation.reset();
	}

	void acceptSoft(double soft) {
		symbolsSeen++;
		frameHistory[(int) ((symbolsSeen - 1L) % FRAME_SYMBOLS)] = soft;
		if (count < window.length) window[count++] = soft;
		else { System.arraycopy(window, 1, window, 0, window.length - 1); window[window.length - 1] = soft; }
		if (count >= window.length && (lastValidSymbol == Long.MIN_VALUE
				|| symbolsSeen - lastValidSymbol >= FRAME_SYMBOLS - SF1_SYMBOLS)) {
			Frame decoded = decode(window, expectedPrn, symbolsSeen - SF1_SYMBOLS + 1L);
			if (decoded.valid) {
				latest = decoded; lastValidSymbol = symbolsSeen; pendingFrameStart = decoded.frameStartSymbol;
			}
		}
		if (pendingFrameStart != Long.MIN_VALUE && symbolsSeen >= pendingFrameStart + FRAME_SYMBOLS - 1L) {
			double[] frame = new double[FRAME_SYMBOLS];
			for (int index = 0; index < FRAME_SYMBOLS; index++) {
				long symbol = pendingFrameStart + index;
				frame[index] = frameHistory[(int) ((symbol - 1L) % FRAME_SYMBOLS)];
			}
			navigation.accept(frame, expectedPrn, pendingFrameStart); pendingFrameStart = Long.MIN_VALUE;
		}
	}

	Frame latestFrame() { return latest; }
	BeiDouB1cNavigationData.Snapshot navigation() { return navigation.snapshot(symbolsSeen); }
	long symbolsSeen() { return symbolsSeen; }

	static Frame decode(double[] symbols, int expectedPrn, long frameStartSymbol) {
		if (symbols == null || symbols.length < SF1_SYMBOLS || expectedPrn < 1 || expectedPrn > 63)
			return Frame.waiting();
		double prnNormal = correlation(symbols, 0, PRN_CODEWORDS[expectedPrn]);
		double polarity = prnNormal >= 0d ? 1d : -1d;
		double prnQuality = Math.abs(prnNormal);
		if (prnQuality < REQUIRED_CORRELATION) return Frame.waiting();
		int bestSoh = -1; double best = -1d, second = -1d;
		for (int soh = 0; soh < 200; soh++) {
			double quality = polarity * correlation(symbols, 21, SOH_CODEWORDS[soh]);
			if (quality > best) { second = best; best = quality; bestSoh = soh; }
			else if (quality > second) second = quality;
		}
		if (best < REQUIRED_CORRELATION || best - second < .02d) return Frame.waiting();
		return new Frame(true, expectedPrn, bestSoh, bestSoh * 18, Math.min(prnQuality, best), frameStartSymbol);
	}

	static double[] encodeSubframe1(int prn, int soh) {
		if (prn < 1 || prn > 63 || soh < 0 || soh > 199) throw new IllegalArgumentException();
		double[] result = new double[SF1_SYMBOLS];
		System.arraycopy(PRN_CODEWORDS[prn], 0, result, 0, 21);
		System.arraycopy(SOH_CODEWORDS[soh], 0, result, 21, 51);
		return result;
	}

	private static double correlation(double[] symbols, int offset, double[] codeword) {
		double sum = 0d, energy = 0d;
		for (int index = 0; index < codeword.length; index++) {
			double value = symbols[offset + index]; sum += value * codeword[index]; energy += Math.abs(value);
		}
		return energy == 0d ? 0d : sum / energy;
	}

	private static double[][] codebook(int n, int k, int[] feedbackPositions) {
		double[][] result = new double[1 << k][n];
		for (int message = 0; message < result.length; message++) {
			double[] register = new double[k];
			for (int bit = 0; bit < k; bit++) register[k - 1 - bit] =
					((message >>> (k - 1 - bit)) & 1) == 0 ? 1d : -1d;
			for (int symbol = 0; symbol < n; symbol++) {
				result[message][symbol] = register[k - 1]; double feedback = 1d;
				for (int position : feedbackPositions) feedback *= register[position - 1];
				for (int stage = k - 1; stage > 0; stage--) register[stage] = register[stage - 1];
				register[0] = feedback;
			}
		}
		return result;
	}

	static final class Frame {
		final boolean valid; final int prn, soh, secondsOfHour; final double quality; final long frameStartSymbol;
		Frame(boolean valid, int prn, int soh, int secondsOfHour, double quality, long frameStartSymbol) {
			this.valid = valid; this.prn = prn; this.soh = soh; this.secondsOfHour = secondsOfHour;
			this.quality = quality; this.frameStartSymbol = frameStartSymbol;
		}
		static Frame waiting() { return new Frame(false, -1, -1, -1, 0d, -1L); }
	}
}
