package jspectrumanalyzer.iq;

import java.awt.Component;
import java.awt.Color;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingUtilities;

/** Headless UI regression for displayed navigation data and persistent selection. */
public final class GpsL1SatelliteTablePanelTest {
	public static void main(String[] args) throws Exception {
		File settings = File.createTempFile("hackrf-gps-table-", ".ini"); settings.deleteOnExit();
		Properties initial = new Properties(); initial.setProperty("wifi.table.columnOrder", "keep");
		try (FileOutputStream output = new FileOutputStream(settings)) { initial.store(output, "test"); }
		SwingUtilities.invokeAndWait(() -> {
			GpsL1NavigationData data = new GpsL1NavigationData();
			data.accept(GpsL1NavigationDataTest.sf1(345600, 0x2a5, 0), 0);
			data.accept(GpsL1NavigationDataTest.sf2(345606, 0xa5), 1000);
			data.accept(GpsL1NavigationDataTest.sf3(345612, 0xa5), 2000);
			GpsL1SignalAnalyzer.Satellite first = satellite(7).withNavigation("LNAV SF 3", data.snapshot(2000));
			GpsL1SignalAnalyzer.Satellite second = satellite(14).withNavigation("Not tracked", null);
			GpsL1SignalAnalyzer.Result result = new GpsL1SignalAnalyzer.Result(1575420000L, 2600000, 2600, "test",
					Arrays.asList(first, second), 2, 1);
			GpsL1SatelliteTablePanel panel = new GpsL1SatelliteTablePanel(settings); panel.refresh(result);
			JTable table = table(panel);
			JScrollPane scroll = scroll(panel);
			if (!Color.BLACK.equals(scroll.getColumnHeader().getBackground())
					|| !Color.WHITE.equals(table.getTableHeader().getForeground()))
				throw new AssertionError("GPS table header or trailing header area is not dark");
			int sortedViewColumn = table.convertColumnIndexToView(0), plainViewColumn = table.convertColumnIndexToView(1);
			Component sortedHeader = table.getTableHeader().getDefaultRenderer().getTableCellRendererComponent(
					table, table.getColumnName(0), false, false, -1, sortedViewColumn);
			Color sortedBackground = sortedHeader.getBackground(), sortedForeground = sortedHeader.getForeground();
			Component plainHeader = table.getTableHeader().getDefaultRenderer().getTableCellRendererComponent(
					table, table.getColumnName(1), false, false, -1, plainViewColumn);
			if (Color.BLACK.equals(sortedBackground) || !Color.BLACK.equals(plainHeader.getBackground())
					|| !Color.WHITE.equals(sortedForeground) || !Color.WHITE.equals(plainHeader.getForeground()))
				throw new AssertionError("Only sorted GPS columns may have colored headings");
			if (!"SV".equals(table.getColumnName(0)) || !"Signal state".equals(table.getColumnName(1)))
				throw new AssertionError("GNSS-neutral satellite table headings missing");
			int row = table.convertRowIndexToView(0);
			if (!"Healthy".equals(table.getValueAt(row, 7)) || !"Complete".equals(table.getValueAt(row, 8)))
				throw new AssertionError("Navigation fields missing in GPS table");
			Rectangle rect = table.getCellRect(row, table.convertColumnIndexToView(8), true);
			String tooltip = table.getToolTipText(new MouseEvent(table, MouseEvent.MOUSE_MOVED, 0L, 0, rect.x + 1, rect.y + 1, 0, false));
			if (tooltip == null || !tooltip.contains("IODE / IODC: 165 / 677") || !tooltip.contains("GPS week modulo 1024: 400"))
				throw new AssertionError("Decoded ephemeris tooltip missing");
			Component greenCell = table.getCellRenderer(row, 1).getTableCellRendererComponent(
					table, table.getValueAt(row, 1), false, false, row, 1);
			if (greenCell.getBackground().getGreen() <= greenCell.getBackground().getRed())
				throw new AssertionError("Completed live GPS row is not green");
			int undecodedRow = -1;
			for (int viewRow = 0; viewRow < table.getRowCount(); viewRow++)
				if ("G14".equals(table.getValueAt(viewRow, 0))) { undecodedRow = viewRow; break; }
			Component undecodedCell = table.getCellRenderer(undecodedRow, 1).getTableCellRendererComponent(
					table, table.getValueAt(undecodedRow, 1), false, false, undecodedRow, 1);
			if (!Color.BLACK.equals(undecodedCell.getBackground()))
				throw new AssertionError("Never-decoded satellite row must not be colored");
			table.setRowSelectionInterval(row, row);
			GalileoNavigationData.Snapshot galileoNav = new GalileoNavigationData.Snapshot(
					4, 2, false, false, 0, 0, 1234, 345678, 1e-9, -2e-9, null);
			GalileoE1SignalAnalyzer.Satellite galileoSatellite = new GalileoE1SignalAnalyzer.Satellite(
					11, 1500, 100, 25d, 50d, .5d, .3d, .4d, 42d, true).withTracking(
							new GalileoE1StreamTracker.Tracking(500L, .5d, true,
									"I/NAV W4 CRC OK; eph 2/4 words",
									new GalileoInavDecoder.Page(true, 4, new int[128], 1d, 1d), galileoNav));
			GalileoE1SignalAnalyzer.Result galileo = new GalileoE1SignalAnalyzer.Result(1575420000L, 5000000,
					"one acquired", Collections.singletonList(galileoSatellite), 1, 36, 1, 1);
			panel.setSystem(GnssL1System.GALILEO);
			panel.refreshGalileo(galileo);
			boolean e11Found = false;
			int e11Row = -1;
			for (int viewRow = 0; viewRow < table.getRowCount(); viewRow++) if ("E11".equals(table.getValueAt(viewRow, 0))) {
				e11Row = viewRow;
				e11Found = "E1-B tracked".equals(table.getValueAt(viewRow, 1))
						&& "Signal OK / data valid".equals(table.getValueAt(viewRow, 7))
						&& "2/4 words".equals(table.getValueAt(viewRow, 8));
				Rectangle healthRect = table.getCellRect(viewRow, table.convertColumnIndexToView(7), true);
				String healthTip = table.getToolTipText(new MouseEvent(table, MouseEvent.MOUSE_MOVED, 0L, 0,
						healthRect.x + 1, healthRect.y + 1, 0, false));
				if (healthTip == null || !healthTip.contains("GST week/TOW: 1234 / 345678 s"))
					throw new AssertionError("Galileo health/GST tooltip missing");
				Rectangle stateRect = table.getCellRect(viewRow, table.convertColumnIndexToView(1), true);
				String stateTip = table.getToolTipText(new MouseEvent(table, MouseEvent.MOUSE_MOVED, 0L, 0,
						stateRect.x + 1, stateRect.y + 1, 0, false));
				if (stateTip == null || !stateTip.contains("E1-B 30.0%") || !stateTip.contains("E1-C 40.0%"))
					throw new AssertionError("Galileo E1-B/C acquisition tooltip missing");
			}
			if (!e11Found) throw new AssertionError("Galileo E1-B navigation row missing");
			table.setRowSelectionInterval(e11Row, e11Row);
			panel.refreshGalileo(GalileoE1SignalAnalyzer.Result.empty(1575420000L, 5000000, "signal gone"));
			if (table.getRowCount() != 36 || table.getSelectedRow() < 0
					|| !"E11".equals(table.getValueAt(table.getSelectedRow(), 0))
					|| !"Signal lost (retained)".equals(table.getValueAt(table.getSelectedRow(), 1))
					|| !"2/4 words".equals(table.getValueAt(table.getSelectedRow(), 8)))
				throw new AssertionError("Decoded Galileo row or selection did not survive signal loss");
			int lostE11Row = table.getSelectedRow();
			table.clearSelection();
			Component redCell = table.getCellRenderer(lostE11Row, 1).getTableCellRendererComponent(
					table, table.getValueAt(lostE11Row, 1), false, false, lostE11Row, 1);
			if (redCell.getBackground().getRed() <= redCell.getBackground().getGreen())
				throw new AssertionError("Lost retained satellite row is not red");
			BeiDouB1cNavDecoder.Frame sf1 = new BeiDouB1cNavDecoder.Frame(true, 19, 73, 1314, 1d, 1L);
			BeiDouB1cSignalAnalyzer.Satellite beidouBase = new BeiDouB1cSignalAnalyzer.Satellite(
					19, 300, 100, 31d, 150d, .08d, 42d, true);
			BeiDouB1cSignalAnalyzer.Satellite beidouYellow = beidouBase.withTracking(
					new BeiDouB1cStreamTracker.Tracking(100L, .05d, true, "B-CNAV1 SF1 BCH OK", sf1,
							BeiDouB1cNavigationData.Snapshot.waiting()));
			BeiDouB1cSignalAnalyzer.Result beidouResult = new BeiDouB1cSignalAnalyzer.Result(
					1575420000L, 5000000, "one acquired", Collections.singletonList(beidouYellow),
					1, 63, 1, 1, 0, GpsL1PositionSolver.Solution.waiting("waiting"),
					Collections.singletonList(beidouYellow), Collections.<BeiDouB1cStreamTracker.Observation>emptyList());
			panel.setSystem(GnssL1System.BEIDOU); panel.refreshBeidou(beidouResult, 1575420000L, 5000000);
			int c19Row = findRow(table, "C19"); Component yellowCell = table.getCellRenderer(c19Row, 1)
					.getTableCellRendererComponent(table, table.getValueAt(c19Row, 1), false, false, c19Row, 1);
			if (yellowCell.getBackground().getRed() <= yellowCell.getBackground().getBlue()
					|| yellowCell.getBackground().getGreen() <= yellowCell.getBackground().getBlue())
				throw new AssertionError("Live partial BeiDou NAV row is not yellow");
			double[] completeSymbols = new double[1800]; Arrays.fill(completeSymbols, 1d);
			System.arraycopy(BeiDouB1cNavDecoder.encodeSubframe1(19, 73), 0, completeSymbols, 0, 72);
			BeiDouB1cNavigationData.FullFrame full = BeiDouB1cNavigationData.decode(completeSymbols, 19, 1L);
			BeiDouB1cNavigationData.Snapshot completeNav = new BeiDouB1cNavigationData.Snapshot(full, 1, full.ephemeris);
			BeiDouB1cSignalAnalyzer.Satellite beidouGreen = beidouBase.withTracking(
					new BeiDouB1cStreamTracker.Tracking(1800L, .05d, true, "B-CNAV1 SF2 CRC OK; eph Complete",
							sf1, completeNav));
			beidouResult = new BeiDouB1cSignalAnalyzer.Result(1575420000L, 5000000, "complete",
					Collections.singletonList(beidouGreen), 1, 63, 1, 1, 1,
					GpsL1PositionSolver.Solution.waiting("waiting"), Collections.singletonList(beidouGreen),
					Collections.<BeiDouB1cStreamTracker.Observation>emptyList());
			panel.refreshBeidou(beidouResult, 1575420000L, 5000000); c19Row = findRow(table, "C19");
			Component beidouGreenCell = table.getCellRenderer(c19Row, 1).getTableCellRendererComponent(
					table, table.getValueAt(c19Row, 1), false, false, c19Row, 1);
			if (beidouGreenCell.getBackground().getGreen() <= beidouGreenCell.getBackground().getRed())
				throw new AssertionError("Complete live BeiDou ephemeris row is not green");
			panel.refreshBeidou(BeiDouB1cSignalAnalyzer.Result.empty(1575420000L, 5000000, "lost"),
					1575420000L, 5000000); c19Row = findRow(table, "C19");
			Component beidouRed = table.getCellRenderer(c19Row, 1).getTableCellRendererComponent(
					table, table.getValueAt(c19Row, 1), false, false, c19Row, 1);
			if (beidouRed.getBackground().getRed() <= beidouRed.getBackground().getGreen())
				throw new AssertionError("Lost retained BeiDou row is not red");
			panel.setSystem(GnssL1System.GPS);
			if (table.getRowCount() != 32 || table.getSelectedRow() < 0
					|| !"G07".equals(table.getValueAt(table.getSelectedRow(), 0))
					|| !"Signal lost (retained)".equals(table.getValueAt(table.getSelectedRow(), 1)))
				throw new AssertionError("Per-system GPS history or selection did not survive a decoder switch");
			panel.refreshGps(result);
			row = table.convertRowIndexToView(0);
			panel.refreshGps(result);
			if (table.getSelectedRow() < 0 || !"G07".equals(table.getValueAt(table.getSelectedRow(), 0)))
				throw new AssertionError("GPS table selection did not survive refresh");
			panel.addNotify();
			table.moveColumn(table.convertColumnIndexToView(4), 0);
			table.getColumnModel().getColumn(0).setPreferredWidth(177);
			table.getColumnModel().getColumn(0).setWidth(177);
			table.getRowSorter().setSortKeys(Arrays.asList(new RowSorter.SortKey(4, SortOrder.DESCENDING),
					new RowSorter.SortKey(2, SortOrder.ASCENDING)));
			panel.removeNotify();
			GpsL1SatelliteTablePanel restored = new GpsL1SatelliteTablePanel(settings); restored.addNotify();
			JTable restoredTable = table(restored);
			if (restoredTable.convertColumnIndexToView(4) != 0 || restoredTable.getColumnModel().getColumn(0).getWidth() != 177)
				throw new AssertionError("GPS column order or width not restored");
			if (!restoredTable.getRowSorter().getSortKeys().equals(Arrays.asList(new RowSorter.SortKey(4, SortOrder.DESCENDING),
					new RowSorter.SortKey(2, SortOrder.ASCENDING)))) throw new AssertionError("GPS multi-column sort not restored");
			restoredTable.getRowSorter().setSortKeys(Collections.<RowSorter.SortKey>emptyList()); restored.removeNotify();
			GpsL1SatelliteTablePanel unsorted = new GpsL1SatelliteTablePanel(settings); unsorted.addNotify();
			if (!table(unsorted).getRowSorter().getSortKeys().isEmpty()) throw new AssertionError("Cleared GPS sort not restored");
			unsorted.removeNotify();
		});
		Properties saved = new Properties();
		try (FileInputStream input = new FileInputStream(settings)) { saved.load(input); }
		if (!"keep".equals(saved.getProperty("wifi.table.columnOrder"))) throw new AssertionError("Other analyzer settings were lost");
		System.out.println("GPS navigation table, selection and INI layout persistence tests passed");
	}
	private static JTable table(GpsL1SatelliteTablePanel panel) {
		return (JTable) scroll(panel).getViewport().getView();
	}
	private static JScrollPane scroll(GpsL1SatelliteTablePanel panel) {
		for (Component component : panel.getComponents()) if (component instanceof JScrollPane) return (JScrollPane) component;
		throw new AssertionError("GPS table missing");
	}
	private static int findRow(JTable table, String key) {
		for (int row = 0; row < table.getRowCount(); row++) if (key.equals(table.getValueAt(row, 0))) return row;
		throw new AssertionError(key + " row missing");
	}
	private static GpsL1SignalAnalyzer.Satellite satellite(int prn) {
		return new GpsL1SignalAnalyzer.Satellite(prn, 0, 0, 0, 0d, 100d, 1d, 50d, 20, 1d, true, true);
	}
}
