package jspectrumanalyzer.iq;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

/** Compact, session-scoped NR cell list. */
final class NrCellTablePanel extends JPanel {
	private static final String WAIT = "wait...";
	private static final String[] COLUMNS = {
			"PCI", "PLMN", "Country", "Operator / network", "TAC", "NCI", "BW", "Band",
			"Air load", "SSB", "iSSB", "PSS", "SSS", "DM-RS", "CFO", "MIB", "SIB1"
	};
	private final DefaultTableModel model = new DefaultTableModel(COLUMNS, 0) {
		@Override public boolean isCellEditable(int row, int column) { return false; }
	};
	private final JTable table = new JTable(model);
	private final JLabel status = new JLabel("Waiting for 5G NR cells...");

	NrCellTablePanel() {
		super(new BorderLayout());
		setBackground(Color.BLACK);
		table.setAutoCreateRowSorter(true);
		table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
		table.setRowHeight(23);
		table.setFillsViewportHeight(true);
		table.setBackground(Color.BLACK);
		table.setForeground(Color.WHITE);
		table.setGridColor(new Color(0x333333));
		table.setSelectionBackground(new Color(0x244b66));
		table.setSelectionForeground(Color.WHITE);
		table.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
		DefaultTableCellRenderer header = new DefaultTableCellRenderer();
		header.setHorizontalAlignment(SwingConstants.CENTER);
		header.setOpaque(true);
		header.setBackground(new Color(0x202020));
		header.setForeground(Color.WHITE);
		header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, new Color(0x555555)));
		table.getTableHeader().setDefaultRenderer(header);
		int[] widths = { 46, 90, 95, 165, 62, 92, 62, 52, 72, 92, 48, 62, 62, 68, 72, 48, 48 };
		for (int index = 0; index < widths.length; index++) {
			table.getColumnModel().getColumn(index).setPreferredWidth(widths[index]);
		}
		DefaultTableCellRenderer centered = new DefaultTableCellRenderer();
		centered.setHorizontalAlignment(SwingConstants.CENTER);
		centered.setBackground(Color.BLACK);
		centered.setForeground(Color.WHITE);
		for (int index = 0; index < COLUMNS.length; index++) {
			table.getColumnModel().getColumn(index).setCellRenderer(centered);
		}
		status.setBorder(BorderFactory.createEmptyBorder(3, 7, 3, 7));
		status.setForeground(new Color(0xdddddd));
		status.setBackground(Color.BLACK);
		status.setOpaque(true);
		JScrollPane scroll = new JScrollPane(table);
		scroll.getViewport().setBackground(Color.BLACK);
		scroll.setPreferredSize(new Dimension(700, 155));
		add(scroll, BorderLayout.CENTER);
		add(status, BorderLayout.SOUTH);
	}

	void updateCells(Collection<NrSignalAnalyzer.Result> values, long centerFrequencyHz) {
		int selected = selectedPci();
		List<NrSignalAnalyzer.Result> snapshot = values == null
				? new ArrayList<NrSignalAnalyzer.Result>() : new ArrayList<NrSignalAnalyzer.Result>(values);
		model.setRowCount(0);
		for (NrSignalAnalyzer.Result cell : snapshot) {
			String firstPlmn = cell.sib1.valid && !cell.sib1.plmns.isEmpty() ? cell.sib1.plmns.get(0) : WAIT;
			String plmn = cell.sib1.valid && !cell.sib1.plmns.isEmpty()
					? String.join(", ", cell.sib1.plmns) : WAIT;
			String[] key = firstPlmn.split("-", 2);
			PlmnDatabase.Entry network = key.length == 2 ? PlmnDatabase.lookup(key[0], key[1]) : null;
			model.addRow(new Object[] {
					cell.pci, plmn, network == null ? WAIT : network.country,
					network == null ? WAIT : network.networkName(),
					cell.sib1.valid ? cell.sib1.trackingAreaCode : WAIT,
					cell.sib1.valid ? cell.sib1.cellIdentity : WAIT,
					cell.sib1.valid && cell.sib1.channelBandwidthMhz > 0
							? cell.sib1.channelBandwidthMhz + " MHz" : WAIT,
					cell.sib1.valid && !cell.sib1.frequencyBands.isEmpty()
							? joinBands(cell.sib1.frequencyBands) : WAIT,
					cell.load.valid ? String.format(Locale.US, "%.1f %%", cell.load.percent) : WAIT,
					String.format(Locale.US, "%.4f MHz", (centerFrequencyHz + cell.freqOffsetHz) / 1e6),
					cell.iBarSsb >= 0 ? cell.iBarSsb & 3 : WAIT,
					String.format(Locale.US, "%.1f %%", cell.pssCorrelation * 100d),
					String.format(Locale.US, "%.1f %%", cell.sssCorrelation * 100d),
					cell.dmrsConfirmed ? String.format(Locale.US, "%.1f %%", cell.dmrsCorrelation * 100d) : WAIT,
					cell.candidates.isEmpty() ? WAIT
							: String.format(Locale.US, "%+.0f Hz", cell.candidates.get(0).cfoHz),
					cell.mib.valid ? "OK" : WAIT,
					cell.sib1.valid ? "OK" : WAIT
			});
		}
		if (model.getRowCount() > 0) {
			int modelRow = 0;
			for (int row = 0; row < model.getRowCount(); row++) {
				if (((Number)model.getValueAt(row, 0)).intValue() == selected) {
					modelRow = row;
					break;
				}
			}
			int viewRow = table.convertRowIndexToView(modelRow);
			if (viewRow >= 0) table.setRowSelectionInterval(viewRow, viewRow);
		}
		boolean mib = false, sib1 = false;
		for (NrSignalAnalyzer.Result cell : snapshot) {
			mib |= cell.mib.valid;
			sib1 |= cell.sib1.valid;
		}
		status.setText(snapshot.size() + " 5G NR cell" + (snapshot.size() == 1 ? "" : "s")
				+ " - MIB " + (mib ? "OK" : "waiting") + " - SIB1 " + (sib1 ? "OK" : "waiting"));
	}

	int selectedPci() {
		int viewRow = table.getSelectedRow();
		if (viewRow < 0) return -1;
		Object value = model.getValueAt(table.convertRowIndexToModel(viewRow), 0);
		return value instanceof Number ? ((Number)value).intValue() : -1;
	}

	private static String joinBands(List<Integer> bands) {
		StringBuilder value = new StringBuilder();
		for (Integer band : bands) {
			if (value.length() > 0) value.append(", ");
			value.append('n').append(band);
		}
		return value.toString();
	}

}
