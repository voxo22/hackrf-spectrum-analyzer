package jspectrumanalyzer.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Label;
import java.awt.event.ActionListener;
import java.beans.PropertyChangeEvent;
import java.io.File;
import java.io.FileNotFoundException;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;
import java.util.Vector;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.swing.ComboBoxModel;
import javax.swing.DefaultBoundedRangeModel;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JSpinner.ListEditor;
import javax.swing.JTextField;
import javax.swing.SpinnerListModel;
import javax.swing.UIManager;

import jspectrumanalyzer.HackRFSweepSpectrumAnalyzer;
import jspectrumanalyzer.Version;
import jspectrumanalyzer.capture.ScreenCaptureH264;
import jspectrumanalyzer.core.FrequencyAllocationTable;
import jspectrumanalyzer.core.FrequencyAllocations;
import jspectrumanalyzer.core.FrequencyPresets;
import jspectrumanalyzer.core.FrequencyRange;
import jspectrumanalyzer.core.HackRFSettings;
import jspectrumanalyzer.core.HackRFSettings.HackRFEventAdapter;
import jspectrumanalyzer.core.Preset;
import net.miginfocom.swing.MigLayout;
import shared.mvc.MVCController;
import shared.mvc.ModelValue;
import shared.mvc.ModelValue.ModelValueBoolean;

import javax.swing.border.BevelBorder;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.JComboBox;
import javax.swing.SwingConstants;

public class HackRFSweepSettingsUI extends JPanel
{
	/**
	 * 
	 */
	private HackRFSettings hRF;
	private static final long serialVersionUID = 7721079457485020637L;
	private FrequencyRange FrequencyRange;
	private JLabel txtHackrfConnected;
	private FrequencySelectorPanel frequencySelectorStart;
	private FrequencySelectorPanel frequencySelectorEnd;
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
	private JCheckBox chckbxShowPeakMarker;
	private JCheckBox chckbxShowMaxHoldMarker;
	private JCheckBox chckbxDatestamp;
	private JCheckBox chckbxRemoveSpurs;
	private JButton btnPause;
	private JButton btnRecVideo;
	private JButton btnRecData;
	private JButton button_plus;
	private JButton button_minus;
	private SpinnerListModel spinnerModelFFTBinHz;
	private FrequencySelectorRangeBinder frequencyRangeSelector;
	private JCheckBox chckbxFilterSpectrum;
	private JSpinner spinnerPeakFallSpeed;
	private JComboBox<FrequencyAllocationTable> comboBoxFrequencyAllocationBands;
	private JComboBox<Preset> comboBoxFrequencyPresets;
	private JSlider sliderGainVGA;
	private JSlider sliderGainLNA;
	private JCheckBox chckbxAntennaLNA;
	//private JComboBox<BigDecimal> comboBoxLineThickness;
	private JSpinner spinnerLineThickness;
	private JCheckBox checkBoxPersistentDisplay;
	private JCheckBox checkBoxWaterfallEnabled;
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
	private JSpinner spinnerVideoArea;
	private JSlider sliderAvgOffset;
	private JLabel lblPeakFall;
	private JLabel lblPeakFallTrs;
	private JLabel lblPeakHoldTime;
	private JLabel lblAvgIterations;
	private JLabel lblAvgOffset;
	private JLabel lblAvgOffset2;
	private JLabel lblDecayRate;	
	
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
		JLabel lblNewLabel = new JLabel("Frequency start [MHz]");
		lblNewLabel.setForeground(Color.WHITE);
		panelMainSettings.add(lblNewLabel, "cell 0 0,growx,aligny center");

		frequencySelectorStart = new FrequencySelectorPanel(minFreq, maxFreq, freqStep, minFreq);
		panelMainSettings.add(frequencySelectorStart, "cell 0 1,grow");

		JLabel lblFrequencyEndmhz = new JLabel("Frequency stop [MHz]");
		lblFrequencyEndmhz.setForeground(Color.WHITE);
		panelMainSettings.add(lblFrequencyEndmhz, "cell 0 3,alignx left,aligny center");

		frequencySelectorEnd = new FrequencySelectorPanel(minFreq, maxFreq, freqStep, maxFreq);
		panelMainSettings.add(frequencySelectorEnd, "cell 0 4,grow");
		
		JLabel lblFrequencyPreset = new JLabel("Frequency presets");
		lblFrequencyPreset.setForeground(Color.WHITE);
		panelMainSettings.add(lblFrequencyPreset, "cell 0 7,alignx left,aligny center");

		FrequencyPresets presets = new FrequencyPresets();
		Vector presetValues = new Vector<>();
		//presetValues.add(null);
		presetValues.addAll(presets.getList());
		comboBoxFrequencyPresets = new JComboBox(presetValues);
		comboBoxFrequencyPresets.addActionListener(e -> {
			Preset preset = (Preset)comboBoxFrequencyPresets.getSelectedItem();
			frequencySelectorStart.setValue(preset.getStartFreq());
			frequencySelectorEnd.setValue(preset.getStopFreq());
			spinnerFFTBinHz.setValue(preset.getFFTBinWidth());
			spinner_FrequencyShift.setValue(preset.getFreqShift());
			slider_AmplitudeOffset.setValue(preset.getAmplitudeOffset());
		});
		panelMainSettings.add(comboBoxFrequencyPresets, "cell 0 8,growx");

		JButton button_minus = new JButton("<---");
		button_minus.setBackground(Color.BLACK);
		button_minus.addActionListener(e -> {
			int dif = (int) Math.round((frequencySelectorEnd.getValue() - frequencySelectorStart.getValue()) * 0.9);
			frequencySelectorStart.setValue(frequencySelectorStart.getValue() - dif);
			frequencySelectorEnd.setValue(frequencySelectorEnd.getValue() - dif);
		});
		panelMainSettings.add(button_minus, "cell 0 6,alignx left,growx");

		JButton button_plus = new JButton("--->");
		button_plus.setBackground(Color.BLACK);
		button_plus.addActionListener(e -> {
			int dif = (int) Math.round((frequencySelectorEnd.getValue() - frequencySelectorStart.getValue()) * 0.9);
			frequencySelectorStart.setValue(frequencySelectorStart.getValue() + dif);
			frequencySelectorEnd.setValue(frequencySelectorEnd.getValue() + dif);
		});
		panelMainSettings.add(button_plus, "flowx,cell 0 6,growx,alignx right");
		
		txtHackrfConnected = new JLabel();
		txtHackrfConnected.setText("HW disconnected");
		txtHackrfConnected.setForeground(Color.WHITE);
		txtHackrfConnected.setBackground(Color.BLACK);
		panelMainSettings.add(txtHackrfConnected, "cell 0 23,growx");
		txtHackrfConnected.setBorder(null);
		
		btnPause = new JButton("Pause");
		panelMainSettings.add(btnPause, "cell 0 25,growx,alignx left");
		btnPause.setBackground(Color.black);
		
		btnRecVideo = new JButton("S");
		panelMainSettings.add(btnRecVideo, "cell 0 25,growx,alignx left");
		btnRecVideo.setBackground(Color.red);
		
		btnRecData = new JButton("D");
		panelMainSettings.add(btnRecData, "cell 0 25,growx,alignx left");
		btnRecData.setBackground(Color.red);

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
		
		tabbedPane.addTab("Scan  ", tab1);
		tabbedPane.addTab("Params   ", tab2);
		tabbedPane.addTab("Waterfall/REC  ", tab3);
		tabbedPane.setForegroundAt(2, Color.BLACK);
		tabbedPane.setBackgroundAt(2, Color.WHITE);
		
		tabbedPane.setForegroundAt(1, Color.BLACK);
		tabbedPane.setBackgroundAt(1, Color.WHITE);

		tabbedPane.setForegroundAt(0, Color.BLACK);
		tabbedPane.setBackgroundAt(0, Color.WHITE);
		
		//tab1
		{
			JLabel lblGain = new JLabel("Gain");
			lblGain.setForeground(Color.WHITE);
			tab1.add(lblGain, "cell 0 0");
			
			JLabel lbl_gainValue = new JLabel(hackRFSettings.getGain() + "dB");
			lbl_gainValue.setForeground(Color.WHITE);
			tab1.add(lbl_gainValue, "flowx,cell 0 0,alignx right");

			sliderGain = new JSlider(JSlider.HORIZONTAL, 0, 100, 2);
			sliderGain.setFont(new Font("Monospaced", Font.BOLD, 14));
			sliderGain.setBackground(Color.BLACK);
			sliderGain.setForeground(Color.WHITE);
			tab1.add(sliderGain, "flowx,cell 0 1,growx");

			JLabel lblNewLabel_2 = new JLabel("LNA Gain");
			lblNewLabel_2.setForeground(Color.WHITE);
			tab1.add(lblNewLabel_2, "cell 0 2");
			
			sliderGainLNA = new JSlider(SwingConstants.HORIZONTAL, 0, 100, 2);
			sliderGainLNA.setForeground(Color.WHITE);
			sliderGainLNA.setFont(new Font("Monospaced", Font.BOLD, 14));
			sliderGainLNA.setBackground(Color.BLACK);
			tab1.add(sliderGainLNA, "cell 0 2,growx");
			
			JLabel lblVgfaGaindb = new JLabel("VGA Gain");
			lblVgfaGaindb.setForeground(Color.WHITE);
			tab1.add(lblVgfaGaindb, "cell 0 4");
			
			sliderGainVGA = new JSlider(SwingConstants.HORIZONTAL, 0, 100, 2);
			sliderGainVGA.setForeground(Color.WHITE);
			sliderGainVGA.setFont(new Font("Monospaced", Font.BOLD, 14));
			sliderGainVGA.setBackground(Color.BLACK);
			tab1.add(sliderGainVGA, "cell 0 4,growx");
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
			
			JLabel lblCALC = new JLabel("SPECTR");
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

			chckbxShowMaxHold = new JCheckBox(" MaxHOLD");
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
			
			chckbxShowMaxHoldMarker = new JCheckBox(" MaxHOLD");
			chckbxShowMaxHoldMarker.setHorizontalTextPosition(SwingConstants.TRAILING);
			chckbxShowMaxHoldMarker.setForeground(Color.WHITE);
			chckbxShowMaxHoldMarker.setBackground(Color.BLACK);
			tab1.add(chckbxShowMaxHoldMarker, "cell 0 11,alignx right");
			
			
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
			spinnerModelFFTBinHz = new SpinnerListModel(new String[] { "3", "10", "20", 
					"50", "100", "200", "500", "1000", "2000" });
			spinnerFFTBinHz.setModel(spinnerModelFFTBinHz);
			tab1.add(spinnerFFTBinHz, "flowx, cell 0 13,alignx right");
			((ListEditor) spinnerFFTBinHz.getEditor()).getTextField().setHorizontalAlignment(JTextField.RIGHT);
			((ListEditor) spinnerFFTBinHz.getEditor()).getTextField().setColumns(5);
			

			hackRFSettings.getGain().addListener((gain) -> lbl_gainValue.setText(String.format("%d dB (LNA: %d dB  VGA: %d dB)", 
					gain, hackRFSettings.getGainLNA().getValue(), hackRFSettings.getGainVGA().getValue())));
			
			JLabel lblDisplayFrequencyShift = new JLabel("Shift [MHz] ");
			lblDisplayFrequencyShift.setForeground(Color.WHITE);
			tab1.add(lblDisplayFrequencyShift, "cell 0 15,alignx right");
			
			spinner_FrequencyShift = new JSpinner();
			spinner_FrequencyShift.setModel(new SpinnerListModel(new String[] { "0", "-120", "9750", "10600" }));
			spinner_FrequencyShift.setFont(new Font("Monospaced", Font.BOLD, 14));
			((ListEditor) spinner_FrequencyShift.getEditor()).getTextField().setHorizontalAlignment(JTextField.RIGHT);
			((ListEditor) spinner_FrequencyShift.getEditor()).getTextField().setColumns(5);
			tab1.add(spinner_FrequencyShift, "cell 0 15,alignx right");
			
			JLabel lblAmplitudeOffset = new JLabel("Ampl Offset");
			lblAmplitudeOffset.setForeground(Color.WHITE);
			tab1.add(lblAmplitudeOffset, "cell 0 16");
			
			slider_AmplitudeOffset = new JSlider(JSlider.HORIZONTAL, 0, 100, 2);
			slider_AmplitudeOffset.setForeground(Color.WHITE);
			slider_AmplitudeOffset.setBackground(Color.BLACK);
			slider_AmplitudeOffset.setFont(new Font("Monospaced", Font.BOLD, 14));
			slider_AmplitudeOffset.setMinimum(-20);
			slider_AmplitudeOffset.setMaximum(20);
			//slider_AmplitudeOffset.setValue(30);
			tab1.add(slider_AmplitudeOffset, "cell 0 16,growx");
			
			JLabel lblAmplitudeOffset2 = new JLabel();
			lblAmplitudeOffset2.setForeground(Color.WHITE);
			tab1.add(lblAmplitudeOffset2, "cell 0 16, alignx right");
			
			hackRFSettings.getAmplitudeOffset().addListener((amplitude) -> lblAmplitudeOffset2.setText(String.format("%d dB", amplitude)));
			
			JLabel lblPowerFluxCal = new JLabel("Power Calbr");
			lblPowerFluxCal.setForeground(Color.WHITE);
			tab1.add(lblPowerFluxCal, "cell 0 17");
			
			slider_PowerFluxCal = new JSlider(JSlider.HORIZONTAL, 0, 100, 2);
			slider_PowerFluxCal.setForeground(Color.WHITE);
			slider_PowerFluxCal.setBackground(Color.BLACK);
			slider_PowerFluxCal.setFont(new Font("Monospaced", Font.BOLD, 14));
			slider_PowerFluxCal.setMinimum(25);
			slider_PowerFluxCal.setMaximum(65);
			//slider_AmplitudeOffset.setValue(30);
			tab1.add(slider_PowerFluxCal, "cell 0 17,growx");
			
			JLabel lblPowerFluxCal2 = new JLabel();
			lblPowerFluxCal2.setForeground(Color.WHITE);
			tab1.add(lblPowerFluxCal2, "cell 0 17, alignx right");
			
			hackRFSettings.getPowerFluxCal().addListener((fluxcal) -> lblPowerFluxCal2.setText(String.format("%d", fluxcal)));
		}
		//tab2
		{
			chckbxDatestamp = new JCheckBox(" Timestamp");
			chckbxDatestamp.setForeground(Color.WHITE);
			chckbxDatestamp.setBackground(Color.BLACK);
			tab2.add(chckbxDatestamp, "cell 0 0,alignx right");
			/*
			chckbxFilterSpectrum = new JCheckBox("Filter spectrum");
			chckbxFilterSpectrum.setBackground(Color.BLACK);
			chckbxFilterSpectrum.setForeground(Color.WHITE);
			tab2.add(chckbxFilterSpectrum, "flowx,cell 0 0,growx");
			*/
			JLabel lblSpectrLineThickness = new JLabel("Lines Thickness ");
			lblSpectrLineThickness.setForeground(Color.WHITE);
			tab2.add(lblSpectrLineThickness, "cell 0 1,alignx right");
			
			spinnerLineThickness = new JSpinner();
			spinnerLineThickness.setModel(new SpinnerListModel(new String[] { "1", "1.5", "2", "3" }));
			spinnerLineThickness.setFont(new Font("Monospaced", Font.BOLD, 14));
			((ListEditor) spinnerLineThickness.getEditor()).getTextField().setHorizontalAlignment(JTextField.RIGHT);
			((ListEditor) spinnerLineThickness.getEditor()).getTextField().setColumns(3);
			tab2.add(spinnerLineThickness, "cell 0 1,alignx right");
			/*
			comboBoxLineThickness = new JComboBox(new BigDecimal[] {
					new BigDecimal("1"), new BigDecimal("1.5"), new BigDecimal("2"), new BigDecimal("3")
					});
			tab2.add(comboBoxLineThickness, "cell 0 1,alignx right");
			*/
			lblPeakFall = new JLabel("Peak Fallout Time ");
			lblPeakFall.setForeground(Color.WHITE);
			tab2.add(lblPeakFall, "cell 0 3,alignx right");
			
			spinnerPeakFallSpeed = new JSpinner();
			spinnerPeakFallSpeed.setModel(new SpinnerListModel(new String[] { "1", "5", "10", "20", "30", "60" }));
			spinnerPeakFallSpeed.setFont(new Font("Monospaced", Font.BOLD, 14));
			((ListEditor) spinnerPeakFallSpeed.getEditor()).getTextField().setHorizontalAlignment(JTextField.RIGHT);
			((ListEditor) spinnerPeakFallSpeed.getEditor()).getTextField().setColumns(2);
			tab2.add(spinnerPeakFallSpeed, "cell 0 3,alignx right");
			
			lblPeakFallTrs = new JLabel("Peak Fall Threshold ");
			lblPeakFallTrs.setForeground(Color.WHITE);
			tab2.add(lblPeakFallTrs, "cell 0 5,alignx right");
			
			spinnerPeakFallTrs = new JSpinner();
			spinnerPeakFallTrs.setModel(new SpinnerListModel(new String[] { "0", "2", "5", "10", "15" }));
			spinnerPeakFallTrs.setFont(new Font("Monospaced", Font.BOLD, 14));
			((ListEditor) spinnerPeakFallTrs.getEditor()).getTextField().setHorizontalAlignment(JTextField.RIGHT);
			((ListEditor) spinnerPeakFallTrs.getEditor()).getTextField().setColumns(2);
			tab2.add(spinnerPeakFallTrs, "cell 0 5,alignx right");
			
			lblPeakHoldTime = new JLabel("Peak Hold Time ");
			lblPeakHoldTime.setForeground(Color.WHITE);
			tab2.add(lblPeakHoldTime, "cell 0 7,alignx right");
			
			spinnerPeakHoldTime = new JSpinner();
			spinnerPeakHoldTime.setModel(new SpinnerListModel(new String[] { "0", "1", "2", "5", "10", "30", "60" }));
			spinnerPeakHoldTime.setFont(new Font("Monospaced", Font.BOLD, 14));
			((ListEditor) spinnerPeakHoldTime.getEditor()).getTextField().setHorizontalAlignment(JTextField.RIGHT);
			((ListEditor) spinnerPeakHoldTime.getEditor()).getTextField().setColumns(2);
			tab2.add(spinnerPeakHoldTime, "cell 0 7,alignx right");
			
			lblDecayRate = new JLabel("Persistent Decay Rate ");
			lblDecayRate.setForeground(Color.WHITE);
			tab2.add(lblDecayRate, "cell 0 9,alignx right");
			
			spinnerDecayRate = new JSpinner();
			spinnerDecayRate.setModel(new SpinnerListModel(new String[] { "1", "5", "10", "30", "60" }));
			spinnerDecayRate.setFont(new Font("Monospaced", Font.BOLD, 14));
			((ListEditor) spinnerDecayRate.getEditor()).getTextField().setHorizontalAlignment(JTextField.RIGHT);
			((ListEditor) spinnerDecayRate.getEditor()).getTextField().setColumns(2);
			tab2.add(spinnerDecayRate, "cell 0 9,alignx right");
			/*
			spinnerDecayRate = new JSpinner();
			spinnerDecayRate.setModel(new SpinnerNumberModel(10, 0, 500, 1));
			spinnerDecayRate.setFont(new Font("Monospaced", Font.BOLD, 14));
			tab2.add(spinnerDecayRate, "cell 0 9,alignx right");
			*/
			/*
			comboBoxDecayRate = new JComboBox(
					new Vector<>(IntStream.rangeClosed(hRF.getPersistentDisplayDecayRate().getMin(), hRF.getPersistentDisplayDecayRate().getMax()).
							boxed().collect(Collectors.toList())));
			tab2.add(comboBoxDecayRate, "cell 0 5,alignx right");
			*/
			
			lblAvgIterations = new JLabel("Average Iterations ");
			lblAvgIterations.setForeground(Color.WHITE);
			tab2.add(lblAvgIterations, "cell 0 11,alignx right");
			
			spinnerAvgIterations = new JSpinner();
			spinnerAvgIterations.setModel(new SpinnerListModel(new String[] { "20", "50", "80" }));
			spinnerAvgIterations.setFont(new Font("Monospaced", Font.BOLD, 14));
			((ListEditor) spinnerAvgIterations.getEditor()).getTextField().setHorizontalAlignment(JTextField.RIGHT);
			((ListEditor) spinnerAvgIterations.getEditor()).getTextField().setColumns(2);
			tab2.add(spinnerAvgIterations, "cell 0 11,alignx right");
			
			lblAvgOffset = new JLabel("Avg Offset");
			lblAvgOffset.setForeground(Color.WHITE);
			tab2.add(lblAvgOffset, "cell 0 12");
			
			sliderAvgOffset = new JSlider();
			sliderAvgOffset.setForeground(Color.WHITE);
			sliderAvgOffset.setBackground(Color.BLACK);
			sliderAvgOffset.setFont(new Font("Monospaced", Font.BOLD, 14));
			sliderAvgOffset.setMinimum(-10);
			sliderAvgOffset.setMaximum(10);
			sliderAvgOffset.setValue(0);
			tab2.add(sliderAvgOffset, "cell 0 12,growx");
			
			JLabel lblAvgOffset2 = new JLabel();
			lblAvgOffset2.setForeground(Color.WHITE);
			tab2.add(lblAvgOffset2, "cell 0 12, alignx right");
			
			hackRFSettings.getAvgOffset().addListener((avgOff) -> lblAvgOffset2.setText(String.format("%d dB", avgOff)));
			
			JLabel lblNumberOfSamples = new JLabel("Samples ");
			lblNumberOfSamples.setForeground(Color.WHITE);
			tab2.add(lblNumberOfSamples, "cell 0 14,alignx right");

			spinner_numberOfSamples = new JSpinner();
			spinner_numberOfSamples.setModel(new SpinnerListModel(new String[] { "8192", "16384", "32768", "65536", "131072", "262144" }));
			spinner_numberOfSamples.setFont(new Font("Monospaced", Font.BOLD, 14));
			((ListEditor) spinner_numberOfSamples.getEditor()).getTextField().setHorizontalAlignment(JTextField.RIGHT);
			((ListEditor) spinner_numberOfSamples.getEditor()).getTextField().setColumns(6);
			tab2.add(spinner_numberOfSamples, "cell 0 14,alignx right");
			
			JLabel lblDisplayFrequencyAllocation = new JLabel("Frequency Allocation Bands");
			lblDisplayFrequencyAllocation.setForeground(Color.WHITE);
			tab2.add(lblDisplayFrequencyAllocation, "cell 0 15");
			
			FrequencyAllocations frequencyAllocations	= new FrequencyAllocations();
			Vector<FrequencyAllocationTable> freqAllocValues	= new Vector<>();
			freqAllocValues.add(null);
			freqAllocValues.addAll(frequencyAllocations.getTable().values());
			DefaultComboBoxModel<FrequencyAllocationTable> freqAllocModel	= new  DefaultComboBoxModel<>(freqAllocValues);
			comboBoxFrequencyAllocationBands = new JComboBox<FrequencyAllocationTable>(freqAllocModel);
			tab2.add(comboBoxFrequencyAllocationBands, "cell 0 16,growx");
			
			
			/*
			lblDebugDisplay = new JLabel("Debug display");
			lblDebugDisplay.setForeground(Color.WHITE);
			tab2.add(lblDebugDisplay, "flowx,cell 0 22,growx");
			
			checkBoxDebugDisplay = new JCheckBox("");
			checkBoxDebugDisplay.setForeground(Color.WHITE);
			checkBoxDebugDisplay.setBackground(Color.BLACK);
			tab2.add(checkBoxDebugDisplay, "cell 0 22,alignx right");
			*/
		}
		//tab3 Waterfall
		{
			checkBoxWaterfallEnabled = new JCheckBox(" Waterfall");
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
			tab3.add(lblWaterfallPaletteLength, "cell 0 5");
	
			slider_waterfallPaletteSize = new JSlider();
			slider_waterfallPaletteSize.setBackground(Color.BLACK);
			slider_waterfallPaletteSize.setForeground(Color.WHITE);
			slider_waterfallPaletteSize.setMinimum(10); //palette offset
			slider_waterfallPaletteSize.setMaximum(90);
			tab3.add(slider_waterfallPaletteSize, "cell 0 5,growx");
			
			JLabel lblWaterfallPaletteLength2 = new JLabel();
			lblWaterfallPaletteLength2.setForeground(Color.WHITE);
			tab3.add(lblWaterfallPaletteLength2, "cell 0 5, alignx right");
			
			hackRFSettings.getSpectrumPaletteSize().addListener((plen) -> lblWaterfallPaletteLength2.setText(String.format("%d dB", plen)));
			
			JLabel lblWaterfallSpeed = new JLabel("Speed");
			lblWaterfallSpeed.setForeground(Color.WHITE);
			tab3.add(lblWaterfallSpeed, "cell 0 7");
			
			slider_WaterfallSpeed = new JSlider();
			slider_WaterfallSpeed.setForeground(Color.WHITE);
			slider_WaterfallSpeed.setBackground(Color.BLACK);
			slider_WaterfallSpeed.setFont(new Font("Monospaced", Font.BOLD, 14));
			slider_WaterfallSpeed.setMinimum(1);
			slider_WaterfallSpeed.setMaximum(10);
			slider_WaterfallSpeed.setValue(4);
			tab3.add(slider_WaterfallSpeed, "cell 0 7,growx");	
			
			JLabel lblWaterfallSpeed2 = new JLabel();
			lblWaterfallSpeed2.setForeground(Color.WHITE);
			tab3.add(lblWaterfallSpeed2, "cell 0 7, alignx right");
			
			hackRFSettings.getWaterfallSpeed().addListener((speed) -> lblWaterfallSpeed2.setText(String.format("%d", speed)));
			
			JSeparator separator1 = new JSeparator();
			tab3.add(separator1,"cell 0 9,growx");
			
			JLabel lblCALC = new JLabel("REC");
			lblCALC.setForeground(Color.WHITE);
			tab3.add(lblCALC, "growx,cell 0 10");
			
			JLabel lblLogDetail = new JLabel("Data Log Interval ");
			lblLogDetail.setForeground(Color.WHITE);
			tab3.add(lblLogDetail, "cell 0 10,alignx right");
			
			spinnerLogDetail = new JSpinner();
			spinnerLogDetail.setModel(new SpinnerListModel(new String[] { "FRA", "SEC", "MIN" }));
			spinnerLogDetail.setFont(new Font("Monospaced", Font.BOLD, 14));
			((ListEditor) spinnerLogDetail.getEditor()).getTextField().setHorizontalAlignment(JTextField.RIGHT);
			((ListEditor) spinnerLogDetail.getEditor()).getTextField().setColumns(3);
			tab3.add(spinnerLogDetail, "cell 0 10,alignx right");
			
			JLabel lblVideoArea = new JLabel("Video Area ");
			lblVideoArea.setForeground(Color.WHITE);
			tab3.add(lblVideoArea, "cell 0 12,alignx right");
			
			spinnerVideoArea = new JSpinner();
			spinnerVideoArea.setModel(new SpinnerListModel(new String[] { "SPEC", "SP+W", "FULL" }));
			spinnerVideoArea.setFont(new Font("Monospaced", Font.BOLD, 14));
			((ListEditor) spinnerVideoArea.getEditor()).getTextField().setHorizontalAlignment(JTextField.RIGHT);
			((ListEditor) spinnerVideoArea.getEditor()).getTextField().setColumns(4);
			tab3.add(spinnerVideoArea, "cell 0 12,alignx right");
			
			JLabel lblVideoFormat = new JLabel("Video Format ");
			lblVideoFormat.setForeground(Color.WHITE);
			tab3.add(lblVideoFormat, "cell 0 14,alignx right");
			
			spinnerVideoFormat = new JSpinner();
			spinnerVideoFormat.setModel(new SpinnerListModel(new String[] { "GIF", "MP4" }));
			spinnerVideoFormat.setFont(new Font("Monospaced", Font.BOLD, 14));
			((ListEditor) spinnerVideoFormat.getEditor()).getTextField().setHorizontalAlignment(JTextField.RIGHT);
			((ListEditor) spinnerVideoFormat.getEditor()).getTextField().setColumns(3);
			tab3.add(spinnerVideoFormat, "cell 0 14,alignx right");
			
			JLabel lblVideoResolution = new JLabel("Video Resolution ");
			lblVideoResolution.setForeground(Color.WHITE);
			tab3.add(lblVideoResolution, "cell 0 16,alignx right");
			
			spinnerVideoResolution = new JSpinner();
			spinnerVideoResolution.setModel(new SpinnerListModel(new String[] { "360", "540", "720", "1080" }));
			spinnerVideoResolution.setFont(new Font("Monospaced", Font.BOLD, 14));
			((ListEditor) spinnerVideoResolution.getEditor()).getTextField().setHorizontalAlignment(JTextField.RIGHT);
			((ListEditor) spinnerVideoResolution.getEditor()).getTextField().setColumns(4);
			tab3.add(spinnerVideoResolution, "cell 0 16,alignx right");
			
			JLabel lblVideoFrameRate = new JLabel("Video Framerate ");
			lblVideoFrameRate.setForeground(Color.WHITE);
			tab3.add(lblVideoFrameRate, "cell 0 18,alignx right");
			
			spinnerVideoFrameRate = new JSpinner();
			spinnerVideoFrameRate.setModel(new SpinnerListModel(new String[] { "1", "2", "5", "10", "15", "25", "30", "60" }));
			spinnerVideoFrameRate.setFont(new Font("Monospaced", Font.BOLD, 14));
			((ListEditor) spinnerVideoFrameRate.getEditor()).getTextField().setHorizontalAlignment(JTextField.RIGHT);
			((ListEditor) spinnerVideoFrameRate.getEditor()).getTextField().setColumns(2);
			tab3.add(spinnerVideoFrameRate, "cell 0 18,alignx right");
		}
		bindViewToModel();
	}

	private void bindViewToModel() {
		frequencyRangeSelector = new FrequencySelectorRangeBinder(frequencySelectorStart, frequencySelectorEnd);

		new MVCController(spinnerFFTBinHz, hRF.getFFTBinHz(), 
				viewValue -> Integer.parseInt(viewValue.toString().replaceAll("\\s", "")), 
				modelValue -> {
					Optional<?> val = spinnerModelFFTBinHz.getList().stream().filter(value -> modelValue <= Integer.parseInt(value.toString().replaceAll("\\s", ""))).findFirst();
					if (val.isPresent())
						return val.get();
					else
						return spinnerModelFFTBinHz.getList().get(0);
				});
		new MVCController(sliderGain, hRF.getGain());
		new MVCController(spinner_numberOfSamples, hRF.getSamples(), val -> Integer.parseInt(val.toString()), val -> val.toString());
		new MVCController(spinner_FrequencyShift, hRF.getFreqShift(), val -> Integer.parseInt(val.toString()), val -> val.toString());
		new MVCController(chckbxAntennaPower, hRF.getAntennaPowerEnable());
		new MVCController(chckbxAntennaLNA, hRF.getAntennaLNA());
		new MVCController(slider_waterfallPaletteStart, hRF.getSpectrumPaletteStart());
		new MVCController(slider_waterfallPaletteSize, hRF.getSpectrumPaletteSize());
		new MVCController(slider_AmplitudeOffset, hRF.getAmplitudeOffset());
		new MVCController(slider_WaterfallSpeed, hRF.getWaterfallSpeed());
		new MVCController(	(Consumer<FrequencyRange> valueChangedCall) ->  
								frequencyRangeSelector.addPropertyChangeListener((PropertyChangeEvent evt) -> valueChangedCall.accept(frequencyRangeSelector.getFrequencyRange()) ) ,
							(FrequencyRange newComponentValue) -> {
								if(frequencyRangeSelector.selFreqStart.getValue() != newComponentValue.getStartMHz())
									frequencyRangeSelector.selFreqStart.setValue(newComponentValue.getStartMHz());
								if(frequencyRangeSelector.selFreqEnd.getValue() != newComponentValue.getEndMHz())
									frequencyRangeSelector.selFreqEnd.setValue(newComponentValue.getEndMHz());
							},
							hRF.getFrequency()
		); 
		new MVCController(chckbxShowRealtime, hRF.isChartsRealtimeVisible());
		new MVCController(chckbxShowAverage, hRF.isChartsAverageVisible());
		new MVCController(chckbxShowPeaks, hRF.isChartsPeaksVisible());
		new MVCController(chckbxShowMaxHold, hRF.isChartsMaxHoldVisible());
		//new MVCController(chckbxFilterSpectrum, hRF.isFilterSpectrum());
		new MVCController(chckbxRemoveSpurs, hRF.isSpurRemoval());
		
		new MVCController((valueChangedCall) -> btnPause.addActionListener((event) -> valueChangedCall.accept(!hRF.isCapturingPaused().getValue())), 
				isCapt -> btnPause.setText(!isCapt ? "||"  : "►"), 
				hRF.isCapturingPaused());
		
		new MVCController((valueChangedCall) -> btnRecVideo.addActionListener((event) -> valueChangedCall.accept(!hRF.isRecordedVideo().getValue())), 
				isRec -> btnRecVideo.setText(!isRec ? "Video"  : "■"), 
				hRF.isRecordedVideo());
		
		new MVCController((valueChangedCall) -> btnRecData.addActionListener((event) -> valueChangedCall.accept(!hRF.isRecordedData().getValue())), 
				isRecData -> btnRecData.setText(!isRecData ? "Data"  : "■"), 
				hRF.isRecordedData());
	
		new MVCController(spinnerPeakFallSpeed, hRF.getPeakFallRate(), val -> Integer.parseInt(val.toString()), val -> val.toString());
		new MVCController(spinnerPeakFallTrs, hRF.getPeakFallTrs(), val -> Integer.parseInt(val.toString()), val -> val.toString());
		new MVCController(spinnerPeakHoldTime, hRF.getPeakHoldTime(), val -> Integer.parseInt(val.toString()), val -> val.toString());
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
				//lblAvgOffset2.setVisible(enabled);
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
		
		new MVCController(checkBoxPersistentDisplay, hRF.isPersistentDisplayVisible());
		
		new MVCController(spinnerLogDetail, hRF.getLogDetail(), val -> new String (val.toString()), val -> val.toString());
		new MVCController(spinnerVideoArea, hRF.getVideoArea(), val -> new String (val.toString()), val -> val.toString());
		new MVCController(spinnerVideoFormat, hRF.getVideoFormat(), val -> new String (val.toString()), val -> val.toString());
		new MVCController(spinnerVideoResolution, hRF.getVideoResolution(), val -> Integer.parseInt(val.toString()), val -> val.toString());
		new MVCController(spinnerVideoFrameRate, hRF.getVideoFrameRate(), val -> Integer.parseInt(val.toString()), val -> val.toString());
		
		
		new MVCController(chckbxDatestamp, hRF.isDatestampVisible());
		
		new MVCController(chckbxShowPeakMarker, hRF.isPeakMarkerVisible());
		new MVCController(chckbxShowMaxHoldMarker, hRF.isMaxHoldMarkerVisible());
		hRF.isChartsMaxHoldVisible().addListener((enabled) -> {
			SwingUtilities.invokeLater(()->{
				chckbxShowMaxHoldMarker.setVisible(enabled);
			});
		});
		
		new MVCController(checkBoxWaterfallEnabled, hRF.isWaterfallVisible());
		
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
				txtHackrfConnected.setText("HW "+(hardwareSendingData ? "connected":"disconnected"));
			}
		});;
		
	}

}
