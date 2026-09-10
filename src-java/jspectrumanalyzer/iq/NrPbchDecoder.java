package jspectrumanalyzer.iq;

import java.util.Arrays;

/** 3GPP TS 38.211/38.212 PBCH demodulation and polar decoding for an FR1 SSB. */
final class NrPbchDecoder {
	private static final int PBCH_SYMBOLS = 432;
	private static final int E = 864;
	private static final int N = 512;
	private static final int K = 56;
	private static final int[] SUBBLOCK_PATTERN = {
			0, 1, 2, 4, 3, 5, 6, 7, 8, 16, 9, 17, 10, 18, 11, 19,
			12, 20, 13, 21, 14, 22, 15, 23, 24, 25, 26, 28, 27, 29, 30, 31
	};
	private static final int[] INFORMATION_SET = {
			247, 253, 254, 255, 367, 375, 379, 381, 382, 383, 415, 431, 439, 441,
			443, 444, 445, 446, 447, 463, 469, 470, 471, 473, 474, 475, 476, 477,
			478, 479, 483, 485, 486, 487, 489, 490, 491, 492, 493, 494, 495, 497,
			498, 499, 500, 501, 502, 503, 504, 505, 506, 507, 508, 509, 510, 511
	};
	private static final int[] INPUT_INTERLEAVER = {
			0, 2, 4, 7, 9, 14, 19, 20, 24, 25, 26, 28, 31, 34, 42, 45, 49, 50,
			51, 53, 54, 56, 58, 59, 61, 62, 65, 66, 67, 69, 70, 71, 72, 76, 77,
			81, 82, 83, 87, 88, 89, 91, 93, 95, 98, 101, 104, 106, 108, 110, 111,
			113, 115, 118, 119, 120, 122, 123, 126, 127, 129, 132, 134, 138, 139,
			140, 1, 3, 5, 8, 10, 15, 21, 27, 29, 32, 35, 43, 46, 52, 55, 57, 60,
			63, 68, 73, 78, 84, 90, 92, 94, 96, 99, 102, 105, 107, 109, 112, 114,
			116, 121, 124, 128, 130, 133, 135, 141, 6, 11, 16, 22, 30, 33, 36, 44,
			47, 64, 74, 79, 85, 97, 100, 103, 117, 125, 131, 136, 142, 12, 17, 23,
			37, 48, 75, 80, 86, 137, 143, 13, 18, 38, 144, 39, 145, 40, 146, 41,
			147, 148, 149, 150, 151, 152, 153, 154, 155, 156, 157, 158, 159, 160,
			161, 162, 163
	};
	private static final int[] PAYLOAD_G = {
			16, 23, 18, 17, 8, 30, 10, 6, 24, 7, 0, 5, 3, 2, 1, 4,
			9, 11, 12, 13, 14, 15, 19, 20, 21, 22, 25, 26, 27, 28, 29, 31
	};

	private NrPbchDecoder() {}

	static Decoded decode(double[][][] symbols, int pci, int iBarSsb, int centerBin) {
		if (symbols == null || symbols.length != 3 || iBarSsb < 0) return Decoded.EMPTY;
		double[][] dmrs = dmrs(pci, iBarSsb);
		double[] llr = new double[E];
		int dmrsAt = 0, dataAt = 0;
		double error = 0, signal = 0;
		for (int symbol = 1; symbol <= 3; symbol++) {
			int[][] ranges = symbol == 2 ? new int[][] { { 0, 47 }, { 192, 239 } }
					: new int[][] { { 0, 239 } };
			for (int[] range : ranges) {
				double[][] channel = estimateChannel(symbols[symbol - 1], pci & 3, range, dmrs, dmrsAt,
						centerBin);
				dmrsAt += pilotCount(pci & 3, range);
				for (int k = range[0]; k <= range[1]; k++) {
					if ((k & 3) == (pci & 3)) continue;
					int bin = Math.floorMod(centerBin + k - 119, 1024);
					double yr = symbols[symbol - 1][0][bin], yi = symbols[symbol - 1][1][bin];
					double hr = channel[0][k - range[0]], hi = channel[1][k - range[0]];
					double hp = hr * hr + hi * hi;
					if (hp < 1e-18) {
						llr[2 * dataAt] = llr[2 * dataAt + 1] = 0;
					} else {
						double xr = (yr * hr + yi * hi) / hp;
						double xi = (yi * hr - yr * hi) / hp;
						llr[2 * dataAt] = xr;
						llr[2 * dataAt + 1] = xi;
						double targetR = Math.copySign(1d / Math.sqrt(2d), xr);
						double targetI = Math.copySign(1d / Math.sqrt(2d), xi);
						error += square(xr - targetR) + square(xi - targetI);
						signal += targetR * targetR + targetI * targetI;
					}
					dataAt++;
				}
			}
		}
		if (dataAt != PBCH_SYMBOLS || dmrsAt != 144) return Decoded.EMPTY;
		double evm = signal <= 0 ? 0 : Math.sqrt(error / signal);
		for (int swap = 0; swap < 2; swap++) {
			for (int sign = 1; sign >= -1; sign -= 2) {
				double[] ordered = reorderLlrs(llr, swap != 0, sign);
				int[] message = decodeBits(ordered, pci, iBarSsb & 3);
				if (message != null && crc24cOk(message)) {
					return new Decoded(true, evm, parseMib(message, pci, iBarSsb));
				}
			}
		}
		return new Decoded(false, evm, NrSignalAnalyzer.MibData.EMPTY);
	}

	private static double[][] estimateChannel(double[][] symbol, int shift, int[] range,
			double[][] reference, int referenceStart, int centerBin) {
		int first = range[0] + Math.floorMod(shift - range[0], 4);
		int pilots = first > range[1] ? 0 : (range[1] - first) / 4 + 1;
		double[] pilotR = new double[pilots], pilotI = new double[pilots];
		for (int p = 0; p < pilots; p++) {
			int k = first + 4 * p;
			int bin = Math.floorMod(centerBin + k - 119, 1024);
			double yr = symbol[0][bin], yi = symbol[1][bin];
			double rr = reference[0][referenceStart + p], ri = reference[1][referenceStart + p];
			pilotR[p] = yr * rr + yi * ri;
			pilotI[p] = yi * rr - yr * ri;
		}
		double[] channelR = new double[range[1] - range[0] + 1];
		double[] channelI = new double[channelR.length];
		for (int k = range[0]; k <= range[1]; k++) {
			double position = (k - first) / 4d;
			int left = Math.max(0, Math.min(pilots - 1, (int)Math.floor(position)));
			int right = Math.max(0, Math.min(pilots - 1, left + 1));
			double fraction = Math.max(0, Math.min(1, position - left));
			channelR[k - range[0]] = pilotR[left] * (1 - fraction) + pilotR[right] * fraction;
			channelI[k - range[0]] = pilotI[left] * (1 - fraction) + pilotI[right] * fraction;
		}
		return new double[][] { channelR, channelI };
	}

	private static int pilotCount(int shift, int[] range) {
		int first = range[0] + Math.floorMod(shift - range[0], 4);
		return first > range[1] ? 0 : (range[1] - first) / 4 + 1;
	}

	private static double[] reorderLlrs(double[] input, boolean swap, int sign) {
		double[] output = new double[input.length];
		for (int symbol = 0; symbol < PBCH_SYMBOLS; symbol++) {
			output[2 * symbol] = sign * input[2 * symbol + (swap ? 1 : 0)];
			output[2 * symbol + 1] = sign * input[2 * symbol + (swap ? 0 : 1)];
		}
		return output;
	}

	private static int[] decodeBits(double[] llr, int pci, int ssbIndex) {
		int[] scramble = gold(pci, (ssbIndex & 3) * E + E);
		int offset = (ssbIndex & 3) * E;
		double[] rateMatched = new double[E];
		for (int i = 0; i < E; i++) rateMatched[i] = llr[i] * (1 - 2 * scramble[offset + i]);

		double[] repeated = new double[N];
		for (int i = 0; i < E; i++) repeated[i % N] += rateMatched[i];
		double[] mother = new double[N];
		for (int j = 0; j < N; j++) {
			int index = SUBBLOCK_PATTERN[j / 16] * 16 + (j & 15);
			mother[index] = repeated[j];
		}

		boolean[] information = new boolean[N];
		for (int value : INFORMATION_SET) information[value] = true;
		int[] decoded = new int[N];
		polarDecode(mother, 0, information, decoded);
		int[] interleaved = new int[K];
		for (int i = 0; i < K; i++) interleaved[i] = decoded[INFORMATION_SET[i]];
		int[] message = new int[K];
		int at = 0;
		for (int m = 0; m < INPUT_INTERLEAVER.length; m++) {
			if (INPUT_INTERLEAVER[m] >= INPUT_INTERLEAVER.length - K) {
				int pi = INPUT_INTERLEAVER[m] - (INPUT_INTERLEAVER.length - K);
				message[pi] = interleaved[at++];
			}
		}
		return at == K ? message : null;
	}

	private static int[] polarDecode(double[] alpha, int offset, boolean[] information, int[] decoded) {
		if (alpha.length == 1) {
			int bit = information[offset] && alpha[0] < 0 ? 1 : 0;
			decoded[offset] = bit;
			return new int[] { bit };
		}
		int half = alpha.length / 2;
		double[] leftLlr = new double[half];
		for (int i = 0; i < half; i++) {
			double a = alpha[i], b = alpha[i + half];
			leftLlr[i] = Math.copySign(Math.min(Math.abs(a), Math.abs(b)), a * b);
		}
		int[] left = polarDecode(leftLlr, offset, information, decoded);
		double[] rightLlr = new double[half];
		for (int i = 0; i < half; i++) rightLlr[i] = alpha[i + half] + (1 - 2 * left[i]) * alpha[i];
		int[] right = polarDecode(rightLlr, offset + half, information, decoded);
		int[] beta = new int[alpha.length];
		for (int i = 0; i < half; i++) {
			beta[i] = left[i] ^ right[i];
			beta[i + half] = right[i];
		}
		return beta;
	}

	private static boolean crc24cOk(int[] bits) {
		int crc = 0;
		for (int bit : bits) {
			int feedback = ((crc >>> 23) & 1) ^ bit;
			crc = (crc << 1) & 0xffffff;
			if (feedback != 0) crc ^= 0xb2b117;
		}
		return crc == 0;
	}

	private static NrSignalAnalyzer.MibData parseMib(int[] message, int pci, int iBarSsb) {
		int[] a = Arrays.copyOf(message, 32);
		int v = 2 * a[PAYLOAD_G[7]] + a[PAYLOAD_G[8]];
		int[] sequence = gold(pci, v * 29 + 29);
		int sequenceAt = v * 29;
		for (int i = 0; i < 32; i++) {
			boolean protectedBit = i == PAYLOAD_G[10] || i == PAYLOAD_G[7] || i == PAYLOAD_G[8];
			if (!protectedBit) a[i] ^= sequence[sequenceAt++];
		}
		int[] payload = new int[24];
		int jSfn = 0, jOther = 14;
		for (int i = 0; i < payload.length; i++) {
			payload[i] = i >= 1 && i < 7 ? a[PAYLOAD_G[jSfn++]] : a[PAYLOAD_G[jOther++]];
		}
		int sfnLow = 0;
		for (int i = 0; i < 4; i++) sfnLow = (sfnLow << 1) | a[PAYLOAD_G[jSfn++]];
		if (payload[0] != 0) return NrSignalAnalyzer.MibData.EMPTY;
		int at = 1;
		int sfn = (read(payload, at, 6) << 4) | sfnLow; at += 6;
		int scs = payload[at++] == 0 ? 15 : 30;
		int kSsb = (a[PAYLOAD_G[11]] << 4) | read(payload, at, 4); at += 4;
		int dmrsPosition = payload[at++] == 0 ? 2 : 3;
		int pdcch = read(payload, at, 8); at += 8;
		boolean barred = payload[at++] == 0;
		boolean reselection = payload[at++] == 0;
		return new NrSignalAnalyzer.MibData(true, sfn, scs, kSsb, dmrsPosition, pdcch,
				barred, reselection);
	}

	private static int read(int[] bits, int offset, int length) {
		int value = 0;
		for (int i = 0; i < length; i++) value = (value << 1) | bits[offset + i];
		return value;
	}

	private static double[][] dmrs(int pci, int iBarSsb) {
		int cinit = (1 << 11) * (iBarSsb + 1) * (pci / 4 + 1)
				+ (1 << 6) * (iBarSsb + 1) + (pci & 3);
		int[] sequence = gold(cinit, 288);
		double[][] result = new double[2][144];
		double scale = 1d / Math.sqrt(2d);
		for (int m = 0; m < 144; m++) {
			result[0][m] = (1 - 2 * sequence[2 * m]) * scale;
			result[1][m] = (1 - 2 * sequence[2 * m + 1]) * scale;
		}
		return result;
	}

	private static int[] gold(int init, int length) {
		int total = 1600 + length + 31;
		int[] x1 = new int[total], x2 = new int[total];
		x1[0] = 1;
		for (int n = 0; n < 31; n++) x2[n] = (init >>> n) & 1;
		for (int n = 0; n < 1600 + length; n++) {
			x1[n + 31] = x1[n + 3] ^ x1[n];
			x2[n + 31] = x2[n + 3] ^ x2[n + 2] ^ x2[n + 1] ^ x2[n];
		}
		int[] result = new int[length];
		for (int n = 0; n < length; n++) result[n] = x1[n + 1600] ^ x2[n + 1600];
		return result;
	}

	private static double square(double value) {
		return value * value;
	}

	static final class Decoded {
		static final Decoded EMPTY = new Decoded(false, 0, NrSignalAnalyzer.MibData.EMPTY);
		final boolean crcOk;
		final double evm;
		final NrSignalAnalyzer.MibData mib;
		Decoded(boolean crcOk, double evm, NrSignalAnalyzer.MibData mib) {
			this.crcOk = crcOk;
			this.evm = evm;
			this.mib = mib;
		}
	}
}
