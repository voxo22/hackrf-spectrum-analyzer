package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lightweight 5G NR SSB acquisition: PSS/SSS cell search for FR1 15 kHz SCS captures. */
final class NrSignalAnalyzer {
	private static final int BASE_RATE = 7_680_000;
	private static final int FFT_SIZE = 512;
	private static final int SURVEY_RATE = 15_360_000;
	private static final int SURVEY_FFT = 1024;
	private static final int SURVEY_CP = 72;
	private static final int SURVEY_MAX_OFFSET = 260;
	private static final int SURVEY_CP_CANDIDATES = 1400;
	private static final int PSS_LENGTH = 127;
	private static final int[] CP_CANDIDATES = { 36, 40, 32, 44 };
	private static final int[] FREQ_OFFSETS = {
			0, -24, 24, -48, 48, -72, 72, -96, 96, -120, 120, -144, 144, -168, 168, -192, 192
	};
	private static final Map<Long, ResampleKernel> RESAMPLE_KERNELS = new LinkedHashMap<Long, ResampleKernel>();
	private static final double[][][][][] PSS_REFERENCES = buildPssReferences();
	private static final int[][] PSS_SEQUENCES = buildPssSequences();
	private static final int[][][] SSS_SEQUENCES = buildSssSequences();
	private static final double[][][] SURVEY_FILTERS = buildSurveyFilters();

	PssSurvey surveyPss(byte[] iq, int length, int sampleRateHz) {
		int inputSamples = Math.min(length, iq == null ? 0 : iq.length) / 2;
		if (sampleRateHz < 5_000_000 || inputSamples < sampleRateHz / 50) {
			return new PssSurvey(Collections.<PssSurveyCandidate>emptyList(), 0);
		}
		double[][] resampled = resample(iq, inputSamples, sampleRateHz, SURVEY_RATE);
		double[] inI = resampled[0], inQ = resampled[1];
		List<CpCandidate> cpCandidates = findCpCandidates(inI, inQ);
		List<PssSurveyCandidate> detected = new ArrayList<PssSurveyCandidate>();
		for (CpCandidate cp : cpCandidates) {
			for (int orientation = 0; orientation < 2; orientation++) {
				PssSurveyCandidate best = spectralPss(inI, inQ, cp, orientation);
				if (best != null) detected.add(best);
			}
		}
		Collections.sort(detected, (a, b) -> Double.compare(b.pssCorrelation, a.pssCorrelation));
		if (detected.size() > 300) detected = new ArrayList<PssSurveyCandidate>(detected.subList(0, 300));
		int period = SURVEY_RATE / 50;
		List<PssSurveyCandidate> confirmed = new ArrayList<PssSurveyCandidate>();
		for (PssSurveyCandidate candidate : detected) {
			int repeats = 1;
			double repeatSum = candidate.pssCorrelation;
			for (int multiple = 1; multiple <= 3; multiple++) {
				PssSurveyCandidate match = periodicMatch(detected, candidate, multiple * period);
				if (match != null) {
					repeats++;
					repeatSum += match.pssCorrelation;
				}
			}
			confirmed.add(candidate.withPeriodicity(repeats, repeatSum / repeats));
		}
		Collections.sort(confirmed, (a, b) -> {
			int repeatCompare = Integer.compare(b.periodicRepeats, a.periodicRepeats);
			if (repeatCompare != 0) return repeatCompare;
			return Double.compare(b.periodicCorrelation, a.periodicCorrelation);
		});
		List<PssSurveyCandidate> unique = new ArrayList<PssSurveyCandidate>();
		for (PssSurveyCandidate candidate : confirmed) {
			boolean duplicate = false;
			for (PssSurveyCandidate kept : unique) {
				if (candidate.nid2 == kept.nid2 && candidate.iqOrientation == kept.iqOrientation
						&& Math.abs(candidate.freqOffsetHz - kept.freqOffsetHz) <= 30_000
						&& Math.abs(Math.floorMod(candidate.sample - kept.sample, period)) <= 160) {
					duplicate = true;
					break;
				}
			}
			if (!duplicate) unique.add(candidate);
			if (unique.size() >= 12) break;
		}
		List<PssSurveyCandidate> decoded = new ArrayList<PssSurveyCandidate>();
		for (PssSurveyCandidate candidate : unique) decoded.add(decodeSurveySss(inI, inQ, candidate));
		Collections.sort(decoded, (a, b) -> {
			if ((a.pci >= 0) != (b.pci >= 0)) return a.pci >= 0 ? -1 : 1;
			int repeatCompare = Integer.compare(b.periodicRepeats, a.periodicRepeats);
			if (repeatCompare != 0) return repeatCompare;
			return Double.compare(b.sssCorrelation, a.sssCorrelation);
		});
		return new PssSurvey(Collections.unmodifiableList(decoded), cpCandidates.size());
	}

	private static PssSurveyCandidate decodeSurveySss(double[] inI, double[] inQ,
			PssSurveyCandidate candidate) {
		int bestNid1 = -1, bestDelta = 0, bestRepeats = 0;
		double best = 0, second = 0;
		int period = SURVEY_RATE / 50;
		int sssSpacing = 2 * (SURVEY_FFT + SURVEY_CP);
		double omega = candidate.cfoHz * 2d * Math.PI / SURVEY_RATE;
		for (int delta = -12; delta <= 12; delta++) {
			double[] scoreSum = new double[336];
			int repeats = 0;
			for (int repeat = -3; repeat <= 3; repeat++) {
				int pssAt = candidate.sample + repeat * period + delta;
				int sssAt = pssAt + sssSpacing;
				if (pssAt < 0 || sssAt + SURVEY_FFT > inI.length) continue;
				double[][] pssBins = surveyBins(inI, inQ, pssAt, candidate.sample, omega,
						candidate.iqOrientation, candidate.centerBin);
				double[][] sssBins = surveyBins(inI, inQ, sssAt, candidate.sample, omega,
						candidate.iqOrientation, candidate.centerBin);
				double[] zr = new double[PSS_LENGTH], zi = new double[PSS_LENGTH];
				double power = 0;
				int[] pss = PSS_SEQUENCES[candidate.nid2];
				for (int n = 0; n < PSS_LENGTH; n++) {
					double hr = pssBins[0][n] * pss[n], hi = pssBins[1][n] * pss[n];
					double sr = sssBins[0][n], si = sssBins[1][n];
					zr[n] = sr * hr + si * hi;
					zi[n] = si * hr - sr * hi;
					power += zr[n] * zr[n] + zi[n] * zi[n];
				}
				if (power <= 1e-18) continue;
				for (int nid1 = 0; nid1 < 336; nid1++) {
					double cr = 0, ci = 0;
					int[] reference = SSS_SEQUENCES[candidate.nid2][nid1];
					for (int n = 0; n < PSS_LENGTH; n++) {
						cr += zr[n] * reference[n];
						ci += zi[n] * reference[n];
					}
					scoreSum[nid1] += Math.hypot(cr, ci) / Math.sqrt(power * PSS_LENGTH);
				}
				repeats++;
			}
			if (repeats == 0) continue;
			double localBest = 0, localSecond = 0;
			int localNid1 = -1;
			for (int nid1 = 0; nid1 < 336; nid1++) {
				double score = scoreSum[nid1] / repeats;
				if (score > localBest) {
					localSecond = localBest;
					localBest = score;
					localNid1 = nid1;
				} else if (score > localSecond) {
					localSecond = score;
				}
			}
			if (localBest > best) {
				best = localBest;
				second = localSecond;
				bestNid1 = localNid1;
				bestDelta = delta;
				bestRepeats = repeats;
			}
		}
		int pci = best >= 0.45 && best >= second * 1.35 ? 3 * bestNid1 + candidate.nid2 : -1;
		PssSurveyCandidate withSss = candidate.withSss(pci, bestNid1, best, second, bestDelta, bestRepeats);
		return pci >= 0 ? decodePbchDmrs(inI, inQ, withSss) : withSss;
	}

	private static PssSurveyCandidate decodePbchDmrs(double[] inI, double[] inQ,
			PssSurveyCandidate candidate) {
		double[] sums = new double[8];
		int[] counts = new int[8];
		double[][][] references = new double[8][][];
		for (int iBarSsb = 0; iBarSsb < 8; iBarSsb++) references[iBarSsb] = pbchDmrs(candidate.pci, iBarSsb);
		int period = SURVEY_RATE / 50;
		double omega = candidate.cfoHz * 2d * Math.PI / SURVEY_RATE;
		for (int repeat = -3; repeat <= 3; repeat++) {
			int pssAt = candidate.sample + repeat * period + candidate.sssTimingDelta;
			if (pssAt < 0 || pssAt + 3 * (SURVEY_FFT + SURVEY_CP) + SURVEY_FFT > inI.length) continue;
			double[][][] symbols = new double[3][][];
			for (int symbol = 1; symbol <= 3; symbol++) {
				symbols[symbol - 1] = surveySymbol(inI, inQ,
						pssAt + symbol * (SURVEY_FFT + SURVEY_CP), candidate.sample, omega,
						candidate.iqOrientation);
			}
			for (int iBarSsb = 0; iBarSsb < 8; iBarSsb++) {
				double[][] reference = references[iBarSsb];
				int sequence = 0;
				double scoreSum = 0;
				int scoreGroups = 0;
				for (int symbol : new int[] { 1, 2, 3 }) {
					int[][] ranges = symbol == 2 ? new int[][] { { 0, 47 }, { 192, 239 } }
							: new int[][] { { 0, 239 } };
					for (int[] range : ranges) {
						double previousR = 0, previousI = 0;
						double cr = 0, ci = 0, previousPower = 0, currentPower = 0;
						int pairs = 0;
						for (int k = (candidate.pci & 3); k <= 239; k += 4) {
							if (k < range[0] || k > range[1]) continue;
							int bin = Math.floorMod(candidate.centerBin + k - 119, SURVEY_FFT);
							double yr = symbols[symbol - 1][0][bin], yi = symbols[symbol - 1][1][bin];
							double rr = reference[0][sequence], ri = reference[1][sequence++];
							double hr = yr * rr + yi * ri, hi = yi * rr - yr * ri;
							if (pairs > 0) {
								cr += hr * previousR + hi * previousI;
								ci += hi * previousR - hr * previousI;
								previousPower += previousR * previousR + previousI * previousI;
								currentPower += hr * hr + hi * hi;
							}
							previousR = hr;
							previousI = hi;
							pairs++;
						}
						if (pairs > 1 && previousPower > 1e-18 && currentPower > 1e-18) {
							scoreSum += Math.hypot(cr, ci) / Math.sqrt(previousPower * currentPower);
							scoreGroups++;
						}
					}
				}
				if (sequence == 144 && scoreGroups > 0) {
					sums[iBarSsb] += scoreSum / scoreGroups;
					counts[iBarSsb]++;
				}
			}
		}
		double best = 0, second = 0;
		int bestIBar = -1, bestBursts = 0;
		for (int iBarSsb = 0; iBarSsb < 8; iBarSsb++) {
			double score = counts[iBarSsb] == 0 ? 0 : sums[iBarSsb] / counts[iBarSsb];
			if (score > best) {
				second = best;
				best = score;
				bestIBar = iBarSsb;
				bestBursts = counts[iBarSsb];
			} else if (score > second) {
				second = score;
			}
		}
		boolean confirmed = best >= 0.35 && best >= second * 1.30;
		PssSurveyCandidate result = candidate.withDmrs(confirmed, bestIBar, best, second, bestBursts);
		if (!confirmed) return result;
		NrPbchDecoder.Decoded bestPbch = NrPbchDecoder.Decoded.EMPTY;
		for (int repeat = -3; repeat <= 3; repeat++) {
			int pssAt = candidate.sample + repeat * period + candidate.sssTimingDelta;
			if (pssAt < 0 || pssAt + 3 * (SURVEY_FFT + SURVEY_CP) + SURVEY_FFT > inI.length) continue;
			double[][][] symbols = new double[3][][];
			for (int symbol = 1; symbol <= 3; symbol++) {
				symbols[symbol - 1] = surveySymbol(inI, inQ,
						pssAt + symbol * (SURVEY_FFT + SURVEY_CP), candidate.sample, omega,
						candidate.iqOrientation);
			}
			NrPbchDecoder.Decoded decoded = NrPbchDecoder.decode(symbols, candidate.pci, bestIBar,
					candidate.centerBin);
			if (bestPbch == NrPbchDecoder.Decoded.EMPTY || decoded.crcOk || decoded.evm < bestPbch.evm) {
				bestPbch = decoded;
			}
			if (decoded.crcOk) break;
		}
		result = result.withPbch(bestPbch);
		if (bestPbch.mib.valid) {
			result = result.withPdcch(NrPdcchDecoder.survey(inI, inQ, result, bestPbch.mib));
			result.load = estimateDownlinkLoad(inI, inQ, result);
		}
		return result;
	}

	private static LoadData estimateDownlinkLoad(double[] inI, double[] inQ,
			PssSurveyCandidate cell) {
		NrPdcchDecoder.Survey control = cell.pdcch;
		if (control.slot < 0 || control.usefulSample < 0) return LoadData.EMPTY;
		final int rbCount = 48, rbBins = 12, slotSamples = 15_360;
		double omega = cell.cfoHz * 2d * Math.PI / SURVEY_RATE;
		int symbols = 0;
		double[][] prbPower = new double[128][rbCount];
		for (int slotOffset = -12; slotOffset <= 12; slotOffset++) {
			int slotUseful = control.usefulSample + slotOffset * slotSamples;
			for (int symbol = 3; symbol < 14; symbol += 3) {
				int useful = slotUseful + nrSymbolUsefulOffset(symbol);
				if (useful < 0 || useful + SURVEY_FFT > inI.length) continue;
				double[][] bins = surveySymbol(inI, inQ, useful, cell.sample, omega, cell.iqOrientation);
				if (symbols >= prbPower.length) continue;
				for (int rb = 0; rb < rbCount; rb++) {
					double power = 0;
					for (int k = 0; k < rbBins; k++) {
						int bin = Math.floorMod(control.coresetBin0 + rb * rbBins + k, SURVEY_FFT);
						power += bins[0][bin] * bins[0][bin] + bins[1][bin] * bins[1][bin];
					}
					double meanPower = power / rbBins;
					prbPower[symbols][rb] = meanPower;
				}
				symbols++;
			}
		}
		int active = 0, total = symbols * rbCount;
		for (int rb = 0; rb < rbCount; rb++) {
			double[] history = new double[symbols];
			for (int symbol = 0; symbol < symbols; symbol++) history[symbol] = prbPower[symbol][rb];
			java.util.Arrays.sort(history);
			double idleFloor = history.length == 0 ? 0 : history[history.length / 4];
			double threshold = Math.max(1e-12, idleFloor * 3.5d);
			for (int symbol = 0; symbol < symbols; symbol++)
				if (prbPower[symbol][rb] > threshold) active++;
		}
		return total == 0 ? LoadData.EMPTY
				: new LoadData(true, active, total, symbols, 100d * active / total);
	}

	private static int nrSymbolUsefulOffset(int symbol) {
		return symbol * 1096 + (symbol >= 7 ? 8 : 0);
	}

	static double[][] surveySymbol(double[] inI, double[] inQ, int sample, int phaseReference,
			double omega, int iqOrientation) {
		double[] re = new double[SURVEY_FFT], im = new double[SURVEY_FFT];
		for (int n = 0; n < SURVEY_FFT; n++) {
			double phase = -omega * (sample + n - phaseReference);
			double ca = Math.cos(phase), sa = Math.sin(phase);
			double ar = inI[sample + n], ai = inQ[sample + n];
			re[n] = ar * ca - ai * sa;
			double correctedI = ar * sa + ai * ca;
			im[n] = iqOrientation > 0 ? correctedI : -correctedI;
		}
		fft(re, im, false);
		return new double[][] { re, im };
	}

	private static double[][] pbchDmrs(int pci, int iBarSsb) {
		int cinit = (1 << 11) * (iBarSsb + 1) * (pci / 4 + 1)
				+ (1 << 6) * (iBarSsb + 1) + (pci & 3);
		int[] sequence = gold(cinit, 288);
		double[][] result = new double[2][144];
		double scale = 1d / Math.sqrt(2d);
		for (int m = 0; m < 144; m++) {
			result[0][m] = (1 - 2 * sequence[2 * m]) * scale;
			result[1][m] = (1 - 2 * sequence[2 * m + 1]) * scale;
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

	private static double[][] surveyBins(double[] inI, double[] inQ, int sample, int phaseReference,
			double omega, int iqOrientation, int centerBin) {
		double[] re = new double[SURVEY_FFT], im = new double[SURVEY_FFT];
		for (int n = 0; n < SURVEY_FFT; n++) {
			double phase = -omega * (sample + n - phaseReference);
			double ca = Math.cos(phase), sa = Math.sin(phase);
			double ar = inI[sample + n], ai = inQ[sample + n];
			re[n] = ar * ca - ai * sa;
			double correctedI = ar * sa + ai * ca;
			im[n] = iqOrientation > 0 ? correctedI : -correctedI;
		}
		fft(re, im, false);
		double[][] result = new double[2][PSS_LENGTH];
		for (int n = 0; n < PSS_LENGTH; n++) {
			int bin = Math.floorMod(centerBin + n - 63, SURVEY_FFT);
			result[0][n] = re[bin];
			result[1][n] = im[bin];
		}
		return result;
	}

	private static List<CpCandidate> findCpCandidates(double[] inI, double[] inQ) {
		List<CpCandidate> blocks = new ArrayList<CpCandidate>();
		int first = SURVEY_CP, last = inI.length - SURVEY_FFT - 1;
		if (last <= first) return blocks;
		double cr = 0, ci = 0, pa = 0, pb = 0;
		for (int n = 0; n < SURVEY_CP; n++) {
			int a = first - SURVEY_CP + n, b = first + SURVEY_FFT - SURVEY_CP + n;
			double ar = inI[a], ai = inQ[a], br = inI[b], bi = inQ[b];
			cr += ar * br + ai * bi;
			ci += ai * br - ar * bi;
			pa += ar * ar + ai * ai;
			pb += br * br + bi * bi;
		}
		final int blockSize = 128;
		CpCandidate blockBest = null;
		for (int useful = first; useful <= last; useful++) {
			double metric = Math.hypot(cr, ci) / Math.sqrt(Math.max(1e-18, pa * pb));
			double omega = -Math.atan2(ci, cr) / SURVEY_FFT;
			CpCandidate current = new CpCandidate(useful, metric, omega);
			if (blockBest == null || current.cpCorrelation > blockBest.cpCorrelation) blockBest = current;
			if ((useful - first + 1) % blockSize == 0) {
				blocks.add(blockBest);
				blockBest = null;
			}
			if (useful == last) break;
			int oldA = useful - SURVEY_CP, oldB = useful + SURVEY_FFT - SURVEY_CP;
			int newA = useful, newB = useful + SURVEY_FFT;
			double ar = inI[oldA], ai = inQ[oldA], br = inI[oldB], bi = inQ[oldB];
			cr -= ar * br + ai * bi;
			ci -= ai * br - ar * bi;
			pa -= ar * ar + ai * ai;
			pb -= br * br + bi * bi;
			ar = inI[newA]; ai = inQ[newA]; br = inI[newB]; bi = inQ[newB];
			cr += ar * br + ai * bi;
			ci += ai * br - ar * bi;
			pa += ar * ar + ai * ai;
			pb += br * br + bi * bi;
		}
		if (blockBest != null) blocks.add(blockBest);
		Collections.sort(blocks, (a, b) -> Double.compare(b.cpCorrelation, a.cpCorrelation));
		List<CpCandidate> result = new ArrayList<CpCandidate>();
		for (CpCandidate candidate : blocks) {
			boolean near = false;
			for (CpCandidate kept : result) if (Math.abs(candidate.sample - kept.sample) < 256) {
				near = true;
				break;
			}
			if (!near) result.add(candidate);
			if (result.size() >= SURVEY_CP_CANDIDATES) break;
		}
		return result;
	}

	private static PssSurveyCandidate spectralPss(double[] inI, double[] inQ, CpCandidate cp, int orientation) {
		double[] binsR = new double[SURVEY_FFT], binsI = new double[SURVEY_FFT];
		for (int n = 0; n < SURVEY_FFT; n++) {
			double phase = -cp.omega * n, ca = Math.cos(phase), sa = Math.sin(phase);
			double ar = inI[cp.sample + n], ai = inQ[cp.sample + n];
			double re = ar * ca - ai * sa, im = ar * sa + ai * ca;
			binsR[n] = re;
			binsI[n] = orientation == 0 ? im : -im;
		}
		fft(binsR, binsI, false);
		double[] transformedR = binsR.clone(), transformedI = binsI.clone();
		fft(transformedR, transformedI, false);
		double best = 0;
		int bestNid2 = -1, bestCenter = 0;
		for (int nid2 = 0; nid2 < 3; nid2++) {
			double[] corrR = new double[SURVEY_FFT], corrI = new double[SURVEY_FFT];
			for (int n = 0; n < SURVEY_FFT; n++) {
				double ar = transformedR[n], ai = transformedI[n];
				double br = SURVEY_FILTERS[nid2][0][n], bi = SURVEY_FILTERS[nid2][1][n];
				corrR[n] = ar * br - ai * bi;
				corrI[n] = ar * bi + ai * br;
			}
			fft(corrR, corrI, true);
			for (int center = -SURVEY_MAX_OFFSET; center <= SURVEY_MAX_OFFSET; center++) {
				double power = 0;
				for (int d = -63; d <= 63; d++) {
					int bin = Math.floorMod(center + d, SURVEY_FFT);
					power += binsR[bin] * binsR[bin] + binsI[bin] * binsI[bin];
				}
				if (power <= 1e-18) continue;
				int bin = Math.floorMod(center, SURVEY_FFT);
				double score = Math.hypot(corrR[bin], corrI[bin]) / Math.sqrt(power * PSS_LENGTH);
				if (score > best) {
					best = score;
					bestNid2 = nid2;
					bestCenter = center;
				}
			}
		}
		if (bestNid2 < 0) return null;
		double cfoHz = cp.omega * SURVEY_RATE / (2d * Math.PI);
		int frequency = (int)Math.round((orientation == 0 ? bestCenter : -bestCenter) * 15_000d + cfoHz);
		return new PssSurveyCandidate(bestNid2, best, cp.cpCorrelation, cp.sample, frequency,
				orientation == 0 ? 1 : -1, cfoHz, 1, best, bestCenter,
				-1, -1, 0, 0, 0, 0, false, -1, 0, 0, 0,
				false, 0, MibData.EMPTY, NrPdcchDecoder.Survey.EMPTY);
	}

	private static PssSurveyCandidate periodicMatch(List<PssSurveyCandidate> candidates,
			PssSurveyCandidate anchor, int distance) {
		PssSurveyCandidate best = null;
		for (int direction : new int[] { -1, 1 }) {
			int expected = anchor.sample + direction * distance;
			for (PssSurveyCandidate candidate : candidates) {
				if (candidate == anchor || candidate.nid2 != anchor.nid2
						|| candidate.iqOrientation != anchor.iqOrientation) continue;
				if (Math.abs(candidate.sample - expected) > 180
						|| Math.abs(candidate.freqOffsetHz - anchor.freqOffsetHz) > 30_000) continue;
				if (best == null || candidate.pssCorrelation > best.pssCorrelation) best = candidate;
			}
		}
		return best;
	}

	private static double[][][] buildSurveyFilters() {
		double[][][] result = new double[3][2][SURVEY_FFT];
		for (int nid2 = 0; nid2 < 3; nid2++) {
			for (int d = -63; d <= 63; d++) {
				result[nid2][0][Math.floorMod(-d, SURVEY_FFT)] = PSS_SEQUENCES[nid2][d + 63];
			}
			fft(result[nid2][0], result[nid2][1], false);
		}
		return result;
	}

	private static void fft(double[] real, double[] imag, boolean inverse) {
		int size = real.length;
		for (int i = 1, j = 0; i < size; i++) {
			int bit = size >> 1;
			for (; (j & bit) != 0; bit >>= 1) j ^= bit;
			j ^= bit;
			if (i < j) {
				double value = real[i]; real[i] = real[j]; real[j] = value;
				value = imag[i]; imag[i] = imag[j]; imag[j] = value;
			}
		}
		for (int length = 2; length <= size; length <<= 1) {
			double angle = (inverse ? 2d : -2d) * Math.PI / length;
			double stepR = Math.cos(angle), stepI = Math.sin(angle);
			for (int start = 0; start < size; start += length) {
				double wr = 1, wi = 0;
				for (int n = 0; n < length / 2; n++) {
					int a = start + n, b = a + length / 2;
					double br = real[b] * wr - imag[b] * wi;
					double bi = real[b] * wi + imag[b] * wr;
					real[b] = real[a] - br; imag[b] = imag[a] - bi;
					real[a] += br; imag[a] += bi;
					double nextR = wr * stepR - wi * stepI;
					wi = wr * stepI + wi * stepR; wr = nextR;
				}
			}
		}
		if (inverse) for (int n = 0; n < size; n++) {
			real[n] /= size;
			imag[n] /= size;
		}
	}

	Result analyze(byte[] iq, int length, int sampleRateHz) {
		PssSurvey survey = surveyPss(iq, length, sampleRateHz);
		if (survey.candidates.isEmpty()) return Result.empty("Searching 5G NR PSS");
		List<Candidate> candidates = new ArrayList<Candidate>();
		for (PssSurveyCandidate value : survey.candidates) {
			int rawPci = value.rawNid1 >= 0 ? 3 * value.rawNid1 + value.nid2 : -1;
			candidates.add(new Candidate(value.pci, value.pci >= 0 ? value.rawNid1 : -1, value.nid2,
					rawPci, value.rawNid1, value.nid2, value.pssCorrelation, value.sssCorrelation,
					value.sample / 2, value.freqOffsetHz, value.iqOrientation,
					(SURVEY_CP + value.sssTimingDelta) / 2, value.cfoHz));
		}
		Candidate primary = candidates.get(0);
		PssSurveyCandidate surveyPrimary = survey.candidates.get(0);
		boolean detected = primary.pci >= 0;
		double quality = detected && surveyPrimary.dmrsConfirmed
				? Math.min(100, 34d * primary.pssCorrelation + 33d * primary.sssCorrelation
						+ 33d * surveyPrimary.dmrsCorrelation)
				: detected ? Math.min(90, 45d * primary.pssCorrelation + 45d * primary.sssCorrelation)
				: Math.min(45, primary.pssCorrelation * 100d);
		Sib1Data sib1 = surveyPrimary.pdcch.pdsch.transportBlock.crcOk
				? NrSib1Decoder.decode(surveyPrimary.pdcch.pdsch.transportBlock.payload) : Sib1Data.EMPTY;
		String state = sib1.valid ? "5G NR SIB1 decoded"
				: surveyPrimary.mib.valid ? "5G NR MIB decoded"
				: detected && surveyPrimary.dmrsConfirmed ? "5G NR PCI confirmed by PBCH DM-RS"
				: detected ? "5G NR PSS + SSS decoded"
				: primary.pssCorrelation >= 0.45 && survey.candidates.get(0).periodicRepeats >= 2
						? "5G NR PSS detected" : "Possible 5G NR PSS";
		return new Result(detected, primary.pci, primary.nid1, primary.nid2,
				primary.pssCorrelation, primary.sssCorrelation, primary.sample, primary.freqOffsetHz,
				primary.iqOrientation, quality, state, Collections.unmodifiableList(candidates),
				surveyPrimary.dmrsConfirmed, surveyPrimary.dmrsCorrelation,
				surveyPrimary.secondDmrsCorrelation, surveyPrimary.iBarSsb, surveyPrimary.dmrsBursts,
				surveyPrimary.mib, sib1, surveyPrimary.load);
	}

	@SuppressWarnings("unused")
	private Result analyzeLegacy(byte[] iq, int length, int sampleRateHz) {
		int inputSamples = Math.min(length, iq == null ? 0 : iq.length) / 2;
		if (sampleRateHz < 5_000_000 || inputSamples < sampleRateHz / 100) {
			return Result.empty("Need >= 5 MS/s and 10 ms of 5G NR IQ");
		}
		double[][] resampled = resample(iq, inputSamples, sampleRateHz, BASE_RATE);
		double[] inI = resampled[0], inQ = resampled[1];
		int samples = inI.length;
		if (samples < FFT_SIZE * 8) {
			return Result.empty("5G NR PSS sampling geometry unavailable");
		}
		int step = 32;
		int acquisitionSamples = Math.min(samples, BASE_RATE / 25);
		List<Peak> peaks = new ArrayList<Peak>();
		List<Candidate> candidates = new ArrayList<Candidate>();
		Peak strongestPss = null;
		for (int freqOffset : FREQ_OFFSETS) {
			for (int nid2 = 0; nid2 < 3; nid2++) {
				for (int orientation = 0; orientation < 2; orientation++) for (boolean reversed : new boolean[] { false }) {
					Peak peak = findPeak(inI, inQ, nid2, freqOffset, orientation, reversed, acquisitionSamples, step);
					if (peak == null) continue;
					if (strongestPss == null || peak.pssCorrelation > strongestPss.pssCorrelation) strongestPss = peak;
					if (peak.pssCorrelation >= 0.13) peaks.add(peak);
				}
			}
		}
		Collections.sort(peaks, (a, b) -> Double.compare(b.pssCorrelation, a.pssCorrelation));
		List<Peak> uniquePeaks = new ArrayList<Peak>();
		for (Peak coarsePeak : peaks) {
			Peak peak = refinePeak(inI, inQ, coarsePeak);
			boolean duplicate = false;
			for (Peak kept : uniquePeaks) {
				if (peak.nid2 == kept.nid2 && peak.iqOrientation == kept.iqOrientation
						&& peak.reversed == kept.reversed
						&& Math.abs(peak.freqOffsetBins - kept.freqOffsetBins) <= 8
						&& Math.abs(peak.sample - kept.sample) <= 96) {
					duplicate = true;
					break;
				}
			}
			if (!duplicate) uniquePeaks.add(peak);
			if (uniquePeaks.size() >= 4) break;
		}
		for (Peak peak : uniquePeaks) candidates.add(decodeSss(inI, inQ, peak));
		Collections.sort(candidates, (a, b) -> {
			if ((a.pci >= 0) != (b.pci >= 0)) return a.pci >= 0 ? -1 : 1;
			int scoreCompare = Double.compare(b.score(), a.score());
			if (scoreCompare != 0) return scoreCompare;
			return Double.compare(b.pssCorrelation, a.pssCorrelation);
		});
		List<Candidate> unique = new ArrayList<Candidate>();
		for (Candidate candidate : candidates) {
			boolean duplicate = false;
			for (Candidate kept : unique) {
				if (candidate.pci >= 0 && candidate.pci == kept.pci) {
					duplicate = true;
					if (candidate.score() > kept.score()) {
						unique.remove(kept);
						unique.add(candidate);
					}
					break;
				}
			}
			if (!duplicate) unique.add(candidate);
			Collections.sort(unique, (a, b) -> Double.compare(b.score(), a.score()));
			if (unique.size() > 8) unique.remove(unique.size() - 1);
		}
		Candidate primary = unique.isEmpty() ? null : unique.get(0);
		double pss = primary != null ? primary.pssCorrelation : strongestPss == null ? 0 : strongestPss.pssCorrelation;
		double sss = primary == null ? 0 : primary.sssCorrelation;
		boolean detected = primary != null && primary.pci >= 0 && sss >= 0.32;
		double quality = detected ? Math.min(100, 100d * (0.45d * pss / 0.45d + 0.55d * sss / 0.75d))
				: Math.min(45, pss * 180d);
		String state = detected ? "5G NR PSS + SSS decoded" : pss >= 0.16 ? "5G NR PSS detected"
				: pss >= 0.10 ? "Possible 5G NR PSS" : "Searching 5G NR PSS";
		return new Result(detected, primary == null ? -1 : primary.pci, primary == null ? -1 : primary.nid1,
				primary == null ? strongestPss == null ? -1 : strongestPss.nid2 : primary.nid2,
				pss, sss, primary == null ? -1 : primary.sample, primary == null ? 0 : primary.freqOffsetHz,
				primary == null ? 1 : primary.iqOrientation, quality, state, Collections.unmodifiableList(unique),
				false, 0, 0, -1, 0, MibData.EMPTY, Sib1Data.EMPTY, LoadData.EMPTY);
	}

	private static Peak findPeak(double[] inI, double[] inQ, int nid2, int freqOffset, int orientation, boolean reversed,
			int samples, int step) {
		double best = 0;
		int bestAt = -1;
		for (int at = 0; at + FFT_SIZE <= samples; at += step) {
			double score = correlation(inI, inQ, PSS_REFERENCES[freqIndex(freqOffset)][nid2][reversed ? 1 : 0],
					orientation, at);
			if (score > best) {
				best = score;
				bestAt = at;
			}
		}
		if (bestAt < 0) return null;
		for (int at = Math.max(0, bestAt - step); at <= Math.min(inI.length - FFT_SIZE, bestAt + step); at++) {
			double score = correlation(inI, inQ, PSS_REFERENCES[freqIndex(freqOffset)][nid2][reversed ? 1 : 0],
					orientation, at);
			if (score > best) {
				best = score;
				bestAt = at;
			}
		}
		double cfoHz = cyclicPrefixOffset(inI, inQ, bestAt, orientation) * BASE_RATE / (2d * Math.PI);
		if (orientation != 0) cfoHz = -cfoHz;
		return new Peak(nid2, best, bestAt, freqOffset, orientation == 0 ? 1 : -1, reversed, cfoHz);
	}

	private static Peak refinePeak(double[] inI, double[] inQ, Peak coarse) {
		double best = coarse.pssCorrelation;
		int bestAt = coarse.sample;
		double bestFreq = coarse.freqOffsetBins;
		int orientation = coarse.iqOrientation > 0 ? 0 : 1;
		for (double freq = coarse.freqOffsetBins - 24; freq <= coarse.freqOffsetBins + 24; freq += 0.5d) {
			double[][] reference = reference(PSS_SEQUENCES[coarse.nid2], freq, coarse.reversed);
			for (int at = Math.max(0, coarse.sample - 16);
					at <= Math.min(inI.length - FFT_SIZE, coarse.sample + 16); at++) {
				double score = correlation(inI, inQ, reference, orientation, at);
				if (score > best) {
					best = score;
					bestAt = at;
					bestFreq = freq;
				}
			}
		}
		double cfoHz = cyclicPrefixOffset(inI, inQ, bestAt, orientation) * BASE_RATE / (2d * Math.PI);
		if (orientation != 0) cfoHz = -cfoHz;
		return new Peak(coarse.nid2, best, bestAt, bestFreq, coarse.iqOrientation, coarse.reversed, cfoHz);
	}

	private static int refineAt(double[] inI, double[] inQ, double[][] reference, int orientation, int expected,
			int radius) {
		int bestAt = Math.max(0, Math.min(inI.length - FFT_SIZE, expected));
		double best = -1;
		for (int at = Math.max(0, expected - radius); at <= Math.min(inI.length - FFT_SIZE, expected + radius); at++) {
			double score = correlation(inI, inQ, reference, orientation, at);
			if (score > best) {
				best = score;
				bestAt = at;
			}
		}
		return bestAt;
	}

	private static Candidate decodeSss(double[] inI, double[] inQ, Peak peak) {
		int bestPci = -1, bestNid1 = -1, bestCp = 0;
		double best = 0;
		double[][] pssReference = reference(PSS_SEQUENCES[peak.nid2], peak.freqOffsetBins, peak.reversed);
		int orientation = peak.iqOrientation > 0 ? 0 : 1;
		int period = BASE_RATE / 50;
		for (int cp : CP_CANDIDATES) {
			int nominal = peak.sample + 2 * FFT_SIZE + 2 * cp;
			for (int delta = -48; delta <= 48; delta += 8) {
				double[] accR = new double[1008], accI = new double[1008];
				double[] directR = new double[1008], directI = new double[1008];
				double power = 0, directPower = 0;
				int repeats = 0;
				for (int repeat = -4; repeat <= 4; repeat++) {
					int expectedPss = peak.sample + repeat * period;
					if (expectedPss < 0 || expectedPss + 3 * FFT_SIZE > inI.length) continue;
					int localPss = refineAt(inI, inQ, pssReference, orientation, expectedPss, 24);
					double localScore = correlation(inI, inQ, pssReference, orientation, localPss);
					if (localScore < 0.12) continue;
					int sssAt = localPss + (nominal - peak.sample) + delta;
					if (sssAt < 0 || sssAt + FFT_SIZE > inI.length) continue;
					double[][] pssBins = extractBins(inI, inQ, localPss, peak.freqOffsetBins, peak.iqOrientation < 0,
							peak.reversed);
					double[][] sssBins = extractBins(inI, inQ, sssAt, peak.freqOffsetBins, peak.iqOrientation < 0,
							peak.reversed);
					double[] equalized = equalizeSss(pssBins, sssBins, peak.nid2);
					for (int n = 0; n < PSS_LENGTH; n++) {
						power += equalized[2 * n] * equalized[2 * n]
								+ equalized[2 * n + 1] * equalized[2 * n + 1];
						directPower += sssBins[0][n] * sssBins[0][n] + sssBins[1][n] * sssBins[1][n];
					}
					int sssNid2 = peak.nid2;
					for (int nid1 = 0; nid1 < 336; nid1++) {
						int index = sssNid2 * 336 + nid1;
						int[] ref = SSS_SEQUENCES[sssNid2][nid1];
						double cr = 0, ci = 0;
						for (int n = 0; n < PSS_LENGTH; n++) {
							cr += equalized[2 * n] * ref[n];
							ci += equalized[2 * n + 1] * ref[n];
						}
						accR[index] += cr;
						accI[index] += ci;
						cr = 0;
						ci = 0;
						for (int n = 0; n < PSS_LENGTH; n++) {
							cr += sssBins[0][n] * ref[n];
							ci += sssBins[1][n] * ref[n];
						}
						directR[index] += cr;
						directI[index] += ci;
					}
					repeats++;
				}
				if (repeats == 0 || power <= 1e-12) continue;
				double norm = Math.sqrt(power * PSS_LENGTH * repeats);
				double directNorm = Math.sqrt(Math.max(1e-12, directPower * PSS_LENGTH * repeats));
				int sssNid2 = peak.nid2;
				for (int nid1 = 0; nid1 < 336; nid1++) {
					int index = sssNid2 * 336 + nid1;
					double score = Math.max(Math.hypot(accR[index], accI[index]) / norm,
							Math.hypot(directR[index], directI[index]) / directNorm);
					if (score > best) {
						best = score;
						bestNid1 = nid1;
						bestPci = 3 * nid1 + sssNid2;
						bestCp = cp + delta;
					}
				}
			}
		}
		int rawPci = bestPci, rawNid1 = bestNid1, rawNid2 = bestPci >= 0 ? bestPci % 3 : peak.nid2;
		int decodedPci = best >= 0.32 ? bestPci : -1;
		int decodedNid1 = best >= 0.32 ? bestNid1 : -1;
		return new Candidate(decodedPci, decodedNid1, decodedPci >= 0 ? decodedPci % 3 : peak.nid2,
				rawPci, rawNid1, rawNid2, peak.pssCorrelation, best, peak.sample,
				(int)Math.round(peak.freqOffsetBins * 15_000d), peak.iqOrientation, bestCp, peak.cfoHz);
	}

	ExpectedScore scoreExpectedPci(byte[] iq, int length, int sampleRateHz, int expectedPci) {
		return scoreExpectedPci(iq, length, sampleRateHz, expectedPci, null);
	}

	ExpectedScore scoreExpectedPci(byte[] iq, int length, int sampleRateHz, int expectedPci, Result acquired) {
		if (expectedPci < 0 || expectedPci > 1007) {
			return new ExpectedScore(expectedPci, 0, 0, -1, -1, -1, 0, 1, 0, false);
		}
		if (acquired == null) acquired = analyze(iq, length, sampleRateHz);
		int inputSamples = Math.min(length, iq == null ? 0 : iq.length) / 2;
		if (sampleRateHz < 5_000_000 || inputSamples < sampleRateHz / 100 || acquired.candidates.isEmpty()) {
			return new ExpectedScore(expectedPci, 0, 0, -1, -1, -1, 0, 1, 0, false);
		}
		double[][] resampled = resample(iq, inputSamples, sampleRateHz, BASE_RATE);
		int nid1 = expectedPci / 3, nid2 = expectedPci % 3;
		Peak bestPeak = null;
		Candidate bestCandidate = null;
		for (Candidate acquiredCandidate : acquired.candidates) {
			if (acquiredCandidate.nid2 != nid2) continue;
			Peak peak = new Peak(nid2, acquiredCandidate.pssCorrelation, acquiredCandidate.sample,
					acquiredCandidate.freqOffsetHz / 15_000d, acquiredCandidate.iqOrientation, false,
					acquiredCandidate.cfoHz);
			Candidate candidate = scoreSssForCell(resampled[0], resampled[1], peak, nid1, nid2);
			if (bestCandidate == null || candidate.sssCorrelation > bestCandidate.sssCorrelation) {
				bestPeak = peak;
				bestCandidate = candidate;
			}
		}
		if (bestCandidate == null || bestPeak == null) {
			return new ExpectedScore(expectedPci, 0, 0, -1, -1, -1, 0, 1, 0, false);
		}
		return new ExpectedScore(expectedPci, bestCandidate.pssCorrelation, bestCandidate.sssCorrelation,
				bestCandidate.sample, bestCandidate.freqOffsetHz, bestCandidate.cpSamples, bestCandidate.cfoHz,
				bestCandidate.iqOrientation, bestCandidate.rawPci, bestPeak.reversed);
	}

	private static Candidate scoreSssForCell(double[] inI, double[] inQ, Peak peak, int nid1, int nid2) {
		double best = 0;
		int bestCp = 0;
		double[][] pssReference = reference(PSS_SEQUENCES[peak.nid2], peak.freqOffsetBins, peak.reversed);
		int orientation = peak.iqOrientation > 0 ? 0 : 1;
		int period = BASE_RATE / 50;
		int[] ref = SSS_SEQUENCES[nid2][nid1];
		for (int cp : CP_CANDIDATES) {
			int nominal = peak.sample + 2 * FFT_SIZE + 2 * cp;
			for (int delta = -80; delta <= 80; delta += 4) {
				double accR = 0, accI = 0, power = 0;
				int repeats = 0;
				for (int repeat = -8; repeat <= 8; repeat++) {
					int expectedPss = peak.sample + repeat * period;
					if (expectedPss < 0 || expectedPss + 3 * FFT_SIZE > inI.length) continue;
					int localPss = refineAt(inI, inQ, pssReference, orientation, expectedPss, 32);
					double localScore = correlation(inI, inQ, pssReference, orientation, localPss);
					if (localScore < 0.10) continue;
					int sssAt = localPss + (nominal - peak.sample) + delta;
					if (sssAt < 0 || sssAt + FFT_SIZE > inI.length) continue;
					double[][] pssBins = extractBins(inI, inQ, localPss, peak.freqOffsetBins, peak.iqOrientation < 0,
							peak.reversed);
					double[][] sssBins = extractBins(inI, inQ, sssAt, peak.freqOffsetBins, peak.iqOrientation < 0,
							peak.reversed);
					double[] equalized = equalizeSss(pssBins, sssBins, peak.nid2);
					for (int n = 0; n < PSS_LENGTH; n++) {
						double er = equalized[2 * n], ei = equalized[2 * n + 1];
						power += er * er + ei * ei;
						accR += er * ref[n];
						accI += ei * ref[n];
					}
					repeats++;
				}
				if (repeats == 0 || power <= 1e-12) continue;
				double score = Math.hypot(accR, accI) / Math.sqrt(power * PSS_LENGTH * repeats);
				if (score > best) {
					best = score;
					bestCp = cp + delta;
				}
			}
		}
		return new Candidate(best >= 0.32 ? 3 * nid1 + nid2 : -1, best >= 0.32 ? nid1 : -1,
				best >= 0.32 ? nid2 : peak.nid2, 3 * nid1 + nid2, nid1, nid2, peak.pssCorrelation,
				best, peak.sample, (int)Math.round(peak.freqOffsetBins * 15_000d), peak.iqOrientation, bestCp, peak.cfoHz);
	}

	private static double[] equalizeSss(double[][] pssBins, double[][] sssBins, int nid2) {
		double[] result = new double[PSS_LENGTH * 2];
		int[] pss = PSS_SEQUENCES[nid2];
		for (int n = 0; n < PSS_LENGTH; n++) {
			double hr = pssBins[0][n] * pss[n], hi = pssBins[1][n] * pss[n];
			double den = hr * hr + hi * hi;
			if (den < 1e-9) den = 1e-9;
			double yr = sssBins[0][n], yi = sssBins[1][n];
			result[2 * n] = (yr * hr + yi * hi) / den;
			result[2 * n + 1] = (yi * hr - yr * hi) / den;
		}
		return result;
	}

	private static double[][] extractBins(double[] inI, double[] inQ, int at, double freqOffset, boolean conjugated) {
		return extractBins(inI, inQ, at, freqOffset, conjugated, false);
	}

	private static double[][] extractBins(double[] inI, double[] inQ, int at, double freqOffset, boolean conjugated,
			boolean reversed) {
		double[][] result = new double[2][PSS_LENGTH];
		for (int m = 0; m < PSS_LENGTH; m++) {
			double k = (reversed ? 63 - m : m - 63) + freqOffset;
			double re = 0, im = 0;
			for (int n = 0; n < FFT_SIZE; n++) {
				double a = inI[at + n], b = conjugated ? -inQ[at + n] : inQ[at + n];
				double p = -2d * Math.PI * k * n / FFT_SIZE;
				re += a * Math.cos(p) - b * Math.sin(p);
				im += a * Math.sin(p) + b * Math.cos(p);
			}
			result[0][m] = re;
			result[1][m] = im;
		}
		return result;
	}

	private static double correlation(double[] inI, double[] inQ, double[][] reference, int orientation, int at) {
		double cr = 0, ci = 0, power = 0;
		for (int n = 0; n < FFT_SIZE; n++) {
			double a = inI[at + n], b = inQ[at + n], rr = reference[0][n],
					ri = orientation == 0 ? reference[1][n] : -reference[1][n];
			cr += a * rr + b * ri;
			ci += b * rr - a * ri;
			power += a * a + b * b;
		}
		return power <= 1e-12 ? 0 : Math.hypot(cr, ci) / Math.sqrt(power);
	}

	private static double cyclicPrefixOffset(double[] inI, double[] inQ, int at, int orientation) {
		int cp = 36, start = at - cp;
		if (start < 0 || at + FFT_SIZE > inI.length) return 0;
		double cr = 0, ci = 0;
		for (int n = 0; n < cp; n++) {
			double ar = inI[start + n], ai = orientation == 0 ? inQ[start + n] : -inQ[start + n];
			double br = inI[at + FFT_SIZE - cp + n], bi = orientation == 0 ? inQ[at + FFT_SIZE - cp + n]
					: -inQ[at + FFT_SIZE - cp + n];
			cr += ar * br + ai * bi;
			ci += ai * br - ar * bi;
		}
		return Math.atan2(ci, cr) / FFT_SIZE;
	}

	private static double[][] resample(byte[] iq, int inputSamples, int inputRate, int outputRate) {
		int outputSamples = (int)Math.floor(inputSamples * (outputRate / (double)inputRate));
		double[] re = new double[outputSamples], im = new double[outputSamples];
		if (inputRate == outputRate) {
			for (int n = 0; n < outputSamples; n++) {
				re[n] = iq[2 * n] / 128d;
				im[n] = iq[2 * n + 1] / 128d;
			}
			return new double[][] { re, im };
		}
		double ratio = inputRate / (double)outputRate;
		ResampleKernel kernel = resampleKernel(inputRate, outputRate);
		for (int n = 0; n < outputSamples; n++) {
			double position = n * ratio;
			int center = (int)Math.floor(position);
			int phase = (int)Math.round((position - center) * ResampleKernel.PHASES);
			if (phase == ResampleKernel.PHASES) {
				phase = 0;
				center++;
			}
			double sum = 0, ar = 0, ai = 0;
			double[] weights = kernel.weights[phase];
			int first = center - kernel.radius + 1;
			for (int tap = 0; tap < weights.length; tap++) {
				int k = first + tap;
				if (k < 0 || k >= inputSamples) continue;
				double w = weights[tap];
				ar += w * iq[2 * k];
				ai += w * iq[2 * k + 1];
				sum += w;
			}
			if (Math.abs(sum) > 1e-12) {
				re[n] = ar / (128d * sum);
				im[n] = ai / (128d * sum);
			}
		}
		return new double[][] { re, im };
	}

	private static synchronized ResampleKernel resampleKernel(int inputRate, int outputRate) {
		long key = ((long)inputRate << 32) | (outputRate & 0xffffffffL);
		ResampleKernel result = RESAMPLE_KERNELS.get(key);
		if (result == null) {
			result = new ResampleKernel(inputRate / (double)outputRate);
			RESAMPLE_KERNELS.put(key, result);
		}
		return result;
	}

	private static int freqIndex(int freqOffset) {
		for (int i = 0; i < FREQ_OFFSETS.length; i++) if (FREQ_OFFSETS[i] == freqOffset) return i;
		return 0;
	}

	private static double[][][][][] buildPssReferences() {
		double[][][][][] result = new double[FREQ_OFFSETS.length][3][2][][];
		for (int f = 0; f < FREQ_OFFSETS.length; f++) {
			for (int nid2 = 0; nid2 < 3; nid2++) {
				int[] sequence = PSS_SEQUENCES == null ? pssSequence(nid2) : PSS_SEQUENCES[nid2];
				result[f][nid2][0] = reference(sequence, FREQ_OFFSETS[f], false);
				result[f][nid2][1] = reference(sequence, FREQ_OFFSETS[f], true);
			}
		}
		return result;
	}

	private static int[][] buildPssSequences() {
		int[][] result = new int[3][];
		for (int nid2 = 0; nid2 < 3; nid2++) result[nid2] = pssSequence(nid2);
		return result;
	}

	private static int[][][] buildSssSequences() {
		int[][][] result = new int[3][336][];
		for (int nid2 = 0; nid2 < 3; nid2++)
			for (int nid1 = 0; nid1 < 336; nid1++)
				result[nid2][nid1] = sssSequence(nid1, nid2);
		return result;
	}

	private static double[][] reference(int[] sequence, double freqOffset, boolean reversed) {
		double[] real = new double[FFT_SIZE], imag = new double[FFT_SIZE];
		for (int m = 0; m < PSS_LENGTH; m++) {
			double k = (reversed ? 63 - m : m - 63) + freqOffset;
			double value = sequence[m];
			for (int n = 0; n < FFT_SIZE; n++) {
				double p = 2d * Math.PI * k * n / FFT_SIZE;
				real[n] += value * Math.cos(p);
				imag[n] += value * Math.sin(p);
			}
		}
		double energy = 0;
		for (int n = 0; n < FFT_SIZE; n++) energy += real[n] * real[n] + imag[n] * imag[n];
		double scale = energy <= 0 ? 1 : 1d / Math.sqrt(energy);
		for (int n = 0; n < FFT_SIZE; n++) {
			real[n] *= scale;
			imag[n] *= scale;
		}
		return new double[][] { real, imag };
	}

	static int[] pssSequence(int nid2) {
		int[] x = new int[PSS_LENGTH];
		x[0] = 0; x[1] = 1; x[2] = 1; x[3] = 0; x[4] = 1; x[5] = 1; x[6] = 1;
		for (int i = 0; i < PSS_LENGTH - 7; i++) x[i + 7] = (x[i + 4] + x[i]) & 1;
		int[] d = new int[PSS_LENGTH];
		for (int n = 0; n < PSS_LENGTH; n++) d[n] = 1 - 2 * x[(n + 43 * nid2) % PSS_LENGTH];
		return d;
	}

	static int[] sssSequence(int nid1, int nid2) {
		int[] x0 = new int[PSS_LENGTH], x1 = new int[PSS_LENGTH];
		x0[0] = 1;
		x1[0] = 1;
		for (int i = 0; i < PSS_LENGTH - 7; i++) {
			x0[i + 7] = (x0[i + 4] + x0[i]) & 1;
			x1[i + 7] = (x1[i + 1] + x1[i]) & 1;
		}
		int m0 = 15 * (nid1 / 112) + 5 * nid2;
		int m1 = nid1 % 112;
		int[] d = new int[PSS_LENGTH];
		for (int n = 0; n < PSS_LENGTH; n++) {
			d[n] = (1 - 2 * x0[(n + m0) % PSS_LENGTH]) * (1 - 2 * x1[(n + m1) % PSS_LENGTH]);
		}
		return d;
	}

	private static final class ResampleKernel {
		static final int PHASES = 256;
		final int radius;
		final double[][] weights;
		ResampleKernel(double ratio) {
			radius = Math.max(12, (int)Math.ceil(12 * ratio));
			double cutoff = Math.min(.48, .48 / ratio);
			weights = new double[PHASES][2 * radius];
			for (int phase = 0; phase < PHASES; phase++) {
				double fraction = phase / (double)PHASES;
				for (int tap = 0; tap < 2 * radius; tap++) {
					double d = tap - radius + 1 - fraction, x = 2d * cutoff * d;
					double sinc = Math.abs(x) < 1e-12 ? 1 : Math.sin(Math.PI * x) / (Math.PI * x);
					double window = Math.abs(d) > radius ? 0 : .5 + .5 * Math.cos(Math.PI * d / radius);
					weights[phase][tap] = 2d * cutoff * sinc * window;
				}
			}
		}
	}

	private static final class Peak {
		final int nid2, sample, iqOrientation;
		final double freqOffsetBins;
		final boolean reversed;
		final double pssCorrelation, cfoHz;
		Peak(int nid2, double pssCorrelation, int sample, double freqOffsetBins, int iqOrientation, boolean reversed,
				double cfoHz) {
			this.nid2 = nid2;
			this.pssCorrelation = pssCorrelation;
			this.sample = sample;
			this.freqOffsetBins = freqOffsetBins;
			this.iqOrientation = iqOrientation;
			this.reversed = reversed;
			this.cfoHz = cfoHz;
		}
	}

	private static final class CpCandidate {
		final int sample;
		final double cpCorrelation, omega;
		CpCandidate(int sample, double cpCorrelation, double omega) {
			this.sample = sample;
			this.cpCorrelation = cpCorrelation;
			this.omega = omega;
		}
	}

	static final class PssSurveyCandidate {
		final int nid2, sample, freqOffsetHz, iqOrientation, periodicRepeats, centerBin;
		final int pci, rawNid1, sssTimingDelta, sssRepeats, iBarSsb, dmrsBursts;
		final double pssCorrelation, cpCorrelation, cfoHz, periodicCorrelation;
		final double sssCorrelation, secondSssCorrelation, dmrsCorrelation, secondDmrsCorrelation, pbchEvm;
		final boolean dmrsConfirmed, pbchCrcOk;
		final MibData mib;
		final NrPdcchDecoder.Survey pdcch;
		LoadData load = LoadData.EMPTY;
		PssSurveyCandidate(int nid2, double pssCorrelation, double cpCorrelation, int sample,
				int freqOffsetHz, int iqOrientation, double cfoHz, int periodicRepeats,
				double periodicCorrelation, int centerBin, int pci, int rawNid1,
				double sssCorrelation, double secondSssCorrelation, int sssTimingDelta, int sssRepeats,
				boolean dmrsConfirmed, int iBarSsb, double dmrsCorrelation,
				double secondDmrsCorrelation, int dmrsBursts, boolean pbchCrcOk, double pbchEvm,
				MibData mib, NrPdcchDecoder.Survey pdcch) {
			this.nid2 = nid2;
			this.pssCorrelation = pssCorrelation;
			this.cpCorrelation = cpCorrelation;
			this.sample = sample;
			this.freqOffsetHz = freqOffsetHz;
			this.iqOrientation = iqOrientation;
			this.cfoHz = cfoHz;
			this.periodicRepeats = periodicRepeats;
			this.periodicCorrelation = periodicCorrelation;
			this.centerBin = centerBin;
			this.pci = pci;
			this.rawNid1 = rawNid1;
			this.sssCorrelation = sssCorrelation;
			this.secondSssCorrelation = secondSssCorrelation;
			this.sssTimingDelta = sssTimingDelta;
			this.sssRepeats = sssRepeats;
			this.dmrsConfirmed = dmrsConfirmed;
			this.iBarSsb = iBarSsb;
			this.dmrsCorrelation = dmrsCorrelation;
			this.secondDmrsCorrelation = secondDmrsCorrelation;
			this.dmrsBursts = dmrsBursts;
			this.pbchCrcOk = pbchCrcOk;
			this.pbchEvm = pbchEvm;
			this.mib = mib;
			this.pdcch = pdcch;
		}
		PssSurveyCandidate withPeriodicity(int repeats, double correlation) {
			return new PssSurveyCandidate(nid2, pssCorrelation, cpCorrelation, sample, freqOffsetHz,
					iqOrientation, cfoHz, repeats, correlation, centerBin, pci, rawNid1,
					sssCorrelation, secondSssCorrelation, sssTimingDelta, sssRepeats,
					dmrsConfirmed, iBarSsb, dmrsCorrelation, secondDmrsCorrelation, dmrsBursts,
					pbchCrcOk, pbchEvm, mib, pdcch);
		}
		PssSurveyCandidate withSss(int decodedPci, int nid1, double score, double second,
				int timingDelta, int repeats) {
			return new PssSurveyCandidate(nid2, pssCorrelation, cpCorrelation, sample, freqOffsetHz,
					iqOrientation, cfoHz, periodicRepeats, periodicCorrelation, centerBin, decodedPci,
					nid1, score, second, timingDelta, repeats, false, -1, 0, 0, 0,
					false, 0, MibData.EMPTY, NrPdcchDecoder.Survey.EMPTY);
		}
		PssSurveyCandidate withDmrs(boolean confirmed, int barSsb, double score, double second,
				int bursts) {
			return new PssSurveyCandidate(nid2, pssCorrelation, cpCorrelation, sample, freqOffsetHz,
					iqOrientation, cfoHz, periodicRepeats, periodicCorrelation, centerBin, pci,
					rawNid1, sssCorrelation, secondSssCorrelation, sssTimingDelta, sssRepeats,
					confirmed, barSsb, score, second, bursts, false, 0, MibData.EMPTY,
					NrPdcchDecoder.Survey.EMPTY);
		}
		PssSurveyCandidate withPbch(NrPbchDecoder.Decoded decoded) {
			return new PssSurveyCandidate(nid2, pssCorrelation, cpCorrelation, sample, freqOffsetHz,
					iqOrientation, cfoHz, periodicRepeats, periodicCorrelation, centerBin, pci,
					rawNid1, sssCorrelation, secondSssCorrelation, sssTimingDelta, sssRepeats,
					dmrsConfirmed, iBarSsb, dmrsCorrelation, secondDmrsCorrelation, dmrsBursts,
					decoded.crcOk, decoded.evm, decoded.mib, pdcch);
		}
		PssSurveyCandidate withPdcch(NrPdcchDecoder.Survey decoded) {
			return new PssSurveyCandidate(nid2, pssCorrelation, cpCorrelation, sample, freqOffsetHz,
					iqOrientation, cfoHz, periodicRepeats, periodicCorrelation, centerBin, pci,
					rawNid1, sssCorrelation, secondSssCorrelation, sssTimingDelta, sssRepeats,
					dmrsConfirmed, iBarSsb, dmrsCorrelation, secondDmrsCorrelation, dmrsBursts,
					pbchCrcOk, pbchEvm, mib, decoded);
		}
	}

	static final class PssSurvey {
		final List<PssSurveyCandidate> candidates;
		final int cpCandidates;
		PssSurvey(List<PssSurveyCandidate> candidates, int cpCandidates) {
			this.candidates = candidates;
			this.cpCandidates = cpCandidates;
		}
	}

	static final class Candidate {
		final int pci, nid1, nid2, rawPci, rawNid1, rawNid2, sample, freqOffsetHz, iqOrientation, cpSamples;
		final double pssCorrelation, sssCorrelation, cfoHz;
		Candidate(int pci, int nid1, int nid2, int rawPci, int rawNid1, int rawNid2,
				double pssCorrelation, double sssCorrelation, int sample,
				int freqOffsetHz, int iqOrientation, int cpSamples, double cfoHz) {
			this.pci = pci;
			this.nid1 = nid1;
			this.nid2 = nid2;
			this.rawPci = rawPci;
			this.rawNid1 = rawNid1;
			this.rawNid2 = rawNid2;
			this.pssCorrelation = pssCorrelation;
			this.sssCorrelation = sssCorrelation;
			this.sample = sample;
			this.freqOffsetHz = freqOffsetHz;
			this.iqOrientation = iqOrientation;
			this.cpSamples = cpSamples;
			this.cfoHz = cfoHz;
		}
		double score() {
			return pci >= 0 ? pssCorrelation * sssCorrelation : pssCorrelation * 0.2d;
		}
	}

	static final class ExpectedScore {
		final int expectedPci, bestRawPci, sample, freqOffsetHz, cpSamples, iqOrientation;
		final double pssCorrelation, sssCorrelation, cfoHz;
		final boolean reversed;
		ExpectedScore(int expectedPci, double pssCorrelation, double sssCorrelation, int sample,
				int freqOffsetHz, int cpSamples, double cfoHz, int iqOrientation, int bestRawPci,
				boolean reversed) {
			this.expectedPci = expectedPci;
			this.pssCorrelation = pssCorrelation;
			this.sssCorrelation = sssCorrelation;
			this.sample = sample;
			this.freqOffsetHz = freqOffsetHz;
			this.cpSamples = cpSamples;
			this.cfoHz = cfoHz;
			this.iqOrientation = iqOrientation;
			this.bestRawPci = bestRawPci;
			this.reversed = reversed;
		}
	}

	static final class MibData {
		static final MibData EMPTY = new MibData(false, -1, -1, -1, -1, -1, false, false);
		final boolean valid, cellBarred, intraFrequencyReselection;
		final int systemFrameNumber, subcarrierSpacingCommonKhz, ssbSubcarrierOffset;
		final int dmrsTypeAPosition, pdcchConfigSib1;
		MibData(boolean valid, int systemFrameNumber, int subcarrierSpacingCommonKhz,
				int ssbSubcarrierOffset, int dmrsTypeAPosition, int pdcchConfigSib1,
				boolean cellBarred, boolean intraFrequencyReselection) {
			this.valid = valid;
			this.systemFrameNumber = systemFrameNumber;
			this.subcarrierSpacingCommonKhz = subcarrierSpacingCommonKhz;
			this.ssbSubcarrierOffset = ssbSubcarrierOffset;
			this.dmrsTypeAPosition = dmrsTypeAPosition;
			this.pdcchConfigSib1 = pdcchConfigSib1;
			this.cellBarred = cellBarred;
			this.intraFrequencyReselection = intraFrequencyReselection;
		}
	}

	static final class Sib1Data {
		static final Sib1Data EMPTY = new Sib1Data(false, Collections.<String>emptyList(), -1L, -1,
				Collections.<Integer>emptyList(), Collections.<String>emptyList(), -1, -1, -1, -1,
				-1, -1, -1, -1, -1, "", -1, -1, -1, -1);
		final boolean valid;
		final List<String> plmns;
		final long cellIdentity;
		final int trackingAreaCode;
		final List<Integer> frequencyBands;
		final List<String> schedules;
		final int siWindowSlots, carrierBandwidthRb, carrierScsKhz, channelBandwidthMhz;
		final int initialBwpRb, initialBwpScsKhz, offsetToPointA;
		final int modificationPeriodCoefficient, pagingCycleFrames, pagingFrameOffset;
		final int pagingOccasions, otherSiSearchSpace, pagingSearchSpace;
		final String pagingFrameOffsetType;
		Sib1Data(boolean valid, List<String> plmns, long cellIdentity, int trackingAreaCode,
				List<Integer> frequencyBands, List<String> schedules, int siWindowSlots,
				int carrierBandwidthRb, int carrierScsKhz, int channelBandwidthMhz,
				int initialBwpRb, int initialBwpScsKhz, int offsetToPointA,
				int modificationPeriodCoefficient, int pagingCycleFrames,
				String pagingFrameOffsetType, int pagingFrameOffset, int pagingOccasions,
				int otherSiSearchSpace, int pagingSearchSpace) {
			this.valid = valid;
			this.plmns = Collections.unmodifiableList(new ArrayList<String>(plmns));
			this.cellIdentity = cellIdentity;
			this.trackingAreaCode = trackingAreaCode;
			this.frequencyBands = Collections.unmodifiableList(new ArrayList<Integer>(frequencyBands));
			this.schedules = Collections.unmodifiableList(new ArrayList<String>(schedules));
			this.siWindowSlots = siWindowSlots;
			this.carrierBandwidthRb = carrierBandwidthRb;
			this.carrierScsKhz = carrierScsKhz;
			this.channelBandwidthMhz = channelBandwidthMhz;
			this.initialBwpRb = initialBwpRb;
			this.initialBwpScsKhz = initialBwpScsKhz;
			this.offsetToPointA = offsetToPointA;
			this.modificationPeriodCoefficient = modificationPeriodCoefficient;
			this.pagingCycleFrames = pagingCycleFrames;
			this.pagingFrameOffsetType = pagingFrameOffsetType;
			this.pagingFrameOffset = pagingFrameOffset;
			this.pagingOccasions = pagingOccasions;
			this.otherSiSearchSpace = otherSiSearchSpace;
			this.pagingSearchSpace = pagingSearchSpace;
		}
	}

	static final class LoadData {
		static final LoadData EMPTY = new LoadData(false, 0, 0, 0, 0);
		final boolean valid;
		final int activePrbSamples, totalPrbSamples, symbols;
		final double percent;
		LoadData(boolean valid, int activePrbSamples, int totalPrbSamples, int symbols, double percent) {
			this.valid = valid;
			this.activePrbSamples = activePrbSamples;
			this.totalPrbSamples = totalPrbSamples;
			this.symbols = symbols;
			this.percent = percent;
		}
	}

	static final class Result {
		final boolean detected;
		final int pci, nid1, nid2, sample, freqOffsetHz, iqOrientation;
		final int iBarSsb, dmrsBursts;
		final double pssCorrelation, sssCorrelation, quality, dmrsCorrelation, secondDmrsCorrelation;
		final boolean dmrsConfirmed;
		final MibData mib;
		final Sib1Data sib1;
		final LoadData load;
		final String state;
		final List<Candidate> candidates;
		Result(boolean detected, int pci, int nid1, int nid2, double pssCorrelation, double sssCorrelation,
				int sample, int freqOffsetHz, int iqOrientation, double quality, String state,
				List<Candidate> candidates, boolean dmrsConfirmed, double dmrsCorrelation,
				double secondDmrsCorrelation, int iBarSsb, int dmrsBursts, MibData mib, Sib1Data sib1,
				LoadData load) {
			this.detected = detected;
			this.pci = pci;
			this.nid1 = nid1;
			this.nid2 = nid2;
			this.pssCorrelation = pssCorrelation;
			this.sssCorrelation = sssCorrelation;
			this.sample = sample;
			this.freqOffsetHz = freqOffsetHz;
			this.iqOrientation = iqOrientation;
			this.quality = quality;
			this.state = state;
			this.candidates = candidates;
			this.dmrsConfirmed = dmrsConfirmed;
			this.dmrsCorrelation = dmrsCorrelation;
			this.secondDmrsCorrelation = secondDmrsCorrelation;
			this.iBarSsb = iBarSsb;
			this.dmrsBursts = dmrsBursts;
			this.mib = mib;
			this.sib1 = sib1;
			this.load = load == null ? LoadData.EMPTY : load;
		}
		static Result empty(String state) {
			return new Result(false, -1, -1, -1, 0, 0, -1, 0, 1, 0, state,
					Collections.<Candidate>emptyList(), false, 0, 0, -1, 0, MibData.EMPTY, Sib1Data.EMPTY,
					LoadData.EMPTY);
		}
		Result withSystemInformation(MibData decodedMib, Sib1Data decodedSib1) {
			return withMeasurements(decodedMib, decodedSib1, load);
		}
		Result withMeasurements(MibData decodedMib, Sib1Data decodedSib1, LoadData decodedLoad) {
			return new Result(detected, pci, nid1, nid2, pssCorrelation, sssCorrelation, sample,
					freqOffsetHz, iqOrientation, quality, state, candidates, dmrsConfirmed,
					dmrsCorrelation, secondDmrsCorrelation, iBarSsb, dmrsBursts,
					decodedMib == null ? MibData.EMPTY : decodedMib,
					decodedSib1 == null ? Sib1Data.EMPTY : decodedSib1,
					decodedLoad == null ? LoadData.EMPTY : decodedLoad);
		}
	}
}
