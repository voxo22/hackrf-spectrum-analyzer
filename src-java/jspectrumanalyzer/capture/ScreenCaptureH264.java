package jspectrumanalyzer.capture;

import java.awt.Component;
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

import jspectrumanalyzer.iq.IQAudioOutput;
import jspectrumanalyzer.iq.Pcm16AudioSink;

/**
 * Class to capture a video of the whole JFrame into the H264 video, while capturing only when the view updates with the new data
 */
public class ScreenCaptureH264 implements Pcm16AudioSink {
	private static final int VIDEO_STREAM_INDEX = 0;
	private static final int AUDIO_STREAM_INDEX = 1;
	private static final int AUDIO_CHANNELS = 2;
	private final Component component;
	private final boolean componentCapture;
	private final int width, height;
	private final boolean audioEnabled;
	private long startCaptureNano = 0;
	private long startCaptureTS	= 0;
	private long lastFrameTS	= 0;
	private int framesCaptured	= 0;
	private int fheight = 0;
	private ExecutorService saveThread	= Executors.newSingleThreadExecutor();
	private long frameIIntervalMS;
	private IMediaWriter writer;
	private volatile boolean closing = false;
	
	public ScreenCaptureH264(JFrame frame, int initSecs, int captureSecs, float fps, int width, int height, String area, String outputFile) throws FileNotFoundException, IOException {
		this(frame, initSecs, captureSecs, fps, width, height, area, outputFile, false);
	}

	public ScreenCaptureH264(JFrame frame, int initSecs, int captureSecs, float fps, int width, int height, String area,
			String outputFile, boolean audioEnabled) throws FileNotFoundException, IOException {
		this(frame, frame, initSecs, captureSecs, fps, width, height, area, outputFile, audioEnabled, true);
	}

	public ScreenCaptureH264(Component component, int initSecs, int captureSecs, float fps, int width, int height,
			String outputFile, boolean audioEnabled) throws FileNotFoundException, IOException {
		this(null, component, initSecs, captureSecs, fps, width, height, "COMPONENT", outputFile, audioEnabled, false);
	}

	private ScreenCaptureH264(JFrame frame, Component component, int initSecs, int captureSecs, float fps, int width,
			int height, String area, String outputFile, boolean audioEnabled, boolean resizeFrame)
			throws FileNotFoundException, IOException {
		this.component	= component;
		this.componentCapture = !resizeFrame;
		this.width	= width;
		this.height	= height;
		this.audioEnabled = audioEnabled;

		this.startCaptureNano	= System.nanoTime();
		frameIIntervalMS = (long) (1000/fps);
		if(resizeFrame && area.equals("SPECTR")) //record video of spectrum
		{
			switch(height) {
			case 360: fheight = 570; break;
			case 540: fheight = 780; break;
			case 720: fheight = 1010; break;
			}
			frame.setMinimumSize(new Dimension(width + 230, fheight));			
		}
		else if(resizeFrame && area.equals("SPEC+WF")) //record video of spectrum & waterfall
		{
			frame.setMinimumSize(new Dimension(width + 230, height - 50));
		}
		else if(resizeFrame) //record full spectrum analyzer screen
		{
			frame.setMinimumSize(new Dimension(width, height));
		}
		if (resizeFrame) {
			frame.setSize(width, height);
		}
		
	    writer = ToolFactory.makeWriter(outputFile);
	    writer.addVideoStream(VIDEO_STREAM_INDEX, 0, ICodec.ID.CODEC_ID_H264, width, height);
	    if (audioEnabled) {
			writer.addAudioStream(AUDIO_STREAM_INDEX, 0, ICodec.ID.CODEC_ID_AAC, AUDIO_CHANNELS,
					IQAudioOutput.TARGET_AUDIO_RATE_HZ);
	    }
	}

	public boolean isAudioEnabled() {
		return audioEnabled;
	}

	public void captureFrame(boolean rec) {
		if (!SwingUtilities.isEventDispatchThread()) {
			throw new IllegalStateException("Capture is NOT inside event dispatch thread!");
		}

		long start	= System.currentTimeMillis();
		
		if (framesCaptured == -1)
			return;

		if (!rec) { /*System.currentTimeMillis() - startedCapture >= captureMillis*/ 
			System.out.println("Capture finished... frames captured: "+framesCaptured);
			framesCaptured	= -1;
			closing = true;
//			task.cancel();
			saveThread.submit(() -> {
			try {
				writer.close();
			} catch (Exception e) {
				e.printStackTrace();
			}		
			saveThread.shutdown();
			});
			//System.exit(0);
			return;
		}

		if (start-lastFrameTS < frameIIntervalMS || start < startCaptureTS) {
			return;
		}
		lastFrameTS	= start;
		
		framesCaptured++;

		BufferedImage capImage = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
		Graphics2D g	= (Graphics2D) capImage.getGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);					
		if (componentCapture) {
			double scaleX = width / Math.max(1d, component.getWidth());
			double scaleY = height / Math.max(1d, component.getHeight());
			g.scale(scaleX, scaleY);
			component.printAll(g);
		} else {
			component.paint(g);
		}
		g.dispose();

		/**
		 * convert to video in a separate thread to not slow down swing's event thread
		 */
		saveThread.submit(() -> {
			try {
				writer.encodeVideo(VIDEO_STREAM_INDEX, capImage, System.nanoTime() - startCaptureNano, TimeUnit.NANOSECONDS);
				//gif.writeToSequence(capImage);
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
		
		//System.out.println("Frame: "+framesCaptured);
	}

	@Override
	public void writePcm16(byte[] data, int offset, int length) throws IOException {
		if (!audioEnabled || closing || framesCaptured == -1 || data == null || length <= 0) {
			return;
		}
		int safeOffset = Math.max(0, offset);
		int safeLength = Math.min(length, data.length - safeOffset);
		safeLength -= safeLength % 4;
		if (safeLength <= 0) {
			return;
		}
		short[] samples = new short[safeLength / 2];
		for (int in = safeOffset, out = 0; out < samples.length; in += 2, out++) {
			samples[out] = (short) ((data[in] & 0xff) | (data[in + 1] << 8));
		}
		long timestampNanos = System.nanoTime() - startCaptureNano;
		saveThread.submit(() -> {
			try {
				if (!closing) {
					writer.encodeAudio(AUDIO_STREAM_INDEX, samples, timestampNanos, TimeUnit.NANOSECONDS);
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		});
	}
}
