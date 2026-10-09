package jspectrumanalyzer.iq;

import java.io.File;
import java.util.Arrays;
import java.util.Locale;

/** Diagnostic Barker correlator for IEEE 802.11 DSSS recordings. */
final class WifiDsssProbe {
	private static final int[] BARKER = {1,-1,1,1,-1,1,1,1,-1,-1,-1};
	private static final int OUTPUT_RATE = 22_000_000;

	private WifiDsssProbe() { }

	public static void main(String[] args) throws Exception {
		if (args.length == 0) {
			System.err.println("Usage: WifiDsssProbe <iq.pcm|iq.cu8|iq.wav> [startMillis] [durationMillis]");
			return;
		}
		long startMillis = args.length > 1 ? Long.parseLong(args[1]) : 0;
		long durationMillis = args.length > 2 ? Long.parseLong(args[2]) : 200;
		try (IQReplayFile replay = IQReplayFile.open(new File(args[0]))) {
			int inputRate = replay.getSampleRateHz();
			replay.seekMillis(startMillis);
			int inputSamples = (int)Math.min(Integer.MAX_VALUE / 2L,
					durationMillis * inputRate / 1000L);
			byte[] iq = new byte[inputSamples * 2];
			replay.readLoopedSigned(iq);
			for (double cfoMHz = -.8d; cfoMHz >= -1.2d; cfoMHz -= .05d)
				analyze(iq, inputRate, startMillis, cfoMHz * 1e6);
			WifiDsssAnalyzer streaming = new WifiDsssAnalyzer();
			WifiDsssAnalyzer.Result result = null;
			int blockBytes = inputRate / 100 * 2;
			for (int at = 0; at < iq.length; at += blockBytes) {
				int blockLength = Math.min(blockBytes, iq.length - at) & ~1;
				byte[] block = Arrays.copyOfRange(iq, at, at + blockLength);
				result = streaming.analyze(replay.getCenterFrequencyHz(), block, block.length, inputRate);
			}
			System.out.println(String.format(Locale.US,
					"stream analyzer: %s, FCS %s, %s, SSID '%s', channel %d, Barker %.1f%%",
					result.state, result.fcsValid ? "OK" : "--", result.frameType,
					result.ssid, result.channel, result.barkerCorrelation * 100d));
		}
	}

	private static void analyze(byte[] iq, int inputRate, long startMillis, double cfoHz) {
		int inputSamples = iq.length / 2;
		int count = (int)Math.floor(inputSamples * (double)OUTPUT_RATE / inputRate);
		float[] re = new float[count], im = new float[count];
		double step = inputRate / (double)OUTPUT_RATE;
		for (int n = 0; n < count; n++) {
			double position = n * step;
			int left = Math.min(inputSamples - 1, (int)position);
			int right = Math.min(inputSamples - 1, left + 1);
			double f = position - left;
			double r = iq[left * 2] + (iq[right * 2] - iq[left * 2]) * f;
			double q = iq[left * 2 + 1] + (iq[right * 2 + 1] - iq[left * 2 + 1]) * f;
			double angle = -2d * Math.PI * cfoHz * n / OUTPUT_RATE;
			double cos = Math.cos(angle), sin = Math.sin(angle);
			re[n] = (float)(r * cos - q * sin);
			im[n] = (float)(r * sin + q * cos);
		}
		double bestAverage = 0;
		int bestAt = 0, bestRun = 0;
		for (int phase = 0; phase < 22; phase++) {
			double sum = 0;
			int run = 0;
			for (int at = phase; at + 20 < count; at += 22) {
				double cr = 0, ci = 0, power = 0;
				for (int chip = 0; chip < 11; chip++) {
					int index = at + chip * 2;
					cr += re[index] * BARKER[chip];
					ci += im[index] * BARKER[chip];
					power += re[index] * re[index] + im[index] * im[index];
				}
				double metric = Math.hypot(cr, ci) / Math.sqrt(Math.max(1e-12, power * 11d));
				if (metric >= .62d) {
					sum += metric;
					run++;
					if (run >= 12 && sum / run > bestAverage) {
						bestAverage = sum / run;
						bestAt = at - (run - 1) * 22;
						bestRun = run;
					}
				} else {
					sum = 0;
					run = 0;
				}
			}
		}
		System.out.println(String.format(Locale.US,
				"CFO %+.0f kHz: Barker run %d symbols, average %.1f%% at %.3f ms",
				cfoHz / 1000d, bestRun, bestAverage * 100d,
				startMillis + bestAt * 1000d / OUTPUT_RATE));
		if (bestRun >= 64) decodeLongPreamble(re, im, bestAt,
				Math.min(16_384, (re.length - bestAt - 20) / 22));
	}

	private static void decodeLongPreamble(float[] re, float[] im, int start, int symbols) {
		double[] sr = new double[symbols], si = new double[symbols];
		for (int symbol = 0; symbol < symbols; symbol++) {
			int at = start + symbol * 22;
			for (int chip = 0; chip < 11; chip++) {
				int index = at + chip * 2;
				sr[symbol] += re[index] * BARKER[chip];
				si[symbol] += im[index] * BARKER[chip];
			}
		}
		int[] scrambled = new int[symbols - 1];
		for (int n = 1; n < symbols; n++) {
			double differential = sr[n] * sr[n - 1] + si[n] * si[n - 1];
			scrambled[n - 1] = differential < 0 ? 1 : 0;
		}
		for (int polarity = 0; polarity <= 1; polarity++) {
			int[] bits = new int[scrambled.length];
			for (int n = 7; n < scrambled.length; n++)
				bits[n] = scrambled[n] ^ scrambled[n - 4] ^ scrambled[n - 7] ^ polarity;
			String sfd = "0000010111001111";
			for (int at = 7; at + 64 <= bits.length; at++) {
				boolean match = true;
				for (int n = 0; n < 16; n++) match &= bits[at + n] == sfd.charAt(n) - '0';
				if (!match) continue;
				int header = at + 16;
				int signal = byteLsb(bits, header);
				int service = byteLsb(bits, header + 8);
				int length = byteLsb(bits, header + 16) | byteLsb(bits, header + 24) << 8;
				System.out.println(String.format(Locale.US,
						"  long SFD at symbol %d: SIGNAL 0x%02x (%s), SERVICE 0x%02x, LENGTH %d us",
						at, signal, rateName(signal), service, length));
				int payloadAt = header + 48;
				int payloadBits = signal == 0x0a ? length : 0;
				if (payloadBits > 0 && payloadAt + payloadBits <= bits.length) {
					byte[] psdu = new byte[payloadBits / 8];
					for (int n = 0; n < psdu.length; n++) psdu[n] = (byte)byteLsb(bits, payloadAt + n * 8);
					printMac(psdu);
				}
			}
		}
	}

	private static void printMac(byte[] psdu) {
		if (psdu.length < 28) return;
		int frameControl = psdu[0] & 0xff | (psdu[1] & 0xff) << 8;
		long calculated = crc32(psdu, 0, psdu.length - 4);
		long received = (psdu[psdu.length - 4] & 0xffL) | (psdu[psdu.length - 3] & 0xffL) << 8
				| (psdu[psdu.length - 2] & 0xffL) << 16 | (psdu[psdu.length - 1] & 0xffL) << 24;
		String ssid = "--";
		if ((frameControl & 0xfc) == 0x80 && psdu.length >= 40) {
			for (int at = 36; at + 2 <= psdu.length - 4;) {
				int id = psdu[at] & 0xff, size = psdu[at + 1] & 0xff;
				if (at + 2 + size > psdu.length - 4) break;
				if (id == 0) {
					try { ssid = new String(psdu, at + 2, size, "UTF-8"); }
					catch (Exception ignored) { ssid = "<invalid>"; }
					break;
				}
				at += 2 + size;
			}
		}
		System.out.println(String.format(Locale.US,
				"  MAC FC 0x%04x, %d bytes, FCS %s, SSID '%s'",
				frameControl, psdu.length, calculated == received ? "OK" : "bad", ssid));
	}

	private static long crc32(byte[] data, int offset, int length) {
		long crc = 0xffffffffL;
		for (int n = 0; n < length; n++) {
			crc ^= data[offset + n] & 0xff;
			for (int bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) != 0 ? 0xedb88320L : 0);
		}
		return (crc ^ 0xffffffffL) & 0xffffffffL;
	}

	private static int byteLsb(int[] bits, int at) {
		int value = 0;
		for (int n = 0; n < 8; n++) value |= bits[at + n] << n;
		return value;
	}

	private static String rateName(int signal) {
		switch (signal) {
		case 0x0a: return "1 Mb/s";
		case 0x14: return "2 Mb/s";
		case 0x37: return "5.5 Mb/s";
		case 0x6e: return "11 Mb/s";
		default: return "unknown";
		}
	}
}
