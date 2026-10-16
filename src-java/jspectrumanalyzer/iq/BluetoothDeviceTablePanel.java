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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;

/** Shared BLE advertising/data and Classic BR/EDR device inventory. */
final class BluetoothDeviceTablePanel extends JPanel {
	private static final Color HEADER_BG=new Color(0x202020),HEADER_FG=Color.WHITE;
	private static final Color[] SORT_HEADER_BACKGROUNDS={new Color(0x17627d),new Color(0x4b557c),new Color(0x685076)};
	private static final Color HEADER_BORDER=new Color(0x555555);
	private static final long TRACKING_CHIME_COOLDOWN_MILLIS=750L;
	private static final Color TRACKED_BACKGROUND=new Color(0xeeeeee);
	private static final String TRACKING_HELP="Tracking: right-click a device row to enable its chime; right-click it again to disable tracking.";
	private static final String[] COLUMNS={"Name","PHY","Company / ID","ID","Model / version","RSSI","BT version","Device type","Identification evidence","Class of Device","Address",
			"Address type","Role","Latest PDU","Ch","Frequency","Properties","Services","Manufacturer data",
			"Tx power","CFO","Packets","Connection / extended advertising","Last seen"};
	private final Map<String,Device> devices=new LinkedHashMap<String,Device>();
	private final BluetoothGattIdentifier identifier=new BluetoothGattIdentifier();
	private final BluetoothClassicIdentifier classicIdentifier=new BluetoothClassicIdentifier();
	private final List<String> rowKeys=new ArrayList<String>();
	private boolean headerPopupHandled;
	private boolean rowPopupHandled;
	private volatile String trackedAddress="--";
	private volatile boolean audibleNotifications=true;
	private long lastChimeMillis,notificationCount;
	private final DefaultTableModel model=new DefaultTableModel(COLUMNS,0){
		@Override public boolean isCellEditable(int row,int column){return false;}
		@Override public Class<?> getColumnClass(int column){switch(column){case 14:case 19:return Integer.class;case 5:case 20:return Double.class;case 21:case 23:return Long.class;default:return String.class;}}
	};
	private final JTable table=new JTable(model);
	private final JLabel sortHint=new JLabel("Sort: click a column to add/prioritize; right-click a sorted column to remove it; right-click an unsorted column to clear sorting.");
	private final JLabel trackingHelp=new JLabel(TRACKING_HELP);
	private final JLabel status=new JLabel("Waiting for CRC-valid BLE advertising packets...");
	private final PersistentTableLayout tableLayout;
	private long packets,dataPackets,classicAccessCodes,classicHeaders,classicPayloads,classicFhsPackets;private String latestPdu="--";

	BluetoothDeviceTablePanel(){this(PersistentTableLayout.DEFAULT_SETTINGS_FILE);}
	BluetoothDeviceTablePanel(File tableSettingsFile){super(new BorderLayout());setBackground(Color.BLACK);table.setAutoCreateRowSorter(true);
		table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);table.setRowHeight(23);table.setFillsViewportHeight(true);
		table.setBackground(Color.BLACK);table.setForeground(Color.WHITE);table.setGridColor(new Color(0x333333));
		table.setSelectionBackground(new Color(0x244b66));table.setSelectionForeground(Color.WHITE);
		table.getTableHeader().setDefaultRenderer(new SortHeaderRenderer());
		table.getRowSorter().addRowSorterListener(event->table.getTableHeader().repaint());
		table.getTableHeader().addMouseListener(new MouseAdapter(){@Override public void mousePressed(MouseEvent event){headerPopupHandled=handleHeaderPopup(event);}
			@Override public void mouseReleased(MouseEvent event){if(!headerPopupHandled)handleHeaderPopup(event);headerPopupHandled=false;}});
		table.addMouseListener(new MouseAdapter(){@Override public void mousePressed(MouseEvent event){rowPopupHandled=handleRowPopup(event);}
			@Override public void mouseReleased(MouseEvent event){if(!rowPopupHandled)handleRowPopup(event);rowPopupHandled=false;}});
		int[] widths={145,60,155,62,210,78,115,180,220,105,132,140,82,125,42,92,165,210,220,72,78,62,390,82};
		DefaultTableCellRenderer renderer=new ValueRenderer();for(int n=0;n<COLUMNS.length;n++){table.getColumnModel().getColumn(n).setPreferredWidth(widths[n]);table.getColumnModel().getColumn(n).setCellRenderer(renderer);}
		sortHint.setBorder(BorderFactory.createEmptyBorder(3,7,0,7));sortHint.setForeground(new Color(0x999999));sortHint.setBackground(Color.BLACK);sortHint.setOpaque(true);
		status.setBorder(BorderFactory.createEmptyBorder(3,7,3,7));status.setForeground(new Color(0xdddddd));status.setBackground(Color.BLACK);status.setOpaque(true);
		trackingHelp.setBorder(BorderFactory.createEmptyBorder(0,7,3,7));trackingHelp.setForeground(new Color(0x9fd7ff));trackingHelp.setBackground(Color.BLACK);trackingHelp.setOpaque(true);
		JScrollPane scroll=new JScrollPane(table);scroll.getViewport().setBackground(Color.BLACK);scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS);scroll.setPreferredSize(new Dimension(1000,300));JPanel footer=new JPanel(new GridLayout(3,1));footer.setBackground(Color.BLACK);footer.add(sortHint);footer.add(trackingHelp);footer.add(status);add(scroll,BorderLayout.CENTER);add(footer,BorderLayout.SOUTH);
		setDefaultSort();tableLayout=new PersistentTableLayout(table,COLUMNS.length,tableSettingsFile,"bluetooth.table.",this::setDefaultSort);
	}
	@Override public void addNotify(){super.addNotify();tableLayout.installAndRestore();}
	@Override public void removeNotify(){tableLayout.saveAndStop();super.removeNotify();}
	private void setDefaultSort(){table.getRowSorter().setSortKeys(java.util.Collections.singletonList(new RowSorter.SortKey(23,SortOrder.DESCENDING)));}
	void saveTableLayoutForTest(){tableLayout.save();}void restoreTableLayoutForTest(){tableLayout.restore();}
	void moveColumnForTest(String name,int target){int modelColumn=model.findColumn(name),view=table.convertColumnIndexToView(modelColumn);if(view>=0)table.moveColumn(view,target);}
	void setColumnWidthForTest(String name,int width){int view=table.convertColumnIndexToView(model.findColumn(name));table.getColumnModel().getColumn(view).setWidth(width);}
	int viewColumnForTest(String name){return table.convertColumnIndexToView(model.findColumn(name));}
	int columnWidthForTest(String name){return table.getColumnModel().getColumn(viewColumnForTest(name)).getWidth();}
	void sortByColumnForTest(String name,SortOrder order){table.getRowSorter().setSortKeys(java.util.Collections.singletonList(new RowSorter.SortKey(model.findColumn(name),order)));}
	void sortByColumnsForTest(String first,SortOrder firstOrder,String second,SortOrder secondOrder){table.getRowSorter().setSortKeys(java.util.Arrays.asList(
			new RowSorter.SortKey(model.findColumn(first),firstOrder),new RowSorter.SortKey(model.findColumn(second),secondOrder)));}
	void rightClickHeaderForTest(String name){removeOrClearSort(table.convertColumnIndexToView(model.findColumn(name)));}
	int sortKeyCountForTest(){return table.getRowSorter().getSortKeys().size();}
	void selectAddressForTest(String address){for(int row=0;row<rowKeys.size();row++)if(address.equals(rowKeys.get(row))){int view=table.convertRowIndexToView(row);if(view>=0)table.setRowSelectionInterval(view,view);return;}}
	void rightClickAddressForTest(String address){for(int row=0;row<rowKeys.size();row++)if(address.equals(rowKeys.get(row))){toggleTracking(rowKeys.get(row));return;}}
	boolean addressTrackedForTest(String address){return address.equals(trackedAddress);}
	void setAudibleNotificationsForTest(boolean enabled){audibleNotifications=enabled;}
	synchronized long notificationCountForTest(){return notificationCount;}
	String primarySortColumnForTest(){List<? extends RowSorter.SortKey> keys=table.getRowSorter().getSortKeys();return keys.isEmpty()?"":COLUMNS[keys.get(0).getColumn()];}
	SortOrder primarySortOrderForTest(){List<? extends RowSorter.SortKey> keys=table.getRowSorter().getSortKeys();return keys.isEmpty()?SortOrder.UNSORTED:keys.get(0).getSortOrder();}
	String headerTextForTest(String name){int view=table.convertColumnIndexToView(model.findColumn(name));Component component=table.getTableHeader().getDefaultRenderer()
			.getTableCellRendererComponent(table,table.getColumnName(view),false,false,-1,view);return ((JLabel)component).getText();}
	private boolean handleHeaderPopup(MouseEvent event){if(!event.isPopupTrigger())return false;int viewColumn=table.getTableHeader().columnAtPoint(event.getPoint());if(viewColumn<0)return false;
		event.consume();removeOrClearSort(viewColumn);return true;}
	private void removeOrClearSort(int viewColumn){if(viewColumn<0)return;int modelColumn=table.convertColumnIndexToModel(viewColumn);List<RowSorter.SortKey> retained=new ArrayList<RowSorter.SortKey>();boolean removed=false;
		for(RowSorter.SortKey key:table.getRowSorter().getSortKeys())if(key.getColumn()==modelColumn&&key.getSortOrder()!=SortOrder.UNSORTED)removed=true;else retained.add(key);
		table.getRowSorter().setSortKeys(removed?retained:java.util.Collections.<RowSorter.SortKey>emptyList());}
	private boolean handleRowPopup(MouseEvent event){if(!event.isPopupTrigger())return false;int viewRow=table.rowAtPoint(event.getPoint());if(viewRow<0)return false;int modelRow=table.convertRowIndexToModel(viewRow);
		if(modelRow<0||modelRow>=rowKeys.size())return false;event.consume();toggleTracking(rowKeys.get(modelRow));return true;}
	private void toggleTracking(String address){trackedAddress=address.equals(trackedAddress)?"--":address;table.repaint();}

	synchronized void observe(BluetoothLePacket packet){if(packet==null)return;packets++;latestPdu=packet.pduType;
		long arrivalMillis=System.currentTimeMillis();
		if(usable(packet.address))observeAddress(packet.address,packet.addressType,packet.role,packet,false,arrivalMillis);
		if(usable(packet.peerAddress))observeAddress(packet.peerAddress,packet.peerAddressType,packet.peerRole,packet,true,arrivalMillis);
	}
	synchronized void observe(BluetoothLeDataPacket packet){if(packet==null)return;dataPackets++;latestPdu=packet.packetType;
		long arrivalMillis=System.currentTimeMillis();observeConnected(packet.initiatorAddress,"Initiator",packet,arrivalMillis);
		observeConnected(packet.advertiserAddress,"Advertiser",packet,arrivalMillis);
		for(BluetoothGattIdentifier.Identity identity:identifier.observe(packet))applyIdentity(identity);}
	private void applyIdentity(BluetoothGattIdentifier.Identity identity){if(identity==null||!usable(identity.address))return;Device d=devices.get(identity.address);
		if(d==null){d=new Device(identity.address);devices.put(identity.address,d);}int old=strength(d.identificationConfidence),fresh=strength(identity.confidence);
		if(!"--".equals(identity.name)&&(fresh>=old||"--".equals(d.name)))d.name=identity.name;
		if(!"--".equals(identity.deviceType)&&(fresh>=old||"--".equals(d.deviceType)))d.deviceType=identity.deviceType;
		if(!"--".equals(identity.company)&&(fresh>=old||"--".equals(d.company)))d.company=identity.company;
		if(!"--".equals(identity.bluetoothVersion)&&(fresh>=old||"--".equals(d.bluetoothVersion)))d.bluetoothVersion=identity.bluetoothVersion;
		if(!"--".equals(identity.services))d.services=merge(d.services,identity.services);
		if(!"--".equals(identity.model))d.model=identity.model;
		if(fresh>=old)d.identificationConfidence=identity.confidence;
		d.identificationEvidence=merge(d.identificationEvidence,identity.evidence);
		if(!"--".equals(identity.details))d.identificationDetails=identity.details;chimeForTrackedAddress(identity.address);}
	private void observeConnected(String address,String role,BluetoothLeDataPacket packet,long arrivalMillis){if(!usable(address))return;Device d=devices.get(address);
		if(d==null){d=new Device(address);devices.put(address,d);}d.role="Connected "+role;d.pdu=packet.packetType;d.phy=packet.phy;
		d.channel=packet.channel;d.frequencyHz=packet.frequencyHz;d.levelDbfs=packet.levelDbfs;d.cfoHz=packet.cfoHz;
		d.lastSeen=arrivalMillis;d.packets++;d.properties="CRC valid; "+(packet.sn?"SN=1":"SN=0")+", "
				+(packet.nesn?"NESN=1":"NESN=0")+(packet.moreData?", more data":"");d.details=packet.accessText()+"; "
				+("--".equals(packet.trackingInfo)?"":packet.trackingInfo+"; ")
				+("--".equals(packet.direction)?"":packet.direction+"; ")+packet.details;chimeForTrackedAddress(address);}
	synchronized void observe(BluetoothClassicPacket packet){if(packet==null)return;classicAccessCodes++;latestPdu=packet.packetType;
		if(packet.headerValid)classicHeaders++;if(packet.payloadValid&&!packet.fhsValid)classicPayloads++;
		long arrivalMillis=System.currentTimeMillis();
		if(packet.fhsValid){classicFhsPackets++;String address=packet.address;Device d=devices.get(address);
			if(d==null){d=new Device(address);devices.put(address,d);}d.addressType="BR/EDR public (FHS)";
			d.company=WifiOuiLookup.company(address);d.role="Inquiry responder";d.pdu=packet.packetType;d.phy=packet.phy;
			d.channel=packet.channel;d.frequencyHz=packet.frequencyHz;d.levelDbfs=packet.levelDbfs;d.cfoHz=packet.cfoHz;
			d.lastSeen=arrivalMillis;d.packets++;d.deviceType=packet.deviceType;
			d.classOfDevice=String.format(Locale.US,"0x%06X",packet.classOfDevice);
			d.identificationConfidence="High";d.identificationEvidence=merge(d.identificationEvidence,"Classic FHS, Class of Device");
			d.properties="HEC + FHS CRC valid; BCH errors "+packet.bitErrors+", FEC corrections "+packet.payloadCorrections;
			d.details="EIR "+(packet.eir?"may follow":"not indicated")+", page scan "+packet.scanRepetition
					+", native clock 0x"+Long.toHexString(packet.nativeClock).toUpperCase(Locale.US)
					+", whitening X="+packet.whiteningSeed;chimeForTrackedAddress(address);return;}
		if(packet.headerValid&&usable(packet.address)){Device d=devices.get(packet.address);if(d==null){d=new Device(packet.address);devices.put(packet.address,d);}
			d.addressType="BR/EDR public (learned from FHS)";d.company=WifiOuiLookup.company(packet.address);d.role="Classic piconet";
			d.pdu=packet.packetType;d.phy=packet.phy;d.channel=packet.channel;d.frequencyHz=packet.frequencyHz;
			d.levelDbfs=packet.levelDbfs;d.cfoHz=packet.cfoHz;d.lastSeen=arrivalMillis;d.packets++;
			if(!"--".equals(packet.deviceType))d.deviceType=packet.deviceType;if(packet.classOfDevice>=0)d.classOfDevice=String.format(Locale.US,"0x%06X",packet.classOfDevice);
			if(packet.classOfDevice>=0){d.identificationConfidence="High";d.identificationEvidence=merge(d.identificationEvidence,"Class of Device");}
			d.properties="HEC valid; LT_ADDR "+packet.ltAddr+", FLOW "+(packet.flow?"GO":"STOP")+", ARQN "+(packet.arqn?"ACK":"NAK")
					+", SEQN "+(packet.seqn?1:0)+(packet.payloadValid?"; payload CRC valid":"");
			d.details=String.format(Locale.US,"%s; UAP 0x%02X; whitening X=%d; %s",packet.classicType,packet.uap,
					packet.whiteningSeed,packet.payloadInfo);chimeForTrackedAddress(packet.address);applyIdentity(classicIdentifier.observe(packet));return;}
		if(packet.inquiryAccess)return;String address=packet.lapText();Device d=devices.get(address);if(d==null){d=new Device(address);devices.put(address,d);}
		d.addressType="BR/EDR partial address";d.role="Classic piconet";d.pdu=packet.packetType;d.phy=packet.phy;
		d.channel=packet.channel;d.frequencyHz=packet.frequencyHz;d.levelDbfs=packet.levelDbfs;d.cfoHz=packet.cfoHz;
		d.lastSeen=arrivalMillis;d.packets++;d.properties="BCH-validated access code, "+packet.bitErrors+" bit errors"
				+(packet.trailerPresent?", trailer present":"");d.details="LAP only; FHS/header decode is required for full BD_ADDR and Class of Device";chimeForTrackedAddress(address);
	}
	private void observeAddress(String address,String type,String role,BluetoothLePacket p,boolean peer,long arrivalMillis){Device d=devices.get(address);if(d==null){d=new Device(address);devices.put(address,d);}d.addressType=type;d.role=role==null||role.length()==0?"--":role;d.pdu=p.pduType;d.phy=p.phy;d.channel=p.channel;d.frequencyHz=p.frequencyHz;d.levelDbfs=p.levelDbfs;d.cfoHz=p.cfoHz;d.lastSeen=arrivalMillis;d.packets++;
		if(!peer){if(!"--".equals(p.localName)){d.name=p.localName;identify(d,"High","Advertising name");}if(!"--".equals(p.deviceType)){d.deviceType=p.deviceType;identify(d,"High",!"--".equals(p.appearance)?p.appearance:!"--".equals(p.classOfDevice)?"Class of Device":"Advertised service");}if(!"--".equals(p.classOfDevice))d.classOfDevice=p.classOfDevice;if(!"--".equals(p.services)){d.services=p.services;if("--".equals(d.identificationConfidence))identify(d,"Medium","Advertised services");}if(!"--".equals(p.manufacturerData)){d.manufacturerData=p.manufacturerData;identify(d,"Low","Manufacturer data");}if(!"--".equals(p.manufacturer))d.company=p.manufacturer;if(!"--".equals(p.connectionInfo))d.details=p.connectionInfo;if(!"--".equals(p.extendedInfo))d.details=p.extendedInfo;d.properties=properties(p);d.txPower=p.txPower;}
		else if("Public".equals(type))d.company=WifiOuiLookup.company(address);chimeForTrackedAddress(address);
	}
	private static void identify(Device d,String confidence,String evidence){if(strength(confidence)>=strength(d.identificationConfidence))d.identificationConfidence=confidence;d.identificationEvidence=merge(d.identificationEvidence,evidence);}
	private static int strength(String confidence){return "High".equals(confidence)?3:"Medium".equals(confidence)?2:"Low".equals(confidence)?1:0;}
	private static String merge(String oldValue,String fresh){if(fresh==null||fresh.length()==0||"--".equals(fresh))return oldValue;if(oldValue==null||oldValue.length()==0||"--".equals(oldValue))return fresh;
		for(String item:fresh.split(", "))if(!containsItem(oldValue,item))oldValue+=", "+item;return oldValue;}
	private static boolean containsItem(String value,String item){for(String existing:value.split(", "))if(existing.equals(item))return true;return false;}
	private static boolean usable(String value){return value!=null&&!"--".equals(value)&&!"00:00:00:00:00:00".equals(value)&&!"FF:FF:FF:FF:FF:FF".equals(value);}
	private static String properties(BluetoothLePacket p){StringBuilder out=new StringBuilder();if(p.connectable)out.append("connectable");if(p.scannable){if(out.length()>0)out.append(", ");out.append("scannable");}if(p.directed){if(out.length()>0)out.append(", ");out.append("directed");}if(!"--".equals(p.flags)){if(out.length()>0)out.append("; ");out.append(p.flags);}return out.length()==0?"--":out.toString();}
	private static String displayPhy(String phy){return phy!=null&&phy.startsWith("LE ")?"BLE "+phy.substring(3):phy;}
	private void chimeForTrackedAddress(String address){if(!address.equals(trackedAddress))return;long now=System.currentTimeMillis();
		if(now-lastChimeMillis<TRACKING_CHIME_COOLDOWN_MILLIS)return;lastChimeMillis=now;notificationCount++;
		SwingUtilities.invokeLater(()->{if(audibleNotifications)Toolkit.getDefaultToolkit().beep();});}

	synchronized int deviceCount(){return devices.size();}synchronized long packetCount(){return packets;}synchronized String latestPdu(){return latestPdu;}
	synchronized long lastSeenForTest(String address){Device device=devices.get(address);return device==null?0L:device.lastSeen;}
	void refresh(){List<Device> copy=new ArrayList<Device>();synchronized(this){for(Device d:devices.values())copy.add(new Device(d));}
		String selected=selectedKey();model.setRowCount(0);rowKeys.clear();for(Device d:copy){rowKeys.add(d.address);String detail="--".equals(d.details)?d.identificationDetails:"--".equals(d.identificationDetails)?d.details:d.details+"; "+d.identificationDetails;model.addRow(new Object[]{d.name,displayPhy(d.phy),d.company,d.identificationConfidence,d.model,Double.valueOf(d.levelDbfs),d.bluetoothVersion,d.deviceType,d.identificationEvidence,d.classOfDevice,d.address,d.addressType,d.role,d.pdu,Integer.valueOf(d.channel),String.format(Locale.US,"%.0f MHz",d.frequencyHz/1e6),d.properties,d.services,d.manufacturerData,Integer.valueOf(d.txPower),Double.valueOf(d.cfoHz),Long.valueOf(d.packets),detail,Long.valueOf(d.lastSeen)});}
		if(usable(selected))for(int row=0;row<model.getRowCount();row++)if(selected.equals(rowKeys.get(row))){int view=table.convertRowIndexToView(row);if(view>=0)table.setRowSelectionInterval(view,view);break;}
		status.setText(deviceCount()+" device/partial addresses, "+packetCount()+" BLE advertising + "+dataPackets+" data CRC-valid packets, "
				+classicAccessCodes+" Classic access / "+classicHeaders+" headers / "+classicPayloads+" payloads, "+classicFhsPackets
				+" CRC-valid FHS identities; random/private addresses may rotate");
	}
	private String selectedKey(){int view=table.getSelectedRow();if(view<0)return null;int modelRow=table.convertRowIndexToModel(view);return modelRow>=0&&modelRow<rowKeys.size()?rowKeys.get(modelRow):null;}

	private static final class Device {final String address;String name="--",deviceType="--",identificationConfidence="--",model="--",bluetoothVersion="--",identificationEvidence="--",identificationDetails="--",classOfDevice="--",addressType="Unknown",company="--",role="--",pdu="--",phy="--",properties="--",services="--",manufacturerData="--",details="--";int channel,txPower=Integer.MIN_VALUE;long frequencyHz,packets,lastSeen;double levelDbfs=Double.NaN,cfoHz=Double.NaN;Device(String address){this.address=address;}Device(Device d){address=d.address;name=d.name;deviceType=d.deviceType;identificationConfidence=d.identificationConfidence;model=d.model;bluetoothVersion=d.bluetoothVersion;identificationEvidence=d.identificationEvidence;identificationDetails=d.identificationDetails;classOfDevice=d.classOfDevice;addressType=d.addressType;company=d.company;role=d.role;pdu=d.pdu;phy=d.phy;properties=d.properties;services=d.services;manufacturerData=d.manufacturerData;details=d.details;channel=d.channel;txPower=d.txPower;frequencyHz=d.frequencyHz;packets=d.packets;lastSeen=d.lastSeen;levelDbfs=d.levelDbfs;cfoHz=d.cfoHz;}}
	private static final class SortHeaderRenderer extends DefaultTableCellRenderer {
		SortHeaderRenderer(){setHorizontalAlignment(SwingConstants.CENTER);setOpaque(true);setBorder(BorderFactory.createMatteBorder(0,0,1,1,HEADER_BORDER));}
		@Override public Component getTableCellRendererComponent(JTable table,Object value,boolean selected,boolean focus,int row,int column){
			super.getTableCellRendererComponent(table,value,selected,focus,row,column);int modelColumn=table.convertColumnIndexToModel(column),priority=-1;SortOrder order=SortOrder.UNSORTED;
			for(RowSorter.SortKey key:table.getRowSorter().getSortKeys())if(key.getColumn()==modelColumn&&key.getSortOrder()!=SortOrder.UNSORTED){priority=priority<0?sortPriority(table,key):priority;order=key.getSortOrder();break;}
			setBackground(priority<0?HEADER_BG:SORT_HEADER_BACKGROUNDS[priority%SORT_HEADER_BACKGROUNDS.length]);setForeground(HEADER_FG);
			String text=String.valueOf(value);setText(priority<0?text:text+" "+(order==SortOrder.ASCENDING?"\u25B2":"\u25BC")+(table.getRowSorter().getSortKeys().size()>1?Integer.toString(priority+1):""));return this;
		}
		private static int sortPriority(JTable table,RowSorter.SortKey wanted){int priority=0;for(RowSorter.SortKey key:table.getRowSorter().getSortKeys()){
			if(key.getSortOrder()==SortOrder.UNSORTED)continue;if(key.equals(wanted))return priority;priority++;}return -1;}
	}
	private final class ValueRenderer extends DefaultTableCellRenderer {private String columnName="";ValueRenderer(){setHorizontalAlignment(SwingConstants.CENTER);}@Override protected void setValue(Object value){if(value instanceof Double){double n=((Double)value).doubleValue();value=Double.isNaN(n)?"--":"RSSI".equals(columnName)?String.format(Locale.US,"%.1f dBFS",n):String.format(Locale.US,"%+.0f Hz",n);}else if(value instanceof Integer&&"Tx power".equals(columnName)&&((Integer)value).intValue()==Integer.MIN_VALUE)value="--";else if(value instanceof Integer&&"Tx power".equals(columnName))value=value+" dBm";else if(value instanceof Long&&"Last seen".equals(columnName)){long ms=Math.max(0L,System.currentTimeMillis()-((Long)value).longValue());value=ms<1000?ms+" ms":String.format(Locale.US,"%.1f s",ms/1000d);}super.setValue(value);}@Override public Component getTableCellRendererComponent(JTable table,Object value,boolean selected,boolean focus,int row,int column){columnName=table.getColumnName(column);Component c=super.getTableCellRendererComponent(table,value,selected,focus,row,column);int modelRow=table.convertRowIndexToModel(row);boolean tracked=modelRow>=0&&modelRow<rowKeys.size()&&trackedAddress.equals(rowKeys.get(modelRow));if(tracked){c.setBackground(TRACKED_BACKGROUND);c.setForeground(Color.BLACK);}else if(!selected){c.setBackground(Color.BLACK);c.setForeground(Color.WHITE);}return c;}}
}
