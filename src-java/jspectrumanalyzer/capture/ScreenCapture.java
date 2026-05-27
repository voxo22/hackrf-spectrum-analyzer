package jspectrumanalyzer.capture;

import java.awt.Component;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.stream.FileImageOutputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.swing.SwingUtilities;

/**
 * Class to capture a video of the whole JFrame into the animated GIF, while capturing only when the view updates with the new data
 */
public class ScreenCapture {
	private final Component source;
	private final VideoFrameSource frameSource;
	private final int width, height;
	private final ImageOutputStream output;
	private final GifSequenceWriter gif;
	private final long captureMillis;
	private final File outputFile;
	private long startCaptureTS	= 0;
	private long startedCapture	= 0;
	private long lastFrameTS	= 0;
	private int framesCaptured	= 0;
	private ExecutorService saveThread	= Executors.newSingleThreadExecutor();
	private long frameIIntervalMS;
	private boolean rec;
	
	public ScreenCapture(Component source, int initSecs, int captureSecs, int fps, int width, int height, String area, File outputFile) throws FileNotFoundException, IOException {
		this(source, null, initSecs, captureSecs, fps, width, height, area, outputFile);
	}

	public ScreenCapture(VideoFrameSource frameSource, int initSecs, int captureSecs, int fps, int width, int height, String area, File outputFile) throws FileNotFoundException, IOException {
		this(null, frameSource, initSecs, captureSecs, fps, width, height, area, outputFile);
	}

	private ScreenCapture(Component source, VideoFrameSource frameSource, int initSecs, int captureSecs, int fps, int width, int height, String area, File outputFile) throws FileNotFoundException, IOException {
		this.captureMillis	= captureSecs*1000L;
		this.source	= source;
		this.frameSource = frameSource;
		this.width	= width;
		this.height	= height;
		this.outputFile	= outputFile;

		this.startCaptureTS	= System.currentTimeMillis()+initSecs*1000L;
		frameIIntervalMS = 1000/fps;
		
		outputFile.delete();
		
	    output = new FileImageOutputStream(outputFile);
	    gif = new GifSequenceWriter(output, BufferedImage.TYPE_3BYTE_BGR, (int)frameIIntervalMS, true);
	}

	public void captureFrame(boolean rec) {
		this.rec = rec;
		if (!SwingUtilities.isEventDispatchThread()) {
			throw new IllegalStateException("Capture is NOT inside event dispatch thread!");
		}
		
		long start	= System.currentTimeMillis();
		
		if (start-lastFrameTS < frameIIntervalMS || start < startCaptureTS) {
			return;
		}
		lastFrameTS	= start;
		
		if (framesCaptured == -1)
			return;
		if (framesCaptured == 0) {
			startedCapture	= System.currentTimeMillis();
			System.out.println("Capture started...");
		}

		if (!rec) { /*System.currentTimeMillis() - startedCapture >= captureMillis*/ 
			System.out.println("Capture finished... frames captured: "+framesCaptured);
			framesCaptured	= -1;
//			task.cancel();
			saveThread.submit(() -> {
			try {
				gif.close();
			} catch (IOException e) {
				e.printStackTrace();
			}		
			try {
				output.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
			});
			//System.exit(0);
			return;
		}
		
		framesCaptured++;

		BufferedImage capImage = renderFrame();

		/**
		 * convert to gif in a separate thread to not slow down swing's event thread
		 */
		saveThread.submit(() -> {
			try {
				gif.writeToSequence(capImage);
			} catch (IOException e) {
				e.printStackTrace();
			}
		});
		
		//System.out.println("time to save gif "+(System.currentTimeMillis()-start));
		//System.out.println("Frame: "+framesCaptured);
	}

	private BufferedImage renderFrame() {
		if (frameSource != null) {
			return frameSource.renderFrame(width, height);
		}
		return CaptureRenderer.renderFrame(source, width, height);
	}
}
