package jspectrumanalyzer.iq;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Decodes Paging Type 1 while retaining only session-scoped anonymous UE labels. */
final class UmtsPagingDecoder {
	private static final String[] CAUSES = {
		"Conversational call", "Streaming call", "Interactive call", "Background call",
		"High-priority signalling", "Low-priority signalling", "Unknown", "Spare"
	};
	private final byte[] sessionKey = createSessionKey();
	private final Map<String, Integer> clients = new LinkedHashMap<String, Integer>();
	private final List<Event> events = new ArrayList<Event>();
	private long sequence;

	int accept(int[] transportBlock, int sfn) {
		if (transportBlock == null || transportBlock.length < 8) return 0;
		Bits bits = new Bits(transportBlock, 1); // PCCH message-type discriminator precedes Paging Type 1.
		try {
			boolean recordsPresent = bits.flag();
			bits.flag(); // BCCH modification information.
			bits.flag(); // Later non-critical extensions.
			if (!recordsPresent) return 0;
			int count = bits.integer(1, 8), added = 0;
			for (int index = 0; index < count; index++) {
				if (bits.flag()) {
					boolean connectedModeInfo = bits.flag();
					byte[] identity = bits.bytes(32);
					added += add("U-RNTI", "UTRAN", connectedModeInfo ? "Connected-mode page" : "", "", "", identity, sfn);
					if (connectedModeInfo) bits.skip(3 + 1 + 2);
				} else {
					int cause = bits.read(3);
					String domain = bits.flag() ? "PS" : "CS";
					int identityChoice = bits.read(3);
					String type, mcc = "", mnc = "";
					byte[] identity;
					switch (identityChoice) {
					case 0:
						int digits = bits.integer(6, 21);
						StringBuilder imsi = new StringBuilder(digits);
						for (int digit = 0; digit < digits; digit++) {
							int value = bits.read(4);
							if (value > 9) throw new DecodeException();
							imsi.append((char)('0' + value));
						}
						type = "IMSI";
						identity = imsi.toString().getBytes(StandardCharsets.US_ASCII);
						if (imsi.length() >= 5) {
							mcc = imsi.substring(0, 3);
							mnc = imsi.substring(3, 5);
							if (imsi.length() >= 6 && PlmnDatabase.lookup(mcc, mnc) == null
									&& PlmnDatabase.lookup(mcc, imsi.substring(3, 6)) != null)
								mnc = imsi.substring(3, 6);
						}
						break;
					case 1: type = "TMSI"; identity = bits.bytes(32); break;
					case 2: type = "P-TMSI"; identity = bits.bytes(32); break;
					case 3: type = "IMSI (DS-41)"; identity = bits.bytes(bits.integer(5, 7) * 8); break;
					case 4: type = "TMSI (DS-41)"; identity = bits.bytes(bits.integer(2, 17) * 8); break;
					default: type = "Spare identity"; identity = new byte[] { (byte)identityChoice }; break;
					}
					added += add(type, domain, CAUSES[cause], mcc, mnc, identity, sfn);
				}
			}
			return added;
		} catch (DecodeException error) {
			return 0;
		}
	}

	Snapshot snapshot() {
		return new Snapshot(Collections.unmodifiableList(new ArrayList<Event>(events)), clients.size());
	}

	private int add(String type, String domain, String cause, String mcc, String mnc,
			byte[] identity, int sfn) {
		String token = token(type, identity);
		Integer client = clients.get(token);
		if (client == null) {
			client = clients.size() + 1;
			clients.put(token, client);
		}
		PlmnDatabase.Entry network = mcc.length() == 0 ? null : PlmnDatabase.lookup(mcc, mnc);
		events.add(new Event(++sequence, System.currentTimeMillis(), sfn, client, type, domain, cause,
				mcc, mnc, network == null ? "" : network.country,
				network == null ? "" : network.networkName()));
		while (events.size() > 5000) events.remove(0);
		return 1;
	}

	private String token(String type, byte[] identity) {
		try {
			javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
			mac.init(new javax.crypto.spec.SecretKeySpec(sessionKey, "HmacSHA256"));
			mac.update(type.getBytes(StandardCharsets.US_ASCII));
			mac.update((byte)0);
			byte[] digest = mac.doFinal(identity);
			StringBuilder value = new StringBuilder(32);
			for (int index = 0; index < 16; index++)
				value.append(String.format(java.util.Locale.ROOT, "%02x", digest[index] & 0xff));
			return value.toString();
		} catch (GeneralSecurityException error) {
			throw new IllegalStateException("HMAC-SHA256 is unavailable", error);
		}
	}

	private static byte[] createSessionKey() {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return key;
	}

	static final class Snapshot {
		static final Snapshot EMPTY = new Snapshot(Collections.<Event>emptyList(), 0);
		final List<Event> events;
		final int clients;
		Snapshot(List<Event> events, int clients) { this.events = events; this.clients = clients; }
	}

	static final class Event {
		final long sequence, timestampMillis;
		final int sfn, clientNumber;
		final String identityType, domain, cause, homeMcc, homeMnc, country, operator;
		Event(long sequence, long timestampMillis, int sfn, int clientNumber, String identityType,
				String domain, String cause, String homeMcc, String homeMnc, String country, String operator) {
			this.sequence = sequence; this.timestampMillis = timestampMillis; this.sfn = sfn;
			this.clientNumber = clientNumber; this.identityType = identityType; this.domain = domain;
			this.cause = cause; this.homeMcc = homeMcc; this.homeMnc = homeMnc;
			this.country = country; this.operator = operator;
		}
	}

	private static final class Bits {
		final int[] data;
		int at;
		Bits(int[] data, int at) { this.data = data; this.at = at; }
		boolean flag() throws DecodeException { return read(1) != 0; }
		int integer(int minimum, int maximum) throws DecodeException {
			int values = maximum - minimum + 1, width = 0;
			for (int range = values - 1; range > 0; range >>>= 1) width++;
			int value = read(width);
			if (value >= values) throw new DecodeException();
			return minimum + value;
		}
		int read(int count) throws DecodeException {
			if (count < 0 || count > 30 || at + count > data.length) throw new DecodeException();
			int value = 0;
			while (count-- > 0) value = value << 1 | data[at++];
			return value;
		}
		byte[] bytes(int count) throws DecodeException {
			if (count < 0 || at + count > data.length) throw new DecodeException();
			byte[] value = new byte[(count + 7) / 8];
			for (int bit = 0; bit < count; bit++) value[bit / 8] |= data[at++] << (7 - bit % 8);
			return value;
		}
		void skip(int count) throws DecodeException {
			if (count < 0 || at + count > data.length) throw new DecodeException();
			at += count;
		}
	}

	private static final class DecodeException extends Exception {
		private static final long serialVersionUID = 1L;
	}
}
