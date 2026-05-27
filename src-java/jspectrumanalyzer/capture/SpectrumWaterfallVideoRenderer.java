package jspectrumanalyzer.capture;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import jspectrumanalyzer.ui.WaterfallPlot;

public class SpectrumWaterfallVideoRenderer implements VideoFrameSource {
	private final SpectrumVideoRenderer spectrumRenderer;
	private final WaterfallPlot waterfallPlot;

	public SpectrumWaterfallVideoRenderer(SpectrumVideoRenderer spectrumRenderer, WaterfallPlot waterfallPlot) {
		this.spectrumRenderer = spectrumRenderer;
		this.waterfallPlot = waterfallPlot;
	}

	@Override
	public BufferedImage renderFrame(int width, int height) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
		Graphics2D g = image.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			int gap = height >= 720 ? 12 : 8;
			int spectrumHeight = Math.max(1, (int) Math.round(height * 0.78) - gap);
			int waterfallHeight = Math.max(1, height - spectrumHeight - gap);
			Graphics2D spectrumGraphics = (Graphics2D) g.create(0, 0, width, spectrumHeight);
			Rectangle plot;
			try {
				plot = spectrumRenderer.renderInto(spectrumGraphics, width, spectrumHeight);
			} finally {
				spectrumGraphics.dispose();
			}
			BufferedImage waterfallImage = waterfallPlot.renderVideoFrame(width, waterfallHeight, plot.x, plot.width);
			g.drawImage(waterfallImage, 0, spectrumHeight + gap, null);
		} finally {
			g.dispose();
		}
		return image;
	}
}
