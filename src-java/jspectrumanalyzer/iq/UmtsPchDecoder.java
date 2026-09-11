package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** S-CCPCH/PCH physical and transport decoder for the common 10 ms convolutional format. */
final class UmtsPchDecoder {
	private static final int FRAME_CHIPS = 38400;
	private static final int[] SECOND_INTERLEAVER = {
		0, 20, 10, 5, 15, 25, 3, 13, 23, 8, 18, 28, 1, 11, 21,
		6, 16, 26, 4, 14, 24, 19, 9, 29, 12, 2, 7, 22, 27, 17
	};
	private static final int[] POLYNOMIALS = { 0435, 0657 };
	private final Map<Integer, Block> blocks = new LinkedHashMap<Integer, Block>();
	private final Map<Long, Long> recentBlocks = new LinkedHashMap<Long, Long>();
	private final UmtsPagingDecoder paging = new UmtsPagingDecoder();
	private int checkedFrames;

	Snapshot scan(double[] inI, double[] inQ, UmtsSignalAnalyzer.Candidate cell,
			UmtsSib5Decoder.Config config) {
		UmtsSib5Decoder.Channel channel = config == null ? null : config.pagingChannel;
		UmtsSib5Decoder.Transport transport = channel == null ? null : channel.pagingTransport();
		UmtsSib5Decoder.DynamicFormat dynamic = transport == null ? null : transport.format.primary();
		if (channel == null || transport == null || dynamic == null || channel.tfci || channel.pilots
				|| channel.sttd || channel.scramblingCode != 0 || channel.flexible
				|| transport.format.ttiMs != 10 || transport.format.codingChoice != 1
				|| transport.format.codingRate != 2 || transport.format.crcBits != 16
				|| dynamic.maximumBlocks < 1) return snapshot(false, "Unsupported PCH transport format");
		int physicalBits = 2 * FRAME_CHIPS / channel.spreadingFactor;
		int decodedBits = dynamic.blockBits + transport.format.crcBits + 8;
		int codedBits = decodedBits * 2;
		if (physicalBits < codedBits || physicalBits % 30 != 0)
			return snapshot(false, "Unsupported PCH rate matching");
		int baseSfn = baseSfn(cell.bch);
		if (baseSfn < 0) return snapshot(false, "Waiting for BCH frame number");
		int first = cell.frameStartSample + channel.timingOffset * 256;
		int frameIndex = 0;
		while (first < 0) { first += FRAME_CHIPS; frameIndex++; }
		while (first >= FRAME_CHIPS && first - FRAME_CHIPS >= 0) {
			first -= FRAME_CHIPS;
			frameIndex--;
		}
		for (int start = first; start + FRAME_CHIPS <= inI.length; start += FRAME_CHIPS, frameIndex++) {
			checkedFrames++;
			double[] physical = despread(inI, inQ, cell, channel, start);
			if (physical == null) continue;
			double[] matched = undoRateMatching(undoSecondInterleaver(physical), codedBits);
			DecodeAttempt decoded = bestViterbiOrientation(matched, decodedBits);
			if (!crcValid(decoded.bits, dynamic.blockBits, transport.format.crcBits)) continue;
			int sfn = Math.floorMod(baseSfn + frameIndex, 4096);
			int[] payload = Arrays.copyOf(decoded.bits, dynamic.blockBits);
			long fingerprint = ((long)sfn << 32) ^ (Arrays.hashCode(payload) & 0xffffffffL);
			long now = System.nanoTime(), previous = recentBlocks.containsKey(fingerprint)
					? recentBlocks.get(fingerprint) : Long.MIN_VALUE;
			if (previous == Long.MIN_VALUE || now - previous > 20_000_000_000L) {
				recentBlocks.put(fingerprint, now);
				int events = paging.accept(payload, sfn);
				blocks.put(sfn, new Block(sfn, decoded.metric, events));
				while (blocks.size() > 512) blocks.remove(blocks.keySet().iterator().next());
				while (recentBlocks.size() > 1024) recentBlocks.remove(recentBlocks.keySet().iterator().next());
			}
		}
		return snapshot(true, blocks.isEmpty() ? "PCH synchronized; waiting for transmitted blocks"
				: "PCH transport CRC OK");
	}

	private Snapshot snapshot(boolean supported, String state) {
		return new Snapshot(supported, checkedFrames,
				Collections.unmodifiableList(new ArrayList<Block>(blocks.values())), paging.snapshot(), state);
	}

	private static int baseSfn(UmtsBchDecoder.BchData bch) {
		if (bch == null || !bch.valid || bch.blocks.isEmpty()) return -1;
		UmtsBchDecoder.BchBlock block = bch.blocks.get(0);
		return Math.floorMod(block.sfn - block.firstFrame, 4096);
	}

	private static double[] despread(double[] inI, double[] inQ, UmtsSignalAnalyzer.Candidate cell,
			UmtsSib5Decoder.Channel channel, int start) {
		int sf = channel.spreadingFactor;
		int symbols = FRAME_CHIPS / sf;
		double[] result = new double[symbols * 2];
		double[] value = new double[2];
		double omega = cell.cfoHz * 2d * Math.PI / UmtsSignalAnalyzer.CHIP_RATE;
		for (int symbol = 0; symbol < symbols; symbol++) {
			int symbolStart = start + symbol * sf;
			int cpichStart = symbolStart - Math.floorMod(symbolStart - cell.frameStartSample, 256);
			if (symbolStart < 0 || symbolStart + sf > inI.length
					|| cpichStart < 0 || cpichStart + 256 > inI.length) return null;
			double pilotRe = 0, pilotIm = 0;
			for (int chip = 0; chip < 256; chip++) {
				int at = cpichStart + chip;
				descramble(inI, inQ, cell, at, omega, value);
				pilotRe += value[0]; pilotIm += value[1];
			}
			double dataRe = 0, dataIm = 0;
			for (int chip = 0; chip < sf; chip++) {
				int at = symbolStart + chip;
				descramble(inI, inQ, cell, at, omega, value);
				double ovsf = ovsfChip(sf, channel.code, chip);
				dataRe += value[0] * ovsf; dataIm += value[1] * ovsf;
			}
			// P-CPICH transmits 1+j and supplies a local channel estimate.
			double channelRe = (pilotRe + pilotIm) * .5d;
			double channelIm = (pilotIm - pilotRe) * .5d;
			double norm = channelRe * channelRe + channelIm * channelIm + 1e-12;
			result[2 * symbol] = (dataRe * channelRe + dataIm * channelIm) / norm;
			result[2 * symbol + 1] = (dataIm * channelRe - dataRe * channelIm) / norm;
		}
		return result;
	}

	private static void descramble(double[] inI, double[] inQ, UmtsSignalAnalyzer.Candidate cell,
			int at, double omega, double[] value) {
		double angle = -omega * (at - cell.frameStartSample);
		double ca = Math.cos(angle), sa = Math.sin(angle);
		double ar = inI[at] * ca - inQ[at] * sa;
		double ai = inI[at] * sa + inQ[at] * ca;
		if (cell.iqOrientation < 0) ai = -ai;
		int relative = Math.floorMod(at - cell.frameStartSample, FRAME_CHIPS);
		int[] sc = UmtsSignalAnalyzer.scramblingChip(cell.psc * 16, relative);
		value[0] = ar * sc[0] + ai * sc[1];
		value[1] = ai * sc[0] - ar * sc[1];
	}

	private static double ovsfChip(int sf, int code, int chip) {
		int reversed = Integer.reverse(chip) >>> (32 - Integer.numberOfTrailingZeros(sf));
		return (Integer.bitCount(code & reversed) & 1) == 0 ? 1d : -1d;
	}

	private static double[] undoSecondInterleaver(double[] received) {
		int rows = (received.length + 29) / 30;
		double[] input = new double[received.length];
		int at = 0;
		for (int column = 0; column < 30; column++) {
			int original = SECOND_INTERLEAVER[column];
			for (int row = 0; row < rows; row++) {
				int target = row * 30 + original;
				if (target < input.length && at < received.length) input[target] = received[at++];
			}
		}
		return input;
	}

	private static double[] undoRateMatching(double[] received, int sourceBits) {
		if (received.length == sourceBits) return received;
		if (received.length < sourceBits) return Arrays.copyOf(received, sourceBits);
		int repeated = received.length - sourceBits;
		double[] source = new double[sourceBits];
		int at = 0, e = 1, plus = 2 * sourceBits, minus = 2 * repeated;
		for (int index = 0; index < sourceBits && at < received.length; index++) {
			double value = received[at++];
			e -= minus;
			while (e <= 0 && at < received.length) {
				value += received[at++];
				e += plus;
			}
			source[index] = value;
		}
		return source;
	}

	private static DecodeAttempt bestViterbiOrientation(double[] coded, int inputBits) {
		DecodeAttempt best = null;
		for (int swap = 0; swap < 2; swap++) for (int invertI = 0; invertI < 2; invertI++)
			for (int invertQ = 0; invertQ < 2; invertQ++) {
				double[] alternative = new double[coded.length];
				for (int pair = 0; pair + 1 < coded.length; pair += 2) {
					double i = coded[pair] * (invertI == 0 ? 1 : -1);
					double q = coded[pair + 1] * (invertQ == 0 ? 1 : -1);
					alternative[pair] = swap == 0 ? i : q;
					alternative[pair + 1] = swap == 0 ? q : i;
				}
				DecodeAttempt attempt = viterbi(alternative, inputBits);
				if (best == null || attempt.metric > best.metric) best = attempt;
			}
		return best;
	}

	private static DecodeAttempt viterbi(double[] coded, int inputBits) {
		double impossible = -1e100;
		double[] metric = new double[256];
		Arrays.fill(metric, impossible); metric[0] = 0;
		short[][] previous = new short[inputBits][256];
		byte[][] bitAt = new byte[inputBits][256];
		for (int time = 0; time < inputBits; time++) {
			double[] next = new double[256]; Arrays.fill(next, impossible);
			for (int state = 0; state < 256; state++) {
				if (metric[state] <= impossible / 2) continue;
				for (int bit = 0; bit < 2; bit++) {
					int register = state << 1 | bit, target = register & 255;
					double score = metric[state];
					for (int stream = 0; stream < 2; stream++) {
						int encoded = Integer.bitCount(register & POLYNOMIALS[stream]) & 1;
						double soft = coded[2 * time + stream];
						score += encoded == 0 ? soft : -soft;
					}
					if (score > next[target]) {
						next[target] = score; previous[time][target] = (short)state;
						bitAt[time][target] = (byte)bit;
					}
				}
			}
			metric = next;
		}
		int[] bits = new int[inputBits]; int state = 0;
		for (int time = inputBits - 1; time >= 0; time--) {
			bits[time] = bitAt[time][state]; state = previous[time][state] & 0xffff;
		}
		return new DecodeAttempt(bits, metric[0] / Math.max(1, inputBits));
	}

	private static boolean crcValid(int[] decoded, int payloadBits, int crcBits) {
		for (int index = payloadBits + crcBits; index < decoded.length; index++)
			if (decoded[index] != 0) return false;
		int crc = 0;
		for (int index = 0; index < payloadBits + crcBits; index++) {
			int bit = index < payloadBits ? decoded[index]
					: decoded[payloadBits + crcBits - 1 - (index - payloadBits)];
			int feedback = (crc >>> 15 & 1) ^ bit;
			crc = crc << 1 & 0xffff;
			if (feedback != 0) crc ^= 0x1021;
		}
		return crc == 0;
	}

	static final class Snapshot {
		static final Snapshot EMPTY = new Snapshot(false, 0, Collections.<Block>emptyList(),
				UmtsPagingDecoder.Snapshot.EMPTY, "Waiting for SIB5");
		final boolean supported;
		final int checkedFrames;
		final List<Block> blocks;
		final UmtsPagingDecoder.Snapshot paging;
		final String state;
		Snapshot(boolean supported, int checkedFrames, List<Block> blocks,
				UmtsPagingDecoder.Snapshot paging, String state) {
			this.supported = supported; this.checkedFrames = checkedFrames; this.blocks = blocks;
			this.paging = paging; this.state = state;
		}
	}

	static final class Block {
		final int sfn;
		final double metric;
		final int pagingEvents;
		Block(int sfn, double metric, int pagingEvents) {
			this.sfn = sfn; this.metric = metric; this.pagingEvents = pagingEvents;
		}
	}

	private static final class DecodeAttempt {
		final int[] bits;
		final double metric;
		DecodeAttempt(int[] bits, double metric) { this.bits = bits; this.metric = metric; }
	}
}
