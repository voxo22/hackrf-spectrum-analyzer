package jspectrumanalyzer.iq;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;

/** Headless regression for the exclusive GPS/Galileo/BeiDou selector. */
public final class GnssL1OverviewPanelTest {
	public static void main(String[] args) throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			File settings;
			try { settings = File.createTempFile("hackrf-gnss-table-", ".ini"); }
			catch (Exception e) { throw new RuntimeException(e); }
			settings.deleteOnExit();
			List<GnssL1System> changes = new ArrayList<GnssL1System>();
			GnssL1OverviewPanel panel = new GnssL1OverviewPanel(
					new GpsL1SatelliteTablePanel(settings), changes::add);
			List<Component> radios = find(panel, JRadioButton.class);
			if (radios.size() != 3) throw new AssertionError("Three GNSS decoder radio buttons required");
			int selected = 0;
			for (Component component : radios) {
				JRadioButton button = (JRadioButton) component;
				if (!Color.WHITE.equals(button.getForeground())) throw new AssertionError("GNSS selector text is not white");
				if (button.isSelected()) selected++;
			}
			if (selected != 1 || panel.selectedSystem() != GnssL1System.GPS)
				throw new AssertionError("GPS must be the sole default decoder");
			for (Component component : find(panel, JScrollPane.class)) {
				JScrollPane scroll = (JScrollPane) component;
				if (!Color.BLACK.equals(scroll.getColumnHeader().getBackground()))
					throw new AssertionError("Trailing table-header area is not black");
			}
			for (Component component : find(panel, JTable.class)) {
				JTable table = (JTable) component;
				if (!Color.WHITE.equals(table.getForeground()) || !Color.WHITE.equals(table.getTableHeader().getForeground()))
					throw new AssertionError("Table text or header text is not white");
				if (table.getRowCount() != 32) throw new AssertionError("All 32 GPS rows must be visible before acquisition");
			}

			panel.setSystem(GnssL1System.GALILEO);
			panel.refresh(null, new GalileoE1SignalAnalyzer.Result(1_575_420_000L, 5_000_000, "one acquired",
					Collections.singletonList(new GalileoE1SignalAnalyzer.Satellite(
							11, 1500, 100, 25d, 50d, .5d, 42d, true)), 1, 36),
					1_575_420_000L, 5_000_000);
			if (panel.selectedSystem() != GnssL1System.GALILEO || !labelText(panel).contains("one acquired")
					|| ((JTable) find(panel, JTable.class).get(0)).getRowCount() != 36)
				throw new AssertionError("Galileo-only view did not activate");
			panel.setSystem(GnssL1System.BEIDOU);
			panel.refresh(null, null, 1_575_420_000L, 5_000_000);
			if (!labelText(panel).contains("BeiDou B1C waiting") || changes.size() != 2
					|| ((JTable) find(panel, JTable.class).get(0)).getRowCount() != 63)
				throw new AssertionError("BeiDou waiting mode or selector callback missing");
		});
		System.out.println("GNSS exclusive decoder selector test passed");
	}

	private static String labelText(Container root) {
		StringBuilder text = new StringBuilder();
		for (Component component : find(root, javax.swing.JLabel.class))
			text.append(((javax.swing.JLabel) component).getText()).append('\n');
		return text.toString();
	}
	private static List<Component> find(Container root, Class<?> type) {
		List<Component> found = new ArrayList<Component>();
		for (Component component : root.getComponents()) {
			if (type.isInstance(component)) found.add(component);
			if (component instanceof Container) found.addAll(find((Container) component, type));
		}
		return found;
	}
}
