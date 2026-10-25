package jspectrumanalyzer.iq;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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

/** Satellite rows for the one currently selected GNSS L1 decoder. */
final class GpsL1SatelliteTablePanel extends JPanel {
	/** Trial UI switch: false restores the former candidates-plus-decoded-rows view. */
	private static final boolean SHOW_ALL_SATELLITES = true;
	private static final Color HEADER_BG = Color.BLACK, HEADER_FG = Color.WHITE, HEADER_BORDER = new Color(0x555555);
	private static final Color[] SORT_HEADER_BACKGROUNDS = { new Color(0x17627d), new Color(0x4b557c), new Color(0x685076) };
	private static final Color ROW_RED = new Color(0x381d20), ROW_YELLOW = new Color(0x39351c), ROW_GREEN = new Color(0x1d3924);
	private final DefaultTableModel model = new DefaultTableModel(
			new String[] { "SV", "Signal state", "Doppler", "Code phase", "C/N0 est.", "Prompt lock", "NAV", "Health", "Ephemeris", "Used" }, 0) {
		@Override public boolean isCellEditable(int row, int column) { return false; }
		@Override public Class<?> getColumnClass(int column) {
			return column == 2 ? Integer.class : column == 3 || column == 4 || column == 5 ? Double.class : String.class;
		}
	};
	private final List<RowRecord> displayed = new ArrayList<RowRecord>();
	private final Map<GnssL1System, Map<String, RowRecord>> retainedBySystem =
			new EnumMap<GnssL1System, Map<String, RowRecord>>(GnssL1System.class);
	private final Map<GnssL1System, String> selectedBySystem = new EnumMap<GnssL1System, String>(GnssL1System.class);
	private GnssL1System system = GnssL1System.GPS;
	private final JTable table = new JTable(model) {
		@Override public String getToolTipText(MouseEvent event) {
			int viewRow = rowAtPoint(event.getPoint()), viewColumn = columnAtPoint(event.getPoint());
			if (viewRow < 0 || viewColumn < 0) return null;
			int column = convertColumnIndexToModel(viewColumn), row = convertRowIndexToModel(viewRow);
			if (row >= displayed.size() || (column != 1 && column != 7 && column != 8)) return null;
			Object satellite = displayed.get(row).satellite;
			if (satellite instanceof GpsL1SignalAnalyzer.Satellite) return gpsTooltip(
					(GpsL1SignalAnalyzer.Satellite) satellite, column);
			if (satellite instanceof GalileoE1SignalAnalyzer.Satellite) return galileoTooltip(
					(GalileoE1SignalAnalyzer.Satellite) satellite, column);
			if (satellite instanceof BeiDouB1cSignalAnalyzer.Satellite) return beidouTooltip(
					(BeiDouB1cSignalAnalyzer.Satellite) satellite, column);
			return null;
		}
	};
	private final JLabel sortHint = new JLabel("Decoded satellites stay in the table. Sort: click to add/prioritize; right-click to remove or clear sorting.");
	private final JLabel status = new JLabel("Waiting for GPS L1 IQ samples...");
	private final PersistentTableLayout tableLayout;
	private boolean headerPopupHandled;

	GpsL1SatelliteTablePanel() { this(PersistentTableLayout.DEFAULT_SETTINGS_FILE); }
	GpsL1SatelliteTablePanel(File settingsFile) {
		super(new BorderLayout()); setBackground(Color.BLACK);
		for (GnssL1System value : GnssL1System.values())
			retainedBySystem.put(value, new LinkedHashMap<String, RowRecord>());
		table.setAutoCreateRowSorter(true); table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
		table.setRowHeight(23); table.setFillsViewportHeight(true); table.setBackground(Color.BLACK);
		table.setForeground(Color.WHITE); table.setGridColor(new Color(0x333333));
		table.setSelectionBackground(new Color(0x245c82)); table.setSelectionForeground(Color.WHITE);
		table.setToolTipText("");
		table.getTableHeader().setBackground(Color.BLACK); table.getTableHeader().setForeground(Color.WHITE);
		table.getTableHeader().setDefaultRenderer(new SortHeaderRenderer());
		table.getRowSorter().addRowSorterListener(event -> table.getTableHeader().repaint());
		table.getTableHeader().addMouseListener(new MouseAdapter() {
			@Override public void mousePressed(MouseEvent event) { headerPopupHandled = handleHeaderPopup(event); }
			@Override public void mouseReleased(MouseEvent event) { if (!headerPopupHandled) handleHeaderPopup(event); headerPopupHandled = false; }
		});
		DefaultTableCellRenderer right = new ValueRenderer(SwingConstants.RIGHT), centered = new ValueRenderer(SwingConstants.CENTER);
		for (int column = 0; column < model.getColumnCount(); column++)
			table.getColumnModel().getColumn(column).setCellRenderer(column == 2 || column == 3 ? right : centered);
		int[] widths = { 55, 125, 90, 105, 85, 90, 150, 120, 110, 55 };
		for (int column = 0; column < widths.length; column++) table.getColumnModel().getColumn(column).setPreferredWidth(widths[column]);
		JScrollPane scroll = new JScrollPane(table); scroll.getViewport().setBackground(Color.BLACK);
		scroll.setColumnHeaderView(table.getTableHeader()); scroll.getColumnHeader().setBackground(Color.BLACK);
		JPanel upperRight = new JPanel(); upperRight.setBackground(Color.BLACK); scroll.setCorner(JScrollPane.UPPER_RIGHT_CORNER, upperRight);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		scroll.setPreferredSize(new Dimension(860, 305)); add(scroll, BorderLayout.CENTER);
		sortHint.setOpaque(true); sortHint.setBackground(Color.BLACK); sortHint.setForeground(new Color(0x999999));
		sortHint.setBorder(BorderFactory.createEmptyBorder(3, 8, 0, 8));
		status.setOpaque(true); status.setBackground(Color.BLACK); status.setForeground(new Color(0xdddddd));
		status.setBorder(BorderFactory.createEmptyBorder(2, 8, 5, 8));
		JPanel footer = new JPanel(new GridLayout(2, 1)); footer.setBackground(Color.BLACK);
		footer.add(sortHint); footer.add(status); add(footer, BorderLayout.SOUTH);
		setDefaultSort();
		tableLayout = new PersistentTableLayout(table, model.getColumnCount(), settingsFile, "gps.table.", this::setDefaultSort);
	}

	@Override public void addNotify() { super.addNotify(); tableLayout.installAndRestore(); }
	@Override public void removeNotify() { tableLayout.saveAndStop(); super.removeNotify(); }

	void setSystem(GnssL1System value) {
		if (value == null) value = GnssL1System.GPS;
		if (system == value) {
			if (model.getRowCount() == 0) refreshRows(Collections.<RowRecord>emptyList(),
					"Waiting for " + value.label + " IQ samples...");
			return;
		}
		String selected = selectedKey();
		if (selected == null) selectedBySystem.remove(system); else selectedBySystem.put(system, selected);
		system = value; displayed.clear(); model.setRowCount(0); table.clearSelection();
		refreshRows(Collections.<RowRecord>emptyList(), "Waiting for " + value.label + " IQ samples...");
		restoreSelection(selectedBySystem.get(value));
	}

	void refresh(GpsL1SignalAnalyzer.Result result) { refreshGps(result); }
	void refresh(GpsL1SignalAnalyzer.Result result, GalileoE1SignalAnalyzer.Result galileoResult) {
		if (system == GnssL1System.GALILEO) refreshGalileo(galileoResult); else refreshGps(result);
	}

	void refreshGps(GpsL1SignalAnalyzer.Result result) {
		if (system != GnssL1System.GPS) return;
		List<RowRecord> current = new ArrayList<RowRecord>();
		if (result != null) for (GpsL1SignalAnalyzer.Satellite satellite : result.satellites)
			current.add(gpsRecord(satellite, result));
		refreshRows(current, (result == null ? "GPS waiting" : result.state)
				+ "  |  red: no live NAV, yellow: NAV data, green: complete ephemeris");
	}

	void refreshGalileo(GalileoE1SignalAnalyzer.Result result) {
		if (system != GnssL1System.GALILEO) return;
		List<RowRecord> current = new ArrayList<RowRecord>();
		if (result != null) for (GalileoE1SignalAnalyzer.Satellite satellite : result.satellites)
			current.add(galileoRecord(satellite));
		refreshRows(current, (result == null ? "Galileo waiting" : result.state)
				+ "  |  red: no live I/NAV, yellow: I/NAV data, green: complete ephemeris");
	}

	void refreshBeidou(long centerHz, int rateHz) {
		refreshBeidou(null, centerHz, rateHz);
	}

	void refreshBeidou(BeiDouB1cSignalAnalyzer.Result result, long centerHz, int rateHz) {
		if (system != GnssL1System.BEIDOU) return;
		List<RowRecord> current = new ArrayList<RowRecord>();
		if (result != null) for (BeiDouB1cSignalAnalyzer.Satellite satellite : result.satellites)
			current.add(beidouRecord(satellite, result));
		refreshRows(current, (result == null ? "BeiDou B1C waiting" : result.state)
				+ "  |  red: no live NAV, yellow: B-CNAV1 data, green: complete ephemeris");
	}

	private void refreshRows(List<RowRecord> currentRows, String state) {
		String selectedKey = selectedKey();
		Map<String, RowRecord> retained = retainedBySystem.get(system);
		Map<String, RowRecord> current = new LinkedHashMap<String, RowRecord>();
		for (RowRecord row : currentRows) {
			current.put(row.key, row);
			if (row.everDecoded) {
				RowRecord previous = retained.get(row.key);
				retained.put(row.key, previous == null ? row : previous.bestHistory(row));
			}
		}
		List<RowRecord> rows = new ArrayList<RowRecord>();
		List<RowRecord> historical = new ArrayList<RowRecord>(retained.values());
		Collections.sort(historical, new Comparator<RowRecord>() {
			@Override public int compare(RowRecord left, RowRecord right) {
				int quality = Integer.compare(right.historyRank, left.historyRank);
				return quality != 0 ? quality : left.key.compareTo(right.key);
			}
		});
		for (RowRecord saved : historical) {
			RowRecord live = current.remove(saved.key);
			rows.add(live == null ? saved.lost() : live.withHistory(saved));
		}
		rows.addAll(current.values());
		if (SHOW_ALL_SATELLITES) appendMissingSatellites(rows);
		model.setRowCount(0); displayed.clear();
		for (RowRecord row : rows) { displayed.add(row); model.addRow(row.values); }
		restoreSelection(selectedKey); status.setText(state);
	}

	private void appendMissingSatellites(List<RowRecord> rows) {
		Map<String, Boolean> present = new LinkedHashMap<String, Boolean>();
		for (RowRecord row : rows) present.put(row.key, Boolean.TRUE);
		int count = system == GnssL1System.GPS ? 32 : system == GnssL1System.GALILEO ? 36 : 63;
		String prefix = system == GnssL1System.GPS ? "G" : system == GnssL1System.GALILEO ? "E" : "C";
		for (int id = 1; id <= count; id++) {
			String key = prefix + String.format(Locale.US, "%02d", id);
			if (!present.containsKey(key)) rows.add(emptyRecord(key));
		}
	}

	private static RowRecord emptyRecord(String key) {
		return new RowRecord(key, new Object[] { key, "No signal", null, null, null, null, "--", "--", "--", "No" },
				null, -1, 0, false);
	}

	private static RowRecord gpsRecord(GpsL1SignalAnalyzer.Satellite satellite, GpsL1SignalAnalyzer.Result result) {
		String key = "G" + String.format(Locale.US, "%02d", satellite.prn);
		GpsL1NavigationData.Snapshot nav = satellite.navigation;
		boolean ever = nav != null && nav.subframeCount > 0;
		int history = nav != null && nav.ephemeris != null ? 2 : ever ? 1 : 0;
		boolean liveNav = satellite.trackingLocked && satellite.navState.startsWith("LNAV SF");
		int live = liveNav ? (history >= 2 ? 2 : 1) : ever ? 0 : -1;
		Object[] values = { key,
				satellite.trackingLocked ? "Prompt tracked" : satellite.acquired ? "C/A acquired" : "Candidate",
				Integer.valueOf(satellite.dopplerHz), Double.valueOf(satellite.codePhaseChips), Double.valueOf(satellite.cNoDbHz),
				Double.valueOf(satellite.trackingStability * 100d), satellite.navState,
				nav == null ? "--" : nav.healthText(), nav == null ? "--" : nav.ephemerisText(),
				result.position.plausible && result.position.usedPrns.contains(Integer.valueOf(satellite.prn)) ? "Yes" : "No" };
		return new RowRecord(key, values, satellite, live, history, ever);
	}

	private static RowRecord galileoRecord(GalileoE1SignalAnalyzer.Satellite satellite) {
		String key = "E" + String.format(Locale.US, "%02d", satellite.svid);
		GalileoNavigationData.Snapshot nav = satellite.navigation;
		boolean ever = nav != null && nav.hasValidatedWord();
		int history = nav != null && nav.ephemeris != null ? 2 : ever ? 1 : 0;
		boolean liveNav = satellite.trackingLocked && satellite.page.valid;
		int live = liveNav ? (history >= 2 ? 2 : 1) : ever ? 0 : -1;
		Object[] values = { key,
				satellite.trackingLocked ? "E1-B tracked" : satellite.acquired ? "E1-B/C acquired" : "Candidate",
				Integer.valueOf(satellite.dopplerHz), Double.valueOf(satellite.codePhaseChips), Double.valueOf(satellite.cNoDbHz),
				Double.valueOf(satellite.trackingLocked ? satellite.trackingStability * 100d : Double.NaN), satellite.navState,
				nav == null ? "--" : nav.healthText(), nav == null ? "--" : nav.ephemerisText(), "No" };
		return new RowRecord(key, values, satellite, live, history, ever);
	}

	private static RowRecord beidouRecord(BeiDouB1cSignalAnalyzer.Satellite satellite,
			BeiDouB1cSignalAnalyzer.Result result) {
		String key = "C" + String.format(Locale.US, "%02d", satellite.prn);
		BeiDouB1cNavigationData.Snapshot nav = satellite.navigation;
		boolean sf1 = satellite.frame != null && satellite.frame.valid;
		boolean ever = sf1 || nav != null && nav.hasValidatedFrame();
		int history = nav != null && nav.ephemeris != null ? 2 : ever ? 1 : 0;
		boolean liveNav = satellite.trackingLocked && (sf1 || nav != null && nav.frame.valid);
		int live = liveNav ? (history >= 2 ? 2 : 1) : ever ? 0 : -1;
		Object[] values = { key,
				satellite.trackingLocked ? "B1C data tracked" : satellite.acquired ? "B1C pilot acquired" : "Candidate",
				Integer.valueOf(satellite.dopplerHz), Double.valueOf(satellite.codePhaseChips), Double.valueOf(satellite.cNoDbHz),
				Double.valueOf(satellite.trackingLocked ? satellite.trackingStability * 100d : Double.NaN), satellite.navState,
				nav == null ? "--" : nav.healthText(), nav == null ? "--" : nav.ephemerisText(),
				result.position.plausible && result.position.usedPrns.contains(Integer.valueOf(satellite.prn)) ? "Yes" : "No" };
		return new RowRecord(key, values, satellite, live, history, ever);
	}

	private String selectedKey() {
		int viewRow = table.getSelectedRow(); if (viewRow < 0) return null;
		int modelRow = table.convertRowIndexToModel(viewRow);
		return modelRow >= 0 && modelRow < displayed.size() ? displayed.get(modelRow).key : null;
	}
	private void restoreSelection(String key) {
		if (key == null) return;
		for (int row = 0; row < displayed.size(); row++) if (key.equals(displayed.get(row).key)) {
			int view = table.convertRowIndexToView(row); if (view >= 0) table.setRowSelectionInterval(view, view); return;
		}
	}
	private void setDefaultSort() {
		table.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(0, SortOrder.ASCENDING)));
	}
	private boolean handleHeaderPopup(MouseEvent event) {
		if (!event.isPopupTrigger()) return false;
		int viewColumn = table.getTableHeader().columnAtPoint(event.getPoint()); if (viewColumn < 0) return false;
		event.consume(); int modelColumn = table.convertColumnIndexToModel(viewColumn);
		List<RowSorter.SortKey> retainedKeys = new ArrayList<RowSorter.SortKey>(); boolean removed = false;
		for (RowSorter.SortKey key : table.getRowSorter().getSortKeys())
			if (key.getColumn() == modelColumn && key.getSortOrder() != SortOrder.UNSORTED) removed = true; else retainedKeys.add(key);
		table.getRowSorter().setSortKeys(removed ? retainedKeys : Collections.<RowSorter.SortKey>emptyList()); return true;
	}

	private static String gpsTooltip(GpsL1SignalAnalyzer.Satellite satellite, int column) {
		if (column == 1) return null;
		GpsL1NavigationData.Snapshot nav = satellite.navigation; if (nav == null) return null;
		if (column == 7) return nav.healthCode < 0 ? null : String.format(Locale.US,
				"Broadcast SF1 health bits: 0x%02X (00 means healthy)", nav.healthCode);
		GpsL1NavigationData.Ephemeris e = nav.ephemeris;
		if (e == null) return "Fresh, parity-checked SF 1/2/3 are required; their issue numbers must match.";
		return String.format(Locale.US, "<html>GPS week modulo 1024: %d<br>IODE / IODC: %d / %d<br>"
				+ "Toe / Toc: %d / %d GPS seconds of week<br>sqrt(A): %.3f m&#189;; eccentricity: %.8f<br>"
				+ "Inclination: %.3f&deg;; URA index: %d<br>Clock bias coefficient: %.3g s</html>",
				e.weekModulo1024, e.iode, e.iodc, e.toeSeconds, e.tocSeconds, e.sqrtA,
				e.eccentricity, Math.toDegrees(e.inclination), e.uraIndex, e.af0);
	}

	private static String galileoTooltip(GalileoE1SignalAnalyzer.Satellite satellite, int column) {
		if (column == 1) return String.format(Locale.US,
				"Pilot-assisted acquisition: E1-B %.1f%%, E1-C %.1f%%, combined %.1f%%",
				satellite.dataCoherence * 100d, satellite.pilotCoherence * 100d, satellite.coherence * 100d);
		GalileoNavigationData.Snapshot nav = satellite.navigation;
		if (column == 7) return nav.healthCode < 0 ? null : String.format(Locale.US,
				"E1-B SHS: %d; DVS: %d; GST week/TOW: %d / %d s; BGD(E1,E5b): %.3g s",
				nav.healthCode, nav.dataValidity, nav.weekModulo4096, nav.towSeconds, nav.bgdE1E5bSeconds);
		GalileoNavigationData.Ephemeris e = nav.ephemeris;
		if (e == null) return "Fresh CRC-checked I/NAV words 1/2/3/4 with matching IODnav and SVID are required.";
		return String.format(Locale.US, "<html>Galileo SVID: %d; IODnav: %d<br>Toe / Toc: %d / %d GST seconds of week<br>"
				+ "sqrt(A): %.3f m&#189;; eccentricity: %.8f<br>Inclination: %.3f&deg;; SISA index: %d</html>",
				e.svid, e.iodNav, e.toeSeconds, e.tocSeconds, e.sqrtA, e.eccentricity,
				Math.toDegrees(e.inclination), e.sisaIndex);
	}
	private static String beidouTooltip(BeiDouB1cSignalAnalyzer.Satellite satellite, int column) {
		BeiDouB1cNavigationData.Snapshot nav = satellite.navigation;
		if (column == 7) return nav.frame.health < 0 ? null : String.format(Locale.US,
				"B-CNAV1 SF3 page %d; health %d; BDT frame SOH %d s",
				nav.frame.pageId, nav.frame.health, nav.frame.secondsOfHour);
		BeiDouB1cNavigationData.Ephemeris eph = nav.ephemeris;
		if (eph == null) return "BCH-valid SF1 and CRC-checked LDPC SF2 are required.";
		return String.format(Locale.US, "<html>BeiDou PRN: C%02d; BDT week/TOW: %d / %d s<br>"
				+ "IODC / IODE: %d / %d; Toe / Toc: %d / %d s<br>"
				+ "A: %.3f m; eccentricity: %.8f<br>Inclination: %.3f&deg;; satellite type: %d</html>",
				eph.prn, eph.week, eph.towSeconds, eph.iodc, eph.iode, eph.toeSeconds, eph.tocSeconds,
				eph.semiMajorAxis, eph.eccentricity, Math.toDegrees(eph.inclination), eph.satelliteType);
	}

	private static final class RowRecord {
		final String key; final Object[] values; final Object satellite;
		final int liveLevel, historyRank; final boolean everDecoded;
		RowRecord(String key, Object[] values, Object satellite, int liveLevel, int historyRank, boolean everDecoded) {
			this.key = key; this.values = values; this.satellite = satellite; this.liveLevel = liveLevel;
			this.historyRank = historyRank; this.everDecoded = everDecoded;
		}
		RowRecord bestHistory(RowRecord other) { return other.historyRank >= historyRank ? other : this; }
		RowRecord withHistory(RowRecord history) {
			if (history.historyRank <= historyRank) return this;
			Object[] merged = values.clone(); for (int column = 6; column <= 8; column++) merged[column] = history.values[column];
			return new RowRecord(key, merged, history.satellite, liveLevel, history.historyRank, true);
		}
		RowRecord lost() {
			Object[] lost = values.clone(); lost[1] = "Signal lost (retained)"; lost[5] = Double.valueOf(Double.NaN); lost[9] = "No";
			return new RowRecord(key, lost, satellite, 0, historyRank, true);
		}
	}

	private final class ValueRenderer extends DefaultTableCellRenderer {
		private final int alignment;
		ValueRenderer(int alignment) { this.alignment = alignment; setHorizontalAlignment(alignment); }
		@Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int column) {
			int modelColumn = table.convertColumnIndexToModel(column); Object shown = value;
			if (value instanceof Integer && modelColumn == 2) shown = String.format(Locale.US, "%+d Hz", ((Integer) value).intValue());
			else if (value instanceof Double && modelColumn == 3) shown = String.format(Locale.US, "%.1f chips", ((Double) value).doubleValue());
			else if (value instanceof Double && modelColumn == 4) shown = String.format(Locale.US, "%.1f dB-Hz", ((Double) value).doubleValue());
			else if (value instanceof Double && modelColumn == 5) shown = Double.isNaN(((Double) value).doubleValue()) ? "--"
					: String.format(Locale.US, "%.0f %%", ((Double) value).doubleValue());
			Component component = super.getTableCellRendererComponent(table, shown, selected, focus, row, column);
			setHorizontalAlignment(alignment);
			if (!selected) {
				int modelRow = table.convertRowIndexToModel(row);
				int level = modelRow >= 0 && modelRow < displayed.size() ? displayed.get(modelRow).liveLevel : 0;
				component.setBackground(level >= 2 ? ROW_GREEN : level == 1 ? ROW_YELLOW : level == 0 ? ROW_RED : Color.BLACK);
				component.setForeground(Color.WHITE);
			}
			return component;
		}
	}

	private static final class SortHeaderRenderer extends DefaultTableCellRenderer {
		SortHeaderRenderer() { setHorizontalAlignment(SwingConstants.CENTER); setOpaque(true);
			setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, HEADER_BORDER)); }
		@Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int column) {
			super.getTableCellRendererComponent(table, value, selected, focus, row, column);
			int modelColumn = table.convertColumnIndexToModel(column), priority = -1, index = 0; SortOrder order = SortOrder.UNSORTED;
			for (RowSorter.SortKey key : table.getRowSorter().getSortKeys()) {
				if (key.getSortOrder() == SortOrder.UNSORTED) continue;
				if (key.getColumn() == modelColumn) { priority = index; order = key.getSortOrder(); break; } index++;
			}
			setBackground(priority < 0 ? HEADER_BG : SORT_HEADER_BACKGROUNDS[priority % SORT_HEADER_BACKGROUNDS.length]);
			setForeground(HEADER_FG); String text = String.valueOf(value);
			setText(priority < 0 ? text : text + " " + (order == SortOrder.ASCENDING ? "\u25B2" : "\u25BC")
					+ (table.getRowSorter().getSortKeys().size() > 1 ? Integer.toString(priority + 1) : ""));
			return this;
		}
	}
}
