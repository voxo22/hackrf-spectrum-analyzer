package jspectrumanalyzer.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;

import javax.swing.JPanel;

import org.jfree.chart.ChartPanel;

import jspectrumanalyzer.core.DatasetSpectrum;
import jspectrumanalyzer.core.EMA;

public class WaterfallPlot extends JPanel {
	/**
	 * 
	 */
	private static final long	serialVersionUID		= 3249110968962287324L;
	private BufferedImage		bufferedImages[]		= new BufferedImage[2];
	private int					chartXOffset			= 0, chartWidth = 100;
	private boolean				displayMarker			= false;
	private double				displayMarkerFrequency	= 0;
	private int					displayMarkerX			= 0;
	private int					drawIndex				= 0;
	/**
	 * stores max value in pixel
	 */
	private float				drawMaxBuffer[];
	private EMA					fps						= new EMA(3);
	private int					fpsRenderedFrames		= 0;
	private long				lastFPSRecalculated		= 0;
	private DatasetSpectrum		lastSpectrum			= null;
	private ColorPalette		palette					= new HotIronBluePalette();
	private Rectangle2D.Float	rect					= new Rectangle2D.Float(0f, 0f, 1f, 1f);
	private String				renderingInfo			= "";
	private int					screenWidth;
	private double				spectrumPaletteSize		= 65;
	private double				spectrumPaletteStart	= -110;
	private boolean				infoBoxVisible			= true;
	private String[]			statusMessage			= new String[4];
	private int[]			rangePairs				= null;
	private double			compressedTotalLength	= 0;
	private int				wfFreqShift			= 0;

	public WaterfallPlot(ChartPanel chartPanel, int maxHeight) {
		setPreferredSize(new Dimension(100, 200));
		setMinimumSize(new Dimension(100, 200));

		addComponentListener(new ComponentAdapter() {
			@Override
			public void componentResized(ComponentEvent e) {
				setHistorySize(getHeight());
			}
		});

		screenWidth = (int) Toolkit.getDefaultToolkit().getScreenSize().getWidth();
		drawMaxBuffer = new float[screenWidth];

		bufferedImages[0] = GraphicsToolkit.createAcceleratedImageOpaque(screenWidth, maxHeight);
		bufferedImages[1] = GraphicsToolkit.createAcceleratedImageOpaque(screenWidth, maxHeight);

		/**
		 * setup frequency marker
		 */
		addMouseMotionListener(new MouseMotionAdapter() {
			@Override
			public void mouseMoved(MouseEvent e) {
				displayMarker = false;
				int x = e.getX();
				if (x < chartXOffset || x > chartXOffset + chartWidth) {
					return;
				}
				double freq = translateChartXToFrequency(x - chartXOffset);
				if (freq != -1) {
					displayMarker = true;
					displayMarkerFrequency = freq;
					displayMarkerX = x;
				}
				WaterfallPlot.this.repaint();
			}
		});
		addMouseListener(new MouseAdapter() {
			@Override
			public void mouseExited(MouseEvent e) {
				displayMarker = false;
			}
		});
	}

	private EMA newDataTimeEMA =	 new EMA(100);
	/**
	 * Adds new data to the waterfall plot and renders it
	 * 
	 * @param spectrum
	 */
	public synchronized void addNewData(DatasetSpectrum spectrum) {
		long start	= System.nanoTime();

		int size = spectrum.spectrumLength();
		double startFreq = spectrum.getFreqStartMHz() * 1000000d;
		double freqRange = (spectrum.getFreqStopMHz() - spectrum.getFreqStartMHz()) * 1000000d;
		double width = bufferedImages[0].getWidth();
		double spectrumPalleteMax = spectrumPaletteStart + spectrumPaletteSize;

		this.lastSpectrum = spectrum;

		/**
		 * shift image by one pixel down
		 */
		BufferedImage previousImage = bufferedImages[drawIndex];
		drawIndex = (drawIndex + 1) % 2;
		Graphics2D g = bufferedImages[drawIndex].createGraphics();
		g.drawImage(previousImage, 0, 1, null);
		g.setColor(Color.black);
		g.fillRect(0, 0, (int) width, 1);

		float binWidth = (float) (spectrum.getFFTBinSizeHz() / freqRange * width);
		rect.x = 0;
		rect.y = 0;
		rect.height = 0;
		rect.width = binWidth;

		float minimumValueDrawBuffer = -150;
		Arrays.fill(drawMaxBuffer, minimumValueDrawBuffer);

		//optimized drawing
		double widthDivSize = (double)width / size;
		double inverseSpectrumPaletteSize	= 1d/spectrumPaletteSize;
		double spectrumPaletteStartDivSpectrumPaletteSize	= (double)spectrumPaletteStart/spectrumPaletteSize;
		for (int i = 0; i < size; i++) {
			double power = spectrum.getPower(i);
			double percentagePower	= 0;
			if (power > spectrumPaletteStart) {
				if ( power < spectrumPalleteMax) { 
//						percentagePower	= (power - spectrumPaletteStart) / spectrumPaletteSize;
					//percentagePower	= power/spectrumPaletteSize - spectrumPaletteStart/spectrumPaletteSize;
					percentagePower	= power*inverseSpectrumPaletteSize - spectrumPaletteStartDivSpectrumPaletteSize;
				}
				else
					percentagePower = 1;
			}
			int pixelX;
			if (rangePairs != null && rangePairs.length > 2 && compressedTotalLength > 0) {
				// compute original frequency in MHz (without shift)
				double originalNoShiftMHz = (spectrum.getFreqStartMHz() + (spectrum.getFFTBinSizeHz() * i) / 1000000d);
				// check if in any selected range
				boolean inRange = false;
				for (int r = 0; r < rangePairs.length; r += 2) {
					if (originalNoShiftMHz >= rangePairs[r] && originalNoShiftMHz <= rangePairs[r+1]) { inRange = true; break; }
				}
				if (!inRange) {
					continue; // skip this bin
				}
				// map to compressed coordinate
				double cum = 0;
				double mapped = 0;
				for (int r = 0; r < rangePairs.length; r += 2) {
					int s = rangePairs[r];
					int e = rangePairs[r+1];
					double len = e - s;
					if (originalNoShiftMHz >= s && originalNoShiftMHz <= e) {
						mapped = cum + (originalNoShiftMHz - s);
						break;
					}
					cum += len;
				}
				double percentage = mapped / compressedTotalLength;
				pixelX = (int) Math.round(percentage * width);
			} else {
				pixelX = (int) Math.round(widthDivSize * i);
			}
			pixelX = pixelX >= drawMaxBuffer.length ? drawMaxBuffer.length - 1 : pixelX < 0 ? 0 : pixelX;
			if (percentagePower > drawMaxBuffer[pixelX])
				drawMaxBuffer[pixelX] = (float) percentagePower;
		}

		/**
		 * fill in pixels that do not have power with last bin's color in order
		 * to smooth the spectrum
		 */
		Color lastValidColor = palette.getColor(0);
		for (int x = 0; x < drawMaxBuffer.length; x++) {
			Color color;
			if (drawMaxBuffer[x] == minimumValueDrawBuffer)
				color = lastValidColor;
			else {
				color = palette.getColorNormalized(drawMaxBuffer[x]);
				lastValidColor = color;
			}
			rect.x = x;
			g.setColor(color);
			g.draw(rect);
		}

		renderingInfo = String.format("RBW %.1f kHz / FFT bins: %d%s / %.1f fps",
				lastSpectrum == null ? 0 : lastSpectrum.getFFTBinSizeHz() / 1000d, size >= 10000 ? size / 1000 : size,
				size >= 10000 ? "k" : "", fps.getEma()).replace(',', '.');
		fpsRenderedFrames++;
		if (System.currentTimeMillis() - lastFPSRecalculated > 1000) {
			double rawfps = fpsRenderedFrames / ((System.currentTimeMillis() - (double) lastFPSRecalculated) / 1000d);
			fps.addNewValue(rawfps);
			lastFPSRecalculated = System.currentTimeMillis();
			fpsRenderedFrames = 0;
		}
		g.dispose();

//		double time	= newDataTimeEMA.addNewValue(((System.nanoTime()-start)/1000));
//		System.out.println("draw "+(int)time+"us");

//		repaint();
	}

	/**
	 * Draws color palette into given area from bottom (0%) to top (100%)
	 * 
	 * @param g
	 * @param x
	 * @param y
	 * @param w
	 * @param h
	 */
	public void drawScale(Graphics2D g, int x, int y, int w, int h) {
		g = (Graphics2D) g.create(x, y, w, h);
		int step = 3;
		for (int i = 0; i < h; i += step) {
			Color c = palette.getColorNormalized(1 - (double) i / h);
			g.setColor(c);
			g.fillRect(0, i, w, step);
		}

		/**
		 * draw border around the scale
		 */
		int thickness = 2;
		g.setColor(Color.darkGray);
		g.fillRect(0, 0, w, thickness);
		g.fillRect(w - thickness, 0, thickness, h);
		g.fillRect(0, h - thickness, w, thickness);
		g.dispose();
	}

	public int getHistorySize() {
		return bufferedImages[0].getHeight();
	}

	public double getSpectrumPaletteSize() {
		return spectrumPaletteSize;
	}

	public double getSpectrumPaletteStart() {
		return spectrumPaletteStart;
	}
	
	public boolean isInfoBoxVisible() {
		return infoBoxVisible;
	}

	public void setDrawingOffsets(int xOffsetLeft, int width) {
		this.chartXOffset = xOffsetLeft;
		this.chartWidth = width;
	}

	/**
	 * Set active ranges (pairs) in MHz and freqShift to be used for compressed X mapping
	 */
	public void setRangePairs(int[] pairs, int freqShift) {
		this.rangePairs = pairs;
		this.wfFreqShift = freqShift;
		if (pairs == null) {
			this.compressedTotalLength = 0;
		} else {
			double sum = 0;
			for (int i = 0; i < pairs.length; i += 2) {
				sum += (pairs[i+1] - pairs[i]);
			}
			this.compressedTotalLength = sum;
		}
	}
	

	public void setInfoBoxVisible(boolean visible) {
	    this.infoBoxVisible = visible;
	    repaint();
	}


	public synchronized void setHistorySize(int historyInPixels) {
		BufferedImage bufferedImages[] = new BufferedImage[2];
		bufferedImages[0] = GraphicsToolkit.createAcceleratedImageOpaque(screenWidth, historyInPixels);
		bufferedImages[1] = GraphicsToolkit.createAcceleratedImageOpaque(screenWidth, historyInPixels);
		copyImage(this.bufferedImages[0], bufferedImages[0]);
		copyImage(this.bufferedImages[1], bufferedImages[1]);
		this.bufferedImages = bufferedImages;
	}

	public void setSpectrumPaletteSize(int dB) {
		this.spectrumPaletteSize = dB;
	}

	/**
	 * Sets start and end of the color scale
	 * 
	 * @param minFreqency
	 * @param maxFrequency
	 */
	public void setSpectrumPaletteStart(int dB) {
		this.spectrumPaletteStart = dB;
	}

	/**
	 * Sets status message to be drawn near bottom right corner
	 * 
	 * @param message
	 * @param index
	 *            max array length is 4
	 */
	public void setStatusMessage(String message, int index) {
		this.statusMessage[index] = message;
	}

	private void copyImage(BufferedImage src, BufferedImage dst) {
		Graphics2D g = dst.createGraphics();
		g.drawImage(src, 0, 0, null);
		g.dispose();
	}

	private double translateChartXToFrequency(int x) {
		if (lastSpectrum != null) {
			// If rangePairs active, map compressed X back to original frequency
			if (rangePairs != null && rangePairs.length > 2 && compressedTotalLength > 0) {
				double percentage = x / (double) chartWidth;
				if (percentage < 0) percentage = 0;
				if (percentage > 1) percentage = 1;
				double compressed = percentage * compressedTotalLength;
				// map compressed back to original MHz (without shift)
				double cum = 0;
				double origNoShift = rangePairs[0];
				for (int i = 0; i < rangePairs.length; i += 2) {
					int s = rangePairs[i];
					int e = rangePairs[i+1];
					double len = e - s;
					if (compressed >= cum && compressed <= cum + len) {
						origNoShift = s + (compressed - cum);
						break;
					}
					cum += len;
				}
				double freqHz = (origNoShift + wfFreqShift) * 1000000d;
				return freqHz;
			} else {
				double startFreq = (lastSpectrum.getFreqStartMHz() + lastSpectrum.getFreqShift()) * 1000000d;
				double stopFreq = (lastSpectrum.getFreqStopMHz() + lastSpectrum.getFreqShift()) * 1000000d;
				double freqRange = (stopFreq - startFreq);
				double percentageFreq = x / (double) chartWidth;
				double freq = percentageFreq * freqRange + startFreq;
				if (freq > stopFreq)
					freq = stopFreq;
				if (freq < startFreq)
					freq = startFreq;
				return freq;
			}
		}
		return -1;
	}

	Rectangle2D stringBounds;
	@Override
	protected void paintComponent(Graphics arg0) {
		long drawStart	= System.nanoTime();
		Graphics2D g = (Graphics2D) arg0;
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		int w = chartWidth;
		int h = getHeight();
		g.setColor(Color.black);
		g.fillRect(0, 0, getWidth(), getHeight());

		g.drawImage(bufferedImages[drawIndex], chartXOffset, 0, w, h, null);
		// draw vertical separators for compressed ranges if set
		if (rangePairs != null && rangePairs.length > 2 && compressedTotalLength > 0) {
			double cum = 0;
			g.setColor(new Color(255,255,255,120));
			float[] dash = {4f,4f};
			java.awt.Stroke old = g.getStroke();
			g.setStroke(new java.awt.BasicStroke(1f, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER, 1.0f, dash, 0f));
			for (int i = 0; i < rangePairs.length; i += 2) {
				double len = rangePairs[i+1] - rangePairs[i];
				cum += len;
				if (i < rangePairs.length - 2) {
					int sepX = chartXOffset + (int) Math.round((cum / compressedTotalLength) * w);
					g.drawLine(sepX, 0, sepX, h);
				}
			}
			g.setStroke(old);
		}

		if (displayMarker) {
			g.setColor(Color.gray);
			g.drawLine(displayMarkerX, 0, displayMarkerX, h);
			g.drawString(String.format("%.1f MHz", displayMarkerFrequency / 1000000.0), displayMarkerX + 5, h / 2);
		} //finish marker 
/*
		g.setColor(Color.white);
		if (stringBounds == null)
			stringBounds = g.getFontMetrics().getStringBounds("TEST", g);
		int fontHeight = (int) stringBounds.getHeight();
		int x = chartXOffset + w - 280;
		int y = h - fontHeight * (statusMessage.length + 1);
		g.drawString(renderingInfo, x, y);

		for (int i = 0; i < statusMessage.length; i++) {
			if (statusMessage[i] != null)
				g.drawString(statusMessage[i], x, y + fontHeight * (i + 1));
		}
*/

	g.setColor(Color.white);
	if (stringBounds == null)
	    stringBounds = g.getFontMetrics().getStringBounds("TEST", g);
	
	int fontHeight = (int) stringBounds.getHeight();
	
	java.util.List<String> lines = new java.util.ArrayList<>();
	if (infoBoxVisible)
	{
		lines.add(renderingInfo);
		for (int i = 0; i < statusMessage.length; i++) {
		    if (statusMessage[i] != null && !statusMessage[i].isEmpty()) {
		        lines.add(statusMessage[i]);
		    }
		}
	}
	if (!lines.isEmpty()) {
	    int maxTextWidth = 0;
	    for (String s : lines) {
	        int wStr = g.getFontMetrics().stringWidth(s);
	        if (wStr > maxTextWidth) maxTextWidth = wStr;
	    }
	
	    int interlineGap = 2;
	    int paddingX = 8;
	    int paddingY = 6;
	    int marginBottom = 20;
	
	    int textBlockHeight = lines.size() * fontHeight + (lines.size() - 1) * interlineGap;
	    int boxWidth = maxTextWidth + 2 * paddingX;
	    int boxHeight = textBlockHeight + 2 * paddingY;
	
	    int anchorLeft = chartXOffset + w - 250;
	    int xBox = anchorLeft;
	    int yBox = getHeight() - boxHeight - marginBottom;
	
	    g.setColor(new Color(0, 0, 0, 140));
	    g.fillRect(xBox, yBox, boxWidth, boxHeight);
	
	    g.setColor(Color.WHITE);
	    int textX = xBox + paddingX;
	    int baseline = yBox + paddingY + g.getFontMetrics().getAscent();
	    for (int i = 0; i < lines.size(); i++) {
	        String s = lines.get(i);
	        g.drawString(s, textX, baseline + i * (fontHeight + interlineGap));
	    }
	}
		
		long drawingTime	= System.nanoTime()-drawStart;
		drawingTimeSum	+= drawingTime;
		drawingCounter++;
	}
	private volatile long drawingTimeSum	= 0;
	private volatile int drawingCounter	= 0;
	public int getDrawingCounterAndReset() {
		int val	= drawingCounter;
		drawingCounter	= 0;
		return val;
	}
	/**
	 * Retrieves time in nanos the component spent in drawing itself and resets
	 * the counter to zero.
	 * @return
	 */
	public long getDrawTimeSumAndReset() {
		long val	= drawingTimeSum;
		drawingTimeSum	= 0;
		return val;
	}
}
