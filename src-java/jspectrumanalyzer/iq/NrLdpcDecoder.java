package jspectrumanalyzer.iq;

/** TS 38.212 BG2 normalized min-sum decoder used by the SIB1 transport block. */
final class NrLdpcDecoder {
	private static final int Z = 80;
	private static final int K = 10 * Z;
	private static final int N = 50 * Z;
	private static final int[][] BG2 = {
		{0,0, 1,0, 2,0, 3,0, 6,0, 9,0, 10,0, 11,0},
		{0,57, 3,44, 4,0, 5,0, 6,8, 7,0, 8,0, 9,55, 11,0, 12,0},
		{0,20, 1,14, 3,19, 4,9, 8,28, 10,1, 12,0, 13,0},
		{1,38, 2,15, 4,22, 5,66, 6,12, 7,57, 8,53, 9,46, 10,0, 13,0},
		{0,0, 1,56, 11,77, 14,0},
		{0,0, 1,51, 5,62, 7,61, 11,64, 15,0},
		{0,0, 5,44, 7,19, 9,45, 11,68, 16,0},
		{1,0, 5,45, 7,68, 11,16, 13,78, 17,0},
		{0,0, 1,65, 12,7, 18,0},
		{1,0, 8,17, 10,51, 11,5, 19,0},
		{0,0, 1,17, 6,76, 7,20, 20,0},
		{0,0, 7,7, 9,4, 13,2, 21,0},
		{1,0, 3,33, 11,48, 22,0},
		{0,0, 1,32, 8,22, 13,26, 23,0},
		{1,0, 6,58, 11,57, 13,27, 24,0},
		{0,0, 10,73, 11,19, 25,0},
		{1,0, 9,79, 11,31, 12,63, 26,0},
		{1,0, 5,24, 11,29, 12,18, 27,0},
		{0,0, 6,18, 7,6, 28,0},
		{0,0, 1,78, 10,74, 29,0},
		{1,0, 4,68, 11,24, 30,0},
		{0,0, 8,17, 13,33, 31,0},
		{1,0, 2,4, 32,0},
		{0,0, 3,75, 5,78, 33,0},
		{1,0, 2,69, 9,7, 34,0},
		{0,0, 5,65, 35,0},
		{2,0, 7,20, 12,13, 13,7, 36,0},
		{0,0, 6,32, 37,0},
		{1,0, 2,46, 5,30, 38,0},
		{0,0, 4,74, 39,0},
		{2,0, 5,35, 7,51, 9,54, 40,0},
		{1,0, 13,20, 41,0},
		{0,0, 5,20, 12,42, 42,0},
		{2,0, 7,8, 10,13, 43,0},
		{0,0, 12,19, 13,78, 44,0},
		{1,0, 5,77, 11,6, 45,0},
		{0,0, 2,63, 7,2, 46,0},
		{10,0, 13,64, 47,0},
		{1,0, 5,13, 11,19, 48,0},
		{0,0, 7,24, 12,58, 49,0},
		{2,0, 10,36, 13,63, 50,0},
		{1,0, 5,2, 11,55, 51,0}
	};

	private NrLdpcDecoder() {}

	static Result decode(double[] rateMatched, int transportBlockBits, int rv) {
		int b = transportBlockBits + 16;
		if (b != 752 || rv < 0 || rv > 3 || rateMatched.length % 2 != 0) return Result.EMPTY;
		int filler = K - b;
		double[] deinterleaved = new double[rateMatched.length];
		int columns = rateMatched.length / 2;
		for (int j = 0; j < columns; j++) for (int i = 0; i < 2; i++)
			deinterleaved[i * columns + j] = rateMatched[j * 2 + i];

		double[] circular = new double[N];
		int[] observations = new int[N];
		int[] baseK0 = { 0, 13, 25, 43 };
		int k0 = Z * baseK0[rv];
		int firstFiller = K - 2 * Z - filler;
		int endFiller = K - 2 * Z;
		int at = 0, walked = 0;
		while (at < deinterleaved.length) {
			int index = (k0 + walked++) % N;
			if (index >= firstFiller && index < endFiller) continue;
			circular[index] += deinterleaved[at++];
			observations[index]++;
		}
		double repetitionAgreement = repetitionAgreement(deinterleaved, rv, firstFiller, endFiller);
		for (int i = firstFiller; i < endFiller; i++) circular[i] = 1e6;

		double[] soft = new double[52 * Z];
		System.arraycopy(circular, 0, soft, 2 * Z, circular.length);
		double[][][] checkMessages = new double[BG2.length][][];
		for (int row = 0; row < BG2.length; row++)
			checkMessages[row] = new double[BG2[row].length / 2][Z];

		int iterations = 0;
		for (; iterations < 50; iterations++) {
			for (int row = 0; row < BG2.length; row++) updateLayer(soft, checkMessages[row], BG2[row]);
			int[] candidate = hardBits(soft, b);
			if (crc16Ok(candidate)) return result(candidate, transportBlockBits, iterations + 1,
					repetitionAgreement);
			if (syndromeOk(soft)) break;
		}
		int[] bits = hardBits(soft, b);
		boolean crc = crc16Ok(bits);
		byte[] payload = new byte[transportBlockBits / 8];
		if (crc) for (int i = 0; i < transportBlockBits; i++)
			payload[i / 8] |= bits[i] << (7 - (i & 7));
		return new Result(crc, iterations + 1, payload, repetitionAgreement);
	}

	static double repetitionAgreement(double[] rateMatched, int rv) {
		if (rv < 0 || rv > 3 || rateMatched.length % 2 != 0) return 0;
		int columns = rateMatched.length / 2;
		double[] deinterleaved = new double[rateMatched.length];
		for (int j = 0; j < columns; j++) for (int i = 0; i < 2; i++)
			deinterleaved[i * columns + j] = rateMatched[j * 2 + i];
		return repetitionAgreement(deinterleaved, rv, K - 2 * Z - 48, K - 2 * Z);
	}

	private static double repetitionAgreement(double[] deinterleaved, int rv, int firstFiller,
			int endFiller) {
		boolean[] seen = new boolean[N];
		boolean[] firstSign = new boolean[N];
		int[] baseK0 = { 0, 13, 25, 43 };
		int k0 = Z * baseK0[rv];
		int at = 0, walked = 0, repeated = 0, agreeing = 0;
		while (at < deinterleaved.length) {
			int index = (k0 + walked++) % N;
			if (index >= firstFiller && index < endFiller) continue;
			boolean sign = deinterleaved[at++] < 0;
			if (seen[index]) {
				repeated++;
				if (firstSign[index] == sign) agreeing++;
			} else {
				seen[index] = true;
				firstSign[index] = sign;
			}
		}
		return repeated == 0 ? 0 : agreeing / (double)repeated;
	}

	private static int[] hardBits(double[] soft, int length) {
		int[] bits = new int[length];
		for (int i = 0; i < bits.length; i++) bits[i] = soft[i] < 0 ? 1 : 0;
		return bits;
	}

	private static Result result(int[] bits, int transportBlockBits, int iterations,
			double repetitionAgreement) {
		byte[] payload = new byte[transportBlockBits / 8];
		for (int i = 0; i < transportBlockBits; i++) payload[i / 8] |= bits[i] << (7 - (i & 7));
		return new Result(true, iterations, payload, repetitionAgreement);
	}

	private static void updateLayer(double[] soft, double[][] oldMessages, int[] row) {
		int edges = row.length / 2;
		double[][] extrinsic = new double[edges][Z];
		for (int edge = 0; edge < edges; edge++) {
			int column = row[2 * edge], shift = row[2 * edge + 1];
			for (int z = 0; z < Z; z++) {
				int variable = column * Z + (z + shift) % Z;
				extrinsic[edge][z] = soft[variable] - oldMessages[edge][z];
			}
		}
		for (int z = 0; z < Z; z++) {
			double min1 = Double.POSITIVE_INFINITY, min2 = Double.POSITIVE_INFINITY;
			int minimumEdge = -1, signProduct = 1;
			for (int edge = 0; edge < edges; edge++) {
				double value = extrinsic[edge][z];
				if (value < 0) signProduct = -signProduct;
				double magnitude = Math.abs(value);
				if (magnitude < min1) {
					min2 = min1; min1 = magnitude; minimumEdge = edge;
				} else if (magnitude < min2) min2 = magnitude;
			}
			for (int edge = 0; edge < edges; edge++) {
				double value = extrinsic[edge][z];
				int sign = signProduct * (value < 0 ? -1 : 1);
				double message = 0.8 * sign * (edge == minimumEdge ? min2 : min1);
				oldMessages[edge][z] = message;
				int variable = row[2 * edge] * Z + (z + row[2 * edge + 1]) % Z;
				soft[variable] = value + message;
			}
		}
	}

	private static boolean syndromeOk(double[] soft) {
		for (int[] row : BG2) for (int z = 0; z < Z; z++) {
			int parity = 0;
			for (int edge = 0; edge < row.length / 2; edge++) {
				int variable = row[2 * edge] * Z + (z + row[2 * edge + 1]) % Z;
				if (soft[variable] < 0) parity ^= 1;
			}
			if (parity != 0) return false;
		}
		return true;
	}

	private static boolean crc16Ok(int[] bits) {
		int crc = 0;
		for (int bit : bits) {
			int feedback = ((crc >>> 15) & 1) ^ bit;
			crc = (crc << 1) & 0xffff;
			if (feedback != 0) crc ^= 0x1021;
		}
		return crc == 0;
	}

	static final class Result {
		static final Result EMPTY = new Result(false, 0, new byte[0], 0);
		final boolean crcOk;
		final int iterations;
		final byte[] payload;
		final double repetitionAgreement;
		Result(boolean crcOk, int iterations, byte[] payload, double repetitionAgreement) {
			this.crcOk = crcOk;
			this.iterations = iterations;
			this.payload = payload;
			this.repetitionAgreement = repetitionAgreement;
		}
	}
}
