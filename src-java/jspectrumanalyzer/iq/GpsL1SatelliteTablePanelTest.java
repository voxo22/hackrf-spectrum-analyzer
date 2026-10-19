package jspectrumanalyzer.iq;

import java.awt.Component;
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
			int row = table.convertRowIndexToView(0);
			if (!"Healthy".equals(table.getValueAt(row, 7)) || !"Complete".equals(table.getValueAt(row, 8)))
				throw new AssertionError("Navigation fields missing in GPS table");
			Rectangle rect = table.getCellRect(row, table.convertColumnIndexToView(8), true);
			String tooltip = table.getToolTipText(new MouseEvent(table, MouseEvent.MOUSE_MOVED, 0L, 0, rect.x + 1, rect.y + 1, 0, false));
			if (tooltip == null || !tooltip.contains("IODE / IODC: 165 / 677") || !tooltip.contains("GPS week modulo 1024: 400"))
				throw new AssertionError("Decoded ephemeris tooltip missing");
			table.setRowSelectionInterval(row, row); panel.refresh(result);
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
		for (Component component : panel.getComponents()) if (component instanceof JScrollPane)
			return (JTable) ((JScrollPane) component).getViewport().getView();
		throw new AssertionError("GPS table missing");
	}
	private static GpsL1SignalAnalyzer.Satellite satellite(int prn) {
		return new GpsL1SignalAnalyzer.Satellite(prn, 0, 0, 0, 0d, 100d, 1d, 50d, 20, 1d, true, true);
	}
}
