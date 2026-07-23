package jspectrumanalyzer.iq;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

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

/** Public BCCH information broadcast by the selected GSM cell. */
final class GsmBcchDetailsFrame extends JFrame {
	private final Supplier<GsmSignalAnalyzer.Result> resultSupplier;
	private final LongSupplier frequencySupplier;
	private final DoubleSupplier levelSupplier;
	private final DefaultTableModel serving = model("Parameter", "Value");
	private final DefaultTableModel neighbours = model("ARFCN", "Downlink", "Source");
	private final DefaultTableModel messages = model("Message", "Type", "Decoded content");
	private final DefaultTableModel timeslots = model("Slot", "Role", "Non-dummy", "Dummy", "Unknown", "Observed occupancy");
	private final JLabel timeslotTotal = new JLabel("Waiting for classified bursts");
	private final JLabel status = new JLabel("Waiting for CRC-valid System Information...");
	private final Timer timer;

	GsmBcchDetailsFrame(Supplier<GsmSignalAnalyzer.Result> resultSupplier,
			LongSupplier frequencySupplier, DoubleSupplier levelSupplier) {
		super("GSM BCCH / Cell Details");
		this.resultSupplier=resultSupplier; this.frequencySupplier=frequencySupplier; this.levelSupplier=levelSupplier;
		setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		status.setBorder(new EmptyBorder(8, 10, 8, 10));
		status.setForeground(Color.WHITE);
		getContentPane().setBackground(Color.BLACK);
		JTabbedPane tabs = new JTabbedPane();
		tabs.addTab("SERVING CELL", panel(serving));
		tabs.addTab("NEIGHBOUR CELLS", panel(neighbours));
		tabs.addTab("TIMESLOT LOAD", panel(timeslots, timeslotTotal));
		tabs.addTab("SYSTEM INFORMATION", panel(messages));
		add(status, BorderLayout.NORTH); add(tabs, BorderLayout.CENTER);
		setSize(820, 480); setMinimumSize(new Dimension(680, 350));
		timer = new Timer(500, e -> refresh()); timer.start();
	}

	@Override public void dispose() { timer.stop(); super.dispose(); }

	private void refresh() {
		GsmSignalAnalyzer.Result r = resultSupplier.get();
		if (r == null) return;
		serving.setRowCount(0); neighbours.setRowCount(0); messages.setRowCount(0); timeslots.setRowCount(0);
		long frequency = frequencySupplier.getAsLong();
		int arfcn = arfcnForDownlink(frequency);
		add(serving, "Selected downlink", formatFrequency(frequency));
		add(serving, "Serving ARFCN", arfcn < 0 ? "Unknown / off raster" : Integer.toString(arfcn));
		add(serving, "Signal level", String.format(Locale.US, "%.1f dBFS", levelSupplier.getAsDouble()));
		add(serving, "PLMN", value(r.mcc, r.mnc));
		PlmnDatabase.Entry plmn = PlmnDatabase.lookup(r.mcc, r.mnc);
		add(serving, "Country", plmn == null ? "--" : plmn.country);
		add(serving, "Operator", plmn == null ? "--" : plmn.networkName());
		add(serving, "Location Area Code", r.lac < 0 ? "--" : Integer.toString(r.lac));
		add(serving, "Cell ID", r.cellId < 0 ? "--" : Integer.toString(r.cellId));
		add(serving, "BSIC", r.bsic < 0 ? "--" : r.bsic + "  (NCC " + r.ncc + ", BCC " + r.bcc + ")");
		add(serving, "Frequency offset", String.format(Locale.US, "%+.0f Hz", r.cfoHz));
		boolean si13 = hasSystemInformation(r, 0x00);
		PacketOptions packet = decodePacketOptions(r);
		add(serving, "Radio access", "GSM");
		add(serving, "Packet service", si13 ? "GPRS advertised" : "Unknown - waiting for SI13");
		add(serving, "EDGE / EGPRS", packet.egprs < 0 ? (si13 ? "Unknown / not present in decoded SI13 path" : "Unknown - waiting for SI13")
				: packet.egprs != 0 ? "Supported" : "Not advertised");
		if (packet.egprs > 0) add(serving, "EGPRS packet channel request",
				packet.egprsPacketRequest < 0 ? "Not included" : packet.egprsPacketRequest != 0 ? "Supported" : "Not supported");
		if (packet.rac >= 0) add(serving, "Routing Area Code", Integer.toString(packet.rac));
		if (packet.networkControlOrder >= 0) add(serving, "Network Control Order", "NC" + packet.networkControlOrder);
		if (packet.pbcch >= 0) add(serving, "PBCCH", packet.pbcch != 0 ? "Present" : "Not present");
		add(serving, "System Information 13", si13 ? "FIRE CRC OK" : "Not received yet");

		GsmSignalAnalyzer.TimeslotLoad load=r.timeslotLoad;
		long trafficActive=0, trafficKnown=0;
		for (int tn=0;tn<8;tn++) {
			long active=load.nonDummy[tn], dummy=load.dummy[tn], unknown=load.unknown[tn], known=active+dummy;
			String occupancy=known==0 ? "--" : String.format(Locale.US,"%.1f %%",100d*active/known);
			timeslots.addRow(new Object[]{"TS"+tn,tn==0 ? "BCCH / CCCH / system" : "Tuned carrier slot",
					active,dummy,unknown,occupancy});
			if (tn>0) { trafficActive+=active; trafficKnown+=known; }
		}
		String total=trafficKnown==0 ? "Waiting for classified bursts" : String.format(Locale.US,
				"Recent tuned-carrier occupancy (TS1-TS7, rolling 3 s): %.1f %% — %d classified bursts",
				100d*trafficActive/trafficKnown,trafficKnown);
		timeslotTotal.setText(total);

		List<Integer> seen = new ArrayList<Integer>();
		for (GsmSignalAnalyzer.SystemInformation si : r.systemInformation) {
			String name = name(si.messageType);
			String summary = "CRC OK";
			if (si.messageType == 0x19 || si.messageType == 0x1a || si.messageType == 0x02 || si.messageType == 0x03) {
				List<Integer> list = bitmapZero(si.message);
				if (list == null) summary = "CRC OK; range-encoded ARFCN list (decoder pending)";
				else {
					summary = "CRC OK; " + list.size() + " ARFCN" + (list.size() == 1 ? "" : "s");
					if (si.messageType != 0x19) for (Integer n : list) if (!seen.contains(n)) {
						seen.add(n); neighbours.addRow(new Object[] { n, formatFrequency(downlinkForArfcn(n)), name });
					}
				}
			} else if (si.messageType == 0x1b) summary = "CRC OK; Cell ID, PLMN, LAC and cell options";
			messages.addRow(new Object[] { name, String.format("0x%02X", si.messageType), summary });
		}
		status.setText(r.systemInformation.isEmpty() ? "Waiting for System Information; SCH/BCCH acquisition continues..."
				: r.systemInformation.size() + " System Information message type(s) decoded; " + seen.size() + " GSM neighbours advertised");
	}

	private static List<Integer> bitmapZero(byte[] message) {
		if (message == null || message.length < 19 || (message[3] & 0xc0) != 0) return null;
		List<Integer> result = new ArrayList<Integer>();
		for (int n = 124; n >= 1; n--) {
			int position=124-n, octet, bit;
			if (position < 4) { octet=3; bit=3-position; }
			else { int p=position-4; octet=4+p/8; bit=7-p%8; }
			if (((message[octet] >>> bit) & 1) != 0) result.add(n);
		}
		Collections.sort(result); return result;
	}

	private static int arfcnForDownlink(long hz) {
		double mhz=hz/1e6, n;
		if (mhz >= 935.0 && mhz <= 959.8) n=(mhz-935.0)/0.2;
		else if (mhz >= 925.2 && mhz <= 934.8) n=975+(mhz-925.2)/0.2;
		else if (mhz >= 1805.2 && mhz <= 1879.8) n=512+(mhz-1805.2)/0.2;
		else return -1;
		int rounded=(int)Math.round(n); return Math.abs(n-rounded) <= 0.26 ? rounded : -1;
	}

	private static long downlinkForArfcn(int n) {
		if (n >= 1 && n <= 124) return Math.round((935.0 + 0.2*n)*1e6);
		if (n >= 975 && n <= 1023) return Math.round((925.2 + 0.2*(n-975))*1e6);
		if (n >= 512 && n <= 885) return Math.round((1805.2 + 0.2*(n-512))*1e6);
		return -1;
	}

	private static String name(int type) {
		switch (type) {
		case 0x19:return "SI1"; case 0x1a:return "SI2"; case 0x1b:return "SI3"; case 0x1c:return "SI4";
		case 0x1d:return "SI5"; case 0x1e:return "SI6"; case 0x02:return "SI2bis"; case 0x03:return "SI2ter";
		case 0x07:return "SI2quater"; case 0x00:return "SI13"; default:return "System Information";
		}
	}

	private static String value(String mcc, String mnc) { return mcc.length()==0 ? "--" : mcc+"-"+mnc; }
	private static boolean hasSystemInformation(GsmSignalAnalyzer.Result result, int type) {
		for (GsmSignalAnalyzer.SystemInformation si : result.systemInformation)
			if (si.messageType == type) return true;
		return false;
	}

	private static PacketOptions decodePacketOptions(GsmSignalAnalyzer.Result result) {
		for (GsmSignalAnalyzer.SystemInformation si : result.systemInformation) if (si.messageType == 0x00) {
			try {
				/* SI13 Rest Octets, TS 44.018 10.5.2.37b. The first H selects
				 * the populated branch. Ordinary CSN.1 fields are read MSB first. */
				CsnBits bits = new CsnBits(si.message, 3);
				if (bits.read(1) == 0) return PacketOptions.UNKNOWN;
				bits.skip(3 + 4); // BCCH_CHANGE_MARK, SI_CHANGE_FIELD
				if (bits.read(1) != 0) return PacketOptions.UNKNOWN; // optional mobile allocation is variable length
				int pbcch = bits.read(1);
				if (pbcch != 0) return new PacketOptions(-1, -1, -1, -1, 1);
				int rac = bits.read(8);
				bits.skip(1 + 3); // SPGC_CCCH_SUP, PRIORITY_ACCESS_THR
				int nco = bits.read(2);
				bits.skip(2 + 3 + 3 + 3 + 1 + 1 + 4); // fixed GPRS Cell Options
				if (bits.read(1) != 0) bits.skip(9); // PAN_DEC, PAN_INC, PAN_MAX
				if (bits.read(1) == 0) return new PacketOptions(0, -1, rac, nco, 0);
				int extensionBits = bits.read(6) + 1;
				if (extensionBits < 1 || bits.remaining() < extensionBits)
					return new PacketOptions(-1, -1, rac, nco, 0);
				int egprs = bits.read(1);
				int packetRequest = -1;
				if (egprs != 0 && extensionBits >= 2) packetRequest = bits.read(1);
				return new PacketOptions(egprs, packetRequest, rac, nco, 0);
			} catch (IllegalArgumentException ignored) { return PacketOptions.UNKNOWN; }
		}
		return PacketOptions.UNKNOWN;
	}

	private static final class PacketOptions {
		static final PacketOptions UNKNOWN = new PacketOptions(-1, -1, -1, -1, -1);
		final int egprs, egprsPacketRequest, rac, networkControlOrder, pbcch;
		PacketOptions(int egprs, int egprsPacketRequest, int rac, int networkControlOrder, int pbcch) {
			this.egprs=egprs; this.egprsPacketRequest=egprsPacketRequest; this.rac=rac;
			this.networkControlOrder=networkControlOrder; this.pbcch=pbcch;
		}
	}

	private static final class CsnBits {
		final byte[] data; int position; final int limit;
		CsnBits(byte[] data, int byteOffset) { this.data=data; position=byteOffset*8; limit=data.length*8; }
		int remaining() { return limit-position; }
		void skip(int count) { read(count); }
		int read(int count) {
			if (count < 0 || count > 30 || remaining() < count) throw new IllegalArgumentException("truncated CSN.1");
			int value=0;
			for (int i=0;i<count;i++,position++) value=(value<<1)|((data[position>>3]>>(7-(position&7)))&1);
			return value;
		}
	}
	private static String formatFrequency(long hz) { return hz < 0 ? "--" : String.format(Locale.US, "%.3f MHz", hz/1e6); }
	private static void add(DefaultTableModel m,String a,String b) { m.addRow(new Object[]{a,b}); }
	private static DefaultTableModel model(String... columns) { return new DefaultTableModel(columns,0) { @Override public boolean isCellEditable(int r,int c){return false;} }; }
	private static JPanel panel(DefaultTableModel model) {
		return panel(model, null);
	}
	private static JPanel panel(DefaultTableModel model, JLabel footer) {
		JTable table=new JTable(model); table.setAutoCreateRowSorter(true); table.setFillsViewportHeight(true);
		table.setBackground(new Color(0x161616)); table.setForeground(Color.WHITE); table.setGridColor(new Color(0x3a3a3a));
		JScrollPane scroll=new JScrollPane(table); scroll.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
		JPanel panel=new JPanel(new BorderLayout()); panel.setBackground(Color.BLACK); panel.setBorder(new EmptyBorder(8,8,8,8)); panel.add(scroll);
		if (footer != null) {
			footer.setOpaque(true); footer.setBackground(new Color(0x202020)); footer.setForeground(Color.WHITE);
			footer.setBorder(new EmptyBorder(7,8,7,8)); panel.add(footer,BorderLayout.SOUTH);
		}
		return panel;
	}
}
