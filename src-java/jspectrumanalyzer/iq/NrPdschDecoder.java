package jspectrumanalyzer.iq;

/** PDSCH demodulation for the SI-RNTI grant carried by Type0 common search space. */
final class NrPdschDecoder {
	private static final int FFT = 1024;
	private static final int SYMBOL_STEP = 1096;
	private static final int[] DMRS_SYMBOLS = { 2, 7, 11 };
	private static final int[] MODULATION_ORDER = {
		2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4,
		6, 6, 6, 6, 6, 6, 6, 6, 2
	};
	private static final int[] CODE_RATE_X1024 = {
		120, 157, 193, 251, 308, 379, 449, 526, 602, 679, 340, 378, 434, 490, 553,
		616, 658, 438, 466, 517, 567, 616, 666, 719, 772, 822, 873, 910, 0
	};
	private static final int[] SMALL_TBS = {
		24, 32, 40, 48, 56, 64, 72, 80, 88, 96, 104, 112, 120, 128, 136, 144, 152,
		160, 168, 176, 184, 192, 208, 224, 240, 256, 272, 288, 304, 320, 336, 352,
		368, 384, 408, 432, 456, 480, 504, 528, 552, 576, 608, 640, 672, 704, 736,
		768, 808, 848, 888, 928, 984, 1032, 1064, 1128, 1160, 1192, 1224, 1256,
		1288, 1320, 1352, 1416, 1480, 1544, 1608, 1672, 1736, 1800, 1864, 1928,
		2024, 2088, 2152, 2216, 2280, 2408, 2472, 2536, 2600, 2664, 2728, 2792,
		2856, 2976, 3104, 3240, 3368, 3496, 3624, 3752, 3824
	};

	private NrPdschDecoder() {}

	static Decoded decode(double[] inI, double[] inQ, NrSignalAnalyzer.PssSurveyCandidate candidate,
			NrSignalAnalyzer.MibData mib, NrPdcchDecoder.Survey pdcch) {
		NrPdcchDecoder.Dci dci = pdcch.dci;
		if (!dci.valid || dci.timeAllocation != 11 || mib.dmrsTypeAPosition != 2
				|| dci.mcs < 0 || dci.mcs >= MODULATION_ORDER.length) return Decoded.EMPTY;
		int qm = MODULATION_ORDER[dci.mcs];
		int rate = CODE_RATE_X1024[dci.mcs];
		if (qm != 2 || rate == 0 || dci.rbStart < 0 || dci.rbLength <= 0) return Decoded.EMPTY;

		double omega = candidate.cfoHz * 2d * Math.PI / 15_360_000d;
		double[][][] symbols = new double[14][][];
		for (int l = 1; l < 14; l++) {
			int useful = pdcch.usefulSample + symbolUsefulOffset(l);
			if (useful < 0 || useful + FFT > inI.length) return Decoded.EMPTY;
			symbols[l] = NrSignalAnalyzer.surveySymbol(inI, inQ, useful, candidate.sample, omega,
					candidate.iqOrientation);
		}

		double[][][][] channel = new double[DMRS_SYMBOLS.length][dci.rbLength][2][12];
		double coherenceSum = 0;
		int coherenceCount = 0;
		for (int ds = 0; ds < DMRS_SYMBOLS.length; ds++) {
			int l = DMRS_SYMBOLS[ds];
			double[][] reference = dmrs(candidate.pci, pdcch.slot, l, dci.rbStart + dci.rbLength);
			for (int rb = 0; rb < dci.rbLength; rb++) {
				double[] pilotR = new double[6], pilotI = new double[6];
				for (int p = 0; p < 6; p++) {
					int m = (dci.rbStart + rb) * 6 + p;
					int bin = Math.floorMod(pdcch.coresetBin0 + (dci.rbStart + rb) * 12 + 2 * p, FFT);
					double yr = symbols[l][0][bin], yi = symbols[l][1][bin];
					double rr = reference[0][m], ri = reference[1][m];
					pilotR[p] = yr * rr + yi * ri;
					pilotI[p] = yi * rr - yr * ri;
					if (p > 0) {
						double a = Math.hypot(pilotR[p - 1], pilotI[p - 1]);
						double b = Math.hypot(pilotR[p], pilotI[p]);
						if (a > 1e-12 && b > 1e-12) {
							coherenceSum += (pilotR[p - 1] * pilotR[p] + pilotI[p - 1] * pilotI[p]) / (a * b);
							coherenceCount++;
						}
					}
				}
				for (int k = 0; k < 12; k++) {
					double position = k / 2d;
					int left = Math.max(0, Math.min(5, (int)Math.floor(position)));
					int right = Math.max(0, Math.min(5, left + 1));
					double fraction = Math.max(0, Math.min(1, position - left));
					channel[ds][rb][0][k] = pilotR[left] * (1 - fraction) + pilotR[right] * fraction;
					channel[ds][rb][1][k] = pilotI[left] * (1 - fraction) + pilotI[right] * fraction;
				}
			}
		}

		int dataRe = dci.rbLength * (13 * 12 - DMRS_SYMBOLS.length * 12);
		double[] llr = new double[dataRe * qm];
		double[] equalizedR = new double[dataRe], equalizedI = new double[dataRe];
		int[] equalizedSymbol = new int[dataRe];
		double error = 0, signal = 0;
		int at = 0;
		for (int l = 1; l < 14; l++) for (int rb = 0; rb < dci.rbLength; rb++) {
			int[] time = timeInterpolation(l);
			for (int k = 0; k < 12; k++) {
				if (isDmrsSymbol(l)) continue;
				double fraction = time[2] / 1000d;
				double hr = (channel[time[0]][rb][0][k] * (1 - fraction)
						+ channel[time[1]][rb][0][k] * fraction) / Math.sqrt(2d);
				double hi = (channel[time[0]][rb][1][k] * (1 - fraction)
						+ channel[time[1]][rb][1][k] * fraction) / Math.sqrt(2d);
				double power = hr * hr + hi * hi;
				int bin = Math.floorMod(pdcch.coresetBin0 + (dci.rbStart + rb) * 12 + k, FFT);
				double yr = symbols[l][0][bin], yi = symbols[l][1][bin];
				double matchedR = yr * hr + yi * hi, matchedI = yi * hr - yr * hi;
				double xr = power < 1e-18 ? 0 : matchedR / power;
				double xi = power < 1e-18 ? 0 : matchedI / power;
				double tr = Math.copySign(1d / Math.sqrt(2d), xr);
				double ti = Math.copySign(1d / Math.sqrt(2d), xi);
				error += square(xr - tr) + square(xi - ti);
				signal++;
				equalizedR[at] = matchedR;
				equalizedI[at] = matchedI;
				equalizedSymbol[at++] = l;
			}
		}
		if (2 * at != llr.length) return Decoded.EMPTY;
		double fourthCoherent = 0, fourthPower = 0;
		for (int l = 1; l < 14; l++) {
			double fourthR = 0, fourthI = 0;
			for (int i = 0; i < at; i++) if (equalizedSymbol[i] == l) {
				double r = equalizedR[i], q = equalizedI[i];
				double squareR = r * r - q * q, squareI = 2 * r * q;
				fourthR += squareR * squareR - squareI * squareI;
				fourthI += 2 * squareR * squareI;
				fourthPower += squareR * squareR + squareI * squareI;
			}
			fourthCoherent += Math.hypot(fourthR, fourthI);
			double phase = 0;
			double cosine = Math.cos(phase), sine = Math.sin(phase);
			for (int i = 0; i < at; i++) if (equalizedSymbol[i] == l) {
				double xr = equalizedR[i] * cosine + equalizedI[i] * sine;
				double xi = equalizedI[i] * cosine - equalizedR[i] * sine;
				llr[2 * i] = xr;
				llr[2 * i + 1] = xi;
			}
		}
		int tbs = transportBlockSize(dci.rbLength, 13, DMRS_SYMBOLS.length, qm, rate);
		DecodeAttempt best = decodeRateMatched(llr, candidate.pci, tbs, dci.rv, dci.qpskSwap, dci.qpskSign);
		DecodeAttempt phaseAligned = decodeSymbolRotations(llr, equalizedSymbol, candidate.pci, tbs, dci.rv);
		if (phaseAligned.result.crcOk || phaseAligned.result.repetitionAgreement
				> best.result.repetitionAgreement) best = phaseAligned;
		double coherence = coherenceCount == 0 ? 0 : coherenceSum / coherenceCount;
		double evm = signal == 0 ? 0 : Math.sqrt(error / signal);
		double qpskCoherence = fourthPower == 0 ? 0 : fourthCoherent / fourthPower;
		return new Decoded(true, coherence, evm, qpskCoherence, best.llrs.length / qm, best.llrs.length,
				tbs, best.result, best.llrs);
	}

	private static DecodeAttempt decodeSymbolRotations(double[] llr, int[] symbols, int pci, int tbs, int rv) {
		int[] rotations = new int[14];
		DecodeAttempt best = rotatedAttempt(llr, symbols, rotations, pci, tbs, rv);
		for (int pass = 0; pass < 3 && !best.result.crcOk; pass++) {
			boolean changed = false;
			for (int symbol = 1; symbol < 14 && !best.result.crcOk; symbol++) {
				if (isDmrsSymbol(symbol)) continue;
				int previous = rotations[symbol], selected = previous;
				DecodeAttempt symbolBest = best;
				for (int rotation = 0; rotation < 4; rotation++) {
					rotations[symbol] = rotation;
					DecodeAttempt trial = rotatedAttempt(llr, symbols, rotations, pci, tbs, rv);
					if (trial.result.crcOk || trial.result.repetitionAgreement
							> symbolBest.result.repetitionAgreement) {
						symbolBest = trial;
						selected = rotation;
					}
				}
				rotations[symbol] = selected;
				changed |= selected != previous;
				best = symbolBest;
			}
			if (!changed) break;
		}
		return best;
	}

	private static DecodeAttempt rotatedAttempt(double[] llr, int[] symbols, int[] rotations,
			int pci, int tbs, int rv) {
		double[] ordered = new double[llr.length];
		for (int i = 0; i < symbols.length; i++) {
			double real = llr[2 * i], imag = llr[2 * i + 1];
			switch (rotations[symbols[i]]) {
			case 1: ordered[2 * i] = imag; ordered[2 * i + 1] = -real; break;
			case 2: ordered[2 * i] = -real; ordered[2 * i + 1] = -imag; break;
			case 3: ordered[2 * i] = -imag; ordered[2 * i + 1] = real; break;
			default: ordered[2 * i] = real; ordered[2 * i + 1] = imag;
			}
		}
		int[] scramble = gold((0xffff << 15) + pci, ordered.length);
		for (int i = 0; i < ordered.length; i++) ordered[i] *= 1 - 2 * scramble[i];
		return new DecodeAttempt(NrLdpcDecoder.decode(ordered, tbs, rv), ordered);
	}

	private static DecodeAttempt decodeRateMatched(double[] llr, int pci, int tbs, int rv, int swap, int sign) {
		int[] scramble = gold((0xffff << 15) + pci, llr.length);
		double[] ordered = new double[llr.length];
		for (int i = 0; i < llr.length / 2; i++) {
			ordered[2 * i] = sign * llr[2 * i + (swap == 0 ? 0 : 1)];
			ordered[2 * i + 1] = sign * llr[2 * i + (swap == 0 ? 1 : 0)];
		}
		for (int i = 0; i < ordered.length; i++) ordered[i] *= 1 - 2 * scramble[i];
		return new DecodeAttempt(NrLdpcDecoder.decode(ordered, tbs, rv), ordered);
	}

	private static final class DecodeAttempt {
		final NrLdpcDecoder.Result result;
		final double[] llrs;
		DecodeAttempt(NrLdpcDecoder.Result result, double[] llrs) {
			this.result = result;
			this.llrs = llrs;
		}
	}

	private static int symbolUsefulOffset(int symbol) {
		// At 15.36 MS/s the regular CP is 72 samples and symbol 7 has an 80-sample CP.
		return symbol * SYMBOL_STEP + (symbol >= 7 ? 8 : 0);
	}

	private static int[] timeInterpolation(int symbol) {
		if (symbol <= DMRS_SYMBOLS[0]) return new int[] { 0, 0, 0 };
		if (symbol >= DMRS_SYMBOLS[2]) return new int[] { 2, 2, 0 };
		int left = symbol < DMRS_SYMBOLS[1] ? 0 : 1;
		int right = left + 1;
		int fraction = 1000 * (symbol - DMRS_SYMBOLS[left])
				/ (DMRS_SYMBOLS[right] - DMRS_SYMBOLS[left]);
		return new int[] { left, right, fraction };
	}

	private static boolean isDmrsSymbol(int symbol) {
		for (int value : DMRS_SYMBOLS) if (value == symbol) return true;
		return false;
	}

	private static int transportBlockSize(int rb, int symbols, int dmrsSymbols, int qm, int rate) {
		int rePerRb = Math.min(156, 12 * symbols - 12 * dmrsSymbols);
		double nInfo = rePerRb * rb * qm * rate / 1024d;
		if (nInfo <= 3824) {
			int n = Math.max(3, (int)Math.floor(Math.log(nInfo) / Math.log(2)) - 6);
			int step = 1 << n;
			int quantized = Math.max(24, step * (int)Math.floor(nInfo / step));
			for (int tbs : SMALL_TBS) if (quantized <= tbs) return tbs;
			return SMALL_TBS[SMALL_TBS.length - 1];
		}
		int n = (int)Math.floor(Math.log(nInfo - 24) / Math.log(2)) - 5;
		int step = 1 << n;
		int quantized = Math.max(3840, step * (int)Math.round((nInfo - 24) / step));
		if (rate <= 256) {
			int c = (int)Math.ceil((quantized + 24) / 3816d);
			return 8 * c * (int)Math.ceil((quantized + 24) / (8d * c)) - 24;
		}
		if (quantized > 8424) {
			int c = (int)Math.ceil((quantized + 24) / 8424d);
			return 8 * c * (int)Math.ceil((quantized + 24) / (8d * c)) - 24;
		}
		return 8 * (int)Math.ceil((quantized + 24) / 8d) - 24;
	}

	private static double[][] dmrs(int pci, int slot, int symbol, int rbEnd) {
		long init = ((1L << 17) * (14L * slot + symbol + 1L) * (2L * pci + 1L)
				+ 2L * pci) & 0x7fffffffL;
		int[] bits = gold((int)init, rbEnd * 12);
		double[][] result = new double[2][rbEnd * 6];
		for (int m = 0; m < result[0].length; m++) {
			result[0][m] = (1 - 2 * bits[2 * m]) / Math.sqrt(2d);
			result[1][m] = (1 - 2 * bits[2 * m + 1]) / Math.sqrt(2d);
		}
		return result;
	}

	private static int[] gold(int init, int length) {
		int total = 1600 + length + 31;
		int[] x1 = new int[total], x2 = new int[total];
		x1[0] = 1;
		for (int n = 0; n < 31; n++) x2[n] = (init >>> n) & 1;
		for (int n = 0; n < 1600 + length; n++) {
			x1[n + 31] = x1[n + 3] ^ x1[n];
			x2[n + 31] = x2[n + 3] ^ x2[n + 2] ^ x2[n + 1] ^ x2[n];
		}
		int[] result = new int[length];
		for (int n = 0; n < length; n++) result[n] = x1[n + 1600] ^ x2[n + 1600];
		return result;
	}

	private static double square(double value) {
		return value * value;
	}

	static final class Decoded {
		static final Decoded EMPTY = new Decoded(false, 0, 0, 0, 0, 0, 0,
				NrLdpcDecoder.Result.EMPTY, new double[0]);
		final boolean demodulated;
		final double dmrsCoherence, evm, qpskCoherence;
		final int dataResourceElements, codedBits, transportBlockBits;
		final NrLdpcDecoder.Result transportBlock;
		final double[] rateMatchedLlrs;
		Decoded(boolean demodulated, double dmrsCoherence, double evm, double qpskCoherence,
				int dataResourceElements,
				int codedBits, int transportBlockBits, NrLdpcDecoder.Result transportBlock,
				double[] rateMatchedLlrs) {
			this.demodulated = demodulated;
			this.dmrsCoherence = dmrsCoherence;
			this.evm = evm;
			this.qpskCoherence = qpskCoherence;
			this.dataResourceElements = dataResourceElements;
			this.codedBits = codedBits;
			this.transportBlockBits = transportBlockBits;
			this.transportBlock = transportBlock;
			this.rateMatchedLlrs = rateMatchedLlrs;
		}
		Decoded withTransportBlock(NrLdpcDecoder.Result value) {
			return new Decoded(demodulated, dmrsCoherence, evm, qpskCoherence, dataResourceElements, codedBits,
					transportBlockBits, value, rateMatchedLlrs);
		}
	}
}
