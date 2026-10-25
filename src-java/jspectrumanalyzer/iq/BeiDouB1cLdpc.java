package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** B-CNAV1 non-binary LDPC decoder over GF(64), following the B1C ICD annex. */
final class BeiDouB1cLdpc {
	private static final int Q = 64, MAX_ITERATIONS = 15;
	private static final int[] GF_EXP = {
		1,2,4,8,16,32,3,6,12,24,48,35,5,10,20,40,19,38,15,30,60,59,53,
		41,17,34,7,14,28,56,51,37,9,18,36,11,22,44,27,54,47,29,58,55,
		45,25,50,39,13,26,52,43,21,42,23,46,31,62,63,61,57,49,33,1 };
	private static final int[] GF_LOG = {
		0,0,1,6,2,12,7,26,3,32,13,35,8,48,27,18,4,24,33,16,14,52,36,
		54,9,45,49,38,28,41,19,56,5,62,25,11,34,31,17,47,15,23,53,51,
		37,44,55,40,10,61,46,30,50,22,39,43,29,60,42,21,20,59,57,58 };

	private static final int[] INDEX_200 = {
		11,62,102,150,9,60,100,148,0,51,142,197,22,80,116,154,4,90,131,177,47,95,138,191,
		51,79,146,195,44,75,142,190,13,57,135,198,24,65,120,173,6,88,129,179,7,89,130,176,
		6,58,106,158,8,60,108,160,44,92,139,188,4,56,104,156,10,61,101,149,39,87,123,168,
		15,67,105,167,50,78,145,194,17,98,151,187,46,94,137,190,14,66,104,166,7,59,107,159,
		21,83,119,153,31,87,114,167,2,49,140,199,12,64,106,164,40,53,132,159,19,96,149,185,
		16,68,112,168,14,58,132,199,34,69,125,162,23,75,119,175,42,96,144,192,8,63,103,151,
		23,81,117,155,24,93,111,182,20,72,116,172,17,69,113,169,34,82,130,182,1,53,101,153,
		46,73,140,188,13,65,107,165,2,54,102,154,18,70,114,170,26,67,122,175,29,77,125,177,
		36,84,120,169,25,94,108,183,39,89,137,185,21,73,117,173,28,76,124,176,36,90,138,186,
		33,68,124,161,12,56,134,197,29,85,112,165,45,93,136,189,27,64,123,172,28,84,115,164,
		25,66,121,174,37,85,121,170,3,50,141,196,48,76,147,192,35,70,126,163,32,80,128,180,
		0,52,100,152,43,52,135,158,35,83,131,183,10,62,110,162,19,71,115,171,15,59,133,196,
		33,81,129,181,41,54,133,156,20,82,118,152,38,86,122,171,30,78,126,178,9,61,109,161,
		26,95,109,180,45,72,143,191,1,48,143,198,40,98,146,194,18,99,148,184,5,57,105,157,
		41,99,147,195,31,79,127,179,3,55,103,155,22,74,118,174,37,91,139,187,5,91,128,178,
		30,86,113,166,43,97,145,193,16,97,150,186,11,63,111,163,32,71,127,160,42,55,134,157,
		38,88,136,184,47,74,141,189,49,77,144,193,27,92,110,181 };
	private static final int[] ELEMENT_200 = {
		35,13,51,60,1,44,53,24,1,45,15,6,45,15,6,1,1,44,53,24,1,45,15,6,35,46,56,15,
		6,1,45,15,15,6,1,45,44,53,24,1,24,1,44,30,1,45,15,6,30,24,1,44,24,1,44,30,
		45,15,6,1,17,38,49,11,24,1,44,30,24,1,44,53,24,1,44,53,30,24,1,44,33,42,14,24,
		33,42,14,24,45,15,6,1,1,45,15,6,30,24,1,44,24,1,44,53,1,44,30,24,57,25,9,41,
		1,45,15,6,1,45,15,6,42,36,12,57,6,1,45,15,24,1,44,53,24,1,44,30,1,45,15,6,
		1,45,15,6,44,53,24,1,30,24,1,44,1,44,30,24,53,24,1,44,1,44,53,24,27,28,30,31,
		53,24,1,44,24,1,44,30,45,15,6,1,30,24,1,44,1,45,15,6,26,22,14,2,35,13,18,60,
		45,15,6,1,30,1,44,7,6,1,45,15,6,1,45,15,53,24,1,44,24,1,44,53,30,24,1,44,
		1,44,30,24,44,53,24,1,53,24,1,44,44,30,24,1,30,24,1,44,1,44,30,24,1,44,30,24,
		41,16,29,51,1,44,30,24,38,23,22,7,44,53,24,1,1,45,15,6,30,24,1,44,53,24,1,44,
		6,1,45,15,24,1,44,53,35,46,56,15,5,33,42,14,54,7,38,23,1,45,15,6,44,30,24,1,
		6,1,45,15,53,24,1,44,44,53,24,1,1,44,53,24,1,44,30,24,44,30,24,1,1,44,53,24,
		45,15,6,1,6,1,45,15,1,44,53,24,42,47,37,32,51,60,35,13,29,28,30,31,6,1,45,15,
		24,1,44,53,44,53,24,1,44,30,24,1,38,49,11,17,44,30,24,1,24,1,44,30,24,1,44,30,
		1,44,53,24,53,24,1,44 };
	private static final int[] INDEX_88 = {
		14,35,56,70,11,29,55,73,13,39,53,69,15,34,57,71,1,27,45,54,23,41,63,87,2,20,46,68,
		6,24,50,61,2,26,61,79,9,33,59,77,4,30,48,74,22,42,59,76,12,38,52,68,23,43,58,77,
		19,21,63,64,11,25,65,82,17,39,44,75,9,35,49,72,19,29,66,84,13,36,56,82,17,43,67,81,
		22,40,62,86,3,21,47,69,10,24,64,83,0,37,70,86,5,31,49,75,4,40,53,84,5,41,52,85,
		18,28,67,85,0,26,44,55,10,28,54,72,7,30,50,81,1,36,71,87,16,38,45,74,8,34,48,73,
		8,32,58,76,12,37,57,83,6,31,51,80,15,33,47,79,16,42,66,80,7,25,51,60,3,27,60,78,
		14,32,46,78,18,20,62,65 };
	private static final int[] ELEMENT_88 = {
		30,24,1,44,24,1,44,30,40,32,61,18,53,24,1,44,51,60,35,13,18,15,32,61,15,6,1,45,
		30,24,1,44,6,1,45,15,45,15,6,1,1,45,15,6,1,44,53,24,24,1,44,53,44,30,24,1,
		34,33,45,36,55,9,34,3,1,44,53,24,61,47,20,8,53,24,1,44,15,6,1,45,13,18,60,35,
		45,15,6,1,24,1,44,53,37,32,52,47,44,53,24,1,39,36,34,33,44,35,31,50,12,25,36,14,
		15,35,46,56,53,24,1,44,1,44,53,24,24,1,44,30,44,30,24,1,15,6,1,45,30,24,1,44,
		2,50,22,14,33,42,14,5,34,3,55,9,44,35,61,50,15,6,1,45,45,15,6,1,1,44,30,24,
		6,1,45,15,1,44,53,24 };

	private static final Graph LONG = new Graph(100, 200, INDEX_200, ELEMENT_200);
	private static final Graph SHORT = new Graph(44, 88, INDEX_88, ELEMENT_88);

	static int[] decodeLong(double[] llr) { return decode(llr, LONG); }
	static int[] decodeShort(double[] llr) { return decode(llr, SHORT); }

	private static int[] decode(double[] bitLlr, Graph graph) {
		if (bitLlr == null || bitLlr.length < graph.variables * 6) return null;
		int n = graph.variables, edges = graph.checks * 4;
		double[][] channel = new double[n][Q]; int[] hard = new int[n];
		for (int var = 0; var < n; var++) {
			int symbol = 0; double[] magnitude = new double[6];
			for (int bit = 0; bit < 6; bit++) {
				double value = bitLlr[var * 6 + bit]; if (!Double.isFinite(value)) return null;
				symbol = (symbol << 1) | (value >= 0d ? 1 : 0); magnitude[bit] = Math.abs(value);
			}
			hard[var] = symbol;
			for (int candidate = 0; candidate < Q; candidate++) {
				int difference = candidate ^ symbol; double metric = 0d;
				for (int bit = 0; bit < 6; bit++) if ((difference & (1 << (5 - bit))) != 0) metric += magnitude[bit];
				channel[var][candidate] = Math.exp(-metric);
			}
			normalize(channel[var]);
		}
		if (syndromeZero(graph, hard)) return toBits(hard);

		double[][] variableToCheck = new double[edges][Q], checkToVariable = new double[edges][Q];
		for (int edge = 0; edge < edges; edge++) {
			int check = edge / 4, slot = edge & 3, var = graph.checkVar[check][slot], h = graph.checkH[check][slot];
			for (int value = 0; value < Q; value++) variableToCheck[edge][multiply(h, value)] = channel[var][value];
		}
		double[][] transformed = new double[edges][Q];
		for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
			for (int edge = 0; edge < edges; edge++) { System.arraycopy(variableToCheck[edge], 0, transformed[edge], 0, Q); walsh(transformed[edge]); }
			for (int check = 0; check < graph.checks; check++) for (int target = 0; target < 4; target++) {
				double[] message = checkToVariable[check * 4 + target]; Arrays.fill(message, 1d);
				for (int slot = 0; slot < 4; slot++) if (slot != target) {
					double[] source = transformed[check * 4 + slot]; for (int value = 0; value < Q; value++) message[value] *= source[value];
				}
				walsh(message); for (int value = 0; value < Q; value++) message[value] /= Q; normalize(message);
			}
			for (int var = 0; var < n; var++) {
				int[] adjacent = graph.varEdges[var]; double[] posterior = channel[var].clone();
				for (int edge : adjacent) {
					int h = graph.checkH[edge / 4][edge & 3]; double[] incoming = checkToVariable[edge];
					for (int value = 0; value < Q; value++) posterior[value] *= incoming[multiply(h, value)];
				}
				hard[var] = maximumIndex(posterior);
				for (int target : adjacent) {
					double[] outgoing = channel[var].clone();
					for (int edge : adjacent) if (edge != target) {
						int h = graph.checkH[edge / 4][edge & 3]; double[] incoming = checkToVariable[edge];
						for (int value = 0; value < Q; value++) outgoing[value] *= incoming[multiply(h, value)];
					}
					normalize(outgoing); int h = graph.checkH[target / 4][target & 3];
					for (int value = 0; value < Q; value++) variableToCheck[target][multiply(h, value)] = outgoing[value];
				}
			}
			if (syndromeZero(graph, hard)) return toBits(hard);
		}
		return null;
	}

	private static int[] toBits(int[] symbols) {
		int[] bits = new int[symbols.length * 6];
		for (int index = 0; index < symbols.length; index++) for (int bit = 0; bit < 6; bit++)
			bits[index * 6 + bit] = (symbols[index] >>> (5 - bit)) & 1;
		return bits;
	}
	private static boolean syndromeZero(Graph graph, int[] word) {
		for (int check = 0; check < graph.checks; check++) {
			int syndrome = 0; for (int slot = 0; slot < 4; slot++) syndrome ^= multiply(graph.checkH[check][slot], word[graph.checkVar[check][slot]]);
			if (syndrome != 0) return false;
		}
		return true;
	}
	private static int multiply(int left, int right) {
		if (left == 0 || right == 0) return 0; return GF_EXP[(GF_LOG[left] + GF_LOG[right]) % 63];
	}
	private static int inverse(int value) { return value == 0 ? 0 : GF_EXP[(63 - GF_LOG[value]) % 63]; }
	private static int maximumIndex(double[] values) {
		int best = 0; for (int index = 1; index < values.length; index++) if (values[index] > values[best]) best = index; return best;
	}
	private static void walsh(double[] values) {
		for (int stride = 1; stride < Q; stride <<= 1) for (int start = 0; start < Q; start += stride << 1)
			for (int index = start; index < start + stride; index++) {
				double left = values[index], right = values[index + stride]; values[index] = left + right; values[index + stride] = left - right;
			}
	}
	private static void normalize(double[] values) {
		double sum = 0d; for (int index = 0; index < values.length; index++) { values[index] = Math.max(1e-15d, values[index]); sum += values[index]; }
		for (int index = 0; index < values.length; index++) values[index] /= sum;
	}

	private static final class Graph {
		final int checks, variables; final int[][] checkVar, checkH, varEdges;
		Graph(int checks, int variables, int[] index, int[] element) {
			this.checks = checks; this.variables = variables; checkVar = new int[checks][4]; checkH = new int[checks][4];
			int baseRows = checks / 4;
			for (int band = 0; band < 4; band++) for (int row = 0; row < baseRows; row++) {
				int source = row * 16 + band * 4, target = band * baseRows + row;
				for (int slot = 0; slot < 4; slot++) { checkVar[target][slot] = index[source + slot]; checkH[target][slot] = element[source + slot]; }
			}
			List<List<Integer>> adjacent = new ArrayList<List<Integer>>(variables);
			for (int var = 0; var < variables; var++) adjacent.add(new ArrayList<Integer>());
			for (int check = 0; check < checks; check++) for (int slot = 0; slot < 4; slot++) adjacent.get(checkVar[check][slot]).add(Integer.valueOf(check * 4 + slot));
			varEdges = new int[variables][];
			for (int var = 0; var < variables; var++) { varEdges[var] = new int[adjacent.get(var).size()]; for (int i = 0; i < varEdges[var].length; i++) varEdges[var][i] = adjacent.get(var).get(i).intValue(); }
		}
	}

	private BeiDouB1cLdpc() { }
}
