package jspectrumanalyzer.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

import java.io.FileNotFoundException;
import java.math.BigDecimal;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.DefaultBoundedRangeModel;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.Popup;
import javax.swing.PopupFactory;
import javax.swing.RootPaneContainer;
import javax.swing.JSeparator;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JSpinner.ListEditor;
import javax.swing.JTextField;
import javax.swing.SpinnerListModel;
import javax.swing.UIManager;

import jspectrumanalyzer.core.FrequencyAllocationTable;
import jspectrumanalyzer.core.FrequencyAllocations;
import jspectrumanalyzer.core.FrequencyPresets;
import jspectrumanalyzer.core.FrequencyRange;
import jspectrumanalyzer.core.HackRFSettings;
import jspectrumanalyzer.core.Preset;
import jspectrumanalyzer.core.Ranges;
import net.miginfocom.swing.MigLayout;
import shared.mvc.MVCController;
import javax.swing.border.EmptyBorder;
import javax.swing.SwingUtilities;
import javax.swing.JComboBox;
import javax.swing.SwingConstants;
import javax.swing.JOptionPane;


public class HackRFSweepSettingsUI extends JPanel
{
	/**
	 * 
	 */
	private HackRFSettings hRF;
	private static final long serialVersionUID = 7721079457485020637L;
	public static final String CONTEXT_HELP_PROPERTY = "jspectrumanalyzer.contextHelp";
	private static final Pattern FREQUENCY_RANGE_INTERVAL = Pattern.compile("\\s*(-?\\d+)\\s*-\\s*(-?\\d+)\\s*");
	private static final int CONTEXT_HELP_HOVER_DELAY_MS = 650;
	private static final int CONTEXT_HELP_AUTO_HIDE_MS = 9000;
	private FrequencyRange FrequencyRange;
	private FrequencySelectorPanel frequencySelectorStart;
	private FrequencySelectorPanel frequencySelectorEnd;
	private JLabel frequencySelectorStartPrefix;
	private JLabel frequencySelectorEndPrefix;
	private boolean updatingShiftedFrequencyControls;
	private JSpinner spinnerFFTBinHz;
	private JSlider sliderGain;
	private JSpinner spinner_numberOfSamples;
	private JCheckBox chckbxAntennaPower;
	private JSlider slider_waterfallPaletteStart;
	private JSlider slider_AmplitudeOffset;
	private JSlider slider_PowerFluxCal;
	private JSlider slider_WaterfallSpeed;	
	private JSlider slider_waterfallPaletteSize;
	private JCheckBox chckbxShowRealtime;
	private JCheckBox chckbxShowAverage;
	private JCheckBox chckbxShowPeaks;
	private JCheckBox chckbxShowMaxHold;
	private JCheckBox chckbxShowMinHold;
	private JCheckBox chckbxShowPeakMarker;
	private JCheckBox chckbxShowHoldMarker;
	private JCheckBox chckbxDatestamp;
	private JCheckBox chckbxRemoveSpurs;
	private JButton btnPause;
	private JButton btnRecVideo;
	private JButton btnRecData;
	private JButton btnRecSpectrum;
	private JButton btnPlaySpectrum;
	private JButton btnTrigger;
	private JButton btnReset;
	private JButton btnContextHelp;
	private Cursor contextHelpCursor;
	private Cursor contextHelpPreviousCursor;
	private JComponent contextHelpPreviewGlassPane;
	private MouseAdapter contextHelpPreviewMouseListener;
	private KeyAdapter contextHelpPreviewKeyListener;
	private javax.swing.Timer contextHelpHoverTimer;
	private javax.swing.Timer contextHelpAutoHideTimer;
	private Popup contextHelpPopup;
	private Component contextHelpHoverComponent;
	private Point contextHelpHoverPoint;
	private JButton button_plus;
	private JButton button_minus;
	private SpinnerListModel spinnerModelFFTBinHz;
	private boolean updatingRbwSpinner;
	private JCheckBox chckbxFilterSpectrum;
	private JSpinner spinnerPeakFallSpeed;
	private JSpinner spinnerMarkerCount;
	private JComboBox<FrequencyAllocationTable> comboBoxFrequencyAllocationBands;
	private JComboBox<Preset> comboBoxFrequencyPresets;
	private JButton btnSavePreset;
	private JSlider sliderGainVGA;
	private JSlider sliderGainLNA;
	private JCheckBox chckbxAntennaLNA;
	//private JComboBox<BigDecimal> comboBoxLineThickness;
	private JSpinner spinnerLineThickness;
	private JCheckBox checkBoxSpectrumSpline;
	private JCheckBox checkBoxPersistentDisplay;
	private JCheckBox checkBoxWaterfallEnabled;
	private JCheckBox checkBoxInfoBoxEnabled;
	//private JComboBox comboBoxDecayRate;
	private JSpinner spinnerDecayRate;
	private JSpinner spinnerAvgIterations;
	private JSpinner spinnerLogDetail;
	private JCheckBox checkBoxDebugDisplay;
	private JSpinner spinner_FrequencyShift;
	private JSpinner spinnerPeakFallTrs;
	private JSpinner spinnerPeakHoldTime;
	private JSpinner spinnerVideoFormat;
	private JSpinner spinnerVideoResolution;
	private JSpinner spinnerVideoFrameRate;
	private JSpinner spinnerSpectrumRecordFrameRate;
	private JSpinner spinnerVideoArea;
	private JSlider sliderAvgOffset;
	private JPanel panelIqReplayAudio;
	private JCheckBox checkBoxIqReplayAudio;
	private JSlider sliderIqReplayAudioVolume;
	private JComboBox<String> comboBoxIqReplayAudioMode;
	private JPanel panelIqReplayOptions;
	private JCheckBox checkBoxIqReplayBurstCapture;
	private JCheckBox checkBoxIqReplayTxCustomFrequency;
	private JPanel panelIqReplayTx;
	private JCheckBox checkBoxIqReplayTx;
	private JTextField textFieldIqReplayTxCenter;
	private JSlider sliderIqReplayTxPower;
	private JLabel labelIqReplayTxPower;
	private JLabel lblGain;
	private JLabel lblGainValue;
	private JLabel lblLnaGain;
	private JLabel lblVgaGain;
	private JLabel lblPeakFall;
	private JLabel lblPeakFallTrs;
	private JLabel lblPeakHoldTime;
	private JLabel lblMarkerCount;
	private JLabel lblAvgIterations;
	private JLabel lblAvgOffset;
	private JLabel lblAvgOffset2;
	private JLabel lblDecayRate;
	private JTextField textFieldFreqRange;
	/**
	 * Create the panel.
	 * @throws FileNotFoundException 
	 */
	public HackRFSweepSettingsUI(HackRFSettings hackRFSettings) throws FileNotFoundException
	{
		this.hRF	= hackRFSettings;
		setForeground(Color.WHITE);
		setBackground(Color.BLACK);
		int minFreq = 0;
		int maxFreq = 7250;
		int freqStep = 1;

		JPanel panelMainSettings	= new JPanel(new MigLayout("", "[190.00px,grow,leading]", "[][][::0px][][]"));
		panelMainSettings.setBorder(new EmptyBorder(UIManager.getInsets("TabbedPane.tabAreaInsets")));;
		panelMainSettings.setBackground(Color.BLACK);

		JPanel headerPanel = new JPanel(new BorderLayout(4, 0));
		headerPanel.setBackground(Color.BLACK);
		JLabel lblNewLabel = new JLabel("Frequency start [MHz]");
		lblNewLabel.setForeground(Color.WHITE);
		headerPanel.add(lblNewLabel, BorderLayout.CENTER);
		btnContextHelp = createContextHelpButton();
		headerPanel.add(btnContextHelp, BorderLayout.EAST);
		panelMainSettings.add(headerPanel, "cell 0 0,growx,aligny center");

		frequencySelectorStart = new FrequencySelectorPanel(minFreq, 9999, freqStep, minFreq);
		frequencySelectorStartPrefix = createFrequencySelectorPrefixLabel();
		panelMainSettings.add(createShiftedFrequencySelectorPreview(frequencySelectorStart,
				frequencySelectorStartPrefix), "cell 0 1,alignx center");

		JLabel lblFrequencyEndmhz = new JLabel("Frequency stop [MHz]");
		lblFrequencyEndmhz.setForeground(Color.WHITE);
		panelMainSettings.add(lblFrequencyEndmhz, "cell 0 3,alignx left,aligny center");

		frequencySelectorEnd = new FrequencySelectorPanel(minFreq, 9999, freqStep, maxFreq);
		frequencySelectorEndPrefix = createFrequencySelectorPrefixLabel();
		panelMainSettings.add(createShiftedFrequencySelectorPreview(frequencySelectorEnd,
				frequencySelectorEndPrefix), "cell 0 4,alignx center");
		
		button_minus = new JButton("<---");
		button_minus.setBackground(Color.BLACK);
		button_minus.addActionListener(e -> {
			FrequencyRange current = hRF.getFrequency().getValue();
			int dif = (int) Math.round((current.getEndMHz() - current.getStartMHz()) * 0.9);
			applyHardwareFrequencyRange(current.getStartMHz() - dif, current.getEndMHz() - dif);
		});
		panelMainSettings.add(button_minus, "cell 0 6,alignx left,growx");

		button_plus = new JButton("--->");
		button_plus.setBackground(Color.BLACK);
		button_plus.addActionListener(e -> {
			FrequencyRange current = hRF.getFrequency().getValue();
			int dif = (int) Math.round((current.getEndMHz() - current.getStartMHz()) * 0.9);
			applyHardwareFrequencyRange(current.getStartMHz() + dif, current.getEndMHz() + dif);
		});
		panelMainSettings.add(button_plus, "flowx,cell 0 6,growx,alignx right");
		
		JLabel lblFreqRange = new JLabel("Ranges ");
		lblFreqRange.setForeground(Color.WHITE);
		panelMainSettings.add(lblFreqRange, "cell 0 7,alignx left");
		
		textFieldFreqRange = new JTextField(20);
		textFieldFreqRange.setFont(new Font("Monospaced", Font.BOLD, 14));
		textFieldFreqRange.setForeground(Color.WHITE);
		textFieldFreqRange.setBackground(Color.BLACK);
		textFieldFreqRange.setCaretColor(Color.WHITE);
		textFieldFreqRange.setText(hRF.getparamFreqRange().getValue());
		panelMainSettings.add(textFieldFreqRange, "cell 0 7,growx,alignx right");
		
		JLabel lblFrequencyPreset = new JLabel("Presets ");
		lblFrequencyPreset.setForeground(Color.WHITE);
		panelMainSettings.add(lblFrequencyPreset, "cell 0 8,alignx left");
		
		btnSavePreset = new JButton("+");
		btnSavePreset.setBackground(Color.green);
		btnSavePreset.setForeground(Color.BLACK);
		//btnSavePreset.setBorderPainted(false);
		btnSavePreset.setPreferredSize(new Dimension(20, 20));
		btnSavePreset.setBorder(BorderFactory.createEmptyBorder());
		btnSavePreset.addActionListener(e -> {
			// prompt for preset name
			String presetName = JOptionPane.showInputDialog(this, "Preset name:", "Save preset", JOptionPane.PLAIN_MESSAGE);
			if (presetName == null) return; // cancelled
			presetName = presetName.trim();
			if (presetName.length() == 0) return;
			// sanitize (no semicolons/newlines)
			presetName = presetName.replaceAll("[;\n\r]", " ");
			// gather current values
			FrequencyRange fr = hRF.getFrequency().getValue();
			int start = fr.getStartMHz();
			int stop = fr.getEndMHz();
			String fft = String.valueOf(hRF.getFFTBinHz().getValue());
			String fshift = String.valueOf(hRF.getFreqShift().getValue());
			int ampOff = hRF.getAmplitudeOffset().getValue();
			// append to presets.csv
			// read existing presets, insert new one sorted by start freq (for multirange start=-1 use first range start)
			java.io.File presetsFile = new java.io.File("presets.csv");
			try {
				java.util.List<String> lines = new java.util.ArrayList<>();
				if (presetsFile.exists()) {
					try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(presetsFile))) {
						String l;
						while ((l = br.readLine()) != null) lines.add(l);
					}
				} else {
					// create with header if missing
					lines.add("Preset Name;Freq Min;Freq Max;FFT Bin Width;Freq Shift;Amplitude Offset");
				}
				// prepare new entry fields
				String startStr;
				String stopStr;
				String freqRangeText = hRF.getparamFreqRange().getValue();
				if (freqRangeText != null && freqRangeText.contains(",")) {
					startStr = "-1";
					stopStr = freqRangeText;
				} else {
					startStr = String.valueOf(start);
					stopStr = String.valueOf(stop);
				}
				String newLine = presetName + ";" + startStr + ";" + stopStr + ";" + fft + ";" + fshift + ";" + ampOff;
				// build list of entries (skip header)
				java.util.List<java.util.AbstractMap.SimpleEntry<Long,String>> entries = new java.util.ArrayList<>();
				for (int i=0;i<lines.size();i++) {
					String ln = lines.get(i);
					if (i==0 && ln.startsWith("Preset Name")) continue;
					String[] cols = ln.split(";");
					if (cols.length < 2) continue;
					long key = 0;
					try {
						int s = Integer.parseInt(cols[1]);
						if (s == -1) {
							// parse first range from cols[2]
							String ranges = cols[2];
							String first = ranges.split(",")[0];
							String[] ps = first.split("-");
							key = Integer.parseInt(ps[0]);
						} else {
							key = s;
						}
					} catch (Exception ex) { key = Long.MAX_VALUE/4; }
					entries.add(new java.util.AbstractMap.SimpleEntry<>(key, ln));
				}
				// add new entry
				long newKey = 0;
				try {
					if (startStr.equals("-1")) {
						String first = stopStr.split(",")[0];
						newKey = Integer.parseInt(first.split("-")[0]);
					} else {
						newKey = Integer.parseInt(startStr);
					}
				} catch (Exception ex) { newKey = Long.MAX_VALUE/4; }
				entries.add(new java.util.AbstractMap.SimpleEntry<>(newKey, newLine));
				// sort by key
				entries.sort((a,b) -> Long.compare(a.getKey(), b.getKey()));
				// write back
				try (java.io.BufferedWriter bw = new java.io.BufferedWriter(new java.io.FileWriter(presetsFile))) {
					bw.write("Preset Name;Freq Min;Freq Max;FFT Bin Width;Freq Shift;Amplitude Offset");
					bw.newLine();
					for (java.util.AbstractMap.SimpleEntry<Long,String> entry : entries) {
						bw.write(entry.getValue());
						bw.newLine();
					}
					bw.flush();
				}
				// refresh presets combo
				FrequencyPresets newPres = new FrequencyPresets();
				Vector<Preset> newPresetValues = new Vector<>();
				newPresetValues.addAll(newPres.getList());
				comboBoxFrequencyPresets.setModel(new DefaultComboBoxModel<>(newPresetValues));
				for (Preset p : newPres.getList()) {
					if (presetName.equals(p.toString())) {
						comboBoxFrequencyPresets.setSelectedItem(p);
						break;
					}
				}
			} catch (Exception ex) {
				ex.printStackTrace();
				JOptionPane.showMessageDialog(this, "Failed to save preset: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
			}
		});
		// layout: add save then reset
		panelMainSettings.add(btnSavePreset, "cell 0 8,alignx right");

		FrequencyPresets presets = new FrequencyPresets();
		Vector presetValues = new Vector<>();
		//presetValues.add(null);
		presetValues.addAll(presets.getList());
		comboBoxFrequencyPresets = new JComboBox(presetValues);
		comboBoxFrequencyPresets.addActionListener(e -> {
			Preset preset = (Preset)comboBoxFrequencyPresets.getSelectedItem();
			spinnerFFTBinHz.setValue(preset.getFFTBinWidth());
			spinner_FrequencyShift.setValue(preset.getFreqShift());
			slider_AmplitudeOffset.setValue(preset.getAmplitudeOffset());
			String presetRange = preset.getFreqRange();
			hRF.getparamFreqRange().setValue(presetRange);
			Ranges range = new Ranges(presetRange);
			hRF.getFrequency().setValue(new FrequencyRange(range.getFreqStart(), range.getFreqStop()));

		});
		panelMainSettings.add(comboBoxFrequencyPresets, "cell 0 8,growx,alignx right");

		JPanel spacerMainControls = new JPanel();
		spacerMainControls.setBackground(Color.BLACK);
		panelMainSettings.add(spacerMainControls, "cell 0 23,growx,h 8!");
		
		btnPause = new JButton("PAUSE");
		panelMainSettings.add(btnPause, "cell 0 25,growx,alignx left");
		btnPause.setBackground(Color.yellow);
		//btnPause.setBorderPainted(false);
		btnPause.setPreferredSize(new Dimension(16, 16));
		btnPause.setBorder(BorderFactory.createEmptyBorder());

		btnTrigger = new JButton("TRIG");
		panelMainSettings.add(btnTrigger, "cell 0 25,growx,alignx left");
		btnTrigger.setForeground(Color.black);
		btnTrigger.setBackground(new Color(0xFFAA33));
		btnTrigger.setPreferredSize(new Dimension(25, 16));
		btnTrigger.setBorder(BorderFactory.createEmptyBorder());

		btnPlaySpectrum = new JButton("REPLAY");
		panelMainSettings.add(btnPlaySpectrum, "cell 0 25,growx,alignx left,wrap");
		btnPlaySpectrum.setForeground(Color.black);
		btnPlaySpectrum.setBackground(Color.cyan);
		btnPlaySpectrum.setPreferredSize(new Dimension(25, 16));
		btnPlaySpectrum.setBorder(BorderFactory.createEmptyBorder());

		btnRecVideo = new JButton("VIDEO");
		panelMainSettings.add(btnRecVideo, "cell 0 26,growx,alignx left");
		btnRecVideo.setForeground(Color.black);
		btnRecVideo.setBackground(Color.red);
		//btnRecVideo.setBorderPainted(false);
		btnRecVideo.setPreferredSize(new Dimension(25, 16));
		btnRecVideo.setBorder(BorderFactory.createEmptyBorder());
		
		btnRecData = new JButton("STATS");
		panelMainSettings.add(btnRecData, "cell 0 26,growx,alignx left");
		btnRecData.setForeground(Color.black);
		btnRecData.setBackground(Color.red);
		//btnRecData.setBorderPainted(false);
		btnRecData.setPreferredSize(new Dimension(25, 16));
		btnRecData.setBorder(BorderFactory.createEmptyBorder());

		btnRecSpectrum = new JButton("DATA");
		panelMainSettings.add(btnRecSpectrum, "cell 0 26,growx,alignx left");
		btnRecSpectrum.setForeground(Color.black);
		btnRecSpectrum.setBackground(Color.red);
		btnRecSpectrum.setPreferredSize(new Dimension(25, 16));
		btnRecSpectrum.setBorder(BorderFactory.createEmptyBorder());

		JTabbedPane tabbedPane	= new JTabbedPane(JTabbedPane.TOP);
		setLayout(new BorderLayout());
		add(panelMainSettings, BorderLayout.NORTH);
		add(tabbedPane, BorderLayout.CENTER);
		tabbedPane.setForeground(Color.WHITE);
		tabbedPane.setBackground(Color.BLACK);

		JPanel tab1	= new JPanel(new MigLayout("", "[123.00px,grow,leading]", "[][][0][][][0][][][0][][][0][][][0][][0][][grow,fill]"));
		tab1.setForeground(Color.WHITE);
		tab1.setBackground(Color.BLACK);
		
		JPanel tab2	= new JPanel(new MigLayout("", "[123.00px,grow,leading]", "[][0][][][0][][][0][][0][][][0][][0][][][0][0][][][0][][0][grow,fill]"));
		tab2.setForeground(Color.WHITE);
		tab2.setBackground(Color.BLACK);
		
		JPanel tab3	= new JPanel(new MigLayout("", "[123.00px,grow,leading]", "[][0][][][0][][][0][][0][][][0][][0][][][0][0][][][0][][0][grow,fill]"));
		tab3.setForeground(Color.WHITE);
		tab3.setBackground(Color.BLACK);
		
		tabbedPane.addTab("Sweep  ", tab1);
		tabbedPane.addTab("Params  ", tab2);
		tabbedPane.addTab("Waterfall/REC  ", tab3);
		tabbedPane.setForegroundAt(2, Color.BLACK);
		tabbedPane.setBackgroundAt(2, Color.WHITE);
		
		tabbedPane.setForegroundAt(1, Color.BLACK);
		tabbedPane.setBackgroundAt(1, Color.WHITE);

		tabbedPane.setForegroundAt(0, Color.BLACK);
		tabbedPane.setBackgroundAt(0, Color.WHITE);
		
		//tab1
		{
			lblGain = new JLabel("Gain");
			lblGain.setForeground(Color.WHITE);
			tab1.add(lblGain, "cell 0 0,hidemode 3");
			
			lblGainValue = new JLabel(hackRFSettings.getGain() + "dB");
			lblGainValue.setForeground(Color.WHITE);
			tab1.add(lblGainValue, "flowx,cell 0 0,alignx right,hidemode 3");

			sliderGain = new JSlider(JSlider.HORIZONTAL, 0, 100, 2);
			sliderGain.setFont(new Font("Monospaced", Font.BOLD, 14));
			sliderGain.setBackground(Color.BLACK);
			sliderGain.setForeground(Color.WHITE);
			tab1.add(sliderGain, "flowx,cell 0 1,growx,hidemode 3");

			lblLnaGain = new JLabel("LNA Gain");
			lblLnaGain.setForeground(Color.WHITE);
			tab1.add(lblLnaGain, "cell 0 2,hidemode 3");
			
			sliderGainLNA = new JSlider(SwingConstants.HORIZONTAL, 0, 100, 2);
			sliderGainLNA.setForeground(Color.WHITE);
			sliderGainLNA.setFont(new Font("Monospaced", Font.BOLD, 14));
			sliderGainLNA.setBackground(Color.BLACK);
			tab1.add(sliderGainLNA, "cell 0 2,growx,hidemode 3");
			
			lblVgaGain = new JLabel("VGA Gain");
			lblVgaGain.setForeground(Color.WHITE);
			tab1.add(lblVgaGain, "cell 0 4,hidemode 3");
			
			sliderGainVGA = new JSlider(SwingConstants.HORIZONTAL, 0, 100, 2);
			sliderGainVGA.setForeground(Color.WHITE);
			sliderGainVGA.setFont(new Font("Monospaced", Font.BOLD, 14));
			sliderGainVGA.setBackground(Color.BLACK);
			tab1.add(sliderGainVGA, "cell 0 4,growx,hidemode 3");
			/*
			JLabel lblHW = new JLabel("HW");
			lblHW.setForeground(Color.WHITE);
			tab1.add(lblHW, "growx,cell 0 7");
			*/
			chckbxAntennaLNA = new JCheckBox(" RF amp");
			chckbxAntennaLNA.setHorizontalTextPosition(SwingConstants.TRAILING);
			chckbxAntennaLNA.setBackground(Color.BLACK);
			chckbxAntennaLNA.setForeground(Color.WHITE);
			tab1.add(chckbxAntennaLNA, "cell 0 7,alignx right");

			chckbxAntennaPower = new JCheckBox(" BiasT");
			chckbxAntennaPower.setHorizontalTextPosition(SwingConstants.TRAILING);
			chckbxAntennaPower.setBackground(Color.BLACK);
			chckbxAntennaPower.setForeground(Color.WHITE);
			tab1.add(chckbxAntennaPower, "cell 0 7,alignx right");
			
			chckbxRemoveSpurs = new JCheckBox(" no DC");
			chckbxRemoveSpurs.setHorizontalTextPosition(SwingConstants.TRAILING);
			chckbxRemoveSpurs.setForeground(Color.WHITE);
			chckbxRemoveSpurs.setBackground(Color.BLACK);
			tab1.add(chckbxRemoveSpurs, "cell 0 7,alignx right");
			
			JSeparator separator2 = new JSeparator();
			tab1.add(separator2,"cell 0 8,growx");
			
			JLabel lblCALC = new JLabel("TRACE");
			lblCALC.setForeground(Color.WHITE);
			tab1.add(lblCALC, "growx,cell 0 9");
			
			chckbxShowRealtime = new JCheckBox(" RealTime");
			chckbxShowRealtime.setHorizontalTextPosition(SwingConstants.TRAILING);
			chckbxShowRealtime.setBackground(Color.BLACK);
			chckbxShowRealtime.setForeground(Color.WHITE);
			tab1.add(chckbxShowRealtime, "cell 0 9,alignx right");
			
			checkBoxPersistentDisplay = new JCheckBox(" Persist");
			checkBoxPersistentDisplay.setHorizontalTextPosition(SwingConstants.TRAILING);
			checkBoxPersistentDisplay.setForeground(Color.WHITE);
			checkBoxPersistentDisplay.setBackground(Color.BLACK);
			tab1.add(checkBoxPersistentDisplay, "cell 0 9,alignx right");

			chckbxShowAverage = new JCheckBox(" Avg");
			chckbxShowAverage.setHorizontalTextPosition(SwingConstants.TRAILING);
			chckbxShowAverage.setBackground(Color.BLACK);
			chckbxShowAverage.setForeground(Color.WHITE);
			tab1.add(chckbxShowAverage, "cell 0 10,alignx right");
			
			chckbxShowPeaks = new JCheckBox(" Peak");
			chckbxShowPeaks.setForeground(Color.WHITE);
			chckbxShowPeaks.setBackground(Color.BLACK);
			tab1.add(chckbxShowPeaks, "cell 0 10,alignx right");
			
			chckbxShowMaxHold = new JCheckBox(" HOLD");
			chckbxShowMaxHold.setHorizontalTextPosition(SwingConstants.TRAILING);
			chckbxShowMaxHold.setBackground(Color.BLACK);
			chckbxShowMaxHold.setForeground(Color.WHITE);
			tab1.add(chckbxShowMaxHold, "cell 0 10,alignx right");
			
			JLabel lblMARK = new JLabel("MARKER");
			lblMARK.setForeground(Color.WHITE);
			tab1.add(lblMARK, "growx,cell 0 11");
			
			chckbxShowPeakMarker = new JCheckBox(" Peak");
			chckbxShowPeakMarker.setHorizontalTextPosition(SwingConstants.TRAILING);
			chckbxShowPeakMarker.setBackground(Color.BLACK);
			chckbxShowPeakMarker.setForeground(Color.WHITE);
			tab1.add(chckbxShowPeakMarker, "cell 0 11,alignx right");
			
			chckbxShowHoldMarker = new JCheckBox(" HOLD");
			chckbxShowHoldMarker.setHorizontalTextPosition(SwingConstants.TRAILING);
			chckbxShowHoldMarker.setForeground(Color.WHITE);
			chckbxShowHoldMarker.setBackground(Color.BLACK);
			tab1.add(chckbxShowHoldMarker, "cell 0 11,alignx right");
			
			
			JSeparator separator3 = new JSeparator();
			tab1.add(separator3,"cell 0 12,growx");
			
			JLabel lblFREQ = new JLabel("FREQ");
			lblFREQ.setForeground(Color.WHITE);
			tab1.add(lblFREQ, "growx,cell 0 13");
			
			JLabel lblFftBinhz = new JLabel("RBW [kHz] ");
			lblFftBinhz.setForeground(Color.WHITE);
			tab1.add(lblFftBinhz, "cell 0 13,alignx right");

			spinnerFFTBinHz = new JSpinner();
			spinnerFFTBinHz.setFont(new Font("Monospaced", Font.BOLD, 14));
			spinnerModelFFTBinHz = new SpinnerListModel(new String[] { "0.05", "0.1", "0.2", "0.5", "1",
					"3", "10", "20",
					"50", "100", "200", "500", "1000", "2000" });
			spinnerFFTBinHz.setModel(spinnerModelFFTBinHz);
			tab1.add(spinnerFFTBinHz, "flowx, cell 0 13,alignx right");
			((ListEditor) spinnerFFTBinHz.getEditor()).getTextField().setHorizontalAlignment(JTextField.RIGHT);
			((ListEditor) spinnerFFTBinHz.getEditor()).getTextField().setColumns(5);
			

			hackRFSettings.getGain().addListener((gain) -> lblGainValue.setText(String.format("%d dB (LNA: %d dB  VGA: %d dB)",
					gain, hackRFSettings.getGainLNA().getValue(), hackRFSettings.getGainVGA().getValue())));
			
			JLabel lblDisplayFrequencyShift = new JLabel("Shift [MHz] ");
			lblDisplayFrequencyShift.setForeground(Color.WHITE);
			tab1.add(lblDisplayFrequencyShift, "cell 0 14,alignx right");
			
			spinner_FrequencyShift = new JSpinner();
			spinner_FrequencyShift.setModel(new SpinnerListModel(new String[] { "0", "-120", "9750", "10600" }));
			spinner_FrequencyShift.setFont(new Font("Monospaced", Font.BOLD, 14));
			((ListEditor) spinner_FrequencyShift.getEditor()).getTextField().setHorizontalAlignment(JTextField.RIGHT);
			((ListEditor) spinner_FrequencyShift.getEditor()).getTextField().setColumns(5);
			tab1.add(spinner_FrequencyShift, "cell 0 14,alignx right");
			
			JLabel lblAmplitudeOffset = new JLabel("Ampl Offset");
			lblAmplitudeOffset.setForeground(Color.WHITE);
			tab1.add(lblAmplitudeOffset, "cell 0 15");
			
			slider_AmplitudeOffset = new JSlider(JSlider.HORIZONTAL, 0, 100, 2);
			slider_AmplitudeOffset.setForeground(Color.WHITE);
			slider_AmplitudeOffset.setBackground(Color.BLACK);
			slider_AmplitudeOffset.setFont(new Font("Monospaced", Font.BOLD, 14));
			slider_AmplitudeOffset.setMinimum(-20);
			slider_AmplitudeOffset.setMaximum(20);
			//slider_AmplitudeOffset.setValue(30);
			tab1.add(slider_AmplitudeOffset, "cell 0 15,growx");
			
			JLabel lblAmplitudeOffset2 = new JLabel();
			lblAmplitudeOffset2.setForeground(Color.WHITE);
			tab1.add(lblAmplitudeOffset2, "cell 0 15, alignx right");
			
			hackRFSettings.getAmplitudeOffset().addListener((amplitude) -> lblAmplitudeOffset2.setText(String.format("%d dB", amplitude)));
			
			JLabel lblPowerFluxCal = new JLabel("Power Calbr");
			lblPowerFluxCal.setForeground(Color.WHITE);
			tab1.add(lblPowerFluxCal, "cell 0 16");
			
			slider_PowerFluxCal = new JSlider(JSlider.HORIZONTAL, 0, 100, 2);
			slider_PowerFluxCal.setForeground(Color.WHITE);
			slider_PowerFluxCal.setBackground(Color.BLACK);
			slider_PowerFluxCal.setFont(new Font("Monospaced", Font.BOLD, 14));
			slider_PowerFluxCal.setMinimum(25);
			slider_PowerFluxCal.setMaximum(65);
			//slider_AmplitudeOffset.setValue(30);
			tab1.add(slider_PowerFluxCal, "cell 0 16,growx");
			
			JLabel lblPowerFluxCal2 = new JLabel();
			lblPowerFluxCal2.setForeground(Color.WHITE);
			tab1.add(lblPowerFluxCal2, "cell 0 16, alignx right");
			
			hackRFSettings.getPowerFluxCal().addListener((fluxcal) -> lblPowerFluxCal2.setText(String.format("%d", fluxcal)));

			panelIqReplayAudio = new JPanel(new BorderLayout(3, 0));
			panelIqReplayAudio.setBackground(Color.BLACK);
			checkBoxIqReplayAudio = new JCheckBox("Audio");
			checkBoxIqReplayAudio.setForeground(Color.WHITE);
			checkBoxIqReplayAudio.setBackground(Color.BLACK);
			checkBoxIqReplayAudio.setMargin(new java.awt.Insets(0, 0, 0, 0));
			sliderIqReplayAudioVolume = new JSlider(0, 100, 80);
			sliderIqReplayAudioVolume.setForeground(Color.WHITE);
			sliderIqReplayAudioVolume.setBackground(Color.BLACK);
			compactSlider(sliderIqReplayAudioVolume, new Dimension(44, 16));
			sliderIqReplayAudioVolume.setMinimumSize(new Dimension(32, 16));
			sliderIqReplayAudioVolume.setMaximumSize(new Dimension(48, 16));
			comboBoxIqReplayAudioMode = new JComboBox<>(new String[] { "AM", "FM" });
			comboBoxIqReplayAudioMode.setPreferredSize(new Dimension(48, 22));
			comboBoxIqReplayAudioMode.setMinimumSize(new Dimension(48, 22));
			comboBoxIqReplayAudioMode.setMaximumSize(new Dimension(48, 22));
			panelIqReplayAudio.add(checkBoxIqReplayAudio, BorderLayout.WEST);
			panelIqReplayAudio.add(sliderIqReplayAudioVolume, BorderLayout.CENTER);
			panelIqReplayAudio.add(comboBoxIqReplayAudioMode, BorderLayout.EAST);
			tab1.add(panelIqReplayAudio, "cell 0 2,growx,pushx,hidemode 3");
			panelIqReplayAudio.setVisible(false);

			panelIqReplayOptions = new JPanel(new BorderLayout(3, 0));
			panelIqReplayOptions.setBackground(Color.BLACK);
			checkBoxIqReplayBurstCapture = new JCheckBox(" Burst det");
			checkBoxIqReplayBurstCapture.setHorizontalTextPosition(SwingConstants.TRAILING);
			checkBoxIqReplayBurstCapture.setForeground(Color.WHITE);
			checkBoxIqReplayBurstCapture.setBackground(Color.BLACK);
			checkBoxIqReplayTxCustomFrequency = new JCheckBox(" Custom freq");
			checkBoxIqReplayTxCustomFrequency.setHorizontalTextPosition(SwingConstants.TRAILING);
			checkBoxIqReplayTxCustomFrequency.setForeground(Color.WHITE);
			checkBoxIqReplayTxCustomFrequency.setBackground(Color.BLACK);
			panelIqReplayOptions.add(checkBoxIqReplayBurstCapture, BorderLayout.WEST);
			panelIqReplayOptions.add(checkBoxIqReplayTxCustomFrequency, BorderLayout.EAST);
			tab1.add(panelIqReplayOptions, "cell 0 4,growx,pushx,hidemode 3");
			panelIqReplayOptions.setVisible(false);

			panelIqReplayTx = new JPanel(new BorderLayout(3, 0));
			panelIqReplayTx.setBackground(Color.BLACK);
			checkBoxIqReplayTx = new JCheckBox("TX");
			checkBoxIqReplayTx.setForeground(Color.WHITE);
			checkBoxIqReplayTx.setBackground(Color.BLACK);
			checkBoxIqReplayTx.setMargin(new java.awt.Insets(0, 0, 0, 0));
			textFieldIqReplayTxCenter = new JTextField("", 7);
			textFieldIqReplayTxCenter.setFont(new Font("Monospaced", Font.BOLD, 14));
			textFieldIqReplayTxCenter.setForeground(Color.WHITE);
			textFieldIqReplayTxCenter.setBackground(Color.BLACK);
			textFieldIqReplayTxCenter.setCaretColor(Color.WHITE);
			textFieldIqReplayTxCenter.setHorizontalAlignment(JTextField.RIGHT);
			sliderIqReplayTxPower = new JSlider(0, 47, 0);
			sliderIqReplayTxPower.setForeground(Color.WHITE);
			sliderIqReplayTxPower.setBackground(Color.BLACK);
			compactSlider(sliderIqReplayTxPower, new Dimension(40, 16));
			labelIqReplayTxPower = new JLabel("0");
			labelIqReplayTxPower.setForeground(Color.WHITE);
			labelIqReplayTxPower.setPreferredSize(new Dimension(18, 18));
			JPanel panelIqReplayTxPower = new JPanel(new BorderLayout(2, 0));
			panelIqReplayTxPower.setBackground(Color.BLACK);
			panelIqReplayTxPower.add(sliderIqReplayTxPower, BorderLayout.CENTER);
			panelIqReplayTxPower.add(labelIqReplayTxPower, BorderLayout.EAST);
			panelIqReplayTx.add(checkBoxIqReplayTx, BorderLayout.WEST);
			panelIqReplayTx.add(panelIqReplayTxPower, BorderLayout.CENTER);
			panelIqReplayTx.add(textFieldIqReplayTxCenter, BorderLayout.EAST);
			tab1.add(panelIqReplayTx, "cell 0 5,growx,pushx,hidemode 3");
			panelIqReplayTx.setVisible(false);
		}
		//tab2
		{
			btnReset = new JButton("RESET PARAMS");
			btnReset.setForeground(Color.black);
			btnReset.setBackground(Color.orange);
			//btnRecData.setBorderPainted(false);
			btnReset.setPreferredSize(new Dimension(100, 20));
			btnReset.setBorder(BorderFactory.createEmptyBorder());
			btnReset.addActionListener(e -> {
				// restore defaults as in original init
				SwingUtilities.invokeLater(() -> {
					hRF.getparamFreqRange().setValue("920-960");
					hRF.getFrequency().setValue(new FrequencyRange(920, 960));
					hRF.getFFTBinHz().setValue(50);
					hRF.getSamples().setValue(8192);
					hRF.getGain().setValue(52);
					// GainLNA/VGA will be recalculated by listeners when Gain is changed
					hRF.getAntennaLNA().setValue(false);
					hRF.getAntennaPowerEnable().setValue(false);
					hRF.getFreqShift().setValue(0);
					hRF.isPersistentDisplayVisible().setValue(false);
					hRF.getPersistentDisplayDecayRate().setValue(5);
					hRF.isChartsRealtimeVisible().setValue(false);
					hRF.isChartsPeaksVisible().setValue(true);
					hRF.isChartsAverageVisible().setValue(false);
					hRF.isChartsMaxHoldVisible().setValue(true);
					hRF.isPeakMarkerVisible().setValue(false);
					hRF.isHoldMarkerVisible().setValue(false);
					hRF.getMarkerCount().setValue(1);
					hRF.isSpurRemoval().setValue(false);
					hRF.getSpectrumLineThickness().setValue(new BigDecimal("1"));
					hRF.isSpectrumSpline().setValue(false);
					// default waterfall palette: start -110 dB, size 65 dB
					hRF.getSpectrumPaletteSize().setValue(65);
					hRF.getSpectrumPaletteStart().setValue(-110);
					hRF.getAmplitudeOffset().setValue(0);
					hRF.getPowerFluxCal().setValue(50);
					hRF.getAvgIterations().setValue(20);
					hRF.getAvgOffset().setValue(0);
					hRF.getWaterfallSpeed().setValue(4);
					hRF.isWaterfallVisible().setValue(true);
					hRF.isInfoBoxVisible().setValue(true);
					hRF.isDatestampVisible().setValue(true);
					hRF.getVideoArea().setValue("SPECTR");
					hRF.getVideoFormat().setValue("GIF");
					hRF.getVideoResolution().setValue(540);
					hRF.getVideoFrameRate().setValue(15);
					hRF.getLogDetail().setValue("SEC");
				});
			});
			tab2.add(btnReset, "cell 0 0,alignx left");

			checkBoxSpectrumSpline = new JCheckBox("Spline ");
			checkBoxSpectrumSpline.setHorizontalTextPosition(SwingConstants.TRAILING);
			checkBoxSpectrumSpline.setForeground(Color.WHITE);
			checkBoxSpectrumSpline.setBackground(Color.BLACK);
			tab2.add(checkBoxSpectrumSpline, "cell 0 1,alignx left");

			JLabel lblSpectrLineThickness = new JLabel("Lines Thickness ");
			lblSpectrLineThickness.setForeground(Color.WHITE);
			tab2.add(lblSpectrLineThickness, "cell 0 1,alignx right");
			
			spinnerLineThickness = new JSpinner();
			spinnerLineThickness.setModel(new SpinnerListModel(new String[] { "1", "1.5", "2", "3" }));
			spinnerLineThickness.setFont(new Font("Monospaced", Font.BOLD, 14));
			((ListEditor) spinnerLineThickness.getEditor()).getTextField().setHorizontalAlignment(JTextField.RIGHT);
			((ListEditor) spinnerLineThickness.getEditor()).getTextField().setColumns(3);
			tab2.add(spinnerLineThickness, "cell 0 1,alignx right");

			lblPeakFall = new JLabel("Peak Fallout Time ");
			lblPeakFall.setForeground(Color.WHITE);
			tab2.add(lblPeakFall, "cell 0 2,alignx right");
			
			spinnerPeakFallSpeed = new JSpinner();
			spinnerPeakFallSpeed.setModel(new SpinnerListModel(new String[] { "1", "5", "10", "20", "30", "60" }));
			spinnerPeakFallSpeed.setFont(new Font("Monospaced", Font.BOLD, 14));
			((ListEditor) spinnerPeakFallSpeed.getEditor()).getTextField().setHorizontalAlignment(JTextField.RIGHT);
			((ListEditor) spinnerPeakFallSpeed.getEditor()).getTextField().setColumns(2);
			tab2.add(spinnerPeakFallSpeed, "cell 0 2,alignx right");
			
			lblPeakFallTrs = new JLabel("Peak Fall Threshold ");
			lblPeakFallTrs.setForeground(Color.WHITE);
			tab2.add(lblPeakFallTrs, "cell 0 3,alignx right");
			
			spinnerPeakFallTrs = new JSpinner();
			spinnerPeakFallTrs.setModel(new SpinnerListModel(new String[] { "0", "2", "5", "10", "15" }));
			spinnerPeakFallTrs.setFont(new Font("Monospaced", Font.BOLD, 14));
			((ListEditor) spinnerPeakFallTrs.getEditor()).getTextField().setHorizontalAlignment(JTextField.RIGHT);
			((ListEditor) spinnerPeakFallTrs.getEditor()).getTextField().setColumns(2);
			tab2.add(spinnerPeakFallTrs, "cell 0 3,alignx right");
			
			lblPeakHoldTime = new JLabel("Peak Hold Time ");
			lblPeakHoldTime.setForeground(Color.WHITE);
			tab2.add(lblPeakHoldTime, "cell 0 4,alignx right");
			
			spinnerPeakHoldTime = new JSpinner();
			spinnerPeakHoldTime.setModel(new SpinnerListModel(new String[] { "0", "1", "2", "5", "10", "30", "60" }));
			spinnerPeakHoldTime.setFont(new Font("Monospaced", Font.BOLD, 14));
			((ListEditor) spinnerPeakHoldTime.getEditor()).getTextField().setHorizontalAlignment(JTextField.RIGHT);
			((ListEditor) spinnerPeakHoldTime.getEditor()).getTextField().setColumns(2);
			tab2.add(spinnerPeakHoldTime, "cell 0 4,alignx right");

			lblMarkerCount = new JLabel("Marker Count ");
			lblMarkerCount.setForeground(Color.WHITE);
			tab2.add(lblMarkerCount, "cell 0 5,alignx right");

			spinnerMarkerCount = new JSpinner();
			spinnerMarkerCount.setModel(new SpinnerListModel(new String[] { "1", "2", "3", "4", "5" }));
			spinnerMarkerCount.setFont(new Font("Monospaced", Font.BOLD, 14));
			((ListEditor) spinnerMarkerCount.getEditor()).getTextField().setHorizontalAlignment(JTextField.RIGHT);
			((ListEditor) spinnerMarkerCount.getEditor()).getTextField().setColumns(1);
			tab2.add(spinnerMarkerCount, "cell 0 5,alignx right");
			
			lblDecayRate = new JLabel("Persistent Decay Rate ");
			lblDecayRate.setForeground(Color.WHITE);
			tab2.add(lblDecayRate, "cell 0 6,alignx right");
			
			spinnerDecayRate = new JSpinner();
			spinnerDecayRate.setModel(new SpinnerListModel(new String[] { "1", "5", "10", "30", "60" }));
			spinnerDecayRate.setFont(new Font("Monospaced", Font.BOLD, 14));
			((ListEditor) spinnerDecayRate.getEditor()).getTextField().setHorizontalAlignment(JTextField.RIGHT);
			((ListEditor) spinnerDecayRate.getEditor()).getTextField().setColumns(2);
			tab2.add(spinnerDecayRate, "cell 0 6,alignx right");
			
			lblAvgIterations = new JLabel("Average Iterations ");
			lblAvgIterations.setForeground(Color.WHITE);
			tab2.add(lblAvgIterations, "cell 0 7,alignx right");
			
			spinnerAvgIterations = new JSpinner();
			spinnerAvgIterations.setModel(new SpinnerListModel(new String[] { "20", "50", "80" }));
			spinnerAvgIterations.setFont(new Font("Monospaced", Font.BOLD, 14));
			((ListEditor) spinnerAvgIterations.getEditor()).getTextField().setHorizontalAlignment(JTextField.RIGHT);
			((ListEditor) spinnerAvgIterations.getEditor()).getTextField().setColumns(2);
			tab2.add(spinnerAvgIterations, "cell 0 7,alignx right");
			
			lblAvgOffset = new JLabel("Avg Offset");
			lblAvgOffset.setForeground(Color.WHITE);
			tab2.add(lblAvgOffset, "cell 0 8");
			
			sliderAvgOffset = new JSlider();
			sliderAvgOffset.setForeground(Color.WHITE);
			sliderAvgOffset.setBackground(Color.BLACK);
			sliderAvgOffset.setFont(new Font("Monospaced", Font.BOLD, 14));
			sliderAvgOffset.setMinimum(-10);
			sliderAvgOffset.setMaximum(10);
			sliderAvgOffset.setValue(0);
			tab2.add(sliderAvgOffset, "cell 0 8,growx");
			
			lblAvgOffset2 = new JLabel();
			lblAvgOffset2.setForeground(Color.WHITE);
			tab2.add(lblAvgOffset2, "cell 0 8, alignx right");
			
			hackRFSettings.getAvgOffset().addListener((avgOff) -> lblAvgOffset2.setText(String.format("%d dB", avgOff)));
			/*
			JLabel lblNumberOfSamples = new JLabel("Samples ");
			lblNumberOfSamples.setForeground(Color.WHITE);
			tab2.add(lblNumberOfSamples, "cell 0 14,alignx right");

			spinner_numberOfSamples = new JSpinner();
			spinner_numberOfSamples.setModel(new SpinnerListModel(new String[] { "8192", "16384", "32768", "65536", "131072", "262144" }));
			spinner_numberOfSamples.setFont(new Font("Monospaced", Font.BOLD, 14));
			((ListEditor) spinner_numberOfSamples.getEditor()).getTextField().setHorizontalAlignment(JTextField.RIGHT);
			((ListEditor) spinner_numberOfSamples.getEditor()).getTextField().setColumns(6);
			tab2.add(spinner_numberOfSamples, "cell 0 14,alignx right");
			*/

			JLabel lblDisplayFrequencyAllocation = new JLabel("Frequency Allocation Bands");
			lblDisplayFrequencyAllocation.setForeground(Color.WHITE);
			tab2.add(lblDisplayFrequencyAllocation, "cell 0 9");
			
			FrequencyAllocations frequencyAllocations	= new FrequencyAllocations();
			Vector<FrequencyAllocationTable> freqAllocValues	= new Vector<>();
			freqAllocValues.addAll(frequencyAllocations.getTable().values());
			freqAllocValues.add(null);
			DefaultComboBoxModel<FrequencyAllocationTable> freqAllocModel	= new  DefaultComboBoxModel<>(freqAllocValues);
			comboBoxFrequencyAllocationBands = new JComboBox<FrequencyAllocationTable>(freqAllocModel);
			tab2.add(comboBoxFrequencyAllocationBands, "cell 0 10,growx");
			
		}
		//tab3 Waterfall/REC
		{
			chckbxDatestamp = new JCheckBox(" Date");
			chckbxDatestamp.setForeground(Color.WHITE);
			chckbxDatestamp.setBackground(Color.BLACK);
			tab3.add(chckbxDatestamp, "cell 0 0,alignx right");

			checkBoxInfoBoxEnabled = new JCheckBox(" InfoBox");
			checkBoxInfoBoxEnabled.setForeground(Color.WHITE);
			checkBoxInfoBoxEnabled.setBackground(Color.BLACK);
			tab3.add(checkBoxInfoBoxEnabled, "cell 0 0,alignx right");
			
			checkBoxWaterfallEnabled = new JCheckBox(" WFall");
			checkBoxWaterfallEnabled.setForeground(Color.WHITE);
			checkBoxWaterfallEnabled.setBackground(Color.BLACK);
			tab3.add(checkBoxWaterfallEnabled, "cell 0 0,alignx right");
		
			JLabel lblWaterfallPaletteStart = new JLabel("Palette Start");
			lblWaterfallPaletteStart.setForeground(Color.WHITE);
			tab3.add(lblWaterfallPaletteStart, "cell 0 2");
	
			slider_waterfallPaletteStart = new JSlider();
			slider_waterfallPaletteStart.setForeground(Color.WHITE);
			slider_waterfallPaletteStart.setBackground(Color.BLACK);
			slider_waterfallPaletteStart.setMinimum(-130); //palette offset
			slider_waterfallPaletteStart.setMaximum(-80);
			tab3.add(slider_waterfallPaletteStart, "cell 0 2,growx");
			
			JLabel lblWaterfallPaletteStart2 = new JLabel();
			lblWaterfallPaletteStart2.setForeground(Color.WHITE);
			tab3.add(lblWaterfallPaletteStart2, "cell 0 2, alignx right");
			
			hackRFSettings.getSpectrumPaletteStart().addListener((pstart) -> lblWaterfallPaletteStart2.setText(String.format("%d dB", pstart)));
	
			JLabel lblWaterfallPaletteLength = new JLabel("Palette Length");
			lblWaterfallPaletteLength.setForeground(Color.WHITE);
			tab3.add(lblWaterfallPaletteLength, "cell 0 4");
	
			slider_waterfallPaletteSize = new JSlider();
			slider_waterfallPaletteSize.setBackground(Color.BLACK);
			slider_waterfallPaletteSize.setForeground(Color.WHITE);
			slider_waterfallPaletteSize.setMinimum(10); //palette offset
			slider_waterfallPaletteSize.setMaximum(90);
			tab3.add(slider_waterfallPaletteSize, "cell 0 4,growx");
			
			JLabel lblWaterfallPaletteLength2 = new JLabel();
			lblWaterfallPaletteLength2.setForeground(Color.WHITE);
			tab3.add(lblWaterfallPaletteLength2, "cell 0 4, alignx right");
			
			hackRFSettings.getSpectrumPaletteSize().addListener((plen) -> lblWaterfallPaletteLength2.setText(String.format("%d dB", plen)));
			
			JLabel lblWaterfallSpeed = new JLabel("Speed");
			lblWaterfallSpeed.setForeground(Color.WHITE);
			tab3.add(lblWaterfallSpeed, "cell 0 6");
			
			slider_WaterfallSpeed = new JSlider();
			slider_WaterfallSpeed.setForeground(Color.WHITE);
			slider_WaterfallSpeed.setBackground(Color.BLACK);
			slider_WaterfallSpeed.setFont(new Font("Monospaced", Font.BOLD, 14));
			slider_WaterfallSpeed.setMinimum(1);
			slider_WaterfallSpeed.setMaximum(10);
			slider_WaterfallSpeed.setValue(4);
			tab3.add(slider_WaterfallSpeed, "cell 0 6,growx");	
			
			JLabel lblWaterfallSpeed2 = new JLabel();
			lblWaterfallSpeed2.setForeground(Color.WHITE);
			tab3.add(lblWaterfallSpeed2, "cell 0 6, alignx right");
			
			hackRFSettings.getWaterfallSpeed().addListener((speed) -> lblWaterfallSpeed2.setText(String.format("%d", speed)));
			
			JSeparator separator1 = new JSeparator();
			tab3.add(separator1,"cell 0 7,growx");

			JLabel lblREC = new JLabel("REC");
			lblREC.setForeground(Color.WHITE);
			tab3.add(lblREC, "growx,cell 0 8");
			
			JLabel lblLogDetail = new JLabel("STATS Log Interval ");
			lblLogDetail.setForeground(Color.WHITE);
			tab3.add(lblLogDetail, "cell 0 8,alignx right");
			
			spinnerLogDetail = new JSpinner();
			spinnerLogDetail.setModel(new SpinnerListModel(new String[] { "FRAC", "SEC", "MIN" }));
			spinnerLogDetail.setFont(new Font("Monospaced", Font.BOLD, 14));
			((ListEditor) spinnerLogDetail.getEditor()).getTextField().setHorizontalAlignment(JTextField.RIGHT);
			((ListEditor) spinnerLogDetail.getEditor()).getTextField().setColumns(4);
			tab3.add(spinnerLogDetail, "cell 0 8,alignx right");

			JLabel lblSpectrumRecordFrameRate = new JLabel("DATA REC FPS ");
			lblSpectrumRecordFrameRate.setForeground(Color.WHITE);
			tab3.add(lblSpectrumRecordFrameRate, "cell 0 9,alignx right");
			
			spinnerSpectrumRecordFrameRate = new JSpinner();
			spinnerSpectrumRecordFrameRate.setModel(new SpinnerListModel(new String[] { "FULL", "90", "60", "30", "15" }));
			spinnerSpectrumRecordFrameRate.setFont(new Font("Monospaced", Font.BOLD, 14));
			((ListEditor) spinnerSpectrumRecordFrameRate.getEditor()).getTextField().setHorizontalAlignment(JTextField.RIGHT);
			((ListEditor) spinnerSpectrumRecordFrameRate.getEditor()).getTextField().setColumns(4);
			tab3.add(spinnerSpectrumRecordFrameRate, "cell 0 9,alignx right");

			JSeparator separator2 = new JSeparator();
			tab3.add(separator2,"cell 0 10,growx");

			JLabel lblCALC = new JLabel("VIDEO REC");
			lblCALC.setForeground(Color.WHITE);
			tab3.add(lblCALC, "growx,cell 0 11");
			
			JLabel lblVideoArea = new JLabel("Area ");
			lblVideoArea.setForeground(Color.WHITE);
			tab3.add(lblVideoArea, "cell 0 11,alignx right");
			
			spinnerVideoArea = new JSpinner();
			spinnerVideoArea.setModel(new SpinnerListModel(new String[] { "SPECTR", "SPEC+WF", "FULLSCR" }));
			spinnerVideoArea.setFont(new Font("Monospaced", Font.BOLD, 14));
			((ListEditor) spinnerVideoArea.getEditor()).getTextField().setHorizontalAlignment(JTextField.RIGHT);
			((ListEditor) spinnerVideoArea.getEditor()).getTextField().setColumns(7);
			tab3.add(spinnerVideoArea, "cell 0 11,alignx right");

			JLabel lblVideoResolution = new JLabel("Resolution ");
			lblVideoResolution.setForeground(Color.WHITE);
			tab3.add(lblVideoResolution, "cell 0 12,alignx right");
			
			spinnerVideoResolution = new JSpinner();
			spinnerVideoResolution.setModel(new SpinnerListModel(new String[] { "360", "540", "720", "1080" }));
			spinnerVideoResolution.setFont(new Font("Monospaced", Font.BOLD, 14));
			((ListEditor) spinnerVideoResolution.getEditor()).getTextField().setHorizontalAlignment(JTextField.RIGHT);
			((ListEditor) spinnerVideoResolution.getEditor()).getTextField().setColumns(4);
			tab3.add(spinnerVideoResolution, "cell 0 12,alignx right");

			JLabel lblVideoFrameRate = new JLabel("FPS ");
			lblVideoFrameRate.setForeground(Color.WHITE);
			tab3.add(lblVideoFrameRate, "cell 0 13, split 4, alignx left");
			
			spinnerVideoFrameRate = new JSpinner();
			spinnerVideoFrameRate.setModel(new SpinnerListModel(new String[] { "1", "2", "5", "10", "15", "25", "30", "60" }));
			spinnerVideoFrameRate.setFont(new Font("Monospaced", Font.BOLD, 14));
			((ListEditor) spinnerVideoFrameRate.getEditor()).getTextField().setHorizontalAlignment(JTextField.RIGHT);
			((ListEditor) spinnerVideoFrameRate.getEditor()).getTextField().setColumns(2);
			tab3.add(spinnerVideoFrameRate, "cell 0 13");
			
			JLabel lblVideoFormat = new JLabel("Format ");
			lblVideoFormat.setForeground(Color.WHITE);
			tab3.add(lblVideoFormat, "cell 0 13, gapleft push");
			
			spinnerVideoFormat = new JSpinner();
			spinnerVideoFormat.setModel(new SpinnerListModel(new String[] { "GIF", "MP4" }));
			spinnerVideoFormat.setFont(new Font("Monospaced", Font.BOLD, 14));
			((ListEditor) spinnerVideoFormat.getEditor()).getTextField().setHorizontalAlignment(JTextField.RIGHT);
			((ListEditor) spinnerVideoFormat.getEditor()).getTextField().setColumns(3);
			tab3.add(spinnerVideoFormat, "cell 0 13, alignx right");

		}
		installContextHelpTexts(tabbedPane);
		compactSliders();
		bindViewToModel();
	}

	private void installContextHelpTexts(JTabbedPane tabbedPane) {
		setContextHelp("spectrum.help", btnContextHelp);
		setContextHelp("spectrum.tabs", tabbedPane);

		setContextHelp("frequency.start", frequencySelectorStart, frequencySelectorStartPrefix);
		setContextHelp("frequency.stop", frequencySelectorEnd, frequencySelectorEndPrefix);
		setContextHelp("frequency.panLeft", button_minus);
		setContextHelp("frequency.panRight", button_plus);
		setContextHelp("frequency.ranges", textFieldFreqRange);
		setContextHelp("frequency.presets", comboBoxFrequencyPresets);
		setContextHelp("frequency.savePreset", btnSavePreset);

		setContextHelp("capture.pause", btnPause);
		setContextHelp("capture.trigger", btnTrigger);
		setContextHelp("capture.replay", btnPlaySpectrum);
		setContextHelp("capture.video", btnRecVideo);
		setContextHelp("capture.stats", btnRecData);
		setContextHelp("capture.data", btnRecSpectrum);

		setContextHelp("sweep.gain", sliderGain, lblGain, lblGainValue);
		setContextHelp("sweep.lnaGain", sliderGainLNA, lblLnaGain);
		setContextHelp("sweep.vgaGain", sliderGainVGA, lblVgaGain);
		setContextHelp("sweep.rfAmp", chckbxAntennaLNA);
		setContextHelp("sweep.biasT", chckbxAntennaPower);
		setContextHelp("sweep.noDc", chckbxRemoveSpurs);
		setContextHelp("chart.realTime", chckbxShowRealtime);
		setContextHelp("chart.persist", checkBoxPersistentDisplay);
		setContextHelp("chart.average", chckbxShowAverage);
		setContextHelp("chart.peak", chckbxShowPeaks);
		setContextHelp("chart.maxHold", chckbxShowMaxHold);
		setContextHelp("marker.peak", chckbxShowPeakMarker);
		setContextHelp("marker.hold", chckbxShowHoldMarker);
		setContextHelp("freq.rbw", spinnerFFTBinHz);
		setContextHelp("freq.shift", spinner_FrequencyShift);
		setContextHelp("amplitude.offset", slider_AmplitudeOffset);
		setContextHelp("power.calibration", slider_PowerFluxCal);

		setContextHelp("iq.audio", panelIqReplayAudio, checkBoxIqReplayAudio);
		setContextHelp("iq.audioVolume", sliderIqReplayAudioVolume);
		setContextHelp("iq.audioMode", comboBoxIqReplayAudioMode);
		setContextHelp("iq.burstDetect", checkBoxIqReplayBurstCapture);
		setContextHelp("iq.customTxFrequency", checkBoxIqReplayTxCustomFrequency);
		setContextHelp("iq.tx", panelIqReplayTx, checkBoxIqReplayTx);
		setContextHelp("iq.txPower", sliderIqReplayTxPower, labelIqReplayTxPower);
		setContextHelp("iq.txCenter", textFieldIqReplayTxCenter);

		setContextHelp("params.reset", btnReset);
		setContextHelp("params.spline", checkBoxSpectrumSpline);
		setContextHelp("params.lineThickness", spinnerLineThickness);
		setContextHelp("params.peakFallTime", spinnerPeakFallSpeed, lblPeakFall);
		setContextHelp("params.peakFallThreshold", spinnerPeakFallTrs, lblPeakFallTrs);
		setContextHelp("params.peakHoldTime", spinnerPeakHoldTime, lblPeakHoldTime);
		setContextHelp("params.markerCount", spinnerMarkerCount, lblMarkerCount);
		setContextHelp("params.persistDecay", spinnerDecayRate, lblDecayRate);
		setContextHelp("params.avgIterations", spinnerAvgIterations, lblAvgIterations);
		setContextHelp("params.avgOffset", sliderAvgOffset, lblAvgOffset, lblAvgOffset2);
		setContextHelp("params.allocations", comboBoxFrequencyAllocationBands);

		setContextHelp("waterfall.date", chckbxDatestamp);
		setContextHelp("waterfall.infoBox", checkBoxInfoBoxEnabled);
		setContextHelp("waterfall.enable", checkBoxWaterfallEnabled);
		setContextHelp("waterfall.paletteStart", slider_waterfallPaletteStart);
		setContextHelp("waterfall.paletteLength", slider_waterfallPaletteSize);
		setContextHelp("waterfall.speed", slider_WaterfallSpeed);
		setContextHelp("record.statsInterval", spinnerLogDetail);
		setContextHelp("record.dataFps", spinnerSpectrumRecordFrameRate);
		setContextHelp("record.videoArea", spinnerVideoArea);
		setContextHelp("record.videoResolution", spinnerVideoResolution);
		setContextHelp("record.videoFps", spinnerVideoFrameRate);
		setContextHelp("record.videoFormat", spinnerVideoFormat);
	}

	private JButton createContextHelpButton() {
		JButton button = new JButton("HELP");
		Dimension size = new Dimension(50, 16);
		button.setPreferredSize(size);
		button.setMinimumSize(size);
		button.setMaximumSize(size);
		button.setMargin(new java.awt.Insets(0, 4, 0, 4));
		button.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
		button.setBackground(Color.BLACK);
		button.setForeground(Color.BLACK);
		button.setBorder(UIManager.getBorder("Button.border"));
		button.setFocusPainted(false);
		button.setFocusable(false);
		button.setToolTipText("Context help");
		button.addActionListener(e -> startContextHelpCursorPreview());
		return button;
	}

	private JLabel createFrequencySelectorPrefixLabel() {
		JLabel prefix = new JLabel("");
		prefix.setForeground(Color.WHITE);
		prefix.setFont(new Font(Font.MONOSPACED, Font.BOLD, 16));
		prefix.setHorizontalAlignment(JLabel.CENTER);
		prefix.setPreferredSize(new Dimension(0, 65));
		return prefix;
	}

	private JPanel createShiftedFrequencySelectorPreview(FrequencySelectorPanel selector, JLabel prefix) {
		JPanel panel = new JPanel(new BorderLayout(2, 0));
		panel.setBackground(Color.BLACK);
		panel.add(prefix, BorderLayout.WEST);
		panel.add(selector, BorderLayout.CENTER);
		return panel;
	}

	public static void setContextHelp(Component component, String key) {
		if (component instanceof JComponent) {
			((JComponent) component).putClientProperty(CONTEXT_HELP_PROPERTY, key);
		}
	}

	private void setContextHelp(String key, Component... components) {
		for (Component component : components) {
			setContextHelp(component, key);
		}
	}

	private void startContextHelpCursorPreview() {
		Window window = SwingUtilities.getWindowAncestor(this);
		if (!(window instanceof RootPaneContainer)) {
			return;
		}
		if (contextHelpPreviewGlassPane != null && contextHelpPreviewGlassPane.isVisible()) {
			stopContextHelpCursorPreview();
			return;
		}
		JComponent glassPane = (JComponent) ((RootPaneContainer) window).getGlassPane();
		contextHelpPreviewGlassPane = glassPane;
		contextHelpPreviousCursor = glassPane.getCursor();
		contextHelpPreviewMouseListener = new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				showContextHelp(e, true);
				e.consume();
			}

			@Override
			public void mouseMoved(MouseEvent e) {
				scheduleHoverContextHelp(e);
			}

			@Override
			public void mouseDragged(MouseEvent e) {
				scheduleHoverContextHelp(e);
			}

			@Override
			public void mouseExited(MouseEvent e) {
				contextHelpHoverComponent = null;
				stopContextHelpHoverTimer();
				hideContextHelpPopup();
			}
		};
		contextHelpPreviewKeyListener = new KeyAdapter() {
			@Override
			public void keyPressed(KeyEvent e) {
				if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
					stopContextHelpCursorPreview();
					e.consume();
				}
			}
		};
		glassPane.setCursor(getContextHelpCursor());
		glassPane.addMouseListener(contextHelpPreviewMouseListener);
		glassPane.addMouseMotionListener(contextHelpPreviewMouseListener);
		glassPane.addKeyListener(contextHelpPreviewKeyListener);
		glassPane.setFocusable(true);
		glassPane.setVisible(true);
		glassPane.requestFocusInWindow();
	}

	private void stopContextHelpCursorPreview() {
		stopContextHelpCursorPreview(true);
	}

	private void stopContextHelpCursorPreview(boolean hidePopup) {
		JComponent glassPane = contextHelpPreviewGlassPane;
		if (glassPane == null) {
			if (hidePopup) {
				hideContextHelpPopup();
			}
			return;
		}
		if (contextHelpPreviewMouseListener != null) {
			glassPane.removeMouseListener(contextHelpPreviewMouseListener);
			glassPane.removeMouseMotionListener(contextHelpPreviewMouseListener);
		}
		if (contextHelpPreviewKeyListener != null) {
			glassPane.removeKeyListener(contextHelpPreviewKeyListener);
		}
		stopContextHelpHoverTimer();
		glassPane.setCursor(contextHelpPreviousCursor == null
				? Cursor.getDefaultCursor()
				: contextHelpPreviousCursor);
		glassPane.setVisible(false);
		contextHelpPreviewGlassPane = null;
		contextHelpPreviousCursor = null;
		contextHelpPreviewMouseListener = null;
		contextHelpPreviewKeyListener = null;
		contextHelpHoverComponent = null;
		contextHelpHoverPoint = null;
		if (hidePopup) {
			hideContextHelpPopup();
		}
	}

	private void scheduleHoverContextHelp(MouseEvent event) {
		Component component = findContextHelpComponent(event);
		if (component == null) {
			contextHelpHoverComponent = null;
			stopContextHelpHoverTimer();
			hideContextHelpPopup();
			return;
		}
		Point point = event.getPoint();
		if (component == contextHelpHoverComponent && contextHelpHoverPoint != null
				&& contextHelpHoverPoint.distance(point) < 4d) {
			return;
		}
		contextHelpHoverComponent = component;
		contextHelpHoverPoint = point;
		stopContextHelpHoverTimer();
		contextHelpHoverTimer = new javax.swing.Timer(CONTEXT_HELP_HOVER_DELAY_MS,
				e -> showContextHelpForComponent(component, point, false));
		contextHelpHoverTimer.setRepeats(false);
		contextHelpHoverTimer.start();
	}

	private void stopContextHelpHoverTimer() {
		if (contextHelpHoverTimer != null) {
			contextHelpHoverTimer.stop();
			contextHelpHoverTimer = null;
		}
	}

	private void stopContextHelpAutoHideTimer() {
		if (contextHelpAutoHideTimer != null) {
			contextHelpAutoHideTimer.stop();
			contextHelpAutoHideTimer = null;
		}
	}

	private void showContextHelp(MouseEvent event, boolean deactivateAfterShow) {
		Component component = findContextHelpComponent(event);
		Point point = event.getPoint();
		if (component == null) {
			showContextHelpPopup("No context help is available for this part of the window.", point);
			startContextHelpAutoHideTimer();
		} else {
			showContextHelpForComponent(component, point, true);
		}
		if (deactivateAfterShow) {
			stopContextHelpCursorPreview(false);
		}
	}

	private void showContextHelpForComponent(Component component, Point glassPanePoint, boolean autoHide) {
		String text = getContextHelpText(component);
		if (text == null || text.trim().length() == 0) {
			return;
		}
		showContextHelpPopup(text, glassPanePoint);
		if (autoHide) {
			startContextHelpAutoHideTimer();
		}
	}

	private void startContextHelpAutoHideTimer() {
		stopContextHelpAutoHideTimer();
		contextHelpAutoHideTimer = new javax.swing.Timer(CONTEXT_HELP_AUTO_HIDE_MS, e -> hideContextHelpPopup());
		contextHelpAutoHideTimer.setRepeats(false);
		contextHelpAutoHideTimer.start();
	}

	private Component findContextHelpComponent(MouseEvent event) {
		if (contextHelpPreviewGlassPane == null) {
			return null;
		}
		Window window = SwingUtilities.getWindowAncestor(this);
		if (!(window instanceof RootPaneContainer)) {
			return null;
		}
		Container contentPane = ((RootPaneContainer) window).getContentPane();
		Point contentPoint = SwingUtilities.convertPoint(contextHelpPreviewGlassPane, event.getPoint(), contentPane);
		Component deepest = SwingUtilities.getDeepestComponentAt(contentPane, contentPoint.x, contentPoint.y);
		while (deepest != null) {
			if (getContextHelpText(deepest) != null) {
				return deepest;
			}
			deepest = deepest.getParent();
		}
		return null;
	}

	private String getContextHelpText(Component component) {
		if (!(component instanceof JComponent)) {
			return null;
		}
		Object key = ((JComponent) component).getClientProperty(CONTEXT_HELP_PROPERTY);
		if (key == null) {
			return null;
		}
		String text = ContextHelpLibrary.get(key.toString());
		return text == null ? key.toString() : text;
	}

	private void showContextHelpPopup(String text, Point glassPanePoint) {
		if (contextHelpPreviewGlassPane == null) {
			return;
		}
		hideContextHelpPopup();
		JLabel label = new JLabel(toContextHelpHtml(text));
		label.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
		label.setForeground(Color.BLACK);
		label.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(new Color(255, 255, 220));
		panel.setBorder(BorderFactory.createLineBorder(Color.BLACK));
		panel.add(label, BorderLayout.CENTER);
		Point screenPoint = new Point(glassPanePoint);
		SwingUtilities.convertPointToScreen(screenPoint, contextHelpPreviewGlassPane);
		Dimension popupSize = panel.getPreferredSize();
		java.awt.Rectangle screenBounds = contextHelpPreviewGlassPane.getGraphicsConfiguration().getBounds();
		java.awt.Insets screenInsets = Toolkit.getDefaultToolkit()
				.getScreenInsets(contextHelpPreviewGlassPane.getGraphicsConfiguration());
		int minX = screenBounds.x + screenInsets.left;
		int maxX = screenBounds.x + screenBounds.width - screenInsets.right;
		int minY = screenBounds.y + screenInsets.top;
		int maxY = screenBounds.y + screenBounds.height - screenInsets.bottom;
		int popupX = screenPoint.x + 14;
		if (popupX + popupSize.width > maxX) {
			popupX = screenPoint.x - popupSize.width - 14;
		}
		popupX = Math.max(minX, Math.min(maxX - popupSize.width, popupX));
		int popupY = screenPoint.y + 18;
		if (popupY + popupSize.height > maxY) {
			popupY = screenPoint.y - popupSize.height - 18;
		}
		popupY = Math.max(minY, Math.min(maxY - popupSize.height, popupY));
		contextHelpPopup = PopupFactory.getSharedInstance().getPopup(contextHelpPreviewGlassPane, panel,
				popupX, popupY);
		contextHelpPopup.show();
	}

	private String toContextHelpHtml(String text) {
		String[] lines = text.split("\\r?\\n");
		StringBuilder html = new StringBuilder("<html><div style='width: 280px;'>");
		boolean inList = false;
		for (String line : lines) {
			String trimmed = line.trim();
			if (trimmed.startsWith("- ")) {
				if (!inList) {
					html.append("<ul style='margin-left: 14px;'>");
					inList = true;
				}
				html.append("<li>").append(escapeContextHelpHtml(trimmed.substring(2).trim())).append("</li>");
			} else {
				if (inList) {
					html.append("</ul>");
					inList = false;
				}
				if (trimmed.length() == 0) {
					html.append("<br>");
				} else {
					html.append(escapeContextHelpHtml(trimmed)).append("<br>");
				}
			}
		}
		if (inList) {
			html.append("</ul>");
		}
		html.append("</div></html>");
		return html.toString();
	}

	private String escapeContextHelpHtml(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private void hideContextHelpPopup() {
		stopContextHelpAutoHideTimer();
		if (contextHelpPopup != null) {
			contextHelpPopup.hide();
			contextHelpPopup = null;
		}
	}

	private Cursor getContextHelpCursor() {
		if (contextHelpCursor == null) {
			contextHelpCursor = createContextHelpCursor();
		}
		return contextHelpCursor;
	}

	private Cursor createContextHelpCursor() {
		Toolkit toolkit = Toolkit.getDefaultToolkit();
		Dimension bestSize = toolkit.getBestCursorSize(32, 32);
		if (bestSize.width <= 0 || bestSize.height <= 0) {
			return Cursor.getDefaultCursor();
		}
		BufferedImage image = new BufferedImage(bestSize.width, bestSize.height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		try {
			int[] arrowX = { 0, 0, 5, 8, 11, 8, 14 };
			int[] arrowY = { 0, 20, 14, 23, 22, 13, 13 };
			g.setColor(Color.WHITE);
			g.fillPolygon(arrowX, arrowY, arrowX.length);
			g.setColor(Color.BLACK);
			g.drawPolygon(arrowX, arrowY, arrowX.length);
			g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
			g.setColor(Color.BLACK);
			g.drawString("?", 16, 14);
			g.drawString("?", 14, 14);
			g.drawString("?", 15, 15);
			g.drawString("?", 15, 13);
			g.setColor(Color.WHITE);
			g.drawString("?", 15, 14);
		} finally {
			g.dispose();
		}
		return toolkit.createCustomCursor(image, new Point(0, 0), "Context help");
	}

	private int getFrequencyShiftMHz() {
		return hRF.getFreqShift().getValue();
	}

	private int toDisplayFrequencyMHz(int hardwareMHz) {
		return hardwareMHz + getFrequencyShiftMHz();
	}

	private int toHardwareFrequencyMHz(int displayMHz) {
		return displayMHz - getFrequencyShiftMHz();
	}

	private int clampHardwareFrequencyMHz(int frequencyMHz) {
		return Math.max(0, Math.min(7250, frequencyMHz));
	}

	private int getSelectorDisplayFrequency(FrequencySelectorPanel selector, JLabel prefix) {
		int prefixValue = 0;
		if (prefix != null && prefix.isVisible() && prefix.getText() != null && prefix.getText().trim().length() > 0) {
			try {
				prefixValue = Integer.parseInt(prefix.getText().trim());
			} catch (NumberFormatException e) {
				prefixValue = 0;
			}
		}
		return prefixValue * 10000 + selector.getValue();
	}

	private void setFrequencySelectorDisplayValue(FrequencySelectorPanel selector, JLabel prefix, int displayMHz) {
		int safeDisplayMHz = Math.max(0, displayMHz);
		int prefixValue = safeDisplayMHz / 10000;
		int selectorValue = safeDisplayMHz % 10000;
		if (prefixValue > 0) {
			prefix.setText(Integer.toString(prefixValue));
			prefix.setPreferredSize(new Dimension(Math.max(10, prefix.getFontMetrics(prefix.getFont())
					.stringWidth(prefix.getText()) + 2), selector.getPreferredSize().height));
			prefix.setVisible(true);
			selector.setMaximumValue(9999);
		} else {
			prefix.setText("");
			prefix.setPreferredSize(new Dimension(0, selector.getPreferredSize().height));
			prefix.setVisible(false);
			selector.setMaximumValue(7250);
		}
		if (selector.getValue() != selectorValue) {
			selector.setValue(selectorValue);
		}
	}

	private String convertFrequencyRangeText(String ranges, int deltaMHz, boolean clampToHardwareRange) {
		if (ranges == null || ranges.trim().length() == 0) {
			return ranges;
		}
		StringBuilder result = new StringBuilder();
		String[] intervals = ranges.split(",");
		for (int i = 0; i < intervals.length; i++) {
			String interval = intervals[i].trim();
			Matcher matcher = FREQUENCY_RANGE_INTERVAL.matcher(interval);
			if (!matcher.matches()) {
				return ranges;
			}
			try {
				int start = Integer.parseInt(matcher.group(1)) + deltaMHz;
				int stop = Integer.parseInt(matcher.group(2)) + deltaMHz;
				if (clampToHardwareRange) {
					int low = clampHardwareFrequencyMHz(Math.min(start, stop));
					int high = clampHardwareFrequencyMHz(Math.max(start, stop));
					if (low >= high) {
						if (low >= 7250) {
							low = 7249;
							high = 7250;
						} else {
							high = Math.min(7250, low + 1);
						}
					}
					start = low;
					stop = high;
				}
				if (i > 0) {
					result.append(",");
				}
				result.append(start).append("-").append(stop);
			} catch (NumberFormatException e) {
				return ranges;
			}
		}
		return result.toString();
	}

	private String hardwareRangesToDisplayRanges(String hardwareRanges) {
		return convertFrequencyRangeText(hardwareRanges, getFrequencyShiftMHz(), false);
	}

	private String displayRangesToHardwareRanges(String displayRanges) {
		return convertFrequencyRangeText(displayRanges, -getFrequencyShiftMHz(), true);
	}

	private FrequencyRange getDisplayFrequencyBounds(String ranges) {
		if (ranges == null || ranges.trim().length() == 0) {
			return null;
		}
		int minMHz = Integer.MAX_VALUE;
		int maxMHz = Integer.MIN_VALUE;
		String[] intervals = ranges.split(",");
		for (String interval : intervals) {
			Matcher matcher = FREQUENCY_RANGE_INTERVAL.matcher(interval.trim());
			if (!matcher.matches()) {
				return null;
			}
			try {
				int start = Integer.parseInt(matcher.group(1));
				int stop = Integer.parseInt(matcher.group(2));
				minMHz = Math.min(minMHz, Math.min(start, stop));
				maxMHz = Math.max(maxMHz, Math.max(start, stop));
			} catch (NumberFormatException e) {
				return null;
			}
		}
		if (minMHz == Integer.MAX_VALUE || maxMHz == Integer.MIN_VALUE) {
			return null;
		}
		return new FrequencyRange(minMHz, maxMHz);
	}

	private boolean updateReplayFrequencyControlsFromDisplayRange() {
		String displayRanges = hRF.getDisplayFreqRange().getValue();
		FrequencyRange displayBounds = getDisplayFrequencyBounds(displayRanges);
		if (displayBounds == null) {
			return false;
		}
		setFrequencySelectorDisplayValue(frequencySelectorStart, frequencySelectorStartPrefix,
				displayBounds.getStartMHz());
		setFrequencySelectorDisplayValue(frequencySelectorEnd, frequencySelectorEndPrefix,
				displayBounds.getEndMHz());
		if (!displayRanges.equals(textFieldFreqRange.getText())) {
			textFieldFreqRange.setText(displayRanges);
		}
		return true;
	}

	private void updateShiftedFrequencyControlsFromModel() {
		if (frequencySelectorStart == null || frequencySelectorEnd == null || textFieldFreqRange == null) {
			return;
		}
		updatingShiftedFrequencyControls = true;
		try {
			if (hRF.isPlayingSpectrum().getValue() && updateReplayFrequencyControlsFromDisplayRange()) {
				return;
			}
			FrequencyRange hardwareRange = hRF.getFrequency().getValue();
			setFrequencySelectorDisplayValue(frequencySelectorStart, frequencySelectorStartPrefix,
					toDisplayFrequencyMHz(hardwareRange.getStartMHz()));
			setFrequencySelectorDisplayValue(frequencySelectorEnd, frequencySelectorEndPrefix,
					toDisplayFrequencyMHz(hardwareRange.getEndMHz()));
			String displayRanges = hardwareRangesToDisplayRanges(hRF.getparamFreqRange().getValue());
			if (!displayRanges.equals(textFieldFreqRange.getText())) {
				textFieldFreqRange.setText(displayRanges);
			}
		} finally {
			updatingShiftedFrequencyControls = false;
		}
	}

	private void applyShiftedSelectorValuesToModel(boolean changedStart) {
		if (updatingShiftedFrequencyControls) {
			return;
		}
		int displayStart = getSelectorDisplayFrequency(frequencySelectorStart, frequencySelectorStartPrefix);
		int displayEnd = getSelectorDisplayFrequency(frequencySelectorEnd, frequencySelectorEndPrefix);
		if (displayStart >= displayEnd) {
			if (changedStart) {
				displayEnd = displayStart + 1;
			} else {
				displayStart = Math.max(0, displayEnd - 1);
			}
		}
		int hardwareStart = clampHardwareFrequencyMHz(toHardwareFrequencyMHz(displayStart));
		int hardwareEnd = clampHardwareFrequencyMHz(toHardwareFrequencyMHz(displayEnd));
		if (hardwareStart >= hardwareEnd) {
			if (changedStart) {
				hardwareEnd = Math.min(7250, hardwareStart + 1);
			} else {
				hardwareStart = Math.max(0, hardwareEnd - 1);
			}
		}
		FrequencyRange range = new FrequencyRange(hardwareStart, hardwareEnd);
		hRF.getFrequency().setValue(range);
		hRF.getparamFreqRange().setValue(hardwareStart + "-" + hardwareEnd);
	}

	private void applyHardwareFrequencyRange(int startMHz, int stopMHz) {
		int hardwareStart = clampHardwareFrequencyMHz(Math.min(startMHz, stopMHz));
		int hardwareEnd = clampHardwareFrequencyMHz(Math.max(startMHz, stopMHz));
		if (hardwareStart >= hardwareEnd) {
			hardwareEnd = Math.min(7250, hardwareStart + 1);
			if (hardwareStart >= hardwareEnd) {
				hardwareStart = Math.max(0, hardwareEnd - 1);
			}
		}
		hRF.getFrequency().setValue(new FrequencyRange(hardwareStart, hardwareEnd));
		hRF.getparamFreqRange().setValue(hardwareStart + "-" + hardwareEnd);
	}

	private void applyDisplayRangeTextToModel() {
		if (updatingShiftedFrequencyControls) {
			return;
		}
		String hardwareRanges = displayRangesToHardwareRanges(textFieldFreqRange.getText());
		Ranges range = new Ranges(hardwareRanges);
		hRF.getparamFreqRange().setValue(hardwareRanges);
		hRF.getFrequency().setValue(new FrequencyRange(clampHardwareFrequencyMHz(range.getFreqStart()),
				clampHardwareFrequencyMHz(range.getFreqStop())));
	}

	private void compactSliders() {
		Dimension compact = new Dimension(120, 16);
		compactSlider(sliderGain, compact);
		compactSlider(sliderGainLNA, compact);
		compactSlider(sliderGainVGA, compact);
		compactSlider(slider_AmplitudeOffset, compact);
		compactSlider(slider_PowerFluxCal, compact);
		compactSlider(sliderAvgOffset, compact);
		compactSlider(slider_waterfallPaletteStart, compact);
		compactSlider(slider_waterfallPaletteSize, compact);
		compactSlider(slider_WaterfallSpeed, compact);
	}

	private void compactSlider(JSlider slider, Dimension size) {
		if (slider == null)
			return;
		slider.setPreferredSize(size);
		slider.setMinimumSize(new Dimension(40, size.height));
		slider.setMaximumSize(new Dimension(Integer.MAX_VALUE, size.height));
		slider.setBorder(BorderFactory.createEmptyBorder());
		slider.setFocusable(false);
	}

	private void bindViewToModel() {
		spinnerFFTBinHz.addChangeListener(e -> applyRbwSpinnerValue());
		hRF.getFFTBinHz().addListener(value -> SwingUtilities.invokeLater(this::updateRbwSpinnerValue));
		hRF.getIqReplayRbwHz().addListener(value -> SwingUtilities.invokeLater(this::updateRbwSpinnerValue));
		hRF.getReplayType().addListener(value -> SwingUtilities.invokeLater(this::updateRbwSpinnerValue));
		updateRbwSpinnerValue();
		new MVCController(sliderGain, hRF.getGain());
		//new MVCController(spinner_numberOfSamples, hRF.getSamples(), val -> Integer.parseInt(val.toString()), val -> val.toString());
		new MVCController(spinner_FrequencyShift, hRF.getFreqShift(), val -> Integer.parseInt(val.toString()), val -> val.toString());
		new MVCController(chckbxAntennaPower, hRF.getAntennaPowerEnable());
		new MVCController(chckbxAntennaLNA, hRF.getAntennaLNA());
		new MVCController(slider_waterfallPaletteStart, hRF.getSpectrumPaletteStart());
		new MVCController(slider_waterfallPaletteSize, hRF.getSpectrumPaletteSize());
		new MVCController(slider_AmplitudeOffset, hRF.getAmplitudeOffset());
		textFieldFreqRange.addActionListener(e -> {
			try {
				applyDisplayRangeTextToModel();
			} catch (Exception ex) {
				ex.printStackTrace();
				updateShiftedFrequencyControlsFromModel();
			}
		});
		frequencySelectorStart.addPropertyChangeListener("value",
				event -> applyShiftedSelectorValuesToModel(true));
		frequencySelectorEnd.addPropertyChangeListener("value",
				event -> applyShiftedSelectorValuesToModel(false));
		hRF.getFrequency().addListener(value -> SwingUtilities.invokeLater(this::updateShiftedFrequencyControlsFromModel));
		hRF.getparamFreqRange().addListener(value -> SwingUtilities.invokeLater(this::updateShiftedFrequencyControlsFromModel));
		hRF.getFreqShift().addListener(value -> SwingUtilities.invokeLater(this::updateShiftedFrequencyControlsFromModel));
		hRF.getDisplayFreqRange().addListener((ranges) -> SwingUtilities.invokeLater(() -> {
			if (hRF.isPlayingSpectrum().getValue()) {
				updateShiftedFrequencyControlsFromModel();
			}
		}));
		new MVCController(slider_WaterfallSpeed, hRF.getWaterfallSpeed());
		updateShiftedFrequencyControlsFromModel();
		new MVCController(chckbxShowRealtime, hRF.isChartsRealtimeVisible());
		new MVCController(chckbxShowAverage, hRF.isChartsAverageVisible());
		new MVCController(chckbxShowPeaks, hRF.isChartsPeaksVisible());
		new MVCController(chckbxShowMaxHold, hRF.isChartsMaxHoldVisible());
		//new MVCController(chckbxFilterSpectrum, hRF.isFilterSpectrum());
		new MVCController(chckbxRemoveSpurs, hRF.isSpurRemoval());
		
		new MVCController((valueChangedCall) -> btnPause.addActionListener((event) -> valueChangedCall.accept(!hRF.isCapturingPaused().getValue())), 
				isCapt -> btnPause.setText(!isCapt ? "PAUSE"  : "►"), 
				hRF.isCapturingPaused());
		
		new MVCController((valueChangedCall) -> btnRecVideo.addActionListener((event) -> valueChangedCall.accept(!hRF.isRecordedVideo().getValue())), 
				isRec -> btnRecVideo.setText(!isRec ? "VIDEO"  : "■"), 
				hRF.isRecordedVideo());
		
		new MVCController((valueChangedCall) -> btnRecData.addActionListener((event) -> valueChangedCall.accept(!hRF.isRecordedData().getValue())), 
				isRecData -> btnRecData.setText(!isRecData ? "STATS"  : "■"), 
				hRF.isRecordedData());

		new MVCController((valueChangedCall) -> btnRecSpectrum.addActionListener((event) -> valueChangedCall.accept(!hRF.isRecordedSpectrum().getValue())),
				isRecSpectrum -> btnRecSpectrum.setText(!isRecSpectrum ? "DATA" : "■"),
				hRF.isRecordedSpectrum());

		new MVCController((valueChangedCall) -> btnPlaySpectrum.addActionListener((event) -> valueChangedCall.accept(!hRF.isPlayingSpectrum().getValue())),
				isPlayingSpectrum -> btnPlaySpectrum.setText(!isPlayingSpectrum ? "REPLAY" : "■"),
				hRF.isPlayingSpectrum());
	
		btnTrigger.addActionListener((event) -> hRF.showTriggerDialog());

		new MVCController(spinnerPeakFallSpeed, hRF.getPeakFallRate(), val -> Integer.parseInt(val.toString()), val -> val.toString());
		new MVCController(spinnerPeakFallTrs, hRF.getPeakFallTrs(), val -> Integer.parseInt(val.toString()), val -> val.toString());
		new MVCController(spinnerPeakHoldTime, hRF.getPeakHoldTime(), val -> Integer.parseInt(val.toString()), val -> val.toString());
		new MVCController(spinnerMarkerCount, hRF.getMarkerCount(), val -> Integer.parseInt(val.toString()), val -> val.toString());
		hRF.isChartsPeaksVisible().addListener((enabled) -> {
			SwingUtilities.invokeLater(()->{
				spinnerPeakFallSpeed.setEnabled(enabled);
				spinnerPeakFallSpeed.setVisible(enabled);
				lblPeakFall.setVisible(enabled);
				spinnerPeakFallTrs.setEnabled(enabled);
				spinnerPeakFallTrs.setVisible(enabled);
				lblPeakFallTrs.setVisible(enabled);
				spinnerPeakHoldTime.setEnabled(enabled);
				spinnerPeakHoldTime.setVisible(enabled);
				lblPeakHoldTime.setVisible(enabled);
				btnRecData.setVisible(enabled);
				chckbxShowPeakMarker.setVisible(enabled);
				updateMarkerCountVisibility();
			});
		});
		hRF.isChartsPeaksVisible().callObservers();
		
		new MVCController(spinnerAvgIterations, hRF.getAvgIterations(), val -> Integer.parseInt(val.toString()), val -> val.toString());
		new MVCController(sliderAvgOffset, hRF.getAvgOffset());
		new MVCController(slider_PowerFluxCal, hRF.getPowerFluxCal());
		
		hRF.isChartsAverageVisible().addListener((enabled) -> {
			SwingUtilities.invokeLater(()->{
				spinnerAvgIterations.setEnabled(enabled);
				spinnerAvgIterations.setVisible(enabled);
				lblAvgIterations.setVisible(enabled);
				sliderAvgOffset.setEnabled(enabled);
				sliderAvgOffset.setVisible(enabled);
				lblAvgOffset.setVisible(enabled);
				lblAvgOffset2.setVisible(enabled);
			});
		});
		hRF.isChartsAverageVisible().callObservers();
	
		new MVCController(comboBoxFrequencyAllocationBands, hRF.getFrequencyAllocationTable());
		
		sliderGainLNA.setModel(new DefaultBoundedRangeModel(hRF.getGainLNA().getValue(), 0, hRF.getGainLNA().getMin(), hRF.getGainLNA().getMax()));
		sliderGainVGA.setModel(new DefaultBoundedRangeModel(hRF.getGainVGA().getValue(), 0, hRF.getGainVGA().getMin(), hRF.getGainVGA().getMax()));
		
		sliderGainLNA.setSnapToTicks(true);
		sliderGainLNA.setMinorTickSpacing(hRF.getGainLNA().getStep());
		
		sliderGainVGA.setSnapToTicks(true);
		sliderGainVGA.setMinorTickSpacing(hRF.getGainVGA().getStep());
		
		new MVCController(sliderGainLNA, hRF.getGainLNA());
		new MVCController(sliderGainVGA, hRF.getGainVGA());

		//new MVCController(comboBoxLineThickness, hRF.getSpectrumLineThickness());
		new MVCController(spinnerLineThickness, hRF.getSpectrumLineThickness(), val -> new BigDecimal (val.toString()), val -> val.toString());
		new MVCController(checkBoxSpectrumSpline, hRF.isSpectrumSpline());
		
		new MVCController(checkBoxPersistentDisplay, hRF.isPersistentDisplayVisible());
		
		new MVCController(spinnerLogDetail, hRF.getLogDetail(), val -> new String (val.toString()), val -> val.toString());
		new MVCController(spinnerVideoArea, hRF.getVideoArea(), val -> new String (val.toString()), val -> val.toString());
		new MVCController(spinnerVideoFormat, hRF.getVideoFormat(), val -> new String (val.toString()), val -> val.toString());
		new MVCController(spinnerVideoResolution, hRF.getVideoResolution(), val -> Integer.parseInt(val.toString()), val -> val.toString());
		new MVCController(spinnerVideoFrameRate, hRF.getVideoFrameRate(), val -> Integer.parseInt(val.toString()), val -> val.toString());
		new MVCController(spinnerSpectrumRecordFrameRate, hRF.getSpectrumRecordFrameRate(), val -> new String (val.toString()), val -> val.toString());
		new MVCController(checkBoxIqReplayAudio, hRF.isIqReplayAudioEnabled());
		new MVCController(sliderIqReplayAudioVolume, hRF.getIqReplayAudioVolume());
		new MVCController(comboBoxIqReplayAudioMode, hRF.getIqReplayAudioMode());
		new MVCController(checkBoxIqReplayBurstCapture, hRF.isIqReplayBurstCaptureEnabled());
		new MVCController(checkBoxIqReplayTxCustomFrequency, hRF.isIqReplayTxCustomFrequencyEnabled());
		new MVCController(checkBoxIqReplayTx, hRF.isIqReplayTxEnabled());
		new MVCController(textFieldIqReplayTxCenter, hRF.getIqReplayTxCenter());
		new MVCController(sliderIqReplayTxPower, hRF.getIqReplayTxPower());
		hRF.getIqReplayTxPower().addListener((power) -> SwingUtilities.invokeLater(() ->
				labelIqReplayTxPower.setText(Integer.toString(power))));
		hRF.getIqReplayTxPower().callObservers();
		
		
		new MVCController(chckbxDatestamp, hRF.isDatestampVisible());
		
		new MVCController(chckbxShowPeakMarker, hRF.isPeakMarkerVisible());
		new MVCController(chckbxShowHoldMarker, hRF.isHoldMarkerVisible());
		hRF.isChartsMaxHoldVisible().addListener((enabled) -> {
			SwingUtilities.invokeLater(()->{
				chckbxShowHoldMarker.setVisible(enabled);
				updateMarkerCountVisibility();
			});
		});
		hRF.isChartsMaxHoldVisible().callObservers();
		
		new MVCController(checkBoxWaterfallEnabled, hRF.isWaterfallVisible());
		new MVCController(checkBoxInfoBoxEnabled, hRF.isInfoBoxVisible());
		
		//new MVCController(checkBoxDebugDisplay, hRF.isDebugDisplay());
		
		// new MVCController(comboBoxDecayRate, hRF.getPersistentDisplayDecayRate());		
		new MVCController(spinnerDecayRate, hRF.getPersistentDisplayDecayRate(), val -> Integer.parseInt(val.toString()), val -> val.toString());
		hRF.isPersistentDisplayVisible().addListener((visible) -> {
			SwingUtilities.invokeLater(()->{
				spinnerDecayRate.setVisible(visible);
				lblDecayRate.setVisible(visible);
			});
		});
		
		hRF.isPersistentDisplayVisible().callObservers();
		
		hRF.registerListener(new HackRFSettings.HackRFEventAdapter()
		{
			@Override public void captureStateChanged(boolean isCapturing)
			{
//				btnPause.setText(isCapturing ? "Pause"  : "Resume");
			}
			@Override public void hardwareStatusChanged(boolean hardwareSendingData)
			{
			}
		});;

		hRF.isPlayingSpectrum().addListener((playing) -> SwingUtilities.invokeLater(this::updateReplayControlState));
		hRF.getReplayType().addListener((type) -> SwingUtilities.invokeLater(this::updateReplayControlState));
		hRF.isRecordedSpectrum().addListener((recording) -> SwingUtilities.invokeLater(this::updateReplayControlState));
		updateReplayControlState();
		
	}

	private void updateReplayControlState() {
		boolean playingSpectrum = hRF.isPlayingSpectrum().getValue();
		setLiveControlsEnabled(!playingSpectrum);
		String replayType = hRF.getReplayType().getValue();
		boolean iqReplay = playingSpectrum && ("WAV".equals(replayType) || "RAW".equals(replayType));
		lblGain.setVisible(!iqReplay);
		lblGainValue.setVisible(!iqReplay);
		sliderGain.setVisible(!iqReplay);
		lblLnaGain.setVisible(!iqReplay);
		sliderGainLNA.setVisible(!iqReplay);
		lblVgaGain.setVisible(!iqReplay);
		sliderGainVGA.setVisible(!iqReplay);
		chckbxAntennaLNA.setVisible(true);
		chckbxAntennaPower.setVisible(true);
		chckbxRemoveSpurs.setVisible(true);
		chckbxAntennaLNA.setEnabled(iqReplay || !playingSpectrum);
		chckbxAntennaPower.setEnabled(!iqReplay && !playingSpectrum);
		chckbxRemoveSpurs.setEnabled(true);
		panelIqReplayAudio.setVisible(iqReplay);
		panelIqReplayOptions.setVisible(iqReplay);
		panelIqReplayTx.setVisible(iqReplay);
		updateShiftedFrequencyControlsFromModel();
		revalidate();
		repaint();
		if (playingSpectrum) {
			if ("WAV".equals(replayType) || "RAW".equals(replayType)) {
				setIqReplayControlsEnabled(true);
			}
		}
		btnPlaySpectrum.setEnabled(playingSpectrum || !hRF.isRecordedSpectrum().getValue());
	}

	private void applyRbwSpinnerValue() {
		if (updatingRbwSpinner) {
			return;
		}
		double rbwKhz;
		try {
			rbwKhz = Double.parseDouble(spinnerFFTBinHz.getValue().toString().trim());
		} catch (NumberFormatException e) {
			updateRbwSpinnerValue();
			return;
		}
		if (isIqReplay()) {
			hRF.getIqReplayRbwHz().setValue(Math.max(20, (int) Math.round(rbwKhz * 1000d)));
		} else {
			hRF.getFFTBinHz().setValue(Math.max(1, (int) Math.round(rbwKhz)));
		}
	}

	private void updateRbwSpinnerValue() {
		if (spinnerFFTBinHz == null) {
			return;
		}
		double rbwKhz = isIqReplay() ? hRF.getIqReplayRbwHz().getValue() / 1000d
				: hRF.getFFTBinHz().getValue();
		String value = formatRbwKhz(rbwKhz);
		if (!spinnerModelFFTBinHz.getList().contains(value)) {
			value = spinnerModelFFTBinHz.getList().get(spinnerModelFFTBinHz.getList().size() - 1).toString();
			for (Object candidate : spinnerModelFFTBinHz.getList()) {
				if (Double.parseDouble(candidate.toString()) >= rbwKhz) {
					value = candidate.toString();
					break;
				}
			}
		}
		updatingRbwSpinner = true;
		try {
			spinnerFFTBinHz.setValue(value);
		} finally {
			updatingRbwSpinner = false;
		}
	}

	private boolean isIqReplay() {
		String replayType = hRF.getReplayType().getValue();
		return "WAV".equals(replayType) || "RAW".equals(replayType);
	}

	private String formatRbwKhz(double rbwKhz) {
		if (rbwKhz >= 1d) {
			return Integer.toString((int) Math.round(rbwKhz));
		}
		return BigDecimal.valueOf(rbwKhz).stripTrailingZeros().toPlainString();
	}

	private void setIqReplayControlsEnabled(boolean enabled) {
		Component[] iqReplayControls = {
				spinnerFFTBinHz,
				slider_AmplitudeOffset,
				slider_PowerFluxCal,
				spinnerAvgIterations,
				sliderAvgOffset,
				chckbxFilterSpectrum,
				checkBoxIqReplayAudio,
				sliderIqReplayAudioVolume,
				comboBoxIqReplayAudioMode,
				checkBoxIqReplayBurstCapture,
				checkBoxIqReplayTxCustomFrequency,
				checkBoxIqReplayTx,
				textFieldIqReplayTxCenter,
				sliderIqReplayTxPower
		};
		for (Component component : iqReplayControls) {
			setComponentTreeEnabled(component, enabled);
		}
	}

	private void setLiveControlsEnabled(boolean enabled) {
		Component[] liveOnlyControls = {
				frequencySelectorStart,
				frequencySelectorEnd,
				button_minus,
				button_plus,
				textFieldFreqRange,
				comboBoxFrequencyPresets,
				btnSavePreset,
				btnReset,
				spinnerFFTBinHz,
				spinner_FrequencyShift,
				sliderGain,
				sliderGainLNA,
				sliderGainVGA,
				chckbxAntennaLNA,
				chckbxAntennaPower,
				slider_AmplitudeOffset,
				slider_PowerFluxCal,
				spinnerAvgIterations,
				spinnerSpectrumRecordFrameRate,
				btnRecSpectrum
		};
		for (Component component : liveOnlyControls) {
			setComponentTreeEnabled(component, enabled);
		}
	}

	private void setComponentTreeEnabled(Component component, boolean enabled) {
		if (component == null)
			return;
		component.setEnabled(enabled);
		if (component instanceof Container) {
			Component[] children = ((Container) component).getComponents();
			for (Component child : children) {
				setComponentTreeEnabled(child, enabled);
			}
		}
	}

	private void updateMarkerCountVisibility() {
		boolean visible = hRF.isChartsPeaksVisible().getValue() || hRF.isChartsMaxHoldVisible().getValue();
		spinnerMarkerCount.setEnabled(visible);
		spinnerMarkerCount.setVisible(visible);
		lblMarkerCount.setVisible(visible);
	}

}
