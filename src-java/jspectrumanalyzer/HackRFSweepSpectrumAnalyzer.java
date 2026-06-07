package jspectrumanalyzer;

import java.awt.BasicStroke;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
//import java.awt.Shape;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.time.Instant;
import java.util.Properties;
import java.time.ZoneId;
import java.net.URISyntaxException;
import java.math.BigDecimal;
import java.util.Locale;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;
import javax.swing.ImageIcon;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.KeyStroke;
import javax.swing.UIManager;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
//import org.jfree.chart.annotations.XYAnnotation;
//import org.jfree.chart.annotations.XYDrawableAnnotation;
import org.jfree.chart.annotations.XYPointerAnnotation;
import org.jfree.chart.axis.AxisSpace;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.axis.NumberTickUnit;
import org.jfree.chart.axis.StandardTickUnitSource;
import org.jfree.chart.event.ChartProgressEvent;
import org.jfree.chart.event.ChartProgressListener;
import org.jfree.chart.event.OverlayChangeListener;
import org.jfree.chart.panel.Overlay;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.ValueMarker;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.renderer.xy.XYSplineRenderer;
import org.jfree.chart.title.TextTitle;
import org.jfree.data.Range;
import org.jfree.data.xy.XYSeries;
//import org.jfree.ui.Drawable;
import org.jfree.ui.HorizontalAlignment;
import org.jfree.ui.RectangleAnchor;
import org.jfree.ui.RectangleEdge;
import org.jfree.ui.RectangleInsets;
import org.jfree.ui.TextAnchor;
//import org.jfree.ui.Layer;

import jspectrumanalyzer.capture.ScreenCapture;
import jspectrumanalyzer.capture.ScreenCaptureH264;
import jspectrumanalyzer.core.DatasetSpectrumPeak;
import jspectrumanalyzer.core.FFTBins;
import jspectrumanalyzer.core.FrequencyAllocationTable;
import jspectrumanalyzer.core.FrequencyAllocations;
import jspectrumanalyzer.core.FrequencyRange;
import jspectrumanalyzer.core.HackRFSettings;
import jspectrumanalyzer.core.PersistentDisplay;
import jspectrumanalyzer.core.Ranges;
import jspectrumanalyzer.core.SpectrumRecording;
//import jspectrumanalyzer.core.PowerCalibration;
import jspectrumanalyzer.core.SpurFilter;
import jspectrumanalyzer.core.jfc.XYSeriesCollectionImmutable;
import jspectrumanalyzer.core.jfc.XYSeriesImmutable;
import jspectrumanalyzer.iq.IQAnalyzerApp;
import jspectrumanalyzer.iq.IQChannelProcessor;
import jspectrumanalyzer.iq.IQFftChannelProcessor;
import jspectrumanalyzer.iq.IQReplayFile;
import jspectrumanalyzer.iq.IQSampleProcessor;
import jspectrumanalyzer.iq.IQSpectrumFrame;
import jspectrumanalyzer.nativebridge.HackRFSweepDataCallback;
import jspectrumanalyzer.nativebridge.HackRFSweepNativeBridge;
import jspectrumanalyzer.ui.HackRFSweepSettingsUI;
import jspectrumanalyzer.ui.WaterfallPlot;
import jspectrumanalyzer.ui.GraphicsToolkit;
//import jspectrumanalyzer.ui.CircleDrawer;
import shared.mvc.ModelValue;
import shared.mvc.ModelValue.ModelValueBoolean;
import shared.mvc.ModelValue.ModelValueInt;

public class HackRFSweepSpectrumAnalyzer implements HackRFSettings, HackRFSweepDataCallback {
	private static final String MAIN_WINDOW_TITLE = "HackRF Spectrum Analyzer";
	private static final int IQ_REPLAY_FRAMES_PER_SECOND = 100;
	private static final double IQ_REPLAY_MAX_VIEW_SPAN_MULTIPLIER = 10d;
	private static final int IQ_REPLAY_MIN_TARGET_RBW_HZ = 50;

	private enum ReplayType {
		DATA, WAV, RAW
	}

	private static class IQReplayAnalyzerFeed {
		private static class InputBlock {
			byte[] data;
			int length;

			InputBlock(int capacity) {
				data = new byte[capacity];
			}
		}

		final IQAnalyzerApp analyzer;
		final IQSampleProcessor processor;
		final int outputSampleRateHz;
		final ArrayBlockingQueue<InputBlock> pending = new ArrayBlockingQueue<>(8);
		final ArrayBlockingQueue<InputBlock> available = new ArrayBlockingQueue<>(8);
		byte[] output = new byte[262144];
		volatile boolean running;
		Thread worker;

		IQReplayAnalyzerFeed(IQAnalyzerApp analyzer, int sourceSampleRateHz, long channelOffsetHz,
				int channelBandwidthHz, int outputRateHz) {
			this.analyzer = analyzer;
			boolean fullBandwidth = Math.abs(channelOffsetHz) <= sourceSampleRateHz / 200L
					&& channelBandwidthHz >= sourceSampleRateHz * 0.9d
					&& outputRateHz >= sourceSampleRateHz;
			IQSampleProcessor selectedProcessor = null;
			if (!fullBandwidth) {
				try {
					selectedProcessor = new IQFftChannelProcessor(sourceSampleRateHz, channelOffsetHz,
							channelBandwidthHz, outputRateHz);
				} catch (Throwable error) {
					System.err.println("Replay FFT channelizer unavailable, using FIR: " + error.getMessage());
					selectedProcessor = new IQChannelProcessor(sourceSampleRateHz, channelOffsetHz,
							channelBandwidthHz, outputRateHz);
				}
			}
			this.processor = selectedProcessor;
			this.outputSampleRateHz = processor == null ? sourceSampleRateHz : processor.getActualOutputRateHz();
			if (processor != null) {
				running = true;
				worker = new Thread(this::processLoop, "iq-replay-channelizer");
				worker.setDaemon(true);
				worker.start();
			}
		}

		void accept(byte[] iqData, int length) {
			if (processor == null) {
				analyzer.acceptExternalIQ(iqData, length);
				return;
			}
			InputBlock block = available.poll();
			if (block == null) {
				block = new InputBlock(length);
			} else if (block.data.length < length) {
				block.data = new byte[length];
			}
			System.arraycopy(iqData, 0, block.data, 0, length);
			block.length = length;
			try {
				pending.put(block);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				available.offer(block);
			}
		}

		private void processLoop() {
			while (running) {
				InputBlock block;
				try {
					block = pending.take();
				} catch (InterruptedException e) {
					if (!running) {
						return;
					}
					continue;
				}
				if (output.length < block.length) {
					output = new byte[block.length];
				}
				int processed = processor.process(block.data, block.length, output);
				available.offer(block);
				if (processed > 0 && running) {
					analyzer.acceptExternalIQOwned(output, processed);
				}
			}
		}

		void close() {
			running = false;
			Thread activeWorker = worker;
			worker = null;
			if (activeWorker != null) {
				activeWorker.interrupt();
				try {
					activeWorker.join(500);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			pending.clear();
			available.clear();
			if (processor instanceof AutoCloseable) {
				try {
					((AutoCloseable) processor).close();
				} catch (Exception ignored) {
				}
			}
		}
	}

	private static class PerformanceEntry{
		final String name;
		long nanosSum;
		int count;
		public PerformanceEntry(String name) {
			this.name 	= name;
		}
		public void addDrawingTime(long nanos) {
			nanosSum	+= nanos;
			count++;
		}
		public void reset() {
			count	= 0;
			nanosSum	= 0;
		}
		@Override
		public String toString() {
			return name;
		}
	}
	
	private static class RuntimePerformanceWatch {
		/**
		 * incoming full spectrum updates from the hardware
		 */
		int				hwFullSpectrumRefreshes	= 0;
		volatile long	lastStatisticsRefreshed	= System.currentTimeMillis();
		PerformanceEntry persisentDisplay	= new PerformanceEntry("Pers.disp");
		PerformanceEntry waterfallUpdate	= new PerformanceEntry("Wtrfall.upd");
		PerformanceEntry waterfallDraw	= new PerformanceEntry("Wtrfll.drw");
		PerformanceEntry chartDrawing	= new PerformanceEntry("Spectr.chart");
		PerformanceEntry spurFilter = new PerformanceEntry("Spur.fil");
		
		private ArrayList<PerformanceEntry> entries	= new ArrayList<>();
		public RuntimePerformanceWatch() {
			entries.add(persisentDisplay);
			entries.add(waterfallUpdate);
			entries.add(waterfallDraw);
			entries.add(chartDrawing);
			entries.add(spurFilter);
		}
		
		public synchronized String generateStatistics() {
			long timeElapsed = System.currentTimeMillis() - lastStatisticsRefreshed;
			if (timeElapsed <= 0)
				timeElapsed = 1;
			StringBuilder b	= new StringBuilder();
			long sumNanos	= 0;
			for (PerformanceEntry entry : entries) {
				sumNanos	+= entry.nanosSum;
				float callsPerSec	= entry.count/(timeElapsed/1000f);
				b.append(entry.name).append(String.format(" %3dms (%5.1f calls/s) \n", entry.nanosSum/1000000, callsPerSec));
			}
			b.append(String.format("Total: %4dms draw time/s: ", sumNanos/1000000));
			return b.toString();
//			double timeSpentDrawingChartPerSec = chartDrawingSum / (timeElapsed / 1000d) / 1000d;
//			return String.format("Spectrum refreshes: %d / Chart redraws: %d / Drawing time in 1 sec %.2fs",
//					hwFullSpectrumRefreshes, chartRedrawed, timeSpentDrawingChartPerSec);

		}

		public synchronized void reset() {
			hwFullSpectrumRefreshes = 0;
			for (PerformanceEntry dataDrawingEntry : entries) {
				dataDrawingEntry.reset();
			}
			lastStatisticsRefreshed = System.currentTimeMillis();
		}
	}

	private static class MarkerLabelPlacement {
		final double xPercent;
		final int row;

		MarkerLabelPlacement(double xPercent, int row) {
			this.xPercent = xPercent;
			this.row = row;
		}
	}

	private static class TriggerSettings {
		static final String SOURCE_REALTIME = "REALTIME";
		static final String SOURCE_PEAKS = "PEAKS";
		static final String OPERATOR_GT = ">";
		static final String OPERATOR_LT = "<";
		static final String OPERATOR_GTE = ">=";
		static final String OPERATOR_LTE = "<=";
		static final String MATCH_ALL = "ALL";
		static final String MATCH_ANY = "ANY";

		boolean enabled = false;
		double startMHz = 0;
		double stopMHz = 8000;
		long cooldownMillis = 5000;
		boolean sound = true;
		boolean log = true;
		String source = SOURCE_REALTIME;
		String matchMode = MATCH_ALL;
		boolean maxLevelEnabled = true;
		String maxLevelOperator = OPERATOR_GT;
		double maxLevelThresholdDbm = -60;
		boolean totalPowerEnabled = false;
		String totalPowerOperator = OPERATOR_GT;
		double totalPowerThresholdDbm = -80;
	}

	private static class TriggerHit {
		final boolean matched;
		final double frequencyMHz;
		final double amplitudeDbm;
		final double maxLevelDbm;
		final double totalPowerDbm;

		TriggerHit(boolean matched, double frequencyMHz, double amplitudeDbm, double maxLevelDbm, double totalPowerDbm) {
			this.matched = matched;
			this.frequencyMHz = frequencyMHz;
			this.amplitudeDbm = amplitudeDbm;
			this.maxLevelDbm = maxLevelDbm;
			this.totalPowerDbm = totalPowerDbm;
		}
	}

	private static class TriggerEvent {
		final long epochMillis;
		final long playbackMillis;
		final boolean playback;
		final double frequencyMHz;
		final double amplitudeDbm;
		final double maxLevelDbm;
		final double totalPowerDbm;

		TriggerEvent(long epochMillis, long playbackMillis, boolean playback, double frequencyMHz, double amplitudeDbm) {
			this(epochMillis, playbackMillis, playback, frequencyMHz, amplitudeDbm, amplitudeDbm, Double.NaN);
		}

		TriggerEvent(long epochMillis, long playbackMillis, boolean playback, double frequencyMHz, double amplitudeDbm,
				double maxLevelDbm, double totalPowerDbm) {
			this.epochMillis = epochMillis;
			this.playbackMillis = playbackMillis;
			this.playback = playback;
			this.frequencyMHz = frequencyMHz;
			this.amplitudeDbm = amplitudeDbm;
			this.maxLevelDbm = maxLevelDbm;
			this.totalPowerDbm = totalPowerDbm;
		}
	}

	private static class IQSelection {
		final double anchorMHz;
		double startMHz;
		double stopMHz;
		double displayStartMHz;
		double displayStopMHz;
		double compressedStartMHz;
		double compressedStopMHz;
		final double[] subRange;

		IQSelection(double anchorMHz, double[] subRange) {
			this.anchorMHz = anchorMHz;
			this.subRange = subRange;
			this.startMHz = anchorMHz;
			this.stopMHz = anchorMHz;
			this.displayStartMHz = anchorMHz;
			this.displayStopMHz = anchorMHz;
			this.compressedStartMHz = anchorMHz;
			this.compressedStopMHz = anchorMHz;
		}

		double getBandwidthMHz() {
			return Math.abs(stopMHz - startMHz);
		}
	}

	/**
	 * Color palette for UI
	 */
	protected static class ColorScheme {
		Color	palette0	= Color.white;
		Color	cwhite	= new Color(0xaaaaaa); // 0xe5e5e5 darkwhite
		Color	cgreen	= new Color(0x11FF11); // 0xFCA311 green
		Color	palette3	= new Color(0x14213D); // 0x14213D dark blue
		Color	palette4	= Color.BLACK;
		Color	corange	= new Color(0xFCA311); // orange
		Color	clime	= new Color(0xA2DE9B); // lime
		Color	cyellow	= new Color(0xFCFC00); // yellow
		Color	cred	= new Color(0xFF0000); // red
		Color	cpink	= new Color(0xE0A2B1); // pink
		Color	blue	= new Color(0x428AF5); // blue
	}

	public static final int	SPECTRUM_PALETTE_SIZE_MIN	= 5;
	private static final double IQ_SELECTION_MAX_BW_MHZ = 20.0d;
	private static final double IQ_SELECTION_MIN_BW_MHZ = 0.001d;
/*
	private static int	pFreqMin						= 920;
	private static int	pFreqMax						= 960;
	private static int	pFFT							= 20000;
	private static int	pSamples						= 8192;
	private static int	pGain							= 48;
	private static boolean	pSpur						= true;
	private static boolean	pAmp						= true;
	private static BigDecimal pThick					= new BigDecimal("1");
	private static boolean	pPeaks						= true;
	private static int	pPeaksTime						= 60;
	private static boolean	pPersist					= false;
	private static int	pPersistTime					= 30;
	private static boolean	pWfall						= true;
	private static int	pPalSize						= 0;
	private static int	pPalStart						= 0;
	private static boolean	pAlloc						= false;
	private static int	pDelay							= 10;
	private static int	pTime							= 15;
	private static int	pFps							= 15;
	private static int	pX								= 680; //650 w/o waterfall
	private static int	pY								= 580; //395
	private static long	initTime						= System.currentTimeMillis();
	private static int	pOffset							= 30;
	private static int	pWSpeed							= 4;	
*/
	private static int	cnt								= 0;
	private static int	pSizeX							= 900;
	private static int	pSizeY							= 600;
	
	public static void main(String[] args) throws IOException {
		//		System.out.println(new File("").getAbsolutePath());

		//		try { Thread.sleep(20000); System.out.println("Started..."); } catch (InterruptedException e) {}

		new HackRFSweepSpectrumAnalyzer();
	}

	public boolean									flagIsHWSendingData					= false;
	private float									alphaFreqAllocationTableBandsImage	= 0.5f;
	private float									alphaPersistentDisplayImage			= 1.0f;
	// set to true when settings were successfully loaded from disk
	private boolean									settingsLoaded						= false;
	private JFreeChart								chart;
	private ModelValue<Rectangle2D>					chartDataArea						= new ModelValue<Rectangle2D>("Chart data area", new Rectangle2D.Double(0, 0, 1, 1));
	private XYSeriesCollectionImmutable				chartDataset						= new XYSeriesCollectionImmutable();
	private XYLineAndShapeRenderer					chartLineRenderer;
	private ChartPanel								chartPanel;
	private ColorScheme								colors								= new ColorScheme();
	private DatasetSpectrumPeak						datasetSpectrum;
	private int										dropped								= 0;
	private volatile boolean						flagManualGain						= false;
	private volatile boolean						forceStopSweep						= false;
	private ScreenCapture							gifCap								= null;
	private ScreenCaptureH264						h264Cap								= null;
	private ModelValueBoolean						parameterIsRecordedVideo			= new ModelValueBoolean("Recording", false);
	private ModelValueBoolean						parameterIsRecordedData				= new ModelValueBoolean("Recording", false);
	private ModelValueBoolean						parameterIsRecordedSpectrum			= new ModelValueBoolean("DATA Recording", false);
	private ModelValueBoolean						parameterIsPlayingSpectrum			= new ModelValueBoolean("Spectrum Playback", false);
	private ModelValue<String>						parameterReplayType					= new ModelValue<>("Replay Type", "");
	private ArrayList<HackRFEventListener>			hRFlisteners						= new ArrayList<>();
	private ArrayBlockingQueue<FFTBins>				hwProcessingQueue					= new ArrayBlockingQueue<>(1000);
	private BufferedImage							imageFrequencyAllocationTableBands	= null;
	private boolean									isChartDrawing						= false;
	private ReentrantLock							lock								= new ReentrantLock();
	private ModelValueBoolean						parameterAntennaLNA   				= new ModelValueBoolean("RF amp", false);
	private ModelValueBoolean						parameterAntPower					= new ModelValueBoolean("Antenna Power", false);
	private ModelValueInt							parameterFFTBinHz					= new ModelValueInt("RBW", 50);
	private ModelValueInt							parameterIqReplayRbwHz				= new ModelValueInt("IQ Replay RBW", 20_000);
	private ModelValueBoolean						parameterFilterSpectrum				= new ModelValueBoolean("Filter", false);
	private ModelValue<FrequencyRange>				parameterFrequency					= new ModelValue<>("Frequency Range", new FrequencyRange(920, 960));
	private ModelValue<FrequencyAllocationTable>	parameterFrequencyAllocationTable	= new ModelValue<FrequencyAllocationTable>("Frequency Allocation Table", new FrequencyAllocations().getTable().get("Slovakia.csv"));
	private ModelValueInt							parameterGainLNA					= new ModelValueInt("LNA Gain",0, 8, 0, 40);
	private ModelValueInt							parameterGainTotal					= new ModelValueInt("Gain", 52);
	private ModelValueInt							parameterGainVGA					= new ModelValueInt("VGA Gain", 0, 2, 0, 60);
	private ModelValueBoolean						parameterIsCapturingPaused			= new ModelValueBoolean("Capturing Paused", false);
	private ModelValueInt							parameterPersistentDisplayPersTime  = new ModelValueInt("Persistence Time", 5);
	private ModelValueInt							parameterPeakFallRateSecs			= new ModelValueInt("Peak Fall Rate", 5);
	private ModelValueInt							parameterPeakFallThreshold			= new ModelValueInt("Peak Fall Threshold", 2);
	private ModelValueInt							parameterPeakHoldTime				= new ModelValueInt("Peak Hold Time", 0);	
	private ModelValueBoolean						parameterPersistentDisplay			= new ModelValueBoolean("Persistent display", false);
	private ModelValueInt							parameterSamples					= new ModelValueInt("Samples", 8192);
	private ModelValueInt							parameterFreqShift					= new ModelValueInt("FreqShift", 0);
	private ModelValueBoolean						parameterDatestamp					= new ModelValueBoolean("Datestamp", true);
	private ModelValueBoolean						parameterShowRealtime				= new ModelValueBoolean("Show Realtime", false);
	private ModelValueBoolean						parameterShowAverage				= new ModelValueBoolean("Show Average", false);
	private ModelValueBoolean						parameterShowPeaks					= new ModelValueBoolean("Show Peaks", true);
	private ModelValueBoolean						parameterShowMaxHold				= new ModelValueBoolean("Show MaxHold", true);
	private ModelValueBoolean						parameterShowPeakMarker				= new ModelValueBoolean("Show PeakMarker", false);
	private ModelValueBoolean						parameterShowHoldMarker				= new ModelValueBoolean("Show MaxHoldMarker", false);	
	private ModelValueInt							parameterMarkerCount				= new ModelValueInt("Marker Count", 1, 1, 1, 5);
	private ModelValueBoolean 						parameterDebugDisplay				= new ModelValueBoolean("Debug", false);
	private ModelValue<BigDecimal>					parameterSpectrumLineThickness		= new ModelValue<>("Spectrum Line Thickness", new BigDecimal("1"));
	private ModelValueBoolean						parameterSpectrumSpline				= new ModelValueBoolean("Spectrum Spline", false);
	private ModelValueInt							parameterSpectrumPaletteSize		= new ModelValueInt("Palette Size", 0);
	private ModelValueInt							parameterSpectrumPaletteStart		= new ModelValueInt("Palette Start", 0);
	private ModelValueInt							parameterAmplitudeOffset			= new ModelValueInt("Amplitude Offset", 0);
	private ModelValueInt							parameterPowerFluxCal				= new ModelValueInt("Power Flux Calibration", 50);
	private ModelValueInt							parameterAvgIterations				= new ModelValueInt("Average Iterations", 20);
	private ModelValueInt							parameterAvgOffset					= new ModelValueInt("Average Offset", 0);
	private ModelValueInt							parameterWaterfallSpeed				= new ModelValueInt("Waterfall Speed", 4);	
	private ModelValueBoolean						parameterSpurRemoval				= new ModelValueBoolean("Spur Removal", false);
	private ModelValueBoolean						parameterWaterfallVisible			= new ModelValueBoolean("Waterfall Visible", true);
	private ModelValueBoolean						parameterInfoBoxVisible				= new ModelValueBoolean("InfoBox Visible", true);
	private ModelValue<String>						parameterLogDetail					= new ModelValue<>("STATS Log Interval", new String("SEC"));
	private ModelValue<String>						parameterVideoArea					= new ModelValue<>("Video Area", new String("SPECTR"));
	private ModelValue<String>						parameterVideoFormat				= new ModelValue<>("Video Format", new String("GIF"));
	private ModelValueInt							parameterVideoResolution			= new ModelValueInt("Video Resolution", 540);
	private ModelValueInt							parameterVideoFrameRate				= new ModelValueInt("Video Framerate", 15);
	private ModelValue<String>						parameterSpectrumRecordFrameRate	= new ModelValue<>("DATA Record FPS", new String("FULL"));
	private ModelValue<String>						parameterFreqRange					= new ModelValue<>("FreqRange", new String("920-960"));
	private ModelValue<String>						parameterDisplayFreqRange			= new ModelValue<>("Display FreqRange", new String("920-960"));
	private PersistentDisplay						persistentDisplay					= new PersistentDisplay();
	private float									spectrumInitValue					= -150;
	private SpurFilter								spurFilter;
	private Thread									threadHackrfSweep;
	private ArrayBlockingQueue<Integer>				threadLaunchCommands				= new ArrayBlockingQueue<>(1);
	private Thread									threadLauncher;
	private Thread									threadProcessing;
	private TextTitle								titleFreqBand						= new TextTitle("",	new Font("Dialog", Font.PLAIN, 11));
	private RuntimePerformanceWatch					perfWatch							= new RuntimePerformanceWatch();
	private JFrame									uiFrame;
	private ValueMarker								waterfallPaletteEndMarker;
	private ValueMarker								waterfallPaletteStartMarker;
	private double									markerFrequencyPeak;
	private double									markerAmplitudePeak;
	//private ValueMarker							markerFrequencyHold;
	private double									markerFrequencyMaxHold;
	private double									markerAmplitudeMaxHold;
	private DatasetSpectrumPeak.SpectrumMarker[]	markersPeak						= new DatasetSpectrumPeak.SpectrumMarker[0];
	private DatasetSpectrumPeak.SpectrumMarker[]	markersMaxHold					= new DatasetSpectrumPeak.SpectrumMarker[0];
	private WaterfallPlot							waterfallPlot;
	private JLabel									labelMessages;
	private FileWriter		 						dataCap;
	private SpectrumRecording						spectrumCap;
	private long									spectrumRecordStartedMillis			= 0;
	private long									spectrumRecordFramesWritten			= 0;
	private Thread									threadSpectrumPlayback;
	private volatile File							currentSpectrumPlaybackFile;
	private volatile File							lastReplayDirectory				= new File(".");
	private volatile boolean						stopSpectrumPlayback				= false;
	private volatile SpectrumRecording.Header		playbackHeader;
	private volatile ReplayType						playbackType;
	private volatile IQReplayFile					playbackIqFile;
	private final CopyOnWriteArrayList<IQReplayAnalyzerFeed> playbackIqAnalyzers		= new CopyOnWriteArrayList<>();
	private volatile long							playbackPositionMillis				= 0;
	private volatile long							playbackDurationMillis				= 0;
	private volatile long							playbackCurrentEpochMillis			= 0;
	private volatile long							playbackSeekRequestMillis			= -1;
	private volatile boolean						resetTriggerRangeOnPlaybackStart	= false;
	private float									fSlope;
    private float									fShift;
    private int										lastX;
    private int										lastXX;
	private boolean									dragging;
	private boolean									draggingStartedInMultiRange;
	private int										draggingBaseStartMHz;
	private int										draggingBaseStopMHz;
	private int										draggingPanDeltaMHz;
	private IQSelection								iqSelection;
	private double									dragZoomAnchorMHz				= Double.NaN;
	private double									dragZoomCurrentMHz				= Double.NaN;
	private int										dragZoomAnchorX				= -1;
	private int										iqReplayPanLastX				= -1;
	private final TriggerSettings					triggerSettings					= new TriggerSettings();
	private final ArrayList<TriggerEvent>			triggerEvents					= new ArrayList<>();
	private long									lastTriggerAlertMillis			= 0;
	private boolean									lastTriggerMatched				= false;
	private FileWriter								triggerLogWriter;
	private javax.swing.JDialog						triggerDialog;
	private javax.swing.table.DefaultTableModel		triggerTableModel;
	private javax.swing.JLabel						triggerStatusLabel;
	private javax.swing.JSpinner					triggerStartSpinner;
	private javax.swing.JSpinner					triggerStopSpinner;
	private boolean									updatingTriggerRangeFromCode		= false;




//	private ValueMarker freqMarker;
//	private ValueMarker signalMarker;
//	private int mouseX;
//	private int mouseY;

	public HackRFSweepSpectrumAnalyzer() throws FileNotFoundException {
		//printInit(0);
		// load persisted settings (if any) from .ini placed next to the JAR
		try {
			loadSettings();
		} catch (Exception e) {
			// non-fatal - continue with defaults
			System.err.println("Failed to load settings: " + e.getMessage());
		}
		resetTriggerRangeToActiveFullRange();
		//HackRFSweepSettingsUI ui = new HackRFSweepSettingsUI(this);
		//ui.setVisible(true);
		//parameterFrequencyAllocationTable.setValue(new FrequencyAllocations().getTable().values().stream().findFirst().get());
		//parameterFrequencyAllocationTable.setValue(new FrequencyAllocations().getTable().get("SK.csv"));
		recalculateGains(parameterGainTotal.getValue());

		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		} catch (Exception e1) {
			e1.printStackTrace();
		}
		//		UIManager.getLookAndFeelDefaults().put("TabbedPane.borderHightlightColor", Color.black);
		//		UIManager.getLookAndFeelDefaults().put("TabbedPane.background", Color.black);
		//		UIManager.getLookAndFeelDefaults().put("TabbedPane.contentAreaColor", Color.black);
		//		UIManager.getLookAndFeelDefaults().put("TabbedPane.darkShadow", Color.black);
		//		UIManager.getLookAndFeelDefaults().put("TabbedPane.focus", Color.black);
		//		UIManager.getLookAndFeelDefaults().put("TabbedPane.highlight", Color.black);
		//		UIManager.getLookAndFeelDefaults().put("TabbedPane.light", Color.black);
		//		UIManager.getLookAndFeelDefaults().put("TabbedPane.selected", Color.black);
		//		UIManager.getLookAndFeelDefaults().put("TabbedPane.selectedForeground", Color.black);
		//		UIManager.getLookAndFeelDefaults().put("TabbedPane.selectHighlight", Color.black);
		//		UIManager.getLookAndFeelDefaults().put("TabbedPane.shadow", Color.black);
		//		UIManager.getLookAndFeelDefaults().put("TabbedPane.tabAreaBackground", Color.black);

		Insets insets = new Insets(1, 1, 1, 1);
		UIManager.getLookAndFeelDefaults().put("TabbedPane.contentBorderInsets", insets);
		UIManager.getLookAndFeelDefaults().put("TabbedPane.selectedTabPadInsets", insets);
		UIManager.getLookAndFeelDefaults().put("TabbedPane.tabAreaInsets", insets);
		//		UIManager.getLookAndFeelDefaults().put("", insets);
		//		UIManager.getLookAndFeelDefaults().put("", insets);

		//		UIManager.getLookAndFeelDefaults().values().forEach((p) -> {
		//			System.out.println(p.toString());
		//		});

		setupChart();

		setupChartMouseMarkers();

		waterfallPlot = new WaterfallPlot(chartPanel, 300);
		waterfallPlot.setPlaybackSeekListener(this::seekSpectrumPlayback);
		// initialize waterfall and persistent display with current range pairs (for compressed axis)
		int[] initialPairs = parseRangePairs(parameterFreqRange.getValue());
		waterfallPlot.setRangePairs(initialPairs, parameterFreqShift.getValue());
		persistentDisplay.setRangePairs(initialPairs, parameterFreqShift.getValue());
		// if settings were loaded from disk, apply palette values to waterfall before markers are used
		if (settingsLoaded) {
			waterfallPlot.setSpectrumPaletteStart(parameterSpectrumPaletteStart.getValue());
			waterfallPlot.setSpectrumPaletteSize(parameterSpectrumPaletteSize.getValue());
		}
		waterfallPaletteStartMarker = new ValueMarker(waterfallPlot.getSpectrumPaletteStart(), colors.cgreen,
				new BasicStroke(1f));
		waterfallPaletteEndMarker = new ValueMarker(
				waterfallPlot.getSpectrumPaletteStart() + waterfallPlot.getSpectrumPaletteSize(), colors.cgreen,
				new BasicStroke(1f));
		// ensure markers reflect possibly loaded settings
		waterfallPaletteStartMarker.setValue(waterfallPlot.getSpectrumPaletteStart());
		waterfallPaletteEndMarker.setValue(waterfallPlot.getSpectrumPaletteStart() + waterfallPlot.getSpectrumPaletteSize());

		//printInit(2);

		HackRFSweepSettingsUI settingsPanel = new HackRFSweepSettingsUI(this);

		//printInit(3);
		
		
		JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, chartPanel, waterfallPlot);
		splitPane.setResizeWeight(0.8);
		splitPane.setBorder(null);

		labelMessages = new JLabel("dsadasd");
		labelMessages.setForeground(Color.white);
		labelMessages.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
		parameterDebugDisplay.addListener((debug) -> {
			labelMessages.setVisible(debug);
		});
		parameterDebugDisplay.callObservers();
		
		JPanel splitPanePanel	= new JPanel(new BorderLayout());
		splitPanePanel.setBackground(Color.black);
		splitPanePanel.add(splitPane, BorderLayout.CENTER);
		splitPanePanel.add(labelMessages, BorderLayout.SOUTH);

		uiFrame = new JFrame();
		uiFrame.setExtendedState(uiFrame.getExtendedState() | Frame.MAXIMIZED_BOTH);
		uiFrame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		uiFrame.setLayout(new BorderLayout());
		uiFrame.setTitle(MAIN_WINDOW_TITLE);
		uiFrame.add(splitPanePanel, BorderLayout.CENTER);
		uiFrame.setMinimumSize(new Dimension(pSizeX, pSizeY));
		uiFrame.add(settingsPanel, BorderLayout.EAST);
		setupRecordingEscapeShortcut();
		try {
			uiFrame.setIconImage(new ImageIcon("program.png").getImage());
		} catch (Exception e) {
			//			e.printStackTrace();
		}
		
		//printInit(4);
		setupFrequencyAllocationTable();
		//printInit(5);
		
		uiFrame.pack();
		uiFrame.setVisible(true);

		//printInit(6);

		startLauncherThread();
		restartHackrfSweep();

		/**
		 * register parameter observers
		 */
		setupParameterObservers();
		
		//shutdown on exit
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				saveSettings();
			} catch (Exception e) {
				e.printStackTrace();
			}
			stopHackrfSweep();
		}));
	}

	/**
	 * Returns settings file placed in same directory as the running JAR (or classpath location when running from IDE).
	 */
	private File getSettingsFile() throws URISyntaxException {
		String fileName = "hackrf-sweep.ini";
		// Prefer to store/read .ini next to presets.csv if present.
		try {
			// try class location first
			java.net.URI uri = HackRFSweepSpectrumAnalyzer.class.getProtectionDomain().getCodeSource().getLocation().toURI();
			File loc = new File(uri);
			File dir = loc.isDirectory() ? loc : loc.getParentFile();
			if (dir != null) {
				File presetsInDir = new File(dir, "presets.csv");
				if (presetsInDir.exists()) {
					return new File(dir, fileName);
				}
			}
		} catch (Exception ex) {
			// ignore and try working dir
		}
		// fallback to current working directory where presets.csv usually resides when running from IDE
		File cwdPresets = new File("presets.csv");
		if (cwdPresets.exists()) {
			File cwd = cwdPresets.getAbsoluteFile().getParentFile();
			if (cwd != null) return new File(cwd, fileName);
		}
		// last resort: return ini next to class/jar
		try {
			java.net.URI uri = HackRFSweepSpectrumAnalyzer.class.getProtectionDomain().getCodeSource().getLocation().toURI();
			File loc = new File(uri);
			File dir = loc.isDirectory() ? loc : loc.getParentFile();
			if (dir == null) dir = new File(".");
			return new File(dir, fileName);
		} catch (Exception e) {
			return new File(fileName);
		}
	}

	private int clampMarkerCount(int count) {
		return Math.max(1, Math.min(5, count));
	}

	private boolean isValidVideoArea(String area) {
		return "SPECTR".equals(area) || "SPEC+WF".equals(area) || "FULLSCR".equals(area);
	}

	private boolean isValidTriggerSource(String source) {
		return TriggerSettings.SOURCE_REALTIME.equals(source) || TriggerSettings.SOURCE_PEAKS.equals(source);
	}

	private boolean isValidTriggerOperator(String operator) {
		return TriggerSettings.OPERATOR_GT.equals(operator)
				|| TriggerSettings.OPERATOR_LT.equals(operator)
				|| TriggerSettings.OPERATOR_GTE.equals(operator)
				|| TriggerSettings.OPERATOR_LTE.equals(operator);
	}

	private boolean isValidTriggerMatchMode(String matchMode) {
		return TriggerSettings.MATCH_ALL.equals(matchMode) || TriggerSettings.MATCH_ANY.equals(matchMode);
	}

	private void loadSettings() {
		try {
			File f = getSettingsFile();
			if (!f.exists()) return;
			Properties p = new Properties();
			try (FileInputStream in = new FileInputStream(f)) {
				p.load(in);
			}
			// simple mapping of properties to parameters
			if (p.getProperty("paramFreqRange") != null) {
				parameterFreqRange.setValue(p.getProperty("paramFreqRange"));
				int[] pairs = parseRangePairs(parameterFreqRange.getValue());
				if (pairs != null && pairs.length >= 2) {
					parameterFrequency.setValue(new FrequencyRange(pairs[0], pairs[pairs.length - 1]));
				}
			}
			if (p.getProperty("FFTBinHz") != null) parameterFFTBinHz.setValue(Integer.parseInt(p.getProperty("FFTBinHz")));
			if (p.getProperty("ReplayDirectory") != null) {
				File replayDirectory = new File(p.getProperty("ReplayDirectory"));
				if (replayDirectory.isDirectory())
					lastReplayDirectory = replayDirectory;
			}
			if (p.getProperty("Samples") != null) parameterSamples.setValue(Integer.parseInt(p.getProperty("Samples")));
			if (p.getProperty("GainTotal") != null) parameterGainTotal.setValue(Integer.parseInt(p.getProperty("GainTotal")));
			if (p.getProperty("GainLNA") != null) parameterGainLNA.setValue(Integer.parseInt(p.getProperty("GainLNA")));
			if (p.getProperty("GainVGA") != null) parameterGainVGA.setValue(Integer.parseInt(p.getProperty("GainVGA")));
			// Do NOT restore Antenna LNA (RF amp) from settings file.
			// Keep the runtime state (and Reset will set it unchecked).
			if (p.getProperty("AntennaPower") != null) parameterAntPower.setValue(Boolean.parseBoolean(p.getProperty("AntennaPower")));
			if (p.getProperty("FreqShift") != null) parameterFreqShift.setValue(Integer.parseInt(p.getProperty("FreqShift")));
			if (p.getProperty("PersistentDisplay") != null) parameterPersistentDisplay.setValue(Boolean.parseBoolean(p.getProperty("PersistentDisplay")));
			if (p.getProperty("PersistentDisplayPersTime") != null) parameterPersistentDisplayPersTime.setValue(Integer.parseInt(p.getProperty("PersistentDisplayPersTime")));
			if (p.getProperty("RealTime") != null) parameterShowRealtime.setValue(Boolean.parseBoolean(p.getProperty("RealTime")));
			if (p.getProperty("ShowPeaks") != null) parameterShowPeaks.setValue(Boolean.parseBoolean(p.getProperty("ShowPeaks")));
			if (p.getProperty("ShowAverage") != null) parameterShowAverage.setValue(Boolean.parseBoolean(p.getProperty("ShowAverage")));
			if (p.getProperty("ShowMaxHold") != null) parameterShowMaxHold.setValue(Boolean.parseBoolean(p.getProperty("ShowMaxHold")));
			if (p.getProperty("ShowPeakMarker") != null) parameterShowPeakMarker.setValue(Boolean.parseBoolean(p.getProperty("ShowPeakMarker")));
			if (p.getProperty("ShowHoldMarker") != null) parameterShowHoldMarker.setValue(Boolean.parseBoolean(p.getProperty("ShowHoldMarker")));
			if (p.getProperty("MarkerCount") != null) parameterMarkerCount.setValue(clampMarkerCount(Integer.parseInt(p.getProperty("MarkerCount"))));
			if (p.getProperty("SpurRemoval") != null) parameterSpurRemoval.setValue(Boolean.parseBoolean(p.getProperty("SpurRemoval")));
			if (p.getProperty("SpectrumLineThickness") != null) parameterSpectrumLineThickness.setValue(new java.math.BigDecimal(p.getProperty("SpectrumLineThickness")));
			if (p.getProperty("SpectrumSpline") != null) parameterSpectrumSpline.setValue(Boolean.parseBoolean(p.getProperty("SpectrumSpline")));
			if (p.getProperty("SpectrumPaletteSize") != null) parameterSpectrumPaletteSize.setValue(Integer.parseInt(p.getProperty("SpectrumPaletteSize")));
			if (p.getProperty("SpectrumPaletteStart") != null) parameterSpectrumPaletteStart.setValue(Integer.parseInt(p.getProperty("SpectrumPaletteStart")));
			if (p.getProperty("AmplitudeOffset") != null) parameterAmplitudeOffset.setValue(Integer.parseInt(p.getProperty("AmplitudeOffset")));
			if (p.getProperty("PowerFluxCal") != null) parameterPowerFluxCal.setValue(Integer.parseInt(p.getProperty("PowerFluxCal")));
			if (p.getProperty("AvgIterations") != null) parameterAvgIterations.setValue(Integer.parseInt(p.getProperty("AvgIterations")));
			if (p.getProperty("AvgOffset") != null) parameterAvgOffset.setValue(Integer.parseInt(p.getProperty("AvgOffset")));
			if (p.getProperty("WaterfallSpeed") != null) parameterWaterfallSpeed.setValue(Integer.parseInt(p.getProperty("WaterfallSpeed")));
			if (p.getProperty("WaterfallVisible") != null) parameterWaterfallVisible.setValue(Boolean.parseBoolean(p.getProperty("WaterfallVisible")));
			if (p.getProperty("InfoBoxVisible") != null) parameterInfoBoxVisible.setValue(Boolean.parseBoolean(p.getProperty("InfoBoxVisible")));
			if (p.getProperty("Datestamp") != null) parameterDatestamp.setValue(Boolean.parseBoolean(p.getProperty("Datestamp")));
			if (p.getProperty("VideoArea") != null && isValidVideoArea(p.getProperty("VideoArea"))) parameterVideoArea.setValue(p.getProperty("VideoArea"));
			if (p.getProperty("VideoFormat") != null) parameterVideoFormat.setValue(p.getProperty("VideoFormat"));
			if (p.getProperty("VideoResolution") != null) parameterVideoResolution.setValue(Integer.parseInt(p.getProperty("VideoResolution")));
			if (p.getProperty("VideoFrameRate") != null) parameterVideoFrameRate.setValue(Integer.parseInt(p.getProperty("VideoFrameRate")));
			if (p.getProperty("SpectrumRecordFrameRate") != null) parameterSpectrumRecordFrameRate.setValue(p.getProperty("SpectrumRecordFrameRate"));
			if (p.getProperty("LogDetail") != null) parameterLogDetail.setValue(p.getProperty("LogDetail"));
			if (p.getProperty("TriggerEnabled") != null) triggerSettings.enabled = Boolean.parseBoolean(p.getProperty("TriggerEnabled"));
			if (p.getProperty("TriggerThresholdDbm") != null) triggerSettings.maxLevelThresholdDbm = Double.parseDouble(p.getProperty("TriggerThresholdDbm"));
			if (p.getProperty("TriggerCooldownMillis") != null) triggerSettings.cooldownMillis = Long.parseLong(p.getProperty("TriggerCooldownMillis"));
			if (p.getProperty("TriggerSound") != null) triggerSettings.sound = Boolean.parseBoolean(p.getProperty("TriggerSound"));
			if (p.getProperty("TriggerLog") != null) triggerSettings.log = Boolean.parseBoolean(p.getProperty("TriggerLog"));
			if (p.getProperty("TriggerSource") != null && isValidTriggerSource(p.getProperty("TriggerSource"))) {
				triggerSettings.source = p.getProperty("TriggerSource");
			}
			if (p.getProperty("TriggerOperator") != null && isValidTriggerOperator(p.getProperty("TriggerOperator")))
				triggerSettings.maxLevelOperator = p.getProperty("TriggerOperator");
			if (p.getProperty("TriggerMatchMode") != null && isValidTriggerMatchMode(p.getProperty("TriggerMatchMode")))
				triggerSettings.matchMode = p.getProperty("TriggerMatchMode");
			if (p.getProperty("TriggerMaxLevelEnabled") != null) triggerSettings.maxLevelEnabled = Boolean.parseBoolean(p.getProperty("TriggerMaxLevelEnabled"));
			if (p.getProperty("TriggerMaxLevelOperator") != null && isValidTriggerOperator(p.getProperty("TriggerMaxLevelOperator"))) triggerSettings.maxLevelOperator = p.getProperty("TriggerMaxLevelOperator");
			if (p.getProperty("TriggerMaxLevelThresholdDbm") != null) triggerSettings.maxLevelThresholdDbm = Double.parseDouble(p.getProperty("TriggerMaxLevelThresholdDbm"));
			if (p.getProperty("TriggerTotalPowerEnabled") != null) triggerSettings.totalPowerEnabled = Boolean.parseBoolean(p.getProperty("TriggerTotalPowerEnabled"));
			if (p.getProperty("TriggerTotalPowerOperator") != null && isValidTriggerOperator(p.getProperty("TriggerTotalPowerOperator"))) triggerSettings.totalPowerOperator = p.getProperty("TriggerTotalPowerOperator");
			if (p.getProperty("TriggerTotalPowerThresholdDbm") != null) triggerSettings.totalPowerThresholdDbm = Double.parseDouble(p.getProperty("TriggerTotalPowerThresholdDbm"));
			if (p.getProperty("FrequencyAllocationTable") != null) {
				String allocationTableName = p.getProperty("FrequencyAllocationTable");
				if ("NONE".equals(allocationTableName)) {
					parameterFrequencyAllocationTable.setValue(null);
				} else {
					FrequencyAllocationTable table = new FrequencyAllocations().getTable().get(allocationTableName);
					if (table != null)
						parameterFrequencyAllocationTable.setValue(table);
				}
			}
			// mark that we successfully loaded settings from file
			this.settingsLoaded = true;
		} catch (Exception e) {
			System.err.println("Error reading settings: " + e.getMessage());
		}
	}

	private void saveSettings() {
		try {
			File f = getSettingsFile();
			Properties p = new Properties();
			p.setProperty("paramFreqRange", parameterFreqRange.getValue() == null ? "" : parameterFreqRange.getValue());
			p.setProperty("FFTBinHz", Integer.toString(parameterFFTBinHz.getValue()));
			File replayDirectory = lastReplayDirectory;
			if (replayDirectory != null)
				p.setProperty("ReplayDirectory", replayDirectory.getAbsolutePath());
			p.setProperty("Samples", Integer.toString(parameterSamples.getValue()));
			p.setProperty("GainTotal", Integer.toString(parameterGainTotal.getValue()));
			p.setProperty("GainLNA", Integer.toString(parameterGainLNA.getValue()));
			p.setProperty("GainVGA", Integer.toString(parameterGainVGA.getValue()));
			// Do NOT persist Antenna LNA (RF amp) - keep it runtime only and Reset sets it unchecked
			p.setProperty("AntennaPower", Boolean.toString(parameterAntPower.getValue()));
			p.setProperty("FreqShift", Integer.toString(parameterFreqShift.getValue()));
			p.setProperty("PersistentDisplay", Boolean.toString(parameterPersistentDisplay.getValue()));
			p.setProperty("PersistentDisplayPersTime", Integer.toString(parameterPersistentDisplayPersTime.getValue()));
			p.setProperty("RealTime", Boolean.toString(parameterShowRealtime.getValue()));
			p.setProperty("ShowPeaks", Boolean.toString(parameterShowPeaks.getValue()));
			p.setProperty("ShowAverage", Boolean.toString(parameterShowAverage.getValue()));
			p.setProperty("ShowMaxHold", Boolean.toString(parameterShowMaxHold.getValue()));
			p.setProperty("ShowPeakMarker", Boolean.toString(parameterShowPeakMarker.getValue()));
			p.setProperty("ShowHoldMarker", Boolean.toString(parameterShowHoldMarker.getValue()));
			p.setProperty("MarkerCount", Integer.toString(parameterMarkerCount.getValue()));
			p.setProperty("SpurRemoval", Boolean.toString(parameterSpurRemoval.getValue()));
			p.setProperty("SpectrumLineThickness", parameterSpectrumLineThickness.getValue().toString());
			p.setProperty("SpectrumSpline", Boolean.toString(parameterSpectrumSpline.getValue()));
			p.setProperty("SpectrumPaletteSize", Integer.toString(parameterSpectrumPaletteSize.getValue()));
			p.setProperty("SpectrumPaletteStart", Integer.toString(parameterSpectrumPaletteStart.getValue()));
			p.setProperty("AmplitudeOffset", Integer.toString(parameterAmplitudeOffset.getValue()));
			p.setProperty("PowerFluxCal", Integer.toString(parameterPowerFluxCal.getValue()));
			p.setProperty("AvgIterations", Integer.toString(parameterAvgIterations.getValue()));
			p.setProperty("AvgOffset", Integer.toString(parameterAvgOffset.getValue()));
			p.setProperty("WaterfallSpeed", Integer.toString(parameterWaterfallSpeed.getValue()));
			p.setProperty("WaterfallVisible", Boolean.toString(parameterWaterfallVisible.getValue()));
			p.setProperty("InfoBoxVisible", Boolean.toString(parameterInfoBoxVisible.getValue()));
			p.setProperty("Datestamp", Boolean.toString(parameterDatestamp.getValue()));
			p.setProperty("VideoArea", parameterVideoArea.getValue());
			p.setProperty("VideoFormat", parameterVideoFormat.getValue());
			p.setProperty("VideoResolution", Integer.toString(parameterVideoResolution.getValue()));
			p.setProperty("VideoFrameRate", Integer.toString(parameterVideoFrameRate.getValue()));
			p.setProperty("SpectrumRecordFrameRate", parameterSpectrumRecordFrameRate.getValue());
			p.setProperty("LogDetail", parameterLogDetail.getValue());
			synchronized (triggerSettings) {
				p.setProperty("TriggerEnabled", Boolean.toString(triggerSettings.enabled));
				p.setProperty("TriggerCooldownMillis", Long.toString(triggerSettings.cooldownMillis));
				p.setProperty("TriggerSound", Boolean.toString(triggerSettings.sound));
				p.setProperty("TriggerLog", Boolean.toString(triggerSettings.log));
				p.setProperty("TriggerSource", triggerSettings.source);
				p.setProperty("TriggerMatchMode", triggerSettings.matchMode);
				p.setProperty("TriggerMaxLevelEnabled", Boolean.toString(triggerSettings.maxLevelEnabled));
				p.setProperty("TriggerMaxLevelOperator", triggerSettings.maxLevelOperator);
				p.setProperty("TriggerMaxLevelThresholdDbm", Double.toString(triggerSettings.maxLevelThresholdDbm));
				p.setProperty("TriggerTotalPowerEnabled", Boolean.toString(triggerSettings.totalPowerEnabled));
				p.setProperty("TriggerTotalPowerOperator", triggerSettings.totalPowerOperator);
				p.setProperty("TriggerTotalPowerThresholdDbm", Double.toString(triggerSettings.totalPowerThresholdDbm));
			}
			FrequencyAllocationTable allocationTable = parameterFrequencyAllocationTable.getValue();
			p.setProperty("FrequencyAllocationTable", allocationTable == null ? "NONE" : allocationTable.toString());

			try (FileOutputStream out = new FileOutputStream(f)) {
				p.store(out, "HackRF Sweep saved settings");
			}
		} catch (Exception e) {
			System.err.println("Error saving settings: " + e.getMessage());
		}
	}

	@Override
	public ModelValueBoolean getAntennaPowerEnable() {
		return parameterAntPower;
	}

	@Override
	public ModelValueInt getFFTBinHz() {
		return parameterFFTBinHz;
	}

	@Override
	public ModelValueInt getIqReplayRbwHz() {
		return parameterIqReplayRbwHz;
	}

	@Override
	public ModelValue<FrequencyRange> getFrequency() {
		return parameterFrequency;
	}
	@Override
	public ModelValue<String> getparamFreqRange() {
		return parameterFreqRange;
	}

	@Override
	public ModelValue<String> getDisplayFreqRange() {
		return parameterDisplayFreqRange;
	}

	@Override
	public ModelValue<FrequencyAllocationTable> getFrequencyAllocationTable() {
		return parameterFrequencyAllocationTable;
	}

	@Override
	public ModelValueInt getGain() {
		return parameterGainTotal;
	}

	@Override
	public ModelValueInt getGainLNA() {
		return parameterGainLNA;
	}

	@Override
	public ModelValueInt getGainVGA() {
		return parameterGainVGA;
	}

	@Override
	public ModelValueBoolean getAntennaLNA() {
		return parameterAntennaLNA;
	}
	
	@Override
	public ModelValueBoolean isDatestampVisible() {
		return parameterDatestamp;
	}
	
	@Override
	public ModelValueInt getPeakFallRate() {
		return parameterPeakFallRateSecs;
	}
	
	@Override
	public ModelValueInt getPeakFallTrs() {
		return parameterPeakFallThreshold;
	}

	@Override
	public ModelValueInt getPeakHoldTime() {
		return parameterPeakHoldTime;
	}

	@Override
	public ModelValueInt getMarkerCount() {
		return parameterMarkerCount;
	}
	
	@Override
	public ModelValueInt getSamples() {
		return parameterSamples;
	}
	
	@Override
	public ModelValueInt getFreqShift() {
		return parameterFreqShift;
	}
	
	@Override
	public ModelValueInt getAvgIterations() {
		return parameterAvgIterations;
	}
	
	@Override
	public ModelValueInt getAvgOffset() {
		return parameterAvgOffset;
	}

	@Override
	public ModelValue<BigDecimal> getSpectrumLineThickness() {
		return parameterSpectrumLineThickness;
	}

	@Override
	public ModelValueBoolean isSpectrumSpline() {
		return parameterSpectrumSpline;
	}
	
	@Override
	public ModelValueInt getPersistentDisplayDecayRate() {
		return parameterPersistentDisplayPersTime;
	}

	@Override
	public ModelValueInt getSpectrumPaletteSize() {
		return parameterSpectrumPaletteSize;
	}

	@Override
	public ModelValueInt getSpectrumPaletteStart() {
		return parameterSpectrumPaletteStart;
	}

	@Override
	public ModelValueBoolean isCapturingPaused() {
		return parameterIsCapturingPaused;
	}
	
	@Override
	public ModelValueBoolean isRecordedVideo() {
		return parameterIsRecordedVideo;
	}

	@Override
	public ModelValueBoolean isRecordedData() {
		return parameterIsRecordedData;
	}

	@Override
	public ModelValueBoolean isRecordedSpectrum() {
		return parameterIsRecordedSpectrum;
	}

	@Override
	public ModelValueBoolean isPlayingSpectrum() {
		return parameterIsPlayingSpectrum;
	}

	@Override
	public ModelValue<String> getReplayType() {
		return parameterReplayType;
	}

	@Override
	public ModelValueBoolean isChartsRealtimeVisible() {
		return parameterShowRealtime;
	}
	
	@Override
	public ModelValueBoolean isChartsAverageVisible() {
		return parameterShowAverage;
	}
	
	@Override
	public ModelValueBoolean isChartsPeaksVisible() {
		return parameterShowPeaks;
	}
	
	@Override
	public ModelValueBoolean isChartsMaxHoldVisible() {
		return parameterShowMaxHold;
	}
	
	@Override
	public ModelValueBoolean isPeakMarkerVisible() {
		return parameterShowPeakMarker;
	}
	
	@Override
	public ModelValueBoolean isHoldMarkerVisible() {
		return parameterShowHoldMarker;
	}
	
	@Override
	public ModelValueBoolean isDebugDisplay() {
		return parameterDebugDisplay;
	}

	@Override
	public ModelValueBoolean isFilterSpectrum() {
		return parameterFilterSpectrum;
	}

	@Override
	public ModelValueBoolean isPersistentDisplayVisible() {
		return parameterPersistentDisplay;
	}

	@Override
	public ModelValueBoolean isSpurRemoval() {
		return this.parameterSpurRemoval;
	}

	@Override
	public ModelValueBoolean isWaterfallVisible() {
		return parameterWaterfallVisible;
	}
	
	@Override
	public ModelValueBoolean isInfoBoxVisible() {
		return parameterInfoBoxVisible;
	}
	
	@Override
	public ModelValueInt getWaterfallSpeed() {
		return parameterWaterfallSpeed;
	}	
	
	@Override
	public ModelValueInt getAmplitudeOffset() {
		return parameterAmplitudeOffset;
	}
	
	@Override
	public ModelValueInt getPowerFluxCal() {
		return parameterPowerFluxCal;
	}
	
	@Override
	public ModelValue<String> getLogDetail() {
		return parameterLogDetail;
	}

	@Override
	public ModelValue<String> getVideoArea() {
		return parameterVideoArea;
	}
	
	@Override
	public ModelValue<String> getVideoFormat() {
		return parameterVideoFormat;
	}
	
	@Override
	public ModelValueInt getVideoResolution() {
		return parameterVideoResolution;
	}
	
	@Override
	public ModelValueInt getVideoFrameRate() {
		return parameterVideoFrameRate;
	}

	@Override
	public ModelValue<String> getSpectrumRecordFrameRate() {
		return parameterSpectrumRecordFrameRate;
	}
	
	@Override
	public void newSpectrumData(boolean fullSweepDone, double[] frequencyStart, float fftBinWidthHz,
			float[] signalPowerdBm) {
			//	System.out.println(fullSweepDone+" "+frequencyStart+" "+fftBinWidthHz+" "+signalPowerdBm);
		fireHardwareStateChanged(true);
		if (!hwProcessingQueue.offer(new FFTBins(fullSweepDone, frequencyStart, fftBinWidthHz, signalPowerdBm))) {
			System.out.println("queue full");
			dropped++;
		}
	}

	@Override
	public void registerListener(HackRFEventListener listener) {
		hRFlisteners.add(listener);
	}

	@Override
	public void removeListener(HackRFEventListener listener) {
		hRFlisteners.remove(listener);
	}

	private void fireCapturingStateChanged() {
		SwingUtilities.invokeLater(() -> {
			if (chartPanel != null) {
				chartPanel.repaint();
			}
			synchronized (hRFlisteners) {
				for (HackRFEventListener hackRFEventListener : hRFlisteners) {
					try {
						hackRFEventListener.captureStateChanged(!parameterIsCapturingPaused.getValue());
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
			}
		});
	}

	private void setupRecordingEscapeShortcut() {
		uiFrame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
				.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "stopActiveRecordingsAndPlayback");
		uiFrame.getRootPane().getActionMap().put("stopActiveRecordingsAndPlayback", new javax.swing.AbstractAction() {
			private static final long serialVersionUID = -4196689206235301109L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				stopActiveRecordingsAndPlayback();
			}
		});
	}

	private void stopActiveRecordingsAndPlayback() {
		if (parameterIsRecordedVideo.getValue()) {
			parameterIsRecordedVideo.setValue(false);
		}
		if (parameterIsRecordedData.getValue()) {
			parameterIsRecordedData.setValue(false);
		}
		if (parameterIsRecordedSpectrum.getValue()) {
			parameterIsRecordedSpectrum.setValue(false);
		}
		if (parameterIsPlayingSpectrum.getValue()) {
			parameterIsPlayingSpectrum.setValue(false);
		}
	}

	private void fireHardwareStateChanged(boolean sendingData) {
		if (this.flagIsHWSendingData != sendingData) {
			this.flagIsHWSendingData = sendingData;
			SwingUtilities.invokeLater(() -> {
				synchronized (hRFlisteners) {
					for (HackRFEventListener hackRFEventListener : hRFlisteners) {
						try {
							hackRFEventListener.hardwareStatusChanged(sendingData);
						} catch (Exception e) {
							e.printStackTrace();
						}
					}
				}
			});
		}
	}
	
	private void startCaptureVideo() {
		int videoWidth = 0;
		int videoHeight = 0;
		if(parameterIsRecordedVideo.getValue())
		try {
			uiFrame.dispose();
			uiFrame.setVisible(false);
			uiFrame.setUndecorated(true);
			uiFrame.setVisible(true);
			uiFrame.pack();
			DateTimeFormatter dStampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm");
			LocalDateTime dateStamp = LocalDateTime.now();
			switch (parameterVideoResolution.getValue())
			{
				case 360: videoWidth = 640; videoHeight = 360; break;
				case 540: videoWidth = 960; videoHeight = 540; break;
				case 720: videoWidth = 1280; videoHeight = 720; break;
				case 1080: videoWidth = 1920; videoHeight = 1080; break;
			}
			if(parameterVideoResolution.getValue() == 1080 && !parameterVideoArea.getValue().equals("FULLSCR"))
			{
				//videoWidth = 1280; videoHeight = 720;
			}

			if(parameterVideoFormat.getValue().equals("GIF"))
			{
				gifCap = new ScreenCapture(uiFrame, 1, 0, parameterVideoFrameRate.getValue(), videoWidth, videoHeight,
					parameterVideoArea.getValue(), new File(formatVideoRecordingFilePrefix()
							+ formatRecordingRangeName() + " MHz "
							+ dateStamp.format(dStampFormat) + ".gif")
					);
			}
			else
			{
				h264Cap = new ScreenCaptureH264(uiFrame, 1, 0, parameterVideoFrameRate.getValue(), videoWidth, videoHeight,
					parameterVideoArea.getValue(), new String(formatVideoRecordingFilePrefix()
							+ formatRecordingRangeName() + " MHz "
							+ dateStamp.format(dStampFormat) + ".mp4")
					);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		else
		{
			uiFrame.dispose();
			uiFrame.setVisible(false);
			uiFrame.setUndecorated(false);
			uiFrame.setVisible(true);
			uiFrame.pack();
		}
	}

	private void startCaptureData() {
		if(parameterIsRecordedData.getValue())
		try {
			DateTimeFormatter dStampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm");
			LocalDateTime dateStamp = LocalDateTime.now();
			//System.out.println(frequencyStart+" "+fftBinWidthHz+" "+signalPowerdBm);
			dataCap = new FileWriter("# STATS " + formatRecordingRangeName() + " MHz "
					+ dateStamp.format(dStampFormat) + ".csv");
			dataCap.write("Timestamp,Total Spectrum Power [dBm],Power Flux Density [µW/m²],Max Amplitude [dBm],Frequency [MHz]\r\n");
		} catch (Exception e) {
			e.printStackTrace();
		}
		else
		{
			closeDataCapture();
		}
	}

	private String formatStatsLogTimestamp() {
		String pattern;
		switch (parameterLogDetail.getValue()) {
		case "FRAC":
			pattern = "HH:mm:ss.S";
			break;
		case "MIN":
			pattern = "HH:mm";
			break;
		case "SEC":
		default:
			pattern = "HH:mm:ss";
			break;
		}
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd " + pattern);
		long replayTime = playbackCurrentEpochMillis;
		if (isReplayActive() && replayTime > 0) {
			return LocalDateTime.ofInstant(Instant.ofEpochMilli(replayTime), ZoneId.systemDefault()).format(formatter);
		}
		return LocalDateTime.now().format(formatter);
	}

	private void closeDataCapture() {
		FileWriter cap = dataCap;
		dataCap = null;
		if (cap != null) {
			try {
				cap.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private void startCaptureSpectrum() {
		if (!parameterIsRecordedSpectrum.getValue()) {
			closeSpectrumRecording();
		} else {
			spectrumRecordStartedMillis = 0;
			spectrumRecordFramesWritten = 0;
		}
	}

	private void closeSpectrumRecording() {
		SpectrumRecording cap = spectrumCap;
		spectrumCap = null;
		if (cap != null) {
			try {
				cap.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private void recordSpectrumFrame(DatasetSpectrumPeak spectrum) {
		if (!parameterIsRecordedSpectrum.getValue())
			return;
		if (!shouldRecordSpectrumFrame())
			return;
		try {
			if (spectrumCap == null) {
				DateTimeFormatter dStampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss");
				LocalDateTime dateStamp = LocalDateTime.now();
				File file = new File("# DATA " + formatRecordingRangeName(spectrum) + " MHz "
						+ dateStamp.format(dStampFormat) + ".hsr");
				spectrumCap = new SpectrumRecording(file, spectrum, parameterFreqRange.getValue());
			}
			spectrumCap.writeFrame(spectrum);
		} catch (Exception e) {
			e.printStackTrace();
			parameterIsRecordedSpectrum.setValue(false);
			closeSpectrumRecording();
		}
	}

	private boolean shouldRecordSpectrumFrame() {
		String fps = parameterSpectrumRecordFrameRate.getValue();
		if (fps == null || fps.equals("FULL")) {
			return true;
		}
		int fpsValue;
		try {
			fpsValue = Integer.parseInt(fps);
		} catch (NumberFormatException e) {
			return true;
		}
		if (fpsValue <= 0) {
			return true;
		}
		long now = System.currentTimeMillis();
		if (spectrumRecordStartedMillis == 0) {
			spectrumRecordStartedMillis = now;
			spectrumRecordFramesWritten = 1;
			return true;
		}
		double elapsedSeconds = (now - spectrumRecordStartedMillis) / 1000d;
		long expectedFrames = (long) Math.floor(elapsedSeconds * fpsValue) + 1;
		if (spectrumRecordFramesWritten >= expectedFrames) {
			return false;
		}
		spectrumRecordFramesWritten++;
		return true;
	}

	@Override
	public void showTriggerDialog() {
		SwingUtilities.invokeLater(() -> {
			if (triggerDialog == null) {
				resetTriggerRangeToActiveFullRange();
				createTriggerDialog();
			}
			triggerDialog.setLocationRelativeTo(uiFrame);
			triggerDialog.setVisible(true);
		});
	}

	private void resetTriggerRangeToActiveFullRange() {
		FrequencyRange bounds = getFrequencyBoundsForDisplayRange(getActiveRangesForDisplay(),
				getActiveFreqStartMHzForDisplay(), getActiveFreqStopMHzForDisplay());
		int shift = getActiveFreqShiftForDisplay();
		synchronized (triggerSettings) {
			triggerSettings.startMHz = bounds.getStartMHz() + shift;
			triggerSettings.stopMHz = bounds.getEndMHz() + shift;
		}
		if (triggerStartSpinner != null && triggerStopSpinner != null) {
			SwingUtilities.invokeLater(() -> {
				updatingTriggerRangeFromCode = true;
				try {
					triggerStartSpinner.setValue(triggerSettings.startMHz);
					triggerStopSpinner.setValue(triggerSettings.stopMHz);
				} finally {
					updatingTriggerRangeFromCode = false;
				}
			});
		}
	}

	private void createTriggerDialog() {
		triggerDialog = new javax.swing.JDialog(uiFrame, "Trigger Alert", false);
		triggerDialog.setLayout(new BorderLayout(6, 6));
		triggerDialog.getContentPane().setBackground(Color.black);

		javax.swing.JPanel settingsPanel = new javax.swing.JPanel(
				new net.miginfocom.swing.MigLayout("", "[][80!][][80!][][90!]", "[][][][][]"));
		settingsPanel.setBackground(Color.black);

		javax.swing.JCheckBox enabled = new javax.swing.JCheckBox("Enabled", triggerSettings.enabled);
		enabled.setForeground(Color.white);
		enabled.setBackground(Color.black);
		settingsPanel.add(enabled, "cell 0 0");

		javax.swing.JSpinner start = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(triggerSettings.startMHz, 0d, 8000d, 0.1d));
		javax.swing.JSpinner stop = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(triggerSettings.stopMHz, 0d, 8000d, 0.1d));
		triggerStartSpinner = start;
		triggerStopSpinner = stop;
		javax.swing.JSpinner cooldown = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(triggerSettings.cooldownMillis / 1000d, 0d, 3600d, 1d));
		javax.swing.JSpinner source = new javax.swing.JSpinner(new javax.swing.SpinnerListModel(
				new String[] { TriggerSettings.SOURCE_REALTIME, TriggerSettings.SOURCE_PEAKS }));
		source.setValue(triggerSettings.source);
		javax.swing.JSpinner matchMode = new javax.swing.JSpinner(new javax.swing.SpinnerListModel(
				new String[] { TriggerSettings.MATCH_ALL, TriggerSettings.MATCH_ANY }));
		matchMode.setValue(triggerSettings.matchMode);
		javax.swing.JCheckBox maxLevelEnabled = new javax.swing.JCheckBox("Max level", triggerSettings.maxLevelEnabled);
		javax.swing.JSpinner maxLevelOperator = new javax.swing.JSpinner(new javax.swing.SpinnerListModel(
				new String[] { TriggerSettings.OPERATOR_GT, TriggerSettings.OPERATOR_LT,
						TriggerSettings.OPERATOR_GTE, TriggerSettings.OPERATOR_LTE }));
		maxLevelOperator.setValue(triggerSettings.maxLevelOperator);
		javax.swing.JSpinner maxLevelThreshold = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(triggerSettings.maxLevelThresholdDbm, -150d, 30d, 1d));
		javax.swing.JCheckBox totalPowerEnabled = new javax.swing.JCheckBox("Total power", triggerSettings.totalPowerEnabled);
		javax.swing.JSpinner totalPowerOperator = new javax.swing.JSpinner(new javax.swing.SpinnerListModel(
				new String[] { TriggerSettings.OPERATOR_GT, TriggerSettings.OPERATOR_LT,
						TriggerSettings.OPERATOR_GTE, TriggerSettings.OPERATOR_LTE }));
		totalPowerOperator.setValue(triggerSettings.totalPowerOperator);
		javax.swing.JSpinner totalPowerThreshold = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(triggerSettings.totalPowerThresholdDbm, -150d, 30d, 1d));
		addTriggerDialogCell(settingsPanel, "Start MHz", start, 0, 1);
		addTriggerDialogCell(settingsPanel, "Stop MHz", stop, 2, 1);
		addTriggerDialogCell(settingsPanel, "Cooldown s", cooldown, 4, 1);
		addTriggerDialogCell(settingsPanel, "Source", source, 0, 2);
		addTriggerDialogCell(settingsPanel, "Match", matchMode, 2, 2);

		styleTriggerCheckBox(maxLevelEnabled);
		styleTriggerCheckBox(totalPowerEnabled);
		settingsPanel.add(maxLevelEnabled, "cell 0 3");
		settingsPanel.add(maxLevelOperator, "cell 1 3,growx");
		addTriggerDialogCell(settingsPanel, "Threshold dBm", maxLevelThreshold, 2, 3);
		settingsPanel.add(totalPowerEnabled, "cell 0 4");
		settingsPanel.add(totalPowerOperator, "cell 1 4,growx");
		addTriggerDialogCell(settingsPanel, "Threshold dBm", totalPowerThreshold, 2, 4);

		javax.swing.JCheckBox sound = new javax.swing.JCheckBox("Sound", triggerSettings.sound);
		javax.swing.JCheckBox log = new javax.swing.JCheckBox("Log CSV", triggerSettings.log);
		sound.setForeground(Color.white);
		sound.setBackground(Color.black);
		log.setForeground(Color.white);
		log.setBackground(Color.black);
		settingsPanel.add(sound, "cell 4 2");
		settingsPanel.add(log, "cell 5 2");

		javax.swing.JButton clear = new javax.swing.JButton("CLEAR");
		javax.swing.JButton find = new javax.swing.JButton("FIND IN REPLAY");
		javax.swing.JPanel buttonPanel = new javax.swing.JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 2));
		buttonPanel.setBackground(Color.black);
		buttonPanel.add(clear);
		buttonPanel.add(find);

		triggerStatusLabel = new javax.swing.JLabel(formatTriggerEventCountStatus());
		triggerStatusLabel.setForeground(Color.white);
		buttonPanel.add(triggerStatusLabel);

		triggerTableModel = new javax.swing.table.DefaultTableModel(new Object[] { "Time", "Mode", "Offset", "MHz", "dBm" }, 0) {
			private static final long serialVersionUID = 1L;
			@Override
			public boolean isCellEditable(int row, int column) {
				return false;
			}
		};
		javax.swing.JTable table = new javax.swing.JTable(triggerTableModel);
		table.setFont(new Font("Arial", Font.PLAIN, 12));
		table.getTableHeader().setFont(new Font("Arial", Font.BOLD, 12));
		table.setRowHeight(20);
		table.setBackground(Color.black);
		table.setForeground(Color.white);
		table.setGridColor(Color.darkGray);
		table.getColumnModel().getColumn(0).setPreferredWidth(150);
		table.getColumnModel().getColumn(0).setMinWidth(140);
		table.getColumnModel().getColumn(1).setPreferredWidth(55);
		table.getColumnModel().getColumn(1).setMaxWidth(65);
		table.getColumnModel().getColumn(1).setMinWidth(45);
		table.getColumnModel().getColumn(2).setPreferredWidth(55);
		table.getColumnModel().getColumn(2).setMaxWidth(70);
		table.getColumnModel().getColumn(2).setMinWidth(45);

		Runnable updateTriggerSettingsFromUi = () -> updateTriggerSettingsFromUi(enabled, start, stop, cooldown,
				sound, log, source, matchMode, maxLevelEnabled, maxLevelOperator, maxLevelThreshold,
				totalPowerEnabled, totalPowerOperator, totalPowerThreshold);
		enabled.addActionListener(e -> updateTriggerSettingsFromUi.run());
		sound.addActionListener(e -> updateTriggerSettingsFromUi.run());
		log.addActionListener(e -> updateTriggerSettingsFromUi.run());
		maxLevelEnabled.addActionListener(e -> updateTriggerSettingsFromUi.run());
		totalPowerEnabled.addActionListener(e -> updateTriggerSettingsFromUi.run());
		start.addChangeListener(e -> updateTriggerSettingsFromUi.run());
		stop.addChangeListener(e -> updateTriggerSettingsFromUi.run());
		cooldown.addChangeListener(e -> updateTriggerSettingsFromUi.run());
		source.addChangeListener(e -> updateTriggerSettingsFromUi.run());
		matchMode.addChangeListener(e -> updateTriggerSettingsFromUi.run());
		maxLevelOperator.addChangeListener(e -> updateTriggerSettingsFromUi.run());
		maxLevelThreshold.addChangeListener(e -> updateTriggerSettingsFromUi.run());
		totalPowerOperator.addChangeListener(e -> updateTriggerSettingsFromUi.run());
		totalPowerThreshold.addChangeListener(e -> updateTriggerSettingsFromUi.run());
		updateTriggerSettingsFromUi.run();
		clear.addActionListener(e -> clearTriggerEvents());
		find.addActionListener(e -> startTriggerReplaySearch());

		triggerDialog.add(settingsPanel, BorderLayout.NORTH);
		triggerDialog.add(new javax.swing.JScrollPane(table), BorderLayout.CENTER);
		triggerDialog.add(buttonPanel, BorderLayout.SOUTH);
		triggerDialog.setSize(560, 360);
		refreshTriggerTable();
	}

	private void addTriggerDialogCell(javax.swing.JPanel panel, String label, javax.swing.JComponent component, int column, int row) {
		javax.swing.JLabel l = new javax.swing.JLabel(label);
		l.setForeground(Color.white);
		panel.add(l, "cell " + column + " " + row);
		panel.add(component, "cell " + (column + 1) + " " + row + ",growx");
	}

	private void styleTriggerCheckBox(javax.swing.JCheckBox checkBox) {
		checkBox.setForeground(Color.white);
		checkBox.setBackground(Color.black);
	}

	private void updateTriggerSettingsFromUi(javax.swing.JCheckBox enabled, javax.swing.JSpinner start,
			javax.swing.JSpinner stop, javax.swing.JSpinner cooldown, javax.swing.JCheckBox sound,
			javax.swing.JCheckBox log, javax.swing.JSpinner source, javax.swing.JSpinner matchMode,
			javax.swing.JCheckBox maxLevelEnabled, javax.swing.JSpinner maxLevelOperator,
			javax.swing.JSpinner maxLevelThreshold, javax.swing.JCheckBox totalPowerEnabled,
			javax.swing.JSpinner totalPowerOperator, javax.swing.JSpinner totalPowerThreshold) {
		if (updatingTriggerRangeFromCode)
			return;
		boolean enabledNow;
		synchronized (triggerSettings) {
			triggerSettings.enabled = enabled.isSelected();
			triggerSettings.startMHz = ((Number) start.getValue()).doubleValue();
			triggerSettings.stopMHz = ((Number) stop.getValue()).doubleValue();
			if (triggerSettings.stopMHz < triggerSettings.startMHz) {
				double tmp = triggerSettings.startMHz;
				triggerSettings.startMHz = triggerSettings.stopMHz;
				triggerSettings.stopMHz = tmp;
				updatingTriggerRangeFromCode = true;
				try {
					triggerStartSpinner.setValue(triggerSettings.startMHz);
					triggerStopSpinner.setValue(triggerSettings.stopMHz);
				} finally {
					updatingTriggerRangeFromCode = false;
				}
			}
			triggerSettings.cooldownMillis = Math.round(((Number) cooldown.getValue()).doubleValue() * 1000d);
			triggerSettings.sound = sound.isSelected();
			triggerSettings.log = log.isSelected();
			triggerSettings.source = source.getValue().toString();
			triggerSettings.matchMode = matchMode.getValue().toString();
			triggerSettings.maxLevelEnabled = maxLevelEnabled.isSelected();
			triggerSettings.maxLevelOperator = maxLevelOperator.getValue().toString();
			triggerSettings.maxLevelThresholdDbm = ((Number) maxLevelThreshold.getValue()).doubleValue();
			triggerSettings.totalPowerEnabled = totalPowerEnabled.isSelected();
			triggerSettings.totalPowerOperator = totalPowerOperator.getValue().toString();
			triggerSettings.totalPowerThresholdDbm = ((Number) totalPowerThreshold.getValue()).doubleValue();
			enabledNow = triggerSettings.enabled;
		}
		updateTriggerStatus("Trigger " + (enabledNow ? "enabled" : "disabled"));
	}

	private void clearTriggerEvents() {
		synchronized (triggerEvents) {
			triggerEvents.clear();
		}
		lastTriggerMatched = false;
		lastTriggerAlertMillis = 0;
		waterfallPlot.setPlaybackEventMarkers(new long[0]);
		refreshTriggerTable();
		updateTriggerStatus(formatTriggerEventCountStatus());
	}

	private void clearPlaybackTriggerEvents() {
		synchronized (triggerEvents) {
			triggerEvents.removeIf(event -> event.playback);
		}
		waterfallPlot.setPlaybackEventMarkers(new long[0]);
		SwingUtilities.invokeLater(this::refreshTriggerTable);
	}

	private void startTriggerReplaySearch() {
		File file = currentSpectrumPlaybackFile;
		if (file == null || !file.isFile()) {
			updateTriggerStatus("Start replay first, then FIND");
			return;
		}
		updateTriggerStatus("Searching replay...");
		ReplayType searchType = playbackType;
		Thread searchThread = new Thread(() -> {
			if (searchType == ReplayType.DATA) {
				searchTriggerEventsInReplay(file);
			} else if (searchType == ReplayType.WAV || searchType == ReplayType.RAW) {
				searchTriggerEventsInIqReplay(file);
			} else {
				SwingUtilities.invokeLater(() -> updateTriggerStatus("Unsupported replay type"));
			}
		});
		searchThread.setName("trigger replay search");
		searchThread.setDaemon(true);
		searchThread.setPriority(Thread.MIN_PRIORITY);
		searchThread.start();
	}

	private void searchTriggerEventsInReplay(File file) {
		ArrayList<TriggerEvent> found = new ArrayList<>();
		try (SpectrumRecording.Reader reader = new SpectrumRecording.Reader(file)) {
			TriggerSettings settings = snapshotTriggerSettings();
			SpectrumRecording.Header header = reader.getHeader();
			SpectrumRecording.Frame frame;
			boolean previousMatched = false;
			float[] searchPeak = null;
			float[] searchPeakHold = null;
			long[] searchPeakHoldTime = null;
			long previousOffsetMillis = 0;
			while ((frame = reader.readFrame()) != null) {
				float[] triggerValues = frame.spectrum;
				if (TriggerSettings.SOURCE_PEAKS.equals(settings.source)) {
					if (searchPeak == null) {
						searchPeak = frame.spectrum.clone();
						searchPeakHold = frame.spectrum.clone();
						searchPeakHoldTime = new long[frame.spectrum.length];
						java.util.Arrays.fill(searchPeakHoldTime, frame.timeOffsetMillis);
					} else {
						updateTriggerSearchPeaks(frame.spectrum, searchPeak, searchPeakHold, searchPeakHoldTime,
								Math.max(1, frame.timeOffsetMillis - previousOffsetMillis), frame.timeOffsetMillis);
					}
					triggerValues = searchPeakHold;
				}
				TriggerHit hit = evaluateTriggerFrame(triggerValues, header.freqStartMHz, header.fftBinHz,
						header.freqShift, settings);
				if (hit.matched && !previousMatched) {
					long epoch = header.startEpochMillis <= 0 ? 0 : header.startEpochMillis + frame.timeOffsetMillis;
					found.add(new TriggerEvent(epoch, frame.timeOffsetMillis, true, hit.frequencyMHz, hit.amplitudeDbm,
							hit.maxLevelDbm, hit.totalPowerDbm));
				}
				previousMatched = hit.matched;
				previousOffsetMillis = frame.timeOffsetMillis;
			}
		} catch (IOException e) {
			e.printStackTrace();
			SwingUtilities.invokeLater(() -> updateTriggerStatus("Replay search failed: " + e.getMessage()));
			return;
		}
		synchronized (triggerEvents) {
			triggerEvents.removeIf(event -> event.playback);
			triggerEvents.addAll(found);
		}
		SwingUtilities.invokeLater(() -> {
			refreshTriggerTable();
			waterfallPlot.setPlaybackEventMarkers(buildPlaybackEventMarkers());
			updateTriggerStatus("Found " + found.size() + " replay events / " + formatTriggerEventCountStatus());
		});
	}

	private void searchTriggerEventsInIqReplay(File file) {
		ArrayList<TriggerEvent> found = new ArrayList<>();
		try (IQReplayFile iqFile = IQReplayFile.open(file)) {
			TriggerSettings settings = snapshotTriggerSettings();
			int sampleRateHz = iqFile.getSampleRateHz();
			int fftSize = IQSpectrumFrame.chooseSize(sampleRateHz, parameterIqReplayRbwHz.getValue());
			int frameSamples = Math.max(fftSize, Math.max(1, sampleRateHz / IQ_REPLAY_FRAMES_PER_SECOND));
			long frameMillis = Math.max(1, Math.round(frameSamples * 1000d / sampleRateHz));
			long durationMillis = iqFile.getDurationMillis();
			IQSpectrumFrame spectrumFrame = new IQSpectrumFrame(fftSize);
			byte[] iqData = new byte[frameSamples * 2];
			float[] searchPeak = null;
			float[] searchPeakHold = null;
			long[] searchPeakHoldTime = null;
			boolean previousMatched = false;
			long previousOffsetMillis = 0;
			float fftBinHz = sampleRateHz / (float) fftSize;
			double firstBinMHz = (iqFile.getCenterFrequencyHz() - sampleRateHz / 2d + fftBinHz * 0.5d)
					/ 1_000_000d;

			for (long frameStartMillis = 0; frameStartMillis < durationMillis; frameStartMillis += frameMillis) {
				iqFile.seekMillis(frameStartMillis);
				iqFile.readLoopedSigned(iqData);
				long frameOffsetMillis = Math.min(durationMillis, frameStartMillis + frameMillis);
				float[] spectrum = spectrumFrame.compute(iqData, iqData.length - fftSize * 2);
				for (int i = 0; i < spectrum.length; i++) {
					spectrum[i] -= (30 - parameterAmplitudeOffset.getValue());
				}

				float[] triggerValues = spectrum;
				if (TriggerSettings.SOURCE_PEAKS.equals(settings.source)) {
					if (searchPeak == null) {
						searchPeak = spectrum.clone();
						searchPeakHold = spectrum.clone();
						searchPeakHoldTime = new long[spectrum.length];
						java.util.Arrays.fill(searchPeakHoldTime, frameOffsetMillis);
					} else {
						updateTriggerSearchPeaks(spectrum, searchPeak, searchPeakHold, searchPeakHoldTime,
								Math.max(1, frameOffsetMillis - previousOffsetMillis), frameOffsetMillis);
					}
					triggerValues = searchPeakHold;
				}

				TriggerHit hit = evaluateTriggerFrame(triggerValues, firstBinMHz, fftBinHz, settings);
				if (hit.matched && !previousMatched) {
					found.add(new TriggerEvent(file.lastModified() + frameOffsetMillis, frameOffsetMillis, true,
							hit.frequencyMHz, hit.amplitudeDbm, hit.maxLevelDbm, hit.totalPowerDbm));
				}
				previousMatched = hit.matched;
				previousOffsetMillis = frameOffsetMillis;
			}
		} catch (IOException | RuntimeException e) {
			e.printStackTrace();
			SwingUtilities.invokeLater(() -> updateTriggerStatus("Replay search failed: " + e.getMessage()));
			return;
		}
		storeTriggerReplaySearchResults(found);
	}

	private void storeTriggerReplaySearchResults(ArrayList<TriggerEvent> found) {
		synchronized (triggerEvents) {
			triggerEvents.removeIf(event -> event.playback);
			triggerEvents.addAll(found);
		}
		SwingUtilities.invokeLater(() -> {
			refreshTriggerTable();
			waterfallPlot.setPlaybackEventMarkers(buildPlaybackEventMarkers());
			updateTriggerStatus("Found " + found.size() + " replay events / " + formatTriggerEventCountStatus());
		});
	}

	private void updateTriggerSearchPeaks(float[] realtime, float[] peak, float[] peakHold, long[] peakHoldTime,
			long timeDiffMillis, long offsetMillis) {
		long peakFalloutMillis = parameterPeakFallRateSecs.getValue() * 1000l;
		long peakHoldMillis = parameterPeakHoldTime.getValue() * 1000l;
		float peakFallThreshold = parameterPeakFallThreshold.getValue();
		for (int i = 0; i < realtime.length; i++) {
			if (realtime[i] > peakHold[i]) {
				peakHold[i] = peak[i] = realtime[i];
				peakHoldTime[i] = offsetMillis;
			}
			peak[i] = (float) jspectrumanalyzer.core.EMA.calculateTimeDependent(realtime[i], peak[i],
					timeDiffMillis, peakFalloutMillis);
			if (peakHold[i] - peak[i] > peakFallThreshold && offsetMillis - peakHoldTime[i] > peakHoldMillis) {
				peakHold[i] = peak[i];
			}
		}
	}

	private TriggerSettings snapshotTriggerSettings() {
		TriggerSettings copy = new TriggerSettings();
		synchronized (triggerSettings) {
			copy.enabled = triggerSettings.enabled;
			copy.startMHz = triggerSettings.startMHz;
			copy.stopMHz = triggerSettings.stopMHz;
			copy.cooldownMillis = triggerSettings.cooldownMillis;
			copy.sound = triggerSettings.sound;
			copy.log = triggerSettings.log;
			copy.source = triggerSettings.source;
			copy.matchMode = triggerSettings.matchMode;
			copy.maxLevelEnabled = triggerSettings.maxLevelEnabled;
			copy.maxLevelOperator = triggerSettings.maxLevelOperator;
			copy.maxLevelThresholdDbm = triggerSettings.maxLevelThresholdDbm;
			copy.totalPowerEnabled = triggerSettings.totalPowerEnabled;
			copy.totalPowerOperator = triggerSettings.totalPowerOperator;
			copy.totalPowerThresholdDbm = triggerSettings.totalPowerThresholdDbm;
		}
		return copy;
	}

	private void evaluateTrigger(DatasetSpectrumPeak spectrum, boolean playbackMode) {
		TriggerSettings settings = snapshotTriggerSettings();
		if (!settings.enabled)
			return;
		float[] triggerValues = TriggerSettings.SOURCE_PEAKS.equals(settings.source)
				? spectrum.getPeakSpectrumArray()
				: spectrum.getSpectrumArray();
		TriggerHit hit = evaluateTriggerFrame(triggerValues, spectrum.getFreqStartMHz(),
				spectrum.getFFTBinSizeHz(), spectrum.getFreqShift(), settings);
		long now = System.currentTimeMillis();
		boolean cooldownOk = settings.cooldownMillis <= 0 || now - lastTriggerAlertMillis >= settings.cooldownMillis;
		if (!hit.matched) {
			lastTriggerMatched = false;
			return;
		}

		long playbackMillis = playbackMode ? playbackPositionMillis : -1;
		long epochMillis = playbackMode ? playbackCurrentEpochMillis : now;
		TriggerEvent event = new TriggerEvent(epochMillis, playbackMillis, playbackMode, hit.frequencyMHz,
				hit.amplitudeDbm, hit.maxLevelDbm, hit.totalPowerDbm);

		if (playbackMode) {
			if (!lastTriggerMatched) {
				addTriggerEvent(event, settings, cooldownOk, cooldownOk);
				if (cooldownOk)
					lastTriggerAlertMillis = now;
			}
		} else if (cooldownOk) {
			lastTriggerAlertMillis = now;
			addTriggerEvent(event, settings);
		}
		lastTriggerMatched = true;
	}

	private TriggerHit evaluateTriggerFrame(float[] values, int freqStartMHz, float fftBinHz, int freqShift,
			TriggerSettings settings) {
		return evaluateTriggerFrame(values, freqStartMHz + freqShift, fftBinHz, settings);
	}

	private TriggerHit evaluateTriggerFrame(float[] values, double firstBinMHz, float fftBinHz,
			TriggerSettings settings) {
		double bestAmp = -1000;
		double bestFreq = firstBinMHz;
		double powerMw = 0;
		double freqStepMHz = fftBinHz / 1000000d;
		for (int i = 0; i < values.length; i++) {
			double freqMHz = firstBinMHz + freqStepMHz * i;
			if (freqMHz < settings.startMHz || freqMHz > settings.stopMHz)
				continue;
			if (values[i] > -95) {
				powerMw += Math.pow(10, values[i] / 10d);
			}
			if (values[i] > bestAmp) {
				bestAmp = values[i];
				bestFreq = freqMHz;
			}
		}
		double totalPowerDbm = powerMw > 0 ? 10d * Math.log10(powerMw) : -1000;
		boolean hasCondition = settings.maxLevelEnabled || settings.totalPowerEnabled;
		boolean matched = TriggerSettings.MATCH_ANY.equals(settings.matchMode) ? false : true;
		if (settings.maxLevelEnabled) {
			boolean conditionMatched = matchesTriggerOperator(bestAmp, settings.maxLevelThresholdDbm,
					settings.maxLevelOperator);
			matched = TriggerSettings.MATCH_ANY.equals(settings.matchMode) ? matched || conditionMatched
					: matched && conditionMatched;
		}
		if (settings.totalPowerEnabled) {
			boolean conditionMatched = matchesTriggerOperator(totalPowerDbm, settings.totalPowerThresholdDbm,
					settings.totalPowerOperator);
			matched = TriggerSettings.MATCH_ANY.equals(settings.matchMode) ? matched || conditionMatched
					: matched && conditionMatched;
		}
		double displayValue = settings.maxLevelEnabled ? bestAmp : totalPowerDbm;
		return new TriggerHit(hasCondition && matched, roundFrequencyToBinStep(bestFreq, fftBinHz),
				Math.round(displayValue * 10d) / 10d, Math.round(bestAmp * 10d) / 10d,
				Math.round(totalPowerDbm * 10d) / 10d);
	}

	private boolean matchesTriggerOperator(double value, double threshold, String operator) {
		if (TriggerSettings.OPERATOR_LT.equals(operator))
			return value < threshold;
		if (TriggerSettings.OPERATOR_GTE.equals(operator))
			return value >= threshold;
		if (TriggerSettings.OPERATOR_LTE.equals(operator))
			return value <= threshold;
		return value > threshold;
	}

	private double roundFrequencyToBinStep(double frequencyMHz, float fftBinHz) {
		double freqStepMHz = fftBinHz / 1000000d;
		int freqRound = Math.round(1 / (float) freqStepMHz);
		if (freqRound < 1)
			freqRound = 1;
		return (double) Math.round(freqRound * frequencyMHz) / freqRound;
	}

	private String formatTriggerFrequencyMHz(double frequencyMHz) {
		return String.format(new Locale("sk", "SK"), "%.2f", frequencyMHz);
	}

	private void addTriggerEvent(TriggerEvent event, TriggerSettings settings) {
		addTriggerEvent(event, settings, true, true);
	}

	private void addTriggerEvent(TriggerEvent event, TriggerSettings settings, boolean allowSound, boolean allowLog) {
		synchronized (triggerEvents) {
			if (event.playback && hasPlaybackTriggerEvent(event.playbackMillis)) {
				return;
			}
			triggerEvents.add(event);
		}
		if (settings.sound && allowSound) {
			Toolkit.getDefaultToolkit().beep();
		}
		if (settings.log && allowLog) {
			writeTriggerLog(event);
		}
		SwingUtilities.invokeLater(() -> {
			refreshTriggerTable();
			if (event.playback)
				waterfallPlot.setPlaybackEventMarkers(buildPlaybackEventMarkers());
			updateTriggerStatus(formatTriggerEventCountStatus());
		});
	}

	private boolean hasPlaybackTriggerEvent(long playbackMillis) {
		for (TriggerEvent event : triggerEvents) {
			if (event.playback && event.playbackMillis == playbackMillis)
				return true;
		}
		return false;
	}

	private void writeTriggerLog(TriggerEvent event) {
		try {
			if (triggerLogWriter == null) {
				DateTimeFormatter dStampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss");
				triggerLogWriter = new FileWriter("# TRIGGER " + formatRecordingRangeName() + " MHz "
						+ LocalDateTime.now().format(dStampFormat) + ".csv");
				triggerLogWriter.write("Mode,Timestamp,Playback Offset,Frequency [MHz],Max Level [dBm],Total Power [dBm]\r\n");
			}
			triggerLogWriter.write((event.playback ? "REPLAY" : "LIVE") + ","
					+ formatTriggerEventTime(event) + ","
					+ (event.playbackMillis >= 0 ? formatMillis(event.playbackMillis) : "") + ","
					+ String.format(Locale.US, "%.2f", event.frequencyMHz) + ","
					+ String.format(Locale.US, "%.1f", event.maxLevelDbm) + ","
					+ String.format(Locale.US, "%.1f", event.totalPowerDbm) + "\r\n");
			triggerLogWriter.flush();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void refreshTriggerTable() {
		if (triggerTableModel == null)
			return;
		triggerTableModel.setRowCount(0);
		synchronized (triggerEvents) {
			for (int i = triggerEvents.size() - 1; i >= 0; i--) {
				TriggerEvent event = triggerEvents.get(i);
				triggerTableModel.addRow(new Object[] {
						formatTriggerEventTime(event),
						event.playback ? "REPLAY" : "LIVE",
						event.playbackMillis >= 0 ? formatMillis(event.playbackMillis) : "",
						formatTriggerFrequencyMHz(event.frequencyMHz),
						formatTriggerEventDbm(event)
				});
			}
		}
	}

	private String formatTriggerEventDbm(TriggerEvent event) {
		boolean maxEnabled = triggerSettings.maxLevelEnabled;
		boolean totalEnabled = triggerSettings.totalPowerEnabled;
		if (maxEnabled && totalEnabled)
			return String.format(Locale.US, "M %.1f / T %.1f", event.maxLevelDbm, event.totalPowerDbm);
		if (totalEnabled)
			return String.format(Locale.US, "T %.1f", event.totalPowerDbm);
		return String.format(Locale.US, "M %.1f", event.maxLevelDbm);
	}

	private long[] buildPlaybackEventMarkers() {
		ArrayList<Long> markers = new ArrayList<>();
		synchronized (triggerEvents) {
			for (TriggerEvent event : triggerEvents) {
				if (event.playback && event.playbackMillis >= 0)
					markers.add(event.playbackMillis);
			}
		}
		long[] out = new long[markers.size()];
		for (int i = 0; i < markers.size(); i++)
			out[i] = markers.get(i);
		return out;
	}

	private void updateTriggerStatus(String status) {
		if (triggerStatusLabel != null)
			triggerStatusLabel.setText(status);
	}

	private String formatTriggerEventCountStatus() {
		int live = 0;
		int replay = 0;
		synchronized (triggerEvents) {
			for (TriggerEvent event : triggerEvents) {
				if (event.playback)
					replay++;
				else
					live++;
			}
		}
		return "Events LIVE: " + live + " / REPLAY: " + replay;
	}

	private String formatTriggerEventTime(TriggerEvent event) {
		if (event.epochMillis <= 0)
			return "";
		return LocalDateTime.ofInstant(Instant.ofEpochMilli(event.epochMillis), ZoneId.systemDefault())
				.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
	}

	private String formatMillis(long millis) {
		if (millis < 0)
			millis = 0;
		long totalSeconds = millis / 1000;
		long seconds = totalSeconds % 60;
		long minutes = (totalSeconds / 60) % 60;
		long hours = totalSeconds / 3600;
		if (hours > 0)
			return String.format("%d:%02d:%02d", hours, minutes, seconds);
		return String.format("%02d:%02d", minutes, seconds);
	}

	private void startSpectrumPlayback() {
		if (!parameterIsPlayingSpectrum.getValue()) {
			stopSpectrumPlayback = true;
			return;
		}
		if (parameterIsRecordedSpectrum.getValue()) {
			parameterIsPlayingSpectrum.setValue(false);
			return;
		}
		File replayDirectory = lastReplayDirectory;
		if (replayDirectory == null || !replayDirectory.isDirectory())
			replayDirectory = new File(".");
		JFileChooser chooser = new JFileChooser(replayDirectory);
		chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
				"HackRF replay (*.hsr, *.wav, *.pcm)", "hsr", "wav", "pcm"));
		int result = chooser.showOpenDialog(uiFrame);
		if (result != JFileChooser.APPROVE_OPTION) {
			parameterIsPlayingSpectrum.setValue(false);
			return;
		}

		File file = chooser.getSelectedFile();
		File selectedDirectory = file.getAbsoluteFile().getParentFile();
		if (selectedDirectory != null)
			lastReplayDirectory = selectedDirectory;
		currentSpectrumPlaybackFile = file;
		resetTriggerRangeOnPlaybackStart = true;
		clearPlaybackTriggerEvents();
		stopSpectrumPlayback = false;
		hwProcessingQueue.clear();
		threadSpectrumPlayback = new Thread(() -> playReplayFile(file));
		threadSpectrumPlayback.setName("spectrum recording playback");
		threadSpectrumPlayback.start();
	}

	private void playReplayFile(File file) {
		boolean restartLive = true;
		FrequencyRange liveFrequencyRangeBeforePlayback = getFreq();
		int liveRbwBeforePlayback = parameterFFTBinHz.getValue();
		try {
			playbackType = replayTypeForFile(file);
			parameterReplayType.setValue(playbackType.name());
			SwingUtilities.invokeLater(() -> uiFrame.setTitle(MAIN_WINDOW_TITLE + " - " + file.getName()));
			stopHackrfSweep();
			setChartLiveGesturesEnabled(playbackType != ReplayType.DATA);
			hwProcessingQueue.clear();
			playbackPositionMillis = 0;
			playbackSeekRequestMillis = -1;
			if (playbackType == ReplayType.DATA) {
				playbackDurationMillis = readSpectrumRecordingDuration(file);
				long startOffsetMillis = 0;
				while (!stopSpectrumPlayback) {
					long nextSeek = playSpectrumRecordingFrom(file, startOffsetMillis);
					startOffsetMillis = nextSeek < 0 ? 0 : nextSeek;
				}
			} else {
				playIqRecording(file);
			}
		} catch (Exception e) {
			e.printStackTrace();
			SwingUtilities.invokeLater(() -> javax.swing.JOptionPane.showMessageDialog(uiFrame, e.getMessage(),
					"Replay error", javax.swing.JOptionPane.ERROR_MESSAGE));
		} finally {
			for (IQReplayAnalyzerFeed feed : playbackIqAnalyzers) {
				feed.close();
			}
			playbackIqAnalyzers.clear();
			parameterFrequency.setValue(liveFrequencyRangeBeforePlayback);
			parameterFFTBinHz.setValue(liveRbwBeforePlayback);
			playbackHeader = null;
			playbackIqFile = null;
			playbackType = null;
			parameterReplayType.setValue("");
			SwingUtilities.invokeLater(() -> uiFrame.setTitle(MAIN_WINDOW_TITLE));
			waterfallPlot.setFrequencyBounds(Double.NaN, Double.NaN);
			persistentDisplay.setFrequencyBounds(Double.NaN, Double.NaN);
			parameterDisplayFreqRange.setValue(parameterFreqRange.getValue());
			resetTriggerRangeToActiveFullRange();
			redrawFrequencySpectrumTable();
			playbackPositionMillis = 0;
			playbackDurationMillis = 0;
			playbackCurrentEpochMillis = 0;
			playbackSeekRequestMillis = -1;
			resetTriggerRangeOnPlaybackStart = false;
			waterfallPlot.setPlaybackStatus(false, 0, 0);
			waterfallPlot.setPlaybackEventMarkers(new long[0]);
			currentSpectrumPlaybackFile = null;
			if (threadProcessing != null) {
				threadProcessing.interrupt();
				try {
					threadProcessing.join(500);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				threadProcessing = null;
			}
			stopSpectrumPlayback = false;
			fireHardwareStateChanged(false);
			setChartLiveGesturesEnabled(true);
			SwingUtilities.invokeLater(() -> parameterIsPlayingSpectrum.setValue(false));
			if (restartLive) {
				restartHackrfSweep();
			}
		}
	}

	private ReplayType replayTypeForFile(File file) throws IOException {
		String name = file.getName().toLowerCase(Locale.ROOT);
		if (name.endsWith(".hsr"))
			return ReplayType.DATA;
		if (name.endsWith(".wav"))
			return ReplayType.WAV;
		if (name.endsWith(".pcm"))
			return ReplayType.RAW;
		throw new IOException("Unsupported replay file extension");
	}

	private void playIqRecording(File file) throws IOException, InterruptedException {
		try (IQReplayFile iqFile = IQReplayFile.open(file)) {
			playbackIqFile = iqFile;
			playbackDurationMillis = iqFile.getDurationMillis();
			playbackCurrentEpochMillis = file.lastModified();
			updateFrequencySelectorForIqPlayback(iqFile);
			resetTriggerRangeToActiveFullRange();
			redrawFrequencySpectrumTable();
			fireHardwareStateChanged(true);

			int sampleRateHz = iqFile.getSampleRateHz();
			double replaySpanMHz = sampleRateHz / 1_000_000d;
			updateIqReplayRbw(replaySpanMHz);
			int lastFftSize = 0;
			boolean processingPrimed = false;
			IQSpectrumFrame spectrumFrame = null;
			byte[] iqData = new byte[0];
			long playbackDeadlineNanos = System.nanoTime();
			while (!stopSpectrumPlayback) {
				long seek = takePlaybackSeekRequest();
				if (seek >= 0) {
					iqFile.seekMillis(seek);
					playbackDeadlineNanos = System.nanoTime();
				}
				while (!stopSpectrumPlayback && parameterIsCapturingPaused.getValue()) {
					Thread.sleep(50);
					playbackDeadlineNanos = System.nanoTime();
				}
				if (stopSpectrumPlayback)
					break;

				int fftSize = IQSpectrumFrame.chooseSize(sampleRateHz, parameterIqReplayRbwHz.getValue());
				if (fftSize != lastFftSize) {
					spectrumFrame = new IQSpectrumFrame(fftSize);
					restartPlaybackProcessingThread();
					lastFftSize = fftSize;
					processingPrimed = false;
				}
				int frameSamples = Math.max(fftSize, Math.max(1, sampleRateHz / IQ_REPLAY_FRAMES_PER_SECOND));
				int frameBytes = frameSamples * 2;
				if (iqData.length != frameBytes) {
					iqData = new byte[frameBytes];
				}
				iqFile.readLoopedSigned(iqData);
				playbackPositionMillis = iqFile.getPositionMillis();
				playbackCurrentEpochMillis = file.lastModified() + playbackPositionMillis;
				waterfallPlot.setPlaybackStatus(true, playbackPositionMillis, playbackDurationMillis);
				for (IQReplayAnalyzerFeed analyzer : playbackIqAnalyzers) {
					analyzer.accept(iqData, iqData.length);
				}
				playbackDeadlineNanos += Math.round(frameSamples * 1_000_000_000d / sampleRateHz);
				if (!playbackIqAnalyzers.isEmpty()) {
					processingPrimed = false;
					playbackDeadlineNanos = paceIqReplay(playbackDeadlineNanos);
					continue;
				}

				int fftOffset = iqData.length - fftSize * 2;
				float[] spectrum = spectrumFrame.compute(iqData, fftOffset);
				float binHz = sampleRateHz / (float) fftSize;
				double firstBinHz = iqFile.getCenterFrequencyHz() - sampleRateHz / 2d + binHz * 0.5d;
				double[] frequencyStart = new double[spectrum.length];
				for (int i = 0; i < frequencyStart.length; i++) {
					frequencyStart[i] = firstBinHz + binHz * i;
				}
				FFTBins bins = new FFTBins(true, frequencyStart, binHz, spectrum);
				if (!processingPrimed) {
					hwProcessingQueue.put(bins);
					hwProcessingQueue.put(new FFTBins(true, frequencyStart, binHz, spectrum.clone()));
					processingPrimed = true;
				} else {
					if (!hwProcessingQueue.isEmpty()) {
						hwProcessingQueue.clear();
					}
					hwProcessingQueue.offer(bins);
				}
				playbackDeadlineNanos = paceIqReplay(playbackDeadlineNanos);
			}
		}
	}

	private long paceIqReplay(long deadlineNanos) throws InterruptedException {
		long now = System.nanoTime();
		long remaining = deadlineNanos - now;
		if (remaining > 0) {
			long millis = remaining / 1_000_000L;
			int nanos = (int) (remaining % 1_000_000L);
			Thread.sleep(millis, nanos);
			return deadlineNanos;
		}
		if (remaining < -250_000_000L) {
			return now;
		}
		return deadlineNanos;
	}

	private void restartPlaybackProcessingThread() throws InterruptedException {
		hwProcessingQueue.clear();
		Thread processing = threadProcessing;
		if (processing != null) {
			processing.interrupt();
			processing.join(500);
			threadProcessing = null;
		}
		ensureProcessingThreadRunning();
	}

	private void updateFrequencySelectorForIqPlayback(IQReplayFile iqFile) {
		double halfBandwidthMHz = iqFile.getSampleRateHz() / 2_000_000d;
		double centerMHz = iqFile.getCenterFrequencyHz() / 1_000_000d;
		int startMHz = Math.max(0, (int) Math.floor(centerMHz - halfBandwidthMHz));
		int stopMHz = Math.max(startMHz + 1, (int) Math.ceil(centerMHz + halfBandwidthMHz));
		parameterFrequency.setValue(new FrequencyRange(startMHz, stopMHz));
		parameterDisplayFreqRange.setValue(startMHz + "-" + stopMHz);
		setIqReplayDomainRange(iqFile);
	}

	private void setIqReplayDomainRange(IQReplayFile iqFile) {
		double halfBandwidthMHz = iqFile.getSampleRateHz() / 2_000_000d;
		double centerMHz = iqFile.getCenterFrequencyHz() / 1_000_000d;
		updateIqReplayAuxiliaryViews(centerMHz - halfBandwidthMHz, centerMHz + halfBandwidthMHz);
		SwingUtilities.invokeLater(() -> chart.getXYPlot().getDomainAxis().setRange(centerMHz - halfBandwidthMHz,
				centerMHz + halfBandwidthMHz));
	}

	private long playSpectrumRecordingFrom(File file, long startOffsetMillis) throws IOException, InterruptedException {
		try (SpectrumRecording.Reader reader = new SpectrumRecording.Reader(file)) {
			playbackHeader = reader.getHeader();
			parameterDisplayFreqRange.setValue(getActiveRangesForDisplay());
			updateFrequencySelectorForPlayback(playbackHeader);
			if (resetTriggerRangeOnPlaybackStart) {
				resetTriggerRangeToActiveFullRange();
				resetTriggerRangeOnPlaybackStart = false;
			}
			playbackCurrentEpochMillis = playbackHeader.startEpochMillis <= 0 ? 0 : playbackHeader.startEpochMillis + startOffsetMillis;
			redrawFrequencySpectrumTable();
			fireHardwareStateChanged(true);
			ensureProcessingThreadRunning();

			SpectrumRecording.Frame frame = null;
			while (!stopSpectrumPlayback && (frame = reader.readFrame()) != null) {
				if (frame.timeOffsetMillis >= startOffsetMillis)
					break;
			}
			if (frame == null)
				return -1;

			hwProcessingQueue.clear();
			offerPlaybackFrame(frame);
			offerPlaybackFrame(frame);
			SpectrumRecording.Frame previousFrame = frame;

			while (!stopSpectrumPlayback && (frame = reader.readFrame()) != null) {
				long seek = takePlaybackSeekRequest();
				if (seek >= 0)
					return seek;

				long delay = previousFrame == null ? 0 : frame.timeOffsetMillis - previousFrame.timeOffsetMillis;
				if (!sleepPlaybackDelay(delay))
					return takePlaybackSeekRequest();

				seek = takePlaybackSeekRequest();
				if (seek >= 0)
					return seek;

				offerPlaybackFrame(frame);
				previousFrame = frame;
			}
			return -1;
		}
	}

	private boolean sleepPlaybackDelay(long delayMillis) throws InterruptedException {
		if (delayMillis <= 0 || delayMillis >= 10000)
			return !stopSpectrumPlayback;
		long remaining = delayMillis;
		while (!stopSpectrumPlayback && playbackSeekRequestMillis < 0 && remaining > 0) {
			while (!stopSpectrumPlayback && playbackSeekRequestMillis < 0 && parameterIsCapturingPaused.getValue()) {
				Thread.sleep(50);
			}
			long sleep = Math.min(remaining, 50);
			Thread.sleep(sleep);
			remaining -= sleep;
		}
		return !stopSpectrumPlayback && playbackSeekRequestMillis < 0;
	}

	private long takePlaybackSeekRequest() {
		long seek = playbackSeekRequestMillis;
		if (seek >= 0) {
			playbackSeekRequestMillis = -1;
			hwProcessingQueue.clear();
		}
		return seek;
	}

	private void seekSpectrumPlayback(double fraction) {
		long duration = playbackDurationMillis;
		if (!isReplayActive() || duration <= 0)
			return;
		playbackSeekRequestMillis = Math.round(duration * Math.min(1d, Math.max(0d, fraction)));
	}

	private long readSpectrumRecordingDuration(File file) {
		long duration = 0;
		try (SpectrumRecording.Reader reader = new SpectrumRecording.Reader(file)) {
			SpectrumRecording.Frame frame;
			while ((frame = reader.readFrame()) != null) {
				duration = frame.timeOffsetMillis;
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		return duration;
	}

	private void ensureProcessingThreadRunning() {
		if (threadProcessing != null && threadProcessing.isAlive())
			return;
		threadProcessing = new Thread(() -> {
			Thread.currentThread().setName("spectrum playback data processing thread");
			try {
				processingThread();
			} catch (IOException e) {
				e.printStackTrace();
			}
		});
		threadProcessing.start();
	}

	private void offerPlaybackFrame(SpectrumRecording.Frame frame) throws InterruptedException {
		SpectrumRecording.Header header = playbackHeader;
		if (header == null)
			return;
		double[] frequencyStart = new double[frame.spectrum.length];
		for (int i = 0; i < frequencyStart.length; i++) {
			frequencyStart[i] = header.freqStartMHz * 1000000d + header.fftBinHz * (i + 0.5d);
		}
		playbackPositionMillis = frame.timeOffsetMillis;
		playbackCurrentEpochMillis = header.startEpochMillis <= 0 ? 0 : header.startEpochMillis + frame.timeOffsetMillis;
		waterfallPlot.setPlaybackStatus(true, playbackPositionMillis, playbackDurationMillis);
		hwProcessingQueue.put(new FFTBins(true, frequencyStart, header.fftBinHz, frame.spectrum.clone()));
	}
	
	private FrequencyRange getFreq() {
		return parameterFrequency.getValue();
	}

	private String getActiveRangesForDisplay() {
		SpectrumRecording.Header header = playbackHeader;
		if (header == null) {
			if (isIqReplayActive())
				return parameterDisplayFreqRange.getValue();
			return parameterFreqRange.getValue();
		}
		if (header.ranges == null || header.ranges.trim().isEmpty())
			return header.freqStartMHz + "-" + header.freqStopMHz;
		return header.ranges;
	}

	private void updateFrequencySelectorForPlayback(SpectrumRecording.Header header) {
		if (header == null)
			return;
		FrequencyRange playbackRange = getFrequencyBoundsForDisplayRange(header.ranges, header.freqStartMHz,
				header.freqStopMHz);
		parameterFrequency.setValue(playbackRange);
	}

	private FrequencyRange getFrequencyBoundsForDisplayRange(String ranges, int fallbackStartMHz, int fallbackStopMHz) {
		int[] pairs = parseRangePairs(ranges);
		if (pairs == null || pairs.length < 2)
			return new FrequencyRange(fallbackStartMHz, fallbackStopMHz);

		int minMHz = Integer.MAX_VALUE;
		int maxMHz = Integer.MIN_VALUE;
		for (int i = 0; i < pairs.length; i += 2) {
			minMHz = Math.min(minMHz, Math.min(pairs[i], pairs[i + 1]));
			maxMHz = Math.max(maxMHz, Math.max(pairs[i], pairs[i + 1]));
		}
		return new FrequencyRange(minMHz, maxMHz);
	}

	private int getActiveFreqShiftForDisplay() {
		SpectrumRecording.Header header = playbackHeader;
		return header == null ? (isIqReplayActive() ? 0 : parameterFreqShift.getValue()) : header.freqShift;
	}

	private int getActiveFreqStartMHzForDisplay() {
		SpectrumRecording.Header header = playbackHeader;
		return header == null ? getFreq().getStartMHz() : header.freqStartMHz;
	}

	private int getActiveFreqStopMHzForDisplay() {
		SpectrumRecording.Header header = playbackHeader;
		return header == null ? getFreq().getEndMHz() : header.freqStopMHz;
	}

	private void printInit(int initNumber) {
		//		System.out.println("Startup "+(initNumber++)+" in " + (System.currentTimeMillis() - initTime) + "ms");
	}

	private void processingThread() throws IOException {
		long counter = 0;
		long frameCounterChart = 0;
		boolean dcap = false;
		String dt1 = formatStatsLogTimestamp();
		String dt2 = dt1;

		//mainWhile:
		//while(true)
		{
			FFTBins bin1 = null;
			try {
				bin1 = hwProcessingQueue.take();
			} catch (InterruptedException e1) {
				return;
			}
			float binHz = bin1.fftBinWidthHz;
			SpectrumRecording.Header activePlaybackHeader = playbackHeader;
			ReplayType activePlaybackType = playbackType;
			boolean playbackMode = activePlaybackType != null;
			boolean iqPlaybackMode = activePlaybackType == ReplayType.WAV || activePlaybackType == ReplayType.RAW;
			int activeFreqStartMHz = activePlaybackHeader == null ? getFreq().getStartMHz() : activePlaybackHeader.freqStartMHz;
			int activeFreqStopMHz = activePlaybackHeader == null ? getFreq().getEndMHz() : activePlaybackHeader.freqStopMHz;
			int activeFreqShift = activePlaybackHeader == null
					? (activePlaybackType == null ? parameterFreqShift.getValue() : 0)
					: activePlaybackHeader.freqShift;
			String activeFreqRanges = activePlaybackHeader == null
					? (activePlaybackType == null ? parameterFreqRange.getValue() : parameterDisplayFreqRange.getValue())
					: activePlaybackHeader.ranges;

			/**
			 * prevents from spectrum chart from using too much CPU
			 */
			int limitChartRefreshFPS		= 30;
			int limitPersistentRefreshEveryChartFrame	= 2;
			
			//PowerCalibration calibration	 = new PowerCalibration(-45, -12.5, 40); 

			datasetSpectrum = new DatasetSpectrumPeak(binHz, activeFreqStartMHz, activeFreqStopMHz,
					spectrumInitValue, parameterPeakFallThreshold.getValue(), parameterPeakFallRateSecs.getValue() * 1000,
					parameterPeakHoldTime.getValue() * 1000, activeFreqShift, parameterAvgIterations.getValue(),
					parameterAvgOffset.getValue());
			// If multiple ranges are selected, compress X axis so gaps are removed
			int[] pairs = parseRangePairs(activeFreqRanges);
			datasetSpectrum.setActiveRangePairs(pairs);
			waterfallPlot.setRangePairs(pairs, activeFreqShift);
			persistentDisplay.setRangePairs(pairs, activeFreqShift);
			if (pairs != null && pairs.length > 2) {
				double total = totalRangesLength(pairs);
				if (!iqPlaybackMode)
					chart.getXYPlot().getDomainAxis().setRange(0, total);
				// override tick labels to map compressed X back to original frequencies (including freqShift)
				((NumberAxis)chart.getXYPlot().getDomainAxis()).setNumberFormatOverride(new java.text.NumberFormat() {
					@Override
					public StringBuffer format(double value, StringBuffer toAppendTo, java.text.FieldPosition pos) {
						double origNoShift = mapCompressedToOriginal(value, pairs);
						double label = origNoShift + activeFreqShift;
						// round to cents (hundredths) to avoid floating precision artefacts
						long centi = Math.round(label * 100);
						String s;
						Locale loc = new Locale("sk", "SK");
						if (centi % 100 == 0) {
							// integer, no decimals
							s = String.format(loc, "%.0f", label);
						} else if (centi % 10 == 0) {
							// one decimal needed
							s = String.format(loc, "%.1f", label);
						} else {
							// two decimals
							s = String.format(loc, "%.2f", label);
						}
						return toAppendTo.append(s);
					}

					@Override
					public StringBuffer format(long value, StringBuffer toAppendTo, java.text.FieldPosition pos) {
						return format((double) value, toAppendTo, pos);
					}

					@Override
					public Number parse(String source, java.text.ParsePosition parsePosition) {
						return null;
					}
				});
			} else {
				if (!iqPlaybackMode)
					chart.getXYPlot().getDomainAxis().setRange(activeFreqStartMHz + activeFreqShift,
							activeFreqStopMHz + activeFreqShift);
				((NumberAxis)chart.getXYPlot().getDomainAxis()).setNumberFormatOverride(new DecimalFormat(" #.### "));
			}
			
			XYSeries spectrumPeaksEmpty	= new XYSeries("peaks");
			XYSeries spectrumAverageEmpty	= new XYSeries("average");
			XYSeries spectrumMaxHoldEmpty	= new XYSeries("maxhold");
			XYSeries spectrumMinHoldEmpty	= new XYSeries("minhold");
			
			float maxPeakJitterdB = 6;
			float peakThresholdAboveNoise = 4;
			int maxPeakBins = 4;
			int validIterations = 25;
			spurFilter = new SpurFilter(maxPeakJitterdB, peakThresholdAboveNoise, maxPeakBins, validIterations,	datasetSpectrum);
			
			long lastChartUpdated = System.currentTimeMillis();
			long lastScanStartTime = System.currentTimeMillis();
			double lastFreq = 0;

			while (true) {
				try {
					counter++;
					FFTBins bins = hwProcessingQueue.take();
					if (parameterIsCapturingPaused.getValue() && !playbackMode)
						continue;
					
					boolean triggerChartRefresh = bins.fullSweepDone;
					//continue;
				
					if (bins.freqStart != null && bins.sigPowdBm != null) {
					//	PowerCalibration.correctPower(calibration, parameterGaindB, bins);
						if (activePlaybackType != ReplayType.DATA) {
							for (int i = 0; i < bins.sigPowdBm.length; i++) {
								bins.sigPowdBm[i] -= (30-parameterAmplitudeOffset.getValue()); //offset calibration
							}
						}
						datasetSpectrum.addNewData(bins);
					}

					if ((triggerChartRefresh/* || timeDiff > 1000 */)) {
						//						System.out.println("ctr "+counter+" dropped "+dropped);
						if (!playbackMode) {
							recordSpectrumFrame(datasetSpectrum);
						}
						/**
						 * filter first
						 */
						if (parameterSpurRemoval.getValue()) {
							long start	= System.nanoTime();
							spurFilter.filterDataset();
							synchronized (perfWatch) {
								perfWatch.spurFilter.addDrawingTime(System.nanoTime()-start);
							}
						}
						/**
						 * after filtering, calculate peak spectrum
						 */
						TriggerSettings triggerSettingsSnapshot = snapshotTriggerSettings();
						boolean triggerUsesPeaks = triggerSettingsSnapshot.enabled
								&& TriggerSettings.SOURCE_PEAKS.equals(triggerSettingsSnapshot.source);
						if (parameterShowPeaks.getValue() || triggerUsesPeaks) {
							datasetSpectrum.refreshPeakSpectrum();
						}
						evaluateTrigger(datasetSpectrum, playbackMode);
						if (parameterShowPeaks.getValue()) {
							double[] spp = datasetSpectrum.calculateSpectrumPeakPower(parameterPowerFluxCal.getValue());
							if (!parameterShowHoldMarker.getValue())
							{
									waterfallPlot.setStatusMessage(String.format(new Locale("sk","SK"), "Total Peak Power: %.1f dBm (≈ %s µW/m²)", spp[0], spp[3]),0);
									waterfallPlot.setStatusMessage(String.format(new Locale("sk","SK"), "Max: %.1f dBm @ %.2f MHz", spp[1], spp[2]),1);
								waterfallPlot.setStatusMessage(String.format(""),2);
							}
							dt1 = formatStatsLogTimestamp();
							/*
							markerFrequencyPeak.setValue(spp[2]);
							markerFrequencyPeak.setLabel(String.format("%.1f MHz", spp[2]));
							markerAmplitudePeak.setValue(spp[1]);
							markerAmplitudePeak.setLabel(String.format("%.1f dB", spp[1]));
							*/
							
							markerFrequencyPeak = spp[2];
							markerAmplitudePeak = spp[1];
							markersPeak = datasetSpectrum.calculatePeakMarkers(parameterMarkerCount.getValue());
					        
							if(parameterIsRecordedData.getValue() && dataCap != null) {
								dcap = true;
								if(!dt1.equals(dt2)) {
												dataCap.write(dt1 + ","
															+ String.format(Locale.US, "%.1f", spp[0]) + ","
															+ String.format(Locale.US, "%s", spp[3]) + ","
															+ String.format(Locale.US, "%.1f", spp[1]) + ","
															+ String.format(Locale.US, "%.2f", spp[2]) + "\r\n"
															);
									dt2 = dt1;
								}
							}
							else
							{
								if(dcap == true)
								{
									dcap = false;
									closeDataCapture();
								}
							}
						}
						
						if (parameterShowAverage.getValue()) {
							datasetSpectrum.refreshAverageSpectrum();
						}
						
						if (parameterShowMaxHold.getValue()) {
							datasetSpectrum.refreshMaxHoldSpectrum();
							if (parameterShowHoldMarker.getValue())
							{
								datasetSpectrum.refreshMinHoldSpectrum();
								double[] mh = datasetSpectrum.calculateMarkerHold(parameterPowerFluxCal.getValue());
								markerFrequencyMaxHold = mh[1];
								markerAmplitudeMaxHold = mh[0];
								markersMaxHold = datasetSpectrum.calculateMaxHoldMarkers(parameterMarkerCount.getValue());
								waterfallPlot.setStatusMessage(String.format("Total MaxHold Power: %.1f dBm (≈ %s µW/m²)", mh[2], mh[3]).replace(',', '.'),0);
								waterfallPlot.setStatusMessage(String.format("MaxHold: %.1f dBm @ %.2f MHz", mh[0], mh[1]).replace(',', '.'),1);
								waterfallPlot.setStatusMessage(String.format("Total MinHold Power: %.1f dBm (≈ %s µW/m²)", mh[4], mh[5]).replace(',', '.'),2);
							}
						}
						
						/**
						 * Update performance counters
						 */
						if (System.currentTimeMillis() - perfWatch.lastStatisticsRefreshed > 1000) {
							synchronized (perfWatch) {
//								waterfallPlot.setStatusMessage(perfWatch.generateStatistics(), 1);
								perfWatch.waterfallDraw.nanosSum	= waterfallPlot.getDrawTimeSumAndReset();
								perfWatch.waterfallDraw.count	= waterfallPlot.getDrawingCounterAndReset();
								String stats	= perfWatch.generateStatistics();
								SwingUtilities.invokeLater(() -> {
									labelMessages.setText(stats);
								});
								perfWatch.reset();
							}
						}

						boolean flagChartRedraw	= false;
						/**
						 * Update chart in the swing thread
						 */
						if (System.currentTimeMillis() - lastChartUpdated > 1000/limitChartRefreshFPS) {
							flagChartRedraw	= true;
							frameCounterChart++;
							lastChartUpdated = System.currentTimeMillis();
						}

						
						XYSeries spectrumSeries;
						XYSeries spectrumPeaks;
						XYSeries spectrumAverage;
						XYSeries spectrumMaxHold;
						XYSeries spectrumMinHold;

						spectrumSeries = datasetSpectrum.createSpectrumDataset("spectrum");

						if (parameterShowPeaks.getValue()) {
							spectrumPeaks = datasetSpectrum.createPeaksDataset("peaks");
						} else {
							spectrumPeaks = spectrumPeaksEmpty;
						}
						if (parameterShowAverage.getValue()) {
							spectrumAverage = datasetSpectrum.createAverageDataset("average");
						} else {
							spectrumAverage = spectrumAverageEmpty;
						}
						if (parameterShowMaxHold.getValue()) {
							spectrumMaxHold = datasetSpectrum.createMaxHoldDataset("maxhold");
						} else {
							spectrumMaxHold = spectrumMaxHoldEmpty;
						}
						if (parameterShowHoldMarker.getValue()) {
							spectrumMinHold = datasetSpectrum.createMinHoldDataset("minhold");
						} else {
							spectrumMinHold = spectrumMinHoldEmpty;
						}

						if (parameterPersistentDisplay.getValue()) {
							long start	= System.nanoTime();
							boolean redraw	= false;
							if (flagChartRedraw && frameCounterChart % limitPersistentRefreshEveryChartFrame == 0)
								redraw	= true;
							
							//persistentDisplay.drawSpectrumFloat
							persistentDisplay.drawSpectrum2
							(datasetSpectrum,
									(float) chart.getXYPlot().getRangeAxis().getRange().getLowerBound(),
									(float) chart.getXYPlot().getRangeAxis().getRange().getUpperBound(), redraw);
							synchronized (perfWatch) {
								perfWatch.persisentDisplay.addDrawingTime(System.nanoTime()-start);	
							}
						}

						/**
						 * do not render it in swing thread because it might
						 * miss data
						 */
						if (parameterWaterfallVisible.getValue()) {cnt++;
							long start	= System.nanoTime();
							int waterfallDivider = 11 - parameterWaterfallSpeed.getValue();
							if ((cnt % waterfallDivider) == 0) {waterfallPlot.addNewData(datasetSpectrum);}
							synchronized (perfWatch) {
								perfWatch.waterfallUpdate.addDrawingTime(System.nanoTime()-start);	
							}
						}
						
						if (flagChartRedraw) {
							if (parameterWaterfallVisible.getValue()) {
								waterfallPlot.repaint();
							}
							// prepare immutable copies to be used inside Swing runnable
							final XYSeries toAddPeaks = spectrumPeaks;
							final XYSeries toAddAverage = spectrumAverage;
							final XYSeries toAddMaxHold = spectrumMaxHold;
							final XYSeries toAddMinHold = spectrumMinHold;
							final XYSeries toAddRealtime;
							int[] pairsAdd = pairs;
							if (pairsAdd != null && pairsAdd.length > 2 && spectrumSeries instanceof XYSeriesImmutable) {
								toAddRealtime = remapSeriesToCompressed((XYSeriesImmutable) spectrumSeries, pairsAdd);
							} else {
								toAddRealtime = spectrumSeries;
							}
							final XYSeries finalPeaks = (pairsAdd != null && pairsAdd.length > 2 && toAddPeaks instanceof XYSeriesImmutable) ? remapSeriesToCompressed((XYSeriesImmutable) toAddPeaks, pairsAdd) : toAddPeaks;
							final XYSeries finalAverage = (pairsAdd != null && pairsAdd.length > 2 && toAddAverage instanceof XYSeriesImmutable) ? remapSeriesToCompressed((XYSeriesImmutable) toAddAverage, pairsAdd) : toAddAverage;
							final XYSeries finalMaxHold = (pairsAdd != null && pairsAdd.length > 2 && toAddMaxHold instanceof XYSeriesImmutable) ? remapSeriesToCompressed((XYSeriesImmutable) toAddMaxHold, pairsAdd) : toAddMaxHold;
							final XYSeries finalMinHold = (pairsAdd != null && pairsAdd.length > 2 && toAddMinHold instanceof XYSeriesImmutable) ? remapSeriesToCompressed((XYSeriesImmutable) toAddMinHold, pairsAdd) : toAddMinHold;

							SwingUtilities.invokeLater(() -> {

								chart.setNotify(false);

								chartDataset.removeAllSeries();

								chartDataset.addSeries(finalPeaks);
								chartDataset.addSeries(finalAverage);
								chartDataset.addSeries(finalMaxHold);
								chartDataset.addSeries(finalMinHold);

								if(parameterShowRealtime.getValue()) {
									chartDataset.addSeries(toAddRealtime);
								}

								chart.getXYPlot().clearAnnotations();
								ArrayList<MarkerLabelPlacement> markerLabelPlacements = new ArrayList<>();
								
								if(parameterShowPeaks.getValue() && parameterShowPeakMarker.getValue()) {
									for (DatasetSpectrumPeak.SpectrumMarker marker : markersPeak) {
										addSpectrumMarkerAnnotation(marker, colors.clime, markerLabelPlacements);
									}
								}

								if(parameterShowMaxHold.getValue() && parameterShowHoldMarker.getValue()) {
									for (DatasetSpectrumPeak.SpectrumMarker marker : markersMaxHold) {
										addSpectrumMarkerAnnotation(marker, colors.cpink, markerLabelPlacements);
									}
								}

								chart.setNotify(true);

								if (gifCap != null) {
									gifCap.captureFrame(parameterIsRecordedVideo.getValue());
								}
								if (h264Cap != null) {
									h264Cap.captureFrame(parameterIsRecordedVideo.getValue());
								}
							});
						}

						synchronized (perfWatch) {
							perfWatch.hwFullSpectrumRefreshes++;
						}

						counter = 0;
					}

				} catch (InterruptedException e) {
					return;
				}
			}

		}

	}

	private void recalculateGains(int totalGain) {
		/**
		 * use only lna gain when <=40 when >40, add only vga gain
		 */
		int lnaGain = totalGain / 8 * 8; //lna gain has step 8, range <0, 40>
		if (lnaGain > 40)
			lnaGain = 40;
		int vgaGain = lnaGain != 40 ? 0 : ((totalGain - lnaGain) & ~1); //vga gain has step 2, range <0,60>
		this.parameterGainLNA.setValue(lnaGain);
		this.parameterGainVGA.setValue(vgaGain);
		this.parameterGainTotal.setValue(lnaGain + vgaGain);
	}

	/**
	 * uses fifo queue to process launch commands, only the last launch command
	 * is important, delete others
	 */
	private synchronized void restartHackrfSweep() {
		if (isReplayActive())
			return;
		if (threadLaunchCommands.offer(0) == false) {
			threadLaunchCommands.clear();
			threadLaunchCommands.offer(0);
		}
	}

	private String formatRecordingRangeName() {
		SpectrumRecording.Header header = playbackHeader;
		if (header != null) {
			String ranges = header.ranges == null || header.ranges.trim().isEmpty()
					? header.freqStartMHz + "-" + header.freqStopMHz
					: header.ranges;
			return formatRecordingRangeName(ranges, header.freqStartMHz, header.freqStopMHz, header.freqShift);
		}
		return formatRecordingRangeName(parameterFreqRange.getValue(), getFreq().getStartMHz(), getFreq().getEndMHz(),
				parameterFreqShift.getValue());
	}

	private String formatVideoRecordingFilePrefix() {
		String area = parameterVideoArea.getValue();
		if ("SPEC+WF".equals(area))
			return "# VIDEO SPEC+WF ";
		if ("FULLSCR".equals(area))
			return "# VIDEO FULLSCR ";
		return "# VIDEO SPEC ";
	}

	private String formatRecordingRangeName(DatasetSpectrumPeak spectrum) {
		SpectrumRecording.Header header = playbackHeader;
		String ranges = header == null ? parameterFreqRange.getValue() : header.ranges;
		return formatRecordingRangeName(ranges, spectrum.getFreqStartMHz(), spectrum.getFreqStopMHz(), spectrum.getFreqShift());
	}

	private String formatRecordingRangeName(String ranges, int fallbackStartMHz, int fallbackStopMHz, int shift) {
		int[] pairs = parseRangePairs(ranges);
		if (pairs != null && pairs.length > 2) {
			StringBuilder b = new StringBuilder();
			for (int i = 0; i < pairs.length; i += 2) {
				if (b.length() > 0)
					b.append("+");
				b.append(pairs[i] + shift).append("-").append(pairs[i + 1] + shift);
			}
			return b.toString();
		}
		return (fallbackStartMHz + shift) + "-" + (fallbackStopMHz + shift);
	}

	private boolean isReplayActive() {
		return playbackType != null;
	}

	private boolean isDataReplayActive() {
		return playbackType == ReplayType.DATA;
	}

	private boolean isIqReplayActive() {
		return playbackType == ReplayType.WAV || playbackType == ReplayType.RAW;
	}

	private void setChartLiveGesturesEnabled(boolean enabled) {
		SwingUtilities.invokeLater(() -> {
			chartPanel.setMouseWheelEnabled(enabled);
			chartPanel.setDomainZoomable(enabled);
			chartPanel.setRangeZoomable(false);
			if (!enabled)
				dragging = false;
		});
	}

	/**
	 * no need to synchronize, executes only in the launcher thread
	 */
	private void restartHackrfSweepExecute() {
		if (isReplayActive())
			return;
		stopHackrfSweep();
		threadHackrfSweep = new Thread(() -> {
			Thread.currentThread().setName("hackrf_sweep");
			try {
				forceStopSweep = false;
				sweep();
			} catch (IOException e) {
				e.printStackTrace();
			}
		});
		threadHackrfSweep.start();
	}

	private XYLineAndShapeRenderer createSpectrumRenderer(boolean spline) {
		XYLineAndShapeRenderer renderer = spline ? new XYSplineRenderer() : new XYLineAndShapeRenderer();
		configureSpectrumRenderer(renderer);
		return renderer;
	}

	private void configureSpectrumRenderer(XYLineAndShapeRenderer renderer) {
		renderer.setBaseShapesVisible(false);
		renderer.setBaseStroke(new BasicStroke(parameterSpectrumLineThickness.getValue().floatValue()));
		renderer.setAutoPopulateSeriesStroke(false);
		renderer.setAutoPopulateSeriesPaint(false);
		renderer.setSeriesPaint(0, colors.cgreen);  //peak
		renderer.setSeriesPaint(1, colors.cyellow);  //avg
		renderer.setSeriesPaint(2, colors.cred);  //maxhold
		renderer.setSeriesPaint(3, colors.blue);  //minhold
		renderer.setSeriesPaint(4, colors.cwhite);  //realtime
		renderer.setBasePaint(Color.white);
	}

	private void setSpectrumSplineRenderer(boolean spline) {
		SwingUtilities.invokeLater(() -> {
			chartLineRenderer = createSpectrumRenderer(spline);
			chart.getXYPlot().setRenderer(chartLineRenderer);
		});
	}

	private void setupChart() {
		int axisWidthLeft = 60;
		int axisWidthRight = 40;

		chart = ChartFactory.createXYLineChart("Spectrum analyzer", "Frequency (MHz)", "Amplitude (dBm)", chartDataset,
				PlotOrientation.VERTICAL, false, false, false);
		chart.getRenderingHints().put(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

		XYPlot plot = chart.getXYPlot();
		NumberAxis domainAxis = ((NumberAxis) plot.getDomainAxis());
		NumberAxis rangeAxis = ((NumberAxis) plot.getRangeAxis());

		chartLineRenderer = createSpectrumRenderer(parameterSpectrumSpline.getValue());

		rangeAxis.setAutoRange(false);
		rangeAxis.setRange(-100, -10); //amplitude range
		rangeAxis.setTickUnit(new NumberTickUnit(10, new DecimalFormat("###")));

		domainAxis.setNumberFormatOverride(new DecimalFormat(" #.### "));

		plot.setDomainGridlinesVisible(true);
		plot.setRenderer(chartLineRenderer);

		/**
		 * sets empty space around the plot
		 */
		AxisSpace axisSpace = new AxisSpace();
		axisSpace.setLeft(axisWidthLeft);
		axisSpace.setRight(axisWidthRight);
		axisSpace.setTop(0);
		axisSpace.setBottom(50);
		plot.setFixedDomainAxisSpace(axisSpace);//sets width of the domain axis left/right
		plot.setFixedRangeAxisSpace(axisSpace);//sets heigth of range axis top/bottom

		rangeAxis.setAxisLineVisible(false);
		rangeAxis.setTickMarksVisible(false);

		plot.setAxisOffset(RectangleInsets.ZERO_INSETS); //no space between range axis and plot

		Font labelFont = new Font(Font.SANS_SERIF, Font.BOLD, 14);
		rangeAxis.setLabelFont(labelFont);
		rangeAxis.setTickLabelFont(labelFont);
		rangeAxis.setLabelPaint(colors.cwhite);
		rangeAxis.setTickLabelPaint(colors.cwhite);
		domainAxis.setLabelFont(labelFont);
		domainAxis.setTickLabelFont(labelFont);
		domainAxis.setLabelPaint(colors.cwhite);
		domainAxis.setTickLabelPaint(colors.cwhite);
		plot.setBackgroundPaint(colors.palette4);
		chart.setBackgroundPaint(colors.palette4);
		//plot.setDomainPannable(true); //pan with CTRL key

		chartPanel = new ChartPanel(chart);
		chartPanel.setMaximumDrawWidth(4096);
		chartPanel.setMaximumDrawHeight(2160);
		chartPanel.setMouseWheelEnabled(true);
		//chartPanel.setMouseZoomable(true);
		chartPanel.setDomainZoomable(true);
		chartPanel.setRangeZoomable(false);
		chartPanel.setPopupMenu(null);
		chartPanel.setMinimumSize(new Dimension(200, 200));

		printInit(1);
		
		/**
		 * Draws overlay of waterfall's color scale next to main spectrum chart
		 * to show
		 */
		if(true)
		chartPanel.addOverlay(new Overlay() {
			@Override
			public void addChangeListener(OverlayChangeListener listener) {
			}

			@Override
			public void paintOverlay(Graphics2D g, ChartPanel chartPanel) {
				if(parameterIsRecordedVideo.getValue()) { return; }
				Rectangle2D area = chartPanel.getChartRenderingInfo().getPlotInfo().getDataArea();
				int plotStartX = (int) area.getX();
				int plotWidth = (int) area.getWidth();

				Rectangle2D subplotArea = chartPanel.getChartRenderingInfo().getPlotInfo().getDataArea();

				int y1 = (int) plot.getRangeAxis().valueToJava2D(waterfallPlot.getSpectrumPaletteStart(), subplotArea,
						plot.getRangeAxisEdge());
				int y2 = (int) plot.getRangeAxis().valueToJava2D(
						waterfallPlot.getSpectrumPaletteStart() + waterfallPlot.getSpectrumPaletteSize(), subplotArea,
						plot.getRangeAxisEdge());

				int x = plotStartX + plotWidth + 20;
				int w = 10;
				int h = y1 - y2;
				waterfallPlot.drawScale(g, x, y2, w, h);
			}

			@Override
			public void removeChangeListener(OverlayChangeListener listener) {
			}
		});

		/**
		 * Draw frequency bands as an overlay
		 */
		if (true)
		chartPanel.addOverlay(new Overlay() {
			@Override
			public void addChangeListener(OverlayChangeListener listener) {
			}

			@Override
			public void paintOverlay(Graphics2D g2, ChartPanel chartPanel) {
				Rectangle2D area = chartPanel.getChartRenderingInfo().getPlotInfo().getDataArea();
				BufferedImage img = imageFrequencyAllocationTableBands;
				if (img != null) {
					g2.drawImage(img, (int) area.getX(), (int) area.getY()+20, null);
				}
				drawCompressedRangeSeparators(g2, area, parseRangePairs(getActiveRangesForDisplay()), (int) area.getY() + 20,
						(int) (area.getY() + area.getHeight()));
			}

			@Override
			public void removeChangeListener(OverlayChangeListener listener) {
			}
		});
		
		chartPanel.addOverlay(new Overlay() {
			@Override
			public void addChangeListener(OverlayChangeListener listener) {
			}

			@Override
			public void paintOverlay(Graphics2D g2, ChartPanel chartPanel) {
				drawIQSelectionOverlay(g2);
				drawIqReplayZoomOverlay(g2);
			}

			@Override
			public void removeChangeListener(OverlayChangeListener listener) {
			}
		});

		/**
		 * Date Time overlay
		 */
		if(true)
		chartPanel.addOverlay(new Overlay() {
			@Override
			public void addChangeListener(OverlayChangeListener listener) {
			}

			@Override
			public void paintOverlay(Graphics2D g2, ChartPanel chartPanel) {
					g2.setColor(Color.gray);
					DateTimeFormatter dtFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");
					if(parameterDatestamp.getValue()) {
						long playbackTime = playbackCurrentEpochMillis;
						String text;
						if (isReplayActive() && playbackTime > 0) {
							text = LocalDateTime.ofInstant(Instant.ofEpochMilli(playbackTime), ZoneId.systemDefault()).format(dtFormat);
						} else {
							text = LocalDateTime.now().format(dtFormat);
						}
						g2.drawString(text, 20, 15);
					}
					Rectangle2D area = chartPanel.getChartRenderingInfo().getPlotInfo().getDataArea();
					int indicatorY = 15;
					boolean paused = parameterIsCapturingPaused.getValue();
					ReplayType activeReplayType = playbackType;
					boolean replay = activeReplayType != null;
					String statusText = replay
							? "REPLAY " + activeReplayType.name() + " FILE" + (paused ? " PAUSED" : "")
							: (paused ? "LIVE PAUSED" : "LIVE");
					boolean showLiveRecordingDot = !replay && !paused && parameterIsRecordedSpectrum.getValue();
					int symbolWidth = replay || showLiveRecordingDot ? 16 : 0;
					int statusWidth = g2.getFontMetrics().stringWidth(statusText);
					int indicatorX = (int) area.getMaxX() - symbolWidth - statusWidth - 8;

					if (replay) {
						g2.setColor(new Color(0x4FAF4F));
						int[] xs = { indicatorX, indicatorX, indicatorX + 10 };
						int[] ys = { indicatorY - 9, indicatorY + 1, indicatorY - 4 };
						g2.fillPolygon(xs, ys, 3);
					} else if (showLiveRecordingDot) {
						g2.setColor(new Color(0xB84A4A));
						g2.fillOval(indicatorX, indicatorY - 9, 9, 9);
					}
					g2.setColor(Color.gray);
					g2.drawString(statusText, indicatorX + symbolWidth, indicatorY);
			}

			@Override
			public void removeChangeListener(OverlayChangeListener listener) {
			}
		});
		

		/**
		 * Mouse Cross overlay
		 */

		
		/**
		 * monitors chart data area for change due to no other way to extract
		 * that info from jfreechart when it changes
		 */

		chart.addChangeListener(event -> {
			Rectangle2D aN = chartPanel.getChartRenderingInfo().getPlotInfo().getDataArea();
			Rectangle2D aO = chartDataArea.getValue();
			if (aO.getX() != aN.getX() || aO.getY() != aN.getY() || aO.getWidth() != aN.getWidth()
					|| aO.getHeight() != aN.getHeight()) {
				chartDataArea.setValue(new Rectangle2D.Double(aN.getX(), aN.getY(), aN.getWidth(), aN.getHeight()));
			}
		});

		chart.addProgressListener(new ChartProgressListener() {
			private long chartRedrawStarted;

			@Override
			public void chartProgress(ChartProgressEvent arg0) {
				if (arg0.getType() == ChartProgressEvent.DRAWING_STARTED) {
					chartRedrawStarted = System.nanoTime();
				} else if (arg0.getType() == ChartProgressEvent.DRAWING_FINISHED) {
					synchronized (perfWatch) {
						perfWatch.chartDrawing.addDrawingTime(System.nanoTime() - chartRedrawStarted);
					}
				}
			}
		});
		
		
	}

	private void drawCompressedRangeSeparators(Graphics2D g2, Rectangle2D area, int[] pairs, int yStart, int yEnd) {
		if (pairs == null || pairs.length <= 2)
			return;
		double totalLen = totalRangesLength(pairs);
		if (totalLen <= 0)
			return;
		double cum = 0;
		java.awt.Stroke oldStroke = g2.getStroke();
		java.awt.Color oldColor = g2.getColor();
		float[] dash = {4f, 4f};
		g2.setStroke(new java.awt.BasicStroke(1f, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER, 1.0f, dash, 0f));
		g2.setColor(new Color(255, 255, 255, 120));
		for (int i = 0; i < pairs.length; i += 2) {
			double len = pairs[i + 1] - pairs[i];
			cum += len;
			if (i < pairs.length - 2) {
				int sepX = (int) Math.round(area.getX() + (cum / totalLen) * area.getWidth());
				g2.drawLine(sepX, yStart, sepX, yEnd);
			}
		}
		g2.setStroke(oldStroke);
		g2.setColor(oldColor);
	}

	private void drawIQSelectionOverlay(Graphics2D g2) {
		IQSelection selection = iqSelection;
		if (selection == null)
			return;
		Rectangle2D area = chartPanel.getChartRenderingInfo().getPlotInfo().getDataArea();
		if (area == null || area.getWidth() <= 0 || area.getHeight() <= 0)
			return;
		XYPlot plot = chart.getXYPlot();
		double x1Value = selection.compressedStartMHz;
		double x2Value = selection.compressedStopMHz;
		double x1 = plot.getDomainAxis().valueToJava2D(x1Value, area, plot.getDomainAxisEdge());
		double x2 = plot.getDomainAxis().valueToJava2D(x2Value, area, plot.getDomainAxisEdge());
		int x = (int) Math.round(Math.min(x1, x2));
		int width = (int) Math.max(1, Math.round(Math.abs(x2 - x1)));
		int y = (int) area.getY();
		int height = (int) area.getHeight();

		java.awt.Composite oldComposite = g2.getComposite();
		java.awt.Stroke oldStroke = g2.getStroke();
		Color oldColor = g2.getColor();
		g2.setComposite(java.awt.AlphaComposite.SrcOver.derive(0.34f));
		g2.setColor(new Color(0x8b0000));
		g2.fillRect(x, y, width, height);
		g2.setComposite(java.awt.AlphaComposite.SrcOver.derive(0.85f));
		g2.setStroke(new BasicStroke(2f));
		g2.drawRect(x, y, width, height);
		g2.setComposite(oldComposite);

		String text = String.format(Locale.US, "IQ %.3f-%.3f MHz   BW %.3f MHz",
				Math.min(selection.displayStartMHz, selection.displayStopMHz),
				Math.max(selection.displayStartMHz, selection.displayStopMHz), selection.getBandwidthMHz());
		g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
		int textWidth = g2.getFontMetrics().stringWidth(text);
		int textX = Math.max((int) area.getX() + 4,
				Math.min(x + 4, (int) area.getMaxX() - textWidth - 4));
		int textY = y + 18;
		g2.setColor(new Color(0, 0, 0, 150));
		g2.fillRect(textX - 3, textY - 13, textWidth + 6, 17);
		g2.setColor(Color.white);
		g2.drawString(text, textX, textY);
		g2.setStroke(oldStroke);
		g2.setColor(oldColor);
	}

	private void drawIqReplayZoomOverlay(Graphics2D g2) {
		if (Double.isNaN(dragZoomAnchorMHz) || Double.isNaN(dragZoomCurrentMHz)) {
			return;
		}
		Rectangle2D area = chartPanel.getChartRenderingInfo().getPlotInfo().getDataArea();
		if (area == null || area.getWidth() <= 0 || area.getHeight() <= 0) {
			return;
		}
		XYPlot plot = chart.getXYPlot();
		double lower = Math.min(dragZoomAnchorMHz, dragZoomCurrentMHz);
		double upper = Math.max(dragZoomAnchorMHz, dragZoomCurrentMHz);
		double x1 = plot.getDomainAxis().valueToJava2D(lower, area, plot.getDomainAxisEdge());
		double x2 = plot.getDomainAxis().valueToJava2D(upper, area, plot.getDomainAxisEdge());
		int x = (int) Math.round(Math.min(x1, x2));
		int width = (int) Math.max(1, Math.round(Math.abs(x2 - x1)));
		int y = (int) area.getY();
		int height = (int) area.getHeight();

		java.awt.Composite oldComposite = g2.getComposite();
		java.awt.Stroke oldStroke = g2.getStroke();
		Color oldColor = g2.getColor();
		g2.setComposite(java.awt.AlphaComposite.SrcOver.derive(0.25f));
		g2.setColor(new Color(0x2277cc));
		g2.fillRect(x, y, width, height);
		g2.setComposite(java.awt.AlphaComposite.SrcOver.derive(0.9f));
		g2.setStroke(new BasicStroke(2f));
		g2.drawRect(x, y, width, height);
		g2.setComposite(oldComposite);
		g2.setStroke(oldStroke);
		g2.setColor(oldColor);
	}

	private boolean isInPlotArea(int mouseX, int mouseY) {
		Rectangle2D area = chartPanel.getChartRenderingInfo().getPlotInfo().getDataArea();
		return area != null && area.contains(mouseX, mouseY);
	}

	private boolean isInPlotRow(int mouseY) {
		Rectangle2D area = chartPanel.getChartRenderingInfo().getPlotInfo().getDataArea();
		return area != null && mouseY >= area.getMinY() && mouseY <= area.getMaxY();
	}

	private int clampMouseXToPlot(int mouseX) {
		Rectangle2D area = chartPanel.getChartRenderingInfo().getPlotInfo().getDataArea();
		if (area == null || area.getWidth() <= 0) {
			return mouseX;
		}
		return (int) Math.round(Math.max(area.getMinX(), Math.min(area.getMaxX(), mouseX)));
	}

	private double mouseToDomainMHz(int mouseX) {
		XYPlot plot = chart.getXYPlot();
		return plot.getDomainAxis().java2DToValue(clampMouseXToPlot(mouseX),
				chartPanel.getChartRenderingInfo().getPlotInfo().getDataArea(), plot.getDomainAxisEdge());
	}

	private double domainToSelectionMHz(double domainMHz, int[] activePairs) {
		if (isMultiRange(activePairs))
			return mapCompressedToOriginal(domainMHz, activePairs);
		return domainMHz;
	}

	private void startDragZoom(MouseEvent event) {
		dragZoomAnchorMHz = mouseToDomainMHz(event.getX());
		dragZoomCurrentMHz = dragZoomAnchorMHz;
		dragZoomAnchorX = clampMouseXToPlot(event.getX());
	}

	private void clearDragZoom() {
		dragZoomAnchorMHz = Double.NaN;
		dragZoomCurrentMHz = Double.NaN;
		dragZoomAnchorX = -1;
	}

	private void applyLiveDragZoom(double firstDomainMHz, double secondDomainMHz) {
		int[] activePairs = parseRangePairs(parameterFreqRange.getValue());
		double lowerDomain = Math.min(firstDomainMHz, secondDomainMHz);
		double upperDomain = Math.max(firstDomainMHz, secondDomainMHz);
		double lowerMHz = domainToSelectionMHz(lowerDomain, activePairs);
		double upperMHz = domainToSelectionMHz(upperDomain, activePairs);
		int newLowerX = clampFrequencyMHz((int) Math.round(Math.min(lowerMHz, upperMHz)));
		int newUpperX = clampFrequencyMHz((int) Math.round(Math.max(lowerMHz, upperMHz)));
		if (newUpperX - newLowerX < 1) {
			newUpperX = Math.min(7200, newLowerX + 1);
			if (newUpperX - newLowerX < 1) {
				newLowerX = Math.max(0, newUpperX - 1);
			}
		}
		int[] newChartParams = setupChartParams(newUpperX - newLowerX);
		parameterFrequency.setValue(new FrequencyRange(newLowerX, newUpperX));
		if (isMultiRange(activePairs)) {
			parameterFreqRange.setValue(newLowerX + "-" + newUpperX);
		}
		parameterFFTBinHz.setValue(newChartParams[0]);
		parameterAmplitudeOffset.setValue(newChartParams[1]);
		restartHackrfSweep();
	}

	private double selectionToCompressedMHz(double selectionMHz, int[] activePairs) {
		if (isMultiRange(activePairs))
			return mapRealToCompressed(selectionMHz, activePairs);
		return selectionMHz;
	}

	private IQSelection startIQSelection(MouseEvent e) {
		int[] activePairs = parseRangePairs(getActiveRangesForDisplay());
		double anchorMHz = domainToSelectionMHz(mouseToDomainMHz(e.getX()), activePairs);
		double[] subRange = null;
		if (isIqReplayActive() && playbackIqFile != null) {
			subRange = getIqReplaySourceBounds(playbackIqFile);
			if (anchorMHz < subRange[0] || anchorMHz > subRange[1]) {
				return null;
			}
		} else if (isMultiRange(activePairs)) {
			int[] selectedRange = findRangeForFrequency(anchorMHz, activePairs);
			subRange = new double[] { selectedRange[0], selectedRange[1] };
		}
		if (subRange == null) {
			double lower = chart.getXYPlot().getDomainAxis().getLowerBound();
			double upper = chart.getXYPlot().getDomainAxis().getUpperBound();
			subRange = new double[] { Math.min(lower, upper), Math.max(lower, upper) };
		}
		anchorMHz = Math.max(subRange[0], Math.min(subRange[1], anchorMHz));
		IQSelection selection = new IQSelection(anchorMHz, subRange);
		updateIQSelection(selection, e.getX());
		return selection;
	}

	private void updateIQSelection(IQSelection selection, int mouseX) {
		int[] activePairs = parseRangePairs(getActiveRangesForDisplay());
		double currentMHz = domainToSelectionMHz(mouseToDomainMHz(mouseX), activePairs);
		double minMHz = Math.max(selection.subRange[0], selection.anchorMHz - IQ_SELECTION_MAX_BW_MHZ);
		double maxMHz = Math.min(selection.subRange[1], selection.anchorMHz + IQ_SELECTION_MAX_BW_MHZ);
		currentMHz = Math.max(minMHz, Math.min(maxMHz, currentMHz));
		selection.startMHz = Math.min(selection.anchorMHz, currentMHz);
		selection.stopMHz = Math.max(selection.anchorMHz, currentMHz);

		int shift = isMultiRange(activePairs) ? getActiveFreqShiftForDisplay() : 0;
		selection.displayStartMHz = selection.startMHz + shift;
		selection.displayStopMHz = selection.stopMHz + shift;
		selection.compressedStartMHz = selectionToCompressedMHz(selection.startMHz, activePairs);
		selection.compressedStopMHz = selectionToCompressedMHz(selection.stopMHz, activePairs);
		chartPanel.repaint();
	}

	private void openIQAnalyzerForSelection(IQSelection selection) {
		if (selection == null || selection.getBandwidthMHz() < IQ_SELECTION_MIN_BW_MHZ)
			return;
		double displayStart = Math.min(selection.displayStartMHz, selection.displayStopMHz);
		double displayStop = Math.max(selection.displayStartMHz, selection.displayStopMHz);
		double centerMHz = (displayStart + displayStop) * 0.5d;
		double bandwidthMHz = Math.min(IQ_SELECTION_MAX_BW_MHZ, displayStop - displayStart);
		long centerHz = Math.round(centerMHz * 1_000_000d);
		int bandwidthHz = Math.max(1, (int) Math.round(bandwidthMHz * 1_000_000d));
		int sampleRateHz = chooseIQSampleRateHz(bandwidthHz);
		int lnaGain = parameterGainLNA.getValue();
		int vgaGain = 32;
		boolean rfAmp = parameterAntennaLNA.getValue();
		IQReplayFile iqReplay = playbackIqFile;
		if (iqReplay != null && isIqReplayActive()) {
			long sourceCenterHz = iqReplay.getCenterFrequencyHz();
			int sourceSampleRateHz = iqReplay.getSampleRateHz();
			long channelOffsetHz = centerHz - sourceCenterHz;
			int outputRateHz = chooseReplayIqOutputRate(sourceSampleRateHz, bandwidthHz);
			SwingUtilities.invokeLater(() -> {
				IQAnalyzerApp analyzer = new IQAnalyzerApp();
				IQReplayAnalyzerFeed feed = new IQReplayAnalyzerFeed(analyzer, sourceSampleRateHz, channelOffsetHz,
						bandwidthHz, outputRateHz);
				playbackIqAnalyzers.add(feed);
				analyzer.showExternal(centerHz, feed.outputSampleRateHz, 0, bandwidthHz,
						() -> {
							playbackIqAnalyzers.remove(feed);
							feed.close();
						});
			});
			return;
		}

		new Thread(() -> {
			stopHackrfSweep();
			SwingUtilities.invokeLater(() -> new IQAnalyzerApp().show(centerHz, sampleRateHz, lnaGain, vgaGain, rfAmp,
					0, bandwidthHz, this::restartHackrfSweep));
		}, "open-iq-analyzer").start();
	}

	private int chooseIQSampleRateHz(int bandwidthHz) {
		int[] rates = { 2_000_000, 4_000_000, 6_000_000, 8_000_000, 10_000_000, 12_500_000, 16_000_000, 20_000_000 };
		for (int rate : rates) {
			if (rate >= bandwidthHz)
				return rate;
		}
		return 20_000_000;
	}

	private int chooseReplayIqOutputRate(int sourceSampleRateHz, int bandwidthHz) {
		int requested = Math.max(48_000, (int) Math.ceil(bandwidthHz * 1.25d));
		if (requested >= sourceSampleRateHz) {
			return sourceSampleRateHz;
		}
		int decimation = Math.max(1, sourceSampleRateHz / requested);
		return sourceSampleRateHz / decimation;
	}

	private void updateIqReplayRbw(double visibleSpanMHz) {
		if (!isIqReplayActive()) {
			return;
		}
		int targetRbwHz;
		if (visibleSpanMHz <= 0.05d) {
			targetRbwHz = IQ_REPLAY_MIN_TARGET_RBW_HZ;
		} else if (visibleSpanMHz <= 0.1d) {
			targetRbwHz = 100;
		} else if (visibleSpanMHz <= 0.2d) {
			targetRbwHz = 200;
		} else if (visibleSpanMHz <= 0.5d) {
			targetRbwHz = 500;
		} else if (visibleSpanMHz <= 1d) {
			targetRbwHz = 1_000;
		} else if (visibleSpanMHz < 6d) {
			targetRbwHz = 3_000;
		} else if (visibleSpanMHz <= 12d) {
			targetRbwHz = 10_000;
		} else {
			targetRbwHz = 20_000;
		}
		if (parameterIqReplayRbwHz.getValue() != targetRbwHz) {
			parameterIqReplayRbwHz.setValue(targetRbwHz);
		}
	}

	private double[] getIqReplaySourceBounds(IQReplayFile iqFile) {
		double centerMHz = iqFile.getCenterFrequencyHz() / 1_000_000d;
		double halfSpanMHz = iqFile.getSampleRateHz() / 2_000_000d;
		return new double[] { centerMHz - halfSpanMHz, centerMHz + halfSpanMHz };
	}

	private double[] getIqReplayViewportBounds(IQReplayFile iqFile) {
		double centerMHz = iqFile.getCenterFrequencyHz() / 1_000_000d;
		double halfSpanMHz = iqFile.getSampleRateHz() / 2_000_000d
				* IQ_REPLAY_MAX_VIEW_SPAN_MULTIPLIER;
		return new double[] { centerMHz - halfSpanMHz, centerMHz + halfSpanMHz };
	}

	private void applyIqReplayDomainRange(double requestedLower, double requestedUpper) {
		IQReplayFile iqFile = playbackIqFile;
		if (iqFile == null) {
			return;
		}
		double[] viewportBounds = getIqReplayViewportBounds(iqFile);
		double viewportLower = viewportBounds[0];
		double viewportUpper = viewportBounds[1];
		double lower = Math.max(viewportLower, Math.min(requestedLower, requestedUpper));
		double upper = Math.min(viewportUpper, Math.max(requestedLower, requestedUpper));
		double minimumSpan = Math.max(0.0002d, parameterIqReplayRbwHz.getValue() / 1_000_000d * 8d);
		if (upper - lower < minimumSpan) {
			double center = (lower + upper) * 0.5d;
			lower = center - minimumSpan * 0.5d;
			upper = center + minimumSpan * 0.5d;
			if (lower < viewportLower) {
				upper += viewportLower - lower;
				lower = viewportLower;
			}
			if (upper > viewportUpper) {
				lower -= upper - viewportUpper;
				upper = viewportUpper;
			}
		}
		chart.getXYPlot().getDomainAxis().setRange(lower, upper);
		updateIqReplayAuxiliaryViews(lower, upper);
		updateIqReplayRbw(upper - lower);
	}

	private void updateIqReplayAuxiliaryViews(double lowerMHz, double upperMHz) {
		waterfallPlot.setFrequencyBounds(lowerMHz, upperMHz);
		persistentDisplay.setFrequencyBounds(lowerMHz, upperMHz);
	}

	private void panIqReplayDomain(int currentMouseX) {
		IQReplayFile iqFile = playbackIqFile;
		if (iqFile == null || iqReplayPanLastX < 0) {
			return;
		}
		Rectangle2D area = chartPanel.getChartRenderingInfo().getPlotInfo().getDataArea();
		if (area == null || area.getWidth() <= 0) {
			return;
		}
		XYPlot plot = chart.getXYPlot();
		double lower = plot.getDomainAxis().getLowerBound();
		double upper = plot.getDomainAxis().getUpperBound();
		double span = upper - lower;
		double deltaMHz = (iqReplayPanLastX - currentMouseX) * span / area.getWidth();
		double[] viewportBounds = getIqReplayViewportBounds(iqFile);
		double viewportLower = viewportBounds[0];
		double viewportUpper = viewportBounds[1];
		double nextLower = lower + deltaMHz;
		double nextUpper = upper + deltaMHz;
		if (nextLower < viewportLower) {
			nextUpper += viewportLower - nextLower;
			nextLower = viewportLower;
		}
		if (nextUpper > viewportUpper) {
			nextLower -= nextUpper - viewportUpper;
			nextUpper = viewportUpper;
		}
		plot.getDomainAxis().setRange(nextLower, nextUpper);
		iqReplayPanLastX = currentMouseX;
	}

	private void zoomIqReplayDomain(MouseWheelEvent event) {
		IQReplayFile iqFile = playbackIqFile;
		if (iqFile == null)
			return;
		XYPlot plot = chart.getXYPlot();
		double lower = plot.getDomainAxis().getLowerBound();
		double upper = plot.getDomainAxis().getUpperBound();
		double cursor = mouseToDomainMHz(event.getX());
		double factor = event.getWheelRotation() < 0 ? 0.65d : 1d / 0.65d;
		double newLower = cursor - (cursor - lower) * factor;
		double newUpper = cursor + (upper - cursor) * factor;
		double minimumSpan = Math.max(0.0002d, parameterIqReplayRbwHz.getValue() / 1_000_000d * 8d);
		if (newUpper - newLower < minimumSpan) {
			newLower = cursor - minimumSpan / 2d;
			newUpper = cursor + minimumSpan / 2d;
		}
		applyIqReplayDomainRange(newLower, newUpper);
	}
	
	/**
	 * Displays a cross marker with current frequency and signal strength when
	 * mouse hovers over the frequency chart
	 */
	private void setupChartMouseMarkers() {
		ValueMarker freqMarker = new ValueMarker(0, Color.WHITE, new BasicStroke(1f));
		freqMarker.setLabelPaint(Color.white);
		freqMarker.setLabelAnchor(RectangleAnchor.TOP_RIGHT);
		freqMarker.setLabelTextAnchor(TextAnchor.TOP_LEFT);
		freqMarker.setLabelFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
		ValueMarker signalMarker = new ValueMarker(0, Color.WHITE, new BasicStroke(1f));
		signalMarker.setLabelPaint(Color.white);
		signalMarker.setLabelAnchor(RectangleAnchor.TOP_RIGHT);
		signalMarker.setLabelTextAnchor(TextAnchor.BOTTOM_RIGHT);
		signalMarker.setLabelFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));

		chartPanel.addMouseMotionListener(new MouseMotionAdapter() {
			DecimalFormat format = new DecimalFormat("0.#");

			@Override
			public void mouseMoved(MouseEvent e) {
				//mouseX = e.getX();
				//mouseY = e.getY();

				int x = e.getX();
				int y = e.getY();

				XYPlot plot = chart.getXYPlot();
				Rectangle2D subplotArea = chartPanel.getChartRenderingInfo().getPlotInfo().getDataArea();
				double crosshairRange = plot.getRangeAxis().java2DToValue(y, subplotArea, plot.getRangeAxisEdge());
				signalMarker.setValue(crosshairRange);
				signalMarker.setLabel(String.format("%.1f dB", crosshairRange));
				double crosshairDomain = plot.getDomainAxis().java2DToValue(x, subplotArea, plot.getDomainAxisEdge());
				// if compressed ranges are active, map compressed X back to original frequency for display
				int[] pairsForLabel = parseRangePairs(getActiveRangesForDisplay());
				double displayFreqMHz;
				if (pairsForLabel != null && pairsForLabel.length > 2) {
					double origNoShift = mapCompressedToOriginal(crosshairDomain, pairsForLabel);
					displayFreqMHz = origNoShift + getActiveFreqShiftForDisplay();
				} else {
					displayFreqMHz = crosshairDomain;
				}
				freqMarker.setValue(crosshairDomain);
				freqMarker.setLabel(String.format(new Locale("sk","SK"), "%.2f MHz", displayFreqMHz));
				// determine label side based on mouse position within plot area (works with freqShift and compressed axis)
				double relPos = 0.5;
				try {
					Rectangle2D subplotArea2 = chartPanel.getChartRenderingInfo().getPlotInfo().getDataArea();
					relPos = (e.getX() - subplotArea2.getX()) / subplotArea2.getWidth();
				} catch (Exception ex) {
					// fallback to center
				}
				if (relPos > 0.9) {
					freqMarker.setLabelAnchor(RectangleAnchor.TOP_LEFT);
					freqMarker.setLabelTextAnchor(TextAnchor.TOP_RIGHT);
				} else if (relPos < 0.1) {
					freqMarker.setLabelAnchor(RectangleAnchor.TOP_RIGHT);
					freqMarker.setLabelTextAnchor(TextAnchor.TOP_LEFT);
				} else {
					// default centered side
					freqMarker.setLabelAnchor(RectangleAnchor.TOP_RIGHT);
					freqMarker.setLabelTextAnchor(TextAnchor.TOP_LEFT);
				}

		        
		        if (isInAxisArea(e.getX(), e.getY())) {
                    chartPanel.setCursor(Cursor.getPredefinedCursor((Cursor.HAND_CURSOR)));
                }
		        else
		        {
		        	chartPanel.setCursor(Cursor.getDefaultCursor());
		        }

			}
		});
		chartPanel.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseEntered(MouseEvent e) {
				chart.getXYPlot().clearDomainMarkers();
				chart.getXYPlot().clearRangeMarkers();
				chart.getXYPlot().addRangeMarker(signalMarker);
				chart.getXYPlot().addDomainMarker(freqMarker);
			}

			@Override
			public void mouseExited(MouseEvent e) {
				chart.getXYPlot().clearDomainMarkers();
				chart.getXYPlot().clearRangeMarkers();
				titleFreqBand.setText("");
			}
			
            @Override
            public void mousePressed(MouseEvent e) {
                if (isDataReplayActive()) {
                    chartPanel.requestFocus();
                    return;
                }
                if (SwingUtilities.isRightMouseButton(e) && isInPlotRow(e.getY())) {
                	iqSelection = startIQSelection(e);
                	dragging = false;
                	chartPanel.setDomainZoomable(false);
                	chartPanel.requestFocus();
                	e.consume();
                	return;
                }
                if (isIqReplayActive()) {
					if (SwingUtilities.isLeftMouseButton(e) && isInPlotRow(e.getY())) {
						startDragZoom(e);
						chartPanel.setDomainZoomable(false);
						chartPanel.repaint();
					} else if (SwingUtilities.isLeftMouseButton(e) && isInAxisArea(e.getX(), e.getY())) {
						dragging = true;
						iqReplayPanLastX = e.getX();
						chartPanel.setDomainZoomable(false);
					}
                    chartPanel.requestFocus();
                    return;
                }
                draggingStartedInMultiRange = false;
                if (SwingUtilities.isLeftMouseButton(e) && isInPlotRow(e.getY())) {
					startDragZoom(e);
					chartPanel.setDomainZoomable(false);
					chartPanel.requestFocus();
					e.consume();
					return;
				}
                if (SwingUtilities.isLeftMouseButton(e) && isInAxisArea(e.getX(), e.getY())) {
                    dragging = true;
                    int[] activePairs = parseRangePairs(parameterFreqRange.getValue());
                    draggingStartedInMultiRange = isMultiRange(activePairs);
                    draggingBaseStartMHz = getFreq().getStartMHz();
                    draggingBaseStopMHz = getFreq().getEndMHz();
                    draggingPanDeltaMHz = 0;
                    lastX = (int) chart.getXYPlot().getDomainAxis().java2DToValue(e.getX(),
                    		chartPanel.getChartRenderingInfo().getPlotInfo().getDataArea(), chart.getXYPlot().getDomainAxisEdge());
                    chartPanel.setDomainZoomable(false);
                }
                lastXX = e.getX();
                chartPanel.requestFocus();
            }
			
            @Override
            public void mouseReleased(MouseEvent e) {
                if (isDataReplayActive()) {
            		dragging = false;
            		iqSelection = null;
            		chartPanel.repaint();
            		return;
            	}
            	if (iqSelection != null) {
            		IQSelection finishedSelection = iqSelection;
            		updateIQSelection(finishedSelection, e.getX());
            		iqSelection = null;
            		chartPanel.setDomainZoomable(true);
            		chartPanel.repaint();
            		openIQAnalyzerForSelection(finishedSelection);
            		e.consume();
            		return;
                }
                if (isIqReplayActive()) {
					if (!Double.isNaN(dragZoomAnchorMHz)) {
						final double zoomStart = dragZoomAnchorMHz;
						final double zoomStop = mouseToDomainMHz(e.getX());
						final boolean applyZoom = Math.abs(clampMouseXToPlot(e.getX()) - dragZoomAnchorX) >= 4;
						clearDragZoom();
						dragging = false;
						iqReplayPanLastX = -1;
						chartPanel.repaint();
						e.consume();
						SwingUtilities.invokeLater(() -> {
							if (applyZoom && isIqReplayActive()) {
								applyIqReplayDomainRange(zoomStart, zoomStop);
							}
							chartPanel.setDomainZoomable(true);
							chartPanel.repaint();
						});
						return;
					}
					if (dragging) {
						XYPlot plot = chart.getXYPlot();
						updateIqReplayAuxiliaryViews(plot.getDomainAxis().getLowerBound(),
								plot.getDomainAxis().getUpperBound());
					}
                    dragging = false;
					iqReplayPanLastX = -1;
					SwingUtilities.invokeLater(() -> chartPanel.setDomainZoomable(true));
                    chartPanel.repaint();
                    return;
                }
				if (!Double.isNaN(dragZoomAnchorMHz)) {
					dragZoomCurrentMHz = mouseToDomainMHz(e.getX());
					if (Math.abs(clampMouseXToPlot(e.getX()) - dragZoomAnchorX) >= 4) {
						applyLiveDragZoom(dragZoomAnchorMHz, dragZoomCurrentMHz);
					}
					clearDragZoom();
					dragging = false;
					chartPanel.setDomainZoomable(true);
					chartPanel.repaint();
					e.consume();
					return;
				}
                boolean releasedAxisDrag = dragging;
            	dragging = false;
            	chartPanel.setDomainZoomable(true);
            	if (lastXX != e.getX())
            	{
		        	chart.getXYPlot().getRangeAxis().setAutoRange(false);
		        	chart.getXYPlot().getRangeAxis().setRange(-100, -10);
                    int[] activePairs = parseRangePairs(parameterFreqRange.getValue());
                    if (releasedAxisDrag && draggingStartedInMultiRange && isMultiRange(activePairs)) {
                        int newLowerX = draggingBaseStartMHz + draggingPanDeltaMHz;
                        int newUpperX = draggingBaseStopMHz + draggingPanDeltaMHz;
                        newLowerX = clampFrequencyMHz(newLowerX);
                        newUpperX = clampFrequencyMHz(newUpperX);
                        if (newUpperX - newLowerX < 1)
                            newUpperX = Math.min(7200, newLowerX + 1);
                        applySingleFrequencyRange(newLowerX, newUpperX, false);
                        draggingStartedInMultiRange = false;
                        return;
                    }
		        	int newLowerX = (int) Math.round(chart.getXYPlot().getDomainAxis().getLowerBound());
		            int newUpperX = (int) Math.round(chart.getXYPlot().getDomainAxis().getUpperBound());
		            if (isMultiRange(activePairs)) {
                        newLowerX = (int) Math.round(mapCompressedToOriginal(newLowerX, activePairs));
                        newUpperX = (int) Math.round(mapCompressedToOriginal(newUpperX, activePairs));
		            }
		            if (newLowerX < 0) newLowerX = 0;
                    if (newUpperX > 7200) newUpperX = 7200;
		            double newDif = newUpperX - newLowerX;
		            if (newDif < 1) newUpperX = newLowerX + 1;
		            int[] newChartParams = setupChartParams(newDif);
		            parameterFrequency.setValue(new FrequencyRange(newLowerX, newUpperX));
		            if (isMultiRange(activePairs))
                        parameterFreqRange.setValue(newLowerX + "-" + newUpperX);
		            parameterFFTBinHz.setValue(newChartParams[0]);
		            parameterAmplitudeOffset.setValue(newChartParams[1]);
		            restartHackrfSweep();
            	}
            }
		});
		
		chartPanel.addMouseWheelListener(new MouseWheelListener() {
            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (isDataReplayActive())
                    return;
                if (isIqReplayActive()) {
                    zoomIqReplayDomain(e);
                    return;
                }
                int notches = e.getWheelRotation();
                // Get current domain and range
                double lowerX = chart.getXYPlot().getDomainAxis().getLowerBound();
                double upperX = chart.getXYPlot().getDomainAxis().getUpperBound();
                double zoomFactor = 0.3; //from center, 0.2 from edges
                double dif = upperX - lowerX;
                double mouseX = chart.getXYPlot().getDomainAxis().java2DToValue(e.getX(),
                		chartPanel.getChartRenderingInfo().getPlotInfo().getDataArea(), chart.getXYPlot().getDomainAxisEdge());
                int[] activePairs = parseRangePairs(parameterFreqRange.getValue());
                
                if (notches < 0) {
                    // Zoom in
                	//int newLowerX = (int) Math.round(lowerX + dif * zoomFactor); //from edge
                    if (isMultiRange(activePairs)) {
                        double cursorFrequencyMHz = mapCompressedToOriginal(mouseX, activePairs);
                        double subRangeDif = findRangeLengthForFrequency(cursorFrequencyMHz, activePairs);
                        int newLowerX = (int) Math.round(cursorFrequencyMHz - subRangeDif * zoomFactor);
                        int newUpperX = (int) Math.round(cursorFrequencyMHz + subRangeDif * zoomFactor);
                        if (newUpperX - newLowerX < 1)
                            newUpperX = newLowerX + 1;
                        applySingleFrequencyRange(newLowerX, newUpperX, true);
                        return;
                    }
                    int newLowerX = (int) Math.round(mouseX - dif * zoomFactor); //from center
                	//int newUpperX = (int) Math.round(upperX - dif * zoomFactor);
                    int newUpperX = (int) Math.round(mouseX + dif * zoomFactor);
                    double newDif = newUpperX - newLowerX;
                    if (newDif < 1) newUpperX = newLowerX + 1;
                    int[] newChartParams = setupChartParams(newDif);
                    parameterFrequency.setValue(new FrequencyRange(newLowerX, newUpperX));
                    parameterFFTBinHz.setValue(newChartParams[0]);
                    parameterAmplitudeOffset.setValue(newChartParams[1]);
                    restartHackrfSweep();
                } else {
                    // Zoom out
                    if (isMultiRange(activePairs)) {
                        double cursorFrequencyMHz = mapCompressedToOriginal(mouseX, activePairs);
                        int[] subRange = findRangeForFrequency(cursorFrequencyMHz, activePairs);
                        dif = subRange[1] - subRange[0];
                        int newLowerX = (int) Math.round(subRange[0] - dif * zoomFactor);
                        int newUpperX = (int) Math.round(subRange[1] + dif * zoomFactor);
                        applySingleFrequencyRange(newLowerX, newUpperX, true);
                        return;
                    }
                	int newLowerX = (int) Math.round(lowerX - dif * zoomFactor); //from edge
                    if (newLowerX < 0) newLowerX = 0;
                    int newUpperX = (int) Math.round(upperX + dif * zoomFactor);
                    if (newUpperX > 7200) newUpperX = 7200;
                    double newDif = newUpperX - newLowerX;
                    if (newDif < 1) newLowerX = newUpperX - 1;
                    int[] newChartParams = setupChartParams(newDif);
                    parameterFrequency.setValue(new FrequencyRange(newLowerX, newUpperX));
                    parameterFFTBinHz.setValue(newChartParams[0]);
                    parameterAmplitudeOffset.setValue(newChartParams[1]);
                    restartHackrfSweep();
                }
            }
        });

        chartPanel.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (isDataReplayActive()) {
                	dragging = false;
                	iqSelection = null;
                	return;
                }
                if (iqSelection != null) {
                	updateIQSelection(iqSelection, e.getX());
                	e.consume();
                	return;
                }
                if (isIqReplayActive()) {
					if (!Double.isNaN(dragZoomAnchorMHz)) {
						dragZoomCurrentMHz = mouseToDomainMHz(e.getX());
						chartPanel.repaint();
					} else if (dragging && iqReplayPanLastX >= 0) {
						panIqReplayDomain(e.getX());
					}
                    return;
                }
				if (!Double.isNaN(dragZoomAnchorMHz)) {
					dragZoomCurrentMHz = mouseToDomainMHz(e.getX());
					chartPanel.repaint();
					e.consume();
					return;
				}
                if (dragging) {
                	double lowerX = chart.getXYPlot().getDomainAxis().getLowerBound();
                	double upperX = chart.getXYPlot().getDomainAxis().getUpperBound();
                	if (lowerX >= 0 && upperX <= 7200) {
	                    int deltaX = lastX - (int) chart.getXYPlot().getDomainAxis().java2DToValue(e.getX(),
	                    		chartPanel.getChartRenderingInfo().getPlotInfo().getDataArea(), chart.getXYPlot().getDomainAxisEdge());
	                    if (draggingStartedInMultiRange)
                            draggingPanDeltaMHz += deltaX;
	                    
	                    // Pan the plot
	                    chart.getXYPlot().getDomainAxis().setLowerBound(lowerX + deltaX);
	                    chart.getXYPlot().getDomainAxis().setUpperBound(upperX + deltaX);
	                    lastX = (int) chart.getXYPlot().getDomainAxis().java2DToValue(e.getX(),
	                    		chartPanel.getChartRenderingInfo().getPlotInfo().getDataArea(), chart.getXYPlot().getDomainAxisEdge());
                	}
                }
            }
        });
        
        chartPanel.setFocusable(true);
        chartPanel.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
            	if (isReplayActive())
            		return;
            	int newLowerX = 0;
            	int newUpperX = 0;
            	int dif = (int) Math.round((getFreq().getEndMHz() - getFreq().getStartMHz()) * 0.9);
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_LEFT: // Move left
                    	newLowerX = (int) getFreq().getStartMHz() - dif;
                    	newUpperX = (int) getFreq().getEndMHz() - dif;
                    	if (newLowerX < 0) newLowerX = 0;
                    	if (newUpperX > 7200) newUpperX = 7200;
                    	if (newUpperX - newLowerX < 1) newUpperX = newLowerX + 1;
                        if (isMultiRange(parseRangePairs(parameterFreqRange.getValue()))) {
                            applySingleFrequencyRange(newLowerX, newUpperX, false);
                            break;
                        }
                    	parameterFrequency.setValue(new FrequencyRange(newLowerX, newUpperX));
                    	restartHackrfSweep();
                        break;
                    case KeyEvent.VK_RIGHT: // Move right
                    	newLowerX = (int) getFreq().getStartMHz() + dif;
                    	newUpperX = (int) getFreq().getEndMHz() + dif;
                    	if (newLowerX < 0) newLowerX = 0;
                    	if (newUpperX > 7200) newUpperX = 7200;
                    	if (newUpperX - newLowerX < 1) newLowerX = newUpperX - 1;
                        if (isMultiRange(parseRangePairs(parameterFreqRange.getValue()))) {
                            applySingleFrequencyRange(newLowerX, newUpperX, false);
                            break;
                        }
                    	parameterFrequency.setValue(new FrequencyRange(newLowerX, newUpperX));
                    	restartHackrfSweep();
                        break;
                }
            }
        });
    

		
		titleFreqBand.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
		titleFreqBand.setPosition(RectangleEdge.BOTTOM);
		titleFreqBand.setHorizontalAlignment(HorizontalAlignment.LEFT);
		titleFreqBand.setMargin(0.0, 2.0, 0.0, 2.0);
		titleFreqBand.setPaint(Color.white);
		chart.addSubtitle(titleFreqBand);
	}
	
	private boolean isInAxisArea(int mouseX, int mouseY)
	{
		// Check if the mouse is in the X-axis area
		if (mouseY >= chartPanel.getHeight() - 56 &&
			mouseY <= chartPanel.getHeight() &&
			mouseX >= chartPanel.getInsets().left &&
			mouseX <= chartPanel.getWidth() - chartPanel.getInsets().right)
		{
			return true;
		}

		return false;
	}

	private boolean isMultiRange(int[] pairs) {
		return pairs != null && pairs.length > 2;
	}

	private int clampFrequencyMHz(int frequencyMHz) {
		if (frequencyMHz < 0)
			return 0;
		if (frequencyMHz > 7200)
			return 7200;
		return frequencyMHz;
	}

	private double findRangeLengthForFrequency(double frequencyMHz, int[] pairs) {
		if (pairs == null)
			return Math.max(1, getFreq().getEndMHz() - getFreq().getStartMHz());
		for (int i = 0; i < pairs.length; i += 2) {
			int start = pairs[i];
			int stop = pairs[i + 1];
			if (frequencyMHz >= start && frequencyMHz <= stop)
				return Math.max(1, stop - start);
		}
		return Math.max(1, getFreq().getEndMHz() - getFreq().getStartMHz());
	}

	private int[] findRangeForFrequency(double frequencyMHz, int[] pairs) {
		if (pairs != null) {
			for (int i = 0; i < pairs.length; i += 2) {
				int start = pairs[i];
				int stop = pairs[i + 1];
				if (frequencyMHz >= start && frequencyMHz <= stop)
					return new int[] { start, stop };
			}
		}
		return new int[] { getFreq().getStartMHz(), getFreq().getEndMHz() };
	}

	private void applySingleFrequencyRange(int startMHz, int stopMHz, boolean updateChartParams) {
		int newLowerX = clampFrequencyMHz(startMHz);
		int newUpperX = clampFrequencyMHz(stopMHz);
		if (newUpperX - newLowerX < 1) {
			if (newLowerX >= 7200)
				newLowerX = 7199;
			newUpperX = newLowerX + 1;
		}
		double newDif = newUpperX - newLowerX;
		if (updateChartParams) {
			int[] newChartParams = setupChartParams(newDif);
			parameterFFTBinHz.setValue(newChartParams[0]);
			parameterAmplitudeOffset.setValue(newChartParams[1]);
		}
		parameterFreqRange.setValue(newLowerX + "-" + newUpperX);
		parameterFrequency.setValue(new FrequencyRange(newLowerX, newUpperX));
		restartHackrfSweep();
	}

	/**
	 * Parse ranges string like "920-925,940-945" into int[] {920,925,940,945}
	 */
	private int[] parseRangePairs(String rangesStr) {
						if (rangesStr == null || rangesStr.trim().isEmpty())
							return null;
						String[] parts = rangesStr.split(",");
						int[] out = new int[parts.length * 2];
						int idx = 0;
						for (String p : parts) {
							String[] se = p.trim().split("-");
							try {
								int s = Integer.parseInt(se[0].trim());
								int e = Integer.parseInt(se[1].trim());
								out[idx++] = s;
								out[idx++] = e;
							} catch (Exception ex) {
								return null;
							}
						}
						return out;
					}

					private double totalRangesLength(int[] pairs) {
						if (pairs == null) return 0;
						double sum = 0;
						for (int i = 0; i < pairs.length; i += 2) {
							sum += (pairs[i+1] - pairs[i]);
						}
						return sum;
					}

					/**
					 * Map a real frequency value (MHz) to compressed display X (MHz gapless).
					 * realFreq is expected WITHOUT freqShift (shift will be applied in labels).
					 */
					private double mapRealToCompressed(double realFreqNoShift, int[] pairs) {
						if (pairs == null) return realFreqNoShift;
						double cum = 0;
						for (int i = 0; i < pairs.length; i += 2) {
							int s = pairs[i];
							int e = pairs[i+1];
							if (realFreqNoShift >= s && realFreqNoShift <= e) {
								return cum + (realFreqNoShift - s);
							}
							cum += (e - s);
						}
						// if not found, clamp to nearest
						if (realFreqNoShift < pairs[0]) return 0;
						return cum;
					}

					/**
					 * Map compressed X back to original frequency (MHz) WITHOUT freqShift.
					 */
					private double mapCompressedToOriginal(double compressed, int[] pairs) {
						if (pairs == null) return compressed;
						double cum = 0;
						for (int i = 0; i < pairs.length; i += 2) {
							int s = pairs[i];
							int e = pairs[i+1];
							double len = e - s;
							if (compressed >= cum && compressed <= cum + len) {
								return s + (compressed - cum);
							}
							cum += len;
						}
						// clamp
						if (compressed < 0) return pairs[0];
						return pairs[pairs.length-1];
					}

					private XYSeriesImmutable remapSeriesToCompressed(XYSeriesImmutable src, int[] pairs) {
						if (pairs == null) return src;
						int n = src.getItemCount();
						ArrayList<Float> xs = new ArrayList<>();
						ArrayList<Float> ys = new ArrayList<>();
						for (int i = 0; i < n; i++) {
							double x = src.getXX(i); // this x already includes freqShift as dataset adds it
							double originalNoShift = x - parameterFreqShift.getValue();
							// check if within any range
							boolean inRange = false;
							for (int r = 0; r < pairs.length; r += 2) {
								if (originalNoShift >= pairs[r] && originalNoShift <= pairs[r+1]) { inRange = true; break; }
							}
							if (!inRange) continue; // skip points outside selected ranges
							double compressed = mapRealToCompressed(originalNoShift, pairs);
							xs.add((float) compressed);
							ys.add((float) src.getYY(i));
						}
						float[] xArr = new float[xs.size()];
						float[] yArr = new float[ys.size()];
						for (int i = 0; i < xs.size(); i++) { xArr[i] = xs.get(i); yArr[i] = ys.get(i); }
						return new XYSeriesImmutable(src.getKey(), xArr, yArr);
					}

	private void addSpectrumMarkerAnnotation(DatasetSpectrumPeak.SpectrumMarker marker, Color color,
			ArrayList<MarkerLabelPlacement> placements) {
		int[] pairsForAnnot = parseRangePairs(parameterFreqRange.getValue());
		double pointerX = marker.frequencyMHz;
		if (pairsForAnnot != null && pairsForAnnot.length > 2) {
			double origNoShift = marker.frequencyMHz - parameterFreqShift.getValue();
			pointerX = mapRealToCompressed(origNoShift, pairsForAnnot);
		}

		double lowerX = chart.getXYPlot().getDomainAxis().getLowerBound();
		double upperX = chart.getXYPlot().getDomainAxis().getUpperBound();
		double xPercent = upperX == lowerX ? 50 : (pointerX - lowerX) * 100 / (upperX - lowerX);
		int row = findMarkerLabelRow(xPercent, placements);
		placements.add(new MarkerLabelPlacement(xPercent, row));

		XYPointerAnnotation pointer = new XYPointerAnnotation(String.format(new Locale("sk","SK"), "%.1f", marker.amplitudeDbm)
				+ " @ " + String.format(new Locale("sk","SK"), "%.2f", marker.frequencyMHz),
				pointerX, marker.amplitudeDbm - 1.8f, 4.71f);
		pointer.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
		pointer.setPaint(color);
		pointer.setLabelOffset(3 + row * 18);

		if (xPercent < 9)
			pointer.setTextAnchor(TextAnchor.BOTTOM_LEFT);
		else if (xPercent > 91)
			pointer.setTextAnchor(TextAnchor.BOTTOM_RIGHT);
		else
			pointer.setTextAnchor(TextAnchor.BOTTOM_CENTER);

		pointer.setArrowLength(12);
		pointer.setArrowPaint(Color.white);
		chart.getXYPlot().addAnnotation(pointer);
	}

	private int findMarkerLabelRow(double xPercent, ArrayList<MarkerLabelPlacement> placements) {
		int row = 0;
		while (row < 5) {
			boolean occupied = false;
			for (MarkerLabelPlacement placement : placements) {
				if (placement.row == row && Math.abs(placement.xPercent - xPercent) < 9) {
					occupied = true;
					break;
				}
			}
			if (!occupied)
				return row;
			row++;
		}
		return 4;
	}
	
	private int[] setupChartParams(double newDif)
	{
        int RBW = 0;
        int ampOff = 0;
		int out[] = new int[2]; 
        if (newDif < 10) {RBW = 3; ampOff = 10;}
        else if (newDif < 20) {RBW = 10; ampOff = 6;}
        else if (newDif < 40) {RBW = 20; ampOff = 3;}
        else if (newDif < 80) {RBW = 50; ampOff = 0;}
        else if (newDif < 160) {RBW = 100; ampOff = -3;}
        else if (newDif < 320) {RBW = 200; ampOff = -6;}
        else if (newDif < 640) {RBW = 500; ampOff = -9;}
        else if (newDif < 1280) {RBW = 1000; ampOff = -11;}
        else {RBW = 2000; ampOff = -12;}
        
        out[0] = RBW;
        out[1] = ampOff;
        return out;
	}
	
	private void setupFrequencyAllocationTable() {
		SwingUtilities.invokeLater(() -> {
			chartPanel.addComponentListener(new ComponentAdapter() {
				public void componentResized(ComponentEvent e) {
					redrawFrequencySpectrumTable();
				}
			});
			chart.getXYPlot().getDomainAxis().addChangeListener((e) -> {
				redrawFrequencySpectrumTable();
			});
		});
		parameterFrequencyAllocationTable.addListener(this::redrawFrequencySpectrumTable);
	}

	private void setupParameterObservers() {
		Runnable restartHackrf = this::restartHackrfSweep;
		parameterFrequency.addListener(restartHackrf);
		parameterFrequency.addListener(() -> {
			if (!isReplayActive())
				resetTriggerRangeToActiveFullRange();
		});
		parameterAntPower.addListener(restartHackrf);
		parameterAntennaLNA.addListener(restartHackrf);
		parameterFFTBinHz.addListener(restartHackrf);
		parameterSamples.addListener(restartHackrf);
		parameterFreqShift.addListener(restartHackrf);
		// update waterfall and persistent display compressed ranges when freqShift changes
		parameterFreqShift.addListener((v) -> {
			int[] pairs = parseRangePairs(parameterFreqRange.getValue());
			waterfallPlot.setRangePairs(pairs, parameterFreqShift.getValue());
			persistentDisplay.setRangePairs(pairs, parameterFreqShift.getValue());
			if (!isReplayActive())
				resetTriggerRangeToActiveFullRange();
		});
		parameterFreqRange.addListener(restartHackrf);
		// update waterfall and persistent display compressed ranges when freqRange changes
		parameterFreqRange.addListener(() -> {
			if (!isReplayActive())
				parameterDisplayFreqRange.setValue(parameterFreqRange.getValue());
			int[] pairs = parseRangePairs(parameterFreqRange.getValue());
			waterfallPlot.setRangePairs(pairs, parameterFreqShift.getValue());
			persistentDisplay.setRangePairs(pairs, parameterFreqShift.getValue());
			if (!isReplayActive())
				resetTriggerRangeToActiveFullRange();
		});
		parameterIsCapturingPaused.addListener(this::fireCapturingStateChanged);
		
		parameterIsRecordedVideo.addListener(this::startCaptureVideo);
		parameterIsRecordedData.addListener(this::startCaptureData);
		parameterIsRecordedSpectrum.addListener(this::startCaptureSpectrum);
		parameterIsPlayingSpectrum.addListener(this::startSpectrumPlayback);

		parameterGainTotal.addListener((gainTotal) -> {
			if (flagManualGain) //flag is being adjusted manually by LNA or VGA, do not recalculate the gains
				return;
			recalculateGains(gainTotal);
			restartHackrfSweep();
		});
		Runnable gainRecalc = () -> {
			int totalGain = parameterGainLNA.getValue() + parameterGainVGA.getValue();
			flagManualGain = true;
			try {
				parameterGainTotal.setValue(totalGain);
			} catch (Exception e) {
				e.printStackTrace();
			} finally {
				flagManualGain = false;
			}
			restartHackrfSweep();
		};
		parameterGainLNA.addListener(gainRecalc);
		parameterGainVGA.addListener(gainRecalc);

		parameterSpurRemoval.addListener(() -> {
			SpurFilter filter = spurFilter;
			if (filter != null) {
				filter.recalibrate();
			}
		});
		parameterShowPeaks.addListener(() -> {
			DatasetSpectrumPeak p = datasetSpectrum;
			if (p != null) {
				p.resetPeaks();
			}
		});
		parameterShowAverage.addListener(() -> {
			DatasetSpectrumPeak p = datasetSpectrum;
			if (p != null) {
				p.resetAverage();
			}
		});
		parameterShowMaxHold.addListener(() -> {
			DatasetSpectrumPeak p = datasetSpectrum;
			if (p != null) {
				p.resetMaxHold();
			}
		});
		
		parameterShowHoldMarker.addListener(() -> {
			DatasetSpectrumPeak p = datasetSpectrum;
			if (p != null) {
				p.resetMinHold();
			}
		});
		
		parameterInfoBoxVisible.setValue((boolean) waterfallPlot.isInfoBoxVisible());
		parameterInfoBoxVisible.addListener(e -> {
		    boolean visible = (boolean) parameterInfoBoxVisible.getValue();
		    waterfallPlot.setInfoBoxVisible(visible);
		});


		// only initialize palette controls from waterfall defaults when no settings were loaded from disk
		if (!settingsLoaded) {
			parameterSpectrumPaletteStart.setValue((int) waterfallPlot.getSpectrumPaletteStart());
			parameterSpectrumPaletteSize.setValue((int) waterfallPlot.getSpectrumPaletteSize());
		}
		parameterSpectrumPaletteStart.addListener((dB) -> {
			waterfallPlot.setSpectrumPaletteStart(dB);
			SwingUtilities.invokeLater(() -> {
				waterfallPaletteStartMarker.setValue(waterfallPlot.getSpectrumPaletteStart());
				waterfallPaletteEndMarker
						.setValue(waterfallPlot.getSpectrumPaletteStart() + waterfallPlot.getSpectrumPaletteSize());
			});
		});
		parameterSpectrumPaletteSize.addListener((dB) -> {
			if (dB < SPECTRUM_PALETTE_SIZE_MIN)
				return;
			waterfallPlot.setSpectrumPaletteSize(dB);
			SwingUtilities.invokeLater(() -> {
				waterfallPaletteStartMarker.setValue(waterfallPlot.getSpectrumPaletteStart());
				waterfallPaletteEndMarker
						.setValue(waterfallPlot.getSpectrumPaletteStart() + waterfallPlot.getSpectrumPaletteSize());
			});

		});
		parameterPeakFallRateSecs.addListener((fallRate) -> {
			datasetSpectrum.setPeakFalloutMillis(fallRate * 1000l);
		});
		
		parameterPeakFallThreshold.addListener((threshold) -> {
			datasetSpectrum.setPeakFallThreshold(threshold);
		});
		
		parameterPeakHoldTime.addListener((holdTime) -> {
			datasetSpectrum.setPeakHoldMillis(holdTime * 1000l);
		});
		
		parameterAvgIterations.addListener(restartHackrf);
		
		parameterLogDetail.addListener(restartHackrf);
		parameterVideoArea.addListener(restartHackrf);
		parameterVideoFormat.addListener(restartHackrf);
		parameterVideoResolution.addListener(restartHackrf);
		parameterVideoFrameRate.addListener(restartHackrf);
		
		parameterAvgOffset.addListener((offset) -> {
			datasetSpectrum.setAvgOffset(offset);
		});

		parameterSpectrumLineThickness.addListener((thickness) -> {
			SwingUtilities.invokeLater(() -> chartLineRenderer.setBaseStroke(new BasicStroke(thickness.floatValue())));
		});
		parameterSpectrumSpline.addListener((Boolean spline) -> setSpectrumSplineRenderer(spline));
		
		parameterPersistentDisplayPersTime.addListener((time) -> {
			persistentDisplay.setPersistenceTime(time);
		});

		int persistentDisplayDownscaleFactor = 4;

		Runnable resetPersistentImage = () -> {
			boolean display = parameterPersistentDisplay.getValue();
			persistentDisplay.reset();
			chart.getXYPlot().setBackgroundImage(display ? persistentDisplay.getDisplayImage().getValue() : null);
			chart.getXYPlot().setBackgroundImageAlpha(alphaPersistentDisplayImage);
		};
		persistentDisplay.getDisplayImage().addListener((image) -> {
			if (parameterPersistentDisplay.getValue())
				chart.getXYPlot().setBackgroundImage(image);
		});

		registerListener(new HackRFEventAdapter() {
			@Override
			public void hardwareStatusChanged(boolean hardwareSendingData) {
				SwingUtilities.invokeLater(() -> {
					if (hardwareSendingData && parameterPersistentDisplay.getValue()) {
						resetPersistentImage.run();
					}
				});
			}
		});

		parameterPersistentDisplay.addListener((display) -> {
			SwingUtilities.invokeLater(resetPersistentImage::run);
		});

		chartDataArea.addListener((area) -> {
			SwingUtilities.invokeLater(() -> {
				/*
				 * Align the waterfall plot and the spectrum chart
				 */
				if (waterfallPlot != null)
					waterfallPlot.setDrawingOffsets((int) area.getX(), (int) area.getWidth());

				/**
				 * persistent display config
				 */
				persistentDisplay.setImageSize((int) area.getWidth() / persistentDisplayDownscaleFactor,
						(int) area.getWidth() / persistentDisplayDownscaleFactor);
				if (parameterPersistentDisplay.getValue()) {
					chart.getXYPlot().setBackgroundImage(persistentDisplay.getDisplayImage().getValue());
					chart.getXYPlot().setBackgroundImageAlpha(alphaPersistentDisplayImage);
				}
			});
		});
	}

	private void startLauncherThread() {
		threadLauncher = new Thread(() -> {
			Thread.currentThread().setName("Launcher-thread");
			while (true) {
				try {
					threadLaunchCommands.take();
					restartHackrfSweepExecute();
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		});
		threadLauncher.start();
	}

	/**
	 * no need to synchronize, executes only in launcher thread
	 */
	private void stopHackrfSweep() {
		forceStopSweep = true;
		closeSpectrumRecording();
		if (threadHackrfSweep != null) {
			while (threadHackrfSweep.isAlive()) {
				forceStopSweep = true;
				//				System.out.println("Calling HackRFSweepNativeBridge.stop()");
				HackRFSweepNativeBridge.stop();
				try {
					Thread.sleep(20);
				} catch (InterruptedException e) {
				}
			}
			try {
				threadHackrfSweep.join();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			threadHackrfSweep = null;
		}
		System.out.println("HackRFSweep thread stopped.");
		if (threadProcessing != null) {
			threadProcessing.interrupt();
			try {
				threadProcessing.join();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			threadProcessing = null;
			System.out.println("Processing thread stopped.");
		}
	}

	private void sweep() throws IOException {
		lock.lock();
		try {
			threadProcessing = new Thread(() -> {
				Thread.currentThread().setName("hackrf_sweep data processing thread");
				try {
					processingThread();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			});
			threadProcessing.start();

			/**
			 * Ensures auto-restart if HW disconnects
			 */
			while (forceStopSweep == false) {
				fSlope = 100 / (float) (getFreq().getEndMHz() - getFreq().getStartMHz());
				fShift = fSlope * getFreq().getStartMHz();
				System.out.println(
						"Starting hackrf_sweep... " + getFreq().getStartMHz() + "-" + getFreq().getEndMHz() + " MHz");
				/*System.out.println("hackrf_sweep params:  freq " + getFreq().getStartMHz() + "-" + getFreq().getEndMHz()
						+ " MHz  RBW " + parameterFFTBinHz.getValue() + " kHz  samples " + parameterSamples.getValue()
						+ "  lna: " + parameterGainLNA.getValue() + " vga: " + parameterGainVGA.getValue() +
						" antenna_lna: "+parameterAntennaLNA.getValue());*/
				fireHardwareStateChanged(false);
				Ranges range = new Ranges(parameterFreqRange.getValue());		
				String FromTo = (range.isMultipleRanges())?
						(parameterFreqRange.getValue().replace("-",":")):(getFreq().getStartMHz() + ":" + getFreq().getEndMHz());
						System.out.println(FromTo);
				HackRFSweepNativeBridge.start(this, /*getFreq().getStartMHz(), getFreq().getEndMHz(), */ FromTo,
						parameterFFTBinHz.getValue()*1000, parameterSamples.getValue(), parameterGainLNA.getValue(),
						parameterGainVGA.getValue(), parameterAntPower.getValue(), parameterAntennaLNA.getValue());
				fireHardwareStateChanged(false);
				if (forceStopSweep == false) {
					Thread.sleep(1000);
				}
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		} finally {
			lock.unlock();
			fireHardwareStateChanged(false);
		}
	}

	protected void redrawFrequencySpectrumTable() {
		Rectangle2D area = chartPanel.getChartRenderingInfo().getPlotInfo().getDataArea();
		FrequencyAllocationTable activeTable = parameterFrequencyAllocationTable.getValue();
		if (activeTable == null) {
			imageFrequencyAllocationTableBands = null;
		} else if (area.getWidth() > 0 && area.getHeight() > 0) {

			// If multiple ranges selected, stitch per-range images side-by-side so overlay matches compressed axis
			int width = (int) area.getWidth();
			int height = (int) area.getHeight();
			String activeRanges = getActiveRangesForDisplay();
			int activeShift = getActiveFreqShiftForDisplay();
			int[] pairs = parseRangePairs(activeRanges);
			if (isIqReplayActive()) {
				double lowerMHz = chart.getXYPlot().getDomainAxis().getLowerBound();
				double upperMHz = chart.getXYPlot().getDomainAxis().getUpperBound();
				imageFrequencyAllocationTableBands = activeTable.drawAllocationTable(width, height,
						alphaFreqAllocationTableBandsImage,
						Math.round(Math.min(lowerMHz, upperMHz) * 1000000d),
						Math.round(Math.max(lowerMHz, upperMHz) * 1000000d),
						Color.white, Color.green);
			} else if (pairs != null && pairs.length > 2) {
				double totalLen = totalRangesLength(pairs);
				BufferedImage out = GraphicsToolkit.createAcceleratedImageTransparent(width, height);
				Graphics2D g = out.createGraphics();
				int x = 0;
				for (int i = 0; i < pairs.length; i += 2) {
					int s = pairs[i];
					int e = pairs[i+1];
					double len = e - s;
					int partW = (int) Math.round(len / totalLen * width);
					if (i == pairs.length - 2) {
						// last block - fill remaining width to avoid rounding gaps
						partW = width - x;
					}
					if (partW <= 0) continue;
					BufferedImage part = activeTable.drawAllocationTable(partW, height, alphaFreqAllocationTableBandsImage,
						((long) (s + activeShift)) * 1000000l,
						((long) (e + activeShift)) * 1000000l,
						Color.white, Color.green);
					g.drawImage(part, x, 0, null);
					x += partW;
				}
				g.dispose();
				imageFrequencyAllocationTableBands = out;
			} else {
				imageFrequencyAllocationTableBands = activeTable.drawAllocationTable(width, height, alphaFreqAllocationTableBandsImage,
						(getActiveFreqStartMHzForDisplay() + activeShift) * 1000000l,
						(getActiveFreqStopMHzForDisplay() + activeShift) * 1000000l,
						Color.white, Color.green);
			}
		}
	}
}

