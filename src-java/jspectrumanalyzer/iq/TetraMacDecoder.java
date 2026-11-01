package jspectrumanalyzer.iq;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Minimal clear downlink MAC/LLC/MLE/CMCE path with MAC fragmentation. */
final class TetraMacDecoder {
	private static final int MAC_RESOURCE = 0;
	private static final int MAC_FRAG_END = 1;
	private static final int MAX_SDS_EVENTS = 256;

	private final Fragment[] fragments = new Fragment[4];
	private final TetraCellInfo cellInfo = new TetraCellInfo();
	private final ArrayDeque<SdsMessage> pending = new ArrayDeque<SdsMessage>();
	private final ArrayDeque<TetraMessage> pendingEvents = new ArrayDeque<TetraMessage>();
	private long syncSampleIndex;
	private int syncTimeslot;
	private double sampleRateHz;
	private boolean synchronizedTime;
	private long clearResources, encryptedResources, fragmentStarts, fragmentEnds;
	private long sdsMessages;
	private SdsMessage latestSds;

	void synchronize(TetraDecoderCore.Burst burst, TetraBschDecoder.SystemInfo info,
			double processingRateHz) {
		if (burst == null || info == null || processingRateHz <= 0d) return;
		syncSampleIndex = burst.sampleIndex;
		syncTimeslot = info.timeslot;
		sampleRateHz = processingRateHz;
		synchronizedTime = true;
	}

	List<SdsMessage> accept(TetraDecoderCore.Burst burst,
			TetraSchfDecoder.DecodeAttempt schf) {
		if (burst == null || schf == null || !schf.crcOk) return Collections.emptyList();
		int timeslot = timeslot(burst.sampleIndex);
		List<TetraMessage> events = new ArrayList<TetraMessage>();
		List<SdsMessage> found = new ArrayList<SdsMessage>();
		parseMacBlock(schf.payloadBits, timeslot, burst.timestampMillis, events);
		for (TetraMessage event : events) {
			pendingEvents.addLast(event);
			while (pendingEvents.size() > MAX_SDS_EVENTS * 4) pendingEvents.removeFirst();
			if (event instanceof SdsMessage) {
				SdsMessage message = (SdsMessage)event;
				found.add(message);
				sdsMessages++;
				latestSds = message;
				pending.addLast(message);
				while (pending.size() > MAX_SDS_EVENTS) pending.removeFirst();
			}
		}
		return found;
	}

	List<SdsMessage> takeMessages() {
		if (pending.isEmpty()) return Collections.emptyList();
		List<SdsMessage> result = new ArrayList<SdsMessage>(pending);
		pending.clear();
		return result;
	}

	List<TetraMessage> takeEvents() {
		if (pendingEvents.isEmpty()) return Collections.emptyList();
		List<TetraMessage> result = new ArrayList<TetraMessage>(pendingEvents);
		pendingEvents.clear();
		return result;
	}

	void reset() {
		for (int n = 0; n < fragments.length; n++) fragments[n] = null;
		pending.clear();
		pendingEvents.clear();
		cellInfo.reset();
		synchronizedTime = false;
		clearResources = encryptedResources = fragmentStarts = fragmentEnds = 0L;
		sdsMessages = 0L;
		latestSds = null;
	}

	long clearResources() { return clearResources; }
	long encryptedResources() { return encryptedResources; }
	long fragmentStarts() { return fragmentStarts; }
	long fragmentEnds() { return fragmentEnds; }
	long sdsMessages() { return sdsMessages; }
	SdsMessage latestSds() { return latestSds; }
	TetraCellInfo.Snapshot cellInfo(long tunedHz) { return cellInfo.snapshot(tunedHz); }
	void resetCellInfo() { cellInfo.reset(); }

	private int timeslot(long sampleIndex) {
		if (!synchronizedTime) return -1;
		double slotSamples = sampleRateHz * TetraDecoderCore.BURST_SYMBOLS
				/ TetraDecoderCore.SYMBOL_RATE_HZ;
		long delta = Math.round((sampleIndex - syncSampleIndex) / slotSamples);
		return Math.floorMod(syncTimeslot - 1 + (int)Math.floorMod(delta, 4L), 4) + 1;
	}

	private void parseMacBlock(int[] bits, int timeslot, long timestamp,
			List<TetraMessage> found) {
		int offset = 0;
		while (offset + 16 <= bits.length) {
			int type = read(bits, offset, 2);
			if (type == MAC_RESOURCE) {
				Resource resource = parseResource(bits, offset);
				if (resource == null) return;
				/* Address type zero is MAC-RESOURCE padding/null data.  TTT stops the
				 * block here; parsing the following fill delimiter as another PDU can
				 * otherwise manufacture an all-zero SYSINFO and a false cell role. */
				if (resource.addressType == 0) return;
				if (resource.encryptionMode == 0) clearResources++;
				else encryptedResources++;
				int pduEnd = resource.lengthOctets > 0
						? Math.min(bits.length, offset + resource.lengthOctets * 8) : bits.length;
				int dataEnd = resource.fillBits ? stripFill(bits, resource.dataOffset, pduEnd)
						: pduEnd;
				if (resource.encryptionMode != 0 && pduEnd > resource.dataOffset)
					found.add(new TetraMessage(timestamp,
							"ENCRYPTED-MAC-" + resource.encryptionMode, resource.address,
							-1, -1, -1, slice(bits, resource.dataOffset, pduEnd)));
				if (resource.encryptionMode == 0 && resource.addressType != 0
						&& dataEnd > resource.dataOffset) {
					if (resource.startFragment) {
						fragmentStarts++;
						if (timeslot >= 1) fragments[timeslot - 1] = new Fragment(
								resource.address, slice(bits, resource.dataOffset, dataEnd), timestamp,
								resource.encryptionMode, resource.addressType);
					} else {
						TetraMessage message = parseTmSdu(slice(bits, resource.dataOffset, dataEnd),
								resource.address, timestamp, timeslot, resource.encryptionMode,
								resource.addressType);
						if (message != null) found.add(message);
					}
				}
				if (resource.startFragment || resource.lengthOctets <= 0
						|| offset + resource.lengthOctets * 8 >= bits.length) return;
				offset += resource.lengthOctets * 8;
			} else if (type == MAC_FRAG_END) {
				parseFragment(bits, offset, timeslot, timestamp, found);
				return;
			} else if (type == 2) { // MAC-BROADCAST
				int[] broadcast = slice(bits, offset, bits.length);
				cellInfo.acceptSysInfo(broadcast, timestamp);
				int subtype = read(broadcast, 2, 2);
				found.add(new TetraMessage(timestamp,
						subtype == 0 ? "MAC-SYSINFO" : subtype == 1
								? "MAC-ACCESS-DEFINE" : "MAC-BROADCAST-" + subtype,
						-1, -1, -1, -1, broadcast));
				return;
			} else {
				return;
			}
		}
	}

	private Resource parseResource(int[] bits, int start) {
		Cursor cursor = new Cursor(bits, start + 2);
		Resource result = new Resource();
		result.fillBits = cursor.take(1) == 1;
		cursor.skip(1); // position of grant
		result.encryptionMode = cursor.take(2);
		cursor.skip(1); // random access flag
		int length = cursor.take(6);
		result.startFragment = length == 0x3f;
		result.lengthOctets = length >= 1 && length <= 0x3a ? length : -1;
		result.addressType = cursor.take(3);
		switch (result.addressType) {
		case 1: case 3: case 4:
			result.address = cursor.take(24); break;
		case 2:
			result.address = cursor.take(10); break;
		case 5: case 7:
			result.address = cursor.take(24); cursor.skip(10); break;
		case 6:
			result.address = cursor.take(24); cursor.skip(6); break;
		case 0:
			return result;
		default:
			return null;
		}
		if (!cursor.ok()) return null;
		if (cursor.take(1) == 1) cursor.skip(4); // power control
		if (cursor.take(1) == 1) cursor.skip(8); // basic slot granting
		if (cursor.take(1) == 1 && !skipChannelAllocation(cursor)) return null;
		if (!cursor.ok()) return null;
		result.dataOffset = cursor.position;
		return result;
	}

	private static boolean skipChannelAllocation(Cursor cursor) {
		cursor.skip(2); // allocation type
		cursor.skip(4); // timeslot
		int ulDl = cursor.take(2);
		cursor.skip(2); // CLCH permission + cell change flag
		cursor.skip(12); // carrier
		if (cursor.take(1) == 1) cursor.skip(4 + 2 + 3 + 1);
		int monitoring = cursor.take(2);
		if (monitoring == 0) cursor.skip(2);
		/* Augmented allocation (uplink/downlink assignment zero) has a large,
		 * version-dependent tail. Do not guess where its SDU begins. */
		return cursor.ok() && ulDl != 0;
	}

	private void parseFragment(int[] bits, int start, int timeslot, long timestamp,
			List<TetraMessage> found) {
		if (timeslot < 1) return;
		Fragment fragment = fragments[timeslot - 1];
		int subtype = read(bits, start + 2, 1);
		boolean fill = read(bits, start + 3, 1) == 1;
		if (subtype == 0) {
			if (fragment == null) return;
			int end = fill ? stripFill(bits, start + 4, bits.length) : bits.length;
			fragment.append(bits, start + 4, end);
			return;
		}

		fragmentEnds++;
		Cursor cursor = new Cursor(bits, start + 4);
		cursor.skip(1); // position of grant
		int lengthOctets = cursor.take(6);
		if (cursor.take(1) == 1) cursor.skip(8);
		if (cursor.take(1) == 1 && !skipChannelAllocation(cursor)) {
			fragments[timeslot - 1] = null;
			return;
		}
		if (!cursor.ok() || fragment == null) return;
		int pduEnd = lengthOctets > 0
				? Math.min(bits.length, start + lengthOctets * 8) : bits.length;
		int dataEnd = fill ? stripFill(bits, cursor.position, pduEnd) : pduEnd;
		fragment.append(bits, cursor.position, dataEnd);
		TetraMessage message = parseTmSdu(fragment.bits(), fragment.destination, timestamp,
				timeslot, fragment.encryptionMode, fragment.addressType);
		fragments[timeslot - 1] = null;
		if (message != null) found.add(message);
	}

	private TetraMessage parseTmSdu(int[] bits, int destination, long timestamp,
			int timeslot, int macEncryptionMode, int macAddressType) {
		Cursor cursor = new Cursor(bits, 0);
		int llc = cursor.take(4);
		boolean fcs = llc >= 4 && llc <= 7;
		switch (llc) {
		case 0: case 4: cursor.skip(2); break; // BL-ADATA
		case 1: case 5: cursor.skip(1); break; // BL-DATA
		case 2: case 6: break;                 // BL-UDATA
		default:
			return new TetraMessage(timestamp, "LLC-" + llc, destination,
					-1, -1, -1, bits);
		}
		int usableEnd = bits.length - (fcs ? 32 : 0);
		if (!cursor.ok() || cursor.position + 8 > usableEnd) return null;
		int pduStart = cursor.position;
		int discriminator = cursor.take(3);
		if (discriminator == 5) { // MLE, including D-NWRK-BROADCAST
			int primitive = cursor.take(3);
			if (primitive == 2 && cursor.ok())
				cellInfo.acceptNeighbourBroadcast(bits, cursor.position, usableEnd, timestamp);
			return new TetraMessage(timestamp, mlePrimitiveName(primitive), destination,
					-1, -1, -1, slice(bits, pduStart, usableEnd));
		}
		if (discriminator != 2)
			return new TetraMessage(timestamp, mlePduName(discriminator), destination,
					-1, -1, -1, slice(bits, pduStart, usableEnd));
		int primitive = cursor.take(5);
		if (primitive != 15) {
			int[] pdu = slice(bits, pduStart, usableEnd);
			if (isCallPrimitive(primitive) && cursor.position + 14 <= usableEnd) {
				int callIdentifier = cursor.take(14);
				CallFields fields = parseCallFields(primitive, cursor, usableEnd);
				return new CallEvent(timestamp, cmcePrimitiveName(primitive), destination,
						fields.partySsi, pdu, callIdentifier, fields.basicService,
						fields.encryptionControl, fields.transmissionGrant, fields.simplex,
						fields.priority, timeslot, macEncryptionMode, macAddressType);
			}
			return new TetraMessage(timestamp, cmcePrimitiveName(primitive), destination,
					-1, -1, -1, pdu);
		}

		int partyType = cursor.take(2);
		int source = -1;
		if (partyType == 0) cursor.skip(8);
		else if (partyType == 1) source = cursor.take(24);
		else if (partyType == 2) { source = cursor.take(24); cursor.skip(24); }
		int shortDataType = cursor.take(2);
		int length = shortDataType == 0 ? 16 : shortDataType == 1 ? 32
				: shortDataType == 2 ? 64 : cursor.take(11);
		if (!cursor.ok() || length < 8 || cursor.position + length > usableEnd)
			return new TetraMessage(timestamp, cmcePrimitiveName(primitive), destination,
					source, -1, shortDataType + 1, slice(bits, pduStart, usableEnd));
		int[] userData = slice(bits, cursor.position, cursor.position + length);
		int protocol = read(userData, 0, 8);
		return new SdsMessage(timestamp, destination, source, shortDataType + 1,
				length, protocol, userData);
	}

	private static boolean isCallPrimitive(int primitive) {
		return primitive >= 0 && primitive <= 7 || primitive >= 9 && primitive <= 14;
	}

	/** Decode fixed CMCE call fields and the useful optional party identities. */
	private static CallFields parseCallFields(int primitive, Cursor cursor, int usableEnd) {
		CallFields fields = new CallFields();
		switch (primitive) {
		case 2: // D-CONNECT
			if (!has(cursor, usableEnd, 10)) return fields;
			cursor.skip(4); // call time-out
			cursor.skip(1); // hook method
			fields.simplex = cursor.take(1);
			fields.transmissionGrant = cursor.take(2);
			cursor.skip(2); // request permission + call ownership
			break;
		case 3: // D-CONNECT-ACK
			if (!has(cursor, usableEnd, 7)) return fields;
			cursor.skip(4);
			fields.transmissionGrant = cursor.take(2);
			cursor.skip(1);
			break;
		case 7: // D-SETUP
			if (!has(cursor, usableEnd, 21)) return fields;
			cursor.skip(4); // call time-out
			cursor.skip(1); // hook method
			fields.simplex = cursor.take(1);
			fields.basicService = cursor.take(8);
			fields.encryptionControl = fields.basicService >> 4 & 1;
			fields.transmissionGrant = cursor.take(2);
			cursor.skip(1); // transmission request permission
			fields.priority = cursor.take(4);
			parseSetupOptionals(cursor, usableEnd, fields);
			break;
		case 11: // D-TX-GRANTED
		case 13: // D-TX-INTERRUPT
			if (!has(cursor, usableEnd, 5)) return fields;
			fields.transmissionGrant = cursor.take(2);
			cursor.skip(1); // transmission request permission
			fields.encryptionControl = cursor.take(1);
			cursor.skip(1); // reserved
			parseTxOptionals(cursor, usableEnd, fields);
			break;
		case 14: // D-CALL-RESTORE
			if (!has(cursor, usableEnd, 4)) return fields;
			fields.transmissionGrant = cursor.take(2);
			cursor.skip(2); // request permission + reset timer
			break;
		default:
			break;
		}
		return fields;
	}

	private static void parseSetupOptionals(Cursor cursor, int usableEnd, CallFields fields) {
		if (!has(cursor, usableEnd, 1) || cursor.take(1) == 0) return; // O-bit
		if (!skipOptional(cursor, usableEnd, 6)) return;  // notification indicator
		if (!skipOptional(cursor, usableEnd, 24)) return; // temporary address
		if (!has(cursor, usableEnd, 1) || cursor.take(1) == 0) return;
		parsePartyIdentity(cursor, usableEnd, fields);
	}

	private static void parseTxOptionals(Cursor cursor, int usableEnd, CallFields fields) {
		if (!has(cursor, usableEnd, 1) || cursor.take(1) == 0) return; // O-bit
		if (!skipOptional(cursor, usableEnd, 6)) return; // notification indicator
		if (!has(cursor, usableEnd, 1) || cursor.take(1) == 0) return;
		parsePartyIdentity(cursor, usableEnd, fields);
	}

	private static boolean skipOptional(Cursor cursor, int usableEnd, int length) {
		if (!has(cursor, usableEnd, 1)) return false;
		if (cursor.take(1) == 1) {
			if (!has(cursor, usableEnd, length)) return false;
			cursor.skip(length);
		}
		return true;
	}

	private static void parsePartyIdentity(Cursor cursor, int usableEnd, CallFields fields) {
		if (!has(cursor, usableEnd, 2)) return;
		int type = cursor.take(2);
		if ((type != 1 && type != 2) || !has(cursor, usableEnd, 24)) return;
		fields.partySsi = cursor.take(24);
		if (type == 2 && has(cursor, usableEnd, 24)) cursor.skip(24);
	}

	private static boolean has(Cursor cursor, int usableEnd, int length) {
		return cursor.ok() && length >= 0 && cursor.position + length <= usableEnd;
	}

	private static String cmcePrimitiveName(int primitive) {
		String[] names = { "D-ALERT", "D-CALL-PROCEEDING", "D-CONNECT",
				"D-CONNECT-ACK", "D-DISCONNECT", "D-INFO", "D-RELEASE", "D-SETUP",
				"D-STATUS", "D-TX-CEASED", "D-TX-CONTINUE", "D-TX-GRANTED",
				"D-TX-WAIT", "D-TX-INTERRUPT", "D-CALL-RESTORE", "D-SDS-DATA",
				"D-FACILITY" };
		if (primitive >= 0 && primitive < names.length) return names[primitive];
		return primitive == 31 ? "CMCE-NOT-SUPPORTED" : "CMCE-RESERVED-" + primitive;
	}

	private static String mlePrimitiveName(int primitive) {
		String[] names = { "D-NEW-CELL", "D-PREPARE", "D-NWRK-BROADCAST",
				"MLE-RESERVED-3", "D-RESTORE-ACK", "D-RESTORE-FAIL",
				"MLE-RESERVED-6", "MLE-RESERVED-7" };
		return primitive >= 0 && primitive < names.length ? names[primitive]
				: "MLE-PRIMITIVE-" + primitive;
	}

	private static String mlePduName(int discriminator) {
		String[] names = { "MLE-RESERVED", "MM", "CMCE", "MLE-RESERVED-3",
				"SNDCP", "MLE", "TETRA-MANAGEMENT", "TESTING" };
		return discriminator >= 0 && discriminator < names.length ? names[discriminator]
				: "MLE-PDU-" + discriminator;
	}

	private static int stripFill(int[] bits, int start, int end) {
		for (int index = end - 1; index >= start; index--)
			if (bits[index] == 1) return index;
		return end;
	}

	private static int[] slice(int[] source, int start, int end) {
		int safeStart = Math.max(0, Math.min(source.length, start));
		int safeEnd = Math.max(safeStart, Math.min(source.length, end));
		int[] result = new int[safeEnd - safeStart];
		System.arraycopy(source, safeStart, result, 0, result.length);
		return result;
	}

	private static int read(int[] bits, int start, int length) {
		if (start < 0 || length < 0 || start + length > bits.length) return -1;
		int value = 0;
		for (int n = 0; n < length; n++) value = (value << 1) | (bits[start + n] & 1);
		return value;
	}

	static class TetraMessage {
		final long timestampMillis;
		final String type;
		final int destination, source, protocol, userDataType, lengthBits;
		final int[] dataBits;

		TetraMessage(long timestampMillis, String type, int destination, int source,
				int protocol, int userDataType, int[] dataBits) {
			this.timestampMillis = timestampMillis;
			this.type = type;
			this.destination = destination;
			this.source = source;
			this.protocol = protocol;
			this.userDataType = userDataType;
			this.dataBits = dataBits == null ? new int[0] : dataBits;
			this.lengthBits = this.dataBits.length;
		}

		String dataHex() { return bitsHex(dataBits); }

		private static String bitsHex(int[] bits) {
			StringBuilder text = new StringBuilder((bits.length + 3) / 4);
			for (int offset = 0; offset < bits.length; offset += 4) {
				int value = 0;
				for (int n = 0; n < 4; n++) value = (value << 1)
						| (offset + n < bits.length ? bits[offset + n] : 0);
				text.append(Character.toUpperCase(Character.forDigit(value, 16)));
			}
			return text.toString();
		}
	}

	static final class SdsMessage extends TetraMessage {
		final int[] userDataBits;

		SdsMessage(long timestampMillis, int destination, int source, int userDataType,
				int lengthBits, int protocol, int[] userDataBits) {
			super(timestampMillis, "D-SDS-DATA", destination, source, protocol,
					userDataType, userDataBits);
			this.userDataBits = userDataBits;
			if (lengthBits != this.lengthBits)
				throw new IllegalArgumentException("SDS bit length does not match payload");
		}

		@Override public String toString() {
			String base = String.format(Locale.US,
					"D-SDS-DATA SSI %d Party_SSI %d UDT-%d Length %d PID %d DATA %s",
					destination, source, userDataType, lengthBits, protocol, dataHex());
			TetraPid209Decoder.Update update = TetraPid209Decoder.decode(this);
			return update == null ? base : base + "  " + update.summary();
		}
	}

	static final class CallEvent extends TetraMessage {
		final int callIdentifier;
		final int basicService;
		final int encryptionControl;
		final int transmissionGrant;
		final int simplex;
		final int priority;
		final int timeslot;
		final int macEncryptionMode;
		final int macAddressType;

		CallEvent(long timestampMillis, String type, int destination, int partySsi,
				int[] dataBits, int callIdentifier, int basicService, int encryptionControl,
				int transmissionGrant, int simplex, int priority, int timeslot,
				int macEncryptionMode, int macAddressType) {
			super(timestampMillis, type, destination, partySsi, -1, -1, dataBits);
			this.callIdentifier = callIdentifier;
			this.basicService = basicService;
			this.encryptionControl = encryptionControl;
			this.transmissionGrant = transmissionGrant;
			this.simplex = simplex;
			this.priority = priority;
			this.timeslot = timeslot;
			this.macEncryptionMode = macEncryptionMode;
			this.macAddressType = macAddressType;
		}

		boolean isKnownNonSpeech() {
			return basicService >= 0 && (basicService >> 5 & 7) != 0;
		}
	}

	private static final class CallFields {
		int partySsi = -1;
		int basicService = -1;
		int encryptionControl = -1;
		int transmissionGrant = -1;
		int simplex = -1;
		int priority = -1;
	}

	private static final class Resource {
		boolean fillBits, startFragment;
		int encryptionMode, lengthOctets, addressType, address, dataOffset;
	}

	private static final class Fragment {
		final int destination;
		final long startedMillis;
		final int encryptionMode, addressType;
		final List<Integer> data = new ArrayList<Integer>();
		Fragment(int destination, int[] initial, long startedMillis,
				int encryptionMode, int addressType) {
			this.destination = destination;
			this.startedMillis = startedMillis;
			this.encryptionMode = encryptionMode;
			this.addressType = addressType;
			append(initial, 0, initial.length);
		}
		void append(int[] bits, int start, int end) {
			for (int n = Math.max(0, start); n < Math.min(bits.length, end); n++)
				data.add(bits[n] & 1);
		}
		int[] bits() {
			int[] result = new int[data.size()];
			for (int n = 0; n < result.length; n++) result[n] = data.get(n);
			return result;
		}
	}

	private static final class Cursor {
		final int[] bits;
		int position;
		boolean valid = true;
		Cursor(int[] bits, int position) { this.bits = bits; this.position = position; }
		int take(int length) {
			if (!valid || length < 0 || position + length > bits.length) {
				valid = false; return 0;
			}
			int value = read(bits, position, length);
			position += length;
			return value;
		}
		void skip(int length) { take(length); }
		boolean ok() { return valid; }
	}
}
