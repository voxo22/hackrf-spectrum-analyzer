package jspectrumanalyzer.iq;

import java.io.File;
import java.util.Locale;

/** Command-line probe for checking real Wi-Fi IQ recordings. */
final class WifiProbe {
	private static final int BLOCK_MILLIS = 50;

	private WifiProbe() { }

	public static void main(String[] args) throws Exception {
		if (args.length == 0) {
			System.err.println("Usage: WifiProbe <iq.wav|iq.pcm> [maxMillis] [startMillis]");
			return;
		}
		File file = new File(args[0]);
		long requestedMillis = args.length > 1 ? Long.parseLong(args[1]) : Long.MAX_VALUE;
		long startMillis = args.length > 2 ? Long.parseLong(args[2]) : 0L;
		try (IQReplayFile replay = IQReplayFile.open(file)) {
			int rate = replay.getSampleRateHz();
			replay.seekMillis(startMillis);
			long durationMillis = Math.min(requestedMillis,
					Math.max(0L, replay.getDurationMillis() - startMillis));
			long remainingSamples = Math.max(1L, durationMillis * rate / 1000L);
			WifiSignalAnalyzer analyzer = new WifiSignalAnalyzer();
			Spectrum spectrum = new Spectrum();
			long processed = 0, lastDetected = 0, lastDecoded = 0, blocks = 0, analyzeNanos = 0;
			double sumPower = 0, peak = 0, bestShort = 0;
			long values = 0, clipped = 0;
			while (remainingSamples > 0) {
				int samples = (int)Math.min(Math.max(1, rate * BLOCK_MILLIS / 1000), remainingSamples);
				byte[] iq = new byte[samples * 2];
				replay.readLoopedSigned(iq);
				if (blocks++ % 10 == 0) spectrum.add(iq);
				for (byte value : iq) {
					double normalized = value / 128d;
					sumPower += normalized * normalized;
					peak = Math.max(peak, Math.abs(normalized));
					if (value == Byte.MIN_VALUE || value == Byte.MAX_VALUE) clipped++;
					values++;
				}
				bestShort = Math.max(bestShort, bestShortCorrelation(iq));
				long analyzeStarted = System.nanoTime();
				WifiSignalAnalyzer.Result result = analyzer.analyze(iq, iq.length, rate);
				analyzeNanos += System.nanoTime() - analyzeStarted;
				if (result.detectedPreambles != lastDetected || result.decodedPackets != lastDecoded) {
					System.out.println(String.format(Locale.US,
							"%7.1f ms preambles %d decoded %d: %s, LTF %.1f%% CFO %+.1f kHz SNR %.1f dB EVM %.1f%% %s %d B",
							processed * 1000d / rate, result.detectedPreambles, result.decodedPackets,
							result.state, result.ltfCorrelation * 100d, result.cfoHz / 1000d,
							result.snrDb, result.evmPercent, result.rate, result.lengthBytes));
					lastDetected = result.detectedPreambles;
					lastDecoded = result.decodedPackets;
				}
				processed += samples;
				remainingSamples -= samples;
			}
			System.out.println(String.format(Locale.US,
					"%s: %.3f MS/s, %.3f s, RMS %.1f/127, peak %.0f/127, clipped %.4f%%, raw/analyzer best STF %.1f%%/%.1f%%, sustained STF %d, best LTF %.1f%%/repeat %.1f%%, LTF locks %d, decoded %d",
					file.getName(), rate / 1_000_000d, processed / (double)rate,
					Math.sqrt(sumPower / Math.max(1L, values)) * 128d, peak * 127d,
					clipped * 100d / Math.max(1L, values), bestShort * 100d,
					analyzer.diagnosticBestShort() * 100d,
					analyzer.diagnosticShortRuns(), analyzer.diagnosticBestLtf() * 100d,
					analyzer.diagnosticBestLtfRepeat() * 100d,
					lastDetected, lastDecoded));
			System.out.println("  spectrum (dB relative, 1 MHz bands -10..+10): " + spectrum.format());
			System.out.println(String.format(Locale.US, "  analyzer CPU %.3f s (%.2fx real time)",
					analyzeNanos / 1e9, processed / (double)rate / Math.max(1e-9, analyzeNanos / 1e9)));
		}
	}

	private static final class Spectrum {
		private static final int SIZE = 1024;
		private final double[] power = new double[SIZE];
		private int frames;

		void add(byte[] iq) {
			int samples = iq.length / 2;
			for (int frame = 0; frame < 8; frame++) {
				int start = (int)((long)frame * Math.max(0, samples - SIZE) / 7L);
				double[] real = new double[SIZE], imag = new double[SIZE];
				for (int n = 0; n < SIZE; n++) {
					double window = .5d - .5d * Math.cos(2d * Math.PI * n / (SIZE - 1d));
					real[n] = iq[(start + n) * 2] * window;
					imag[n] = iq[(start + n) * 2 + 1] * window;
				}
				fft(real, imag);
				for (int n = 0; n < SIZE; n++) power[n] += real[n] * real[n] + imag[n] * imag[n];
				frames++;
			}
		}

		String format() {
			double[] bands = new double[20];
			for (int bin = 0; bin < SIZE; bin++) {
				int shifted = (bin + SIZE / 2) & (SIZE - 1);
				int band = Math.min(19, shifted * 20 / SIZE);
				bands[band] += power[bin] / Math.max(1, frames);
			}
			double peak = 0;
			for (double band : bands) peak = Math.max(peak, band);
			StringBuilder text = new StringBuilder();
			for (int n = 0; n < bands.length; n++) {
				if (n > 0) text.append(' ');
				text.append(String.format(Locale.US, "%+.0f", 10d * Math.log10(Math.max(1e-30, bands[n] / peak))));
			}
			return text.toString();
		}

		private static void fft(double[] real, double[] imag) {
			int size = real.length;
			for (int i = 1, j = 0; i < size; i++) {
				int bit = size >> 1;
				for (; (j & bit) != 0; bit >>= 1) j ^= bit;
				j ^= bit;
				if (i < j) {
					double value = real[i]; real[i] = real[j]; real[j] = value;
					value = imag[i]; imag[i] = imag[j]; imag[j] = value;
				}
			}
			for (int length = 2; length <= size; length <<= 1) {
				double angle = -2d * Math.PI / length, baseR = Math.cos(angle), baseI = Math.sin(angle);
				for (int start = 0; start < size; start += length) {
					double wr = 1, wi = 0;
					for (int n = 0; n < length / 2; n++) {
						int a = start + n, b = a + length / 2;
						double br = real[b] * wr - imag[b] * wi;
						double bi = real[b] * wi + imag[b] * wr;
						real[b] = real[a] - br; imag[b] = imag[a] - bi;
						real[a] += br; imag[a] += bi;
						double nextR = wr * baseR - wi * baseI;
						wi = wr * baseI + wi * baseR; wr = nextR;
					}
				}
			}
		}
	}

	private static double bestShortCorrelation(byte[] iq) {
		int samples = iq.length / 2;
		if (samples < 80) return 0;
		double cr = 0, ci = 0, firstPower = 0, secondPower = 0, best = 0;
		for (int n = 0; n < 64; n++) {
			int a = n * 2, b = (n + 16) * 2;
			double ar = iq[a], ai = iq[a + 1], br = iq[b], bi = iq[b + 1];
			cr += ar * br + ai * bi; ci += ar * bi - ai * br;
			firstPower += ar * ar + ai * ai; secondPower += br * br + bi * bi;
		}
		for (int at = 0; at <= samples - 80; at++) {
			if ((at & 3) == 0 && secondPower / 64d >= 9d)
				best = Math.max(best, Math.hypot(cr, ci) / Math.sqrt(Math.max(1d, firstPower * secondPower)));
			if (at == samples - 80) break;
			int oa = at * 2, ob = (at + 16) * 2;
			double ar = iq[oa], ai = iq[oa + 1], br = iq[ob], bi = iq[ob + 1];
			cr -= ar * br + ai * bi; ci -= ar * bi - ai * br;
			firstPower -= ar * ar + ai * ai; secondPower -= br * br + bi * bi;
			int na = (at + 64) * 2, nb = (at + 80) * 2;
			ar = iq[na]; ai = iq[na + 1]; br = iq[nb]; bi = iq[nb + 1];
			cr += ar * br + ai * bi; ci += ar * bi - ai * br;
			firstPower += ar * ar + ai * ai; secondPower += br * br + bi * bi;
		}
		return best;
	}
}
