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
	private static final String TRACKING_HELP="Highlight a row to track it; its updates play a chime.";
	private static final long UPDATE_FLASH_MILLIS=300L;
	private static final Color UPDATE_FLASH_COLOR=new Color(0x777777);
	private static final Color TRACKED_BACKGROUND=new Color(0xeeeeee);
	private static final File DEFAULT_SETTINGS_FILE=new File("hackrf-iq-analyzer.ini");
	private static final String LAYOUT_PREFIX="ism868.table.";
	private static final String[] COLUMNS = {
			"Frequency", "Meter ID", "Manufacturer", "Protocol / mode", "Device / medium",
			"Version", "C", "CI", "AC", "Frame", "Security", "Driver",
			"Current reading", "Current date", "Previous reading", "Previous date",
			"Room temp", "Radiator temp", "Temperature", "Humidity", "Wind avg",
			"Wind gust", "Wind dir", "Rain", "UV / UVI", "Light", "Battery",
			"Decoded values", "RSSI", "SNR", "Received", "Missed", "Tx period", "CRC", "Last seen"
	};
	private final List<Signal> signals = new ArrayList<Signal>();
	private final List<Long> displayedRowKeys=new ArrayList<Long>();
	private final DefaultTableModel model = new DefaultTableModel(COLUMNS, 0) {
		@Override public boolean isCellEditable(int row, int column) { return false; }
		@Override public Class<?> getColumnClass(int column) {
			switch (column) {
			case 0: return Long.class;
			case 28: case 29: case 32: return Double.class;
			case 30: case 31: case 34: return Long.class;
			default: return String.class;
			}
		}
	};
	private final JTable table = new JTable(model);
	private final JLabel status = new JLabel("Waiting for 863-870 MHz IQ samples...");
	private final JLabel trackingHelp=new JLabel(TRACKING_HELP);
	private volatile Set<Long> selectedSignalKeys=Collections.emptySet();
	private final ConcurrentHashMap<Long,Long> flashUntil=new ConcurrentHashMap<Long,Long>();
	private final File tableSettingsFile;
	private final Timer layoutSaveTimer;
	private boolean rebuildingTable;
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
		DefaultTableCellRenderer header = new DefaultTableCellRenderer();
		header.setHorizontalAlignment(SwingConstants.CENTER); header.setOpaque(true);
		header.setBackground(new Color(0x202020)); header.setForeground(Color.WHITE);
		header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, new Color(0x555555)));
		table.getTableHeader().setDefaultRenderer(header);
		int[] widths = {105,105,92,125,145,68,62,62,58,75,150,125,105,92,105,92,82,92,
				92,78,92,92,78,88,92,92,72,240,86,74,72,62,72,55,82};
		for (int n=0;n<widths.length;n++) table.getColumnModel().getColumn(n).setPreferredWidth(widths[n]);
		ValueRenderer renderer = new ValueRenderer();
		for (int n=0;n<COLUMNS.length;n++) table.getColumnModel().getColumn(n).setCellRenderer(renderer);
		table.addMouseListener(new MouseAdapter(){
			@Override public void mousePressed(MouseEvent event){
				int viewRow=table.rowAtPoint(event.getPoint());if(viewRow<0)return;
				int modelRow=table.convertRowIndexToModel(viewRow);
				if(modelRow>=0&&modelRow<displayedRowKeys.size()){
					selectedSignalKeys=Collections.singleton(displayedRowKeys.get(modelRow));table.repaint();}
			}
		});
		table.getSelectionModel().addListSelectionListener(event->{
			if(!event.getValueIsAdjusting()&&!rebuildingTable)updateTrackedSignalKeys();
		});
		status.setBorder(BorderFactory.createEmptyBorder(4,7,1,7)); status.setForeground(new Color(0xdddddd));
		status.setBackground(Color.BLACK); status.setOpaque(true);
		trackingHelp.setBorder(BorderFactory.createEmptyBorder(1,7,4,7));
		trackingHelp.setForeground(new Color(0x9fd7ff));trackingHelp.setBackground(Color.BLACK);
		trackingHelp.setOpaque(true);
		JScrollPane scroll = new JScrollPane(table); scroll.getViewport().setBackground(Color.BLACK);
		scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS);
		JPanel footer=new JPanel(new GridLayout(2,1));footer.add(status);footer.add(trackingHelp);
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
			int[] order=parseIntList(properties.getProperty(LAYOUT_PREFIX+"columnOrder"),COLUMNS.length);
			if(validColumnOrder(order))for(int target=0;target<order.length;target++){
				int current=table.convertColumnIndexToView(order[target]);
				if(current>=0&&current!=target)table.moveColumn(current,target);
			}
			int[] widths=parseIntList(properties.getProperty(LAYOUT_PREFIX+"columnWidths"),COLUMNS.length);
			if(widths!=null)for(int modelColumn=0;modelColumn<widths.length;modelColumn++){
				int viewColumn=table.convertColumnIndexToView(modelColumn);if(viewColumn<0)continue;
				TableColumn column=table.getColumnModel().getColumn(viewColumn);
				int width=Math.max(35,Math.min(2000,widths[modelColumn]));
				column.setPreferredWidth(width);column.setWidth(width);
			}
			List<RowSorter.SortKey> sortKeys=parseSortKeys(properties.getProperty(LAYOUT_PREFIX+"sortKeys"));
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

	private static boolean validColumnOrder(int[] order){
		if(order==null||order.length!=COLUMNS.length)return false;boolean[] seen=new boolean[COLUMNS.length];
		for(int modelColumn:order){if(modelColumn<0||modelColumn>=seen.length||seen[modelColumn])return false;
			seen[modelColumn]=true;}return true;
	}

	private static List<RowSorter.SortKey> parseSortKeys(String value){
		if(value==null)return null;if(value.length()==0)return Collections.emptyList();
		List<RowSorter.SortKey> keys=new ArrayList<RowSorter.SortKey>();String[] parts=value.split(";");
		try{for(String part:parts){String[] fields=part.split(":",-1);if(fields.length!=2)return null;
			int column=Integer.parseInt(fields[0]);if(column<0||column>=COLUMNS.length)return null;
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
			Signal signal = matching(observation);
			if (signal == null) { signal = new Signal(nextSignalKey++,observation,arrivalNanos,
					arrivalMillis); signals.add(signal); }
			else if(signal.update(observation,arrivalNanos,arrivalMillis))
				flashUpdatedSignal(signal.rowKey,selectedSignalKeys.contains(Long.valueOf(signal.rowKey)));
		}
	}
	private boolean isRedundantLoRaCandidate(Ism868SignalAnalyzer.Observation observation){
		if(!"LoRa candidate".equals(observation.protocol)||observation.meterId.length()>0)return false;
		for(Signal signal:signals)if(signal.meterId.length()>0&&signal.protocol.startsWith("LoRa")
				&&Math.abs(signal.frequencyHz-observation.frequencyHz)<=150_000L)return true;
		return false;
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
				&&signal.meterId.equals(meterId))return signal.periodSeconds();
		return Double.NaN;
	}
	synchronized double periodForProtocolForTest(String protocol) {
		for(Signal signal:signals)if(signal.protocol.equals(protocol))return signal.periodSeconds();
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
			long tolerance = Math.max(18_000L, Math.min(100_000L,
					Math.max(signal.bandwidthHz, observation.bandwidthHz) / 2L));
			if (distance <= tolerance && distance < bestDistance) { best=signal; bestDistance=distance; }
		}
		return best;
	}

	/** Called by the analyzer's Swing timer, so table model changes remain on the EDT. */
	void refresh(Ism868SignalAnalyzer.Result result) {
		List<Signal> copy = new ArrayList<Signal>();
		synchronized (this) { for (Signal signal : signals) copy.add(new Signal(signal)); }
		Set<Long> selectedBefore=selectedSignalKeys;
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
				Double.valueOf(signal.periodSeconds()),signal.checksumValid ? "OK" : "--",
				Long.valueOf(signal.lastSeenMillis)
		});}
		for(int modelRow=0;modelRow<displayedRowKeys.size();modelRow++)
			if(selectedBefore.contains(displayedRowKeys.get(modelRow))){
			int viewRow=table.convertRowIndexToView(modelRow);
			if(viewRow>=0)table.addRowSelectionInterval(viewRow,viewRow);
		}
		applyRowHeights();rebuildingTable=false;updateTrackedSignalKeys();
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
	private void updateTrackedSignalKeys(){Set<Long> selected=new HashSet<Long>();
		for(int viewRow:table.getSelectedRows()){
			int modelRow=table.convertRowIndexToModel(viewRow);
			if(modelRow>=0&&modelRow<displayedRowKeys.size())selected.add(displayedRowKeys.get(modelRow));
		}
		/* Rebuilding and sorter notifications may briefly clear JTable selection.
		 * Keep the last explicit highlight so monitoring does not disappear. */
		if(selected.isEmpty()&&!selectedSignalKeys.isEmpty())return;
		selectedSignalKeys=Collections.unmodifiableSet(selected);
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
		long rateStartMillis,lastRateEventMillis,periodClockMillis,lastPeriodSourceMillis;
		long lastObservationNanos,accessSteps,rateReceived;
		double levelDbfs, snrDb, confidence;
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
			confidence=o.confidence; protocol=o.protocol; modulation=o.modulation; details=o.details;
			payload=o.payloadHex; manufacturer=o.manufacturer; meterId=o.meterId;
			deviceTypeName=o.deviceTypeName;meterVersion=o.meterVersion;controlField=o.controlField;
			ciField=o.ciField;lastAccessNumber=o.accessNumber;frameFormat=o.frameFormat;
			security=o.security;application=o.application;weather=o.weather;tpms=o.tpms;checksumValid=o.checksumValid;
			received=1;rateReceived=1;rateStartMillis=o.captureMillis;lastRateEventMillis=o.captureMillis;
			periodClockMillis=o.captureMillis;lastPeriodSourceMillis=o.captureMillis;
			lastObservationNanos=arrivalNanos;lastSeenMillis=arrivalMillis;
		}
		Signal(Signal o) {
			rowKey=o.rowKey;
			frequencyHz=o.frequencyHz;bandwidthHz=o.bandwidthHz;received=o.received;missed=o.missed;
			lastSeenMillis=o.lastSeenMillis;rateStartMillis=o.rateStartMillis;
			lastRateEventMillis=o.lastRateEventMillis;periodClockMillis=o.periodClockMillis;
			lastPeriodSourceMillis=o.lastPeriodSourceMillis;lastObservationNanos=o.lastObservationNanos;
			accessSteps=o.accessSteps;rateReceived=o.rateReceived;
			levelDbfs=o.levelDbfs;snrDb=o.snrDb;confidence=o.confidence;protocol=o.protocol;
			modulation=o.modulation;details=o.details;payload=o.payload;manufacturer=o.manufacturer;
			meterId=o.meterId;deviceTypeName=o.deviceTypeName;meterVersion=o.meterVersion;
			controlField=o.controlField;ciField=o.ciField;lastAccessNumber=o.lastAccessNumber;
			frameFormat=o.frameFormat;
			security=o.security;application=o.application;weather=o.weather;tpms=o.tpms;checksumValid=o.checksumValid;
		}
		boolean update(Ism868SignalAnalyzer.Observation o,long arrivalNanos,long arrivalMillis) {
			long eventMillis=advancePeriodClock(o.captureMillis,arrivalNanos);
			boolean unique=true,resetRate=false;
			if(meterId.length()>0){
				if(o.accessNumber>=0&&lastAccessNumber>=0){
					int delta=(o.accessNumber-lastAccessNumber)&255;
					if(delta==0)unique=false;
					else if(delta<=127){accessSteps+=delta;missed+=Math.max(0,delta-1);}
					else resetRate=true;
				}else if(o.accessNumber<0&&payload.length()>0&&payload.equals(o.payloadHex)
						&&eventMillis-lastRateEventMillis<750L)unique=false;
			}
			frequencyHz=Math.round(frequencyHz*.65d+o.frequencyHz*.35d);
			bandwidthHz=Math.round(bandwidthHz*.65d+o.bandwidthHz*.35d);
			if(!Double.isNaN(o.levelDbfs))levelDbfs=Double.isNaN(levelDbfs)?o.levelDbfs
					:levelDbfs*.7d+o.levelDbfs*.3d;
			if(!Double.isNaN(o.snrDb))snrDb=Double.isNaN(snrDb)?o.snrDb:snrDb*.7d+o.snrDb*.3d;
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
			if(resetRate){rateStartMillis=eventMillis;accessSteps=0;rateReceived=1;}
			else rateReceived++;
			received++;lastRateEventMillis=eventMillis;
			lastSeenMillis=arrivalMillis;return true;
		}
		private long advancePeriodClock(long sourceMillis,long arrivalNanos){
			long increment;
			if(sourceMillis>lastPeriodSourceMillis)increment=sourceMillis-lastPeriodSourceMillis;
			else increment=Math.max(0L,Math.round((arrivalNanos-lastObservationNanos)/1_000_000d));
			periodClockMillis+=increment;lastPeriodSourceMillis=sourceMillis;
			lastObservationNanos=arrivalNanos;return periodClockMillis;
		}
		double periodSeconds(){
			long elapsed=lastRateEventMillis-rateStartMillis;
			if(elapsed<=0)return Double.NaN;
			long intervals=accessSteps>0?accessSteps:Math.max(0L,rateReceived-1L);
			return intervals<=0?Double.NaN:elapsed/(1000d*intervals);
		}
	}

	private final class ValueRenderer extends DefaultTableCellRenderer {
		private int column;
		ValueRenderer() { setHorizontalAlignment(SwingConstants.CENTER); }
		@Override protected void setValue(Object value) {
			if(value instanceof Long) {
				long n=((Long)value).longValue();
				if(column==0)value=String.format(Locale.US,"%.6f MHz",n/1e6);
				else if(column==34){long age=Math.max(0L,System.currentTimeMillis()-n);value=age<1000?age+" ms":String.format(Locale.US,"%.1f s",age/1000d);}
			} else if(value instanceof Double) {
				double n=((Double)value).doubleValue();
				if(Double.isNaN(n))value="--";
				else if(column==28)value=String.format(Locale.US,"%.1f dBFS",n);
				else if(column==29)value=String.format(Locale.US,"%.1f dB",n);
				else if(column==32)value=formatPeriod(n);
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
