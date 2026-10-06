package jspectrumanalyzer.iq;

/** TS 38.212 BG2 normalized min-sum decoder used by the SIB1 transport block. */
final class NrLdpcDecoder {
	private static final int MAX_ITERATIONS = Integer.getInteger("nr.ldpc.iterations", 50);
	private static final double MIN_SUM_SCALE = Double.parseDouble(System.getProperty("nr.ldpc.alpha", "0.8"));
	// TS 38.212 BG2, lifting-size set iLS=0 (Zc=32).
	private static final int[][] BG2_32 = {
		{0,9, 1,117, 2,204, 3,26, 6,189, 9,205, 10,0, 11,0},
		{0,167, 3,166, 4,253, 5,125, 6,226, 7,156, 8,224, 9,252, 11,0, 12,0},
		{0,81, 1,114, 3,44, 4,52, 8,240, 10,1, 12,0, 13,0},
		{1,8, 2,58, 4,158, 5,104, 6,209, 7,54, 8,18, 9,128, 10,0, 13,0},
		{0,179, 1,214, 11,71, 14,0},
		{0,231, 1,41, 5,194, 7,159, 11,103, 15,0},
		{0,155, 5,228, 7,45, 9,28, 11,158, 16,0},
		{1,129, 5,147, 7,140, 11,3, 13,116, 17,0},
		{0,142, 1,94, 12,230, 18,0},
		{1,203, 8,205, 10,61, 11,247, 19,0},
		{0,11, 1,185, 6,0, 7,117, 20,0},
		{0,11, 7,236, 9,210, 13,56, 21,0},
		{1,63, 3,111, 11,14, 22,0},
		{0,83, 1,2, 8,38, 13,222, 23,0},
		{1,115, 6,145, 11,3, 13,232, 24,0},
		{0,51, 10,175, 11,213, 25,0},
		{1,203, 9,142, 11,8, 12,242, 26,0},
		{1,254, 5,124, 11,114, 12,64, 27,0},
		{0,220, 6,194, 7,50, 28,0},
		{0,87, 1,20, 10,185, 29,0},
		{1,26, 4,105, 11,29, 30,0},
		{0,76, 8,42, 13,210, 31,0},
		{1,222, 2,63, 32,0},
		{0,23, 3,235, 5,238, 33,0},
		{1,46, 2,139, 9,8, 34,0},
		{0,228, 5,156, 35,0},
		{2,29, 7,143, 12,160, 13,122, 36,0},
		{0,8, 6,151, 37,0},
		{1,98, 2,101, 5,135, 38,0},
		{0,18, 4,28, 39,0},
		{2,71, 5,240, 7,9, 9,84, 40,0},
		{1,106, 13,1, 41,0},
		{0,242, 5,44, 12,166, 42,0},
		{2,132, 7,164, 10,235, 43,0},
		{0,147, 12,85, 13,36, 44,0},
		{1,57, 5,40, 11,63, 45,0},
		{0,140, 2,38, 7,154, 46,0},
		{10,219, 13,151, 47,0},
		{1,31, 5,66, 11,38, 48,0},
		{0,239, 7,172, 12,34, 49,0},
		{2,0, 10,75, 13,120, 50,0},
		{1,129, 5,229, 11,118, 51,0}
	};
	// TS 38.212 BG2, lifting-size set iLS=4 (Zc=72).
	private static final int[][] BG2_72 = {
		{0,3, 1,26, 2,53, 3,35, 6,115, 9,127, 10,0, 11,0},
		{0,19, 3,94, 4,104, 5,66, 6,84, 7,98, 8,69, 9,50, 11,0, 12,0},
		{0,95, 1,106, 3,92, 4,110, 8,111, 10,1, 12,0, 13,0},
		{1,120, 2,121, 4,22, 5,4, 6,73, 7,49, 8,128, 9,79, 10,0, 13,0},
		{0,42, 1,24, 11,51, 14,0},
		{0,40, 1,140, 5,84, 7,137, 11,71, 15,0},
		{0,109, 5,87, 7,107, 9,133, 11,139, 16,0},
		{1,97, 5,135, 7,35, 11,108, 13,65, 17,0},
		{0,70, 1,69, 12,88, 18,0},
		{1,97, 8,40, 10,24, 11,49, 19,0},
		{0,46, 1,41, 6,101, 7,96, 20,0},
		{0,28, 7,30, 9,116, 13,64, 21,0},
		{1,33, 3,122, 11,131, 22,0},
		{0,76, 1,37, 8,62, 13,47, 23,0},
		{1,143, 6,51, 11,130, 13,97, 24,0},
		{0,139, 10,96, 11,128, 25,0},
		{1,48, 9,9, 11,28, 12,8, 26,0},
		{1,120, 5,43, 11,65, 12,42, 27,0},
		{0,17, 6,106, 7,142, 28,0},
		{0,79, 1,28, 10,41, 29,0},
		{1,2, 4,103, 11,78, 30,0},
		{0,91, 8,75, 13,81, 31,0},
		{1,54, 2,132, 32,0},
		{0,68, 3,115, 5,56, 33,0},
		{1,30, 2,42, 9,101, 34,0},
		{0,128, 5,63, 35,0},
		{2,142, 7,28, 12,100, 13,133, 36,0},
		{0,13, 6,10, 37,0},
		{1,106, 2,77, 5,43, 38,0},
		{0,133, 4,25, 39,0},
		{2,87, 5,56, 7,104, 9,70, 40,0},
		{1,80, 13,139, 41,0},
		{0,32, 5,89, 12,71, 42,0},
		{2,135, 7,6, 10,2, 43,0},
		{0,37, 12,25, 13,114, 44,0},
		{1,60, 5,137, 11,93, 45,0},
		{0,121, 2,129, 7,26, 46,0},
		{10,97, 13,56, 47,0},
		{1,1, 5,70, 11,1, 48,0},
		{0,119, 7,32, 12,142, 49,0},
		{2,6, 10,73, 13,102, 50,0},
		{1,48, 5,47, 11,19, 51,0}
	};
	private static final int[][] BG2_80 = {
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
	// TS 38.212 BG2, lifting-size set iLS=1 (Zc=96).
	private static final int[][] BG2_96 = {
		{0,78, 1,1, 2,70, 3,66, 6,71, 9,76, 10,0, 11,0},
		{0,27, 3,36, 4,48, 5,92, 6,31, 7,91, 8,89, 9,3, 11,0, 12,0},
		{0,25, 1,18, 3,21, 4,14, 8,18, 10,1, 12,0, 13,0},
		{1,40, 2,79, 4,17, 5,72, 6,27, 7,22, 8,28, 9,90, 10,0, 13,0},
		{0,72, 1,74, 11,29, 14,0},
		{0,10, 1,44, 5,25, 7,80, 11,48, 15,0},
		{0,33, 5,92, 7,4, 9,49, 11,88, 16,0},
		{1,80, 5,90, 7,16, 11,6, 13,47, 17,0},
		{0,22, 1,70, 12,56, 18,0},
		{1,28, 8,36, 10,89, 11,82, 19,0},
		{0,59, 1,8, 6,22, 7,52, 20,0},
		{0,32, 7,92, 9,78, 13,58, 21,0},
		{1,39, 3,93, 11,11, 22,0},
		{0,49, 1,29, 8,35, 13,70, 23,0},
		{1,19, 6,22, 11,21, 13,67, 24,0},
		{0,68, 10,63, 11,81, 25,0},
		{1,87, 9,81, 11,39, 12,64, 26,0},
		{1,62, 5,23, 11,9, 12,6, 27,0},
		{0,90, 6,6, 7,46, 28,0},
		{0,58, 1,42, 10,60, 29,0},
		{1,76, 4,61, 11,57, 30,0},
		{0,61, 8,79, 13,67, 31,0},
		{1,20, 2,52, 32,0},
		{0,10, 3,86, 5,95, 33,0},
		{1,86, 2,57, 9,64, 34,0},
		{0,45, 5,21, 35,0},
		{2,67, 7,41, 12,55, 13,85, 36,0},
		{0,7, 6,50, 37,0},
		{1,70, 2,15, 5,72, 38,0},
		{0,14, 4,17, 39,0},
		{2,24, 5,58, 7,52, 9,56, 40,0},
		{1,3, 13,74, 41,0},
		{0,84, 5,8, 12,17, 42,0},
		{2,69, 7,83, 10,28, 43,0},
		{0,77, 12,81, 13,12, 44,0},
		{1,77, 5,88, 11,18, 45,0},
		{0,25, 2,55, 7,74, 46,0},
		{10,37, 13,31, 47,0},
		{1,84, 5,55, 11,94, 48,0},
		{0,93, 7,36, 12,57, 49,0},
		{2,7, 10,11, 13,67, 50,0},
		{1,51, 5,7, 11,60, 51,0}
	};
	// TS 38.212 BG2, lifting-size set iLS=3 (Zc=112).
	private static final int[][] BG2_112 = {
		{0,72, 1,110, 2,23, 3,181, 6,95, 9,8, 10,1, 11,0},
		{0,53, 3,156, 4,115, 5,156, 6,115, 7,200, 8,29, 9,31, 11,0, 12,0},
		{0,152, 1,131, 3,46, 4,191, 8,91, 10,0, 12,0, 13,0},
		{1,185, 2,6, 4,36, 5,124, 6,124, 7,110, 8,156, 9,133, 10,1, 13,0},
		{0,200, 1,16, 11,101, 14,0},
		{0,185, 1,138, 5,170, 7,219, 11,193, 15,0},
		{0,123, 5,55, 7,31, 9,222, 11,209, 16,0},
		{1,103, 5,13, 7,105, 11,150, 13,181, 17,0},
		{0,147, 1,43, 12,152, 18,0},
		{1,2, 8,30, 10,184, 11,83, 19,0},
		{0,174, 1,150, 6,8, 7,56, 20,0},
		{0,99, 7,138, 9,110, 13,99, 21,0},
		{1,46, 3,217, 11,109, 22,0},
		{0,37, 1,113, 8,143, 13,140, 23,0},
		{1,36, 6,95, 11,40, 13,116, 24,0},
		{0,116, 10,200, 11,110, 25,0},
		{1,75, 9,158, 11,134, 12,97, 26,0},
		{1,48, 5,132, 11,206, 12,2, 27,0},
		{0,68, 6,16, 7,156, 28,0},
		{0,35, 1,138, 10,86, 29,0},
		{1,6, 4,20, 11,141, 30,0},
		{0,80, 8,43, 13,81, 31,0},
		{1,49, 2,1, 32,0},
		{0,156, 3,54, 5,134, 33,0},
		{1,153, 2,88, 9,63, 34,0},
		{0,211, 5,94, 35,0},
		{2,90, 7,6, 12,221, 13,6, 36,0},
		{0,27, 6,118, 37,0},
		{1,216, 2,212, 5,193, 38,0},
		{0,108, 4,61, 39,0},
		{2,106, 5,44, 7,185, 9,176, 40,0},
		{1,147, 13,182, 41,0},
		{0,108, 5,21, 12,110, 42,0},
		{2,71, 7,12, 10,109, 43,0},
		{0,29, 12,201, 13,69, 44,0},
		{1,91, 5,165, 11,55, 45,0},
		{0,1, 2,175, 7,83, 46,0},
		{10,40, 13,12, 47,0},
		{1,37, 5,97, 11,46, 48,0},
		{0,106, 7,181, 12,154, 49,0},
		{2,98, 10,35, 13,36, 50,0},
		{1,120, 5,101, 11,81, 51,0}
	};

	private NrLdpcDecoder() {}

	static boolean supportsTransportBlock(int transportBlockBits) {
		return transportBlockBits == 120 || transportBlockBits == 176
				|| transportBlockBits == 640
				|| transportBlockBits == 736 || transportBlockBits == 768
				|| transportBlockBits == 928 || transportBlockBits == 1032;
	}

	static Result decode(double[] rateMatched, int transportBlockBits, int rv) {
		return decode(rateMatched, transportBlockBits, rv, MAX_ITERATIONS);
	}

	static Result decodeForScore(double[] rateMatched, int transportBlockBits, int rv) {
		return decode(rateMatched, transportBlockBits, rv, Math.min(8, MAX_ITERATIONS));
	}

	private static Result decode(double[] rateMatched, int transportBlockBits, int rv,
			int maxIterations) {
		if (!supportsTransportBlock(transportBlockBits) || rv < 0 || rv > 3
				|| rateMatched.length % 2 != 0) return Result.EMPTY;
		SoftBuffer buffer = new SoftBuffer(transportBlockBits);
		buffer.add(rateMatched, rv);
		return buffer.decode(maxIterations);
	}

	static SoftBuffer softBuffer(int transportBlockBits) {
		return supportsTransportBlock(transportBlockBits) ? new SoftBuffer(transportBlockBits) : null;
	}

	private static Result decodeRecovered(double[] circular, int transportBlockBits,
			int usefulLength, double repetitionAgreement, int maxIterations) {
		int b = transportBlockBits + 16;
		int z = liftingSize(transportBlockBits);
		int k = 10 * z, n = 50 * z;
		int[][] bg2 = baseGraph(transportBlockBits);
		int filler = k - b;
		int firstFiller = k - 2 * z - filler;
		int endFiller = k - 2 * z;
		double[] recovered = circular.clone();
		for (int i = firstFiller; i < endFiller; i++) recovered[i] = 1e6;

		double[] soft = new double[52 * z];
		System.arraycopy(recovered, 0, soft, 2 * z, recovered.length);
		double[][][] checkMessages = new double[bg2.length][][];
		for (int row = 0; row < bg2.length; row++)
			checkMessages[row] = new double[bg2[row].length / 2][z];
		// TS 38.212 rate recovery leaves the untransmitted extension region unknown.
		// Its degree-one parity nodes cannot constrain the information bits, so process
		// only the layers covered by useful LLRs (the same rule used by srsRAN).
		usefulLength = Math.max(usefulLength, 12 * z);
		int activeRows = Math.min(bg2.length, (usefulLength + z - 1) / z - 8);

		int iterations = 0;
		for (; iterations < maxIterations; iterations++) {
			for (int row = 0; row < activeRows; row++)
				updateLayer(soft, checkMessages[row], bg2[row], z);
			int[] candidate = hardBits(soft, b);
			int syndrome = syndromeErrors(soft, bg2, z, activeRows);
			if (crc16Ok(candidate) && syndrome == 0)
				return result(candidate, transportBlockBits, iterations + 1, repetitionAgreement);
			if (syndrome == 0) break;
		}
		int[] bits = hardBits(soft, b);
		int syndrome = syndromeErrors(soft, bg2, z, activeRows);
		boolean valid = crc16Ok(bits) && syndrome == 0;
		byte[] payload = new byte[transportBlockBits / 8];
		if (valid) for (int i = 0; i < transportBlockBits; i++)
			payload[i / 8] |= bits[i] << (7 - (i & 7));
		return new Result(valid, Math.min(iterations + 1, maxIterations), payload,
				repetitionAgreement, syndrome);
	}

	static final class SoftBuffer {
		final int transportBlockBits;
		private final int z, k, n, firstFiller, endFiller;
		private final double[] circular;
		private int usefulLength, count;
		private double agreementSum, lastOverlapAgreement;
		private int lastOverlapBits;

		private SoftBuffer(int transportBlockBits) {
			this.transportBlockBits = transportBlockBits;
			z = liftingSize(transportBlockBits);
			k = 10 * z;
			n = 50 * z;
			int filler = k - transportBlockBits - 16;
			firstFiller = k - 2 * z - filler;
			endFiller = k - 2 * z;
			circular = new double[n];
		}

		boolean add(double[] rateMatched, int rv) {
			if (rateMatched == null || rateMatched.length == 0 || (rateMatched.length & 1) != 0
					|| rv < 0 || rv > 3) return false;
			int columns = rateMatched.length / 2;
			double[] deinterleaved = new double[rateMatched.length];
			for (int j = 0; j < columns; j++) for (int i = 0; i < 2; i++)
				deinterleaved[i * columns + j] = rateMatched[j * 2 + i];
			int k0 = z * new int[] { 0, 13, 25, 43 }[rv];
			int at = 0, walked = 0;
			double overlapAgree = 0, overlapTotal = 0;
			int overlapBits = 0;
			while (at < deinterleaved.length) {
				int index = (k0 + walked++) % n;
				if (index >= firstFiller && index < endFiller) continue;
				double value = deinterleaved[at++];
				if (circular[index] != 0 && value != 0) {
					double weight = Math.min(Math.abs(circular[index]), Math.abs(value));
					overlapTotal += weight;
					if (circular[index] * value > 0) overlapAgree += weight;
					overlapBits++;
				}
				circular[index] += value;
			}
			lastOverlapAgreement = overlapTotal == 0 ? 0 : overlapAgree / overlapTotal;
			lastOverlapBits = overlapBits;
			usefulLength = Math.max(usefulLength, Math.min(n, k0 + deinterleaved.length));
			agreementSum += repetitionAgreement(deinterleaved, k0, n, firstFiller, endFiller);
			count++;
			return true;
		}

		int count() {
			return count;
		}

		double lastOverlapAgreement() {
			return lastOverlapAgreement;
		}

		int lastOverlapBits() {
			return lastOverlapBits;
		}

		Result decode() {
			return decode(MAX_ITERATIONS);
		}

		private Result decode(int maxIterations) {
			if (count == 0) return Result.EMPTY;
			return decodeRecovered(circular, transportBlockBits, usefulLength,
					agreementSum / count, maxIterations);
		}
	}

	static double repetitionAgreement(double[] rateMatched, int transportBlockBits, int rv) {
		if (!supportsTransportBlock(transportBlockBits) || rv < 0 || rv > 3
				|| rateMatched.length % 2 != 0) return 0;
		int z = liftingSize(transportBlockBits);
		int columns = rateMatched.length / 2;
		double[] deinterleaved = new double[rateMatched.length];
		for (int j = 0; j < columns; j++) for (int i = 0; i < 2; i++)
			deinterleaved[i * columns + j] = rateMatched[j * 2 + i];
		int fillerStart = transportBlockBits + 16 - 2 * z;
		return repetitionAgreement(deinterleaved, z * new int[] { 0, 13, 25, 43 }[rv],
				50 * z, fillerStart, 8 * z);
	}

	private static int liftingSize(int transportBlockBits) {
		if (transportBlockBits == 120) return 24;
		if (transportBlockBits == 176) return 32;
		if (transportBlockBits == 640) return 72;
		if (transportBlockBits == 736 || transportBlockBits == 768) return 80;
		if (transportBlockBits == 928) return 96;
		return 112;
	}

	private static int[][] baseGraph(int transportBlockBits) {
		if (transportBlockBits == 120) return BG2_96;
		if (transportBlockBits == 176) return BG2_32;
		if (transportBlockBits == 640) return BG2_72;
		if (transportBlockBits == 736 || transportBlockBits == 768) return BG2_80;
		if (transportBlockBits == 928) return BG2_96;
		return BG2_112;
	}

	private static double repetitionAgreement(double[] deinterleaved, int k0, int n, int firstFiller,
			int endFiller) {
		double[] sum = new double[n], magnitude = new double[n];
		int[] count = new int[n];
		int at = 0, walked = 0;
		while (at < deinterleaved.length) {
			int index = (k0 + walked++) % n;
			if (index >= firstFiller && index < endFiller) continue;
			double value = deinterleaved[at++];
			sum[index] += value;
			magnitude[index] += Math.abs(value);
			count[index]++;
		}
		double coherent = 0, total = 0;
		for (int i = 0; i < n; i++) if (count[i] > 1) {
			coherent += Math.abs(sum[i]);
			total += magnitude[i];
		}
		return total == 0 ? 0 : coherent / total;
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
		return new Result(true, iterations, payload, repetitionAgreement, 0);
	}

	private static void updateLayer(double[] soft, double[][] oldMessages, int[] row, int zc) {
		int edges = row.length / 2;
		double[][] extrinsic = new double[edges][zc];
		for (int edge = 0; edge < edges; edge++) {
			int column = row[2 * edge], shift = row[2 * edge + 1];
			for (int z = 0; z < zc; z++) {
				int variable = column * zc + (z + shift) % zc;
				extrinsic[edge][z] = soft[variable] - oldMessages[edge][z];
			}
		}
		for (int z = 0; z < zc; z++) {
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
				double message = MIN_SUM_SCALE * sign * (edge == minimumEdge ? min2 : min1);
				oldMessages[edge][z] = message;
				int variable = row[2 * edge] * zc + (z + row[2 * edge + 1]) % zc;
				soft[variable] = value + message;
			}
		}
	}

	private static int syndromeErrors(double[] soft, int[][] bg2, int zc, int rows) {
		int errors = 0;
		for (int rowIndex = 0; rowIndex < rows; rowIndex++) for (int z = 0; z < zc; z++) {
			int[] row = bg2[rowIndex];
			int parity = 0;
			for (int edge = 0; edge < row.length / 2; edge++) {
				int variable = row[2 * edge] * zc + (z + row[2 * edge + 1]) % zc;
				if (soft[variable] < 0) parity ^= 1;
			}
			if (parity != 0) errors++;
		}
		return errors;
	}

	private static boolean crc16Ok(int[] bits) {
		int crc = 0;
		for (int bit : bits) crc = crcStep(crc, bit);
		return crc == 0;
	}

	private static int crcStep(int crc, int bit) {
		int feedback = ((crc >>> 15) & 1) ^ bit;
		crc = (crc << 1) & 0xffff;
		return feedback == 0 ? crc : crc ^ 0x1021;
	}

	static final class Result {
		static final Result EMPTY = new Result(false, 0, new byte[0], 0, Integer.MAX_VALUE);
		final boolean crcOk;
		final int iterations, syndromeErrors;
		final byte[] payload;
		final double repetitionAgreement;
		Result(boolean crcOk, int iterations, byte[] payload, double repetitionAgreement) {
			this(crcOk, iterations, payload, repetitionAgreement, Integer.MAX_VALUE);
		}
		Result(boolean crcOk, int iterations, byte[] payload, double repetitionAgreement,
				int syndromeErrors) {
			this.crcOk = crcOk;
			this.iterations = iterations;
			this.payload = payload;
			this.repetitionAgreement = repetitionAgreement;
			this.syndromeErrors = syndromeErrors;
		}
	}
}
