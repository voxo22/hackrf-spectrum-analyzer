package jspectrumanalyzer.iq;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public class IQBridgeSmokeTest {
	private static final long DEFAULT_CENTER_FREQ_HZ = 100_000_000L;
	private static final int DEFAULT_SAMPLE_RATE_HZ = 10_000_000;
	private static final int DEFAULT_BASEBAND_FILTER_HZ = 0;
	private static final int DEFAULT_LNA_GAIN = 16;
	private static final int DEFAULT_VGA_GAIN = 20;
	private static final int DEFAULT_SECONDS = 5;

	public static void main(String[] args) throws Exception {
		long centerFreqHz = args.length > 0 ? parseFrequencyHz(args[0]) : DEFAULT_CENTER_FREQ_HZ;
		int sampleRateHz = args.length > 1 ? parseInt(args[1], "sample rate") : DEFAULT_SAMPLE_RATE_HZ;
		int seconds = args.length > 2 ? parseInt(args[2], "seconds") : DEFAULT_SECONDS;
		int lnaGain = args.length > 3 ? parseInt(args[3], "lna gain") : DEFAULT_LNA_GAIN;
		int vgaGain = args.length > 4 ? parseInt(args[4], "vga gain") : DEFAULT_VGA_GAIN;

		AtomicLong bytes = new AtomicLong();
		AtomicLong blocks = new AtomicLong();
		AtomicLong firstCallbackNanos = new AtomicLong();
		AtomicLong lastCallbackNanos = new AtomicLong();

		Thread iqThread = new Thread(() -> {
			int result = HackRFIQNativeBridge.start((callbackCenterFreqHz, callbackSampleRateHz, iqData) -> {
				long now = System.nanoTime();
				firstCallbackNanos.compareAndSet(0, now);
				lastCallbackNanos.set(now);
				blocks.incrementAndGet();
				bytes.addAndGet(iqData.length);
			}, centerFreqHz, sampleRateHz, DEFAULT_BASEBAND_FILTER_HZ, lnaGain, vgaGain, false);

			if (result != 0) {
				System.err.println("hackrf_iq_lib_start returned " + result);
			}
		}, "hackrf-iq-smoke-test");

		System.out.println("Starting IQ stream");
		System.out.println("Center: " + centerFreqHz + " Hz, sample rate: " + sampleRateHz + " Hz, duration: "
				+ seconds + " s");

		long startNanos = System.nanoTime();
		iqThread.start();

		for (int second = 1; second <= seconds; second++) {
			Thread.sleep(1000);
			printStats("t+" + second + "s", bytes.get(), blocks.get(), startNanos);
		}

		HackRFIQNativeBridge.stop();
		iqThread.join(3000);

		printStats("final", bytes.get(), blocks.get(), startNanos);
		long first = firstCallbackNanos.get();
		long last = lastCallbackNanos.get();
		if (first == 0) {
			System.out.println("No IQ callbacks received.");
		} else {
			double activeSeconds = Math.max(0.001, (last - first) / 1_000_000_000d);
			double mbps = bytes.get() / activeSeconds / (1024d * 1024d);
			System.out.println(String.format(Locale.US, "Callback-active throughput: %.2f MiB/s", mbps));
		}
	}

	private static void printStats(String label, long bytes, long blocks, long startNanos) {
		double seconds = Math.max(0.001, (System.nanoTime() - startNanos) / 1_000_000_000d);
		double mb = bytes / (1024d * 1024d);
		double mbps = mb / seconds;
		System.out.println(String.format(Locale.US, "%s: blocks=%d bytes=%d %.2f MiB %.2f MiB/s", label, blocks,
				bytes, mb, mbps));
	}

	private static long parseFrequencyHz(String text) {
		String value = text.trim().toLowerCase(Locale.US);
		double multiplier = 1d;
		if (value.endsWith("mhz")) {
			multiplier = 1_000_000d;
			value = value.substring(0, value.length() - 3);
		} else if (value.endsWith("khz")) {
			multiplier = 1_000d;
			value = value.substring(0, value.length() - 3);
		} else if (value.endsWith("hz")) {
			value = value.substring(0, value.length() - 2);
		}
		return Math.round(Double.parseDouble(value.trim()) * multiplier);
	}

	private static int parseInt(String text, String name) {
		try {
			return Integer.parseInt(text.trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Invalid " + name + ": " + text, e);
		}
	}
}
