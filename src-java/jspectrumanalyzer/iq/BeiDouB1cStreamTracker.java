package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Continuous B1C data-channel tracker feeding one 100-symbol/s stream per acquired PRN. */
final class BeiDouB1cStreamTracker {
	private static final int MAX_CHANNELS = 6, LOST_SECONDS = 30;
	private static final double HALF_CHIP_RATE_HZ = 2_046_000d, HALF_CHIPS_PER_CODE = 20_460d;
	private final Map<Integer, Channel> channels = new HashMap<Integer, Channel>();
	private long centerHz = Long.MIN_VALUE, samplesSeen; private int rateHz;

	synchronized void reset() { channels.clear(); centerHz = Long.MIN_VALUE; rateHz = 0; samplesSeen = 0L; }

	synchronized void accept(long center, int rate, byte[] iq, int length) {
		if (iq == null || length < 2 || rate <= 0) return;
		if (center != centerHz || rate != rateHz) {
			channels.clear(); centerHz = center; rateHz = rate; samplesSeen = 0L;
		}
		int samples = Math.min(length, iq.length) / 2;
		for (Channel channel : channels.values()) channel.accept(iq, samples);
		samplesSeen += samples;
	}

	synchronized void updateAcquisition(long center, int rate,
			java.util.List<BeiDouB1cSignalAnalyzer.Satellite> satellites, int acquisitionInputSamples) {
		if (center != centerHz || rate != rateHz || satellites == null || acquisitionInputSamples <= 0) return;
		Iterator<Channel> iterator = channels.values().iterator();
		while (iterator.hasNext()) if (samplesSeen - iterator.next().lastAcquiredSample > (long) rate * LOST_SECONDS)
			iterator.remove();
		for (BeiDouB1cSignalAnalyzer.Satellite satellite : satellites) {
			if (!satellite.acquired) continue;
			double phaseAtWindowStart = wrap(-satellite.codePhaseSamples * HALF_CHIPS_PER_CODE
					/ BeiDouB1cSignalAnalyzer.ACQUISITION_SAMPLES);
			double phaseAtNextSample = wrap(phaseAtWindowStart + acquisitionInputSamples * HALF_CHIP_RATE_HZ / rate);
			Channel channel = channels.get(Integer.valueOf(satellite.prn));
			if (channel == null && channels.size() < MAX_CHANNELS) {
				channel = new Channel(satellite.prn, center, rate, satellite.dopplerHz, phaseAtNextSample, samplesSeen);
				channels.put(Integer.valueOf(satellite.prn), channel);
			} else if (channel != null) channel.correct(phaseAtNextSample, satellite.dopplerHz, samplesSeen);
			if (channel != null) channel.lastAcquiredSample = samplesSeen;
		}
	}

	synchronized Tracking tracking(int prn) {
		Channel channel = channels.get(Integer.valueOf(prn)); return channel == null ? Tracking.NONE : channel.snapshot();
	}
	synchronized int trackedCount() { int count = 0; for (Channel c : channels.values()) if (c.locked()) count++; return count; }
	synchronized int decodedCount() { int count = 0; for (Channel c : channels.values()) if (c.frameFresh()) count++; return count; }
	synchronized int ephemerisCount() {
		int count = 0; for (Channel c : channels.values()) if (c.decoder.navigation().ephemeris != null) count++; return count;
	}
	synchronized List<Observation> observations() {
		List<Observation> result = new ArrayList<Observation>(); if (rateHz <= 0) return result;
		double receptionSeconds = samplesSeen / (double) rateHz;
		for (Channel channel : channels.values()) {
			BeiDouB1cNavigationData.Snapshot nav = channel.decoder.navigation();
			if (!channel.epochAligned || channel.timingWeek < 0 || channel.timingTow < 0 || nav.ephemeris == null
					|| (nav.frame.health >= 0 && nav.frame.health != 0)) continue;
			long epoch = channel.symbols + channel.rangeEpochAdjustment;
			long age = epoch - channel.timingAnchorEpoch; if (age < 0L || age > 18_000L) continue;
			double satelliteTime = channel.timingTow + age * .010d + channel.halfChipPhase / HALF_CHIP_RATE_HZ;
			result.add(new Observation(channel.prn, channel.timingWeek, satelliteTime, receptionSeconds,
					channel.stability, nav.ephemeris));
		}
		return result;
	}
	synchronized int[] activePrns() {
		int[] result = new int[channels.size()]; int index = 0;
		for (Integer prn : channels.keySet()) result[index++] = prn.intValue(); return result;
	}

	private static double wrap(double value) {
		while (value >= HALF_CHIPS_PER_CODE) value -= HALF_CHIPS_PER_CODE;
		while (value < 0d) value += HALF_CHIPS_PER_CODE; return value;
	}
	private static double signedError(double value) {
		while (value > HALF_CHIPS_PER_CODE / 2d) value -= HALF_CHIPS_PER_CODE;
		while (value < -HALF_CHIPS_PER_CODE / 2d) value += HALF_CHIPS_PER_CODE; return value;
	}

	static final class Tracking {
		static final Tracking NONE = new Tracking(0L, 0d, false, "Not tracked", BeiDouB1cNavDecoder.Frame.waiting(),
				BeiDouB1cNavigationData.Snapshot.waiting());
		final long symbols; final double stability; final boolean locked; final String navState;
		final BeiDouB1cNavDecoder.Frame frame; final BeiDouB1cNavigationData.Snapshot navigation;
		Tracking(long symbols, double stability, boolean locked, String navState, BeiDouB1cNavDecoder.Frame frame,
				BeiDouB1cNavigationData.Snapshot navigation) {
			this.symbols = symbols; this.stability = stability; this.locked = locked; this.navState = navState;
			this.frame = frame; this.navigation = navigation;
		}
	}
	static final class Observation {
		final int prn, week; final double satelliteTime, receptionSeconds, trackingStability;
		final BeiDouB1cNavigationData.Ephemeris ephemeris;
		Observation(int prn, int week, double satelliteTime, double receptionSeconds,
				double trackingStability, BeiDouB1cNavigationData.Ephemeris ephemeris) {
			this.prn=prn; this.week=week; this.satelliteTime=satelliteTime; this.receptionSeconds=receptionSeconds;
			this.trackingStability=trackingStability; this.ephemeris=ephemeris;
		}
	}

	private static final class Channel {
		final int prn, rate; final long center; final int[] code; final BeiDouB1cNavDecoder decoder;
		double halfChipPhase, halfChipStep, oscillatorRe, oscillatorIm, oscillatorStepRe, oscillatorStepIm;
		double promptRe, promptIm, promptAbsolute, previousPromptRe, previousPromptIm, phaseCorrection, stability;
		boolean epochAligned, havePrompt; long symbols, lastAcquiredSample, lastCorrectionSample, lastDecodedSymbol = Long.MIN_VALUE;
		long rangeEpochAdjustment, timingAnchorEpoch = Long.MIN_VALUE; int timingWeek = -1, timingTow = -1, navigationFrameCount;

		Channel(int prn, long center, int rate, int dopplerHz, double phaseAtNextSample, long firstSample) {
			this.prn = prn; this.center = center; this.rate = rate; code = BeiDouB1cCodes.primaryCode(prn, false);
			decoder = new BeiDouB1cNavDecoder(prn); halfChipPhase = phaseAtNextSample;
			halfChipStep = carrierAidedCodeStep(dopplerHz); lastCorrectionSample = firstSample;
			setCarrier(dopplerHz, firstSample); lastAcquiredSample = firstSample;
		}

		void correct(double desiredPhase, int dopplerHz, long sample) {
			double error = signedError(desiredPhase - halfChipPhase); long elapsed = sample - lastCorrectionSample;
			double nominal = HALF_CHIP_RATE_HZ / rate, aided = carrierAidedCodeStep(dopplerHz);
			if (elapsed > 0L) halfChipStep += .65d * error / elapsed;
			halfChipStep += .20d * (aided - halfChipStep);
			halfChipStep = Math.max(nominal * .9998d, Math.min(nominal * 1.0002d, halfChipStep));
			double corrected = halfChipPhase + .90d * error;
			if (corrected >= HALF_CHIPS_PER_CODE) rangeEpochAdjustment++; else if (corrected < 0d) rangeEpochAdjustment--;
			halfChipPhase = wrap(corrected); lastCorrectionSample = sample;
			setCarrier(dopplerHz, sample);
		}

		private double carrierAidedCodeStep(int dopplerHz) {
			return HALF_CHIP_RATE_HZ * (1d + dopplerHz / (double) BeiDouB1cSignalAnalyzer.B1C_FREQUENCY_HZ) / rate;
		}
		private void setCarrier(int dopplerHz, long sample) {
			double offset = BeiDouB1cSignalAnalyzer.B1C_FREQUENCY_HZ - center + dopplerHz;
			double step = -2d * Math.PI * offset / rate;
			oscillatorStepRe = Math.cos(step); oscillatorStepIm = Math.sin(step);
			double start = step * (sample % rate); oscillatorRe = Math.cos(start); oscillatorIm = Math.sin(start);
		}

		void accept(byte[] iq, int samplesInBlock) {
			for (int n = 0; n < samplesInBlock; n++) {
				int half = Math.min(20_459, Math.max(0, (int) halfChipPhase));
				double replica = code[half >>> 1] * ((half & 1) == 0 ? 1d : -1d);
				if (!epochAligned) {
					advanceOscillator(); if (advanceCode()) { epochAligned = true; clearPrompt(); } continue;
				}
				double i = iq[n * 2], q = iq[n * 2 + 1];
				double mixedRe = i * oscillatorRe - q * oscillatorIm;
				double mixedIm = i * oscillatorIm + q * oscillatorRe;
				promptRe += replica * mixedRe; promptIm += replica * mixedIm;
				promptAbsolute += Math.abs(mixedRe) + Math.abs(mixedIm);
				advanceOscillator(); if (advanceCode()) finishEpoch();
			}
		}

		private boolean advanceCode() {
			halfChipPhase += halfChipStep; if (halfChipPhase < HALF_CHIPS_PER_CODE) return false;
			halfChipPhase -= HALF_CHIPS_PER_CODE; return true;
		}
		private void advanceOscillator() {
			double nextRe = oscillatorRe * oscillatorStepRe - oscillatorIm * oscillatorStepIm;
			oscillatorIm = oscillatorRe * oscillatorStepIm + oscillatorIm * oscillatorStepRe; oscillatorRe = nextRe;
		}
		private void finishEpoch() {
			if (!havePrompt) { phaseCorrection = Math.atan2(promptIm, promptRe); havePrompt = true; }
			else {
				double currentSqRe = promptRe * promptRe - promptIm * promptIm, currentSqIm = 2d * promptRe * promptIm;
				double previousSqRe = previousPromptRe * previousPromptRe - previousPromptIm * previousPromptIm;
				double previousSqIm = 2d * previousPromptRe * previousPromptIm;
				phaseCorrection += .5d * Math.atan2(currentSqIm * previousSqRe - currentSqRe * previousSqIm,
						currentSqRe * previousSqRe + currentSqIm * previousSqIm);
			}
			double soft = promptRe * Math.cos(phaseCorrection) + promptIm * Math.sin(phaseCorrection);
			double epochCoherence = Math.hypot(promptRe, promptIm) / Math.max(1d, promptAbsolute);
			stability += .04d * (epochCoherence - stability);
			BeiDouB1cNavDecoder.Frame before = decoder.latestFrame(); decoder.acceptSoft(soft); symbols++;
			if (decoder.latestFrame() != before && decoder.latestFrame().valid) lastDecodedSymbol = symbols;
			BeiDouB1cNavigationData.Snapshot nav = decoder.navigation();
			if (nav.frameCount > navigationFrameCount) {
				navigationFrameCount = nav.frameCount;
				if (nav.ephemeris != null && nav.frame.valid) {
					timingWeek = nav.ephemeris.week; timingTow = nav.ephemeris.towSeconds;
					timingAnchorEpoch = nav.frame.frameStartSymbol - 1L + rangeEpochAdjustment;
				}
			}
			previousPromptRe = promptRe; previousPromptIm = promptIm; clearPrompt();
		}
		private void clearPrompt() { promptRe = promptIm = promptAbsolute = 0d; }
		private boolean locked() { return symbols >= 5L && stability >= .025d; }
		private boolean frameFresh() { return decoder.latestFrame().valid && symbols - lastDecodedSymbol <= 3_600L; }
		Tracking snapshot() {
			BeiDouB1cNavDecoder.Frame frame = frameFresh() ? decoder.latestFrame() : BeiDouB1cNavDecoder.Frame.waiting();
			BeiDouB1cNavigationData.Snapshot nav = decoder.navigation();
			String state = nav.frame.valid ? "B-CNAV1 SF2 CRC OK; eph " + nav.ephemerisText()
					+ (nav.frame.sf3CrcValid ? "; SF3 P" + nav.frame.pageId : "")
					: frame.valid ? "B-CNAV1 SF1 BCH OK; SOH " + frame.secondsOfHour + " s"
					: locked() ? "Collecting B-CNAV1 symbols" : symbols == 0 ? "Code timing" : "Tracking " + symbols + " sym";
			return new Tracking(symbols, stability, locked(), state, frame, nav);
		}
	}
}
