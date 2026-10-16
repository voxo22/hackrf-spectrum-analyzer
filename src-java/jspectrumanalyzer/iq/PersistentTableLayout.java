package jspectrumanalyzer.iq;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import javax.swing.JTable;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.Timer;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.RowSorterEvent;
import javax.swing.event.RowSorterListener;
import javax.swing.event.TableColumnModelEvent;
import javax.swing.event.TableColumnModelListener;
import javax.swing.table.TableColumn;

/** Persists a table's view-column order, widths, and model-column sort keys in the shared INI file. */
final class PersistentTableLayout {
	static final File DEFAULT_SETTINGS_FILE=new File("hackrf-iq-analyzer.ini");
	private final JTable table;private final int columnCount;private final File settingsFile;
	private final String prefix;private final Runnable defaultSort;private final Timer saveTimer;
	private boolean installed,restoring;

	PersistentTableLayout(JTable table,int columnCount,File settingsFile,String prefix,Runnable defaultSort){
		this.table=table;this.columnCount=columnCount;this.settingsFile=settingsFile;this.prefix=prefix;
		this.defaultSort=defaultSort;saveTimer=new Timer(450,event->save());saveTimer.setRepeats(false);
	}

	void installAndRestore(){if(installed)return;restore();installListeners();installed=true;}
	void saveAndStop(){if(!installed)return;saveTimer.stop();save();}
	void restore(){
		if(settingsFile==null||!settingsFile.isFile())return;Properties properties=load();restoring=true;
		try{
			String savedOrder=properties.getProperty(prefix+"columnOrder");
			String savedWidths=properties.getProperty(prefix+"columnWidths");
			int[] order=parseIntList(savedOrder,columnCount),widths=parseIntList(savedWidths,columnCount);
			if((savedOrder!=null&&!validOrder(order))||(savedWidths!=null&&widths==null)){defaultSort.run();return;}
			if(validOrder(order))for(int target=0;target<order.length;target++){
				int current=table.convertColumnIndexToView(order[target]);
				if(current>=0&&current!=target)table.moveColumn(current,target);
			}
			if(widths!=null)for(int modelColumn=0;modelColumn<widths.length;modelColumn++){
				int viewColumn=table.convertColumnIndexToView(modelColumn);if(viewColumn<0)continue;
				TableColumn column=table.getColumnModel().getColumn(viewColumn);
				int width=Math.max(35,Math.min(2000,widths[modelColumn]));column.setPreferredWidth(width);column.setWidth(width);
			}
			List<RowSorter.SortKey> sortKeys=parseSortKeys(properties.getProperty(prefix+"sortKeys"));
			if(sortKeys!=null)table.getRowSorter().setSortKeys(sortKeys);else defaultSort.run();
		}catch(RuntimeException ignored){defaultSort.run();}
		finally{restoring=false;}
	}

	void save(){
		if(settingsFile==null)return;StringBuilder order=new StringBuilder(),widths=new StringBuilder(),sorting=new StringBuilder();
		for(int viewColumn=0;viewColumn<table.getColumnCount();viewColumn++){
			if(viewColumn>0)order.append(',');order.append(table.getColumnModel().getColumn(viewColumn).getModelIndex());
		}
		for(int modelColumn=0;modelColumn<columnCount;modelColumn++){
			if(modelColumn>0)widths.append(',');int viewColumn=table.convertColumnIndexToView(modelColumn);
			widths.append(viewColumn<0?75:table.getColumnModel().getColumn(viewColumn).getWidth());
		}
		for(RowSorter.SortKey key:table.getRowSorter().getSortKeys()){
			if(sorting.length()>0)sorting.append(';');sorting.append(key.getColumn()).append(':')
					.append(key.getSortOrder()==SortOrder.DESCENDING?'D':'A');
		}
		synchronized(PersistentTableLayout.class){Properties properties=load();
			properties.setProperty(prefix+"columnOrder",order.toString());
			properties.setProperty(prefix+"columnWidths",widths.toString());
			properties.setProperty(prefix+"sortKeys",sorting.toString());
			try(FileOutputStream output=new FileOutputStream(settingsFile)){
				properties.store(output,"HackRF IQ Analyzer settings");}
			catch(IOException ignored){ }
		}
	}

	private void installListeners(){
		table.getColumnModel().addColumnModelListener(new TableColumnModelListener(){
			@Override public void columnAdded(TableColumnModelEvent event){scheduleSave();}
			@Override public void columnRemoved(TableColumnModelEvent event){scheduleSave();}
			@Override public void columnMoved(TableColumnModelEvent event){if(event.getFromIndex()!=event.getToIndex())scheduleSave();}
			@Override public void columnMarginChanged(ChangeEvent event){scheduleSave();}
			@Override public void columnSelectionChanged(ListSelectionEvent event){ }
		});
		table.getRowSorter().addRowSorterListener(new RowSorterListener(){
			@Override public void sorterChanged(RowSorterEvent event){if(event.getType()==RowSorterEvent.Type.SORT_ORDER_CHANGED)scheduleSave();}
		});
	}
	private void scheduleSave(){if(!restoring&&settingsFile!=null)saveTimer.restart();}
	private Properties load(){Properties properties=new Properties();if(settingsFile==null||!settingsFile.isFile())return properties;
		try(FileInputStream input=new FileInputStream(settingsFile)){properties.load(input);}catch(IOException ignored){ }return properties;}
	private static int[] parseIntList(String value,int count){if(value==null)return null;String[] parts=value.split(",",-1);
		if(parts.length!=count)return null;int[] parsed=new int[count];try{for(int n=0;n<count;n++)parsed[n]=Integer.parseInt(parts[n]);}
		catch(NumberFormatException invalid){return null;}return parsed;}
	private boolean validOrder(int[] order){if(order==null||order.length!=columnCount)return false;boolean[] seen=new boolean[columnCount];
		for(int modelColumn:order){if(modelColumn<0||modelColumn>=seen.length||seen[modelColumn])return false;seen[modelColumn]=true;}return true;}
	private List<RowSorter.SortKey> parseSortKeys(String value){if(value==null)return null;if(value.length()==0)return Collections.emptyList();
		List<RowSorter.SortKey> keys=new ArrayList<RowSorter.SortKey>();try{for(String part:value.split(";")){
			String[] fields=part.split(":",-1);if(fields.length!=2)return null;int column=Integer.parseInt(fields[0]);
			if(column<0||column>=columnCount)return null;SortOrder order="D".equals(fields[1])?SortOrder.DESCENDING:
					("A".equals(fields[1])?SortOrder.ASCENDING:null);if(order==null)return null;keys.add(new RowSorter.SortKey(column,order));
		}}catch(NumberFormatException invalid){return null;}return keys;}
}
