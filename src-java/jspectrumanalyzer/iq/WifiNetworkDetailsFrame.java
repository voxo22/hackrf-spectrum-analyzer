package jspectrumanalyzer.iq;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.Supplier;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;

/** Details retained for the Wi-Fi network selected in the main network table. */
final class WifiNetworkDetailsFrame extends JFrame {
	private final Supplier<WifiNetworkTablePanel.NetworkDetails> networkSupplier;
	private final DefaultTableModel model = new DefaultTableModel(
			new String[] { "Parameter", "Value", "Meaning" }, 0) {
		@Override public boolean isCellEditable(int row, int column) { return false; }
	};
	private final JLabel status = new JLabel("Select a Wi-Fi network in the main window.");
	private final Timer timer;

	WifiNetworkDetailsFrame(Supplier<WifiNetworkTablePanel.NetworkDetails> networkSupplier) {
		super("Wi-Fi Signal Analyzer - AP / Client Details");
		this.networkSupplier = networkSupplier;
		setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		status.setBorder(new EmptyBorder(8, 10, 8, 10));
		status.setForeground(Color.WHITE);
		status.setOpaque(true);
		status.setBackground(Color.BLACK);

		JTable table = new JTable(model);
		table.setFillsViewportHeight(true);
		table.setAutoCreateRowSorter(true);
		table.setBackground(new Color(0x161616));
		table.setForeground(Color.WHITE);
		table.setGridColor(new Color(0x3a3a3a));
		table.setRowHeight(23);
		table.getColumnModel().getColumn(0).setPreferredWidth(190);
		table.getColumnModel().getColumn(1).setPreferredWidth(210);
		table.getColumnModel().getColumn(2).setPreferredWidth(390);
		JScrollPane scroll = new JScrollPane(table);
		scroll.getViewport().setBackground(Color.BLACK);
		scroll.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
		JPanel content = new JPanel(new BorderLayout());
		content.setBackground(Color.BLACK);
		content.setBorder(new EmptyBorder(8, 8, 8, 8));
		content.add(scroll, BorderLayout.CENTER);
		add(SignalTesterClipboard.createDetailsHeader(getContentPane(), status), BorderLayout.NORTH);
		add(content, BorderLayout.CENTER);
		setSize(820, 480);
		setMinimumSize(new Dimension(680, 360));
		timer = new Timer(500, e -> refresh());
		timer.start();
		refresh();
	}

	@Override public void dispose() {
		timer.stop();
		super.dispose();
	}

	private void refresh() {
		WifiNetworkTablePanel.NetworkDetails details = networkSupplier.get();
		model.setRowCount(0);
		if (details == null) {
			status.setText("Select a Wi-Fi network in the main window.");
			return;
		}
		WifiNetworkTablePanel.Network network = details.network;
		boolean client = "Client".equals(network.role);
		status.setText(network.displaySsid() + "  -  " + network.bssid + "  -  " + network.role
				+ "  -  session data from CRC-valid frames");
		add("Role", network.role, client ? "Client observed transmitting Probe Request frames."
				: "Access point identified by Beacon or Probe Response frames.");
		add(client ? "Requested SSIDs" : "SSID", network.displaySsid(), client
				? "Names requested by this client; <any network> denotes a wildcard scan."
				: "Network name advertised by the selected access point.");
		add(client ? "Client MAC" : "BSSID", network.bssid, client
				? "Source address carried by this client's Probe Requests."
				: "MAC address identifying the selected basic service set.");
		add("MAC address type", network.addressType,
				"Locally administered addresses are commonly randomized and have no reliable OUI owner.");
		add("Company / MAC vendor", network.macVendor,
				"Offline OUI/MA-L/MA-M/MA-S lookup; may identify a radio/module vendor rather than product brand.");
		add("RSSI", Double.isNaN(network.levelDbfs) ? "--"
				: String.format(Locale.US, "%.1f dBFS", network.levelDbfs),
				"Received level of the latest decoded frame; relative to receiver full scale.");
		if (client) {
			add("Observed channel", network.channel <= 0 ? "--" : Integer.toString(network.channel),
					"Channel on which this client Probe Request was received.");
			add("PHY observed", network.phyText(), "Physical layers used by decoded Probe Requests.");
			add("Standards/capabilities", network.standards,
					"Generations inferred from capability elements carried by Probe Requests.");
			add("Maximum advertised PHY", network.maxAdvertisedPhy,
					"Client capability advertised in Probe Requests when available.");
			add("Channel capability", network.channelCapability, "Channel widths advertised by the client.");
			add("WPS", network.wps, "WPS information carried by client Probe Requests.");
			add("WPS manufacturer", network.manufacturer, "Manufacturer advertised by WPS; may be unavailable.");
			add("Model", network.modelName, "Model advertised by WPS; may be unavailable.");
			add("Model number", network.modelNumber, "Model number advertised by WPS; may be unavailable.");
			add("Device name", network.deviceName, "Device name advertised by WPS; may be unavailable.");
			add("Legacy supported rates", network.supportedRates, "Legacy rates advertised by the client.");
			add("Latest PHY / rate", network.rate, "PHY and rate of the latest decoded Probe Request.");
			add("Probe requests", Long.toString(network.probeRequests), "CRC-valid Probe Requests observed this session.");
			add("Recent probe rate", String.format(Locale.US, "%.1f /min", network.probesPerMinute(System.currentTimeMillis())),
					"Observed Probe Requests in the most recent 60-second window.");
			add("Observed frame bytes", Long.toString(network.observedBytes), "Bytes in decoded Probe Requests only.");
			add("First seen", age(network.firstSeenMillis), "Elapsed time since this client first appeared.");
			add("Last seen", age(network.lastSeenMillis), "Elapsed time since the latest Probe Request.");
			return;
		}
		add("Advertised channel", network.channel <= 0 ? "--" : Integer.toString(network.channel),
				"Channel number carried in the network's management frame.");
		add("PHY observed", network.phyText(), "Physical layers actually decoded and assigned to this BSSID.");
		add("Standards", network.standards, "802.11 generations advertised in management capabilities.");
		add("Maximum advertised PHY", network.maxAdvertisedPhy,
				"Advertised theoretical PHY capability, not measured throughput.");
		add("Current channel width", network.channelWidth, "Operating width advertised by the AP.");
		add("Channel capability", network.channelCapability, "Advertised supported channel widths.");
		add("Security", network.security, "Authentication/privacy information advertised by the network.");
		add("WPS", network.wps, "WPS state advertised in vendor management information.");
		add("WPS manufacturer", network.manufacturer, "Manufacturer advertised by WPS; may differ from the MAC vendor.");
		add("Model", network.modelName, "Model name advertised by WPS; may be unavailable.");
		add("Model number", network.modelNumber, "Model number advertised by WPS; may be unavailable.");
		add("Device name", network.deviceName, "Device name advertised by WPS; may be unavailable.");
		add("Country", network.country, "Configured regulatory country code; not proof of physical location.");
		add("Advertised channel range", network.channelRange,
				"Supported Channels or Country information advertised by this BSSID.");
		add("Channel utilization", Double.isNaN(network.channelUtilizationPercent) ? "--"
				: String.format(Locale.US, "%.1f %%", network.channelUtilizationPercent),
				"QBSS load reported by the AP, not the analyzer's own RF energy estimate.");
		add("Associated stations", network.stationCount < 0 ? "--" : Integer.toString(network.stationCount),
				"Station count from the optional QBSS Load element.");
		add("Latest PHY / rate", network.rate, "Physical layer and rate of the latest decoded frame.");
		add("Legacy supported rates", network.supportedRates, "Legacy rates retained here for diagnostics.");
		add("Beacon interval", network.beaconIntervalTu <= 0 ? "--" : network.beaconIntervalTu + " TU",
				"Nominal time between beacon transmissions; one TU is 1024 microseconds.");
		add("TSF uptime", tsfDuration(network.timestampTsfMicros),
				"Beacon TSF timer; usually close to BSS uptime but not guaranteed router boot time.");
		add("Estimated BSS start", estimatedStart(network.timestampTsfMicros, network.lastCaptureMillis),
				"Capture time minus the latest TSF timer value; IQ replay uses the recording timeline.");
		add("Latest frame type", network.frameType, "MAC frame type that most recently updated this row.");
		add("Latest PSDU length", network.lengthBytes <= 0 ? "--" : network.lengthBytes + " B",
				"Decoded MAC protocol data unit length.");
		add("PHY correlation", String.format(Locale.US, "%.1f %%", network.correlation * 100d),
				"Preamble/training correlation of the latest decoded frame.");
		add("Frequency error", String.format(Locale.US, "%+.1f kHz", network.cfoHz / 1000d),
				"Estimated carrier-frequency offset for the latest decoded frame.");
		add("FCS", "OK", "Only frames with a valid MAC frame check sequence enter this network list.");
		add("Beacons", Long.toString(network.beacons), "CRC-valid beacons observed this session.");
		add("Probe responses", Long.toString(network.probeResponses), "CRC-valid probe responses observed this session.");
		add("Probe requests", Long.toString(network.probeRequests), "Probe requests attributable to this BSSID.");
		add("Recent beacon rate", String.format(Locale.US, "%.1f /s", network.beaconsPerSecond(System.currentTimeMillis())),
				"Observed beacons in the most recent five-second window.");
		add("Management / control / data", network.managementFrames + " / " + network.controlFrames + " / " + network.dataFrames,
				"CRC-valid frame classes attributed to this BSSID.");
		add("Observed frame bytes", Long.toString(network.observedBytes),
				"Bytes in decoded frames only; this is not total AP traffic or throughput.");
		add("Frames seen", Long.toString(network.seen), "All CRC-valid frames attributed to this BSSID this session.");
		add("First seen", age(network.firstSeenMillis), "Elapsed time since this network first entered the list.");
		add("Last seen", age(network.lastSeenMillis), "Elapsed time since the latest CRC-valid frame.");
	}

	private void add(String parameter, String value, String meaning) {
		model.addRow(new Object[] { parameter, value, meaning });
	}

	private static String age(long timestamp) {
		if (timestamp <= 0L) return "--";
		long millis = Math.max(0L, System.currentTimeMillis() - timestamp);
		if (millis < 1000L) return millis + " ms ago";
		return String.format(Locale.US, "%.1f s ago", millis / 1000d);
	}

	private static String tsfDuration(long micros) {
		if (micros < 0L) return "--";
		long seconds = micros / 1_000_000L, days = seconds / 86_400L;
		seconds %= 86_400L;
		return String.format(Locale.US, "%d d %02d:%02d:%02d", days,
				seconds / 3600L, seconds / 60L % 60L, seconds % 60L);
	}

	private static String estimatedStart(long micros, long capturedMillis) {
		if (micros < 0L || capturedMillis <= 0L) return "--";
		long timestamp = capturedMillis - micros / 1000L;
		return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(timestamp));
	}
}
