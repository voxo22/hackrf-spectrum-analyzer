package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Continuous E1-B primary-code/carrier tracker feeding 250 soft symbols/s to I/NAV. */
final class GalileoE1StreamTracker {
	private static final int MAX_CHANNELS = 4;
	private static final int LOST_SECONDS = 30;
	private static final double HALF_CHIP_RATE_HZ = 2_046_000d;
	private static final double HALF_CHIPS_PER_CODE = 8184d;
	private final Map<Integer, Channel> channels = new HashMap<Integer, Channel>();
	private long centerHz = Long.MIN_VALUE, samplesSeen;
	private int rateHz;

	synchronized void reset() { channels.clear(); centerHz = Long.MIN_VALUE; rateHz = 0; samplesSeen = 0L; }

	synchronized void accept(long center, int rate, byte[] iq, int length) {
		if (iq == null || length < 2 || rate <= 0) return;
		if (center != centerHz || rate != rateHz) {
			channels.clear(); centerHz = center; rateHz = rate; samplesSeen = 0L;
		}
		int samples = Math.min(length, iq.length) / 2;
		for (Channel channel : channels.values()) channel.accept(iq, samples, samplesSeen);
		samplesSeen += samples;
	}

	synchronized void updateAcquisition(long center, int rate,
			List<GalileoE1SignalAnalyzer.Satellite> satellites, int acquisitionInputSamples) {
		if (center != centerHz || rate != rateHz || satellites == null || acquisitionInputSamples <= 0) return;
		Iterator<Channel> iterator = channels.values().iterator();
		while (iterator.hasNext()) if (samplesSeen - iterator.next().lastAcquiredSample > (long) rate * LOST_SECONDS) iterator.remove();
		for (GalileoE1SignalAnalyzer.Satellite satellite : satellites) {
			if (!satellite.acquired) continue;
			double phaseAtWindowStart = wrap(-satellite.codePhaseSamples * HALF_CHIPS_PER_CODE / 16384d);
			double phaseAtNextSample = wrap(phaseAtWindowStart + acquisitionInputSamples * HALF_CHIP_RATE_HZ / rate);
			Channel channel = channels.get(Integer.valueOf(satellite.svid));
			if (channel == null) {
				if (channels.size() < MAX_CHANNELS) {
					channel = new Channel(satellite.svid, center, rate, satellite.dopplerHz,
							phaseAtNextSample, samplesSeen);
					channels.put(Integer.valueOf(satellite.svid), channel);
				}
			} else channel.correct(phaseAtNextSample, satellite.dopplerHz, samplesSeen);
			if (channel != null) channel.lastAcquiredSample = samplesSeen;
		}
	}

	synchronized Tracking tracking(int svid) {
		Channel channel = channels.get(Integer.valueOf(svid));
		return channel == null ? Tracking.NONE : channel.snapshot();
	}

	synchronized int trackedCount() {
		int count = 0; for (Channel channel : channels.values()) if (channel.locked()) count++; return count;
	}

	synchronized int decodedCount() {
		int count = 0; for (Channel channel : channels.values()) if (channel.pageFresh()) count++; return count;
	}

	synchronized int ephemerisCount() {
		int count = 0;
		for (Channel channel : channels.values()) if (channel.navigation.snapshot(channel.symbols).ephemeris != null) count++;
		return count;
	}

	/** Snapshot all Galileo code clocks at the current input-sample epoch. */
	synchronized List<Observation> observations() {
		List<Observation> result = new ArrayList<Observation>();
		if (rateHz <= 0) return result;
		double receptionSeconds = samplesSeen / (double) rateHz;
		for (Channel channel : channels.values()) {
			GalileoNavigationData.Snapshot navigation = channel.navigation.snapshot(channel.symbols);
			if (!channel.epochAligned || channel.timingWeek < 0 || channel.timingTow < 0
					|| navigation.ephemeris == null || navigation.healthCode != 0
					|| navigation.dataValidity != 0 || !Double.isFinite(navigation.bgdE1E5bSeconds)) continue;
			long age = channel.symbols + channel.rangeEpochAdjustment - channel.timingAnchorEpoch;
			if (age < 0L || age > 90L * 250L) continue;
			double satelliteTime = satelliteTime(channel.timingTow,
					channel.symbols + channel.rangeEpochAdjustment, channel.timingAnchorEpoch, channel.halfChipPhase);
			result.add(new Observation(channel.svid, satelliteTime, receptionSeconds,
					channel.stability, channel.timingWeek, navigation.bgdE1E5bSeconds, navigation.ephemeris));
		}
		return result;
	}

	synchronized int[] activeSvids() {
		int[] result = new int[channels.size()]; int index = 0;
		for (Integer svid : channels.keySet()) result[index++] = svid.intValue();
		return result;
	}

	private static double wrap(double value) {
		while (value >= HALF_CHIPS_PER_CODE) value -= HALF_CHIPS_PER_CODE;
		while (value < 0d) value += HALF_CHIPS_PER_CODE;
		return value;
	}

	private static double signedError(double value) {
		while (value > HALF_CHIPS_PER_CODE / 2d) value -= HALF_CHIPS_PER_CODE;
		while (value < -HALF_CHIPS_PER_CODE / 2d) value += HALF_CHIPS_PER_CODE;
		return value;
	}

	static long pageStartEpoch(long completedSymbols, long rangeEpochAdjustment) {
		return completedSymbols + rangeEpochAdjustment - 500L;
	}

	static double satelliteTime(int pageTow, long currentEpoch, long pageStartEpoch, double halfChipPhase) {
		return pageTow + (currentEpoch - pageStartEpoch) * .004d + halfChipPhase / HALF_CHIP_RATE_HZ;
	}

	static final class Tracking {
		static final Tracking NONE = new Tracking(0L, 0d, false, "Not tracked",
				GalileoInavDecoder.Page.waiting(), GalileoNavigationData.Snapshot.waiting());
		final long symbols; final double stability; final boolean locked; final String navState;
		final GalileoInavDecoder.Page page;
		final GalileoNavigationData.Snapshot navigation;
		Tracking(long symbols, double stability, boolean locked, String navState, GalileoInavDecoder.Page page,
				GalileoNavigationData.Snapshot navigation) {
			this.symbols = symbols; this.stability = stability; this.locked = locked;
			this.navState = navState; this.page = page; this.navigation = navigation;
		}
	}

	static final class Observation {
		final int svid, weekModulo4096;
		final double satelliteTime, receptionSeconds, trackingStability, bgdE1E5bSeconds;
		final GalileoNavigationData.Ephemeris ephemeris;
		Observation(int svid, double satelliteTime, double receptionSeconds, double trackingStability,
				int weekModulo4096, double bgdE1E5bSeconds, GalileoNavigationData.Ephemeris ephemeris) {
			this.svid = svid; this.satelliteTime = satelliteTime; this.receptionSeconds = receptionSeconds;
			this.trackingStability = trackingStability; this.weekModulo4096 = weekModulo4096;
			this.bgdE1E5bSeconds = bgdE1E5bSeconds; this.ephemeris = ephemeris;
		}
	}

	private static final class Channel {
		final int svid, rate; final long center; final int[] code;
		final GalileoInavDecoder decoder = new GalileoInavDecoder();
		final GalileoNavigationData navigation;
		double halfChipPhase, halfChipStep, oscillatorRe, oscillatorIm, oscillatorStepRe, oscillatorStepIm;
		double promptRe, promptIm, promptAbsolute, previousPromptRe, previousPromptIm, phaseCorrection;
		double stability;
		boolean epochAligned, havePrompt;
		long symbols, lastAcquiredSample, lastCorrectionSample, lastDecodedSymbol = Long.MIN_VALUE;
		long rangeEpochAdjustment, timingAnchorEpoch = Long.MIN_VALUE;
		int timingWeek = -1, timingTow = -1;

		Channel(int svid, long center, int rate, int dopplerHz, double phaseAtNextSample, long firstSample) {
			this.svid = svid; this.center = center; this.rate = rate; this.code = GalileoE1SignalAnalyzer.primaryCode(svid);
			this.navigation = new GalileoNavigationData(svid);
			halfChipPhase = phaseAtNextSample; halfChipStep = carrierAidedCodeStep(dopplerHz);
			lastCorrectionSample = firstSample;
			setCarrier(center, dopplerHz, firstSample); lastAcquiredSample = firstSample;
		}

		void correct(double desiredPhase, int dopplerHz, long sample) {
			double error = signedError(desiredPhase - halfChipPhase);
			long elapsed = sample - lastCorrectionSample;
			double nominal = HALF_CHIP_RATE_HZ / rate, aided = carrierAidedCodeStep(dopplerHz);
			if (elapsed > 0L) halfChipStep += .65d * error / elapsed;
			halfChipStep += .20d * (aided - halfChipStep);
			halfChipStep = Math.max(nominal * .9998d, Math.min(nominal * 1.0002d, halfChipStep));
			/* BOC(1,1) changes sign every half-chip, so leaving even one half-chip
			 * of acquisition error can collapse the 4 ms prompt integral. */
			double correctedPhase = halfChipPhase + .90d * error;
			if (correctedPhase >= HALF_CHIPS_PER_CODE) rangeEpochAdjustment++;
			else if (correctedPhase < 0d) rangeEpochAdjustment--;
			halfChipPhase = wrap(correctedPhase);
			lastCorrectionSample = sample;
			setCarrier(center, dopplerHz, sample);
		}

		private double carrierAidedCodeStep(int dopplerHz) {
			return HALF_CHIP_RATE_HZ * (1d + dopplerHz / (double) GalileoE1SignalAnalyzer.E1_FREQUENCY_HZ) / rate;
		}

		private void setCarrier(long center, int dopplerHz, long sample) {
			double offset = GalileoE1SignalAnalyzer.E1_FREQUENCY_HZ - center + dopplerHz;
			double step = -2d * Math.PI * offset / rate;
			oscillatorStepRe = Math.cos(step); oscillatorStepIm = Math.sin(step);
			double start = step * (sample % rate);
			oscillatorRe = Math.cos(start); oscillatorIm = Math.sin(start);
		}

		void accept(byte[] iq, int samplesInBlock, long firstSample) {
			for (int n = 0; n < samplesInBlock; n++) {
				int half = Math.min(8183, Math.max(0, (int) halfChipPhase));
				double replica = code[half >>> 1] * ((half & 1) == 0 ? 1d : -1d);
				if (!epochAligned) {
					advanceOscillator(); if (advanceCode()) { epochAligned = true; clearPrompt(); }
					continue;
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
			halfChipPhase += halfChipStep;
			if (halfChipPhase < HALF_CHIPS_PER_CODE) return false;
			halfChipPhase -= HALF_CHIPS_PER_CODE; return true;
		}

		private void advanceOscillator() {
			double nextRe = oscillatorRe * oscillatorStepRe - oscillatorIm * oscillatorStepIm;
			oscillatorIm = oscillatorRe * oscillatorStepIm + oscillatorIm * oscillatorStepRe;
			oscillatorRe = nextRe;
		}

		private void finishEpoch() {
			if (!havePrompt) { phaseCorrection = Math.atan2(promptIm, promptRe); havePrompt = true; }
			else {
				double currentSqRe = promptRe * promptRe - promptIm * promptIm;
				double currentSqIm = 2d * promptRe * promptIm;
				double previousSqRe = previousPromptRe * previousPromptRe - previousPromptIm * previousPromptIm;
				double previousSqIm = 2d * previousPromptRe * previousPromptIm;
				phaseCorrection += .5d * Math.atan2(currentSqIm * previousSqRe - currentSqRe * previousSqIm,
						currentSqRe * previousSqRe + currentSqIm * previousSqIm);
			}
			double cosine = Math.cos(phaseCorrection), sine = Math.sin(phaseCorrection);
			double soft = promptRe * cosine + promptIm * sine;
			double epochCoherence = Math.hypot(promptRe, promptIm) / Math.max(1d, promptAbsolute);
			stability += .04d * (epochCoherence - stability);
			GalileoInavDecoder.Page before = decoder.latestPage();
			decoder.acceptSoft(soft); symbols++;
			if (decoder.latestPage() != before && decoder.latestPage().valid) {
				lastDecodedSymbol = symbols;
				navigation.accept(decoder.latestPage(), symbols);
				if (decoder.latestPage().wordType == 5) {
					GalileoNavigationData.Snapshot timing = navigation.snapshot(symbols);
					if (timing.weekModulo4096 >= 0 && timing.towSeconds >= 0) {
						/* I/NAV TOW identifies the leading code edge of the first
						 * symbol of its two-second vertical page. */
						timingWeek = timing.weekModulo4096; timingTow = timing.towSeconds;
						timingAnchorEpoch = pageStartEpoch(symbols, rangeEpochAdjustment);
					}
				}
			}
			previousPromptRe = promptRe; previousPromptIm = promptIm; clearPrompt();
		}

		private void clearPrompt() { promptRe = promptIm = promptAbsolute = 0d; }
		private boolean locked() { return symbols >= 10 && stability >= .08d; }
		private boolean pageFresh() { return decoder.latestPage().valid && symbols - lastDecodedSymbol <= 22_500L; }

		Tracking snapshot() {
			GalileoInavDecoder.Page page = pageFresh() ? decoder.latestPage() : GalileoInavDecoder.Page.waiting();
			GalileoNavigationData.Snapshot nav = navigation.snapshot(symbols);
			String state;
			if (page.valid) state = "I/NAV W" + page.wordType + " CRC OK; eph " + nav.ephemerisText();
			else if (decoder.latestPart().valid) state = "I/NAV page sync";
			else if (locked()) state = "Collecting symbols";
			else state = symbols == 0 ? "Code timing" : "Tracking " + symbols + " sym";
			return new Tracking(symbols, stability, locked(), state, page, nav);
		}
	}
}
