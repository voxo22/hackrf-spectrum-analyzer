package jspectrumanalyzer.iq;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

/** Session-only anonymous UMTS Paging Type 1 observations for the selected PSC. */
final class UmtsPagingStatsFrame extends JFrame {
	private final Supplier<UmtsSignalAnalyzer.Candidate> cellSupplier;
	private final DefaultTableModel eventModel = model(
			"UE", "Identity type", "Domain", "Cause", "Country", "Operator",
			"Home PLMN", "Relation", "Paging count", "First seen", "Last seen");
	private final DefaultTableModel summaryModel = model(
			"Domain", "Cause", "Country", "Operator", "Home PLMN", "Relation", "Paging events");
	private final JLabel status = new JLabel("Waiting for CRC-valid UMTS paging messages...");
	private final Timer timer;
	private long lastSequence = -1;
	private int lastPsc = -1;

	UmtsPagingStatsFrame(Supplier<UmtsSignalAnalyzer.Candidate> cellSupplier) {
		super("UMTS Paging Statistics");
		this.cellSupplier = cellSupplier;
		setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		getContentPane().setBackground(Color.BLACK);
		status.setForeground(Color.WHITE);
		status.setBorder(new EmptyBorder(8, 10, 8, 10));
		JTabbedPane tabs = new JTabbedPane();
		tabs.addTab("OBSERVATIONS", tablePanel(eventModel));
		tabs.addTab("COUNTRY / OPERATOR SUMMARY", tablePanel(summaryModel));
		add(status, BorderLayout.NORTH);
		add(tabs, BorderLayout.CENTER);
		setSize(1080, 480);
		setMinimumSize(new Dimension(780, 350));
		timer = new Timer(500, e -> refresh());
		timer.start();
		refresh();
	}

	@Override public void dispose() { timer.stop(); super.dispose(); }

	private static JPanel tablePanel(DefaultTableModel data) {
		JTable table = new JTable(data);
		table.setAutoCreateRowSorter(true);
		table.setFillsViewportHeight(true);
		table.setBackground(new Color(0x161616));
		table.setForeground(Color.WHITE);
		table.setGridColor(new Color(0x3a3a3a));
		table.setSelectionBackground(new Color(0x285577));
		JScrollPane scroll = new JScrollPane(table);
		scroll.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
		JPanel panel = new JPanel(new BorderLayout());
		panel.setBackground(Color.BLACK);
		panel.setBorder(new EmptyBorder(8, 8, 8, 8));
		panel.add(scroll);
		return panel;
	}

	private void refresh() {
		UmtsSignalAnalyzer.Candidate cell = cellSupplier.get();
		if (cell == null) {
			status.setText("Select a UMTS PSC in the main table.");
			return;
		}
		List<UmtsPagingDecoder.Event> events = cell.paging.paging.events;
		long newest = events.isEmpty() ? -1 : events.get(events.size() - 1).sequence;
		if (cell.psc == lastPsc && newest == lastSequence) return;
		lastPsc = cell.psc;
		lastSequence = newest;
		eventModel.setRowCount(0);
		summaryModel.setRowCount(0);
		Map<Integer, Client> clients = new LinkedHashMap<Integer, Client>();
		Map<String, Summary> summaries = new LinkedHashMap<String, Summary>();
		for (UmtsPagingDecoder.Event event : events) {
			String plmn = event.homeMcc.length() == 0 ? "--" : event.homeMcc + "-" + event.homeMnc;
			String relation = relation(event, cell);
			Client client = clients.get(event.clientNumber);
			if (client == null) {
				client = new Client(event, plmn, relation);
				clients.put(event.clientNumber, client);
			}
			client.count++;
			client.lastSeen = event.timestampMillis;
			String key = event.domain + '|' + event.cause + '|' + plmn + '|' + relation;
			Summary summary = summaries.get(key);
			if (summary == null) {
				summary = new Summary(event, plmn, relation);
				summaries.put(key, summary);
			}
			summary.count++;
		}
		SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss", Locale.US);
		for (Client client : clients.values()) eventModel.addRow(new Object[] {
				"UE " + client.number, client.type, client.domain, client.cause,
				blank(client.country), blank(client.operator), client.plmn, client.relation,
				client.count, time.format(new Date(client.firstSeen)), time.format(new Date(client.lastSeen)) });
		for (Summary summary : summaries.values()) summaryModel.addRow(new Object[] {
				summary.domain, summary.cause, blank(summary.country), blank(summary.operator),
				summary.plmn, summary.relation, summary.count });
		status.setText(String.format(Locale.US,
				"PSC %d   anonymous UE this session: %d   paging records: %d   CRC-valid PCH blocks: %d",
				cell.psc, clients.size(), events.size(), cell.paging.blocks.size()));
	}

	private static String relation(UmtsPagingDecoder.Event event, UmtsSignalAnalyzer.Candidate cell) {
		if (event.homeMcc.length() == 0 || !cell.systemInformation.mibValid) return "UNKNOWN";
		return event.homeMcc.equals(cell.systemInformation.mcc)
				&& event.homeMnc.equals(cell.systemInformation.mnc) ? "LOCAL" : "ROAMING";
	}

	private static String blank(String value) { return value.length() == 0 ? "unknown" : value; }

	private static DefaultTableModel model(String... columns) {
		return new DefaultTableModel(columns, 0) {
			private static final long serialVersionUID = 1L;
			@Override public boolean isCellEditable(int row, int column) { return false; }
		};
	}

	private static final class Client {
		final int number;
		final String type, domain, cause, country, operator, plmn, relation;
		final long firstSeen;
		long lastSeen;
		int count;
		Client(UmtsPagingDecoder.Event event, String plmn, String relation) {
			this.number = event.clientNumber; this.type = event.identityType; this.domain = event.domain;
			this.cause = event.cause; this.country = event.country; this.operator = event.operator;
			this.plmn = plmn; this.relation = relation;
			this.firstSeen = event.timestampMillis; this.lastSeen = event.timestampMillis;
		}
	}

	private static final class Summary {
		final String domain, cause, country, operator, plmn, relation;
		int count;
		Summary(UmtsPagingDecoder.Event event, String plmn, String relation) {
			this.domain = event.domain; this.cause = event.cause; this.country = event.country;
			this.operator = event.operator; this.plmn = plmn; this.relation = relation;
		}
	}
}
