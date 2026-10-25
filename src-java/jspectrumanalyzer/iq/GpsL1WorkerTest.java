package jspectrumanalyzer.iq;

import java.util.concurrent.atomic.AtomicReference;

/** Replay discontinuities and bounded-input overflow must invalidate old results. */
public final class GpsL1WorkerTest {
	public static void main(String[] args) throws Exception {
		AtomicReference<GpsL1SignalAnalyzer.Result> latest = new AtomicReference<GpsL1SignalAnalyzer.Result>();
		try (GpsL1Worker worker = new GpsL1Worker(latest::set)) {
			byte[] iq = new byte[104_000];
			worker.offer(GpsL1SignalAnalyzer.L1_FREQUENCY_HZ, 2_600_000, iq, iq.length);
			Thread.sleep(50);
			worker.discontinuity();
			Thread.sleep(2500);
			if (latest.get() == null || !latest.get().state.startsWith("Replay position changed"))
				throw new AssertionError("Old acquisition result survived a replay seek");
			if (worker.overflowCount() != 0) throw new AssertionError("Replay seek is not a backlog overflow");
			worker.offer(1_500_000_000L, 2_600_000, iq, iq.length);
			long deadline = System.nanoTime() + 3_000_000_000L;
			while (latest.get().state.startsWith("Replay position changed") && System.nanoTime() < deadline) Thread.sleep(10);
			if (!latest.get().state.startsWith("IQ window must cover")) throw new AssertionError("No new result after seek");
		}
		try (GpsL1Worker worker = new GpsL1Worker(latest::set)) {
			byte[] oversized = new byte[16_000_002];
			worker.offer(1_500_000_000L, 2_600_000, oversized, oversized.length);
			if (worker.overflowCount() != 1) throw new AssertionError("Oversized IQ must report a backlog reset");
		}
		latest.set(null);
		try (GpsL1Worker worker = new GpsL1Worker(latest::set)) {
			int rate = 5_000_000, prn = 7, dopplerHz = 1_500, samples = rate / 25;
			int[] code = GpsL1SignalAnalyzer.caCode(prn);
			byte[] wideIq = new byte[samples * 2];
			for (int n = 0; n < samples; n++) {
				int chip = (int) ((long) n * 1_023_000L / rate) % 1023;
				double phase = 2d * Math.PI * dopplerHz * n / rate;
				wideIq[2 * n] = (byte) Math.round(45d * code[chip] * Math.cos(phase));
				wideIq[2 * n + 1] = (byte) Math.round(45d * code[chip] * Math.sin(phase));
			}
			worker.offer(GpsL1SignalAnalyzer.L1_FREQUENCY_HZ, rate, wideIq, wideIq.length);
			long deadline = System.nanoTime() + 5_000_000_000L;
			while ((latest.get() == null || latest.get().acquiredCount == 0) && System.nanoTime() < deadline) Thread.sleep(10);
			GpsL1SignalAnalyzer.Result result = latest.get();
			if (result == null || result.acquiredCount == 0 || result.sampleRateHz > 4_096_000)
				throw new AssertionError("Wide GPS input was not channelized and acquired: "
						+ (result == null ? "no result" : result.state + " at " + result.sampleRateHz));
		}
		System.out.println("GPS worker replay discontinuity and backlog reset tests passed");
	}
}
