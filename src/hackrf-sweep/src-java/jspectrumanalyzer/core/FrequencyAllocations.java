package jspectrumanalyzer.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.FileInputStream;
import java.io.FileNotFoundException;

public class FrequencyAllocations {
	//private TreeMap<String, FrequencyAllocationTable> table	= new TreeMap<>(Collections.reverseOrder());
	private TreeMap<String, FrequencyAllocationTable> table	= new TreeMap<>();

	public FrequencyAllocations() throws FileNotFoundException {
		loadEurope();
	}

	public TreeMap<String, FrequencyAllocationTable> getTable() {
		return new TreeMap<>(table);
	}

	private void loadEurope() throws FileNotFoundException {
		File dir = new File("./freq");
		File[] fileList = dir.listFiles();
		//Arrays.sort(fileList, Comparator.comparingLong(File::lastModified));
		//SimpleDateFormat dft = new SimpleDateFormat("yyyy-MM");

		for(int i = 0; i < fileList.length; i++) {
			if(fileList[i].isFile()) {
				//loadTableFromCSV(dft.format(fileList[i].lastModified())+" "+fileList[i].getName(), new FileInputStream(dir+"/"+fileList[i].getName()));				
				loadTableFromCSV(fileList[i].getName(), new FileInputStream(dir+"/"+fileList[i].getName()));
			}
		}
	}
	
	private void loadTableFromCSV(String locationName, InputStream is) {
		BufferedReader reader	= null;
		
		ArrayList<FrequencyBand> bands	= new ArrayList<>();
		
		try {
			/**
			 * Source:
			 * https://www.efis.dk/views2/search-general.jsp
			 */
			//"- Europe (ECA) -";"526.500 - 1606.500 kHz";"Broadcasting";"Inductive applications/Broadcasting"
			Pattern patternCSV	= Pattern.compile("\"[^\"]+\";\"([0-9.]+)\\s+-\\s+([0-9.]+)\\s+([kM])Hz\";\"([^\"]+)\";\"([^\"]+)\"");
			
			reader	= new BufferedReader(new InputStreamReader(is));
			String line	= null;
			int lineNo	= 0;
			while((line = reader.readLine()) != null) {
				lineNo++;
				if (lineNo == 1)
					continue;
				
				Matcher m	= patternCSV.matcher(line);
				if (m.find()) {
					double multiplier	= m.group(3).equals("k") ? 1000 : 1000000;
					long startFreq	= Math.round(Double.parseDouble(m.group(1)) * multiplier);
					long stopFreq	= Math.round(Double.parseDouble(m.group(2)) * multiplier);
					String name	= m.group(4);
					String applications	= m.group(5);
					FrequencyBand band	= new FrequencyBand(startFreq, stopFreq, name, applications);
					bands.add(band);
				}
			}
			
		} catch (Exception e) {
			e.printStackTrace();
			return;
		}
		finally {
			if (reader != null) {
				try {
					reader.close();
				} catch (IOException e) {
					
				}
			}
		}
		FrequencyAllocationTable allocationTable	= new FrequencyAllocationTable(locationName, bands);
		table.put(locationName, allocationTable);
	}
}
