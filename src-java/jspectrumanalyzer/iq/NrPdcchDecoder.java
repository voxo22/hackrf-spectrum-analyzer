package jspectrumanalyzer.iq;

/** Type0-PDCCH common-search-space acquisition derived from the decoded NR MIB. */
final class NrPdcchDecoder {
	private static final int FFT = 1024;
	private static final int SYMBOLS_PER_SLOT = 14;
	private static final int SAMPLES_PER_SLOT = 15_360;
	private static final int FIRST_SYMBOL_CP = 80;
	private static final int PSS_USEFUL_FROM_FRAME_START = 2_272;
	private static final int CORESET_RB = 48;
	private static final int CORESET_OFFSET_RB = 16;

	private NrPdcchDecoder() {}

	static Survey survey(double[] inI, double[] inQ, NrSignalAnalyzer.PssSurveyCandidate candidate,
			NrSignalAnalyzer.MibData mib) {
		if (mib == null || !mib.valid || mib.subcarrierSpacingCommonKhz != 15
				|| ((mib.pdcchConfigSib1 >>> 4) & 15) != 7) return Survey.EMPTY;
		int period = 15_360_000 / 50;
		double omega = candidate.cfoHz * 2d * Math.PI / 15_360_000d;
		Survey best = Survey.EMPTY;
		double[] combined = null;
		int combinedCount = 0;
		for (int repeat = -3; repeat <= 3; repeat++) {
			int pssUseful = candidate.sample + candidate.sssTimingDelta + repeat * period;
			int frameStart = pssUseful - PSS_USEFUL_FROM_FRAME_START;
			for (int slot = 2; slot <= 3; slot++) {
				int useful = frameStart + slot * SAMPLES_PER_SLOT + FIRST_SYMBOL_CP;
				if (useful < 0 || useful + FFT > inI.length) continue;
				double[][] symbol = NrSignalAnalyzer.surveySymbol(inI, inQ, useful, candidate.sample,
						omega, candidate.iqOrientation);
				Survey measured = measureDmrs(symbol, candidate, mib, slot, useful);
				if (measured.correlation >= 0.35) measured = measured.withDci(decodeDci(symbol, candidate,
						mib, measured));
				if (measured.dci.valid)
					measured = measured.withPdsch(NrPdschDecoder.decode(inI, inQ, candidate, mib, measured));
				if (measured.pdsch.rateMatchedLlrs.length > 0) {
					if (combined == null) combined = new double[measured.pdsch.rateMatchedLlrs.length];
					if (combined.length == measured.pdsch.rateMatchedLlrs.length) {
						for (int i = 0; i < combined.length; i++) combined[i] += measured.pdsch.rateMatchedLlrs[i];
						combinedCount++;
						NrLdpcDecoder.Result decoded = NrLdpcDecoder.decode(combined,
								measured.pdsch.transportBlockBits, measured.dci.rv);
						if (decoded.crcOk) measured = measured.withPdsch(measured.pdsch.withTransportBlock(decoded));
					}
				}
				if (better(measured, best)) best = measured;
			}
		}
		return combinedCount > 1 && !best.pdsch.transportBlock.crcOk
				? best.withPdsch(best.pdsch.withTransportBlock(NrLdpcDecoder.decode(combined,
						best.pdsch.transportBlockBits, best.dci.rv))) : best;
	}

	private static boolean better(Survey candidate, Survey current) {
		if (candidate.pdsch.transportBlock.crcOk != current.pdsch.transportBlock.crcOk)
			return candidate.pdsch.transportBlock.crcOk;
		if (candidate.dci.valid != current.dci.valid) return candidate.dci.valid;
		if (candidate.dci.valid && candidate.pdsch.transportBlock.repetitionAgreement
				!= current.pdsch.transportBlock.repetitionAgreement)
			return candidate.pdsch.transportBlock.repetitionAgreement
					> current.pdsch.transportBlock.repetitionAgreement;
		return candidate.correlation > current.correlation;
	}

	private static Survey measureDmrs(double[][] symbol, NrSignalAnalyzer.PssSurveyCandidate candidate,
			NrSignalAnalyzer.MibData mib, int slot, int usefulSample) {
		int commonRb0 = candidate.centerBin - 119 - mib.ssbSubcarrierOffset;
		int coresetBin0 = commonRb0 - CORESET_OFFSET_RB * 12;
		long cinitLong = ((1L << 17) * (SYMBOLS_PER_SLOT * slot + 1L)
				* (2L * candidate.pci + 1L) + 2L * candidate.pci) & 0x7fffffffL;
		int[] sequence = gold((int)cinitLong, CORESET_RB * 6);
		double cr = 0, ci = 0, previousPower = 0, currentPower = 0;
		double previousR = 0, previousI = 0;
		int pilots = 0, pairs = 0;
		for (int rb = 0; rb < CORESET_RB; rb++) {
			for (int pilot = 0; pilot < 3; pilot++) {
				int m = 3 * rb + pilot;
				double rr = (1 - 2 * sequence[2 * m]) / Math.sqrt(2d);
				double ri = (1 - 2 * sequence[2 * m + 1]) / Math.sqrt(2d);
				int bin = Math.floorMod(coresetBin0 + rb * 12 + 1 + pilot * 4, FFT);
				double yr = symbol[0][bin], yi = symbol[1][bin];
				double hr = yr * rr + yi * ri, hi = yi * rr - yr * ri;
				if (pilot > 0) {
					cr += hr * previousR + hi * previousI;
					ci += hi * previousR - hr * previousI;
					previousPower += previousR * previousR + previousI * previousI;
					currentPower += hr * hr + hi * hi;
					pairs++;
				}
				previousR = hr;
				previousI = hi;
				pilots++;
			}
		}
		double score = previousPower <= 1e-18 || currentPower <= 1e-18 ? 0
				: Math.hypot(cr, ci) / Math.sqrt(previousPower * currentPower);
		return new Survey(score, slot, usefulSample, coresetBin0, pilots, pairs, Dci.EMPTY,
				NrPdschDecoder.Decoded.EMPTY);
	}

	private static Dci decodeDci(double[][] symbol, NrSignalAnalyzer.PssSurveyCandidate candidate,
			NrSignalAnalyzer.MibData mib, Survey survey) {
		Dci best = Dci.EMPTY;
		int[][] locations = { { 4, 0 }, { 4, 4 }, { 8, 0 } };
		for (int[] location : locations) {
			double[] llr = candidateLlrs(symbol, candidate.pci, survey.slot, survey.coresetBin0,
					location[0], location[1]);
			for (int swap = 0; swap < 2; swap++) for (int sign = 1; sign >= -1; sign -= 2) {
				double[] ordered = new double[llr.length];
				for (int i = 0; i < llr.length / 2; i++) {
					ordered[2 * i] = sign * llr[2 * i + (swap == 0 ? 0 : 1)];
					ordered[2 * i + 1] = sign * llr[2 * i + (swap == 0 ? 1 : 0)];
				}
				int[] scramble = gold(candidate.pci, ordered.length);
				for (int i = 0; i < ordered.length; i++) ordered[i] *= 1 - 2 * scramble[i];
				int[] decoded = NrPolarDecoder.decode(ordered, 63);
				Dci dci = parseDci(decoded, location[0], location[1], swap, sign);
				if (dci.valid) return dci;
			}
		}
		return best;
	}

	private static double[] candidateLlrs(double[][] symbol, int pci, int slot, int coresetBin0,
			int aggregation, int ncce) {
		boolean[] rbMask = new boolean[CORESET_RB];
		for (int cce = ncce; cce < ncce + aggregation; cce++) {
			int bundle = ((cce & 1) * 4 + cce / 2 + pci) % 8;
			for (int rb = 6 * bundle; rb < 6 * bundle + 6; rb++) rbMask[rb] = true;
		}
		long cinitLong = ((1L << 17) * (SYMBOLS_PER_SLOT * slot + 1L)
				* (2L * pci + 1L) + 2L * pci) & 0x7fffffffL;
		int[] sequence = gold((int)cinitLong, CORESET_RB * 6);
		double[] output = new double[aggregation * 108];
		int at = 0;
		for (int rb = 0; rb < CORESET_RB; rb++) {
			if (!rbMask[rb]) continue;
			double[] hr = new double[3], hi = new double[3];
			for (int pilot = 0; pilot < 3; pilot++) {
				int m = 3 * rb + pilot;
				double rr = (1 - 2 * sequence[2 * m]) / Math.sqrt(2d);
				double ri = (1 - 2 * sequence[2 * m + 1]) / Math.sqrt(2d);
				int bin = Math.floorMod(coresetBin0 + rb * 12 + 1 + pilot * 4, FFT);
				double yr = symbol[0][bin], yi = symbol[1][bin];
				hr[pilot] = yr * rr + yi * ri;
				hi[pilot] = yi * rr - yr * ri;
			}
			for (int k = 0; k < 12; k++) {
				if ((k & 3) == 1) continue;
				double position = (k - 1) / 4d;
				int left = Math.max(0, Math.min(2, (int)Math.floor(position)));
				int right = Math.max(0, Math.min(2, left + 1));
				double fraction = Math.max(0, Math.min(1, position - left));
				double channelR = hr[left] * (1 - fraction) + hr[right] * fraction;
				double channelI = hi[left] * (1 - fraction) + hi[right] * fraction;
				double power = channelR * channelR + channelI * channelI;
				int bin = Math.floorMod(coresetBin0 + rb * 12 + k, FFT);
				double yr = symbol[0][bin], yi = symbol[1][bin];
				output[at++] = power < 1e-18 ? 0 : (yr * channelR + yi * channelI) / power;
				output[at++] = power < 1e-18 ? 0 : (yi * channelR - yr * channelI) / power;
			}
		}
		return output;
	}

	private static Dci parseDci(int[] decoded, int aggregation, int ncce, int qpskSwap, int qpskSign) {
		if (decoded == null || decoded.length != 63) return Dci.EMPTY;
		int[] unmasked = decoded.clone();
		for (int i = unmasked.length - 16; i < unmasked.length; i++) unmasked[i] ^= 1;
		int[] crcInput = new int[24 + unmasked.length];
		for (int i = 0; i < 24; i++) crcInput[i] = 1;
		System.arraycopy(unmasked, 0, crcInput, 24, unmasked.length);
		if (!crc24cOk(crcInput)) return Dci.EMPTY;
		int at = 0;
		int riv = read(unmasked, at, 11); at += 11;
		int time = read(unmasked, at, 4); at += 4;
		int vrb = unmasked[at++];
		int mcs = read(unmasked, at, 5); at += 5;
		int rv = read(unmasked, at, 2); at += 2;
		int sii = unmasked[at++];
		int reserved = read(unmasked, at, 15);
		int[] allocation = decodeRiv(riv);
		return new Dci(true, aggregation, ncce, riv, allocation[0], allocation[1], time, vrb,
				mcs, rv, sii, reserved, qpskSwap, qpskSign);
	}

	private static int[] decodeRiv(int riv) {
		for (int length = 1; length <= CORESET_RB; length++) for (int start = 0;
				start + length <= CORESET_RB; start++) {
			int encoded = length - 1 <= CORESET_RB / 2
					? CORESET_RB * (length - 1) + start
					: CORESET_RB * (CORESET_RB - length + 1) + CORESET_RB - 1 - start;
			if (encoded == riv) return new int[] { start, length };
		}
		return new int[] { -1, -1 };
	}

	private static int read(int[] bits, int offset, int length) {
		int value = 0;
		for (int i = 0; i < length; i++) value = (value << 1) | bits[offset + i];
		return value;
	}

	private static boolean crc24cOk(int[] bits) {
		int crc = 0;
		for (int bit : bits) {
			int feedback = ((crc >>> 23) & 1) ^ bit;
			crc = (crc << 1) & 0xffffff;
			if (feedback != 0) crc ^= 0xb2b117;
		}
		return crc == 0;
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

	static final class Survey {
		static final Survey EMPTY = new Survey(0, -1, -1, 0, 0, 0, Dci.EMPTY,
				NrPdschDecoder.Decoded.EMPTY);
		final double correlation;
		final int slot, usefulSample, coresetBin0, pilots, pairs;
		final Dci dci;
		final NrPdschDecoder.Decoded pdsch;
		Survey(double correlation, int slot, int usefulSample, int coresetBin0, int pilots, int pairs,
				Dci dci, NrPdschDecoder.Decoded pdsch) {
			this.correlation = correlation;
			this.slot = slot;
			this.usefulSample = usefulSample;
			this.coresetBin0 = coresetBin0;
			this.pilots = pilots;
			this.pairs = pairs;
			this.dci = dci;
			this.pdsch = pdsch;
		}
		Survey withDci(Dci value) {
			return new Survey(correlation, slot, usefulSample, coresetBin0, pilots, pairs, value, pdsch);
		}
		Survey withPdsch(NrPdschDecoder.Decoded value) {
			return new Survey(correlation, slot, usefulSample, coresetBin0, pilots, pairs, dci, value);
		}
	}

	static final class Dci {
		static final Dci EMPTY = new Dci(false, 0, 0, 0, -1, -1, 0, 0, 0, 0, 0, 0, 0, 1);
		final boolean valid;
		final int aggregation, ncce, riv, rbStart, rbLength, timeAllocation;
		final int vrbMapping, mcs, rv, systemInformationIndicator, reserved;
		final int qpskSwap, qpskSign;
		Dci(boolean valid, int aggregation, int ncce, int riv, int rbStart, int rbLength,
				int timeAllocation, int vrbMapping, int mcs, int rv, int systemInformationIndicator,
				int reserved, int qpskSwap, int qpskSign) {
			this.valid = valid;
			this.aggregation = aggregation;
			this.ncce = ncce;
			this.riv = riv;
			this.rbStart = rbStart;
			this.rbLength = rbLength;
			this.timeAllocation = timeAllocation;
			this.vrbMapping = vrbMapping;
			this.mcs = mcs;
			this.rv = rv;
			this.systemInformationIndicator = systemInformationIndicator;
			this.reserved = reserved;
			this.qpskSwap = qpskSwap;
			this.qpskSign = qpskSign;
		}
	}
}
