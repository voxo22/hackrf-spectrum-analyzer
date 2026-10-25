package jspectrumanalyzer.iq;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Reduced-band Galileo E1-B/C acquisition. The dominant BOC(1,1) components
 * are correlated against both official 4092-chip primary codes and their
 * powers are combined at the common code delay. Continuous E1-B tracking,
 * I/NAV decoding and ephemeris assembly start after pilot-assisted acquisition.
 */
final class GalileoE1SignalAnalyzer {
	static final long E1_FREQUENCY_HZ = 1_575_420_000L;
	static final int ACQUISITION_RATE_HZ = 4_096_000;
	private static final int CODE_CHIPS = 4092;
	private static final int CODE_SAMPLES = 16_384; // exactly 4 ms at 4.096 MS/s
	private static final int COHERENT_SAMPLES = CODE_SAMPLES / 2; // 2 ms avoids 250 Hz Doppler nulls
	private static final int SVID_COUNT = 36;
	private static final int SEARCH_GROUP_SIZE = 3;
	private static final double REQUIRED_HALF_BAND_HZ = 2_047_000d;
	private static final double ACQUISITION_THRESHOLD = .16d;
	private static final int[] DOPPLER_BINS_HZ = {
			-5_000, -4_500, -4_000, -3_500, -3_000, -2_500, -2_000, -1_500, -1_000,
			-500, 0, 500, 1_000, 1_500, 2_000, 2_500, 3_000, 3_500, 4_000, 4_500, 5_000 };
	private static final int FINE_DOPPLER_RANGE_HZ = 250, FINE_DOPPLER_STEP_HZ = 50;
	private static final double FINE_DOPPLER_MIN_COHERENCE = .095d;
	private static final int[][] DATA_PRIMARY_CODES = loadPrimaryCodes("galileo-e1b-codes.csv", "E1-B");
	private static final int[][] PILOT_PRIMARY_CODES = loadPrimaryCodes("galileo-e1c-codes.csv", "E1-C");

	private final double[][] dataCodeRe = new double[SVID_COUNT][];
	private final double[][] dataCodeIm = new double[SVID_COUNT][];
	private final double[][] pilotCodeRe = new double[SVID_COUNT][];
	private final double[][] pilotCodeIm = new double[SVID_COUNT][];
	private final Satellite[] latest = new Satellite[SVID_COUNT];
	private final boolean[] searched = new boolean[SVID_COUNT];
	private final GalileoE1StreamTracker streamTracker = new GalileoE1StreamTracker();
	private int generation;

	synchronized void reset() {
		Arrays.fill(latest, null);
		Arrays.fill(searched, false);
		generation = 0;
		streamTracker.reset();
	}

	void acceptStream(long centerFreqHz, int sampleRateHz, byte[] iq, int length) {
		streamTracker.accept(centerFreqHz, sampleRateHz, iq, length);
	}
	List<GalileoE1StreamTracker.Observation> observations() { return streamTracker.observations(); }

	synchronized Result analyze(long centerFreqHz, int sampleRateHz, byte[] iq, int length) {
		int firstSvid = (generation++ * SEARCH_GROUP_SIZE) % SVID_COUNT + 1;
		return analyzeSvidRange(centerFreqHz, sampleRateHz, iq, length, firstSvid, SEARCH_GROUP_SIZE);
	}

	synchronized Result analyzeSvidRange(long centerFreqHz, int sampleRateHz, byte[] iq, int length,
			int firstSvid, int count) {
		if (iq == null || length < 2 || sampleRateHz <= 0)
			return Result.empty(centerFreqHz, sampleRateHz, "Waiting for Galileo E1 IQ samples");
		if (Math.abs(centerFreqHz - E1_FREQUENCY_HZ) + REQUIRED_HALF_BAND_HZ > sampleRateHz / 2d)
			return Result.empty(centerFreqHz, sampleRateHz,
					"IQ window must cover the Galileo E1 main lobes (use at least 4.2 MS/s)");
		int inputSamples = Math.min(length, iq.length) / 2;
		int neededInput = (int) Math.ceil((double) COHERENT_SAMPLES * sampleRateHz / ACQUISITION_RATE_HZ) + 2;
		if (inputSamples < neededInput)
			return Result.empty(centerFreqHz, sampleRateHz, "Collecting a 2 ms Galileo E1 interval");

		double[] sourceRe = new double[COHERENT_SAMPLES];
		double[] sourceIm = new double[COHERENT_SAMPLES];
		resampleAndCenter(centerFreqHz, sampleRateHz, iq, inputSamples, neededInput, sourceRe, sourceIm);
		double meanRe = 0d, meanIm = 0d;
		for (int n = 0; n < COHERENT_SAMPLES; n++) { meanRe += sourceRe[n]; meanIm += sourceIm[n]; }
		meanRe /= COHERENT_SAMPLES; meanIm /= COHERENT_SAMPLES;
		double inputEnergy = 0d;
		for (int n = 0; n < COHERENT_SAMPLES; n++) {
			sourceRe[n] -= meanRe; sourceIm[n] -= meanIm;
			inputEnergy += sourceRe[n] * sourceRe[n] + sourceIm[n] * sourceIm[n];
		}

		int boundedCount = Math.max(0, Math.min(count, SVID_COUNT));
		List<Integer> selectedIndexes = new ArrayList<Integer>();
		for (int index = 0; index < boundedCount; index++) {
			int selected = (Math.max(1, firstSvid) - 1 + index) % SVID_COUNT;
			selectedIndexes.add(Integer.valueOf(selected)); searched[selected] = true;
		}
		/* Once a channel exists, reacquire it on every half-second pass while
		 * the remaining SVIDs continue their progressive scan. BOC half-chip
		 * timing is too sharp to leave a weak channel without phase correction
		 * for an entire 6-second 36-SVID scan. */
		for (int activeSvid : streamTracker.activeSvids()) {
			Integer selected = Integer.valueOf(activeSvid - 1);
			if (!selectedIndexes.contains(selected)) selectedIndexes.add(selected);
		}
		int[] indexes = new int[selectedIndexes.size()];
		for (int index = 0; index < indexes.length; index++) {
			indexes[index] = selectedIndexes.get(index).intValue(); ensureCodeSpectrum(indexes[index]);
		}
		double[] bestPeak = new double[indexes.length];
		double[] bestDataPeak = new double[indexes.length];
		double[] bestPilotPeak = new double[indexes.length];
		double[] bestNoise = new double[indexes.length];
		int[] bestDoppler = new int[indexes.length];
		int[] bestPhase = new int[indexes.length];
		Arrays.fill(bestPeak, -1d);
		Arrays.fill(bestNoise, 1d);
		double[] inputRe = new double[CODE_SAMPLES], inputIm = new double[CODE_SAMPLES];
		double[] correlationRe = new double[CODE_SAMPLES], correlationIm = new double[CODE_SAMPLES];
		double[] dataPower = new double[CODE_SAMPLES], pilotPower = new double[CODE_SAMPLES];
		for (int doppler : DOPPLER_BINS_HZ) {
			Arrays.fill(inputRe, 0d); Arrays.fill(inputIm, 0d);
			double step = -2d * Math.PI * doppler / ACQUISITION_RATE_HZ;
			for (int n = 0; n < COHERENT_SAMPLES; n++) {
				double phase = step * n, cosine = Math.cos(phase), sine = Math.sin(phase);
				inputRe[n] = sourceRe[n] * cosine - sourceIm[n] * sine;
				inputIm[n] = sourceRe[n] * sine + sourceIm[n] * cosine;
			}
			fft(inputRe, inputIm, false);
			for (int candidate = 0; candidate < indexes.length; candidate++) {
				correlationPower(inputRe, inputIm, dataCodeRe[indexes[candidate]], dataCodeIm[indexes[candidate]],
						correlationRe, correlationIm, dataPower);
				correlationPower(inputRe, inputIm, pilotCodeRe[indexes[candidate]], pilotCodeIm[indexes[candidate]],
						correlationRe, correlationIm, pilotPower);
				double peak = -1d, sum = 0d; int peakPhase = 0;
				for (int phase = 0; phase < CODE_SAMPLES; phase++) {
					double power = dataPower[phase] + pilotPower[phase];
					sum += power;
					if (power > peak) { peak = power; peakPhase = phase; }
				}
				double noise = Math.max(1e-12d, (sum - peak) / (CODE_SAMPLES - 1));
				if (peak / noise > bestPeak[candidate] / bestNoise[candidate]) {
					bestPeak[candidate] = peak; bestNoise[candidate] = noise;
					bestDataPeak[candidate] = dataPower[peakPhase];
					bestPilotPeak[candidate] = pilotPower[peakPhase];
					bestDoppler[candidate] = doppler; bestPhase[candidate] = peakPhase;
				}
			}
		}
		/* A 500 Hz coarse bin is sufficient for 2 ms acquisition, but not for
		 * coherent 4 ms E1-B tracking: a residual near 250 Hz can place a null
		 * across one code epoch. Refine only plausible candidates so ordinary
		 * noise does not multiply the live acquisition cost. */
		for (int candidate = 0; candidate < indexes.length; candidate++) {
			double coarseCoherence = Math.sqrt(Math.max(0d, bestPeak[candidate])
					/ Math.max(1e-12d, inputEnergy * COHERENT_SAMPLES));
			if (coarseCoherence < FINE_DOPPLER_MIN_COHERENCE) continue;
			int coarseDoppler = bestDoppler[candidate];
			for (int delta = -FINE_DOPPLER_RANGE_HZ; delta <= FINE_DOPPLER_RANGE_HZ; delta += FINE_DOPPLER_STEP_HZ) {
				if (delta == 0) continue;
				int doppler = coarseDoppler + delta;
				Arrays.fill(inputRe, 0d); Arrays.fill(inputIm, 0d);
				double step = -2d * Math.PI * doppler / ACQUISITION_RATE_HZ;
				for (int n = 0; n < COHERENT_SAMPLES; n++) {
					double phase = step * n, cosine = Math.cos(phase), sine = Math.sin(phase);
					inputRe[n] = sourceRe[n] * cosine - sourceIm[n] * sine;
					inputIm[n] = sourceRe[n] * sine + sourceIm[n] * cosine;
				}
				fft(inputRe, inputIm, false);
				correlationPower(inputRe, inputIm, dataCodeRe[indexes[candidate]], dataCodeIm[indexes[candidate]],
						correlationRe, correlationIm, dataPower);
				correlationPower(inputRe, inputIm, pilotCodeRe[indexes[candidate]], pilotCodeIm[indexes[candidate]],
						correlationRe, correlationIm, pilotPower);
				double peak = -1d, sum = 0d; int peakPhase = 0;
				for (int phase = 0; phase < CODE_SAMPLES; phase++) {
					double power = dataPower[phase] + pilotPower[phase];
					sum += power;
					if (power > peak) { peak = power; peakPhase = phase; }
				}
				double noise = Math.max(1e-12d, (sum - peak) / (CODE_SAMPLES - 1));
				if (peak / noise > bestPeak[candidate] / bestNoise[candidate]) {
					bestPeak[candidate] = peak; bestNoise[candidate] = noise;
					bestDataPeak[candidate] = dataPower[peakPhase];
					bestPilotPeak[candidate] = pilotPower[peakPhase];
					bestDoppler[candidate] = doppler; bestPhase[candidate] = peakPhase;
				}
			}
		}

		for (int candidate = 0; candidate < indexes.length; candidate++) {
			int index = indexes[candidate];
			double ratio = bestPeak[candidate] / bestNoise[candidate];
			double coherence = Math.sqrt(Math.max(0d, bestPeak[candidate])
					/ Math.max(1e-12d, inputEnergy * COHERENT_SAMPLES));
			double dataCoherence = Math.sqrt(Math.max(0d, bestDataPeak[candidate])
					/ Math.max(1e-12d, inputEnergy * COHERENT_SAMPLES));
			double pilotCoherence = Math.sqrt(Math.max(0d, bestPilotPeak[candidate])
					/ Math.max(1e-12d, inputEnergy * COHERENT_SAMPLES));
			double cNo = 10d * Math.log10(Math.max(1e-6d, ratio / .002d));
			latest[index] = new Satellite(index + 1, bestDoppler[candidate], bestPhase[candidate],
					bestPhase[candidate] * CODE_CHIPS / (double) CODE_SAMPLES,
					ratio, coherence, dataCoherence, pilotCoherence, cNo,
					coherence >= ACQUISITION_THRESHOLD);
		}

		List<Satellite> all = new ArrayList<Satellite>();
		int scanned = 0;
		for (int index = 0; index < SVID_COUNT; index++) {
			if (searched[index]) scanned++;
			if (latest[index] != null) all.add(latest[index]);
		}
		Collections.sort(all, new Comparator<Satellite>() {
			@Override public int compare(Satellite left, Satellite right) {
				return Double.compare(right.coherence, left.coherence);
			}
		});
		List<Satellite> acquired = new ArrayList<Satellite>();
		for (Satellite satellite : all) if (satellite.acquired) acquired.add(satellite);
		streamTracker.updateAcquisition(centerFreqHz, sampleRateHz, acquired, neededInput);
		List<Satellite> baseShown = acquired.isEmpty() ? all.subList(0, Math.min(8, all.size())) : acquired;
		List<Satellite> shown = new ArrayList<Satellite>();
		for (Satellite satellite : baseShown)
			shown.add(satellite.withTracking(streamTracker.tracking(satellite.svid)));
		int trackedCount = streamTracker.trackedCount(), decodedCount = streamTracker.decodedCount();
		int ephemerisCount = streamTracker.ephemerisCount();
		String state = acquired.isEmpty()
				? "Scanning Galileo E1-B/C SVIDs (" + scanned + "/" + SVID_COUNT + "); strongest candidates shown"
				: acquired.size() + " Galileo E1-B/C satellite" + (acquired.size() == 1 ? " acquired" : "s acquired")
						+ (trackedCount > 0 ? "; tracked " + trackedCount : "; starting tracking")
						+ (decodedCount > 0 ? "; I/NAV CRC valid " + decodedCount : "")
						+ (ephemerisCount > 0 ? "; ephemerides " + ephemerisCount : "");
		return new Result(centerFreqHz, sampleRateHz, state, new ArrayList<Satellite>(shown),
				acquired.size(), scanned, trackedCount, decodedCount, ephemerisCount,
				new ArrayList<Satellite>(all), streamTracker.observations());
	}

	private static void resampleAndCenter(long centerFreqHz, int sampleRateHz, byte[] iq, int inputSamples,
			int neededInput, double[] outputRe, double[] outputIm) {
		int first = Math.max(0, inputSamples - neededInput);
		double inputStep = sampleRateHz / (double) ACQUISITION_RATE_HZ;
		double offsetHz = E1_FREQUENCY_HZ - centerFreqHz;
		for (int n = 0; n < outputRe.length; n++) {
			double position = first + n * inputStep;
			int index = Math.min(inputSamples - 2, (int) position);
			double fraction = position - index;
			double i = iq[index * 2] * (1d - fraction) + iq[(index + 1) * 2] * fraction;
			double q = iq[index * 2 + 1] * (1d - fraction) + iq[(index + 1) * 2 + 1] * fraction;
			double phase = -2d * Math.PI * offsetHz * (position - first) / sampleRateHz;
			double cosine = Math.cos(phase), sine = Math.sin(phase);
			outputRe[n] = i * cosine - q * sine;
			outputIm[n] = i * sine + q * cosine;
		}
	}

	private void ensureCodeSpectrum(int index) {
		if (dataCodeRe[index] != null) return;
		double[][] data = codeSpectrum(DATA_PRIMARY_CODES[index]);
		double[][] pilot = codeSpectrum(PILOT_PRIMARY_CODES[index]);
		dataCodeRe[index] = data[0]; dataCodeIm[index] = data[1];
		pilotCodeRe[index] = pilot[0]; pilotCodeIm[index] = pilot[1];
	}

	private static double[][] codeSpectrum(int[] chips) {
		double[] real = new double[CODE_SAMPLES], imaginary = new double[CODE_SAMPLES];
		for (int sample = 0; sample < CODE_SAMPLES; sample++) {
			int chip = (int) ((long) sample * CODE_CHIPS / CODE_SAMPLES);
			int halfChip = (int) ((long) sample * CODE_CHIPS * 2L / CODE_SAMPLES) & 1;
			real[sample] = chips[chip] * (halfChip == 0 ? 1d : -1d);
		}
		fft(real, imaginary, false);
		return new double[][] { real, imaginary };
	}

	private static void correlationPower(double[] inputRe, double[] inputIm,
			double[] replicaRe, double[] replicaIm, double[] correlationRe,
			double[] correlationIm, double[] power) {
		for (int n = 0; n < CODE_SAMPLES; n++) {
			correlationRe[n] = inputRe[n] * replicaRe[n] + inputIm[n] * replicaIm[n];
			correlationIm[n] = inputIm[n] * replicaRe[n] - inputRe[n] * replicaIm[n];
		}
		fft(correlationRe, correlationIm, true);
		for (int phase = 0; phase < CODE_SAMPLES; phase++)
			power[phase] = correlationRe[phase] * correlationRe[phase]
					+ correlationIm[phase] * correlationIm[phase];
	}

	static int[] primaryCode(int svid) {
		if (svid < 1 || svid > SVID_COUNT) throw new IllegalArgumentException("Galileo SVID must be 1..36");
		return DATA_PRIMARY_CODES[svid - 1].clone();
	}

	static int[] pilotPrimaryCode(int svid) {
		if (svid < 1 || svid > SVID_COUNT) throw new IllegalArgumentException("Galileo SVID must be 1..36");
		return PILOT_PRIMARY_CODES[svid - 1].clone();
	}

	static double bocReplicaSample(int svid, int sample) {
		int wrapped = ((sample % CODE_SAMPLES) + CODE_SAMPLES) % CODE_SAMPLES;
		int chip = (int) ((long) wrapped * CODE_CHIPS / CODE_SAMPLES);
		int halfChip = (int) ((long) wrapped * CODE_CHIPS * 2L / CODE_SAMPLES) & 1;
		return DATA_PRIMARY_CODES[svid - 1][chip] * (halfChip == 0 ? 1d : -1d);
	}

	static double pilotBocReplicaSample(int svid, int sample) {
		int wrapped = ((sample % CODE_SAMPLES) + CODE_SAMPLES) % CODE_SAMPLES;
		int chip = (int) ((long) wrapped * CODE_CHIPS / CODE_SAMPLES);
		int halfChip = (int) ((long) wrapped * CODE_CHIPS * 2L / CODE_SAMPLES) & 1;
		return PILOT_PRIMARY_CODES[svid - 1][chip] * (halfChip == 0 ? 1d : -1d);
	}

	private static int[][] loadPrimaryCodes(String resource, String signal) {
		int[][] codes = new int[SVID_COUNT][CODE_CHIPS];
		InputStream stream = GalileoE1SignalAnalyzer.class.getResourceAsStream(resource);
		if (stream == null) throw new ExceptionInInitializerError("Missing Galileo " + signal + " primary-code resource");
		try {
			BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.US_ASCII));
			String line; int rows = 0;
			while ((line = reader.readLine()) != null) {
				String[] fields = line.trim().split(";", 2);
				if (fields.length != 2 || fields[1].length() != 1023) continue;
				int svid = Integer.parseInt(fields[0].substring(fields[0].length() - 2));
				if (svid < 1 || svid > SVID_COUNT) continue;
				int chip = 0;
				for (int symbol = 0; symbol < fields[1].length(); symbol++) {
					int value = Character.digit(fields[1].charAt(symbol), 16);
					if (value < 0) throw new IOException("Invalid hexadecimal " + signal + " code");
					for (int bit = 3; bit >= 0; bit--) codes[svid - 1][chip++] = ((value >>> bit) & 1) == 0 ? 1 : -1;
				}
				rows++;
			}
			reader.close();
			if (rows != SVID_COUNT) throw new IOException("Expected 36 Galileo " + signal + " codes, found " + rows);
			return codes;
		} catch (IOException | RuntimeException error) {
			throw new ExceptionInInitializerError(error);
		}
	}

	private static void fft(double[] real, double[] imaginary, boolean inverse) {
		int size = real.length;
		for (int n = 1, reversed = 0; n < size; n++) {
			int bit = size >>> 1; for (; (reversed & bit) != 0; bit >>>= 1) reversed ^= bit; reversed ^= bit;
			if (n < reversed) { double value = real[n]; real[n] = real[reversed]; real[reversed] = value;
				value = imaginary[n]; imaginary[n] = imaginary[reversed]; imaginary[reversed] = value; }
		}
		for (int width = 2; width <= size; width <<= 1) {
			double angle = (inverse ? 2d : -2d) * Math.PI / width;
			double widthRe = Math.cos(angle), widthIm = Math.sin(angle);
			for (int start = 0; start < size; start += width) {
				double wRe = 1d, wIm = 0d;
				for (int n = 0; n < width / 2; n++) {
					int left = start + n, right = left + width / 2;
					double r = real[right] * wRe - imaginary[right] * wIm;
					double i = real[right] * wIm + imaginary[right] * wRe;
					real[right] = real[left] - r; imaginary[right] = imaginary[left] - i;
					real[left] += r; imaginary[left] += i;
					double nextRe = wRe * widthRe - wIm * widthIm;
					wIm = wRe * widthIm + wIm * widthRe; wRe = nextRe;
				}
			}
		}
		if (inverse) for (int n = 0; n < size; n++) { real[n] /= size; imaginary[n] /= size; }
	}

	static final class Satellite {
		final int svid, dopplerHz, codePhaseSamples;
		final double codePhaseChips, peakToNoise, coherence, dataCoherence, pilotCoherence, cNoDbHz;
		final boolean acquired, trackingLocked; final long trackingSymbols;
		final double trackingStability; final String navState; final GalileoInavDecoder.Page page;
		final GalileoNavigationData.Snapshot navigation;
		Satellite(int svid, int dopplerHz, int codePhaseSamples, double codePhaseChips,
				double peakToNoise, double coherence, double cNoDbHz, boolean acquired) {
			this(svid, dopplerHz, codePhaseSamples, codePhaseChips, peakToNoise,
					coherence, coherence, 0d, cNoDbHz, acquired);
		}
		Satellite(int svid, int dopplerHz, int codePhaseSamples, double codePhaseChips,
				double peakToNoise, double coherence, double dataCoherence,
				double pilotCoherence, double cNoDbHz, boolean acquired) {
			this.svid = svid; this.dopplerHz = dopplerHz; this.codePhaseSamples = codePhaseSamples;
			this.codePhaseChips = codePhaseChips; this.peakToNoise = peakToNoise;
			this.coherence = coherence; this.dataCoherence = dataCoherence;
			this.pilotCoherence = pilotCoherence; this.cNoDbHz = cNoDbHz; this.acquired = acquired;
			this.trackingLocked = false; this.trackingSymbols = 0L; this.trackingStability = 0d;
			this.navState = "Not tracked"; this.page = GalileoInavDecoder.Page.waiting();
			this.navigation = GalileoNavigationData.Snapshot.waiting();
		}
		private Satellite(Satellite source, GalileoE1StreamTracker.Tracking tracking) {
			this.svid = source.svid; this.dopplerHz = source.dopplerHz; this.codePhaseSamples = source.codePhaseSamples;
			this.codePhaseChips = source.codePhaseChips; this.peakToNoise = source.peakToNoise;
			this.coherence = source.coherence; this.dataCoherence = source.dataCoherence;
			this.pilotCoherence = source.pilotCoherence; this.cNoDbHz = source.cNoDbHz; this.acquired = source.acquired;
			this.trackingLocked = tracking.locked; this.trackingSymbols = tracking.symbols;
			this.trackingStability = tracking.stability; this.navState = tracking.navState;
			this.page = tracking.page; this.navigation = tracking.navigation;
		}
		Satellite withTracking(GalileoE1StreamTracker.Tracking tracking) { return new Satellite(this, tracking); }
	}

	static final class Result {
		final long centerFreqHz; final int sampleRateHz; final String state;
		final List<Satellite> satellites, candidates;
		final List<GalileoE1StreamTracker.Observation> observations;
		final int acquiredCount, scannedCount, trackedCount, decodedCount, ephemerisCount;
		Result(long centerFreqHz, int sampleRateHz, String state, List<Satellite> satellites,
				int acquiredCount, int scannedCount) {
			this(centerFreqHz, sampleRateHz, state, satellites, acquiredCount, scannedCount, 0, 0);
		}
		Result(long centerFreqHz, int sampleRateHz, String state, List<Satellite> satellites,
				int acquiredCount, int scannedCount, int trackedCount, int decodedCount) {
			this(centerFreqHz, sampleRateHz, state, satellites, acquiredCount, scannedCount, trackedCount, decodedCount, 0);
		}
		Result(long centerFreqHz, int sampleRateHz, String state, List<Satellite> satellites,
				int acquiredCount, int scannedCount, int trackedCount, int decodedCount, int ephemerisCount) {
			this(centerFreqHz, sampleRateHz, state, satellites, acquiredCount, scannedCount,
					trackedCount, decodedCount, ephemerisCount, satellites);
		}
		Result(long centerFreqHz, int sampleRateHz, String state, List<Satellite> satellites,
				int acquiredCount, int scannedCount, int trackedCount, int decodedCount,
				int ephemerisCount, List<Satellite> candidates) {
			this(centerFreqHz, sampleRateHz, state, satellites, acquiredCount, scannedCount,
					trackedCount, decodedCount, ephemerisCount, candidates,
					Collections.<GalileoE1StreamTracker.Observation>emptyList());
		}
		Result(long centerFreqHz, int sampleRateHz, String state, List<Satellite> satellites,
				int acquiredCount, int scannedCount, int trackedCount, int decodedCount,
				int ephemerisCount, List<Satellite> candidates,
				List<GalileoE1StreamTracker.Observation> observations) {
			this.centerFreqHz = centerFreqHz; this.sampleRateHz = sampleRateHz; this.state = state;
			this.satellites = Collections.unmodifiableList(satellites);
			this.candidates = Collections.unmodifiableList(candidates);
			this.acquiredCount = acquiredCount; this.scannedCount = scannedCount;
			this.trackedCount = trackedCount; this.decodedCount = decodedCount; this.ephemerisCount = ephemerisCount;
			this.observations = Collections.unmodifiableList(new ArrayList<GalileoE1StreamTracker.Observation>(observations));
		}
		static Result empty(long centerFreqHz, int sampleRateHz, String state) {
			return new Result(centerFreqHz, sampleRateHz, state, Collections.<Satellite>emptyList(), 0, 0);
		}
	}
}
