package jspectrumanalyzer.iq;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Read-only MCC/MNC reference bundled in the application JAR. */
final class PlmnDatabase {
	private static final String RESOURCE = "/jspectrumanalyzer/iq/plmn.csv";
	private static final Map<String, Entry> ENTRIES = load();

	private PlmnDatabase() {}

	static Entry lookup(String mcc, String mnc) {
		if (mcc == null || mnc == null) return null;
		return ENTRIES.get(mcc.trim() + "-" + mnc.trim());
	}

	static int size() { return ENTRIES.size(); }

	private static Map<String, Entry> load() {
		Map<String, Entry> result = new HashMap<>();
		InputStream stream = PlmnDatabase.class.getResourceAsStream(RESOURCE);
		if (stream == null) return Collections.emptyMap();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.length() == 0 || line.charAt(0) == '#') continue;
				String[] value = line.split(";", -1);
				if (value.length < 7 || value[0].length() != 3 || value[1].length() < 2) continue;
				Entry candidate = new Entry(value[0], value[1], value[2], value[3], value[4], value[5], value[6]);
				String key = candidate.mcc + "-" + candidate.mnc;
				Entry previous = result.get(key);
				if (previous == null || candidate.priority() > previous.priority()) result.put(key, candidate);
			}
		} catch (IOException ignored) {
			return Collections.emptyMap();
		}
		return Collections.unmodifiableMap(result);
	}

	static final class Entry {
		final String mcc, mnc, countryCode, country, brand, operator, status;

		Entry(String mcc, String mnc, String countryCode, String country,
				String brand, String operator, String status) {
			this.mcc=mcc; this.mnc=mnc; this.countryCode=countryCode; this.country=country;
			this.brand=brand; this.operator=operator; this.status=status;
		}

		String networkName() {
			if (brand.length() > 0 && operator.length() > 0 && !brand.equalsIgnoreCase(operator))
				return brand + " (" + operator + ")";
			return brand.length() > 0 ? brand : operator;
		}

		private int priority() {
			if ("Operational".equalsIgnoreCase(status)) return 3;
			if ("Unknown".equalsIgnoreCase(status)) return 2;
			if (status.toLowerCase(java.util.Locale.ROOT).contains("not operational")) return 0;
			return 1;
		}
	}
}
