package jspectrumanalyzer.iq;

import java.util.ArrayDeque;
import java.util.function.Consumer;

/** Bounded, ordered GPS IQ worker shared by the UI and real-time replay test. */
final class GpsL1Worker implements AutoCloseable {
	private static final int MAX_NATIVE_ANALYZER_RATE_HZ = 4_096_000;
	private static final int CHANNELIZED_TARGET_RATE_HZ = 2_200_000;
	private static final int GPS_OCCUPIED_BANDWIDTH_HZ = 2_100_000;
	/* About three seconds at 2.6 MS/s; at wide input rates this primarily bounds
	 * memory while channelization keeps the analyzer work rate low. */
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
		IQSubchannelExtractor.Stream channelizer = null;
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
				channelizer = needsChannelizer(chunk.centerHz, chunk.rate)
						? new IQSubchannelExtractor.Stream(chunk.rate,
								GpsL1SignalAnalyzer.L1_FREQUENCY_HZ - chunk.centerHz,
								GPS_OCCUPIED_BANDWIDTH_HZ, CHANNELIZED_TARGET_RATE_HZ)
						: null;
			}
			byte[] analysisIq = chunk.iq;
			long analysisCenter = chunk.centerHz;
			int analysisRate = chunk.rate;
			if (channelizer != null) {
				IQSubchannelExtractor.Result extracted = channelizer.process(chunk.iq, chunk.iq.length);
				analysisIq = quantize(extracted);
				analysisCenter = GpsL1SignalAnalyzer.L1_FREQUENCY_HZ;
				analysisRate = (int) Math.round(extracted.sampleRateHz);
			}
			long started = System.nanoTime();
			analyzer.acceptStream(analysisCenter, analysisRate, analysisIq, analysisIq.length);
			maximumStreamNanos = Math.max(maximumStreamNanos, System.nanoTime() - started);
			samplesSinceAcquisition += analysisIq.length / 2;
			int wanted = Math.max(2, (int) Math.round(analysisRate / 1000d) * 2 * 20);
			if (history.length != wanted) { history = new byte[wanted]; historyLength = 0; }
			historyLength = appendHistory(history, historyLength, analysisIq);
			long intervalSamples = Math.max(1, analysisRate / 2);
			if (historyLength == history.length && (!haveAcquisition || samplesSinceAcquisition >= intervalSamples)) {
				started = System.nanoTime();
				GpsL1SignalAnalyzer.Result result = analyzer.analyze(analysisCenter, analysisRate, history, historyLength);
				maximumAnalyzeNanos = Math.max(maximumAnalyzeNanos, System.nanoTime() - started);
				haveAcquisition = true; samplesSinceAcquisition = 0L;
				synchronized (queue) { if (!closed && chunk.generation == generation) onResult.accept(result); }
			}
		}
	}
	private static boolean needsChannelizer(long centerHz, int rateHz) {
		int decimation = rateHz / CHANNELIZED_TARGET_RATE_HZ;
		return rateHz > MAX_NATIVE_ANALYZER_RATE_HZ
				&& Math.abs(centerHz - GpsL1SignalAnalyzer.L1_FREQUENCY_HZ) + GPS_OCCUPIED_BANDWIDTH_HZ / 2d <= rateHz / 2d
				&& decimation >= 2 && rateHz / decimation >= 2_200_000;
	}
	private static byte[] quantize(IQSubchannelExtractor.Result extracted) {
		byte[] output = new byte[extracted.samples * 2];
		double scale = 127d * Math.sqrt(extracted.decimation);
		for (int index = 0; index < output.length; index++) {
			int value = (int) Math.round(extracted.iq[index] * scale);
			output[index] = (byte) Math.max(-127, Math.min(127, value));
		}
		return output;
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
