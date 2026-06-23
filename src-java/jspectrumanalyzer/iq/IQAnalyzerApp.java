package jspectrumanalyzer.iq;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Rectangle;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import javax.swing.JButton;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.BorderFactory;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;

public class IQAnalyzerApp {
	private static final Color PANEL_BG = Color.BLACK;
	private static final Color CONTROL_BG = Color.BLACK;
	private static final Color TEXT_FG = Color.WHITE;
	private static final Color MUTED_FG = new Color(0xdddddd);
	private static final Color START_BG = new Color(0x44cc44);
	private static final Color STOP_BG = Color.YELLOW;
	private static final long DEFAULT_CENTER_FREQ_HZ = 951_800_000L;
	private static final int DEFAULT_SAMPLE_RATE_HZ = 10_000_000;
	private static final int DEFAULT_BASEBAND_FILTER_HZ = 0;
	private static final int DEFAULT_LNA_GAIN = 40;
	private static final int DEFAULT_VGA_GAIN = 32;
	private static final int DEFAULT_VISIBLE_SAMPLES = 4096;
	private static final int DEFAULT_BUFFER_SECONDS = 2;
	private static final int MAX_BLOCK_BYTES = 262_144;
	private static final int NARROW_DC_AVOID_MIN_HZ = 50_000;
	private static final int NARROW_DC_AVOID_GUARD_HZ = 25_000;
	private static final int WIDE_DC_AVOID_HZ = 100_000;
	private static final int WIDE_DC_AVOID_GUARD_HZ = 25_000;
	private static final File WINDOW_SETTINGS_FILE = new File("hackrf-iq-analyzer.ini");

	private final AtomicLong blocks = new AtomicLong();
	private final AtomicLong bytes = new AtomicLong();
	private final AtomicLong latestCenterFreqHz = new AtomicLong(DEFAULT_CENTER_FREQ_HZ);
	private final AtomicLong latestSampleRateHz = new AtomicLong(DEFAULT_SAMPLE_RATE_HZ);
	private final AtomicLong latestDecimation = new AtomicLong(1);

	private JFrame frame;
	private JSplitPane splitPane;
	private IQTimeDomainPanel timeDomainPanel;
	private IQSpectrumPanel spectrumPanel;
	private volatile IQRingBuffer ringBuffer;
	private JTextField centerField;
	private JComboBox<RateOption> sampleRateCombo;
	private JComboBox<SampleViewOption> sampleViewCombo;
	private JLabel timeViewInfoLabel;
	private JComboBox<PresetOption> presetCombo;
	private JSlider lnaSlider;
	private JSlider vgaSlider;
	private JLabel lnaValueLabel;
	private JLabel vgaValueLabel;
	private JCheckBox rfAmpCheck;
	private JComboBox<ViewModeOption> viewModeCombo;
	private JTextField channelOffsetField;
	private JComboBox<BandwidthOption> channelBandwidthCombo;
	private JComboBox<OutputRateOption> outputRateCombo;
	private JCheckBox envelopeCheck;
	private JCheckBox deviationCheck;
	private JCheckBox burstDetectCheck;
	private JCheckBox autoLevelCheck;
	private JCheckBox audioEnableCheck;
	private JComboBox<IQAudioOutput.Mode> audioModeCombo;
	private JSlider audioVolumeSlider;
	private JSlider audioToneSlider;
	private JLabel audioToneValueLabel;
	private JButton audioRecordButton;
	private JButton iqRecordButton;
	private JLabel recordStatusLabel;
	private JCheckBox triggerCheck;
	private JButton singleTriggerButton;
	private JSlider triggerLevelSlider;
	private JComboBox<TriggerPreOption> triggerPreCombo;
	private JButton runStopButton;
	private JLabel statusLabel;
	private Timer repaintTimer;
	private Timer rfRestartTimer;
	private Thread iqThread;
	private volatile IQChannelProcessor channelProcessor;
	private volatile IQFrequencyShifter wideLowIfShifter;
	private IQAudioOutput audioOutput = new IQAudioOutput();
	private IQAutoLevel autoLevel = new IQAutoLevel();
	private volatile WavFileWriter audioRecorder;
	private volatile WavFileWriter iqRecorder;
	private volatile int iqRecorderSampleRateHz = DEFAULT_SAMPLE_RATE_HZ;
	private byte[] channelOutput = new byte[MAX_BLOCK_BYTES];
	private byte[] iqRecordBuffer = new byte[MAX_BLOCK_BYTES];
	private volatile long startedNanos = 0;
	private volatile boolean streaming = false;
	private volatile int streamGeneration = 0;
	private volatile int activeRawSampleRateHz = DEFAULT_SAMPLE_RATE_HZ;
	private volatile long activeCenterFreqHz = DEFAULT_CENTER_FREQ_HZ;
	private volatile long activeLowIfShiftHz = 0;
	private volatile int activeRfSampleRateHz = DEFAULT_SAMPLE_RATE_HZ;
	private volatile int activeLnaGain = DEFAULT_LNA_GAIN;
	private volatile int activeVgaGain = DEFAULT_VGA_GAIN;
	private volatile boolean activeRfAmp = false;
	private volatile boolean externalSource = false;
	private volatile long externalCenterFreqHz = DEFAULT_CENTER_FREQ_HZ;
	private volatile int externalSampleRateHz = DEFAULT_SAMPLE_RATE_HZ;
	private volatile int externalContentBandwidthHz = DEFAULT_SAMPLE_RATE_HZ;
	private volatile LongSupplier recordingTimeMillisSupplier;
	private boolean singleTriggerArmed = false;
	private Long spectrumDragBaseOffsetHz = null;

	public static void main(String[] args) {
		long centerFreqHz = args.length > 0 ? parseFrequencyHz(args[0]) : DEFAULT_CENTER_FREQ_HZ;
		int sampleRateHz = args.length > 1 ? parseInt(args[1], "sample rate") : DEFAULT_SAMPLE_RATE_HZ;
		int lnaGain = args.length > 2 ? parseInt(args[2], "lna gain") : DEFAULT_LNA_GAIN;
		int vgaGain = args.length > 3 ? parseInt(args[3], "vga gain") : DEFAULT_VGA_GAIN;

		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		} catch (Exception e) {
			// The analyzer is usable with the default Swing look and feel too.
		}
		SwingUtilities.invokeLater(() -> new IQAnalyzerApp().show(centerFreqHz, sampleRateHz, lnaGain, vgaGain));
	}

	public JFrame show(long centerFreqHz, int sampleRateHz, int lnaGain, int vgaGain) {
		return show(centerFreqHz, sampleRateHz, lnaGain, vgaGain, false, 0, 0, null);
	}

	public JFrame show(long centerFreqHz, int sampleRateHz, int lnaGain, int vgaGain, boolean rfAmp,
			long channelOffsetHz, int channelBandwidthHz, Runnable closedCallback) {
		ringBuffer = new IQRingBuffer(createBufferBytes(sampleRateHz));
		timeDomainPanel = new IQTimeDomainPanel(ringBuffer, DEFAULT_VISIBLE_SAMPLES);
		timeDomainPanel.addVisibleSamplesListener(e -> SwingUtilities.invokeLater(this::syncTimeViewFromPanel));
		spectrumPanel = new IQSpectrumPanel(ringBuffer);
		spectrumPanel.setOffsetDragListener(offsetHz -> SwingUtilities.invokeLater(() -> adjustOffsetFromSpectrum(offsetHz)));

		frame = new JFrame("HackRF Time-Domain Analyzer");
		frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		frame.setLayout(new BorderLayout());
		frame.add(createControls(centerFreqHz, sampleRateHz, lnaGain, vgaGain, rfAmp), BorderLayout.EAST);
		splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, timeDomainPanel, spectrumPanel);
		splitPane.setResizeWeight(0.75d);
		splitPane.setDividerSize(6);
		frame.add(splitPane, BorderLayout.CENTER);
		frame.pack();
		if (!restoreWindowGeometry()) {
			splitPane.setDividerLocation(0.74d);
			frame.setLocationRelativeTo(null);
		}

		repaintTimer = new Timer(33, e -> {
			TriggerPreOption triggerPre = (TriggerPreOption) triggerPreCombo.getSelectedItem();
			timeDomainPanel.setEnvelopeOnly(envelopeCheck.isSelected());
			timeDomainPanel.setDeviationView(deviationCheck.isSelected());
			timeDomainPanel.setBurstDetectorEnabled(burstDetectCheck.isSelected());
			timeDomainPanel.setTrigger(triggerCheck.isSelected(), triggerLevelSlider.getValue(),
					triggerPre == null ? 25 : triggerPre.percent);
			timeDomainPanel.setSingleTrigger(singleTriggerArmed);
			timeDomainPanel.setStats(latestCenterFreqHz.get(), (int) latestSampleRateHz.get(), blocks.get(),
					bytes.get(), startedNanos, (int) latestDecimation.get());
			spectrumPanel.setCenterFrequencyHz(latestCenterFreqHz.get());
			spectrumPanel.setSampleRateHz((int) latestSampleRateHz.get());
			spectrumPanel.setChannelOffsetHz(getCurrentChannelOffsetHz());
			spectrumPanel.setDcArtifactOffsetHz(-activeLowIfShiftHz);
			spectrumPanel.setChannelBandwidthHz(getSelectedChannelBandwidthHz());
			updateTimeViewInfo();
			updateRecordingStatus();
			styleSingleTriggerButton();
			timeDomainPanel.repaint();
			spectrumPanel.repaint();
		});

		frame.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				saveWindowGeometry();
			}

			@Override
			public void windowClosed(WindowEvent e) {
				stopRecordings();
				stopStream();
				repaintTimer.stop();
				if (rfRestartTimer != null) {
					rfRestartTimer.stop();
				}
				if (closedCallback != null) {
					closedCallback.run();
				}
			}
		});

		applyInitialChannelSelection(channelOffsetHz, channelBandwidthHz, sampleRateHz);
		frame.setVisible(true);
		repaintTimer.start();
		startStream();
		return frame;
	}

	public JFrame showExternal(long centerFreqHz, int sampleRateHz, long channelOffsetHz, int channelBandwidthHz,
			Runnable closedCallback) {
		return showExternal(centerFreqHz, sampleRateHz, channelOffsetHz, channelBandwidthHz, null, closedCallback);
	}

	public JFrame showExternal(long centerFreqHz, int sampleRateHz, long channelOffsetHz, int channelBandwidthHz,
			LongSupplier recordingTimeMillisSupplier, Runnable closedCallback) {
		externalSource = true;
		this.recordingTimeMillisSupplier = recordingTimeMillisSupplier;
		externalCenterFreqHz = centerFreqHz;
		externalSampleRateHz = sampleRateHz;
		externalContentBandwidthHz = channelBandwidthHz <= 0 ? sampleRateHz
				: Math.min(sampleRateHz, channelBandwidthHz);
		return show(centerFreqHz, sampleRateHz, DEFAULT_LNA_GAIN, DEFAULT_VGA_GAIN, false, channelOffsetHz,
				channelBandwidthHz, closedCallback);
	}

	public void acceptExternalIQ(byte[] signedIqData, int length) {
		if (!externalSource || !streaming || signedIqData == null || length < 2) {
			return;
		}
		acceptIQBlock(externalCenterFreqHz, externalSampleRateHz, signedIqData, length & ~1, false);
	}

	public void acceptExternalIQOwned(byte[] signedIqData, int length) {
		if (!externalSource || !streaming || signedIqData == null || length < 2) {
			return;
		}
		acceptIQBlock(externalCenterFreqHz, externalSampleRateHz, signedIqData, length & ~1, true);
	}

	private void applyInitialChannelSelection(long channelOffsetHz, int channelBandwidthHz, int sampleRateHz) {
		if (channelOffsetField == null || viewModeCombo == null) {
			return;
		}
		presetCombo.setSelectedIndex(0);
		channelOffsetField.setText(formatOffset(channelOffsetHz));
		selectExactRate(sampleRateHz);
		if (channelBandwidthHz <= 0 || channelBandwidthHz >= sampleRateHz * 0.9d) {
			viewModeCombo.setSelectedIndex(0);
			selectBandwidth(sampleRateHz);
			selectExactOutputRate(sampleRateHz);
			return;
		}
		viewModeCombo.setSelectedIndex(1);
		selectBandwidth(channelBandwidthHz);
		selectExactOutputRate(sampleRateHz);
	}

	private boolean restoreWindowGeometry() {
		Properties properties = loadSettingsProperties();
		int x = parsePropertyInt(properties, "window.x", Integer.MIN_VALUE);
		int y = parsePropertyInt(properties, "window.y", Integer.MIN_VALUE);
		int width = parsePropertyInt(properties, "window.width", 0);
		int height = parsePropertyInt(properties, "window.height", 0);
		if (x == Integer.MIN_VALUE || y == Integer.MIN_VALUE || width < 640 || height < 420) {
			return false;
		}
		frame.setBounds(x, y, width, height);
		int divider = parsePropertyInt(properties, "split.divider", -1);
		if (divider > 80) {
			SwingUtilities.invokeLater(() -> splitPane.setDividerLocation(divider));
		}
		return true;
	}

	private void saveWindowGeometry() {
		if (frame == null) {
			return;
		}
		Rectangle bounds = frame.getBounds();
		Properties properties = loadSettingsProperties();
		properties.setProperty("window.x", Integer.toString(bounds.x));
		properties.setProperty("window.y", Integer.toString(bounds.y));
		properties.setProperty("window.width", Integer.toString(bounds.width));
		properties.setProperty("window.height", Integer.toString(bounds.height));
		if (splitPane != null) {
			properties.setProperty("split.divider", Integer.toString(splitPane.getDividerLocation()));
		}
		savePanelSettings(properties);
		try (FileOutputStream output = new FileOutputStream(WINDOW_SETTINGS_FILE)) {
			properties.store(output, "HackRF IQ Analyzer window settings");
		} catch (IOException e) {
			// Window geometry is convenience state only; failing to save it is harmless.
		}
	}

	private Properties loadSettingsProperties() {
		Properties properties = new Properties();
		if (!WINDOW_SETTINGS_FILE.isFile()) {
			return properties;
		}
		try (FileInputStream input = new FileInputStream(WINDOW_SETTINGS_FILE)) {
			properties.load(input);
		} catch (IOException e) {
			// Settings are optional convenience state.
		}
		return properties;
	}

	private void restorePanelSettings() {
		Properties properties = loadSettingsProperties();
		if (envelopeCheck != null) {
			envelopeCheck.setSelected(Boolean.parseBoolean(properties.getProperty("view.envelope", "true")));
		}
		if (deviationCheck != null) {
			deviationCheck.setSelected(Boolean.parseBoolean(properties.getProperty("view.deviation", "false")));
		}
		if (burstDetectCheck != null) {
			burstDetectCheck.setSelected(Boolean.parseBoolean(properties.getProperty("view.burst", "false")));
		}
		if (autoLevelCheck != null) {
			autoLevelCheck.setSelected(Boolean.parseBoolean(properties.getProperty("view.autoLevel", "true")));
		}
		if (audioEnableCheck != null) {
			audioEnableCheck.setSelected(Boolean.parseBoolean(properties.getProperty("audio.enabled", "false")));
		}
		if (audioModeCombo != null) {
			try {
				IQAudioOutput.Mode mode = IQAudioOutput.Mode.valueOf(properties.getProperty("audio.mode", "AM"));
				if (mode != IQAudioOutput.Mode.OFF) {
					audioModeCombo.setSelectedItem(mode);
				}
			} catch (IllegalArgumentException e) {
				audioModeCombo.setSelectedItem(IQAudioOutput.Mode.AM);
			}
		}
		if (audioVolumeSlider != null) {
			audioVolumeSlider.setValue(parsePropertyInt(properties, "audio.volume", 80));
			audioOutput.setVolumePercent(audioVolumeSlider.getValue());
		}
		if (audioToneSlider != null) {
			audioToneSlider.setValue(clamp(parsePropertyInt(properties, "audio.toneCutoffHz",
					IQAudioOutput.DEFAULT_TONE_CUTOFF_HZ), IQAudioOutput.MIN_TONE_CUTOFF_HZ,
					IQAudioOutput.MAX_TONE_CUTOFF_HZ));
			applyAudioToneCutoff();
		}
		if (triggerCheck != null) {
			triggerCheck.setSelected(Boolean.parseBoolean(properties.getProperty("trigger.enabled", "false")));
		}
		if (triggerPreCombo != null) {
			selectTriggerPre(parsePropertyInt(properties, "trigger.prePercent", 25));
		}
		if (triggerLevelSlider != null) {
			triggerLevelSlider.setValue(clamp(parsePropertyInt(properties, "trigger.level", 64),
					triggerLevelSlider.getMinimum(), triggerLevelSlider.getMaximum()));
		}
	}

	private void savePanelSettings(Properties properties) {
		if (envelopeCheck != null) {
			properties.setProperty("view.envelope", Boolean.toString(envelopeCheck.isSelected()));
		}
		if (deviationCheck != null) {
			properties.setProperty("view.deviation", Boolean.toString(deviationCheck.isSelected()));
		}
		if (burstDetectCheck != null) {
			properties.setProperty("view.burst", Boolean.toString(burstDetectCheck.isSelected()));
		}
		if (autoLevelCheck != null) {
			properties.setProperty("view.autoLevel", Boolean.toString(autoLevelCheck.isSelected()));
		}
		if (audioEnableCheck != null) {
			properties.setProperty("audio.enabled", Boolean.toString(audioEnableCheck.isSelected()));
		}
		if (audioModeCombo != null) {
			IQAudioOutput.Mode mode = (IQAudioOutput.Mode) audioModeCombo.getSelectedItem();
			properties.setProperty("audio.mode", mode == null ? IQAudioOutput.Mode.AM.name() : mode.name());
		}
		if (audioVolumeSlider != null) {
			properties.setProperty("audio.volume", Integer.toString(audioVolumeSlider.getValue()));
		}
		if (audioToneSlider != null) {
			properties.setProperty("audio.toneCutoffHz", Integer.toString(audioToneSlider.getValue()));
		}
		if (triggerCheck != null) {
			properties.setProperty("trigger.enabled", Boolean.toString(triggerCheck.isSelected()));
		}
		if (triggerPreCombo != null) {
			TriggerPreOption triggerPre = (TriggerPreOption) triggerPreCombo.getSelectedItem();
			properties.setProperty("trigger.prePercent", Integer.toString(triggerPre == null ? 25 : triggerPre.percent));
		}
		if (triggerLevelSlider != null) {
			properties.setProperty("trigger.level", Integer.toString(triggerLevelSlider.getValue()));
		}
	}

	private int parsePropertyInt(Properties properties, String key, int fallback) {
		try {
			return Integer.parseInt(properties.getProperty(key, "").trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private JPanel createControls(long centerFreqHz, int sampleRateHz, int lnaGain, int vgaGain, boolean rfAmp) {
		JPanel controls = new JPanel();
		controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
		controls.setBorder(new EmptyBorder(6, 8, 6, 8));
		controls.setBackground(PANEL_BG);
		controls.setForeground(TEXT_FG);

		centerField = new JTextField(formatFrequency(centerFreqHz), 6);
		centerField.addActionListener(e -> applyRfSettingsLive());
		centerField.addFocusListener(new FocusAdapter() {
			@Override
			public void focusLost(FocusEvent e) {
				applyRfSettingsLive();
			}
		});
		sampleRateCombo = new JComboBox<>(new RateOption[] {
				new RateOption("2 MS/s", 2_000_000),
				new RateOption("4 MS/s", 4_000_000),
				new RateOption("6 MS/s", 6_000_000),
				new RateOption("8 MS/s", 8_000_000),
				new RateOption("10 MS/s", 10_000_000),
				new RateOption("12.5 MS/s", 12_500_000),
				new RateOption("16 MS/s", 16_000_000),
				new RateOption("20 MS/s", 20_000_000)
		});
		selectRate(sampleRateHz);
		sampleRateCombo.addActionListener(e -> scheduleRfSettingsLiveApply());

		sampleViewCombo = new JComboBox<>(new SampleViewOption[] {
				new SampleViewOption("Custom", 0),
				new SampleViewOption("1k samples", 1024),
				new SampleViewOption("2k samples", 2048),
				new SampleViewOption("4k samples", 4096),
				new SampleViewOption("8k samples", 8192),
				new SampleViewOption("16k samples", 16384),
				new SampleViewOption("32k samples", 32768),
				new SampleViewOption("64k samples", 65536),
				new SampleViewOption("128k samples", 131072),
				new SampleViewOption("256k samples", 262144),
				new SampleViewOption("512k samples", 524288),
				new SampleViewOption("1M samples", 1048576),
				new SampleViewOption("2M samples", 2097152)
		});
		sampleViewCombo.setSelectedIndex(3);
		sampleViewCombo.addActionListener(e -> {
			SampleViewOption option = (SampleViewOption) sampleViewCombo.getSelectedItem();
			if (option != null && option.samples > 0) {
				timeDomainPanel.setVisibleSamples(option.samples);
			}
		});
		timeViewInfoLabel = new JLabel("");

		lnaSlider = createGainSlider(0, 40, 8, lnaGain);
		vgaSlider = createGainSlider(0, 62, 2, vgaGain);
		lnaValueLabel = createGainValueLabel(lnaSlider.getValue());
		vgaValueLabel = createGainValueLabel(vgaSlider.getValue());
		rfAmpCheck = new JCheckBox("RF amp");
		rfAmpCheck.setSelected(rfAmp);
		lnaSlider.addChangeListener(e -> {
			lnaValueLabel.setText(formatGainValue(lnaSlider.getValue()));
			scheduleRfSettingsLiveApply();
		});
		vgaSlider.addChangeListener(e -> {
			vgaValueLabel.setText(formatGainValue(vgaSlider.getValue()));
			scheduleRfSettingsLiveApply();
		});
		rfAmpCheck.addActionListener(e -> scheduleRfSettingsLiveApply());
		presetCombo = new JComboBox<>(new PresetOption[] {
				new PresetOption("Manual", 0, 0, 0),
				new PresetOption("Wide pulses", 0, 0, 1024),
				new PresetOption("GSM 200 kHz", 200_000, 250_000, 8192),
				new PresetOption("NFM 12.5 kHz", 12_500, 48_000, 4096),
				new PresetOption("NFM 25 kHz", 25_000, 96_000, 4096),
				new PresetOption("WFM 200 kHz", 200_000, 250_000, 8192),
				new PresetOption("Wide 1 MHz", 1_000_000, 1_000_000, 16384)
		});
		presetCombo.addActionListener(e -> applyPreset());
		viewModeCombo = new JComboBox<>(new ViewModeOption[] {
				new ViewModeOption("Wideband pulses", false),
				new ViewModeOption("Narrow channel", true)
		});
		viewModeCombo.setSelectedIndex(1);
		viewModeCombo.addActionListener(e -> applyDspSettingsLive());
		channelOffsetField = new JTextField("0", 7);
		channelOffsetField.addActionListener(e -> applyDspSettingsLive());
		channelOffsetField.addFocusListener(new FocusAdapter() {
			@Override
			public void focusLost(FocusEvent e) {
				applyDspSettingsLive();
			}
		});
		channelBandwidthCombo = new JComboBox<>(new BandwidthOption[] {
				new BandwidthOption("12.5 kHz", 12_500),
				new BandwidthOption("25 kHz", 25_000),
				new BandwidthOption("100 kHz", 100_000),
				new BandwidthOption("200 kHz", 200_000),
				new BandwidthOption("500 kHz", 500_000),
				new BandwidthOption("1 MHz", 1_000_000)
		});
		channelBandwidthCombo.setSelectedIndex(3);
		channelBandwidthCombo.addActionListener(e -> applyDspSettingsLive());
		outputRateCombo = new JComboBox<>(new OutputRateOption[] {
				new OutputRateOption("48 kS/s", 48_000),
				new OutputRateOption("96 kS/s", 96_000),
				new OutputRateOption("192 kS/s", 192_000),
				new OutputRateOption("250 kS/s", 250_000),
				new OutputRateOption("500 kS/s", 500_000),
				new OutputRateOption("1 MS/s", 1_000_000)
		});
		outputRateCombo.setSelectedIndex(3);
		outputRateCombo.addActionListener(e -> applyDspSettingsLive());
		envelopeCheck = new JCheckBox("Envelope");
		envelopeCheck.setSelected(true);
		deviationCheck = new JCheckBox("FSK dev");
		burstDetectCheck = new JCheckBox("Burst");
		autoLevelCheck = new JCheckBox("Auto level");
		autoLevelCheck.setSelected(true);
		audioEnableCheck = new JCheckBox("Audio");
		audioEnableCheck.addActionListener(e -> applyAudioSettingsLive());
		audioModeCombo = new JComboBox<>(new IQAudioOutput.Mode[] {
				IQAudioOutput.Mode.AM,
				IQAudioOutput.Mode.FM
		});
		audioModeCombo.setSelectedItem(IQAudioOutput.Mode.AM);
		audioModeCombo.addActionListener(e -> applyAudioSettingsLive());
		audioVolumeSlider = new JSlider(0, 100, 80);
		audioVolumeSlider.setPreferredSize(new java.awt.Dimension(100, 34));
		audioVolumeSlider.setMajorTickSpacing(50);
		audioVolumeSlider.setPaintTicks(true);
		audioVolumeSlider.setToolTipText("Audio output volume");
		audioVolumeSlider.addChangeListener(e -> audioOutput.setVolumePercent(audioVolumeSlider.getValue()));
		audioOutput.setVolumePercent(audioVolumeSlider.getValue());
		audioToneSlider = new JSlider(IQAudioOutput.MIN_TONE_CUTOFF_HZ, IQAudioOutput.MAX_TONE_CUTOFF_HZ,
				IQAudioOutput.DEFAULT_TONE_CUTOFF_HZ);
		audioToneSlider.setPreferredSize(new java.awt.Dimension(108, 30));
		audioToneSlider.setMajorTickSpacing(5_000);
		audioToneSlider.setMinorTickSpacing(1_000);
		audioToneSlider.setSnapToTicks(true);
		audioToneSlider.setPaintTicks(true);
		audioToneSlider.setToolTipText("Audio tone bandwidth cutoff");
		audioToneValueLabel = createAudioToneValueLabel(audioToneSlider.getValue());
		audioToneSlider.addChangeListener(e -> applyAudioToneCutoff());
		applyAudioToneCutoff();
		audioRecordButton = new JButton("Audio REC");
		iqRecordButton = new JButton("IQ REC");
		recordStatusLabel = new JLabel("");
		styleButton(audioRecordButton, Color.RED, Color.BLACK);
		styleButton(iqRecordButton, Color.RED, Color.BLACK);
		recordStatusLabel.setForeground(MUTED_FG);
		recordStatusLabel.setPreferredSize(new java.awt.Dimension(180, 22));
		recordStatusLabel.setMinimumSize(new java.awt.Dimension(180, 22));
		audioRecordButton.addActionListener(e -> toggleAudioRecording());
		iqRecordButton.addActionListener(e -> toggleIqRecording());
		triggerCheck = new JCheckBox("Trigger");
		triggerCheck.addActionListener(e -> timeDomainPanel.clearSingleTriggerHold());
		singleTriggerButton = new JButton("Single Arm");
		singleTriggerButton.addActionListener(e -> toggleSingleTrigger());
		triggerLevelSlider = new JSlider(1, 181, 64);
		triggerLevelSlider.setPreferredSize(new java.awt.Dimension(100, 34));
		triggerLevelSlider.setMajorTickSpacing(60);
		triggerLevelSlider.setPaintTicks(true);
		triggerLevelSlider.setToolTipText("Burst trigger threshold; lower is more sensitive");
		triggerPreCombo = new JComboBox<>(new TriggerPreOption[] {
				new TriggerPreOption("10%", 10),
				new TriggerPreOption("25%", 25),
				new TriggerPreOption("50%", 50),
				new TriggerPreOption("75%", 75)
		});
		triggerPreCombo.setSelectedIndex(1);
		restorePanelSettings();
		runStopButton = new JButton("Run");
		statusLabel = new JLabel("Idle");
		styleButton(runStopButton, START_BG, Color.BLACK);
		statusLabel.setForeground(TEXT_FG);

		runStopButton.addActionListener(e -> {
			if (streaming) {
				stopStream();
			} else {
				restartStream();
			}
		});

		JPanel rfSection = createSection("RF");
		addLabeledPair(rfSection, "Center", centerField, "Rate", sampleRateCombo);
		addGainSliderPair(rfSection);
		JPanel runRow = new JPanel(new BorderLayout(6, 0));
		runRow.setBackground(PANEL_BG);
		runRow.add(rfAmpCheck, BorderLayout.WEST);
		runRow.add(runStopButton, BorderLayout.CENTER);
		addWide(rfSection, runRow);
		addWide(rfSection, statusLabel);

		JPanel channelSection = createSection("Channel");
		addLabeled(channelSection, "Zoom", presetCombo);
		addLabeled(channelSection, "Mode", viewModeCombo);
		addLabeled(channelSection, "Offset", channelOffsetField);
		addLabeledPair(channelSection, "BW", channelBandwidthCombo, "Out", outputRateCombo);

		JPanel viewSection = createSection("View");
		addLabeled(viewSection, "Time view", sampleViewCombo);
		addWide(viewSection, timeViewInfoLabel);
		addInline(viewSection, envelopeCheck, deviationCheck, burstDetectCheck);
		addInline(viewSection, autoLevelCheck);

		JPanel audioSection = createSection("Audio");
		addInline(audioSection, audioEnableCheck, createAudioToneRow());
		addLabeled(audioSection, "Demod", audioModeCombo);
		addLabeled(audioSection, "Volume", audioVolumeSlider);
		addInline(audioSection, audioRecordButton, iqRecordButton);
		addWide(audioSection, recordStatusLabel);
		java.awt.Dimension audioPreferredSize = audioSection.getPreferredSize();
		audioSection.setMinimumSize(new java.awt.Dimension(220, audioPreferredSize.height));
		audioSection.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, audioPreferredSize.height));

		JPanel triggerSection = createSection("Trigger");
		addInline(triggerSection, triggerCheck, singleTriggerButton);
		addLabeled(triggerSection, "Pre", triggerPreCombo);
		addLabeled(triggerSection, "Threshold", triggerLevelSlider);

		controls.add(rfSection);
		controls.add(channelSection);
		controls.add(viewSection);
		controls.add(audioSection);
		controls.add(triggerSection);
		styleComponentTree(controls);
		styleRunStopButton();
		styleRecordButtons();
		styleSingleTriggerButton();
		java.awt.Dimension controlsPreferredSize = controls.getPreferredSize();
		controls.setPreferredSize(new java.awt.Dimension(280, controlsPreferredSize.height));

		updateButtons();
		syncTimeViewFromPanel();
		return controls;
	}

	private JPanel createSection(String title) {
		JPanel panel = new JPanel(new GridBagLayout());
		panel.setAlignmentX(Component.LEFT_ALIGNMENT);
		TitledBorder border = BorderFactory.createTitledBorder(BorderFactory.createLineBorder(Color.DARK_GRAY), title);
		border.setTitleColor(TEXT_FG);
		panel.setBorder(border);
		panel.setBackground(PANEL_BG);
		panel.setForeground(TEXT_FG);
		panel.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 130));
		panel.setMinimumSize(new java.awt.Dimension(220, 0));
		panel.putClientProperty("row", Integer.valueOf(0));
		return panel;
	}

	private void addLabeled(JPanel panel, String label, Component component) {
		int row = nextControlRow(panel);
		GridBagConstraints labelConstraints = new GridBagConstraints();
		labelConstraints.gridx = 0;
		labelConstraints.gridy = row;
		labelConstraints.anchor = GridBagConstraints.WEST;
		labelConstraints.insets = new java.awt.Insets(2, 4, 2, 6);
		JLabel labelComponent = new JLabel(label);
		labelComponent.setForeground(TEXT_FG);
		panel.add(labelComponent, labelConstraints);

		GridBagConstraints valueConstraints = new GridBagConstraints();
		valueConstraints.gridx = 1;
		valueConstraints.gridy = row;
		valueConstraints.gridwidth = GridBagConstraints.REMAINDER;
		valueConstraints.weightx = 1;
		valueConstraints.fill = GridBagConstraints.HORIZONTAL;
		valueConstraints.insets = new java.awt.Insets(2, 0, 2, 4);
		panel.add(component, valueConstraints);
	}

	private void addLabeledPair(JPanel panel, String leftLabel, Component leftComponent, String rightLabel,
			Component rightComponent) {
		int row = nextControlRow(panel);
		addInlineLabel(panel, leftLabel, 0, row);
		addInlineValue(panel, leftComponent, 1, row, 0.45d);
		if (rightLabel != null && rightLabel.length() > 0) {
			addInlineLabel(panel, rightLabel, 2, row);
		}
		addInlineValue(panel, rightComponent, 3, row, 0.55d, true);
	}

	private void addGainSliderPair(JPanel panel) {
		int row = nextControlRow(panel);
		GridBagConstraints lnaConstraints = new GridBagConstraints();
		lnaConstraints.gridx = 0;
		lnaConstraints.gridy = row;
		lnaConstraints.gridwidth = 2;
		lnaConstraints.weightx = 0.5d;
		lnaConstraints.fill = GridBagConstraints.HORIZONTAL;
		lnaConstraints.insets = new java.awt.Insets(2, 4, 2, 4);
		panel.add(createGainSliderPanel("LNA", lnaSlider, lnaValueLabel), lnaConstraints);

		GridBagConstraints vgaConstraints = new GridBagConstraints();
		vgaConstraints.gridx = 2;
		vgaConstraints.gridy = row;
		vgaConstraints.gridwidth = GridBagConstraints.REMAINDER;
		vgaConstraints.weightx = 0.5d;
		vgaConstraints.fill = GridBagConstraints.HORIZONTAL;
		vgaConstraints.insets = new java.awt.Insets(2, 4, 2, 4);
		panel.add(createGainSliderPanel("VGA", vgaSlider, vgaValueLabel), vgaConstraints);
	}

	private JPanel createGainSliderPanel(String label, JSlider slider, JLabel valueLabel) {
		JPanel panel = new JPanel(new BorderLayout(4, 0));
		panel.setBackground(PANEL_BG);
		panel.setForeground(TEXT_FG);
		JLabel labelComponent = new JLabel(label);
		labelComponent.setForeground(TEXT_FG);
		panel.add(labelComponent, BorderLayout.WEST);
		panel.add(slider, BorderLayout.CENTER);
		panel.add(valueLabel, BorderLayout.EAST);
		return panel;
	}

	private void addInline(JPanel panel, Component... components) {
		int row = nextControlRow(panel);
		for (int i = 0; i < components.length; i++) {
			GridBagConstraints constraints = new GridBagConstraints();
			constraints.gridx = i;
			constraints.gridy = row;
			if (i == components.length - 1) {
				constraints.gridwidth = GridBagConstraints.REMAINDER;
			}
			constraints.weightx = 1;
			constraints.fill = GridBagConstraints.HORIZONTAL;
			constraints.anchor = GridBagConstraints.WEST;
			constraints.insets = new java.awt.Insets(1, i == 0 ? 4 : 2, 1, 4);
			panel.add(components[i], constraints);
		}
	}

	private void addInlineLabel(JPanel panel, String label, int gridx, int row) {
		JLabel labelComponent = new JLabel(label);
		labelComponent.setForeground(TEXT_FG);
		GridBagConstraints constraints = new GridBagConstraints();
		constraints.gridx = gridx;
		constraints.gridy = row;
		constraints.anchor = GridBagConstraints.WEST;
		constraints.insets = new java.awt.Insets(2, gridx == 0 ? 4 : 6, 2, 3);
		panel.add(labelComponent, constraints);
	}

	private void addInlineValue(JPanel panel, Component component, int gridx, int row, double weightx) {
		addInlineValue(panel, component, gridx, row, weightx, false);
	}

	private void addInlineValue(JPanel panel, Component component, int gridx, int row, double weightx,
			boolean remainder) {
		GridBagConstraints constraints = new GridBagConstraints();
		constraints.gridx = gridx;
		constraints.gridy = row;
		if (remainder) {
			constraints.gridwidth = GridBagConstraints.REMAINDER;
		}
		constraints.weightx = weightx;
		constraints.fill = GridBagConstraints.HORIZONTAL;
		constraints.insets = new java.awt.Insets(2, 0, 2, 4);
		panel.add(component, constraints);
	}

	private void addWide(JPanel panel, Component component) {
		int row = nextControlRow(panel);
		GridBagConstraints constraints = new GridBagConstraints();
		constraints.gridx = 0;
		constraints.gridy = row;
		constraints.gridwidth = GridBagConstraints.REMAINDER;
		constraints.weightx = 1;
		constraints.fill = GridBagConstraints.HORIZONTAL;
		constraints.anchor = GridBagConstraints.WEST;
		constraints.insets = new java.awt.Insets(2, 4, 2, 4);
		panel.add(component, constraints);
	}

	private int nextControlRow(JPanel panel) {
		Object rowObject = panel.getClientProperty("row");
		int row = rowObject instanceof Integer ? ((Integer) rowObject).intValue() : 0;
		panel.putClientProperty("row", Integer.valueOf(row + 1));
		return row;
	}

	private void styleComponentTree(Component component) {
		if (component instanceof JPanel) {
			component.setBackground(PANEL_BG);
			component.setForeground(TEXT_FG);
		} else if (component instanceof JLabel) {
			component.setForeground(TEXT_FG);
		} else if (component instanceof JCheckBox) {
			component.setBackground(PANEL_BG);
			component.setForeground(TEXT_FG);
		} else if (component instanceof JTextField) {
			component.setBackground(CONTROL_BG);
			component.setForeground(TEXT_FG);
			((JTextField) component).setCaretColor(TEXT_FG);
			component.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, component.getPreferredSize().height));
		} else if (component instanceof JComboBox) {
			styleLightControl(component);
			return;
		} else if (component instanceof JSpinner) {
			styleLightControl(component);
			styleSpinnerEditor((JSpinner) component);
			return;
		} else if (component instanceof JSlider) {
			component.setBackground(PANEL_BG);
			component.setForeground(TEXT_FG);
		}

		if (component instanceof Container) {
			for (Component child : ((Container) component).getComponents()) {
				styleComponentTree(child);
			}
		}
	}

	private void styleLightControl(Component component) {
		component.setBackground(Color.WHITE);
		component.setForeground(Color.BLACK);
		component.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, component.getPreferredSize().height));
	}

	private void styleSpinnerEditor(JSpinner spinner) {
		Component editor = spinner.getEditor();
		styleLightControl(editor);
		if (editor instanceof Container) {
			for (Component child : ((Container) editor).getComponents()) {
				styleLightControl(child);
				if (child instanceof JTextField) {
					((JTextField) child).setCaretColor(Color.BLACK);
				}
			}
		}
	}

	private JSlider createGainSlider(int min, int max, int step, int value) {
		JSlider slider = new JSlider(JSlider.HORIZONTAL, min, max, clamp(value, min, max));
		slider.setSnapToTicks(true);
		slider.setMinorTickSpacing(step);
		slider.setBackground(PANEL_BG);
		slider.setForeground(TEXT_FG);
		slider.setFont(new Font("Monospaced", Font.BOLD, 14));
		slider.setPreferredSize(new java.awt.Dimension(150, 28));
		return slider;
	}

	private JLabel createGainValueLabel(int value) {
		JLabel label = new JLabel(formatGainValue(value));
		label.setForeground(TEXT_FG);
		label.setFont(new Font("Monospaced", Font.BOLD, 13));
		label.setHorizontalAlignment(JLabel.RIGHT);
		label.setPreferredSize(new java.awt.Dimension(44, 22));
		return label;
	}

	private JLabel createAudioToneValueLabel(int value) {
		JLabel label = new JLabel(formatAudioToneValue(value));
		label.setForeground(TEXT_FG);
		label.setHorizontalAlignment(JLabel.RIGHT);
		label.setPreferredSize(new java.awt.Dimension(48, 22));
		return label;
	}

	private JPanel createAudioToneRow() {
		JPanel panel = new JPanel(new BorderLayout(4, 0));
		panel.setBackground(PANEL_BG);
		JLabel label = new JLabel("BW");
		label.setForeground(TEXT_FG);
		panel.add(label, BorderLayout.WEST);
		panel.add(audioToneSlider, BorderLayout.CENTER);
		panel.add(audioToneValueLabel, BorderLayout.EAST);
		return panel;
	}

	private void applyAudioToneCutoff() {
		if (audioToneSlider == null) {
			return;
		}
		audioOutput.setToneCutoffHz(audioToneSlider.getValue());
		if (audioToneValueLabel != null) {
			audioToneValueLabel.setText(formatAudioToneValue(audioOutput.getToneCutoffHz()));
		}
	}

	private String formatGainValue(int gain) {
		return gain + " dB";
	}

	private String formatAudioToneValue(int cutoffHz) {
		return Math.round(cutoffHz / 1000f) + "kHz";
	}

	private int clamp(int value, int min, int max) {
		if (value < min) {
			return min;
		}
		if (value > max) {
			return max;
		}
		return value;
	}

	private void styleButton(JButton button, Color background, Color foreground) {
		button.setBackground(background);
		button.setForeground(foreground);
		button.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
		button.setFocusPainted(false);
		button.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, button.getPreferredSize().height));
	}

	private void styleRunStopButton() {
		if (runStopButton == null) {
			return;
		}
		styleButton(runStopButton, streaming ? STOP_BG : START_BG, Color.BLACK);
		runStopButton.setText(streaming ? "Stop" : "Run");
	}

	private void styleRecordButtons() {
		if (audioRecordButton != null) {
			styleButton(audioRecordButton, audioRecorder == null ? Color.RED : STOP_BG, Color.BLACK);
			audioRecordButton.setText(audioRecorder == null ? "Audio REC" : "STOP Audio");
		}
		if (iqRecordButton != null) {
			styleButton(iqRecordButton, iqRecorder == null ? Color.RED : STOP_BG, Color.BLACK);
			iqRecordButton.setText(iqRecorder == null ? "IQ REC" : "STOP IQ");
		}
	}

	private void styleSingleTriggerButton() {
		if (singleTriggerButton == null) {
			return;
		}
		if (!singleTriggerArmed) {
			styleButton(singleTriggerButton, START_BG, Color.BLACK);
			singleTriggerButton.setText("Single Arm");
		} else {
			styleButton(singleTriggerButton, new Color(0xff9f1c), Color.BLACK);
			singleTriggerButton.setText("Cancel Single");
		}
	}

	private void toggleSingleTrigger() {
		if (!singleTriggerArmed) {
			singleTriggerArmed = true;
			timeDomainPanel.clearSingleTriggerHold();
		} else {
			singleTriggerArmed = false;
			timeDomainPanel.clearSingleTriggerHold();
		}
		if (timeDomainPanel != null) {
			timeDomainPanel.setSingleTrigger(singleTriggerArmed);
		}
		styleSingleTriggerButton();
	}

	private void toggleAudioRecording() {
		if (audioRecorder != null) {
			stopAudioRecording();
			return;
		}
		if (audioEnableCheck == null || !audioEnableCheck.isSelected()) {
			setStatus("Audio is off", true);
			return;
		}
		try {
			WavFileWriter recorder = new WavFileWriter(createRecordingFile("AUDIO", getSelectedRecordingBandwidthHz()), 48_000, 2, 16);
			audioRecorder = recorder;
			audioOutput.setRecorder(recorder);
			styleRecordButtons();
			updateRecordingStatus();
		} catch (IOException e) {
			setStatus("Audio REC failed", true);
		}
	}

	private void toggleIqRecording() {
		if (iqRecorder != null) {
			stopIqRecording();
			return;
		}
		try {
			iqRecorderSampleRateHz = Math.max(1, (int) latestSampleRateHz.get());
			iqRecorder = new WavFileWriter(createRecordingFile("IQ", iqRecorderSampleRateHz), iqRecorderSampleRateHz, 2, 8);
			styleRecordButtons();
			updateRecordingStatus();
		} catch (IOException e) {
			setStatus("IQ REC failed", true);
		}
	}

	private void stopRecordings() {
		stopAudioRecording();
		stopIqRecording();
	}

	private void stopAudioRecording() {
		WavFileWriter recorder = audioRecorder;
		audioRecorder = null;
		audioOutput.setRecorder(null);
		closeRecorder(recorder);
		styleRecordButtons();
		updateRecordingStatus();
	}

	private void stopIqRecording() {
		WavFileWriter recorder = iqRecorder;
		iqRecorder = null;
		closeRecorder(recorder);
		styleRecordButtons();
		updateRecordingStatus();
	}

	private void closeRecorder(WavFileWriter recorder) {
		if (recorder == null) {
			return;
		}
		try {
			recorder.close();
		} catch (IOException e) {
			setStatus("REC close failed", true);
		}
	}

	private File createRecordingFile(String prefix, int bandwidthHz) {
		long centerHz = getSelectedRecordingCenterFreqHz();
		bandwidthHz = Math.max(1, bandwidthHz);
		LongSupplier timeSupplier = recordingTimeMillisSupplier;
		long timestampMillis = timeSupplier == null ? System.currentTimeMillis() : timeSupplier.getAsLong();
		LocalDateTime recordingTime = timestampMillis > 0
				? LocalDateTime.ofInstant(Instant.ofEpochMilli(timestampMillis), ZoneId.systemDefault())
				: LocalDateTime.now();
		String timestamp = recordingTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss"));
		return new File(String.format(Locale.US, "# %s %dHz BW-%s %s.wav", prefix, centerHz,
				formatRecordingBandwidth(bandwidthHz), timestamp));
	}

	private long getSelectedRecordingCenterFreqHz() {
		long centerHz = latestCenterFreqHz.get();
		ViewModeOption viewMode = viewModeCombo == null ? null : (ViewModeOption) viewModeCombo.getSelectedItem();
		if (viewMode == null || !viewMode.channel) {
			return centerHz;
		}
		return Math.max(0, centerHz + getCurrentChannelOffsetHz());
	}

	private int getSelectedRecordingBandwidthHz() {
		int bandwidthHz = getSelectedChannelBandwidthHz();
		return bandwidthHz <= 0 ? Math.max(1, (int) latestSampleRateHz.get()) : bandwidthHz;
	}

	private String formatRecordingBandwidth(int bandwidthHz) {
		if (bandwidthHz % 1_000_000 == 0) {
			return (bandwidthHz / 1_000_000) + "M";
		}
		if (bandwidthHz >= 1_000_000) {
			return String.format(Locale.US, "%.3fM", bandwidthHz / 1_000_000d);
		}
		if (bandwidthHz % 1000 == 0) {
			return (bandwidthHz / 1000) + "k";
		}
		if (bandwidthHz >= 1000) {
			return String.format(Locale.US, "%.1fk", bandwidthHz / 1000d);
		}
		return Integer.toString(bandwidthHz);
	}

	private void updateRecordingStatus() {
		if (recordStatusLabel == null) {
			return;
		}
		StringBuilder text = new StringBuilder();
		WavFileWriter audio = audioRecorder;
		WavFileWriter iq = iqRecorder;
		if (audio != null) {
			text.append("Audio ").append(formatBytes(audio.getDataBytes()));
		}
		if (iq != null) {
			if (text.length() > 0) {
				text.append("   ");
			}
			text.append("IQ ").append(formatBytes(iq.getDataBytes()));
		}
		recordStatusLabel.setText(text.toString());
	}

	private String formatBytes(long bytes) {
		if (bytes >= 1024L * 1024L * 1024L) {
			return String.format(Locale.US, "%.2f GB", bytes / (1024d * 1024d * 1024d));
		}
		if (bytes >= 1024L * 1024L) {
			return String.format(Locale.US, "%.1f MB", bytes / (1024d * 1024d));
		}
		if (bytes >= 1024L) {
			return String.format(Locale.US, "%.1f kB", bytes / 1024d);
		}
		return bytes + " B";
	}

	private void restartStream() {
		stopStream(false);
		startStream();
	}

	private void scheduleRfSettingsLiveApply() {
		if (externalSource) {
			return;
		}
		if (!streaming) {
			return;
		}
		if (rfRestartTimer == null) {
			rfRestartTimer = new Timer(250, e -> applyRfSettingsLive());
			rfRestartTimer.setRepeats(false);
		}
		rfRestartTimer.restart();
		setStatus("RF pending", false);
	}

	private void applyRfSettingsLive() {
		if (externalSource) {
			centerField.setText(formatFrequency(externalCenterFreqHz));
			selectRate(externalSampleRateHz);
			return;
		}
		if (centerField == null) {
			return;
		}
		long centerFreqHz;
		int sampleRateHz;
		int lnaGain;
		int vgaGain;
		boolean rfAmp;
		try {
			centerFreqHz = parseFrequencyHz(centerField.getText());
			RateOption rate = (RateOption) sampleRateCombo.getSelectedItem();
			if (rate == null) {
				throw new IllegalArgumentException("Missing sample rate");
			}
			sampleRateHz = rate.sampleRateHz;
			lnaGain = lnaSlider.getValue();
			vgaGain = vgaSlider.getValue();
			rfAmp = rfAmpCheck.isSelected();
		} catch (RuntimeException e) {
			setStatus("Invalid RF settings", true);
			return;
		}
		centerField.setText(formatFrequency(centerFreqHz));
		if (streaming && centerFreqHz == activeCenterFreqHz && sampleRateHz == activeRfSampleRateHz
				&& lnaGain == activeLnaGain && vgaGain == activeVgaGain && rfAmp == activeRfAmp) {
			return;
		}
		if (streaming) {
			restartStream();
			setStatus("RF applied", false);
		}
	}

	private void startStream() {
		if (streaming) {
			return;
		}
		if (externalSource) {
			blocks.set(0);
			bytes.set(0);
			activeRawSampleRateHz = externalSampleRateHz;
			activeCenterFreqHz = externalCenterFreqHz;
			activeLowIfShiftHz = 0;
			activeRfSampleRateHz = externalSampleRateHz;
			latestCenterFreqHz.set(externalCenterFreqHz);
			startedNanos = System.nanoTime();
			configureDspPipeline(externalSampleRateHz, true);
			streaming = true;
			updateButtons();
			setStatus("Running", false);
			return;
		}

		long centerFreqHz;
		long hardwareCenterFreqHz;
		long lowIfShiftHz;
		int sampleRateHz;
		int displayRateHz;
		int lnaGain;
		int vgaGain;
		boolean rfAmp;
		try {
			centerFreqHz = parseFrequencyHz(centerField.getText());
			RateOption rate = (RateOption) sampleRateCombo.getSelectedItem();
			sampleRateHz = rate == null ? DEFAULT_SAMPLE_RATE_HZ : rate.sampleRateHz;
			displayRateHz = calculateDisplayRateHz(sampleRateHz);
			lnaGain = lnaSlider.getValue();
			vgaGain = vgaSlider.getValue();
			rfAmp = rfAmpCheck.isSelected();
			lowIfShiftHz = calculateLowIfShiftHz(sampleRateHz);
			hardwareCenterFreqHz = centerFreqHz - lowIfShiftHz;
		} catch (RuntimeException e) {
			setStatus("Invalid settings", true);
			return;
		}

		blocks.set(0);
		bytes.set(0);
		activeRawSampleRateHz = sampleRateHz;
		activeCenterFreqHz = centerFreqHz;
		activeLowIfShiftHz = lowIfShiftHz;
		activeRfSampleRateHz = sampleRateHz;
		activeLnaGain = lnaGain;
		activeVgaGain = vgaGain;
		activeRfAmp = rfAmp;
		final int streamId = ++streamGeneration;
		latestCenterFreqHz.set(centerFreqHz);
		latestSampleRateHz.set(displayRateHz);
		latestDecimation.set(calculateDecimation(sampleRateHz));
		startedNanos = System.nanoTime();
		configureDspPipeline(sampleRateHz, true);
		streaming = true;
		updateButtons();
		setStatus(lowIfShiftHz == 0 ? "Starting" : "Starting Low-IF", false);

		iqThread = new Thread(() -> {
			int result = HackRFIQNativeBridge.start((callbackCenterFreqHz, callbackSampleRateHz, iqData) -> {
				if (streamId != streamGeneration) {
					return;
				}
				acceptIQBlock(centerFreqHz, callbackSampleRateHz, iqData, iqData.length, false);
			}, hardwareCenterFreqHz, sampleRateHz, DEFAULT_BASEBAND_FILTER_HZ, lnaGain, vgaGain, rfAmp);

			SwingUtilities.invokeLater(() -> {
				if (streamId != streamGeneration) {
					return;
				}
				streaming = false;
				iqThread = null;
				if (result != 0) {
					audioOutput.stop();
				}
				updateButtons();
				if (result == 0) {
					setStatus("Stopped", false);
				} else {
					setStatus("Native error " + result, true);
				}
			});
		}, "hackrf-iq-analyzer");
		iqThread.setDaemon(true);
		iqThread.start();
	}

	private void acceptIQBlock(long centerFreqHz, int sampleRateHz, byte[] iqData, int length, boolean inputOwned) {
		IQRingBuffer activeRingBuffer = ringBuffer;
		if (activeRingBuffer == null || length < 2) {
			return;
		}
		IQChannelProcessor processor = channelProcessor;
		if (processor == null) {
			byte[] recordingData = iqData;
			int recordingLength = length;
			IQFrequencyShifter shifter = wideLowIfShifter;
			if (shifter != null) {
				if (channelOutput.length < length) {
					channelOutput = new byte[length];
				}
				recordingLength = shifter.process(iqData, length, channelOutput);
				recordingData = channelOutput;
			}
			byte[] copy;
			if (inputOwned && recordingData == iqData) {
				copy = iqData;
				applyAutoLevel(copy, recordingLength);
			} else {
				copy = copyAndLevel(recordingData, recordingLength);
			}
			timeDomainPanel.offerTriggerSamples(copy, recordingLength);
			activeRingBuffer.write(copy, recordingLength);
			writeIqRecording(copy, recordingLength);
			audioOutput.acceptIQ(copy, recordingLength);
			latestSampleRateHz.set(sampleRateHz);
			latestDecimation.set(1);
		} else {
			int maximumOutputBytes = Math.max(2, length);
			if (channelOutput.length < maximumOutputBytes) {
				channelOutput = new byte[maximumOutputBytes];
			}
			int processedBytes = processor.process(iqData, length, channelOutput);
			if (processedBytes > 0) {
				byte[] copy = new byte[processedBytes];
				System.arraycopy(channelOutput, 0, copy, 0, processedBytes);
				applyAutoLevel(copy, copy.length);
				timeDomainPanel.offerTriggerSamples(copy, copy.length);
				activeRingBuffer.write(copy);
				writeIqRecording(copy, copy.length);
				audioOutput.acceptIQ(copy, copy.length);
			}
			latestSampleRateHz.set(processor.getActualOutputRateHz());
			latestDecimation.set(processor.getDecimation());
		}
		blocks.incrementAndGet();
		bytes.addAndGet(length);
		latestCenterFreqHz.set(centerFreqHz);
	}

	private byte[] copyAndLevel(byte[] iqData, int length) {
		byte[] copy = new byte[length];
		System.arraycopy(iqData, 0, copy, 0, length);
		applyAutoLevel(copy, copy.length);
		return copy;
	}

	private void applyAutoLevel(byte[] iqData, int length) {
		if (autoLevelCheck != null && autoLevelCheck.isSelected()) {
			autoLevel.process(iqData, length);
		}
	}

	private void writeIqRecording(byte[] iqData, int length) {
		WavFileWriter recorder = iqRecorder;
		if (recorder == null || iqData == null || length <= 0) {
			return;
		}
		if (iqRecordBuffer.length < length) {
			iqRecordBuffer = new byte[length];
		}
		for (int i = 0; i < length; i++) {
			iqRecordBuffer[i] = (byte) ((iqData[i] + 128) & 0xff);
		}
		try {
			recorder.write(iqRecordBuffer, 0, length);
		} catch (IOException e) {
			iqRecorder = null;
			SwingUtilities.invokeLater(() -> {
				styleRecordButtons();
				updateRecordingStatus();
				setStatus("IQ REC failed", true);
			});
		}
	}

	private void stopStream() {
		stopStream(true);
	}

	private void stopStream(boolean updateStatus) {
		if (!streaming && iqThread == null) {
			updateButtons();
			return;
		}
		streamGeneration++;
		if (updateStatus) {
			setStatus("Stopping", false);
		}
		if (!externalSource) {
			HackRFIQNativeBridge.stop();
		}
		audioOutput.stop();
		Thread thread = iqThread;
		iqThread = null;
		if (thread != null && thread != Thread.currentThread()) {
			try {
				thread.join(1200);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		streaming = false;
		updateButtons();
		if (updateStatus) {
			setStatus("Stopped", false);
		}
	}

	private void applyDspSettingsLive() {
		if (!streaming) {
			return;
		}
		try {
			long lowIfShiftHz = externalSource ? 0 : calculateLowIfShiftHz(activeRawSampleRateHz);
			if (lowIfShiftHz != activeLowIfShiftHz) {
				restartStream();
				setStatus(lowIfShiftHz == 0 ? "DSP applied" : "Low-IF applied", false);
				return;
			}
			configureDspPipeline(activeRawSampleRateHz, true);
			setStatus("DSP applied", false);
		} catch (RuntimeException e) {
			setStatus("Invalid DSP settings", true);
		}
	}

	private void applyAudioSettingsLive() {
		if (!streaming) {
			return;
		}
		audioOutput.start(getSelectedAudioMode(), (int) latestSampleRateHz.get());
	}

	private void adjustOffsetFromSpectrum(double deltaHz) {
		if (channelOffsetField == null || viewModeCombo == null) {
			return;
		}
		ViewModeOption viewMode = (ViewModeOption) viewModeCombo.getSelectedItem();
		if (viewMode == null || !viewMode.channel) {
			return;
		}
		if (Double.isInfinite(deltaHz)) {
			applyDspSettingsLive();
			spectrumDragBaseOffsetHz = null;
			return;
		}
		if (Double.isNaN(deltaHz)) {
			try {
				spectrumDragBaseOffsetHz = Long.valueOf(parseFrequencyHz(channelOffsetField.getText()));
			} catch (RuntimeException e) {
				spectrumDragBaseOffsetHz = Long.valueOf(0);
			}
			return;
		}
		try {
			long baseOffsetHz = spectrumDragBaseOffsetHz == null ? parseFrequencyHz(channelOffsetField.getText())
					: spectrumDragBaseOffsetHz.longValue();
			long nextOffsetHz = Math.round(baseOffsetHz - deltaHz);
			channelOffsetField.setText(formatOffset(nextOffsetHz));
			if (!streaming) {
				applyDspSettingsLive();
			}
		} catch (RuntimeException e) {
			setStatus("Invalid offset", true);
		}
	}

	private void configureDspPipeline(int rawSampleRateHz, boolean resetBuffer) {
		ViewModeOption viewMode = (ViewModeOption) viewModeCombo.getSelectedItem();
		boolean channelMode = viewMode != null && viewMode.channel;
		int displayRateHz = calculateDisplayRateHz(rawSampleRateHz);
		IQChannelProcessor newProcessor = null;
		IQFrequencyShifter newWideLowIfShifter = null;
		if (channelMode) {
			double channelOffsetHz = parseFrequencyHz(channelOffsetField.getText()) + activeLowIfShiftHz;
			BandwidthOption bandwidth = (BandwidthOption) channelBandwidthCombo.getSelectedItem();
			int channelBandwidthHz = bandwidth == null ? 200_000 : bandwidth.bandwidthHz;
			OutputRateOption outputRate = (OutputRateOption) outputRateCombo.getSelectedItem();
			int outputRateHz = outputRate == null ? 250_000 : outputRate.outputRateHz;
			boolean externalPassthrough = externalSource && channelOffsetHz == 0
					&& channelBandwidthHz >= externalContentBandwidthHz
					&& outputRateHz >= rawSampleRateHz;
			if (!externalPassthrough) {
				newProcessor = new IQChannelProcessor(rawSampleRateHz, channelOffsetHz, channelBandwidthHz, outputRateHz);
				displayRateHz = newProcessor.getActualOutputRateHz();
			}
		} else if (activeLowIfShiftHz != 0) {
			newWideLowIfShifter = new IQFrequencyShifter(rawSampleRateHz, activeLowIfShiftHz);
		}

		if (resetBuffer || ringBuffer == null) {
			IQRingBuffer newRingBuffer = new IQRingBuffer(createBufferBytes(displayRateHz));
			ringBuffer = newRingBuffer;
			timeDomainPanel.setRingBuffer(newRingBuffer);
			spectrumPanel.setRingBuffer(newRingBuffer);
		}
		channelProcessor = newProcessor;
		wideLowIfShifter = newWideLowIfShifter;
		latestSampleRateHz.set(displayRateHz);
		latestDecimation.set(newProcessor == null ? 1 : newProcessor.getDecimation());

		audioOutput.start(getSelectedAudioMode(), displayRateHz);
		autoLevel.reset();
	}

	private IQAudioOutput.Mode getSelectedAudioMode() {
		if (audioEnableCheck == null || !audioEnableCheck.isSelected()) {
			return IQAudioOutput.Mode.OFF;
		}
		IQAudioOutput.Mode audioMode = (IQAudioOutput.Mode) audioModeCombo.getSelectedItem();
		return audioMode == null ? IQAudioOutput.Mode.AM : audioMode;
	}

	private int calculateDisplayRateHz(int rawSampleRateHz) {
		ViewModeOption viewMode = (ViewModeOption) viewModeCombo.getSelectedItem();
		if (viewMode == null || !viewMode.channel) {
			return rawSampleRateHz;
		}
		OutputRateOption outputRate = (OutputRateOption) outputRateCombo.getSelectedItem();
		int outputRateHz = outputRate == null ? 250_000 : outputRate.outputRateHz;
		return rawSampleRateHz / Math.max(1, Math.round(rawSampleRateHz / (float) outputRateHz));
	}

	private int getSelectedChannelBandwidthHz() {
		ViewModeOption viewMode = (ViewModeOption) viewModeCombo.getSelectedItem();
		if (viewMode == null || !viewMode.channel) {
			return 0;
		}
		BandwidthOption bandwidth = (BandwidthOption) channelBandwidthCombo.getSelectedItem();
		return bandwidth == null ? 0 : bandwidth.bandwidthHz;
	}

	private long calculateLowIfShiftHz(int rawSampleRateHz) {
		if (viewModeCombo == null) {
			return 0;
		}
		ViewModeOption viewMode = (ViewModeOption) viewModeCombo.getSelectedItem();
		if (viewMode == null) {
			return 0;
		}
		if (!viewMode.channel) {
			return calculateWideLowIfShiftHz(rawSampleRateHz);
		}
		if (channelOffsetField == null) {
			return 0;
		}
		long channelOffsetHz = parseFrequencyHz(channelOffsetField.getText());
		int bandwidthHz = getSelectedChannelBandwidthHz();
		long avoidHz = calculateLowIfAvoidHz(rawSampleRateHz, bandwidthHz);
		if (avoidHz <= 0 || Math.abs(channelOffsetHz) >= avoidHz) {
			return 0;
		}
		long shiftHz = channelOffsetHz < 0 ? -avoidHz : avoidHz;
		long requestedCenterHz = centerField == null ? activeCenterFreqHz : parseFrequencyHz(centerField.getText());
		if (requestedCenterHz - shiftHz <= 0) {
			shiftHz = -shiftHz;
		}
		return shiftHz;
	}

	private long calculateWideLowIfShiftHz(int rawSampleRateHz) {
		long maxShiftHz = rawSampleRateHz / 2L - WIDE_DC_AVOID_GUARD_HZ;
		if (maxShiftHz <= 0) {
			return 0;
		}
		long shiftHz = Math.min(WIDE_DC_AVOID_HZ, maxShiftHz);
		long requestedCenterHz = centerField == null ? activeCenterFreqHz : parseFrequencyHz(centerField.getText());
		if (requestedCenterHz - shiftHz <= 0) {
			shiftHz = -shiftHz;
		}
		return shiftHz;
	}

	private long calculateLowIfAvoidHz(int rawSampleRateHz, int bandwidthHz) {
		long halfBandwidthHz = Math.max(0, bandwidthHz) / 2L;
		long avoidHz = Math.max(NARROW_DC_AVOID_MIN_HZ, halfBandwidthHz + NARROW_DC_AVOID_GUARD_HZ);
		long maxOffsetHz = Math.max(0, rawSampleRateHz / 2L - halfBandwidthHz - NARROW_DC_AVOID_GUARD_HZ);
		if (maxOffsetHz <= 0) {
			return 0;
		}
		return Math.min(avoidHz, maxOffsetHz);
	}

	private long getCurrentChannelOffsetHz() {
		if (channelOffsetField == null || viewModeCombo == null) {
			return 0;
		}
		ViewModeOption viewMode = (ViewModeOption) viewModeCombo.getSelectedItem();
		if (viewMode == null || !viewMode.channel) {
			return 0;
		}
		try {
			return parseFrequencyHz(channelOffsetField.getText());
		} catch (RuntimeException e) {
			return 0;
		}
	}

	private int calculateDecimation(int rawSampleRateHz) {
		ViewModeOption viewMode = (ViewModeOption) viewModeCombo.getSelectedItem();
		if (viewMode == null || !viewMode.channel) {
			return 1;
		}
		OutputRateOption outputRate = (OutputRateOption) outputRateCombo.getSelectedItem();
		int outputRateHz = outputRate == null ? 250_000 : outputRate.outputRateHz;
		return Math.max(1, Math.round(rawSampleRateHz / (float) outputRateHz));
	}

	private void updateButtons() {
		if (runStopButton == null) {
			return;
		}
		runStopButton.setEnabled(true);
		styleRunStopButton();
	}

	private void setStatus(String text, boolean error) {
		statusLabel.setText(text);
		statusLabel.setForeground(error ? new Color(0xff6666) : MUTED_FG);
	}

	private int createBufferBytes(int sampleRateHz) {
		return Math.max(sampleRateHz * 2 * DEFAULT_BUFFER_SECONDS, DEFAULT_VISIBLE_SAMPLES * 2);
	}

	private void selectRate(int sampleRateHz) {
		for (int i = 0; i < sampleRateCombo.getItemCount(); i++) {
			if (sampleRateCombo.getItemAt(i).sampleRateHz == sampleRateHz) {
				sampleRateCombo.setSelectedIndex(i);
				return;
			}
		}
	}

	private void selectExactRate(int sampleRateHz) {
		selectRate(sampleRateHz);
		RateOption selected = (RateOption) sampleRateCombo.getSelectedItem();
		if (selected != null && selected.sampleRateHz == sampleRateHz) {
			return;
		}
		sampleRateCombo.addItem(new RateOption(formatSampleRateLabel(sampleRateHz), sampleRateHz));
		sampleRateCombo.setSelectedIndex(sampleRateCombo.getItemCount() - 1);
	}

	private void applyPreset() {
		PresetOption preset = (PresetOption) presetCombo.getSelectedItem();
		if (preset == null) {
			return;
		}
		envelopeCheck.setSelected(true);
		if (preset.bandwidthHz == 0) {
			viewModeCombo.setSelectedIndex(0);
			deviationCheck.setSelected(false);
			selectSampleView(preset.visibleSamples);
			return;
		}
		viewModeCombo.setSelectedIndex(1);
		deviationCheck.setSelected(false);
		selectBandwidth(preset.bandwidthHz);
		selectOutputRate(preset.outputRateHz);
		selectSampleView(preset.visibleSamples);
	}

	private void selectBandwidth(int bandwidthHz) {
		for (int i = 0; i < channelBandwidthCombo.getItemCount(); i++) {
			if (channelBandwidthCombo.getItemAt(i).bandwidthHz == bandwidthHz) {
				channelBandwidthCombo.setSelectedIndex(i);
				return;
			}
		}
		channelBandwidthCombo.addItem(new BandwidthOption(formatBandwidthLabel(bandwidthHz), bandwidthHz));
		channelBandwidthCombo.setSelectedIndex(channelBandwidthCombo.getItemCount() - 1);
	}

	private void selectOutputRate(int outputRateHz) {
		int bestIndex = -1;
		for (int i = 0; i < outputRateCombo.getItemCount(); i++) {
			if (outputRateCombo.getItemAt(i).outputRateHz == outputRateHz) {
				outputRateCombo.setSelectedIndex(i);
				return;
			}
			if (outputRateCombo.getItemAt(i).outputRateHz >= outputRateHz && bestIndex < 0) {
				bestIndex = i;
			}
		}
		if (bestIndex >= 0) {
			outputRateCombo.setSelectedIndex(bestIndex);
		} else if (outputRateCombo.getItemCount() > 0) {
			outputRateCombo.setSelectedIndex(outputRateCombo.getItemCount() - 1);
		}
	}

	private void selectExactOutputRate(int outputRateHz) {
		for (int i = 0; i < outputRateCombo.getItemCount(); i++) {
			if (outputRateCombo.getItemAt(i).outputRateHz == outputRateHz) {
				outputRateCombo.setSelectedIndex(i);
				return;
			}
		}
		outputRateCombo.addItem(new OutputRateOption(formatSampleRateLabel(outputRateHz), outputRateHz));
		outputRateCombo.setSelectedIndex(outputRateCombo.getItemCount() - 1);
	}

	private String formatSampleRateLabel(int sampleRateHz) {
		if (sampleRateHz >= 1_000_000) {
			return String.format(Locale.US, "%.3g MS/s", sampleRateHz / 1_000_000d);
		}
		if (sampleRateHz >= 1000) {
			return String.format(Locale.US, "%.3g kS/s", sampleRateHz / 1000d);
		}
		return sampleRateHz + " S/s";
	}

	private String formatBandwidthLabel(int bandwidthHz) {
		if (bandwidthHz >= 1_000_000) {
			return String.format(Locale.US, "%.3g MHz", bandwidthHz / 1_000_000d);
		}
		if (bandwidthHz >= 1000) {
			return String.format(Locale.US, "%.3g kHz", bandwidthHz / 1000d);
		}
		return bandwidthHz + " Hz";
	}

	private void selectSampleView(int samples) {
		for (int i = 0; i < sampleViewCombo.getItemCount(); i++) {
			if (sampleViewCombo.getItemAt(i).samples == samples) {
				sampleViewCombo.setSelectedIndex(i);
				return;
			}
		}
	}

	private void selectTriggerPre(int percent) {
		for (int i = 0; i < triggerPreCombo.getItemCount(); i++) {
			if (triggerPreCombo.getItemAt(i).percent == percent) {
				triggerPreCombo.setSelectedIndex(i);
				return;
			}
		}
		triggerPreCombo.setSelectedIndex(1);
	}

	private void syncTimeViewFromPanel() {
		if (timeDomainPanel == null || sampleViewCombo == null) {
			return;
		}
		int samples = timeDomainPanel.getVisibleSamples();
		boolean matched = false;
		for (int i = 0; i < sampleViewCombo.getItemCount(); i++) {
			if (sampleViewCombo.getItemAt(i).samples == samples) {
				if (sampleViewCombo.getSelectedIndex() != i) {
					sampleViewCombo.setSelectedIndex(i);
				}
				matched = true;
				break;
			}
		}
		if (!matched && sampleViewCombo.getSelectedIndex() != 0) {
			sampleViewCombo.setSelectedIndex(0);
		}
		updateTimeViewInfo();
	}

	private void updateTimeViewInfo() {
		if (timeViewInfoLabel == null || timeDomainPanel == null) {
			return;
		}
		int samples = timeDomainPanel.getVisibleSamples();
		int rate = (int) latestSampleRateHz.get();
		double seconds = rate <= 0 ? 0 : samples / (double) rate;
		timeViewInfoLabel.setText(samples + " samples, " + formatDuration(seconds));
	}

	private String formatDuration(double seconds) {
		if (seconds < 0.001d) {
			return String.format(Locale.US, "%.1f us", seconds * 1_000_000d);
		}
		if (seconds < 1d) {
			return String.format(Locale.US, "%.2f ms", seconds * 1_000d);
		}
		return String.format(Locale.US, "%.2f s", seconds);
	}

	private static String formatFrequency(long frequencyHz) {
		if (frequencyHz % 1_000_000 == 0) {
			return String.format(Locale.US, "%d MHz", frequencyHz / 1_000_000);
		}
		String value = String.format(Locale.US, "%.2f", frequencyHz / 1_000_000d);
		while (value.indexOf('.') >= 0 && value.endsWith("0")) {
			value = value.substring(0, value.length() - 1);
		}
		if (value.endsWith(".")) {
			value = value.substring(0, value.length() - 1);
		}
		return value + " MHz";
	}

	private static String formatOffset(long offsetHz) {
		if (offsetHz == 0) {
			return "0";
		}
		long abs = Math.abs(offsetHz);
		if (abs % 1_000_000 == 0) {
			return String.format(Locale.US, "%dMHz", offsetHz / 1_000_000);
		}
		if (abs >= 1_000_000) {
			return String.format(Locale.US, "%.3fMHz", offsetHz / 1_000_000d);
		}
		if (abs % 1000 == 0) {
			return String.format(Locale.US, "%dkHz", offsetHz / 1000);
		}
		if (abs >= 1000) {
			return String.format(Locale.US, "%.1fkHz", offsetHz / 1000d);
		}
		return offsetHz + "Hz";
	}

	private static long parseFrequencyHz(String text) {
		String value = text.trim().toLowerCase(Locale.US);
		double multiplier = 1d;
		if (value.endsWith("mhz")) {
			multiplier = 1_000_000d;
			value = value.substring(0, value.length() - 3);
		} else if (value.endsWith("khz")) {
			multiplier = 1_000d;
			value = value.substring(0, value.length() - 3);
		} else if (value.endsWith("hz")) {
			value = value.substring(0, value.length() - 2);
		}
		return Math.round(Double.parseDouble(value.trim()) * multiplier);
	}

	private static int parseInt(String text, String name) {
		try {
			return Integer.parseInt(text.trim());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Invalid " + name + ": " + text, e);
		}
	}

	private static class RateOption {
		final String label;
		final int sampleRateHz;

		RateOption(String label, int sampleRateHz) {
			this.label = label;
			this.sampleRateHz = sampleRateHz;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	private static class PresetOption {
		final String label;
		final int bandwidthHz;
		final int outputRateHz;
		final int visibleSamples;

		PresetOption(String label, int bandwidthHz, int outputRateHz, int visibleSamples) {
			this.label = label;
			this.bandwidthHz = bandwidthHz;
			this.outputRateHz = outputRateHz;
			this.visibleSamples = visibleSamples;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	private static class ViewModeOption {
		final String label;
		final boolean channel;

		ViewModeOption(String label, boolean channel) {
			this.label = label;
			this.channel = channel;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	private static class BandwidthOption {
		final String label;
		final int bandwidthHz;

		BandwidthOption(String label, int bandwidthHz) {
			this.label = label;
			this.bandwidthHz = bandwidthHz;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	private static class OutputRateOption {
		final String label;
		final int outputRateHz;

		OutputRateOption(String label, int outputRateHz) {
			this.label = label;
			this.outputRateHz = outputRateHz;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	private static class SampleViewOption {
		final String label;
		final int samples;

		SampleViewOption(String label, int samples) {
			this.label = label;
			this.samples = samples;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	private static class TriggerPreOption {
		final String label;
		final int percent;

		TriggerPreOption(String label, int percent) {
			this.label = label;
			this.percent = percent;
		}

		@Override
		public String toString() {
			return label;
		}
	}
}
