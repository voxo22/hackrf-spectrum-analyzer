package jspectrumanalyzer.iq;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

final class SignalQualityTesterFrame extends JFrame {
	private static final int MAX_SAMPLES = 4096;
	private static final int SPECTRUM_SIZE = 1024;
	private static final long MIN_ANALYSIS_INTERVAL_NANOS = 50_000_000L;
	private static final Color PANEL_BG = Color.BLACK;
	private static final Color TEXT_FG = Color.WHITE;
	private static final Color MUTED_FG = new Color(0xdddddd);
	private static final Color GRID = new Color(0x2d2d2d);
	private static final Color POINT = new Color(0x55ccff);
	private static final Color QUALITY_GOOD = new Color(0x44cc44);
	private static final Color QUALITY_WARN = new Color(0xffcc33);
	private static final Color QUALITY_BAD = new Color(0xff5555);

	private final ScatterPanel scatterPanel = new ScatterPanel();
	private final SpectrumPanel spectrumPanel = new SpectrumPanel();
	private final QualityPanel qualityPanel = new QualityPanel();
	private final JLabel titleLabel;
	private final JLabel statsLabel = new JLabel("Waiting for IQ samples...");
	private final JLabel detailLabel = new JLabel("RAW quality waits for signal level, clipping, DC offset and stability");
	private final Timer repaintTimer;

	private volatile Snapshot snapshot;
	private volatile long lastAnalysisNanos = 0;
	private double emaDbfs = Double.NaN;
	private double stabilityDb = 0;

	SignalQualityTesterFrame(String mode, Runnable closedCallback) {
		super("Signal Quality Tester");
		setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

		titleLabel = new JLabel(mode + " raw IQ monitor");
		titleLabel.setForeground(TEXT_FG);
		titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14f));
		statsLabel.setForeground(MUTED_FG);
		detailLabel.setForeground(MUTED_FG);

		JPanel header = new JPanel(new BorderLayout(8, 0));
		header.setBackground(PANEL_BG);
		header.setBorder(new EmptyBorder(8, 10, 6, 10));
		header.add(titleLabel, BorderLayout.WEST);
		header.add(statsLabel, BorderLayout.CENTER);

		JPanel footer = new JPanel(new BorderLayout(8, 4));
		footer.setBackground(PANEL_BG);
		footer.setBorder(new EmptyBorder(6, 10, 8, 10));
		footer.add(qualityPanel, BorderLayout.NORTH);
		footer.add(detailLabel, BorderLayout.CENTER);

		scatterPanel.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
		spectrumPanel.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
		JPanel plots = new JPanel(new GridLayout(1, 2, 6, 0));
		plots.setBackground(PANEL_BG);
		plots.setBorder(new EmptyBorder(0, 8, 0, 8));
		plots.add(scatterPanel);
		plots.add(spectrumPanel);

		add(header, BorderLayout.NORTH);
		add(plots, BorderLayout.CENTER);
		add(footer, BorderLayout.SOUTH);
		setSize(760, 420);

		repaintTimer = new Timer(100, e -> updateView());
		repaintTimer.start();
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosed(WindowEvent e) {
				repaintTimer.stop();
				if (closedCallback != null) {
					closedCallback.run();
				}
			}
		});
	}

	void offerIQBlock(long centerFreqHz, int sampleRateHz, byte[] iqData, int length) {
		long now = System.nanoTime();
		if (now - lastAnalysisNanos < MIN_ANALYSIS_INTERVAL_NANOS) {
			return;
		}
		lastAnalysisNanos = now;
		if (iqData == null || length < 2) {
			return;
		}
		int evenLength = Math.min(length, iqData.length) & ~1;
		int totalSamples = evenLength / 2;
		if (totalSamples <= 0) {
			return;
		}
		int sampleCount = Math.min(MAX_SAMPLES, totalSamples);
		int stride = Math.max(1, totalSamples / sampleCount);
		byte[] samples = new byte[sampleCount * 2];
		long sumPower = 0;
		long sumI = 0;
		long sumQ = 0;
		int clipped = 0;
		int peak = 0;
		int out = 0;
		for (int sample = 0; sample < totalSamples && out + 1 < samples.length; sample += stride) {
			int offset = sample * 2;
			int i = iqData[offset];
			int q = iqData[offset + 1];
			samples[out++] = (byte) i;
			samples[out++] = (byte) q;
			sumPower += i * i + q * q;
			sumI += i;
			sumQ += q;
			if (Math.abs(i) >= 126 || Math.abs(q) >= 126) {
				clipped++;
			}
			peak = Math.max(peak, Math.max(Math.abs(i), Math.abs(q)));
		}
		if (out != samples.length) {
			byte[] trimmed = new byte[out];
			System.arraycopy(samples, 0, trimmed, 0, out);
			samples = trimmed;
			sampleCount = out / 2;
		}
		double rms = sampleCount <= 0 ? 0 : Math.sqrt(sumPower / (sampleCount * 2d));
		double dbfs = rms <= 0 ? -120d : 20d * Math.log10(rms / 128d);
		double meanI = sampleCount <= 0 ? 0 : sumI / (double) sampleCount;
		double meanQ = sampleCount <= 0 ? 0 : sumQ / (double) sampleCount;
		double dcPercent = Math.sqrt(meanI * meanI + meanQ * meanQ) / 128d * 100d;
		double clippingPercent = sampleCount <= 0 ? 0 : clipped * 100d / sampleCount;
		double quality = calculateRawQuality(dbfs, clippingPercent, dcPercent);
		float[] spectrum = computeSpectrum(samples, sampleCount);
		snapshot = new Snapshot(centerFreqHz, sampleRateHz, samples, sampleCount, dbfs, peak, dcPercent,
				clippingPercent, stabilityDb, quality, spectrum);
	}

	private float[] computeSpectrum(byte[] samples, int sampleCount) {
		if (sampleCount < SPECTRUM_SIZE) {
			return new float[0];
		}
		double[] real = new double[SPECTRUM_SIZE];
		double[] imag = new double[SPECTRUM_SIZE];
		int stride = Math.max(1, sampleCount / SPECTRUM_SIZE);
		for (int i = 0; i < SPECTRUM_SIZE; i++) {
			int source = Math.min(sampleCount - 1, i * stride) * 2;
			double window = 0.5d - 0.5d * Math.cos(2d * Math.PI * i / (SPECTRUM_SIZE - 1));
			real[i] = samples[source] / 128d * window;
			imag[i] = samples[source + 1] / 128d * window;
		}
		fft(real, imag);
		float[] db = new float[SPECTRUM_SIZE];
		for (int i = 0; i < SPECTRUM_SIZE; i++) {
			int shifted = (i + SPECTRUM_SIZE / 2) % SPECTRUM_SIZE;
			double power = real[shifted] * real[shifted] + imag[shifted] * imag[shifted];
			db[i] = (float) (10d * Math.log10(power / SPECTRUM_SIZE + 1e-12));
		}
		return db;
	}

	private void fft(double[] real, double[] imag) {
		int size = real.length;
		for (int i = 1, j = 0; i < size; i++) {
			int bit = size >> 1;
			for (; (j & bit) != 0; bit >>= 1) {
				j ^= bit;
			}
			j ^= bit;
			if (i < j) {
				double temp = real[i];
				real[i] = real[j];
				real[j] = temp;
				temp = imag[i];
				imag[i] = imag[j];
				imag[j] = temp;
			}
		}
		for (int length = 2; length <= size; length <<= 1) {
			double angle = -2d * Math.PI / length;
			double wLengthReal = Math.cos(angle);
			double wLengthImag = Math.sin(angle);
			for (int start = 0; start < size; start += length) {
				double wReal = 1d;
				double wImag = 0d;
				for (int j = 0; j < length / 2; j++) {
					int even = start + j;
					int odd = even + length / 2;
					double oddReal = real[odd] * wReal - imag[odd] * wImag;
					double oddImag = real[odd] * wImag + imag[odd] * wReal;
					real[odd] = real[even] - oddReal;
					imag[odd] = imag[even] - oddImag;
					real[even] += oddReal;
					imag[even] += oddImag;
					double nextReal = wReal * wLengthReal - wImag * wLengthImag;
					wImag = wReal * wLengthImag + wImag * wLengthReal;
					wReal = nextReal;
				}
			}
		}
	}

	private double calculateRawQuality(double dbfs, double clippingPercent, double dcPercent) {
		double levelScore;
		if (dbfs < -50d) {
			levelScore = 0;
		} else if (dbfs < -25d) {
			levelScore = (dbfs + 50d) / 25d * 100d;
		} else if (dbfs <= -8d) {
			levelScore = 100d;
		} else if (dbfs <= -3d) {
			levelScore = 100d - (dbfs + 8d) / 5d * 45d;
		} else {
			levelScore = 25d;
		}

		if (Double.isNaN(emaDbfs)) {
			emaDbfs = dbfs;
			stabilityDb = 0;
		} else {
			double delta = Math.abs(dbfs - emaDbfs);
			stabilityDb = stabilityDb * 0.85d + delta * 0.15d;
			emaDbfs = emaDbfs * 0.9d + dbfs * 0.1d;
		}
		double clippingScore = Math.max(0, 100d - clippingPercent * 60d);
		double dcScore = Math.max(0, 100d - dcPercent * 3d);
		double stabilityScore = Math.max(0, 100d - stabilityDb * 20d);
		double quality = levelScore * 0.45d + clippingScore * 0.25d + dcScore * 0.15d + stabilityScore * 0.15d;
		if (clippingPercent >= 1d) {
			quality = Math.min(quality, 45d);
		}
		return Math.max(0, Math.min(100, quality));
	}

	private void updateView() {
		Snapshot active = snapshot;
		scatterPanel.setSnapshot(active);
		spectrumPanel.setSnapshot(active);
		qualityPanel.setSnapshot(active);
		if (active == null) {
			statsLabel.setText("Waiting for IQ samples...");
			detailLabel.setText("RAW quality waits for signal level, clipping, DC offset and stability");
			return;
		}
		statsLabel.setText(String.format(Locale.US, "%.3f MHz   %.3f MS/s   RMS %.1f dBFS   peak %d",
				active.centerFreqHz / 1_000_000d, active.sampleRateHz / 1_000_000d, active.dbfs, active.peak));
		detailLabel.setText(String.format(Locale.US, "DC %.1f%%   clipping %.2f%%   stability %.2f dB",
				active.dcPercent, active.clippingPercent, active.stabilityDb));
	}

	private static final class Snapshot {
		final long centerFreqHz;
		final int sampleRateHz;
		final byte[] samples;
		final int sampleCount;
		final double dbfs;
		final int peak;
		final double dcPercent;
		final double clippingPercent;
		final double stabilityDb;
		final double quality;
		final float[] spectrumDb;

		Snapshot(long centerFreqHz, int sampleRateHz, byte[] samples, int sampleCount, double dbfs, int peak,
				double dcPercent, double clippingPercent, double stabilityDb, double quality, float[] spectrumDb) {
			this.centerFreqHz = centerFreqHz;
			this.sampleRateHz = sampleRateHz;
			this.samples = samples;
			this.sampleCount = sampleCount;
			this.dbfs = dbfs;
			this.peak = peak;
			this.dcPercent = dcPercent;
			this.clippingPercent = clippingPercent;
			this.stabilityDb = stabilityDb;
			this.quality = quality;
			this.spectrumDb = spectrumDb;
		}
	}

	private static final class QualityPanel extends JPanel {
		private volatile Snapshot snapshot;

		QualityPanel() {
			setBackground(PANEL_BG);
			setPreferredSize(new Dimension(100, 24));
		}

		void setSnapshot(Snapshot snapshot) {
			this.snapshot = snapshot;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			super.paintComponent(graphics);
			Graphics2D g = (Graphics2D) graphics.create();
			try {
				int width = getWidth();
				int height = getHeight();
				int barX = 92;
				int barY = 5;
				int barW = Math.max(30, width - barX - 6);
				int barH = Math.max(8, height - 10);
				Snapshot active = snapshot;
				double quality = active == null ? 0 : active.quality;
				Color color = quality >= 70 ? QUALITY_GOOD : quality >= 40 ? QUALITY_WARN : QUALITY_BAD;
				g.setColor(TEXT_FG);
				g.drawString(active == null ? "RAW --%" : String.format(Locale.US, "RAW %.0f%%", quality), 4, 17);
				g.setColor(GRID);
				g.fillRect(barX, barY, barW, barH);
				g.setColor(color);
				g.fillRect(barX, barY, (int) Math.round(barW * quality / 100d), barH);
				g.setColor(Color.DARK_GRAY);
				g.drawRect(barX, barY, barW, barH);
			} finally {
				g.dispose();
			}
		}
	}

	private static final class SpectrumPanel extends JPanel {
		private volatile Snapshot snapshot;

		SpectrumPanel() {
			setBackground(PANEL_BG);
		}

		void setSnapshot(Snapshot snapshot) {
			this.snapshot = snapshot;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			super.paintComponent(graphics);
			Graphics2D g = (Graphics2D) graphics.create();
			try {
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				int width = getWidth();
				int height = getHeight();
				int left = 12;
				int right = width - 10;
				int top = 22;
				int bottom = height - 22;
				g.setColor(GRID);
				for (int i = 0; i <= 4; i++) {
					int y = top + (bottom - top) * i / 4;
					g.drawLine(left, y, right, y);
				}
				g.drawLine(width / 2, top, width / 2, bottom);
				g.setColor(MUTED_FG);
				g.drawString("FFT spectrum", left, 15);
				Snapshot active = snapshot;
				if (active == null || active.spectrumDb == null || active.spectrumDb.length == 0) {
					g.drawString("Waiting for spectrum...", left, top + 24);
					return;
				}
				float[] spectrum = active.spectrumDb;
				float min = Float.MAX_VALUE;
				float max = -Float.MAX_VALUE;
				for (float value : spectrum) {
					if (value < min) {
						min = value;
					}
					if (value > max) {
						max = value;
					}
				}
				float floor = Math.max(min, max - 70f);
				int previousX = left;
				int previousY = scaleSpectrumY(spectrum[0], floor, max, top, bottom);
				g.setColor(POINT);
				for (int i = 1; i < spectrum.length; i++) {
					int x = left + Math.round((right - left) * i / (float) (spectrum.length - 1));
					int y = scaleSpectrumY(spectrum[i], floor, max, top, bottom);
					g.drawLine(previousX, previousY, x, y);
					previousX = x;
					previousY = y;
				}
				g.setColor(MUTED_FG);
				g.drawString("-span/2", left, height - 6);
				g.drawString("0", Math.max(left, width / 2 - 4), height - 6);
				String rightLabel = "+span/2";
				g.drawString(rightLabel, Math.max(left, right - g.getFontMetrics().stringWidth(rightLabel)), height - 6);
			} finally {
				g.dispose();
			}
		}

		private int scaleSpectrumY(float value, float min, float max, int top, int bottom) {
			float range = Math.max(1f, max - min);
			float normalized = Math.max(0f, Math.min(1f, (value - min) / range));
			return bottom - Math.round((bottom - top) * normalized);
		}
	}

	private static final class ScatterPanel extends JPanel {
		private volatile Snapshot snapshot;

		ScatterPanel() {
			setBackground(PANEL_BG);
		}

		void setSnapshot(Snapshot snapshot) {
			this.snapshot = snapshot;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			super.paintComponent(graphics);
			Graphics2D g = (Graphics2D) graphics.create();
			try {
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				int width = getWidth();
				int height = getHeight();
				int cx = width / 2;
				int cy = height / 2;
				int radius = Math.max(20, Math.min(width, height) / 2 - 24);
				g.setColor(GRID);
				g.drawLine(16, cy, width - 16, cy);
				g.drawLine(cx, 16, cx, height - 16);
				g.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);
				Snapshot active = snapshot;
				if (active == null || active.sampleCount <= 0) {
					g.setColor(MUTED_FG);
					g.drawString("Waiting for IQ samples...", 18, 28);
					return;
				}
				g.setColor(POINT);
				byte[] samples = active.samples;
				for (int i = 0; i + 1 < samples.length; i += 2) {
					int x = cx + Math.round(samples[i] / 128f * radius);
					int y = cy - Math.round(samples[i + 1] / 128f * radius);
					g.fillRect(x, y, 2, 2);
				}
			} finally {
				g.dispose();
			}
		}
	}
}
