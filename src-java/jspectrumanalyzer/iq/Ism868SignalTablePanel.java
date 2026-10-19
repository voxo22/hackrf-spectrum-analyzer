package jspectrumanalyzer.iq;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.RowSorterEvent;
import javax.swing.event.RowSorterListener;
import javax.swing.event.TableColumnModelEvent;
import javax.swing.event.TableColumnModelListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;

/** Session-scoped list of RF activity and CRC-verified packets in ISM 868. */
final class Ism868SignalTablePanel extends JPanel {
	private static final String TRACKING_HELP="Tracking: right-click a row to enable its chime; right-click it again to disable tracking.";
	private static final long UPDATE_FLASH_MILLIS=300L;
	private static final Color UPDATE_FLASH_COLOR=new Color(0x777777);
	private static final Color TRACKED_BACKGROUND=new Color(0xeeeeee);
	private static final Color HEADER_BG=new Color(0x202020),HEADER_FG=Color.WHITE,HEADER_BORDER=new Color(0x555555);
	private static final Color[] SORT_HEADER_BACKGROUNDS={new Color(0x17627d),new Color(0x4b557c),new Color(0x685076)};
	private static final File DEFAULT_SETTINGS_FILE=new File("hackrf-iq-analyzer.ini");
	private static final String LAYOUT_PREFIX="ism868.table.";
	private static final int AC_PERIOD_COLUMN=33;
	private static final int PREVIOUS_COLUMN_COUNT=36;
	private static final int LEGACY_COLUMN_COUNT=35;
	private static final String[] COLUMNS = {
			"Frequency", "Meter ID", "Manufacturer", "Protocol / mode", "Device / medium",
			"Version", "C", "CI", "AC", "Frame", "Security", "Driver",
			"Current reading", "Current date", "Previous reading", "Previous date",
			"Room temp", "Radiator temp", "Temperature", "Humidity", "Wind avg",
			"Wind gust", "Wind dir", "Rain", "UV / UVI", "Light", "Battery",
			"Decoded values", "RSSI", "SNR", "Frames", "Missed", "Tx period", "AC period",
			"CRC", "Last seen", "Modulation", "Symbol rate", "Occupied BW", "Burst",
			"Tone spacing", "PHY confidence"
	};
	private static final int[] COLUMN_WIDTHS={105,105,92,125,145,68,62,62,58,75,150,125,
			105,92,105,92,82,92,92,78,92,92,78,88,92,92,72,240,86,74,72,62,72,72,55,82,
			125,100,100,90,105,105};
	private final List<Signal> signals = new ArrayList<Signal>();
	private final List<Long> displayedRowKeys=new ArrayList<Long>();
	private final DefaultTableModel model = new DefaultTableModel(COLUMNS, 0) {
		@Override public boolean isCellEditable(int row, int column) { return false; }
		@Override public Class<?> getColumnClass(int column) {
			switch (column) {
			case 0: return Long.class;
			case 28: case 29: case 32: case 33: case 37: case 38: case 39: case 40: case 41:
				return Double.class;
			case 30: case 31: case 35: return Long.class;
			default: return String.class;
			}
		}
	};
	private final JTable table = new JTable(model);
	private final JLabel status = new JLabel("Waiting for 863-870 MHz IQ samples...");
	private final JLabel sortHint=new JLabel("Sort: click a column to add/prioritize; right-click a sorted column to remove it; right-click an unsorted column to clear sorting.");
	private final JLabel trackingHelp=new JLabel(TRACKING_HELP);
	private volatile Set<Long> selectedSignalKeys=Collections.emptySet();
	private final ConcurrentHashMap<Long,Long> flashUntil=new ConcurrentHashMap<Long,Long>();
	private final File tableSettingsFile;
	private final Timer layoutSaveTimer;
	private boolean rebuildingTable;
	private boolean headerPopupHandled,rowPopupHandled;
	private boolean layoutPersistenceInstalled,restoringTableLayout;
	private volatile boolean audibleNotifications=true;
	private long notificationCount,updateFlashCount,nextSignalKey=1L;

	Ism868SignalTablePanel(){this(DEFAULT_SETTINGS_FILE);}

	Ism868SignalTablePanel(File tableSettingsFile) {
		super(new BorderLayout()); setBackground(Color.BLACK);
		this.tableSettingsFile=tableSettingsFile;
		layoutSaveTimer=new Timer(450,event->saveTableLayout());layoutSaveTimer.setRepeats(false);
		table.setAutoCreateRowSorter(true); table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
		table.setRowHeight(23); table.setFillsViewportHeight(true); table.setBackground(Color.BLACK);
		table.setForeground(Color.WHITE); table.setGridColor(new Color(0x333333));
		table.setSelectionBackground(new Color(0x244b66)); table.setSelectionForeground(Color.WHITE);
		table.getTableHeader().setDefaultRenderer(new SortHeaderRenderer());
		table.getRowSorter().addRowSorterListener(event->table.getTableHeader().repaint());
		table.getTableHeader().addMouseListener(new MouseAdapter(){@Override public void mousePressed(MouseEvent event){headerPopupHandled=handleHeaderPopup(event);}
			@Override public void mouseReleased(MouseEvent event){if(!headerPopupHandled)handleHeaderPopup(event);headerPopupHandled=false;}});
		for (int n=0;n<COLUMN_WIDTHS.length;n++)
			table.getColumnModel().getColumn(n).setPreferredWidth(COLUMN_WIDTHS[n]);
		ValueRenderer renderer = new ValueRenderer();
		for (int n=0;n<COLUMNS.length;n++) table.getColumnModel().getColumn(n).setCellRenderer(renderer);
		table.addMouseListener(new MouseAdapter(){@Override public void mousePressed(MouseEvent event){rowPopupHandled=handleRowPopup(event);}
			@Override public void mouseReleased(MouseEvent event){if(!rowPopupHandled)handleRowPopup(event);rowPopupHandled=false;}});
		status.setBorder(BorderFactory.createEmptyBorder(4,7,1,7)); status.setForeground(new Color(0xdddddd));
		status.setBackground(Color.BLACK); status.setOpaque(true);
		sortHint.setBorder(BorderFactory.createEmptyBorder(3,7,0,7));sortHint.setForeground(new Color(0x999999));sortHint.setBackground(Color.BLACK);sortHint.setOpaque(true);
		trackingHelp.setBorder(BorderFactory.createEmptyBorder(1,7,4,7));
		trackingHelp.setForeground(new Color(0x9fd7ff));trackingHelp.setBackground(Color.BLACK);
		trackingHelp.setOpaque(true);
		JScrollPane scroll = new JScrollPane(table); scroll.getViewport().setBackground(Color.BLACK);
		scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS);
		JPanel footer=new JPanel(new GridLayout(3,1));footer.setBackground(Color.BLACK);footer.add(sortHint);footer.add(trackingHelp);footer.add(status);
		scroll.setPreferredSize(new Dimension(1050, 330)); add(scroll, BorderLayout.CENTER); add(footer, BorderLayout.SOUTH);
		setDefaultSort();
	}

	@Override public void addNotify(){
		super.addNotify();
		if(!layoutPersistenceInstalled){restoreTableLayout();installLayoutPersistence();
			layoutPersistenceInstalled=true;}
	}

	@Override public void removeNotify(){
		if(layoutPersistenceInstalled){layoutSaveTimer.stop();saveTableLayout();}
		super.removeNotify();
	}

	private void setDefaultSort(){table.getRowSorter().setSortKeys(Collections.singletonList(
			new RowSorter.SortKey(0,SortOrder.ASCENDING)));}
	private boolean handleHeaderPopup(MouseEvent event){if(!event.isPopupTrigger())return false;int viewColumn=table.getTableHeader().columnAtPoint(event.getPoint());if(viewColumn<0)return false;
		event.consume();int modelColumn=table.convertColumnIndexToModel(viewColumn);List<RowSorter.SortKey> retained=new ArrayList<RowSorter.SortKey>();boolean removed=false;
		for(RowSorter.SortKey key:table.getRowSorter().getSortKeys())if(key.getColumn()==modelColumn&&key.getSortOrder()!=SortOrder.UNSORTED)removed=true;else retained.add(key);
		table.getRowSorter().setSortKeys(removed?retained:Collections.<RowSorter.SortKey>emptyList());return true;}
	private boolean handleRowPopup(MouseEvent event){if(!event.isPopupTrigger())return false;int viewRow=table.rowAtPoint(event.getPoint());if(viewRow<0)return false;int modelRow=table.convertRowIndexToModel(viewRow);
		if(modelRow<0||modelRow>=displayedRowKeys.size())return false;event.consume();Long key=displayedRowKeys.get(modelRow);selectedSignalKeys=selectedSignalKeys.contains(key)?Collections.<Long>emptySet():Collections.singleton(key);table.repaint();return true;}

	private void installLayoutPersistence(){
		table.getColumnModel().addColumnModelListener(new TableColumnModelListener(){
			@Override public void columnAdded(TableColumnModelEvent event){scheduleLayoutSave();}
			@Override public void columnRemoved(TableColumnModelEvent event){scheduleLayoutSave();}
			@Override public void columnMoved(TableColumnModelEvent event){
				if(event.getFromIndex()!=event.getToIndex())scheduleLayoutSave();}
			@Override public void columnMarginChanged(ChangeEvent event){scheduleLayoutSave();}
			@Override public void columnSelectionChanged(ListSelectionEvent event){ }
		});
		table.getRowSorter().addRowSorterListener(new RowSorterListener(){
			@Override public void sorterChanged(RowSorterEvent event){
				if(event.getType()==RowSorterEvent.Type.SORT_ORDER_CHANGED)scheduleLayoutSave();
			}
		});
	}

	private void scheduleLayoutSave(){if(!restoringTableLayout&&tableSettingsFile!=null)layoutSaveTimer.restart();}

	private void restoreTableLayout(){
		if(tableSettingsFile==null||!tableSettingsFile.isFile())return;
		Properties properties=loadTableProperties();restoringTableLayout=true;
		try{
			String savedOrder=properties.getProperty(LAYOUT_PREFIX+"columnOrder");
			int[] order=parseIntList(savedOrder,COLUMNS.length);boolean legacyLayout=false;
			if(order==null){int[] previous=parseIntList(savedOrder,PREVIOUS_COLUMN_COUNT);
				if(validColumnOrder(previous,PREVIOUS_COLUMN_COUNT))order=appendNewColumns(previous);}
			if(order==null){int[] legacy=parseIntList(savedOrder,LEGACY_COLUMN_COUNT);
				if(validColumnOrder(legacy,LEGACY_COLUMN_COUNT)){order=appendNewColumns(upgradeLegacyOrder(legacy));
					legacyLayout=true;}}
			if(validColumnOrder(order))for(int target=0;target<order.length;target++){
				int current=table.convertColumnIndexToView(order[target]);
				if(current>=0&&current!=target)table.moveColumn(current,target);
			}
			String savedWidths=properties.getProperty(LAYOUT_PREFIX+"columnWidths");
			int[] widths=parseIntList(savedWidths,COLUMNS.length);
			if(widths==null){int[] previous=parseIntList(savedWidths,PREVIOUS_COLUMN_COUNT);
				if(previous!=null)widths=appendNewWidths(previous);}
			if(widths==null){int[] legacy=parseIntList(savedWidths,LEGACY_COLUMN_COUNT);
				if(legacy!=null){widths=appendNewWidths(upgradeLegacyWidths(legacy));legacyLayout=true;}}
			if(widths!=null)for(int modelColumn=0;modelColumn<widths.length;modelColumn++){
				int viewColumn=table.convertColumnIndexToView(modelColumn);if(viewColumn<0)continue;
				TableColumn column=table.getColumnModel().getColumn(viewColumn);
				int width=Math.max(35,Math.min(2000,widths[modelColumn]));
				column.setPreferredWidth(width);column.setWidth(width);
			}
			List<RowSorter.SortKey> sortKeys=parseSortKeys(properties.getProperty(LAYOUT_PREFIX+"sortKeys"),
					legacyLayout);
			if(sortKeys!=null)table.getRowSorter().setSortKeys(sortKeys);else setDefaultSort();
		}catch(RuntimeException ignored){setDefaultSort();}
		finally{restoringTableLayout=false;}
	}

	private Properties loadTableProperties(){Properties properties=new Properties();
		if(tableSettingsFile==null||!tableSettingsFile.isFile())return properties;
		try(FileInputStream input=new FileInputStream(tableSettingsFile)){properties.load(input);}
		catch(IOException ignored){ }
		return properties;
	}

	private void saveTableLayout(){
		if(tableSettingsFile==null)return;Properties properties=loadTableProperties();
		StringBuilder order=new StringBuilder(),widths=new StringBuilder();
		for(int viewColumn=0;viewColumn<table.getColumnCount();viewColumn++){
			if(viewColumn>0)order.append(',');order.append(table.getColumnModel().getColumn(viewColumn).getModelIndex());
		}
		for(int modelColumn=0;modelColumn<COLUMNS.length;modelColumn++){
			if(modelColumn>0)widths.append(',');int viewColumn=table.convertColumnIndexToView(modelColumn);
			widths.append(viewColumn<0?75:table.getColumnModel().getColumn(viewColumn).getWidth());
		}
		StringBuilder sorting=new StringBuilder();
		for(RowSorter.SortKey key:table.getRowSorter().getSortKeys()){
			if(sorting.length()>0)sorting.append(';');sorting.append(key.getColumn()).append(':')
					.append(key.getSortOrder()==SortOrder.DESCENDING?'D':'A');
		}
		properties.setProperty(LAYOUT_PREFIX+"columnOrder",order.toString());
		properties.setProperty(LAYOUT_PREFIX+"columnWidths",widths.toString());
		properties.setProperty(LAYOUT_PREFIX+"sortKeys",sorting.toString());
		try(FileOutputStream output=new FileOutputStream(tableSettingsFile)){
			properties.store(output,"HackRF IQ Analyzer settings");}
		catch(IOException ignored){ }
	}

	private static int[] parseIntList(String value,int count){
		if(value==null)return null;String[] parts=value.split(",",-1);if(parts.length!=count)return null;
		int[] parsed=new int[count];try{for(int n=0;n<count;n++)parsed[n]=Integer.parseInt(parts[n]);}
		catch(NumberFormatException invalid){return null;}return parsed;
	}

	private static boolean validColumnOrder(int[] order){return validColumnOrder(order,COLUMNS.length);}
	private static boolean validColumnOrder(int[] order,int count){
		if(order==null||order.length!=count)return false;boolean[] seen=new boolean[count];
		for(int modelColumn:order){if(modelColumn<0||modelColumn>=seen.length||seen[modelColumn])return false;
			seen[modelColumn]=true;}return true;
	}

	private static int[] upgradeLegacyOrder(int[] legacy){int[] upgraded=new int[PREVIOUS_COLUMN_COUNT];int at=0;
		for(int modelColumn:legacy){upgraded[at++]=modelColumn>=AC_PERIOD_COLUMN?modelColumn+1:modelColumn;
			if(modelColumn==AC_PERIOD_COLUMN-1)upgraded[at++]=AC_PERIOD_COLUMN;}
		return upgraded;
	}

	private static int[] upgradeLegacyWidths(int[] legacy){int[] upgraded=new int[PREVIOUS_COLUMN_COUNT];
		System.arraycopy(legacy,0,upgraded,0,AC_PERIOD_COLUMN);upgraded[AC_PERIOD_COLUMN]=72;
		System.arraycopy(legacy,AC_PERIOD_COLUMN,upgraded,AC_PERIOD_COLUMN+1,
				legacy.length-AC_PERIOD_COLUMN);return upgraded;
	}
	private static int[] appendNewColumns(int[] previous){int[] upgraded=new int[COLUMNS.length];
		System.arraycopy(previous,0,upgraded,0,previous.length);
		for(int n=previous.length;n<upgraded.length;n++)upgraded[n]=n;return upgraded;}
	private static int[] appendNewWidths(int[] previous){int[] upgraded=COLUMN_WIDTHS.clone();
		System.arraycopy(previous,0,upgraded,0,Math.min(previous.length,upgraded.length));return upgraded;}

	private static List<RowSorter.SortKey> parseSortKeys(String value,boolean legacyLayout){
		if(value==null)return null;if(value.length()==0)return Collections.emptyList();
		List<RowSorter.SortKey> keys=new ArrayList<RowSorter.SortKey>();String[] parts=value.split(";");
		try{for(String part:parts){String[] fields=part.split(":",-1);if(fields.length!=2)return null;
			int column=Integer.parseInt(fields[0]);
			if(legacyLayout&&column>=AC_PERIOD_COLUMN)column++;
			if(column<0||column>=COLUMNS.length)return null;
			SortOrder order="D".equals(fields[1])?SortOrder.DESCENDING:
					("A".equals(fields[1])?SortOrder.ASCENDING:null);if(order==null)return null;
			keys.add(new RowSorter.SortKey(column,order));}}
		catch(NumberFormatException invalid){return null;}return keys;
	}

	synchronized void observe(Ism868SignalAnalyzer.Result result) {
		observeAt(result,System.nanoTime());
	}

	private synchronized void observeAt(Ism868SignalAnalyzer.Result result,long arrivalNanos) {
		if (result == null) return;
		long arrivalMillis=System.currentTimeMillis();
		for (Ism868SignalAnalyzer.Observation observation : result.observations) {
			if(isRedundantLoRaCandidate(observation))continue;
			removeConfirmedWmbusCandidateAliases(observation);
			removeJablotronCandidateAliases(observation);
			remove8685CandidateAliases(observation);
			Signal signal = matching(observation);
			if (signal == null) { signal = new Signal(nextSignalKey++,observation,arrivalNanos,
					arrivalMillis); signals.add(signal); }
			else if(signal.update(observation,arrivalNanos,arrivalMillis))
				flashUpdatedSignal(signal.rowKey,selectedSignalKeys.contains(Long.valueOf(signal.rowKey)));
		}
	}
	private void removeConfirmedWmbusCandidateAliases(Ism868SignalAnalyzer.Observation observation){
		if(!observation.checksumValid||!observation.protocol.startsWith("W-MBus"))return;
		for(int n=signals.size()-1;n>=0;n--){Signal signal=signals.get(n);
			if(signal.meterId.length()==0&&signal.protocol.startsWith("W-MBus")
					&&Math.abs(signal.frequencyHz-observation.frequencyHz)<250_000L){
				signals.remove(n);Long key=Long.valueOf(signal.rowKey);flashUntil.remove(key);
				if(selectedSignalKeys.contains(key))selectedSignalKeys=Collections.emptySet();}
		}
	}
	private boolean isRedundantLoRaCandidate(Ism868SignalAnalyzer.Observation observation){
		if(!"LoRa candidate".equals(observation.protocol)||observation.meterId.length()>0)return false;
		for(Signal signal:signals)if(signal.meterId.length()>0&&signal.protocol.startsWith("LoRa")
				&&Math.abs(signal.frequencyHz-observation.frequencyHz)<=150_000L)return true;
		for(Signal signal:signals)if(signal.protocol.startsWith("Jablotron")
				&&Math.abs(signal.frequencyHz-observation.frequencyHz)<=80_000L)return true;
		for(Signal signal:signals)if((signal.protocol.startsWith("Wavenis")
				||signal.protocol.startsWith("868.5 proprietary"))
				&&Math.abs(signal.frequencyHz-observation.frequencyHz)<=80_000L)return true;
		return false;
	}
	private void remove8685CandidateAliases(Ism868SignalAnalyzer.Observation observation){
		if(!observation.protocol.startsWith("Wavenis")
				&&!observation.protocol.startsWith("868.5 proprietary"))return;
		for(int n=signals.size()-1;n>=0;n--){Signal signal=signals.get(n);
			if(("LoRa candidate".equals(signal.protocol)||"SRD / rtl_433 candidate".equals(signal.protocol))
					&&signal.application.driver.length()==0
					&&Math.abs(signal.frequencyHz-observation.frequencyHz)<=80_000L){
				signals.remove(n);Long key=Long.valueOf(signal.rowKey);flashUntil.remove(key);
				if(selectedSignalKeys.contains(key))selectedSignalKeys=Collections.emptySet();}
		}
	}
	private void removeJablotronCandidateAliases(Ism868SignalAnalyzer.Observation observation){
		if(!observation.protocol.startsWith("Jablotron"))return;
		for(int n=signals.size()-1;n>=0;n--){Signal signal=signals.get(n);
			if("LoRa candidate".equals(signal.protocol)&&signal.application.driver.length()==0
					&&Math.abs(signal.frequencyHz-observation.frequencyHz)<=80_000L){
				signals.remove(n);Long key=Long.valueOf(signal.rowKey);flashUntil.remove(key);
				if(selectedSignalKeys.contains(key))selectedSignalKeys=Collections.emptySet();}
		}
	}
	void observeAtForTest(Ism868SignalAnalyzer.Result result,long arrivalNanos){observeAt(result,arrivalNanos);}

	synchronized int signalCountForTest() { return signals.size(); }
	synchronized long receivedForProtocolForTest(String protocol){
		for(Signal signal:signals)if(signal.protocol.equals(protocol))return signal.received;
		return 0L;
	}
	synchronized long seenForMeterForTest(String manufacturer,String meterId) {
		for(Signal signal:signals)if(signal.manufacturer.equals(manufacturer)
				&&signal.meterId.equals(meterId))return signal.received;
		return 0L;
	}
	synchronized long missedForMeterForTest(String manufacturer,String meterId) {
		for(Signal signal:signals)if(signal.manufacturer.equals(manufacturer)
				&&signal.meterId.equals(meterId))return signal.missed;
		return 0L;
	}
	synchronized double periodForMeterForTest(String manufacturer,String meterId) {
		for(Signal signal:signals)if(signal.manufacturer.equals(manufacturer)
				&&signal.meterId.equals(meterId))return signal.txPeriodSeconds();
		return Double.NaN;
	}
	synchronized double acPeriodForMeterForTest(String manufacturer,String meterId) {
		for(Signal signal:signals)if(signal.manufacturer.equals(manufacturer)
				&&signal.meterId.equals(meterId))return signal.acPeriodSeconds();
		return Double.NaN;
	}
	synchronized double periodForProtocolForTest(String protocol) {
		for(Signal signal:signals)if(signal.protocol.equals(protocol))return signal.txPeriodSeconds();
		return Double.NaN;
	}
	synchronized long lastSeenForProtocolForTest(String protocol) {
		for(Signal signal:signals)if(signal.protocol.equals(protocol))return signal.lastSeenMillis;
		return 0L;
	}
	String cellForMeterForTest(String meterId,String columnName) {
		int column=model.findColumn(columnName);if(column<0)return null;
		for(int row=0;row<model.getRowCount();row++)
			if(meterId.equals(model.getValueAt(row,1)))return String.valueOf(model.getValueAt(row,column));
		return null;
	}
	int rowHeightForMeterForTest(String meterId){
		for(int modelRow=0;modelRow<model.getRowCount();modelRow++)if(meterId.equals(model.getValueAt(modelRow,1))){
			int viewRow=table.convertRowIndexToView(modelRow);return viewRow<0?0:table.getRowHeight(viewRow);}
		return 0;
	}
	void moveColumnForTest(String columnName,int targetView){
		int modelColumn=model.findColumn(columnName),viewColumn=table.convertColumnIndexToView(modelColumn);
		if(viewColumn>=0)table.moveColumn(viewColumn,targetView);
	}
	void setColumnWidthForTest(String columnName,int width){int modelColumn=model.findColumn(columnName);
		int viewColumn=table.convertColumnIndexToView(modelColumn);TableColumn column=table.getColumnModel().getColumn(viewColumn);
		column.setPreferredWidth(width);column.setWidth(width);}
	int viewColumnForTest(String columnName){return table.convertColumnIndexToView(model.findColumn(columnName));}
	int columnWidthForTest(String columnName){int view=table.convertColumnIndexToView(model.findColumn(columnName));
		return table.getColumnModel().getColumn(view).getWidth();}
	void sortByColumnForTest(String columnName,SortOrder order){table.getRowSorter().setSortKeys(
			Collections.singletonList(new RowSorter.SortKey(model.findColumn(columnName),order)));}
	String primarySortColumnForTest(){List<? extends RowSorter.SortKey> keys=table.getRowSorter().getSortKeys();
		return keys.isEmpty()?"":COLUMNS[keys.get(0).getColumn()];}
	SortOrder primarySortOrderForTest(){List<? extends RowSorter.SortKey> keys=table.getRowSorter().getSortKeys();
		return keys.isEmpty()?SortOrder.UNSORTED:keys.get(0).getSortOrder();}
	void saveTableLayoutForTest(){saveTableLayout();}
	void restoreTableLayoutForTest(){restoreTableLayout();}
	String renderedCellForMeterForTest(String meterId,String columnName){
		int modelColumn=model.findColumn(columnName),viewColumn=table.convertColumnIndexToView(modelColumn);
		for(int modelRow=0;modelRow<model.getRowCount();modelRow++)if(meterId.equals(model.getValueAt(modelRow,1))){
			int viewRow=table.convertRowIndexToView(modelRow);
			Component rendered=table.prepareRenderer(table.getCellRenderer(viewRow,viewColumn),viewRow,viewColumn);
			return rendered instanceof JLabel?((JLabel)rendered).getText():null;
		}
		return null;
	}
	synchronized long notificationCountForTest(){return notificationCount;}
	synchronized long updateFlashCountForTest(){return updateFlashCount;}
	void setAudibleNotificationsForTest(boolean enabled){audibleNotifications=enabled;}

	private Signal matching(Ism868SignalAnalyzer.Observation observation) {
		if (observation.meterId.length() > 0) {
			for (Signal signal : signals)
				if (signal.meterId.equals(observation.meterId)
						&& signal.manufacturer.equals(observation.manufacturer)
						&&(signal.protocol.equals(observation.protocol)
								||(signal.protocol.startsWith("W-MBus")
								&&observation.protocol.startsWith("W-MBus")))) return signal;
			if(observation.protocol.startsWith("LoRa"))for(Signal signal:signals)
				if(signal.meterId.length()==0&&signal.protocol.startsWith("LoRa")
						&&Math.abs(signal.frequencyHz-observation.frequencyHz)<=150_000L)return signal;
			return null;
		}
		Signal best = null; long bestDistance = Long.MAX_VALUE;
		for (Signal signal : signals) {
			/* An undecoded spectral candidate must never absorb a decoded meter,
			 * otherwise all devices sharing 868.95 MHz collapse into one row. */
			if (signal.meterId.length() > 0) continue;
			boolean sameFamily=signal.protocol.equals(observation.protocol)
					||(signal.protocol.startsWith("W-MBus")&&observation.protocol.startsWith("W-MBus"));
			if (!sameFamily) continue;
			long distance = Math.abs(signal.frequencyHz - observation.frequencyHz);
			long tolerance=observation.protocol.startsWith("Jablotron")?12_000L:
					Math.max(18_000L,Math.min(100_000L,
					Math.max(signal.bandwidthHz,observation.bandwidthHz)/2L));
			if (distance <= tolerance && distance < bestDistance) { best=signal; bestDistance=distance; }
		}
		return best;
	}

	/** Called by the analyzer's Swing timer, so table model changes remain on the EDT. */
	void refresh(Ism868SignalAnalyzer.Result result) {
		List<Signal> copy = new ArrayList<Signal>();
		synchronized (this) { for (Signal signal : signals) copy.add(new Signal(signal)); }
		Set<Long> selectedBefore=selectedRowKeys();
		rebuildingTable=true;
		model.setRowCount(0);displayedRowKeys.clear();table.setRowHeight(23);
		for (Signal signal : copy) { displayedRowKeys.add(Long.valueOf(signal.rowKey));model.addRow(new Object[] {
				Long.valueOf(signal.frequencyHz), signal.meterId.length()==0?"--":signal.meterId,
				signal.manufacturer.length()==0?"--":signal.manufacturer, signal.protocol,
				signal.deviceTypeName.length()==0?"--":signal.deviceTypeName,
				signal.meterVersion<0?"--":String.format(Locale.US,"0x%02X",signal.meterVersion),
				signal.controlField<0?"--":String.format(Locale.US,"0x%02X",signal.controlField),
				signal.ciField<0?"--":String.format(Locale.US,"0x%02X",signal.ciField),
				signal.lastAccessNumber<0?"--":Integer.toString(signal.lastAccessNumber),
				signal.frameFormat.length()==0?"--":signal.frameFormat,
				signal.security.length()==0?"--":signal.security,
				value(signal.application.driver),value(signal.application.currentReading),
				value(signal.application.currentDate),value(signal.application.previousReading),
				value(signal.application.previousDate),value(signal.application.roomTemperature),
				value(signal.application.radiatorTemperature),protocolTemperature(signal),
				weatherHumidity(signal.weather),weatherWind(signal.weather,false),
				weatherWind(signal.weather,true),weatherDirection(signal.weather),
				weatherRain(signal.weather),weatherUv(signal.weather),weatherLight(signal.weather),
				weatherBattery(signal.weather),decodedValues(signal),
				Double.valueOf(signal.levelDbfs),Double.valueOf(signal.snrDb),
				Long.valueOf(signal.received),Long.valueOf(signal.missed),
				Double.valueOf(signal.txPeriodSeconds()),Double.valueOf(signal.acPeriodSeconds()),
				signal.checksumValid ? "OK" : "--",
				Long.valueOf(signal.lastSeenMillis),signal.modulation,
				Double.valueOf(signal.symbolRate),Double.valueOf(signal.bandwidthHz),
				Double.valueOf(signal.burstMillis),Double.valueOf(signal.toneSeparationHz),
				Double.valueOf(signal.phyConfidence)
		});}
		for(int modelRow=0;modelRow<displayedRowKeys.size();modelRow++)
			if(selectedBefore.contains(displayedRowKeys.get(modelRow))){
			int viewRow=table.convertRowIndexToView(modelRow);
			if(viewRow>=0)table.addRowSelectionInterval(viewRow,viewRow);
		}
		applyRowHeights();rebuildingTable=false;
		if (result == null) { status.setText("Waiting for 863-870 MHz IQ samples..."); return; }
		double coveredStart = Math.max(Ism868SignalAnalyzer.BAND_START_HZ, result.visibleStartHz);
		double coveredEnd = Math.min(Ism868SignalAnalyzer.BAND_END_HZ, result.visibleEndHz);
		String coverage = coveredEnd <= coveredStart ? "outside ISM 868"
				: String.format(Locale.US, "%.3f-%.3f MHz", coveredStart/1e6, coveredEnd/1e6);
		status.setText(copy.size() + " session signal" + (copy.size()==1?"":"s") + " | " + result.state
				+ String.format(Locale.US, " | visible %s | noise %.1f dBFS | candidate labels need PHY/CRC confirmation",
					coverage, result.noiseFloorDbfs));
	}

	private void applyRowHeights(){
		for(int modelRow=0;modelRow<model.getRowCount();modelRow++){
			int lines=1;
			for(int column=0;column<model.getColumnCount();column++){
				Object value=model.getValueAt(modelRow,column);
				if(value instanceof String)lines=Math.max(lines,lineCount((String)value));
			}
			int viewRow=table.convertRowIndexToView(modelRow);
			if(viewRow>=0)table.setRowHeight(viewRow,Math.max(23,7+lines*18));
		}
	}

	private static int lineCount(String value){int lines=1;
		for(int n=0;n<value.length();n++)if(value.charAt(n)=='\n')lines++;return lines;}
	private Set<Long> selectedRowKeys(){Set<Long> selected=new HashSet<Long>();
		for(int viewRow:table.getSelectedRows()){
			int modelRow=table.convertRowIndexToModel(viewRow);
			if(modelRow>=0&&modelRow<displayedRowKeys.size())selected.add(displayedRowKeys.get(modelRow));
		}
		return selected;
	}
	private void flashUpdatedSignal(final long key,final boolean chime){
		final Long boxedKey=Long.valueOf(key);final long until=System.currentTimeMillis()+UPDATE_FLASH_MILLIS;
		flashUntil.put(boxedKey,Long.valueOf(until));updateFlashCount++;if(chime)notificationCount++;
		SwingUtilities.invokeLater(()->{
			if(chime&&audibleNotifications)Toolkit.getDefaultToolkit().beep();
			table.repaint();Timer timer=new Timer((int)UPDATE_FLASH_MILLIS+20,event->{
				flashUntil.remove(boxedKey,Long.valueOf(until));table.repaint();});
			timer.setRepeats(false);timer.start();
		});
	}

	private static String value(String value){return value.length()==0?"--":value;}
	private static String protocolTemperature(Signal signal){if(signal.weather!=null&&!Double.isNaN(signal.weather.temperatureC))
		return String.format(Locale.US,"%.1f C",signal.weather.temperatureC);
		if(signal.tpms!=null)return signal.tpms.temperatureC+" C";
		return "--";}
	private static String weatherHumidity(FineOffsetWh65bDecoder.Decoded value){return value==null
			||value.humidity<0?"--":value.humidity+" %";}
	private static String weatherWind(FineOffsetWh65bDecoder.Decoded value,boolean gust){double speed=value==null
			?Double.NaN:(gust?value.windGustMs:value.windAverageMs);return Double.isNaN(speed)?"--":
			String.format(Locale.US,"%.1f m/s",speed);}
	private static String weatherDirection(FineOffsetWh65bDecoder.Decoded value){return value==null
			||value.windDirection<0?"--":value.windDirection+" deg";}
	private static String weatherRain(FineOffsetWh65bDecoder.Decoded value){return value==null?"--":
			String.format(Locale.US,"%.1f mm",value.rainMm);}
	private static String weatherUv(FineOffsetWh65bDecoder.Decoded value){return value==null
			||value.uvRaw<0?"--":value.uvRaw+" / "+value.uvIndex;}
	private static String weatherLight(FineOffsetWh65bDecoder.Decoded value){return value==null
			||Double.isNaN(value.lightLux)?"--":String.format(Locale.US,"%.1f lux",value.lightLux);}
	private static String weatherBattery(FineOffsetWh65bDecoder.Decoded value){return value==null?"--":
			(value.batteryOk?"OK":"LOW");}
	static String formatPeriodForTest(double seconds){return formatPeriod(seconds);}
	void selectMeterForTest(String meterId){
		for(int row=0;row<model.getRowCount();row++)if(meterId.equals(model.getValueAt(row,1))){
			int view=table.convertRowIndexToView(row);table.setRowSelectionInterval(view,view);return;}
	}
	void selectProtocolForTest(String protocol){
		for(int row=0;row<model.getRowCount();row++)if(protocol.equals(model.getValueAt(row,3))){
			int view=table.convertRowIndexToView(row);table.setRowSelectionInterval(view,view);return;}
	}
	void rightClickMeterForTest(String meterId){for(int row=0;row<model.getRowCount();row++)if(meterId.equals(model.getValueAt(row,1))){Long key=displayedRowKeys.get(row);selectedSignalKeys=selectedSignalKeys.contains(key)?Collections.<Long>emptySet():Collections.singleton(key);return;}}
	void rightClickProtocolForTest(String protocol){for(int row=0;row<model.getRowCount();row++)if(protocol.equals(model.getValueAt(row,3))){Long key=displayedRowKeys.get(row);selectedSignalKeys=selectedSignalKeys.contains(key)?Collections.<Long>emptySet():Collections.singleton(key);return;}}
	String statusTextForTest(){return status.getText()+" | "+trackingHelp.getText();}
	boolean protocolTrackedForTest(String protocol){
		for(int row=0;row<model.getRowCount();row++)if(protocol.equals(model.getValueAt(row,3))
				&&selectedSignalKeys.contains(displayedRowKeys.get(row)))return true;return false;
	}
	Color renderedBackgroundForProtocolForTest(String protocol){
		for(int modelRow=0;modelRow<model.getRowCount();modelRow++)if(protocol.equals(model.getValueAt(modelRow,3))){
			int viewRow=table.convertRowIndexToView(modelRow),viewColumn=table.convertColumnIndexToView(3);
			return table.prepareRenderer(table.getCellRenderer(viewRow,viewColumn),viewRow,viewColumn).getBackground();}
		return null;
	}
	boolean meterSelectedForTest(String meterId){
		for(int view:table.getSelectedRows())if(meterId.equals(model.getValueAt(
				table.convertRowIndexToModel(view),1)))return true;return false;
	}

	private static String formatPeriod(double seconds){
		if(Double.isNaN(seconds))return "--";
		long totalSeconds=Math.max(0L,Math.round(seconds));
		if(totalSeconds<60L)return totalSeconds+" s";
		return totalSeconds/60L+" min "+totalSeconds%60L+" s";
	}

	private static Color rowTint(String manufacturer,String protocol){
		if(protocol!=null&&protocol.startsWith("LoRa"))return new Color(0x17677a);
		if("TCH".equals(manufacturer)&&"W-MBus T".equals(protocol))return new Color(0x68252f);
		if("TCH".equals(manufacturer)&&"W-MBus C".equals(protocol))return new Color(0x75401d);
		Color maker=null;
		if("APA".equals(manufacturer))maker=new Color(0x352144);
		else if("TCH".equals(manufacturer))maker=new Color(0x402817);
		else if("QDS".equals(manufacturer))maker=new Color(0x173a27);
		else if("KAM".equals(manufacturer))maker=new Color(0x173449);
		else if("IST".equals(manufacturer))maker=new Color(0x3a1d2b);
		else if("LSE".equals(manufacturer))maker=new Color(0x3b3517);

		Color mode=null;
		/* Only confirmed W-MBus modes are tinted.  A C/T spectral candidate
		 * has not yet earned either colour. */
		if("W-MBus C".equals(protocol))mode=new Color(0x16415a);
		else if("W-MBus T".equals(protocol))mode=new Color(0x421e4d);
		else if("W-MBus S".equals(protocol))mode=new Color(0x494119);
		else if(protocol!=null&&protocol.startsWith("Fineoffset"))mode=new Color(0x25442f);
		else if(protocol!=null&&protocol.startsWith("Renault TPMS"))mode=new Color(0x49331d);
		if(maker==null)return mode==null?Color.BLACK:mode;
		if(mode==null)return maker;
		return new Color((maker.getRed()*3+mode.getRed()*2)/5,
				(maker.getGreen()*3+mode.getGreen()*2)/5,
				(maker.getBlue()*3+mode.getBlue()*2)/5);
	}

	static Color rowTintForTest(String manufacturer,String protocol){return rowTint(manufacturer,protocol);}
	static Color highlightTintForTest(String manufacturer,String protocol){return TRACKED_BACKGROUND;}

	private static String decodedValues(Signal signal){
		if(signal.tpms!=null)return String.format(Locale.US,"pressure=%.1f kPa; flags=0x%02X; unknown=0x%04X",
				signal.tpms.pressureKpa,signal.tpms.flags,signal.tpms.unknown);
		if(signal.application.unparsedValues.length()>0)return signal.application.unparsedValues;
		/* Candidate diagnostics still need a home, but CRC-valid W-MBus rows must
		 * not duplicate their structured ID/link fields in a free-form column. */
		return signal.meterId.length()==0&&signal.details.length()>0?signal.details:"--";
	}

	private static final class Signal {
		final long rowKey;
		long frequencyHz, bandwidthHz, received, missed, lastSeenMillis;
		long txStartMillis,lastTxEventMillis,txReceived;
		long acStartMillis,lastAcEventMillis,accessSteps;
		long periodClockMillis,lastPeriodSourceMillis,lastObservationNanos;
		double levelDbfs, snrDb, confidence;
		double symbolRate,burstMillis,toneSeparationHz,phyConfidence;
		String protocol, modulation, details, payload, manufacturer, meterId;
		String deviceTypeName, frameFormat, security;
		WmbusApplicationDecoder.Result application;
		FineOffsetWh65bDecoder.Decoded weather;
		RenaultTpmsDecoder.Decoded tpms;
		int meterVersion,controlField,ciField,lastAccessNumber;
		boolean checksumValid;
		Signal(long rowKey,Ism868SignalAnalyzer.Observation o,long arrivalNanos,long arrivalMillis) {
			this.rowKey=rowKey;
			frequencyHz=o.frequencyHz; bandwidthHz=o.bandwidthHz; levelDbfs=o.levelDbfs; snrDb=o.snrDb;
			symbolRate=o.symbolRate;burstMillis=o.burstMillis;toneSeparationHz=o.toneSeparationHz;
			phyConfidence=o.phyConfidence;
			confidence=o.confidence; protocol=o.protocol; modulation=o.modulation; details=o.details;
			payload=o.payloadHex; manufacturer=o.manufacturer; meterId=o.meterId;
			deviceTypeName=o.deviceTypeName;meterVersion=o.meterVersion;controlField=o.controlField;
			ciField=o.ciField;lastAccessNumber=o.accessNumber;frameFormat=o.frameFormat;
			security=o.security;application=o.application;weather=o.weather;tpms=o.tpms;checksumValid=o.checksumValid;
			received=1;txReceived=1;txStartMillis=o.captureMillis;lastTxEventMillis=o.captureMillis;
			acStartMillis=o.captureMillis;lastAcEventMillis=o.captureMillis;
			periodClockMillis=o.captureMillis;lastPeriodSourceMillis=o.captureMillis;
			lastObservationNanos=arrivalNanos;lastSeenMillis=arrivalMillis;
		}
		Signal(Signal o) {
			rowKey=o.rowKey;
			frequencyHz=o.frequencyHz;bandwidthHz=o.bandwidthHz;received=o.received;missed=o.missed;
			lastSeenMillis=o.lastSeenMillis;txStartMillis=o.txStartMillis;
			lastTxEventMillis=o.lastTxEventMillis;txReceived=o.txReceived;
			acStartMillis=o.acStartMillis;lastAcEventMillis=o.lastAcEventMillis;
			periodClockMillis=o.periodClockMillis;
			lastPeriodSourceMillis=o.lastPeriodSourceMillis;lastObservationNanos=o.lastObservationNanos;
			accessSteps=o.accessSteps;
			levelDbfs=o.levelDbfs;snrDb=o.snrDb;confidence=o.confidence;protocol=o.protocol;
			symbolRate=o.symbolRate;burstMillis=o.burstMillis;toneSeparationHz=o.toneSeparationHz;
			phyConfidence=o.phyConfidence;
			modulation=o.modulation;details=o.details;payload=o.payload;manufacturer=o.manufacturer;
			meterId=o.meterId;deviceTypeName=o.deviceTypeName;meterVersion=o.meterVersion;
			controlField=o.controlField;ciField=o.ciField;lastAccessNumber=o.lastAccessNumber;
			frameFormat=o.frameFormat;
			security=o.security;application=o.application;weather=o.weather;tpms=o.tpms;checksumValid=o.checksumValid;
		}
		boolean update(Ism868SignalAnalyzer.Observation o,long arrivalNanos,long arrivalMillis) {
			long eventMillis=advancePeriodClock(o.captureMillis,arrivalNanos);
			boolean unique=true,resetAccessPeriod=false;
			if(meterId.length()>0){
				if(o.accessNumber>=0&&lastAccessNumber>=0){
					int delta=(o.accessNumber-lastAccessNumber)&255;
					if(delta==0){
						/* Overlapping analyzer windows can report the same physical packet.
						 * A later packet may legitimately repeat AC and still belongs to Tx period. */
						if(payload.length()>0&&payload.equals(o.payloadHex)
								&&eventMillis-lastTxEventMillis<750L)unique=false;
					}else if(delta<=127){accessSteps+=delta;missed+=Math.max(0,delta-1);
						lastAcEventMillis=eventMillis;
					}else resetAccessPeriod=true;
				}else if(o.accessNumber>=0){
					acStartMillis=eventMillis;lastAcEventMillis=eventMillis;accessSteps=0;
				}else if(o.accessNumber<0&&payload.length()>0&&payload.equals(o.payloadHex)
						&&eventMillis-lastTxEventMillis<750L)unique=false;
			}
			frequencyHz=Math.round(frequencyHz*.65d+o.frequencyHz*.35d);
			bandwidthHz=Math.round(bandwidthHz*.65d+o.bandwidthHz*.35d);
			if(!Double.isNaN(o.levelDbfs))levelDbfs=Double.isNaN(levelDbfs)?o.levelDbfs
					:levelDbfs*.7d+o.levelDbfs*.3d;
			if(!Double.isNaN(o.snrDb))snrDb=Double.isNaN(snrDb)?o.snrDb:snrDb*.7d+o.snrDb*.3d;
			if(!Double.isNaN(o.symbolRate))symbolRate=smoothed(symbolRate,o.symbolRate);
			if(!Double.isNaN(o.burstMillis))burstMillis=smoothed(burstMillis,o.burstMillis);
			if(!Double.isNaN(o.toneSeparationHz))toneSeparationHz=smoothed(toneSeparationHz,o.toneSeparationHz);
			if(!Double.isNaN(o.phyConfidence))phyConfidence=smoothed(phyConfidence,o.phyConfidence);
			confidence=Math.max(confidence*.95d,o.confidence);protocol=o.protocol;modulation=o.modulation;details=o.details;
			if(o.payloadHex.length()>0)payload=o.payloadHex;
			if(o.meterId.length()>0){manufacturer=o.manufacturer;meterId=o.meterId;
				deviceTypeName=o.deviceTypeName;meterVersion=o.meterVersion;controlField=o.controlField;
				ciField=o.ciField;if(o.accessNumber>=0)lastAccessNumber=o.accessNumber;
				frameFormat=o.frameFormat;security=o.security;
				if(o.application.driver.length()>0)application=o.application;
				if(o.weather!=null)weather=o.weather;if(o.tpms!=null)tpms=o.tpms;}
			if(o.checksumValid)checksumValid=true;
			if(!unique)return false;
			if(resetAccessPeriod){acStartMillis=eventMillis;lastAcEventMillis=eventMillis;accessSteps=0;}
			txReceived++;received++;lastTxEventMillis=eventMillis;
			lastSeenMillis=arrivalMillis;return true;
		}
		private long advancePeriodClock(long sourceMillis,long arrivalNanos){
			long increment;
			if(sourceMillis>lastPeriodSourceMillis)increment=sourceMillis-lastPeriodSourceMillis;
			else increment=Math.max(0L,Math.round((arrivalNanos-lastObservationNanos)/1_000_000d));
			periodClockMillis+=increment;lastPeriodSourceMillis=sourceMillis;
			lastObservationNanos=arrivalNanos;return periodClockMillis;
		}
		double txPeriodSeconds(){
			long elapsed=lastTxEventMillis-txStartMillis;
			if(elapsed<=0)return Double.NaN;
			long intervals=Math.max(0L,txReceived-1L);
			return intervals<=0?Double.NaN:elapsed/(1000d*intervals);
		}
		double acPeriodSeconds(){
			long elapsed=lastAcEventMillis-acStartMillis;
			return elapsed<=0||accessSteps<=0?Double.NaN:elapsed/(1000d*accessSteps);
		}
		private static double smoothed(double old,double next){return Double.isNaN(old)?next:old*.7d+next*.3d;}
	}

	private static final class SortHeaderRenderer extends DefaultTableCellRenderer {
		SortHeaderRenderer(){setHorizontalAlignment(SwingConstants.CENTER);setOpaque(true);setBorder(BorderFactory.createMatteBorder(0,0,1,1,HEADER_BORDER));}
		@Override public Component getTableCellRendererComponent(JTable table,Object value,boolean selected,boolean focus,int row,int column){super.getTableCellRendererComponent(table,value,selected,focus,row,column);
			int modelColumn=table.convertColumnIndexToModel(column),priority=-1;SortOrder order=SortOrder.UNSORTED;int index=0;for(RowSorter.SortKey key:table.getRowSorter().getSortKeys()){
				if(key.getSortOrder()==SortOrder.UNSORTED)continue;if(key.getColumn()==modelColumn){priority=index;order=key.getSortOrder();break;}index++;}
			setBackground(priority<0?HEADER_BG:SORT_HEADER_BACKGROUNDS[priority%SORT_HEADER_BACKGROUNDS.length]);setForeground(HEADER_FG);String text=String.valueOf(value);
			setText(priority<0?text:text+" "+(order==SortOrder.ASCENDING?"\u25B2":"\u25BC")+(table.getRowSorter().getSortKeys().size()>1?Integer.toString(priority+1):""));return this;}
	}
	private final class ValueRenderer extends DefaultTableCellRenderer {
		private int column;
		ValueRenderer() { setHorizontalAlignment(SwingConstants.CENTER); }
		@Override protected void setValue(Object value) {
			if(value instanceof Long) {
				long n=((Long)value).longValue();
				if(column==0)value=String.format(Locale.US,"%.6f MHz",n/1e6);
				else if(column==35){long age=Math.max(0L,System.currentTimeMillis()-n);value=age<1000?age+" ms":String.format(Locale.US,"%.1f s",age/1000d);}
			} else if(value instanceof Double) {
				double n=((Double)value).doubleValue();
				if(Double.isNaN(n))value="--";
				else if(column==28)value=String.format(Locale.US,"%.1f dBFS",n);
				else if(column==29)value=String.format(Locale.US,"%.1f dB",n);
				else if(column==32||column==33)value=formatPeriod(n);
				else if(column==37)value=n>=1000d?String.format(Locale.US,"%.1f ksym/s",n/1000d)
						:String.format(Locale.US,"%.0f sym/s",n);
				else if(column==38||column==40)value=String.format(Locale.US,"%.1f kHz",n/1000d);
				else if(column==39)value=String.format(Locale.US,"%.2f ms",n);
				else if(column==41)value=String.format(Locale.US,"%.0f %%",n);
			}
			if(value instanceof String&&((String)value).indexOf('\n')>=0)
				value="<html><div style='text-align:center'>"+html((String)value)+"</div></html>";
			super.setValue(value);
		}
		@Override public Component getTableCellRendererComponent(JTable table,Object value,boolean selected,
				boolean focus,int row,int column) {
			this.column=table.convertColumnIndexToModel(column);
			Component c=super.getTableCellRendererComponent(table,value,selected,focus,row,column);
			int modelRow=table.convertRowIndexToModel(row);
			Long rowKey=modelRow>=0&&modelRow<displayedRowKeys.size()?displayedRowKeys.get(modelRow):null;
			Long until=rowKey==null?null:flashUntil.get(rowKey);
			boolean tracked=rowKey!=null&&selectedSignalKeys.contains(rowKey);
			if(tracked){c.setBackground(TRACKED_BACKGROUND);c.setForeground(Color.BLACK);
			}else if(selected){c.setBackground(table.getSelectionBackground());c.setForeground(table.getSelectionForeground());
			}else if(until!=null&&System.currentTimeMillis()<until.longValue()){
				c.setBackground(UPDATE_FLASH_COLOR);c.setForeground(Color.WHITE);
			}else{String manufacturer=String.valueOf(model.getValueAt(modelRow,2));
				String protocol=String.valueOf(model.getValueAt(modelRow,3));
				c.setBackground(rowTint(manufacturer,protocol));c.setForeground(Color.WHITE);}return c;
		}
		private String html(String value){return value.replace("&","&amp;")
				.replace("<","&lt;").replace(">","&gt;").replace("\n","<br>");}
	}
}
