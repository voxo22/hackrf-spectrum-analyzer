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
import java.awt.Point;
import java.awt.RenderingHints;
//import java.awt.Shape;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Properties;
import java.net.URISyntaxException;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.util.Locale;
import java.text.DecimalFormat;
import java.text.FieldPosition;
import java.text.NumberFormat;
import java.text.ParsePosition;
import java.util.ArrayList;
import java.util.Vector;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import javax.swing.ImageIcon;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
//import org.jfree.chart.annotations.XYAnnotation;
//import org.jfree.chart.annotations.XYDrawableAnnotation;
import org.jfree.chart.annotations.XYPointerAnnotation;
import org.jfree.chart.axis.Axis;
import org.jfree.chart.axis.AxisSpace;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.axis.NumberTickUnit;
import org.jfree.chart.axis.StandardTickUnitSource;
import org.jfree.chart.event.ChartChangeEvent;
import org.jfree.chart.event.ChartChangeListener;
import org.jfree.chart.event.ChartProgressEvent;
import org.jfree.chart.event.ChartProgressListener;
import org.jfree.chart.event.OverlayChangeListener;
import org.jfree.chart.event.PlotChangeEvent;
import org.jfree.chart.event.PlotChangeListener;
import org.jfree.chart.panel.Overlay;
import org.jfree.chart.plot.CombinedDomainXYPlot;
import org.jfree.chart.plot.Marker;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.ValueMarker;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.renderer.xy.XYSplineRenderer;
//import org.jfree.chart.renderer.xy.XYSplineRenderer;
import org.jfree.chart.title.TextTitle;
import org.jfree.data.Range;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.jfree.ui.Align;
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
import jspectrumanalyzer.core.FrequencyBand;
import jspectrumanalyzer.core.FrequencyPresets;
import jspectrumanalyzer.core.FrequencyRange;
import jspectrumanalyzer.core.HackRFSettings;
import jspectrumanalyzer.core.PersistentDisplay;
import jspectrumanalyzer.core.Preset;
import jspectrumanalyzer.core.Ranges;
//import jspectrumanalyzer.core.PowerCalibration;
import jspectrumanalyzer.core.SpurFilter;
import jspectrumanalyzer.core.jfc.XYSeriesCollectionImmutable;
import jspectrumanalyzer.core.jfc.XYSeriesImmutable;
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
	private ArrayList<HackRFEventListener>			hRFlisteners						= new ArrayList<>();
	private ArrayBlockingQueue<FFTBins>				hwProcessingQueue					= new ArrayBlockingQueue<>(1000);
	private BufferedImage							imageFrequencyAllocationTableBands	= null;
	private boolean									isChartDrawing						= false;
	private ReentrantLock							lock								= new ReentrantLock();
	private ModelValueBoolean						parameterAntennaLNA   				= new ModelValueBoolean("RF amp", false);
	private ModelValueBoolean						parameterAntPower					= new ModelValueBoolean("Antenna Power", false);
	private ModelValueInt							parameterFFTBinHz					= new ModelValueInt("RBW", 50);
	private ModelValueBoolean						parameterFilterSpectrum				= new ModelValueBoolean("Filter", false);
	private ModelValue<FrequencyRange>				parameterFrequency					= new ModelValue<>("Frequency Range", new FrequencyRange(920, 960));
	private ModelValue<FrequencyAllocationTable>	parameterFrequencyAllocationTable	= new ModelValue<FrequencyAllocationTable>("Frequency Allocation Table", new FrequencyAllocations().getTable().get("- Slovakia.csv"));
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
	private ModelValueInt							parameterPeakMarkerCount			= new ModelValueInt("Peak Marker Count", 1, 1, 1, 5);
	private ModelValueInt							parameterHoldMarkerCount			= new ModelValueInt("MaxHold Marker Count", 1, 1, 1, 5);
	private ModelValueBoolean 						parameterDebugDisplay				= new ModelValueBoolean("Debug", false);
	private ModelValue<BigDecimal>					parameterSpectrumLineThickness		= new ModelValue<>("Spectrum Line Thickness", new BigDecimal("1"));
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
	private ModelValue<String>						parameterLogDetail					= new ModelValue<>("Data Log Interval", new String("SEC"));
	private ModelValue<String>						parameterVideoArea					= new ModelValue<>("Video Area", new String("SPEC"));
	private ModelValue<String>						parameterVideoFormat				= new ModelValue<>("Video Format", new String("GIF"));
	private ModelValueInt							parameterVideoResolution			= new ModelValueInt("Video Resolution", 540);
	private ModelValueInt							parameterVideoFrameRate				= new ModelValueInt("Video Framerate", 15);
	private ModelValue<String>						parameterFreqRange					= new ModelValue<>("FreqRange", new String("920-960"));
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
	private float									fSlope;
    private float									fShift;
    private int										lastX;
    private int										lastXX;
    private boolean									dragging;




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
		uiFrame.setTitle("HackRF Spectrum Analyzer");
		uiFrame.add(splitPanePanel, BorderLayout.CENTER);
		uiFrame.setMinimumSize(new Dimension(pSizeX, pSizeY));
		uiFrame.add(settingsPanel, BorderLayout.EAST);
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
				// set main frequency from first range (if single range like 920-960)
				String v = parameterFreqRange.getValue();
				if (v != null && v.contains("-")) {
					String first = v.split(",")[0].trim();
					String[] se = first.split("-");
					try {
						int s = Integer.parseInt(se[0].trim());
						int e = Integer.parseInt(se[1].trim());
						parameterFrequency.setValue(new FrequencyRange(s, e));
					} catch (Exception ex) {
						// ignore
					}
				}
			}
			if (p.getProperty("FFTBinHz") != null) parameterFFTBinHz.setValue(Integer.parseInt(p.getProperty("FFTBinHz")));
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
			if (p.getProperty("ShowPeaks") != null) parameterShowPeaks.setValue(Boolean.parseBoolean(p.getProperty("ShowPeaks")));
			if (p.getProperty("ShowAverage") != null) parameterShowAverage.setValue(Boolean.parseBoolean(p.getProperty("ShowAverage")));
			if (p.getProperty("ShowMaxHold") != null) parameterShowMaxHold.setValue(Boolean.parseBoolean(p.getProperty("ShowMaxHold")));
			if (p.getProperty("ShowPeakMarker") != null) parameterShowPeakMarker.setValue(Boolean.parseBoolean(p.getProperty("ShowPeakMarker")));
			if (p.getProperty("ShowHoldMarker") != null) parameterShowHoldMarker.setValue(Boolean.parseBoolean(p.getProperty("ShowHoldMarker")));
			if (p.getProperty("PeakMarkerCount") != null) parameterPeakMarkerCount.setValue(clampMarkerCount(Integer.parseInt(p.getProperty("PeakMarkerCount"))));
			if (p.getProperty("HoldMarkerCount") != null) parameterHoldMarkerCount.setValue(clampMarkerCount(Integer.parseInt(p.getProperty("HoldMarkerCount"))));
			if (p.getProperty("SpurRemoval") != null) parameterSpurRemoval.setValue(Boolean.parseBoolean(p.getProperty("SpurRemoval")));
			if (p.getProperty("SpectrumLineThickness") != null) parameterSpectrumLineThickness.setValue(new java.math.BigDecimal(p.getProperty("SpectrumLineThickness")));
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
			if (p.getProperty("VideoArea") != null) parameterVideoArea.setValue(p.getProperty("VideoArea"));
			if (p.getProperty("VideoFormat") != null) parameterVideoFormat.setValue(p.getProperty("VideoFormat"));
			if (p.getProperty("VideoResolution") != null) parameterVideoResolution.setValue(Integer.parseInt(p.getProperty("VideoResolution")));
			if (p.getProperty("VideoFrameRate") != null) parameterVideoFrameRate.setValue(Integer.parseInt(p.getProperty("VideoFrameRate")));
			if (p.getProperty("LogDetail") != null) parameterLogDetail.setValue(p.getProperty("LogDetail"));
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
			p.setProperty("Samples", Integer.toString(parameterSamples.getValue()));
			p.setProperty("GainTotal", Integer.toString(parameterGainTotal.getValue()));
			p.setProperty("GainLNA", Integer.toString(parameterGainLNA.getValue()));
			p.setProperty("GainVGA", Integer.toString(parameterGainVGA.getValue()));
			// Do NOT persist Antenna LNA (RF amp) - keep it runtime only and Reset sets it unchecked
			p.setProperty("AntennaPower", Boolean.toString(parameterAntPower.getValue()));
			p.setProperty("FreqShift", Integer.toString(parameterFreqShift.getValue()));
			p.setProperty("PersistentDisplay", Boolean.toString(parameterPersistentDisplay.getValue()));
			p.setProperty("PersistentDisplayPersTime", Integer.toString(parameterPersistentDisplayPersTime.getValue()));
			p.setProperty("ShowPeaks", Boolean.toString(parameterShowPeaks.getValue()));
			p.setProperty("ShowAverage", Boolean.toString(parameterShowAverage.getValue()));
			p.setProperty("ShowMaxHold", Boolean.toString(parameterShowMaxHold.getValue()));
			p.setProperty("ShowPeakMarker", Boolean.toString(parameterShowPeakMarker.getValue()));
			p.setProperty("ShowHoldMarker", Boolean.toString(parameterShowHoldMarker.getValue()));
			p.setProperty("PeakMarkerCount", Integer.toString(parameterPeakMarkerCount.getValue()));
			p.setProperty("HoldMarkerCount", Integer.toString(parameterHoldMarkerCount.getValue()));
			p.setProperty("SpurRemoval", Boolean.toString(parameterSpurRemoval.getValue()));
			p.setProperty("SpectrumLineThickness", parameterSpectrumLineThickness.getValue().toString());
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
			p.setProperty("LogDetail", parameterLogDetail.getValue());

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
	public ModelValue<FrequencyRange> getFrequency() {
		return parameterFrequency;
	}
	@Override
	public ModelValue<String> getparamFreqRange() {
		return parameterFreqRange;
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
	public ModelValueInt getPeakMarkerCount() {
		return parameterPeakMarkerCount;
	}

	@Override
	public ModelValueInt getHoldMarkerCount() {
		return parameterHoldMarkerCount;
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
			if(parameterVideoResolution.getValue() == 1080 && !parameterVideoArea.getValue().equals("FULL"))
			{
				videoWidth = 1280; videoHeight = 720;
			}

			if(parameterVideoFormat.getValue().equals("GIF"))
			{
				gifCap = new ScreenCapture(uiFrame, 1, 0, parameterVideoFrameRate.getValue(), videoWidth, videoHeight,
					parameterVideoArea.getValue(), new File("# "+parameterVideoArea.getValue() + " "
							+ (getFreq().getStartMHz()+parameterFreqShift.getValue()) + "-"
							+ (getFreq().getEndMHz()+parameterFreqShift.getValue()) + " MHz "
							+ dateStamp.format(dStampFormat) + ".gif")
					);
			}
			else
			{
				h264Cap = new ScreenCaptureH264(uiFrame, 1, 0, parameterVideoFrameRate.getValue(), videoWidth, videoHeight,
					parameterVideoArea.getValue(), new String("# " + parameterVideoArea.getValue() + " "
							+ (getFreq().getStartMHz()+parameterFreqShift.getValue()) + "-"
							+ (getFreq().getEndMHz()+parameterFreqShift.getValue()) + " MHz "
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
			dataCap = new FileWriter("# DATA " + (getFreq().getStartMHz() + parameterFreqShift.getValue()) + "-" +
					(getFreq().getEndMHz() + parameterFreqShift.getValue()) + " MHz "+dateStamp.format(dStampFormat) + ".csv");
			dataCap.write("Timestamp,Total Spectrum Power [dBm],Power Flux Density [µW/m²],Max Amplitude [dBm],Frequency [MHz]\r\n");
		} catch (Exception e) {
			e.printStackTrace();
		}
		else
		{

		}
	}
	
	private FrequencyRange getFreq() {
		return parameterFrequency.getValue();
	}

	private void printInit(int initNumber) {
		//		System.out.println("Startup "+(initNumber++)+" in " + (System.currentTimeMillis() - initTime) + "ms");
	}
	
	private void processingThread() throws IOException {
		long counter = 0;
		long frameCounterChart = 0;
		boolean dcap = false;
		String pattern = ""; 
		switch (parameterLogDetail.getValue()) {
        case "FRA": pattern = "HH:mm:ss.S";
                break;
        case "SEC":  pattern = "HH:mm:ss";
                break;
        case "MIN":  pattern = "HH:mm";
        		break;
		}
		DateTimeFormatter dtFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd " + pattern);
		String dt1 = LocalDateTime.now().format(dtFormat);
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

			/**
			 * prevents from spectrum chart from using too much CPU
			 */
			int limitChartRefreshFPS		= 30;
			int limitPersistentRefreshEveryChartFrame	= 2;
			
			//PowerCalibration calibration	 = new PowerCalibration(-45, -12.5, 40); 

			datasetSpectrum = new DatasetSpectrumPeak(binHz, getFreq().getStartMHz(), getFreq().getEndMHz(),
					spectrumInitValue, parameterPeakFallThreshold.getValue(), parameterPeakFallRateSecs.getValue() * 1000,
					parameterPeakHoldTime.getValue() * 1000, parameterFreqShift.getValue(), parameterAvgIterations.getValue(),
					parameterAvgOffset.getValue());
			// If multiple ranges are selected, compress X axis so gaps are removed
			int[] pairs = parseRangePairs(parameterFreqRange.getValue());
			datasetSpectrum.setActiveRangePairs(pairs);
			if (pairs != null && pairs.length > 2) {
				double total = totalRangesLength(pairs);
				chart.getXYPlot().getDomainAxis().setRange(0, total);
				// override tick labels to map compressed X back to original frequencies (including freqShift)
				((NumberAxis)chart.getXYPlot().getDomainAxis()).setNumberFormatOverride(new java.text.NumberFormat() {
					@Override
					public StringBuffer format(double value, StringBuffer toAppendTo, java.text.FieldPosition pos) {
						double origNoShift = mapCompressedToOriginal(value, pairs);
						double label = origNoShift + parameterFreqShift.getValue();
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
				chart.getXYPlot().getDomainAxis().setRange(getFreq().getStartMHz()+parameterFreqShift.getValue(),
						getFreq().getEndMHz()+parameterFreqShift.getValue());
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
					if (parameterIsCapturingPaused.getValue())
						continue;
					
					boolean triggerChartRefresh = bins.fullSweepDone;
					//continue;
				
					if (bins.freqStart != null && bins.sigPowdBm != null) {
					//	PowerCalibration.correctPower(calibration, parameterGaindB, bins);
						for (int i = 0; i < bins.sigPowdBm.length; i++) {
							bins.sigPowdBm[i] -= (30-parameterAmplitudeOffset.getValue()); //offset calibration
						}
						datasetSpectrum.addNewData(bins);
					}

					if ((triggerChartRefresh/* || timeDiff > 1000 */)) {
						//						System.out.println("ctr "+counter+" dropped "+dropped);
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
						if (parameterShowPeaks.getValue()) {
							datasetSpectrum.refreshPeakSpectrum();
							double[] spp = datasetSpectrum.calculateSpectrumPeakPower(parameterPowerFluxCal.getValue());
							if (!parameterShowHoldMarker.getValue())
							{
									waterfallPlot.setStatusMessage(String.format(new Locale("sk","SK"), "Total Peak Power: %.1f dBm (≈ %s µW/m²)", spp[0], spp[3]),0);
									waterfallPlot.setStatusMessage(String.format(new Locale("sk","SK"), "Max: %.1f dBm @ %.2f MHz", spp[1], spp[2]),1);
								waterfallPlot.setStatusMessage(String.format(""),2);
							}
							dt1 = LocalDateTime.now().format(dtFormat);
							/*
							markerFrequencyPeak.setValue(spp[2]);
							markerFrequencyPeak.setLabel(String.format("%.1f MHz", spp[2]));
							markerAmplitudePeak.setValue(spp[1]);
							markerAmplitudePeak.setLabel(String.format("%.1f dB", spp[1]));
							*/
							
							markerFrequencyPeak = spp[2];
							markerAmplitudePeak = spp[1];
							markersPeak = datasetSpectrum.calculatePeakMarkers(parameterPeakMarkerCount.getValue());
					        
							if(parameterIsRecordedData.getValue()) {
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
									dataCap.close();
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
								markersMaxHold = datasetSpectrum.calculateMaxHoldMarkers(parameterHoldMarkerCount.getValue());
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

						if (true) {
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
						} else {
							spectrumSeries = new XYSeries("spectrum", false, true);
							spectrumSeries.setNotify(false);
							datasetSpectrum.fillToXYSeries(spectrumSeries);
							spectrumSeries.setNotify(true);

							spectrumPeaks =
									//									new XYSeries("peaks");
									new XYSeries("peaks", false, true);
							if (parameterShowPeaks.getValue()) {
								spectrumPeaks.setNotify(false);
								datasetSpectrum.fillPeaksToXYSeries(spectrumPeaks);
								spectrumPeaks.setNotify(false);
							}
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
							if ((cnt % (11 - parameterWaterfallSpeed.getValue())) == 0) {waterfallPlot.addNewData(datasetSpectrum);}
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
							int[] pairsAdd = parseRangePairs(parameterFreqRange.getValue());
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
								
								if(parameterShowPeaks.getValue() && parameterShowPeakMarker.getValue()) {
									for (DatasetSpectrumPeak.SpectrumMarker marker : markersPeak) {
										addSpectrumMarkerAnnotation(marker, colors.clime);
									}
								}

								if(parameterShowMaxHold.getValue() && parameterShowHoldMarker.getValue()) {
									for (DatasetSpectrumPeak.SpectrumMarker marker : markersMaxHold) {
										addSpectrumMarkerAnnotation(marker, colors.cpink);
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
		if (threadLaunchCommands.offer(0) == false) {
			threadLaunchCommands.clear();
			threadLaunchCommands.offer(0);
		}
	}

	/**
	 * no need to synchronize, executes only in the launcher thread
	 */
	private void restartHackrfSweepExecute() {
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

	private void setupChart() {
		int axisWidthLeft = 60;
		int axisWidthRight = 40;

		chart = ChartFactory.createXYLineChart("Spectrum analyzer", "Frequency (MHz)", "Amplitude (dBm)", chartDataset,
				PlotOrientation.VERTICAL, false, false, false);
		chart.getRenderingHints().put(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

		XYPlot plot = chart.getXYPlot();
		NumberAxis domainAxis = ((NumberAxis) plot.getDomainAxis());
		NumberAxis rangeAxis = ((NumberAxis) plot.getRangeAxis());
		//chartLineRenderer = new XYSplineRenderer();
		chartLineRenderer = new XYLineAndShapeRenderer();
		//chartLineRenderer = new XYSplineRenderer(); //spline
		chartLineRenderer.setBaseShapesVisible(false);
		//chartLineRenderer.setBaseLinesVisible(true);
		//chartLineRenderer.setSeriesShapesFilled(0,false);
		//chartLineRenderer.setUseOutlinePaint(false);
		chartLineRenderer.setBaseStroke(new BasicStroke(parameterSpectrumLineThickness.getValue().floatValue()));

		rangeAxis.setAutoRange(false);
		rangeAxis.setRange(-100, -10); //amplitude range
		rangeAxis.setTickUnit(new NumberTickUnit(10, new DecimalFormat("###")));

		domainAxis.setNumberFormatOverride(new DecimalFormat(" #.### "));

		chartLineRenderer.setAutoPopulateSeriesStroke(false);
		chartLineRenderer.setAutoPopulateSeriesPaint(false);
		chartLineRenderer.setSeriesPaint(0, colors.cgreen);  //peak
		chartLineRenderer.setSeriesPaint(1, colors.cyellow);  //avg
		chartLineRenderer.setSeriesPaint(2, colors.cred);  //maxhold
		chartLineRenderer.setSeriesPaint(3, colors.blue);  //minhold
		chartLineRenderer.setSeriesPaint(4, colors.cwhite);  //realtime

		if (false)
			chart.addProgressListener(new ChartProgressListener() {
				StandardTickUnitSource tus = new StandardTickUnitSource();

				@Override
				public void chartProgress(ChartProgressEvent event) {
					if (event.getType() == ChartProgressEvent.DRAWING_STARTED) {
						Range r = domainAxis.getRange();
						domainAxis.setTickUnit((NumberTickUnit) tus.getCeilingTickUnit(r.getLength() / 20));
						domainAxis.setMinorTickCount(2);
						domainAxis.setMinorTickMarksVisible(true);

					}
				}
			});

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
		chartLineRenderer.setBasePaint(Color.white);
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
				BufferedImage img = imageFrequencyAllocationTableBands;
				if (img != null) {
					Rectangle2D area = chartPanel.getChartRenderingInfo().getPlotInfo().getDataArea();
					g2.drawImage(img, (int) area.getX(), (int) area.getY()+20, null);
								// draw vertical dashed separators for original block boundaries when compressed ranges in use
								int[] pairs = parseRangePairs(parameterFreqRange.getValue());
								if (pairs != null && pairs.length > 2) {
									double totalLen = totalRangesLength(pairs);
									double cum = 0;
									for (int i = 0; i < pairs.length; i += 2) {
										double len = pairs[i+1] - pairs[i];
										cum += len;
										// draw separator at end of block (except last)
										if (i < pairs.length - 2) {
											int sepX = (int) Math.round(area.getX() + (cum / totalLen) * area.getWidth());
											java.awt.Stroke old = g2.getStroke();
											java.awt.Color oldC = g2.getColor();
											float[] dash = {4f, 4f};
											g2.setStroke(new java.awt.BasicStroke(1f, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER, 1.0f, dash, 0f));
											g2.setColor(new Color(255,255,255,120));
											g2.drawLine(sepX, (int)area.getY()+20, sepX, (int)(area.getY()+area.getHeight()));
											g2.setStroke(old);
											g2.setColor(oldC);
										}
									}
								}
				}
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
					LocalDateTime datetime1 = LocalDateTime.now();
					DateTimeFormatter dtFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
					if(parameterDatestamp.getValue()) {
						g2.drawString(datetime1.format(dtFormat), 20, 15);
					}
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
				int[] pairsForLabel = parseRangePairs(parameterFreqRange.getValue());
				double displayFreqMHz;
				if (pairsForLabel != null && pairsForLabel.length > 2) {
					double origNoShift = mapCompressedToOriginal(crosshairDomain, pairsForLabel);
					displayFreqMHz = origNoShift + parameterFreqShift.getValue();
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
                if (isInAxisArea(e.getX(), e.getY())) {
                    dragging = true;
                    lastX = (int) chart.getXYPlot().getDomainAxis().java2DToValue(e.getX(),
                    		chartPanel.getChartRenderingInfo().getPlotInfo().getDataArea(), chart.getXYPlot().getDomainAxisEdge());
                    chartPanel.setDomainZoomable(false);
                }
                lastXX = e.getX();
                chartPanel.requestFocus();
            }
			
            @Override
            public void mouseReleased(MouseEvent e) {
            	dragging = false;
            	chartPanel.setDomainZoomable(true);
            	if (lastXX != e.getX())
            	{
		        	chart.getXYPlot().getRangeAxis().setAutoRange(false);
		        	chart.getXYPlot().getRangeAxis().setRange(-100, -10);
		        	int newLowerX = (int) Math.round(chart.getXYPlot().getDomainAxis().getLowerBound());
		            int newUpperX = (int) Math.round(chart.getXYPlot().getDomainAxis().getUpperBound());
		            if (newLowerX < 0) newLowerX = 0;
                    if (newUpperX > 7200) newUpperX = 7200;
		            double newDif = newUpperX - newLowerX;
		            if (newDif < 1) newUpperX = newLowerX + 1;
		            int[] newChartParams = setupChartParams(newDif);
		            parameterFrequency.setValue(new FrequencyRange(newLowerX, newUpperX));
		            parameterFFTBinHz.setValue(newChartParams[0]);
		            parameterAmplitudeOffset.setValue(newChartParams[1]);
		            restartHackrfSweep();
            	}
            }
		});
		
		chartPanel.addMouseWheelListener(new MouseWheelListener() {
            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                int notches = e.getWheelRotation();
                // Get current domain and range
                double lowerX = chart.getXYPlot().getDomainAxis().getLowerBound();
                double upperX = chart.getXYPlot().getDomainAxis().getUpperBound();
                double zoomFactor = 0.3; //from center, 0.2 from edges
                double dif = upperX - lowerX;
                double mouseX = chart.getXYPlot().getDomainAxis().java2DToValue(e.getX(),
                		chartPanel.getChartRenderingInfo().getPlotInfo().getDataArea(), chart.getXYPlot().getDomainAxisEdge());
                
                if (notches < 0) {
                    // Zoom in
                	//int newLowerX = (int) Math.round(lowerX + dif * zoomFactor); //from edge
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
                if (dragging) {
                	double lowerX = chart.getXYPlot().getDomainAxis().getLowerBound();
                	double upperX = chart.getXYPlot().getDomainAxis().getUpperBound();
                	if (lowerX >= 0 && upperX <= 7200) {
	                    int deltaX = lastX - (int) chart.getXYPlot().getDomainAxis().java2DToValue(e.getX(),
	                    		chartPanel.getChartRenderingInfo().getPlotInfo().getDataArea(), chart.getXYPlot().getDomainAxisEdge());
	                    
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
                    	parameterFrequency.setValue(new FrequencyRange(newLowerX, newUpperX));
                    	restartHackrfSweep();
                        break;
                    case KeyEvent.VK_RIGHT: // Move right
                    	newLowerX = (int) getFreq().getStartMHz() + dif;
                    	newUpperX = (int) getFreq().getEndMHz() + dif;
                    	if (newLowerX < 0) newLowerX = 0;
                    	if (newUpperX > 7200) newUpperX = 7200;
                    	if (newUpperX - newLowerX < 1) newLowerX = newUpperX - 1;
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

	private void addSpectrumMarkerAnnotation(DatasetSpectrumPeak.SpectrumMarker marker, Color color) {
		int[] pairsForAnnot = parseRangePairs(parameterFreqRange.getValue());
		double pointerX = marker.frequencyMHz;
		if (pairsForAnnot != null && pairsForAnnot.length > 2) {
			double origNoShift = marker.frequencyMHz - parameterFreqShift.getValue();
			pointerX = mapRealToCompressed(origNoShift, pairsForAnnot);
		}

		XYPointerAnnotation pointer = new XYPointerAnnotation(String.format(new Locale("sk","SK"), "%.1f", marker.amplitudeDbm)
				+ " @ " + String.format(new Locale("sk","SK"), "%.2f", marker.frequencyMHz),
				pointerX, marker.amplitudeDbm - 1.8f, 4.71f);
		pointer.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
		pointer.setPaint(color);

		double lowerX = chart.getXYPlot().getDomainAxis().getLowerBound();
		double upperX = chart.getXYPlot().getDomainAxis().getUpperBound();
		double xPercent = upperX == lowerX ? 50 : (pointerX - lowerX) * 100 / (upperX - lowerX);
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
		});
		parameterFreqRange.addListener(restartHackrf);
		// update waterfall and persistent display compressed ranges when freqRange changes
		parameterFreqRange.addListener(() -> {
			int[] pairs = parseRangePairs(parameterFreqRange.getValue());
			waterfallPlot.setRangePairs(pairs, parameterFreqShift.getValue());
			persistentDisplay.setRangePairs(pairs, parameterFreqShift.getValue());
		});
		parameterIsCapturingPaused.addListener(this::fireCapturingStateChanged);
		
		parameterIsRecordedVideo.addListener(this::startCaptureVideo);
		parameterIsRecordedData.addListener(this::startCaptureData);

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
			int[] pairs = parseRangePairs(parameterFreqRange.getValue());
			if (pairs != null && pairs.length > 2) {
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
						((long) (s + parameterFreqShift.getValue())) * 1000000l,
						((long) (e + parameterFreqShift.getValue())) * 1000000l,
						Color.white, Color.green);
					g.drawImage(part, x, 0, null);
					x += partW;
				}
				g.dispose();
				imageFrequencyAllocationTableBands = out;
			} else {
				imageFrequencyAllocationTableBands = activeTable.drawAllocationTable(width, height, alphaFreqAllocationTableBandsImage,
						(getFreq().getStartMHz() + parameterFreqShift.getValue()) * 1000000l,
						(getFreq().getEndMHz() + parameterFreqShift.getValue()) * 1000000l,
						Color.white, Color.green);
			}
		}
	}
}

