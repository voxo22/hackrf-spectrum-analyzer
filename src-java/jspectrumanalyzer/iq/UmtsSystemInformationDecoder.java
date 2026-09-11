package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reassembles TS 25.331 BCH segments and decodes the public cell identity fields. */
final class UmtsSystemInformationDecoder {
	private static final int FIXED_SEGMENT_BITS = 222;
	private static final int COMPLETE_FIXED_BITS = 226;
	private static final String[] SIB_TYPES = {
		"MIB", "SIB1", "SIB2", "SIB3", "SIB4", "SIB5", "SIB6", "SIB7",
		"dummy", "dummy2", "dummy3", "SIB11", "SIB12", "SIB13", "SIB13.1", "SIB13.2",
		"SIB13.3", "SIB13.4", "SIB14", "SIB15", "SIB15.1", "SIB15.2", "SIB15.3",
		"SIB16", "SIB17", "SIB15.4", "SIB18", "SB1", "SB2", "SIB15.5", "SIB5bis",
		"extension"
	};
	private static final String[] SIB_REFERENCE_TYPES = {
		"SIB1", "SIB2", "SIB3", "SIB4", "SIB5", "SIB6", "SIB7", "dummy",
		"dummy2", "dummy3", "SIB11", "SIB12", "SIB13", "SIB13.1", "SIB13.2",
		"SIB13.3", "SIB13.4", "SIB14", "SIB15", "SIB16", "SIB17", "SIB15.1",
		"SIB15.2", "SIB15.3", "SIB15.4", "SIB18", "SIB15.5", "SIB5bis",
		"spare4", "spare3", "spare2", "spare1"
	};
	private static final String[] SIB_SB_REFERENCE_TYPES = {
		"SIB1", "SIB2", "SIB3", "SIB4", "SIB5", "SIB6", "SIB7", "dummy",
		"dummy2", "dummy3", "SIB11", "SIB12", "SIB13", "SIB13.1", "SIB13.2",
		"SIB13.3", "SIB13.4", "SIB14", "SIB15", "SIB16", "SIB17", "SB1", "SB2",
		"SIB15.1", "SIB15.2", "SIB15.3", "SIB15.4", "SIB18", "SIB15.5", "SIB5bis",
		"spare2", "spare1"
	};

	private final Map<Integer, PartialSib> partial = new LinkedHashMap<Integer, PartialSib>();
	private final Map<Integer, SibBlock> complete = new LinkedHashMap<Integer, SibBlock>();
	private int transportBlocks;
	private Snapshot snapshot = Snapshot.EMPTY;

	Snapshot accept(UmtsBchDecoder.BchData bch) {
		if (bch != null) {
			for (UmtsBchDecoder.BchBlock block : bch.blocks) {
				try {
					List<Segment> segments = parseTransportBlock(block);
					transportBlocks++;
					for (Segment segment : segments) accept(segment, block.sfn);
				} catch (DecodeException ignored) {
					// The physical block remains useful even if a later-release wrapper is unknown.
				}
			}
		}
		snapshot = buildSnapshot();
		return snapshot;
	}

	private void accept(Segment segment, int sfn) {
		if (segment.complete) {
			complete.put(segment.type, new SibBlock(segment.type, segment.bits, false, sfn));
			return;
		}
		if (segment.first) {
			PartialSib assembly = new PartialSib(segment.type, segment.segmentCount);
			assembly.parts.put(0, segment.bits);
			partial.put(segment.type, assembly);
			completeIfReady(assembly, sfn);
			return;
		}
		PartialSib assembly = partial.get(segment.type);
		if (assembly == null || segment.index <= 0 || segment.index >= assembly.segmentCount) return;
		assembly.parts.put(segment.index, segment.bits);
		completeIfReady(assembly, sfn);
	}

	private void completeIfReady(PartialSib assembly, int sfn) {
		if (assembly.parts.size() != assembly.segmentCount) return;
		int length = 0;
		for (int index = 0; index < assembly.segmentCount; index++) {
			int[] part = assembly.parts.get(index);
			if (part == null) return;
			length += part.length;
		}
		int[] bits = new int[length];
		int at = 0;
		for (int index = 0; index < assembly.segmentCount; index++) {
			int[] part = assembly.parts.get(index);
			System.arraycopy(part, 0, bits, at, part.length);
			at += part.length;
		}
		complete.put(assembly.type, new SibBlock(assembly.type, bits, true, sfn));
		partial.remove(assembly.type);
	}

	private Snapshot buildSnapshot() {
		Mib mib = decodeMib(complete.get(0));
		Sib1 sib1 = decodeSib1(complete.get(1));
		Sib3 sib3 = decodeSib3(complete.get(3));
		UmtsSib5Decoder.Config sib5 = complete.containsKey(5)
				? UmtsSib5Decoder.decode(complete.get(5).bits) : UmtsSib5Decoder.Config.EMPTY;
		List<String> schedules = new ArrayList<String>();
		if (mib.valid) schedules.addAll(mib.schedules);
		SibBlock sb1 = complete.get(27);
		if (sb1 != null) schedules.addAll(decodeSchedulingBlock(sb1));
		Map<Integer, Integer> lengths = new LinkedHashMap<Integer, Integer>();
		Map<Integer, String> blockHex = new LinkedHashMap<Integer, String>();
		for (Map.Entry<Integer, SibBlock> entry : complete.entrySet()) {
			lengths.put(entry.getKey(), entry.getValue().bits.length);
			blockHex.put(entry.getKey(), bitsHex(entry.getValue().bits));
		}
		Map<Integer, String> progress = new LinkedHashMap<Integer, String>();
		for (Map.Entry<Integer, PartialSib> entry : partial.entrySet())
			progress.put(entry.getKey(), entry.getValue().parts.size() + "/" + entry.getValue().segmentCount);
		return new Snapshot(mib.valid, mib.mcc, mib.mnc, mib.valueTag,
				sib1.valid, sib1.lac, sib1.commonNasHex,
				sib3.valid, sib3.cellIdentity, sib3.sib4Indicator,
				sib5,
				Collections.unmodifiableList(schedules),
				Collections.unmodifiableMap(lengths), Collections.unmodifiableMap(blockHex),
				Collections.unmodifiableMap(progress), transportBlocks);
	}

	private static List<Segment> parseTransportBlock(UmtsBchDecoder.BchBlock block) throws DecodeException {
		BitReader bits = new BitReader(block.payloadBits, 15);
		List<Segment> result = new ArrayList<Segment>();
		switch (block.payloadChoice) {
		case 0: break;
		case 1: result.add(first(bits, false)); break;
		case 2: result.add(subsequent(bits)); break;
		case 3: result.add(last(bits, false)); break;
		case 4:
			result.add(last(bits, false));
			result.add(first(bits, true));
			break;
		case 5:
			result.add(last(bits, false));
			completeList(bits, result);
			break;
		case 6:
			result.add(last(bits, false));
			completeList(bits, result);
			result.add(first(bits, true));
			break;
		case 7: completeList(bits, result); break;
		case 8:
			completeList(bits, result);
			result.add(first(bits, true));
			break;
		case 9:
			result.add(Segment.complete(bits.read(5), bits.readBits(COMPLETE_FIXED_BITS)));
			break;
		case 10: result.add(last(bits, true)); break;
		default: throw new DecodeException();
		}
		return result;
	}

	private static Segment first(BitReader bits, boolean shortData) throws DecodeException {
		int type = bits.read(5);
		int count = bits.read(4) + 1;
		int[] data = shortData ? bits.readVariableBits() : bits.readBits(FIXED_SEGMENT_BITS);
		return Segment.first(type, count, data);
	}

	private static Segment subsequent(BitReader bits) throws DecodeException {
		return Segment.part(bits.read(5), bits.read(4) + 1, bits.readBits(FIXED_SEGMENT_BITS));
	}

	private static Segment last(BitReader bits, boolean fixed) throws DecodeException {
		int type = bits.read(5);
		int index = bits.read(4) + 1;
		int[] data = fixed ? bits.readBits(FIXED_SEGMENT_BITS) : bits.readVariableBits();
		return Segment.part(type, index, data);
	}

	private static void completeList(BitReader bits, List<Segment> result) throws DecodeException {
		int count = bits.read(4) + 1;
		for (int index = 0; index < count; index++)
			result.add(Segment.complete(bits.read(5), bits.readVariableBits()));
	}

	private static Mib decodeMib(SibBlock block) {
		if (block == null) return Mib.EMPTY;
		try {
			BitReader bits = new BitReader(block.bits);
			bits.flag(); // v690NonCriticalExtensions
			int valueTag = bits.read(3) + 1;
			int plmnChoice = bits.read(2);
			if (plmnChoice != 0 && plmnChoice != 2) return Mib.EMPTY;
			String mcc = readDigits(bits, 3);
			String mnc = readDigits(bits, bits.read(1) + 2);
			if (plmnChoice == 2) return Mib.EMPTY;
			int count = bits.read(5) + 1;
			List<String> schedules = readSchedules(bits, count, true);
			return new Mib(true, mcc, mnc, valueTag, schedules);
		} catch (DecodeException error) {
			return Mib.EMPTY;
		}
	}

	private static Sib1 decodeSib1(SibBlock block) {
		if (block == null) return Sib1.EMPTY;
		try {
			BitReader bits = new BitReader(block.bits);
			bits.skip(3); // Optional UE timers and the R3 extension container.
			int octets = bits.read(3) + 1;
			byte[] commonNas = new byte[octets];
			for (int index = 0; index < octets; index++) commonNas[index] = (byte)bits.read(8);
			if (commonNas.length < 2) return Sib1.EMPTY;
			int lac = (commonNas[0] & 0xff) << 8 | commonNas[1] & 0xff;
			return new Sib1(true, lac, bytesHex(commonNas));
		} catch (DecodeException error) {
			return Sib1.EMPTY;
		}
	}

	private static Sib3 decodeSib3(SibBlock block) {
		if (block == null) return Sib3.EMPTY;
		try {
			BitReader bits = new BitReader(block.bits);
			bits.flag(); // Non-critical extension container.
			boolean sib4 = bits.flag();
			return new Sib3(true, bits.read(28), sib4);
		} catch (DecodeException error) {
			return Sib3.EMPTY;
		}
	}

	private static List<String> decodeSchedulingBlock(SibBlock block) {
		try {
			BitReader bits = new BitReader(block.bits);
			bits.flag(); // Non-critical extension container.
			return readSchedules(bits, bits.read(5) + 1, false);
		} catch (DecodeException error) {
			return Collections.emptyList();
		}
	}

	private static List<String> readSchedules(BitReader bits, int count, boolean mibList) throws DecodeException {
		List<String> result = new ArrayList<String>();
		for (int index = 0; index < count; index++) {
			int type = bits.read(5);
			String name = typeName(type, mibList ? SIB_SB_REFERENCE_TYPES : SIB_REFERENCE_TYPES);
			readReferenceTag(bits, type, mibList);
			boolean hasSegmentCount = bits.flag();
			boolean hasOffsets = bits.flag();
			int segments = hasSegmentCount ? bits.read(4) + 1 : 1;
			int periodChoice = bits.read(4);
			if (periodChoice > 10) throw new DecodeException();
			int position = bits.read(periodChoice + 1) * 2;
			if (hasOffsets) {
				int offsets = bits.read(4) + 1;
				for (int offset = 0; offset < offsets; offset++) bits.read(4);
			}
			int period = 4 << periodChoice;
			result.add(name + " RF" + period + " @" + position
					+ (segments == 1 ? "" : " (" + segments + " segments)"));
		}
		return result;
	}

	private static void readReferenceTag(BitReader bits, int type, boolean mibList) throws DecodeException {
		if (type == 0) {
			bits.read(8); // PLMN value tag
			return;
		}
		if (mibList) {
			if (type == 6 || type == 8 || type == 9 || type == 17 || type == 20 || type >= 30) return;
			if (type == 19) { bits.read(5); bits.read(4); return; }
			if (type == 24 || type == 25) { bits.read(4); bits.read(4); return; }
		} else {
			if (type == 6 || type == 8 || type == 9 || type == 17 || type == 20 || type >= 28) return;
			if (type == 19) { bits.read(5); bits.read(4); return; }
			if (type == 22 || type == 23) { bits.read(4); bits.read(4); return; }
		}
		bits.read(2); // Cell value tag
	}

	private static String readDigits(BitReader bits, int count) throws DecodeException {
		StringBuilder value = new StringBuilder(count);
		for (int index = 0; index < count; index++) {
			int digit = bits.read(4);
			if (digit > 9) throw new DecodeException();
			value.append((char)('0' + digit));
		}
		return value.toString();
	}

	private static String bytesHex(byte[] bytes) {
		StringBuilder result = new StringBuilder(bytes.length * 2);
		for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02X", value & 0xff));
		return result.toString();
	}

	private static String bitsHex(int[] bits) {
		StringBuilder result = new StringBuilder((bits.length + 3) / 4);
		for (int at = 0; at < bits.length; at += 4) {
			int value = 0;
			for (int bit = 0; bit < 4; bit++)
				value = value << 1 | (at + bit < bits.length ? bits[at + bit] : 0);
			result.append(Character.forDigit(value, 16));
		}
		return result.toString().toUpperCase(java.util.Locale.ROOT);
	}

	static String typeName(int type) { return typeName(type, SIB_TYPES); }

	private static String typeName(int type, String[] names) {
		return type >= 0 && type < names.length ? names[type] : "SIB?";
	}

	static final class Snapshot {
		static final Snapshot EMPTY = new Snapshot(false, "", "", -1, false, -1, "", false, -1, false,
				UmtsSib5Decoder.Config.EMPTY,
				Collections.<String>emptyList(), Collections.<Integer, Integer>emptyMap(),
				Collections.<Integer, String>emptyMap(),
				Collections.<Integer, String>emptyMap(), 0);
		final boolean mibValid, sib1Valid, sib3Valid, sib4Indicator;
		final String mcc, mnc, commonNasHex;
		final int mibValueTag, lac, cellIdentity, transportBlocks;
		final UmtsSib5Decoder.Config sib5;
		final List<String> schedules;
		final Map<Integer, Integer> blockBitLengths;
		final Map<Integer, String> blockHex;
		final Map<Integer, String> segmentProgress;

		Snapshot(boolean mibValid, String mcc, String mnc, int mibValueTag,
				boolean sib1Valid, int lac, String commonNasHex,
				boolean sib3Valid, int cellIdentity, boolean sib4Indicator,
				UmtsSib5Decoder.Config sib5,
				List<String> schedules, Map<Integer, Integer> blockBitLengths, Map<Integer, String> blockHex,
				Map<Integer, String> segmentProgress, int transportBlocks) {
			this.mibValid = mibValid;
			this.mcc = mcc;
			this.mnc = mnc;
			this.mibValueTag = mibValueTag;
			this.sib1Valid = sib1Valid;
			this.lac = lac;
			this.commonNasHex = commonNasHex;
			this.sib3Valid = sib3Valid;
			this.cellIdentity = cellIdentity;
			this.sib4Indicator = sib4Indicator;
			this.sib5 = sib5 == null ? UmtsSib5Decoder.Config.EMPTY : sib5;
			this.schedules = schedules;
			this.blockBitLengths = blockBitLengths;
			this.blockHex = blockHex;
			this.segmentProgress = segmentProgress;
			this.transportBlocks = transportBlocks;
		}

		boolean hasAny() { return !blockBitLengths.isEmpty(); }
		boolean hasBlock(int type) { return blockBitLengths.containsKey(type); }
		int blockBits(int type) {
			Integer value = blockBitLengths.get(type);
			return value == null ? 0 : value;
		}
		String receivedBlocks() {
			List<String> names = new ArrayList<String>();
			for (Integer type : blockBitLengths.keySet()) names.add(typeName(type));
			return names.isEmpty() ? "" : String.join(", ", names);
		}
		String progressFor(int type) { return segmentProgress.get(type); }
		String blockHex(int type) {
			String value = blockHex.get(type);
			return value == null ? "" : value;
		}
	}

	private static final class Mib {
		static final Mib EMPTY = new Mib(false, "", "", -1, Collections.<String>emptyList());
		final boolean valid;
		final String mcc, mnc;
		final int valueTag;
		final List<String> schedules;
		Mib(boolean valid, String mcc, String mnc, int valueTag, List<String> schedules) {
			this.valid = valid; this.mcc = mcc; this.mnc = mnc; this.valueTag = valueTag;
			this.schedules = schedules;
		}
	}

	private static final class Sib1 {
		static final Sib1 EMPTY = new Sib1(false, -1, "");
		final boolean valid;
		final int lac;
		final String commonNasHex;
		Sib1(boolean valid, int lac, String commonNasHex) {
			this.valid = valid; this.lac = lac; this.commonNasHex = commonNasHex;
		}
	}

	private static final class Sib3 {
		static final Sib3 EMPTY = new Sib3(false, -1, false);
		final boolean valid, sib4Indicator;
		final int cellIdentity;
		Sib3(boolean valid, int cellIdentity, boolean sib4Indicator) {
			this.valid = valid; this.cellIdentity = cellIdentity; this.sib4Indicator = sib4Indicator;
		}
	}

	private static final class SibBlock {
		final int type, sfn;
		final int[] bits;
		final boolean segmented;
		SibBlock(int type, int[] bits, boolean segmented, int sfn) {
			this.type = type; this.bits = bits.clone(); this.segmented = segmented; this.sfn = sfn;
		}
	}

	private static final class PartialSib {
		final int type, segmentCount;
		final Map<Integer, int[]> parts = new LinkedHashMap<Integer, int[]>();
		PartialSib(int type, int segmentCount) { this.type = type; this.segmentCount = segmentCount; }
	}

	private static final class Segment {
		final int type, index, segmentCount;
		final int[] bits;
		final boolean complete, first;
		private Segment(int type, int index, int segmentCount, int[] bits, boolean complete, boolean first) {
			if (type < 0 || type >= SIB_TYPES.length) throw new IllegalArgumentException();
			this.type = type; this.index = index; this.segmentCount = segmentCount;
			this.bits = bits; this.complete = complete; this.first = first;
		}
		static Segment complete(int type, int[] bits) { return new Segment(type, 0, 1, bits, true, false); }
		static Segment first(int type, int count, int[] bits) { return new Segment(type, 0, count, bits, false, true); }
		static Segment part(int type, int index, int[] bits) { return new Segment(type, index, 0, bits, false, false); }
	}

	private static final class BitReader {
		final int[] bits;
		int at;
		BitReader(int[] bits) { this(bits, 0); }
		BitReader(int[] bits, int at) { this.bits = bits; this.at = at; }
		boolean flag() throws DecodeException { return read(1) != 0; }
		void skip(int count) throws DecodeException { read(count); }
		int read(int count) throws DecodeException {
			if (count < 0 || at + count > bits.length) throw new DecodeException();
			int value = 0;
			while (count-- > 0) value = value << 1 | bits[at++];
			return value;
		}
		int[] readBits(int count) throws DecodeException {
			if (count < 0 || at + count > bits.length) throw new DecodeException();
			int[] value = Arrays.copyOfRange(bits, at, at + count);
			at += count;
			return value;
		}
		int[] readVariableBits() throws DecodeException {
			int length = read(8) + 1;
			if (length > 214) throw new DecodeException();
			return readBits(length);
		}
	}

	private static final class DecodeException extends Exception {
		private static final long serialVersionUID = 1L;
	}
}
