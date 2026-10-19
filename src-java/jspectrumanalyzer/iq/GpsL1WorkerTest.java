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
		System.out.println("GPS worker replay discontinuity and backlog reset tests passed");
	}
}
