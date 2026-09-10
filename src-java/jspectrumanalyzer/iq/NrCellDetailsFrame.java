package jspectrumanalyzer.iq;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.util.Locale;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;

/** Detailed decoded NR information for the PCI selected in the main table. */
final class NrCellDetailsFrame extends JFrame {
	private static final String WAIT = "wait...";
	private final java.util.function.IntSupplier pciSupplier;
	private final IntFunction<NrSignalAnalyzer.Result> cellSupplier;
	private final IntFunction<Double> loadSupplier;
	private final LongSupplier frequencySupplier;
	private final DefaultTableModel system = model("Parameter", "Value", "Meaning");
	private final DefaultTableModel radio = model("Parameter", "Value", "Meaning");
	private final DefaultTableModel sib1 = model("Parameter", "Value", "Meaning");
	private final DefaultTableModel load = model("Statistic", "Value", "Meaning");
	private final JLabel status = new JLabel("Select an NR cell in the main window.");
	private final Timer timer;

	NrCellDetailsFrame(java.util.function.IntSupplier pciSupplier,
			IntFunction<NrSignalAnalyzer.Result> cellSupplier, IntFunction<Double> loadSupplier,
			LongSupplier frequencySupplier) {
		super("NR Cell Info");
		this.pciSupplier = pciSupplier;
		this.cellSupplier = cellSupplier;
		this.loadSupplier = loadSupplier;
		this.frequencySupplier = frequencySupplier;
		setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		status.setBorder(new EmptyBorder(8, 10, 8, 10));
		status.setForeground(Color.WHITE);
		status.setOpaque(true);
		status.setBackground(Color.BLACK);
		JTabbedPane tabs = new JTabbedPane();
		tabs.addTab("SYSTEM", panel(system));
		tabs.addTab("RADIO", panel(radio));
		tabs.addTab("SIB1", panel(sib1));
		tabs.addTab("AIR LOAD", panel(load));
		add(status, BorderLayout.NORTH);
		add(tabs, BorderLayout.CENTER);
		setSize(850, 520);
		setMinimumSize(new Dimension(700, 400));
		timer = new Timer(500, e -> refresh());
		timer.start();
		refresh();
	}

	@Override public void dispose() { timer.stop(); super.dispose(); }

	private void refresh() {
		int pci = pciSupplier.getAsInt();
		NrSignalAnalyzer.Result cell = pci < 0 ? null : cellSupplier.apply(pci);
		system.setRowCount(0); radio.setRowCount(0); sib1.setRowCount(0); load.setRowCount(0);
		if (cell == null) { status.setText("Select an NR cell in the main window."); return; }
		status.setText("PCI " + pci + " - last CRC-verified MIB and SIB1 retained during this session");
		NrSignalAnalyzer.Sib1Data s = cell.sib1;
		NrSignalAnalyzer.MibData m = cell.mib;
		long tuned = frequencySupplier.getAsLong() + cell.freqOffsetHz;
		add(system, "Physical Cell ID", Integer.toString(cell.pci), "NR physical-layer cell identity.");
		add(system, "PLMN", s.valid ? String.join(", ", s.plmns) : WAIT, "Public mobile network identifier.");
		add(system, "Tracking Area Code", s.valid ? Integer.toString(s.trackingAreaCode) : WAIT, "Area used for mobility and paging.");
		add(system, "NR Cell Identity", s.valid ? Long.toString(s.cellIdentity) : WAIT, "36-bit cell identity broadcast in SIB1.");
		add(system, "NR band", s.valid ? bands(s.frequencyBands) : WAIT, "Operating band advertised by SIB1.");
		add(system, "SSB frequency", tuned > 0 ? String.format(Locale.US, "%.6f MHz", tuned / 1e6) : WAIT, "Measured center of the detected synchronization block.");

		add(radio, "PSS / SSS quality", String.format(Locale.US, "%.1f %% / %.1f %%", cell.pssCorrelation * 100d, cell.sssCorrelation * 100d), "Synchronization quality for this PCI.");
		add(radio, "PBCH DM-RS quality", cell.dmrsConfirmed ? String.format(Locale.US, "%.1f %%", cell.dmrsCorrelation * 100d) : WAIT, "Reference-signal confirmation of the PCI.");
		add(radio, "Frequency offset", String.format(Locale.US, "%+.0f Hz", cell.candidates.isEmpty() ? 0d : cell.candidates.get(0).cfoHz), "Estimated residual tuning error.");
		add(radio, "SSB index", cell.iBarSsb >= 0 ? Integer.toString(cell.iBarSsb & 3) : WAIT, "Decoded SSB index for this capture.");
		add(radio, "System frame number", m.valid ? Integer.toString(m.systemFrameNumber) : WAIT, "Radio-frame timing decoded from MIB.");
		add(radio, "Common subcarrier spacing", m.valid ? m.subcarrierSpacingCommonKhz + " kHz" : WAIT, "Common SCS signalled by MIB.");
		add(radio, "SSB subcarrier offset", m.valid ? Integer.toString(m.ssbSubcarrierOffset) : WAIT, "kSSB signalled by MIB.");
		add(radio, "DM-RS type-A position", m.valid ? Integer.toString(m.dmrsTypeAPosition) : WAIT, "PDSCH DM-RS start position.");
		add(radio, "CORESET0 / SearchSpace0", m.valid ? ((m.pdcchConfigSib1 >>> 4) & 15) + " / " + (m.pdcchConfigSib1 & 15) : WAIT, "Initial common control-channel configuration.");
		add(radio, "Cell access", m.valid ? (m.cellBarred ? "Barred" : "Available") : WAIT, "Whether MIB permits ordinary cell access.");
		add(radio, "NR channel bandwidth", s.valid && s.channelBandwidthMhz > 0 ? s.channelBandwidthMhz + " MHz" : WAIT, "Nominal carrier bandwidth derived from the SIB1 transmission bandwidth configuration.");
		add(radio, "Carrier resource grid", s.valid ? s.carrierBandwidthRb + " RB @ " + s.carrierScsKhz + " kHz" : WAIT, "Downlink carrier bandwidth and subcarrier spacing broadcast in SIB1.");

		add(sib1, "Initial downlink BWP", s.valid ? s.initialBwpRb + " RB @ " + s.initialBwpScsKhz + " kHz" : WAIT, "Common downlink bandwidth part decoded from SIB1.");
		add(sib1, "Offset to Point A", s.valid ? Integer.toString(s.offsetToPointA) : WAIT, "Common resource-block grid offset.");
		add(sib1, "SI window", s.valid ? s.siWindowSlots + " slots" : WAIT, "Window in which scheduled System Information is transmitted.");
		add(sib1, "SI schedule", s.valid && !s.schedules.isEmpty() ? String.join("; ", s.schedules) : WAIT, "SIB mapping, repetition period and broadcast status.");
		add(sib1, "Other-SI search space", s.valid && s.otherSiSearchSpace >= 0 ? Integer.toString(s.otherSiSearchSpace) : WAIT, "Common search space used for other System Information.");
		add(sib1, "BCCH modification coefficient", s.valid ? "n" + s.modificationPeriodCoefficient : WAIT, "Multiplier used for BCCH modification periods.");
		add(sib1, "Paging cycle", s.valid ? "RF" + s.pagingCycleFrames : WAIT, "Default idle-mode paging cycle.");
		add(sib1, "Paging frame offset", s.valid ? s.pagingFrameOffsetType + " + " + s.pagingFrameOffset : WAIT, "nAndPagingFrameOffset configuration.");
		add(sib1, "Paging occasions per frame", s.valid ? Integer.toString(s.pagingOccasions) : WAIT, "Number of paging occasions in a paging frame.");
		add(sib1, "Paging search space", s.valid && s.pagingSearchSpace >= 0 ? Integer.toString(s.pagingSearchSpace) : WAIT, "Common search space monitored for paging.");

		double recent = loadSupplier.apply(pci);
		add(load, "Recent downlink air load", Double.isNaN(recent) ? WAIT : String.format(Locale.US, "%.1f %%", recent), "Smoothed occupied-PRB estimate for the captured NR downlink.");
		add(load, "Latest measurement", cell.load.valid ? String.format(Locale.US, "%.1f %%  (%d/%d)", cell.load.percent, cell.load.activePrbSamples, cell.load.totalPrbSamples) : WAIT, "PRB/symbol samples significantly above each PRB's temporal idle floor.");
		add(load, "Observed OFDM symbols", cell.load.valid ? Integer.toString(cell.load.symbols) : WAIT, "Data symbols contributing to the latest estimate.");
		add(load, "Measurement span", "48 PRB in captured bandwidth", "The 10 MS/s input does not cover resources outside this observed span.");
		add(load, "Co-channel cells", "Combined radio energy", "Energy from cells sharing the frequency cannot be separated by PCI.");
		add(load, "Interpretation", "Radio-resource occupancy estimate", "Not BTS CPU, backhaul load, user count or guaranteed throughput.");
	}

	private static JPanel panel(DefaultTableModel model) {
		JTable table = new JTable(model);
		table.setFillsViewportHeight(true); table.setAutoCreateRowSorter(true);
		table.setBackground(new Color(0x161616)); table.setForeground(Color.WHITE);
		table.setGridColor(new Color(0x3a3a3a));
		table.getColumnModel().getColumn(0).setPreferredWidth(195);
		table.getColumnModel().getColumn(1).setPreferredWidth(220);
		table.getColumnModel().getColumn(2).setPreferredWidth(400);
		JScrollPane scroll = new JScrollPane(table);
		scroll.getViewport().setBackground(Color.BLACK);
		scroll.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
		JPanel panel = new JPanel(new BorderLayout()); panel.setBackground(Color.BLACK);
		panel.setBorder(new EmptyBorder(8, 8, 8, 8)); panel.add(scroll); return panel;
	}

	private static String bands(java.util.List<Integer> values) {
		StringBuilder result = new StringBuilder();
		for (Integer value : values) { if (result.length() > 0) result.append(", "); result.append('n').append(value); }
		return result.length() == 0 ? WAIT : result.toString();
	}
	private static DefaultTableModel model(String... columns) { return new DefaultTableModel(columns, 0) { @Override public boolean isCellEditable(int r, int c) { return false; } }; }
	private static void add(DefaultTableModel model, String parameter, String value, String meaning) { model.addRow(new Object[] { parameter, value, meaning }); }
}
