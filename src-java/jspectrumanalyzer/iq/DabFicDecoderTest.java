package jspectrumanalyzer.iq;

import java.nio.charset.Charset;
import java.util.Arrays;

/** Deterministic end-to-end Mode I FIC modem/decoder test; no RF fixture needed. */
public final class DabFicDecoderTest {
	private static final int N = 2048, GUARD = 504, CARRIERS = 1536;
	private static final int[] POLYS = {0155, 0117, 0123, 0155};
	private static final int[] PI15 = {
			1,1,1,0, 1,1,1,0, 1,1,1,0, 1,1,1,0,
			1,1,1,0, 1,1,1,0, 1,1,1,0, 1,1,0,0};
	private static final int[] PI16 = {
			1,1,1,0, 1,1,1,0, 1,1,1,0, 1,1,1,0,
			1,1,1,0, 1,1,1,0, 1,1,1,0, 1,1,1,0};
	private static final int[] PIX = {
			1,1,0,0, 1,1,0,0, 1,1,0,0,
			1,1,0,0, 1,1,0,0, 1,1,0,0};

	public static void main(String[] args) {
		byte[] iq = syntheticFrame();
		DabFicDecoder decoder = new DabFicDecoder();
		DabFicDecoder.Result result = decoder.analyze(iq, iq.length, 2_048_000);
		assertDecoded(result, "baseband", 12);
		DabFicDecoder.Result shifted = new DabFicDecoder().analyze(
				frequencyShift(iq, 5_250d, 2_048_000), iq.length, 2_048_000);
		assertDecoded(shifted, "5.25 kHz CFO", 3);
		byte[] wideRate = upsampleByTwo(iq);
		DabFicDecoder.Result resampled = new DabFicDecoder().analyze(
				wideRate, wideRate.length, 4_096_000);
		assertDecoded(resampled, "4.096 MS/s resampling", 3);
		System.out.println("DabFicDecoderTest OK: baseband 12/12 FIB CRC, CFO/resampling lock and metadata decoded");
	}

	private static void assertDecoded(DabFicDecoder.Result result, String scenario, int minimumValidFibs) {
		if (result.validFibs < minimumValidFibs)
			throw new AssertionError("Expected at least " + minimumValidFibs + " valid FIBs in " + scenario
					+ ", got " + result.validFibs);
		if (result.ensembleId != 0x5234) throw new AssertionError("Wrong ensemble ID " + result.ensembleId);
		if (!"SK".equals(result.countryCode) || result.ensembleEcc != 0xe2)
			throw new AssertionError("Wrong country metadata: " + result.countryCode + "/" + result.ensembleEcc);
		if (!"TEST MULTIPLEX".equals(result.ensembleLabel))
			throw new AssertionError("Wrong ensemble label: " + result.ensembleLabel);
		if (result.services.size() != 1) throw new AssertionError("Expected one service");
		DabFicDecoder.Service service = result.services.get(0);
		if (!"Rádio Šťastné".equals(service.label) || service.id != 0xc001 || service.bitrateKbps != 96)
			throw new AssertionError("Wrong service metadata: " + service.label + "/" + service.id
					+ "/" + service.bitrateKbps + " in " + scenario);
	}

	private static byte[] frequencyShift(byte[] source, double hz, int sampleRate) {
		byte[] shifted = new byte[source.length];
		for (int n = 0; n < source.length / 2; n++) {
			double phase = 2d * Math.PI * hz * n / sampleRate;
			double cos = Math.cos(phase), sin = Math.sin(phase);
			double i = source[n * 2], q = source[n * 2 + 1];
			shifted[n * 2] = (byte)Math.max(-127, Math.min(127, Math.round(i * cos - q * sin)));
			shifted[n * 2 + 1] = (byte)Math.max(-127, Math.min(127, Math.round(i * sin + q * cos)));
		}
		return shifted;
	}

	private static byte[] upsampleByTwo(byte[] source) {
		int sourceSamples = source.length / 2;
		byte[] result = new byte[source.length * 2];
		for (int n = 0; n < sourceSamples; n++) {
			int next = Math.min(sourceSamples - 1, n + 1);
			result[n * 4] = source[n * 2];
			result[n * 4 + 1] = source[n * 2 + 1];
			result[n * 4 + 2] = (byte)((source[n * 2] + source[next * 2]) / 2);
			result[n * 4 + 3] = (byte)((source[n * 2 + 1] + source[next * 2 + 1]) / 2);
		}
		return result;
	}

	private static byte[] syntheticFrame() {
		byte[][] fibs = {ensembleFib(), configurationFib(), serviceLabelFib()};
		boolean[] input = new boolean[768];
		for (int f = 0; f < 3; f++) for (int i = 0; i < 256; i++)
			input[f * 256 + i] = ((fibs[f][i >> 3] >>> (7 - (i & 7))) & 1) != 0;
		boolean[] dispersed = input.clone();
		scramble(dispersed);
		double[] fic = new double[9216];
		for (int block = 0; block < 4; block++) {
			boolean[] encoded = convolutionalEncode(dispersed);
			boolean[] punctured = puncture(encoded);
			for (int i = 0; i < punctured.length; i++) fic[block * 2304 + i] = punctured[i] ? 1 : -1;
		}
		boolean[] recovered = DabFicDecoder.decodeCodewordForTest(Arrays.copyOf(fic, 2304));
		if (!Arrays.equals(input, recovered)) throw new AssertionError("FIC codec round trip failed");

		int[] carrierMap = carrierMap();
		double[][] frequencyReal = new double[4][N], frequencyImag = new double[4][N];
		for (int logical = 0; logical < CARRIERS; logical++) {
			int carrier = carrierMap[logical], bin = carrier < 0 ? N + carrier : carrier;
			frequencyReal[0][bin] = 1;
			for (int symbol = 1; symbol <= 3; symbol++) {
				int base = (symbol - 1) * CARRIERS * 2;
				double dr = -fic[base + logical] / Math.sqrt(2d);
				double di = -fic[base + CARRIERS + logical] / Math.sqrt(2d);
				double pr = frequencyReal[symbol - 1][bin], pi = frequencyImag[symbol - 1][bin];
				frequencyReal[symbol][bin] = pr * dr - pi * di;
				frequencyImag[symbol][bin] = pr * di + pi * dr;
			}
		}
		for (int symbol = 0; symbol < 4; symbol++)
			DabFicDecoder.fft(frequencyReal[symbol], frequencyImag[symbol], true);

		int lead = 3000, nullLength = 2656, samples = lead + nullLength + 4 * (N + GUARD);
		byte[] iq = new byte[samples * 2];
		for (int n = 0; n < lead; n++) { iq[n * 2] = 45; iq[n * 2 + 1] = (byte)(n % 31 - 15); }
		int out = lead + nullLength;
		for (int symbol = 0; symbol < 4; symbol++) {
			double peak = 0;
			for (int n = 0; n < N; n++) peak = Math.max(peak,
					Math.max(Math.abs(frequencyReal[symbol][n]), Math.abs(frequencyImag[symbol][n])));
			double gain = 100d / peak;
			for (int n = N - GUARD; n < N; n++) out = put(iq, out,
					frequencyReal[symbol][n] * gain, frequencyImag[symbol][n] * gain);
			for (int n = 0; n < N; n++) out = put(iq, out,
					frequencyReal[symbol][n] * gain, frequencyImag[symbol][n] * gain);
		}
		return iq;
	}

	private static int put(byte[] iq, int sample, double i, double q) {
		iq[sample * 2] = (byte)Math.max(-127, Math.min(127, Math.round(i)));
		iq[sample * 2 + 1] = (byte)Math.max(-127, Math.min(127, Math.round(q)));
		return sample + 1;
	}

	private static byte[] ensembleFib() {
		byte[] fib = blankFib();
		fib[0] = 0x05; fib[1] = 0; fib[2] = 0x52; fib[3] = 0x34;
		fib[6] = 0x35; fib[7] = 0; fib[8] = 0x52; fib[9] = 0x34;
		putLabel(fib, 10, "TEST MULTIPLEX");
		return finishFib(fib);
	}

	private static byte[] configurationFib() {
		byte[] fib = blankFib();
		fib[0] = 0x05; fib[1] = 0x01;
		fib[2] = 0x04; fib[3] = 0; fib[4] = (byte)0x88; fib[5] = 72;
		fib[6] = 0x06; fib[7] = 0x02; fib[8] = (byte)0xc0; fib[9] = 0x01;
		fib[10] = 1; fib[11] = 0x3f; fib[12] = 0x06;
		fib[13] = 0x04; fib[14] = 0x09; fib[15] = 0; fib[16] = (byte)0xe2; fib[17] = 1;
		return finishFib(fib);
	}

	private static byte[] serviceLabelFib() {
		byte[] fib = blankFib();
		fib[0] = 0x35; fib[1] = 0x01; fib[2] = (byte)0xc0; fib[3] = 0x01;
		byte[] ebu = {'R', (byte)0x80, 'd', 'i', 'o', ' ', (byte)0xcc, (byte)0xfe,
				'a', 's', 't', 'n', (byte)0x82};
		for (int i = 0; i < 16; i++) fib[4 + i] = i < ebu.length ? ebu[i] : 0x20;
		return finishFib(fib);
	}

	private static byte[] blankFib() { byte[] value = new byte[32]; Arrays.fill(value, (byte)0xff); return value; }
	private static void putLabel(byte[] target, int at, String value) {
		byte[] bytes = value.getBytes(Charset.forName("ISO-8859-1"));
		for (int i = 0; i < 16; i++) target[at + i] = i < bytes.length ? bytes[i] : 0x20;
	}
	private static byte[] finishFib(byte[] fib) {
		int crc = 0xffff;
		for (int i = 0; i < 30; i++) {
			crc ^= (fib[i] & 0xff) << 8;
			for (int bit = 0; bit < 8; bit++) crc = (crc & 0x8000) != 0
					? ((crc << 1) ^ 0x1021) & 0xffff : (crc << 1) & 0xffff;
		}
		crc ^= 0xffff; fib[30] = (byte)(crc >>> 8); fib[31] = (byte)crc; return fib;
	}

	private static boolean[] convolutionalEncode(boolean[] input) {
		boolean[] output = new boolean[(input.length + 6) * 4]; int state = 0;
		for (int t = 0; t < input.length + 6; t++) {
			int bit = t < input.length && input[t] ? 1 : 0;
			state = (state << 1) | bit;
			for (int p = 0; p < 4; p++) output[t * 4 + p] = (Integer.bitCount(state & POLYS[p]) & 1) != 0;
		}
		return output;
	}

	private static boolean[] puncture(boolean[] encoded) {
		boolean[] output = new boolean[2304]; int in = 0, out = 0;
		for (int block = 0; block < 21; block++) for (int k = 0; k < 128; k++, in++)
			if (PI16[k & 31] != 0) output[out++] = encoded[in];
		for (int block = 0; block < 3; block++) for (int k = 0; k < 128; k++, in++)
			if (PI15[k & 31] != 0) output[out++] = encoded[in];
		for (int k = 0; k < 24; k++, in++) if (PIX[k] != 0) output[out++] = encoded[in];
		if (out != 2304) throw new AssertionError("Puncturing produced " + out + " bits");
		return output;
	}

	private static void scramble(boolean[] bits) {
		int[] shift = {1,1,1,1,1,1,1,1,1};
		for (int i = 0; i < bits.length; i++) {
			int prbs = shift[8] ^ shift[4]; bits[i] ^= prbs != 0;
			for (int j = 8; j > 0; j--) shift[j] = shift[j - 1]; shift[0] = prbs;
		}
	}

	private static int[] carrierMap() {
		int[] temporary = new int[N], result = new int[CARRIERS];
		for (int i = 1; i < N; i++) temporary[i] = (13 * temporary[i - 1] + 511) % N;
		int out = 0;
		for (int value : temporary) if (value >= 256 && value <= 1792 && value != 1024)
			result[out++] = value - 1024;
		return result;
	}
}
