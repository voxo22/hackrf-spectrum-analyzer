package jspectrumanalyzer.iq;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

/** Session-scoped AP and probing-client list built from CRC-valid Wi-Fi frames. */
final class WifiNetworkTablePanel extends JPanel {
	private static final Color CLIENT_ROW = new Color(0x291f38);
	private static final String[] COLUMNS = {
			"SSID / requested SSID", "BSSID / client MAC", "RSSI", "Role", "PHY", "Standards",
			"Max advertised PHY", "Channel", "Current width", "Width capability", "Security", "WPS",
			"Channel util.", "Country", "Company (MAC)", "WPS manufacturer", "Model / device", "Latest frame",
			"Beacons", "Probe responses", "Probe requests", "Beacons/s", "Probe req./min",
			"Seen", "Estimated BSS start", "Last seen"
	};
	private final Map<String, Network> networks = new LinkedHashMap<String, Network>();
	private final Map<String, Network> clients = new LinkedHashMap<String, Network>();
	private final Map<String, Long> channelFrameTypes = new LinkedHashMap<String, Long>();
	private final List<String> rowKeys = new ArrayList<String>();
	private long channelManagementFrames, channelControlFrames, channelDataFrames;
	private String latestChannelFrame = "--";
	private final DefaultTableModel model = new DefaultTableModel(COLUMNS, 0) {
		@Override public boolean isCellEditable(int row, int column) { return false; }
		@Override public Class<?> getColumnClass(int column) {
			switch (column) {
			case 2: case 12: case 21: case 22: return Double.class;
			case 7: return Integer.class;
			case 18: case 19: case 20: case 23: case 25: return Long.class;
			default: return String.class;
			}
		}
	};
	private final JTable table = new JTable(model);
	private final JLabel status = new JLabel("Waiting for Wi-Fi beacons and probe requests...");

	WifiNetworkTablePanel() {
		super(new BorderLayout()); setBackground(Color.BLACK);
		table.setAutoCreateRowSorter(true); table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
		table.setRowHeight(23); table.setFillsViewportHeight(true); table.setBackground(Color.BLACK);
		table.setForeground(Color.WHITE); table.setGridColor(new Color(0x333333));
		table.setSelectionBackground(new Color(0x244b66)); table.setSelectionForeground(Color.WHITE);
		DefaultTableCellRenderer header = new DefaultTableCellRenderer();
		header.setHorizontalAlignment(SwingConstants.CENTER); header.setOpaque(true);
		header.setBackground(new Color(0x202020)); header.setForeground(Color.WHITE);
		header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, new Color(0x555555)));
		table.getTableHeader().setDefaultRenderer(header);
		int[] widths = {170,132,78,66,92,112,135,62,92,160,118,112,92,62,145,145,145,135,
				68,105,98,78,92,55,145,82};
		for (int n = 0; n < widths.length; n++) table.getColumnModel().getColumn(n).setPreferredWidth(widths[n]);
		DefaultTableCellRenderer centered = new ValueRenderer();
		for (int n = 0; n < COLUMNS.length; n++) table.getColumnModel().getColumn(n).setCellRenderer(centered);
		status.setBorder(BorderFactory.createEmptyBorder(3, 7, 3, 7)); status.setForeground(new Color(0xdddddd));
		status.setBackground(Color.BLACK); status.setOpaque(true);
		JScrollPane scroll = new JScrollPane(table); scroll.getViewport().setBackground(Color.BLACK);
		scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS);
		scroll.setPreferredSize(new Dimension(1000, 300)); add(scroll, BorderLayout.CENTER); add(status, BorderLayout.SOUTH);
		table.getRowSorter().setSortKeys(java.util.Collections.singletonList(
				new RowSorter.SortKey(2, SortOrder.DESCENDING)));
	}

	synchronized void observe(WifiDsssAnalyzer.Result result) {
		observe(result, result == null ? 0L : result.decodedMillis);
	}
	synchronized void observe(WifiDsssAnalyzer.Result result, long captureMillis) {
		if (result == null || !result.fcsValid) return;
		observe(result.mac, "DSSS", "DSSS " + result.rate, result.levelDbfs,
				result.barkerCorrelation, result.cfoHz, result.decodedMillis, captureMillis);
	}
	synchronized void observe(WifiSignalAnalyzer.Observation observation) {
		observe(observation, observation == null ? 0L : observation.decodedMillis);
	}
	synchronized void observe(WifiSignalAnalyzer.Observation observation, long captureMillis) {
		if (observation == null || observation.mac == null || !observation.mac.fcsValid) return;
		observe(observation.mac, "OFDM", "OFDM " + observation.rate, observation.levelDbfs,
				observation.correlation, observation.cfoHz, observation.decodedMillis, captureMillis);
	}

	private void observe(WifiMacFrame frame, String phy, String rate, double levelDbfs,
			double correlation, double cfoHz, long decodedMillis, long captureMillis) {
		Long count = channelFrameTypes.get(frame.frameType);
		channelFrameTypes.put(frame.frameType, Long.valueOf(count == null ? 1L : count.longValue() + 1L));
		if ("Management".equals(frame.frameClass)) channelManagementFrames++;
		else if ("Control".equals(frame.frameClass)) channelControlFrames++;
		else if ("Data".equals(frame.frameClass)) channelDataFrames++;
		latestChannelFrame = phy + " " + frame.frameType;

		if ("Probe request".equals(frame.frameType) && usableAddress(frame.source)) {
			String address = frame.source;
			Network client = clients.get(address);
			if (client == null) {
				client = new Network("C:" + address, "Client", address);
				client.firstSeenMillis = decodedMillis;
				clients.put(address, client);
			}
			client.updateClient(frame, phy, rate, levelDbfs, correlation, cfoHz, decodedMillis);
			return;
		}

		String address = frame.bssid;
		if (!usableAddress(address)) return;
		boolean announcement = "Beacon".equals(frame.frameType) || "Probe response".equals(frame.frameType);
		Network network = networks.get(address);
		if (network == null && !announcement) return;
		if (network == null) {
			network = new Network("A:" + address, "AP", address);
			network.firstSeenMillis = decodedMillis;
			networks.put(address, network);
		}
		network.updateAp(frame, phy, rate, levelDbfs, correlation, cfoHz, decodedMillis, captureMillis);
	}

	private static boolean usableAddress(String address) {
		return address != null && !"--".equals(address) && !"ff:ff:ff:ff:ff:ff".equals(address)
				&& !"00:00:00:00:00:00".equals(address);
	}

	synchronized int networkCount() { return networks.size(); }
	synchronized int clientCount() { return clients.size(); }
	synchronized String latestChannelFrame() { return latestChannelFrame; }
	synchronized String channelClassSummary() {
		return "Management " + channelManagementFrames + " / Control " + channelControlFrames
				+ " / Data " + channelDataFrames;
	}
	synchronized String[] channelTypeSummaryRows() {
		List<String> values = new ArrayList<String>();
		for (Map.Entry<String,Long> entry : channelFrameTypes.entrySet())
			values.add(entry.getKey() + " " + entry.getValue());
		if (values.isEmpty()) return new String[]{"--", "", ""};
		int perRow = (values.size() + 2) / 3;
		int second = Math.min(values.size(), perRow), third = Math.min(values.size(), perRow * 2);
		return new String[]{joinValues(values.subList(0, second)),
				second < values.size() ? joinValues(values.subList(second, third)) : "",
				third < values.size() ? joinValues(values.subList(third, values.size())) : ""};
	}
	private static String joinValues(List<String> values) {
		StringBuilder out = new StringBuilder();
		for (String value : values) { if (out.length() > 0) out.append(", "); out.append(value); }
		return out.toString();
	}

	/** Called by the Swing timer, therefore all table model changes stay on the EDT. */
	void refresh() {
		List<Network> snapshot = new ArrayList<Network>();
		synchronized (this) {
			for (Network network : networks.values()) snapshot.add(new Network(network));
			for (Network client : clients.values()) snapshot.add(new Network(client));
		}
		String selected = selectedKey(); model.setRowCount(0); rowKeys.clear(); long now = System.currentTimeMillis();
		for (Network n : snapshot) {
			rowKeys.add(n.key);
			model.addRow(new Object[]{n.displaySsid(), n.bssid, n.levelDbfs, n.role, n.phyText(), n.standards,
				n.maxAdvertisedPhy, Integer.valueOf(n.channel), n.channelWidth, n.channelCapability,
				n.security, n.wps, n.channelUtilizationPercent, n.country, n.macVendor, n.manufacturer,
				n.modelDevice(), n.frameType, Long.valueOf(n.beacons), Long.valueOf(n.probeResponses),
				Long.valueOf(n.probeRequests), n.beaconsPerSecond(now), n.probesPerMinute(now),
				Long.valueOf(n.seen), n.estimatedStart(), Long.valueOf(n.lastSeenMillis)});
		}
		if (selected != null) for (int row = 0; row < model.getRowCount(); row++)
			if (selected.equals(rowKeys.get(row))) {
				int view = table.convertRowIndexToView(row); if (view >= 0) table.setRowSelectionInterval(view, view); break;
			}
		if (table.getSelectedRow() < 0 && model.getRowCount() > 0) {
			int view = table.convertRowIndexToView(0); if (view >= 0) table.setRowSelectionInterval(view, view);
		}
		status.setText(networkCount() + " APs + " + clientCount()
				+ " clients captured - purple rows are probing clients");
	}

	private String selectedKey() {
		int view = table.getSelectedRow(); if (view < 0) return null;
		int row = table.convertRowIndexToModel(view);
		return row >= 0 && row < rowKeys.size() ? rowKeys.get(row) : null;
	}
	synchronized NetworkDetails selectedNetwork() {
		String key = selectedKey(); if (key == null || key.length() < 3) return null;
		Network value = key.startsWith("C:") ? clients.get(key.substring(2)) : networks.get(key.substring(2));
		return value == null ? null : new NetworkDetails(new Network(value));
	}

	static final class NetworkDetails { final Network network; NetworkDetails(Network network) { this.network = network; } }

	static final class Network {
		final String key, role;
		String ssid = "--", bssid, macVendor, addressType;
		String security = "--", rate = "--", frameType = "--", supportedRates = "--";
		String standards = "--", maxAdvertisedPhy = "--", channelWidth = "--", channelCapability = "--";
		String wps = "Not advertised", country = "Not advertised", channelRange = "--", manufacturer = "--";
		String modelName = "--", modelNumber = "--", deviceName = "--";
		int channel, beaconIntervalTu, lengthBytes, stationCount = -1;
		double levelDbfs = Double.NaN, correlation, cfoHz, channelUtilizationPercent = Double.NaN;
		long timestampTsfMicros = -1L, seen, beacons, probeResponses, probeRequests;
		long managementFrames, controlFrames, dataFrames, observedBytes;
		long firstSeenMillis, lastSeenMillis, lastCaptureMillis;
		final Set<String> phySeen = new LinkedHashSet<String>();
		final Set<String> requestedSsids = new LinkedHashSet<String>();
		final ArrayDeque<Long> recentBeacons = new ArrayDeque<Long>();
		final ArrayDeque<Long> recentProbeRequests = new ArrayDeque<Long>();

		Network(String key, String role, String address) {
			this.key = key; this.role = role; this.bssid = address;
			this.macVendor = WifiOuiLookup.company(address); this.addressType = WifiOuiLookup.addressType(address);
		}
		Network(Network o) {
			key=o.key;role=o.role;ssid=o.ssid;bssid=o.bssid;macVendor=o.macVendor;addressType=o.addressType;
			security=o.security;rate=o.rate;frameType=o.frameType;supportedRates=o.supportedRates;standards=o.standards;
			maxAdvertisedPhy=o.maxAdvertisedPhy;channelWidth=o.channelWidth;channelCapability=o.channelCapability;
			wps=o.wps;country=o.country;channelRange=o.channelRange;manufacturer=o.manufacturer;modelName=o.modelName;
			modelNumber=o.modelNumber;deviceName=o.deviceName;channel=o.channel;beaconIntervalTu=o.beaconIntervalTu;
			lengthBytes=o.lengthBytes;stationCount=o.stationCount;levelDbfs=o.levelDbfs;correlation=o.correlation;
			cfoHz=o.cfoHz;channelUtilizationPercent=o.channelUtilizationPercent;timestampTsfMicros=o.timestampTsfMicros;
			seen=o.seen;beacons=o.beacons;probeResponses=o.probeResponses;probeRequests=o.probeRequests;
			managementFrames=o.managementFrames;controlFrames=o.controlFrames;dataFrames=o.dataFrames;
			observedBytes=o.observedBytes;firstSeenMillis=o.firstSeenMillis;
			lastSeenMillis=o.lastSeenMillis;lastCaptureMillis=o.lastCaptureMillis;phySeen.addAll(o.phySeen);
			requestedSsids.addAll(o.requestedSsids);recentBeacons.addAll(o.recentBeacons);
			recentProbeRequests.addAll(o.recentProbeRequests);
		}

		void updateAp(WifiMacFrame f, String phy, String frameRate, double level, double corr,
				double error, long time, long captureTime) {
			boolean announcement = "Beacon".equals(f.frameType) || "Probe response".equals(f.frameType);
			updateCommon(f, phy, frameRate, level, corr, error, time);
			if (announcement) {
				if (!"--".equals(f.ssid)) ssid=f.ssid;
				if (f.beaconIntervalTu>0) beaconIntervalTu=f.beaconIntervalTu;
				if (!"--".equals(f.security)) security=f.security;
				updateCapabilities(f);
				if (!"--".equals(f.country)) country=f.country;
				if (!"--".equals(f.channelRange)) channelRange=f.channelRange;
				if (f.stationCount>=0) stationCount=f.stationCount;
				if (Double.isFinite(f.channelUtilizationPercent)) channelUtilizationPercent=f.channelUtilizationPercent;
				if (f.timestampTsfMicros>=0) { timestampTsfMicros=f.timestampTsfMicros; lastCaptureMillis=captureTime>0L?captureTime:time; }
			}
			if ("Beacon".equals(f.frameType)) { beacons++; recentBeacons.addLast(time); }
			else if ("Probe response".equals(f.frameType)) probeResponses++;
			else if ("Probe request".equals(f.frameType)) probeRequests++;
			prune(time);
		}

		void updateClient(WifiMacFrame f, String phy, String frameRate, double level, double corr,
				double error, long time) {
			updateCommon(f, phy, frameRate, level, corr, error, time);
			if (!"--".equals(f.ssid)) requestedSsids.add(f.ssid);
			updateCapabilities(f); probeRequests++; recentProbeRequests.addLast(time); prune(time);
		}

		private void updateCommon(WifiMacFrame f, String phy, String frameRate, double level,
				double corr, double error, long time) {
			rate=frameRate;frameType=f.frameType;phySeen.add(phy);if(f.channel>0)channel=f.channel;
			lengthBytes=f.lengthBytes;levelDbfs=level;correlation=corr;cfoHz=error;seen++;observedBytes+=f.lengthBytes;lastSeenMillis=time;
			if("Management".equals(f.frameClass))managementFrames++;else if("Control".equals(f.frameClass))controlFrames++;else if("Data".equals(f.frameClass))dataFrames++;
		}
		private void updateCapabilities(WifiMacFrame f) {
			if(!"--".equals(f.supportedRates))supportedRates=f.supportedRates;if(!"--".equals(f.standards))standards=f.standards;
			if(!"--".equals(f.maxAdvertisedPhy))maxAdvertisedPhy=f.maxAdvertisedPhy;if(!"--".equals(f.channelWidth))channelWidth=f.channelWidth;
			if(!"--".equals(f.channelCapability))channelCapability=f.channelCapability;if(!"Not advertised".equals(f.wps))wps=f.wps;
			if(!"--".equals(f.manufacturer))manufacturer=f.manufacturer;if(!"--".equals(f.modelName))modelName=f.modelName;
			if(!"--".equals(f.modelNumber))modelNumber=f.modelNumber;if(!"--".equals(f.deviceName))deviceName=f.deviceName;
		}

		String displaySsid() { return "Client".equals(role) ? requestedSsidText() : ssid; }
		String requestedSsidText() { return requestedSsids.isEmpty() ? "--" : join(requestedSsids, ", "); }
		String phyText() { return phySeen.isEmpty() ? "--" : join(phySeen, " + "); }
		String modelDevice() {
			String model="--".equals(modelName)?("--".equals(modelNumber)?"":modelNumber):modelName;
			String device="--".equals(deviceName)?"":deviceName;
			return model.length()==0?(device.length()==0?"--":device):device.length()==0||device.equals(model)?model:model+" / "+device;
		}
		String estimatedStart() {
			if(timestampTsfMicros<0L||lastCaptureMillis<=0L)return "--";
			return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(lastCaptureMillis-timestampTsfMicros/1000L));
		}
		double beaconsPerSecond(long now) { prune(now); return recentBeacons.size()/5d; }
		double probesPerMinute(long now) { prune(now); return recentProbeRequests.size(); }
		private void prune(long now) {
			while(!recentBeacons.isEmpty()&&now-recentBeacons.peekFirst()>5000L)recentBeacons.removeFirst();
			while(!recentProbeRequests.isEmpty()&&now-recentProbeRequests.peekFirst()>60000L)recentProbeRequests.removeFirst();
		}
		private static String join(Iterable<String> values,String separator) { StringBuilder out=new StringBuilder();for(String value:values){if(out.length()>0)out.append(separator);out.append(value);}return out.toString(); }
	}

	private static final class ValueRenderer extends DefaultTableCellRenderer {
		private int column;
		private boolean lastSeenColumn;
		ValueRenderer() { setHorizontalAlignment(SwingConstants.CENTER); }
		@Override protected void setValue(Object value) {
			if(value instanceof Double){double n=((Double)value).doubleValue();if(column==2)value=Double.isNaN(n)?"--":String.format(Locale.US,"%.1f dBFS",n);else if(column==12)value=Double.isNaN(n)?"--":String.format(Locale.US,"%.1f %%",n);else if(column==21)value=String.format(Locale.US,"%.1f/s",n);else if(column==22)value=String.format(Locale.US,"%.1f/min",n);}
			else if(value instanceof Integer&&column==7&&((Integer)value).intValue()<=0)value="--";
			else if(value instanceof Long&&lastSeenColumn){long age=Math.max(0L,System.currentTimeMillis()-((Long)value).longValue());value=age<1000?age+" ms":String.format(Locale.US,"%.1f s",age/1000d);}super.setValue(value);
		}
		@Override public Component getTableCellRendererComponent(JTable table,Object value,boolean selected,boolean focus,int row,int column) {
			this.column=column;this.lastSeenColumn="Last seen".equals(table.getColumnName(column));
			Component component=super.getTableCellRendererComponent(table,value,selected,focus,row,column);
			if(!selected){int modelRow=table.convertRowIndexToModel(row);Object role=table.getModel().getValueAt(modelRow,3);component.setBackground("Client".equals(role)?CLIENT_ROW:Color.BLACK);component.setForeground(Color.WHITE);}return component;
		}
	}
}
