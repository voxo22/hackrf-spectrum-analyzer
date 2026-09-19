package jspectrumanalyzer.iq;

import java.util.HashMap;
import java.util.Map;

/** Type0-PDCCH common-search-space acquisition derived from the decoded NR MIB. */
final class NrPdcchDecoder {
	private static final int FFT = 1024;
	private static final int SYMBOLS_PER_SLOT = 14;
	private static final int SAMPLES_PER_SLOT = 15_360;
	private static final int FIRST_SYMBOL_CP = 80;
	private static final int PSS_USEFUL_FROM_FRAME_START = 2_272;
	private static final int LEGACY_CORESET_RB = 48;
	private static final int LEGACY_CORESET_OFFSET_RB = 16;
	private static final int SYMBOL_STEP = 1_096;
	private static final int HALF_FRAME_SAMPLES = 153_600;

	private NrPdcchDecoder() {}

	static Survey survey(double[] inI, double[] inQ, NrSignalAnalyzer.PssSurveyCandidate candidate,
			NrSignalAnalyzer.MibData mib) {
		if (candidate.ssbSubcarrierSpacingKhz == 15) return survey15Khz(inI, inQ, candidate, mib);
		CoreSetConfig config = coreSetConfig(candidate, mib);
		if (config == null) return Survey.EMPTY;
		int period = candidate.surveySampleRateHz / 50;
		double omega = candidate.cfoHz * 2d * Math.PI / candidate.surveySampleRateHz;
		Survey best = Survey.EMPTY;
		double[] combined = null;
		int combinedCount = 0;
		Map<String, double[]> combinedPdcch = new HashMap<String, double[]>();
		for (int repeat = -3; repeat <= 3; repeat++) {
			int pssUseful = candidate.sample + candidate.sssTimingDelta + repeat * period;
			for (int pssOffset : pssUsefulOffsets(candidate)) {
			int frameStart = pssUseful - pssOffset;
			for (int slot : config.monitoringSlots) {
			int timingRadius = candidate.ssbSubcarrierSpacingKhz == 30 ? 24 : 0;
			for (int timing = -timingRadius; timing <= timingRadius; timing++) {
				int useful = frameStart + slot * SAMPLES_PER_SLOT + FIRST_SYMBOL_CP + timing;
				if (useful < 0 || useful + (config.duration - 1) * SYMBOL_STEP + FFT > inI.length) continue;
				double[][][] symbols = new double[config.duration][][];
				for (int l = 0; l < config.duration; l++)
					symbols[l] = NrSignalAnalyzer.surveySymbol(inI, inQ, useful + l * SYMBOL_STEP,
							candidate.sample, omega, candidate.iqOrientation);
				int binRadius = candidate.ssbSubcarrierSpacingKhz == 30 ? 12 : 0;
				for (int binShift = -binRadius; binShift <= binRadius; binShift++) {
				Survey measured = measureDmrs(symbols, candidate, slot, useful, config, binShift);
				if (config.duration == 1
						&& measured.correlation >= (config.duration == 1 ? 0.35 : 0.25)) {
					String combineKey = pssOffset + ":" + slot + ":" + timing + ":" + binShift;
					measured = measured.withDci(decodeDci(symbols, candidate, measured, config,
							combinedPdcch, combineKey, 0, null));
				}
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
			}
			}
		}
		if (!best.dci.valid && best.slot >= 0 && best.correlation >= 0.40) {
			double[][][] symbols = new double[config.duration][][];
			for (int l = 0; l < config.duration; l++)
				symbols[l] = NrSignalAnalyzer.surveySymbol(inI, inQ,
						best.usefulSample + l * SYMBOL_STEP, candidate.sample, omega,
						candidate.iqOrientation);
			DecodeDebug debug = new DecodeDebug();
			Dci dci = decodeDci(symbols, candidate, best, config,
					new HashMap<String, double[]>(), "best", -1, debug);
			if (!dci.valid) dci = decodeDciRepeated(inI, inQ, candidate, best, config,
					omega, -1, debug);
			if (!dci.valid) dci = decodeDci(symbols, candidate, best, config,
					new HashMap<String, double[]>(), "best", 32, debug);
			if (!dci.valid) dci = decodeDciRepeated(inI, inQ, candidate, best, config,
					omega, 32, debug);
			best = best.withDci(dci);
			best = best.withDebug(debug.summary());
			if (dci.valid) best = best.withPdsch(NrPdschDecoder.decode(inI, inQ, candidate, mib, best));
		}
		return combinedCount > 1 && !best.pdsch.transportBlock.crcOk
				? best.withPdsch(best.pdsch.withTransportBlock(NrLdpcDecoder.decode(combined,
						best.pdsch.transportBlockBits, best.dci.rv))) : best;
	}

	private static Survey survey15Khz(double[] inI, double[] inQ,
			NrSignalAnalyzer.PssSurveyCandidate candidate, NrSignalAnalyzer.MibData mib) {
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
				Survey measured = measureDmrs15Khz(symbol, candidate, mib, slot, useful);
				if (measured.correlation >= 0.35)
					measured = measured.withDci(decodeDci15Khz(symbol, candidate, measured));
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

	private static Survey measureDmrs15Khz(double[][] symbol, NrSignalAnalyzer.PssSurveyCandidate candidate,
			NrSignalAnalyzer.MibData mib, int slot, int usefulSample) {
		int commonRb0 = candidate.centerBin - 119 - mib.ssbSubcarrierOffset;
		int coresetBin0 = commonRb0 - LEGACY_CORESET_OFFSET_RB * 12;
		long cinitLong = ((1L << 17) * (SYMBOLS_PER_SLOT * slot + 1L)
				* (2L * candidate.pci + 1L) + 2L * candidate.pci) & 0x7fffffffL;
		int[] sequence = gold((int)cinitLong, LEGACY_CORESET_RB * 6);
		double cr = 0, ci = 0, previousPower = 0, currentPower = 0;
		double previousR = 0, previousI = 0;
		int pilots = 0, pairs = 0;
		for (int rb = 0; rb < LEGACY_CORESET_RB; rb++) {
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
		return new Survey(score, slot, usefulSample, coresetBin0, LEGACY_CORESET_RB, 1,
				pilots, pairs, Dci.EMPTY, NrPdschDecoder.Decoded.EMPTY, "");
	}

	private static Dci decodeDci15Khz(double[][] symbol, NrSignalAnalyzer.PssSurveyCandidate candidate,
			Survey survey) {
		Dci best = Dci.EMPTY;
		int[][] locations = { { 4, 0 }, { 4, 4 }, { 8, 0 } };
		for (int[] location : locations) {
			double[] llr = candidateLlrs15Khz(symbol, candidate.pci, survey.slot, survey.coresetBin0,
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
				Dci dci = parseDci15Khz(decoded, location[0], location[1], swap, sign);
				if (dci.valid) return dci;
			}
		}
		return best;
	}

	private static double[] candidateLlrs15Khz(double[][] symbol, int pci, int slot, int coresetBin0,
			int aggregation, int ncce) {
		boolean[] rbMask = new boolean[LEGACY_CORESET_RB];
		for (int cce = ncce; cce < ncce + aggregation; cce++) {
			int bundle = ((cce & 1) * 4 + cce / 2 + pci) % 8;
			for (int rb = 6 * bundle; rb < 6 * bundle + 6; rb++) rbMask[rb] = true;
		}
		long cinitLong = ((1L << 17) * (SYMBOLS_PER_SLOT * slot + 1L)
				* (2L * pci + 1L) + 2L * pci) & 0x7fffffffL;
		int[] sequence = gold((int)cinitLong, LEGACY_CORESET_RB * 6);
		double[] output = new double[aggregation * 108];
		int at = 0;
		for (int rb = 0; rb < LEGACY_CORESET_RB; rb++) {
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

	private static Dci parseDci15Khz(int[] decoded, int aggregation, int ncce,
			int qpskSwap, int qpskSign) {
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
		int[] allocation = decodeRiv15Khz(riv);
		return new Dci(true, aggregation, ncce, riv, allocation[0], allocation[1], time, vrb,
				mcs, rv, sii, reserved, qpskSwap, qpskSign);
	}

	private static int[] decodeRiv15Khz(int riv) {
		for (int length = 1; length <= LEGACY_CORESET_RB; length++) for (int start = 0;
				start + length <= LEGACY_CORESET_RB; start++) {
			int encoded = length - 1 <= LEGACY_CORESET_RB / 2
					? LEGACY_CORESET_RB * (length - 1) + start
					: LEGACY_CORESET_RB * (LEGACY_CORESET_RB - length + 1)
							+ LEGACY_CORESET_RB - 1 - start;
			if (encoded == riv) return new int[] { start, length };
		}
		return new int[] { -1, -1 };
	}

	private static CoreSetConfig coreSetConfig(NrSignalAnalyzer.PssSurveyCandidate candidate,
			NrSignalAnalyzer.MibData mib) {
		if (mib == null || !mib.valid) return null;
		int coreSetIndex = (mib.pdcchConfigSib1 >>> 4) & 15;
		int searchSpaceIndex = mib.pdcchConfigSib1 & 15;
		if (candidate.ssbSubcarrierSpacingKhz == 15 && mib.subcarrierSpacingCommonKhz == 15
				&& coreSetIndex == 7) {
			return new CoreSetConfig(48, 1, 16, new int[] { 2, 3 });
		}
		if (candidate.ssbSubcarrierSpacingKhz == 30 && mib.subcarrierSpacingCommonKhz == 30
				&& coreSetIndex == 0 && searchSpaceIndex == 4) {
			int iSsb = Math.max(0, Math.min(7, candidate.iBarSsb & 7));
			return new CoreSetConfig(24, 2, 0, new int[] { 10 + iSsb });
		}
		return null;
	}

	private static int[] pssUsefulOffsets(NrSignalAnalyzer.PssSurveyCandidate candidate) {
		if (candidate.ssbSubcarrierSpacingKhz == 15) return new int[] { 2_272 };
		int iSsb = Math.max(0, Math.min(7, candidate.iBarSsb & 7));
		int firstSymbol = 14 * (iSsb / 2) + ((iSsb & 1) == 0 ? 2 : 8);
		int slot = firstSymbol / SYMBOLS_PER_SLOT;
		int symbol = firstSymbol % SYMBOLS_PER_SLOT;
		int firstHalf = slot * SAMPLES_PER_SLOT + FIRST_SYMBOL_CP + symbol * SYMBOL_STEP;
		/* For Lmax=8 the PBCH DM-RS hypothesis identifies all three iSSB bits;
		 * the half-frame position is not encoded in iBarSSB. Try both 5 ms halves. */
		return new int[] { firstHalf, firstHalf + HALF_FRAME_SAMPLES };
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

	private static Survey measureDmrs(double[][][] symbols, NrSignalAnalyzer.PssSurveyCandidate candidate,
			int slot, int usefulSample, CoreSetConfig config, int binShift) {
		int kSsbBins = candidate.mib.ssbSubcarrierOffset * 15
				/ candidate.mib.subcarrierSpacingCommonKhz;
		int commonRb0 = candidate.centerBin - 119 - kSsbBins;
		int coresetBin0 = commonRb0 - config.offsetRb * 12 + binShift;
		double scoreSum = 0;
		int scoreSymbols = 0, pilots = 0, pairs = 0;
		for (int l = 0; l < config.duration; l++) {
			double cr = 0, ci = 0, previousPower = 0, currentPower = 0;
			int symbolPairs = 0;
			long cinitLong = ((1L << 17) * (SYMBOLS_PER_SLOT * slot + l + 1L)
					* (2L * candidate.pci + 1L) + 2L * candidate.pci) & 0x7fffffffL;
			int[] sequence = gold((int)cinitLong, config.rb * 6);
			for (int rb = 0; rb < config.rb; rb++) {
				double previousR = 0, previousI = 0;
				for (int pilot = 0; pilot < 3; pilot++) {
					int m = 3 * rb + pilot;
					double rr = (1 - 2 * sequence[2 * m]) / Math.sqrt(2d);
					double ri = (1 - 2 * sequence[2 * m + 1]) / Math.sqrt(2d);
					int bin = Math.floorMod(coresetBin0 + rb * 12 + 1 + pilot * 4, FFT);
					double yr = symbols[l][0][bin], yi = symbols[l][1][bin];
					double hr = yr * rr + yi * ri, hi = yi * rr - yr * ri;
					if (pilot > 0) {
						cr += hr * previousR + hi * previousI;
						ci += hi * previousR - hr * previousI;
						previousPower += previousR * previousR + previousI * previousI;
						currentPower += hr * hr + hi * hi;
						symbolPairs++;
					}
					previousR = hr;
					previousI = hi;
					pilots++;
				}
			}
			pairs += symbolPairs;
			if (previousPower > 1e-18 && currentPower > 1e-18) {
				scoreSum += Math.hypot(cr, ci) / Math.sqrt(previousPower * currentPower);
				scoreSymbols++;
			}
		}
		double score = scoreSymbols == 0 ? 0 : scoreSum / scoreSymbols;
		return new Survey(score, slot, usefulSample, coresetBin0, config.rb, config.duration,
				pilots, pairs, Dci.EMPTY,
				NrPdschDecoder.Decoded.EMPTY, "");
	}

	private static Dci decodeDci(double[][][] symbols, NrSignalAnalyzer.PssSurveyCandidate candidate,
			Survey survey, CoreSetConfig config, Map<String, double[]> combined,
			String combineKey, int listSize, DecodeDebug debug) {
		Dci best = Dci.EMPTY;
		int mappingVariants = listSize != 0 ? 4 : 1;
		for (int[] location : candidateLocations(config)) {
			for (int mapping = 0; mapping < mappingVariants; mapping++) {
			double[] llr = candidateLlrs(symbols, candidate.pci, survey.slot, survey.coresetBin0,
					location[0], location[1], config, mapping);
			Dci dci = decodeDciLlrs(llr, candidate.pci, location, config.rb, listSize,
					mapping, debug);
			if (dci.valid) return dci;
			String locationKey = combineKey + ":" + location[0] + ":" + location[1] + ":" + mapping;
			double[] sum = combined.get(locationKey);
			if (sum == null) {
				sum = new double[llr.length];
				combined.put(locationKey, sum);
			}
			for (int i = 0; i < llr.length; i++) sum[i] += llr[i];
			dci = decodeDciLlrs(sum, candidate.pci, location, config.rb, listSize,
					mapping, debug);
			if (dci.valid) return dci;
			}
		}
		return best;
	}

	private static Dci decodeDciRepeated(double[] inI, double[] inQ,
			NrSignalAnalyzer.PssSurveyCandidate candidate, Survey survey, CoreSetConfig config,
			double omega, int listSize, DecodeDebug debug) {
		int period = candidate.surveySampleRateHz / 50;
		int mappingVariants = listSize != 0 ? 4 : 1;
		for (int[] location : candidateLocations(config)) {
			for (int mapping = 0; mapping < mappingVariants; mapping++) {
				double[] sum = null;
				int count = 0;
				for (int repeat = -3; repeat <= 3; repeat++) {
					int useful = survey.usefulSample + repeat * period;
					if (useful < 0 || useful + (config.duration - 1) * SYMBOL_STEP + FFT > inI.length)
						continue;
					double[][][] symbols = new double[config.duration][][];
					for (int l = 0; l < config.duration; l++)
						symbols[l] = NrSignalAnalyzer.surveySymbol(inI, inQ,
								useful + l * SYMBOL_STEP, candidate.sample, omega,
								candidate.iqOrientation);
					double[] llr = candidateLlrs(symbols, candidate.pci, survey.slot,
							survey.coresetBin0, location[0], location[1], config, mapping);
					if (sum == null) sum = new double[llr.length];
					if (sum.length != llr.length) continue;
					for (int i = 0; i < sum.length; i++) sum[i] += llr[i];
					count++;
				}
				if (count < 2) continue;
				Dci dci = decodeDciLlrs(sum, candidate.pci, location, config.rb, listSize,
						mapping, debug);
				if (dci.valid) return dci;
			}
		}
		return Dci.EMPTY;
	}

	private static int[][] candidateLocations(CoreSetConfig config) {
		int cces = config.rb * config.duration / 6;
		int[] aggregations = { 1, 2, 4, 8 };
		int count = 0;
		for (int aggregation : aggregations)
			for (int ncce = 0; ncce + aggregation <= cces; ncce += aggregation) count++;
		int[][] result = new int[count][2];
		int at = 0;
		for (int aggregation : aggregations)
			for (int ncce = 0; ncce + aggregation <= cces; ncce += aggregation) {
				result[at][0] = aggregation;
				result[at][1] = ncce;
				at++;
			}
		return result;
	}

	private static Dci decodeDciLlrs(double[] llr, int pci, int[] location, int bwpRb,
			int listSize, int mapping, DecodeDebug debug) {
			for (int swap = 0; swap < 2; swap++) for (int sign = 1; sign >= -1; sign -= 2) {
				double[] ordered = new double[llr.length];
				for (int i = 0; i < llr.length / 2; i++) {
					ordered[2 * i] = sign * llr[2 * i + (swap == 0 ? 0 : 1)];
					ordered[2 * i + 1] = sign * llr[2 * i + (swap == 0 ? 1 : 0)];
				}
				double[] baseOrdered = ordered;
				for (int scrambleInit : pdcchScramblingInits(pci, listSize)) {
				ordered = baseOrdered.clone();
				int[] scramble = gold(scrambleInit, ordered.length);
				for (int i = 0; i < ordered.length; i++) ordered[i] *= 1 - 2 * scramble[i];
				for (int candidateBwpRb : dciBwpCandidates(bwpRb, listSize)) {
					for (int reservedBits : reservedBitsCandidates(listSize)) {
					int dciBits = frequencyAllocationBits(candidateBwpRb) + 13 + reservedBits + 24;
					int[][] decodedList = listSize > 0
							? NrPolarDecoder.decodeList(ordered, dciBits, listSize)
							: new int[][] { NrPolarDecoder.decode(ordered, dciBits) };
					for (int[] decoded : decodedList) {
						Dci dci = parseDci(decoded, location[0], location[1], swap, sign,
								candidateBwpRb, reservedBits, mapping, scrambleInit, debug);
						if (dci.valid) return dci;
						if (listSize < 0 && dciCrcWeight(decoded, candidateBwpRb, reservedBits) <= 4) {
							dci = parseDciWithBitCorrections(decoded, location[0], location[1],
									swap, sign, candidateBwpRb, reservedBits, mapping, scrambleInit);
							if (dci.valid) return dci;
						}
					}
					}
				}
				}
			}
		return Dci.EMPTY;
	}

	private static int[] pdcchScramblingInits(int pci, int listSize) {
		return listSize < 0 ? new int[] { pci, ((0xffff & 0xffff) << 15) + pci,
				(((0xffff & 0xffff) << 16) + pci) & 0x7fffffff }
				: new int[] { pci };
	}

	private static int[] dciBwpCandidates(int coresetRb, int listSize) {
		if (listSize < 0 && coresetRb == 24) return new int[] { 24, 48, 51, 52, 106 };
		return new int[] { coresetRb };
	}

	private static int[] reservedBitsCandidates(int listSize) {
		return listSize < 0 ? new int[] { 15, 17 } : new int[] { 15 };
	}

	private static double[] candidateLlrs(double[][][] symbols, int pci, int slot, int coresetBin0,
			int aggregation, int ncce, CoreSetConfig config, int mapping) {
		int bundles = config.rb * config.duration / 6;
		double[] output = new double[aggregation * 108];
		int at = 0;
		int[][] sequences = new int[config.duration][];
		double[][] channelR = new double[config.duration][config.rb * 3];
		double[][] channelI = new double[config.duration][config.rb * 3];
		for (int l = 0; l < config.duration; l++) {
			long cinitLong = ((1L << 17) * (SYMBOLS_PER_SLOT * slot + l + 1L)
					* (2L * pci + 1L) + 2L * pci) & 0x7fffffffL;
			sequences[l] = gold((int)cinitLong, config.rb * 6);
			for (int rb = 0; rb < config.rb; rb++) for (int pilot = 0; pilot < 3; pilot++) {
				int m = 3 * rb + pilot;
				double rr = (1 - 2 * sequences[l][2 * m]) / Math.sqrt(2d);
				double ri = (1 - 2 * sequences[l][2 * m + 1]) / Math.sqrt(2d);
				int bin = Math.floorMod(coresetBin0 + rb * 12 + 1 + pilot * 4, FFT);
				double yr = symbols[l][0][bin], yi = symbols[l][1][bin];
				channelR[l][m] = yr * rr + yi * ri;
				channelI[l][m] = yi * rr - yr * ri;
			}
			channelR[l] = smooth(channelR[l]);
			channelI[l] = smooth(channelI[l]);
		}
		for (int cce = ncce; cce < ncce + aggregation; cce++) {
			int bundle = regBundleForCce(cce, bundles, pci, mapping);
			for (int regOffset = 0; regOffset < 6; regOffset++) {
				int[] lr = regLocation(6 * bundle + regOffset, config, mapping);
				int l = lr[0], rb = lr[1];
				if (l < 0 || l >= config.duration || rb < 0 || rb >= config.rb) continue;
			for (int k = 0; k < 12; k++) {
				if ((k & 3) == 1) continue;
				double position = 3 * rb + (k - 1) / 4d;
				int last = config.rb * 3 - 1;
				int left = Math.max(0, Math.min(last, (int)Math.floor(position)));
				int right = Math.max(0, Math.min(last, left + 1));
				double fraction = Math.max(0, Math.min(1, position - left));
				double hr = channelR[l][left] * (1 - fraction) + channelR[l][right] * fraction;
				double hi = channelI[l][left] * (1 - fraction) + channelI[l][right] * fraction;
				double power = hr * hr + hi * hi;
				int bin = Math.floorMod(coresetBin0 + rb * 12 + k, FFT);
				double yr = symbols[l][0][bin], yi = symbols[l][1][bin];
				output[at++] = power < 1e-18 ? 0 : (yr * hr + yi * hi) / power;
				output[at++] = power < 1e-18 ? 0 : (yi * hr - yr * hi) / power;
			}
		}
		}
		if (at != output.length) {
			double[] trimmed = new double[output.length];
			System.arraycopy(output, 0, trimmed, 0, Math.min(at, output.length));
			return trimmed;
		}
		return output;
	}

	private static int regBundleForCce(int cce, int bundles, int pci, int mapping) {
		if (mapping >= 2) return cce % bundles;
		int columns = bundles / 2;
		return ((cce & 1) * columns + cce / 2 + pci) % bundles;
	}

	private static int[] regLocation(int reg, CoreSetConfig config, int mapping) {
		if ((mapping & 1) == 0) return new int[] { reg % config.duration, reg / config.duration };
		return new int[] { reg / config.rb, reg % config.rb };
	}

	private static double[] smooth(double[] input) {
		double[] output = new double[input.length];
		for (int i = 0; i < input.length; i++) {
			double sum = 2 * input[i];
			int weight = 2;
			if (i > 0) { sum += input[i - 1]; weight++; }
			if (i + 1 < input.length) { sum += input[i + 1]; weight++; }
			output[i] = sum / weight;
		}
		return output;
	}

	private static Dci parseDci(int[] decoded, int aggregation, int ncce, int qpskSwap, int qpskSign,
			int bwpRb, int reservedBits, int mapping, int scrambleInit, DecodeDebug debug) {
		int frequencyBits = frequencyAllocationBits(bwpRb);
		if (decoded == null || decoded.length != frequencyBits + 37 + reservedBits) return Dci.EMPTY;
		int[] unmasked = decoded.clone();
		for (int i = unmasked.length - 16; i < unmasked.length; i++) unmasked[i] ^= 1;
		int[] crcInput = new int[24 + unmasked.length];
		for (int i = 0; i < 24; i++) crcInput[i] = 1;
		System.arraycopy(unmasked, 0, crcInput, 24, unmasked.length);
		int crc = crc24c(crcInput);
		int crcWeight = Integer.bitCount(crc);
		int at = 0;
		int riv = read(unmasked, at, frequencyBits); at += frequencyBits;
		int time = read(unmasked, at, 4); at += 4;
		int vrb = unmasked[at++];
		int mcs = read(unmasked, at, 5); at += 5;
		int rv = read(unmasked, at, 2); at += 2;
		int sii = unmasked[at++];
		int reserved = read(unmasked, at, reservedBits);
		int[] allocation = decodeRiv(riv, bwpRb);
		if (debug != null) debug.record(crcWeight, aggregation, ncce, mapping, bwpRb,
				decoded.length, qpskSwap, qpskSign, scrambleInit, riv, allocation[0],
				allocation[1], time, vrb, mcs, rv, sii, reserved);
		if (crc != 0 || allocation[0] < 0 || sii != 0 || reserved != 0) return Dci.EMPTY;
		return new Dci(true, aggregation, ncce, riv, allocation[0], allocation[1], time, vrb,
				mcs, rv, sii, reserved, qpskSwap, qpskSign);
	}

	private static Dci parseDciWithBitCorrections(int[] decoded, int aggregation, int ncce,
			int qpskSwap, int qpskSign, int bwpRb, int reservedBits, int mapping, int scrambleInit) {
		for (int i = 0; i < decoded.length; i++) {
			int[] corrected = decoded.clone();
			corrected[i] ^= 1;
			Dci dci = parseDci(corrected, aggregation, ncce, qpskSwap, qpskSign,
					bwpRb, reservedBits, mapping, scrambleInit, null);
			if (dci.valid) return dci;
		}
		for (int i = 0; i < decoded.length; i++) for (int j = i + 1; j < decoded.length; j++) {
			int[] corrected = decoded.clone();
			corrected[i] ^= 1;
			corrected[j] ^= 1;
			Dci dci = parseDci(corrected, aggregation, ncce, qpskSwap, qpskSign,
					bwpRb, reservedBits, mapping, scrambleInit, null);
			if (dci.valid) return dci;
		}
		return Dci.EMPTY;
	}

	private static int dciCrcWeight(int[] decoded, int bwpRb, int reservedBits) {
		int frequencyBits = frequencyAllocationBits(bwpRb);
		if (decoded == null || decoded.length != frequencyBits + 37 + reservedBits) return 24;
		int[] unmasked = decoded.clone();
		for (int i = unmasked.length - 16; i < unmasked.length; i++) unmasked[i] ^= 1;
		int[] crcInput = new int[24 + unmasked.length];
		for (int i = 0; i < 24; i++) crcInput[i] = 1;
		System.arraycopy(unmasked, 0, crcInput, 24, unmasked.length);
		return Integer.bitCount(crc24c(crcInput));
	}

	private static int frequencyAllocationBits(int bwpRb) {
		int values = bwpRb * (bwpRb + 1) / 2;
		int bits = 0;
		while ((1 << bits) < values) bits++;
		return bits;
	}

	private static int[] decodeRiv(int riv, int bwpRb) {
		for (int length = 1; length <= bwpRb; length++) for (int start = 0;
				start + length <= bwpRb; start++) {
			int encoded = length - 1 <= bwpRb / 2
					? bwpRb * (length - 1) + start
					: bwpRb * (bwpRb - length + 1) + bwpRb - 1 - start;
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
		return crc24c(bits) == 0;
	}

	private static int crc24c(int[] bits) {
		int crc = 0;
		for (int bit : bits) {
			int feedback = ((crc >>> 23) & 1) ^ bit;
			crc = (crc << 1) & 0xffffff;
			if (feedback != 0) crc ^= 0xb2b117;
		}
		return crc;
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
		static final Survey EMPTY = new Survey(0, -1, -1, 0, 0, 0, 0, 0, Dci.EMPTY,
				NrPdschDecoder.Decoded.EMPTY, "");
		final double correlation;
		final int slot, usefulSample, coresetBin0, coresetRb, coresetDuration, pilots, pairs;
		final Dci dci;
		final NrPdschDecoder.Decoded pdsch;
		final String debug;
		Survey(double correlation, int slot, int usefulSample, int coresetBin0, int coresetRb,
				int coresetDuration, int pilots, int pairs,
				Dci dci, NrPdschDecoder.Decoded pdsch, String debug) {
			this.correlation = correlation;
			this.slot = slot;
			this.usefulSample = usefulSample;
			this.coresetBin0 = coresetBin0;
			this.coresetRb = coresetRb;
			this.coresetDuration = coresetDuration;
			this.pilots = pilots;
			this.pairs = pairs;
			this.dci = dci;
			this.pdsch = pdsch;
			this.debug = debug;
		}
		Survey withDci(Dci value) {
			return new Survey(correlation, slot, usefulSample, coresetBin0, coresetRb, coresetDuration,
					pilots, pairs, value, pdsch, debug);
		}
		Survey withPdsch(NrPdschDecoder.Decoded value) {
			return new Survey(correlation, slot, usefulSample, coresetBin0, coresetRb, coresetDuration,
					pilots, pairs, dci, value, debug);
		}
		Survey withDebug(String value) {
			return new Survey(correlation, slot, usefulSample, coresetBin0, coresetRb, coresetDuration,
					pilots, pairs, dci, pdsch, value == null ? "" : value);
		}
	}

	private static final class DecodeDebug {
		private final Entry[] entries = new Entry[8];
		void record(int crcWeight, int aggregation, int ncce, int mapping, int bwpRb, int bits,
				int swap, int sign, int scrambleInit, int riv, int rbStart, int rbLength, int time,
				int vrb, int mcs, int rv, int sii, int reserved) {
			Entry entry = new Entry(crcWeight, aggregation, ncce, mapping, bwpRb, bits, swap, sign,
					scrambleInit, riv, rbStart, rbLength, time, vrb, mcs, rv, sii, reserved);
			for (int i = 0; i < entries.length; i++) {
				if (entries[i] == null || entry.compareTo(entries[i]) < 0) {
					for (int j = entries.length - 1; j > i; j--) entries[j] = entries[j - 1];
					entries[i] = entry;
					return;
				}
			}
		}
		String summary() {
			StringBuilder text = new StringBuilder();
			for (Entry entry : entries) {
				if (entry == null) continue;
				if (text.length() > 0) text.append('\n');
				text.append(entry);
			}
			return text.toString();
		}
	}

	private static final class Entry implements Comparable<Entry> {
		final int crcWeight, aggregation, ncce, mapping, bwpRb, bits, swap, sign, scrambleInit;
		final int riv, rbStart, rbLength, time, vrb, mcs, rv, sii, reserved;
		Entry(int crcWeight, int aggregation, int ncce, int mapping, int bwpRb, int bits,
				int swap, int sign, int scrambleInit, int riv, int rbStart, int rbLength, int time,
				int vrb, int mcs, int rv, int sii, int reserved) {
			this.crcWeight = crcWeight;
			this.aggregation = aggregation;
			this.ncce = ncce;
			this.mapping = mapping;
			this.bwpRb = bwpRb;
			this.bits = bits;
			this.swap = swap;
			this.sign = sign;
			this.scrambleInit = scrambleInit;
			this.riv = riv;
			this.rbStart = rbStart;
			this.rbLength = rbLength;
			this.time = time;
			this.vrb = vrb;
			this.mcs = mcs;
			this.rv = rv;
			this.sii = sii;
			this.reserved = reserved;
		}
		@Override
		public int compareTo(Entry other) {
			int crc = Integer.compare(crcWeight, other.crcWeight);
			if (crc != 0) return crc;
			int reservedCompare = Integer.compare(Integer.bitCount(reserved), Integer.bitCount(other.reserved));
			if (reservedCompare != 0) return reservedCompare;
			return Integer.compare(bits, other.bits);
		}
		@Override
		public String toString() {
			return "crcW " + crcWeight + " AL" + aggregation + " nCCE" + ncce
					+ " map" + mapping + " BWP" + bwpRb + " K" + bits
					+ " swap" + swap + " sign" + (sign > 0 ? "+" : "") + sign
					+ " scr0x" + Integer.toHexString(scrambleInit)
					+ " RIV " + riv + " RB " + rbStart + "+" + rbLength
					+ " time " + time + " VRB " + vrb + " MCS " + mcs + " RV " + rv
					+ " SII " + sii + " res0x" + Integer.toHexString(reserved);
		}
	}

	private static final class CoreSetConfig {
		final int rb, duration, offsetRb;
		final int[] monitoringSlots;
		CoreSetConfig(int rb, int duration, int offsetRb, int[] monitoringSlots) {
			this.rb = rb;
			this.duration = duration;
			this.offsetRb = offsetRb;
			this.monitoringSlots = monitoringSlots;
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
