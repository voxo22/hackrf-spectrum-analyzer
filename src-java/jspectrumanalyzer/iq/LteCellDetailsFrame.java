package jspectrumanalyzer.iq;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.util.Locale;
import java.util.function.IntFunction;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;

/** Detailed public LTE system information for the PCI selected in the main table. */
final class LteCellDetailsFrame extends JFrame {
	private static final String WAIT="wait\u2026";
	private final IntFunction<LteSignalAnalyzer.Candidate> cellSupplier;
	private final IntFunction<LteSignalAnalyzer.SiData> siSupplier;
	private final java.util.function.IntSupplier pciSupplier;
	private final java.util.function.IntToLongFunction pagingCountSupplier;
	private final IntFunction<Double> loadSupplier;
	private final DefaultTableModel system=model("Parameter","Value","Meaning");
	private final DefaultTableModel radio=model("Parameter","Value","Meaning");
	private final DefaultTableModel reselection=model("Parameter","Value","Meaning");
	private final DefaultTableModel paging=model("Statistic","Value","Meaning");
	private final JLabel status=new JLabel("Select an LTE cell in the main window.");
	private final Timer timer;

	LteCellDetailsFrame(java.util.function.IntSupplier pciSupplier,
			IntFunction<LteSignalAnalyzer.Candidate> cellSupplier,
			IntFunction<LteSignalAnalyzer.SiData> siSupplier,
			java.util.function.IntToLongFunction pagingCountSupplier,
			IntFunction<Double> loadSupplier) {
		super("LTE Cell Details");
		this.pciSupplier=pciSupplier;this.cellSupplier=cellSupplier;this.siSupplier=siSupplier;this.pagingCountSupplier=pagingCountSupplier;this.loadSupplier=loadSupplier;
		setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		status.setBorder(new EmptyBorder(8,10,8,10));status.setForeground(Color.WHITE);
		status.setOpaque(true);status.setBackground(Color.BLACK);
		JTabbedPane tabs=new JTabbedPane();
		tabs.addTab("SYSTEM",panel(system));
		tabs.addTab("RADIO",panel(radio));
		tabs.addTab("RESELECTION",panel(reselection));
		tabs.addTab("PAGING",panel(paging));
		tabs.setEnabledAt(1,false);
		tabs.setEnabledAt(2,false);
		tabs.setEnabledAt(3,false);
		add(status,BorderLayout.NORTH);add(tabs,BorderLayout.CENTER);
		setSize(820,500);setMinimumSize(new Dimension(680,380));
		timer=new Timer(500,e->refresh());timer.start();refresh();
	}

	@Override public void dispose(){timer.stop();super.dispose();}

	private void refresh() {
		int pci=pciSupplier.getAsInt();LteSignalAnalyzer.Candidate c=pci<0?null:cellSupplier.apply(pci);
		system.setRowCount(0);radio.setRowCount(0);reselection.setRowCount(0);paging.setRowCount(0);
		if(c==null){status.setText("Select an LTE cell in the main window.");return;}
		status.setText("PCI "+pci+" - last CRC-verified MIB and System Information retained during this session");
		add(system,"Physical Cell ID",Integer.toString(c.pci),"Radio-layer identity; it is not a globally unique BTS identifier.");
		add(system,"PSS / SSS quality",String.format(Locale.US,"%.1f %% / %.1f %%",c.correlation*100,c.sssCorrelation*100),"Synchronization quality for this PCI.");
		add(system,"Frequency offset",String.format(Locale.US,"%+.0f Hz",c.cfoHz),"Estimated tuning error relative to this cell.");
		add(system,"Channel bandwidth",c.mib.valid?bandwidth(c.mib.bandwidthRb):WAIT,"Bandwidth of the decoded LTE carrier.");
		add(system,"Antenna ports",c.mib.valid?Integer.toString(c.mib.antennaPorts):WAIT,"CRS antenna ports advertised by the cell, not the number of detected sites.");
		add(system,"System frame number",c.mib.valid?Integer.toString(c.mib.systemFrameNumber):WAIT,"Radio-frame timing from MIB.");
		add(system,"PLMN",c.sib1.valid?String.join(", ",c.sib1.plmns):WAIT,"Public mobile network identifier.");
		add(system,"Tracking Area Code",c.sib1.valid?Integer.toString(c.sib1.trackingAreaCode):WAIT,"Area used for idle-mode mobility and paging.");
		add(system,"E-UTRAN Cell ID",c.sib1.valid?Integer.toString(c.sib1.cellIdentity):WAIT,"Cell identity broadcast in SIB1.");
		add(system,"LTE band",c.sib1.valid?Integer.toString(c.sib1.frequencyBand):WAIT,"Operating band advertised by SIB1.");
		add(system,"Cell access",c.sib1.valid?(c.sib1.cellBarred?"Barred":"Available"):WAIT,"Whether ordinary UE access is permitted.");
		add(system,"SI schedule",c.sib1.valid?String.join(" / ",c.sib1.schedules):WAIT,"When the remaining System Information blocks are transmitted.");
		double recentLoad=loadSupplier.apply(pci);
		add(system,"Recent downlink air load",Double.isNaN(recentLoad)?WAIT:String.format(Locale.US,"%.1f %%",recentLoad),"Estimated occupied PRBs across the tuned LTE carrier.");
		add(system,"Latest air-load measurement",c.load.valid?String.format(Locale.US,"%.1f %%  (%d/%d)",c.load.percent,c.load.activePrbSamples,c.load.totalPrbSamples):WAIT,"PRB/subframe samples whose data-symbol energy exceeded the measured guard-band noise.");
		add(system,"Air-load observed subframes",c.load.valid?Integer.toString(c.load.subframes):WAIT,"Subframes contributing to the latest estimate.");
		add(system,"Air-load scope","Current downlink carrier only","Does not include other component carriers, uplink traffic, or other LTE bands.");
		add(system,"Air-load co-channel cells","Combined radio energy","Traffic from multiple PCI on the same frequency cannot be separated by this energy estimate.");
		add(system,"Air-load interpretation","Radio-resource occupancy estimate","Not the operator's internal BTS CPU/backhaul load and not guaranteed to equal scheduled throughput.");

		LteSignalAnalyzer.SiData retained=siSupplier.apply(pci);
		LteSignalAnalyzer.SiData s=retained!=null?retained:c.si;
		add(radio,"Paging cycle",s.valid?"RF"+s.pagingCycle+"; nB "+pagingNb(s.pagingNb):WAIT,"Controls paging occasions; it is not a paging-event count.");
		add(radio,"Random-access preambles",s.valid?Integer.toString(s.rachPreambles):WAIT,"Number of contention-based RACH signatures configured by the cell.");
		add(radio,"PRACH configuration",s.valid?"Index "+s.prachConfig+", frequency offset "+s.prachOffset:WAIT,"Time/frequency placement used for initial uplink access.");
		add(radio,"PRACH root sequence",s.valid?Integer.toString(s.prachRoot):WAIT,"Root used to construct random-access preambles.");
		add(radio,"Reference signal power",s.valid?s.referenceSignalPower+" dBm":WAIT,"Nominal CRS transmit power signalled by the cell; not received signal strength.");
		add(radio,"PUSCH 64QAM",s.valid?(s.pusch64Qam?"Supported":"Not advertised"):WAIT,"Whether 64QAM is allowed on the uplink shared channel.");
		add(radio,"Uplink carrier",s.valid?(s.ulCarrier<0?"Same EARFCN relation as downlink":"EARFCN "+s.ulCarrier):WAIT,"Explicit UL EARFCN is optional in SIB2.");
		add(radio,"Uplink bandwidth",s.valid?(s.ulBandwidth<0?"Same as downlink":bandwidthIndex(s.ulBandwidth)):WAIT,"Configured uplink channel bandwidth.");
		add(radio,"PUSCH hopping",s.valid?(s.puschIntraAndInter?"Intra- and inter-subframe":"Inter-subframe"):WAIT,"Frequency-hopping mode for uplink data.");
		add(radio,"P0 nominal PUSCH",s.valid?s.p0Pusch+" dBm":WAIT,"Open-loop uplink power-control reference.");

		add(reselection,"Cell reselection priority",s.sib3Valid?Integer.toString(s.reselectionPriority):WAIT,"Priority assigned to the serving LTE frequency.");
		add(reselection,"Minimum received level",s.sib3Valid?s.qRxLevMin+" dBm":WAIT,"Minimum SIB3 level used in cell-selection criteria.");
		add(reselection,"Serving-low threshold",s.sib3Valid?Integer.toString(s.threshServingLow):WAIT,"Threshold for leaving a low-priority serving frequency.");
		add(reselection,"Hysteresis",s.sib3Valid?qHyst(s.qHyst)+" dB":WAIT,"Reduces repeated reselection between similar cells.");
		add(reselection,"Reselection timer",s.sib3Valid?s.tReselection+" s":WAIT,"Condition must persist for this interval before reselection.");
		add(reselection,"Antenna port 1 present",s.sib3Valid?(s.antennaPort1?"Yes":"No"):WAIT,"SIB3 indication used for intra-frequency measurements.");

		add(paging,"Configured paging cycle",s.valid?"RF"+s.pagingCycle:WAIT,"Broadcast configuration from SIB2.");
		add(paging,"Observed paging allocations",Long.toString(pagingCountSupplier.applyAsLong(pci)),"CRC-verified P-RNTI scheduling events; UE identifiers are not decoded or stored.");
		add(paging,"Observation scope","Current program session","Statistics are not written to disk.");
	}

	private static JPanel panel(DefaultTableModel model){
		JTable table=new JTable(model);table.setFillsViewportHeight(true);table.setAutoCreateRowSorter(true);
		table.setBackground(new Color(0x161616));table.setForeground(Color.WHITE);table.setGridColor(new Color(0x3a3a3a));
		table.getColumnModel().getColumn(0).setPreferredWidth(190);
		table.getColumnModel().getColumn(1).setPreferredWidth(210);
		table.getColumnModel().getColumn(2).setPreferredWidth(390);
		JScrollPane scroll=new JScrollPane(table);scroll.getViewport().setBackground(Color.BLACK);
		scroll.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
		JPanel panel=new JPanel(new BorderLayout());panel.setBackground(Color.BLACK);
		panel.setBorder(new EmptyBorder(8,8,8,8));panel.add(scroll);return panel;
	}

	private static DefaultTableModel model(String... columns){return new DefaultTableModel(columns,0){@Override public boolean isCellEditable(int r,int c){return false;}};}
	private static void add(DefaultTableModel model,String parameter,String value,String meaning){model.addRow(new Object[]{parameter,value,meaning});}
	private static String pagingNb(int i){String[] v={"4T","2T","T","T/2","T/4","T/8","T/16","T/32"};return i>=0&&i<v.length?v[i]:WAIT;}
	private static int qHyst(int i){int[] v={0,1,2,3,4,5,6,8,10,12,14,16,18,20,22,24};return i>=0&&i<v.length?v[i]:-1;}
	private static String bandwidthIndex(int i){String[] v={"1.4 MHz","3 MHz","5 MHz","10 MHz","15 MHz","20 MHz"};return i>=0&&i<v.length?v[i]:WAIT;}
	private static String bandwidth(int rb){switch(rb){case 6:return"1.4 MHz";case 15:return"3 MHz";case 25:return"5 MHz";case 50:return"10 MHz";case 75:return"15 MHz";case 100:return"20 MHz";default:return rb+" RB";}}
}
