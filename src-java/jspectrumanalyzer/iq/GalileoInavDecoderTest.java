package jspectrumanalyzer.iq;

import java.util.Arrays;

/** Official Annex-D vector and end-to-end nominal-page checks. */
public final class GalileoInavDecoderTest {
	private static final String INPUT = "1111111111110000110011001010101000000000000011110011001101010101"
			+ "11100011111011001101111110001010000111000001001101000000";
	private static final String INTERLEAVED = "1010000001011111100011001111000001011110101000000001100111100011"
			+ "1010100001010000010011110101011101111000100001101111101011100111"
			+ "1001100000011111111000100000100111110110000010011100011101100000"
			+ "100101110100100011000110110110010000011100111010";

	public static void main(String[] args) {
		checkOfficialFecVector();
		checkStreamSyncAndCrc();
		System.out.println("GalileoInavDecoderTest: OK");
	}

	private static void checkOfficialFecVector() {
		GalileoInavDecoder.PagePart part = GalileoInavDecoder.decodePart(soft(INTERLEAVED));
		require(part.valid, "official I/NAV tail bits");
		require(INPUT.equals(bits(part.bits)), "official I/NAV Viterbi/deinterleaver vector");
		require(part.quality > .99d, "official I/NAV path quality");
	}

	private static void checkStreamSyncAndCrc() {
		int[] even = new int[120], odd = new int[120];
		even[0] = 0; even[1] = 0;
		int[] word = new int[128]; word[3] = 1; // word type 4
		for (int index = 0; index < 112; index++) even[index + 2] = word[index];
		odd[0] = 1; odd[1] = 0;
		for (int index = 0; index < 16; index++) odd[index + 2] = word[index + 112];
		for (int index = 18; index < 82; index++) odd[index] = (index * 7 + 3) & 1;
		int[] protectedBits = new int[196];
		System.arraycopy(even, 0, protectedBits, 0, 114);
		System.arraycopy(odd, 0, protectedBits, 114, 82);
		int crc = GalileoInavDecoder.crc24(protectedBits);
		for (int index = 0; index < 24; index++) odd[82 + index] = (crc >>> (23 - index)) & 1;
		int[] ssp = { 0,0,0,0,0,1,0,0 };
		System.arraycopy(ssp, 0, odd, 106, ssp.length);

		GalileoInavDecoder decoder = new GalileoInavDecoder();
		feedPart(decoder, even, false);
		feedPart(decoder, odd, false);
		GalileoInavDecoder.Page page = decoder.latestPage();
		require(page.valid, "synthetic vertical-page CRC");
		require(page.wordType == 4, "I/NAV word type");
		require(Arrays.equals(word, page.word), "I/NAV 128-bit word assembly");
	}

	private static void feedPart(GalileoInavDecoder decoder, int[] clear, boolean inverted) {
		String sync = "0101100000";
		for (int index = 0; index < sync.length(); index++)
			decoder.acceptSoft(symbol(sync.charAt(index) - '0', inverted));
		int[] encoded = encode(clear), interleaved = new int[240];
		for (int row = 0; row < 8; row++) for (int column = 0; column < 30; column++)
			interleaved[row * 30 + column] = encoded[column * 8 + row];
		for (int bit : interleaved) decoder.acceptSoft(symbol(bit, inverted));
	}

	private static int[] encode(int[] input) {
		int[] output = new int[input.length * 2]; int state = 0;
		for (int index = 0; index < input.length; index++) {
			int register = state | (input[index] << 6); state = register >>> 1;
			output[index * 2] = Integer.bitCount(register & 0171) & 1;
			output[index * 2 + 1] = (Integer.bitCount(register & 0133) & 1) ^ 1;
		}
		return output;
	}

	private static double[] soft(String bits) {
		double[] result = new double[bits.length()];
		for (int index = 0; index < result.length; index++) result[index] = symbol(bits.charAt(index) - '0', false);
		return result;
	}
	private static double symbol(int bit, boolean inverted) { return (bit == 0 ? 1d : -1d) * (inverted ? -1d : 1d); }
	private static String bits(int[] bits) { StringBuilder text = new StringBuilder(); for (int bit : bits) text.append(bit); return text.toString(); }
	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
