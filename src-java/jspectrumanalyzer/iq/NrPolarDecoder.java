package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Small SC polar decoder for the downlink N=512 control-channel profiles used here. */
final class NrPolarDecoder {
	private static final int N = 512;
	private static final int[] SUBBLOCK_PATTERN = {
			0, 1, 2, 4, 3, 5, 6, 7, 8, 16, 9, 17, 10, 18, 11, 19,
			12, 20, 13, 21, 14, 22, 15, 23, 24, 25, 26, 28, 27, 29, 30, 31
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
	// TS 38.212 reliability order Q_0^N, least to most reliable, restricted to N=512.
	private static final int[] RELIABILITY = {
			0,1,2,4,8,16,32,3,5,64,9,6,17,10,18,128,12,33,65,20,256,34,24,36,
			7,129,66,11,40,68,130,19,13,48,14,72,257,21,132,35,258,26,80,37,25,22,
			136,260,264,38,96,67,41,144,28,69,42,49,74,272,160,288,192,70,44,131,
			81,50,73,15,320,133,52,23,134,384,76,137,82,56,27,97,39,259,84,138,
			145,261,29,43,98,88,140,30,146,71,262,265,161,45,100,51,148,46,75,266,
			273,104,162,53,193,152,77,164,268,274,54,83,57,112,135,78,289,194,85,
			276,58,168,139,99,86,60,280,89,290,196,141,101,147,176,142,321,31,200,
			90,292,322,263,149,102,105,304,296,163,92,47,267,385,324,208,386,150,
			153,165,106,55,328,113,154,79,269,108,224,166,195,270,275,291,59,169,
			114,277,156,87,197,116,170,61,281,278,177,293,388,91,198,172,120,201,
			336,62,282,143,103,178,294,93,202,323,392,297,107,180,151,209,284,94,
			204,298,400,352,325,155,210,305,300,109,184,115,167,225,326,306,157,
			329,110,117,212,171,330,226,387,308,216,416,271,279,158,337,118,332,
			389,173,121,199,179,228,338,312,390,174,393,283,122,448,353,203,63,340,
			394,181,295,285,232,124,205,182,286,299,354,211,401,185,396,344,240,206,
			95,327,402,356,307,301,417,213,186,404,227,418,302,360,111,331,214,309,
			188,449,217,408,229,159,420,310,333,119,339,218,368,230,391,313,450,334,
			233,175,123,341,220,314,424,395,355,287,183,234,125,342,316,241,345,452,
			397,403,207,432,357,187,236,126,242,398,346,456,358,405,303,244,189,361,
			215,348,419,406,464,362,409,219,311,421,410,231,248,369,190,364,335,480,
			315,221,370,422,425,451,235,412,343,372,317,222,426,453,237,433,347,243,
			454,318,376,428,238,359,457,399,434,349,245,458,363,127,191,407,436,465,
			246,350,460,249,411,365,440,374,423,466,250,371,481,413,366,468,429,252,
			373,482,427,414,223,472,455,377,435,319,484,430,488,239,378,459,437,380,
			461,496,351,467,438,251,462,442,441,469,247,367,253,375,444,470,483,415,
			485,473,474,254,379,431,489,486,476,439,490,463,381,497,492,443,382,498,
			445,471,500,446,475,487,504,255,477,491,478,383,493,499,502,494,501,447,
			505,506,479,508,495,503,507,509,510,511
	};

	private NrPolarDecoder() {}

	static int[] decode(double[] rateMatched, int k) {
		int e = rateMatched.length;
		if (k < 36 || k > 164 || e <= k || e > 8192) return null;
		double[] y = new double[N];
		if (e >= N) {
			for (int i = 0; i < e; i++) y[i % N] += rateMatched[i];
		} else if (16 * k <= 7 * e) {
			System.arraycopy(rateMatched, 0, y, N - e, e);
		} else {
			System.arraycopy(rateMatched, 0, y, 0, e);
			Arrays.fill(y, e, N, 1e9);
		}
		double[] mother = new double[N];
		for (int j = 0; j < N; j++) mother[subblockIndex(j)] = y[j];
		int[] informationSet = informationSet(k, e);
		boolean[] information = new boolean[N];
		for (int value : informationSet) information[value] = true;
		int[] decoded = new int[N];
		polarDecode(mother, 0, information, decoded);
		int[] interleaved = new int[k];
		for (int i = 0; i < k; i++) interleaved[i] = decoded[informationSet[i]];
		int[] message = new int[k];
		int at = 0;
		for (int value : INPUT_INTERLEAVER) if (value >= INPUT_INTERLEAVER.length - k) {
			message[value - (INPUT_INTERLEAVER.length - k)] = interleaved[at++];
		}
		return at == k ? message : null;
	}

	private static int[] informationSet(int k, int e) {
		boolean[] excluded = new boolean[N];
		int frozenByRate = Math.max(0, N - e);
		int threshold = -1;
		if (frozenByRate > 0) {
			if (16 * k <= 7 * e) {
				threshold = e >= 3 * N / 4 ? 3 * N / 4 - e / 2 - 1 : 9 * N / 16 - e / 4;
				for (int j = 0; j < frozenByRate; j++) excluded[subblockIndex(j)] = true;
			} else {
				for (int j = e; j < N; j++) excluded[subblockIndex(j)] = true;
			}
		}
		List<Integer> available = new ArrayList<Integer>(N);
		for (int value : RELIABILITY) if (value > threshold && !excluded[value]) available.add(value);
		List<Integer> selected = new ArrayList<Integer>(available.subList(available.size() - k, available.size()));
		Collections.sort(selected);
		int[] result = new int[k];
		for (int i = 0; i < k; i++) result[i] = selected.get(i);
		return result;
	}

	private static int subblockIndex(int j) {
		return SUBBLOCK_PATTERN[j / 16] * 16 + (j & 15);
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
}
