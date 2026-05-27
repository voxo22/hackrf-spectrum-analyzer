package jspectrumanalyzer.capture;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.text.DecimalFormat;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

import jspectrumanalyzer.core.DatasetSpectrumPeak;
import jspectrumanalyzer.core.jfc.XYSeriesImmutable;
import jspectrumanalyzer.ui.ColorPalette;
import jspectrumanalyzer.ui.HotIronBluePalette;

public class SpectrumVideoRenderer implements VideoFrameSource {
	private static final double RANGE_MIN_DBM = -100;
	private static final double RANGE_MAX_DBM = -10;

	private final Supplier<DatasetSpectrumPeak> spectrumSupplier;
	private final BooleanSupplier showPeaks;
	private final BooleanSupplier showAverage;
	private final BooleanSupplier showMaxHold;
	private final BooleanSupplier showMinHold;
	private final BooleanSupplier showRealtime;
	private final Supplier<Float> lineThicknessSupplier;
	private final BooleanSupplier showDatestamp;
	private final BooleanSupplier replayVisible;
	private final BooleanSupplier liveRecordingVisible;
	private final BooleanSupplier mouseCrossVisible;
	private final Supplier<String> datestampSupplier;
	private final Supplier<String> statusSupplier;
	private final Supplier<String> mouseFrequencyLabelSupplier;
	private final Supplier<String> mouseAmplitudeLabelSupplier;
	private final DoubleSupplier mouseDomainSupplier;
	private final DoubleSupplier mouseAmplitudeSupplier;
	private final Supplier<DatasetSpectrumPeak.SpectrumMarker[]> peakMarkersSupplier;
	private final Supplier<DatasetSpectrumPeak.SpectrumMarker[]> maxHoldMarkersSupplier;
	private final Supplier<BufferedImage> allocationImageSupplier;
	private final Supplier<int[]> rangePairsSupplier;
	private final DoubleSupplier paletteStartSupplier;
	private final DoubleSupplier paletteSizeSupplier;
	private final BooleanSupplier showPaletteScale;
	private final Color peakColor;
	private final Color averageColor;
	private final Color maxHoldColor;
	private final Color minHoldColor;
	private final Color peakMarkerColor;
	private final Color maxHoldMarkerColor;
	private final Color realtimeColor;
	private final Color foregroundColor;
	private final Color backgroundColor;
	private final Color plotBackgroundColor;
	private final ColorPalette palette = new HotIronBluePalette();

	public SpectrumVideoRenderer(Supplier<DatasetSpectrumPeak> spectrumSupplier, BooleanSupplier showPeaks,
			BooleanSupplier showAverage, BooleanSupplier showMaxHold, BooleanSupplier showMinHold,
			BooleanSupplier showRealtime, Supplier<Float> lineThicknessSupplier, Color peakColor, Color averageColor,
			Color maxHoldColor, Color minHoldColor, Color realtimeColor, Color foregroundColor, Color backgroundColor,
			Color plotBackgroundColor) {
		this(spectrumSupplier, showPeaks, showAverage, showMaxHold, showMinHold, showRealtime, lineThicknessSupplier,
				() -> false, () -> false, () -> false, () -> false, () -> "", () -> "", () -> "", () -> "", () -> 0d,
				() -> 0d, () -> new DatasetSpectrumPeak.SpectrumMarker[0],
				() -> new DatasetSpectrumPeak.SpectrumMarker[0], () -> null, () -> null, () -> -110d, () -> 65d,
				() -> false, peakColor, averageColor, maxHoldColor, minHoldColor, peakColor, maxHoldColor,
				realtimeColor, foregroundColor, backgroundColor, plotBackgroundColor);
	}

	public SpectrumVideoRenderer(Supplier<DatasetSpectrumPeak> spectrumSupplier, BooleanSupplier showPeaks,
			BooleanSupplier showAverage, BooleanSupplier showMaxHold, BooleanSupplier showMinHold,
			BooleanSupplier showRealtime, Supplier<Float> lineThicknessSupplier, BooleanSupplier showDatestamp,
			BooleanSupplier replayVisible, BooleanSupplier liveRecordingVisible, BooleanSupplier mouseCrossVisible,
			Supplier<String> datestampSupplier, Supplier<String> statusSupplier,
			Supplier<String> mouseFrequencyLabelSupplier, Supplier<String> mouseAmplitudeLabelSupplier,
			DoubleSupplier mouseDomainSupplier, DoubleSupplier mouseAmplitudeSupplier,
			Supplier<DatasetSpectrumPeak.SpectrumMarker[]> peakMarkersSupplier,
			Supplier<DatasetSpectrumPeak.SpectrumMarker[]> maxHoldMarkersSupplier,
			Supplier<BufferedImage> allocationImageSupplier,
			Supplier<int[]> rangePairsSupplier, DoubleSupplier paletteStartSupplier, DoubleSupplier paletteSizeSupplier,
			BooleanSupplier showPaletteScale, Color peakColor, Color averageColor, Color maxHoldColor,
			Color minHoldColor, Color peakMarkerColor, Color maxHoldMarkerColor, Color realtimeColor,
			Color foregroundColor, Color backgroundColor, Color plotBackgroundColor) {
		this.spectrumSupplier = spectrumSupplier;
		this.showPeaks = showPeaks;
		this.showAverage = showAverage;
		this.showMaxHold = showMaxHold;
		this.showMinHold = showMinHold;
		this.showRealtime = showRealtime;
		this.lineThicknessSupplier = lineThicknessSupplier;
		this.showDatestamp = showDatestamp;
		this.replayVisible = replayVisible;
		this.liveRecordingVisible = liveRecordingVisible;
		this.mouseCrossVisible = mouseCrossVisible;
		this.datestampSupplier = datestampSupplier;
		this.statusSupplier = statusSupplier;
		this.mouseFrequencyLabelSupplier = mouseFrequencyLabelSupplier;
		this.mouseAmplitudeLabelSupplier = mouseAmplitudeLabelSupplier;
		this.mouseDomainSupplier = mouseDomainSupplier;
		this.mouseAmplitudeSupplier = mouseAmplitudeSupplier;
		this.peakMarkersSupplier = peakMarkersSupplier;
		this.maxHoldMarkersSupplier = maxHoldMarkersSupplier;
		this.allocationImageSupplier = allocationImageSupplier;
		this.rangePairsSupplier = rangePairsSupplier;
		this.paletteStartSupplier = paletteStartSupplier;
		this.paletteSizeSupplier = paletteSizeSupplier;
		this.showPaletteScale = showPaletteScale;
		this.peakColor = peakColor;
		this.averageColor = averageColor;
		this.maxHoldColor = maxHoldColor;
		this.minHoldColor = minHoldColor;
		this.peakMarkerColor = peakMarkerColor;
		this.maxHoldMarkerColor = maxHoldMarkerColor;
		this.realtimeColor = realtimeColor;
		this.foregroundColor = foregroundColor;
		this.backgroundColor = backgroundColor;
		this.plotBackgroundColor = plotBackgroundColor;
	}

	@Override
	public BufferedImage renderFrame(int width, int height) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
		Graphics2D g = image.createGraphics();
		try {
			renderInto(g, width, height);
		} finally {
			g.dispose();
		}
		return image;
	}

	public Rectangle renderInto(Graphics2D g, int width, int height) {
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		g.setColor(backgroundColor);
		g.fillRect(0, 0, width, height);

		DatasetSpectrumPeak spectrum = spectrumSupplier.get();
		if (spectrum == null || spectrum.spectrumLength() == 0) {
			return calculatePlotBounds(width, height);
		}

		Rectangle plot = calculatePlotBounds(width, height);

		drawPlot(g, spectrum, plot.x, plot.y, plot.width, plot.height);
		drawAllocationOverlay(g, plot);
		drawCompressedRangeSeparators(g, plot);
		drawSeries(g, spectrum, plot.x, plot.y, plot.width, plot.height);
		drawSpectrumMarkers(g, spectrum, plot);
		drawMouseCross(g, spectrum, plot);
		if (showPaletteScale.getAsBoolean()) {
			drawPaletteScale(g, plot);
		}
		drawDatestampAndStatus(g, plot);
		return plot;
	}

	public static Rectangle calculatePlotBounds(int width, int height) {
		int left = 60;
		int right = 40;
		int top = 24;
		int bottom = 50;
		if (height < 500) {
			top = 22;
			bottom = 42;
		}
		return new Rectangle(left, top, Math.max(1, width - left - right), Math.max(1, height - top - bottom));
	}

	private void drawPlot(Graphics2D g, DatasetSpectrumPeak spectrum, int x, int y, int w, int h) {
		g.setColor(plotBackgroundColor);
		g.fillRect(x, y, w, h);

		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, h < 350 ? 12 : 14));
		FontMetrics fm = g.getFontMetrics();
		Color grid = new Color(85, 85, 85);
		g.setStroke(new BasicStroke(1f));
		g.setColor(grid);
		for (int db = -100; db <= -10; db += 10) {
			int yy = toY(db, y, h);
			g.drawLine(x, yy, x + w, yy);
			g.setColor(foregroundColor);
			String label = Integer.toString(db);
			g.drawString(label, x - fm.stringWidth(label) - 8, yy + fm.getAscent() / 2 - 2);
			g.setColor(grid);
		}

		int[] pairs = rangePairsSupplier.get();
		double minFreq = spectrum.getFreqStartMHz() + spectrum.getFreqShift();
		double maxFreq = spectrum.getFreqStopMHz() + spectrum.getFreqShift();
		DecimalFormat freqFormat = new DecimalFormat("0.###");
		for (int i = 0; i <= 8; i++) {
			int xx = x + (int) Math.round(i * w / 8d);
			double freq;
			if (isCompressed(pairs)) {
				double total = totalRangesLength(pairs);
				freq = mapCompressedToOriginal(total * i / 8d, pairs) + spectrum.getFreqShift();
			} else {
				freq = minFreq + (maxFreq - minFreq) * i / 8d;
			}
			g.setColor(grid);
			g.drawLine(xx, y, xx, y + h);
			g.setColor(foregroundColor);
			String label = freqFormat.format(freq);
			g.drawString(label, xx - fm.stringWidth(label) / 2, y + h + fm.getAscent() + 8);
		}

		g.setColor(foregroundColor);
		g.drawRect(x, y, w, h);
		String xLabel = "Frequency (MHz)";
		g.drawString(xLabel, x + (w - fm.stringWidth(xLabel)) / 2, y + h + fm.getAscent() + 28);
		String yLabel = "Amplitude (dBm)";
		g.rotate(-Math.PI / 2);
		g.drawString(yLabel, -(y + (h + fm.stringWidth(yLabel)) / 2), Math.max(14, x - 42));
		g.rotate(Math.PI / 2);
	}

	private void drawAllocationOverlay(Graphics2D g, Rectangle plot) {
		BufferedImage image = allocationImageSupplier.get();
		if (image != null) {
			int y = plot.y + 20;
			int h = Math.min(image.getHeight(), Math.max(1, plot.height - 20));
			g.drawImage(image, plot.x, y, plot.width, h, null);
		}
	}

	private void drawCompressedRangeSeparators(Graphics2D g, Rectangle plot) {
		int[] pairs = rangePairsSupplier.get();
		if (pairs == null || pairs.length <= 2)
			return;
		double total = 0;
		for (int i = 0; i + 1 < pairs.length; i += 2) {
			total += pairs[i + 1] - pairs[i];
		}
		if (total <= 0)
			return;
		java.awt.Stroke oldStroke = g.getStroke();
		g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1.0f, new float[] { 4f, 4f },
				0f));
		g.setColor(new Color(255, 255, 255, 120));
		double cum = 0;
		for (int i = 0; i < pairs.length; i += 2) {
			cum += pairs[i + 1] - pairs[i];
			if (i < pairs.length - 2) {
				int x = plot.x + (int) Math.round((cum / total) * plot.width);
				g.drawLine(x, plot.y + 20, x, plot.y + plot.height);
			}
		}
		g.setStroke(oldStroke);
	}

	private void drawPaletteScale(Graphics2D g, Rectangle plot) {
		double start = paletteStartSupplier.getAsDouble();
		double end = start + paletteSizeSupplier.getAsDouble();
		int y1 = toY(start, plot.y, plot.height);
		int y2 = toY(end, plot.y, plot.height);
		int x = plot.x + plot.width + 20;
		int y = Math.min(y1, y2);
		int h = Math.abs(y1 - y2);
		int w = 10;
		if (h <= 0 || x + w >= plot.x + plot.width + 38)
			return;
		for (int i = 0; i < h; i += 3) {
			g.setColor(palette.getColorNormalized(1 - (double) i / h));
			g.fillRect(x, y + i, w, 3);
		}
		g.setColor(Color.darkGray);
		g.fillRect(x, y, w, 2);
		g.fillRect(x + w - 2, y, 2, h);
		g.fillRect(x, y + h - 2, w, 2);
	}

	private void drawMouseCross(Graphics2D g, DatasetSpectrumPeak spectrum, Rectangle plot) {
		if (!mouseCrossVisible.getAsBoolean())
			return;
		double fraction = getDisplayFraction(mouseDomainSupplier.getAsDouble(), spectrum);
		if (fraction < 0 || fraction > 1)
			return;
		int x = plot.x + (int) Math.round(fraction * plot.width);
		int y = toY(mouseAmplitudeSupplier.getAsDouble(), plot.y, plot.height);
		if (x < plot.x || x > plot.x + plot.width || y < plot.y || y > plot.y + plot.height)
			return;
		g.setColor(Color.white);
		g.setStroke(new BasicStroke(1f));
		g.drawLine(x, plot.y, x, plot.y + plot.height);
		g.drawLine(plot.x, y, plot.x + plot.width, y);
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
		FontMetrics fm = g.getFontMetrics();
		String freqLabel = mouseFrequencyLabelSupplier.get();
		String ampLabel = mouseAmplitudeLabelSupplier.get();
		int freqX = x + 6;
		if (freqX + fm.stringWidth(freqLabel) > plot.x + plot.width)
			freqX = x - fm.stringWidth(freqLabel) - 6;
		g.drawString(freqLabel, freqX, plot.y + fm.getAscent() + 2);
		g.drawString(ampLabel, plot.x + plot.width - fm.stringWidth(ampLabel) - 6, y - 4);
	}

	private void drawSpectrumMarkers(Graphics2D g, DatasetSpectrumPeak spectrum, Rectangle plot) {
		if (showPeaks.getAsBoolean()) {
			drawSpectrumMarkers(g, spectrum, plot, peakMarkersSupplier.get(), peakMarkerColor);
		}
		if (showMaxHold.getAsBoolean() && showMinHold.getAsBoolean()) {
			drawSpectrumMarkers(g, spectrum, plot, maxHoldMarkersSupplier.get(), maxHoldMarkerColor);
		}
	}

	private void drawSpectrumMarkers(Graphics2D g, DatasetSpectrumPeak spectrum, Rectangle plot,
			DatasetSpectrumPeak.SpectrumMarker[] markers, Color color) {
		if (markers == null || markers.length == 0)
			return;
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
		FontMetrics fm = g.getFontMetrics();
		g.setColor(color);
		g.setStroke(new BasicStroke(1f));
		int row = 0;
		for (DatasetSpectrumPeak.SpectrumMarker marker : markers) {
			double fraction = getDisplayFraction(marker.frequencyMHz, spectrum);
			if (fraction < 0 || fraction > 1)
				continue;
			int x = plot.x + (int) Math.round(fraction * plot.width);
			int y = toY(marker.amplitudeDbm, plot.y, plot.height);
			if (y < plot.y || y > plot.y + plot.height)
				continue;
			String label = String.format("%.1f @ %.2f", marker.amplitudeDbm, marker.frequencyMHz);
			int labelX = x + 8;
			if (labelX + fm.stringWidth(label) > plot.x + plot.width)
				labelX = x - fm.stringWidth(label) - 8;
			int labelY = Math.max(plot.y + fm.getAscent() + 2, y - 10 - row * (fm.getHeight() + 2));
			g.drawLine(x, y, labelX < x ? labelX + fm.stringWidth(label) + 4 : labelX - 4, labelY - fm.getAscent() / 2);
			g.fillOval(x - 3, y - 3, 6, 6);
			g.drawString(label, labelX, labelY);
			row = (row + 1) % 4;
		}
	}

	private void drawDatestampAndStatus(Graphics2D g, Rectangle plot) {
		g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
		g.setColor(Color.gray);
		int indicatorY = 12;
		if (showDatestamp.getAsBoolean()) {
			String text = datestampSupplier.get();
			if (text != null) {
				g.drawString(text, 20, indicatorY);
			}
		}
		String status = statusSupplier.get();
		if (status == null || status.isEmpty())
			return;
		FontMetrics fm = g.getFontMetrics();
		boolean replay = replayVisible.getAsBoolean();
		boolean recording = liveRecordingVisible.getAsBoolean();
		int symbolWidth = replay || recording ? 16 : 0;
		int indicatorX = plot.x + plot.width - symbolWidth - fm.stringWidth(status) - 8;
		if (replay) {
			g.setColor(new Color(0x4FAF4F));
			int[] xs = { indicatorX, indicatorX, indicatorX + 10 };
			int[] ys = { indicatorY - 9, indicatorY + 1, indicatorY - 4 };
			g.fillPolygon(xs, ys, 3);
		} else if (recording) {
			g.setColor(new Color(0xB84A4A));
			g.fillOval(indicatorX, indicatorY - 9, 9, 9);
		}
		g.setColor(Color.gray);
		g.drawString(status, indicatorX + symbolWidth, indicatorY);
	}

	private void drawSeries(Graphics2D g, DatasetSpectrumPeak spectrum, int x, int y, int w, int h) {
		float stroke = lineThicknessSupplier.get().floatValue();
		g.setStroke(new BasicStroke(Math.max(1f, stroke), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

		if (showPeaks.getAsBoolean()) {
			drawSeries(g, spectrum, spectrum.createPeaksDataset("peaks"), peakColor, x, y, w, h);
		}
		if (showAverage.getAsBoolean()) {
			drawSeries(g, spectrum, spectrum.createAverageDataset("average"), averageColor, x, y, w, h);
		}
		if (showMaxHold.getAsBoolean()) {
			drawSeries(g, spectrum, spectrum.createMaxHoldDataset("maxhold"), maxHoldColor, x, y, w, h);
		}
		if (showMinHold.getAsBoolean()) {
			drawSeries(g, spectrum, spectrum.createMinHoldDataset("minhold"), minHoldColor, x, y, w, h);
		}
		if (showRealtime.getAsBoolean()) {
			drawSeries(g, spectrum, spectrum.createSpectrumDataset("spectrum"), realtimeColor, x, y, w, h);
		}
	}

	private void drawSeries(Graphics2D g, DatasetSpectrumPeak spectrum, XYSeriesImmutable series, Color color, int x, int y, int w, int h) {
		if (series == null || series.getItemCount() < 2) {
			return;
		}

		Path2D.Float path = new Path2D.Float();
		boolean started = false;
		for (int i = 0; i < series.getItemCount(); i++) {
			double fraction = getDisplayFraction(series.getXX(i), spectrum);
			if (fraction < 0 || fraction > 1) {
				started = false;
				continue;
			}
			float xx = (float) (x + fraction * w);
			float yy = toY(series.getYY(i), y, h);
			if (!started) {
				path.moveTo(xx, yy);
				started = true;
			} else {
				path.lineTo(xx, yy);
			}
		}
		g.setColor(color);
		g.draw(path);
	}

	private static int toY(double dbm, int y, int h) {
		double clipped = Math.max(RANGE_MIN_DBM, Math.min(RANGE_MAX_DBM, dbm));
		double normalized = (clipped - RANGE_MIN_DBM) / (RANGE_MAX_DBM - RANGE_MIN_DBM);
		return y + h - (int) Math.round(normalized * h);
	}

	private double getDisplayFraction(double shiftedFreqMHz, DatasetSpectrumPeak spectrum) {
		int[] pairs = rangePairsSupplier.get();
		if (isCompressed(pairs)) {
			double originalNoShift = shiftedFreqMHz - spectrum.getFreqShift();
			if (!isInRange(originalNoShift, pairs))
				return -1;
			double total = totalRangesLength(pairs);
			if (total <= 0)
				return -1;
			return mapRealToCompressed(originalNoShift, pairs) / total;
		}
		double minX = spectrum.getFreqStartMHz() + spectrum.getFreqShift();
		double maxX = spectrum.getFreqStopMHz() + spectrum.getFreqShift();
		double rangeX = maxX - minX;
		if (rangeX <= 0)
			return -1;
		return (shiftedFreqMHz - minX) / rangeX;
	}

	private boolean isCompressed(int[] pairs) {
		return pairs != null && pairs.length > 2;
	}

	private boolean isInRange(double freqNoShift, int[] pairs) {
		for (int i = 0; i + 1 < pairs.length; i += 2) {
			if (freqNoShift >= pairs[i] && freqNoShift <= pairs[i + 1])
				return true;
		}
		return false;
	}

	private double totalRangesLength(int[] pairs) {
		double sum = 0;
		for (int i = 0; i + 1 < pairs.length; i += 2) {
			sum += pairs[i + 1] - pairs[i];
		}
		return sum;
	}

	private double mapRealToCompressed(double realFreqNoShift, int[] pairs) {
		double cum = 0;
		for (int i = 0; i + 1 < pairs.length; i += 2) {
			int s = pairs[i];
			int e = pairs[i + 1];
			if (realFreqNoShift >= s && realFreqNoShift <= e) {
				return cum + (realFreqNoShift - s);
			}
			cum += e - s;
		}
		if (realFreqNoShift < pairs[0])
			return 0;
		return cum;
	}

	private double mapCompressedToOriginal(double compressed, int[] pairs) {
		double cum = 0;
		for (int i = 0; i + 1 < pairs.length; i += 2) {
			int s = pairs[i];
			int e = pairs[i + 1];
			double len = e - s;
			if (compressed >= cum && compressed <= cum + len) {
				return s + (compressed - cum);
			}
			cum += len;
		}
		if (compressed < 0)
			return pairs[0];
		return pairs[pairs.length - 1];
	}
}
