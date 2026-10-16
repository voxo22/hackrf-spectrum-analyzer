package jspectrumanalyzer.iq;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Random;

/** Deterministic BG2 encode/rate-match/decode checks for the supported SIB1 profiles. */
final class NrLdpcDecoderTest {
	private NrLdpcDecoderTest() {}

	public static void main(String[] args) throws Exception {
		checkProfile(120, 24, 192, 0, "BG2_96", false);
		checkProfile(176, 32, 1536, 0, "BG2_32", false);
		checkProfile(640, 72, 5280, 0, "BG2_72", false);
		checkProfile(768, 80, 6480, 0, "BG2_80", false);
		checkProfile(808, 88, 6720, 0, "BG2_88", false);
		checkProfile(928, 96, 3024, 3, "BG2_96", false);
		checkProfile(1032, 112, 1536, 0, "BG2_112", false);
		checkProfile(1032, 112, 8640, 0, "BG2_112", true);
	}

	private static void checkProfile(int transportBlockBits, int z, int e, int rv, String graphName,
			boolean noisyCheck) throws Exception {
		int b = transportBlockBits + 16;
		int[] block = new int[10 * z];
		Random random = new Random(0x5a17b1L + transportBlockBits);
		for (int i = 0; i < transportBlockBits; i++) block[i] = random.nextBoolean() ? 1 : 0;
		appendCrc16(block, transportBlockBits);
		if (!crc16Ok(block, b)) throw new AssertionError("Test CRC construction failed");

		int[][] graph = graph(graphName);
		int[] codeword = encodeBg2(block, graph, z,
				!"BG2_112".equals(graphName));
		double[] llrs = rateMatch(codeword, b - 2 * z, 8 * z, e,
				new int[] { 0, 13, 25, 43 }[rv] * z);
		NrLdpcDecoder.Result decoded = NrLdpcDecoder.decode(llrs, transportBlockBits, rv);
		if (!decoded.crcOk) throw new AssertionError("LDPC CRC failed after " + decoded.iterations + " iterations");
		byte[] expected = bytes(block, transportBlockBits);
		if (!Arrays.equals(expected, decoded.payload)) throw new AssertionError("Decoded payload differs");
		System.out.println("BG2 Zc=" + z + " rate-match/decode OK in "
				+ decoded.iterations + " iteration(s)");

		if (!noisyCheck) return;
		double[] noisy = llrs.clone();
		Random noise = new Random(0x38_212L);
		for (int i = 0; i < noisy.length; i++) if (noise.nextDouble() < 0.03) noisy[i] = -noisy[i];
		NrLdpcDecoder.Result corrected = NrLdpcDecoder.decode(noisy, transportBlockBits, rv);
		if (!corrected.crcOk) throw new AssertionError("LDPC failed to correct 3% hard errors");
		System.out.println("BG2 Zc=" + z + " corrected 3% hard errors in "
				+ corrected.iterations + " iteration(s)");
	}

	private static int[][] graph(String name) throws Exception {
		Field field = NrLdpcDecoder.class.getDeclaredField(name);
		field.setAccessible(true);
		return (int[][])field.get(null);
	}

	private static int[] encodeBg2(int[] input, int[][] graph, int z, boolean set1) {
		int[][] aux = new int[42][z];
		for (int row = 0; row < graph.length; row++) {
			for (int edge = 0; edge < graph[row].length / 2; edge++) {
				int column = graph[row][2 * edge], shift = graph[row][2 * edge + 1];
				if (column >= 10) continue;
				for (int i = 0; i < z; i++) aux[row][i] ^= input[column * z + (i + shift) % z];
			}
		}

		int[] output = new int[50 * z];
		System.arraycopy(input, 2 * z, output, 0, 8 * z);
		for (int i = 0; i < z; i++) {
			int source = set1 ? Math.floorMod(i - 1, z) : i;
			output[8 * z + i] = aux[0][source] ^ aux[1][source]
					^ aux[2][source] ^ aux[3][source];
		}
		for (int i = 0; i < z; i++) {
			int p0 = output[8 * z + (set1 ? i : (i + 1) % z)];
			output[9 * z + i] = aux[0][i] ^ p0;
			output[10 * z + i] = aux[1][i] ^ output[9 * z + i];
			output[11 * z + i] = aux[3][i] ^ p0;
		}
		for (int row = 4; row < graph.length; row++) {
			for (int i = 0; i < z; i++) {
				int value = aux[row][i];
				for (int edge = 0; edge < graph[row].length / 2; edge++) {
					int column = graph[row][2 * edge], shift = graph[row][2 * edge + 1];
					if (column >= 10 && column < 14)
						value ^= output[(column - 2) * z + (i + shift) % z];
				}
				output[(10 + row - 2) * z + i] = value;
			}
		}
		return output;
	}

	private static double[] rateMatch(int[] codeword, int firstFiller, int endFiller, int e, int k0) {
		int[] selected = new int[e];
		int at = 0, walked = 0;
		while (at < selected.length) {
			int index = (k0 + walked++) % codeword.length;
			if (index >= firstFiller && index < endFiller) continue;
			selected[at++] = codeword[index];
		}
		double[] interleaved = new double[e];
		int columns = e / 2;
		for (int j = 0; j < columns; j++) for (int i = 0; i < 2; i++)
			interleaved[2 * j + i] = selected[i * columns + j] == 0 ? 8d : -8d;
		return interleaved;
	}

	private static void appendCrc16(int[] bits, int payloadBits) {
		int crc = 0;
		for (int i = 0; i < payloadBits; i++) crc = crcStep(crc, bits[i]);
		for (int i = 0; i < 16; i++) bits[payloadBits + i] = (crc >>> (15 - i)) & 1;
	}

	private static int crcStep(int crc, int bit) {
		int feedback = ((crc >>> 15) & 1) ^ bit;
		crc = (crc << 1) & 0xffff;
		return feedback == 0 ? crc : crc ^ 0x1021;
	}

	private static boolean crc16Ok(int[] bits, int length) {
		int crc = 0;
		for (int i = 0; i < length; i++) crc = crcStep(crc, bits[i]);
		return crc == 0;
	}

	private static byte[] bytes(int[] bits, int length) {
		byte[] result = new byte[length / 8];
		for (int i = 0; i < length; i++) result[i / 8] |= bits[i] << (7 - (i & 7));
		return result;
	}
}
