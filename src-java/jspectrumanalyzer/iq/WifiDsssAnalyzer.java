package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Streaming IEEE 802.11 DSSS long-preamble/1 Mb/s PSDU decoder. */
final class WifiDsssAnalyzer {
	private static final int SYMBOL_RATE_HZ = 1_000_000;
	private static final int CHIP_RATE_HZ = 11_000_000;
	private static final int HISTORY_MILLIS = 8;
	private static final int[] BARKER = {1,-1,1,1,-1,1,1,1,-1,-1,-1};
	private static final String LONG_SFD = "0000010111001111";

	private byte[] history = new byte[0];
	private int historySampleRateHz;
	private long detectedPackets, decodedPackets;
	private Result latest = Result.empty("Searching for Wi-Fi DSSS preamble");
	private final List<Result> observations = new ArrayList<Result>();

	synchronized void reset() {
		history = new byte[0];
		historySampleRateHz = 0;
		detectedPackets = decodedPackets = 0;
		latest = Result.empty("Searching for Wi-Fi DSSS preamble");
		observations.clear();
	}

	synchronized void discardOverlap() {
		history = new byte[0];
		historySampleRateHz = 0;
		observations.clear();
	}

	synchronized Result analyze(long centerFreqHz, byte[] iq, int length, int sampleRateHz) {
		observations.clear();
		int usable = Math.min(iq == null ? 0 : iq.length, length) & ~1;
		if (usable < 2 || sampleRateHz <= 0) return latest;
		if (historySampleRateHz != sampleRateHz) {
			history = new byte[0];
			historySampleRateHz = sampleRateHz;
		}
		int carriedSamples = history.length / 2;
		byte[] combined = new byte[history.length + usable];
		System.arraycopy(history, 0, combined, 0, history.length);
		System.arraycopy(iq, 0, combined, history.length, usable);
		Result found = scan(centerFreqHz, combined, sampleRateHz, carriedSamples);
		int keepSamples = Math.min(combined.length / 2, sampleRateHz * HISTORY_MILLIS / 1000);
		history = Arrays.copyOfRange(combined, combined.length - keepSamples * 2, combined.length);
		if (found != null && (found.fcsValid || !latest.fcsValid)) latest = found;
		return latest;
	}

	/** Returns every newly decoded, FCS-valid network announcement from the last block. */
	synchronized List<Result> takeObservations() {
		List<Result> result = new ArrayList<Result>(observations);
		observations.clear();
		return result;
	}

	private Result scan(long centerFreqHz, byte[] iq, int sampleRateHz, int carriedSamples) {
		int samples = iq.length / 2;
		int symbolSamples = Math.max(1, (int)Math.round(sampleRateHz / (double)SYMBOL_RATE_HZ));
		if (samples < symbolSamples * 220) return null;
		double shiftHz = nearestWifiChannelHz(centerFreqHz) - centerFreqHz;
		float[] re = new float[samples], im = new float[samples];
		double angle = -2d * Math.PI * shiftHz / sampleRateHz;
		double stepR = Math.cos(angle), stepI = Math.sin(angle), phaseR = 1d, phaseI = 0d;
		for (int n = 0; n < samples; n++) {
			double rawR = iq[n * 2], rawI = iq[n * 2 + 1];
			re[n] = (float)(rawR * phaseR - rawI * phaseI);
			im[n] = (float)(rawR * phaseI + rawI * phaseR);
			double nextR = phaseR * stepR - phaseI * stepI;
			phaseI = phaseR * stepI + phaseI * stepR;
			phaseR = nextR;
			if ((n & 4095) == 4095) {
				double scale = 1d / Math.max(1e-12, Math.hypot(phaseR, phaseI));
				phaseR *= scale; phaseI *= scale;
			}
		}

		int bins = samples / symbolSamples;
		double[] power = new double[bins], sorted = new double[bins];
		for (int bin = 0; bin < bins; bin++) {
			double value = 0;
			int start = bin * symbolSamples;
			for (int n = 0; n < symbolSamples; n++)
				value += re[start + n] * re[start + n] + im[start + n] * im[start + n];
			power[bin] = value / symbolSamples;
		}
		System.arraycopy(power, 0, sorted, 0, bins);
		Arrays.sort(sorted);
		double noise = sorted[Math.max(0, bins / 2)];
		double threshold = Math.max(16d, noise * 2.0d);
		Result best = null;
		int bin = 0;
		while (bin < bins) {
			while (bin < bins && power[bin] < threshold) bin++;
			int first = bin;
			int lastStrong = bin, gaps = 0;
			while (bin < bins && gaps <= 8) {
				if (power[bin] >= threshold) { lastStrong = bin; gaps = 0; }
				else gaps++;
				bin++;
			}
			if (lastStrong - first < 80) continue;
			int coarseStart = first * symbolSamples;
			for (int timing = -symbolSamples * 2; timing <= symbolSamples * 2; timing++) {
				int start = coarseStart + timing;
				if (start < 0) continue;
				Decode decoded = decodeAt(re, im, start, sampleRateHz, shiftHz);
				if (decoded == null) continue;
				int packetEnd = start + decoded.symbols * symbolSamples;
				if (packetEnd <= carriedSamples) break;
				detectedPackets++;
				if (decoded.fcsValid) decodedPackets++;
				Result candidate = decoded.toResult(centerFreqHz, detectedPackets, decodedPackets);
				if (candidate.fcsValid) observations.add(candidate);
				if (best == null || candidate.fcsValid && !best.fcsValid
						|| candidate.quality > best.quality) best = candidate;
				bin = Math.max(bin, packetEnd / symbolSamples);
				break;
			}
		}
		return best;
	}

	private Decode decodeAt(float[] re, float[] im, int start, int sampleRateHz, double shiftHz) {
		int symbolSamples = Math.max(1, (int)Math.round(sampleRateHz / (double)SYMBOL_RATE_HZ));
		int availableSymbols = Math.min(16_384, (re.length - start - symbolSamples) / symbolSamples);
		if (availableSymbols < 240) return null;
		int headerSymbols = 240;
		double[] sr = new double[headerSymbols], si = new double[headerSymbols], metric = new double[headerSymbols];
		correlateSymbols(re, im, start, sampleRateHz, sr, si, metric, 0, headerSymbols);
		for (int polarity = 0; polarity <= 1; polarity++) {
			int[] bits = descramble(sr, si, polarity);
			for (int sfdAt = 96; sfdAt <= 160 && sfdAt + 64 <= bits.length; sfdAt++) {
				if (!matches(bits, sfdAt, LONG_SFD)) continue;
				int header = sfdAt + 16;
				int signal = byteLsb(bits, header);
				int service = byteLsb(bits, header + 8);
				int lengthMicros = byteLsb(bits, header + 16) | byteLsb(bits, header + 24) << 8;
				if (signal != 0x0a || lengthMicros < 32 || lengthMicros > 12_000) continue;
				int payloadAt = header + 48, payloadBits = lengthMicros;
				int requiredSymbols = payloadAt + payloadBits + 1;
				if (requiredSymbols > availableSymbols) return null;
				double correlation = 0;
				int correlationSymbols = Math.min(sfdAt + 64, metric.length);
				for (int n = 0; n < correlationSymbols; n++) correlation += metric[n];
				correlation /= Math.max(1, correlationSymbols);
				if (correlation < .60d) continue;
				if (requiredSymbols > sr.length) {
					double[] fullR = Arrays.copyOf(sr, requiredSymbols);
					double[] fullI = Arrays.copyOf(si, requiredSymbols);
					correlateSymbols(re, im, start, sampleRateHz, fullR, fullI, null,
							sr.length, requiredSymbols);
					sr = fullR; si = fullI;
					bits = descramble(sr, si, polarity);
				}
				byte[] psdu = new byte[payloadBits / 8];
				for (int n = 0; n < psdu.length; n++) psdu[n] = (byte)byteLsb(bits, payloadAt + n * 8);
				boolean fcsValid = WifiMacFrame.validFcs(psdu);
				double levelDbfs = packetLevelDbfs(re, im, start,
						Math.min(re.length, start + requiredSymbols * symbolSamples));
				return new Decode(signal, service, psdu, fcsValid, correlation, shiftHz,
						levelDbfs, requiredSymbols);
			}
		}
		return null;
	}

	private static double packetLevelDbfs(float[] re, float[] im, int start, int end) {
		if (end <= start) return Double.NaN;
		double power = 0d;
		for (int n = start; n < end; n++) power += re[n] * re[n] + im[n] * im[n];
		double normalized = power / ((end - start) * 2d * 128d * 128d);
		return 10d * Math.log10(Math.max(1e-12, normalized));
	}

	private static void correlateSymbols(float[] re, float[] im, int start, int sampleRateHz,
			double[] sr, double[] si, double[] metric, int firstSymbol, int endSymbol) {
		for (int symbol = firstSymbol; symbol < endSymbol; symbol++) {
			double base = start + symbol * (sampleRateHz / (double)SYMBOL_RATE_HZ);
			double energy = 0;
			for (int chip = 0; chip < 11; chip++) {
				double position = base + chip * (sampleRateHz / (double)CHIP_RATE_HZ);
				int left = (int)position;
				if (left + 1 >= re.length) return;
				double f = position - left;
				double cr = re[left] + (re[left + 1] - re[left]) * f;
				double ci = im[left] + (im[left + 1] - im[left]) * f;
				sr[symbol] += cr * BARKER[chip];
				si[symbol] += ci * BARKER[chip];
				energy += cr * cr + ci * ci;
			}
			if (metric != null) metric[symbol] = Math.hypot(sr[symbol], si[symbol])
					/ Math.sqrt(Math.max(1e-12, energy * 11d));
		}
	}

	private static int[] descramble(double[] sr, double[] si, int polarity) {
		int[] scrambled = new int[sr.length - 1];
		for (int n = 1; n < sr.length; n++)
			scrambled[n - 1] = sr[n] * sr[n - 1] + si[n] * si[n - 1] < 0 ? 1 : 0;
		int[] bits = new int[scrambled.length];
		for (int n = 7; n < scrambled.length; n++)
			bits[n] = scrambled[n] ^ scrambled[n - 4] ^ scrambled[n - 7] ^ polarity;
		return bits;
	}

	private static long nearestWifiChannelHz(long centerHz) {
		if (centerHz >= 2_400_000_000L && centerHz <= 2_500_000_000L) {
			long channel = Math.max(1, Math.min(13, Math.round((centerHz - 2_412_000_000d) / 5_000_000d) + 1));
			long regular = 2_412_000_000L + (channel - 1) * 5_000_000L;
			return Math.abs(centerHz - 2_484_000_000L) < Math.abs(centerHz - regular)
					? 2_484_000_000L : regular;
		}
		return Math.round(centerHz / 5_000_000d) * 5_000_000L;
	}

	private static boolean matches(int[] bits, int at, String pattern) {
		for (int n = 0; n < pattern.length(); n++)
			if (bits[at + n] != pattern.charAt(n) - '0') return false;
		return true;
	}

	private static int byteLsb(int[] bits, int at) {
		int value = 0;
		for (int n = 0; n < 8; n++) value |= bits[at + n] << n;
		return value;
	}

	private static final class Decode {
		final int signal, service, symbols;
		final byte[] psdu;
		final boolean fcsValid;
		final double correlation, cfoHz, levelDbfs;
		Decode(int signal, int service, byte[] psdu, boolean fcsValid, double correlation,
				double cfoHz, double levelDbfs, int symbols) {
			this.signal = signal; this.service = service; this.psdu = psdu; this.fcsValid = fcsValid;
			this.correlation = correlation; this.cfoHz = cfoHz; this.levelDbfs = levelDbfs;
			this.symbols = symbols;
		}

		Result toResult(long centerFreqHz, long detected, long decoded) {
			WifiMacFrame mac = WifiMacFrame.parse(psdu, centerFreqHz);
			double quality = Math.max(0d, Math.min(100d, (correlation - .5d) * 200d));
			if (!fcsValid) quality = Math.min(quality, 54d);
			String state = fcsValid ? "Wi-Fi DSSS frame decoded"
					: "Wi-Fi DSSS PLCP decoded; bad MAC FCS";
			return new Result(true, fcsValid, state, quality, correlation, cfoHz, levelDbfs, "1 Mb/s",
					psdu.length, mac, detected, decoded, System.currentTimeMillis());
		}
	}

	static final class Result {
		final boolean detected, fcsValid;
		final String state;
		final double quality, barkerCorrelation, cfoHz, levelDbfs;
		final String rate;
		final int lengthBytes;
		final String ssid, bssid, frameType, security, supportedRates;
		final int channel;
		final int beaconIntervalTu;
		final long detectedPackets, decodedPackets, decodedMillis;
		final WifiMacFrame mac;

		Result(boolean detected, boolean fcsValid, String state, double quality, double barkerCorrelation,
				double cfoHz, double levelDbfs, String rate, int lengthBytes, WifiMacFrame mac,
				long detectedPackets, long decodedPackets, long decodedMillis) {
			this.detected = detected; this.fcsValid = fcsValid; this.state = state; this.quality = quality;
			this.barkerCorrelation = barkerCorrelation; this.cfoHz = cfoHz;
			this.levelDbfs = levelDbfs; this.rate = rate;
			this.lengthBytes = lengthBytes; this.mac = mac == null ? WifiMacFrame.empty() : mac;
			this.ssid = this.mac.ssid; this.bssid = this.mac.bssid; this.channel = this.mac.channel;
			this.frameType = this.mac.frameType; this.security = this.mac.security;
			this.supportedRates = this.mac.supportedRates; this.beaconIntervalTu = this.mac.beaconIntervalTu;
			this.detectedPackets = detectedPackets;
			this.decodedPackets = decodedPackets; this.decodedMillis = decodedMillis;
		}

		static Result empty(String state) {
			return new Result(false, false, state, 0, 0, 0, Double.NaN, "--", 0,
					WifiMacFrame.empty(), 0, 0, 0);
		}
	}
}
