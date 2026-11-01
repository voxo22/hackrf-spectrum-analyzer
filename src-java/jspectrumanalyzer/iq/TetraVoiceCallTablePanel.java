package jspectrumanalyzer.iq;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

/** Stable call-state view built from clear downlink CMCE signalling. */
final class TetraVoiceCallTablePanel extends JPanel {
	private static final int MAX_CALLS = 2_000;
	private static final Color BACKGROUND = new Color(0x101010);
	private static final Color GRID = new Color(0x303030);
	private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);
	private final List<CallRecord> calls = new ArrayList<CallRecord>();
	private final Map<Integer, CallRecord> latestById = new HashMap<Integer, CallRecord>();
	private final DefaultTableModel model = new DefaultTableModel(new Object[] {
			"Started", "Last seen", "State", "Activity", "Call ID", "Called SSI / address",
			"Calling / talking SSI", "Service", "Mode", "Security", "Last event"
	}, 0) {
		@Override public boolean isCellEditable(int row, int column) { return false; }
		@Override public Class<?> getColumnClass(int column) {
			return column >= 4 && column <= 6 ? Integer.class : String.class;
		}
	};
	private final JTable table = new JTable(model);

	TetraVoiceCallTablePanel() {
		super(new BorderLayout(0, 5));
		setBackground(Color.BLACK);
		JLabel hint = new JLabel("Call-control signalling on the tuned downlink; audio is not decoded.");
		hint.setOpaque(true);
		hint.setBackground(Color.BLACK);
		hint.setForeground(new Color(0xB8B8B8));
		hint.setBorder(new EmptyBorder(5, 8, 2, 8));
		add(hint, BorderLayout.NORTH);

		table.setBackground(BACKGROUND);
		table.setForeground(Color.WHITE);
		table.setGridColor(GRID);
		table.setSelectionBackground(new Color(0x245070));
		table.setFillsViewportHeight(true);
		table.setAutoCreateRowSorter(true);
		table.getTableHeader().setReorderingAllowed(false);
		table.getColumnModel().getColumn(0).setPreferredWidth(90);
		table.getColumnModel().getColumn(1).setPreferredWidth(90);
		table.getColumnModel().getColumn(2).setPreferredWidth(85);
		table.getColumnModel().getColumn(3).setPreferredWidth(100);
		table.getColumnModel().getColumn(4).setPreferredWidth(65);
		table.getColumnModel().getColumn(5).setPreferredWidth(130);
		table.getColumnModel().getColumn(6).setPreferredWidth(135);
		table.getColumnModel().getColumn(7).setPreferredWidth(90);
		table.getColumnModel().getColumn(8).setPreferredWidth(75);
		table.getColumnModel().getColumn(9).setPreferredWidth(125);
		table.getColumnModel().getColumn(10).setPreferredWidth(130);
		table.getColumnModel().getColumn(2).setCellRenderer(new StatusRenderer());
		table.getColumnModel().getColumn(9).setCellRenderer(new SecurityRenderer());
		JScrollPane scroll = new JScrollPane(table);
		scroll.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
		add(scroll, BorderLayout.CENTER);
	}

	void accept(List<TetraMacDecoder.TetraMessage> events) {
		if (events == null || events.isEmpty()) return;
		boolean changed = false;
		for (TetraMacDecoder.TetraMessage message : events) {
			if (!(message instanceof TetraMacDecoder.CallEvent)) continue;
			TetraMacDecoder.CallEvent event = (TetraMacDecoder.CallEvent)message;
			if (event.callIdentifier <= 0 || event.isKnownNonSpeech()) continue;
			CallRecord call = latestById.get(event.callIdentifier);
			if (call == null || call.ended && "D-SETUP".equals(event.type)) {
				call = new CallRecord(event.callIdentifier, event.timestampMillis);
				calls.add(0, call);
				latestById.put(event.callIdentifier, call);
			}
			call.accept(event);
			changed = true;
		}
		if (!changed) return;
		while (calls.size() > MAX_CALLS) {
			CallRecord removed = calls.remove(calls.size() - 1);
			if (latestById.get(removed.callIdentifier) == removed)
				latestById.remove(removed.callIdentifier);
		}
		rebuild();
	}

	void clear() {
		calls.clear();
		latestById.clear();
		model.setRowCount(0);
	}

	private void rebuild() {
		model.setRowCount(0);
		for (CallRecord call : calls) {
			model.addRow(new Object[] {
					timeFormat.format(new Date(call.startedMillis)),
					timeFormat.format(new Date(call.lastSeenMillis)), call.state, call.activity,
					Integer.valueOf(call.callIdentifier), optional(call.destination),
					optional(call.partySsi), call.service, call.mode, call.security, call.lastEvent
			});
		}
	}

	private static Integer optional(int value) {
		return value < 0 ? null : Integer.valueOf(value);
	}

	int callCount() { return model.getRowCount(); }
	Object valueForTest(int row, int column) { return model.getValueAt(row, column); }

	private static final class CallRecord {
		final int callIdentifier;
		final long startedMillis;
		long lastSeenMillis;
		int destination = -1;
		int partySsi = -1;
		String state = "SETUP";
		String activity = "--";
		String service = "Unknown";
		String mode = "--";
		String security = "UNKNOWN";
		String lastEvent = "--";
		boolean ended;

		CallRecord(int callIdentifier, long startedMillis) {
			this.callIdentifier = callIdentifier;
			this.startedMillis = startedMillis;
			this.lastSeenMillis = startedMillis;
		}

		void accept(TetraMacDecoder.CallEvent event) {
			lastSeenMillis = Math.max(lastSeenMillis, event.timestampMillis);
			if (event.destination >= 0) destination = event.destination;
			if (event.source >= 0) partySsi = event.source;
			if (event.basicService >= 0) {
				int circuitMode = event.basicService >> 5 & 7;
				int speechService = event.basicService & 3;
				service = circuitMode == 0
						? speechService == 0 ? "Speech" : speechService == 3
								? "Proprietary speech" : "Reserved speech"
						: "Circuit data " + circuitMode;
			}
			if (event.simplex >= 0) mode = event.simplex == 0 ? "SIMPLEX" : "DUPLEX";
			if (event.encryptionControl >= 0)
				security = event.encryptionControl == 0 ? "CLEAR" : "E2E ENCRYPTED";
			lastEvent = event.type;
			if ("D-RELEASE".equals(event.type) || "D-DISCONNECT".equals(event.type)) {
				state = "ENDED";
				activity = "--";
				ended = true;
			} else if ("D-SETUP".equals(event.type) || "D-ALERT".equals(event.type)
					|| "D-CALL-PROCEEDING".equals(event.type)) {
				state = "SETUP";
				ended = false;
			} else if ("D-TX-CEASED".equals(event.type)) {
				state = "ACTIVE";
				activity = "IDLE";
				ended = false;
			} else if ("D-TX-GRANTED".equals(event.type)) {
				state = "ACTIVE";
				activity = event.transmissionGrant == 0 ? "TRANSMITTING"
						: event.transmissionGrant == 3 ? "OTHER TALKER"
						: event.transmissionGrant == 2 ? "QUEUED" : "IDLE";
				ended = false;
			} else if ("D-TX-WAIT".equals(event.type)) {
				state = "ACTIVE";
				activity = "WAIT";
				ended = false;
			} else if ("D-TX-INTERRUPT".equals(event.type)) {
				state = "ACTIVE";
				activity = "INTERRUPTED";
				ended = false;
			} else if (!"D-INFO".equals(event.type)) {
				state = "ACTIVE";
				ended = false;
			}
		}
	}

	private static final class StatusRenderer extends DefaultTableCellRenderer {
		@Override public Component getTableCellRendererComponent(JTable table, Object value,
				boolean selected, boolean focus, int row, int column) {
			Component component = super.getTableCellRendererComponent(
					table, value, selected, focus, row, column);
			setHorizontalAlignment(SwingConstants.CENTER);
			if (!selected) component.setForeground("ACTIVE".equals(value)
					? new Color(0x66FF88) : "ENDED".equals(value)
							? new Color(0xAAAAAA) : new Color(0xFFD060));
			return component;
		}
	}

	private static final class SecurityRenderer extends DefaultTableCellRenderer {
		@Override public Component getTableCellRendererComponent(JTable table, Object value,
				boolean selected, boolean focus, int row, int column) {
			Component component = super.getTableCellRendererComponent(
					table, value, selected, focus, row, column);
			setHorizontalAlignment(SwingConstants.CENTER);
			if (!selected) component.setForeground("CLEAR".equals(value)
					? new Color(0x66FF88) : "E2E ENCRYPTED".equals(value)
							? new Color(0xFF9060) : new Color(0xBBBBBB));
			return component;
		}
	}
}
