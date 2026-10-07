package jspectrumanalyzer.iq;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lightweight DAB Mode I FIC receiver.  It deliberately stops after the fast
 * information blocks: no MSC, audio or packet payload is decoded.
 */
final class DabFicDecoder {
	private static final int BASE_RATE = 2_048_000;
	private static final int NULL_SAMPLES = 2656;
	private static final int USEFUL_SAMPLES = 2048;
	private static final int GUARD_SAMPLES = 504;
	private static final int SYMBOL_SAMPLES = USEFUL_SAMPLES + GUARD_SAMPLES;
	private static final int CARRIERS = 1536;
	private static final int[] POLYS = {0155, 0117, 0123, 0155};
	private static final int[] PI_15 = {
			1,1,1,0, 1,1,1,0, 1,1,1,0, 1,1,1,0,
			1,1,1,0, 1,1,1,0, 1,1,1,0, 1,1,0,0};
	private static final int[] PI_16 = {
			1,1,1,0, 1,1,1,0, 1,1,1,0, 1,1,1,0,
			1,1,1,0, 1,1,1,0, 1,1,1,0, 1,1,1,0};
	private static final int[] PI_X = {
			1,1,0,0, 1,1,0,0, 1,1,0,0,
			1,1,0,0, 1,1,0,0, 1,1,0,0};
	private static final int[] CARRIER_MAP = createCarrierMap();
	private static final Integer[] CARRIER_ORDER = createCarrierOrder();
	private static final Charset LATIN_2 = Charset.forName("ISO-8859-2");
	private static final char[] EBU_LATIN = createEbuLatin();

	private final Map<Integer, Subchannel> subchannels = new LinkedHashMap<Integer, Subchannel>();
	private final Map<Long, MutableService> services = new LinkedHashMap<Long, MutableService>();
	private int ensembleId = -1;
	private int ensembleEcc = -1;
	private String ensembleLabel = "";
	private String utcTime = "";
	private int totalValidFibs;
	private int totalFibs;
	private boolean carrierLock;
	private int lockedCarrierOffset;
	private boolean lockedInvertedSpectrum;
	private int lockedQuadrant;
	private int carrierLockMisses;

	Result analyze(byte[] iq, int length, int sampleRateHz) {
		if (iq == null || sampleRateHz < 1_800_000 || length < 2)
			return snapshot(0, 0, 0d, "Need at least one Mode I frame");
		int samples = Math.min(length, iq.length) / 2;
		double scale = sampleRateHz / (double) BASE_RATE;
		int nullLength = Math.max(128, (int)Math.round(NULL_SAMPLES * scale));
		int required = nullLength + (int)Math.ceil(4d * SYMBOL_SAMPLES * scale);
		if (samples < required) return snapshot(0, 0, 0d, "Collecting DAB frame IQ");

		NullPosition nullPosition = findNull(iq, samples, nullLength, samples - required);
		int expected = nullPosition.start + nullLength + (int)Math.round(GUARD_SAMPLES * scale);
		Timing[] timing = new Timing[4];
		for (int symbol = 0; symbol < timing.length; symbol++) {
			int at = expected + (int)Math.round(symbol * SYMBOL_SAMPLES * scale);
			timing[symbol] = refineTiming(iq, samples, at,
					(int)Math.round(USEFUL_SAMPLES * scale), (int)Math.round(GUARD_SAMPLES * scale));
		}
		double correctionBins = 0d;
		double cp = 0d;
		for (Timing value : timing) { correctionBins += value.correctionBins; cp += value.correlation; }
		correctionBins /= timing.length;
		cp /= timing.length;

		double[][] fftReal = new double[4][USEFUL_SAMPLES];
		double[][] fftImag = new double[4][USEFUL_SAMPLES];
		for (int symbol = 0; symbol < 4; symbol++) {
			resampleUseful(iq, samples, timing[symbol].usefulStart, scale,
					correctionBins, fftReal[symbol], fftImag[symbol]);
			fft(fftReal[symbol], fftImag[symbol], false);
		}
		int integerCarrierOffset = estimateIntegerCarrierOffset(fftReal[0], fftImag[0]);

		DecodeCandidate best = null;
		int decodedCarrierOffset = integerCarrierOffset, decodedQuadrant = 0;
		boolean decodedInverted = false;
		if (carrierLock) {
			double[] lockedSoft = demodulateFic(fftReal, fftImag, timing, scale,
					correctionBins, lockedCarrierOffset, lockedInvertedSpectrum);
			best = decodeFrame(rotateSoft(lockedSoft, lockedQuadrant));
			decodedCarrierOffset = lockedCarrierOffset;
			decodedInverted = lockedInvertedSpectrum;
			decodedQuadrant = lockedQuadrant;
			if (best.validFibs > 0) carrierLockMisses = 0;
			else if (++carrierLockMisses >= 3) {
				carrierLock = false;
				carrierLockMisses = 0;
			}
		}
		if (!carrierLock) {
			double[] centeredSoft = demodulateFic(fftReal, fftImag, timing, scale, correctionBins, 0, false);
			DecodeCandidate centered = decodeFrame(centeredSoft);
			if (best == null || centered.validFibs > best.validFibs) {
				best = centered; decodedCarrierOffset = 0;
				decodedInverted = false; decodedQuadrant = 0;
			}
			if (centered.validFibs >= 2) carrierLock = true;
		}
		for (int carrierOffset = integerCarrierOffset - 4;
				!carrierLock && carrierOffset <= integerCarrierOffset + 4; carrierOffset++) {
			double[] normalSoft = demodulateFic(fftReal, fftImag, timing, scale,
					correctionBins, carrierOffset, false);
			double[] invertedSoft = demodulateFic(fftReal, fftImag, timing, scale,
					correctionBins, carrierOffset, true);
			int orientation = 0;
			for (double[] soft : new double[][] {normalSoft, invertedSoft}) {
				for (int quadrant = 0; quadrant < 4; quadrant++) {
					DecodeCandidate candidate = decodeFrame(rotateSoft(soft, quadrant));
					if (best == null || candidate.validFibs > best.validFibs) {
						best = candidate; decodedCarrierOffset = carrierOffset;
						decodedInverted = orientation != 0; decodedQuadrant = quadrant;
					}
				}
				orientation++;
			}
		}
		if (!carrierLock && (integerCarrierOffset < -4 || integerCarrierOffset > 4)) {
			double[] normalSoft = demodulateFic(fftReal, fftImag, timing, scale, correctionBins, 0, false);
			double[] invertedSoft = demodulateFic(fftReal, fftImag, timing, scale, correctionBins, 0, true);
			int orientation = 0;
			for (double[] soft : new double[][] {normalSoft, invertedSoft}) {
				for (int quadrant = 0; quadrant < 4; quadrant++) {
					DecodeCandidate candidate = decodeFrame(rotateSoft(soft, quadrant));
					if (best == null || candidate.validFibs > best.validFibs) {
						best = candidate; decodedCarrierOffset = 0;
						decodedInverted = orientation != 0; decodedQuadrant = quadrant;
					}
				}
				orientation++;
			}
		}
		if (best != null && best.validFibs >= 2) {
			carrierLock = true;
			lockedCarrierOffset = decodedCarrierOffset;
			lockedInvertedSpectrum = decodedInverted;
			lockedQuadrant = decodedQuadrant;
			carrierLockMisses = 0;
		}
		int valid = best == null ? 0 : best.validFibs;
		if (best != null) for (byte[] fib : best.valid) parseFib(fib);
		totalValidFibs += valid;
		totalFibs += 12;
		if (totalFibs > 240) { totalValidFibs /= 2; totalFibs /= 2; }
		double quality = valid * 100d / 12d;
		String state = valid > 0 ? "FIC locked"
				: totalValidFibs > 0 ? "FIC locked; current frame CRC failed"
				: cp >= .18d ? "FIC sync, waiting for valid FIB CRC" : "Searching FIC";
		return snapshot(valid, 12, quality, state);
	}

	private static double[] demodulateFic(double[][] fftReal, double[][] fftImag, Timing[] timing,
			double scale, double correctionBins, int integerCarrierOffset, boolean invertedSpectrum) {
		double[] soft = new double[3 * CARRIERS * 2];
		for (int symbol = 1; symbol <= 3; symbol++) {
			double elapsedBaseSamples = (timing[symbol].usefulStart
					- timing[symbol - 1].usefulStart) / scale;
			double cfoPhase = -2d * Math.PI * correctionBins * elapsedBaseSamples / USEFUL_SAMPLES;
			double cfoCos = Math.cos(cfoPhase), cfoSin = Math.sin(cfoPhase);
			double[] diffReal = new double[CARRIERS];
			double[] diffImag = new double[CARRIERS];
			for (int logical = 0; logical < CARRIERS; logical++) {
				int carrier = (invertedSpectrum ? -CARRIER_MAP[logical] : CARRIER_MAP[logical])
						+ integerCarrierOffset;
				int bin = carrier < 0 ? USEFUL_SAMPLES + carrier : carrier;
				double cr = fftReal[symbol][bin], ci = fftImag[symbol][bin];
				double pr = fftReal[symbol - 1][bin], pi = fftImag[symbol - 1][bin];
				double dr = cr * pr + ci * pi;
				double di = ci * pr - cr * pi;
				diffReal[logical] = dr * cfoCos - di * cfoSin;
				diffImag[logical] = dr * cfoSin + di * cfoCos;
				if (invertedSpectrum) diffImag[logical] = -diffImag[logical];
			}
			removeTimingSlope(diffReal, diffImag);
			double commonRotation = fourthPowerRotation(diffReal, diffImag);
			rotate(diffReal, diffImag, commonRotation);
			int base = (symbol - 1) * CARRIERS * 2;
			for (int i = 0; i < CARRIERS; i++) {
				double norm = Math.max(1e-12, Math.hypot(diffReal[i], diffImag[i]));
				soft[base + i] = -diffReal[i] / norm;
				soft[base + CARRIERS + i] = -diffImag[i] / norm;
			}
		}
		return soft;
	}

	private Result snapshot(int valid, int attempted, double quality, String state) {
		List<Service> copy = new ArrayList<Service>();
		for (MutableService service : services.values()) {
			Subchannel sub = service.subchannelId < 0 ? null : subchannels.get(service.subchannelId);
			copy.add(new Service(service.id, service.label, service.componentType, service.subchannelId,
					sub == null ? -1 : sub.bitrateKbps(), sub == null ? "--" : sub.protection()));
		}
		Collections.sort(copy, new Comparator<Service>() {
			@Override public int compare(Service a, Service b) {
				String al = a.label.length() == 0 ? Long.toHexString(a.id) : a.label;
				String bl = b.label.length() == 0 ? Long.toHexString(b.id) : b.label;
				return al.compareToIgnoreCase(bl);
			}
		});
		double rolling = totalFibs == 0 ? quality : totalValidFibs * 100d / totalFibs;
		return new Result(state, ensembleId, ensembleEcc, countryCode(ensembleEcc, ensembleId),
				ensembleLabel, utcTime, valid, attempted,
				quality, rolling, Collections.unmodifiableList(copy), subchannels.size());
	}

	private DecodeCandidate decodeFrame(double[] soft) {
		List<byte[]> valid = new ArrayList<byte[]>();
		for (int block = 0; block < 4; block++) {
			double[] punctured = new double[2304];
			System.arraycopy(soft, block * 2304, punctured, 0, punctured.length);
			boolean[] bits = viterbi(depuncture(punctured), 768);
			descramble(bits);
			for (int fib = 0; fib < 3; fib++) {
				byte[] bytes = bitsToBytes(bits, fib * 256, 256);
				if (validFibCrc(bytes)) valid.add(bytes);
			}
		}
		return new DecodeCandidate(valid);
	}

	static boolean[] decodeCodewordForTest(double[] punctured) {
		boolean[] bits = viterbi(depuncture(punctured), 768);
		descramble(bits);
		return bits;
	}

	private static double[] rotateSoft(double[] source, int quadrant) {
		if (quadrant == 0) return source;
		double[] result = new double[source.length];
		for (int symbol = 0; symbol < 3; symbol++) {
			int base = symbol * CARRIERS * 2;
			for (int i = 0; i < CARRIERS; i++) {
				double re = source[base + i], im = source[base + CARRIERS + i];
				if (quadrant == 1) { result[base + i] = -im; result[base + CARRIERS + i] = re; }
				else if (quadrant == 2) { result[base + i] = -re; result[base + CARRIERS + i] = -im; }
				else { result[base + i] = im; result[base + CARRIERS + i] = -re; }
			}
		}
		return result;
	}

	private static double[] depuncture(double[] input) {
		double[] result = new double[(768 + 6) * 4];
		int in = 0, out = 0;
		for (int block = 0; block < 21; block++)
			for (int k = 0; k < 128; k++, out++) if (PI_16[k & 31] != 0) result[out] = input[in++];
		for (int block = 0; block < 3; block++)
			for (int k = 0; k < 128; k++, out++) if (PI_15[k & 31] != 0) result[out] = input[in++];
		for (int k = 0; k < 24; k++, out++) if (PI_X[k] != 0) result[out] = input[in++];
		return result;
	}

	private static boolean[] viterbi(double[] symbols, int outputBits) {
		int steps = outputBits + 6;
		double[] oldMetric = new double[64], newMetric = new double[64];
		for (int s = 1; s < 64; s++) oldMetric[s] = -1e100;
		byte[][] previous = new byte[steps][64];
		for (int t = 0; t < steps; t++) {
			for (int s = 0; s < 64; s++) newMetric[s] = -1e100;
			for (int state = 0; state < 64; state++) {
				if (oldMetric[state] < -1e90) continue;
				for (int bit = 0; bit <= 1; bit++) {
					int register = (state << 1) | bit;
					int next = register & 63;
					double metric = oldMetric[state];
					for (int p = 0; p < 4; p++)
						metric += (parity(register & POLYS[p]) == 1 ? 1d : -1d) * symbols[t * 4 + p];
					if (metric > newMetric[next]) {
						newMetric[next] = metric;
						previous[t][next] = (byte)state;
					}
				}
			}
			double[] swap = oldMetric; oldMetric = newMetric; newMetric = swap;
		}
		boolean[] decoded = new boolean[outputBits];
		int state = 0;
		for (int t = steps - 1; t >= 0; t--) {
			if (t < outputBits) decoded[t] = (state & 1) != 0;
			state = previous[t][state] & 0xff;
		}
		return decoded;
	}

	private static int parity(int value) { return Integer.bitCount(value) & 1; }

	private static void descramble(boolean[] bits) {
		int[] shift = {1,1,1,1,1,1,1,1,1};
		for (int i = 0; i < bits.length; i++) {
			int prbs = shift[8] ^ shift[4];
			bits[i] ^= prbs != 0;
			for (int j = 8; j > 0; j--) shift[j] = shift[j - 1];
			shift[0] = prbs;
		}
	}

	private static byte[] bitsToBytes(boolean[] bits, int offset, int count) {
		byte[] result = new byte[count / 8];
		for (int i = 0; i < count; i++) if (bits[offset + i]) result[i >> 3] |= 1 << (7 - (i & 7));
		return result;
	}

	private static boolean validFibCrc(byte[] fib) {
		int crc = 0xffff;
		for (int i = 0; i < 30; i++) {
			crc ^= (fib[i] & 0xff) << 8;
			for (int bit = 0; bit < 8; bit++) crc = (crc & 0x8000) != 0
					? ((crc << 1) ^ 0x1021) & 0xffff : (crc << 1) & 0xffff;
		}
		crc ^= 0xffff;
		return ((fib[30] & 0xff) << 8 | (fib[31] & 0xff)) == crc;
	}

	private void parseFib(byte[] fib) {
		int offset = 0;
		while (offset < 30) {
			int header = fib[offset] & 0xff;
			int type = header >>> 5, figLength = header & 0x1f;
			if (type == 7 || figLength == 0 || offset + 1 + figLength > 30) break;
			if (type == 0) parseFig0(fib, offset, figLength);
			else if (type == 1) parseFig1(fib, offset, figLength);
			offset += figLength + 1;
		}
	}

	private void parseFig0(byte[] fib, int at, int length) {
		if (length < 1) return;
		int control = fib[at + 1] & 0xff;
		boolean otherEnsemble = (control & 0x40) != 0;
		boolean dataService = (control & 0x20) != 0;
		int extension = control & 0x1f;
		if (otherEnsemble) return;
		if (extension == 0 && length >= 5) {
			ensembleId = u16(fib, at + 2);
		} else if (extension == 1) {
			parseSubchannels(fib, at + 2, at + 1 + length);
		} else if (extension == 2) {
			parseServices(fib, at + 2, at + 1 + length, dataService);
		} else if (extension == 9 && length >= 4) {
			// FIG 0/9: flags/LTO, ensemble ECC, international table identifier.
			ensembleEcc = fib[at + 3] & 0xff;
		} else if (extension == 10 && length >= 5) {
			parseTime(fib, at + 2, length - 1);
		}
	}

	private void parseSubchannels(byte[] data, int at, int end) {
		while (at + 2 < end) {
			int id = (data[at] & 0xfc) >>> 2;
			int start = ((data[at] & 3) << 8) | (data[at + 1] & 0xff);
			boolean longForm = (data[at + 2] & 0x80) != 0;
			if (!longForm) {
				int tableIndex = data[at + 2] & 0x3f;
				subchannels.put(id, new Subchannel(id, start, -1, true, -1, -1, tableIndex));
				at += 3;
			} else {
				if (at + 3 >= end) break;
				int option = (data[at + 2] >>> 4) & 7;
				int level = ((data[at + 2] >>> 2) & 3) + 1;
				int size = ((data[at + 2] & 3) << 8) | (data[at + 3] & 0xff);
				subchannels.put(id, new Subchannel(id, start, size, false, option, level, -1));
				at += 4;
			}
		}
	}

	private void parseServices(byte[] data, int at, int end, boolean dataService) {
		while (at < end) {
			int sidBytes = dataService ? 4 : 2;
			if (at + sidBytes >= end) break;
			long sid = 0;
			for (int i = 0; i < sidBytes; i++) sid = (sid << 8) | (data[at++] & 0xffL);
			int components = data[at++] & 0x0f;
			MutableService service = service(sid);
			for (int component = 0; component < components && at + 1 < end; component++, at += 2) {
				int first = data[at] & 0xff, second = data[at + 1] & 0xff;
				int transportMode = first >>> 6;
				if (transportMode == 0 || transportMode == 1) {
					int type = first & 0x3f;
					int subchannel = second >>> 2;
					if (service.subchannelId < 0 || (second & 2) != 0) {
						service.subchannelId = subchannel;
						service.componentType = transportMode == 0
								? (type == 63 ? "DAB+ audio" : type == 0 ? "DAB audio" : "Audio ASCTy " + type)
								: "Stream data DSCTy " + type;
					}
				} else if (transportMode == 3 && service.componentType.length() == 0) {
					service.componentType = "Packet data";
				}
			}
		}
	}

	private void parseFig1(byte[] fib, int at, int length) {
		if (length < 4) return;
		int control = fib[at + 1] & 0xff;
		int charset = control >>> 4;
		int extension = control & 7;
		if ((control & 8) != 0) return;
		if (extension == 0 && length >= 20) {
			int eid = u16(fib, at + 2);
			if (ensembleId < 0 || eid == ensembleId) {
				ensembleId = eid;
				ensembleLabel = decodeLabel(fib, at + 4, 16, charset);
			}
		} else if (extension == 1 && length >= 20) {
			long sid = u16(fib, at + 2);
			service(sid).label = decodeLabel(fib, at + 4, 16, charset);
		} else if (extension == 5 && length >= 22) {
			long sid = u32(fib, at + 2);
			service(sid).label = decodeLabel(fib, at + 6, 16, charset);
		}
	}

	private void parseTime(byte[] data, int at, int available) {
		if (available < 4) return;
		int base = at * 8;
		int mjd = extractBits(data, base + 1, 17);
		boolean longUtc = extractBits(data, base + 20, 1) != 0;
		int hour = extractBits(data, base + 21, 5);
		int minute = extractBits(data, base + 26, 6);
		try {
			LocalDate date = LocalDate.ofEpochDay(mjd - 40587L);
			if (longUtc && available >= 6) {
				int second = extractBits(data, base + 32, 6);
				utcTime = String.format(Locale.US, "%04d-%02d-%02d %02d:%02d:%02d UTC",
						date.getYear(), date.getMonthValue(), date.getDayOfMonth(), hour, minute, second);
			} else {
				utcTime = String.format(Locale.US, "%04d-%02d-%02d %02d:%02d UTC",
						date.getYear(), date.getMonthValue(), date.getDayOfMonth(), hour, minute);
			}
		} catch (RuntimeException ignored) { }
	}

	private MutableService service(long id) {
		MutableService service = services.get(id);
		if (service == null) { service = new MutableService(id); services.put(id, service); }
		return service;
	}

	private static String decodeLabel(byte[] data, int at, int length, int charset) {
		int end = Math.min(data.length, at + length);
		String value;
		if (charset == 0) {
			StringBuilder ebu = new StringBuilder(Math.max(0, end - at));
			for (int i = at; i < end; i++) ebu.append(EBU_LATIN[data[i] & 0xff]);
			value = ebu.toString();
		} else if (charset == 6) {
			while (end - at >= 2 && data[end - 2] == 0
					&& ((data[end - 1] & 0xff) == 0 || (data[end - 1] & 0xff) == 0x20)) end -= 2;
			end -= (end - at) & 1;
			value = new String(data, at, Math.max(0, end - at), StandardCharsets.UTF_16BE);
		} else {
			while (end > at && ((data[end - 1] & 0xff) == 0 || (data[end - 1] & 0xff) == 0x20)) end--;
			if (charset == 15) {
				value = new String(data, at, Math.max(0, end - at), StandardCharsets.UTF_8);
			} else if (charset == 3) {
				value = new String(data, at, Math.max(0, end - at), LATIN_2);
			} else {
				value = new String(data, at, Math.max(0, end - at), StandardCharsets.ISO_8859_1);
			}
		}
		StringBuilder clean = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			clean.append(c == 0 || Character.isISOControl(c) ? ' ' : c);
		}
		return clean.toString().trim();
	}

	/** ETSI TS 101 756, complete EBU Latin repertoire (charset 0000). */
	private static char[] createEbuLatin() {
		String mapping =
				"\u0000\u0118\u012e\u0172\u0102\u0116\u010e\u0218\u021a\u010a\u0000\u0000\u0120\u0139\u017b\u0143" +
				"\u0105\u0119\u012f\u0173\u0103\u0117\u010f\u0219\u021b\u010b\u0147\u011a\u0121\u013a\u017c\u0000" +
				" !\"#\u0142%&'()*+,-./" +
				"0123456789:;<=>?" +
				"@ABCDEFGHIJKLMNO" +
				"PQRSTUVWXYZ[\u016e]\u0141_" +
				"\u0104abcdefghijklmno" +
				"pqrstuvwxyz\u00ab\u016f\u00bb\u013d\u0126" +
				"\u00e1\u00e0\u00e9\u00e8\u00ed\u00ec\u00f3\u00f2\u00fa\u00f9\u00d1\u00c7\u015e\u00df\u00a1\u0178" +
				"\u00e2\u00e4\u00ea\u00eb\u00ee\u00ef\u00f4\u00f6\u00fb\u00fc\u00f1\u00e7\u015f\u011f\u0131\u00ff" +
				"\u0136\u0145\u00a9\u0122\u011e\u011b\u0148\u0151\u0150\u20ac\u00a3$\u0100\u0112\u012a\u016a" +
				"\u0137\u0146\u013b\u0123\u013c\u0130\u0144\u0171\u0170\u00bf\u013e\u00b0\u0101\u0113\u012b\u016b" +
				"\u00c1\u00c0\u00c9\u00c8\u00cd\u00cc\u00d3\u00d2\u00da\u00d9\u0158\u010c\u0160\u017d\u00d0\u013f" +
				"\u00c2\u00c4\u00ca\u00cb\u00ce\u00cf\u00d4\u00d6\u00db\u00dc\u0159\u010d\u0161\u017e\u0111\u0140" +
				"\u00c3\u00c5\u00c6\u0152\u0177\u00dd\u00d5\u00d8\u00de\u014a\u0154\u0106\u015a\u0179\u0164\u00f0" +
				"\u00e3\u00e5\u00e6\u0153\u0175\u00fd\u00f5\u00f8\u00fe\u014b\u0155\u0107\u015b\u017a\u0165\u0127";
		if (mapping.length() != 256) throw new IllegalStateException("Invalid EBU Latin table");
		return mapping.toCharArray();
	}

	private static int u16(byte[] data, int at) { return (data[at] & 0xff) << 8 | (data[at + 1] & 0xff); }

	/** ISO alpha-2 code for the European DAB ECC/Country-Id registrations. */
	private static String countryCode(int ecc, int ensembleId) {
		if (ecc < 0 || ensembleId < 0) return "--";
		int key = (ecc << 4) | ((ensembleId >>> 12) & 0x0f);
		switch (key) {
		case 0xe02: return "DZ"; case 0xe03: return "AD"; case 0xe04: return "IL";
		case 0xe05: return "IT"; case 0xe06: return "BE"; case 0xe07: return "RU";
		case 0xe09: return "AL"; case 0xe0a: return "AT"; case 0xe0b: return "HU";
		case 0xe0c: return "MT"; case 0xe0d: return "DE"; case 0xe0e: return "ES";
		case 0xe0f: return "EG";
		case 0xe11: return "GR"; case 0xe12: return "CY"; case 0xe13: return "SM";
		case 0xe14: return "CH"; case 0xe15: return "JO"; case 0xe16: return "FI";
		case 0xe17: return "LU"; case 0xe18: return "BG"; case 0xe19: return "DK";
		case 0xe1a: return "GI"; case 0xe1b: return "IQ"; case 0xe1c: return "GB";
		case 0xe1d: return "LY"; case 0xe1e: return "RO"; case 0xe1f: return "FR";
		case 0xe21: return "MA"; case 0xe22: return "CZ"; case 0xe23: return "PL";
		case 0xe24: return "VA"; case 0xe25: return "SK"; case 0xe26: return "SY";
		case 0xe27: return "TN"; case 0xe28: return "PT"; case 0xe29: return "LI";
		case 0xe2a: return "IS"; case 0xe2b: return "MC"; case 0xe2c: return "LT";
		case 0xe2d: return "RS"; case 0xe2e: return "ES"; case 0xe2f: return "NO";
		case 0xe31: return "ME"; case 0xe32: return "IE"; case 0xe33: return "TR";
		case 0xe35: return "TJ"; case 0xe38: return "NL"; case 0xe39: return "LV";
		case 0xe3a: return "LB"; case 0xe3b: return "AZ"; case 0xe3c: return "HR";
		case 0xe3e: return "SE"; case 0xe3f: return "BY";
		case 0xe41: return "MD"; case 0xe42: return "EE"; case 0xe43: return "MK";
		case 0xe46: return "UA"; case 0xe48: return "PT"; case 0xe49: return "SI";
		case 0xe4a: return "AM"; case 0xe4c: return "GE"; case 0xe4e: return "TM";
		case 0xe4f: return "BA"; default: return "--";
		}
	}

	private static long u32(byte[] data, int at) {
		return (data[at] & 0xffL) << 24 | (data[at + 1] & 0xffL) << 16
				| (data[at + 2] & 0xffL) << 8 | (data[at + 3] & 0xffL);
	}
	private static int extractBits(byte[] data, int bitOffset, int count) {
		int value = 0;
		for (int i = 0; i < count; i++)
			value = value << 1 | ((data[(bitOffset + i) >>> 3] >>> (7 - ((bitOffset + i) & 7))) & 1);
		return value;
	}

	private static int[] createCarrierMap() {
		int[] temporary = new int[USEFUL_SAMPLES];
		int[] result = new int[CARRIERS];
		for (int i = 1; i < temporary.length; i++) temporary[i] = (13 * temporary[i - 1] + 511) % USEFUL_SAMPLES;
		int out = 0;
		for (int value : temporary) if (value >= 256 && value <= 1792 && value != 1024)
			result[out++] = value - 1024;
		if (out != CARRIERS) throw new IllegalStateException("DAB carrier map has " + out + " entries");
		return result;
	}
	private static Integer[] createCarrierOrder() {
		Integer[] order = new Integer[CARRIERS];
		for (int i = 0; i < order.length; i++) order[i] = i;
		java.util.Arrays.sort(order, new Comparator<Integer>() {
			@Override public int compare(Integer a, Integer b) { return CARRIER_MAP[a] - CARRIER_MAP[b]; }
		});
		return order;
	}

	private static NullPosition findNull(byte[] iq, int samples, int nullLength, int latest) {
		long[] prefix = new long[samples + 1];
		for (int n = 0; n < samples; n++) {
			int i = iq[n * 2], q = iq[n * 2 + 1];
			prefix[n + 1] = prefix[n] + i * i + q * q;
		}
		int step = Math.max(1, nullLength / 64), best = 0;
		long bestEnergy = Long.MAX_VALUE;
		for (int at = 0; at <= latest; at += step) {
			long energy = prefix[at + nullLength] - prefix[at];
			if (energy < bestEnergy) { bestEnergy = energy; best = at; }
		}
		for (int at = Math.max(0, best - step); at <= Math.min(latest, best + step); at++) {
			long energy = prefix[at + nullLength] - prefix[at];
			if (energy < bestEnergy) { bestEnergy = energy; best = at; }
		}
		return new NullPosition(best);
	}

	private static Timing refineTiming(byte[] iq, int samples, int expected, int useful, int guard) {
		int radius = Math.max(8, guard / 3), first = Math.max(guard, expected - radius);
		int last = Math.min(samples - useful, expected + radius), coarse = Math.max(1, guard / 48);
		int best = Math.max(first, Math.min(last, expected));
		Correlation bestCorrelation = new Correlation(0, 0, -1);
		for (int at = first; at <= last; at += coarse) {
			Correlation value = correlatePrefix(iq, at, useful, guard, 2);
			if (value.score > bestCorrelation.score) { bestCorrelation = value; best = at; }
		}
		for (int at = Math.max(first, best - coarse); at <= Math.min(last, best + coarse); at++) {
			Correlation value = correlatePrefix(iq, at, useful, guard, 1);
			if (value.score > bestCorrelation.score) { bestCorrelation = value; best = at; }
		}
		double bins = -Math.atan2(bestCorrelation.imag, bestCorrelation.real) / (2d * Math.PI);
		return new Timing(best, bins, bestCorrelation.score);
	}

	private static Correlation correlatePrefix(byte[] iq, int usefulStart, int useful, int guard, int stride) {
		double real = 0, imag = 0, firstEnergy = 0, secondEnergy = 0;
		int prefix = usefulStart - guard;
		for (int n = 0; n < guard; n += stride) {
			int a = (prefix + n) * 2, b = (prefix + n + useful) * 2;
			double ai = iq[a], aq = iq[a + 1], bi = iq[b], bq = iq[b + 1];
			real += ai * bi + aq * bq;
			imag += aq * bi - ai * bq;
			firstEnergy += ai * ai + aq * aq; secondEnergy += bi * bi + bq * bq;
		}
		double denominator = Math.sqrt(firstEnergy * secondEnergy);
		return new Correlation(real, imag, denominator <= 0 ? 0 : Math.hypot(real, imag) / denominator);
	}

	private static void resampleUseful(byte[] iq, int samples, int start, double scale,
			double correctionBins, double[] real, double[] imag) {
		for (int n = 0; n < USEFUL_SAMPLES; n++) {
			double position = start + n * scale;
			double i, q;
			if (scale <= 1.2d) {
				int p = Math.max(0, Math.min(samples - 2, (int)position));
				double fraction = position - p;
				i = iq[p * 2] * (1d - fraction) + iq[(p + 1) * 2] * fraction;
				q = iq[p * 2 + 1] * (1d - fraction) + iq[(p + 1) * 2 + 1] * fraction;
			} else {
				int centre = (int)Math.floor(position);
				int radius = Math.min(96, Math.max(12, (int)Math.ceil(8d * scale)));
				double sumI = 0, sumQ = 0, sumWeight = 0;
				for (int p = Math.max(0, centre - radius); p <= Math.min(samples - 1, centre + radius); p++) {
					double x = p - position;
					double sincArgument = Math.PI * x / scale;
					double sinc = Math.abs(sincArgument) < 1e-9 ? 1d : Math.sin(sincArgument) / sincArgument;
					double windowPosition = x / (radius + 1d);
					double window = .5d + .5d * Math.cos(Math.PI * windowPosition);
					double weight = sinc * window;
					sumI += iq[p * 2] * weight; sumQ += iq[p * 2 + 1] * weight; sumWeight += weight;
				}
				i = sumI / Math.max(1e-12, sumWeight); q = sumQ / Math.max(1e-12, sumWeight);
			}
			double phase = -2d * Math.PI * correctionBins * n / USEFUL_SAMPLES;
			double cos = Math.cos(phase), sin = Math.sin(phase);
			real[n] = i * cos - q * sin; imag[n] = i * sin + q * cos;
		}
	}

	private static int estimateIntegerCarrierOffset(double[] real, double[] imag) {
		int bestOffset = 0;
		double bestEnergy = -1;
		for (int offset = -128; offset <= 128; offset++) {
			double energy = 0;
			for (int carrier = -768; carrier <= 768; carrier++) {
				if (carrier == 0) continue;
				int shifted = carrier + offset;
				if (shifted <= -1024 || shifted >= 1024) continue;
				int bin = shifted < 0 ? USEFUL_SAMPLES + shifted : shifted;
				energy += real[bin] * real[bin] + imag[bin] * imag[bin];
			}
			if (energy > bestEnergy) { bestEnergy = energy; bestOffset = offset; }
		}
		return bestOffset;
	}

	static void fft(double[] real, double[] imag, boolean inverse) {
		int n = real.length;
		for (int i = 1, j = 0; i < n; i++) {
			int bit = n >>> 1;
			for (; (j & bit) != 0; bit >>>= 1) j ^= bit;
			j ^= bit;
			if (i < j) {
				double tr = real[i]; real[i] = real[j]; real[j] = tr;
				double ti = imag[i]; imag[i] = imag[j]; imag[j] = ti;
			}
		}
		for (int length = 2; length <= n; length <<= 1) {
			double angle = (inverse ? 2d : -2d) * Math.PI / length;
			double stepReal = Math.cos(angle), stepImag = Math.sin(angle);
			for (int base = 0; base < n; base += length) {
				double wr = 1, wi = 0;
				for (int j = 0; j < length / 2; j++) {
					int even = base + j, odd = even + length / 2;
					double tr = wr * real[odd] - wi * imag[odd];
					double ti = wr * imag[odd] + wi * real[odd];
					real[odd] = real[even] - tr; imag[odd] = imag[even] - ti;
					real[even] += tr; imag[even] += ti;
					double next = wr * stepReal - wi * stepImag;
					wi = wr * stepImag + wi * stepReal; wr = next;
				}
			}
		}
		if (inverse) for (int i = 0; i < n; i++) { real[i] /= n; imag[i] /= n; }
	}

	private static void removeTimingSlope(double[] real, double[] imag) {
		double sx = 0, sy = 0, sxx = 0, sxy = 0, previous = 0, unwrapped = 0;
		for (int p = 0; p < CARRIER_ORDER.length; p++) {
			int i = CARRIER_ORDER[p];
			double r2 = real[i] * real[i] - imag[i] * imag[i];
			double i2 = 2d * real[i] * imag[i];
			double phase = Math.atan2(2d * r2 * i2, r2 * r2 - i2 * i2);
			if (p == 0) unwrapped = phase;
			else {
				double delta = phase - previous;
				while (delta > Math.PI) delta -= 2d * Math.PI;
				while (delta < -Math.PI) delta += 2d * Math.PI;
				unwrapped += delta;
			}
			previous = phase;
			double x = CARRIER_MAP[i];
			sx += x; sy += unwrapped; sxx += x * x; sxy += x * unwrapped;
		}
		double denominator = CARRIERS * sxx - sx * sx;
		double slope = Math.abs(denominator) < 1e-9 ? 0 : (CARRIERS * sxy - sx * sy) / denominator / 4d;
		for (int i = 0; i < CARRIERS; i++) {
			double phase = -slope * CARRIER_MAP[i], cos = Math.cos(phase), sin = Math.sin(phase);
			double r = real[i]; real[i] = r * cos - imag[i] * sin; imag[i] = r * sin + imag[i] * cos;
		}
	}

	private static double fourthPowerRotation(double[] real, double[] imag) {
		double sumReal = 0, sumImag = 0;
		for (int i = 0; i < real.length; i++) {
			double r2 = real[i] * real[i] - imag[i] * imag[i], i2 = 2d * real[i] * imag[i];
			sumReal += r2 * r2 - i2 * i2; sumImag += 2d * r2 * i2;
		}
		double phase = Math.atan2(sumImag, sumReal);
		if (phase < 0d) phase += 2d * Math.PI;
		return (Math.PI - phase) / 4d;
	}

	private static void rotate(double[] real, double[] imag, double phase) {
		double cos = Math.cos(phase), sin = Math.sin(phase);
		for (int i = 0; i < real.length; i++) {
			double r = real[i]; real[i] = r * cos - imag[i] * sin; imag[i] = r * sin + imag[i] * cos;
		}
	}

	static final class Result {
		final String state, countryCode, ensembleLabel, utcTime;
		final int ensembleId, ensembleEcc, validFibs, attemptedFibs, subchannelCount;
		final double quality, rollingQuality;
		final List<Service> services;
		Result(String state, int ensembleId, int ensembleEcc, String countryCode,
				String ensembleLabel, String utcTime,
				int validFibs, int attemptedFibs, double quality, double rollingQuality,
				List<Service> services, int subchannelCount) {
			this.state = state; this.ensembleId = ensembleId; this.ensembleEcc = ensembleEcc;
			this.countryCode = countryCode; this.ensembleLabel = ensembleLabel;
			this.utcTime = utcTime; this.validFibs = validFibs; this.attemptedFibs = attemptedFibs;
			this.quality = quality; this.rollingQuality = rollingQuality;
			this.services = services; this.subchannelCount = subchannelCount;
		}
		static Result empty(String state) {
			return new Result(state, -1, -1, "--", "", "", 0, 0, 0, 0,
					Collections.<Service>emptyList(), 0);
		}
	}

	static final class Service {
		final long id;
		final String label, componentType, protection;
		final int subchannelId, bitrateKbps;
		Service(long id, String label, String componentType, int subchannelId, int bitrateKbps, String protection) {
			this.id = id; this.label = label == null ? "" : label;
			this.componentType = componentType == null ? "" : componentType;
			this.subchannelId = subchannelId; this.bitrateKbps = bitrateKbps; this.protection = protection;
		}
	}

	private static final class MutableService {
		final long id; String label = "", componentType = ""; int subchannelId = -1;
		MutableService(long id) { this.id = id; }
	}

	private static final class Subchannel {
		final int id, start, size, option, level, tableIndex; final boolean shortForm;
		Subchannel(int id, int start, int size, boolean shortForm, int option, int level, int tableIndex) {
			this.id = id; this.start = start; this.size = size; this.shortForm = shortForm;
			this.option = option; this.level = level; this.tableIndex = tableIndex;
		}
		int bitrateKbps() {
			if (shortForm || size < 0) return -1;
			int[] divisor = option == 0 ? new int[] {0,12,8,6,4} : new int[] {0,27,21,18,15};
			return option == 0 ? size * 8 / divisor[level] : size * 32 / divisor[level];
		}
		String protection() {
			return shortForm ? "UEP index " + tableIndex : option == 0 ? "EEP " + level + "-A" : "EEP " + level + "-B";
		}
	}

	private static final class DecodeCandidate {
		final List<byte[]> valid; final int validFibs;
		DecodeCandidate(List<byte[]> valid) { this.valid = valid; this.validFibs = valid.size(); }
	}
	private static final class NullPosition { final int start; NullPosition(int start) { this.start = start; } }
	private static final class Timing {
		final int usefulStart; final double correctionBins, correlation;
		Timing(int usefulStart, double correctionBins, double correlation) {
			this.usefulStart = usefulStart; this.correctionBins = correctionBins; this.correlation = correlation;
		}
	}
	private static final class Correlation {
		final double real, imag, score;
		Correlation(double real, double imag, double score) { this.real = real; this.imag = imag; this.score = score; }
	}
}
