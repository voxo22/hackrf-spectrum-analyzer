package jspectrumanalyzer.iq;

import java.util.ArrayDeque;
import java.util.function.Consumer;

/** Bounded background worker for the independent BeiDou B1C pipeline. */
final class BeiDouB1cWorker implements AutoCloseable {
	private static final int MAX_QUEUED_BYTES = 32_000_000;
	private final ArrayDeque<Chunk> queue = new ArrayDeque<Chunk>();
	private final BeiDouB1cSignalAnalyzer analyzer = new BeiDouB1cSignalAnalyzer();
	private final Consumer<BeiDouB1cSignalAnalyzer.Result> onResult; private final Thread worker;
	private volatile boolean closed; private volatile long overflowCount, maximumAnalyzeNanos;
	private int queuedBytes; private long generation; private boolean pendingReset;

	BeiDouB1cWorker(Consumer<BeiDouB1cSignalAnalyzer.Result> onResult) {
		this.onResult = onResult; worker = new Thread(this::run, "BeiDou B1C acquisition");
		worker.setDaemon(true); worker.start();
	}
	void offer(long centerHz, int sampleRate, byte[] iq, int length) {
		if (iq == null || length < 2 || sampleRate <= 0) return;
		int available = Math.min(length, iq.length) & ~1; boolean truncated = available > MAX_QUEUED_BYTES;
		int copyLength = Math.min(available, MAX_QUEUED_BYTES) & ~1;
		byte[] copy = new byte[copyLength]; System.arraycopy(iq, available - copyLength, copy, 0, copyLength);
		synchronized (queue) {
			if (closed) return; boolean overflow = truncated || queuedBytes + copyLength > MAX_QUEUED_BYTES;
			if (overflow) { queue.clear(); queuedBytes = 0; overflowCount++; generation++; }
			boolean reset = overflow || pendingReset; pendingReset = false;
			queue.addLast(new Chunk(copy, centerHz, sampleRate, reset, generation)); queuedBytes += copyLength; queue.notifyAll();
		}
	}
	void discontinuity() {
		synchronized (queue) {
			if (closed) return; generation++; queue.clear(); queuedBytes = 0; pendingReset = true;
			onResult.accept(BeiDouB1cSignalAnalyzer.Result.empty(0L, 0, "Replay position changed; reacquiring BeiDou B1C"));
		}
	}
	long overflowCount() { return overflowCount; }
	long maximumAnalyzeNanos() { return maximumAnalyzeNanos; }
	@Override public void close() { closed = true; synchronized (queue) { queue.clear(); queuedBytes = 0; queue.notifyAll(); } worker.interrupt(); }

	private void run() {
		byte[] history = new byte[0]; int historyLength = 0, historyRate = 0; long historyCenter = Long.MIN_VALUE;
		long samplesSinceAcquisition = 0L; boolean initialSweepComplete = false;
		while (!closed) {
			Chunk chunk;
			synchronized (queue) {
				while (queue.isEmpty() && !closed) try { queue.wait(); } catch (InterruptedException error) { if (closed) return; }
				if (closed) return; chunk = queue.removeFirst(); queuedBytes -= chunk.iq.length;
			}
			if (chunk.reset || chunk.centerHz != historyCenter || chunk.rate != historyRate) {
				analyzer.reset(); historyLength = 0; historyCenter = chunk.centerHz; historyRate = chunk.rate;
				samplesSinceAcquisition = 0L; initialSweepComplete = false;
			}
			int wanted = Math.max(2, (int) Math.ceil(chunk.rate * .0101d) * 2 + 4);
			if (history.length != wanted) { history = new byte[wanted]; historyLength = 0; }
			analyzer.acceptStream(chunk.centerHz, chunk.rate, chunk.iq, chunk.iq.length);
			historyLength = appendHistory(history, historyLength, chunk.iq); samplesSinceAcquisition += chunk.iq.length / 2;
			long intervalSamples = Math.max(1, initialSweepComplete ? (long) chunk.rate * 5L : chunk.rate / 2L);
			if (historyLength == history.length && samplesSinceAcquisition >= intervalSamples) {
				long started = System.nanoTime();
				BeiDouB1cSignalAnalyzer.Result result = analyzer.analyze(chunk.centerHz, chunk.rate, history, historyLength);
				maximumAnalyzeNanos = Math.max(maximumAnalyzeNanos, System.nanoTime() - started); samplesSinceAcquisition = 0L;
				initialSweepComplete = result.scannedCount >= BeiDouB1cCodes.PRN_COUNT;
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
