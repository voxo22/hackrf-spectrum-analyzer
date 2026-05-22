package jspectrumanalyzer.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class FrequencyPresets {
	private List<Preset> presets;

	public FrequencyPresets() throws FileNotFoundException {
		loadTableFromCSV("presets", new FileInputStream(new File("presets.csv")));
	}

	public List<Preset> getList() {
		return presets;
	}

	private void loadTableFromCSV(String tableName, InputStream is) {
		BufferedReader reader = null;
		presets = new ArrayList<>();
		try {
			reader = new BufferedReader(new InputStreamReader(is));
			String line = null;
			int lineNo = 0;
			while ((line = reader.readLine()) != null) {
				lineNo++;
				// skip first header line and empty lines
				if (lineNo == 1)
					continue;
				line = line.trim();
				if (line.isEmpty()) continue;
				String[] s = line.split(";");
				if (s.length < 6) continue; // malformed
				// remove BOM from first field if present
				if (s[0] != null && s[0].length() > 0 && s[0].charAt(0) == '\uFEFF') s[0] = s[0].substring(1);
				// try parsing numeric fields; if they fail, skip this line (allows repeated header rows)
				try {
					int freqMin = Integer.parseInt(s[1].trim());
					int ampOffset = Integer.parseInt(s[5].trim());
					Preset preset = new Preset(s[0].trim(), freqMin, String.valueOf(s[2]).trim(), String.valueOf(s[3]).trim(), String.valueOf(s[4]).trim(), ampOffset);
					presets.add(preset);
				} catch (NumberFormatException nfe) {
					// skip lines with non-numeric numeric fields (e.g. repeated header)
					continue;
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
			return;
		} finally {
			if (reader != null) {
				try {
					reader.close();
				} catch (IOException e) {
				}
			}
		}
	}
}
