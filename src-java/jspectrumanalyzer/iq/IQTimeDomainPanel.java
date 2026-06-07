package jspectrumanalyzer.iq;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.Arrays;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.EventListenerList;

public class IQTimeDomainPanel extends JPanel {
	private static final long serialVersionUID = 7109842103069808403L;
	private static final Color COLOR_I = new Color(0x11ff11);
	private static final Color COLOR_Q = new Color(0xff5555);
	private static final Color COLOR_ENVELOPE = new Color(0xeeee00);
	private static final Color COLOR_DEVIATION = new Color(0xd98cff);
	private static final int DEVIATION_SCALE_SAMPLES = 4096;
	private static final int DEVIATION_MIN_MAGNITUDE = 6;
	private static final double DEVIATION_MIN_COHERENCE = 0.22d;
	private static final int OVERVIEW_THRESHOLD_SAMPLES = 2097153;
	private static final long OVERVIEW_REFRESH_NANOS = 600_000_000L;
	private static final int ZOOM_BUTTON_SIZE = 42;
	private static final int ZOOM_BUTTON_GAP = 8;
	private static final double BUTTON_ZOOM_FACTOR = 1.5d;

	private byte[] snapshot;
	private IQRingBuffer ringBuffer;
	private int visibleSamples;
	private int defaultVisibleSamples;
	private volatile long centerFreqHz;
	private volatile int sampleRateHz;
	private volatile long blocks;
	private volatile long bytes;
	private volatile long startedNanos;
	private volatile int decimation = 1;
	private volatile boolean triggerEnabled = false;
	private volatile boolean singleTriggerEnabled = false;
	private volatile int triggerThreshold = 64;
	private volatile int triggerPrePercent = 25;
	private volatile boolean triggerFound = false;
	private volatile boolean singleTriggerCaptured = false;
	private volatile int triggerSampleInView = 0;
	private volatile boolean envelopeOnly = false;
	private volatile boolean deviationView = false;
	private volatile boolean burstDetectorEnabled = false;
	private volatile double deviationScaleHz = 1_000d;
	private volatile int measureStartX = -1;
	private volatile int measureEndX = -1;
	private volatile BurstStats burstStats = BurstStats.empty();
	private volatile long lastBurstStatsUpdateNanos = 0;
	private volatile BurstMark[] burstMarks = new BurstMark[0];
	private byte[] heldSnapshot;
	private int heldRead = 0;
	private int heldTriggerSampleInView = 0;
	private byte[] triggerPreBuffer = new byte[0];
	private byte[] triggerCaptureBuffer;
	private int triggerPreWriteSample = 0;
	private int triggerPreFilledSamples = 0;
	private int triggerCaptureWriteSample = 0;
	private int triggerCaptureTargetSamples = 0;
	private int triggerCaptureSampleInView = 0;
	private boolean triggerCaptureActive = false;
	private boolean triggerWasBelowThreshold = true;
	private volatile long lastOverviewUpdateNanos = 0;
	private volatile int overviewSamplesRead = 0;
	private int[] overviewEnvelopeMax = new int[0];
	private int[] burstEnvelopeScratch = new int[0];
	private int[] burstMagnitudeScratch = new int[0];
	private long[] burstPrefixScratch = new long[0];
	private final float[] deviationScaleScratch = new float[DEVIATION_SCALE_SAMPLES];
	private final EventListenerList listenerList = new EventListenerList();
	private volatile int hoverZoomButton = 0;

	public IQTimeDomainPanel(IQRingBuffer ringBuffer, int visibleSamples) {
		this.ringBuffer = ringBuffer;
		this.visibleSamples = visibleSamples;
		this.defaultVisibleSamples = visibleSamples;
		this.snapshot = new byte[visibleSamples * 2];
		setBackground(Color.BLACK);
		setForeground(Color.WHITE);
		setPreferredSize(new Dimension(1000, 560));
		installMouseControls();
	}

	public void setRingBuffer(IQRingBuffer ringBuffer) {
		this.ringBuffer = ringBuffer;
	}

	public void setVisibleSamples(int visibleSamples) {
		setVisibleSamplesInternal(visibleSamples, true);
	}

	public int getVisibleSamples() {
		return visibleSamples;
	}

	public void addVisibleSamplesListener(ChangeListener listener) {
		listenerList.add(ChangeListener.class, listener);
	}

	private void setVisibleSamplesInternal(int visibleSamples, boolean updateDefault) {
		visibleSamples = clampVisibleSamples(visibleSamples);
		this.visibleSamples = visibleSamples;
		if (updateDefault) {
			this.defaultVisibleSamples = visibleSamples;
		}
		int snapshotSamples = Math.min(visibleSamples, OVERVIEW_THRESHOLD_SAMPLES);
		this.snapshot = new byte[snapshotSamples * 2];
		clearSingleTriggerHold();
		fireVisibleSamplesChanged();
		repaint();
	}

	public void setStats(long centerFreqHz, int sampleRateHz, long blocks, long bytes, long startedNanos) {
		setStats(centerFreqHz, sampleRateHz, blocks, bytes, startedNanos, 1);
	}

	public void setStats(long centerFreqHz, int sampleRateHz, long blocks, long bytes, long startedNanos, int decimation) {
		this.centerFreqHz = centerFreqHz;
		this.sampleRateHz = sampleRateHz;
		this.blocks = blocks;
		this.bytes = bytes;
		this.startedNanos = startedNanos;
		this.decimation = Math.max(1, decimation);
	}

	public void setTrigger(boolean enabled, int threshold, int prePercent) {
		boolean changed = triggerEnabled != enabled
				|| triggerThreshold != Math.max(1, Math.min(181, threshold))
				|| triggerPrePercent != Math.max(0, Math.min(90, prePercent));
		this.triggerEnabled = enabled;
		this.triggerThreshold = Math.max(1, Math.min(181, threshold));
		this.triggerPrePercent = Math.max(0, Math.min(90, prePercent));
		if (changed) {
			clearSingleTriggerHold();
		}
	}

	public void setSingleTrigger(boolean enabled) {
		if (singleTriggerEnabled != enabled) {
			clearSingleTriggerHold();
		}
		this.singleTriggerEnabled = enabled;
	}

	public void clearSingleTriggerHold() {
		synchronized (this) {
			singleTriggerCaptured = false;
			heldSnapshot = null;
			heldRead = 0;
			triggerCaptureBuffer = null;
			triggerCaptureActive = false;
			triggerCaptureWriteSample = 0;
			triggerCaptureTargetSamples = 0;
			triggerPreFilledSamples = 0;
			triggerPreWriteSample = 0;
			triggerWasBelowThreshold = true;
		}
		repaint();
	}

	public void offerTriggerSamples(byte[] data, int length) {
		if (data == null || length < 2 || !singleTriggerEnabled || singleTriggerCaptured) {
			return;
		}
		synchronized (this) {
			if (!singleTriggerEnabled || singleTriggerCaptured) {
				return;
			}
			processSingleTriggerSamples(data, length & ~1);
		}
	}

	public void setEnvelopeOnly(boolean envelopeOnly) {
		this.envelopeOnly = envelopeOnly;
	}

	public void setDeviationView(boolean deviationView) {
		this.deviationView = deviationView;
	}

	public void setBurstDetectorEnabled(boolean burstDetectorEnabled) {
		this.burstDetectorEnabled = burstDetectorEnabled;
	}

	@Override
	protected void paintComponent(Graphics graphics) {
		super.paintComponent(graphics);
		Graphics2D g = (Graphics2D) graphics.create();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			draw(g);
		} finally {
			g.dispose();
		}
	}

	private void draw(Graphics2D g) {
		int width = getWidth();
		int height = getHeight();
		int top = 36;
		int bottom = height - 28;
		int plotHeight = Math.max(1, bottom - top);
		int mid = top + plotHeight / 2;
		IQRingBuffer activeRingBuffer = ringBuffer;
		boolean overviewMode = isOverviewMode(visibleSamples);
		int[] overviewEnvelope = overviewMode ? readOverviewEnvelope(activeRingBuffer, width) : null;
		int read = overviewMode ? overviewSamplesRead * 2 : (activeRingBuffer == null ? 0 : readSnapshot(activeRingBuffer));

		g.setColor(new Color(0x0f0f0f));
		g.fillRect(0, 0, width, height);
		if (read < 4) {
			burstStats = BurstStats.empty();
			burstMarks = new BurstMark[0];
			drawGrid(g, width, top, bottom, mid);
			drawTriggerThreshold(g, width, mid, plotHeight);
			drawHeader(g, read);
			g.setColor(new Color(0xaaaaaa));
			g.drawString("Waiting for IQ samples...", 16, mid);
			drawZoomButtons(g);
			return;
		}

		int samples = overviewMode ? overviewSamplesRead : read / 2;
		if (!overviewMode && deviationView) {
			updateDeviationScale(samples);
		}
		if (burstDetectorEnabled) {
			if (overviewMode) {
				updateBurstStatsFromEnvelope(overviewEnvelope, Math.min(width, overviewEnvelope.length),
						visibleSamples / (double) sampleRateHz / Math.max(1, width));
			} else {
				updateBurstStats(samples);
			}
		} else {
			burstStats = BurstStats.empty();
			burstMarks = new BurstMark[0];
		}
		drawGrid(g, width, top, bottom, mid);
		drawHeader(g, read);

		if (overviewMode) {
			drawOverviewEnvelope(g, overviewEnvelope, width, mid, plotHeight);
		} else if (deviationView) {
			drawDeviationWave(g, samples, COLOR_DEVIATION, mid, plotHeight);
		} else if (!envelopeOnly) {
			drawWave(g, samples, 0, COLOR_I, mid, plotHeight, false);
			drawWave(g, samples, 1, COLOR_Q, mid, plotHeight, false);
		}
		if (!deviationView) {
			drawWave(g, samples, 0, COLOR_ENVELOPE, mid, plotHeight, true);
		}
		if (burstDetectorEnabled && !deviationView) {
			drawBurstEnvelopeOverlay(g, samples, mid, plotHeight);
		}
		drawTriggerThreshold(g, width, mid, plotHeight);
		drawTriggerMarker(g, top, bottom);
		drawMeasurement(g, top, bottom);
		drawZoomButtons(g);
	}

	private int readSnapshot(IQRingBuffer activeRingBuffer) {
		if (singleTriggerEnabled && singleTriggerCaptured && heldSnapshot != null) {
			if (snapshot.length < heldRead) {
				snapshot = new byte[heldRead];
			}
			System.arraycopy(heldSnapshot, 0, snapshot, 0, heldRead);
			triggerFound = true;
			triggerSampleInView = heldTriggerSampleInView;
			return heldRead;
		}
		triggerFound = false;
		triggerSampleInView = visibleSamples * triggerPrePercent / 100;
		if (!triggerEnabled) {
			return activeRingBuffer.readLatest(snapshot, snapshot.length);
		}

		int searchBytes = Math.min(activeRingBuffer.capacityBytes(), Math.max(snapshot.length * 4, snapshot.length));
		byte[] search = new byte[searchBytes];
		int read = activeRingBuffer.readLatest(search, search.length);
		if (read < snapshot.length || read < 4) {
			return copyTail(search, read);
		}

		int samples = read / 2;
		int preSamples = visibleSamples * triggerPrePercent / 100;
		int postSamples = visibleSamples - preSamples;
		int triggerSample = findTriggerSample(search, samples, preSamples, postSamples);
		if (triggerSample < 0) {
			return copyTail(search, read);
		}

		int startSample = triggerSample - preSamples;
		if (startSample < 0) {
			startSample = 0;
		}
		if (startSample + visibleSamples > samples) {
			startSample = Math.max(0, samples - visibleSamples);
		}
		int availableSamples = Math.min(visibleSamples, samples - startSample);
		int bytesToCopy = availableSamples * 2;
		System.arraycopy(search, startSample * 2, snapshot, 0, bytesToCopy);
		triggerFound = true;
		triggerSampleInView = triggerSample - startSample;
		if (singleTriggerEnabled) {
			heldSnapshot = new byte[bytesToCopy];
			System.arraycopy(snapshot, 0, heldSnapshot, 0, bytesToCopy);
			heldRead = bytesToCopy;
			heldTriggerSampleInView = triggerSampleInView;
			singleTriggerCaptured = true;
		}
		return bytesToCopy;
	}

	private void processSingleTriggerSamples(byte[] data, int length) {
		int samples = length / 2;
		int preSamples = visibleSamples * triggerPrePercent / 100;
		int lowThreshold = Math.max(1, triggerThreshold - Math.max(4, triggerThreshold / 8));
		ensureTriggerPreBuffer(Math.max(1, preSamples));

		for (int sample = 0; sample < samples; sample++) {
			int offset = sample * 2;
			if (!triggerCaptureActive) {
				int mag = magnitude(data[offset], data[offset + 1]);
				boolean trigger = triggerWasBelowThreshold && mag >= triggerThreshold;
				if (!trigger) {
					writeTriggerPreSample(data[offset], data[offset + 1], preSamples);
					if (mag <= lowThreshold) {
						triggerWasBelowThreshold = true;
					}
					continue;
				}
				startSingleTriggerCapture(preSamples);
				triggerWasBelowThreshold = false;
			}

			copySingleTriggerSample(data[offset], data[offset + 1]);
			if (triggerCaptureActive && triggerCaptureWriteSample >= triggerCaptureTargetSamples) {
				completeSingleTriggerCapture();
				return;
			}
		}
	}

	private void ensureTriggerPreBuffer(int preSamples) {
		int bytes = Math.max(2, preSamples * 2);
		if (triggerPreBuffer.length == bytes) {
			return;
		}
		triggerPreBuffer = new byte[bytes];
		triggerPreWriteSample = 0;
		triggerPreFilledSamples = 0;
	}

	private void writeTriggerPreSample(byte i, byte q, int preSamples) {
		if (preSamples <= 0 || triggerPreBuffer.length < 2) {
			return;
		}
		int index = (triggerPreWriteSample % preSamples) * 2;
		triggerPreBuffer[index] = i;
		triggerPreBuffer[index + 1] = q;
		triggerPreWriteSample = (triggerPreWriteSample + 1) % preSamples;
		if (triggerPreFilledSamples < preSamples) {
			triggerPreFilledSamples++;
		}
	}

	private void startSingleTriggerCapture(int preSamples) {
		triggerCaptureTargetSamples = Math.max(1, visibleSamples);
		triggerCaptureBuffer = new byte[triggerCaptureTargetSamples * 2];
		int availablePre = Math.min(preSamples, triggerPreFilledSamples);
		int leadingSamples = Math.max(0, preSamples - availablePre);
		triggerCaptureWriteSample = leadingSamples;
		int firstPreSample = (triggerPreWriteSample - availablePre + preSamples) % Math.max(1, preSamples);
		for (int sample = 0; sample < availablePre; sample++) {
			int sourceSample = (firstPreSample + sample) % Math.max(1, preSamples);
			int source = sourceSample * 2;
			int target = triggerCaptureWriteSample * 2;
			triggerCaptureBuffer[target] = triggerPreBuffer[source];
			triggerCaptureBuffer[target + 1] = triggerPreBuffer[source + 1];
			triggerCaptureWriteSample++;
		}
		triggerCaptureSampleInView = triggerCaptureWriteSample;
		triggerCaptureActive = true;
	}

	private void copySingleTriggerSample(byte i, byte q) {
		if (!triggerCaptureActive || triggerCaptureBuffer == null
				|| triggerCaptureWriteSample >= triggerCaptureTargetSamples) {
			return;
		}
		int target = triggerCaptureWriteSample * 2;
		triggerCaptureBuffer[target] = i;
		triggerCaptureBuffer[target + 1] = q;
		triggerCaptureWriteSample++;
	}

	private void completeSingleTriggerCapture() {
		heldSnapshot = triggerCaptureBuffer;
		heldRead = triggerCaptureWriteSample * 2;
		heldTriggerSampleInView = Math.max(0, Math.min(triggerCaptureSampleInView, visibleSamples - 1));
		singleTriggerCaptured = true;
		triggerFound = true;
		triggerSampleInView = heldTriggerSampleInView;
		triggerCaptureBuffer = null;
		triggerCaptureActive = false;
		SwingUtilities.invokeLater(this::repaint);
	}

	private int copyTail(byte[] source, int read) {
		int available = Math.min(read, snapshot.length);
		if ((available & 1) != 0) {
			available--;
		}
		if (available <= 0) {
			return 0;
		}
		System.arraycopy(source, read - available, snapshot, 0, available);
		return available;
	}

	private int findTriggerSample(byte[] data, int samples, int preSamples, int postSamples) {
		int lowThreshold = Math.max(1, triggerThreshold - Math.max(4, triggerThreshold / 8));
		int start = Math.max(1, preSamples);
		int stop = samples - Math.max(1, postSamples);
		if (stop <= start) {
			return -1;
		}

		boolean armed = false;
		for (int sample = stop; sample >= start; sample--) {
			int currentMagnitude = magnitude(data[sample * 2], data[sample * 2 + 1]);
			if (!armed) {
				if (currentMagnitude >= triggerThreshold) {
					armed = true;
				}
				continue;
			}
			if (currentMagnitude <= lowThreshold) {
				for (int edge = sample + 1; edge <= stop; edge++) {
					if (magnitude(data[edge * 2], data[edge * 2 + 1]) >= triggerThreshold) {
						return edge;
					}
				}
				return sample + 1;
			}
		}
		return -1;
	}

	private void drawGrid(Graphics2D g, int width, int top, int bottom, int mid) {
		g.setStroke(new BasicStroke(1f));
		g.setColor(new Color(0x505050));
		for (int i = 0; i <= 10; i++) {
			int x = i * width / 10;
			g.drawLine(x, top, x, bottom);
		}
		for (int i = 0; i <= 6; i++) {
			int y = top + i * (bottom - top) / 6;
			g.drawLine(0, y, width, y);
		}
		g.setColor(new Color(0x686868));
		g.drawLine(0, mid, width, mid);
		drawAxes(g, width, top, bottom, mid);
	}

	private void drawAxes(Graphics2D g, int width, int top, int bottom, int mid) {
		g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
		g.setColor(new Color(0x999999));
		g.drawString("+1", 4, top + 12);
		g.drawString("0", 4, mid - 4);
		g.drawString("-1", 4, bottom - 4);

		double visibleSeconds = sampleRateHz <= 0 ? 0 : visibleSamples / (double) sampleRateHz;
		String left = "0";
		String middle = formatTime(visibleSeconds / 2d);
		String right = formatTime(visibleSeconds);
		int y = getHeight() - 8;
		g.drawString(left, 8, y);
		g.drawString(middle, Math.max(8, width / 2 - 24), y);
		g.drawString(right, Math.max(8, width - 72), y);
		if (deviationView) {
			g.drawString("+" + formatFrequencyDelta(deviationScaleHz), 34, top + 12);
			g.drawString("-" + formatFrequencyDelta(deviationScaleHz), 34, bottom - 4);
		}
	}

	private void drawHeader(Graphics2D g, int read) {
		double elapsedSeconds = startedNanos == 0 ? 0 : (System.nanoTime() - startedNanos) / 1_000_000_000d;
		double mibPerSecond = elapsedSeconds <= 0 ? 0 : bytes / elapsedSeconds / (1024d * 1024d);
		double visibleMicros = sampleRateHz <= 0 ? 0 : visibleSamples * 1_000_000d / sampleRateHz;

		g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
		g.setColor(new Color(0xdddddd));
		String text = String.format("Center %.6f MHz   Display %.3f kS/s   Decim %dx   Blocks %d   %.2f MiB/s   View %.2f us",
				centerFreqHz / 1_000_000d, sampleRateHz / 1_000d, decimation, blocks, mibPerSecond, visibleMicros);
		g.drawString(text, 12, 22);

		drawLegend(g);

		if (read == 0) {
			g.setColor(new Color(0x888888));
			g.drawString("No buffered samples", Math.max(12, getWidth() - 150), 34);
		}
		if (isTriggerThresholdVisible()) {
			g.setColor(triggerFound ? new Color(0x75ff7a) : new Color(0xff7b7b));
			String triggerText;
			if (singleTriggerEnabled) {
				triggerText = singleTriggerCaptured ? "Captured" : "Single armed";
			} else {
				triggerText = triggerFound ? "Triggered" : "Armed";
			}
			g.drawString(triggerText, Math.max(12, getWidth() - 110), 34);
		}
		if (deviationView) {
			g.setColor(COLOR_DEVIATION);
			g.drawString("FSK deviation", Math.max(12, getWidth() - 182), 34);
		}
		if (burstDetectorEnabled) {
			drawBurstStats(g);
		}
	}

	private void drawBurstStats(Graphics2D g) {
		BurstStats stats = burstStats;
		g.setColor(COLOR_ENVELOPE);
		if (stats.count <= 0) {
			g.drawString("Burst: none", 12, 34);
			return;
		}
		String text = "Burst " + stats.count + "   width " + formatTime(stats.averageWidthSeconds);
		if (stats.averagePeriodSeconds > 0) {
			text += "   period " + formatTime(stats.averagePeriodSeconds)
					+ String.format("   frequency %.1f Hz", 1d / stats.averagePeriodSeconds)
					+ String.format("   duty %.1f%%", stats.dutyPercent);
		}
		g.drawString(text, 12, 34);
	}

	private void drawBurstMarks(Graphics2D g, int top, int mid, int plotHeight) {
		BurstMark[] marks = burstMarks;
		if (marks.length == 0 || visibleSamples <= 0 || getWidth() <= 1) {
			return;
		}
		Color edge = new Color(0xfca311);
		for (BurstMark mark : marks) {
			int x1 = mark.startSample * getWidth() / visibleSamples;
			int x2 = Math.max(x1 + 1, mark.endSample * getWidth() / visibleSamples);
			int y = Math.max(top + 4, envelopeToY(mark.peakMagnitude, mid, plotHeight) - 7);
			g.setColor(edge);
			g.drawLine(x1, y, x2, y);
			g.drawLine(x1, y, x1, y + 6);
			g.drawLine(x2, y, x2, y + 6);
		}
	}

	private void drawBurstEnvelopeOverlay(Graphics2D g, int samples, int mid, int plotHeight) {
		int width = getWidth();
		if (width <= 1 || samples <= 1) {
			return;
		}
		int[] pixelEnvelope = preparePixelEnvelope(samples, width);
		int min = 181;
		int max = 0;
		for (int x = 0; x < width; x++) {
			int mag = pixelEnvelope[x];
			if (mag < min) {
				min = mag;
			}
			if (mag > max) {
				max = mag;
			}
		}
		if (max - min < 6) {
			return;
		}
		int high = triggerEnabled ? triggerThreshold : min + Math.round((max - min) * 0.45f);
		g.setColor(new Color(0xfca311));
		g.setStroke(new BasicStroke(1.4f));
		boolean inside = false;
		int startX = 0;
		int peak = 0;
		for (int x = 0; x < width; x++) {
			int mag = pixelEnvelope[x];
			if (!inside && mag >= high) {
				inside = true;
				startX = x;
				peak = mag;
			} else if (inside && mag >= high) {
				if (mag > peak) {
					peak = mag;
				}
			} else if (inside) {
				drawBurstRoof(g, startX, x - 1, peak, mid, plotHeight);
				inside = false;
			}
		}
		if (inside) {
			drawBurstRoof(g, startX, width - 1, peak, mid, plotHeight);
		}
	}

	private void drawBurstRoof(Graphics2D g, int x1, int x2, int peakMagnitude, int mid, int plotHeight) {
		if (x2 <= x1) {
			return;
		}
		int y = Math.max(4, envelopeToY(peakMagnitude, mid, plotHeight) - 1);
		g.drawLine(x1, y, x2, y);
		g.drawLine(x1, y, x1, y + 5);
		g.drawLine(x2, y, x2, y + 5);
	}

	private void drawLegend(Graphics2D g) {
		int x = Math.max(12, getWidth() - 220);
		int y = 22;
		if (deviationView) {
			drawLegendItem(g, x, y, COLOR_DEVIATION, "Deviation");
			return;
		}
		if (!envelopeOnly) {
			x = drawLegendItem(g, x, y, COLOR_I, "I");
			x = drawLegendItem(g, x, y, COLOR_Q, "Q");
		}
		drawLegendItem(g, x, y, COLOR_ENVELOPE, "|IQ| Envelope");
	}

	private int drawLegendItem(Graphics2D g, int x, int y, Color color, String label) {
		g.setColor(color);
		g.fillRect(x, y - 9, 14, 3);
		g.drawString(label, x + 18, y);
		return x + 18 + g.getFontMetrics().stringWidth(label) + 18;
	}

	private void drawZoomButtons(Graphics2D g) {
		Rectangle plus = getZoomButtonBounds(true);
		Rectangle minus = getZoomButtonBounds(false);
		drawZoomButton(g, plus, "+", hoverZoomButton == 1);
		drawZoomButton(g, minus, "-", hoverZoomButton == -1);
	}

	private void drawZoomButton(Graphics2D g, Rectangle bounds, String label, boolean hover) {
		java.awt.Composite oldComposite = g.getComposite();
		Color oldColor = g.getColor();
		Font oldFont = g.getFont();
		g.setComposite(java.awt.AlphaComposite.SrcOver.derive(hover ? 0.58f : 0.38f));
		g.setColor(new Color(0x202020));
		g.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 10, 10);
		g.setComposite(java.awt.AlphaComposite.SrcOver.derive(hover ? 0.95f : 0.75f));
		g.setColor(new Color(0xffffff));
		g.drawRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 10, 10);
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
		int textWidth = g.getFontMetrics().stringWidth(label);
		int textAscent = g.getFontMetrics().getAscent();
		int textX = bounds.x + (bounds.width - textWidth) / 2;
		int textY = bounds.y + (bounds.height + textAscent) / 2 - 5;
		g.drawString(label, textX, textY);
		g.setComposite(oldComposite);
		g.setColor(oldColor);
		g.setFont(oldFont);
	}

	private Rectangle getZoomButtonBounds(boolean plus) {
		int x = Math.max(8, getWidth() - ZOOM_BUTTON_SIZE - 14);
		int y = 46 + (plus ? 0 : ZOOM_BUTTON_SIZE + ZOOM_BUTTON_GAP);
		return new Rectangle(x, y, ZOOM_BUTTON_SIZE, ZOOM_BUTTON_SIZE);
	}

	private int getZoomButtonAt(int x, int y) {
		if (getZoomButtonBounds(true).contains(x, y)) {
			return 1;
		}
		if (getZoomButtonBounds(false).contains(x, y)) {
			return -1;
		}
		return 0;
	}

	private void zoomTimeAxis(boolean zoomIn) {
		double factor = zoomIn ? (1d / BUTTON_ZOOM_FACTOR) : BUTTON_ZOOM_FACTOR;
		int next = (int) Math.round(visibleSamples * factor);
		setVisibleSamplesInternal(next, false);
	}

	private void installMouseControls() {
		MouseAdapter adapter = new MouseAdapter() {
			@Override
			public void mouseWheelMoved(MouseWheelEvent e) {
				double factor = Math.pow(1.25d, e.getPreciseWheelRotation());
				int next = (int) Math.round(visibleSamples * factor);
				setVisibleSamplesInternal(next, false);
			}

			@Override
			public void mouseClicked(MouseEvent e) {
				int zoomButton = getZoomButtonAt(e.getX(), e.getY());
				if (e.getButton() == MouseEvent.BUTTON1 && zoomButton != 0) {
					zoomTimeAxis(zoomButton > 0);
					e.consume();
					return;
				}
				if (e.getClickCount() >= 2 && e.getButton() == MouseEvent.BUTTON1) {
					setVisibleSamplesInternal(defaultVisibleSamples, false);
				} else if (e.getButton() == MouseEvent.BUTTON3) {
					clearMeasurement();
				}
			}

			@Override
			public void mousePressed(MouseEvent e) {
				if (e.getButton() == MouseEvent.BUTTON1 && getZoomButtonAt(e.getX(), e.getY()) != 0) {
					e.consume();
					return;
				}
				if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() == 1) {
					measureStartX = e.getX();
					measureEndX = e.getX();
					repaint();
				}
			}

			@Override
			public void mouseDragged(MouseEvent e) {
				if (measureStartX >= 0) {
					measureEndX = e.getX();
					repaint();
				}
			}

			@Override
			public void mouseMoved(MouseEvent e) {
				int nextHover = getZoomButtonAt(e.getX(), e.getY());
				if (hoverZoomButton != nextHover) {
					hoverZoomButton = nextHover;
					setCursor(nextHover == 0 ? Cursor.getDefaultCursor() : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
					repaint();
				}
			}

			@Override
			public void mouseExited(MouseEvent e) {
				if (hoverZoomButton != 0) {
					hoverZoomButton = 0;
					setCursor(Cursor.getDefaultCursor());
					repaint();
				}
			}
		};
		addMouseWheelListener(adapter);
		addMouseListener(adapter);
		addMouseMotionListener(adapter);
	}

	private int clampVisibleSamples(int samples) {
		int min = 128;
		int max = 2097152;
		if (samples < min) {
			samples = min;
		}
		if (samples > max) {
			samples = max;
		}
		return samples;
	}

	private String formatTime(double seconds) {
		if (seconds < 0.001d) {
			return String.format("%.1f us", seconds * 1_000_000d);
		}
		if (seconds < 1d) {
			return String.format("%.2f ms", seconds * 1_000d);
		}
		return String.format("%.2f s", seconds);
	}

	private String formatFrequencyDelta(double hz) {
		double absHz = Math.abs(hz);
		if (absHz >= 1_000_000d) {
			return String.format("%.2f MHz", hz / 1_000_000d);
		}
		if (absHz >= 1_000d) {
			return String.format("%.1f kHz", hz / 1_000d);
		}
		return String.format("%.0f Hz", hz);
	}

	private void drawMeasurement(Graphics2D g, int top, int bottom) {
		if (measureStartX < 0 || measureEndX < 0 || getWidth() <= 1 || sampleRateHz <= 0) {
			return;
		}
		int x1 = clampX(measureStartX);
		int x2 = clampX(measureEndX);
		int triggerX = triggerEnabled ? sampleToX(triggerSampleInView) : 0;

		g.setStroke(new BasicStroke(1f));
		g.setColor(new Color(0xf0f0f0));
		g.drawLine(x1, top, x1, bottom);
		if (x2 != x1) {
			g.drawLine(x2, top, x2, bottom);
			g.drawLine(Math.min(x1, x2), bottom - 18, Math.max(x1, x2), bottom - 18);
		}

		int cursorSample = xToSample(x2);
		int startSample = xToSample(x1);
		int endSample = xToSample(x2);
		double cursorDelta = (cursorSample - triggerSampleInView) / (double) sampleRateHz;
		double selectionDelta = Math.abs(endSample - startSample) / (double) sampleRateHz;

		String text = "cursor " + formatSignedTime(cursorDelta);
		if (x2 != x1) {
			text += "   span " + formatTime(selectionDelta);
		}

		int labelX = Math.min(Math.max(8, Math.max(x1, x2) + 8), Math.max(8, getWidth() - 210));
		g.setColor(new Color(0x101010));
		g.fillRect(labelX - 4, top + 8, 204, 22);
		g.setColor(new Color(0xf0f0f0));
		g.drawString(text, labelX, top + 24);
	}

	private int clampX(int x) {
		if (x < 0) {
			return 0;
		}
		if (x >= getWidth()) {
			return getWidth() - 1;
		}
		return x;
	}

	private String formatSignedTime(double seconds) {
		String sign = seconds < 0 ? "-" : "+";
		return sign + formatTime(Math.abs(seconds));
	}

	private void clearMeasurement() {
		measureStartX = -1;
		measureEndX = -1;
		repaint();
	}

	private void fireVisibleSamplesChanged() {
		ChangeEvent event = new ChangeEvent(this);
		for (ChangeListener listener : listenerList.getListeners(ChangeListener.class)) {
			listener.stateChanged(event);
		}
	}

	private void drawTriggerMarker(Graphics2D g, int top, int bottom) {
		if (!isTriggerThresholdVisible()) {
			return;
		}
		int x = sampleToX(triggerSampleInView);
		g.setColor(triggerFound ? new Color(0x75ff7a) : new Color(0x777777));
		g.drawLine(x, top, x, bottom);
	}

	private void drawTriggerThreshold(Graphics2D g, int width, int mid, int plotHeight) {
		if (!isTriggerThresholdVisible() || width <= 1) {
			return;
		}
		int y = envelopeToY(triggerThreshold, mid, plotHeight);
		g.setColor(new Color(0xff9f1c));
		g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0,
				new float[] { 8f, 6f }, 0));
		g.drawLine(0, y, width, y);

		String label = "TH " + triggerThreshold + " |IQ|";
		Font previousFont = g.getFont();
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
		int labelWidth = g.getFontMetrics().stringWidth(label);
		int labelX = 34;
		int labelY = Math.max(12, y - 4);
		g.setColor(new Color(0x101010));
		g.fillRect(labelX - 4, labelY - 11, labelWidth + 8, 14);
		g.setColor(new Color(0xffbd4a));
		g.drawString(label, labelX, labelY);
		g.setFont(previousFont);
	}

	private boolean isTriggerThresholdVisible() {
		return triggerEnabled || singleTriggerEnabled;
	}

	private int sampleToX(int sample) {
		if (visibleSamples <= 1 || getWidth() <= 1) {
			return 0;
		}
		int safeSample = Math.max(0, Math.min(sample, visibleSamples - 1));
		return (int) Math.round(safeSample * (getWidth() - 1) / (double) (visibleSamples - 1));
	}

	private int xToSample(int x) {
		if (visibleSamples <= 1 || getWidth() <= 1) {
			return 0;
		}
		int safeX = clampX(x);
		return (int) Math.round(safeX * (visibleSamples - 1) / (double) (getWidth() - 1));
	}

	private void drawWave(Graphics2D g, int samples, int componentOffset, Color color, int mid, int plotHeight,
			boolean magnitude) {
		int width = getWidth();
		if (width <= 1 || samples <= 1) {
			return;
		}

		g.setColor(color);
		g.setStroke(new BasicStroke(magnitude ? 1.3f : 1f));

		int previousX = 0;
		int previousY = sampleToY(0, componentOffset, mid, plotHeight, magnitude);
		for (int x = 1; x < width; x++) {
			int sample = x * (samples - 1) / (width - 1);
			int y = sampleToY(sample, componentOffset, mid, plotHeight, magnitude);
			g.drawLine(previousX, previousY, x, y);
			previousX = x;
			previousY = y;
		}
	}

	private int sampleToY(int sample, int componentOffset, int mid, int plotHeight, boolean magnitude) {
		int i = snapshot[sample * 2];
		int q = snapshot[sample * 2 + 1];
		double value;
		if (magnitude) {
			value = Math.sqrt(i * i + q * q) / 181.0;
			return mid - (int) Math.round(value * plotHeight * 0.45);
		}
		value = snapshot[sample * 2 + componentOffset] / 128.0;
		return mid - (int) Math.round(value * plotHeight * 0.45);
	}

	private boolean isOverviewMode(int samples) {
		return samples >= OVERVIEW_THRESHOLD_SAMPLES;
	}

	private int[] readOverviewEnvelope(IQRingBuffer activeRingBuffer, int width) {
		int bins = Math.max(1, width);
		if (overviewEnvelopeMax.length < bins) {
			overviewEnvelopeMax = new int[bins];
			lastOverviewUpdateNanos = 0;
		}
		if (activeRingBuffer == null) {
			overviewSamplesRead = 0;
			return overviewEnvelopeMax;
		}
		long now = System.nanoTime();
		if (overviewSamplesRead > 0 && now - lastOverviewUpdateNanos < OVERVIEW_REFRESH_NANOS) {
			return overviewEnvelopeMax;
		}
		overviewSamplesRead = activeRingBuffer.readLatestEnvelopeMax(overviewEnvelopeMax, bins, visibleSamples);
		lastOverviewUpdateNanos = now;
		return overviewEnvelopeMax;
	}

	private int[] prepareOverviewEnvelope(int samples, int width) {
		int bins = Math.max(1, width);
		if (overviewEnvelopeMax.length < bins) {
			overviewEnvelopeMax = new int[bins];
		}
		for (int x = 0; x < bins; x++) {
			int start = x * samples / bins;
			int end = (x + 1) * samples / bins;
			if (end <= start) {
				end = Math.min(samples, start + 1);
			}
			int max = 0;
			for (int sample = start; sample < end; sample++) {
				int mag = magnitude(snapshot[sample * 2], snapshot[sample * 2 + 1]);
				if (mag > max) {
					max = mag;
				}
			}
			overviewEnvelopeMax[x] = max;
		}
		return overviewEnvelopeMax;
	}

	private int[] preparePixelEnvelope(int samples, int width) {
		int bins = Math.max(1, width);
		if (overviewEnvelopeMax.length < bins) {
			overviewEnvelopeMax = new int[bins];
		}
		for (int x = 0; x < bins; x++) {
			int start = x * samples / bins;
			int end = (x + 1) * samples / bins;
			if (end <= start) {
				end = Math.min(samples, start + 1);
			}
			int max = 0;
			for (int sample = start; sample < end; sample++) {
				int mag = magnitude(snapshot[sample * 2], snapshot[sample * 2 + 1]);
				if (mag > max) {
					max = mag;
				}
			}
			overviewEnvelopeMax[x] = max;
		}
		return overviewEnvelopeMax;
	}

	private void drawOverviewEnvelope(Graphics2D g, int[] envelope, int width, int mid, int plotHeight) {
		if (envelope == null || width <= 1) {
			return;
		}
		g.setColor(COLOR_ENVELOPE);
		g.setStroke(new BasicStroke(1.3f));
		int previousY = envelopeToY(envelope[0], mid, plotHeight);
		for (int x = 1; x < width && x < envelope.length; x++) {
			int y = envelopeToY(envelope[x], mid, plotHeight);
			g.drawLine(x - 1, previousY, x, y);
			previousY = y;
		}
	}

	private int envelopeToY(int magnitude, int mid, int plotHeight) {
		double value = Math.max(0d, Math.min(1d, magnitude / 181d));
		return mid - (int) Math.round(value * plotHeight * 0.45);
	}

	private void drawDeviationWave(Graphics2D g, int samples, Color color, int mid, int plotHeight) {
		int width = getWidth();
		if (width <= 1 || samples <= 2) {
			return;
		}

		g.setColor(color);
		g.setStroke(new BasicStroke(1.2f));

		int previousX = -1;
		int previousY = 0;
		for (int x = 0; x < width; x++) {
			int start = 1 + x * (samples - 1) / width;
			int end = 1 + (x + 1) * (samples - 1) / width;
			double deviation = averagedDeviationHz(start, end, samples);
			if (Double.isNaN(deviation)) {
				previousX = -1;
				continue;
			}
			int y = deviationToY(deviation, mid, plotHeight);
			if (previousX >= 0) {
				g.drawLine(previousX, previousY, x, y);
			}
			previousX = x;
			previousY = y;
		}
	}

	private int deviationToY(double deviationHz, int mid, int plotHeight) {
		double normalized = deviationHz / Math.max(1d, deviationScaleHz);
		if (normalized > 1d) {
			normalized = 1d;
		} else if (normalized < -1d) {
			normalized = -1d;
		}
		return mid - (int) Math.round(normalized * plotHeight * 0.45);
	}

	private double averagedDeviationHz(int start, int end, int samples) {
		start = Math.max(1, Math.min(samples - 1, start));
		end = Math.max(start + 1, Math.min(samples, end));
		if (end - start < 3) {
			int center = (start + end) / 2;
			start = Math.max(1, center - 2);
			end = Math.min(samples, center + 3);
		}
		double crossSum = 0d;
		double dotSum = 0d;
		double vectorMagnitudeSum = 0d;
		int valid = 0;
		for (int sample = start; sample < end; sample++) {
			int prevI = snapshot[(sample - 1) * 2];
			int prevQ = snapshot[(sample - 1) * 2 + 1];
			int i = snapshot[sample * 2];
			int q = snapshot[sample * 2 + 1];
			if (magnitude(prevI, prevQ) < DEVIATION_MIN_MAGNITUDE
					|| magnitude(i, q) < DEVIATION_MIN_MAGNITUDE) {
				continue;
			}
			double cross = prevI * q - prevQ * i;
			double dot = prevI * i + prevQ * q;
			double vectorMagnitude = Math.hypot(cross, dot);
			if (vectorMagnitude <= 0d) {
				continue;
			}
			crossSum += cross;
			dotSum += dot;
			vectorMagnitudeSum += vectorMagnitude;
			valid++;
		}
		if (valid < 2 || vectorMagnitudeSum <= 0d) {
			return Double.NaN;
		}
		double coherence = Math.hypot(crossSum, dotSum) / vectorMagnitudeSum;
		if (coherence < DEVIATION_MIN_COHERENCE) {
			return Double.NaN;
		}
		return Math.atan2(crossSum, dotSum) * sampleRateHz / (2d * Math.PI);
	}

	private double deviationHz(int sample) {
		int prevI = snapshot[(sample - 1) * 2];
		int prevQ = snapshot[(sample - 1) * 2 + 1];
		int i = snapshot[sample * 2];
		int q = snapshot[sample * 2 + 1];
		double cross = prevI * q - prevQ * i;
		double dot = prevI * i + prevQ * q;
		return Math.atan2(cross, dot) * sampleRateHz / (2d * Math.PI);
	}

	private void updateDeviationScale(int samples) {
		if (sampleRateHz <= 0 || samples <= 2) {
			return;
		}
		int countTarget = Math.min(DEVIATION_SCALE_SAMPLES, samples - 1);
		int count = 0;
		for (int bin = 0; bin < countTarget && count < deviationScaleScratch.length; bin++) {
			int start = 1 + bin * (samples - 1) / countTarget;
			int end = 1 + (bin + 1) * (samples - 1) / countTarget;
			double deviation = averagedDeviationHz(start, end, samples);
			if (Double.isNaN(deviation)) {
				continue;
			}
			deviationScaleScratch[count++] = (float) Math.abs(deviation);
		}
		if (count < 8) {
			return;
		}
		Arrays.sort(deviationScaleScratch, 0, count);
		int percentileIndex = Math.min(count - 1, Math.round(count * 0.96f));
		double targetScaleHz = deviationScaleScratch[percentileIndex] * 1.35d;
		double minScaleHz = Math.max(500d, sampleRateHz / 4000d);
		double maxScaleHz = sampleRateHz * 0.45d;
		targetScaleHz = Math.max(minScaleHz, Math.min(maxScaleHz, targetScaleHz));
		deviationScaleHz = deviationScaleHz * 0.82d + targetScaleHz * 0.18d;
	}

	private void updateBurstStats(int samples) {
		if (sampleRateHz <= 0 || samples <= 2) {
			burstStats = BurstStats.empty();
			return;
		}
		long now = System.nanoTime();
		if (now - lastBurstStatsUpdateNanos < 350_000_000L) {
			return;
		}
		lastBurstStatsUpdateNanos = now;

		int[] envelope = prepareBurstEnvelope(samples);
		int min = 181;
		int max = 0;
		for (int sample = 0; sample < samples; sample++) {
			int mag = envelope[sample];
			if (mag < min) {
				min = mag;
			}
			if (mag > max) {
				max = mag;
			}
		}
		if (max - min < 6) {
			burstStats = BurstStats.empty();
			return;
		}

		int high = triggerEnabled ? triggerThreshold : min + Math.round((max - min) * 0.45f);
		int low = Math.max(min + 1, high - Math.max(4, (high - min) / 3));
		int minimumBurstSamples = Math.max(3, (int) Math.round(sampleRateHz * 50e-6d));
		boolean inside = false;
		int start = 0;
		boolean partialAtLeftEdge = false;
		int previousStart = -1;
		int count = 0;
		long widthSum = 0;
		long periodSum = 0;
		int periodCount = 0;
		BurstMark[] marks = new BurstMark[64];
		int markCount = 0;

		for (int sample = 0; sample < samples; sample++) {
			int mag = envelope[sample];
			if (!inside && mag >= high) {
				inside = true;
				start = sample;
				partialAtLeftEdge = sample == 0;
				if (previousStart >= 0) {
					periodSum += sample - previousStart;
					periodCount++;
				}
				previousStart = sample;
			} else if (inside && mag <= low) {
				int width = sample - start;
				if (!partialAtLeftEdge && width >= minimumBurstSamples) {
					widthSum += width;
					count++;
					if (markCount < marks.length) {
						marks[markCount++] = new BurstMark(start, sample, peakEnvelope(envelope, start, sample));
					}
				}
				inside = false;
				partialAtLeftEdge = false;
			}
		}
		if (count <= 0) {
			burstStats = BurstStats.empty();
			burstMarks = new BurstMark[0];
			return;
		}

		double averageWidthSeconds = widthSum / (double) count / sampleRateHz;
		double averagePeriodSeconds = periodCount <= 0 ? 0 : periodSum / (double) periodCount / sampleRateHz;
		double visibleSeconds = samples / (double) sampleRateHz;
		double dutyPercent = visibleSeconds <= 0 ? 0 : widthSum / (double) samples * 100d;
		if (dutyPercent > 100d) {
			dutyPercent = 100d;
		}
		burstStats = new BurstStats(count, averageWidthSeconds, averagePeriodSeconds, dutyPercent);
		BurstMark[] activeMarks = new BurstMark[markCount];
		System.arraycopy(marks, 0, activeMarks, 0, markCount);
		burstMarks = activeMarks;
	}

	private void updateBurstStatsFromEnvelope(int[] envelope, int bins, double secondsPerBin) {
		if (envelope == null || bins <= 2 || secondsPerBin <= 0) {
			burstStats = BurstStats.empty();
			return;
		}
		long now = System.nanoTime();
		if (now - lastBurstStatsUpdateNanos < 350_000_000L) {
			return;
		}
		lastBurstStatsUpdateNanos = now;

		int min = 181;
		int max = 0;
		for (int bin = 0; bin < bins; bin++) {
			int mag = envelope[bin];
			if (mag < min) {
				min = mag;
			}
			if (mag > max) {
				max = mag;
			}
		}
		if (max - min < 6) {
			burstStats = BurstStats.empty();
			return;
		}

		int high = triggerEnabled ? triggerThreshold : min + Math.round((max - min) * 0.45f);
		int low = Math.max(min + 1, high - Math.max(4, (high - min) / 3));
		int minimumBurstBins = Math.max(1, (int) Math.round(50e-6d / secondsPerBin));
		boolean inside = false;
		int start = 0;
		boolean partialAtLeftEdge = false;
		int previousStart = -1;
		int count = 0;
		long widthSum = 0;
		long periodSum = 0;
		int periodCount = 0;

		for (int bin = 0; bin < bins; bin++) {
			int mag = envelope[bin];
			if (!inside && mag >= high) {
				inside = true;
				start = bin;
				partialAtLeftEdge = bin == 0;
				if (previousStart >= 0) {
					periodSum += bin - previousStart;
					periodCount++;
				}
				previousStart = bin;
			} else if (inside && mag <= low) {
				int width = bin - start;
				if (!partialAtLeftEdge && width >= minimumBurstBins) {
					widthSum += width;
					count++;
				}
				inside = false;
				partialAtLeftEdge = false;
			}
		}
		if (count <= 0) {
			burstStats = BurstStats.empty();
			return;
		}

		double averageWidthSeconds = widthSum / (double) count * secondsPerBin;
		double averagePeriodSeconds = periodCount <= 0 ? 0 : periodSum / (double) periodCount * secondsPerBin;
		double dutyPercent = Math.min(100d, widthSum / (double) bins * 100d);
		burstStats = new BurstStats(count, averageWidthSeconds, averagePeriodSeconds, dutyPercent);
	}

	private int[] prepareBurstEnvelope(int samples) {
		if (burstEnvelopeScratch.length < samples) {
			burstEnvelopeScratch = new int[samples];
			burstMagnitudeScratch = new int[samples];
			burstPrefixScratch = new long[samples + 1];
		}
		int smoothingSamples = Math.max(1, Math.min(samples / 25, (int) Math.round(sampleRateHz * 20e-6d)));
		burstPrefixScratch[0] = 0;
		for (int sample = 0; sample < samples; sample++) {
			int magnitude = magnitude(snapshot[sample * 2], snapshot[sample * 2 + 1]);
			burstMagnitudeScratch[sample] = magnitude;
			burstPrefixScratch[sample + 1] = burstPrefixScratch[sample] + magnitude;
		}
		int halfWindow = smoothingSamples / 2;
		for (int sample = 0; sample < samples; sample++) {
			int left = Math.max(0, sample - halfWindow);
			int right = Math.min(samples, sample + halfWindow + 1);
			int count = Math.max(1, right - left);
			long sum = burstPrefixScratch[right] - burstPrefixScratch[left];
			burstEnvelopeScratch[sample] = (int) Math.round(sum / (double) count);
		}
		return burstEnvelopeScratch;
	}

	private int peakEnvelope(int[] envelope, int start, int end) {
		int peak = 0;
		int safeEnd = Math.min(end, envelope.length);
		for (int i = Math.max(0, start); i < safeEnd; i++) {
			if (envelope[i] > peak) {
				peak = envelope[i];
			}
		}
		return peak;
	}

	private int magnitude(int i, int q) {
		return (int) Math.round(Math.sqrt(i * i + q * q));
	}

	private static class BurstStats {
		final int count;
		final double averageWidthSeconds;
		final double averagePeriodSeconds;
		final double dutyPercent;

		BurstStats(int count, double averageWidthSeconds, double averagePeriodSeconds, double dutyPercent) {
			this.count = count;
			this.averageWidthSeconds = averageWidthSeconds;
			this.averagePeriodSeconds = averagePeriodSeconds;
			this.dutyPercent = dutyPercent;
		}

		static BurstStats empty() {
			return new BurstStats(0, 0, 0, 0);
		}
	}

	private static class BurstMark {
		final int startSample;
		final int endSample;
		final int peakMagnitude;

		BurstMark(int startSample, int endSample, int peakMagnitude) {
			this.startSample = startSample;
			this.endSample = endSample;
			this.peakMagnitude = peakMagnitude;
		}
	}
}
