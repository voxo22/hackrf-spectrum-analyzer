package jspectrumanalyzer.iq;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
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

/** Compact, session-scoped UMTS primary-scrambling-code list. */
final class UmtsCellTablePanel extends JPanel {
	private static final String WAIT = "wait...";
	private static final String[] COLUMNS = {
			"PSC", "PLMN", "Country", "Operator / network", "LAC", "Cell ID", "UARFCN",
			"CPICH", "Air load", "Code group", "Ec/N0", "RSCP", "CFO", "IQ", "BCH", "MIB", "SIB1", "SIB3"
	};
	private final DefaultTableModel model = new DefaultTableModel(COLUMNS, 0) {
		@Override public boolean isCellEditable(int row, int column) { return false; }
	};
	private final JTable table = new JTable(model);
	private final JLabel status = new JLabel("Waiting for UMTS cells...");

	UmtsCellTablePanel() {
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
		int[] widths = { 48, 88, 92, 165, 58, 82, 68, 96, 70, 78, 70, 76, 82, 78, 62, 48, 48, 48 };
		for (int index = 0; index < widths.length; index++)
			table.getColumnModel().getColumn(index).setPreferredWidth(widths[index]);
		DefaultTableCellRenderer centered = new DefaultTableCellRenderer();
		centered.setHorizontalAlignment(SwingConstants.CENTER);
		centered.setBackground(Color.BLACK);
		centered.setForeground(Color.WHITE);
		for (int index = 0; index < COLUMNS.length; index++)
			table.getColumnModel().getColumn(index).setCellRenderer(centered);
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

	void updateCells(Collection<UmtsSignalAnalyzer.Candidate> values, long centerFrequencyHz, int currentPsc) {
		int selected = selectedPsc();
		if (table.getRowSorter() != null) table.getRowSorter().setSortKeys(Collections.emptyList());
		List<UmtsSignalAnalyzer.Candidate> cells = values == null
				? new ArrayList<UmtsSignalAnalyzer.Candidate>()
				: new ArrayList<UmtsSignalAnalyzer.Candidate>(values);
		Collections.sort(cells, new Comparator<UmtsSignalAnalyzer.Candidate>() {
			@Override public int compare(UmtsSignalAnalyzer.Candidate left, UmtsSignalAnalyzer.Candidate right) {
				if (left.psc == currentPsc && right.psc != currentPsc) return -1;
				if (right.psc == currentPsc && left.psc != currentPsc) return 1;
				return Double.compare(right.cpichCorrelation, left.cpichCorrelation);
			}
		});
		model.setRowCount(0);
		for (UmtsSignalAnalyzer.Candidate cell : cells) {
			UmtsSystemInformationDecoder.Snapshot si = cell.systemInformation;
			PlmnDatabase.Entry plmn = si.mibValid ? PlmnDatabase.lookup(si.mcc, si.mnc) : null;
			long carrierHz = centerFrequencyHz > 0 ? Math.round(centerFrequencyHz + cell.cfoHz) : -1L;
			int uarfcn = carrierHz > 0 ? UmtsSignalAnalyzer.uarfcnFromFrequency(carrierHz) : -1;
			model.addRow(new Object[] {
					cell.psc, si.mibValid ? si.mcc + "-" + si.mnc : WAIT,
					plmn == null ? WAIT : plmn.country,
					plmn == null ? WAIT : plmn.networkName(),
					si.sib1Valid ? si.lac : WAIT,
					si.sib3Valid ? si.cellIdentity : WAIT, uarfcn >= 0 ? uarfcn : WAIT,
					carrierHz > 0 ? String.format(Locale.US, "%.6f MHz", carrierHz / 1e6) : WAIT,
					cell.load.valid ? String.format(Locale.US, "%.1f %%", cell.load.percent) : WAIT,
					cell.codeGroup,
					String.format(Locale.US, "%.1f dB", cell.ecNoDb),
					String.format(Locale.US, "%.1f dBFS", cell.rscpDbfs),
					String.format(Locale.US, "%+.0f Hz", cell.cfoHz),
					cell.iqOrientation > 0 ? "normal" : "conjugated",
					cell.bch.valid ? "CRC OK" : WAIT,
					si.mibValid ? "OK" : WAIT, si.sib1Valid ? "OK" : WAIT,
					si.sib3Valid ? "OK" : WAIT
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
		status.setText(cells.size() + " UMTS cell" + (cells.size() == 1 ? "" : "s")
				+ (currentPsc >= 0 ? " - current PSC " + currentPsc + " is first" : "")
				+ " - select a PSC and open Cell details");
	}

	int selectedPsc() {
		int viewRow = table.getSelectedRow();
		if (viewRow < 0) return -1;
		Object value = model.getValueAt(table.convertRowIndexToModel(viewRow), 0);
		return value instanceof Number ? ((Number)value).intValue() : -1;
	}
}
