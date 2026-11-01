package jspectrumanalyzer.iq;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;

/** Stable, program-wide styled view of decoded TETRA cell broadcasts. */
final class TetraCellDetailsFrame extends JFrame {
	private static final String WAIT = "wait\u2026";
	private static final String[] NEIGHBOUR_COLUMNS = {
		"Cell ID", "Main frequency", "Carrier", "Band", "Offset", "Duplex",
		"Operation", "Reselection", "Synchronized", "Service", "MCC", "MNC",
		"Location area", "Max MS TX", "Min RX", "Subscriber class",
		"BS services", "Timeshare / AI", "TDMA offset", "Last received"
	};
	private static final int[] NEIGHBOUR_WIDTHS = {
		65, 120, 70, 55, 65, 60, 80, 85, 90, 65, 60, 70, 90, 80, 65,
		105, 105, 100, 85, 110
	};

	private final JLabel status = new JLabel("Waiting for a CRC-verified TETRA cell broadcast.");
	private final DefaultTableModel cellModel = model("Parameter", "Value", "Meaning");
	private final DefaultTableModel servicesModel = model("Parameter", "Value", "Meaning");
	private final DefaultTableModel neighbourModel = model(NEIGHBOUR_COLUMNS);
	private final SimpleDateFormat timestamp = new SimpleDateFormat(
			"yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);

	TetraCellDetailsFrame() {
		super("TETRA Signal Analyzer - Cell Details");
		setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		status.setBorder(new EmptyBorder(8, 10, 8, 10));
		status.setForeground(Color.WHITE);
		status.setOpaque(true);
		status.setBackground(Color.BLACK);
		JTabbedPane tabs = new JTabbedPane();
		tabs.addTab("CELL", parameterPanel(cellModel));
		tabs.addTab("SERVICES", parameterPanel(servicesModel));
		tabs.addTab("NEIGHBOUR CELLS", neighbourPanel());
		add(SignalTesterClipboard.createDetailsHeader(getContentPane(), status), BorderLayout.NORTH);
		add(tabs, BorderLayout.CENTER);
		setSize(800, 600);
		setMinimumSize(new Dimension(720, 450));
	}

	void refresh(TetraDecoderCore.Result result) {
		TetraCellInfo.Snapshot cell = result == null ? null : result.cellInfo;
		List<Object[]> cellRows = new ArrayList<Object[]>();
		List<Object[]> serviceRows = new ArrayList<Object[]>();
		List<Object[]> neighbourRows = new ArrayList<Object[]>();
		if (cell == null || !cell.hasSysInfo) {
			status.setText("Waiting for a CRC-verified clear MAC-SYSINFO broadcast.");
			add(cellRows, "SYSINFO", WAIT,
					"Cell parameters remain empty until a complete CRC-verified broadcast is decoded.");
		} else {
			TetraCellInfo.SystemParameters s = cell.system;
			String main = cell.role == TetraCellInfo.Role.MAIN ? "MAIN CELL - " : "";
			status.setText(main + "main carrier " + mhz(cell.mainCarrierHz)
					+ " (carrier " + cell.mainCarrier + "); retained CRC-verified broadcasts");

			if (result.systemInfo != null) {
				add(cellRows, "MCC / MNC", result.systemInfo.mcc + " / " + result.systemInfo.mnc,
						"Mobile country and network codes decoded from BSCH.");
				add(cellRows, "Colour code", result.systemInfo.colourCode,
						"TETRA network colour code decoded from BSCH.");
				add(cellRows, "Timeslot / frame / multiframe", result.systemInfo.timeslot + " / "
						+ result.systemInfo.frame + " / " + result.systemInfo.multiframe,
						"Current synchronized TETRA air-interface time.");
			}
			add(cellRows, "Tuned frequency", mhz(cell.tunedHz),
					"Center of the 25 kHz carrier currently being decoded.");
			add(cellRows, "Main carrier frequency", mhz(cell.mainCarrierHz),
					"Calculated from frequency band, carrier number and offset.");
			add(cellRows, "Main carrier number", cell.mainCarrier,
					"12-bit Main_Carrier field from MAC-SYSINFO.");
			add(cellRows, "Frequency band", cell.frequencyBand,
					"100 MHz base-band index used for TETRA carrier calculation.");
			add(cellRows, "Offset", cell.offset + " (" + signed(TetraCellInfo.offsetHz(cell.offset))
					+ " Hz)", "Carrier-grid offset code and resolved frequency offset.");
			add(cellRows, "Duplex spacing code", cell.duplexSpacing,
					"Three-bit Duplex_Spacing value broadcast by the cell.");
			add(cellRows, "Operation", cell.reverseOperation ? "Reverse" : "Normal",
					"Reverse_Operation flag from MAC-SYSINFO.");
			add(cellRows, "Common secondary channels", cell.commonSecondaryChannels,
					"NumberOfCommon_SC broadcast by the cell.");
			add(cellRows, "Maximum MS transmit power", s.maxTransmitPower,
					"Raw three-bit MS_TXPwr_Max_Cell value.");
			add(cellRows, "Minimum RX access level", s.minRxAccessLevel,
					"Raw four-bit RXLevel_Access_Min value.");
			add(cellRows, "Access parameter", s.accessParameter,
					"Raw four-bit random-access parameter.");
			add(cellRows, "Radio downlink timeout", s.downlinkTimeout,
					"Raw four-bit Radio_Downlink_Timeout value.");
			add(cellRows, "Location area", s.locationArea,
					"14-bit Location_Area value from the complete SYSINFO tail.");
			add(cellRows, "Subscriber class", hex(s.subscriberClass, 4),
					"16-bit subscriber-class bitmap from MAC-SYSINFO.");
			add(cellRows, s.hyperframeOrCipherKeyFlag == 0 ? "Hyperframe" : "Cipher key identifier",
					hex(s.hyperframeOrCipherKey, 4),
					"16-bit conditional SYSINFO field selected by its flag.");
			add(cellRows, "Optional field", s.optionalFieldFlag + " / "
					+ hex(s.optionalFieldValue, 5),
					"Optional-field selector and retained value from MAC-SYSINFO.");
			add(cellRows, "Last SYSINFO", time(s.timestampMillis),
					"Timestamp of the most recent complete CRC-verified MAC-SYSINFO.");

			addFlag(serviceRows, "Registration required", s.registrationRequired,
					"Registration_required SYSINFO flag.");
			addFlag(serviceRows, "De-registration required", s.deregistrationRequired,
					"De_registration_required SYSINFO flag.");
			addFlag(serviceRows, "Priority cell", s.priorityCell,
					"Priority_cell SYSINFO flag.");
			addFlag(serviceRows, "Never uses minimum mode", s.neverMinimumMode,
					"Cell_never_uses_minimum_mode SYSINFO flag.");
			addFlag(serviceRows, "Migration supported", s.migrationSupported,
					"Migration_supported SYSINFO flag.");
			addFlag(serviceRows, "System-wide services", s.systemWideServices,
					"System_wide_services SYSINFO flag.");
			addFlag(serviceRows, "TETRA voice service", s.voiceService,
					"TETRA_voice_service SYSINFO flag.");
			addFlag(serviceRows, "Circuit-mode data service", s.circuitModeDataService,
					"Circuit_mode_data_service SYSINFO flag.");
			addFlag(serviceRows, "SNDCP service", s.sndcpService,
					"SNDCP_Service SYSINFO flag.");
			addFlag(serviceRows, "Air-interface encryption", s.airInterfaceEncryption,
					"Air_interface_encryption capability flag; it does not mean every resource is encrypted.");
			addFlag(serviceRows, "Advanced link supported", s.advancedLinkSupported,
					"Advanced_link_supported SYSINFO flag.");
			addFlag(serviceRows, "Authentication required on cell", s.authenticationRequired,
					"Last explicitly decoded security optional-field value.");
			addFlag(serviceRows, "Security Class 1 supported", s.securityClass1Supported,
					"Last explicitly decoded security optional-field value.");
			addFlag(serviceRows, "Security Class 3 supported", s.securityClass3Supported,
					"Last explicitly decoded security optional-field value.");
			if (cell.network != null) {
				TetraCellInfo.NetworkBroadcast n = cell.network;
				add(serviceRows, "Cell reselection parameters", hex(n.cellReselectionParameters, 4),
						"16-bit value retained from D-NWRK-BROADCAST.");
				add(serviceRows, "Serving-cell service level", n.cellServiceLevel,
						"Two-bit Cell_service_level from D-NWRK-BROADCAST.");
				add(serviceRows, "Network time", optionalHex(n.networkTime, 6),
						"Optional 24-bit network-time field.");
				add(serviceRows, "Local time offset", n.localTimeOffset < 0 ? "--"
						: (n.localTimeOffsetSign == 0 ? "+" : "-") + n.localTimeOffset,
						"Optional sign and 23-bit local-time offset field.");
				add(serviceRows, "Advertised neighbour count", optional(n.neighbourCount),
						"Count in the most recent D-NWRK-BROADCAST; learned cells are merged by Cell ID.");
				add(serviceRows, "Last network broadcast", time(n.timestampMillis),
						"Timestamp of the most recent decoded D-NWRK-BROADCAST.");
			}

			List<TetraCellInfo.Neighbour> neighbours =
					new ArrayList<TetraCellInfo.Neighbour>(cell.neighbours);
			Collections.sort(neighbours, new Comparator<TetraCellInfo.Neighbour>() {
				@Override public int compare(TetraCellInfo.Neighbour left,
						TetraCellInfo.Neighbour right) {
					return Integer.compare(left.cellId, right.cellId);
				}
			});
			for (TetraCellInfo.Neighbour n : neighbours) {
				neighbourRows.add(new Object[] { Integer.toString(n.cellId), mhz(n.carrierHz),
						Integer.toString(n.mainCarrier), optional(n.frequencyBand), optional(n.offset),
						optional(n.duplexSpacing), n.reverseOperation ? "Reverse" : "Normal",
						Integer.toString(n.reselectionTypes), yesNo(n.synchronizedCell),
						Integer.toString(n.serviceLevel), optional(n.mcc), optional(n.mnc),
						optional(n.locationArea), optional(n.maxTransmitPower),
						optional(n.minRxAccessLevel), optionalHex(n.subscriberClass, 4),
						optionalHex(n.serviceDetails, 3), optionalHex(n.timeshare, 2),
						optional(n.tdmaFrameOffset), time(n.timestampMillis) });
			}
		}
		syncRows(cellModel, cellRows);
		syncRows(servicesModel, serviceRows);
		syncRows(neighbourModel, neighbourRows);
	}

	private JPanel neighbourPanel() {
		JTable table = styledTable(neighbourModel);
		table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
		for (int n = 0; n < NEIGHBOUR_WIDTHS.length; n++)
			table.getColumnModel().getColumn(n).setPreferredWidth(NEIGHBOUR_WIDTHS[n]);
		return wrap(table);
	}

	private static JPanel parameterPanel(DefaultTableModel model) {
		JTable table = styledTable(model);
		table.getColumnModel().getColumn(0).setPreferredWidth(210);
		table.getColumnModel().getColumn(1).setPreferredWidth(240);
		table.getColumnModel().getColumn(2).setPreferredWidth(620);
		return wrap(table);
	}

	private static JTable styledTable(DefaultTableModel model) {
		JTable table = new JTable(model);
		table.setFillsViewportHeight(true);
		table.setAutoCreateRowSorter(true);
		table.setBackground(new Color(0x161616));
		table.setForeground(Color.WHITE);
		table.setGridColor(new Color(0x3a3a3a));
		table.setSelectionBackground(new Color(0x245070));
		return table;
	}

	private static JPanel wrap(JTable table) {
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

	/** Update only changed cells so a high-rate analyzer refresh cannot flash the tables. */
	private static void syncRows(DefaultTableModel model, List<Object[]> rows) {
		int common = Math.min(model.getRowCount(), rows.size());
		for (int row = 0; row < common; row++) {
			Object[] values = rows.get(row);
			for (int column = 0; column < model.getColumnCount(); column++) {
				Object next = column < values.length ? values[column] : "";
				if (!Objects.equals(model.getValueAt(row, column), next))
					model.setValueAt(next, row, column);
			}
		}
		while (model.getRowCount() > rows.size()) model.removeRow(model.getRowCount() - 1);
		for (int row = common; row < rows.size(); row++) model.addRow(rows.get(row));
	}

	private static void add(List<Object[]> rows, String parameter, Object value, String meaning) {
		rows.add(new Object[] { parameter, value, meaning });
	}

	private static void addFlag(List<Object[]> rows, String parameter, int value, String meaning) {
		add(rows, parameter, value < 0 ? "--" : value == 0 ? "No" : "Yes", meaning);
	}

	private static String mhz(long hz) {
		return hz <= 0L ? "--" : String.format(Locale.US, "%.6f MHz", hz / 1e6);
	}

	private String time(long millis) {
		return millis <= 0L ? "--" : timestamp.format(new Date(millis));
	}

	private static String optional(int value) { return value < 0 ? "--" : Integer.toString(value); }
	private static String optionalHex(int value, int digits) { return value < 0 ? "--" : hex(value, digits); }
	private static String hex(int value, int digits) {
		return String.format(Locale.ROOT, "0x%0" + digits + "X", value);
	}
	private static String signed(long value) { return value >= 0L ? "+" + value : Long.toString(value); }
	private static String yesNo(boolean value) { return value ? "Yes" : "No"; }
}
