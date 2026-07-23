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

/** Anonymous paging observations: no subscriber identifier is retained or displayed. */
final class GsmPagingStatsFrame extends JFrame {
	private final Supplier<GsmSignalAnalyzer.Result> resultSupplier;
	private final DefaultTableModel eventModel = readOnlyModel(
			"UE", "Identity type", "Country", "Operator", "Home PLMN", "Relation", "Paging count", "First seen", "Last seen");
	private final DefaultTableModel summaryModel = readOnlyModel(
			"Country", "Operator", "Home PLMN", "Relation", "Paging events");
	private final JLabel status = new JLabel("Waiting for CRC-valid paging messages...");
	private final Timer timer;
	private long lastSequence = -1;

	GsmPagingStatsFrame(Supplier<GsmSignalAnalyzer.Result> resultSupplier) {
		super("GSM Paging Statistics");
		this.resultSupplier = resultSupplier;
		setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		getContentPane().setBackground(Color.BLACK);
		status.setForeground(Color.WHITE);
		status.setBorder(new EmptyBorder(8, 10, 8, 10));

		JTabbedPane tabs = new JTabbedPane();
		tabs.addTab("OBSERVATIONS", tablePanel(eventModel));
		tabs.addTab("COUNTRY / OPERATOR SUMMARY", tablePanel(summaryModel));
		add(status, BorderLayout.NORTH);
		add(tabs, BorderLayout.CENTER);
		setSize(980, 480);
		setMinimumSize(new Dimension(760, 350));
		timer = new Timer(500, e -> refresh());
		timer.start();
	}

	@Override public void dispose() { timer.stop(); super.dispose(); }

	private JPanel tablePanel(DefaultTableModel model) {
		JTable table = new JTable(model);
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
		panel.add(scroll, BorderLayout.CENTER);
		return panel;
	}

	private void refresh() {
		GsmSignalAnalyzer.Result result = resultSupplier.get();
		if (result == null) return;
		List<GsmSignalAnalyzer.PagingEvent> events = result.pagingEvents;
		long newest = events.isEmpty() ? -1 : events.get(events.size() - 1).sequence;
		if (newest == lastSequence) return;
		lastSequence = newest;
		eventModel.setRowCount(0);
		Map<String, Summary> summaries = new LinkedHashMap<>();
		SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss", Locale.US);
		Map<Integer, ClientRow> clients = new LinkedHashMap<>();
		for (GsmSignalAnalyzer.PagingEvent event : events) {
			String plmn = event.homeMcc.length() == 0 ? "--" : event.homeMcc + "-" + event.homeMnc;
			String relation = relation(event, result);
			ClientRow client = clients.get(event.clientNumber);
			if (client == null) {
				client = new ClientRow(event, plmn, relation);
				clients.put(event.clientNumber, client);
			}
			client.count++; client.lastSeen = event.timestampMillis;
			String key = plmn + '|' + relation;
			Summary summary = summaries.get(key);
			if (summary == null) {
				summary = new Summary(event.country, event.operator, plmn, relation);
				summaries.put(key, summary);
			}
			summary.events++;
		}
		for (ClientRow client : clients.values()) eventModel.addRow(new Object[] {
				"UE " + client.number, client.identityType, client.country, client.operator,
				client.plmn, client.relation, client.count, time.format(new Date(client.firstSeen)),
				time.format(new Date(client.lastSeen)) });
		summaryModel.setRowCount(0);
		for (Summary summary : summaries.values()) summaryModel.addRow(new Object[] {
				summary.country.length() == 0 ? "unknown" : summary.country,
				summary.operator.length() == 0 ? "unknown / temporary identity" : summary.operator,
				summary.plmn, summary.relation, summary.events });
		status.setText(String.format(Locale.US,
				"Anonymous UE this session: %d   recognized paging events: %d   groups: %d   serving cell: %s",
				clients.size(), events.size(), summaries.size(), result.bcchDecoded ? result.mcc + "-" + result.mnc : "unknown"));
	}

	private String relation(GsmSignalAnalyzer.PagingEvent event, GsmSignalAnalyzer.Result serving) {
		if (event.homeMcc.length() == 0 || !serving.bcchDecoded) return "UNKNOWN";
		return event.homeMcc.equals(serving.mcc) && event.homeMnc.equals(serving.mnc) ? "LOCAL" : "ROAMING";
	}

	private static DefaultTableModel readOnlyModel(String... columns) {
		return new DefaultTableModel(columns, 0) {
			private static final long serialVersionUID = 1L;
			@Override public boolean isCellEditable(int row, int column) { return false; }
		};
	}

	private static final class Summary {
		final String country, operator, plmn, relation;
		int events;
		Summary(String country, String operator, String plmn, String relation) {
			this.country=country; this.operator=operator; this.plmn=plmn; this.relation=relation;
		}
	}

	private static final class ClientRow {
		final int number; final String identityType, country, operator, plmn, relation;
		final long firstSeen; long lastSeen; int count;
		ClientRow(GsmSignalAnalyzer.PagingEvent event, String plmn, String relation) {
			this.number=event.clientNumber; this.identityType=event.identityType; this.country=event.country;
			this.operator=event.operator; this.plmn=plmn; this.relation=relation;
			this.firstSeen=event.timestampMillis; this.lastSeen=event.timestampMillis;
		}
	}
}
