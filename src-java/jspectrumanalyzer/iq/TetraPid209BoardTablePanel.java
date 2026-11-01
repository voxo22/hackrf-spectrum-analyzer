package jspectrumanalyzer.iq;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

/** Latest decoded PID-209 passenger-information state, grouped by destination SSI. */
final class TetraPid209BoardTablePanel extends JPanel {
	private static final Color HEADER_BG = new Color(0x202020), HEADER_FG = Color.WHITE;
	private static final Color HEADER_BORDER = new Color(0x555555);
	private static final Color[] SORT_HEADER_BACKGROUNDS = {
			new Color(0x17627d), new Color(0x4b557c), new Color(0x685076)
	};
	private static final String[] COLUMNS = {
			"DST SSI", "Board / direction", "Last change", "Last received",
			"SDS PID", "Counter", "Format byte", "Rows", "Field A / line", "Field B / destination",
			"Time values", "Delta", "Extra (stand)", "Flags", "Previous values",
			"Updates", "Calling SSI", "Raw PID 209 data"
	};
	private static final int[] WIDTHS = {
			150, 230, 100, 100, 60, 70, 85, 125, 150, 180, 105, 70, 100, 80, 135, 65, 90, 620
	};

	private final Map<Integer, BoardState> boards = new LinkedHashMap<Integer, BoardState>();
	private final DefaultTableModel model = new DefaultTableModel(COLUMNS, 0) {
		@Override public boolean isCellEditable(int row, int column) { return false; }
		@Override public Class<?> getColumnClass(int column) {
			switch (column) {
			case 0: case 4: case 5: case 15: case 16: return Integer.class;
			default: return String.class;
			}
		}
	};
	private final JTable table = new JTable(model);
	private final JLabel summary = new JLabel("Waiting for decoded PID 209 messages...");
	private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);
	private final PersistentTableLayout tableLayout;
	private boolean headerPopupHandled;
	private long decodedUpdates;
	private long contentChanges;

	TetraPid209BoardTablePanel() { this(PersistentTableLayout.DEFAULT_SETTINGS_FILE); }

	TetraPid209BoardTablePanel(File tableSettingsFile) {
		super(new BorderLayout(0, 6));
		setBackground(Color.BLACK);
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		JPanel header = new JPanel(new BorderLayout(8, 0));
		header.setOpaque(false);
		summary.setForeground(Color.WHITE);
		header.add(summary, BorderLayout.CENTER);
		JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		actions.setOpaque(false);
		JButton reloadMappings = new JButton("RELOAD MAPPINGS");
		reloadMappings.addActionListener(e -> {
			TetraPid209Mappings.reload();
			rebuild();
		});
		actions.add(reloadMappings);
		actions.add(SignalTesterClipboard.createButton(this, false));
		JButton clear = new JButton("CLEAR");
		clear.addActionListener(e -> clear());
		actions.add(clear);
		header.add(actions, BorderLayout.EAST);

		table.setBackground(new Color(0x101010));
		table.setForeground(Color.WHITE);
		table.setGridColor(new Color(0x303030));
		table.setSelectionBackground(new Color(0x245070));
		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		table.setAutoCreateRowSorter(true);
		table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
		table.setFillsViewportHeight(true);
		table.getTableHeader().setDefaultRenderer(new SortHeaderRenderer());
		table.getRowSorter().addRowSorterListener(event -> table.getTableHeader().repaint());
		table.getTableHeader().addMouseListener(new MouseAdapter() {
			@Override public void mousePressed(MouseEvent event) {
				headerPopupHandled = handleHeaderPopup(event);
			}
			@Override public void mouseReleased(MouseEvent event) {
				if (!headerPopupHandled) handleHeaderPopup(event);
				headerPopupHandled = false;
			}
		});
		for (int n = 0; n < WIDTHS.length; n++)
			table.getColumnModel().getColumn(n).setPreferredWidth(WIDTHS[n]);
		JScrollPane scroll = new JScrollPane(table);
		scroll.setPreferredSize(new Dimension(1450, 560));
		scroll.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
		add(header, BorderLayout.NORTH);
		add(scroll, BorderLayout.CENTER);
		setDefaultSort();
		tableLayout = new PersistentTableLayout(table, COLUMNS.length, tableSettingsFile,
				"tetra.pid209.table.", this::setDefaultSort);
	}

	@Override public void addNotify() { super.addNotify(); tableLayout.installAndRestore(); }
	@Override public void removeNotify() { tableLayout.saveAndStop(); super.removeNotify(); }

	void accept(List<TetraMacDecoder.SdsMessage> messages) {
		if (messages == null || messages.isEmpty()) return;
		boolean changed = false;
		for (TetraMacDecoder.SdsMessage message : messages) {
			if (message == null || message.protocol != 209) continue;
			TetraPid209Decoder.Update update = TetraPid209Decoder.decode(message);
			if (update == null) continue;
			BoardState state = boards.get(message.destination);
			if (state == null) {
				state = new BoardState(message.destination);
				boards.put(message.destination, state);
			}
			boolean contentChanged = state.update(message, update);
			decodedUpdates++;
			if (contentChanged) contentChanges++;
			changed = true;
		}
		if (changed) rebuild();
	}

	void clear() {
		boards.clear();
		decodedUpdates = 0L;
		contentChanges = 0L;
		model.setRowCount(0);
		summary.setText("Waiting for decoded PID 209 messages...");
	}

	int boardCount() { return boards.size(); }

	String departuresForBoard(int ssi) {
		BoardState state = boards.get(ssi);
		return state == null ? null : departures(state.update);
	}

	String intervalForBoard(int ssi) {
		BoardState state = boards.get(ssi);
		return state == null ? null : interval(state.update);
	}

	String previousDeparturesForBoard(int ssi) {
		BoardState state = boards.get(ssi);
		return state == null ? null : state.previousDepartures;
	}

	long lastChangeForBoard(int ssi) {
		BoardState state = boards.get(ssi);
		return state == null ? -1L : state.lastChangeMillis;
	}

	private void rebuild() {
		TetraPid209Mappings.reloadIfChanged();
		int selectedDestination = selectedDestination();
		List<BoardState> states = new ArrayList<BoardState>(boards.values());
		Collections.sort(states, new Comparator<BoardState>() {
			@Override public int compare(BoardState left, BoardState right) {
				return Long.compare(right.lastChangeMillis, left.lastChangeMillis);
			}
		});
		model.setRowCount(0);
		for (BoardState state : states) {
			TetraPid209Decoder.Update update = state.update;
			model.addRow(new Object[] {
					state.destination,
					knownOrUnknown(TetraPid209Mappings.board(state.destination)),
					time(state.lastChangeMillis), time(state.lastReceivedMillis), 209,
					update.counterCandidate, format(update),
					shape(update), fieldAs(update), fieldBs(update), departures(update),
					interval(update), stands(update), flags(update), state.previousDepartures,
					state.updates, state.source, state.raw
			});
		}
		if (selectedDestination >= 0) selectDestination(selectedDestination);
		String mappingError = TetraPid209Mappings.lastError();
		String mappingStatus = mappingError == null
				? String.format(Locale.US, "mappings: %d board / %d line / %d destination",
						TetraPid209Mappings.boardCount(), TetraPid209Mappings.lineCount(),
						TetraPid209Mappings.destinationCount())
				: "mapping file error: " + mappingError;
		summary.setText(String.format(Locale.US,
				"Decoded PID 209 boards: %d    updates: %d    content changes: %d    %s",
				boards.size(), decodedUpdates, contentChanges, mappingStatus));
	}

	int selectedDestination() {
		int viewRow = table.getSelectedRow();
		if (viewRow < 0) return -1;
		int modelRow = table.convertRowIndexToModel(viewRow);
		Object value = model.getValueAt(modelRow, 0);
		return value instanceof Number ? ((Number)value).intValue() : -1;
	}

	boolean selectDestination(int destination) {
		for (int modelRow = 0; modelRow < model.getRowCount(); modelRow++) {
			Object value = model.getValueAt(modelRow, 0);
			if (!(value instanceof Number) || ((Number)value).intValue() != destination) continue;
			int viewRow = table.convertRowIndexToView(modelRow);
			if (viewRow < 0) return false;
			table.setRowSelectionInterval(viewRow, viewRow);
			return true;
		}
		return false;
	}

	private void setDefaultSort() {
		table.getRowSorter().setSortKeys(Collections.singletonList(
				new RowSorter.SortKey(2, SortOrder.DESCENDING)));
	}

	private boolean handleHeaderPopup(MouseEvent event) {
		if (!event.isPopupTrigger()) return false;
		int viewColumn = table.getTableHeader().columnAtPoint(event.getPoint());
		if (viewColumn < 0) return false;
		event.consume();
		removeOrClearSort(viewColumn);
		return true;
	}

	private void removeOrClearSort(int viewColumn) {
		if (viewColumn < 0) return;
		int modelColumn = table.convertColumnIndexToModel(viewColumn);
		List<RowSorter.SortKey> retained = new ArrayList<RowSorter.SortKey>();
		boolean removed = false;
		for (RowSorter.SortKey key : table.getRowSorter().getSortKeys()) {
			if (key.getColumn() == modelColumn && key.getSortOrder() != SortOrder.UNSORTED)
				removed = true;
			else retained.add(key);
		}
		table.getRowSorter().setSortKeys(removed ? retained
				: Collections.<RowSorter.SortKey>emptyList());
	}

	void saveTableLayoutForTest() { tableLayout.save(); }
	void restoreTableLayoutForTest() { tableLayout.restore(); }
	void moveColumnForTest(String name, int target) {
		int modelColumn = model.findColumn(name), view = table.convertColumnIndexToView(modelColumn);
		if (view >= 0) table.moveColumn(view, target);
	}
	void setColumnWidthForTest(String name, int width) {
		int view = table.convertColumnIndexToView(model.findColumn(name));
		if (view >= 0) {
			table.getColumnModel().getColumn(view).setPreferredWidth(width);
			table.getColumnModel().getColumn(view).setWidth(width);
		}
	}
	int viewColumnForTest(String name) {
		return table.convertColumnIndexToView(model.findColumn(name));
	}
	int columnWidthForTest(String name) {
		int view = viewColumnForTest(name);
		return view < 0 ? -1 : table.getColumnModel().getColumn(view).getWidth();
	}
	void sortByColumnsForTest(String first, SortOrder firstOrder,
			String second, SortOrder secondOrder) {
		table.getRowSorter().setSortKeys(java.util.Arrays.asList(
				new RowSorter.SortKey(model.findColumn(first), firstOrder),
				new RowSorter.SortKey(model.findColumn(second), secondOrder)));
	}
	List<? extends RowSorter.SortKey> sortKeysForTest() {
		return table.getRowSorter().getSortKeys();
	}
	String headerTextForTest(String name) {
		int view = viewColumnForTest(name);
		Component component = table.getTableHeader().getDefaultRenderer()
				.getTableCellRendererComponent(table, table.getColumnName(view), false, false, -1, view);
		return ((JLabel)component).getText();
	}

	private static String format(TetraPid209Decoder.Update update) {
		return update.variantByte < 0 ? String.format(Locale.US, "0x%02X", update.formatByte)
				: String.format(Locale.US, "0x%02X/0x%02X", update.formatByte,
						update.variantByte);
	}

	private String time(long millis) {
		return millis <= 0L ? "--" : timeFormat.format(new Date(millis));
	}

	private static String shape(TetraPid209Decoder.Update update) {
		if (update.arrivals.size() == 2 && samePair(update)) return "2× same A/B";
		return update.arrivals.size() + " rows / " + distinctPairCount(update) + " A/B pairs";
	}

	private static String fieldAs(TetraPid209Decoder.Update update) {
		Set<String> values = new LinkedHashSet<String>();
		for (TetraPid209Decoder.Arrival arrival : update.arrivals) {
			String raw = String.format(Locale.US, "0x%04X", arrival.fieldA);
			String line = TetraPid209Mappings.line(arrival.fieldA);
			values.add(line == null ? raw : line);
		}
		return join(values);
	}

	private static String fieldBs(TetraPid209Decoder.Update update) {
		Set<String> values = new LinkedHashSet<String>();
		for (TetraPid209Decoder.Arrival arrival : update.arrivals) {
			String raw = String.format(Locale.US, "0x%04X", arrival.fieldB);
			String destination = TetraPid209Mappings.destination(arrival.fieldB);
			values.add(destination == null ? raw : destination);
		}
		return join(values);
	}

	private static String knownOrUnknown(String value) { return value == null ? "--" : value; }

	private static String departures(TetraPid209Decoder.Update update) {
		StringBuilder text = new StringBuilder();
		for (List<TetraPid209Decoder.Arrival> group : groupedArrivals(update)) {
			if (text.length() > 0) text.append(" - ");
			for (TetraPid209Decoder.Arrival arrival : group) {
				if (text.length() > 0 && text.charAt(text.length() - 1) != ' ') text.append(',');
				text.append(formatTimeValue(arrival.timeValue));
			}
		}
		return text.toString();
	}

	private static String interval(TetraPid209Decoder.Update update) {
		StringBuilder text = new StringBuilder();
		for (List<TetraPid209Decoder.Arrival> group : groupedArrivals(update)) {
			if (text.length() > 0) text.append(" - ");
			if (group.size() < 2) {
				text.append("--");
				continue;
			}
			for (int index = 1; index < group.size(); index++) {
				if (index > 1) text.append(',');
				int first = group.get(index - 1).timeValue;
				int second = group.get(index).timeValue;
				Integer difference = differenceMinutes(first, second);
				text.append(difference == null ? "--" : difference.toString());
			}
		}
		return text.length() == 0 ? "--" : text.toString();
	}

	private static List<List<TetraPid209Decoder.Arrival>> groupedArrivals(
			TetraPid209Decoder.Update update) {
		Map<String, List<TetraPid209Decoder.Arrival>> groups =
				new LinkedHashMap<String, List<TetraPid209Decoder.Arrival>>();
		for (TetraPid209Decoder.Arrival arrival : update.arrivals) {
			String key = arrival.fieldA + ":" + arrival.fieldB;
			List<TetraPid209Decoder.Arrival> group = groups.get(key);
			if (group == null) {
				group = new ArrayList<TetraPid209Decoder.Arrival>();
				groups.put(key, group);
			}
			group.add(arrival);
		}
		return new ArrayList<List<TetraPid209Decoder.Arrival>>(groups.values());
	}

	private static String formatTimeValue(int value) {
		int minute = TetraPid209Decoder.scheduledMinuteOfDay(value);
		if (minute >= 0) return String.format(Locale.US, "~%02d:%02d", minute / 60, minute % 60);
		return value <= 0xff ? Integer.toString(value) : String.format(Locale.US, "0x%04X", value);
	}

	private static Integer differenceMinutes(int first, int second) {
		if (first <= 0xff && second <= 0xff) return Integer.valueOf(second - first);
		int firstMinute = TetraPid209Decoder.scheduledMinuteOfDay(first);
		int secondMinute = TetraPid209Decoder.scheduledMinuteOfDay(second);
		if (firstMinute < 0 || secondMinute < 0) return null;
		int difference = secondMinute - firstMinute;
		if (difference < 0) difference += 24 * 60;
		return Integer.valueOf(difference);
	}

	private static String stands(TetraPid209Decoder.Update update) {
		StringBuilder text = new StringBuilder();
		for (TetraPid209Decoder.Arrival arrival : update.arrivals) {
			if (text.length() > 0) text.append(", ");
			int code = arrival.extensionCode;
			text.append(code < 0 ? "--" : code >= 0x20 && code <= 0x7e
					? Character.toString((char)code) : String.format(Locale.US, "0x%04X", code));
		}
		return text.toString();
	}

	private static String flags(TetraPid209Decoder.Update update) {
		StringBuilder text = new StringBuilder();
		for (TetraPid209Decoder.Arrival arrival : update.arrivals) {
			if (text.length() > 0) text.append(", ");
			text.append(String.format(Locale.US, "%02X", arrival.flags));
		}
		return text.toString();
	}

	private static boolean samePair(TetraPid209Decoder.Update update) {
		if (update.arrivals.isEmpty()) return false;
		TetraPid209Decoder.Arrival first = update.arrivals.get(0);
		for (TetraPid209Decoder.Arrival arrival : update.arrivals)
			if (arrival.fieldA != first.fieldA || arrival.fieldB != first.fieldB) return false;
		return true;
	}

	private static int distinctPairCount(TetraPid209Decoder.Update update) {
		Set<String> routes = new LinkedHashSet<String>();
		for (TetraPid209Decoder.Arrival arrival : update.arrivals)
			routes.add(arrival.fieldA + ":" + arrival.fieldB);
		return routes.size();
	}

	private static String join(Set<String> values) {
		StringBuilder text = new StringBuilder();
		for (String value : values) {
			if (text.length() > 0) text.append(" - ");
			text.append(value);
		}
		return text.toString();
	}

	private static String signature(TetraPid209Decoder.Update update) {
		StringBuilder text = new StringBuilder().append(update.formatByte).append('/')
				.append(update.variantByte).append(':');
		for (TetraPid209Decoder.Arrival arrival : update.arrivals)
			text.append(arrival.fieldA).append('/').append(arrival.fieldB).append('/')
					.append(arrival.timeValue).append('/').append(arrival.flags).append('/')
					.append(arrival.extensionCode).append(';');
		return text.toString();
	}

	private static final class BoardState {
		final int destination;
		int source, updates;
		long lastReceivedMillis, lastChangeMillis;
		String signature, previousDepartures = "--", raw = "";
		TetraPid209Decoder.Update update;

		BoardState(int destination) { this.destination = destination; }

		boolean update(TetraMacDecoder.SdsMessage message, TetraPid209Decoder.Update next) {
			String nextSignature = signature(next);
			boolean changed = signature == null || !signature.equals(nextSignature);
			if (changed) {
				if (update != null) previousDepartures = departures(update);
				lastChangeMillis = message.timestampMillis;
				signature = nextSignature;
			}
			update = next;
			source = message.source;
			lastReceivedMillis = message.timestampMillis;
			raw = message.dataHex();
			updates++;
			return changed;
		}
	}

	private static final class SortHeaderRenderer extends DefaultTableCellRenderer {
		SortHeaderRenderer() {
			setHorizontalAlignment(SwingConstants.CENTER);
			setOpaque(true);
			setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, HEADER_BORDER));
		}

		@Override public Component getTableCellRendererComponent(JTable table, Object value,
				boolean selected, boolean focus, int row, int column) {
			super.getTableCellRendererComponent(table, value, selected, focus, row, column);
			int modelColumn = table.convertColumnIndexToModel(column), priority = -1, index = 0;
			SortOrder order = SortOrder.UNSORTED;
			for (RowSorter.SortKey key : table.getRowSorter().getSortKeys()) {
				if (key.getSortOrder() == SortOrder.UNSORTED) continue;
				if (key.getColumn() == modelColumn) {
					priority = index;
					order = key.getSortOrder();
					break;
				}
				index++;
			}
			setBackground(priority < 0 ? HEADER_BG
					: SORT_HEADER_BACKGROUNDS[priority % SORT_HEADER_BACKGROUNDS.length]);
			setForeground(HEADER_FG);
			String text = String.valueOf(value);
			setText(priority < 0 ? text : text + " "
					+ (order == SortOrder.ASCENDING ? "\u25B2" : "\u25BC")
					+ (table.getRowSorter().getSortKeys().size() > 1
							? Integer.toString(priority + 1) : ""));
			return this;
		}
	}
}
