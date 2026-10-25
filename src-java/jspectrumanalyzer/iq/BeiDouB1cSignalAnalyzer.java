package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Progressive FFT acquisition and continuous data tracking for BeiDou B1C at 1575.42 MHz. */
final class BeiDouB1cSignalAnalyzer {
	static final long B1C_FREQUENCY_HZ = 1_575_420_000L;
	static final int ACQUISITION_RATE_HZ = 3_276_800, ACQUISITION_SAMPLES = 32_768;
	private static final int SEARCH_GROUP_SIZE = 1;
	private static final double REQUIRED_HALF_BAND_HZ = 1_620_000d, ACQUISITION_THRESHOLD = .060d;
	private static final double FINE_DOPPLER_THRESHOLD = .050d;
	private static final int FINE_DOPPLER_RANGE_HZ = 60, FINE_DOPPLER_STEP_HZ = 10;
	private static final int[] DOPPLER_BINS_HZ = dopplerBins();
	private final double[][] pilotCodeRe = new double[BeiDouB1cCodes.PRN_COUNT][];
	private final double[][] pilotCodeIm = new double[BeiDouB1cCodes.PRN_COUNT][];
	private final Satellite[] latest = new Satellite[BeiDouB1cCodes.PRN_COUNT];
	private final boolean[] searched = new boolean[BeiDouB1cCodes.PRN_COUNT];
	private final BeiDouB1cStreamTracker streamTracker = new BeiDouB1cStreamTracker();
	private int generation;

	synchronized void reset() {
		Arrays.fill(latest, null); Arrays.fill(searched, false); generation = 0; streamTracker.reset();
	}
	void acceptStream(long centerFreqHz, int sampleRateHz, byte[] iq, int length) {
		streamTracker.accept(centerFreqHz, sampleRateHz, iq, length);
	}

	synchronized Result analyze(long centerFreqHz, int sampleRateHz, byte[] iq, int length) {
		if (allSearched()) return analyzePrnRange(centerFreqHz, sampleRateHz, iq, length, 1, 0);
		int firstPrn = (generation++ * SEARCH_GROUP_SIZE) % BeiDouB1cCodes.PRN_COUNT + 1;
		return analyzePrnRange(centerFreqHz, sampleRateHz, iq, length, firstPrn, SEARCH_GROUP_SIZE);
	}

	synchronized Result analyzePrnRange(long centerFreqHz, int sampleRateHz, byte[] iq, int length,
			int firstPrn, int count) {
		if (iq == null || length < 2 || sampleRateHz <= 0)
			return Result.empty(centerFreqHz, sampleRateHz, "Waiting for BeiDou B1C IQ samples");
		if (Math.abs(centerFreqHz - B1C_FREQUENCY_HZ) + REQUIRED_HALF_BAND_HZ > sampleRateHz / 2d)
			return Result.empty(centerFreqHz, sampleRateHz,
					"IQ window must cover the BeiDou B1C BOC(1,1) main lobes (use at least 3.3 MS/s)");
		int inputSamples = Math.min(length, iq.length) / 2;
		int neededInput = (int) Math.ceil((double) ACQUISITION_SAMPLES * sampleRateHz / ACQUISITION_RATE_HZ) + 2;
		if (inputSamples < neededInput)
			return Result.empty(centerFreqHz, sampleRateHz, "Collecting a complete 10 ms BeiDou B1C code interval");

		double[] sourceRe = new double[ACQUISITION_SAMPLES], sourceIm = new double[ACQUISITION_SAMPLES];
		resampleAndCenter(centerFreqHz, sampleRateHz, iq, inputSamples, neededInput, sourceRe, sourceIm);
		double meanRe = 0d, meanIm = 0d;
		for (int n = 0; n < ACQUISITION_SAMPLES; n++) { meanRe += sourceRe[n]; meanIm += sourceIm[n]; }
		meanRe /= ACQUISITION_SAMPLES; meanIm /= ACQUISITION_SAMPLES;
		double inputEnergy = 0d;
		for (int n = 0; n < ACQUISITION_SAMPLES; n++) {
			sourceRe[n] -= meanRe; sourceIm[n] -= meanIm;
			inputEnergy += sourceRe[n] * sourceRe[n] + sourceIm[n] * sourceIm[n];
		}

		int boundedCount = Math.max(0, Math.min(count, BeiDouB1cCodes.PRN_COUNT));
		List<Integer> selected = new ArrayList<Integer>();
		for (int index = 0; index < boundedCount; index++) {
			int selectedIndex = (Math.max(1, firstPrn) - 1 + index) % BeiDouB1cCodes.PRN_COUNT;
			selected.add(Integer.valueOf(selectedIndex)); searched[selectedIndex] = true;
		}
		// During the initial sweep, refresh active channels every fifth second rather
		// than paying for four 10 ms FFT correlations on every half-second pass.
		boolean refreshActive = boundedCount == 0 || (boundedCount <= SEARCH_GROUP_SIZE && generation % 10 == 0);
		if (refreshActive) for (int activePrn : streamTracker.activePrns()) {
			Integer index = Integer.valueOf(activePrn - 1); if (!selected.contains(index)) selected.add(index);
		}
		int[] indexes = new int[selected.size()];
		for (int index = 0; index < indexes.length; index++) { indexes[index] = selected.get(index).intValue(); ensureCode(indexes[index]); }
		double[] bestPeak = new double[indexes.length], bestNoise = new double[indexes.length];
		int[] bestDoppler = new int[indexes.length], bestPhase = new int[indexes.length];
		Arrays.fill(bestPeak, -1d); Arrays.fill(bestNoise, 1d);
		double[] inputRe = new double[ACQUISITION_SAMPLES], inputIm = new double[ACQUISITION_SAMPLES];
		double[] correlationRe = new double[ACQUISITION_SAMPLES], correlationIm = new double[ACQUISITION_SAMPLES];
		double[] power = new double[ACQUISITION_SAMPLES];
		for (int doppler : DOPPLER_BINS_HZ) {
			double step = -2d * Math.PI * doppler / ACQUISITION_RATE_HZ;
			double stepRe = Math.cos(step), stepIm = Math.sin(step), oscillatorRe = 1d, oscillatorIm = 0d;
			for (int n = 0; n < ACQUISITION_SAMPLES; n++) {
				inputRe[n] = sourceRe[n] * oscillatorRe - sourceIm[n] * oscillatorIm;
				inputIm[n] = sourceRe[n] * oscillatorIm + sourceIm[n] * oscillatorRe;
				double nextRe = oscillatorRe * stepRe - oscillatorIm * stepIm;
				oscillatorIm = oscillatorRe * stepIm + oscillatorIm * stepRe; oscillatorRe = nextRe;
				if ((n & 4095) == 4095) {
					double scale = 1d / Math.hypot(oscillatorRe, oscillatorIm);
					oscillatorRe *= scale; oscillatorIm *= scale;
				}
			}
			fft(inputRe, inputIm, false);
			for (int candidate = 0; candidate < indexes.length; candidate++) {
				correlationPower(inputRe, inputIm, pilotCodeRe[indexes[candidate]], pilotCodeIm[indexes[candidate]],
						correlationRe, correlationIm, power);
				double peak = -1d, sum = 0d; int peakPhase = 0;
				for (int phase = 0; phase < power.length; phase++) {
					sum += power[phase]; if (power[phase] > peak) { peak = power[phase]; peakPhase = phase; }
				}
				double noise = Math.max(1e-12d, (sum - peak) / (power.length - 1));
				if (peak / noise > bestPeak[candidate] / bestNoise[candidate]) {
					bestPeak[candidate] = peak; bestNoise[candidate] = noise;
					bestDoppler[candidate] = doppler; bestPhase[candidate] = peakPhase;
				}
			}
		}
		for (int candidate = 0; candidate < indexes.length; candidate++) {
			double coarseCoherence = Math.sqrt(Math.max(0d, bestPeak[candidate])
					/ Math.max(1e-12d, inputEnergy * ACQUISITION_SAMPLES));
			if (coarseCoherence < FINE_DOPPLER_THRESHOLD) continue;
			int coarseDoppler = bestDoppler[candidate];
			for (int delta = -FINE_DOPPLER_RANGE_HZ; delta <= FINE_DOPPLER_RANGE_HZ; delta += FINE_DOPPLER_STEP_HZ) {
				if (delta == 0) continue; int doppler = coarseDoppler + delta;
				double step = -2d * Math.PI * doppler / ACQUISITION_RATE_HZ;
				double stepRe = Math.cos(step), stepIm = Math.sin(step), oscillatorRe = 1d, oscillatorIm = 0d;
				for (int n = 0; n < ACQUISITION_SAMPLES; n++) {
					inputRe[n] = sourceRe[n] * oscillatorRe - sourceIm[n] * oscillatorIm;
					inputIm[n] = sourceRe[n] * oscillatorIm + sourceIm[n] * oscillatorRe;
					double nextRe = oscillatorRe * stepRe - oscillatorIm * stepIm;
					oscillatorIm = oscillatorRe * stepIm + oscillatorIm * stepRe; oscillatorRe = nextRe;
				}
				fft(inputRe, inputIm, false);
				correlationPower(inputRe, inputIm, pilotCodeRe[indexes[candidate]], pilotCodeIm[indexes[candidate]],
						correlationRe, correlationIm, power);
				double peak = -1d, sumPower = 0d; int peakPhase = 0;
				for (int phase = 0; phase < power.length; phase++) { sumPower += power[phase]; if (power[phase] > peak) { peak = power[phase]; peakPhase = phase; } }
				double noise = Math.max(1e-12d, (sumPower - peak) / (power.length - 1));
				if (peak / noise > bestPeak[candidate] / bestNoise[candidate]) {
					bestPeak[candidate]=peak; bestNoise[candidate]=noise; bestDoppler[candidate]=doppler; bestPhase[candidate]=peakPhase;
				}
			}
		}

		List<Satellite> freshAcquired = new ArrayList<Satellite>();
		for (int candidate = 0; candidate < indexes.length; candidate++) {
			int index = indexes[candidate]; double ratio = bestPeak[candidate] / bestNoise[candidate];
			double coherence = Math.sqrt(Math.max(0d, bestPeak[candidate])
					/ Math.max(1e-12d, inputEnergy * ACQUISITION_SAMPLES));
			double cNo = 10d * Math.log10(Math.max(1e-6d, ratio / .010d));
			latest[index] = new Satellite(index + 1, bestDoppler[candidate], bestPhase[candidate],
					bestPhase[candidate] * BeiDouB1cCodes.CODE_CHIPS / (double) ACQUISITION_SAMPLES,
					ratio, coherence, cNo, coherence >= ACQUISITION_THRESHOLD);
			if (latest[index].acquired) freshAcquired.add(latest[index]);
		}

		List<Satellite> all = new ArrayList<Satellite>(); int scanned = 0;
		for (int index = 0; index < latest.length; index++) { if (searched[index]) scanned++; if (latest[index] != null) all.add(latest[index]); }
		Collections.sort(all, new Comparator<Satellite>() {
			@Override public int compare(Satellite left, Satellite right) { return Double.compare(right.coherence, left.coherence); }
		});
		List<Satellite> acquired = new ArrayList<Satellite>(); for (Satellite satellite : all) if (satellite.acquired) acquired.add(satellite);
		streamTracker.updateAcquisition(centerFreqHz, sampleRateHz, freshAcquired, neededInput);
		List<Satellite> baseShown = acquired.isEmpty() ? all.subList(0, Math.min(8, all.size())) : acquired;
		List<Satellite> shown = new ArrayList<Satellite>();
		for (Satellite satellite : baseShown) shown.add(satellite.withTracking(streamTracker.tracking(satellite.prn)));
		int tracked = streamTracker.trackedCount(), decoded = streamTracker.decodedCount();
		int ephemerides = streamTracker.ephemerisCount();
		List<BeiDouB1cStreamTracker.Observation> observations = streamTracker.observations();
		GpsL1PositionSolver.Solution position = BeiDouB1cPositioning.solve(observations);
		String state = acquired.isEmpty()
				? "Scanning BeiDou B1C PRNs (" + scanned + "/63); strongest candidates shown"
				: acquired.size() + " BeiDou B1C satellite" + (acquired.size() == 1 ? " acquired" : "s acquired")
						+ (tracked > 0 ? "; tracked " + tracked : "; starting tracking")
						+ (decoded > 0 ? "; B-CNAV1 SF1 " + decoded : "")
						+ (ephemerides > 0 ? "; ephemerides " + ephemerides : "")
						+ (position.plausible ? "; position fixed" : "");
		return new Result(centerFreqHz, sampleRateHz, state, shown, acquired.size(), scanned, tracked, decoded,
				ephemerides, position, all, observations);
	}

	private boolean allSearched() {
		for (boolean value : searched) if (!value) return false;
		return true;
	}

	private void ensureCode(int index) {
		if (pilotCodeRe[index] != null) return;
		int[] code = BeiDouB1cCodes.primaryCode(index + 1, true);
		double[] real = new double[ACQUISITION_SAMPLES], imaginary = new double[ACQUISITION_SAMPLES];
		for (int sample = 0; sample < real.length; sample++) {
			int chip = (int) ((long) sample * BeiDouB1cCodes.CODE_CHIPS / real.length);
			int halfChip = (int) ((long) sample * BeiDouB1cCodes.CODE_CHIPS * 2L / real.length) & 1;
			real[sample] = code[chip] * (halfChip == 0 ? 1d : -1d);
		}
		fft(real, imaginary, false); pilotCodeRe[index] = real; pilotCodeIm[index] = imaginary;
	}

	private static void resampleAndCenter(long centerFreqHz, int sampleRateHz, byte[] iq, int inputSamples,
			int neededInput, double[] outputRe, double[] outputIm) {
		int first = Math.max(0, inputSamples - neededInput); double inputStep = sampleRateHz / (double) ACQUISITION_RATE_HZ;
		double offsetHz = B1C_FREQUENCY_HZ - centerFreqHz;
		for (int n = 0; n < outputRe.length; n++) {
			double position = first + n * inputStep; int index = Math.min(inputSamples - 2, (int) position);
			double fraction = position - index;
			double i = iq[index * 2] * (1d - fraction) + iq[(index + 1) * 2] * fraction;
			double q = iq[index * 2 + 1] * (1d - fraction) + iq[(index + 1) * 2 + 1] * fraction;
			double phase = -2d * Math.PI * offsetHz * (position - first) / sampleRateHz;
			double cosine = Math.cos(phase), sine = Math.sin(phase);
			outputRe[n] = i * cosine - q * sine; outputIm[n] = i * sine + q * cosine;
		}
	}

	private static void correlationPower(double[] inputRe, double[] inputIm, double[] replicaRe, double[] replicaIm,
			double[] correlationRe, double[] correlationIm, double[] power) {
		for (int n = 0; n < inputRe.length; n++) {
			correlationRe[n] = inputRe[n] * replicaRe[n] + inputIm[n] * replicaIm[n];
			correlationIm[n] = inputIm[n] * replicaRe[n] - inputRe[n] * replicaIm[n];
		}
		fft(correlationRe, correlationIm, true);
		for (int n = 0; n < power.length; n++) power[n] = correlationRe[n] * correlationRe[n] + correlationIm[n] * correlationIm[n];
	}

	private static int[] dopplerBins() {
		int[] bins = new int[101]; for (int index = 0; index < bins.length; index++) bins[index] = -5000 + index * 100; return bins;
	}
	private static void fft(double[] real, double[] imaginary, boolean inverse) {
		int size = real.length;
		for (int n = 1, reversed = 0; n < size; n++) {
			int bit = size >>> 1; for (; (reversed & bit) != 0; bit >>>= 1) reversed ^= bit; reversed ^= bit;
			if (n < reversed) { double value = real[n]; real[n] = real[reversed]; real[reversed] = value;
				value = imaginary[n]; imaginary[n] = imaginary[reversed]; imaginary[reversed] = value; }
		}
		for (int width = 2; width <= size; width <<= 1) {
			double angle = (inverse ? 2d : -2d) * Math.PI / width, widthRe = Math.cos(angle), widthIm = Math.sin(angle);
			for (int start = 0; start < size; start += width) {
				double wRe = 1d, wIm = 0d;
				for (int n = 0; n < width / 2; n++) {
					int left = start + n, right = left + width / 2;
					double r = real[right] * wRe - imaginary[right] * wIm, i = real[right] * wIm + imaginary[right] * wRe;
					real[right] = real[left] - r; imaginary[right] = imaginary[left] - i;
					real[left] += r; imaginary[left] += i;
					double nextRe = wRe * widthRe - wIm * widthIm; wIm = wRe * widthIm + wIm * widthRe; wRe = nextRe;
				}
			}
		}
		if (inverse) for (int n = 0; n < size; n++) { real[n] /= size; imaginary[n] /= size; }
	}

	static final class Satellite {
		final int prn, dopplerHz, codePhaseSamples; final double codePhaseChips, peakToNoise, coherence, cNoDbHz;
		final boolean acquired, trackingLocked; final long trackingSymbols; final double trackingStability;
		final String navState; final BeiDouB1cNavDecoder.Frame frame; final BeiDouB1cNavigationData.Snapshot navigation;
		Satellite(int prn, int dopplerHz, int codePhaseSamples, double codePhaseChips,
				double peakToNoise, double coherence, double cNoDbHz, boolean acquired) {
			this.prn = prn; this.dopplerHz = dopplerHz; this.codePhaseSamples = codePhaseSamples;
			this.codePhaseChips = codePhaseChips; this.peakToNoise = peakToNoise; this.coherence = coherence;
			this.cNoDbHz = cNoDbHz; this.acquired = acquired; trackingLocked = false; trackingSymbols = 0L;
			trackingStability = 0d; navState = "Not tracked"; frame = BeiDouB1cNavDecoder.Frame.waiting();
			navigation = BeiDouB1cNavigationData.Snapshot.waiting();
		}
		private Satellite(Satellite source, BeiDouB1cStreamTracker.Tracking tracking) {
			prn = source.prn; dopplerHz = source.dopplerHz; codePhaseSamples = source.codePhaseSamples;
			codePhaseChips = source.codePhaseChips; peakToNoise = source.peakToNoise; coherence = source.coherence;
			cNoDbHz = source.cNoDbHz; acquired = source.acquired; trackingLocked = tracking.locked;
			trackingSymbols = tracking.symbols; trackingStability = tracking.stability; navState = tracking.navState;
			frame = tracking.frame; navigation = tracking.navigation;
		}
		Satellite withTracking(BeiDouB1cStreamTracker.Tracking tracking) { return new Satellite(this, tracking); }
	}

	static final class Result {
		final long centerFreqHz; final int sampleRateHz; final String state; final List<Satellite> satellites, candidates;
		final int acquiredCount, scannedCount, trackedCount, decodedCount, ephemerisCount;
		final GpsL1PositionSolver.Solution position; final List<BeiDouB1cStreamTracker.Observation> observations;
		Result(long centerFreqHz, int sampleRateHz, String state, List<Satellite> satellites,
				int acquiredCount, int scannedCount, int trackedCount, int decodedCount, int ephemerisCount,
				GpsL1PositionSolver.Solution position, List<Satellite> candidates,
				List<BeiDouB1cStreamTracker.Observation> observations) {
			this.centerFreqHz = centerFreqHz; this.sampleRateHz = sampleRateHz; this.state = state;
			this.satellites = Collections.unmodifiableList(new ArrayList<Satellite>(satellites));
			this.candidates = Collections.unmodifiableList(new ArrayList<Satellite>(candidates));
			this.acquiredCount = acquiredCount; this.scannedCount = scannedCount;
			this.trackedCount = trackedCount; this.decodedCount = decodedCount; this.ephemerisCount = ephemerisCount;
			this.position = position; this.observations = Collections.unmodifiableList(
					new ArrayList<BeiDouB1cStreamTracker.Observation>(observations));
		}
		static Result empty(long centerFreqHz, int sampleRateHz, String state) {
			return new Result(centerFreqHz, sampleRateHz, state, Collections.<Satellite>emptyList(), 0, 0, 0, 0, 0,
					GpsL1PositionSolver.Solution.waiting("Waiting for BeiDou navigation data"),
					Collections.<Satellite>emptyList(), Collections.<BeiDouB1cStreamTracker.Observation>emptyList());
		}
	}
}
