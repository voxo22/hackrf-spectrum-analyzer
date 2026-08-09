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

/** Compact, session-scoped LTE cell list. Detailed SIB fields live in the cell-details window. */
final class LteCellTablePanel extends JPanel {
	private static final String WAIT="wait\u2026";
	private static final String[] COLUMNS = {
			"PCI", "PLMN", "Country", "Operator / network", "TAC", "Cell ID",
			"Band", "BW", "Ports", "Air load", "PSS", "SSS", "CFO", "MIB", "SIB1"
	};
	private final DefaultTableModel model = new DefaultTableModel(COLUMNS, 0) {
		@Override public boolean isCellEditable(int row, int column) { return false; }
	};
	private final JTable table = new JTable(model);
	private final JLabel status = new JLabel("Waiting for LTE cells...");

	LteCellTablePanel() {
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
		DefaultTableCellRenderer header=new DefaultTableCellRenderer();
		header.setHorizontalAlignment(SwingConstants.CENTER);
		header.setOpaque(true);header.setBackground(new Color(0x202020));header.setForeground(Color.WHITE);
		header.setBorder(BorderFactory.createMatteBorder(0,0,1,1,new Color(0x555555)));
		table.getTableHeader().setDefaultRenderer(header);
		int[] widths={46,90,95,165,55,82,45,65,48,70,62,62,72,48,48};
		for(int q=0;q<widths.length;q++)table.getColumnModel().getColumn(q).setPreferredWidth(widths[q]);
		DefaultTableCellRenderer centered=new DefaultTableCellRenderer();
		centered.setHorizontalAlignment(SwingConstants.CENTER);
		centered.setBackground(Color.BLACK);centered.setForeground(Color.WHITE);
		for(int q=0;q<COLUMNS.length;q++)table.getColumnModel().getColumn(q).setCellRenderer(centered);
		status.setBorder(BorderFactory.createEmptyBorder(3,7,3,7));
		status.setForeground(new Color(0xdddddd));status.setBackground(Color.BLACK);status.setOpaque(true);
		JScrollPane scroll=new JScrollPane(table);
		scroll.getViewport().setBackground(Color.BLACK);
		scroll.setPreferredSize(new Dimension(700,155));
		add(scroll,BorderLayout.CENTER);add(status,BorderLayout.SOUTH);
	}

	void updateCells(Collection<LteSignalAnalyzer.Candidate> values) {
		int selected=selectedPci();
		List<LteSignalAnalyzer.Candidate> snapshot=values==null
				?new ArrayList<LteSignalAnalyzer.Candidate>()
				:new ArrayList<LteSignalAnalyzer.Candidate>(values);
		model.setRowCount(0);
		for(LteSignalAnalyzer.Candidate c:snapshot) {
			String firstPlmn=c.sib1.valid&&!c.sib1.plmns.isEmpty()?c.sib1.plmns.get(0):WAIT;
			String plmn=c.sib1.valid&&!c.sib1.plmns.isEmpty()?String.join(", ",c.sib1.plmns):WAIT;
			String[] key=firstPlmn.split("-",2);
			PlmnDatabase.Entry network=key.length==2?PlmnDatabase.lookup(key[0],key[1]):null;
			model.addRow(new Object[]{
					c.pci,plmn,network==null?WAIT:network.country,network==null?WAIT:network.networkName(),
					c.sib1.valid?c.sib1.trackingAreaCode:WAIT,c.sib1.valid?c.sib1.cellIdentity:WAIT,
					c.sib1.valid?c.sib1.frequencyBand:WAIT,c.mib.valid?bandwidth(c.mib.bandwidthRb):WAIT,
					c.mib.valid?c.mib.antennaPorts:WAIT,
					c.load.valid?String.format(Locale.US,"%.1f %%",c.load.percent):WAIT,
					String.format(Locale.US,"%.1f %%",c.correlation*100),
					String.format(Locale.US,"%.1f %%",c.sssCorrelation*100),
					String.format(Locale.US,"%+.0f Hz",c.cfoHz),
					c.mib.valid?"OK":WAIT,c.sib1.valid?"OK":WAIT
			});
		}
		if(model.getRowCount()>0){
			int modelRow=0;
			for(int q=0;q<model.getRowCount();q++)if(((Number)model.getValueAt(q,0)).intValue()==selected){modelRow=q;break;}
			int view=table.convertRowIndexToView(modelRow);if(view>=0)table.setRowSelectionInterval(view,view);
		}
		status.setText(snapshot.size()+" LTE cell"+(snapshot.size()==1?"":"s")
				+" - select a row and open Cell details");
	}

	int selectedPci() {
		int view=table.getSelectedRow();if(view<0)return -1;
		Object value=model.getValueAt(table.convertRowIndexToModel(view),0);
		return value instanceof Number?((Number)value).intValue():-1;
	}

	private static String bandwidth(int rb) {
		switch(rb){case 6:return"1.4 MHz";case 15:return"3 MHz";case 25:return"5 MHz";
			case 50:return"10 MHz";case 75:return"15 MHz";case 100:return"20 MHz";default:return rb+" RB";}
	}
}
