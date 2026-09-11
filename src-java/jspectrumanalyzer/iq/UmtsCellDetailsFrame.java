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

/** Detailed public UMTS information for the PSC selected in the main table. */
final class UmtsCellDetailsFrame extends JFrame {
	private static final String WAIT = "wait...";
	private final java.util.function.IntSupplier pscSupplier;
	private final IntFunction<UmtsSignalAnalyzer.Candidate> cellSupplier;
	private final LongSupplier frequencySupplier;
	private final IntFunction<Double> loadSupplier;
	private final DefaultTableModel system = model("Parameter", "Value", "Meaning");
	private final DefaultTableModel radio = model("Parameter", "Value", "Meaning");
	private final DefaultTableModel information = model("Block", "Status", "Information");
	private final DefaultTableModel paging = model("Statistic", "Value", "Meaning");
	private final DefaultTableModel load = model("Statistic", "Value", "Meaning");
	private final JLabel status = new JLabel("Select a UMTS cell in the main window.");
	private final Timer timer;

	UmtsCellDetailsFrame(java.util.function.IntSupplier pscSupplier,
			IntFunction<UmtsSignalAnalyzer.Candidate> cellSupplier, LongSupplier frequencySupplier,
			IntFunction<Double> loadSupplier) {
		super("UMTS Cell Details");
		this.pscSupplier = pscSupplier;
		this.cellSupplier = cellSupplier;
		this.frequencySupplier = frequencySupplier;
		this.loadSupplier = loadSupplier;
		setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		status.setBorder(new EmptyBorder(8, 10, 8, 10));
		status.setForeground(Color.WHITE);
		status.setOpaque(true);
		status.setBackground(Color.BLACK);
		JTabbedPane tabs = new JTabbedPane();
		tabs.addTab("SYSTEM", panel(system));
		tabs.addTab("RADIO", panel(radio));
		tabs.addTab("MIB / SIB", panel(information));
		tabs.addTab("PAGING", panel(paging));
		tabs.addTab("AIR LOAD", panel(load));
		add(status, BorderLayout.NORTH);
		add(tabs, BorderLayout.CENTER);
		setSize(850, 500);
		setMinimumSize(new Dimension(700, 390));
		timer = new Timer(500, e -> refresh());
		timer.start();
		refresh();
	}

	@Override public void dispose() { timer.stop(); super.dispose(); }

	private void refresh() {
		int psc = pscSupplier.getAsInt();
		UmtsSignalAnalyzer.Candidate cell = psc < 0 ? null : cellSupplier.apply(psc);
		system.setRowCount(0);
		radio.setRowCount(0);
		information.setRowCount(0);
		paging.setRowCount(0);
		load.setRowCount(0);
		if (cell == null) {
			status.setText("Select a UMTS cell in the main window.");
			return;
		}
		UmtsSystemInformationDecoder.Snapshot si = cell.systemInformation;
		PlmnDatabase.Entry plmn = si.mibValid ? PlmnDatabase.lookup(si.mcc, si.mnc) : null;
		status.setText("PSC " + psc + (si.mibValid
				? " - MIB decoded; received " + si.receivedBlocks()
				: cell.bch.valid ? " - BCH transport block decoded with valid CRC"
				: " - live CPICH measurements; BCH system information is waiting"));
		long centerHz = frequencySupplier.getAsLong();
		long carrierHz = centerHz > 0 ? Math.round(centerHz + cell.cfoHz) : -1L;
		int uarfcn = carrierHz > 0 ? UmtsSignalAnalyzer.uarfcnFromFrequency(carrierHz) : -1;

		add(system, "Primary Scrambling Code", Integer.toString(cell.psc),
				"Physical-layer cell identity; it is not a globally unique BTS identifier.");
		add(system, "PLMN", si.mibValid ? si.mcc + "-" + si.mnc : WAIT,
				"Public mobile network identifier decoded from MIB.");
		add(system, "Country", plmn == null ? WAIT : plmn.country,
				"Country resolved from the broadcast MCC.");
		add(system, "Operator / network", plmn == null ? WAIT : plmn.networkName(),
				"Network name resolved from the broadcast MCC and MNC.");
		add(system, "Location Area Code", si.sib1Valid ? si.lac + " (0x" + hex(si.lac, 4) + ")" : WAIT,
				"Circuit-switched mobility area decoded from the SIB1 common NAS information.");
		add(system, "UTRAN Cell Identity", si.sib3Valid
				? si.cellIdentity + " (0x" + hex(si.cellIdentity, 7) + ")" : WAIT,
				"28-bit cell identity broadcast in SIB3.");
		add(system, "RNC / local Cell ID", si.sib3Valid
				? (si.cellIdentity >>> 16) + " / " + (si.cellIdentity & 0xffff) : WAIT,
				"Conventional split of the 28-bit UTRAN Cell Identity into 12-bit RNC ID and 16-bit cell ID.");
		add(system, "UARFCN", uarfcn >= 0 ? Integer.toString(uarfcn) : WAIT,
				"UMTS absolute radio-frequency channel number.");
		add(system, "CPICH frequency", carrierHz > 0
				? String.format(Locale.US, "%.6f MHz", carrierHz / 1e6) : WAIT,
				"Measured carrier center including the estimated tuning offset.");

		add(radio, "Scrambling-code group", Integer.toString(cell.codeGroup),
				"One of 64 downlink primary scrambling-code groups.");
		add(radio, "CPICH correlation", String.format(Locale.US, "%.1f %%", cell.cpichCorrelation * 100d),
				"Pilot correlation used to confirm this PSC.");
		add(radio, "CPICH Ec/N0", String.format(Locale.US, "%.1f dB", cell.ecNoDb),
				"Estimated pilot energy relative to received spectral density.");
		add(radio, "CPICH RSCP", String.format(Locale.US, "%.1f dBFS", cell.rscpDbfs),
				"Uncalibrated received pilot level relative to IQ full scale.");
		add(radio, "Carrier offset", String.format(Locale.US, "%+.1f Hz", cell.cfoHz),
				"Measured carrier displacement from the IQ tuning center.");
		add(radio, "Frame slot phase", Integer.toString(cell.frameSlotPhase),
				"Slot phase used by the current frame-timing hypothesis.");
		add(radio, "IQ orientation", cell.iqOrientation > 0 ? "Normal" : "Conjugated",
				"Complex-sample orientation that produced the valid CPICH lock.");

		add(information, "BCH", cell.bch.valid ? "CRC OK" : WAIT,
				cell.bch.valid ? "246-bit broadcast transport block acquired from P-CCPCH."
						: "P-CCPCH transport decoding and CRC.");
		add(information, "BCH payload", cell.bch.valid ? cell.bch.payloadHex() : WAIT,
				"Raw 246 payload bits; the final hexadecimal nibble contains two padding bits.");
		add(information, "System frame number", cell.bch.valid ? Integer.toString(cell.bch.sfn) : WAIT,
				"SFN of the first 10 ms radio frame in this 20 ms BCH transmission interval.");
		add(information, "System-information payload", cell.bch.valid ? cell.bch.payloadType() : WAIT,
				"UPER choice describing the SIB segment combination in this BCH block.");
		add(information, "Decoded blocks", si.hasAny() ? si.receivedBlocks() : WAIT,
				"CRC-verified and fully reassembled ASN.1 system-information blocks retained this session.");
		add(information, "MIB", sibStatus(si, 0), si.mibValid
				? "Value tag " + si.mibValueTag + "; PLMN " + si.mcc + "-" + si.mnc
				: "PLMN and scheduling of remaining system information.");
		add(information, "SI schedule", si.schedules.isEmpty() ? WAIT : String.join("; ", si.schedules),
				"Repetition period, first SFN position and segment count decoded from MIB/SB1.");
		add(information, "SIB1", sibStatus(si, 1), si.sib1Valid
				? "LAC " + si.lac + "; common NAS 0x" + si.commonNasHex
				: "Core-network and location-area information.");
		add(information, "SIB3", sibStatus(si, 3), si.sib3Valid
				? "Cell ID " + si.cellIdentity + "; SIB4 " + (si.sib4Indicator ? "used" : "not used")
				: "UTRAN Cell Identity, access and reselection parameters.");
		add(information, "SIB5", sibStatus(si, 5), "Common physical channels including paging configuration.");
		UmtsSib5Decoder.Channel pagingChannel = si.sib5.pagingChannel;
		UmtsSib5Decoder.Transport pagingTransport = pagingChannel == null ? null : pagingChannel.pagingTransport();
		UmtsSib5Decoder.DynamicFormat pagingFormat = pagingTransport == null ? null : pagingTransport.format.primary();
		add(information, "Paging S-CCPCH", pagingChannel == null ? WAIT
				: "SF" + pagingChannel.spreadingFactor + " / code " + pagingChannel.code
						+ "; timing " + pagingChannel.timingOffset + " x 256 chips",
				"Physical channel associated with the broadcast PICH configuration.");
		add(information, "PICH", pagingChannel == null ? WAIT
				: "SF256 / code " + pagingChannel.pichCode + "; "
						+ pagingChannel.indicatorsPerFrame + " indicators/frame",
				"Paging indicators are transmitted 7680 chips before the associated S-CCPCH frame.");
		add(information, "PCH transport format", pagingTransport == null || pagingFormat == null ? WAIT
				: pagingTransport.format.ttiMs + " ms TTI; " + pagingFormat.blockBits
						+ " bits; CRC" + pagingTransport.format.crcBits
						+ "; conv 1/" + pagingTransport.format.codingRate,
				"Transport format needed for CRC-valid Paging Type 1 decoding.");
		add(information, "SIB7", sibStatus(si, 7), "Dynamic uplink interference information.");
		add(information, "SIB11", sibStatus(si, 11), "Idle-mode neighbouring UMTS and inter-RAT cells.");
		add(information, "SIB12", sibStatus(si, 12), "Connected-mode measurement and neighbouring-cell information.");

		add(paging, "PICH configuration", pagingChannel == null ? WAIT
				: "code " + pagingChannel.pichCode + "; " + pagingChannel.indicatorsPerFrame + " indicators/frame",
				"Decoded from SIB5 and associated with the listed S-CCPCH.");
		add(paging, "PCH acquisition", cell.paging.supported ? cell.paging.state : WAIT,
				"Paging Type 1 is carried on S-CCPCH/PCH.");
		add(paging, "Checked radio frames", cell.paging.supported
				? Integer.toString(cell.paging.checkedFrames) : WAIT,
				"S-CCPCH frames passed through deinterleaving, rate matching and channel decoding.");
		add(paging, "CRC-valid PCH blocks", cell.paging.supported
				? Integer.toString(cell.paging.blocks.size()) : WAIT,
				"Only transport blocks whose CRC and convolutional-code tail both validate are retained.");
		add(paging, "Paging records", cell.paging.supported
				? Integer.toString(cell.paging.paging.events.size()) : WAIT,
				"ASN.1 Paging Type 1 records decoded from CRC-valid PCH transport blocks.");
		add(paging, "Anonymous UE", cell.paging.supported
				? Integer.toString(cell.paging.paging.clients) : WAIT,
				"Identities are mapped to session-only random-HMAC labels and are never displayed raw.");
		add(paging, "Observation scope", "Current program session", "Paging statistics will not be written to disk.");

		double recentLoad = loadSupplier.apply(psc);
		add(load, "Recent code-domain air load", Double.isNaN(recentLoad) ? WAIT
				: String.format(Locale.US, "%.1f %%", recentLoad),
				"Smoothed occupied SF256-equivalent OVSF code estimate for this PSC.");
		add(load, "Latest measurement", cell.load.valid
				? String.format(Locale.US, "%.1f %%  (%d/%d)", cell.load.percent,
						cell.load.activeCodeSamples, cell.load.totalCodeSamples) : WAIT,
				"Code projections above the measured per-block idle floor.");
		add(load, "Observed code blocks", cell.load.valid ? Integer.toString(cell.load.blocks) : WAIT,
				"256-chip intervals contributing to the latest estimate.");
		add(load, "Measurement scope", "Current PSC and downlink carrier",
				"Other scrambling codes are rejected as interference and measured separately during rescans.");
		add(load, "Interpretation", "Radio code occupancy estimate",
				"Not BTS CPU/backhaul load, user count, uplink load or guaranteed throughput.");
	}

	private static JPanel panel(DefaultTableModel model) {
		JTable table = new JTable(model);
		table.setFillsViewportHeight(true);
		table.setAutoCreateRowSorter(true);
		table.setBackground(new Color(0x161616));
		table.setForeground(Color.WHITE);
		table.setGridColor(new Color(0x3a3a3a));
		table.getColumnModel().getColumn(0).setPreferredWidth(190);
		table.getColumnModel().getColumn(1).setPreferredWidth(210);
		table.getColumnModel().getColumn(2).setPreferredWidth(390);
		JScrollPane scroll = new JScrollPane(table);
		scroll.getViewport().setBackground(Color.BLACK);
		scroll.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(Color.BLACK);
		panel.setBorder(new EmptyBorder(8, 8, 8, 8));
		panel.add(scroll);
		return panel;
	}

	private static DefaultTableModel model(String... columns) {
		return new DefaultTableModel(columns, 0) {
			@Override public boolean isCellEditable(int row, int column) { return false; }
		};
	}

	private static void add(DefaultTableModel model, String parameter, String value, String meaning) {
		model.addRow(new Object[] { parameter, value, meaning });
	}

	private static String sibStatus(UmtsSystemInformationDecoder.Snapshot information, int type) {
		if (information.hasBlock(type)) return "decoded (" + information.blockBits(type) + " bits)";
		String progress = information.progressFor(type);
		return progress == null ? WAIT : "segments " + progress;
	}

	private static String hex(int value, int digits) {
		return String.format(Locale.ROOT, "%0" + digits + "X", value);
	}
}
