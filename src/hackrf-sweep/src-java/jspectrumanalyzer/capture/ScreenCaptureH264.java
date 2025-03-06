package jspectrumanalyzer.capture;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.util.concurrent.TimeUnit;

import com.xuggle.mediatool.IMediaWriter;
import com.xuggle.mediatool.ToolFactory;
import com.xuggle.xuggler.ICodec;

/**
 * Class to capture a video of the whole JFrame into the H264 video, while capturing only when the view updates with the new data
 */
public class ScreenCaptureH264 {
	private final JFrame frame;
	private final int width, height;
	private long startCaptureNano = 0;
	private long startCaptureTS	= 0;
	private long lastFrameTS	= 0;
	private int framesCaptured	= 0;
	private int fheight = 0;
	private ExecutorService saveThread	= Executors.newSingleThreadExecutor();
	private long frameIIntervalMS;
	private IMediaWriter writer;
	
	public ScreenCaptureH264(JFrame frame, int initSecs, int captureSecs, float fps, int width, int height, String area, String outputFile) throws FileNotFoundException, IOException {
		this.frame	= frame;
		this.width	= width;
		this.height	= height;

		this.startCaptureNano	= System.nanoTime();
		frameIIntervalMS = (long) (1000/fps);
		if(area.equals("SPEC")) //record video of spectrum
		{
			switch(height) {
			case 360: fheight = 570; break;
			case 540: fheight = 780; break;
			case 720: fheight = 1010; break;
			}
			frame.setMinimumSize(new Dimension(width + 230, fheight));			
		}
		else if(area.equals("SP+W")) //record video of spectrum & waterfall
		{
			frame.setMinimumSize(new Dimension(width + 230, height));
		}
		else //record full spectrum analyzer screen
		{
			frame.setMinimumSize(new Dimension(width, height));
		}
		frame.setSize(width, height);
		
	    writer = ToolFactory.makeWriter(outputFile);
	    writer.addVideoStream(0, 0, ICodec.ID.CODEC_ID_H264, width, height);
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

		BufferedImage capImage = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
		Graphics2D g	= (Graphics2D) capImage.getGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);					
		frame.paint(g);
		g.dispose();

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
}
