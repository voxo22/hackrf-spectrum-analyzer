package jspectrumanalyzer.iq;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Random;

/** Lightweight round-trip checks for the PDCCH polar profiles used by the tester. */
final class NrPolarDecoderTest {
	public static void main(String[] args) throws Exception {
		check(61, 432);
		check(63, 432);
		check(63, 864);
		System.out.println("NR polar round-trip OK");
	}

	private static void check(int k, int e) throws Exception {
		Method lengthMethod = NrPolarDecoder.class.getDeclaredMethod("codeLength", int.class, int.class);
		Method setMethod = NrPolarDecoder.class.getDeclaredMethod("informationSet",
				int.class, int.class, int.class);
		Method subblockMethod = NrPolarDecoder.class.getDeclaredMethod("subblockIndex", int.class, int.class);
		lengthMethod.setAccessible(true);
		setMethod.setAccessible(true);
		subblockMethod.setAccessible(true);
		int n = (Integer)lengthMethod.invoke(null, k, e);
		int[] informationSet = (int[])setMethod.invoke(null, k, e, n);
		int[] message = new int[k];
		for (int i = 0; i < k; i++) message[i] = ((i * 17 + i / 3) & 1);
		int[] interleaved = new int[k];
		int at = 0;
		for (int value : inputInterleaver()) if (value >= 164 - k)
			interleaved[at++] = message[value - (164 - k)];
		int[] encoded = new int[n];
		for (int i = 0; i < k; i++) encoded[informationSet[i]] = interleaved[i];
		for (int step = 1; step < n; step <<= 1)
			for (int base = 0; base < n; base += step << 1)
				for (int i = 0; i < step; i++) encoded[base + i] ^= encoded[base + step + i];
		int[] y = new int[n];
		for (int j = 0; j < n; j++) y[j] = encoded[(Integer)subblockMethod.invoke(null, j, n)];
		double[] llr = new double[e];
		for (int i = 0; i < e; i++) {
			int source = e >= n ? i % n : (16 * k <= 7 * e ? n - e + i : i);
			llr[i] = y[source] == 0 ? 10 : -10;
		}
		int[] decoded = NrPolarDecoder.decode(llr, k);
		if (!Arrays.equals(message, decoded))
			throw new AssertionError("polar round-trip failed for K=" + k + " E=" + e + " N=" + n);
		boolean listed = false;
		for (int[] candidate : NrPolarDecoder.decodeList(llr, k, 8))
			if (Arrays.equals(message, candidate)) listed = true;
		if (!listed)
			throw new AssertionError("polar SCL failed for K=" + k + " E=" + e + " N=" + n);
		if (k == 61 && e == 432) checkListConsistency(message, llr);
	}

	private static void checkListConsistency(int[] message, double[] clean) {
		Random random = new Random(0x5c1L);
		for (int trial = 0; trial < 20; trial++) {
			double[] noisy = clean.clone();
			for (int i = 0; i < noisy.length; i++) noisy[i] += random.nextGaussian() * 8.0;
			int[] sc = NrPolarDecoder.decode(noisy, message.length);
			int[][] list1 = NrPolarDecoder.decodeList(noisy, message.length, 1);
			if (list1.length != 1 || !Arrays.equals(sc, list1[0]))
				throw new AssertionError("polar SCL list-1 differs from SC");
		}
	}

	private static int[] inputInterleaver() throws Exception {
		java.lang.reflect.Field field = NrPolarDecoder.class.getDeclaredField("INPUT_INTERLEAVER");
		field.setAccessible(true);
		return (int[])field.get(null);
	}
}
