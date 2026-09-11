package jspectrumanalyzer.iq;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

final class SignalQualityTesterFrame extends JFrame {
	private static final int MAX_SAMPLES = 4096;
	private static final int SPECTRUM_SIZE = 1024;
	private static final int DAB_BANDWIDTH_HZ = 1_536_000;
	private static final int DAB_BASE_SAMPLE_RATE_HZ = 2_048_000;
	private static final int DAB_MODE_I_NULL_SAMPLES = 2656;
	private static final int DAB_MODE_I_USEFUL_SAMPLES = 2048;
	private static final int DAB_MODE_I_GUARD_SAMPLES = 504;
	private static final int DAB_MODE_I_ACTIVE_CARRIERS = 1536;
	private static final int DAB_CONSTELLATION_CARRIER_STEP = 8;
	/* 100 ms even at 20 MS/s: LTE PBCH has a 40 ms scrambling cycle. */
	private static final int DAB_HISTORY_BYTES = 4_000_000;
	private static final long MIN_ANALYSIS_INTERVAL_NANOS = 50_000_000L;
	private static final long DAB_MIN_ANALYSIS_INTERVAL_NANOS = 100_000_000L;
	private static final long GSM_MIN_ANALYSIS_INTERVAL_NANOS = 500_000_000L;
	private static final Color PANEL_BG = Color.BLACK;
	private static final Color TEXT_FG = Color.WHITE;
	private static final Color MUTED_FG = new Color(0xdddddd);
	private static final Color GRID = new Color(0x2d2d2d);
	private static final Color POINT = new Color(0x55ccff);
	private static final Color QUALITY_GOOD = new Color(0x44cc44);
	private static final Color QUALITY_WARN = new Color(0xffcc33);
	private static final Color QUALITY_BAD = new Color(0xff5555);

	private final ScatterPanel scatterPanel = new ScatterPanel();
	private final SpectrumPanel spectrumPanel = new SpectrumPanel();
	private final DabConstellationPanel dabConstellationPanel = new DabConstellationPanel();
	private final Dvbt2P2Panel dvbt2P2Panel = new Dvbt2P2Panel();
	private final QualityPanel qualityPanel = new QualityPanel();
	private final DabLockPanel dabLockPanel = new DabLockPanel();
	private final Dvbt2CrcPanel dvbt2CrcPanel = new Dvbt2CrcPanel();
	private final JLabel dvbt2PreLabel = new JLabel();
	private final JLabel dvbt2PostLabel = new JLabel();
	private final JLabel gsmParameterLabel = new JLabel();
	private final JLabel lteParameterLabel = new JLabel();
	private final JLabel nrParameterLabel = new JLabel();
	private final JLabel umtsParameterLabel = new JLabel();
	private final LteCellTablePanel lteCellTablePanel = new LteCellTablePanel();
	private final NrCellTablePanel nrCellTablePanel = new NrCellTablePanel();
	private final UmtsCellTablePanel umtsCellTablePanel = new UmtsCellTablePanel();
	private final LteConstellationPanel lteConstellationPanel = new LteConstellationPanel();
	private final JLabel titleLabel;
	private final JLabel statsLabel = new JLabel("Waiting for IQ samples...");
	private final JLabel detailLabel = new JLabel("RAW quality waits for signal level, clipping, DC offset and stability");
	private final Timer repaintTimer;
	private final boolean dabMode;
	private final boolean dvbtMode;
	private final boolean dvbt2Mode;
	private final boolean gsmMode;
	private final boolean rdsMode;
	private final boolean lteMode;
	private final boolean nrMode;
	private final boolean umtsMode;
	private final DvbtSignalAnalyzer dvbtAnalyzer = new DvbtSignalAnalyzer();
	private final Dvbt2SignalAnalyzer dvbt2Analyzer = new Dvbt2SignalAnalyzer();
	private final GsmSignalAnalyzer gsmAnalyzer = new GsmSignalAnalyzer();
	private final RdsSignalAnalyzer rdsAnalyzer = new RdsSignalAnalyzer();
	private final LteSignalAnalyzer lteAnalyzer = new LteSignalAnalyzer();
	private final NrSignalAnalyzer nrAnalyzer = new NrSignalAnalyzer();
	private final UmtsSignalAnalyzer umtsAnalyzer = new UmtsSignalAnalyzer();
	private volatile boolean gsmAnalysisRunning;
	private volatile GsmSignalAnalyzer.Result latestGsmResult;
	private volatile boolean rdsAnalysisRunning;
	private volatile RdsSignalAnalyzer.Result latestRdsResult;
	private String lastRdsDecodedSummary = "";
	private long lastRdsChangeMillis;
	private volatile boolean lteAnalysisRunning;
	private volatile LteSignalAnalyzer.Result latestLteResult;
	private volatile boolean nrAnalysisRunning;
	private volatile NrSignalAnalyzer.Result latestNrResult;
	private volatile boolean umtsAnalysisRunning;
	private volatile UmtsSignalAnalyzer.Result latestUmtsResult;
	private final Map<Integer,LteSignalAnalyzer.Candidate> lteCells=new LinkedHashMap<Integer,LteSignalAnalyzer.Candidate>();
	private final Map<Integer,NrSignalAnalyzer.Result> nrCells=new LinkedHashMap<Integer,NrSignalAnalyzer.Result>();
	private final Map<Integer,UmtsSignalAnalyzer.Candidate> umtsCells=new LinkedHashMap<Integer,UmtsSignalAnalyzer.Candidate>();
	private final Map<Integer,Double> umtsLoadPercent=new LinkedHashMap<Integer,Double>();
	private final Map<Integer,Double> nrLoadPercent=new LinkedHashMap<Integer,Double>();
	private final Map<Integer,LteSignalAnalyzer.SiData> lteSystemInformation=new LinkedHashMap<Integer,LteSignalAnalyzer.SiData>();
	private final Map<Integer,Long> ltePagingCounts=new LinkedHashMap<Integer,Long>();
	private final Map<String,Long> ltePagingSeen=new LinkedHashMap<String,Long>();
	private final Map<Integer,Double> lteLoadPercent=new LinkedHashMap<Integer,Double>();
	private GsmPagingStatsFrame gsmPagingFrame;
	private GsmBcchDetailsFrame gsmBcchFrame;
	private LteCellDetailsFrame lteCellDetailsFrame;
	private NrCellDetailsFrame nrCellDetailsFrame;
	private UmtsCellDetailsFrame umtsCellDetailsFrame;
	private UmtsPagingStatsFrame umtsPagingFrame;
	private volatile boolean dvbt2AnalysisRunning;
	private volatile Dvbt2SignalAnalyzer.Result latestDvbt2Result;
	private static final int DVBT2_SIGNALLING_CAPTURE_BYTES = 6_000_000;
	private static final int DVBT2_P1_SCAN_BYTES = 800_000;
	private static final int DVBT2_P1_OVERLAP_BYTES = 180_000;
	private byte[] dvbt2SignallingCapture;
	private int dvbt2SignallingCaptureLength;
	private volatile boolean dvbt2SignallingRunning;
	private volatile Dvbt2P2Decoder.Result dvbt2Signalling;
	private volatile Dvbt2P1Decoder.Decoded dvbt2P1Lock;
	private volatile long lastDvbt2P1RefreshNanos;
	private volatile long lastDvbt2L1PostRefreshNanos;
	private volatile String dvbt2SignallingState = "collecting DVB-T2 signalling IQ";
	private final boolean[] dvbt2PreCrcHistory = new boolean[32];
	private final boolean[] dvbt2PostCrcHistory = new boolean[32];
	private int dvbt2CrcIndex, dvbt2CrcCount, dvbt2PreCrcSuccess, dvbt2PostCrcSuccess;
	private static final int DVBT_TPS_CAPTURE_BYTES = 4_000_000;
	private byte[] dvbtTpsCapture;
	private int dvbtTpsCaptureLength;
	private volatile boolean dvbtTpsRunning;
	private volatile DvbtSignalAnalyzer.Result dvbtSignalling;

	private volatile Snapshot snapshot;
	private volatile long lastAnalysisNanos = 0;
	private double emaDbfs = Double.NaN;
	private double stabilityDb = 0;
	private double dabLockEma = Double.NaN;
	private final double[] dabCarrierLevelEma =
			new double[DAB_MODE_I_ACTIVE_CARRIERS / DAB_CONSTELLATION_CARRIER_STEP];
	private final byte[] dabHistory = new byte[DAB_HISTORY_BYTES];
	private int dabHistoryLength = 0;

	SignalQualityTesterFrame(String mode, Runnable closedCallback) {
		super("Signal Quality Tester");
		setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		dabMode = "DAB".equals(mode);
		dvbtMode = mode.startsWith("DVB-T ") && !mode.startsWith("DVB-T2");
		dvbt2Mode = mode.startsWith("DVB-T2");
		gsmMode = mode.startsWith("GSM");
		rdsMode = mode.startsWith("FM RDS");
		lteMode = mode.startsWith("LTE");
		nrMode = mode.startsWith("5G NR") || mode.startsWith("NR");
		umtsMode = mode.startsWith("UMTS") || mode.startsWith("WCDMA");
		if (gsmMode) {
			latestGsmResult = GsmSignalAnalyzer.Result.empty("collecting GSM channel IQ");
			prepareParameterLabel(gsmParameterLabel);
			gsmParameterLabel.setFont(gsmParameterLabel.getFont().deriveFont(12f));
		}
		if (rdsMode) {
			latestRdsResult = RdsSignalAnalyzer.Result.empty("collecting FM IQ");
			prepareParameterLabel(gsmParameterLabel);
			gsmParameterLabel.setFont(gsmParameterLabel.getFont().deriveFont(12f));
		}
		if (lteMode) {
			latestLteResult = LteSignalAnalyzer.Result.empty("collecting LTE IQ");
			prepareParameterLabel(lteParameterLabel);
			lteParameterLabel.setFont(lteParameterLabel.getFont().deriveFont(12f));
		}
		if (nrMode) {
			latestNrResult = NrSignalAnalyzer.Result.empty("collecting 5G NR IQ");
			prepareParameterLabel(nrParameterLabel);
			nrParameterLabel.setFont(nrParameterLabel.getFont().deriveFont(12f));
		}
		if (umtsMode) {
			latestUmtsResult = UmtsSignalAnalyzer.Result.empty("collecting UMTS IQ");
			prepareParameterLabel(umtsParameterLabel);
			umtsParameterLabel.setFont(umtsParameterLabel.getFont().deriveFont(12f));
		}
		if (dvbtMode) dvbtTpsCapture = new byte[DVBT_TPS_CAPTURE_BYTES];
		if (dvbt2Mode) {
			latestDvbt2Result = Dvbt2SignalAnalyzer.Result.empty("collecting DVB-T2 IQ");
			dvbt2SignallingCapture = new byte[DVBT2_SIGNALLING_CAPTURE_BYTES];
			detailLabel.setFont(detailLabel.getFont().deriveFont(11f));
			prepareParameterLabel(dvbt2PreLabel);
			prepareParameterLabel(dvbt2PostLabel);
		}

		titleLabel = new JLabel(mode + (dabMode || dvbtMode || dvbt2Mode || gsmMode || rdsMode || lteMode || nrMode || umtsMode ? " signal quality" : " raw IQ monitor"));
		titleLabel.setForeground(TEXT_FG);
		titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14f));
		statsLabel.setForeground(MUTED_FG);
		detailLabel.setForeground(MUTED_FG);

		JPanel header = new JPanel(new BorderLayout(8, 0));
		header.setBackground(PANEL_BG);
		header.setBorder(new EmptyBorder(8, 10, 6, 10));
		header.add(titleLabel, BorderLayout.WEST);
		header.add(statsLabel, BorderLayout.CENTER);
		if (gsmMode) {
			JPanel gsmButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
			gsmButtons.setOpaque(false);
			JButton bcchButton = new JButton("BCCH / CELL DETAILS");
			bcchButton.addActionListener(e -> openGsmBcchDetails());
			JButton pagingButton = new JButton("PAGING / STATS");
			pagingButton.addActionListener(e -> openGsmPagingStats());
			gsmButtons.add(bcchButton); gsmButtons.add(pagingButton);
			header.add(gsmButtons, BorderLayout.EAST);
		} else if (lteMode) {
			JPanel lteButtons=new JPanel(new FlowLayout(FlowLayout.RIGHT,5,0));
			lteButtons.setOpaque(false);
			JButton detailsButton=new JButton("CELL DETAILS");
			detailsButton.addActionListener(e -> openLteCellDetails());
			lteButtons.add(detailsButton);header.add(lteButtons,BorderLayout.EAST);
		} else if (nrMode) {
			JPanel nrButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
			nrButtons.setOpaque(false);
			JButton detailsButton = new JButton("CELL INFO");
			detailsButton.addActionListener(e -> openNrCellDetails());
			nrButtons.add(detailsButton);
			header.add(nrButtons, BorderLayout.EAST);
		} else if (umtsMode) {
			JPanel umtsButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
			umtsButtons.setOpaque(false);
			JButton detailsButton = new JButton("CELL DETAILS");
			detailsButton.addActionListener(e -> openUmtsCellDetails());
			JButton pagingButton = new JButton("PAGING / STATS");
			pagingButton.addActionListener(e -> openUmtsPagingStats());
			umtsButtons.add(detailsButton);
			umtsButtons.add(pagingButton);
			header.add(umtsButtons, BorderLayout.EAST);
		}

		JPanel footer = new JPanel(new BorderLayout(8, 4));
		footer.setBackground(PANEL_BG);
		footer.setBorder(new EmptyBorder(6, 10, 8, 10));
		JPanel qualityRows = new JPanel(new GridLayout(dvbt2Mode ? 3 : dabMode || dvbtMode || gsmMode || rdsMode || lteMode || nrMode || umtsMode ? 2 : 1, 1, 0, 2));
		qualityRows.setBackground(PANEL_BG);
		qualityRows.add(qualityPanel);
		if (dabMode || dvbtMode || dvbt2Mode || gsmMode || rdsMode || lteMode || nrMode || umtsMode) {
			qualityRows.add(dabLockPanel);
		}
		if (dvbt2Mode) qualityRows.add(dvbt2CrcPanel);
		footer.add(qualityRows, BorderLayout.NORTH);
		footer.add(detailLabel, BorderLayout.CENTER);

		scatterPanel.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
		spectrumPanel.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
		dabConstellationPanel.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
		dvbt2P2Panel.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
		JPanel plots = new JPanel(new GridLayout(1, 2, 6, 0));
		plots.setBackground(PANEL_BG);
		plots.setBorder(new EmptyBorder(0, 8, 0, 8));
		if (dvbt2Mode) {
			plots.add(createDvbt2Column(dvbt2P2Panel, dvbt2PreLabel));
			plots.add(createDvbt2Column(dabConstellationPanel, dvbt2PostLabel));
		} else if (gsmMode || rdsMode) {
			gsmParameterLabel.setBorder(BorderFactory.createCompoundBorder(
					BorderFactory.createLineBorder(Color.DARK_GRAY), new EmptyBorder(14, 18, 14, 18)));
			plots.add(gsmParameterLabel);
		} else if (lteMode) {
			lteParameterLabel.setBorder(BorderFactory.createCompoundBorder(
					BorderFactory.createLineBorder(Color.DARK_GRAY), new EmptyBorder(14, 18, 14, 18)));
			JPanel lteColumn=new JPanel(new BorderLayout(0,6));
			lteColumn.setBackground(PANEL_BG);
			lteColumn.add(lteParameterLabel,BorderLayout.NORTH);
			lteColumn.add(lteCellTablePanel,BorderLayout.CENTER);
			plots.add(lteColumn);
		} else if (nrMode) {
			nrParameterLabel.setBorder(BorderFactory.createCompoundBorder(
					BorderFactory.createLineBorder(Color.DARK_GRAY), new EmptyBorder(10, 18, 10, 18)));
			JPanel nrColumn = new JPanel(new GridLayout(2, 1, 0, 6));
			nrColumn.setBackground(PANEL_BG);
			nrColumn.add(nrParameterLabel);
			nrColumn.add(nrCellTablePanel);
			plots.add(nrColumn);
		} else if (umtsMode) {
			umtsParameterLabel.setBorder(BorderFactory.createCompoundBorder(
					BorderFactory.createLineBorder(Color.DARK_GRAY), new EmptyBorder(10, 18, 10, 18)));
			JPanel umtsColumn = new JPanel(new GridLayout(2, 1, 0, 6));
			umtsColumn.setBackground(PANEL_BG);
			umtsColumn.add(umtsParameterLabel);
			umtsColumn.add(umtsCellTablePanel);
			plots.add(umtsColumn);
		} else {
			plots.add(scatterPanel);
			plots.add(dabMode || dvbtMode ? dabConstellationPanel : spectrumPanel);
		}

		add(header, BorderLayout.NORTH);
		add(plots, BorderLayout.CENTER);
		add(footer, BorderLayout.SOUTH);
		setSize(dvbt2Mode ? 1100 : 760, dvbt2Mode ? 650 : lteMode ? 570 : nrMode || umtsMode ? 500 : gsmMode || rdsMode ? 445 : 420);

		repaintTimer = new Timer(100, e -> updateView());
		repaintTimer.start();
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosed(WindowEvent e) {
				repaintTimer.stop();
				if (gsmPagingFrame != null) gsmPagingFrame.dispose();
				if (gsmBcchFrame != null) gsmBcchFrame.dispose();
				if (lteCellDetailsFrame != null) lteCellDetailsFrame.dispose();
				if (nrCellDetailsFrame != null) nrCellDetailsFrame.dispose();
				if (umtsCellDetailsFrame != null) umtsCellDetailsFrame.dispose();
				if (umtsPagingFrame != null) umtsPagingFrame.dispose();
				if (closedCallback != null) {
					closedCallback.run();
				}
			}
		});
	}

	private void openLteCellDetails() {
		if(lteCellDetailsFrame==null||!lteCellDetailsFrame.isDisplayable())
			lteCellDetailsFrame=new LteCellDetailsFrame(
					() -> lteCellTablePanel.selectedPci(),
					pci -> lteCells.get(pci),
					pci -> lteSystemInformation.get(pci),
					pci -> ltePagingCounts.containsKey(pci)?ltePagingCounts.get(pci):0L,
					pci -> lteLoadPercent.containsKey(pci)?lteLoadPercent.get(pci):Double.NaN);
		lteCellDetailsFrame.setLocationRelativeTo(this);
		lteCellDetailsFrame.setVisible(true);
		lteCellDetailsFrame.toFront();
	}

	private void openNrCellDetails() {
		if (nrCellDetailsFrame == null || !nrCellDetailsFrame.isDisplayable())
			nrCellDetailsFrame = new NrCellDetailsFrame(
					() -> nrCellTablePanel.selectedPci(),
					pci -> nrCells.get(pci),
					pci -> nrLoadPercent.containsKey(pci) ? nrLoadPercent.get(pci) : Double.NaN,
					() -> snapshot == null ? -1L : snapshot.centerFreqHz);
		nrCellDetailsFrame.setLocationRelativeTo(this);
		nrCellDetailsFrame.setVisible(true);
		nrCellDetailsFrame.toFront();
	}

	private void openUmtsCellDetails() {
		if (umtsCellDetailsFrame == null || !umtsCellDetailsFrame.isDisplayable())
			umtsCellDetailsFrame = new UmtsCellDetailsFrame(
					() -> umtsCellTablePanel.selectedPsc(),
					psc -> umtsCells.get(psc),
					() -> snapshot == null ? -1L : snapshot.centerFreqHz,
					psc -> umtsLoadPercent.containsKey(psc) ? umtsLoadPercent.get(psc) : Double.NaN);
		umtsCellDetailsFrame.setLocationRelativeTo(this);
		umtsCellDetailsFrame.setVisible(true);
		umtsCellDetailsFrame.toFront();
	}

	private void openUmtsPagingStats() {
		if (umtsPagingFrame == null || !umtsPagingFrame.isDisplayable())
			umtsPagingFrame = new UmtsPagingStatsFrame(() ->
					umtsCells.get(umtsCellTablePanel.selectedPsc()));
		umtsPagingFrame.setLocationRelativeTo(this);
		umtsPagingFrame.setVisible(true);
		umtsPagingFrame.toFront();
	}

	private void openGsmPagingStats() {
		if (gsmPagingFrame == null || !gsmPagingFrame.isDisplayable())
			gsmPagingFrame = new GsmPagingStatsFrame(() -> latestGsmResult);
		gsmPagingFrame.setLocationRelativeTo(this);
		gsmPagingFrame.setVisible(true);
		gsmPagingFrame.toFront();
	}

	private void openGsmBcchDetails() {
		if (gsmBcchFrame == null || !gsmBcchFrame.isDisplayable())
			gsmBcchFrame = new GsmBcchDetailsFrame(() -> latestGsmResult,
					() -> snapshot == null ? -1L : snapshot.centerFreqHz,
					() -> snapshot == null ? -120d : snapshot.dbfs);
		gsmBcchFrame.setLocationRelativeTo(this);
		gsmBcchFrame.setVisible(true);
		gsmBcchFrame.toFront();
	}

	private void prepareParameterLabel(JLabel label) {
		label.setOpaque(true);
		label.setBackground(PANEL_BG);
		label.setForeground(MUTED_FG);
		label.setFont(new Font("Helvetica", Font.PLAIN, 10));
		label.setVerticalAlignment(SwingConstants.TOP);
		label.setBorder(new EmptyBorder(6, 8, 4, 8));
		label.setPreferredSize(new Dimension(100, 250));
	}

	private JPanel createDvbt2Column(JPanel graph, JLabel parameters) {
		JPanel column = new JPanel(new BorderLayout());
		column.setBackground(PANEL_BG);
		column.add(graph, BorderLayout.CENTER);
		column.add(parameters, BorderLayout.SOUTH);
		return column;
	}

	void offerIQBlock(long centerFreqHz, int sampleRateHz, byte[] iqData, int length) {
		if (iqData == null || length < 2) {
			return;
		}
		int evenLength = Math.min(length, iqData.length) & ~1;
		int totalSamples = evenLength / 2;
		if (totalSamples <= 0) {
			return;
		}
		if (dvbtMode) offerDvbtTpsCapture(sampleRateHz, iqData, evenLength);
		if (dvbt2Mode) offerDvbt2SignallingCapture(sampleRateHz, iqData, evenLength);
		byte[] dabAnalysisData = iqData;
		int dabAnalysisLength = evenLength;
		if (dabMode || dvbtMode || dvbt2Mode || gsmMode || rdsMode || lteMode || nrMode || umtsMode) {
			dabAnalysisLength = appendDabHistory(iqData, evenLength);
			dabAnalysisData = dabHistory;
		}
		long now = System.nanoTime();
		long interval = gsmMode || rdsMode || lteMode || nrMode || umtsMode ? GSM_MIN_ANALYSIS_INTERVAL_NANOS
				: dabMode || dvbtMode || dvbt2Mode ? DAB_MIN_ANALYSIS_INTERVAL_NANOS : MIN_ANALYSIS_INTERVAL_NANOS;
		if (now - lastAnalysisNanos < interval) {
			return;
		}
		lastAnalysisNanos = now;
		int sampleCount = Math.min(MAX_SAMPLES, totalSamples);
		int stride = Math.max(1, totalSamples / sampleCount);
		byte[] samples = new byte[sampleCount * 2];
		long sumPower = 0;
		long sumI = 0;
		long sumQ = 0;
		int clipped = 0;
		int peak = 0;
		int out = 0;
		for (int sample = 0; sample < totalSamples && out + 1 < samples.length; sample += stride) {
			int offset = sample * 2;
			int i = iqData[offset];
			int q = iqData[offset + 1];
			samples[out++] = (byte) i;
			samples[out++] = (byte) q;
			sumPower += i * i + q * q;
			sumI += i;
			sumQ += q;
			if (Math.abs(i) >= 126 || Math.abs(q) >= 126) {
				clipped++;
			}
			peak = Math.max(peak, Math.max(Math.abs(i), Math.abs(q)));
		}
		if (out != samples.length) {
			byte[] trimmed = new byte[out];
			System.arraycopy(samples, 0, trimmed, 0, out);
			samples = trimmed;
			sampleCount = out / 2;
		}
		double rms = sampleCount <= 0 ? 0 : Math.sqrt(sumPower / (sampleCount * 2d));
		double dbfs = rms <= 0 ? -120d : 20d * Math.log10(rms / 128d);
		double meanI = sampleCount <= 0 ? 0 : sumI / (double) sampleCount;
		double meanQ = sampleCount <= 0 ? 0 : sumQ / (double) sampleCount;
		double dcPercent = Math.sqrt(meanI * meanI + meanQ * meanQ) / 128d * 100d;
		double clippingPercent = sampleCount <= 0 ? 0 : clipped * 100d / sampleCount;
		double quality = calculateRawQuality(dbfs, clippingPercent, dcPercent);
		float[] spectrum = gsmMode || lteMode || nrMode || umtsMode ? new float[0] : computeSpectrum(iqData, totalSamples);
		DabMetrics dabMetrics = dabMode ? computeDabMetrics(spectrum, sampleRateHz) : null;
		if (dabMetrics != null) {
			dabMetrics.constellation = computeDabConstellation(dabAnalysisData, dabAnalysisLength, sampleRateHz,
					dabMetrics);
		}
		DvbtSignalAnalyzer.Result dvbtResult = dvbtMode
				? dvbtAnalyzer.analyze(dabAnalysisData, dabAnalysisLength, sampleRateHz) : null;
		if (dvbtResult != null) dvbtResult = dvbtResult.withSignalling(dvbtSignalling);
		if (dvbt2Mode) scheduleDvbt2Analysis(dabAnalysisData, dabAnalysisLength, sampleRateHz);
		Dvbt2SignalAnalyzer.Result dvbt2Result = dvbt2Mode ? latestDvbt2Result : null;
		if (gsmMode) scheduleGsmAnalysis(dabAnalysisData, dabAnalysisLength, sampleRateHz);
		GsmSignalAnalyzer.Result gsmResult = gsmMode ? latestGsmResult : null;
		if (rdsMode) scheduleRdsAnalysis(dabAnalysisData, dabAnalysisLength, sampleRateHz);
		RdsSignalAnalyzer.Result rdsResult = rdsMode ? latestRdsResult : null;
		if (lteMode) scheduleLteAnalysis(dabAnalysisData, dabAnalysisLength, sampleRateHz);
		LteSignalAnalyzer.Result lteResult = lteMode ? latestLteResult : null;
		if (nrMode) scheduleNrAnalysis(dabAnalysisData, dabAnalysisLength, sampleRateHz);
		NrSignalAnalyzer.Result nrResult = nrMode ? latestNrResult : null;
		if (umtsMode) scheduleUmtsAnalysis(dabAnalysisData, dabAnalysisLength, sampleRateHz);
		UmtsSignalAnalyzer.Result umtsResult = umtsMode ? latestUmtsResult : null;
		snapshot = new Snapshot(centerFreqHz, sampleRateHz, samples, sampleCount, dbfs, peak, dcPercent,
				clippingPercent, stabilityDb, quality, spectrum, dabMetrics, dvbtResult, dvbt2Result, gsmResult, rdsResult, lteResult, nrResult, umtsResult,
				System.currentTimeMillis());
	}

	private synchronized void scheduleUmtsAnalysis(byte[] iqData, int length, int sampleRateHz) {
		int minimumBytes = Math.max(2, sampleRateHz / 50);
		if (umtsAnalysisRunning || length < minimumBytes) return;
		int wanted = Math.min(length & ~1, Math.max(262_144, sampleRateHz / 5));
		final byte[] capture = new byte[wanted];
		System.arraycopy(iqData, (length - wanted) & ~1, capture, 0, wanted);
		umtsAnalysisRunning = true;
		Thread worker = new Thread(() -> {
			try { latestUmtsResult = umtsAnalyzer.analyze(capture, capture.length, sampleRateHz); }
			finally { umtsAnalysisRunning = false; }
		}, "UMTS CPICH PSC search");
		worker.setDaemon(true); worker.setPriority(Thread.MIN_PRIORITY); worker.start();
	}

	private synchronized void scheduleNrAnalysis(byte[] iqData, int length, int sampleRateHz) {
		int minimumBytes = Math.max(2, sampleRateHz / 50);
		if (nrAnalysisRunning || length < minimumBytes) return;
		int wanted = Math.min(length & ~1, Math.max(131_072, sampleRateHz * 16 / 100));
		final byte[] capture = new byte[wanted];
		System.arraycopy(iqData, (length - wanted) & ~1, capture, 0, wanted);
		nrAnalysisRunning = true;
		Thread worker = new Thread(() -> {
			try { latestNrResult = nrAnalyzer.analyze(capture, capture.length, sampleRateHz); }
			finally { nrAnalysisRunning = false; }
		}, "5G NR SSB cell search");
		worker.setDaemon(true); worker.setPriority(Thread.MIN_PRIORITY); worker.start();
	}

	private synchronized void scheduleLteAnalysis(byte[] iqData, int length, int sampleRateHz) {
		int minimumBytes=Math.max(2,sampleRateHz/40); // 12.5 ms of stereo 8-bit IQ
		if (lteAnalysisRunning || length < minimumBytes) return;
		/* 50 ms contains the complete 40 ms PBCH scrambling cycle plus enough
		 * margin to find the next frame boundary, without delaying live refresh. */
		int wanted=Math.min(length & ~1, Math.max(65_536, sampleRateHz/10));
		final byte[] capture=new byte[wanted];
		System.arraycopy(iqData,(length-wanted)&~1,capture,0,wanted);
		lteAnalysisRunning=true;
		Thread worker=new Thread(() -> {
			try { latestLteResult=lteAnalyzer.analyze(capture,capture.length,sampleRateHz); }
			finally { lteAnalysisRunning=false; }
		},"LTE PSS cell search");
		worker.setDaemon(true); worker.setPriority(Thread.MIN_PRIORITY); worker.start();
	}

	private synchronized void scheduleGsmAnalysis(byte[] iqData, int length, int sampleRateHz) {
		if (gsmAnalysisRunning || length < 2) return;
		/* 350 ms spans an entire GSM 51-multiframe, so a capture containing
		 * any FCCH/SCH pair also reaches the BCCH frames. */
		int wanted = Math.min(length & ~1, Math.max(32_768, sampleRateHz * 7 / 10));
		final byte[] capture = new byte[wanted];
		System.arraycopy(iqData, (length - wanted) & ~1, capture, 0, wanted);
		gsmAnalysisRunning = true;
		Thread worker = new Thread(() -> {
			try {
				latestGsmResult = gsmAnalyzer.analyze(capture, capture.length, sampleRateHz);
			} finally {
				gsmAnalysisRunning = false;
			}
		}, "GSM FCCH analyzer");
		worker.setDaemon(true);
		worker.setPriority(Thread.MIN_PRIORITY);
		worker.start();
	}

	private synchronized void scheduleRdsAnalysis(byte[] iqData, int length, int sampleRateHz) {
		if (rdsAnalysisRunning || length < 2) return;
		int wanted = Math.min(length & ~1, Math.max(131_072, sampleRateHz * 4));
		final byte[] capture = new byte[wanted];
		System.arraycopy(iqData, (length - wanted) & ~1, capture, 0, wanted);
		rdsAnalysisRunning = true;
		Thread worker = new Thread(() -> {
			try {
				latestRdsResult = rdsAnalyzer.analyze(capture, capture.length, sampleRateHz);
			} finally {
				rdsAnalysisRunning = false;
			}
		}, "FM RDS analyzer");
		worker.setDaemon(true);
		worker.setPriority(Thread.MIN_PRIORITY);
		worker.start();
	}

	private synchronized void scheduleDvbt2Analysis(byte[] iqData, int length, int sampleRateHz) {
		/* L1 acquisition is deliberately exclusive because it scans a much longer
		 * capture. Once L1 is locked it never blocks the lightweight live monitor. */
		if (dvbt2AnalysisRunning || (dvbt2Signalling == null && dvbt2SignallingRunning) || length < 2) return;
		final byte[] capture = new byte[length & ~1];
		System.arraycopy(iqData, 0, capture, 0, capture.length);
		dvbt2AnalysisRunning = true;
		Thread worker = new Thread(() -> {
			try {
				Dvbt2SignalAnalyzer.Result result = dvbt2Analyzer.analyze(capture, capture.length, sampleRateHz);
				latestDvbt2Result = dvbt2Signalling == null
						? result.waitingForSignalling(dvbt2SignallingState) : result.withSignalling(dvbt2Signalling);
			} finally {
				dvbt2AnalysisRunning = false;
			}
		}, "DVB-T2 constellation analyzer");
		worker.setDaemon(true);
		worker.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
		worker.start();
	}

	private synchronized void offerDvbt2SignallingCapture(int sampleRateHz, byte[] iqData, int length) {
		if (dvbt2SignallingCapture == null) return;
		if (dvbt2Signalling != null && (dvbt2SignallingRunning
				|| System.nanoTime() - lastDvbt2P1RefreshNanos < 100_000_000L)) return;
		int copy = Math.min(length, dvbt2SignallingCapture.length - dvbt2SignallingCaptureLength) & ~1;
		if (copy > 0) {
			/* Keep the capture temporally continuous. If only part of the incoming
			 * block fits, its beginning follows the data already accumulated. */
			System.arraycopy(iqData, 0, dvbt2SignallingCapture, dvbt2SignallingCaptureLength, copy);
			dvbt2SignallingCaptureLength += copy;
		}
		int required = Math.min(dvbt2SignallingCapture.length,
				Math.max(2_000_000, (int)Math.ceil(sampleRateHz * .27) * 2));
		if (dvbt2SignallingCaptureLength < required) return;
		if (dvbt2SignallingRunning) return;
		final byte[] capture = new byte[dvbt2SignallingCaptureLength];
		System.arraycopy(dvbt2SignallingCapture, 0, capture, 0, capture.length);
		dvbt2SignallingCaptureLength = 0;
		final Dvbt2P2Decoder.Result lockedParameters = dvbt2Signalling;
		final Dvbt2P1Decoder.Decoded knownP1 = dvbt2P1Lock;
		dvbt2SignallingRunning = true;
		if (lockedParameters != null) lastDvbt2P1RefreshNanos = System.nanoTime();
		dvbt2SignallingState = lockedParameters == null
				? "analyzing DVB-T2 P1/P2" : "refreshing DVB-T2 constellation";
		Thread worker = new Thread(() -> {
			try {
				if (lockedParameters != null && knownP1 != null) {
					int scanStep = DVBT2_P1_SCAN_BYTES - DVBT2_P1_OVERLAP_BYTES;
					for (int offset = 0; offset < capture.length; offset += scanStep) {
						int chunkLength = Math.min(DVBT2_P1_SCAN_BYTES, capture.length - offset) & ~1;
						if (chunkLength < 160_000) break;
						byte[] chunk = new byte[chunkLength];
						System.arraycopy(capture, offset, chunk, 0, chunkLength);
						Dvbt2P1Decoder.Result p1 = new Dvbt2P1Decoder().synchronize(chunk, chunk.length, sampleRateHz, knownP1);
						if (p1.decoded != null && p1.decoded.points.length > 0) {
							lockedParameters.p2Points = p1.decoded.points;
							long now = System.nanoTime();
							if (now - lastDvbt2L1PostRefreshNanos >= 1_000_000_000L) {
								lastDvbt2L1PostRefreshNanos = now;
								Dvbt2P2Decoder.Result fresh = new Dvbt2P2Decoder().refresh(
										chunk, chunk.length, sampleRateHz, p1.decoded, lockedParameters);
								if (fresh != null && fresh.l1PostValid && fresh.l1PostPoints.length > 0)
									lockedParameters.l1PostPoints = fresh.l1PostPoints;
							}
							dvbt2SignallingState = "DVB-T2 P1 realtime";
							latestDvbt2Result = latestDvbt2Result.withSignalling(lockedParameters);
							break;
						}
					}
					return;
				}
						Dvbt2P2Decoder.Result p2 = null;
				Dvbt2P1Decoder.Decoded p1ForLock = null;
				boolean p1Seen = false;
				int step = DVBT2_P1_SCAN_BYTES - DVBT2_P1_OVERLAP_BYTES;
				for (int offset = 0; offset < capture.length && p2 == null; offset += step) {
					int chunkLength = Math.min(DVBT2_P1_SCAN_BYTES, capture.length - offset) & ~1;
					if (chunkLength < 160_000) break;
					byte[] chunk = new byte[chunkLength];
					System.arraycopy(capture, offset, chunk, 0, chunkLength);
					Dvbt2P1Decoder p1Decoder = new Dvbt2P1Decoder();
					Dvbt2P1Decoder.Result p1 = lockedParameters != null && knownP1 != null
							? p1Decoder.synchronize(chunk, chunk.length, sampleRateHz, knownP1)
							: p1Decoder.detect(chunk, chunk.length, sampleRateHz);
					if (p1.decoded != null) {
						p1Seen = true;
						p1ForLock = p1.decoded;
						String preamble = p1.decoded.preamble == 0 ? "SISO"
								: p1.decoded.preamble == 1 ? "MISO" : "type " + p1.decoded.preamble;
						dvbt2SignallingState = lockedParameters == null
								? "DVB-T2 P1 " + preamble + " " + p1.decoded.fftMode + " locked; decoding L1"
								: "DVB-T2 P1 synced; refreshing constellation";
						Dvbt2P2Decoder decoder = new Dvbt2P2Decoder();
						p2 = lockedParameters == null
								? decoder.decode(chunk, chunk.length, sampleRateHz, p1.decoded)
								: decoder.refresh(chunk, chunk.length, sampleRateHz, p1.decoded, lockedParameters);
					}
				}
				if (p2 != null) {
					boolean currentPostValid = p2.l1PostValid;
					if (p1ForLock != null && p1ForLock.points.length > 0) p2.p2Points = p1ForLock.points;
					recordDvbt2Crc(true, currentPostValid);
					if (!currentPostValid && lockedParameters != null) p2.keepPostFrom(lockedParameters);
					dvbt2Signalling = p2;
					dvbt2Analyzer.setLockedParameters(p2);
					if (lockedParameters == null) dvbt2P1Lock = p1ForLock;
					dvbt2SignallingState = lockedParameters == null
							? "DVB-T2 L1 locked" : "DVB-T2 realtime P2";
					latestDvbt2Result = latestDvbt2Result.withSignalling(p2);
				} else {
					recordDvbt2Crc(false, false);
					dvbt2SignallingState = lockedParameters != null
							? "DVB-T2 realtime frame missed — retrying"
							: p1Seen ? "DVB-T2 P1 found; L1 CRC failed — retrying"
							: "DVB-T2 P1 not found — retrying";
					if (lockedParameters == null)
						latestDvbt2Result = latestDvbt2Result.waitingForSignalling(dvbt2SignallingState);
				}
			} finally { dvbt2SignallingRunning = false; }
		}, "DVB-T2 P1/P2 signalling detector");
		worker.setDaemon(true);worker.setPriority(Thread.MIN_PRIORITY);worker.start();
	}

	private synchronized void recordDvbt2Crc(boolean preValid, boolean postValid) {
		if (dvbt2CrcCount == dvbt2PreCrcHistory.length) {
			if (dvbt2PreCrcHistory[dvbt2CrcIndex]) dvbt2PreCrcSuccess--;
			if (dvbt2PostCrcHistory[dvbt2CrcIndex]) dvbt2PostCrcSuccess--;
		} else {
			dvbt2CrcCount++;
		}
		dvbt2PreCrcHistory[dvbt2CrcIndex] = preValid;
		dvbt2PostCrcHistory[dvbt2CrcIndex] = postValid;
		if (preValid) dvbt2PreCrcSuccess++;
		if (postValid) dvbt2PostCrcSuccess++;
		dvbt2CrcIndex = (dvbt2CrcIndex + 1) % dvbt2PreCrcHistory.length;
	}

	private synchronized void offerDvbtTpsCapture(int sampleRateHz, byte[] iqData, int length) {
		if (dvbtSignalling != null || dvbtTpsRunning || dvbtTpsCapture == null) return;
		int required = Math.min(dvbtTpsCapture.length,
				Math.max(16_384, ((int) Math.ceil(sampleRateHz * 0.090)) * 2));
		int copy = Math.min(length, required - dvbtTpsCaptureLength) & ~1;
		if (copy > 0) {
			System.arraycopy(iqData, length - copy, dvbtTpsCapture, dvbtTpsCaptureLength, copy);
			dvbtTpsCaptureLength += copy;
		}
		if (dvbtTpsCaptureLength < required) return;
		final byte[] capture = new byte[required];
		System.arraycopy(dvbtTpsCapture, 0, capture, 0, required);
		dvbtTpsRunning = true;
		Thread worker = new Thread(() -> {
			try {
				DvbtSignalAnalyzer.Result result = new DvbtSignalAnalyzer(true)
						.analyze(capture, capture.length, sampleRateHz);
				if (result != null && !"--".equals(result.codeRate)) {
					dvbtSignalling = result;
					synchronized (SignalQualityTesterFrame.this) { dvbtTpsCapture = null; }
				} else {
					synchronized (SignalQualityTesterFrame.this) { dvbtTpsCaptureLength = 0; }
				}
			} finally {
				dvbtTpsRunning = false;
			}
		}, "DVB-T TPS detector");
		worker.setDaemon(true);
		worker.setPriority(Thread.MIN_PRIORITY);
		worker.start();
	}

	private int appendDabHistory(byte[] iqData, int length) {
		int copyLength = Math.min(length, dabHistory.length) & ~1;
		if (copyLength <= 0) {
			return dabHistoryLength;
		}
		if (copyLength >= dabHistory.length) {
			System.arraycopy(iqData, length - copyLength, dabHistory, 0, copyLength);
			dabHistoryLength = copyLength;
			return dabHistoryLength;
		}
		int overflow = Math.max(0, dabHistoryLength + copyLength - dabHistory.length);
		if (overflow > 0) {
			System.arraycopy(dabHistory, overflow, dabHistory, 0, dabHistoryLength - overflow);
			dabHistoryLength -= overflow;
		}
		System.arraycopy(iqData, length - copyLength, dabHistory, dabHistoryLength, copyLength);
		dabHistoryLength += copyLength;
		return dabHistoryLength & ~1;
	}

	private DabMetrics computeDabMetrics(float[] spectrum, int sampleRateHz) {
		if (spectrum == null || spectrum.length == 0 || sampleRateHz <= DAB_BANDWIDTH_HZ) {
			return new DabMetrics(0, 0, 0, 0, 0, "need >= 1.536 MS/s");
		}
		int size = spectrum.length;
		double binHz = sampleRateHz / (double) size;
		int center = size / 2;
		int halfDabBins = Math.max(2, (int) Math.round(DAB_BANDWIDTH_HZ * 0.5d / binHz));
		int left = Math.max(0, center - halfDabBins);
		int right = Math.min(size - 1, center + halfDabBins);
		int guardBins = Math.max(2, (int) Math.round(80_000d / binHz));

		Average inside = averageDb(spectrum, left + guardBins, right - guardBins);
		Average outsideLeft = averageDb(spectrum, 8, Math.max(8, left - guardBins));
		Average outsideRight = averageDb(spectrum, Math.min(size - 8, right + guardBins), size - 8);
		double outsideDb;
		if (outsideLeft.count > 0 && outsideRight.count > 0) {
			outsideDb = (outsideLeft.db + outsideRight.db) * 0.5d;
		} else if (outsideLeft.count > 0) {
			outsideDb = outsideLeft.db;
		} else if (outsideRight.count > 0) {
			outsideDb = outsideRight.db;
		} else {
			outsideDb = inside.db - 3d;
		}

		Average leftInside = averageDb(spectrum, left + guardBins, center - guardBins);
		Average rightInside = averageDb(spectrum, center + guardBins, right - guardBins);
		double contrastDb = inside.db - outsideDb;
		double balanceDb = Math.abs(leftInside.db - rightInside.db);
		double offsetHz = estimateSpectralOffsetHz(spectrum, left + guardBins, right - guardBins, binHz, center);

		double contrastEvidence = clamp01((contrastDb - 0.8d) / 8d);
		double centerScore = clamp01(1d - Math.abs(offsetHz) / 250_000d);
		double balanceScore = clamp01(1d - balanceDb / 8d);
		double score = contrastEvidence * (60d + centerScore * 25d + balanceScore * 15d);
		String state;
		if (score >= 70d) {
			state = "DAB-like";
		} else if (score >= 45d) {
			state = "possible DAB";
		} else {
			state = "weak/no DAB shape";
		}
		return new DabMetrics(score, contrastDb, offsetHz, balanceDb, DAB_BANDWIDTH_HZ, state);
	}

	private DabConstellation computeDabConstellation(byte[] iqData, int length, int sampleRateHz, DabMetrics metrics) {
		int totalSamples = length / 2;
		if (sampleRateHz < 1_800_000 || totalSamples < 10_000) {
			return new DabConstellation(new float[0], 0, 0, "need wider DAB IQ");
		}
		double scale = sampleRateHz / (double) DAB_BASE_SAMPLE_RATE_HZ;
		int nullSamples = Math.max(64, (int) Math.round(DAB_MODE_I_NULL_SAMPLES * scale));
		int usefulSamples = Math.max(256, (int) Math.round(DAB_MODE_I_USEFUL_SAMPLES * scale));
		int guardSamples = Math.max(16, (int) Math.round(DAB_MODE_I_GUARD_SAMPLES * scale));
		int symbolSamples = usefulSamples + guardSamples;
		double exactSymbolSamples = (DAB_MODE_I_USEFUL_SAMPLES + DAB_MODE_I_GUARD_SAMPLES) * scale;
		int required = nullSamples + guardSamples + usefulSamples + symbolSamples * 3;
		if (totalSamples < required) {
			return new DabConstellation(new float[0], 0, 0, "need longer block");
		}

		int latestUsableNull = totalSamples - required;
		NullSearch nullSearch = findDabNull(iqData, totalSamples, nullSamples, latestUsableNull);
		int expectedUsefulStart = nullSearch.startSample + nullSamples + guardSamples;
		SymbolTiming symbolTiming = refineDabSymbolTiming(iqData, totalSamples, expectedUsefulStart,
				usefulSamples, guardSamples);

		int carrierStep = DAB_CONSTELLATION_CARRIER_STEP;
		int displaySymbol = 2;
		int[] symbolStarts = new int[displaySymbol + 1];
		symbolStarts[0] = symbolTiming.usefulStart;
		double timingCorrelation = symbolTiming.correlation;
		double correctionBins = symbolTiming.correctionBins;
		for (int symbol = 1; symbol <= displaySymbol; symbol++) {
			int expectedStart = symbolTiming.usefulStart + (int) Math.round(symbol * exactSymbolSamples);
			SymbolTiming refined = refineDabSymbolTiming(iqData, totalSamples, expectedStart,
					usefulSamples, guardSamples);
			symbolStarts[symbol] = refined.usefulStart;
			timingCorrelation += refined.correlation;
			correctionBins += refined.correctionBins;
		}
		timingCorrelation /= symbolStarts.length;
		correctionBins /= symbolStarts.length;
		int fineTiming = Math.max(1, (int) Math.round(sampleRateHz / 256_000d));
		int[] timingOffsets = {-fineTiming, 0, fineTiming};
		ConstellationCandidate best = null;
		for (int timingOffset : timingOffsets) {
			ConstellationCandidate candidate = buildDabConstellationCandidate(iqData, symbolStarts,
					usefulSamples, timingOffset, carrierStep, displaySymbol, correctionBins);
			if (best == null || candidate.symmetryScore > best.symmetryScore) {
				best = candidate;
			}
		}
		float[] points = best == null ? new float[0] : best.points;
		double symmetryScore = best == null ? 0 : best.symmetryScore;
		double nullScore = clamp01((nullSearch.ratio - 1.15d) / 2d);
		double shapeScore = clamp01(metrics.score / 100d);
		double symmetryEvidence = clamp01((symmetryScore - 0.08d) / 0.72d);
		double timingEvidence = clamp01((timingCorrelation - 0.08d) / 0.5d);
		double rawLock = Math.max(0, Math.min(100,
				symmetryEvidence * 60d + timingEvidence * 20d + nullScore * 15d + shapeScore * 5d));
		if (metrics.contrastDb < 4d || nullSearch.ratio < 1.5d) {
			rawLock = 0d;
		}
		if (rawLock == 0d) {
			dabLockEma = 0d;
		} else if (Double.isNaN(dabLockEma)) {
			dabLockEma = rawLock;
		} else {
			dabLockEma = dabLockEma * 0.7d + rawLock * 0.3d;
		}
		double lock = dabLockEma;
		String state = lock >= 55d ? "DAB OFDM candidate" : lock >= 35d ? "weak OFDM candidate" : "searching OFDM";
		return new DabConstellation(points, lock, nullSearch.ratio, state,
				timingCorrelation, correctionBins * 1000d);
	}

	private ConstellationCandidate buildDabConstellationCandidate(byte[] iqData, int[] symbolStarts,
			int usefulSamples, int timingOffset, int carrierStep, int displaySymbol, double correctionBins) {
		int maxPairs = DAB_MODE_I_ACTIVE_CARRIERS / carrierStep;
		float[] points = new float[maxPairs * 2];
		float[] magnitudes = new float[maxPairs];
		int[] carriers = new int[maxPairs];
		int out = 0;
		int pointCount = 0;
		double sumCarrierMagnitude = 0d;
		int previousStart = symbolStarts[displaySymbol - 1] + timingOffset;
		int currentStart = symbolStarts[displaySymbol] + timingOffset;
			for (int carrier = -DAB_MODE_I_ACTIVE_CARRIERS / 2; carrier <= DAB_MODE_I_ACTIVE_CARRIERS / 2; carrier += carrierStep) {
				if (carrier == 0) {
					continue;
				}
				Complex previous = dftCarrier(iqData, previousStart, usefulSamples, carrier, correctionBins);
				Complex current = dftCarrier(iqData, currentStart, usefulSamples, carrier, correctionBins);
				double real = current.real * previous.real + current.imag * previous.imag;
				double imag = current.imag * previous.real - current.real * previous.imag;
				double magnitude = Math.sqrt(real * real + imag * imag);
				if (magnitude <= 1e-9 || out + 1 >= points.length) {
					continue;
				}
				double normalizedReal = real / magnitude;
				double normalizedImag = imag / magnitude;
				points[out++] = (float) normalizedReal;
				points[out++] = (float) normalizedImag;
				double currentMagnitude = Math.sqrt(current.real * current.real + current.imag * current.imag);
				magnitudes[pointCount] = (float) currentMagnitude;
				carriers[pointCount] = carrier;
				pointCount++;
				sumCarrierMagnitude += currentMagnitude;
			}
		if (out != points.length) {
			float[] trimmed = new float[out];
			System.arraycopy(points, 0, trimmed, 0, out);
			points = trimmed;
		}
		double phaseSlope = estimateDabPhaseSlope(points, carriers, pointCount, carrierStep);
		double fourthReal = 0d;
		double fourthImag = 0d;
		for (int point = 0; point < pointCount; point++) {
			int offset = point * 2;
			double correction = -phaseSlope * carriers[point];
			double cos = Math.cos(correction);
			double sin = Math.sin(correction);
			double real = points[offset];
			double imag = points[offset + 1];
			points[offset] = (float) (real * cos - imag * sin);
			points[offset + 1] = (float) (real * sin + imag * cos);
			Complex fourth = fourthPower(points[offset], points[offset + 1]);
			fourthReal += fourth.real;
			fourthImag += fourth.imag;
		}
		int fourthCount = pointCount;
		double symmetryScore = fourthCount <= 0 ? 0
				: Math.sqrt(fourthReal * fourthReal + fourthImag * fourthImag) / fourthCount;
		if (points.length >= 2 && fourthCount > 0) {
			double rotation = Math.PI / 4d - Math.atan2(fourthImag, fourthReal) / 4d;
			double cos = Math.cos(rotation);
			double sin = Math.sin(rotation);
			double averageCarrierMagnitude = pointCount <= 0 ? 1d : sumCarrierMagnitude / pointCount;
			double phaseConfidence = clamp01((symmetryScore - 0.15d) / 0.65d);
			for (int i = 0; i + 1 < points.length; i += 2) {
				double real = points[i];
				double imag = points[i + 1];
				int point = i / 2;
				double currentMagnitude = point < pointCount ? magnitudes[point] : averageCarrierMagnitude;
				double noiseMagnitude = currentMagnitude / Math.max(1e-9d, averageCarrierMagnitude);
				double noiseRadius = 0.58d * Math.min(2d, noiseMagnitude);
				double carrierReference = point < dabCarrierLevelEma.length ? dabCarrierLevelEma[point] : 0d;
				if (phaseConfidence > 0.15d && point < dabCarrierLevelEma.length) {
					double alpha = carrierReference <= 0d || currentMagnitude > carrierReference * 2d ? 0.5d : 0.08d;
					carrierReference = carrierReference <= 0d
							? currentMagnitude
							: carrierReference * (1d - alpha) + currentMagnitude * alpha;
					dabCarrierLevelEma[point] = carrierReference;
				}
				double equalizedMagnitude = carrierReference <= 0d
						? 1d
						: currentMagnitude / Math.max(1e-9d, carrierReference);
				double equalizedRadius = 0.79d
						* Math.max(0.65d, Math.min(1.35d, 1d + (equalizedMagnitude - 1d) * 1.2d));
				double radiusScale = noiseRadius * (1d - phaseConfidence)
						+ equalizedRadius * phaseConfidence;
				points[i] = (float) ((real * cos - imag * sin) * radiusScale);
				points[i + 1] = (float) ((real * sin + imag * cos) * radiusScale);
			}
		}
		return new ConstellationCandidate(points, symmetryScore);
	}

	private double estimateDabPhaseSlope(float[] points, int[] carriers, int pointCount, int carrierStep) {
		double weightedSlope = 0d;
		int weightedCount = 0;
		int segmentStart = 0;
		while (segmentStart < pointCount) {
			int segmentEnd = segmentStart + 1;
			while (segmentEnd < pointCount
					&& carriers[segmentEnd] - carriers[segmentEnd - 1] == carrierStep) {
				segmentEnd++;
			}
			int count = segmentEnd - segmentStart;
			if (count >= 4) {
				double sumX = 0d;
				double sumY = 0d;
				double sumXX = 0d;
				double sumXY = 0d;
				double previousPhase = 0d;
				double unwrappedPhase = 0d;
				for (int point = segmentStart; point < segmentEnd; point++) {
					Complex fourth = fourthPower(points[point * 2], points[point * 2 + 1]);
					double phase = Math.atan2(fourth.imag, fourth.real);
					if (point == segmentStart) {
						unwrappedPhase = phase;
					} else {
						double delta = phase - previousPhase;
						while (delta > Math.PI) {
							delta -= 2d * Math.PI;
						}
						while (delta < -Math.PI) {
							delta += 2d * Math.PI;
						}
						unwrappedPhase += delta;
					}
					previousPhase = phase;
					double x = carriers[point];
					sumX += x;
					sumY += unwrappedPhase;
					sumXX += x * x;
					sumXY += x * unwrappedPhase;
				}
				double denominator = count * sumXX - sumX * sumX;
				if (Math.abs(denominator) > 1e-9d) {
					double fourthPowerSlope = (count * sumXY - sumX * sumY) / denominator;
					weightedSlope += fourthPowerSlope * count;
					weightedCount += count;
				}
			}
			segmentStart = segmentEnd;
		}
		return weightedCount <= 0 ? 0d : weightedSlope / weightedCount / 4d;
	}

	private Complex fourthPower(double real, double imag) {
		double squaredReal = real * real - imag * imag;
		double squaredImag = 2d * real * imag;
		return new Complex(squaredReal * squaredReal - squaredImag * squaredImag,
				2d * squaredReal * squaredImag);
	}

	private SymbolTiming refineDabSymbolTiming(byte[] iqData, int totalSamples, int expectedUsefulStart,
			int usefulSamples, int guardSamples) {
		int searchRadius = Math.max(8, guardSamples / 3);
		int first = Math.max(guardSamples, expectedUsefulStart - searchRadius);
		int last = Math.min(totalSamples - usefulSamples - 1, expectedUsefulStart + searchRadius);
		int coarseStep = Math.max(1, guardSamples / 48);
		int best = expectedUsefulStart;
		double bestScore = -1d;
		PrefixCorrelation bestCorrelation = PrefixCorrelation.EMPTY;
		for (int candidate = first; candidate <= last; candidate += coarseStep) {
			PrefixCorrelation correlation = cyclicPrefixCorrelation(iqData, candidate, usefulSamples, guardSamples, 2);
			if (correlation.score > bestScore) {
				bestScore = correlation.score;
				best = candidate;
				bestCorrelation = correlation;
			}
		}
		int refineFirst = Math.max(first, best - coarseStep);
		int refineLast = Math.min(last, best + coarseStep);
		for (int candidate = refineFirst; candidate <= refineLast; candidate++) {
			PrefixCorrelation correlation = cyclicPrefixCorrelation(iqData, candidate, usefulSamples, guardSamples, 1);
			if (correlation.score > bestScore) {
				bestScore = correlation.score;
				best = candidate;
				bestCorrelation = correlation;
			}
		}
		double correctionBins = -Math.atan2(bestCorrelation.imag, bestCorrelation.real) / (2d * Math.PI);
		return new SymbolTiming(best, correctionBins, bestScore);
	}

	private PrefixCorrelation cyclicPrefixCorrelation(byte[] iqData, int usefulStart, int usefulSamples,
			int guardSamples, int stride) {
		double real = 0d;
		double imag = 0d;
		double firstEnergy = 0d;
		double secondEnergy = 0d;
		int prefixStart = usefulStart - guardSamples;
		for (int n = 0; n < guardSamples; n += stride) {
			int firstOffset = (prefixStart + n) * 2;
			int secondOffset = (prefixStart + n + usefulSamples) * 2;
			double firstI = iqData[firstOffset];
			double firstQ = iqData[firstOffset + 1];
			double secondI = iqData[secondOffset];
			double secondQ = iqData[secondOffset + 1];
			real += firstI * secondI + firstQ * secondQ;
			imag += firstQ * secondI - firstI * secondQ;
			firstEnergy += firstI * firstI + firstQ * firstQ;
			secondEnergy += secondI * secondI + secondQ * secondQ;
		}
		double denominator = Math.sqrt(firstEnergy * secondEnergy);
		double score = denominator <= 0d ? 0d : Math.sqrt(real * real + imag * imag) / denominator;
		return new PrefixCorrelation(real, imag, score);
	}

	private NullSearch findDabNull(byte[] iqData, int totalSamples, int nullSamples, int latestStart) {
		long[] prefix = new long[totalSamples + 1];
		for (int i = 0; i < totalSamples; i++) {
			int offset = i * 2;
			int iv = iqData[offset];
			int qv = iqData[offset + 1];
			prefix[i + 1] = prefix[i] + iv * iv + qv * qv;
		}
		int step = Math.max(1, nullSamples / 48);
		int limit = Math.max(0, Math.min(totalSamples - nullSamples - 1, latestStart));
		long bestEnergy = Long.MAX_VALUE;
		long sumEnergy = 0;
		int count = 0;
		int best = 0;
		for (int start = 0; start <= limit; start += step) {
			long energy = prefix[start + nullSamples] - prefix[start];
			sumEnergy += energy;
			count++;
			if (energy < bestEnergy) {
				bestEnergy = energy;
				best = start;
			}
		}
		int refineStart = Math.max(0, best - step * 2);
		int refineStop = Math.min(limit, best + step * 2);
		for (int start = refineStart; start <= refineStop; start++) {
			long energy = prefix[start + nullSamples] - prefix[start];
			if (energy < bestEnergy) {
				bestEnergy = energy;
				best = start;
			}
		}
		long localEnergy = 0;
		int localCount = 0;
		if (best >= nullSamples) {
			localEnergy += prefix[best] - prefix[best - nullSamples];
			localCount++;
		}
		if (best + nullSamples * 2 <= totalSamples) {
			localEnergy += prefix[best + nullSamples * 2] - prefix[best + nullSamples];
			localCount++;
		}
		double average = localCount <= 0
				? (count <= 0 ? bestEnergy : sumEnergy / (double) count)
				: localEnergy / (double) localCount;
		double ratio = bestEnergy <= 0 ? 0 : average / bestEnergy;
		return new NullSearch(best, ratio);
	}

	private Complex dftCarrier(byte[] iqData, int startSample, int usefulSamples, int carrier, double correctionBins) {
		double real = 0;
		double imag = 0;
		double angleStep = -2d * Math.PI * (carrier + correctionBins) / usefulSamples;
		double stepReal = Math.cos(angleStep);
		double stepImag = Math.sin(angleStep);
		double oscReal = 1d;
		double oscImag = 0d;
		for (int n = 0; n < usefulSamples; n++) {
			int offset = (startSample + n) * 2;
			double i = iqData[offset] / 128d;
			double q = iqData[offset + 1] / 128d;
			real += i * oscReal - q * oscImag;
			imag += i * oscImag + q * oscReal;
			double nextReal = oscReal * stepReal - oscImag * stepImag;
			oscImag = oscReal * stepImag + oscImag * stepReal;
			oscReal = nextReal;
		}
		return new Complex(real, imag);
	}

	private Average averageDb(float[] values, int from, int to) {
		int start = Math.max(0, Math.min(values.length, from));
		int stop = Math.max(start, Math.min(values.length, to));
		if (stop <= start) {
			return new Average(-120d, 0);
		}
		double sumPower = 0;
		for (int i = start; i < stop; i++) {
			sumPower += Math.pow(10d, values[i] / 10d);
		}
		double db = 10d * Math.log10(sumPower / (stop - start) + 1e-12);
		return new Average(db, stop - start);
	}

	private double estimateSpectralOffsetHz(float[] spectrum, int from, int to, double binHz, int centerBin) {
		int start = Math.max(0, Math.min(spectrum.length, from));
		int stop = Math.max(start, Math.min(spectrum.length, to));
		double weighted = 0;
		double total = 0;
		for (int i = start; i < stop; i++) {
			double power = Math.pow(10d, spectrum[i] / 10d);
			weighted += (i - centerBin) * binHz * power;
			total += power;
		}
		return total <= 0 ? 0 : weighted / total;
	}

	private double clamp01(double value) {
		return Math.max(0d, Math.min(1d, value));
	}

	private float[] computeSpectrum(byte[] samples, int sampleCount) {
		if (sampleCount < SPECTRUM_SIZE) {
			return new float[0];
		}
		double[] real = new double[SPECTRUM_SIZE];
		double[] imag = new double[SPECTRUM_SIZE];
		int startSample = Math.max(0, (sampleCount - SPECTRUM_SIZE) / 2);
		for (int i = 0; i < SPECTRUM_SIZE; i++) {
			int source = (startSample + i) * 2;
			double window = 0.5d - 0.5d * Math.cos(2d * Math.PI * i / (SPECTRUM_SIZE - 1));
			real[i] = samples[source] / 128d * window;
			imag[i] = samples[source + 1] / 128d * window;
		}
		fft(real, imag);
		float[] db = new float[SPECTRUM_SIZE];
		for (int i = 0; i < SPECTRUM_SIZE; i++) {
			int shifted = (i + SPECTRUM_SIZE / 2) % SPECTRUM_SIZE;
			double power = real[shifted] * real[shifted] + imag[shifted] * imag[shifted];
			db[i] = (float) (10d * Math.log10(power / SPECTRUM_SIZE + 1e-12));
		}
		return db;
	}

	private void fft(double[] real, double[] imag) {
		int size = real.length;
		for (int i = 1, j = 0; i < size; i++) {
			int bit = size >> 1;
			for (; (j & bit) != 0; bit >>= 1) {
				j ^= bit;
			}
			j ^= bit;
			if (i < j) {
				double temp = real[i];
				real[i] = real[j];
				real[j] = temp;
				temp = imag[i];
				imag[i] = imag[j];
				imag[j] = temp;
			}
		}
		for (int length = 2; length <= size; length <<= 1) {
			double angle = -2d * Math.PI / length;
			double wLengthReal = Math.cos(angle);
			double wLengthImag = Math.sin(angle);
			for (int start = 0; start < size; start += length) {
				double wReal = 1d;
				double wImag = 0d;
				for (int j = 0; j < length / 2; j++) {
					int even = start + j;
					int odd = even + length / 2;
					double oddReal = real[odd] * wReal - imag[odd] * wImag;
					double oddImag = real[odd] * wImag + imag[odd] * wReal;
					real[odd] = real[even] - oddReal;
					imag[odd] = imag[even] - oddImag;
					real[even] += oddReal;
					imag[even] += oddImag;
					double nextReal = wReal * wLengthReal - wImag * wLengthImag;
					wImag = wReal * wLengthImag + wImag * wLengthReal;
					wReal = nextReal;
				}
			}
		}
	}

	private double calculateRawQuality(double dbfs, double clippingPercent, double dcPercent) {
		double levelScore;
		if (dbfs < -50d) {
			levelScore = 0;
		} else if (dbfs < -25d) {
			levelScore = (dbfs + 50d) / 25d * 100d;
		} else if (dbfs <= -8d) {
			levelScore = 100d;
		} else if (dbfs <= -3d) {
			levelScore = 100d - (dbfs + 8d) / 5d * 45d;
		} else {
			levelScore = 25d;
		}

		if (Double.isNaN(emaDbfs)) {
			emaDbfs = dbfs;
			stabilityDb = 0;
		} else {
			double delta = Math.abs(dbfs - emaDbfs);
			stabilityDb = stabilityDb * 0.85d + delta * 0.15d;
			emaDbfs = emaDbfs * 0.9d + dbfs * 0.1d;
		}
		double clippingScore = Math.max(0, 100d - clippingPercent * 60d);
		double dcScore = Math.max(0, 100d - dcPercent * 3d);
		double stabilityScore = Math.max(0, 100d - stabilityDb * 20d);
		double quality = levelScore * 0.45d + clippingScore * 0.25d + dcScore * 0.15d + stabilityScore * 0.15d;
		if (clippingPercent >= 1d) {
			quality = Math.min(quality, 45d);
		}
		return Math.max(0, Math.min(100, quality));
	}

	private void updateView() {
		Snapshot active = snapshot;
		scatterPanel.setSnapshot(active);
		spectrumPanel.setSnapshot(active);
		dabConstellationPanel.setSnapshot(active);
		dvbt2P2Panel.setSnapshot(active);
		qualityPanel.setSnapshot(active);
		dabLockPanel.setSnapshot(active);
		refreshDvbt2CrcPanel();
		if (active == null) {
			statsLabel.setText("Waiting for IQ samples...");
			detailLabel.setText("RAW quality waits for signal level, clipping, DC offset and stability");
			if (dvbt2Mode) {
				dvbt2PreLabel.setText(parameterTable("L1-PRE SIGNALLING", "", "Waiting for DVB-T2 P1/L1-pre lock..."));
				dvbt2PostLabel.setText(parameterTable("L1-POST / PLP", "", "Waiting for L1-post lock..."));
			}
			if (gsmMode) gsmParameterLabel.setText(parameterTable("GSM DOWNLINK", "", "Waiting for GSM IQ..."));
			if (rdsMode) gsmParameterLabel.setText(parameterTable("FM RDS", "", "Waiting for FM IQ..."));
			if (lteMode) lteParameterLabel.setText(parameterTable("LTE CELL SEARCH", "", "Waiting for LTE IQ..."));
			if (nrMode) nrParameterLabel.setText(parameterTable("NR CELL ACQUISITION / SESSION STATUS", "",
					"Waiting for NR IQ samples..."));
			if (umtsMode) umtsParameterLabel.setText(parameterTable("UMTS CPICH ACQUISITION", "",
					"Waiting for UMTS IQ samples..."));
			return;
		}
		statsLabel.setText(String.format(Locale.US, "%.3f MHz   %.3f MS/s   RMS %.1f dBFS   peak %d",
				active.centerFreqHz / 1_000_000d, active.sampleRateHz / 1_000_000d, active.dbfs, active.peak));
		if (dabMode && active.dabMetrics != null) {
			DabMetrics dab = active.dabMetrics;
			DabConstellation constellation = dab.constellation;
			String sync = constellation == null ? "sync --" : constellation.state;
			detailLabel.setText(String.format(Locale.US,
					"%s   %s %.0f%%   contrast %.1f dB   offset %.0f kHz   null %.1fx   CP %.2f   CFO %.0f Hz",
					dab.state, sync, constellation == null ? 0 : constellation.lockScore,
					dab.contrastDb, dab.offsetHz / 1000d,
					constellation == null ? 0 : constellation.nullRatio,
					constellation == null ? 0 : constellation.timingCorrelation,
					constellation == null ? 0 : constellation.frequencyCorrectionHz));
		} else if (dvbtMode && active.dvbtResult != null) {
			DvbtSignalAnalyzer.Result dvbt = active.dvbtResult;
			detailLabel.setText(String.format(Locale.US,
					"%s   %s %s %s   code %s   MER %.1f dB   CP %.2f   pilots %.2f   CFO %.0f Hz",
					dvbt.state, dvbt.fftMode, dvbt.guard, dvbt.modulation, dvbt.codeRate, dvbt.merDb,
					dvbt.cpCorrelation, dvbt.pilotCoherence, dvbt.frequencyCorrectionHz));
		} else if (dvbt2Mode && active.dvbt2Result != null) {
			Dvbt2SignalAnalyzer.Result t2 = active.dvbt2Result;
			String measurements = String.format(Locale.US,
					"%s   CP %.3f   pilots %.3f   CFO %.0f Hz",
					t2.state, t2.cpCorrelation, t2.pilotCoherence, t2.cfoHz);
			detailLabel.setText(measurements);
			detailLabel.setToolTipText(t2.transmissionDetails.length() == 0 ? measurements
					: t2.transmissionDetails + " | " + measurements);
			dvbt2PreLabel.setText(parameterTable("L1-PRE SIGNALLING", t2.l1PreDetails,
					t2.l1Locked ? "" : t2.state));
			dvbt2PostLabel.setText(parameterTable("L1-POST / PLP", t2.l1PostDetails,
					t2.l1PostLocked ? "" : "L1-post parameters are not locked yet."));
		} else if (gsmMode && active.gsmResult != null) {
			GsmSignalAnalyzer.Result gsm = active.gsmResult;
			PlmnDatabase.Entry plmn = gsm.bcchDecoded ? PlmnDatabase.lookup(gsm.mcc, gsm.mnc) : null;
			detailLabel.setText(String.format(Locale.US,
					"%s   FCCH %.1f%%   SCH %.1f%%   tone %+.0f Hz   CFO %+.0f Hz   burst %.1f dBFS",
					gsm.state, gsm.coherence * 100d, gsm.schCorrelation * 100d,
					gsm.toneHz, gsm.cfoHz, gsm.burstDbfs));
			String details = String.format(Locale.US,
					"Acquisition|%s\nFCCH lock|%s\nSCH sync|%s\nSCH data CRC|%s\nControl channel|%s\nPaging observations|%d\nSystem Information 3|%s\nPLMN (MCC-MNC)|%s\nCountry|%s\nOperator / network|%s\nAssignment status|%s\nLAC|%s\nCell ID|%s\nBSIC|%s\nNCC / BCC|%s\nFrame number|%s\nFCCH quality|%.1f %%\nSCH correlation|%.1f %%\n"
					+ "FCCH tone|%+.1f Hz\nFrequency error (CFO)|%+.1f Hz\nBurst level|%.1f dBFS\n"
					+ "FCCH candidates|%d\nChannel center|%.6f MHz\nIQ sample rate|%.3f kS/s",
					gsm.state, gsm.fcchLocked ? "LOCKED" : "searching", gsm.schDetected ? "SYNCHRONIZED" : "searching",
					gsm.schDecoded ? "OK" : "waiting for a CRC-valid SCH burst",
					gsm.bcchMessageType >= 0 ? String.format(Locale.US, "RR 0x%02X", gsm.bcchMessageType) : "searching",
					gsm.pagingEvents.size(),
					gsm.bcchDecoded ? "FIRE CRC OK" : "searching",
					gsm.bcchDecoded ? gsm.mcc + "-" + gsm.mnc : "--",
					plmn == null ? (gsm.bcchDecoded ? "unknown in embedded database" : "--")
							: plmn.country + (plmn.countryCode.length() == 0 ? "" : " (" + plmn.countryCode + ")"),
					plmn == null ? "--" : plmn.networkName(),
					plmn == null ? "--" : plmn.status,
					gsm.bcchDecoded ? Integer.toString(gsm.lac) : "--",
					gsm.bcchDecoded ? Integer.toString(gsm.cellId) : "--",
					gsm.schDecoded ? Integer.toString(gsm.bsic) : "--",
					gsm.schDecoded ? gsm.ncc + " / " + gsm.bcc : "--",
					gsm.schDecoded ? Long.toString(gsm.frameNumber) : "--",
					gsm.quality, gsm.schCorrelation * 100d, gsm.toneHz, gsm.cfoHz, gsm.burstDbfs,
					gsm.candidates, active.centerFreqHz / 1_000_000d,
					active.sampleRateHz / 1000d);
			gsmParameterLabel.setText(parameterTable("GSM DOWNLINK ACQUISITION", details, gsm.state));
		} else if (rdsMode && active.rdsResult != null) {
			RdsSignalAnalyzer.Result rds = active.rdsResult;
			detailLabel.setText(String.format(Locale.US,
					"%s   quality %.0f%%   pilot %.1f dB   RDS 57 kHz %.1f dB   blocks %d/%d   groups %d",
					rds.state, rds.quality, rds.pilotSnrDb, rds.rdsSnrDb,
					rds.windowBlocks, rds.possibleBlocks, rds.windowGroups));
			String decoded = rds.pi + "|" + rds.programService + "|" + rds.pty + "|" + rds.radioText + "|"
					+ rds.clockText + "|" + rds.tp + "|" + rds.ta;
			if (!decoded.equals(lastRdsDecodedSummary)) {
				lastRdsDecodedSummary = decoded;
				lastRdsChangeMillis = System.currentTimeMillis();
			}
			gsmParameterLabel.setText(rdsParameterTable(rds, active));
		} else if (lteMode && active.lteResult != null) {
			LteSignalAnalyzer.Result lte=active.lteResult;
			lteConstellationPanel.setResult(lte);
			for(LteSignalAnalyzer.Candidate candidate:lte.candidates)if(candidate.pci>=0) {
				if(candidate.si.valid)lteSystemInformation.put(candidate.pci,candidate.si);
				else if(lteSystemInformation.containsKey(candidate.pci))candidate.si=lteSystemInformation.get(candidate.pci);
				if(candidate.load.valid){Double previousLoad=lteLoadPercent.get(candidate.pci);lteLoadPercent.put(candidate.pci,previousLoad==null?candidate.load.percent:previousLoad*.7+candidate.load.percent*.3);}
				long now=System.nanoTime();
				for(Integer event:candidate.pagingEvents){String key=candidate.pci+":"+event;Long last=ltePagingSeen.get(key);if(last==null||now-last>2_000_000_000L){ltePagingSeen.put(key,now);ltePagingCounts.put(candidate.pci,ltePagingCounts.containsKey(candidate.pci)?ltePagingCounts.get(candidate.pci)+1L:1L);}}
				LteSignalAnalyzer.Candidate previous=lteCells.get(candidate.pci);
				/* A short refresh can end at a PBCH cycle boundary.  Keep a previously
				 * CRC-verified MIB while updating the live synchronization measurements. */
				if(!candidate.mib.valid&&previous!=null&&previous.mib.valid)candidate=candidate.withMib(previous.mib);
				if(!candidate.cfi.valid&&previous!=null&&previous.cfi.valid)candidate.cfi=previous.cfi;
				if(!candidate.control.valid&&previous!=null&&previous.control.valid)candidate.control=previous.control;
				if(!candidate.pdcch.valid&&previous!=null&&previous.pdcch.valid)candidate.pdcch=previous.pdcch;
				if(!candidate.sib1Pdcch.valid&&previous!=null&&previous.sib1Pdcch.valid){candidate.sib1Cfi=previous.sib1Cfi;candidate.sib1Pdcch=previous.sib1Pdcch;candidate.sib1FrameOffset=previous.sib1FrameOffset;}
				if(!candidate.sib1Pdsch.valid&&previous!=null&&previous.sib1Pdsch.valid)candidate.sib1Pdsch=previous.sib1Pdsch;
				if(!candidate.sib1Transport.valid&&previous!=null&&previous.sib1Transport.valid)candidate.sib1Transport=previous.sib1Transport;
				if(!candidate.sib1.valid&&previous!=null&&previous.sib1.valid)candidate.sib1=previous.sib1;
				if(!candidate.siTransport.valid&&previous!=null&&previous.siTransport.valid){candidate.siCfi=previous.siCfi;candidate.siPdcch=previous.siPdcch;candidate.siPdsch=previous.siPdsch;candidate.siTransport=previous.siTransport;candidate.siFrameOffset=previous.siFrameOffset;candidate.siSubframe=previous.siSubframe;}
				/* Parsed SIB2/3 may come either from the current CRC-valid transport or
				 * from the analyzer's per-PCI cache. Retain it independently of the
				 * transport object so a later short refresh cannot blank detail tabs. */
				if(!candidate.si.valid&&previous!=null&&previous.si.valid)candidate.si=previous.si;
				if(candidate.si.valid)lteSystemInformation.put(candidate.pci,candidate.si);
				lteCells.put(candidate.pci,candidate);
			}
			lteCellTablePanel.updateCells(lteCells.values());
			detailLabel.setText(String.format(Locale.US,"%s   PSS %.1f%%   detected cells %d",
					lte.state,lte.correlation*100d,lteCells.size()));
			String details=String.format(Locale.US,
					"Acquisition|%s\nDetected cells|%d\nCurrent PSS / SSS|%.1f %% / %.1f %%\nIQ orientation|%s\nChannel center|%.6f MHz\nIQ sample rate|%.3f MS/s",
					lte.state,lteCells.size(),lte.correlation*100d,lte.sssCorrelation*100d,
					lte.iqOrientation>0?"normal":"conjugated",active.centerFreqHz/1e6,active.sampleRateHz/1e6);
			lteParameterLabel.setText(parameterTable("LTE CELL ACQUISITION / SESSION STATUS",details,lte.state));
		} else if (nrMode && active.nrResult != null) {
			NrSignalAnalyzer.Result nr = active.nrResult;
			if (nr.pci >= 0) {
				if (nr.load.valid) {
					Double previousLoad = nrLoadPercent.get(nr.pci);
					nrLoadPercent.put(nr.pci, previousLoad == null ? nr.load.percent
							: previousLoad * .7d + nr.load.percent * .3d);
				}
				NrSignalAnalyzer.Result previous = nrCells.get(nr.pci);
				if (previous != null) {
					NrSignalAnalyzer.MibData mib = nr.mib.valid ? nr.mib : previous.mib;
					NrSignalAnalyzer.Sib1Data sib1 = nr.sib1.valid ? nr.sib1 : previous.sib1;
					NrSignalAnalyzer.LoadData load = nr.load.valid ? nr.load : previous.load;
					if (mib != nr.mib || sib1 != nr.sib1 || load != nr.load)
						nr = nr.withMeasurements(mib, sib1, load);
				}
				nrCells.put(nr.pci, nr);
			}
			nrCellTablePanel.updateCells(nrCells.values(), active.centerFreqHz);
			detailLabel.setText(String.format(Locale.US, "%s   PSS %.1f%%   detected cells %d",
					nr.state, nr.pssCorrelation * 100d, nrCells.size()));
			String details = String.format(Locale.US,
					"Acquisition|%s\nDetected cells|%d\nCurrent PCI|%s\nPSS / SSS / PBCH DM-RS|%.1f %% / %.1f %% / %s\nMIB / SIB1|%s / %s\nIQ orientation|%s",
					nr.state, nrCells.size(), nr.pci >= 0 ? Integer.toString(nr.pci) : "searching",
					nr.pssCorrelation * 100d, nr.sssCorrelation * 100d,
					nr.dmrsConfirmed ? String.format(Locale.US, "%.1f %%", nr.dmrsCorrelation * 100d) : "searching",
					nr.mib.valid ? "decoded" : "waiting", nr.sib1.valid ? "decoded" : "waiting",
					nr.iqOrientation > 0 ? "normal" : nr.iqOrientation < 0 ? "conjugated" : "searching");
			nrParameterLabel.setText(parameterTable("NR CELL ACQUISITION / SESSION STATUS", details, nr.state));
		} else if (umtsMode && active.umtsResult != null) {
			UmtsSignalAnalyzer.Result umts = active.umtsResult;
			for (UmtsSignalAnalyzer.Candidate candidate : umts.candidates) {
				UmtsSignalAnalyzer.Candidate previous = umtsCells.get(candidate.psc);
				if (candidate.load.valid) {
					Double oldLoad = umtsLoadPercent.get(candidate.psc);
					umtsLoadPercent.put(candidate.psc, oldLoad == null ? candidate.load.percent
							: oldLoad * .7d + candidate.load.percent * .3d);
				}
				UmtsBchDecoder.BchData bch = candidate.bch.valid ? candidate.bch
						: previous == null ? candidate.bch : previous.bch;
				UmtsSystemInformationDecoder.Snapshot information = candidate.systemInformation.hasAny()
						? candidate.systemInformation
						: previous == null ? candidate.systemInformation : previous.systemInformation;
				UmtsSignalAnalyzer.LoadData load = candidate.load.valid ? candidate.load
						: previous == null ? candidate.load : previous.load;
				UmtsPchDecoder.Snapshot paging = candidate.paging.supported
						? candidate.paging : previous == null ? candidate.paging : previous.paging;
				if (previous == null || candidate.cpichCorrelation >= previous.cpichCorrelation || candidate.bch.valid)
					umtsCells.put(candidate.psc,
							candidate.withBchAndSystemInformation(bch, information).withLoad(load)
									.withPaging(paging));
			}
			umtsCellTablePanel.updateCells(umtsCells.values(), active.centerFreqHz, umts.psc);
			long measuredCarrierHz = Math.round(active.centerFreqHz + umts.cfoHz);
			int uarfcn = UmtsSignalAnalyzer.uarfcnFromFrequency(measuredCarrierHz);
			detailLabel.setText(String.format(Locale.US,
					"%s   P-SCH %.1f%%   CPICH %.1f%%   detected PSCs %d",
					umts.state, umts.pschCorrelation * 100d, umts.cpichCorrelation * 100d, umtsCells.size()));
			String details = String.format(Locale.US,
					"Acquisition|%s\nDetected PSCs|%d\nCurrent PSC|%s\nScrambling code group|%s\nP-SCH / CPICH|%.1f %% / %.1f %%\nCPICH Ec/N0|%s\nCPICH RSCP|%s\nBCH|%s\nMIB / SIB1 / SIB3|%s\nUARFCN|%s\nChannel center|%.6f MHz\nIQ sample rate|%.3f MS/s\nCarrier offset|%+.1f Hz\nIQ orientation|%s",
					umts.state, umtsCells.size(), umts.psc >= 0 ? Integer.toString(umts.psc) : "searching",
					umts.codeGroup >= 0 ? Integer.toString(umts.codeGroup) : "--",
					umts.pschCorrelation * 100d, umts.cpichCorrelation * 100d,
					umts.candidates.isEmpty() ? "--" : String.format(Locale.US, "%.1f dB", umts.candidates.get(0).ecNoDb),
					umts.candidates.isEmpty() ? "--" : String.format(Locale.US, "%.1f dBFS", umts.candidates.get(0).rscpDbfs),
					!umts.candidates.isEmpty() && umts.candidates.get(0).bch.valid ? "CRC OK" : "waiting",
					!umts.candidates.isEmpty()
							? (umts.candidates.get(0).systemInformation.mibValid ? "OK" : "--") + " / "
									+ (umts.candidates.get(0).systemInformation.sib1Valid ? "OK" : "--") + " / "
									+ (umts.candidates.get(0).systemInformation.sib3Valid ? "OK" : "--")
							: "-- / -- / --",
					uarfcn >= 0 ? Integer.toString(uarfcn) : "--",
					active.centerFreqHz / 1e6, active.sampleRateHz / 1e6, umts.cfoHz,
					umts.iqOrientation > 0 ? "normal" : "conjugated");
			umtsParameterLabel.setText(parameterTable("UMTS/WCDMA DOWNLINK ACQUISITION", details, umts.state));
		} else {
			detailLabel.setText(String.format(Locale.US, "DC %.1f%%   clipping %.2f%%   stability %.2f dB",
					active.dcPercent, active.clippingPercent, active.stabilityDb));
		}
	}
	private static String lteBandwidthMhz(int rb){switch(rb){case 6:return"1.4";case 15:return"3";case 25:return"5";case 50:return"10";case 75:return"15";case 100:return"20";default:return"?";}}
	private static String phichResource(int value){return new String[]{"1/6","1/2","1","2"}[Math.max(0,Math.min(3,value))];}

	private synchronized void refreshDvbt2CrcPanel() {
		dvbt2CrcPanel.setStats(dvbt2CrcCount, dvbt2PreCrcSuccess, dvbt2PostCrcSuccess);
	}

	private String parameterTable(String title, String details, String waiting) {
		StringBuilder html = new StringBuilder(1024);
		html.append("<html><table cellspacing='0' cellpadding='1' width='100%'>")
				.append("<tr><td colspan='5'><b>").append(title).append("</b></td></tr>");
		if (details != null && details.length() > 0) {
			java.util.List<String[]> rows = new java.util.ArrayList<>();
			for (String row : details.split("\\n")) {
				String[] fields = row.split("\\|", 2);
				if (fields.length == 2) rows.add(fields);
			}
			int half=(rows.size()+1)/2;
			for(int i=0;i<half;i++){
				String[] left=rows.get(i),right=i+half<rows.size()?rows.get(i+half):null;
				html.append("<tr><td>").append(left[0]).append("</td><td><b>").append(left[1]).append("</b></td><td width='14'></td>");
				if(right!=null)html.append("<td>").append(right[0]).append("</td><td><b>").append(right[1]).append("</b></td>");
				else html.append("<td></td><td></td>");
				html.append("</tr>");
			}
		} else {
			html.append("<tr><td colspan='5'><i>").append(waiting).append("</i></td></tr>");
		}
		return html.append("</table></html>").toString();
	}

	private String rdsParameterTable(RdsSignalAnalyzer.Result rds, Snapshot active) {
		String ps = rds.programService.length() == 0 ? "--" : rds.programService;
		String rt = rds.radioText.length() == 0 ? "--" : rds.radioText;
		String pty = rds.pty >= 0 ? rds.ptyName : "--";
		String clock = rds.clockText.length() == 0 ? "--" : rds.clockText;
		StringBuilder html = new StringBuilder(1200);
		long ageMillis = lastRdsChangeMillis == 0L ? -1L : Math.max(0L, System.currentTimeMillis() - lastRdsChangeMillis);
		html.append("<html><b>FM RDS DECODER</b><br>")
				.append("<table style='table-layout:fixed' border='1' bordercolor='#e8eef5' ")
				.append("cellspacing='0' cellpadding='1' width='690'>");
		appendRdsPairRow(html, "Station name", ps, "Programme type", pty);
		appendRdsPairRow(html, "Country", rds.countryName, "Coverage", rds.coverageAreaName);
		appendRdsPairRow(html, "Audio mode", rds.audioMode, "Programme mode", rds.programmeMode);
		appendRdsPairRow(html, "Traffic programme", rds.tp ? "Available" : "No",
				"Traffic announcement", rds.ta ? "Active" : "No");
		appendRdsPairRow(html, "Programme item", rds.programmeItem.length() == 0 ? "--" : rds.programmeItem,
				"Last change", ageMillis < 0L ? "--" : String.format(Locale.US, "%d s ago",
						ageMillis / 1000L));
		appendRdsPairRow(html, "RT+", rds.rtPlusStatus, "TMC", rds.tmcStatus);
		appendRdsPairRow(html, "EON", rds.eonStatus, "Other data",
				otherRdsDataStatus(rds.tdcStatus, rds.inHouseStatus, rds.odaStatus));
		appendRdsPairRow(html, "Clock", clock, "Corrected bits",
				String.format(Locale.US, "%d (%.2f / block)", rds.windowCorrectedBits,
						rds.windowBlocks <= 0 ? 0d : rds.windowCorrectedBits / (double) rds.windowBlocks));
		appendRdsWideRow(html, "Alternative frequencies", rds.alternativeFrequencies);
		appendRdsWideRow(html, "RadioText", rt);
		return html.append("</table></html>").toString();
	}

	private static String otherRdsDataStatus(String tdc, String inHouse, String oda) {
		StringBuilder out = new StringBuilder();
		appendPresent(out, "TDC", tdc);
		appendPresent(out, "IH", inHouse);
		appendPresent(out, "ODA", oda);
		return out.length() == 0 ? "--" : out.toString();
	}

	private static void appendPresent(StringBuilder out, String label, String status) {
		if (status == null || status.length() == 0 || "--".equals(status)) return;
		if (out.length() > 0) out.append(", ");
		out.append(label);
	}

	private static void appendRdsPairRow(StringBuilder html, String leftKey, String leftValue,
			String rightKey, String rightValue) {
		html.append("<tr>");
		appendRdsLabelCell(html, leftKey);
		appendRdsValueCell(html, leftValue, "195");
		appendRdsLabelCell(html, rightKey);
		appendRdsValueCell(html, rightValue, "195");
		html.append("</tr>");
	}

	private static void appendRdsWideRow(StringBuilder html, String key, String value) {
		html.append("<tr>");
		appendRdsLabelCell(html, key);
		html.append("<td colspan='3'><font color='#ffffff'><b>").append(escapeHtml(value))
				.append("</b></font></td></tr>");
	}

	private static void appendRdsLabelCell(StringBuilder html, String value) {
		html.append("<td width='150' nowrap bgcolor='#41596c'><font color='#ffffff'>")
				.append(escapeHtml(value)).append("</font></td>");
	}

	private static void appendRdsValueCell(StringBuilder html, String value, String width) {
		html.append("<td width='").append(width).append("'><font color='#ffffff'><b>")
				.append(escapeHtml(value)).append("</b></font></td>");
	}

	private static String escapeHtml(String value) {
		if (value == null || value.length() == 0) return "--";
		StringBuilder out = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
			case '&': out.append("&amp;"); break;
			case '<': out.append("&lt;"); break;
			case '>': out.append("&gt;"); break;
			case '"': out.append("&quot;"); break;
			case '\'': out.append("&#39;"); break;
			default: out.append(c); break;
			}
		}
		return out.toString();
	}

	private static final class Average {
		final double db;
		final int count;

		Average(double db, int count) {
			this.db = db;
			this.count = count;
		}
	}

	private static final class Complex {
		final double real;
		final double imag;

		Complex(double real, double imag) {
			this.real = real;
			this.imag = imag;
		}
	}

	private static final class PrefixCorrelation {
		static final PrefixCorrelation EMPTY = new PrefixCorrelation(0d, 0d, 0d);

		final double real;
		final double imag;
		final double score;

		PrefixCorrelation(double real, double imag, double score) {
			this.real = real;
			this.imag = imag;
			this.score = score;
		}
	}

	private static final class SymbolTiming {
		final int usefulStart;
		final double correctionBins;
		final double correlation;

		SymbolTiming(int usefulStart, double correctionBins, double correlation) {
			this.usefulStart = usefulStart;
			this.correctionBins = correctionBins;
			this.correlation = correlation;
		}
	}

	private static final class NullSearch {
		final int startSample;
		final double ratio;

		NullSearch(int startSample, double ratio) {
			this.startSample = startSample;
			this.ratio = ratio;
		}
	}

	private static final class DabConstellation {
		final float[] points;
		final double lockScore;
		final double nullRatio;
		final String state;
		final double timingCorrelation;
		final double frequencyCorrectionHz;

		DabConstellation(float[] points, double lockScore, double nullRatio, String state) {
			this(points, lockScore, nullRatio, state, 0d, 0d);
		}

		DabConstellation(float[] points, double lockScore, double nullRatio, String state,
				double timingCorrelation, double frequencyCorrectionHz) {
			this.points = points;
			this.lockScore = lockScore;
			this.nullRatio = nullRatio;
			this.state = state;
			this.timingCorrelation = timingCorrelation;
			this.frequencyCorrectionHz = frequencyCorrectionHz;
		}
	}

	private static final class ConstellationCandidate {
		final float[] points;
		final double symmetryScore;

		ConstellationCandidate(float[] points, double symmetryScore) {
			this.points = points;
			this.symmetryScore = symmetryScore;
		}
	}

	private static final class DabMetrics {
		final double score;
		final double contrastDb;
		final double offsetHz;
		final double balanceDb;
		final int bandwidthHz;
		final String state;
		DabConstellation constellation;

		DabMetrics(double score, double contrastDb, double offsetHz, double balanceDb, int bandwidthHz, String state) {
			this.score = score;
			this.contrastDb = contrastDb;
			this.offsetHz = offsetHz;
			this.balanceDb = balanceDb;
			this.bandwidthHz = bandwidthHz;
			this.state = state;
		}
	}

	private static final class Snapshot {
		final long centerFreqHz;
		final int sampleRateHz;
		final byte[] samples;
		final int sampleCount;
		final double dbfs;
		final int peak;
		final double dcPercent;
		final double clippingPercent;
		final double stabilityDb;
		final double quality;
		final float[] spectrumDb;
		final DabMetrics dabMetrics;
		final DvbtSignalAnalyzer.Result dvbtResult;
		final Dvbt2SignalAnalyzer.Result dvbt2Result;
		final GsmSignalAnalyzer.Result gsmResult;
		final RdsSignalAnalyzer.Result rdsResult;
		final LteSignalAnalyzer.Result lteResult;
		final NrSignalAnalyzer.Result nrResult;
		final UmtsSignalAnalyzer.Result umtsResult;
		final long createdMillis;

		Snapshot(long centerFreqHz, int sampleRateHz, byte[] samples, int sampleCount, double dbfs, int peak,
				double dcPercent, double clippingPercent, double stabilityDb, double quality, float[] spectrumDb,
				DabMetrics dabMetrics, DvbtSignalAnalyzer.Result dvbtResult,
				Dvbt2SignalAnalyzer.Result dvbt2Result, GsmSignalAnalyzer.Result gsmResult,
				RdsSignalAnalyzer.Result rdsResult, LteSignalAnalyzer.Result lteResult,
				NrSignalAnalyzer.Result nrResult, UmtsSignalAnalyzer.Result umtsResult, long createdMillis) {
			this.centerFreqHz = centerFreqHz;
			this.sampleRateHz = sampleRateHz;
			this.samples = samples;
			this.sampleCount = sampleCount;
			this.dbfs = dbfs;
			this.peak = peak;
			this.dcPercent = dcPercent;
			this.clippingPercent = clippingPercent;
			this.stabilityDb = stabilityDb;
			this.quality = quality;
			this.spectrumDb = spectrumDb;
			this.dabMetrics = dabMetrics;
			this.dvbtResult = dvbtResult;
			this.dvbt2Result = dvbt2Result;
			this.gsmResult = gsmResult;
			this.rdsResult = rdsResult;
			this.lteResult = lteResult;
			this.nrResult = nrResult;
			this.umtsResult = umtsResult;
			this.createdMillis = createdMillis;
		}
	}

	private static final class LteConstellationPanel extends JPanel {
		private volatile LteSignalAnalyzer.Result result;

		LteConstellationPanel() {
			setBackground(PANEL_BG);
			setPreferredSize(new Dimension(185,135));
			setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
		}

		void setResult(LteSignalAnalyzer.Result result) {
			this.result=result;
			repaint();
		}

		@Override protected void paintComponent(Graphics graphics) {
			super.paintComponent(graphics);
			Graphics2D g=(Graphics2D)graphics.create();
			try {
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
				int width=getWidth(),height=getHeight(),cx=width/2,cy=height/2+5;
				g.setColor(GRID);g.drawLine(7,cy,width-7,cy);g.drawLine(cx,22,cx,height-7);
				LteSignalAnalyzer.Result current=result;double[] points=null;double evm=Double.POSITIVE_INFINITY;String source="PBCH";
				if(current!=null) {
					for(LteSignalAnalyzer.Candidate cell:current.candidates)
						if(cell.pdcch.valid&&cell.pdcch.llr.length>=2&&cell.pdcch.qpskEvm<evm){
							points=cell.pdcch.llr;evm=cell.pdcch.qpskEvm;source="PDCCH";
						}
					if(points==null)for(LteSignalAnalyzer.Candidate cell:current.candidates)
						if(cell.sib1Pdsch.valid&&cell.sib1Pdsch.llr.length>=2&&cell.sib1Pdsch.qpskEvm<evm){
							points=cell.sib1Pdsch.llr;evm=cell.sib1Pdsch.qpskEvm;source="PDSCH";
						}
					if(points==null)for(LteSignalAnalyzer.Candidate cell:current.candidates)
						if(cell.sib1Pdcch.valid&&cell.sib1Pdcch.llr.length>=2&&cell.sib1Pdcch.qpskEvm<evm){
							points=cell.sib1Pdcch.llr;evm=cell.sib1Pdcch.qpskEvm;source="PDCCH";
						}
					if(points==null||points.length<2){points=current.pbchConstellation;evm=current.pbchEvm;}
				}
				g.setColor(TEXT_FG);g.drawString("LTE QPSK",8,14);
				if(points==null||points.length<2){g.setColor(MUTED_FG);g.drawString("waiting",width-48,14);return;}
				int pairs=points.length/2,step=Math.max(1,pairs/900);double power=0;int used=0;
				for(int q=0;q<pairs;q+=step){double re=points[2*q],im=points[2*q+1];power+=re*re+im*im;used++;}
				double component=Math.sqrt(power/Math.max(1,2*used));if(component<1e-9)return;
				double radius=Math.min(width-18,height-31)*.29,scale=radius/component;
				g.setColor(new Color(0x31505f));
				for(int sx:new int[]{-1,1})for(int sy:new int[]{-1,1})
					g.drawOval((int)Math.round(cx+sx*radius)-4,(int)Math.round(cy-sy*radius)-4,8,8);
				double quality=Double.isFinite(evm)?Math.max(0,Math.min(100,100*(1-evm))):0;
				g.setColor(quality>=70?QUALITY_GOOD:quality>=40?QUALITY_WARN:QUALITY_BAD);
				for(int q=0;q<pairs;q+=step){int x=(int)Math.round(cx+points[2*q]*scale),y=(int)Math.round(cy-points[2*q+1]*scale);if(x>=3&&x<width-3&&y>=20&&y<height-3)g.fillRect(x-1,y-1,3,3);}
				g.setColor(MUTED_FG);
				g.drawString(String.format(Locale.US,"%s %.0f%%",source,quality),Math.max(58,width-78),14);
			} finally { g.dispose(); }
		}
	}

	private static final class QualityPanel extends JPanel {
		private volatile Snapshot snapshot;

		QualityPanel() {
			setBackground(PANEL_BG);
			setPreferredSize(new Dimension(100, 24));
		}

		void setSnapshot(Snapshot snapshot) {
			this.snapshot = snapshot;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			super.paintComponent(graphics);
			Graphics2D g = (Graphics2D) graphics.create();
			try {
				int width = getWidth();
				int height = getHeight();
				int barX = 92;
				int barY = 5;
				int barW = Math.max(30, width - barX - 6);
				int barH = Math.max(8, height - 10);
				Snapshot active = snapshot;
				double quality = active == null ? 0 : active.quality;
				Color color = quality >= 70 ? QUALITY_GOOD : quality >= 40 ? QUALITY_WARN : QUALITY_BAD;
				g.setColor(TEXT_FG);
				String label = active != null && (active.dabMetrics != null || active.dvbtResult != null
						|| active.dvbt2Result != null || active.gsmResult != null || active.rdsResult != null
						|| active.lteResult != null || active.nrResult != null || active.umtsResult != null) ? "INPUT" : "RAW";
				g.drawString(active == null ? label + " --%" : String.format(Locale.US, "%s %.0f%%", label, quality), 4, 17);
				g.setColor(GRID);
				g.fillRect(barX, barY, barW, barH);
				g.setColor(color);
				g.fillRect(barX, barY, (int) Math.round(barW * quality / 100d), barH);
				g.setColor(Color.DARK_GRAY);
				g.drawRect(barX, barY, barW, barH);
			} finally {
				g.dispose();
			}
		}
	}

	private static final class DabLockPanel extends JPanel {
		private volatile Snapshot snapshot;

		DabLockPanel() {
			setBackground(PANEL_BG);
			setPreferredSize(new Dimension(100, 22));
		}

		void setSnapshot(Snapshot snapshot) {
			this.snapshot = snapshot;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			super.paintComponent(graphics);
			Graphics2D g = (Graphics2D) graphics.create();
			try {
				int width = getWidth();
				int height = getHeight();
				int barX = 92;
				int barY = 4;
				int barW = Math.max(30, width - barX - 6);
				int barH = Math.max(8, height - 8);
				Snapshot active = snapshot;
				DabConstellation constellation = active == null || active.dabMetrics == null
						? null : active.dabMetrics.constellation;
				double lock = active != null && active.dvbtResult != null
						? active.dvbtResult.quality : active != null && active.dvbt2Result != null
								? active.dvbt2Result.quality : active != null && active.gsmResult != null
										? active.gsmResult.quality : active != null && active.rdsResult != null
												? rdsLockQuality(active.rdsResult) : active != null && active.lteResult != null
												? lteQpskQuality(active.lteResult) : active != null && active.nrResult != null
												? active.nrResult.quality : active != null && active.umtsResult != null
												? active.umtsResult.quality : constellation == null ? 0 : constellation.lockScore;
				Color color = lock >= 55 ? QUALITY_GOOD : lock >= 35 ? QUALITY_WARN : QUALITY_BAD;
				g.setColor(TEXT_FG);
				String label = active != null && active.dvbt2Result != null ? "DEMOD"
						: active != null && active.gsmResult != null ? "FCCH"
						: active != null && active.rdsResult != null ? "RDS"
						: active != null && active.lteResult != null ? "QPSK"
						: active != null && active.nrResult != null ? "SSB"
						: active != null && active.umtsResult != null ? "CPICH" : "QUALITY";
				g.drawString(active == null || (constellation == null && active.dvbtResult == null
						&& active.dvbt2Result == null && active.gsmResult == null && active.rdsResult == null
						&& active.lteResult == null && active.nrResult == null && active.umtsResult == null) ? label + " --%"
						: String.format(Locale.US, "%s %.0f%%", label, lock), 4, 16);
				g.setColor(GRID);
				g.fillRect(barX, barY, barW, barH);
				g.setColor(color);
				g.fillRect(barX, barY, (int) Math.round(barW * lock / 100d), barH);
				g.setColor(Color.DARK_GRAY);
				g.drawRect(barX, barY, barW, barH);
			} finally {
				g.dispose();
			}
		}

		private static double lteQpskQuality(LteSignalAnalyzer.Result result) {
			double evm=result.pbchEvm;
			boolean live=false;for(LteSignalAnalyzer.Candidate cell:result.candidates)
				if(cell.pdcch.valid&&Double.isFinite(cell.pdcch.qpskEvm)){evm=cell.pdcch.qpskEvm;live=true;break;}
			if(!live)for(LteSignalAnalyzer.Candidate cell:result.candidates)
				if(cell.sib1Pdcch.valid&&Double.isFinite(cell.sib1Pdcch.qpskEvm)){evm=cell.sib1Pdcch.qpskEvm;break;}
			if(!live)for(LteSignalAnalyzer.Candidate cell:result.candidates)
				if(cell.sib1Pdsch.valid&&Double.isFinite(cell.sib1Pdsch.qpskEvm)){evm=cell.sib1Pdsch.qpskEvm;break;}
			if(!Double.isFinite(evm))return 0;
			return Math.max(0,Math.min(100,100*(1-evm)));
		}

		private static double rdsLockQuality(RdsSignalAnalyzer.Result result) {
			return result == null ? 0d : result.quality;
		}
	}

	private static final class Dvbt2CrcPanel extends JPanel {
		private volatile int count, preSuccess, postSuccess;

		Dvbt2CrcPanel() {
			setBackground(PANEL_BG);
			setPreferredSize(new Dimension(100, 22));
		}

		void setStats(int count, int preSuccess, int postSuccess) {
			this.count = count;
			this.preSuccess = preSuccess;
			this.postSuccess = postSuccess;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			super.paintComponent(graphics);
			Graphics2D g = (Graphics2D) graphics.create();
			try {
				int width = getWidth(), barY = 5, barH = Math.max(8, getHeight() - 9);
				int labelWidth = 128, gap = 12, barWidth = Math.max(24, (width - 2 * labelWidth - gap - 8) / 2);
				drawCrc(g, 4, barY, labelWidth, barWidth, barH, "L1-pre CRC", preSuccess);
				drawCrc(g, 4 + labelWidth + barWidth + gap, barY, labelWidth, barWidth, barH,
						"L1-post CRC", postSuccess);
			} finally {
				g.dispose();
			}
		}

		private void drawCrc(Graphics2D g, int x, int y, int labelWidth, int barWidth, int barHeight,
				String label, int success) {
			double percent = count == 0 ? 0 : success * 100d / count;
			g.setColor(TEXT_FG);
			String text = count == 0 ? label + " --" : String.format(Locale.US, "%s %d/%d %.0f%%",
					label, success, count, percent);
			g.drawString(text, x, 16);
			int barX = x + labelWidth;
			g.setColor(GRID);
			g.fillRect(barX, y, barWidth, barHeight);
			g.setColor(percent >= 90 ? QUALITY_GOOD : percent >= 50 ? QUALITY_WARN : QUALITY_BAD);
			g.fillRect(barX, y, (int)Math.round(barWidth * percent / 100d), barHeight);
			g.setColor(Color.DARK_GRAY);
			g.drawRect(barX, y, barWidth, barHeight);
		}
	}

	private static final class SpectrumPanel extends JPanel {
		private volatile Snapshot snapshot;

		SpectrumPanel() {
			setBackground(PANEL_BG);
		}

		void setSnapshot(Snapshot snapshot) {
			this.snapshot = snapshot;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			super.paintComponent(graphics);
			Graphics2D g = (Graphics2D) graphics.create();
			try {
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				int width = getWidth();
				int height = getHeight();
				int left = 12;
				int right = width - 10;
				int top = 22;
				int bottom = height - 22;
				g.setColor(GRID);
				for (int i = 0; i <= 4; i++) {
					int y = top + (bottom - top) * i / 4;
					g.drawLine(left, y, right, y);
				}
				g.drawLine(width / 2, top, width / 2, bottom);
				g.setColor(MUTED_FG);
				g.drawString(snapshot != null && snapshot.gsmResult != null
						? "GSM 200 kHz channel / FCCH" : "FFT spectrum", left, 15);
				Snapshot active = snapshot;
				if (active == null || active.spectrumDb == null || active.spectrumDb.length == 0) {
					g.drawString("Waiting for spectrum...", left, top + 24);
					return;
				}
				if (active.dabMetrics != null && active.dabMetrics.bandwidthHz > 0 && active.sampleRateHz > 0) {
					int halfWidth = Math.round((right - left) * (active.dabMetrics.bandwidthHz
							/ (float) active.sampleRateHz) * 0.5f);
					g.setColor(new Color(0x667733));
					g.drawLine(width / 2 - halfWidth, top, width / 2 - halfWidth, bottom);
					g.drawLine(width / 2 + halfWidth, top, width / 2 + halfWidth, bottom);
				} else if (active.gsmResult != null && active.sampleRateHz > 0) {
					int halfWidth = Math.round((right - left) * (200_000f / active.sampleRateHz) * 0.5f);
					g.setColor(new Color(0x667733));
					g.drawLine(width / 2 - halfWidth, top, width / 2 - halfWidth, bottom);
					g.drawLine(width / 2 + halfWidth, top, width / 2 + halfWidth, bottom);
				}
				float[] spectrum = active.spectrumDb;
				float min = Float.MAX_VALUE;
				float max = -Float.MAX_VALUE;
				for (float value : spectrum) {
					if (value < min) {
						min = value;
					}
					if (value > max) {
						max = value;
					}
				}
				float floor = Math.max(min, max - 70f);
				int previousX = left;
				int previousY = scaleSpectrumY(spectrum[0], floor, max, top, bottom);
				g.setColor(POINT);
				for (int i = 1; i < spectrum.length; i++) {
					int x = left + Math.round((right - left) * i / (float) (spectrum.length - 1));
					int y = scaleSpectrumY(spectrum[i], floor, max, top, bottom);
					g.drawLine(previousX, previousY, x, y);
					previousX = x;
					previousY = y;
				}
				g.setColor(MUTED_FG);
				g.drawString("-span/2", left, height - 6);
				g.drawString("0", Math.max(left, width / 2 - 4), height - 6);
				String rightLabel = "+span/2";
				g.drawString(rightLabel, Math.max(left, right - g.getFontMetrics().stringWidth(rightLabel)), height - 6);
			} finally {
				g.dispose();
			}
		}

		private int scaleSpectrumY(float value, float min, float max, int top, int bottom) {
			float range = Math.max(1f, max - min);
			float normalized = Math.max(0f, Math.min(1f, (value - min) / range));
			return bottom - Math.round((bottom - top) * normalized);
		}
	}

	/** P2/data cells reconstructed by the signalling decoder. This is kept
	 * separate from the continuously refreshed data-symbol constellation. */
	private static final class Dvbt2P2Panel extends JPanel {
		private volatile Snapshot snapshot;

		Dvbt2P2Panel() {
			setBackground(PANEL_BG);
		}

		void setSnapshot(Snapshot snapshot) {
			this.snapshot = snapshot;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			super.paintComponent(graphics);
			Graphics2D g = (Graphics2D) graphics.create();
			try {
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				int width = getWidth(), height = getHeight(), cx = width / 2, cy = height / 2;
				int radius = Math.max(20, Math.min(width, height) / 2 - 28);
				g.setColor(GRID);
				g.drawLine(16, cy, width - 16, cy);
				g.drawLine(cx, 18, cx, height - 18);
				g.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);
				g.setColor(MUTED_FG);
				g.drawString("DVB-T2 P1 differential constellation", 12, 16);
				Snapshot active = snapshot;
				Dvbt2SignalAnalyzer.Result result = active == null ? null : active.dvbt2Result;
				if (result == null || result.p2Points.length < 2) {
					g.drawString(result == null ? "Waiting for DVB-T2 P1..." : result.state, 18, 36);
					return;
				}
				drawBpskMarkers(g, cx, cy, radius);
				g.setColor(result.quality >= 65d ? QUALITY_GOOD : result.quality >= 35d ? QUALITY_WARN : POINT);
				float scale = previewScale(result.p2Points);
				for (int p = 0; p + 1 < result.p2Points.length; p += 2) {
					float nx = result.p2Points[p] * scale, ny = result.p2Points[p + 1] * scale;
					if (Math.abs(nx) > 1.08f || Math.abs(ny) > 1.08f) continue;
					g.fillRect(cx + Math.round(nx * radius * .94f), cy - Math.round(ny * radius * .94f), 2, 2);
				}
				g.setColor(MUTED_FG);
				g.drawString("Live P1 carriers", 12, height - 8);
			} finally {
				g.dispose();
			}
		}

		private void drawBpskMarkers(Graphics2D g, int cx, int cy, int radius) {
			g.setColor(new Color(0x446644));
			int distance = Math.round(radius * .72f);
			g.drawOval(cx - distance - 3, cy - 3, 6, 6);
			g.drawOval(cx + distance - 3, cy - 3, 6, 6);
		}

		private float previewScale(float[] points) {
			double magnitude = 0;
			int count = 0;
			for (int p = 0; p + 1 < points.length; p += 2) {
				magnitude += Math.hypot(points[p], points[p + 1]);
				count++;
			}
			return (float) (.72 / Math.max(1e-9, magnitude / Math.max(1, count)));
		}
	}

	private static final class DabConstellationPanel extends JPanel {
		private volatile Snapshot snapshot;

		DabConstellationPanel() {
			setBackground(PANEL_BG);
		}

		void setSnapshot(Snapshot snapshot) {
			this.snapshot = snapshot;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			super.paintComponent(graphics);
			Graphics2D g = (Graphics2D) graphics.create();
			try {
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				int width = getWidth();
				int height = getHeight();
				int cx = width / 2;
				int cy = height / 2;
				int radius = Math.max(20, Math.min(width, height) / 2 - 28);
				g.setColor(GRID);
				g.drawLine(16, cy, width - 16, cy);
				g.drawLine(cx, 18, cx, height - 18);
				g.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);
				Snapshot active = snapshot;
				DvbtSignalAnalyzer.Result dvbt = active == null ? null : active.dvbtResult;
				Dvbt2SignalAnalyzer.Result dvbt2 = active == null ? null : active.dvbt2Result;
				g.setColor(new Color(0x446644));
				int markerRadius = Math.max(3, radius / 28);
				if (dvbt == null && dvbt2 == null) {
					int markerDistance = Math.round(radius * 0.48f);
					for (int xSign : new int[] {-1, 1}) {
						for (int ySign : new int[] {-1, 1}) {
							g.drawOval(cx + xSign * markerDistance - markerRadius,
									cy + ySign * markerDistance - markerRadius, markerRadius * 2, markerRadius * 2);
						}
					}
				} else {
					String modulation = dvbt != null ? dvbt.modulation : dvbt2.l1PostConstellation;
					int levels = "256-QAM".equals(modulation) ? 16 : "64-QAM".equals(modulation) ? 8
							: "16-QAM".equals(modulation) ? 4 : "BPSK".equals(modulation) ? 1 : 2;
					float scale = levels == 8 ? 7.25f : levels == 4 ? 3.25f : 1.15f;
					if (levels == 16) scale = 16.2f;
					for (int xi = 1 - Math.max(2, levels); xi < Math.max(2, levels); xi += 2) {
						int yLevels = levels == 1 ? 1 : levels;
						for (int yi = 1 - yLevels; yi < yLevels; yi += 2) {
							int x = cx + Math.round(xi / scale * radius * 0.86f);
							int y = cy - Math.round(yi / scale * radius * 0.86f);
							g.drawOval(x - 2, y - 2, 4, 4);
						}
					}
				}
				g.setColor(MUTED_FG);
				g.drawString(dvbt2 != null ? "DVB-T2 L1-post " + dvbt2.l1PostConstellation
						: dvbt == null ? "DAB differential DQPSK" : "DVB-T equalized " + dvbt.modulation, 12, 16);
				if (dvbt2 != null) {
					if (dvbt2.l1PostPoints.length < 2) {
						g.drawString("Waiting for CRC-valid L1-post constellation...", 18, 36); return;
					}
					g.setColor(dvbt2.quality >= 65d ? QUALITY_GOOD : dvbt2.quality >= 35d ? QUALITY_WARN : POINT);
					float scale = "64-QAM".equals(dvbt2.l1PostConstellation) ? 7.25f
							: "16-QAM".equals(dvbt2.l1PostConstellation) ? 3.25f : 1.15f;
					for (int p=0;p+1<dvbt2.l1PostPoints.length;p+=2) {
						float nx=dvbt2.l1PostPoints[p]/scale, ny=dvbt2.l1PostPoints[p+1]/scale;
						if(Math.abs(nx)>1.08f||Math.abs(ny)>1.08f)continue;
						g.fillRect(cx+Math.round(nx*radius*.94f),cy-Math.round(ny*radius*.94f),2,2);
					}
					g.setColor(MUTED_FG);g.drawString("CRC-valid L1-post",12,height-8);
					String status=String.format(Locale.US,"DEMOD %.0f%%",dvbt2.quality);
					g.drawString(status,Math.max(12,width-g.getFontMetrics().stringWidth(status)-8),height-8);return;
				}
				if (dvbt != null) {
					if (dvbt.points.length < 2) { g.drawString(dvbt.state, 18, 36); return; }
					g.setColor(dvbt.quality >= 65d ? QUALITY_GOOD : dvbt.quality >= 35d ? QUALITY_WARN : POINT);
					float scale = "64-QAM".equals(dvbt.modulation) ? 7.25f : "16-QAM".equals(dvbt.modulation) ? 3.25f : 1.15f;
					for (int p=0; p+1<dvbt.points.length; p+=2) {
						float normalizedX = dvbt.points[p] / scale;
						float normalizedY = dvbt.points[p + 1] / scale;
						if (Math.abs(normalizedX) > 1.08f || Math.abs(normalizedY) > 1.08f) continue;
						int x=cx+Math.round(normalizedX*radius*0.94f), y=cy-Math.round(normalizedY*radius*0.94f);
						g.fillRect(x,y,2,2);
					}
					g.setColor(MUTED_FG); g.drawString(dvbt.state,12,height-8);
					String status=String.format(Locale.US,"MER %.1f dB  Q %.0f%%",dvbt.merDb,dvbt.quality);
					g.drawString(status,Math.max(12,width-g.getFontMetrics().stringWidth(status)-8),height-8);
					return;
				}
				DabConstellation constellation = active == null || active.dabMetrics == null
						? null : active.dabMetrics.constellation;
				if (constellation == null || constellation.points == null || constellation.points.length < 2) {
					g.drawString("Searching for DAB OFDM sync...", 18, 36);
					return;
				}
				g.setColor(constellation.lockScore >= 65d ? QUALITY_GOOD
						: constellation.lockScore >= 35d ? QUALITY_WARN : POINT);
				float[] points = constellation.points;
				for (int i = 0; i + 1 < points.length; i += 2) {
					int x = cx + Math.round(points[i] * radius * 0.86f);
					int y = cy - Math.round(points[i + 1] * radius * 0.86f);
					g.fillRect(x, y, 2, 2);
				}
				g.setColor(MUTED_FG);
				g.drawString(constellation.state, 12, height - 8);
				long ageMillis = Math.max(0, System.currentTimeMillis() - active.createdMillis);
				String status = String.format(Locale.US, "lock %.0f%%  %d ms", constellation.lockScore, ageMillis);
				g.drawString(status, Math.max(12, width - g.getFontMetrics().stringWidth(status) - 8), height - 8);
			} finally {
				g.dispose();
			}
		}
	}

	private static final class ScatterPanel extends JPanel {
		private volatile Snapshot snapshot;

		ScatterPanel() {
			setBackground(PANEL_BG);
		}

		void setSnapshot(Snapshot snapshot) {
			this.snapshot = snapshot;
			repaint();
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			super.paintComponent(graphics);
			Graphics2D g = (Graphics2D) graphics.create();
			try {
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				int width = getWidth();
				int height = getHeight();
				int cx = width / 2;
				int cy = height / 2;
				int radius = Math.max(20, Math.min(width, height) / 2 - 24);
				g.setColor(GRID);
				g.drawLine(16, cy, width - 16, cy);
				g.drawLine(cx, 16, cx, height - 16);
				g.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);
				Snapshot active = snapshot;
				if (active == null || active.sampleCount <= 0) {
					g.setColor(MUTED_FG);
					g.drawString("Waiting for IQ samples...", 18, 28);
					return;
				}
				g.setColor(POINT);
				byte[] samples = active.samples;
				for (int i = 0; i + 1 < samples.length; i += 2) {
					int x = cx + Math.round(samples[i] / 128f * radius);
					int y = cy - Math.round(samples[i + 1] / 128f * radius);
					g.fillRect(x, y, 2, 2);
				}
			} finally {
				g.dispose();
			}
		}
	}
}
