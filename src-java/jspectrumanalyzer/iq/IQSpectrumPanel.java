package jspectrumanalyzer.iq;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.function.DoubleConsumer;

import javax.swing.JPanel;

public class IQSpectrumPanel extends JPanel {
	private static final long serialVersionUID = -8323865528732112529L;
	private static final int FFT_SIZE = 1024;
	private static final float MIN_DB_SPAN = 24f;
	private static final float MAX_DB_SPAN = 80f;
	private static final float DB_HEADROOM = 4f;
	private static final float SPECTRUM_SMOOTHING = 0.35f;
	private static final float SCALE_SMOOTHING = 0.32f;

	private final byte[] snapshot = new byte[FFT_SIZE * 2];
	private final double[] real = new double[FFT_SIZE];
	private final double[] imag = new double[FFT_SIZE];
	private final float[] spectrumDb = new float[FFT_SIZE];
	private final float[] smoothedDb = new float[FFT_SIZE];
	private final float[] sortedDb = new float[FFT_SIZE];
	private IQRingBuffer ringBuffer;
	private volatile int sampleRateHz = 1;
	private volatile int channelBandwidthHz = 0;
	private volatile long centerFrequencyHz = 0;
	private volatile long channelOffsetHz = 0;
	private volatile long dcArtifactOffsetHz = 0;
	private DoubleConsumer offsetDragListener;
	private double dragStartHz = 0;
	private double dragDeltaHz = 0;
	private boolean dragging = false;
	private float displayMinDb = -90f;
	private float displayMaxDb = -10f;

	public IQSpectrumPanel(IQRingBuffer ringBuffer) {
		this.ringBuffer = ringBuffer;
		setBackground(Color.BLACK);
		setPreferredSize(new Dimension(1000, 190));
		setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		installMouseControls();
	}

	public void setRingBuffer(IQRingBuffer ringBuffer) {
		this.ringBuffer = ringBuffer;
	}

	public void setSampleRateHz(int sampleRateHz) {
		this.sampleRateHz = Math.max(1, sampleRateHz);
	}

	public void setCenterFrequencyHz(long centerFrequencyHz) {
		this.centerFrequencyHz = Math.max(0, centerFrequencyHz);
	}

	public void setChannelOffsetHz(long channelOffsetHz) {
		this.channelOffsetHz = channelOffsetHz;
	}

	public void setDcArtifactOffsetHz(long dcArtifactOffsetHz) {
		this.dcArtifactOffsetHz = dcArtifactOffsetHz;
	}

	public void setChannelBandwidthHz(int channelBandwidthHz) {
		this.channelBandwidthHz = Math.max(0, channelBandwidthHz);
	}

	public void setOffsetDragListener(DoubleConsumer offsetDragListener) {
		this.offsetDragListener = offsetDragListener;
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
		int top = 22;
		int bottom = height - 22;
		int plotHeight = Math.max(1, bottom - top);

		g.setColor(new Color(0x101010));
		g.fillRect(0, 0, width, height);
		drawGrid(g, width, top, bottom);
		drawChannelBandwidth(g, width, top, bottom);
		drawDragHint(g, width, top);

		IQRingBuffer active = ringBuffer;
		int read = active == null ? 0 : active.readLatest(snapshot, snapshot.length);
		if (read < snapshot.length) {
			g.setColor(new Color(0xaaaaaa));
			g.drawString("Waiting for spectrum samples...", 12, top + 24);
			return;
		}

		computeSpectrum();
		smoothSpectrum();
		updateScale();
		g.setColor(new Color(0x4cc9f0));
		g.setStroke(new BasicStroke(1.2f));
		int shiftX = dragging ? offsetHzToSignedPixels(dragDeltaHz, width) : 0;
		int previousX = shiftX;
		int previousY = dbToY(smoothedDb[0], top, plotHeight);
		for (int x = 1; x < width; x++) {
			int bin = x * (FFT_SIZE - 1) / (width - 1);
			int y = dbToY(smoothedDb[bin], top, plotHeight);
			int drawX = x + shiftX;
			g.drawLine(previousX, previousY, drawX, y);
			previousX = drawX;
			previousY = y;
		}

		drawLabels(g, width, height);
	}

	private void drawGrid(Graphics2D g, int width, int top, int bottom) {
		g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
		g.setColor(new Color(0x505050));
		for (int i = 0; i <= 10; i++) {
			int x = i * width / 10;
			g.drawLine(x, top, x, bottom);
		}
		for (int i = 0; i <= 6; i++) {
			int y = top + i * (bottom - top) / 6;
			g.drawLine(0, y, width, y);
		}
		g.setColor(new Color(0x666666));
		int centerX = width / 2;
		g.drawLine(centerX, top, centerX, bottom);
	}

	private void drawChannelBandwidth(Graphics2D g, int width, int top, int bottom) {
		int bandwidthHz = channelBandwidthHz;
		if (bandwidthHz <= 0 || sampleRateHz <= 0 || width <= 1) {
			return;
		}
		double halfBandwidth = Math.min(sampleRateHz / 2d, bandwidthHz / 2d);
		int left = offsetHzToX(-halfBandwidth, width);
		int right = offsetHzToX(halfBandwidth, width);
		if (right <= left) {
			return;
		}
		g.setColor(new Color(0x163040));
		g.fillRect(left, top, right - left + 1, bottom - top);
		g.setColor(new Color(0x79d8ff));
		g.drawLine(left, top, left, bottom);
		g.drawLine(right, top, right, bottom);
	}

	private void drawDragHint(Graphics2D g, int width, int top) {
		if (dragging || offsetDragListener == null || width < 220) {
			return;
		}
		g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
		g.setColor(new Color(0x9f9f9f));
		int x = Math.max(12, width - 166);
		int y = top + 5;
		drawHandIcon(g, x, y);
		g.drawString("drag to tune offset", x + 20, top + 16);
	}

	private void drawHandIcon(Graphics2D g, int x, int y) {
		g.setColor(new Color(0xb6b6b6));
		g.setStroke(new BasicStroke(1.4f));
		g.drawLine(x + 4, y + 8, x + 4, y + 2);
		g.drawLine(x + 7, y + 8, x + 7, y + 1);
		g.drawLine(x + 10, y + 8, x + 10, y + 2);
		g.drawLine(x + 13, y + 9, x + 13, y + 4);
		g.drawLine(x + 3, y + 8, x + 8, y + 14);
		g.drawLine(x + 8, y + 14, x + 14, y + 14);
		g.drawLine(x + 14, y + 14, x + 16, y + 9);
		g.drawLine(x + 3, y + 8, x, y + 10);
		g.drawLine(x, y + 10, x + 5, y + 15);
	}

	private void drawLabels(Graphics2D g, int width, int height) {
		double spanKhz = sampleRateHz / 1000d;
		g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
		g.setColor(new Color(0xdddddd));
		String bwText = channelBandwidthHz > 0 ? String.format("   BW %.1f kHz", channelBandwidthHz / 1000d) : "";
		g.drawString(String.format("Channel spectrum   span %.1f kHz%s", spanKhz, bwText), 12, 16);
		g.setColor(new Color(0x999999));
		long centerHz = Math.max(0, Math.round(centerFrequencyHz + channelOffsetHz - (dragging ? dragDeltaHz : 0d)));
		long leftHz = Math.max(0, Math.round(centerHz - sampleRateHz / 2d));
		long rightHz = Math.max(0, Math.round(centerHz + sampleRateHz / 2d));
		String left = formatFrequencyLabel(leftHz);
		String center = formatFrequencyLabel(centerHz);
		String right = formatFrequencyLabel(rightHz);
		g.drawString(left, 8, height - 6);
		g.drawString(center, Math.max(8, width / 2 - g.getFontMetrics().stringWidth(center) / 2), height - 6);
		g.drawString(right, Math.max(8, width - g.getFontMetrics().stringWidth(right) - 8), height - 6);
		g.drawString(String.format("%.0f..%.0f dB", displayMinDb, displayMaxDb), Math.max(8, width - 86), 16);
	}

	private String formatFrequencyLabel(long frequencyHz) {
		return String.format("%.2f MHz", frequencyHz / 1_000_000d);
	}

	private void computeSpectrum() {
		for (int i = 0; i < FFT_SIZE; i++) {
			double window = 0.5d - 0.5d * Math.cos(2d * Math.PI * i / (FFT_SIZE - 1));
			real[i] = snapshot[i * 2] / 128d * window;
			imag[i] = snapshot[i * 2 + 1] / 128d * window;
		}
		fft(real, imag);
		for (int i = 0; i < FFT_SIZE; i++) {
			int shifted = (i + FFT_SIZE / 2) % FFT_SIZE;
			double mag = Math.sqrt(real[shifted] * real[shifted] + imag[shifted] * imag[shifted]);
			spectrumDb[i] = (float) (20d * Math.log10(mag + 1e-9));
		}
		suppressDcSpike(dcArtifactOffsetHz);
	}

	private void suppressDcSpike(long offsetHz) {
		int center = offsetHzToBin(offsetHz);
		int radius = 1;
		int referenceRadius = 5;
		float reference = 0f;
		int count = 0;
		for (int offset = -referenceRadius; offset <= referenceRadius; offset++) {
			if (Math.abs(offset) <= radius) {
				continue;
			}
			int index = center + offset;
			if (index >= 0 && index < spectrumDb.length) {
				reference += spectrumDb[index];
				count++;
			}
		}
		if (count == 0) {
			return;
		}
		reference /= count;
		for (int offset = -radius; offset <= radius; offset++) {
			int index = center + offset;
			if (index >= 0 && index < spectrumDb.length && spectrumDb[index] > reference) {
				spectrumDb[index] = reference;
			}
		}
	}

	private int offsetHzToBin(long offsetHz) {
		double normalized = offsetHz / (double) Math.max(1, sampleRateHz) + 0.5d;
		int bin = (int) Math.round(normalized * (FFT_SIZE - 1));
		return Math.max(0, Math.min(FFT_SIZE - 1, bin));
	}

	private void smoothSpectrum() {
		for (int i = 0; i < FFT_SIZE; i++) {
			if (smoothedDb[i] == 0f) {
				smoothedDb[i] = spectrumDb[i];
			} else {
				smoothedDb[i] = smoothedDb[i] * (1f - SPECTRUM_SMOOTHING) + spectrumDb[i] * SPECTRUM_SMOOTHING;
			}
		}
	}

	private int dbToY(float db, int top, int plotHeight) {
		if (db < displayMinDb) {
			db = displayMinDb;
		}
		if (db > displayMaxDb) {
			db = displayMaxDb;
		}
		double normalized = (db - displayMinDb) / Math.max(1e-6, displayMaxDb - displayMinDb);
		return top + plotHeight - (int) Math.round(normalized * plotHeight);
	}

	private void updateScale() {
		System.arraycopy(smoothedDb, 0, sortedDb, 0, smoothedDb.length);
		Arrays.sort(sortedDb);

		float low = percentile(sortedDb, 0.05f) - DB_HEADROOM;
		float high = percentile(sortedDb, 0.99f) + DB_HEADROOM;
		float span = high - low;
		if (span < MIN_DB_SPAN) {
			float center = (high + low) * 0.5f;
			low = center - MIN_DB_SPAN * 0.5f;
			high = center + MIN_DB_SPAN * 0.5f;
		} else if (span > MAX_DB_SPAN) {
			low = high - MAX_DB_SPAN;
		}

		displayMinDb = displayMinDb * (1f - SCALE_SMOOTHING) + low * SCALE_SMOOTHING;
		displayMaxDb = displayMaxDb * (1f - SCALE_SMOOTHING) + high * SCALE_SMOOTHING;
	}

	private float percentile(float[] sorted, float percentile) {
		int index = Math.round((sorted.length - 1) * Math.max(0f, Math.min(1f, percentile)));
		return sorted[index];
	}

	private void installMouseControls() {
		MouseAdapter adapter = new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				dragStartHz = xToOffsetHz(e.getX());
				dragDeltaHz = 0;
				dragging = true;
				notifyOffsetDrag(Double.NaN);
				repaint();
			}

			@Override
			public void mouseDragged(MouseEvent e) {
				dragDeltaHz = xToOffsetHz(e.getX()) - dragStartHz;
				notifyOffsetDrag(dragDeltaHz);
				repaint();
			}

			@Override
			public void mouseReleased(MouseEvent e) {
				if (!dragging) {
					return;
				}
				dragDeltaHz = xToOffsetHz(e.getX()) - dragStartHz;
				notifyOffsetDrag(dragDeltaHz);
				notifyOffsetDrag(Double.POSITIVE_INFINITY);
				dragging = false;
				dragDeltaHz = 0;
				repaint();
			}
		};
		addMouseListener(adapter);
		addMouseMotionListener(adapter);
	}

	private void notifyOffsetDrag(double deltaHz) {
		DoubleConsumer listener = offsetDragListener;
		if (listener == null) {
			return;
		}
		listener.accept(deltaHz);
	}

	private double xToOffsetHz(int mouseX) {
		if (getWidth() <= 1) {
			return 0;
		}
		double clampedX = Math.max(0, Math.min(getWidth() - 1, mouseX));
		double relative = clampedX / (getWidth() - 1d) - 0.5d;
		return relative * sampleRateHz;
	}

	private int offsetHzToX(double offsetHz, int width) {
		double normalized = offsetHz / sampleRateHz + 0.5d;
		int x = (int) Math.round(normalized * (width - 1));
		return Math.max(0, Math.min(width - 1, x));
	}

	private int offsetHzToSignedPixels(double offsetHz, int width) {
		if (sampleRateHz <= 0 || width <= 1) {
			return 0;
		}
		return (int) Math.round(offsetHz / sampleRateHz * (width - 1));
	}

	private void fft(double[] real, double[] imag) {
		int n = real.length;
		for (int i = 1, j = 0; i < n; i++) {
			int bit = n >> 1;
			for (; (j & bit) != 0; bit >>= 1) {
				j ^= bit;
			}
			j ^= bit;
			if (i < j) {
				double tr = real[i];
				real[i] = real[j];
				real[j] = tr;
				double ti = imag[i];
				imag[i] = imag[j];
				imag[j] = ti;
			}
		}

		for (int len = 2; len <= n; len <<= 1) {
			double angle = -2d * Math.PI / len;
			double wlenR = Math.cos(angle);
			double wlenI = Math.sin(angle);
			for (int i = 0; i < n; i += len) {
				double wr = 1d;
				double wi = 0d;
				for (int j = 0; j < len / 2; j++) {
					int u = i + j;
					int v = i + j + len / 2;
					double vr = real[v] * wr - imag[v] * wi;
					double vi = real[v] * wi + imag[v] * wr;
					real[v] = real[u] - vr;
					imag[v] = imag[u] - vi;
					real[u] += vr;
					imag[u] += vi;
					double nextWr = wr * wlenR - wi * wlenI;
					wi = wr * wlenI + wi * wlenR;
					wr = nextWr;
				}
			}
		}
	}
}
