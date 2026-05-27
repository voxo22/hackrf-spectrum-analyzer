package jspectrumanalyzer.capture;

import java.awt.image.BufferedImage;

public interface VideoFrameSource {
	BufferedImage renderFrame(int width, int height);
}
