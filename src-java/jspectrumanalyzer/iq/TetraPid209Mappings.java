package jspectrumanalyzer.iq;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Confirmed PID-209 identifiers established from synchronized board observations. */
final class TetraPid209Mappings {
	private static final File FILE = new File("tetra-pid209-mappings.txt");
	private static volatile Map<Integer, String> boards = Collections.emptyMap();
	private static volatile Map<Integer, String> lines = Collections.emptyMap();
	private static volatile Map<Integer, String> destinations = Collections.emptyMap();
	private static long loadedModified = Long.MIN_VALUE;
	private static long loadedLength = Long.MIN_VALUE;
	private static volatile String lastError;

	static {
		reload();
	}

	private TetraPid209Mappings() { }

	static String board(int destinationSsi) { return boards.get(Integer.valueOf(destinationSsi)); }
	static String line(int fieldA) { return lines.get(Integer.valueOf(fieldA)); }
	static String destination(int fieldB) { return destinations.get(Integer.valueOf(fieldB)); }

	static int boardCount() { return boards.size(); }
	static int lineCount() { return lines.size(); }
	static int destinationCount() { return destinations.size(); }
	static String lastError() { return lastError; }

	static synchronized boolean reloadIfChanged() {
		if (FILE.lastModified() == loadedModified && FILE.length() == loadedLength) return false;
		return reload();
	}

	static synchronized boolean reload() {
		loadedModified = FILE.lastModified();
		loadedLength = FILE.length();
		if (!FILE.isFile()) {
			lastError = FILE.getName() + " not found";
			return false;
		}
		Map<Integer, String> nextBoards = new LinkedHashMap<Integer, String>();
		Map<Integer, String> nextLines = new LinkedHashMap<Integer, String>();
		Map<Integer, String> nextDestinations = new LinkedHashMap<Integer, String>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
				new FileInputStream(FILE), StandardCharsets.UTF_8))) {
			String text;
			int lineNumber = 0;
			while ((text = reader.readLine()) != null) {
				lineNumber++;
				if (lineNumber == 1 && text.startsWith("\ufeff")) text = text.substring(1);
				text = text.trim();
				if (text.length() == 0 || text.startsWith("#")) continue;
				String[] values = text.split(";", -1);
				String type = values[0].trim().toUpperCase(Locale.ROOT);
				try {
					if ("BOARD".equals(type)) {
						require(values, 4, lineNumber);
						String stop = values[2].trim(), direction = values[3].trim();
						if (stop.length() == 0) throw new IllegalArgumentException("empty board name");
						nextBoards.put(Integer.valueOf(parseSsi(values[1])), direction.length() == 0
								? stop : stop + " → " + direction);
					} else if ("LINE".equals(type)) {
						require(values, 3, lineNumber);
						nextLines.put(Integer.valueOf(parseHex(values[1])), nonEmpty(values[2], "line name"));
					} else if ("DESTINATION".equals(type)) {
						require(values, 3, lineNumber);
						nextDestinations.put(Integer.valueOf(parseHex(values[1])),
								nonEmpty(values[2], "destination name"));
					} else {
						throw new IllegalArgumentException("unknown record type " + values[0].trim());
					}
				} catch (RuntimeException error) {
					throw new IOException("line " + lineNumber + ": " + error.getMessage(), error);
				}
			}
			boards = Collections.unmodifiableMap(nextBoards);
			lines = Collections.unmodifiableMap(nextLines);
			destinations = Collections.unmodifiableMap(nextDestinations);
			lastError = null;
			return true;
		} catch (IOException error) {
			lastError = error.getMessage();
			return false;
		}
	}

	private static void require(String[] values, int count, int lineNumber) {
		if (values.length < count)
			throw new IllegalArgumentException("expected " + count + " semicolon-separated fields");
	}

	private static int parseSsi(String text) {
		String value = text.trim();
		return value.startsWith("0x") || value.startsWith("0X")
				? Integer.parseInt(value.substring(2), 16) : Integer.parseInt(value);
	}

	private static int parseHex(String text) {
		String value = text.trim();
		if (value.startsWith("0x") || value.startsWith("0X")) value = value.substring(2);
		return Integer.parseInt(value, 16);
	}

	private static String nonEmpty(String text, String description) {
		String value = text.trim();
		if (value.length() == 0) throw new IllegalArgumentException("empty " + description);
		return value;
	}
}
