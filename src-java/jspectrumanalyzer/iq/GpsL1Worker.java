package jspectrumanalyzer.iq;

import java.util.ArrayDeque;
import java.util.function.Consumer;

/** Bounded, ordered GPS IQ worker shared by the UI and real-time replay test. */
final class GpsL1Worker implements AutoCloseable {
	/* About three seconds at 2.6 MS/s: absorbs short acquisition/GC spikes,
	 * while still flagging sustained overload instead of silently losing IQ. */
	private static final int MAX_QUEUED_BYTES = 16_000_000;
	private final ArrayDeque<Chunk> queue = new ArrayDeque<Chunk>();
	private final GpsL1SignalAnalyzer analyzer = new GpsL1SignalAnalyzer();
	private final Consumer<GpsL1SignalAnalyzer.Result> onResult;
	private final Thread worker;
	private volatile boolean closed;
	private volatile long overflowCount;
	private volatile long maximumAnalyzeNanos, maximumStreamNanos;
	private int queuedBytes;
	private long generation;
	private boolean pendingReset;

	GpsL1Worker(Consumer<GpsL1SignalAnalyzer.Result> onResult) {
		this.onResult = onResult;
		worker = new Thread(this::run, "GPS L1 C/A acquisition");
		worker.setDaemon(true); worker.start();
	}
	void offer(long centerHz, int sampleRate, byte[] iq, int length) {
		if (iq == null || length < 2 || sampleRate <= 0) return;
		int available = Math.min(length, iq.length) & ~1;
		boolean truncated = available > MAX_QUEUED_BYTES;
		int copyLength = Math.min(available, MAX_QUEUED_BYTES) & ~1;
		byte[] copy = new byte[copyLength]; System.arraycopy(iq, available - copyLength, copy, 0, copyLength);
		synchronized (queue) {
			if (closed) return;
			boolean overflow = truncated || queuedBytes + copyLength > MAX_QUEUED_BYTES;
			if (overflow) {
				queue.clear(); queuedBytes = 0; overflowCount++; generation++;
				onResult.accept(GpsL1SignalAnalyzer.Result.empty(centerHz, sampleRate, "IQ backlog reset; reacquiring GPS"));
			}
			boolean reset = overflow || pendingReset; pendingReset = false;
			queue.addLast(new Chunk(copy, centerHz, sampleRate, reset, generation)); queuedBytes += copyLength; queue.notifyAll();
		}
	}
	/** A replay seek or loop is a discontinuity, not a long GPS transmission. */
	void discontinuity() {
		synchronized (queue) {
			if (closed) return;
			generation++; queue.clear(); queuedBytes = 0; pendingReset = true;
			onResult.accept(GpsL1SignalAnalyzer.Result.empty(0L, 0, "Replay position changed; reacquiring GPS"));
		}
	}
	long overflowCount() { return overflowCount; }
	long maximumAnalyzeNanos() { return maximumAnalyzeNanos; }
	long maximumStreamNanos() { return maximumStreamNanos; }
	int queuedBytes() { synchronized (queue) { return queuedBytes; } }
	@Override public void close() {
		closed = true;
		synchronized (queue) { queue.clear(); queuedBytes = 0; queue.notifyAll(); }
		worker.interrupt();
	}
	private void run() {
		byte[] history = new byte[0]; int historyLength = 0, historyRate = 0;
		long historyCenter = Long.MIN_VALUE, samplesSinceAcquisition = 0L;
		boolean haveAcquisition = false;
		while (!closed) {
			Chunk chunk;
			synchronized (queue) {
				while (queue.isEmpty() && !closed) {
					try { queue.wait(); } catch (InterruptedException e) { if (closed) return; }
				}
				if (closed) return;
				chunk = queue.removeFirst(); queuedBytes -= chunk.iq.length;
			}
			if (chunk.reset || chunk.centerHz != historyCenter || chunk.rate != historyRate) {
				analyzer.resetStream(); historyLength = 0; historyCenter = chunk.centerHz; historyRate = chunk.rate;
				samplesSinceAcquisition = 0L; haveAcquisition = false;
			}
			long started = System.nanoTime();
			analyzer.acceptStream(chunk.centerHz, chunk.rate, chunk.iq, chunk.iq.length);
			maximumStreamNanos = Math.max(maximumStreamNanos, System.nanoTime() - started);
			samplesSinceAcquisition += chunk.iq.length / 2;
			int wanted = Math.max(2, (int) Math.round(chunk.rate / 1000d) * 2 * 20);
			if (history.length != wanted) { history = new byte[wanted]; historyLength = 0; }
			historyLength = appendHistory(history, historyLength, chunk.iq);
			long intervalSamples = Math.max(1, chunk.rate / 2);
			if (historyLength == history.length && (!haveAcquisition || samplesSinceAcquisition >= intervalSamples)) {
				started = System.nanoTime();
				GpsL1SignalAnalyzer.Result result = analyzer.analyze(chunk.centerHz, chunk.rate, history, historyLength);
				maximumAnalyzeNanos = Math.max(maximumAnalyzeNanos, System.nanoTime() - started);
				haveAcquisition = true; samplesSinceAcquisition = 0L;
				synchronized (queue) { if (!closed && chunk.generation == generation) onResult.accept(result); }
			}
		}
	}
	private static int appendHistory(byte[] history, int historyLength, byte[] input) {
		int length = input.length & ~1;
		if (length >= history.length) { System.arraycopy(input, length - history.length, history, 0, history.length); return history.length; }
		int retained = Math.min(historyLength, history.length - length);
		if (retained > 0) System.arraycopy(history, historyLength - retained, history, 0, retained);
		System.arraycopy(input, 0, history, retained, length); return retained + length;
	}
	private static final class Chunk {
		final byte[] iq; final long centerHz, generation; final int rate; final boolean reset;
		Chunk(byte[] iq, long centerHz, int rate, boolean reset, long generation) {
			this.iq = iq; this.centerHz = centerHz; this.rate = rate; this.reset = reset; this.generation = generation;
		}
	}
}
