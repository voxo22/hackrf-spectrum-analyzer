package jspectrumanalyzer.iq;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/** Replays WAV at wall-clock speed in UI-sized 10 ms blocks into the same worker. */
public final class GpsL1RealtimeReplayTest {
	public static void main(String[] args) throws Exception {
		if (args.length != 3) throw new IllegalArgumentException("WAV path, start second, duration seconds required");
		AtomicReference<GpsL1SignalAnalyzer.Result> latest = new AtomicReference<GpsL1SignalAnalyzer.Result>();
		try (RandomAccessFile input = new RandomAccessFile(new File(args[0]), "r");
				GpsL1Worker worker = new GpsL1Worker(latest::set)) {
			input.seek(24); int rate = Integer.reverseBytes(input.readInt()), bytesPerChunk = (rate / 1000) * 20;
			long offset = dataOffset(input), first = Math.round(Double.parseDouble(args[1]) * 100d);
			int chunks = (int) Math.round(Double.parseDouble(args[2]) * 100d);
			input.seek(offset + first * bytesPerChunk);
			long start = System.nanoTime(); int maximumQueued = 0;
			for (int chunk = 0; chunk < chunks; chunk++) {
				byte[] iq = new byte[bytesPerChunk]; input.readFully(iq);
				for (int i = 0; i < iq.length; i++) iq[i] = (byte) ((iq[i] & 0xff) - 128);
				long remaining = start + chunk * 10_000_000L - System.nanoTime();
				if (remaining > 0) Thread.sleep(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
				worker.offer(GpsL1SignalAnalyzer.L1_FREQUENCY_HZ, rate, iq, iq.length);
				maximumQueued = Math.max(maximumQueued, worker.queuedBytes());
				if (chunk > 0 && chunk % 500 == 0)
					System.out.println("Realtime +" + chunk / 100 + " s; queued " + worker.queuedBytes() + " bytes; resets " + worker.overflowCount()
							+ "; max acquisition " + worker.maximumAnalyzeNanos() / 1_000_000 + " ms; max tracking chunk " + worker.maximumStreamNanos() / 1_000_000 + " ms");
			}
			long deadline = System.nanoTime() + 10_000_000_000L;
			while (worker.queuedBytes() > 0 && System.nanoTime() < deadline) Thread.sleep(50);
			Thread.sleep(750);
			GpsL1SignalAnalyzer.Result result = latest.get();
			if (worker.overflowCount() != 0) throw new AssertionError("Real-time GPS queue reset " + worker.overflowCount() + " times; max queue " + maximumQueued
					+ "; max acquisition " + worker.maximumAnalyzeNanos() / 1_000_000 + " ms; max tracking chunk " + worker.maximumStreamNanos() / 1_000_000 + " ms");
			if (worker.queuedBytes() != 0) throw new AssertionError("Real-time GPS queue did not drain");
			if (result == null || !result.position.plausible) throw new AssertionError("No plausible real-time position: " + (result == null ? "no results" : result.position.state));
			System.out.println(String.format(Locale.US, "Real-time position: %s %.7f %.7f %.1f m, PDOP %.1f; PRNs %s; UTC %s; no queue resets; max queue %d bytes",
					result.position.state, result.position.latitude, result.position.longitude, result.position.altitude, result.position.pdop,
					result.position.usedPrns, result.utc, maximumQueued));
		}
	}
	private static long dataOffset(RandomAccessFile input) throws Exception {
		long position = 12;
		while (position + 8 <= input.length()) {
			input.seek(position); int id = input.readInt(), length = Integer.reverseBytes(input.readInt());
			if (id == 0x64617461) return position + 8;
			position += 8L + (length & 0xffffffffL) + (length & 1);
		}
		throw new IllegalArgumentException("WAV data chunk missing");
	}
}
