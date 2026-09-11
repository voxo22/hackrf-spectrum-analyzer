package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lightweight UMTS/WCDMA FDD downlink acquisition using P-SCH timing and CPICH PSC search. */
final class UmtsSignalAnalyzer {
	static final int CHIP_RATE = 3_840_000;
	private static final int SLOT_CHIPS = 2560;
	private static final int FRAME_CHIPS = 38400;
	private static final int PSCH_CHIPS = 256;
	private static final int GOLD_PERIOD = (1 << 18) - 1;
	private static final int CPICH_STRIDE = 8;
	private static final int[] FREQ_OFFSETS = {
			0, -100_000, 100_000, -200_000, 200_000, -50_000, 50_000, -150_000, 150_000, -250_000, 250_000
	};
	private static final int[] X = buildX();
	private static final int[] Y = buildY();
	private static final double[][] PSCH = buildPsch();
	private static final Map<Long, ResampleKernel> RESAMPLE_KERNELS = new LinkedHashMap<Long, ResampleKernel>();
	private final Map<Integer, UmtsSystemInformationDecoder> systemInformationDecoders =
			new LinkedHashMap<Integer, UmtsSystemInformationDecoder>();
	private final Map<Integer, UmtsPchDecoder> pchDecoders =
			new LinkedHashMap<Integer, UmtsPchDecoder>();
	private int lockedPsc = -1;
	private int lockedFreqOffsetHz = Integer.MIN_VALUE;
	private int lockedPasses;

	Result analyze(byte[] iq, int length, int sampleRateHz) {
		int inputSamples = Math.min(length, iq == null ? 0 : iq.length) / 2;
		if (sampleRateHz < 3_200_000 || inputSamples < sampleRateHz / 100)
			return Result.empty("Need >= 3.2 MS/s and 10 ms of UMTS IQ");
		double[][] resampled = resample(iq, inputSamples, sampleRateHz, CHIP_RATE);
		double[] inI = resampled[0], inQ = resampled[1];
		removeDc(inI, inQ);
		if (inI.length < FRAME_CHIPS / 2)
			return Result.empty("Need at least 5 ms of WCDMA chips");

		PschLock psch = findPsch(inI, inQ);
		if (psch == null || psch.correlation < 0.09)
			return Result.empty("Searching UMTS P-SCH");
		boolean fullRescan = lockedPsc < 0 || ++lockedPasses >= 10;
		if (fullRescan) lockedPasses = 0;
		List<Candidate> candidates = findCpich(inI, inQ, psch, fullRescan);
		int decodeCount = fullRescan ? Math.min(3, candidates.size()) : Math.min(1, candidates.size());
		for (int index = 0; index < decodeCount; index++) {
			Candidate candidate = candidates.get(index);
			if (candidate.cpichCorrelation < 0.06) continue;
			UmtsBchDecoder.BchData bch = UmtsBchDecoder.decode(inI, inQ, candidate);
			UmtsSystemInformationDecoder decoder = systemInformationDecoders.get(candidate.psc);
			if (decoder == null) {
				decoder = new UmtsSystemInformationDecoder();
				systemInformationDecoders.put(candidate.psc, decoder);
			}
			candidate = candidate.withBchAndSystemInformation(bch, decoder.accept(bch));
			candidate = candidate.withLoad(UmtsLoadEstimator.measure(inI, inQ, candidate));
			if (candidate.systemInformation.sib5.valid) {
				UmtsPchDecoder pch = pchDecoders.get(candidate.psc);
				if (pch == null) {
					pch = new UmtsPchDecoder();
					pchDecoders.put(candidate.psc, pch);
				}
				candidate = candidate.withPaging(pch.scan(inI, inQ, candidate,
						candidate.systemInformation.sib5));
			}
			candidates.set(index, candidate);
		}
		Candidate primary = candidates.isEmpty() ? null : candidates.get(0);
		if (primary != null && primary.cpichCorrelation >= 0.08) {
			lockedPsc = primary.psc;
			lockedFreqOffsetHz = psch.freqOffsetHz;
		}
		boolean detected = primary != null && primary.cpichCorrelation >= 0.045;
		double syncQuality = Math.min(100d, Math.max(0d, psch.correlation * 280d));
		double cpichQuality = primary == null ? 0d : Math.min(100d, primary.cpichCorrelation * 900d);
		double quality = detected ? Math.min(100d, syncQuality * .35d + cpichQuality * .65d) : syncQuality * .35d;
		String state = primary != null && primary.systemInformation.mibValid ? "UMTS MIB decoded"
				: primary != null && primary.bch.valid ? "UMTS BCH decoded (CRC OK)"
				: primary != null && primary.cpichCorrelation >= 0.065
				? "UMTS CPICH PSC locked" : detected ? "UMTS CPICH candidate"
				: psch.correlation >= 0.16 ? "UMTS P-SCH slot timing locked" : "Possible UMTS P-SCH";
		return new Result(detected, primary == null ? -1 : primary.psc, primary == null ? -1 : primary.codeGroup,
				psch.sample, psch.iqOrientation, psch.correlation,
				primary == null ? 0d : primary.cpichCorrelation, psch.cfoHz, quality,
				state, Collections.unmodifiableList(candidates));
	}

	private PschLock findPsch(double[] inI, double[] inQ) {
		int searchSamples = Math.min(inI.length, FRAME_CHIPS * 3);
		int slots = Math.max(1, Math.min(12, (searchSamples - PSCH_CHIPS) / SLOT_CHIPS));
		PschLock best = null;
		PschLock preferred = null;
		for (int freqOffset : preferredFreqOffsets()) {
			for (int orientation = 0; orientation < 2; orientation++) {
				for (int phase = 0; phase < SLOT_CHIPS && phase + PSCH_CHIPS <= searchSamples; phase += 4) {
					double scoreSum = 0;
					int used = 0;
					for (int slot = 0; slot < slots; slot++) {
						Complex c = correlatePsch(inI, inQ, phase + slot * SLOT_CHIPS, orientation, freqOffset);
						if (c.power <= 1e-12) continue;
						scoreSum += Math.hypot(c.re, c.im) / Math.sqrt(c.power);
						used++;
					}
					if (used == 0) continue;
					double score = scoreSum / used;
					if (best == null || score > best.correlation)
						best = new PschLock(phase, orientation == 0 ? 1 : -1, score, freqOffset, freqOffset);
					if (freqOffset == lockedFreqOffsetHz && (preferred == null || score > preferred.correlation))
						preferred = new PschLock(phase, orientation == 0 ? 1 : -1, score, freqOffset, freqOffset);
				}
			}
		}
		if (best == null) return null;
		if (preferred != null && preferred.correlation >= Math.max(0.10, best.correlation * .70d))
			best = preferred;
		int orientation = best.iqOrientation > 0 ? 0 : 1;
		for (int phase = Math.max(0, best.sample - 10);
				phase <= Math.min(SLOT_CHIPS - 1, best.sample + 10); phase++) {
			double scoreSum = 0;
			int used = 0;
			for (int slot = 0; slot < slots; slot++) {
				Complex c = correlatePsch(inI, inQ, phase + slot * SLOT_CHIPS, orientation, best.freqOffsetHz);
				if (c.power <= 1e-12) continue;
				scoreSum += Math.hypot(c.re, c.im) / Math.sqrt(c.power);
				used++;
			}
			if (used > 0 && scoreSum / used > best.correlation)
				best = new PschLock(phase, best.iqOrientation, scoreSum / used, best.freqOffsetHz, best.cfoHz);
		}
		double residual = estimatePschCfo(inI, inQ, best.sample, best.iqOrientation > 0 ? 0 : 1,
				best.freqOffsetHz);
		return new PschLock(best.sample, best.iqOrientation, best.correlation, best.freqOffsetHz,
				best.freqOffsetHz + residual);
	}

	private int[] preferredFreqOffsets() {
		if (lockedPsc < 0 || lockedFreqOffsetHz == Integer.MIN_VALUE) return FREQ_OFFSETS;
		int[] result = new int[FREQ_OFFSETS.length + 1];
		result[0] = lockedFreqOffsetHz;
		int out = 1;
		for (int value : FREQ_OFFSETS) if (value != lockedFreqOffsetHz) result[out++] = value;
		if (out == result.length) return result;
		int[] trimmed = new int[out];
		System.arraycopy(result, 0, trimmed, 0, out);
		return trimmed;
	}

	private static Complex correlatePsch(double[] inI, double[] inQ, int at, int orientation, double freqOffsetHz) {
		double cr = 0, ci = 0, power = 0;
		double omega = freqOffsetHz * 2d * Math.PI / CHIP_RATE;
		for (int n = 0; n < PSCH_CHIPS; n++) {
			double angle = -omega * (at + n);
			double ca = Math.cos(angle), sa = Math.sin(angle);
			double a = inI[at + n] * ca - inQ[at + n] * sa;
			double b = inI[at + n] * sa + inQ[at + n] * ca;
			if (orientation != 0) b = -b;
			double rr = PSCH[0][n], ri = PSCH[1][n];
			cr += a * rr + b * ri;
			ci += b * rr - a * ri;
			power += a * a + b * b;
		}
		return new Complex(cr, ci, power);
	}

	private static double estimatePschCfo(double[] inI, double[] inQ, int phase, int orientation, double coarseOffsetHz) {
		double vr = 0, vi = 0;
		Complex previous = null;
		for (int at = phase; at + SLOT_CHIPS + PSCH_CHIPS < inI.length; at += SLOT_CHIPS) {
			Complex current = correlatePsch(inI, inQ, at, orientation, coarseOffsetHz);
			if (previous != null && current.power > 1e-12 && previous.power > 1e-12) {
				vr += current.re * previous.re + current.im * previous.im;
				vi += current.im * previous.re - current.re * previous.im;
			}
			previous = current;
		}
		if (vr == 0 && vi == 0) return 0;
		return Math.atan2(vi, vr) * CHIP_RATE / (2d * Math.PI * SLOT_CHIPS);
	}

	private List<Candidate> findCpich(double[] inI, double[] inQ, PschLock psch, boolean fullRescan) {
		List<Candidate> candidates = new ArrayList<Candidate>();
		if (lockedPsc >= 0 && !fullRescan) {
			Candidate locked = bestCpichForPsc(inI, inQ, psch, lockedPsc);
			if (locked != null && locked.cpichCorrelation >= 0.06) {
				candidates.add(locked);
				return candidates;
			}
		}
		for (int slotPhase = 0; slotPhase < 15; slotPhase++) {
			int frameStart = psch.sample - slotPhase * SLOT_CHIPS;
			while (frameStart < 0) frameStart += FRAME_CHIPS;
			if (frameStart >= inI.length - SLOT_CHIPS) continue;
			for (int psc = 0; psc < 512; psc++) {
				double score = scoreCpich(inI, inQ, frameStart, psc, psch.iqOrientation, psch.cfoHz,
						CPICH_STRIDE, FRAME_CHIPS);
				if (score < 0.025) continue;
				addCandidate(candidates, new Candidate(psc, psc / 8, frameStart, slotPhase, score,
						ecNoDb(score), rscpDbfs(score, inI, inQ, frameStart), psch.iqOrientation, psch.cfoHz));
			}
		}
		Collections.sort(candidates, (a, b) -> Double.compare(b.cpichCorrelation, a.cpichCorrelation));
		if (candidates.size() > 8) return new ArrayList<Candidate>(candidates.subList(0, 8));
		return candidates;
	}

	private static Candidate bestCpichForPsc(double[] inI, double[] inQ, PschLock psch, int psc) {
		Candidate best = null;
		for (int slotPhase = 0; slotPhase < 15; slotPhase++) {
			int frameStart = psch.sample - slotPhase * SLOT_CHIPS;
			while (frameStart < 0) frameStart += FRAME_CHIPS;
			if (frameStart >= inI.length - SLOT_CHIPS) continue;
			double score = scoreCpich(inI, inQ, frameStart, psc, psch.iqOrientation, psch.cfoHz,
					CPICH_STRIDE, FRAME_CHIPS);
			Candidate candidate = new Candidate(psc, psc / 8, frameStart, slotPhase, score,
					ecNoDb(score), rscpDbfs(score, inI, inQ, frameStart), psch.iqOrientation, psch.cfoHz);
			if (best == null || candidate.cpichCorrelation > best.cpichCorrelation) best = candidate;
		}
		return best;
	}

	private static void addCandidate(List<Candidate> candidates, Candidate candidate) {
		for (int i = 0; i < candidates.size(); i++) {
			Candidate existing = candidates.get(i);
			if (existing.psc == candidate.psc) {
				if (candidate.cpichCorrelation > existing.cpichCorrelation) candidates.set(i, candidate);
				return;
			}
		}
		candidates.add(candidate);
	}

	private static double scoreCpich(double[] inI, double[] inQ, int frameStart, int psc,
			int iqOrientation, double cfoHz, int stride, int maxChips) {
		int orientation = iqOrientation > 0 ? 0 : 1;
		double omega = cfoHz * 2d * Math.PI / CHIP_RATE;
		int start = frameStart;
		int end = Math.min(inI.length, frameStart + Math.max(FRAME_CHIPS / 2, maxChips));
		double cr = 0, ci = 0, power = 0;
		int count = 0, codeNumber = psc * 16;
		for (int at = start; at < end; at += stride) {
			int rel = Math.floorMod(at - frameStart, FRAME_CHIPS);
			int[] sc = scramblingChip(codeNumber, rel);
			double angle = -omega * (at - frameStart);
			double ca = Math.cos(angle), sa = Math.sin(angle);
			double ar = inI[at] * ca - inQ[at] * sa;
			double ai = inI[at] * sa + inQ[at] * ca;
			if (orientation != 0) ai = -ai;
			double rr = sc[0], ri = sc[1];
			cr += ar * rr + ai * ri;
			ci += ai * rr - ar * ri;
			power += ar * ar + ai * ai;
			count++;
		}
		return count == 0 || power <= 1e-12 ? 0 : Math.hypot(cr, ci) / Math.sqrt(power * 2d * count);
	}

	private static double ecNoDb(double correlation) {
		double ec = Math.max(1e-9, correlation * correlation);
		double noise = Math.max(1e-9, 1d - ec);
		return 10d * Math.log10(ec / noise);
	}

	private static double rscpDbfs(double correlation, double[] inI, double[] inQ, int start) {
		int end = Math.min(inI.length, start + FRAME_CHIPS);
		double power = 0;
		int count = 0;
		for (int i = Math.max(0, start); i < end; i += 8) {
			power += inI[i] * inI[i] + inQ[i] * inQ[i];
			count++;
		}
		if (count == 0 || power <= 0) return -120d;
		double rms = Math.sqrt(power / (2d * count)) * Math.max(0, correlation);
		return rms <= 1e-12 ? -120d : 20d * Math.log10(rms);
	}

	static int[] scramblingChip(int codeNumber, int chip) {
		int zi = X[(chip + codeNumber) % GOLD_PERIOD] ^ Y[chip % GOLD_PERIOD];
		int zqIndex = (chip + 131072) % GOLD_PERIOD;
		int zq = X[(zqIndex + codeNumber) % GOLD_PERIOD] ^ Y[zqIndex];
		return new int[] { zi == 0 ? 1 : -1, zq == 0 ? 1 : -1 };
	}

	private static int[] buildX() {
		int[] x = new int[GOLD_PERIOD];
		x[0] = 1;
		for (int i = 0; i < GOLD_PERIOD - 18; i++) x[i + 18] = x[i + 7] ^ x[i];
		return x;
	}

	private static int[] buildY() {
		int[] y = new int[GOLD_PERIOD];
		for (int i = 0; i < 18; i++) y[i] = 1;
		for (int i = 0; i < GOLD_PERIOD - 18; i++) y[i + 18] = y[i + 10] ^ y[i + 7] ^ y[i + 5] ^ y[i];
		return y;
	}

	private static double[][] buildPsch() {
		int[] a = { 1, 1, 1, 1, 1, 1, -1, -1, 1, -1, 1, -1, 1, -1, -1, 1 };
		int[] signs = { 1, 1, 1, -1, -1, 1, -1, -1, 1, 1, 1, -1, 1, -1, 1, 1 };
		double[] re = new double[PSCH_CHIPS], im = new double[PSCH_CHIPS];
		double scale = 1d / Math.sqrt(2d * PSCH_CHIPS);
		for (int block = 0; block < 16; block++) {
			for (int i = 0; i < 16; i++) {
				int value = signs[block] * a[i];
				re[block * 16 + i] = value * scale;
				im[block * 16 + i] = value * scale;
			}
		}
		return new double[][] { re, im };
	}

	private static void removeDc(double[] inI, double[] inQ) {
		double si = 0, sq = 0;
		for (int i = 0; i < inI.length; i++) {
			si += inI[i];
			sq += inQ[i];
		}
		double mi = si / Math.max(1, inI.length), mq = sq / Math.max(1, inQ.length);
		for (int i = 0; i < inI.length; i++) {
			inI[i] -= mi;
			inQ[i] -= mq;
		}
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

	static int uarfcnFromFrequency(long centerFrequencyHz) {
		double mhz = centerFrequencyHz / 1_000_000d;
		if (mhz >= 925d && mhz <= 960d) return (int)Math.round((mhz - 340d) * 5d);
		if (mhz >= 2110d && mhz <= 2170d) return (int)Math.round(mhz * 5d);
		if (mhz >= 1805d && mhz <= 1880d) return (int)Math.round((mhz - 1525d) * 5d);
		if (mhz >= 1930d && mhz <= 1990d) return (int)Math.round((mhz - 1850d) * 5d);
		return -1;
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
					double d = tap - radius + 1 - fraction;
					double x = 2d * cutoff * d;
					double sinc = Math.abs(x) < 1e-12 ? 1 : Math.sin(Math.PI * x) / (Math.PI * x);
					double window = Math.abs(d) > radius ? 0 : .5 + .5 * Math.cos(Math.PI * d / radius);
					weights[phase][tap] = 2d * cutoff * sinc * window;
				}
			}
		}
	}

	private static final class Complex {
		final double re, im, power;
		Complex(double re, double im, double power) {
			this.re = re;
			this.im = im;
			this.power = power;
		}
	}

	private static final class PschLock {
		final int sample, iqOrientation, freqOffsetHz;
		final double correlation, cfoHz;
		PschLock(int sample, int iqOrientation, double correlation, int freqOffsetHz, double cfoHz) {
			this.sample = sample;
			this.iqOrientation = iqOrientation;
			this.freqOffsetHz = freqOffsetHz;
			this.correlation = correlation;
			this.cfoHz = cfoHz;
		}
	}

	static final class Candidate {
		final int psc, codeGroup, frameStartSample, frameSlotPhase, iqOrientation;
		final double cpichCorrelation, ecNoDb, rscpDbfs, cfoHz;
		final UmtsBchDecoder.BchData bch;
		final UmtsSystemInformationDecoder.Snapshot systemInformation;
		final LoadData load;
		final UmtsPchDecoder.Snapshot paging;
		Candidate(int psc, int codeGroup, int frameStartSample, int frameSlotPhase,
				double cpichCorrelation, double ecNoDb, double rscpDbfs, int iqOrientation, double cfoHz) {
			this(psc, codeGroup, frameStartSample, frameSlotPhase, cpichCorrelation, ecNoDb,
					rscpDbfs, iqOrientation, cfoHz, UmtsBchDecoder.BchData.EMPTY,
					UmtsSystemInformationDecoder.Snapshot.EMPTY, LoadData.EMPTY,
					UmtsPchDecoder.Snapshot.EMPTY);
		}
		Candidate(int psc, int codeGroup, int frameStartSample, int frameSlotPhase,
				double cpichCorrelation, double ecNoDb, double rscpDbfs, int iqOrientation, double cfoHz,
				UmtsBchDecoder.BchData bch, UmtsSystemInformationDecoder.Snapshot systemInformation) {
			this(psc, codeGroup, frameStartSample, frameSlotPhase, cpichCorrelation, ecNoDb, rscpDbfs,
					iqOrientation, cfoHz, bch, systemInformation, LoadData.EMPTY,
					UmtsPchDecoder.Snapshot.EMPTY);
		}
		Candidate(int psc, int codeGroup, int frameStartSample, int frameSlotPhase,
				double cpichCorrelation, double ecNoDb, double rscpDbfs, int iqOrientation, double cfoHz,
				UmtsBchDecoder.BchData bch, UmtsSystemInformationDecoder.Snapshot systemInformation,
				LoadData load, UmtsPchDecoder.Snapshot paging) {
			this.psc = psc;
			this.codeGroup = codeGroup;
			this.frameStartSample = frameStartSample;
			this.frameSlotPhase = frameSlotPhase;
			this.cpichCorrelation = cpichCorrelation;
			this.ecNoDb = ecNoDb;
			this.rscpDbfs = rscpDbfs;
			this.iqOrientation = iqOrientation;
			this.cfoHz = cfoHz;
			this.bch = bch == null ? UmtsBchDecoder.BchData.EMPTY : bch;
			this.systemInformation = systemInformation == null
					? UmtsSystemInformationDecoder.Snapshot.EMPTY : systemInformation;
			this.load = load == null ? LoadData.EMPTY : load;
			this.paging = paging == null ? UmtsPchDecoder.Snapshot.EMPTY : paging;
		}
		Candidate withBch(UmtsBchDecoder.BchData value) {
			return new Candidate(psc, codeGroup, frameStartSample, frameSlotPhase, cpichCorrelation,
					ecNoDb, rscpDbfs, iqOrientation, cfoHz, value, systemInformation, load, paging);
		}
		Candidate withBchAndSystemInformation(UmtsBchDecoder.BchData value,
				UmtsSystemInformationDecoder.Snapshot information) {
			return new Candidate(psc, codeGroup, frameStartSample, frameSlotPhase, cpichCorrelation,
					ecNoDb, rscpDbfs, iqOrientation, cfoHz, value, information, load, paging);
		}
		Candidate withLoad(LoadData value) {
			return new Candidate(psc, codeGroup, frameStartSample, frameSlotPhase, cpichCorrelation,
					ecNoDb, rscpDbfs, iqOrientation, cfoHz, bch, systemInformation, value, paging);
		}
		Candidate withPaging(UmtsPchDecoder.Snapshot value) {
			return new Candidate(psc, codeGroup, frameStartSample, frameSlotPhase, cpichCorrelation,
					ecNoDb, rscpDbfs, iqOrientation, cfoHz, bch, systemInformation, load, value);
		}
	}

	static final class LoadData {
		static final LoadData EMPTY = new LoadData(false, Double.NaN, 0, 0, 0);
		final boolean valid;
		final double percent;
		final long activeCodeSamples, totalCodeSamples;
		final int blocks;
		LoadData(boolean valid, double percent, long activeCodeSamples, long totalCodeSamples, int blocks) {
			this.valid = valid; this.percent = percent;
			this.activeCodeSamples = activeCodeSamples; this.totalCodeSamples = totalCodeSamples;
			this.blocks = blocks;
		}
	}

	static final class Result {
		final boolean detected;
		final int psc, codeGroup, pschSample, iqOrientation;
		final double pschCorrelation, cpichCorrelation, cfoHz, quality;
		final String state;
		final List<Candidate> candidates;
		Result(boolean detected, int psc, int codeGroup, int pschSample, int iqOrientation,
				double pschCorrelation, double cpichCorrelation, double cfoHz, double quality,
				String state, List<Candidate> candidates) {
			this.detected = detected;
			this.psc = psc;
			this.codeGroup = codeGroup;
			this.pschSample = pschSample;
			this.iqOrientation = iqOrientation;
			this.pschCorrelation = pschCorrelation;
			this.cpichCorrelation = cpichCorrelation;
			this.cfoHz = cfoHz;
			this.quality = quality;
			this.state = state;
			this.candidates = candidates;
		}
		static Result empty(String state) {
			return new Result(false, -1, -1, -1, 1, 0, 0, 0, 0, state,
					Collections.<Candidate>emptyList());
		}
	}
}
