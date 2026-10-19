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
import java.util.List;
import java.util.Locale;

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

/** GPS L1 C/A acquisition candidates for the current IQ window. */
final class GpsL1SatelliteTablePanel extends JPanel {
	private static final Color HEADER_BG = new Color(0x202020), HEADER_FG = Color.WHITE, HEADER_BORDER = new Color(0x555555);
	private static final Color[] SORT_HEADER_BACKGROUNDS = { new Color(0x17627d), new Color(0x4b557c), new Color(0x685076) };
	private final DefaultTableModel model = new DefaultTableModel(
			new String[] { "PRN", "C/A state", "Doppler", "Code phase", "C/N0 est.", "Prompt lock", "NAV", "Health", "Ephemeris", "Used" }, 0) {
		@Override public boolean isCellEditable(int row, int column) { return false; }
		@Override public Class<?> getColumnClass(int column) {
			return column == 2 ? Integer.class : column == 3 || column == 4 || column == 5 ? Double.class : String.class;
		}
	};
	private final List<Integer> rowPrns = new ArrayList<Integer>();
	private final List<GpsL1SignalAnalyzer.Satellite> rowSatellites = new ArrayList<GpsL1SignalAnalyzer.Satellite>();
	private final JTable table = new JTable(model) {
		@Override public String getToolTipText(MouseEvent event) {
			int viewRow = rowAtPoint(event.getPoint()), viewColumn = columnAtPoint(event.getPoint());
			if (viewRow < 0 || viewColumn < 0) return null;
			int column = convertColumnIndexToModel(viewColumn), row = convertRowIndexToModel(viewRow);
			if (row >= rowSatellites.size()) return null;
			if (column != 7 && column != 8) return null;
			GpsL1NavigationData.Snapshot nav = rowSatellites.get(row).navigation;
			if (nav == null) return null;
			if (column == 7) return nav.healthCode < 0 ? null : String.format(Locale.US,
					"Broadcast SF1 health bits: 0x%02X (00 means healthy)", nav.healthCode);
			GpsL1NavigationData.Ephemeris e = nav.ephemeris;
			if (column != 8 || e == null) return "Fresh, parity-checked SF 1/2/3 are required; their issue numbers must match.";
			return String.format(Locale.US, "<html>GPS week modulo 1024: %d<br>IODE / IODC: %d / %d<br>"
					+ "Toe / Toc: %d / %d GPS seconds of week<br>sqrt(A): %.3f m&#189;; eccentricity: %.8f<br>"
					+ "Inclination: %.3f&deg;; URA index: %d<br>Clock bias coefficient: %.3g s<br>"
					+ "Subframes assembled; orbit and position not yet validated.</html>", e.weekModulo1024, e.iode, e.iodc,
					e.toeSeconds, e.tocSeconds, e.sqrtA, e.eccentricity, Math.toDegrees(e.inclination), e.uraIndex, e.af0);
		}
	};
	private final JLabel sortHint = new JLabel("Sort: click a column to add/prioritize; right-click a sorted column to remove it; right-click an unsorted column to clear sorting.");
	private final JLabel status = new JLabel("Waiting for GPS L1 IQ samples...");
	private final PersistentTableLayout tableLayout;
	private boolean headerPopupHandled;

	GpsL1SatelliteTablePanel() { this(PersistentTableLayout.DEFAULT_SETTINGS_FILE); }
	GpsL1SatelliteTablePanel(File settingsFile) {
		super(new BorderLayout()); setBackground(Color.BLACK);
		table.setAutoCreateRowSorter(true); table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
		table.setRowHeight(23); table.setFillsViewportHeight(true); table.setBackground(Color.BLACK);
		table.setForeground(Color.WHITE); table.setGridColor(new Color(0x333333));
		table.setSelectionBackground(new Color(0x244b66)); table.setSelectionForeground(Color.WHITE);
		table.setToolTipText("");
		table.getTableHeader().setDefaultRenderer(new SortHeaderRenderer());
		table.getRowSorter().addRowSorterListener(event -> table.getTableHeader().repaint());
		table.getTableHeader().addMouseListener(new MouseAdapter() {
			@Override public void mousePressed(MouseEvent event) { headerPopupHandled = handleHeaderPopup(event); }
			@Override public void mouseReleased(MouseEvent event) { if (!headerPopupHandled) handleHeaderPopup(event); headerPopupHandled = false; }
		});
		DefaultTableCellRenderer right = new ValueRenderer(SwingConstants.RIGHT), centered = new ValueRenderer(SwingConstants.CENTER);
		table.getColumnModel().getColumn(0).setCellRenderer(centered);
		table.getColumnModel().getColumn(1).setCellRenderer(centered);
		table.getColumnModel().getColumn(2).setCellRenderer(right);
		table.getColumnModel().getColumn(3).setCellRenderer(right);
		for (int column = 4; column < model.getColumnCount(); column++) table.getColumnModel().getColumn(column).setCellRenderer(centered);
		int[] widths = { 55, 95, 90, 105, 85, 90, 95, 85, 110, 55 };
		for (int column = 0; column < widths.length; column++) table.getColumnModel().getColumn(column).setPreferredWidth(widths[column]);
		JScrollPane scroll = new JScrollPane(table); scroll.getViewport().setBackground(Color.BLACK);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		scroll.setPreferredSize(new Dimension(820, 305)); add(scroll, BorderLayout.CENTER);
		sortHint.setOpaque(true); sortHint.setBackground(Color.BLACK); sortHint.setForeground(new Color(0x999999));
		sortHint.setBorder(BorderFactory.createEmptyBorder(3, 8, 0, 8));
		status.setOpaque(true); status.setBackground(Color.BLACK); status.setForeground(new Color(0xdddddd));
		status.setBorder(BorderFactory.createEmptyBorder(2, 8, 5, 8));
		JPanel footer = new JPanel(new GridLayout(2, 1)); footer.setBackground(Color.BLACK); footer.add(sortHint); footer.add(status); add(footer, BorderLayout.SOUTH);
		setDefaultSort();
		tableLayout = new PersistentTableLayout(table, model.getColumnCount(), settingsFile, "gps.table.", this::setDefaultSort);
	}

	@Override public void addNotify() { super.addNotify(); tableLayout.installAndRestore(); }
	@Override public void removeNotify() { tableLayout.saveAndStop(); super.removeNotify(); }

	void refresh(GpsL1SignalAnalyzer.Result result) {
		int selectedPrn = selectedPrn();
		model.setRowCount(0);
		rowPrns.clear();
		rowSatellites.clear();
		if (result == null) return;
		for (GpsL1SignalAnalyzer.Satellite satellite : result.satellites) {
			rowPrns.add(Integer.valueOf(satellite.prn));
			rowSatellites.add(satellite);
			model.addRow(new Object[] { "G" + String.format(Locale.US, "%02d", satellite.prn),
					satellite.trackingLocked ? "Prompt tracked" : satellite.acquired ? "C/A acquired" : "Candidate",
					Integer.valueOf(satellite.dopplerHz), Double.valueOf(satellite.codePhaseChips), Double.valueOf(satellite.cNoDbHz),
					Double.valueOf(satellite.trackingStability * 100d),
					satellite.navState, satellite.navigation == null ? "--" : satellite.navigation.healthText(),
					satellite.navigation == null ? "--" : satellite.navigation.ephemerisText(),
					result.position.plausible && result.position.usedPrns.contains(Integer.valueOf(satellite.prn)) ? "Yes" : "No" });
		}
		restoreSelection(selectedPrn);
		status.setText(result.state + "  -  C/N0 is a 1 ms acquisition estimate; up to 6 PRNs tracked for LNAV, shown only after full subframe parity validation.");
	}

	private void setDefaultSort() { table.getRowSorter().setSortKeys(Collections.singletonList(new RowSorter.SortKey(0, SortOrder.ASCENDING))); }
	private boolean handleHeaderPopup(MouseEvent event) {
		if (!event.isPopupTrigger()) return false;
		int viewColumn = table.getTableHeader().columnAtPoint(event.getPoint()); if (viewColumn < 0) return false;
		event.consume(); int modelColumn = table.convertColumnIndexToModel(viewColumn);
		List<RowSorter.SortKey> retained = new ArrayList<RowSorter.SortKey>(); boolean removed = false;
		for (RowSorter.SortKey key : table.getRowSorter().getSortKeys())
			if (key.getColumn() == modelColumn && key.getSortOrder() != SortOrder.UNSORTED) removed = true; else retained.add(key);
		table.getRowSorter().setSortKeys(removed ? retained : Collections.<RowSorter.SortKey>emptyList()); return true;
	}
	private int selectedPrn() {
		int viewRow = table.getSelectedRow(); if (viewRow < 0) return -1;
		int modelRow = table.convertRowIndexToModel(viewRow);
		return modelRow >= 0 && modelRow < rowPrns.size() ? rowPrns.get(modelRow).intValue() : -1;
	}
	private void restoreSelection(int prn) {
		if (prn < 0) return;
		for (int modelRow = 0; modelRow < rowPrns.size(); modelRow++) if (rowPrns.get(modelRow).intValue() == prn) {
			int viewRow = table.convertRowIndexToView(modelRow); if (viewRow >= 0) table.setRowSelectionInterval(viewRow, viewRow); return;
		}
	}
	private static final class SortHeaderRenderer extends DefaultTableCellRenderer {
		SortHeaderRenderer() { setHorizontalAlignment(SwingConstants.CENTER); setOpaque(true); setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, HEADER_BORDER)); }
		@Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int column) {
			super.getTableCellRendererComponent(table, value, selected, focus, row, column);
			int modelColumn = table.convertColumnIndexToModel(column), priority = -1, index = 0; SortOrder order = SortOrder.UNSORTED;
			for (RowSorter.SortKey key : table.getRowSorter().getSortKeys()) { if (key.getSortOrder() == SortOrder.UNSORTED) continue;
				if (key.getColumn() == modelColumn) { priority = index; order = key.getSortOrder(); break; } index++; }
			setBackground(priority < 0 ? HEADER_BG : SORT_HEADER_BACKGROUNDS[priority % SORT_HEADER_BACKGROUNDS.length]); setForeground(HEADER_FG);
			String text = String.valueOf(value); setText(priority < 0 ? text : text + " " + (order == SortOrder.ASCENDING ? "\u25B2" : "\u25BC")
					+ (table.getRowSorter().getSortKeys().size() > 1 ? Integer.toString(priority + 1) : "")); return this;
		}
	}
	private static final class ValueRenderer extends DefaultTableCellRenderer {
		private final int alignment;
		ValueRenderer(int alignment) { this.alignment = alignment; setHorizontalAlignment(alignment); }
		@Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int column) {
			int modelColumn = table.convertColumnIndexToModel(column); Object displayed = value;
			if (value instanceof Integer && modelColumn == 2) displayed = String.format(Locale.US, "%+d Hz", ((Integer) value).intValue());
			else if (value instanceof Double && modelColumn == 3) displayed = String.format(Locale.US, "%.1f chips", ((Double) value).doubleValue());
			else if (value instanceof Double && modelColumn == 4) displayed = String.format(Locale.US, "%.1f dB-Hz", ((Double) value).doubleValue());
			else if (value instanceof Double && modelColumn == 5) displayed = String.format(Locale.US, "%.0f %%", ((Double) value).doubleValue());
			Component component = super.getTableCellRendererComponent(table, displayed, selected, focus, row, column);
			setHorizontalAlignment(alignment); if (!selected) { component.setBackground(Color.BLACK); component.setForeground(Color.WHITE); } return component;
		}
	}
}
