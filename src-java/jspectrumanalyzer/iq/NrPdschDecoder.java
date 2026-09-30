package jspectrumanalyzer.iq;

/** PDSCH demodulation for the SI-RNTI grant carried by Type0 common search space. */
final class NrPdschDecoder {
	private static final int FFT = 1024;
	private static final int SYMBOL_STEP = 1096;
	private static final int PHASE_STEPS = 16;
	private static final int[] TIMING_OFFSETS = {
		0, -4, 4, -8, 8, -12, 12, -16, 16, -20, 20, -24, 24
	};
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
	private static final int[] DEFAULT_A_START_POS2 = {
		2, 2, 2, 2, 2, 9, 4, 5, 5, 9, 12, 1, 1, 2, 4, 8
	};
	private static final int[] DEFAULT_A_LENGTH_POS2 = {
		12, 10, 9, 7, 5, 4, 4, 7, 2, 2, 2, 13, 6, 4, 7, 4
	};
	private static final int[] DEFAULT_A_START_POS3 = {
		3, 3, 3, 3, 3, 10, 6, 5, 5, 9, 12, 1, 1, 2, 4, 8
	};
	private static final int[] DEFAULT_A_LENGTH_POS3 = {
		11, 9, 8, 6, 4, 4, 4, 7, 2, 2, 2, 13, 6, 4, 7, 4
	};

	private NrPdschDecoder() {}

	static Decoded decode(double[] inI, double[] inQ, NrSignalAnalyzer.PssSurveyCandidate candidate,
			NrSignalAnalyzer.MibData mib, NrPdcchDecoder.Survey pdcch) {
		Decoded best = Decoded.EMPTY;
		for (int timing : TIMING_OFFSETS) {
			Decoded trial = decodeAtTiming(inI, inQ, candidate, mib, pdcch, timing);
			if (trial.transportBlock.crcOk) return trial;
			if (betterDecoded(trial, best)) best = trial;
		}
		return best;
	}

	private static boolean betterDecoded(Decoded candidate, Decoded current) {
		if (candidate.demodulated != current.demodulated) return candidate.demodulated;
		if (!candidate.demodulated) return false;
		int candidateSyndrome = candidate.transportBlock.syndromeErrors;
		int currentSyndrome = current.transportBlock.syndromeErrors;
		if (candidateSyndrome != Integer.MAX_VALUE && currentSyndrome != Integer.MAX_VALUE
				&& candidateSyndrome != currentSyndrome)
			return candidateSyndrome < currentSyndrome;
		if (candidate.transportBlock.repetitionAgreement
				!= current.transportBlock.repetitionAgreement)
			return candidate.transportBlock.repetitionAgreement
					> current.transportBlock.repetitionAgreement;
		return candidate.qpskCoherence > current.qpskCoherence;
	}

	private static Decoded decodeAtTiming(double[] inI, double[] inQ,
			NrSignalAnalyzer.PssSurveyCandidate candidate, NrSignalAnalyzer.MibData mib,
			NrPdcchDecoder.Survey pdcch, int timing) {
		NrPdcchDecoder.Dci dci = pdcch.dci;
		int[] allocation = timeAllocation(dci.timeAllocation, mib.dmrsTypeAPosition);
		if (!dci.valid || allocation.length == 0
				|| dci.mcs < 0 || dci.mcs >= MODULATION_ORDER.length) return Decoded.EMPTY;
		int startSymbol = allocation[0], symbolLength = allocation[1];
		int[] dmrsSymbols = dmrsSymbols(mib.dmrsTypeAPosition, startSymbol + symbolLength);
		if (dmrsSymbols.length == 0) return Decoded.EMPTY;
		int qm = MODULATION_ORDER[dci.mcs];
		int rate = CODE_RATE_X1024[dci.mcs];
		if (qm != 2 || rate == 0 || dci.rbStart < 0 || dci.rbLength <= 0) return Decoded.EMPTY;

		double omega = candidate.cfoHz * 2d * Math.PI / 15_360_000d;
		double[][][] symbols = new double[14][][];
		for (int l = startSymbol; l < startSymbol + symbolLength; l++) {
			int useful = pdcch.usefulSample + symbolUsefulOffset(l) + timing;
			if (useful < 0 || useful + FFT > inI.length) return Decoded.EMPTY;
			symbols[l] = NrSignalAnalyzer.surveySymbol(inI, inQ, useful, candidate.sample, omega,
					candidate.iqOrientation);
		}

		double[][][][] channel = new double[dmrsSymbols.length][dci.rbLength][2][12];
		double coherenceSum = 0;
		int coherenceCount = 0;
		for (int ds = 0; ds < dmrsSymbols.length; ds++) {
			int l = dmrsSymbols[ds];
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
		smoothChannel(channel, dci.rbLength);

		int dataRe = dci.rbLength * (symbolLength * 12 - dmrsSymbols.length * 12);
		double[] llr = new double[dataRe * qm];
		double[] rawLlrs = new double[dataRe * qm];
		double[] equalizedR = new double[dataRe], equalizedI = new double[dataRe];
		int[] equalizedSymbol = new int[dataRe];
		double error = 0, signal = 0;
		int at = 0;
		for (int l = startSymbol; l < startSymbol + symbolLength; l++)
				for (int rb = 0; rb < dci.rbLength; rb++) {
			int[] time = timeInterpolation(l, dmrsSymbols);
			for (int k = 0; k < 12; k++) {
				if (isDmrsSymbol(l, dmrsSymbols)) continue;
				double fraction = time[2] / 1000d;
				double h0r = channel[time[0]][rb][0][k], h0i = channel[time[0]][rb][1][k];
				double h1r = channel[time[1]][rb][0][k], h1i = channel[time[1]][rb][1][k];
				double magnitude0 = Math.hypot(h0r, h0i), magnitude1 = Math.hypot(h1r, h1i);
				double phase0 = Math.atan2(h0i, h0r);
				double phaseDelta = Math.atan2(h1i, h1r) - phase0;
				while (phaseDelta > Math.PI) phaseDelta -= 2d * Math.PI;
				while (phaseDelta < -Math.PI) phaseDelta += 2d * Math.PI;
				double phase = phase0 + fraction * phaseDelta;
				double magnitude = magnitude0 * (1 - fraction) + magnitude1 * fraction;
				double hr = magnitude * Math.cos(phase) / Math.sqrt(2d);
				double hi = magnitude * Math.sin(phase) / Math.sqrt(2d);
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
				rawLlrs[2 * at] = matchedR;
				rawLlrs[2 * at + 1] = matchedI;
				equalizedSymbol[at++] = l;
			}
		}
		if (2 * at != llr.length) return Decoded.EMPTY;
		double fourthCoherent = 0, fourthPower = 0;
		double[] symbolQpskCoherence = new double[14];
		double previousPhase = 0;
		boolean havePreviousPhase = false;
		for (int l = startSymbol; l < startSymbol + symbolLength; l++) {
			double fourthR = 0, fourthI = 0, symbolFourthPower = 0;
			for (int i = 0; i < at; i++) if (equalizedSymbol[i] == l) {
				double r = equalizedR[i], q = equalizedI[i];
				double squareR = r * r - q * q, squareI = 2 * r * q;
				fourthR += squareR * squareR - squareI * squareI;
				fourthI += 2 * squareR * squareI;
				double power = squareR * squareR + squareI * squareI;
				fourthPower += power;
				symbolFourthPower += power;
			}
			double symbolFourthCoherent = Math.hypot(fourthR, fourthI);
			fourthCoherent += symbolFourthCoherent;
			symbolQpskCoherence[l] = symbolFourthPower == 0 ? 0
					: symbolFourthCoherent / symbolFourthPower;
			if (symbolFourthPower == 0) continue;
			double phase = Math.atan2(fourthI, fourthR) / 4d - Math.PI / 4d;
			// Unwrap the pi/2 QPSK ambiguity using the channel's phase continuity in time.
			if (!havePreviousPhase) {
				while (phase > Math.PI / 4d) phase -= Math.PI / 2d;
				while (phase < -Math.PI / 4d) phase += Math.PI / 2d;
				havePreviousPhase = true;
			} else {
				while (phase - previousPhase > Math.PI / 4d) phase -= Math.PI / 2d;
				while (phase - previousPhase < -Math.PI / 4d) phase += Math.PI / 2d;
			}
			previousPhase = phase;
			double cosine = Math.cos(phase), sine = Math.sin(phase);
			for (int i = 0; i < at; i++) if (equalizedSymbol[i] == l) {
				double xr = equalizedR[i] * cosine + equalizedI[i] * sine;
				double xi = equalizedI[i] * cosine - equalizedR[i] * sine;
				llr[2 * i] = xr;
				llr[2 * i + 1] = xi;
			}
		}
		int tbs = transportBlockSize(dci.rbLength, symbolLength, dmrsSymbols.length, qm, rate);
		DecodeAttempt best = decodeRateMatched(llr, candidate.pci, tbs, dci.rv, dci.qpskSwap, dci.qpskSign);
		if (!best.result.crcOk && NrLdpcDecoder.supportsTransportBlock(tbs)) {
			DecodeAttempt raw = decodeRateMatched(rawLlrs, candidate.pci, tbs, dci.rv,
					dci.qpskSwap, dci.qpskSign);
			if (raw.result.crcOk) best = raw;
		}
		if (!best.result.crcOk && NrLdpcDecoder.supportsTransportBlock(tbs)) {
			DecodeAttempt phaseAligned = decodeSymbolRotations(llr, equalizedSymbol, candidate.pci,
					tbs, dci.rv, dmrsSymbols);
			if (phaseAligned.result.crcOk || phaseAligned.result.repetitionAgreement
					> best.result.repetitionAgreement) best = phaseAligned;
		}
		if (!best.result.crcOk && NrLdpcDecoder.supportsTransportBlock(tbs)) {
			DecodeAttempt rawPhaseAligned = decodeSymbolRotations(rawLlrs, equalizedSymbol,
					candidate.pci, tbs, dci.rv, dmrsSymbols);
			if (rawPhaseAligned.result.crcOk) best = rawPhaseAligned;
		}
		double coherence = coherenceCount == 0 ? 0 : coherenceSum / coherenceCount;
		double evm = signal == 0 ? 0 : Math.sqrt(error / signal);
		double qpskCoherence = fourthPower == 0 ? 0 : fourthCoherent / fourthPower;
		return new Decoded(true, coherence, evm, qpskCoherence, best.llrs.length / qm, best.llrs.length,
				tbs, best.result, best.llrs, symbolQpskCoherence, startSymbol, symbolLength,
				dmrsSymbols);
	}

	private static DecodeAttempt decodeSymbolRotations(double[] llr, int[] symbols, int pci, int tbs,
			int rv, int[] dmrsSymbols) {
		int[] rotations = new int[14];
		DecodeAttempt best = evaluatedRotation(llr, symbols, rotations, pci, tbs, rv);
		int rotationStep = PHASE_STEPS / 4;
		for (int pass = 0; pass < 3; pass++) {
			boolean changed = false;
			for (int symbol = 1; symbol < 14; symbol++) {
				if (isDmrsSymbol(symbol, dmrsSymbols)) continue;
				int previous = rotations[symbol], selected = previous;
				DecodeAttempt symbolBest = best;
				for (int rotation = 0; rotation < PHASE_STEPS; rotation += rotationStep) {
					rotations[symbol] = rotation;
					DecodeAttempt trial = evaluatedRotation(llr, symbols, rotations, pci, tbs, rv);
					if (betterRotation(trial, symbolBest)) {
						symbolBest = trial;
						selected = rotation;
					}
				}
				rotations[symbol] = selected;
				changed |= selected != previous;
				best = symbolBest;
				if (best.result.crcOk) return best;
			}
			if (!changed) break;
		}
		DecodeAttempt fallback = best;
		for (int quadrant = 0; quadrant < 4; quadrant++) {
			int[] global = rotations.clone();
			for (int symbol = 1; symbol < global.length; symbol++)
				global[symbol] = (global[symbol] + quadrant * PHASE_STEPS / 4) % PHASE_STEPS;
			DecodeAttempt trial = rotatedAttempt(llr, symbols, global, pci, tbs, rv);
			trial = new DecodeAttempt(NrLdpcDecoder.decode(trial.llrs, tbs, rv), trial.llrs);
			if (trial.result.crcOk) return trial;
			if (betterRotation(trial, fallback)) fallback = trial;
		}
		return fallback;
	}

	private static DecodeAttempt evaluatedRotation(double[] llr, int[] symbols, int[] rotations,
			int pci, int tbs, int rv) {
		DecodeAttempt trial = rotatedAttempt(llr, symbols, rotations, pci, tbs, rv);
		return new DecodeAttempt(NrLdpcDecoder.decodeForScore(trial.llrs, tbs, rv), trial.llrs);
	}

	private static boolean betterRotation(DecodeAttempt candidate, DecodeAttempt current) {
		if (candidate.result.crcOk != current.result.crcOk) return candidate.result.crcOk;
		if (candidate.result.syndromeErrors != Integer.MAX_VALUE
				&& current.result.syndromeErrors != Integer.MAX_VALUE)
			return candidate.result.syndromeErrors < current.result.syndromeErrors;
		if (candidate.result.repetitionAgreement > 0
				|| current.result.repetitionAgreement > 0)
			return candidate.result.repetitionAgreement > current.result.repetitionAgreement;
		return candidate.result.syndromeErrors < current.result.syndromeErrors;
	}

	private static DecodeAttempt rotatedAttempt(double[] llr, int[] symbols, int[] rotations,
			int pci, int tbs, int rv) {
		double[] ordered = new double[llr.length];
		for (int i = 0; i < symbols.length; i++) {
			double real = llr[2 * i], imag = llr[2 * i + 1];
			double angle = 2d * Math.PI * rotations[symbols[i]] / PHASE_STEPS;
			double cosine = Math.cos(angle), sine = Math.sin(angle);
			ordered[2 * i] = real * cosine + imag * sine;
			ordered[2 * i + 1] = imag * cosine - real * sine;
		}
		int[] scramble = gold((0xffff << 15) + pci, ordered.length);
		for (int i = 0; i < ordered.length; i++) ordered[i] *= 1 - 2 * scramble[i];
		double agreement = NrLdpcDecoder.repetitionAgreement(ordered, tbs, rv);
		return new DecodeAttempt(new NrLdpcDecoder.Result(false, 0, new byte[0], agreement), ordered);
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

	private static void smoothChannel(double[][][][] channel, int rbLength) {
		int carriers = rbLength * 12;
		for (int ds = 0; ds < channel.length; ds++) {
			double[] real = new double[carriers], imaginary = new double[carriers];
			for (int rb = 0; rb < rbLength; rb++) for (int k = 0; k < 12; k++) {
				real[rb * 12 + k] = channel[ds][rb][0][k];
				imaginary[rb * 12 + k] = channel[ds][rb][1][k];
			}
			for (int carrier = 0; carrier < carriers; carrier++) {
				double sumR = 0, sumI = 0, weight = 0;
				for (int delta = -4; delta <= 4; delta++) {
					int neighbor = carrier + delta;
					if (neighbor < 0 || neighbor >= carriers) continue;
					double w = 5 - Math.abs(delta);
					sumR += w * real[neighbor];
					sumI += w * imaginary[neighbor];
					weight += w;
				}
				channel[ds][carrier / 12][0][carrier % 12] = sumR / weight;
				channel[ds][carrier / 12][1][carrier % 12] = sumI / weight;
			}
		}
	}

	private static int[] timeAllocation(int index, int typeAPosition) {
		if (index < 0 || index >= 16 || (index >= 5 && index <= 10) || index >= 14)
			return new int[0];
		if (typeAPosition == 2)
			return new int[] { DEFAULT_A_START_POS2[index], DEFAULT_A_LENGTH_POS2[index] };
		if (typeAPosition == 3)
			return new int[] { DEFAULT_A_START_POS3[index], DEFAULT_A_LENGTH_POS3[index] };
		return new int[0];
	}

	private static int[] dmrsSymbols(int typeAPosition, int endSymbol) {
		int first = typeAPosition;
		if (first != 2 && first != 3) return new int[0];
		if (endSymbol <= first) return new int[0];
		if (endSymbol <= 7) return new int[] { first };
		if (endSymbol <= 9) return new int[] { first, 6 };
		if (endSymbol <= 12) return new int[] { first, 6, 9 };
		if (endSymbol <= 14) return new int[] { first, 7, 11 };
		return new int[0];
	}

	private static int[] timeInterpolation(int symbol, int[] dmrsSymbols) {
		if (symbol <= dmrsSymbols[0]) return new int[] { 0, 0, 0 };
		int last = dmrsSymbols.length - 1;
		if (symbol >= dmrsSymbols[last]) return new int[] { last, last, 0 };
		int left = 0;
		while (left + 1 < dmrsSymbols.length && symbol >= dmrsSymbols[left + 1]) left++;
		int right = left + 1;
		int fraction = 1000 * (symbol - dmrsSymbols[left])
				/ (dmrsSymbols[right] - dmrsSymbols[left]);
		return new int[] { left, right, fraction };
	}

	private static boolean isDmrsSymbol(int symbol, int[] dmrsSymbols) {
		for (int value : dmrsSymbols) if (value == symbol) return true;
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
				NrLdpcDecoder.Result.EMPTY, new double[0], new double[0], 0, 0, new int[0]);
		final boolean demodulated;
		final double dmrsCoherence, evm, qpskCoherence;
		final int dataResourceElements, codedBits, transportBlockBits, startSymbol, symbolLength;
		final NrLdpcDecoder.Result transportBlock;
		final double[] rateMatchedLlrs;
		final double[] symbolQpskCoherence;
		final int[] dmrsSymbols;
		Decoded(boolean demodulated, double dmrsCoherence, double evm, double qpskCoherence,
				int dataResourceElements,
				int codedBits, int transportBlockBits, NrLdpcDecoder.Result transportBlock,
				double[] rateMatchedLlrs, double[] symbolQpskCoherence, int startSymbol,
				int symbolLength, int[] dmrsSymbols) {
			this.demodulated = demodulated;
			this.dmrsCoherence = dmrsCoherence;
			this.evm = evm;
			this.qpskCoherence = qpskCoherence;
			this.dataResourceElements = dataResourceElements;
			this.codedBits = codedBits;
			this.transportBlockBits = transportBlockBits;
			this.transportBlock = transportBlock;
			this.rateMatchedLlrs = rateMatchedLlrs;
			this.symbolQpskCoherence = symbolQpskCoherence;
			this.startSymbol = startSymbol;
			this.symbolLength = symbolLength;
			this.dmrsSymbols = dmrsSymbols.clone();
		}
		Decoded withTransportBlock(NrLdpcDecoder.Result value) {
			return new Decoded(demodulated, dmrsCoherence, evm, qpskCoherence, dataResourceElements, codedBits,
					transportBlockBits, value, rateMatchedLlrs, symbolQpskCoherence,
					startSymbol, symbolLength, dmrsSymbols);
		}
	}
}
