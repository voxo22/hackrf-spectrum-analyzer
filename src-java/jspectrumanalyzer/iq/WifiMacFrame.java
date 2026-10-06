package jspectrumanalyzer.iq;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Parsed, non-payload IEEE 802.11 MAC and management information. */
final class WifiMacFrame {
	final boolean fcsValid;
	final int type, subtype, lengthBytes;
	final String frameClass, frameType, ssid, bssid, source, destination, transmitter;
	final int channel, beaconIntervalTu, stationCount;
	final long timestampTsfMicros;
	final String security, supportedRates, standards, maxAdvertisedPhy;
	final String channelWidth, channelCapability, wps, country, channelRange;
	final String manufacturer, modelName, modelNumber, deviceName;
	final double channelUtilizationPercent;

	private WifiMacFrame(boolean fcsValid, int type, int subtype, int lengthBytes,
			String frameClass, String frameType, String ssid, String bssid,
			String source, String destination, String transmitter, int channel, int beaconIntervalTu,
			int stationCount, long timestampTsfMicros, String security,
			String supportedRates, String standards, String maxAdvertisedPhy,
			String channelWidth, String channelCapability, String wps, String country,
			String channelRange, String manufacturer, String modelName,
			String modelNumber, String deviceName, double channelUtilizationPercent) {
		this.fcsValid = fcsValid; this.type = type; this.subtype = subtype;
		this.lengthBytes = lengthBytes; this.frameClass = frameClass; this.frameType = frameType;
		this.ssid = ssid; this.bssid = bssid; this.source = source; this.destination = destination;
		this.transmitter = transmitter;
		this.channel = channel; this.beaconIntervalTu = beaconIntervalTu;
		this.stationCount = stationCount; this.timestampTsfMicros = timestampTsfMicros;
		this.security = security; this.supportedRates = supportedRates;
		this.standards = standards; this.maxAdvertisedPhy = maxAdvertisedPhy;
		this.channelWidth = channelWidth; this.channelCapability = channelCapability;
		this.wps = wps; this.country = country; this.channelRange = channelRange;
		this.manufacturer = manufacturer; this.modelName = modelName;
		this.modelNumber = modelNumber; this.deviceName = deviceName;
		this.channelUtilizationPercent = channelUtilizationPercent;
	}

	static WifiMacFrame empty() {
		return new WifiMacFrame(false, -1, -1, 0, "Unknown", "Unknown", "--", "--",
				"--", "--", "--", 0, 0, -1, -1L, "--", "--", "--", "--", "--", "--",
				"Not advertised", "--", "--", "--", "--", "--", "--", Double.NaN);
	}

	static WifiMacFrame parse(byte[] psdu, long centerFreqHz) {
		if (psdu == null || psdu.length < 2) return empty();
		boolean fcsValid = validFcs(psdu);
		int fc = u16(psdu, 0), type = fc >> 2 & 3, subtype = fc >> 4 & 15;
		String frameClass = type == 0 ? "Management" : type == 1 ? "Control"
				: type == 2 ? "Data" : "Extension";
		String frameType = frameType(type, subtype);
		String destination = mac(psdu, 4), source = "--", bssid = "--";
		String transmitter = mac(psdu, 10);
		if (type == 0 && psdu.length >= 24) {
			source = mac(psdu, 10); bssid = mac(psdu, 16);
		} else if (type == 2 && psdu.length >= 24) {
			boolean toDs = (fc & 0x0100) != 0, fromDs = (fc & 0x0200) != 0;
			if (!toDs && !fromDs) { source = mac(psdu, 10); bssid = mac(psdu, 16); }
			else if (toDs && !fromDs) { source = mac(psdu, 10); bssid = mac(psdu, 4); }
			else if (!toDs) { source = mac(psdu, 16); bssid = mac(psdu, 10); }
			else source = psdu.length >= 30 ? mac(psdu, 24) : "--";
		} else if (type == 1 && psdu.length >= 16) source = mac(psdu, 10);

		int ieStart = informationElementStart(type, subtype, psdu.length);
		String ssid = "--", security = "--", country = "--", channelRange = "--";
		String manufacturer = "--", modelName = "--", modelNumber = "--", deviceName = "--";
		String wps = "Not advertised", currentWidth = "--";
		int channel = 0, beaconInterval = 0, stationCount = -1;
		long timestamp = -1L;
		double utilization = Double.NaN;
		boolean privacy = false, rsn = false, wpa = false, ht = false, vht = false, he = false;
		boolean wpsSeen = false, wpsLocked = false;
		String rsnAuthentication = "";
		boolean ht40 = false, htShortGi20 = false, htShortGi40 = false;
		boolean vht160 = false, vht8080 = false, vhtShortGi80 = false, vhtShortGi160 = false;
		double htHighest = 0d, vhtHighest = 0d;
		Set<String> rates = new LinkedHashSet<String>();
		List<String> ranges = new ArrayList<String>();
		if (type == 0 && (subtype == 8 || subtype == 5) && psdu.length >= 36) {
			timestamp = u64(psdu, 24);
			beaconInterval = u16(psdu, 32);
			privacy = (u16(psdu, 34) & 0x10) != 0;
		}
		if (ieStart >= 0) {
			int end = fcsValid ? psdu.length - 4 : psdu.length;
			for (int at = ieStart; at + 2 <= end;) {
				int id = psdu[at] & 0xff, size = psdu[at + 1] & 0xff, data = at + 2;
				if (data + size > end) break;
				if (id == 0) ssid = text(psdu, data, size);
				else if (id == 1 || id == 50) appendRates(rates, psdu, data, size);
				else if (id == 3 && size >= 1) channel = psdu[data] & 0xff;
				else if (id == 7 && size >= 2) {
					String advertisedCountry = text(psdu, data, 2).toUpperCase(Locale.US);
					if (advertisedCountry.matches("[A-Z]{2}")) country = advertisedCountry;
					if (size > 3) appendCountryRanges(ranges, psdu, data + 3, size - 3);
				} else if (id == 11 && size >= 5) {
					stationCount = u16(psdu, data);
					utilization = (psdu[data + 2] & 0xff) * 100d / 255d;
				} else if (id == 36) appendSupportedRanges(ranges, psdu, data, size);
				else if (id == 45 && size >= 3) {
					ht = true; int cap = u16(psdu, data); ht40 = (cap & 0x0002) != 0;
					htShortGi20 = (cap & 0x0020) != 0; htShortGi40 = (cap & 0x0040) != 0;
					if (size >= 13) htHighest = htMcsMaximum(psdu, data + 3, Math.min(10, size - 3),
							ht40, ht40 ? htShortGi40 : htShortGi20);
					if (size >= 15) htHighest = Math.max(htHighest, u16(psdu, data + 13) & 0x03ff);
				} else if (id == 48) {
					rsn = true; rsnAuthentication = rsnAuthentication(psdu, data, size);
				}
				else if (id == 61 && size >= 2) {
					ht = true; int info = psdu[data + 1] & 0xff;
					currentWidth = (info & 0x04) != 0 && (info & 0x03) != 0 ? "40 MHz" : "20 MHz";
				} else if (id == 191 && size >= 8) {
					vht = true; int cap = u32(psdu, data); int widths = cap >>> 2 & 3;
					vht160 = widths == 1 || widths == 2; vht8080 = widths == 2;
					vhtShortGi80 = (cap & 0x20) != 0; vhtShortGi160 = (cap & 0x40) != 0;
					vhtHighest = vhtMcsMaximum(u16(psdu, data + 4), vht160,
							vht160 ? vhtShortGi160 : vhtShortGi80);
					vhtHighest = Math.max(vhtHighest, u16(psdu, data + 6) & 0x1fff);
				} else if (id == 192 && size >= 1) {
					vht = true; int width = psdu[data] & 0xff;
					if (width == 1) currentWidth = "80 MHz";
					else if (width == 2) currentWidth = "160 MHz";
					else if (width == 3) currentWidth = "80+80 MHz";
					else if ("--".equals(currentWidth)) currentWidth = "20/40 MHz";
				} else if (id == 221 && size >= 4
						&& psdu[data] == 0 && psdu[data + 1] == 0x50
						&& psdu[data + 2] == (byte)0xf2) {
					int vendorType = psdu[data + 3] & 0xff;
					if (vendorType == 1) wpa = true;
					else if (vendorType == 4) {
						WpsInfo info = parseWps(psdu, data + 4, size - 4);
						wpsSeen = true; wpsLocked |= info.locked;
						if (!"--".equals(info.manufacturer)) manufacturer = info.manufacturer;
						if (!"--".equals(info.modelName)) modelName = info.modelName;
						if (!"--".equals(info.modelNumber)) modelNumber = info.modelNumber;
						if (!"--".equals(info.deviceName)) deviceName = info.deviceName;
					}
				} else if (id == 255 && size >= 1) {
					int extension = psdu[data] & 0xff;
					if (extension == 35 || extension == 36) he = true;
				}
				at = data + size;
			}
		}
		if (channel <= 0) channel = channelFromFrequency(centerFreqHz);
		String standards = standards(centerFreqHz, rates, ht, vht, he);
		String maxPhy = maxAdvertisedPhy(rates, ht, htHighest, vht, vhtHighest, he);
		String capability = vht8080 ? "20/40/80/160/80+80 MHz"
				: vht160 ? "20/40/80/160 MHz" : vht ? "20/40/80 MHz"
				: ht40 ? "20/40 MHz" : "20 MHz";
		if ("--".equals(currentWidth) && !"--".equals(standards)) currentWidth = "20 MHz";
		security = rsn ? (rsnAuthentication.length() == 0 ? "WPA2/RSN" : rsnAuthentication)
				: wpa ? "WPA" : privacy ? "WEP/privacy" : "Open";
		if (ranges.size() > 0) channelRange = join(ranges, ", ");
		if (wpsSeen) wps = wpsLocked ? "Advertised (locked)" : "Advertised";
		return new WifiMacFrame(fcsValid, type, subtype, psdu.length, frameClass, frameType,
				ssid.length() == 0 ? (type == 0 && subtype == 4 ? "<any network>" : "<hidden>") : ssid,
				bssid, source, destination, transmitter, channel, beaconInterval, stationCount, timestamp,
				security, join(rates, " "), standards,
				maxPhy, currentWidth, capability, wps, country, channelRange, manufacturer,
				modelName, modelNumber, deviceName, utilization);
	}

	private static int informationElementStart(int type, int subtype, int length) {
		if (type != 0) return -1;
		int start = subtype == 8 || subtype == 5 ? 36 : subtype == 4 ? 24
				: subtype == 0 ? 28 : subtype == 2 ? 34 : subtype == 1 || subtype == 3 ? 30 : -1;
		return start <= length ? start : -1;
	}

	private static String frameType(int type, int subtype) {
		if (type == 0) {
			String[] names = {"Association request","Association response","Reassociation request",
					"Reassociation response","Probe request","Probe response","Timing advertisement",
					"Reserved management","Beacon","ATIM","Disassociation","Authentication",
					"Deauthentication","Action","Action no ACK","Management extension"};
			return names[subtype & 15];
		}
		if (type == 1) {
			String[] names = {"Reserved control","Reserved control","Trigger","TACK","Beamforming report poll",
					"VHT/HE NDP announcement","Control frame extension","Control wrapper","Block ACK request",
					"Block ACK","PS-Poll","RTS","CTS","ACK","CF-End","CF-End + CF-ACK"};
			return names[subtype & 15];
		}
		if (type == 2) {
			String[] names = {"Data","Data + CF-ACK","Data + CF-Poll","Data + CF-ACK + CF-Poll",
					"Null data","CF-ACK","CF-Poll","CF-ACK + CF-Poll","QoS data","QoS data + CF-ACK",
					"QoS data + CF-Poll","QoS data + CF-ACK + CF-Poll","QoS null","Reserved data",
					"QoS CF-Poll","QoS CF-ACK + CF-Poll"};
			return names[subtype & 15];
		}
		return "Extension";
	}

	private static String standards(long frequencyHz, Set<String> rates, boolean ht, boolean vht, boolean he) {
		Set<String> values = new LinkedHashSet<String>();
		boolean dsss = hasAnyRate(rates, 1d, 2d, 5.5d, 11d);
		boolean ofdm = hasAnyRate(rates, 6d, 9d, 12d, 18d, 24d, 36d, 48d, 54d);
		if (frequencyHz >= 2_400_000_000L && frequencyHz < 2_500_000_000L) {
			if (dsss) values.add("b"); if (ofdm) values.add("g");
		} else if (frequencyHz >= 4_900_000_000L && frequencyHz < 5_925_000_000L && ofdm) values.add("a");
		if (ht) values.add("n"); if (vht) values.add("ac"); if (he) values.add("ax");
		return values.size() == 0 ? "--" : "802.11" + join(values, "/");
	}

	private static String maxAdvertisedPhy(Set<String> rates, boolean ht, double htHighest,
			boolean vht, double vhtHighest, boolean he) {
		double highest = Math.max(htHighest, vhtHighest);
		if (highest > 0d && highest < 8191d)
			return (he ? ">= " : "") + formatRate(highest) + " Mb/s" + (he ? " (HE)" : "");
		if (he) return "HE advertised";
		if (vht) return "VHT advertised";
		if (ht) return "HT advertised";
		double legacy = 0;
		for (String rate : rates) try { legacy = Math.max(legacy, Double.parseDouble(rate)); }
		catch (NumberFormatException ignored) { }
		return legacy <= 0 ? "--" : formatRate(legacy) + " Mb/s";
	}

	private static double htMcsMaximum(byte[] data, int at, int size, boolean width40, boolean shortGi) {
		int highestMcs = -1;
		for (int octet = 0; octet < Math.min(size, 4); octet++) for (int bit = 0; bit < 8; bit++)
			if ((data[at + octet] & 1 << bit) != 0) highestMcs = octet * 8 + bit;
		if (highestMcs < 0 || highestMcs > 31) return 0d;
		int streams = highestMcs / 8 + 1, mcs = highestMcs & 7;
		double[] longGi = width40
				? new double[]{13.5,27,40.5,54,81,108,121.5,135}
				: new double[]{6.5,13,19.5,26,39,52,58.5,65};
		double rate = longGi[mcs] * streams;
		return shortGi ? rate / .9d : rate;
	}

	private static double vhtMcsMaximum(int rxMcsMap, boolean width160, boolean shortGi) {
		double maximum = 0d;
		for (int nss = 0; nss < 8; nss++) {
			int code = rxMcsMap >>> (nss * 2) & 3;
			if (code == 3) continue;
			int maxMcs = code == 0 ? 7 : code == 1 ? 8 : 9;
			double[] longGi = width160
					? new double[]{585,702,780}
					: new double[]{292.5,351,390};
			maximum = Math.max(maximum, longGi[maxMcs - 7] * (nss + 1));
		}
		return shortGi ? maximum / .9d : maximum;
	}

	private static void appendRates(Set<String> rates, byte[] data, int at, int size) {
		for (int n = 0; n < size; n++) rates.add(formatRate((data[at + n] & 0x7f) * .5d));
	}

	private static boolean hasAnyRate(Set<String> rates, double... expected) {
		for (String value : rates) try {
			double rate = Double.parseDouble(value);
			for (double candidate : expected) if (Math.abs(rate - candidate) < .01d) return true;
		} catch (NumberFormatException ignored) { }
		return false;
	}

	private static String formatRate(double rate) {
		return rate == Math.rint(rate) ? Integer.toString((int)rate) : String.format(Locale.US, "%.1f", rate);
	}

	private static void appendCountryRanges(List<String> ranges, byte[] data, int at, int size) {
		for (int n = 0; n + 2 < size; n += 3) addRange(ranges, data[at + n] & 0xff, data[at + n + 1] & 0xff);
	}

	private static void appendSupportedRanges(List<String> ranges, byte[] data, int at, int size) {
		for (int n = 0; n + 1 < size; n += 2) addRange(ranges, data[at + n] & 0xff, data[at + n + 1] & 0xff);
	}

	private static void addRange(List<String> ranges, int first, int count) {
		if (first <= 0 || count <= 0) return;
		String value = count == 1 ? Integer.toString(first) : first + "-" + (first + count - 1);
		if (!ranges.contains(value)) ranges.add(value);
	}

	private static WpsInfo parseWps(byte[] data, int at, int size) {
		WpsInfo info = new WpsInfo(); int end = Math.min(data.length, at + size);
		while (at + 4 <= end) {
			int type = (data[at] & 0xff) << 8 | data[at + 1] & 0xff;
			int length = (data[at + 2] & 0xff) << 8 | data[at + 3] & 0xff; at += 4;
			if (at + length > end) break;
			String value = text(data, at, length);
			if (type == 0x1021) info.manufacturer = value;
			else if (type == 0x1023) info.modelName = value;
			else if (type == 0x1024) info.modelNumber = value;
			else if (type == 0x1011) info.deviceName = value;
			else if (type == 0x1057 && length >= 1) info.locked = data[at] != 0;
			at += length;
		}
		return info;
	}

	private static String rsnAuthentication(byte[] data, int at, int size) {
		int end = Math.min(data.length, at + size), cursor = at + 2 + 4;
		if (cursor + 2 > end) return "";
		int pairwise = u16(data, cursor); cursor += 2 + pairwise * 4;
		if (cursor + 2 > end) return "";
		int count = u16(data, cursor); cursor += 2;
		boolean psk = false, sae = false, enterprise = false;
		for (int n = 0; n < count && cursor + 4 <= end; n++, cursor += 4) {
			int type = data[cursor + 3] & 0xff;
			psk |= type == 2; sae |= type == 8; enterprise |= type == 1;
		}
		return sae ? (psk ? "WPA2/WPA3-PSK" : "WPA3-SAE")
				: psk ? "WPA2-PSK" : enterprise ? "WPA2-Enterprise" : "WPA2/RSN";
	}

	private static final class WpsInfo {
		String manufacturer = "--", modelName = "--", modelNumber = "--", deviceName = "--";
		boolean locked;
	}

	static boolean validFcs(byte[] data) {
		if (data == null || data.length < 4) return false;
		int length = data.length - 4;
		long received = data[length] & 0xffL | (data[length + 1] & 0xffL) << 8
				| (data[length + 2] & 0xffL) << 16 | (data[length + 3] & 0xffL) << 24;
		return crc32(data, 0, length) == received;
	}

	private static long crc32(byte[] data, int offset, int length) {
		long crc = 0xffffffffL;
		for (int n = 0; n < length; n++) {
			crc ^= data[offset + n] & 0xff;
			for (int bit = 0; bit < 8; bit++) crc = crc >>> 1 ^ ((crc & 1) != 0 ? 0xedb88320L : 0);
		}
		return (crc ^ 0xffffffffL) & 0xffffffffL;
	}

	private static int channelFromFrequency(long hz) {
		long mhz = Math.round(hz / 1_000_000d);
		if (mhz == 2484) return 14;
		if (mhz >= 2412 && mhz <= 2472) return (int)(1 + (mhz - 2412) / 5);
		if (mhz == 5935) return 2;
		if (mhz >= 5955 && mhz <= 7115) return (int)((mhz - 5950) / 5);
		if (mhz >= 5000 && mhz <= 5895) return (int)((mhz - 5000) / 5);
		return 0;
	}

	private static String mac(byte[] data, int at) {
		if (at < 0 || at + 6 > data.length) return "--";
		return String.format(Locale.US, "%02x:%02x:%02x:%02x:%02x:%02x",
				data[at] & 0xff, data[at + 1] & 0xff, data[at + 2] & 0xff,
				data[at + 3] & 0xff, data[at + 4] & 0xff, data[at + 5] & 0xff);
	}

	private static int u16(byte[] data, int at) {
		return at + 2 <= data.length ? (data[at] & 0xff) | (data[at + 1] & 0xff) << 8 : 0;
	}

	private static int u32(byte[] data, int at) {
		return at + 4 <= data.length ? (data[at] & 0xff) | (data[at + 1] & 0xff) << 8
				| (data[at + 2] & 0xff) << 16 | data[at + 3] << 24 : 0;
	}

	private static long u64(byte[] data, int at) {
		if (at + 8 > data.length) return -1L;
		long value = 0; for (int n = 0; n < 8; n++) value |= (data[at + n] & 0xffL) << (8 * n);
		return value;
	}

	private static String text(byte[] data, int at, int length) {
		String value = new String(data, at, Math.max(0, Math.min(length, data.length - at)), StandardCharsets.UTF_8);
		return value.replaceAll("[\\p{Cntrl}&&[^\\t]]", "").trim();
	}

	private static String join(Iterable<String> values, String separator) {
		StringBuilder result = new StringBuilder();
		for (String value : values) { if (result.length() > 0) result.append(separator); result.append(value); }
		return result.length() == 0 ? "--" : result.toString();
	}
}
