package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Streaming IEEE 802.11 legacy-OFDM acquisition and L-SIG decoder. Input is
 * converted from the user-selected sample rate to the native 20 MS/s OFDM clock.
 *
 * <p>The legacy preamble is also present at the beginning of HT/VHT/HE mixed
 * mode packets, so this is useful beyond 802.11a/g even before the newer SIG
 * fields and payload decoders are added.</p>
 */
final class WifiSignalAnalyzer {
	static final int SAMPLE_RATE_HZ = 20_000_000;
	private static final int FFT_SIZE = 64;
	private static final int SHORT_PERIOD = 16;
	private static final int MIN_PACKET_SAMPLES = 400;
	private static final int OVERLAP_SAMPLES = 65_536;
	private static final int MAX_MANAGEMENT_PSDU_BYTES = 1_536;
	private static final double DETECTION_THRESHOLD = .72d;
	private static final double MIN_SHORT_POWER = 4d;
	/* Multipath distorts the 64-sample time-domain L-LTF substantially.  The
	 * short preamble has already provided a strong packet gate, so this value
	 * intentionally only rejects accidental LTF matches inside that gate. */
	private static final double LTF_THRESHOLD = .38d;
	private static final double LTF_REPEAT_THRESHOLD = .58d;
	private static final int[] DATA_CARRIERS = {
			-26,-25,-24,-23,-22, -20,-19,-18,-17,-16,-15,-14,-13,-12,-11,-10,-9,-8,
			-6,-5,-4,-3,-2,-1, 1,2,3,4,5,6, 8,9,10,11,12,13,14,15,16,17,18,19,20,
			22,23,24,25,26
	};
	private static final int[] PILOT_CARRIERS = {-21,-7,7,21};
	private static final int[] PILOT_SIGNS = {1,1,1,-1};
	/* IEEE 802.11 L-LTF BPSK values for subcarriers -26 ... +26. */
	private static final int[] LONG_TRAINING = {
			1,1,-1,-1,1,1,-1,1,-1,1,1,1,1,1,1,-1,-1,1,1,-1,1,-1,1,1,1,1,
			0,
			1,-1,-1,1,1,-1,1,-1,1,-1,-1,-1,-1,-1,1,1,-1,-1,1,-1,1,-1,1,1,1,1
	};
	private static final double[] LONG_TIME_I = new double[FFT_SIZE];
	private static final double[] LONG_TIME_Q = new double[FFT_SIZE];

	static {
		double[] frequencyI = new double[FFT_SIZE];
		double[] frequencyQ = new double[FFT_SIZE];
		for (int carrier = -26; carrier <= 26; carrier++)
			frequencyI[bin(carrier)] = LONG_TRAINING[carrier + 26];
		ifft(frequencyI, frequencyQ);
		System.arraycopy(frequencyI, 0, LONG_TIME_I, 0, FFT_SIZE);
		System.arraycopy(frequencyQ, 0, LONG_TIME_Q, 0, FFT_SIZE);
	}

	private byte[] overlap = new byte[0];
	private int overlapSampleRateHz;
	private long decodedPackets;
	private long detectedPreambles;
	private long diagnosticShortRuns;
	private double diagnosticBestShort, diagnosticBestLtf, diagnosticBestLtfRepeat;
	private Result latest = Result.empty("Searching for Wi-Fi legacy preamble");
	private final List<Observation> observations = new ArrayList<Observation>();

	synchronized void reset() {
		overlap = new byte[0];
		overlapSampleRateHz = 0;
		decodedPackets = 0;
		detectedPreambles = 0;
		latest = Result.empty("Searching for Wi-Fi legacy preamble");
		observations.clear();
		diagnosticShortRuns = 0;
		diagnosticBestShort = diagnosticBestLtf = diagnosticBestLtfRepeat = 0;
	}

	synchronized void discardOverlap() {
		overlap = new byte[0];
		overlapSampleRateHz = 0;
		observations.clear();
	}

	synchronized long diagnosticShortRuns() { return diagnosticShortRuns; }
	synchronized double diagnosticBestShort() { return diagnosticBestShort; }
	synchronized double diagnosticBestLtf() { return diagnosticBestLtf; }
	synchronized double diagnosticBestLtfRepeat() { return diagnosticBestLtfRepeat; }

	synchronized Result analyze(byte[] iq, int length, int sampleRateHz) {
		return analyze(0L, iq, length, sampleRateHz);
	}

	synchronized Result analyze(long centerFreqHz, byte[] iq, int length, int sampleRateHz) {
		observations.clear();
		int usable = Math.min(iq == null ? 0 : iq.length, length) & ~1;
		if (usable < 2 || sampleRateHz <= 0) return latest;
		if (overlapSampleRateHz != sampleRateHz) {
			overlap = new byte[0];
			overlapSampleRateHz = sampleRateHz;
		}
		byte[] analysisIq = sampleRateHz == SAMPLE_RATE_HZ
				? (usable == iq.length ? iq : Arrays.copyOf(iq, usable))
				: resample(iq, usable, sampleRateHz);
		int carriedSamples = overlap.length / 2;
		byte[] combined = new byte[overlap.length + analysisIq.length];
		System.arraycopy(overlap, 0, combined, 0, overlap.length);
		System.arraycopy(analysisIq, 0, combined, overlap.length, analysisIq.length);
		Result found = scan(centerFreqHz, combined, carriedSamples);
		int keepSamples = Math.min(OVERLAP_SAMPLES, combined.length / 2);
		overlap = Arrays.copyOfRange(combined, combined.length - keepSamples * 2, combined.length);
		if (found != null) latest = found;
		else if (latest.decodedPackets == 0)
			latest = new Result(false, false, "Searching for Wi-Fi legacy preamble", 0, 0, 0,
					0, 0, "--", 0, detectedPreambles, decodedPackets,
					System.currentTimeMillis());
		return latest;
	}

	synchronized List<Observation> takeObservations() {
		List<Observation> result = new ArrayList<Observation>(observations);
		observations.clear();
		return result;
	}

	private static byte[] resample(byte[] iq, int length, int sampleRateHz) {
		if (sampleRateHz == 16_000_000) return resample16To20(iq, length);
		int inputSamples = length / 2;
		int outputSamples = Math.max(1,
				(int)Math.floor(inputSamples * (double)SAMPLE_RATE_HZ / sampleRateHz));
		byte[] output = new byte[outputSamples * 2];
		double step = sampleRateHz / (double)SAMPLE_RATE_HZ;
		for (int n = 0; n < outputSamples; n++) {
			double position = n * step;
			int left = Math.min(inputSamples - 1, (int)position);
			int right = Math.min(inputSamples - 1, left + 1);
			double fraction = position - left;
			for (int component = 0; component < 2; component++) {
				double first = iq[left * 2 + component];
				double value = first + (iq[right * 2 + component] - first) * fraction;
				output[n * 2 + component] = (byte)Math.round(value);
			}
		}
		return output;
	}

	private static byte[] resample16To20(byte[] iq, int length) {
		int inputSamples = length / 2;
		int outputSamples = inputSamples * 5 / 4;
		byte[] output = new byte[outputSamples * 2];
		for (int n = 0; n < outputSamples; n++) {
			int group = n / 5;
			int phase = n - group * 5;
			int left = Math.min(inputSamples - 1, group * 4 + Math.max(0, phase - 1));
			int right = Math.min(inputSamples - 1, left + 1);
			int rightWeight = phase == 0 ? 0 : 5 - phase;
			for (int component = 0; component < 2; component++) {
				int first = iq[left * 2 + component];
				int sum = first * (5 - rightWeight) + iq[right * 2 + component] * rightWeight;
				output[n * 2 + component] = (byte)(sum >= 0 ? (sum + 2) / 5 : (sum - 2) / 5);
			}
		}
		return output;
	}

	private Result scan(long centerFreqHz, byte[] iq, int carriedSamples) {
		int samples = iq.length / 2;
		int lastStart = samples - MIN_PACKET_SAMPLES;
		if (lastStart < 0) return null;
		Result best = null;
		int run = 0, runStart = 0, suppressUntil = 0;
		double correlationReal = 0, correlationImag = 0, firstPower = 0, secondPower = 0;
		for (int n = 0; n < 64; n++) {
			int a = n * 2, b = (n + SHORT_PERIOD) * 2;
			double ar = iq[a], ai = iq[a + 1], br = iq[b], bi = iq[b + 1];
			correlationReal += ar * br + ai * bi;
			correlationImag += ar * bi - ai * br;
			firstPower += ar * ar + ai * ai;
			secondPower += br * br + bi * bi;
		}
		for (int at = 0; at <= lastStart; at++) {
			if (at < suppressUntil) {
				run = 0;
			} else if ((at & 3) == 0) {
				double correlationPower = correlationReal * correlationReal
						+ correlationImag * correlationImag;
				double shortMetric = Math.sqrt(correlationPower
						/ Math.max(1d, firstPower * secondPower));
				diagnosticBestShort = Math.max(diagnosticBestShort, shortMetric);
				double requiredPower = DETECTION_THRESHOLD * DETECTION_THRESHOLD
						* Math.max(1d, firstPower * secondPower);
				if (correlationPower >= requiredPower && secondPower / 64d >= MIN_SHORT_POWER) {
					if (run == 0) runStart = at;
					run++;
				} else {
					run = 0;
				}
				if (run >= 12) {
					diagnosticShortRuns++;
					int roughStart = runStart;
					double coarseCfo = shortCfo(iq, Math.max(0, roughStart), samples);
					LtfLock ltf = findLongTraining(iq, roughStart, coarseCfo);
					if (ltf == null) {
						/* Do not repeatedly rescan the same non-OFDM burst. */
						suppressUntil = roughStart + 320;
					} else {
						int packetStart = ltf.usefulStart - 192;
						int signalEnd = ltf.usefulStart + 208;
						if (packetStart >= 0 && signalEnd <= samples) {
							suppressUntil = signalEnd;
							/* A packet wholly in the overlap was counted in the previous block. */
							if (signalEnd > carriedSamples) {
								detectedPreambles++;
								Result decoded = decodeSignal(centerFreqHz, iq, ltf);
								if (decoded.lSigValid) decodedPackets++;
								decoded = decoded.withCounts(detectedPreambles, decodedPackets);
								int packetEnd = legacyPacketEnd(ltf, decoded);
								if (packetEnd > suppressUntil) suppressUntil = packetEnd;
								if (best == null || decoded.quality > best.quality
										|| decoded.lSigValid && !best.lSigValid) best = decoded;
							}
						}
					}
					run = 0;
				}
			}
			if (at < lastStart) {
				int oldA = at * 2, oldB = (at + SHORT_PERIOD) * 2;
				double oldAr = iq[oldA], oldAi = iq[oldA + 1], oldBr = iq[oldB], oldBi = iq[oldB + 1];
				correlationReal -= oldAr * oldBr + oldAi * oldBi;
				correlationImag -= oldAr * oldBi - oldAi * oldBr;
				firstPower -= oldAr * oldAr + oldAi * oldAi;
				secondPower -= oldBr * oldBr + oldBi * oldBi;
				int newA = (at + 64) * 2, newB = (at + 64 + SHORT_PERIOD) * 2;
				double newAr = iq[newA], newAi = iq[newA + 1], newBr = iq[newB], newBi = iq[newB + 1];
				correlationReal += newAr * newBr + newAi * newBi;
				correlationImag += newAr * newBi - newAi * newBr;
				firstPower += newAr * newAr + newAi * newAi;
				secondPower += newBr * newBr + newBi * newBi;
			}
		}
		return best;
	}

	private LtfLock findLongTraining(byte[] iq, int roughStart, double coarseCfoHz) {
		int samples = iq.length / 2;
		int first = Math.max(0, roughStart + 110);
		int last = Math.min(samples - MIN_PACKET_SAMPLES + 192, roughStart + 300);
		double best = 0, bestRepeat = 0, bestScore = 0;
		int bestAt = -1, bestOrientation = 1;
		double bestCfo = coarseCfoHz;
		for (int at = first; at <= last; at++) {
			double repetition = longTrainingRepetition(iq, at);
			diagnosticBestLtfRepeat = Math.max(diagnosticBestLtfRepeat, repetition);
			if (repetition < LTF_REPEAT_THRESHOLD) continue;
			for (int orientation = 1; orientation >= -1; orientation -= 2) {
				double orientedCfo = coarseCfoHz * orientation;
				/* STF phase determines CFO only modulo 20 MHz / 16 = 1.25 MHz.
				 * Resolve the nearby aliases with the known L-LTF sequence. */
				for (int alias = -2; alias <= 2; alias++) {
					double candidateCfo = orientedCfo
							+ alias * (SAMPLE_RATE_HZ / (double)SHORT_PERIOD);
					double correlation = trainingCorrelation(iq, at, candidateCfo, orientation);
					double score = correlation * repetition;
					if (score > bestScore) {
						bestScore = score;
						best = correlation;
						bestRepeat = repetition;
						bestAt = at;
						bestOrientation = orientation;
						bestCfo = candidateCfo;
					}
				}
			}
		}
		diagnosticBestLtf = Math.max(diagnosticBestLtf, best);
		if (bestAt < 0 || best < LTF_THRESHOLD || bestRepeat < LTF_REPEAT_THRESHOLD
				|| bestAt + 208 > samples) return null;
		double real = 0, imag = 0;
		for (int n = 0; n < FFT_SIZE; n++) {
			Complex a = corrected(iq, bestAt + n, bestCfo, bestOrientation);
			Complex b = corrected(iq, bestAt + FFT_SIZE + n, bestCfo, bestOrientation);
			real += a.real * b.real + a.imag * b.imag;
			imag += a.real * b.imag - a.imag * b.real;
		}
		double residualHz = Math.atan2(imag, real) * SAMPLE_RATE_HZ / (2d * Math.PI * FFT_SIZE);
		return new LtfLock(bestAt, best, bestCfo + residualHz, bestOrientation);
	}

	private double longTrainingRepetition(byte[] iq, int start) {
		double real = 0, imag = 0, firstPower = 0, secondPower = 0;
		for (int n = 0; n < FFT_SIZE; n++) {
			int a = (start + n) * 2, b = (start + FFT_SIZE + n) * 2;
			double ar = iq[a], ai = iq[a + 1], br = iq[b], bi = iq[b + 1];
			real += ar * br + ai * bi;
			imag += ar * bi - ai * br;
			firstPower += ar * ar + ai * ai;
			secondPower += br * br + bi * bi;
		}
		return Math.hypot(real, imag) / Math.sqrt(Math.max(1d, firstPower * secondPower));
	}

	private Result decodeSignal(long centerFreqHz, byte[] iq, LtfLock lock) {
		Result best = null;
		LtfLock bestLock = lock;
		/* A matched-filter peak follows the strongest channel path, which can
		 * be several samples after the OFDM symbol boundary.  Moving both LTF
		 * and SIGNAL FFT windows back inside the cyclic prefix preserves the
		 * channel phase while avoiding inter-symbol interference. */
		for (int timing = 0; timing >= -15; timing--) {
			if (lock.usefulStart + timing < 0) break;
			LtfLock shifted = new LtfLock(lock.usefulStart + timing, lock.correlation,
					lock.cfoHz, lock.orientation);
			Result candidate = decodeSignalAt(iq, shifted);
			if (best == null || candidate.lSigValid && !best.lSigValid
					|| candidate.lSigValid == best.lSigValid && candidate.evmPercent < best.evmPercent) {
				best = candidate;
				bestLock = shifted;
			}
		}
		if (best != null && best.lSigValid && "6 Mb/s".equals(best.rate)
				&& best.lengthBytes >= 28 && best.lengthBytes <= MAX_MANAGEMENT_PSDU_BYTES) {
			WifiMacFrame mac = decodeLegacy6Psdu(iq, bestLock, best.lengthBytes, centerFreqHz);
			if (mac != null && mac.fcsValid) {
				int end = Math.min(iq.length / 2, legacyPacketEnd(bestLock, best));
				double level = packetLevelDbfs(iq, Math.max(0, bestLock.usefulStart - 192), end);
				observations.add(new Observation(mac, best.rate, level, best.ltfCorrelation,
						best.cfoHz, System.currentTimeMillis()));
			}
		}
		return best;
	}

	private Result decodeSignalAt(byte[] iq, LtfLock lock) {
		double cfoHz = lock.cfoHz;
		double[] firstI = fftSymbol(iq, lock.usefulStart, cfoHz, lock.orientation);
		double[] firstQ = lastImag;
		double[] secondI = fftSymbol(iq, lock.usefulStart + FFT_SIZE, cfoHz, lock.orientation);
		double[] secondQ = lastImag;
		double[] channelI = new double[FFT_SIZE], channelQ = new double[FFT_SIZE];
		double signalPower = 0, noisePower = 0;
		for (int carrier = -26; carrier <= 26; carrier++) {
			if (carrier == 0) continue;
			int b = bin(carrier), reference = LONG_TRAINING[carrier + 26];
			channelI[b] = (firstI[b] + secondI[b]) * .5d * reference;
			channelQ[b] = (firstQ[b] + secondQ[b]) * .5d * reference;
			double dr = firstI[b] - secondI[b], di = firstQ[b] - secondQ[b];
			signalPower += channelI[b] * channelI[b] + channelQ[b] * channelQ[b];
			noisePower += (dr * dr + di * di) * .5d;
		}
		double snrDb = 10d * Math.log10(Math.max(1e-12, signalPower / Math.max(1e-12, noisePower)));
		int signalStart = lock.usefulStart + 144;
		double[] signalI = fftSymbol(iq, signalStart, cfoHz, lock.orientation);
		double[] signalQ = lastImag;
		double phaseReal = 0, phaseImag = 0;
		for (int p = 0; p < PILOT_CARRIERS.length; p++) {
			Complex matched = matched(signalI, signalQ, channelI, channelQ, PILOT_CARRIERS[p]);
			phaseReal += matched.real * PILOT_SIGNS[p];
			phaseImag += matched.imag * PILOT_SIGNS[p];
		}
		double pilotPhase = Math.atan2(phaseImag, phaseReal);
		/* L-SIG is entirely BPSK.  Squaring all 48 equalized data carriers
		 * removes their signs and gives a much less noisy phase estimate than
		 * four pilots alone; the pilots resolve the remaining pi ambiguity. */
		double doubledReal = 0, doubledImag = 0;
		for (int carrier : DATA_CARRIERS) {
			Complex value = equalize(signalI, signalQ, channelI, channelQ, carrier);
			doubledReal += value.real * value.real - value.imag * value.imag;
			doubledImag += 2d * value.real * value.imag;
		}
		double commonPhase = .5d * Math.atan2(doubledImag, doubledReal);
		if (Math.cos(commonPhase - pilotPhase) < 0) commonPhase += Math.PI;
		double cos = Math.cos(commonPhase), sin = Math.sin(commonPhase);
		double[] interleaved = new double[48];
		double error = 0;
		for (int index = 0; index < DATA_CARRIERS.length; index++) {
			Complex value = equalize(signalI, signalQ, channelI, channelQ, DATA_CARRIERS[index]);
			double real = value.real * cos + value.imag * sin;
			double imag = value.imag * cos - value.real * sin;
			Complex weighted = matched(signalI, signalQ, channelI, channelQ, DATA_CARRIERS[index]);
			interleaved[index] = weighted.real * cos + weighted.imag * sin;
			double ideal = real >= 0 ? 1d : -1d;
			error += (real - ideal) * (real - ideal) + imag * imag;
		}
		double[] coded = deinterleaveSignal(interleaved);
		int[] bits = viterbi(coded);
		boolean structure = bits != null && bits.length == 24 && bits[4] == 0;
		int parity = 0;
		if (bits != null) for (int n = 0; n <= 17; n++) parity ^= bits[n];
		boolean tail = bits != null;
		if (bits != null) for (int n = 18; n < 24; n++) tail &= bits[n] == 0;
		int rateCode = bits == null ? -1 : bits[0] | bits[1] << 1 | bits[2] << 2 | bits[3] << 3;
		String rate = rateName(rateCode);
		int length = 0;
		if (bits != null) for (int n = 0; n < 12; n++) length |= bits[5 + n] << n;
		double evmPercent = Math.sqrt(error / 48d) * 100d;
		boolean valid = structure && parity == 0 && tail && !"--".equals(rate)
				&& length > 0 && evmPercent <= 80d && lock.correlation >= LTF_THRESHOLD;
		double syncQuality = clamp01((lock.correlation - .45d) / .50d);
		double snrQuality = clamp01((snrDb - 3d) / 27d);
		double evmQuality = clamp01((45d - evmPercent) / 38d);
		double quality = 100d * (.35d * syncQuality + .30d * snrQuality + .35d * evmQuality);
		if (!valid) quality = Math.min(quality, 54d);
		String state;
		if (valid) {
			state = "Wi-Fi L-SIG decoded";
		} else if (bits == null) {
			state = "Wi-Fi preamble detected; L-SIG Viterbi failed";
		} else {
			StringBuilder rawRate = new StringBuilder(4);
			for (int n = 0; n < 4; n++) rawRate.append(bits[n]);
			state = "Wi-Fi preamble detected; invalid L-SIG (RATE " + rawRate
					+ ", R " + bits[4] + ", parity " + (parity == 0 ? "OK" : "bad")
					+ ", tail " + (tail ? "OK" : "bad") + ")";
		}
		return new Result(true, valid, state, quality, lock.correlation, cfoHz, snrDb, evmPercent,
				valid ? rate : "--", valid ? length : 0, detectedPreambles, decodedPackets,
				System.currentTimeMillis());
	}

	private WifiMacFrame decodeLegacy6Psdu(byte[] iq, LtfLock lock, int lengthBytes,
			long centerFreqHz) {
		int symbols = (16 + lengthBytes * 8 + 6 + 23) / 24;
		int dataStart = lock.usefulStart + 224;
		if (dataStart < 0 || dataStart + (symbols - 1) * 80 + 64 > iq.length / 2) return null;
		double[] firstI = fftSymbol(iq, lock.usefulStart, lock.cfoHz, lock.orientation);
		double[] firstQ = lastImag;
		double[] secondI = fftSymbol(iq, lock.usefulStart + FFT_SIZE, lock.cfoHz, lock.orientation);
		double[] secondQ = lastImag;
		double[] channelI = new double[FFT_SIZE], channelQ = new double[FFT_SIZE];
		for (int carrier = -26; carrier <= 26; carrier++) {
			if (carrier == 0) continue;
			int bin = bin(carrier), reference = LONG_TRAINING[carrier + 26];
			channelI[bin] = (firstI[bin] + secondI[bin]) * .5d * reference;
			channelQ[bin] = (firstQ[bin] + secondQ[bin]) * .5d * reference;
		}
		double[] coded = new double[symbols * 48];
		double previousPhase = 0d;
		for (int symbol = 0; symbol < symbols; symbol++) {
			double[] symbolI = fftSymbol(iq, dataStart + symbol * 80, lock.cfoHz, lock.orientation);
			double[] symbolQ = lastImag;
			double doubledReal = 0d, doubledImag = 0d;
			for (int carrier : DATA_CARRIERS) {
				Complex value = equalize(symbolI, symbolQ, channelI, channelQ, carrier);
				doubledReal += value.real * value.real - value.imag * value.imag;
				doubledImag += 2d * value.real * value.imag;
			}
			double phase = closestBpskPhase(.5d * Math.atan2(doubledImag, doubledReal), previousPhase);
			previousPhase = phase;
			double cos = Math.cos(phase), sin = Math.sin(phase);
			double[] interleaved = new double[48];
			for (int index = 0; index < DATA_CARRIERS.length; index++) {
				Complex weighted = matched(symbolI, symbolQ, channelI, channelQ, DATA_CARRIERS[index]);
				interleaved[index] = weighted.real * cos + weighted.imag * sin;
			}
			double[] deinterleaved = deinterleaveSignal(interleaved);
			System.arraycopy(deinterleaved, 0, coded, symbol * 48, 48);
		}
		int[] scrambled = viterbi(coded, false);
		if (scrambled == null || scrambled.length < 16 + lengthBytes * 8) return null;
		byte[] psdu = descramblePsdu(scrambled, lengthBytes);
		return psdu == null ? null : WifiMacFrame.parse(psdu, centerFreqHz);
	}

	private static byte[] descramblePsdu(int[] scrambled, int lengthBytes) {
		for (int mode = 0; mode < 2; mode++) {
			for (int seed = 1; seed < 128; seed++) {
				int state = seed;
				int[] bits = new int[16 + lengthBytes * 8];
				for (int n = 0; n < bits.length; n++) {
					int feedback;
					if (mode == 0) {
						feedback = (state >> 6 ^ state >> 3) & 1;
						state = (state << 1 & 0x7e) | feedback;
					} else {
						feedback = (state ^ state >> 3) & 1;
						state = state >> 1 | feedback << 6;
					}
					bits[n] = scrambled[n] ^ feedback;
				}
				boolean serviceZero = true;
				for (int n = 0; n < 16; n++) serviceZero &= bits[n] == 0;
				if (!serviceZero) continue;
				byte[] psdu = new byte[lengthBytes];
				for (int n = 0; n < psdu.length; n++) {
					int value = 0;
					for (int bit = 0; bit < 8; bit++) value |= bits[16 + n * 8 + bit] << bit;
					psdu[n] = (byte)value;
				}
				if (WifiMacFrame.validFcs(psdu)) return psdu;
			}
		}
		return null;
	}

	private static double closestBpskPhase(double phase, double reference) {
		while (phase - reference > Math.PI / 2d) phase -= Math.PI;
		while (phase - reference < -Math.PI / 2d) phase += Math.PI;
		return phase;
	}

	private static int legacyPacketEnd(LtfLock lock, Result result) {
		int dbps = "6 Mb/s".equals(result.rate) ? 24 : "9 Mb/s".equals(result.rate) ? 36
				: "12 Mb/s".equals(result.rate) ? 48 : "18 Mb/s".equals(result.rate) ? 72
				: "24 Mb/s".equals(result.rate) ? 96 : "36 Mb/s".equals(result.rate) ? 144
				: "48 Mb/s".equals(result.rate) ? 192 : "54 Mb/s".equals(result.rate) ? 216 : 0;
		if (!result.lSigValid || dbps <= 0) return lock.usefulStart + 208;
		int symbols = (16 + result.lengthBytes * 8 + 6 + dbps - 1) / dbps;
		return lock.usefulStart + 224 + Math.max(0, symbols - 1) * 80 + 64;
	}

	private static double packetLevelDbfs(byte[] iq, int start, int end) {
		end = Math.min(end, iq.length / 2);
		if (end <= start) return Double.NaN;
		double power = 0d;
		for (int n = start; n < end; n++) {
			double i = iq[n * 2], q = iq[n * 2 + 1]; power += i * i + q * q;
		}
		return 10d * Math.log10(Math.max(1e-12, power / ((end - start) * 2d * 128d * 128d)));
	}

	private double[] lastImag;

	private double[] fftSymbol(byte[] iq, int start, double cfoHz, int orientation) {
		double[] real = new double[FFT_SIZE], imag = new double[FFT_SIZE];
		for (int n = 0; n < FFT_SIZE; n++) {
			Complex sample = corrected(iq, start + n, cfoHz, orientation);
			real[n] = sample.real;
			imag[n] = sample.imag;
		}
		fft(real, imag);
		lastImag = imag;
		return real;
	}

	private Complex equalize(double[] symbolI, double[] symbolQ, double[] channelI, double[] channelQ,
			int carrier) {
		int b = bin(carrier);
		double denominator = channelI[b] * channelI[b] + channelQ[b] * channelQ[b];
		if (denominator < 1e-12) return Complex.ZERO;
		Complex matched = matched(symbolI, symbolQ, channelI, channelQ, carrier);
		return new Complex(matched.real / denominator, matched.imag / denominator);
	}

	private Complex matched(double[] symbolI, double[] symbolQ, double[] channelI, double[] channelQ,
			int carrier) {
		int b = bin(carrier);
		return new Complex(symbolI[b] * channelI[b] + symbolQ[b] * channelQ[b],
				symbolQ[b] * channelI[b] - symbolI[b] * channelQ[b]);
	}

	private double trainingCorrelation(byte[] iq, int start, double cfoHz, int orientation) {
		double real = 0, imag = 0, inputEnergy = 0, referenceEnergy = 0;
		for (int n = 0; n < FFT_SIZE; n++) {
			Complex sample = corrected(iq, start + n, cfoHz, orientation);
			real += sample.real * LONG_TIME_I[n] + sample.imag * LONG_TIME_Q[n];
			imag += sample.imag * LONG_TIME_I[n] - sample.real * LONG_TIME_Q[n];
			inputEnergy += sample.real * sample.real + sample.imag * sample.imag;
			referenceEnergy += LONG_TIME_I[n] * LONG_TIME_I[n] + LONG_TIME_Q[n] * LONG_TIME_Q[n];
		}
		return Math.hypot(real, imag) / Math.max(1e-12, Math.sqrt(inputEnergy * referenceEnergy));
	}

	private double shortCfo(byte[] iq, int start, int samples) {
		int count = Math.min(96, samples - start - SHORT_PERIOD);
		double real = 0, imag = 0;
		for (int n = 0; n < count; n++) {
			int a = (start + n) * 2, b = (start + n + SHORT_PERIOD) * 2;
			double ar = iq[a], ai = iq[a + 1], br = iq[b], bi = iq[b + 1];
			real += ar * br + ai * bi;
			imag += ar * bi - ai * br;
		}
		return Math.atan2(imag, real) * SAMPLE_RATE_HZ / (2d * Math.PI * SHORT_PERIOD);
	}

	private Complex corrected(byte[] iq, int sample, double cfoHz, int orientation) {
		double angle = -2d * Math.PI * cfoHz * sample / SAMPLE_RATE_HZ;
		double cos = Math.cos(angle), sin = Math.sin(angle);
		double real = iq[sample * 2] / 128d, imag = orientation * iq[sample * 2 + 1] / 128d;
		return new Complex(real * cos - imag * sin, real * sin + imag * cos);
	}

	private static double[] deinterleaveSignal(double[] received) {
		double[] output = new double[48];
		for (int k = 0; k < 48; k++) {
			int interleaved = 3 * (k & 15) + k / 16;
			output[k] = received[interleaved];
		}
		return output;
	}

	private static int[] viterbi(double[] coded) {
		return viterbi(coded, true);
	}

	private static int[] viterbi(double[] coded, boolean forceZeroEndState) {
		final int steps = coded.length / 2;
		double[] metric = new double[64], nextMetric = new double[64];
		Arrays.fill(metric, Double.NEGATIVE_INFINITY);
		metric[0] = 0;
		int[][] previous = new int[steps][64];
		int[][] input = new int[steps][64];
		for (int time = 0; time < steps; time++) {
			Arrays.fill(nextMetric, Double.NEGATIVE_INFINITY);
			for (int state = 0; state < 64; state++) {
				if (!Double.isFinite(metric[state])) continue;
				for (int bit = 0; bit <= 1; bit++) {
					int register = state << 1 | bit;
					int next = register & 63;
					/* Bit-reversed 133/171-octal polynomials for a newest-bit-at-LSB register. */
					int a = parity(register & 0x6d), b = parity(register & 0x4f);
					/* IEEE 802.11 BPSK maps coded 0 to -1 and coded 1 to +1. */
					double branch = (a == 0 ? -coded[time * 2] : coded[time * 2])
							+ (b == 0 ? -coded[time * 2 + 1] : coded[time * 2 + 1]);
					double candidate = metric[state] + branch;
					if (candidate > nextMetric[next]) {
						nextMetric[next] = candidate;
						previous[time][next] = state;
						input[time][next] = bit;
					}
				}
			}
			double[] swap = metric; metric = nextMetric; nextMetric = swap;
		}
		int state = 0;
		if (!forceZeroEndState)
			for (int candidate = 1; candidate < metric.length; candidate++)
				if (metric[candidate] > metric[state]) state = candidate;
		if (!Double.isFinite(metric[state])) return null;
		int[] bits = new int[steps];
		for (int time = steps - 1; time >= 0; time--) {
			bits[time] = input[time][state];
			state = previous[time][state];
		}
		return bits;
	}

	private static String rateName(int code) {
		switch (code) {
		case 0xb: return "6 Mb/s";
		case 0xf: return "9 Mb/s";
		case 0xa: return "12 Mb/s";
		case 0xe: return "18 Mb/s";
		case 0x9: return "24 Mb/s";
		case 0xd: return "36 Mb/s";
		case 0x8: return "48 Mb/s";
		case 0xc: return "54 Mb/s";
		default: return "--";
		}
	}

	private static int parity(int value) {
		value ^= value >>> 16; value ^= value >>> 8; value ^= value >>> 4;
		return (0x6996 >>> (value & 15)) & 1;
	}

	private static int bin(int carrier) { return (carrier + FFT_SIZE) & (FFT_SIZE - 1); }
	private static double clamp01(double value) { return Math.max(0d, Math.min(1d, value)); }

	private static void ifft(double[] real, double[] imag) {
		for (int n = 0; n < imag.length; n++) imag[n] = -imag[n];
		fft(real, imag);
		for (int n = 0; n < imag.length; n++) {
			real[n] /= real.length;
			imag[n] = -imag[n] / real.length;
		}
	}

	private static void fft(double[] real, double[] imag) {
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
			double angle = -2d * Math.PI / length, baseR = Math.cos(angle), baseI = Math.sin(angle);
			for (int start = 0; start < size; start += length) {
				double wr = 1, wi = 0;
				for (int n = 0; n < length / 2; n++) {
					int a = start + n, b = a + length / 2;
					double br = real[b] * wr - imag[b] * wi, bi = real[b] * wi + imag[b] * wr;
					real[b] = real[a] - br; imag[b] = imag[a] - bi;
					real[a] += br; imag[a] += bi;
					double nextR = wr * baseR - wi * baseI;
					wi = wr * baseI + wi * baseR; wr = nextR;
				}
			}
		}
	}

	private static final class Complex {
		static final Complex ZERO = new Complex(0, 0);
		final double real, imag;
		Complex(double real, double imag) { this.real = real; this.imag = imag; }
	}

	private static final class LtfLock {
		final int usefulStart;
		final double correlation, cfoHz;
		final int orientation;
		LtfLock(int usefulStart, double correlation, double cfoHz, int orientation) {
			this.usefulStart = usefulStart; this.correlation = correlation; this.cfoHz = cfoHz;
			this.orientation = orientation;
		}
	}

	static final class Observation {
		final WifiMacFrame mac;
		final String rate;
		final double levelDbfs, correlation, cfoHz;
		final long decodedMillis;

		Observation(WifiMacFrame mac, String rate, double levelDbfs, double correlation,
				double cfoHz, long decodedMillis) {
			this.mac = mac; this.rate = rate; this.levelDbfs = levelDbfs;
			this.correlation = correlation; this.cfoHz = cfoHz; this.decodedMillis = decodedMillis;
		}
	}

	static final class Result {
		final boolean detected, lSigValid;
		final String state;
		final double quality, ltfCorrelation, cfoHz, snrDb, evmPercent;
		final String rate;
		final int lengthBytes;
		final long detectedPreambles, decodedPackets, decodedMillis;

		Result(boolean detected, boolean lSigValid, String state, double quality, double ltfCorrelation,
				double cfoHz, double snrDb, double evmPercent, String rate, int lengthBytes,
				long detectedPreambles, long decodedPackets, long decodedMillis) {
			this.detected = detected; this.lSigValid = lSigValid; this.state = state;
			this.quality = quality; this.ltfCorrelation = ltfCorrelation; this.cfoHz = cfoHz;
			this.snrDb = snrDb; this.evmPercent = evmPercent; this.rate = rate;
			this.lengthBytes = lengthBytes;
			this.detectedPreambles = detectedPreambles; this.decodedPackets = decodedPackets;
			this.decodedMillis = decodedMillis;
		}

		Result withCounts(long preambles, long packets) {
			return new Result(detected, lSigValid, state, quality, ltfCorrelation, cfoHz, snrDb,
					evmPercent, rate, lengthBytes, preambles, packets, decodedMillis);
		}

		static Result empty(String state) {
			return new Result(false, false, state, 0, 0, 0, 0, 0, "--", 0, 0, 0, 0);
		}
	}
}
