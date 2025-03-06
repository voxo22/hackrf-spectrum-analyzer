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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
				if (lineNo == 1)
					continue;
				String[] s = line.split(";");
				Preset preset = new Preset(s[0], Integer.parseInt(s[1]), Integer.parseInt(s[2]), String.valueOf(s[3]), String.valueOf(s[4]), Integer.parseInt(s[5]));
				presets.add(preset);
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
