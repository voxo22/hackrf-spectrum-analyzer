package jspectrumanalyzer.iq;

import java.util.Arrays;

/** Experimental FM RDS/RBDS decoder for a selected WFM IQ channel. */
final class RdsSignalAnalyzer {
	private static final double PILOT_HZ = 19_000d;
	private static final double RDS_SUBCARRIER_HZ = 57_000d;
	private static final double RDS_BIT_RATE = 1187.5d;
	private static final int RDS_BLOCK_BITS = 26;
	private static final int RDS_INFO_BITS = 16;
	private static final int SYNDROME_MASK = 0x3ff;
	private static final int POLY = 0x5b9;
	/* Offset syndromes for A, B, C, D, C'. */
	private static final int[] OFFSET_SYNDROMES = { 383, 14, 303, 663, 748 };
	private static final double[] RDS_FREQUENCY_OFFSETS_HZ = { 0d, -25d, 25d, -50d, 50d, -100d, 100d, -200d, 200d };
	private static final double[] RDS_BIT_RATE_OFFSETS = { 0d, -0.25d, 0.25d, -0.5d, 0.5d, -1d, 1d };
	private static final int[] ERROR_BITS_BY_SYNDROME = new int[SYNDROME_MASK + 1];
	private static final int[] ERROR_INFO_MASK_BY_SYNDROME = new int[SYNDROME_MASK + 1];
	private static final int MAX_CORRECTED_BITS_PER_BLOCK = 2;

	static {
		createErrorTables();
	}

	private final char[] ps = new char[8];
	private final int[] psConfidence = new int[8];
	private final char[] radioText = new char[64];
	private final int[] radioTextConfidence = new int[64];
	private final String[] radioTextSegments = new String[16];
	private final int[] radioTextSegmentConfidence = new int[16];
	private String committedRadioText = "";
	private int committedRadioTextFlag = -1;
	private int radioTextLength = -1;
	private int radioTextFlag = -1;
	private int pi = -1;
	private int piConfidence;
	private int pendingPi = -1;
	private int pendingPiConfidence;
	private int ecc = -1;
	private final int[] eccVotes = new int[256];
	private int pty = -1;
	private final int[] ptyVotes = new int[32];
	private int musicSpeech = -1;
	private final int[] musicSpeechVotes = new int[2];
	private int stereoDi = -1;
	private final int[] stereoDiVotes = new int[2];
	private final int[] afVotes = new int[205];
	private String programmeItem = "";
	private String pendingProgrammeItem = "";
	private int pendingProgrammeItemConfidence;
	private final int[] odaAidByGroup = new int[32];
	private boolean rtPlusPresent;
	private boolean tmcPresent;
	private boolean tdcPresent;
	private boolean inHousePresent;
	private boolean unknownOdaPresent;
	private String rtPlusText = "";
	private final int[] eonPi = new int[16];
	private int eonPiCount;
	private boolean tp;
	private boolean ta;
	private String clockText = "";
	private ClockValue clockValue;
	private ClockValue pendingClockValue;
	private int pendingClockConfidence;
	private int totalGroups;
	private int validBlocks;
	private int consecutiveGroups;
	private double qualityEma;
	private int windowsSinceDecode = 1000;
	private DecodeParameters lockedParameters;

	RdsSignalAnalyzer() {
		Arrays.fill(ps, ' ');
		Arrays.fill(radioText, ' ');
	}

	Result analyze(byte[] iq, int length, int sampleRateHz) {
		int inputSamples = Math.min(length, iq == null ? 0 : iq.length) / 2;
		if (sampleRateHz < 150_000 || inputSamples < sampleRateHz / 5)
			return new Result(false, "Need WFM IQ at >=150 kS/s and at least 200 ms", 0d, 0d, 0, 0, 0d,
					pi, piDescription(pi), countryName(pi, ecc), coverageAreaName(pi),
					pty, ptyName(pty), audioMode(0d), programmeMode(), tp, ta,
					text(ps), text(radioText), clockText, alternativeFrequencies(), programmeItem,
					rtPlusStatus(), tmcStatus(), eonStatus(), tdcStatus(), inHouseStatus(), odaStatus(),
					validBlocks, totalGroups, 0, 0);
		double[] fm = demodFm(iq, inputSamples);
		double pilot = toneSnr(fm, sampleRateHz, PILOT_HZ);
		double rds = rdsBandSnr(fm, sampleRateHz);
		DecodeCandidate best = decodeBits(fm, sampleRateHz);
		if (best != null && best.groups > 0) {
			applyGroups(best);
			lockedParameters = best.parameters;
			validBlocks += best.validBlocks;
			totalGroups += best.groups;
			consecutiveGroups = Math.min(24, consecutiveGroups + best.groups);
		} else if (consecutiveGroups > 0) {
			consecutiveGroups--;
		}
		boolean locked = consecutiveGroups > 0;
		int windowBlocks = best == null ? 0 : best.validBlocks;
		int windowGroups = best == null ? 0 : best.groups;
		int windowCorrectedBits = best == null ? 0 : best.correctedBits;
		int possibleBlocks = expectedBlockCount(fm.length, sampleRateHz);
		double quality = updateQuality(rds, windowBlocks, windowGroups, windowCorrectedBits, possibleBlocks, locked);
		String state = locked ? "RDS group sync"
				: best != null && best.validBlocks > 0 ? "RDS block sync candidate"
				: rds >= 6d ? "RDS subcarrier detected, waiting for block sync" : "Searching RDS";
		return new Result(locked, state, pilot, rds, windowBlocks, windowGroups, quality,
				pi, piDescription(pi), countryName(pi, ecc), coverageAreaName(pi),
				pty, ptyName(pty), audioMode(pilot), programmeMode(), tp, ta,
				text(ps), radioText(), clockText, alternativeFrequencies(), programmeItem,
				rtPlusStatus(), tmcStatus(), eonStatus(), tdcStatus(), inHouseStatus(), odaStatus(),
				validBlocks, totalGroups, windowCorrectedBits, possibleBlocks);
	}

	private int expectedBlockCount(int demodSamples, int sampleRateHz) {
		if (sampleRateHz <= 0 || demodSamples <= 0) return 0;
		double bits = demodSamples * RDS_BIT_RATE / sampleRateHz - 1d;
		return Math.max(0, (int) Math.floor(bits / RDS_BLOCK_BITS));
	}

	private double updateQuality(double rdsSnrDb, int windowBlocks, int windowGroups,
			int correctedBits, int possibleBlocks, boolean locked) {
		if (windowBlocks > 0 || windowGroups > 0) {
			windowsSinceDecode = 0;
		} else if (windowsSinceDecode < 1000) {
			windowsSinceDecode++;
		}
		boolean sessionHasData = totalGroups > 0 || pi >= 0 || text(ps).length() > 0 || radioText().length() > 0;
		double instant = scoreBetween(rdsSnrDb, 1.5d, 12d) * 35d;
		if (windowBlocks > 0 && possibleBlocks > 0) {
			double blockRate = clamp01(windowBlocks / (double) possibleBlocks);
			double groupRate = possibleBlocks >= 4 ? clamp01(windowGroups / (possibleBlocks / 4d)) : 0d;
			double correctedPerBlock = correctedBits / Math.max(1d, windowBlocks);
			double blockScore = scoreBetween(blockRate, 0.12d, 0.88d);
			double groupScore = scoreBetween(groupRate, 0.02d, 0.18d);
			double correctionScore = clamp01(1d - correctedPerBlock / 1.2d);
			double carrierScore = scoreBetween(rdsSnrDb, 1.5d, 12d);
			instant = 100d * (blockScore * 0.42d + correctionScore * 0.23d
					+ carrierScore * 0.25d + groupScore * 0.10d);
			if (windowGroups == 0) instant = Math.min(instant, 58d);
		} else if (sessionHasData) {
			instant = windowsSinceDecode <= 12 ? 44d : windowsSinceDecode <= 80 ? 32d : instant;
		} else if (locked) {
			instant = Math.max(28d, instant);
		}
		double attack = instant >= qualityEma ? 0.35d : 0.22d;
		qualityEma = qualityEma <= 0d ? instant : qualityEma * (1d - attack) + instant * attack;
		return Math.max(0d, Math.min(100d, qualityEma));
	}

	private static double scoreBetween(double value, double low, double high) {
		return clamp01((value - low) / (high - low));
	}

	private static double clamp01(double value) {
		return Math.max(0d, Math.min(1d, value));
	}

	private double[] demodFm(byte[] iq, int samples) {
		double[] out = new double[Math.max(0, samples - 1)];
		int prevI = iq[0], prevQ = iq[1];
		double dc = 0d;
		for (int n = 1; n < samples; n++) {
			int i = iq[2 * n], q = iq[2 * n + 1];
			double cross = prevI * q - prevQ * i;
			double dot = prevI * i + prevQ * q;
			double value = Math.atan2(cross, dot);
			dc += (value - dc) * 0.0005d;
			out[n - 1] = value - dc;
			prevI = i;
			prevQ = q;
		}
		return out;
	}

	private double toneSnr(double[] samples, int sampleRateHz, double frequencyHz) {
		double coherent = coherentTonePower(samples, sampleRateHz, frequencyHz);
		double power = averagePower(samples);
		return Math.max(0d, 10d * Math.log10(1d + coherent / Math.max(1e-12d, power)));
	}

	private double rdsBandSnr(double[] samples, int sampleRateHz) {
		double signal = 0d;
		for (double offset : new double[] { -1_600d, -1_200d, -800d, 800d, 1_200d, 1_600d })
			signal += coherentTonePower(samples, sampleRateHz, RDS_SUBCARRIER_HZ + offset);
		signal /= 6d;
		double noise = 0d;
		for (double frequency : new double[] { 51_000d, 53_000d, 61_000d, 63_000d })
			noise += coherentTonePower(samples, sampleRateHz, frequency);
		noise /= 4d;
		return Math.max(0d, 10d * Math.log10((signal + 1e-12d) / (noise + 1e-12d)));
	}

	private double averagePower(double[] samples) {
		if (samples.length == 0) return 0d;
		int step = Math.max(1, samples.length / 200_000);
		double power = 0d;
		int used = 0;
		for (int i = 0; i < samples.length; i += step) {
			power += samples[i] * samples[i];
			used++;
		}
		return power / Math.max(1d, used);
	}

	private double coherentTonePower(double[] samples, int sampleRateHz, double frequencyHz) {
		if (samples.length < 16) return 0d;
		double wr = Math.cos(2d * Math.PI * frequencyHz / sampleRateHz);
		double wi = -Math.sin(2d * Math.PI * frequencyHz / sampleRateHz);
		double zr = 1d, zi = 0d, re = 0d, im = 0d;
		int step = Math.max(1, samples.length / 200_000);
		int used = 0;
		for (int i = 0; i < samples.length; i += step) {
			double v = samples[i];
			re += v * zr;
			im += v * zi;
			double nr = zr * wr - zi * wi;
			zi = zr * wi + zi * wr;
			zr = nr;
			used++;
		}
		return (re * re + im * im) / Math.max(1d, used);
	}

	private DecodeCandidate decodeBits(double[] fm, int sampleRateHz) {
		DecodeCandidate best = null;
		int[] phaseSteps = {
				0, 8, 16, 24, 4, 12, 20, 28, 2, 6, 10, 14, 18, 22, 26, 30,
				1, 3, 5, 7, 9, 11, 13, 15, 17, 19, 21, 23, 25, 27, 29, 31
		};
		if (lockedParameters != null) {
			MixData mixed = mixSubcarrier(fm, sampleRateHz, RDS_SUBCARRIER_HZ + lockedParameters.frequencyOffsetHz);
			double bitRate = RDS_BIT_RATE + lockedParameters.bitRateOffset;
			for (int phase : phaseSteps) {
				double phaseSymbols = phase / 32d;
				int[] bits = demodulateRdsBits(mixed, sampleRateHz, bitRate, phaseSymbols);
				for (int invert = 0; invert < 2; invert++) {
					if (invert != 0) invertBits(bits);
					DecodeParameters parameters = new DecodeParameters(lockedParameters.frequencyOffsetHz,
							lockedParameters.bitRateOffset, phaseSymbols, invert != 0);
					DecodeCandidate candidate = decodeBlocks(bits, parameters);
					if (best == null || candidate.betterThan(best)) best = candidate;
					if (invert != 0) invertBits(bits);
				}
			}
			if (best != null && best.groups > 0) return best;
		}
		for (double offsetHz : RDS_FREQUENCY_OFFSETS_HZ) {
			MixData mixed = mixSubcarrier(fm, sampleRateHz, RDS_SUBCARRIER_HZ + offsetHz);
			for (double bitRateOffset : RDS_BIT_RATE_OFFSETS) {
				double bitRate = RDS_BIT_RATE + bitRateOffset;
				for (int phase : phaseSteps) {
					double phaseSymbols = phase / 32d;
					int[] bits = demodulateRdsBits(mixed, sampleRateHz, bitRate, phaseSymbols);
					if (bits.length < RDS_BLOCK_BITS * 4) continue;
					for (int invert = 0; invert < 2; invert++) {
						if (invert != 0) invertBits(bits);
						DecodeParameters parameters = new DecodeParameters(offsetHz, bitRateOffset, phaseSymbols,
								invert != 0);
						DecodeCandidate candidate = decodeBlocks(bits, parameters);
						if (best == null || candidate.betterThan(best)) best = candidate;
						if (candidate.groups >= 2 && candidate.correctedBits <= 4) return candidate;
						if (invert != 0) invertBits(bits);
					}
				}
			}
		}
		return best;
	}

	private MixData mixSubcarrier(double[] fm, int sampleRateHz, double frequencyHz) {
		double[] sumRe = new double[fm.length + 1];
		double[] sumIm = new double[fm.length + 1];
		double wr = Math.cos(2d * Math.PI * frequencyHz / sampleRateHz);
		double wi = -Math.sin(2d * Math.PI * frequencyHz / sampleRateHz);
		double zr = 1d, zi = 0d;
		for (int i = 0; i < fm.length; i++) {
			sumRe[i + 1] = sumRe[i] + fm[i] * zr;
			sumIm[i + 1] = sumIm[i] + fm[i] * zi;
			double nr = zr * wr - zi * wi;
			zi = zr * wi + zi * wr;
			zr = nr;
		}
		return new MixData(sumRe, sumIm);
	}

	private int[] demodulateRdsBits(MixData mixed, int sampleRateHz, double bitRate, double phaseSymbols) {
		double samplesPerBit = sampleRateHz / bitRate;
		int count = Math.max(0, (int) ((mixed.samples() - phaseSymbols * samplesPerBit) / samplesPerBit) - 1);
		double[] softRe = new double[count];
		double[] softIm = new double[count];
		for (int bit = 0; bit < count; bit++) {
			double start = (bit + phaseSymbols) * samplesPerBit;
			Complex first = mixed.integrate(start, samplesPerBit * 0.5d);
			Complex second = mixed.integrate(start + samplesPerBit * 0.5d, samplesPerBit * 0.5d);
			softRe[bit] = first.re - second.re;
			softIm[bit] = first.im - second.im;
		}
		int[] bits = new int[Math.max(0, count - 1)];
		for (int i = 1; i < count; i++) {
			double differential = softRe[i] * softRe[i - 1] + softIm[i] * softIm[i - 1];
			bits[i - 1] = differential < 0d ? 1 : 0;
		}
		return bits;
	}

	private DecodeCandidate decodeBlocks(int[] bits, DecodeParameters parameters) {
		DecodeCandidate best = new DecodeCandidate();
		best.parameters = parameters;
		for (int offset = 0; offset < RDS_BLOCK_BITS; offset++) {
			DecodeCandidate current = new DecodeCandidate();
			current.parameters = parameters;
			int expected = 0;
			int[] values = new int[4];
			boolean cPrime = false;
			int valueCount = 0;
			int groupErrors = 0;
			for (int pos = offset; pos + RDS_BLOCK_BITS <= bits.length; pos += RDS_BLOCK_BITS) {
				Block block = decodeBlock(bits, pos);
				if (block == null) {
					expected = 0;
					valueCount = 0;
					cPrime = false;
					groupErrors = 0;
					continue;
				}
				current.validBlocks++;
				current.correctedBits += block.correctedBits;
				if (block.type == expected) {
					values[valueCount++] = block.info;
					groupErrors += block.correctedBits;
					if (block.type == 2) cPrime = block.typeCPrime;
					expected++;
					if (expected == 4) {
						current.addGroup(values, cPrime, groupErrors);
						expected = 0;
						valueCount = 0;
						cPrime = false;
						groupErrors = 0;
					}
				} else {
					expected = block.type == 0 ? 1 : 0;
					valueCount = block.type == 0 ? 1 : 0;
					cPrime = false;
					groupErrors = block.type == 0 ? block.correctedBits : 0;
					if (valueCount == 1) values[0] = block.info;
				}
			}
			if (current.betterThan(best)) best = current;
		}
		return best;
	}

	private void applyGroups(DecodeCandidate candidate) {
		for (int i = 0; i < Math.min(candidate.groups, candidate.groupValues.length); i++)
			parseGroup(candidate.groupValues[i], candidate.groupCPrime[i], candidate.groupErrors[i]);
	}

	private Block decodeBlock(int[] bits, int offset) {
		int syndrome = syndrome(bits, offset);
		Block best = null;
		for (int i = 0; i < OFFSET_SYNDROMES.length; i++) {
			int residual = (syndrome ^ OFFSET_SYNDROMES[i]) & SYNDROME_MASK;
			int correctedBits = ERROR_BITS_BY_SYNDROME[residual];
			if (correctedBits <= MAX_CORRECTED_BITS_PER_BLOCK
					&& (best == null || correctedBits < best.correctedBits)) {
				best = blockFromBits(bits, offset, i, ERROR_INFO_MASK_BY_SYNDROME[residual], correctedBits);
				if (correctedBits == 0) return best;
			}
		}
		return best;
	}

	private static Block blockFromBits(int[] bits, int offset, int offsetIndex, int infoMask, int correctedBits) {
		int info = 0;
		for (int b = 0; b < RDS_INFO_BITS; b++) info = (info << 1) | bits[offset + b];
		info ^= infoMask;
		return new Block(offsetIndex == 4 ? 2 : offsetIndex, offsetIndex == 4, info, correctedBits);
	}

	private static int syndrome(int[] bits, int offset) {
		int reg = 0;
		for (int i = 0; i < RDS_BLOCK_BITS; i++) {
			reg = ((reg << 1) | (bits[offset + i] & 1));
			if ((reg & 0x400) != 0) reg ^= POLY;
		}
		for (int i = 0; i < 10; i++) {
			reg <<= 1;
			if ((reg & 0x400) != 0) reg ^= POLY;
		}
		return reg & SYNDROME_MASK;
	}

	private void parseGroup(int[] block, boolean cPrime, int correctedBits) {
		int groupPi = block[0] & 0xffff;
		if (!acceptPi(groupPi)) return;
		int b = block[1] & 0xffff;
		int groupType = (b >> 12) & 0xf;
		boolean versionB = ((b >> 11) & 1) != 0;
		tp = ((b >> 10) & 1) != 0;
		votePty((b >> 5) & 0x1f);
		if (groupType == 0) {
			if (correctedBits > 3) return;
			ta = ((b >> 4) & 1) != 0;
			voteBinary(musicSpeechVotes, (b >> 3) & 1);
			musicSpeech = bestBinary(musicSpeechVotes, musicSpeech);
			int segment = b & 3;
			if (segment == 3) {
				voteBinary(stereoDiVotes, (b >> 2) & 1);
				stereoDi = bestBinary(stereoDiVotes, stereoDi);
			}
			if (!versionB && !cPrime) addAlternativeFrequencies(block[2] & 0xffff, correctedBits);
			int index = (b & 3) * 2;
			voteChar(ps, psConfidence, index, block[3] >> 8);
			voteChar(ps, psConfidence, index + 1, block[3]);
		} else if (groupType == 1) {
			if (correctedBits <= 2) voteProgrammeItem(decodeProgrammeItem(block[3] & 0xffff));
			if (!versionB && !cPrime && correctedBits <= 2) {
				int c = block[2] & 0xffff;
				int variant = (c >> 12) & 0x7;
				if (variant == 0) voteEcc(c & 0xff);
			}
		} else if (groupType == 2 && !versionB && !cPrime) {
			if (correctedBits > 2) return;
			updateRadioTextFlag((b >> 4) & 1);
			int index = (b & 0xf) * 4;
			voteRadioTextSegment(index, new int[] { block[2] >> 8, block[2], block[3] >> 8, block[3] });
		} else if (groupType == 2 && versionB) {
			if (correctedBits > 2) return;
			updateRadioTextFlag((b >> 4) & 1);
			int index = (b & 0xf) * 2;
			voteRadioTextSegment(index, new int[] { block[3] >> 8, block[3] });
		} else if (groupType == 4 && !versionB && !cPrime) {
			voteClockTime(decodeClockTime(b, block[2] & 0xffff, block[3] & 0xffff), correctedBits);
		} else if (groupType == 3 && !versionB && !cPrime) {
			if (correctedBits <= 2) parseOda(block[1] & 0xffff, block[3] & 0xffff);
		} else if (groupType == 5) {
			tdcPresent = true;
		} else if (groupType == 6) {
			inHousePresent = true;
		} else if (groupType == 14) {
			addEon(block[3] & 0xffff);
		}
		parseMappedOda(groupType, versionB, block, correctedBits);
	}

	private static void voteBinary(int[] votes, int value) {
		if (value < 0 || value >= votes.length) return;
		for (int i = 0; i < votes.length; i++) if (votes[i] > 0) votes[i]--;
		votes[value] += 4;
	}

	private static int bestBinary(int[] votes, int current) {
		int best = current;
		int bestVotes = current >= 0 && current < votes.length ? votes[current] : -1;
		for (int i = 0; i < votes.length; i++) {
			if (votes[i] > bestVotes) {
				bestVotes = votes[i];
				best = i;
			}
		}
		return best;
	}

	private boolean acceptPi(int value) {
		if (pi < 0) {
			if (pendingPi == value) {
				pendingPiConfidence++;
			} else {
				pendingPi = value;
				pendingPiConfidence = 1;
			}
			if (pendingPiConfidence >= 2) {
				pi = pendingPi;
				piConfidence = 4;
				pendingPi = -1;
				pendingPiConfidence = 0;
				return true;
			}
			return false;
		}
		if (pi == value) {
			piConfidence = Math.min(40, piConfidence + 1);
			pendingPi = -1;
			pendingPiConfidence = 0;
			return true;
		}
		piConfidence = Math.max(0, piConfidence - 1);
		if (pendingPi == value) pendingPiConfidence++;
		else {
			pendingPi = value;
			pendingPiConfidence = 1;
		}
		if (pendingPiConfidence >= 8 && piConfidence == 0) {
			pi = value;
			piConfidence = 4;
			pendingPi = -1;
			pendingPiConfidence = 0;
			clearTextBuffers();
			return true;
		}
		return false;
	}

	private void votePty(int value) {
		if (value < 0 || value >= ptyVotes.length) return;
		for (int i = 0; i < ptyVotes.length; i++) if (ptyVotes[i] > 0) ptyVotes[i]--;
		ptyVotes[value] += 3;
		int best = pty;
		int bestVotes = pty >= 0 ? ptyVotes[pty] : -1;
		for (int i = 0; i < ptyVotes.length; i++) {
			if (ptyVotes[i] > bestVotes) {
				bestVotes = ptyVotes[i];
				best = i;
			}
		}
		pty = best;
	}

	private void voteClockTime(ClockValue value, int correctedBits) {
		if (value == null) return;
		if (clockValue != null && value.compatibleWith(clockValue, 2)) {
			clockValue = value;
			clockText = value.text;
			pendingClockValue = null;
			pendingClockConfidence = 0;
			return;
		}
		if (correctedBits > 0) return;
		if (pendingClockValue != null && value.compatibleWith(pendingClockValue, 1)) {
			pendingClockConfidence++;
		} else {
			pendingClockValue = value;
			pendingClockConfidence = 1;
		}
		if (clockValue == null && pendingClockConfidence >= 2) {
			clockValue = pendingClockValue;
			clockText = clockValue.text;
			pendingClockValue = null;
			pendingClockConfidence = 0;
		} else if (clockValue != null && pendingClockConfidence >= 4) {
			clockValue = pendingClockValue;
			clockText = clockValue.text;
			pendingClockValue = null;
			pendingClockConfidence = 0;
		}
	}

	private void voteEcc(int value) {
		if (value < 0 || value >= eccVotes.length) return;
		if (pi < 0 || !knownCountry((pi >> 12) & 0xf, value)) return;
		for (int i = 0; i < eccVotes.length; i++) if (eccVotes[i] > 0) eccVotes[i]--;
		eccVotes[value] += 5;
		int best = ecc;
		int bestVotes = ecc >= 0 ? eccVotes[ecc] : -1;
		for (int i = 0; i < eccVotes.length; i++) {
			if (eccVotes[i] > bestVotes) {
				bestVotes = eccVotes[i];
				best = i;
			}
		}
		ecc = best;
	}

	private void addAlternativeFrequencies(int value, int correctedBits) {
		if (correctedBits > 0) return;
		addAfCode((value >> 8) & 0xff);
		addAfCode(value & 0xff);
	}

	private void addAfCode(int code) {
		if (code >= 1 && code < afVotes.length) afVotes[code] = Math.min(12, afVotes[code] + 1);
	}

	private void voteProgrammeItem(String value) {
		if (value == null || value.length() == 0) return;
		if (value.equals(programmeItem)) {
			pendingProgrammeItem = "";
			pendingProgrammeItemConfidence = 0;
			return;
		}
		if (value.equals(pendingProgrammeItem)) {
			pendingProgrammeItemConfidence++;
		} else {
			pendingProgrammeItem = value;
			pendingProgrammeItemConfidence = 1;
		}
		if ((programmeItem.length() == 0 && pendingProgrammeItemConfidence >= 2)
				|| (programmeItem.length() > 0 && pendingProgrammeItemConfidence >= 4)) {
			programmeItem = pendingProgrammeItem;
			pendingProgrammeItem = "";
			pendingProgrammeItemConfidence = 0;
		}
	}

	private static String decodeProgrammeItem(int value) {
		int day = (value >> 11) & 0x1f;
		int hour = (value >> 6) & 0x1f;
		int minute = value & 0x3f;
		if (day < 1 || day > 31 || hour > 23 || minute > 59) return "";
		return String.format(java.util.Locale.US, "day %d, %02d:%02d", day, hour, minute);
	}

	private void parseOda(int b, int aid) {
		int groupCode = b & 0x1f;
		if (groupCode < 0 || groupCode >= odaAidByGroup.length) return;
		odaAidByGroup[groupCode] = aid & 0xffff;
		if (aid == 0x4bd7) rtPlusPresent = true;
		else if (aid == 0xcd46) tmcPresent = true;
		else unknownOdaPresent = true;
	}

	private void parseMappedOda(int groupType, boolean versionB, int[] block, int correctedBits) {
		if (correctedBits > 2) return;
		int groupCode = (groupType << 1) | (versionB ? 1 : 0);
		if (groupCode < 0 || groupCode >= odaAidByGroup.length) return;
		int aid = odaAidByGroup[groupCode];
		if (aid == 0x4bd7) {
			rtPlusPresent = true;
			decodeRtPlus(block[1] & 0xffff, block[2] & 0xffff, block[3] & 0xffff);
		} else if (aid == 0xcd46) {
			tmcPresent = true;
		} else if (aid != 0) {
			unknownOdaPresent = true;
		}
	}

	private void decodeRtPlus(int b, int c, int d) {
		long data = ((long)(b & 0x1f) << 32) | ((long)c << 16) | d;
		boolean running = ((data >> 35) & 1L) != 0;
		int type1 = (int)((data >> 29) & 0x3f);
		int start1 = (int)((data >> 23) & 0x3f);
		int length1 = (int)((data >> 17) & 0x3f) + 1;
		int type2 = (int)((data >> 11) & 0x3f);
		int start2 = (int)((data >> 5) & 0x3f);
		int length2 = (int)(data & 0x1f) + 1;
		String rt = radioText();
		StringBuilder out = new StringBuilder(96);
		if (!running) out.append("Not running");
		appendRtPlusTag(out, rt, type1, start1, length1);
		appendRtPlusTag(out, rt, type2, start2, length2);
		if (out.length() > 0) rtPlusText = out.toString();
	}

	private void appendRtPlusTag(StringBuilder out, String rt, int type, int start, int length) {
		String name = rtPlusTypeName(type);
		if (name.length() == 0) return;
		String value = "";
		if (rt != null && start >= 0 && start < rt.length()) {
			int end = Math.min(rt.length(), start + Math.max(1, length));
			value = rt.substring(start, end).trim();
		}
		if (out.length() > 0) out.append("; ");
		out.append(name);
		if (value.length() > 0) out.append(": ").append(value);
	}

	private void addEon(int otherPi) {
		if (otherPi <= 0 || otherPi == pi) {
			eonPiCount = Math.max(eonPiCount, 1);
			return;
		}
		for (int i = 0; i < eonPiCount && i < eonPi.length; i++)
			if (eonPi[i] == otherPi) return;
		if (eonPiCount < eonPi.length) eonPi[eonPiCount] = otherPi;
		eonPiCount++;
	}

	private void clearTextBuffers() {
		Arrays.fill(ps, ' ');
		Arrays.fill(psConfidence, 0);
		Arrays.fill(radioText, ' ');
		Arrays.fill(radioTextConfidence, 0);
		Arrays.fill(radioTextSegments, null);
		Arrays.fill(radioTextSegmentConfidence, 0);
		committedRadioText = "";
		committedRadioTextFlag = -1;
		radioTextLength = -1;
		radioTextFlag = -1;
		Arrays.fill(ptyVotes, 0);
		pty = -1;
		Arrays.fill(eccVotes, 0);
		ecc = -1;
		musicSpeech = -1;
		stereoDi = -1;
		Arrays.fill(musicSpeechVotes, 0);
		Arrays.fill(stereoDiVotes, 0);
		Arrays.fill(afVotes, 0);
		programmeItem = "";
		pendingProgrammeItem = "";
		pendingProgrammeItemConfidence = 0;
		Arrays.fill(odaAidByGroup, 0);
		rtPlusPresent = false;
		tmcPresent = false;
		tdcPresent = false;
		inHousePresent = false;
		unknownOdaPresent = false;
		rtPlusText = "";
		Arrays.fill(eonPi, 0);
		eonPiCount = 0;
		clockText = "";
		clockValue = null;
		pendingClockValue = null;
		pendingClockConfidence = 0;
	}

	private void updateRadioTextFlag(int flag) {
		if (radioTextFlag >= 0 && radioTextFlag != flag) {
			Arrays.fill(radioText, ' ');
			Arrays.fill(radioTextConfidence, 0);
			Arrays.fill(radioTextSegments, null);
			Arrays.fill(radioTextSegmentConfidence, 0);
			radioTextLength = -1;
		}
		radioTextFlag = flag;
	}

	private static ClockValue decodeClockTime(int b, int c, int d) {
		int mjd = ((b & 0x3) << 15) | ((c >> 1) & 0x7fff);
		int hour = ((c & 0x1) << 4) | ((d >> 12) & 0xf);
		int minute = (d >> 6) & 0x3f;
		int offsetHalfHours = d & 0x1f;
		if ((d & 0x20) != 0) offsetHalfHours = -offsetHalfHours;
		if (mjd <= 0 || hour > 23 || minute > 59 || Math.abs(offsetHalfHours) > 28) return null;
		int[] ymd = mjdToYmd(mjd);
		if (ymd[0] < 2000 || ymd[0] > 2099) return null;
		int offsetMinutes = offsetHalfHours * 30;
		char sign = offsetMinutes < 0 ? '-' : '+';
		int absoluteOffset = Math.abs(offsetMinutes);
		String text = String.format(java.util.Locale.US, "%04d-%02d-%02d %02d:%02d UTC%c%02d:%02d",
				ymd[0], ymd[1], ymd[2], hour, minute, sign, absoluteOffset / 60, absoluteOffset % 60);
		return new ClockValue(mjd * 1440L + hour * 60L + minute, offsetMinutes, text);
	}

	private static int[] mjdToYmd(int mjd) {
		long j = mjd + 2_400_001L;
		long l = j + 68_569L;
		long n = (4L * l) / 146_097L;
		l -= (146_097L * n + 3L) / 4L;
		long i = (4000L * (l + 1L)) / 1_461_001L;
		l = l - (1461L * i) / 4L + 31L;
		long month = (80L * l) / 2447L;
		long day = l - (2447L * month) / 80L;
		l = month / 11L;
		month = month + 2L - 12L * l;
		long year = 100L * (n - 49L) + i + l;
		return new int[] { (int) year, (int) month, (int) day };
	}

	private static String piDescription(int pi) {
		if (pi < 0) return "--";
		int area = (pi >> 8) & 0xf;
		return coverageArea(area);
	}

	private static String coverageAreaName(int pi) {
		if (pi < 0) return "--";
		return coverageArea((pi >> 8) & 0xf);
	}

	private static String coverageArea(int area) {
		switch (area) {
		case 0: return "Local";
		case 1: return "International";
		case 2: return "National";
		case 3: return "Supra-regional";
		default: return "Regional " + (area - 3);
		}
	}

	private static String countryName(int pi, int ecc) {
		if (pi < 0) return "--";
		int ci = (pi >> 12) & 0xf;
		if (ecc >= 0) {
			for (CountryCode country : COUNTRY_CODES) {
				if (country.ecc == ecc && country.ci == ci) return country.name;
			}
			return "Country extension received, not in table";
		}
		return "Waiting for country extension";
	}

	private static boolean knownCountry(int ci, int ecc) {
		for (CountryCode country : COUNTRY_CODES) {
			if (country.ecc == ecc && country.ci == ci) return true;
		}
		return false;
	}

	private String audioMode(double pilotSnrDb) {
		boolean pilotStereo = pilotSnrDb >= 3d;
		if (pilotStereo) return "Stereo";
		if (stereoDi >= 0) return stereoDi != 0 ? "Stereo" : "Mono";
		return "Mono / no pilot";
	}

	private String programmeMode() {
		if (musicSpeech < 0) return "--";
		return musicSpeech != 0 ? "Music" : "Speech";
	}

	private String alternativeFrequencies() {
		StringBuilder out = new StringBuilder();
		for (int code = 1; code < afVotes.length; code++) {
			if (afVotes[code] < 2) continue;
			if (out.length() > 0) out.append(", ");
			out.append(String.format(java.util.Locale.US, "%.1f", 87.5d + code / 10d));
		}
		if (out.length() == 0) return "--";
		out.append(" MHz");
		return out.toString();
	}

	private String rtPlusStatus() {
		if (!rtPlusPresent) return "--";
		return rtPlusText.length() == 0 ? "Present" : rtPlusText;
	}

	private String tmcStatus() {
		return tmcPresent ? "Present" : "--";
	}

	private String eonStatus() {
		if (eonPiCount <= 0) return "--";
		return eonPiCount == 1 ? "Present" : "Present, " + eonPiCount + " services";
	}

	private String tdcStatus() {
		return tdcPresent ? "Present" : "--";
	}

	private String inHouseStatus() {
		return inHousePresent ? "Present" : "--";
	}

	private String odaStatus() {
		if (!unknownOdaPresent) return "--";
		return "Other ODA present";
	}

	private static String ptyName(int pty) {
		if (pty < 0 || pty >= PTY_NAMES.length) return "--";
		return PTY_NAMES[pty];
	}

	private static String rtPlusTypeName(int type) {
		switch (type) {
		case 1: return "Title";
		case 2: return "Album";
		case 3: return "Track";
		case 4: return "Artist";
		case 5: return "Composition";
		case 6: return "Movement";
		case 7: return "Conductor";
		case 8: return "Composer";
		case 9: return "Band";
		case 10: return "Comment";
		case 11: return "Genre";
		case 12: return "News";
		case 13: return "Local news";
		case 14: return "Stock market";
		case 15: return "Sport";
		case 16: return "Lottery";
		case 17: return "Horoscope";
		case 18: return "Daily diversion";
		case 19: return "Health";
		case 20: return "Event";
		case 21: return "Scene";
		case 22: return "Cinema";
		case 23: return "TV";
		case 24: return "Date/time";
		case 25: return "Weather";
		case 26: return "Traffic";
		case 27: return "Alarm";
		case 28: return "Advert";
		case 29: return "URL";
		case 30: return "Other";
		case 31: return "Short station name";
		case 32: return "Long station name";
		default: return "";
		}
	}

	private static void writeChar(char[] out, int index, int value) {
		if (index < 0 || index >= out.length) return;
		int c = value & 0xff;
		out[index] = c >= 32 && c < 127 ? (char) c : ' ';
	}

	private static void voteChar(char[] out, int[] confidence, int index, int value) {
		if (index < 0 || index >= out.length) return;
		int c = value & 0xff;
		if (c < 32 || c >= 127) c = 32;
		char next = (char)c;
		if (confidence[index] == 0 || out[index] == next) {
			out[index] = next;
			confidence[index] = Math.min(12, confidence[index] + 2);
		} else {
			confidence[index]--;
			if (confidence[index] <= 0) {
				out[index] = next;
				confidence[index] = 1;
			}
		}
	}

	private void voteRadioTextSegment(int charIndex, int[] values) {
		if (charIndex < 0 || charIndex >= radioText.length || values == null || values.length == 0) return;
		int segment = charIndex / 4;
		if (segment < 0 || segment >= radioTextSegments.length) return;
		StringBuilder text = new StringBuilder(values.length);
		int end = -1;
		for (int i = 0; i < values.length; i++) {
			int c = values[i] & 0xff;
			if (c == 0x0d) {
				end = charIndex + i;
				text.append('\r');
			} else {
				if (c < 32 || c >= 127) c = 32;
				text.append((char)c);
			}
		}
		String value = text.toString();
		if (value.equals(radioTextSegments[segment])) {
			radioTextSegmentConfidence[segment] = Math.min(12, radioTextSegmentConfidence[segment] + 2);
		} else {
			radioTextSegmentConfidence[segment]--;
			if (radioTextSegmentConfidence[segment] <= 0) {
				radioTextSegments[segment] = value;
				radioTextSegmentConfidence[segment] = 1;
			}
		}
		if (end >= 0 && radioTextSegmentConfidence[segment] >= 2)
			radioTextLength = radioTextLength < 0 ? end : Math.min(radioTextLength, end);
	}

	private static void invertBits(int[] bits) {
		for (int i = 0; i < bits.length; i++) bits[i] ^= 1;
	}

	private static String text(char[] chars) {
		return new String(chars).trim();
	}

	private String radioText() {
		RadioTextCandidate candidate = radioTextCandidate();
		if (candidate.text.length() == 0) return committedRadioText;
		boolean confirmedReplacement = candidate.complete && candidate.minConfidence >= 4;
		if (committedRadioTextFlag != radioTextFlag) confirmedReplacement = candidate.minConfidence >= 2;
		if (committedRadioText.startsWith(candidate.text) && candidate.text.length() < committedRadioText.length()
				&& !confirmedReplacement)
			return committedRadioText;
		if (committedRadioText.length() == 0 || candidate.text.length() >= committedRadioText.length()
				|| confirmedReplacement) {
			committedRadioText = candidate.text;
			committedRadioTextFlag = radioTextFlag;
		}
		return committedRadioText;
	}

	private RadioTextCandidate radioTextCandidate() {
		StringBuilder out = new StringBuilder(64);
		int minConfidence = Integer.MAX_VALUE;
		for (int segment = 0; segment < radioTextSegments.length; segment++) {
			String value = radioTextSegments[segment];
			if (value == null || radioTextSegmentConfidence[segment] < 1) break;
			minConfidence = Math.min(minConfidence, radioTextSegmentConfidence[segment]);
			for (int i = 0; i < value.length(); i++) {
				char c = value.charAt(i);
				if (c == '\r') return new RadioTextCandidate(out.toString().trim(), true, minConfidence);
				int absoluteIndex = segment * 4 + i;
				if (radioTextLength >= 0 && absoluteIndex >= radioTextLength)
					return new RadioTextCandidate(out.toString().trim(), true, minConfidence);
				out.append(c);
			}
		}
		return new RadioTextCandidate(out.toString().trim(), false,
				minConfidence == Integer.MAX_VALUE ? 0 : minConfidence);
	}

	private static void createErrorTables() {
		Arrays.fill(ERROR_BITS_BY_SYNDROME, 99);
		ERROR_BITS_BY_SYNDROME[0] = 0;
		ERROR_INFO_MASK_BY_SYNDROME[0] = 0;
		for (int a = 0; a < RDS_BLOCK_BITS; a++) {
			int syndrome = errorSyndrome(a, -1);
			recordErrorPattern(syndrome, infoMask(a, -1), 1);
			for (int b = a + 1; b < RDS_BLOCK_BITS; b++) {
				syndrome = errorSyndrome(a, b);
				recordErrorPattern(syndrome, infoMask(a, b), 2);
			}
		}
	}

	private static int errorSyndrome(int firstBit, int secondBit) {
		int[] bits = new int[RDS_BLOCK_BITS];
		bits[firstBit] = 1;
		if (secondBit >= 0) bits[secondBit] = 1;
		return syndrome(bits, 0);
	}

	private static int infoMask(int firstBit, int secondBit) {
		int mask = infoMask(firstBit);
		if (secondBit >= 0) mask ^= infoMask(secondBit);
		return mask;
	}

	private static int infoMask(int bit) {
		return bit >= 0 && bit < RDS_INFO_BITS ? 1 << (RDS_INFO_BITS - 1 - bit) : 0;
	}

	private static void recordErrorPattern(int syndrome, int infoMask, int bits) {
		if (bits < ERROR_BITS_BY_SYNDROME[syndrome]) {
			ERROR_BITS_BY_SYNDROME[syndrome] = bits;
			ERROR_INFO_MASK_BY_SYNDROME[syndrome] = infoMask;
		}
	}

	private static final String[] PTY_NAMES = {
			"None", "News", "Current affairs", "Information", "Sport", "Education", "Drama", "Culture",
			"Science", "Varied", "Pop music", "Rock music", "Easy listening", "Light classical",
			"Serious classical", "Other music", "Weather", "Finance", "Children's programmes",
			"Social affairs", "Religion", "Phone-in", "Travel", "Leisure", "Jazz music", "Country music",
			"National music", "Oldies music", "Folk music", "Documentary", "Alarm test", "Alarm"
	};

	private static final CountryCode[] COUNTRY_CODES = {
			new CountryCode(0xe0, 0x2, "Algeria"),
			new CountryCode(0xe0, 0x3, "Andorra"),
			new CountryCode(0xe0, 0x4, "Israel"),
			new CountryCode(0xe0, 0x5, "Italy"),
			new CountryCode(0xe0, 0x6, "Belgium"),
			new CountryCode(0xe0, 0x7, "Russian Federation"),
			new CountryCode(0xe0, 0x8, "Azores / Palestine"),
			new CountryCode(0xe0, 0x9, "Albania"),
			new CountryCode(0xe0, 0xa, "Austria"),
			new CountryCode(0xe0, 0xb, "Hungary"),
			new CountryCode(0xe0, 0xc, "Malta"),
			new CountryCode(0xe0, 0xd, "Germany"),
			new CountryCode(0xe0, 0xf, "Egypt"),
			new CountryCode(0xe1, 0x1, "Greece"),
			new CountryCode(0xe1, 0x2, "Cyprus"),
			new CountryCode(0xe1, 0x3, "San Marino"),
			new CountryCode(0xe1, 0x4, "Switzerland"),
			new CountryCode(0xe1, 0x6, "Finland"),
			new CountryCode(0xe1, 0x7, "Luxembourg"),
			new CountryCode(0xe1, 0x8, "Bulgaria"),
			new CountryCode(0xe1, 0x9, "Denmark"),
			new CountryCode(0xe1, 0xa, "Gibraltar"),
			new CountryCode(0xe1, 0xb, "Iraq"),
			new CountryCode(0xe1, 0xd, "Libya"),
			new CountryCode(0xe1, 0xe, "Romania"),
			new CountryCode(0xe1, 0xf, "France"),
			new CountryCode(0xe2, 0x1, "Morocco"),
			new CountryCode(0xe2, 0x2, "Czech Republic"),
			new CountryCode(0xe2, 0x3, "Poland"),
			new CountryCode(0xe2, 0x5, "Slovakia"),
			new CountryCode(0xe2, 0x6, "Syria"),
			new CountryCode(0xe2, 0x7, "Tunisia"),
			new CountryCode(0xe2, 0x8, "Madeira"),
			new CountryCode(0xe2, 0x9, "Liechtenstein"),
			new CountryCode(0xe2, 0xa, "Iceland"),
			new CountryCode(0xe2, 0xb, "Monaco"),
			new CountryCode(0xe2, 0xc, "Lithuania"),
			new CountryCode(0xe2, 0xd, "Serbia"),
			new CountryCode(0xe2, 0xe, "Spain"),
			new CountryCode(0xe2, 0xf, "Norway"),
			new CountryCode(0xe3, 0x1, "Montenegro"),
			new CountryCode(0xe3, 0x2, "Ireland"),
			new CountryCode(0xe3, 0x3, "Turkey"),
			new CountryCode(0xe3, 0x5, "Tajikistan"),
			new CountryCode(0xe3, 0x8, "Netherlands"),
			new CountryCode(0xe3, 0x9, "Latvia"),
			new CountryCode(0xe3, 0xa, "Lebanon"),
			new CountryCode(0xe3, 0xb, "Azerbaijan"),
			new CountryCode(0xe3, 0xc, "Croatia"),
			new CountryCode(0xe3, 0xe, "Sweden"),
			new CountryCode(0xe3, 0xf, "Belarus"),
			new CountryCode(0xe4, 0x1, "Moldova"),
			new CountryCode(0xe4, 0x2, "Estonia"),
			new CountryCode(0xe4, 0x3, "Macedonia"),
			new CountryCode(0xe4, 0x8, "Portugal"),
			new CountryCode(0xe4, 0x9, "Slovenia"),
			new CountryCode(0xe4, 0xa, "Armenia"),
			new CountryCode(0xe4, 0xc, "Georgia"),
			new CountryCode(0xe4, 0xf, "Bosnia Herzegovina")
	};

	static final class Result {
		final boolean locked;
		final String state;
		final double pilotSnrDb;
		final double rdsSnrDb;
		final int windowBlocks;
		final int windowGroups;
		final double quality;
		final int pi;
		final String piDescription;
		final String countryName;
		final String coverageAreaName;
		final int pty;
		final String ptyName;
		final String audioMode;
		final String programmeMode;
		final boolean tp;
		final boolean ta;
		final String programService;
		final String radioText;
		final String clockText;
		final String alternativeFrequencies;
		final String programmeItem;
		final String rtPlusStatus;
		final String tmcStatus;
		final String eonStatus;
		final String tdcStatus;
		final String inHouseStatus;
		final String odaStatus;
		final int validBlocks;
		final int totalGroups;
		final int windowCorrectedBits;
		final int possibleBlocks;

		Result(boolean locked, String state, double pilotSnrDb, double rdsSnrDb, int windowBlocks, int windowGroups,
				double quality,
				int pi, String piDescription, String countryName, String coverageAreaName,
				int pty, String ptyName, String audioMode, String programmeMode, boolean tp, boolean ta,
				String programService, String radioText, String clockText, String alternativeFrequencies,
				String programmeItem, String rtPlusStatus, String tmcStatus, String eonStatus,
				String tdcStatus, String inHouseStatus, String odaStatus, int validBlocks,
				int totalGroups, int windowCorrectedBits, int possibleBlocks) {
			this.locked = locked;
			this.state = state;
			this.pilotSnrDb = pilotSnrDb;
			this.rdsSnrDb = rdsSnrDb;
			this.windowBlocks = windowBlocks;
			this.windowGroups = windowGroups;
			this.quality = quality;
			this.pi = pi;
			this.piDescription = piDescription;
			this.countryName = countryName;
			this.coverageAreaName = coverageAreaName;
			this.pty = pty;
			this.ptyName = ptyName;
			this.audioMode = audioMode;
			this.programmeMode = programmeMode;
			this.tp = tp;
			this.ta = ta;
			this.programService = programService;
			this.radioText = radioText;
			this.clockText = clockText;
			this.alternativeFrequencies = alternativeFrequencies;
			this.programmeItem = programmeItem;
			this.rtPlusStatus = rtPlusStatus;
			this.tmcStatus = tmcStatus;
			this.eonStatus = eonStatus;
			this.tdcStatus = tdcStatus;
			this.inHouseStatus = inHouseStatus;
			this.odaStatus = odaStatus;
			this.validBlocks = validBlocks;
			this.totalGroups = totalGroups;
			this.windowCorrectedBits = windowCorrectedBits;
			this.possibleBlocks = possibleBlocks;
		}

		static Result empty(String state) {
			return new Result(false, state, 0d, 0d, 0, 0, 0d, -1, "--", "--", "--",
					-1, "--", "--", "--", false, false, "", "", "", "--", "", "--", "--", "--",
					"--", "--", "--",
					0, 0, 0, 0);
		}
	}

	private static final class CountryCode {
		final int ecc;
		final int ci;
		final String name;

		CountryCode(int ecc, int ci, String name) {
			this.ecc = ecc;
			this.ci = ci;
			this.name = name;
		}
	}

	private static final class DecodeCandidate {
		int validBlocks;
		int correctedBits;
		int groups;
		final int[][] groupValues = new int[32][4];
		final boolean[] groupCPrime = new boolean[32];
		final int[] groupErrors = new int[32];
		DecodeParameters parameters;

		void addGroup(int[] values, boolean cPrime, int groupErrors) {
			if (groups < groupValues.length) {
				System.arraycopy(values, 0, groupValues[groups], 0, 4);
				groupCPrime[groups] = cPrime;
				this.groupErrors[groups] = groupErrors;
			}
			groups++;
		}

		boolean betterThan(DecodeCandidate other) {
			if (groups != other.groups) return groups > other.groups;
			if (validBlocks != other.validBlocks) return validBlocks > other.validBlocks;
			if (correctedBits != other.correctedBits) return correctedBits < other.correctedBits;
			return false;
		}
	}

	private static final class DecodeParameters {
		final double frequencyOffsetHz;
		final double bitRateOffset;
		final double phaseSymbols;
		final boolean inverted;

		DecodeParameters(double frequencyOffsetHz, double bitRateOffset, double phaseSymbols, boolean inverted) {
			this.frequencyOffsetHz = frequencyOffsetHz;
			this.bitRateOffset = bitRateOffset;
			this.phaseSymbols = phaseSymbols;
			this.inverted = inverted;
		}
	}

	private static final class Block {
		final int type;
		final boolean typeCPrime;
		final int info;
		final int correctedBits;

		Block(int type, boolean typeCPrime, int info, int correctedBits) {
			this.type = type;
			this.typeCPrime = typeCPrime;
			this.info = info;
			this.correctedBits = correctedBits;
		}
	}

	private static final class RadioTextCandidate {
		final String text;
		final boolean complete;
		final int minConfidence;

		RadioTextCandidate(String text, boolean complete, int minConfidence) {
			this.text = text;
			this.complete = complete;
			this.minConfidence = minConfidence;
		}
	}

	private static final class ClockValue {
		final long minuteSerial;
		final int offsetMinutes;
		final String text;

		ClockValue(long minuteSerial, int offsetMinutes, String text) {
			this.minuteSerial = minuteSerial;
			this.offsetMinutes = offsetMinutes;
			this.text = text;
		}

		boolean compatibleWith(ClockValue other, int maxMinutes) {
			return other != null && offsetMinutes == other.offsetMinutes
					&& Math.abs(minuteSerial - other.minuteSerial) <= maxMinutes;
		}
	}

	private static final class Complex {
		final double re, im;

		Complex(double re, double im) {
			this.re = re;
			this.im = im;
		}
	}

	private static final class MixData {
		final double[] sumRe, sumIm;

		MixData(double[] sumRe, double[] sumIm) {
			this.sumRe = sumRe;
			this.sumIm = sumIm;
		}

		int samples() {
			return Math.max(0, sumRe.length - 1);
		}

		Complex integrate(double start, double length) {
			int first = Math.max(0, (int) Math.floor(start));
			int last = Math.min(samples(), (int) Math.ceil(start + length));
			if (last <= first) return new Complex(0d, 0d);
			return new Complex(sumRe[last] - sumRe[first], sumIm[last] - sumIm[first]);
		}
	}
}
