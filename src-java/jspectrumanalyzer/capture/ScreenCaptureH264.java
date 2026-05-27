package jspectrumanalyzer.capture;

import java.awt.Component;
import java.awt.image.BufferedImage;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.SwingUtilities;
import java.util.concurrent.TimeUnit;

import com.xuggle.mediatool.IMediaWriter;
import com.xuggle.mediatool.ToolFactory;
import com.xuggle.xuggler.ICodec;
import com.xuggle.xuggler.IPixelFormat;
import com.xuggle.xuggler.IStreamCoder;

/**
 * Class to capture a video of the whole JFrame into the H264 video, while capturing only when the view updates with the new data
 */
public class ScreenCaptureH264 {
	private final Component source;
	private final VideoFrameSource frameSource;
	private final int width, height;
	private long startCaptureNano = 0;
	private long startCaptureTS	= 0;
	private long lastFrameTS	= 0;
	private int framesCaptured	= 0;
	private ExecutorService saveThread	= Executors.newSingleThreadExecutor();
	private long frameIIntervalMS;
	private IMediaWriter writer;
	
	public ScreenCaptureH264(Component source, int initSecs, int captureSecs, float fps, int width, int height, String area, String outputFile) throws FileNotFoundException, IOException {
		this(source, null, initSecs, captureSecs, fps, width, height, area, outputFile);
	}

	public ScreenCaptureH264(VideoFrameSource frameSource, int initSecs, int captureSecs, float fps, int width, int height, String area, String outputFile) throws FileNotFoundException, IOException {
		this(null, frameSource, initSecs, captureSecs, fps, width, height, area, outputFile);
	}

	private ScreenCaptureH264(Component source, VideoFrameSource frameSource, int initSecs, int captureSecs, float fps, int width, int height, String area, String outputFile) throws FileNotFoundException, IOException {
		this.source	= source;
		this.frameSource = frameSource;
		this.width	= width;
		this.height	= height;

		this.startCaptureNano	= System.nanoTime();
		frameIIntervalMS = (long) (1000/fps);
		
	    writer = ToolFactory.makeWriter(outputFile);
	    writer.addVideoStream(0, 0, ICodec.ID.CODEC_ID_H264, width, height);
	    IStreamCoder coder = writer.getContainer().getStream(0).getStreamCoder();
	    coder.setPixelType(IPixelFormat.Type.YUV444P);
	}

	public void captureFrame(boolean rec) {
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

		if (!rec) { /*System.currentTimeMillis() - startedCapture >= captureMillis*/ 
			System.out.println("Capture finished... frames captured: "+framesCaptured);
			framesCaptured	= -1;
//			task.cancel();
			saveThread.submit(() -> {
			try {
				writer.close();
			} catch (Exception e) {
				e.printStackTrace();
			}		
			});
			//System.exit(0);
			return;
		}
		
		framesCaptured++;

		BufferedImage capImage = renderFrame();

		/**
		 * convert to video in a separate thread to not slow down swing's event thread
		 */
		saveThread.submit(() -> {
			try {
				writer.encodeVideo(0, capImage, System.nanoTime() - startCaptureNano, TimeUnit.NANOSECONDS);
				//gif.writeToSequence(capImage);
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
		
		//System.out.println("Frame: "+framesCaptured);
	}

	private BufferedImage renderFrame() {
		if (frameSource != null) {
			return frameSource.renderFrame(width, height);
		}
		return CaptureRenderer.renderFrame(source, width, height);
	}
}
