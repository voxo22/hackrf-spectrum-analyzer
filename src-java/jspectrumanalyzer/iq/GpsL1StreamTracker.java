package jspectrumanalyzer.iq;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Iterator;

/**
 * Keeps code/carrier prompt correlations continuous between coarse acquisition
 * runs.  Each channel tests all twenty possible LNAV (50 bit/s) bit phases;
 * only a complete preamble plus all LNAV parity words promotes it to decoded.
 */
final class GpsL1StreamTracker {
	private static final int MAX_TRACKED_CHANNELS = 6;
	private static final int LOST_CHANNEL_SECONDS = 30;
	private final Map<Integer, Channel> channels = new HashMap<Integer, Channel>();
	private long centerFrequencyHz = Long.MIN_VALUE;
	private int sampleRateHz;
	private long samplesSeen;

	synchronized void reset() { channels.clear(); centerFrequencyHz = Long.MIN_VALUE; sampleRateHz = 0; samplesSeen = 0L; }

	synchronized void accept(long centerHz, int rateHz, byte[] iq, int length) {
		if (iq == null || length < 2 || rateHz <= 0) return;
		if (centerHz != centerFrequencyHz || rateHz != sampleRateHz) {
			channels.clear(); centerFrequencyHz = centerHz; sampleRateHz = rateHz; samplesSeen = 0L;
		}
		int samples = (length & ~1) / 2;
		for (Channel channel : channels.values()) channel.accept(iq, samples, samplesSeen);
		samplesSeen += samples;
	}

	/** Adds only channels whose short prompt verification passed in acquisition. */
	synchronized void updateAcquisition(long centerHz, int rateHz, List<GpsL1SignalAnalyzer.Satellite> satellites) {
		if (centerHz != centerFrequencyHz || rateHz != sampleRateHz || satellites == null) return;
		int samplesPerMs = (int) Math.round(rateHz / 1000d);
		long lastMsStart = samplesSeen - samplesPerMs;
		if (lastMsStart < 0) return;
		for (GpsL1SignalAnalyzer.Satellite satellite : satellites) {
			Channel channel = channels.get(Integer.valueOf(satellite.prn));
			if (channel != null && satellite.acquired && satellite.trackingLocked) channel.lastAcquiredSample = samplesSeen;
		}
		Iterator<Channel> iterator = channels.values().iterator();
		while (iterator.hasNext()) {
			Channel channel = iterator.next();
			if (samplesSeen - channel.lastAcquiredSample > (long) rateHz * LOST_CHANNEL_SECONDS
					&& (!channel.latestNav.valid || channel.milliseconds - channel.lastNavMilliseconds > LOST_CHANNEL_SECONDS * 1000L))
				iterator.remove();
		}
		for (GpsL1SignalAnalyzer.Satellite satellite : satellites) {
			if (!satellite.acquired || !satellite.trackingLocked) continue;
			double preciseSamples = satellite.codePhaseChips * rateHz / 1_023_000d;
			double phaseAtStreamStart = (preciseSamples - (lastMsStart % samplesPerMs) + samplesPerMs) % samplesPerMs;
			Channel channel = channels.get(Integer.valueOf(satellite.prn));
			if (channel == null) {
				if (channels.size() < MAX_TRACKED_CHANNELS)
					channels.put(Integer.valueOf(satellite.prn), new Channel(satellite.prn, satellite.dopplerHz, phaseAtStreamStart, rateHz, samplesSeen));
			} else {
				channel.correct(phaseAtStreamStart, satellite.dopplerHz);
			}
		}
	}

	synchronized String navState(int prn) {
		Channel channel = channels.get(Integer.valueOf(prn));
		if (channel == null) return "Not tracked";
		GpsL1LnavDecoder.Result result = channel.latestNav;
		if (result.valid && channel.milliseconds - channel.lastNavMilliseconds <= 90_000L) return "LNAV SF " + result.subframeId;
		return channel.milliseconds < 20 ? "Timing" : "Collecting";
	}

	synchronized GpsL1NavigationData.Snapshot navigation(int prn) {
		Channel channel = channels.get(Integer.valueOf(prn));
		return channel == null ? null : channel.navigation.snapshot(channel.milliseconds);
	}

	/** Snapshot all code clocks at one common input sample epoch. */
	synchronized List<Observation> observations() {
		List<Observation> result = new ArrayList<Observation>();
		double receptionSeconds = sampleRateHz <= 0 ? 0d : samplesSeen / (double) sampleRateHz;
		for (Channel channel : channels.values()) {
			GpsL1NavigationData.Snapshot navigation = channel.navigation.snapshot(channel.milliseconds);
			if (navigation.ephemeris == null || navigation.healthCode != 0 || !channel.codeEpochAligned) continue;
			int best = -1;
			for (int phase = 0; phase < 20; phase++) {
				if (channel.anchorNav[phase] == null || channel.milliseconds + channel.rangeEpochAdjustment - channel.anchorEpoch[phase] > 18_000L) continue;
				if (best < 0 || channel.bitQuality[phase] > channel.bitQuality[best]) best = phase;
			}
			if (best < 0 || channel.bitQuality[best] < .35d) continue;
			double elapsedCodeMs = channel.milliseconds + channel.rangeEpochAdjustment - channel.anchorEpoch[best]
					+ channel.codePhaseChips / 1023d;
			double satelliteTime = channel.anchorNav[best].timeOfWeekSeconds + elapsedCodeMs * .001d;
			result.add(new Observation(channel.prn, satelliteTime, receptionSeconds,
					channel.bitQuality[best], best, navigation.ephemeris, navigation.utc));
		}
		return result;
	}

	static final class Observation {
		final int prn, bitPhase;
		final double satelliteTime, receptionSeconds, bitQuality;
		final GpsL1NavigationData.Ephemeris ephemeris;
		final GpsL1NavigationData.UtcModel utc;
		Observation(int prn, double satelliteTime, double bitQuality, int bitPhase, GpsL1NavigationData.Ephemeris ephemeris) {
			this(prn, satelliteTime, 0d, bitQuality, bitPhase, ephemeris, null);
		}
		Observation(int prn, double satelliteTime, double bitQuality, int bitPhase, GpsL1NavigationData.Ephemeris ephemeris, GpsL1NavigationData.UtcModel utc) {
			this(prn, satelliteTime, 0d, bitQuality, bitPhase, ephemeris, utc);
		}
		Observation(int prn, double satelliteTime, double receptionSeconds, double bitQuality, int bitPhase,
				GpsL1NavigationData.Ephemeris ephemeris, GpsL1NavigationData.UtcModel utc) {
			this.prn = prn; this.satelliteTime = satelliteTime; this.bitQuality = bitQuality;
			this.receptionSeconds = receptionSeconds; this.bitPhase = bitPhase; this.ephemeris = ephemeris; this.utc = utc;
		}
	}

	synchronized int decodedCount() {
		int count = 0; for (Channel channel : channels.values())
			if (channel.latestNav.valid && channel.milliseconds - channel.lastNavMilliseconds <= 90_000L) count++;
		return count;
	}

	private static final class Channel {
		final int prn, sampleRateHz, samplesPerMs;
		double codePhaseChips, codePhaseStep;
		long lastCodeCorrectionSample, lastCalibrationSample;
		final int[] code;
		final GpsL1LnavDecoder[] bitDecoders = new GpsL1LnavDecoder[20];
		final GpsL1LnavDecoder.Result[] processedNav = new GpsL1LnavDecoder.Result[20];
		final GpsL1LnavDecoder.Result[] anchorNav = new GpsL1LnavDecoder.Result[20];
		final long[] anchorEpoch = new long[20];
		final GpsL1NavigationData navigation = new GpsL1NavigationData();
		final double[] bitSums = new double[20];
		final double[] bitAbsoluteSums = new double[20], bitQuality = new double[20];
		final int[] bitCounts = new int[20];
		double oscillatorRe, oscillatorIm, oscillatorStepRe, oscillatorStepIm;
		double phaseCorrection;
		double previousPromptRe, previousPromptIm;
		boolean havePrompt, codeEpochAligned;
		long milliseconds, lastNavMilliseconds, rangeEpochAdjustment, lastAcquiredSample;
		double promptRe, promptIm;
		GpsL1LnavDecoder.Result latestNav = GpsL1LnavDecoder.Result.waiting();

		Channel(int prn, int dopplerHz, double codeOffsetSamples, int sampleRateHz, long firstSample) {
			this.prn = prn; this.sampleRateHz = sampleRateHz; this.samplesPerMs = (int) Math.round(sampleRateHz / 1000d);
			code = GpsL1SignalAnalyzer.caCode(prn);
			codePhaseStep = 1_023_000d / sampleRateHz;
			codePhaseChips = phaseForSample(firstSample, codeOffsetSamples);
			lastCodeCorrectionSample = lastCalibrationSample = lastAcquiredSample = firstSample;
			for (int phase = 0; phase < bitDecoders.length; phase++) bitDecoders[phase] = new GpsL1LnavDecoder();
			double step = -2d * Math.PI * dopplerHz / sampleRateHz;
			oscillatorStepRe = Math.cos(step); oscillatorStepIm = Math.sin(step);
			double start = step * firstSample;
			oscillatorRe = Math.cos(start); oscillatorIm = Math.sin(start);
		}

		void correct(double correctedCodeOffsetSamples, int correctedDopplerHz) {
			/* The FFT acquisition measures code phase at the current stream end.
			 * It corrects both the phase and, very gently, the code-NCO frequency;
			 * this accommodates sample-clock ppm error without breaking 1 ms epochs. */
			double desiredPhase = phaseForSample(lastCodeCorrectionSample, correctedCodeOffsetSamples);
			double error = signedChipError(desiredPhase - codePhaseChips);
			double correctedPhase = codePhaseChips + .35d * error;
			/* A fractional correction can cross a code-epoch boundary between
			 * input samples. Preserve the corresponding integer millisecond in
			 * the ranging clock instead of introducing a 300 km jump. */
			if (correctedPhase >= 1023d) rangeEpochAdjustment++;
			else if (correctedPhase < 0d) rangeEpochAdjustment--;
			codePhaseChips = wrapChips(correctedPhase);
			long elapsed = Math.max(1L, lastCodeCorrectionSample - lastCalibrationSample);
			lastCalibrationSample = lastCodeCorrectionSample;
			double nominalStep = 1_023_000d / sampleRateHz;
			codePhaseStep = Math.max(nominalStep * .9998d, Math.min(nominalStep * 1.0002d,
					codePhaseStep + .03d * error / elapsed));
			double step = -2d * Math.PI * correctedDopplerHz / sampleRateHz;
			oscillatorStepRe = Math.cos(step); oscillatorStepIm = Math.sin(step);
		}

		void accept(byte[] iq, int samples, long firstSample) {
			for (int n = 0; n < samples; n++) {
				int chip = chipAt(codePhaseChips);
				if (!codeEpochAligned) {
					advanceOscillator(); if (advanceCodePhase()) { codeEpochAligned = true; clearPrompt(); }
					continue;
				}
				double i = iq[n * 2], q = iq[n * 2 + 1];
				double mixedRe = i * oscillatorRe - q * oscillatorIm, mixedIm = i * oscillatorIm + q * oscillatorRe;
				promptRe += code[chip] * mixedRe; promptIm += code[chip] * mixedIm;
				advanceOscillator();
				if (advanceCodePhase()) finishMillisecond();
			}
			lastCodeCorrectionSample = firstSample + samples;
		}

		private double phaseForSample(long sample, double codeOffsetSamples) {
			double phaseSample = ((sample % samplesPerMs) + codeOffsetSamples) % samplesPerMs;
			if (phaseSample < 0d) phaseSample += samplesPerMs;
			return phaseSample * 1_023_000d / sampleRateHz;
		}

		private static double wrapChips(double value) {
			while (value >= 1023d) value -= 1023d;
			while (value < 0d) value += 1023d;
			return value;
		}

		private static double signedChipError(double value) {
			while (value > 511.5d) value -= 1023d;
			while (value < -511.5d) value += 1023d;
			return value;
		}

		private int chipAt(double phase) { return Math.min(1022, Math.max(0, (int) phase)); }

		private boolean advanceCodePhase() {
			codePhaseChips += codePhaseStep;
			if (codePhaseChips < 1023d) return false;
			codePhaseChips -= 1023d; return true;
		}

		private void advanceOscillator() {
			double nextRe = oscillatorRe * oscillatorStepRe - oscillatorIm * oscillatorStepIm;
			oscillatorIm = oscillatorRe * oscillatorStepIm + oscillatorIm * oscillatorStepRe;
			oscillatorRe = nextRe;
		}

		private void finishMillisecond() {
			if (!havePrompt) { phaseCorrection = Math.atan2(promptIm, promptRe); havePrompt = true; }
			else {
				double currentSqRe = promptRe * promptRe - promptIm * promptIm;
				double currentSqIm = 2d * promptRe * promptIm;
				double previousSqRe = previousPromptRe * previousPromptRe - previousPromptIm * previousPromptIm;
				double previousSqIm = 2d * previousPromptRe * previousPromptIm;
				double phaseError = .5d * Math.atan2(currentSqIm * previousSqRe - currentSqRe * previousSqIm,
						currentSqRe * previousSqRe + currentSqIm * previousSqIm);
				/* Squared prompt phase removes the BPSK data sign.  A direct
				 * differential correction is more robust here than a slow loop while
				 * coarse Doppler is periodically refreshed by acquisition. */
				phaseCorrection += phaseError;
			}
			double cosine = Math.cos(phaseCorrection), sine = Math.sin(phaseCorrection);
			double soft = promptRe * cosine + promptIm * sine;
			int phase = (int) (milliseconds % 20L);
			for (int hypothesis = 0; hypothesis < 20; hypothesis++) {
				int bitPosition = (phase + 20 - hypothesis) % 20;
				if (bitPosition == 0) { bitSums[hypothesis] = 0d; bitAbsoluteSums[hypothesis] = 0d; bitCounts[hypothesis] = 0; }
				bitSums[hypothesis] += soft; bitAbsoluteSums[hypothesis] += Math.abs(soft); bitCounts[hypothesis]++;
				if (bitPosition == 19 && bitCounts[hypothesis] == 20) {
					double quality = Math.abs(bitSums[hypothesis]) / Math.max(1d, bitAbsoluteSums[hypothesis]);
					bitQuality[hypothesis] += .02d * (quality - bitQuality[hypothesis]);
					bitDecoders[hypothesis].acceptBit(bitSums[hypothesis] >= 0d ? 1 : 0);
					GpsL1LnavDecoder.Result result = bitDecoders[hypothesis].result();
					if (result.valid && result != processedNav[hypothesis]) {
						processedNav[hypothesis] = result; latestNav = result; lastNavMilliseconds = milliseconds;
						anchorNav[hypothesis] = result; anchorEpoch[hypothesis] = milliseconds + 1 + rangeEpochAdjustment;
						navigation.accept(result, milliseconds);
					}
				}
			}
			previousPromptRe = promptRe; previousPromptIm = promptIm;
			clearPrompt(); milliseconds++;
		}

		private void clearPrompt() { promptRe = promptIm = 0d; }
	}
}
