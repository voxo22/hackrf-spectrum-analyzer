package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Coarse GPS L1 C/A acquisition for a live or replayed IQ stream.  It searches
 * the civilian 1 ms Gold codes (PRN 1..32) and reports code delay and Doppler.
 * Navigation-bit tracking and PVT are deliberately separate stages.
 */
final class GpsL1SignalAnalyzer {
	static final long L1_FREQUENCY_HZ = 1_575_420_000L;
	private static final double CA_CHIP_RATE_HZ = 1_023_000d;
	private static final int PRN_COUNT = 32;
	private static final int MIN_SAMPLE_RATE_HZ = 2_200_000;
	private static final double REQUIRED_HALF_BAND_HZ = 1_050_000d;
	private static final int MAX_FFT_SIZE = 8192;
	private static final int TRACKING_WINDOW_MILLIS = 20;
	private static final int[] DOPPLER_BINS_HZ = {
			-5_000, -4_500, -4_000, -3_500, -3_000, -2_500, -2_000, -1_500, -1_000,
			-500, 0, 500, 1_000, 1_500, 2_000, 2_500, 3_000, 3_500, 4_000, 4_500, 5_000 };
	/* A noise-only 1 ms correlation peak is normally below 0.08 after this
	 * 21-bin search.  0.12 retains the clearly separated real L1 peaks while
	 * rejecting Gold-code cross-correlation in an 8-bit replay. */
	private static final double ACQUISITION_THRESHOLD = 0.12d;
	private final Map<String, CodeSpectra> codeSpectra = new HashMap<String, CodeSpectra>();
	private final GpsL1StreamTracker streamTracker = new GpsL1StreamTracker();
	private final int[] previousDopplers = new int[PRN_COUNT];
	private long searchCenterHz = Long.MIN_VALUE;
	private int searchRateHz, searchGeneration;

	void acceptStream(long centerFreqHz, int sampleRateHz, byte[] iq, int length) {
		streamTracker.accept(centerFreqHz, sampleRateHz, iq, length);
	}
	void resetStream() { streamTracker.reset(); synchronized (this) { searchCenterHz = Long.MIN_VALUE; searchGeneration = 0; } }
	List<GpsL1StreamTracker.Observation> observations() { return streamTracker.observations(); }

	synchronized Result analyze(long centerFreqHz, int sampleRateHz, byte[] iq, int length) {
		if (iq == null || length < 2 || sampleRateHz <= 0)
			return Result.empty(centerFreqHz, sampleRateHz, "Waiting for IQ samples");
		double halfRate = sampleRateHz / 2d;
		if (Math.abs(centerFreqHz - L1_FREQUENCY_HZ) + REQUIRED_HALF_BAND_HZ > halfRate)
			return Result.empty(centerFreqHz, sampleRateHz,
					"IQ window must cover GPS L1 at 1575.420 MHz (at least 2.2 MS/s)");
		if (sampleRateHz < MIN_SAMPLE_RATE_HZ)
			return Result.empty(centerFreqHz, sampleRateHz, "GPS L1 C/A acquisition needs at least 2.2 MS/s");
		int available = length / 2;
		int samplesPerMs = (int) Math.round(sampleRateHz / 1000d);
		if (available < samplesPerMs)
			return Result.empty(centerFreqHz, sampleRateHz, "Collecting a complete 1 ms GPS C/A interval");
		/* Zero padding to at least 2N-1 permits a linear FFT correlation.  Its
		 * two non-overlapping pieces are combined below into the true N-sample
		 * circular C/A correlation; an FFT-size circular correlation is not the
		 * same thing when N is not a power of two. */
		int fftSize = nextPowerOfTwo(2 * samplesPerMs - 1);
		if (fftSize > MAX_FFT_SIZE)
			return Result.empty(centerFreqHz, sampleRateHz, "Sample rate is too high for the GPS acquisition window");
		CodeSpectra codes = codes(sampleRateHz, samplesPerMs, fftSize);
		if (searchCenterHz != centerFreqHz || searchRateHz != sampleRateHz) {
			searchCenterHz = centerFreqHz; searchRateHz = sampleRateHz; searchGeneration = 0;
		}
		/* Doppler changes slowly. Search near each PRN's last bin most of the
		 * time. After the initial sweep, spread the complete search across ten
		 * acquisitions so the periodic reacquisition does not stall the IQ worker. */
		int generation = searchGeneration++;
		int first = available - samplesPerMs;
		double meanI = 0d, meanQ = 0d;
		for (int n = 0; n < samplesPerMs; n++) { meanI += iq[(first + n) * 2]; meanQ += iq[(first + n) * 2 + 1]; }
		meanI /= samplesPerMs; meanQ /= samplesPerMs;
		double inputEnergy = 0d;
		for (int n = 0; n < samplesPerMs; n++) {
			double i = iq[(first + n) * 2] - meanI, q = iq[(first + n) * 2 + 1] - meanQ;
			inputEnergy += i * i + q * q;
		}
		List<Satellite> all = new ArrayList<Satellite>();
		double[] inputRe = new double[fftSize], inputIm = new double[fftSize];
		double[] correlationRe = new double[fftSize], correlationIm = new double[fftSize];
		double[] bestPeaks = new double[PRN_COUNT], bestNoises = new double[PRN_COUNT], bestPrecisePhases = new double[PRN_COUNT];
		int[] bestDopplers = new int[PRN_COUNT], bestPhases = new int[PRN_COUNT];
		Arrays.fill(bestPeaks, -1d); Arrays.fill(bestNoises, 1d);
		/* Doppler wipe-off and the forward FFT depend only on the IQ block, not
		 * the PRN.  Share them across all satellite codes at each Doppler bin. */
		for (int doppler : DOPPLER_BINS_HZ) {
			Arrays.fill(inputRe, 0d); Arrays.fill(inputIm, 0d);
			double step = -2d * Math.PI * doppler / sampleRateHz;
			for (int n = 0; n < samplesPerMs; n++) {
				double phase = step * n, cosine = Math.cos(phase), sine = Math.sin(phase);
				double i = iq[(first + n) * 2] - meanI, q = iq[(first + n) * 2 + 1] - meanQ;
				inputRe[n] = i * cosine - q * sine;
				inputIm[n] = i * sine + q * cosine;
			}
			fft(inputRe, inputIm, false);
			for (int prn = 0; prn < PRN_COUNT; prn++) {
				boolean fullSearchForPrn = generation == 0 || prn % 10 == (generation - 1) % 10;
				if (!fullSearchForPrn && Math.abs(doppler - previousDopplers[prn]) > 1000) continue;
				double[] codeRe = codes.re[prn], codeIm = codes.im[prn];
				for (int n = 0; n < fftSize; n++) {
					correlationRe[n] = inputRe[n] * codeRe[n] + inputIm[n] * codeIm[n];
					correlationIm[n] = inputIm[n] * codeRe[n] - inputRe[n] * codeIm[n];
				}
				fft(correlationRe, correlationIm, true);
				double peak = -1d, sum = 0d;
				int phase = 0;
				for (int n = 0; n < samplesPerMs; n++) {
					/* lag -n gives the non-wrapped part; lag N-n supplies the
					 * wrapped tail.  The resulting n is the replica's forward phase. */
					int mainIndex = n == 0 ? 0 : fftSize - n;
					int wrapIndex = samplesPerMs - n;
					double re = correlationRe[mainIndex] + (n == 0 ? 0d : correlationRe[wrapIndex]);
					double im = correlationIm[mainIndex] + (n == 0 ? 0d : correlationIm[wrapIndex]);
					double power = re * re + im * im;
					sum += power;
					if (power > peak) { peak = power; phase = n; }
				}
				double noise = Math.max(1d, (sum - peak) / Math.max(1, samplesPerMs - 1));
				if (peak / noise > bestPeaks[prn] / bestNoises[prn]) {
					bestPeaks[prn] = peak; bestNoises[prn] = noise; bestDopplers[prn] = doppler; bestPhases[prn] = phase;
					double left = Math.sqrt(correlationPower(correlationRe, correlationIm, (phase + samplesPerMs - 1) % samplesPerMs, samplesPerMs));
					double right = Math.sqrt(correlationPower(correlationRe, correlationIm, (phase + 1) % samplesPerMs, samplesPerMs));
					double amplitude = Math.sqrt(Math.max(0d, peak)), slope = amplitude - Math.min(left, right);
					/* The C/A autocorrelation peak is approximately triangular. Use
					 * neighbouring magnitudes to estimate its sub-sample position. */
					double offset = slope > 1e-9 ? Math.max(-.5d, Math.min(.5d, (right - left) / (2d * slope))) : 0d;
					bestPrecisePhases[prn] = (phase + offset + samplesPerMs) % samplesPerMs;
				}
			}
		}
		for (int prn = 0; prn < PRN_COUNT; prn++) {
			double bestPeak = bestPeaks[prn], bestNoise = bestNoises[prn];
			int bestDoppler = bestDopplers[prn], bestPhase = bestPhases[prn];
			previousDopplers[prn] = bestDoppler;
			double ratio = bestPeak / bestNoise;
			double coherence = Math.sqrt(bestPeak / Math.max(1d, inputEnergy * samplesPerMs));
			/* A 1 ms coherent integration converts the correlator power ratio to a
			 * useful C/N0 estimate.  It is deliberately marked as an estimate until
			 * a carrier/code tracking loop is running. */
			double cNoDbHz = 10d * Math.log10(Math.max(0.000001d, ratio * 1000d));
			boolean acquired = coherence >= ACQUISITION_THRESHOLD;
			Tracking tracking = acquired
					? track(prn + 1, bestDoppler, bestPhase, sampleRateHz, iq, first + samplesPerMs) : Tracking.NONE;
			int displayedDoppler = tracking.milliseconds == 0 ? bestDoppler : tracking.refinedDopplerHz;
			all.add(new Satellite(prn + 1, bestDoppler, displayedDoppler, bestPhase,
					bestPrecisePhases[prn] * CA_CHIP_RATE_HZ / sampleRateHz, ratio, coherence, cNoDbHz, tracking.milliseconds,
					tracking.phaseStability, acquired, tracking.locked));
		}
		Collections.sort(all, new Comparator<Satellite>() {
			@Override public int compare(Satellite a, Satellite b) { return Double.compare(b.coherence, a.coherence); }
		});
		List<Satellite> acquired = new ArrayList<Satellite>();
		for (Satellite satellite : all) if (satellite.acquired) acquired.add(satellite);
		List<Satellite> shown = acquired.isEmpty() ? all.subList(0, Math.min(8, all.size())) : acquired;
		int trackedCount = 0; for (Satellite satellite : acquired) if (satellite.trackingLocked) trackedCount++;
		String state = acquired.isEmpty() ? "No GPS L1 C/A PRN acquired yet; showing strongest candidates"
				: acquired.size() + " GPS L1 C/A satellite" + (acquired.size() == 1 ? " acquired" : "s acquired");
		if (trackedCount > 0) state += "; prompt lock " + trackedCount;
		streamTracker.updateAcquisition(centerFreqHz, sampleRateHz, acquired);
		List<GpsL1StreamTracker.Observation> observations = streamTracker.observations();
		GpsL1PositionSolver.Solution position = GpsL1Positioning.solve(observations);
		String gpsTime = "--";
		if (position.plausible && !observations.isEmpty()) {
			String formatted = GpsL1NavigationData.formatGpsTime(observations.get(0).ephemeris.weekModulo1024, position.receiverTow);
			if (formatted != null) gpsTime = formatted;
		}
		String utc = "Waiting for LNAV UTC parameters";
		if (position.plausible) for (GpsL1StreamTracker.Observation observation : observations) {
			if (observation.utc == null) continue;
			String formatted = observation.utc.format(observation.ephemeris.weekModulo1024, position.receiverTow);
			if (formatted != null) { utc = formatted; break; }
			utc = "UTC parameters unavailable or leap change pending";
		}
		for (int index = 0; index < shown.size(); index++) {
			Satellite satellite = shown.get(index);
			shown.set(index, satellite.withNavigation(streamTracker.navState(satellite.prn), streamTracker.navigation(satellite.prn)));
		}
		if (streamTracker.decodedCount() > 0) state += "; LNAV parity valid " + streamTracker.decodedCount();
		return new Result(centerFreqHz, sampleRateHz, samplesPerMs, state, new ArrayList<Satellite>(shown),
				acquired.size(), trackedCount, position, utc, gpsTime, new ArrayList<Satellite>(all), observations);
	}

	private static double correlationPower(double[] re, double[] im, int phase, int samplesPerMs) {
		int main = phase == 0 ? 0 : re.length - phase, wrap = samplesPerMs - phase;
		double real = re[main] + (phase == 0 ? 0d : re[wrap]);
		double imaginary = im[main] + (phase == 0 ? 0d : im[wrap]);
		return real * real + imaginary * imaginary;
	}

	/* Verifies the acquired code delay over a short contiguous window and refines
	 * Doppler from the phase change of squared prompt correlations. Squaring
	 * removes the 180-degree LNAV data-bit changes without pretending that a NAV
	 * frame has already been decoded. */
	private Tracking track(int prn, int dopplerHz, int codePhaseSamples, int sampleRateHz, byte[] iq, int endSample) {
		int samplesPerMs = (int) Math.round(sampleRateHz / 1000d);
		int availableMilliseconds = Math.min(TRACKING_WINDOW_MILLIS, endSample / samplesPerMs);
		if (availableMilliseconds < 2) return Tracking.NONE;
		int firstSample = endSample - availableMilliseconds * samplesPerMs;
		int[] chips = caCode(prn);
		double previousRe = 0d, previousIm = 0d, phaseRe = 0d, phaseIm = 0d;
		double step = -2d * Math.PI * dopplerHz / sampleRateHz;
		double stepRe = Math.cos(step), stepIm = Math.sin(step), oscillatorRe = 1d, oscillatorIm = 0d;
		int phasePairs = 0;
		for (int millisecond = 0; millisecond < availableMilliseconds; millisecond++) {
			double promptRe = 0d, promptIm = 0d;
			int base = firstSample + millisecond * samplesPerMs;
			for (int n = 0; n < samplesPerMs; n++) {
				int chip = (int) (((long) n + codePhaseSamples) * 1_023_000L / sampleRateHz) % 1023;
				double value = chips[chip];
				double i = iq[(base + n) * 2], q = iq[(base + n) * 2 + 1];
				promptRe += value * (i * oscillatorRe - q * oscillatorIm);
				promptIm += value * (i * oscillatorIm + q * oscillatorRe);
				double nextRe = oscillatorRe * stepRe - oscillatorIm * stepIm;
				oscillatorIm = oscillatorRe * stepIm + oscillatorIm * stepRe; oscillatorRe = nextRe;
			}
			if (millisecond > 0) {
				/* (current^2) * conjugate(previous^2): navigation data sign cancels. */
				double currentSquaredRe = promptRe * promptRe - promptIm * promptIm;
				double currentSquaredIm = 2d * promptRe * promptIm;
				double previousSquaredRe = previousRe * previousRe - previousIm * previousIm;
				double previousSquaredIm = 2d * previousRe * previousIm;
				double re = currentSquaredRe * previousSquaredRe + currentSquaredIm * previousSquaredIm;
				double im = currentSquaredIm * previousSquaredRe - currentSquaredRe * previousSquaredIm;
				double magnitude = Math.hypot(re, im);
				if (magnitude > 1d) { phaseRe += re / magnitude; phaseIm += im / magnitude; phasePairs++; }
			}
			previousRe = promptRe; previousIm = promptIm;
		}
		if (phasePairs == 0) return new Tracking(availableMilliseconds, dopplerHz, 0d, false);
		double stability = Math.hypot(phaseRe, phaseIm) / phasePairs;
		double residualHz = Math.atan2(phaseIm, phaseRe) / (4d * Math.PI * .001d);
		return new Tracking(availableMilliseconds, (int) Math.round(dopplerHz + residualHz), stability,
				availableMilliseconds >= 10 && stability >= .25d);
	}

	private CodeSpectra codes(int sampleRateHz, int samplesPerMs, int fftSize) {
		String key = sampleRateHz + "/" + samplesPerMs + "/" + fftSize;
		CodeSpectra cached = codeSpectra.get(key); if (cached != null) return cached;
		double[][] real = new double[PRN_COUNT][fftSize], imaginary = new double[PRN_COUNT][fftSize];
		for (int prn = 1; prn <= PRN_COUNT; prn++) {
			int[] chips = caCode(prn);
			for (int n = 0; n < samplesPerMs; n++) real[prn - 1][n] = chips[(int) ((long) n * 1_023_000L / sampleRateHz) % 1023];
			fft(real[prn - 1], imaginary[prn - 1], false);
		}
		cached = new CodeSpectra(real, imaginary); codeSpectra.put(key, cached); return cached;
	}

	static int[] caCode(int prn) {
		if (prn < 1 || prn > PRN_COUNT) throw new IllegalArgumentException("GPS PRN must be 1..32");
		int[][] taps = {{2,6},{3,7},{4,8},{5,9},{1,9},{2,10},{1,8},{2,9},{3,10},{2,3},{3,4},{5,6},{6,7},{7,8},{8,9},{9,10},
				{1,4},{2,5},{3,6},{4,7},{5,8},{6,9},{1,3},{4,6},{5,7},{6,8},{7,9},{8,10},{1,6},{2,7},{3,8},{4,9}};
		int[] g1 = new int[10], g2 = new int[10], output = new int[1023];
		Arrays.fill(g1, 1); Arrays.fill(g2, 1);
		for (int n = 0; n < output.length; n++) {
			int outputBit = g1[9] ^ g2[taps[prn - 1][0] - 1] ^ g2[taps[prn - 1][1] - 1];
			output[n] = outputBit == 0 ? 1 : -1;
			int g1Feedback = g1[2] ^ g1[9];
			int g2Feedback = g2[1] ^ g2[2] ^ g2[5] ^ g2[7] ^ g2[8] ^ g2[9];
			System.arraycopy(g1, 0, g1, 1, 9); System.arraycopy(g2, 0, g2, 1, 9);
			g1[0] = g1Feedback; g2[0] = g2Feedback;
		}
		return output;
	}

	private static int nextPowerOfTwo(int value) { int result = 1; while (result < value) result <<= 1; return result; }
	private static void fft(double[] real, double[] imaginary, boolean inverse) {
		int size = real.length;
		for (int n = 1, reversed = 0; n < size; n++) {
			int bit = size >>> 1; for (; (reversed & bit) != 0; bit >>>= 1) reversed ^= bit; reversed ^= bit;
			if (n < reversed) { double value = real[n]; real[n] = real[reversed]; real[reversed] = value;
				value = imaginary[n]; imaginary[n] = imaginary[reversed]; imaginary[reversed] = value; }
		}
		for (int width = 2; width <= size; width <<= 1) {
			double angle = (inverse ? 2d : -2d) * Math.PI / width;
			double wLenRe = Math.cos(angle), wLenIm = Math.sin(angle);
			for (int start = 0; start < size; start += width) {
				double wRe = 1d, wIm = 0d;
				for (int n = 0; n < width / 2; n++) {
					int left = start + n, right = left + width / 2;
					double r = real[right] * wRe - imaginary[right] * wIm;
					double i = real[right] * wIm + imaginary[right] * wRe;
					real[right] = real[left] - r; imaginary[right] = imaginary[left] - i;
					real[left] += r; imaginary[left] += i;
					double nextRe = wRe * wLenRe - wIm * wLenIm;
					wIm = wRe * wLenIm + wIm * wLenRe; wRe = nextRe;
				}
			}
		}
		if (inverse) for (int n = 0; n < size; n++) { real[n] /= size; imaginary[n] /= size; }
	}

	private static final class CodeSpectra { final double[][] re, im; CodeSpectra(double[][] re, double[][] im) { this.re = re; this.im = im; } }
	private static final class Tracking {
		static final Tracking NONE = new Tracking(0, 0, 0d, false);
		final int milliseconds, refinedDopplerHz; final double phaseStability; final boolean locked;
		Tracking(int milliseconds, int refinedDopplerHz, double phaseStability, boolean locked) {
			this.milliseconds = milliseconds; this.refinedDopplerHz = refinedDopplerHz; this.phaseStability = phaseStability; this.locked = locked;
		}
	}
	static final class Satellite {
		final int prn, acquisitionDopplerHz, dopplerHz, codePhaseSamples, trackingMilliseconds;
		final double codePhaseChips, peakToNoise, coherence, cNoDbHz, trackingStability; final boolean acquired, trackingLocked;
		final String navState;
		final GpsL1NavigationData.Snapshot navigation;
		Satellite(int prn, int acquisitionDopplerHz, int dopplerHz, int codePhaseSamples, double codePhaseChips,
				double peakToNoise, double coherence, double cNoDbHz, int trackingMilliseconds, double trackingStability,
				boolean acquired, boolean trackingLocked) {
			this(prn, acquisitionDopplerHz, dopplerHz, codePhaseSamples, codePhaseChips, peakToNoise, coherence, cNoDbHz,
					trackingMilliseconds, trackingStability, acquired, trackingLocked, "Waiting", null);
		}
		private Satellite(int prn, int acquisitionDopplerHz, int dopplerHz, int codePhaseSamples, double codePhaseChips,
				double peakToNoise, double coherence, double cNoDbHz, int trackingMilliseconds, double trackingStability,
				boolean acquired, boolean trackingLocked, String navState, GpsL1NavigationData.Snapshot navigation) {
			this.prn = prn; this.acquisitionDopplerHz = acquisitionDopplerHz; this.dopplerHz = dopplerHz;
			this.codePhaseSamples = codePhaseSamples; this.codePhaseChips = codePhaseChips; this.peakToNoise = peakToNoise;
			this.coherence = coherence; this.cNoDbHz = cNoDbHz; this.trackingMilliseconds = trackingMilliseconds;
			this.trackingStability = trackingStability; this.acquired = acquired; this.trackingLocked = trackingLocked; this.navState = navState;
			this.navigation = navigation;
		}
		Satellite withNavigation(String value, GpsL1NavigationData.Snapshot navigation) { return new Satellite(prn, acquisitionDopplerHz, dopplerHz, codePhaseSamples,
				codePhaseChips, peakToNoise, coherence, cNoDbHz, trackingMilliseconds, trackingStability, acquired,
				trackingLocked, value, navigation); }
	}
	static final class Result {
		final long centerFreqHz; final int sampleRateHz, samplesPerMs, acquiredCount, trackedCount; final String state;
		final List<Satellite> satellites, candidates;
		final List<GpsL1StreamTracker.Observation> observations;
		final GpsL1PositionSolver.Solution position;
		final String utc, gpsTime;
		Result(long centerFreqHz, int sampleRateHz, int samplesPerMs, String state, List<Satellite> satellites, int acquiredCount, int trackedCount) {
			this(centerFreqHz, sampleRateHz, samplesPerMs, state, satellites, acquiredCount, trackedCount,
					GpsL1PositionSolver.Solution.waiting("Waiting for 4 timed satellites"));
		}
		Result(long centerFreqHz, int sampleRateHz, int samplesPerMs, String state, List<Satellite> satellites, int acquiredCount, int trackedCount,
				GpsL1PositionSolver.Solution position) {
			this(centerFreqHz, sampleRateHz, samplesPerMs, state, satellites, acquiredCount, trackedCount, position, "Waiting for LNAV UTC parameters");
		}
		Result(long centerFreqHz, int sampleRateHz, int samplesPerMs, String state, List<Satellite> satellites, int acquiredCount, int trackedCount,
				GpsL1PositionSolver.Solution position, String utc) {
			this(centerFreqHz, sampleRateHz, samplesPerMs, state, satellites, acquiredCount, trackedCount, position, utc, "--");
		}
		Result(long centerFreqHz, int sampleRateHz, int samplesPerMs, String state, List<Satellite> satellites, int acquiredCount, int trackedCount,
				GpsL1PositionSolver.Solution position, String utc, String gpsTime) {
			this(centerFreqHz, sampleRateHz, samplesPerMs, state, satellites, acquiredCount, trackedCount,
					position, utc, gpsTime, satellites);
		}
		Result(long centerFreqHz, int sampleRateHz, int samplesPerMs, String state, List<Satellite> satellites, int acquiredCount, int trackedCount,
				GpsL1PositionSolver.Solution position, String utc, String gpsTime, List<Satellite> candidates) {
			this(centerFreqHz, sampleRateHz, samplesPerMs, state, satellites, acquiredCount, trackedCount,
					position, utc, gpsTime, candidates, Collections.<GpsL1StreamTracker.Observation>emptyList());
		}
		Result(long centerFreqHz, int sampleRateHz, int samplesPerMs, String state, List<Satellite> satellites, int acquiredCount, int trackedCount,
				GpsL1PositionSolver.Solution position, String utc, String gpsTime, List<Satellite> candidates,
				List<GpsL1StreamTracker.Observation> observations) {
			this.centerFreqHz = centerFreqHz; this.sampleRateHz = sampleRateHz; this.samplesPerMs = samplesPerMs;
			this.state = state; this.satellites = Collections.unmodifiableList(satellites);
			this.candidates = Collections.unmodifiableList(candidates);
			this.acquiredCount = acquiredCount; this.trackedCount = trackedCount;
			this.position = position; this.utc = utc; this.gpsTime = gpsTime;
			this.observations = Collections.unmodifiableList(new ArrayList<GpsL1StreamTracker.Observation>(observations));
		}
		Result withPosition(GpsL1PositionSolver.Solution value, String utcValue, String gpsTimeValue) {
			return new Result(centerFreqHz, sampleRateHz, samplesPerMs, state, new ArrayList<Satellite>(satellites),
					acquiredCount, trackedCount, value, utcValue, gpsTimeValue,
					new ArrayList<Satellite>(candidates), new ArrayList<GpsL1StreamTracker.Observation>(observations));
		}
		static Result empty(long centerFreqHz, int sampleRateHz, String state) {
			return new Result(centerFreqHz, sampleRateHz, 0, state, Collections.<Satellite>emptyList(), 0, 0);
		}
	}
}
