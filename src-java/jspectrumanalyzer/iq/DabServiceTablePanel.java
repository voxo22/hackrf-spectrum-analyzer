package jspectrumanalyzer.iq;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

/** DAB services and their FIC-signalled technical parameters. */
final class DabServiceTablePanel extends JPanel {
	private static final String[] COLUMNS = {
			"Name", "Service ID (SID)", "Component", "Subchannel", "Bitrate", "Protection"
	};
	private final DefaultTableModel model = new DefaultTableModel(COLUMNS, 0) {
		@Override public boolean isCellEditable(int row, int column) { return false; }
	};
	private final JTable table = new JTable(model);
	private final JLabel status = new JLabel("Waiting for DAB services...");
	private final List<Long> rowIds = new ArrayList<Long>();
	private DabFicDecoder.Result displayed;

	DabServiceTablePanel() {
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
		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

		DefaultTableCellRenderer header = new DefaultTableCellRenderer();
		header.setHorizontalAlignment(SwingConstants.CENTER);
		header.setOpaque(true);
		header.setBackground(new Color(0x202020));
		header.setForeground(Color.WHITE);
		header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, new Color(0x555555)));
		table.getTableHeader().setDefaultRenderer(header);
		int[] widths = {130, 125, 165, 105, 110, 130};
		for (int column = 0; column < widths.length; column++)
			table.getColumnModel().getColumn(column).setPreferredWidth(widths[column]);

		DefaultTableCellRenderer left = renderer(SwingConstants.LEFT);
		DefaultTableCellRenderer centered = renderer(SwingConstants.CENTER);
		table.getColumnModel().getColumn(0).setCellRenderer(left);
		for (int column = 1; column < COLUMNS.length; column++)
			table.getColumnModel().getColumn(column).setCellRenderer(centered);

		status.setBorder(BorderFactory.createEmptyBorder(3, 7, 3, 7));
		status.setForeground(new Color(0xdddddd));
		status.setBackground(Color.BLACK);
		status.setOpaque(true);
		JScrollPane scroll = new JScrollPane(table);
		scroll.getViewport().setBackground(Color.BLACK);
		scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		scroll.setPreferredSize(new Dimension(900, 260));
		add(scroll, BorderLayout.CENTER);
		add(status, BorderLayout.SOUTH);
	}

	void update(DabFicDecoder.Result result) {
		if (result == displayed) return;
		displayed = result;
		long selected = selectedServiceId();
		model.setRowCount(0);
		rowIds.clear();
		if (result != null) {
			int ordinal = 0;
			for (DabFicDecoder.Service service : result.services) {
				ordinal++;
				rowIds.add(Long.valueOf(service.id));
				model.addRow(new Object[] {
						service.label.length() == 0 ? "Service " + ordinal : service.label,
						serviceId(service.id),
						service.componentType.length() == 0 ? "--" : service.componentType,
						service.subchannelId < 0 ? "--" : Integer.toString(service.subchannelId),
						service.bitrateKbps <= 0 ? "--" : service.bitrateKbps + " kbit/s",
						service.protection == null || service.protection.length() == 0
								? "--" : service.protection
				});
			}
		}
		restoreSelection(selected);
		int serviceCount = result == null ? 0 : result.services.size();
		int subchannels = result == null ? 0 : result.subchannelCount;
		double rolling = result == null ? 0d : result.rollingQuality;
		status.setText(String.format(Locale.US, "%d service%s / %d subchannel%s   -   FIC rolling CRC %.0f %%",
				serviceCount, serviceCount == 1 ? "" : "s", subchannels,
				subchannels == 1 ? "" : "s", rolling));
	}

	private long selectedServiceId() {
		int viewRow = table.getSelectedRow();
		if (viewRow < 0) return Long.MIN_VALUE;
		int modelRow = table.convertRowIndexToModel(viewRow);
		return modelRow >= 0 && modelRow < rowIds.size() ? rowIds.get(modelRow).longValue() : Long.MIN_VALUE;
	}

	private void restoreSelection(long serviceId) {
		if (rowIds.isEmpty()) return;
		int modelRow = 0;
		for (int row = 0; row < rowIds.size(); row++) {
			if (rowIds.get(row).longValue() == serviceId) { modelRow = row; break; }
		}
		int viewRow = table.convertRowIndexToView(modelRow);
		if (viewRow >= 0) table.setRowSelectionInterval(viewRow, viewRow);
	}

	private static String serviceId(long id) {
		return id <= 0xffffL ? String.format(Locale.US, "0x%04X", id)
				: String.format(Locale.US, "0x%08X", id);
	}

	private static DefaultTableCellRenderer renderer(int alignment) {
		DefaultTableCellRenderer renderer = new DefaultTableCellRenderer();
		renderer.setHorizontalAlignment(alignment);
		renderer.setBackground(Color.BLACK);
		renderer.setForeground(Color.WHITE);
		return renderer;
	}
}
