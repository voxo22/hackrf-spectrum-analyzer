package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** P-CCPCH physical-channel and BCH transport-channel decoder. */
final class UmtsBchDecoder {
	private static final int FRAME_CHIPS = 38400;
	private static final int SLOT_CHIPS = 2560;
	private static final int SYMBOL_CHIPS = 256;
	private static final int FRAME_BITS = 270;
	private static final int TTI_BITS = 540;
	private static final int BCH_DATA_BITS = 246;
	private static final int BCH_CRC_BITS = 16;
	private static final int CONV_INPUT_BITS = BCH_DATA_BITS + BCH_CRC_BITS + 8;
	private static final int[] SECOND_INTERLEAVER = {
		0, 20, 10, 5, 15, 25, 3, 13, 23, 8, 18, 28, 1, 11, 21,
		6, 16, 26, 4, 14, 24, 19, 9, 29, 12, 2, 7, 22, 27, 17
	};
	// Bit zero in this decoder is the newest shift-register bit.
	private static final int[] POLYNOMIALS = { 0435, 0657 };

	private UmtsBchDecoder() {}

	static BchData decode(double[] inI, double[] inQ, UmtsSignalAnalyzer.Candidate cell) {
		int availableFrames = (inI.length - cell.frameStartSample) / FRAME_CHIPS;
		if (availableFrames < 2) return BchData.EMPTY;
		availableFrames = Math.min(availableFrames, 12);
		double[][] frames = new double[availableFrames][];
		for (int frame = 0; frame < availableFrames; frame++) {
			frames[frame] = despreadFrame(inI, inQ, cell, frame);
			if (frames[frame] == null) return BchData.EMPTY;
		}

		DecodeAttempt best = null;
		int attempts = 0;
		List<BchBlock> decodedBlocks = new ArrayList<BchBlock>();
		for (int firstFrame = 0; firstFrame + 1 < availableFrames; firstFrame++) {
			attempts++;
			double[] first = undoSecondInterleaver(frames[firstFrame]);
			double[] second = undoSecondInterleaver(frames[firstFrame + 1]);
			double[] coded = undoFirstInterleaver(first, second);
			DecodeAttempt attempt = viterbi(coded);
			if (best == null || attempt.metric > best.metric) best = attempt;
			if (crcValid(attempt.bits)) {
				int[] payload = Arrays.copyOf(attempt.bits, BCH_DATA_BITS);
				decodedBlocks.add(new BchBlock(payload, firstFrame, attempt.metric / CONV_INPUT_BITS));
			}
		}
		if (!decodedBlocks.isEmpty())
			return new BchData(decodedBlocks, attempts);
		return new BchData(false, new int[0], -1,
				best == null ? 0d : best.metric / CONV_INPUT_BITS, attempts);
	}

	private static double[] despreadFrame(double[] inI, double[] inQ,
			UmtsSignalAnalyzer.Candidate cell, int frameIndex) {
		double[] physical = new double[FRAME_BITS];
		int frameStart = cell.frameStartSample + frameIndex * FRAME_CHIPS;
		if (frameStart < 0 || frameStart + FRAME_CHIPS > inI.length) return null;
		double omega = cell.cfoHz * 2d * Math.PI / UmtsSignalAnalyzer.CHIP_RATE;
		int codeNumber = cell.psc * 16;
		int out = 0;
		for (int slot = 0; slot < 15; slot++) {
			int slotStart = frameStart + slot * SLOT_CHIPS;
			for (int symbol = 0; symbol < 10; symbol++) {
				int symbolStart = slotStart + symbol * SYMBOL_CHIPS;
				double pilotRe = 0, pilotIm = 0, dataRe = 0, dataIm = 0;
				for (int chip = 0; chip < SYMBOL_CHIPS; chip++) {
					int at = symbolStart + chip;
					int relativeChip = slot * SLOT_CHIPS + symbol * SYMBOL_CHIPS + chip;
					double angle = -omega * (at - frameStart);
					double ca = Math.cos(angle), sa = Math.sin(angle);
					double ar = inI[at] * ca - inQ[at] * sa;
					double ai = inI[at] * sa + inQ[at] * ca;
					if (cell.iqOrientation < 0) ai = -ai;
					int[] sc = UmtsSignalAnalyzer.scramblingChip(codeNumber, relativeChip);
					double dr = ar * sc[0] + ai * sc[1];
					double di = ai * sc[0] - ar * sc[1];
					pilotRe += dr;
					pilotIm += di;
					double ovsf = chip < SYMBOL_CHIPS / 2 ? 1d : -1d;
					dataRe += dr * ovsf;
					dataIm += di * ovsf;
				}
				if (symbol == 0) continue;
				// P-CPICH transmits (1+j); divide it out to estimate the channel.
				double channelRe = (pilotRe + pilotIm) * .5d;
				double channelIm = (pilotIm - pilotRe) * .5d;
				double norm = channelRe * channelRe + channelIm * channelIm + 1e-12;
				double equalizedRe = (dataRe * channelRe + dataIm * channelIm) / norm;
				double equalizedIm = (dataIm * channelRe - dataRe * channelIm) / norm;
				physical[out++] = equalizedRe;
				physical[out++] = equalizedIm;
			}
		}
		return physical;
	}

	private static double[] undoSecondInterleaver(double[] received) {
		double[] input = new double[FRAME_BITS];
		int rows = FRAME_BITS / SECOND_INTERLEAVER.length;
		int at = 0;
		for (int column = 0; column < SECOND_INTERLEAVER.length; column++) {
			int originalColumn = SECOND_INTERLEAVER[column];
			for (int row = 0; row < rows; row++)
				input[row * SECOND_INTERLEAVER.length + originalColumn] = received[at++];
		}
		return input;
	}

	private static double[] undoFirstInterleaver(double[] first, double[] second) {
		double[] coded = new double[TTI_BITS];
		for (int i = 0; i < FRAME_BITS; i++) {
			coded[2 * i] = first[i];
			coded[2 * i + 1] = second[i];
		}
		return coded;
	}

	private static DecodeAttempt viterbi(double[] coded) {
		final double impossible = -1e100;
		double[] metric = new double[256];
		Arrays.fill(metric, impossible);
		metric[0] = 0;
		short[][] previous = new short[CONV_INPUT_BITS][256];
		byte[][] bitAt = new byte[CONV_INPUT_BITS][256];
		for (int time = 0; time < CONV_INPUT_BITS; time++) {
			double[] next = new double[256];
			Arrays.fill(next, impossible);
			for (int state = 0; state < 256; state++) {
				if (metric[state] <= impossible / 2) continue;
				for (int bit = 0; bit < 2; bit++) {
					int register = (state << 1) | bit;
					int target = register & 255;
					double score = metric[state];
					for (int stream = 0; stream < 2; stream++) {
						int encoded = Integer.bitCount(register & POLYNOMIALS[stream]) & 1;
						double soft = coded[2 * time + stream];
						score += encoded == 0 ? soft : -soft;
					}
					if (score > next[target]) {
						next[target] = score;
						previous[time][target] = (short)state;
						bitAt[time][target] = (byte)bit;
					}
				}
			}
			metric = next;
		}
		int[] bits = new int[CONV_INPUT_BITS];
		int state = 0;
		for (int time = CONV_INPUT_BITS - 1; time >= 0; time--) {
			bits[time] = bitAt[time][state];
			state = previous[time][state] & 0xffff;
		}
		return new DecodeAttempt(bits, metric[0]);
	}

	private static boolean crcValid(int[] decoded) {
		for (int i = BCH_DATA_BITS + BCH_CRC_BITS; i < decoded.length; i++)
			if (decoded[i] != 0) return false;
		return crcRemainder(decoded) == 0;
	}

	private static int crcRemainder(int[] decoded) {
		int crc = 0;
		for (int i = 0; i < BCH_DATA_BITS + BCH_CRC_BITS; i++) {
			int bit = i < BCH_DATA_BITS ? decoded[i]
					: decoded[BCH_DATA_BITS + BCH_CRC_BITS - 1 - (i - BCH_DATA_BITS)];
			int feedback = ((crc >>> 15) & 1) ^ bit;
			crc = (crc << 1) & 0xffff;
			if (feedback != 0) crc ^= 0x1021;
		}
		return crc;
	}


	private static String bitsHex(int[] bits, int length) {
		StringBuilder result = new StringBuilder((length + 3) / 4);
		for (int at = 0; at < length; at += 4) {
			int value = 0;
			for (int bit = 0; bit < 4; bit++)
				value = (value << 1) | (at + bit < length ? bits[at + bit] : 0);
			result.append(Character.forDigit(value, 16));
		}
		return result.toString().toUpperCase(java.util.Locale.ROOT);
	}

	private static int bitValue(int[] bits, int at, int length) {
		int value = 0;
		for (int i = 0; i < length; i++) value = (value << 1) | bits[at + i];
		return value;
	}

	static final class BchData {
		private static final String[] PAYLOAD_TYPES = {
			"no segment", "first segment", "subsequent segment", "last segment (short)",
			"last + first", "last + complete", "last + complete + first", "complete SIB list",
			"complete + first", "complete SIB", "last segment",
			"spare 5", "spare 4", "spare 3", "spare 2", "spare 1"
		};
		static final BchData EMPTY = new BchData(false, new int[0], -1, 0d, 0);
		final boolean valid;
		final int[] payloadBits;
		final int firstFrame, attempts, sfn, payloadChoice;
		final double pathMetric;
		final List<BchBlock> blocks;
		BchData(boolean valid, int[] payloadBits, int firstFrame, double pathMetric, int attempts) {
			this.valid = valid;
			this.payloadBits = payloadBits;
			this.firstFrame = firstFrame;
			this.pathMetric = pathMetric;
			this.attempts = attempts;
			this.sfn = valid && payloadBits.length >= 11 ? bitValue(payloadBits, 0, 11) * 2 : -1;
			this.payloadChoice = valid && payloadBits.length >= 15 ? bitValue(payloadBits, 11, 4) : -1;
			this.blocks = valid
					? Collections.singletonList(new BchBlock(payloadBits, firstFrame, pathMetric))
					: Collections.<BchBlock>emptyList();
		}

		BchData(List<BchBlock> blocks, int attempts) {
			BchBlock first = blocks.get(0);
			this.valid = true;
			this.payloadBits = first.payloadBits;
			this.firstFrame = first.firstFrame;
			this.pathMetric = first.pathMetric;
			this.attempts = attempts;
			this.sfn = first.sfn;
			this.payloadChoice = first.payloadChoice;
			this.blocks = Collections.unmodifiableList(new ArrayList<BchBlock>(blocks));
		}

		String payloadHex() {
			if (!valid) return "";
			return bitsHex(payloadBits, payloadBits.length);
		}

		String payloadType() {
			return payloadChoice >= 0 && payloadChoice < PAYLOAD_TYPES.length
					? PAYLOAD_TYPES[payloadChoice] : "unknown";
		}
	}

	static final class BchBlock {
		final int[] payloadBits;
		final int firstFrame, sfn, payloadChoice;
		final double pathMetric;

		BchBlock(int[] payloadBits, int firstFrame, double pathMetric) {
			this.payloadBits = payloadBits;
			this.firstFrame = firstFrame;
			this.pathMetric = pathMetric;
			this.sfn = payloadBits.length >= 11 ? bitValue(payloadBits, 0, 11) * 2 : -1;
			this.payloadChoice = payloadBits.length >= 15 ? bitValue(payloadBits, 11, 4) : -1;
		}

		String payloadHex() { return bitsHex(payloadBits, payloadBits.length); }
	}

	private static final class DecodeAttempt {
		final int[] bits;
		final double metric;
		DecodeAttempt(int[] bits, double metric) { this.bits = bits; this.metric = metric; }
	}
}
