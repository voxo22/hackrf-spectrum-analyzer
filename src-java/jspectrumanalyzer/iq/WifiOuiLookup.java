package jspectrumanalyzer.iq;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Offline MAC assignment lookup backed by Wireshark's generated manuf database. */
final class WifiOuiLookup {
	private WifiOuiLookup() { }

	static String company(String mac) {
		String hex = normalize(mac);
		if (hex == null) return "--";
		int first = Integer.parseInt(hex.substring(0, 2), 16);
		if ((first & 1) != 0) return "Multicast";
		if ((first & 2) != 0) return "Randomized / local";
		Map<String,String> entries = Holder.ENTRIES;
		for (int bits : new int[]{48,36,28,24}) {
			String value = entries.get(bits + ":" + hex.substring(0, (bits + 3) / 4));
			if (value != null && value.length() > 0) return value;
		}
		return "--";
	}

	static String addressType(String mac) {
		String hex = normalize(mac);
		if (hex == null) return "Unknown";
		int first = Integer.parseInt(hex.substring(0, 2), 16);
		if ((first & 1) != 0) return "Multicast";
		return (first & 2) != 0 ? "Randomized / locally administered" : "Globally administered";
	}

	private static String normalize(String mac) {
		if (mac == null) return null;
		String hex = mac.replace(":", "").replace("-", "").replace(".", "").toUpperCase();
		if (hex.length() != 12 || !hex.matches("[0-9A-F]{12}")) return null;
		return hex;
	}

	private static final class Holder {
		static final Map<String,String> ENTRIES = load();
	}

	private static Map<String,String> load() {
		Map<String,String> result = new HashMap<String,String>();
		InputStream raw = WifiOuiLookup.class.getResourceAsStream("manuf.gz");
		try {
			if (raw == null) {
				File developmentCopy = new File("src-java/jspectrumanalyzer/iq/manuf.gz");
				if (developmentCopy.isFile()) raw = new FileInputStream(developmentCopy);
			}
			if (raw == null) return result;
			BufferedReader reader = new BufferedReader(new InputStreamReader(
					new GZIPInputStream(raw), StandardCharsets.UTF_8));
			try {
				String line;
				while ((line = reader.readLine()) != null) {
					line = line.trim();
					if (line.length() == 0 || line.charAt(0) == '#') continue;
					String[] fields = line.split("\\s+", 3);
					if (fields.length < 2) continue;
					String prefix = fields[0];
					int slash = prefix.indexOf('/');
					int bits = slash < 0 ? 24 : parseBits(prefix.substring(slash + 1));
					if (slash >= 0) prefix = prefix.substring(0, slash);
					String hex = prefix.replace(":", "").replace("-", "").replace(".", "").toUpperCase();
					int digits = (bits + 3) / 4;
					if (bits <= 0 || hex.length() < digits) continue;
					String company = fields.length >= 3 && fields[2].length() > 0 ? fields[2] : fields[1];
					result.put(bits + ":" + hex.substring(0, digits), company);
				}
			} finally {
				reader.close();
			}
		} catch (IOException | RuntimeException ignored) {
			try { if (raw != null) raw.close(); } catch (IOException closeIgnored) { }
		}
		return result;
	}

	private static int parseBits(String value) {
		try { return Integer.parseInt(value); }
		catch (NumberFormatException ignored) { return -1; }
	}
}
