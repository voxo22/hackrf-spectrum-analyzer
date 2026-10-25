package jspectrumanalyzer.iq;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JPanel;
import javax.swing.JRadioButton;

/** Selects exactly one independent GNSS L1 decoder and displays its satellite table. */
final class GnssL1OverviewPanel extends JPanel {
	private static final Color BACKGROUND = Color.BLACK, FOREGROUND = Color.WHITE;
	private final GpsL1SatelliteTablePanel satellitePanel;
	private final Consumer<GnssL1System> onSystemChanged;
	private final JRadioButton gps = radio("GPS"), galileo = radio("Galileo"), beidou = radio("BeiDou");
	private GnssL1System system = GnssL1System.GPS;

	GnssL1OverviewPanel(GpsL1SatelliteTablePanel satellitePanel) { this(satellitePanel, null); }

	GnssL1OverviewPanel(GpsL1SatelliteTablePanel satellitePanel, Consumer<GnssL1System> onSystemChanged) {
		super(new BorderLayout(0, 6));
		this.satellitePanel = satellitePanel;
		this.onSystemChanged = onSystemChanged;
		setBackground(BACKGROUND);
		JPanel selector = new JPanel(new FlowLayout(FlowLayout.LEFT, 18, 4));
		selector.setBackground(BACKGROUND);
		selector.setBorder(BorderFactory.createEmptyBorder(0, 5, 0, 5));
		ButtonGroup group = new ButtonGroup();
		group.add(gps); group.add(galileo); group.add(beidou);
		gps.setSelected(true);
		selector.add(gps); selector.add(galileo); selector.add(beidou);
		gps.addActionListener(event -> select(GnssL1System.GPS));
		galileo.addActionListener(event -> select(GnssL1System.GALILEO));
		beidou.addActionListener(event -> select(GnssL1System.BEIDOU));
		add(selector, BorderLayout.NORTH);
		add(satellitePanel, BorderLayout.CENTER);
		satellitePanel.setSystem(system);
	}

	GnssL1System selectedSystem() { return system; }

	void setSystem(GnssL1System value) {
		if (value == null) value = GnssL1System.GPS;
		if (value == GnssL1System.GPS) gps.setSelected(true);
		else if (value == GnssL1System.GALILEO) galileo.setSelected(true);
		else beidou.setSelected(true);
		select(value);
	}

	void refresh(GpsL1SignalAnalyzer.Result gpsResult, GalileoE1SignalAnalyzer.Result galileoResult,
			long centerHz, int sampleRateHz) {
		refresh(gpsResult, galileoResult, null, centerHz, sampleRateHz);
	}

	void refresh(GpsL1SignalAnalyzer.Result gpsResult, GalileoE1SignalAnalyzer.Result galileoResult,
			BeiDouB1cSignalAnalyzer.Result beidouResult, long centerHz, int sampleRateHz) {
		if (system == GnssL1System.GPS) satellitePanel.refreshGps(gpsResult);
		else if (system == GnssL1System.GALILEO) satellitePanel.refreshGalileo(galileoResult);
		else satellitePanel.refreshBeidou(beidouResult, centerHz, sampleRateHz);
	}

	private void select(GnssL1System value) {
		if (system == value) return;
		system = value;
		satellitePanel.setSystem(value);
		if (onSystemChanged != null) onSystemChanged.accept(value);
	}

	private static JRadioButton radio(String text) {
		JRadioButton button = new JRadioButton(text);
		button.setOpaque(false);
		button.setForeground(FOREGROUND);
		button.setFocusPainted(false);
		return button;
	}
}
