package jspectrumanalyzer.capture;

import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

final class CaptureRenderer {
	private CaptureRenderer() {
	}

	static BufferedImage renderFrame(Component source, int width, int height) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
		Graphics2D g = image.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

			int sourceWidth = source.getWidth();
			int sourceHeight = source.getHeight();
			if (sourceWidth <= 0 || sourceHeight <= 0) {
				return image;
			}

			double scale = Math.min(width / (double) sourceWidth, height / (double) sourceHeight);
			int scaledWidth = (int) Math.round(sourceWidth * scale);
			int scaledHeight = (int) Math.round(sourceHeight * scale);
			int x = (width - scaledWidth) / 2;
			int y = (height - scaledHeight) / 2;

			g.translate(x, y);
			g.scale(scale, scale);
			source.paint(g);
		} finally {
			g.dispose();
		}
		return image;
	}

}
