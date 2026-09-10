package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Minimal TS 38.331 unaligned-PER decoder for the SIB1 fields shown by the tester. */
final class NrSib1Decoder {
	private NrSib1Decoder() {}

	static NrSignalAnalyzer.Sib1Data decode(byte[] payload) {
		if (payload == null || payload.length == 0) return NrSignalAnalyzer.Sib1Data.EMPTY;
		try {
			Bits bits = new Bits(payload);
			if (bits.read(1) != 0 || bits.read(1) != 1) return NrSignalAnalyzer.Sib1Data.EMPTY;

			boolean cellSelection = bits.flag();
			boolean connectionFailure = bits.flag();
			boolean scheduling = bits.flag();
			boolean servingCell = bits.flag();
			bits.skip(5); // ims/emergency, eCall, UE timers, UAC barring, full resume ID
			boolean lateExtension = bits.flag();
			bits.skip(1); // nonCriticalExtension (empty in this ASN.1 release)

			if (cellSelection) skipCellSelection(bits);
			Identity identity = readCellAccess(bits);
			if (connectionFailure) skipConnectionFailure(bits);

			Scheduling schedule = scheduling ? readScheduling(bits) : Scheduling.EMPTY;
			ServingCell serving = servingCell ? readServingCell(bits) : ServingCell.EMPTY;
			// Fields after servingCellConfigCommon are irrelevant to the table. A late extension
			// is therefore intentionally not consumed here.
			if (lateExtension && bits.remaining() < 1) throw new DecodeException();
			return new NrSignalAnalyzer.Sib1Data(true, identity.plmns, identity.cellId,
					identity.tac, serving.bands, schedule.entries, schedule.windowSlots,
					serving.carrierBandwidthRb, serving.carrierScsKhz,
					serving.channelBandwidthMhz, serving.initialBwpRb,
					serving.initialBwpScsKhz, serving.offsetToPointA,
					serving.modificationPeriodCoefficient, serving.pagingCycleFrames,
					serving.pagingFrameOffsetType, serving.pagingFrameOffset,
					serving.pagingOccasions, serving.otherSiSearchSpace, serving.pagingSearchSpace);
		} catch (DecodeException ex) {
			return NrSignalAnalyzer.Sib1Data.EMPTY;
		}
	}

	private static void skipCellSelection(Bits bits) throws DecodeException {
		boolean levelOffset = bits.flag();
		boolean sul = bits.flag();
		boolean quality = bits.flag();
		boolean qualityOffset = bits.flag();
		bits.constrained(-70, -22);
		if (levelOffset) bits.constrained(1, 8);
		if (sul) bits.constrained(-70, -22);
		if (quality) bits.constrained(-43, -12);
		if (qualityOffset) bits.constrained(1, 8);
	}

	private static Identity readCellAccess(Bits bits) throws DecodeException {
		if (bits.flag()) throw new DecodeException(); // extension additions are not in Rel-15 captures
		bits.skip(1); // cellReservedForOtherUse present
		int infoCount = bits.length(1, 12);
		List<String> plmns = new ArrayList<String>();
		String inheritedMcc = null;
		int tac = -1;
		long cellId = -1;
		for (int info = 0; info < infoCount; info++) {
			if (bits.flag()) throw new DecodeException();
			boolean tacPresent = bits.flag();
			boolean ranacPresent = bits.flag();
			int plmnCount = bits.length(1, 12);
			for (int index = 0; index < plmnCount; index++) {
				boolean mccPresent = bits.flag();
				if (mccPresent) inheritedMcc = readDigits(bits, 3);
				if (inheritedMcc == null) throw new DecodeException();
				String mnc = readDigits(bits, bits.length(2, 3));
				plmns.add(inheritedMcc + "-" + mnc);
			}
			int currentTac = tacPresent ? (int)bits.readLong(24) : -1;
			if (ranacPresent) bits.constrained(0, 255);
			long currentCellId = bits.readLong(36);
			bits.skip(1); // cellReservedForOperatorUse
			if (tac < 0 && currentTac >= 0) tac = currentTac;
			if (cellId < 0) cellId = currentCellId;
		}
		if (plmns.isEmpty() || cellId < 0) throw new DecodeException();
		return new Identity(plmns, tac, cellId);
	}

	private static String readDigits(Bits bits, int count) throws DecodeException {
		StringBuilder value = new StringBuilder(count);
		for (int i = 0; i < count; i++) value.append(bits.constrained(0, 9));
		return value.toString();
	}

	private static void skipConnectionFailure(Bits bits) throws DecodeException {
		boolean offset = bits.flag();
		bits.readEnum(4);
		bits.readEnum(8);
		if (offset) bits.constrained(0, 15);
	}

	private static Scheduling readScheduling(Bits bits) throws DecodeException {
		if (bits.flag()) throw new DecodeException();
		boolean request = bits.flag();
		boolean sulRequest = bits.flag();
		boolean areaId = bits.flag();
		int schedules = bits.length(1, 32);
		List<String> entries = new ArrayList<String>();
		int[] periods = { 8, 16, 32, 64, 128, 256, 512 };
		for (int i = 0; i < schedules; i++) {
			boolean broadcasting = bits.readEnum(2) == 0;
			int period = periods[bits.readEnum(7)];
			int mappings = bits.length(1, 32);
			List<String> sibs = new ArrayList<String>();
			for (int j = 0; j < mappings; j++) {
				boolean valueTag = bits.flag();
				bits.skip(1); // areaScope present
				if (bits.flag()) throw new DecodeException(); // extensible SIB type enum
				int type = bits.readEnum(16);
				if (valueTag) bits.constrained(0, 31);
				if (type < 8) sibs.add("SIB" + (type + 2));
			}
			entries.add(join(sibs) + " / RF" + period + " / "
					+ (broadcasting ? "broadcasting" : "not broadcasting"));
		}
		int[] windows = { 5, 10, 20, 40, 80, 160, 320, 640, 1280 };
		int window = windows[bits.readEnum(9)];
		// These optional request structures precede servingCellConfigCommon and are not
		// needed by the tester. Reject them rather than guessing their variable layout.
		if (request || sulRequest) throw new DecodeException();
		if (areaId) bits.skip(24);
		return new Scheduling(entries, window);
	}

	private static ServingCell readServingCell(Bits bits) throws DecodeException {
		if (bits.flag()) throw new DecodeException();
		bits.skip(4); // UL, supplementary UL, timing advance and TDD presence
		if (bits.flag()) throw new DecodeException(); // DownlinkConfigCommonSIB extension
		int count = bits.length(1, 8);
		List<Integer> bands = new ArrayList<Integer>();
		for (int i = 0; i < count; i++) {
			boolean bandPresent = bits.flag();
			boolean pMaxList = bits.flag();
			if (bandPresent) bands.add(bits.constrained(1, 1024));
			if (pMaxList) throw new DecodeException();
		}
		int offsetToPointA = bits.constrained(0, 2199);
		int carriers = bits.length(1, 5);
		List<Carrier> carrierList = new ArrayList<Carrier>();
		int[] scsValues = { 15, 30, 60, 120, 240, -1, -1, -1 };
		for (int i = 0; i < carriers; i++) {
			if (bits.flag()) throw new DecodeException();
			bits.constrained(0, 2199);
			int carrierScsKhz = scsValues[bits.readEnum(8)];
			int carrierBandwidthRb = bits.constrained(1, 275);
			carrierList.add(new Carrier(carrierBandwidthRb, carrierScsKhz));
		}

		if (bits.flag()) throw new DecodeException(); // BWP-DownlinkCommon extension
		boolean pdcchPresent = bits.flag();
		boolean pdschPresent = bits.flag();
		bits.skip(1); // cyclicPrefix present
		int locationAndBandwidth = bits.constrained(0, 37949);
		int scsIndex = bits.readEnum(8);
		int initialBwpRb = decodeRivBandwidth(locationAndBandwidth, 275);
		int initialBwpScsKhz = scsValues[scsIndex];
		Carrier selectedCarrier = carrierList.get(0);
		for (Carrier carrier : carrierList) {
			if (carrier.scsKhz == initialBwpScsKhz) {
				selectedCarrier = carrier;
				break;
			}
		}
		boolean fr2 = !bands.isEmpty() && bands.get(0) >= 257;
		int channelBandwidthMhz = channelBandwidthMhz(selectedCarrier.scsKhz,
				selectedCarrier.bandwidthRb, fr2);
		int otherSiSearchSpace = -1, pagingSearchSpace = -1;
		if (pdcchPresent && bits.flag()) {
			int[] spaces = readPdcchCommon(bits);
			otherSiSearchSpace = spaces[0];
			pagingSearchSpace = spaces[1];
		}
		if (pdschPresent && bits.flag()) skipPdschCommon(bits);
		if (bits.flag()) throw new DecodeException(); // BCCH-Config extension
		int[] coefficients = { 2, 4, 8, 16 };
		int modificationCoefficient = coefficients[bits.readEnum(4)];
		bits.flag(); // PCCH-Config extension; base fields still precede additions
		boolean firstOccasionPresent = bits.flag();
		int[] cycles = { 32, 64, 128, 256 };
		int pagingCycle = cycles[bits.readEnum(4)];
		int offsetType = bits.readEnum(5);
		String[] offsetTypes = { "T", "T/2", "T/4", "T/8", "T/16" };
		int[] offsetMax = { 0, 1, 3, 7, 15 };
		int pagingOffset = offsetType == 0 ? 0 : bits.constrained(0, offsetMax[offsetType]);
		int[] occasions = { 4, 2, 1 };
		int pagingOccasions = occasions[bits.readEnum(3)];
		// firstPDCCH-MonitoringOccasion follows, but its variable list is not required
		// for the summary and no later SIB1 field is needed here.
		return new ServingCell(bands, selectedCarrier.bandwidthRb, selectedCarrier.scsKhz,
				channelBandwidthMhz, initialBwpRb, initialBwpScsKhz, offsetToPointA,
				modificationCoefficient, pagingCycle, offsetTypes[offsetType], pagingOffset,
				pagingOccasions, otherSiSearchSpace, pagingSearchSpace);
	}

	private static int[] readPdcchCommon(Bits bits) throws DecodeException {
		if (bits.flag()) throw new DecodeException();
		boolean coreset0 = bits.flag(), commonCoreset = bits.flag(), search0 = bits.flag();
		boolean commonList = bits.flag(), sib1 = bits.flag(), otherSi = bits.flag();
		boolean paging = bits.flag(), randomAccess = bits.flag();
		if (coreset0) bits.constrained(0, 15);
		if (commonCoreset) throw new DecodeException();
		if (search0) bits.constrained(0, 15);
		if (commonList) {
			int count = bits.length(1, 4);
			for (int i = 0; i < count; i++) skipSearchSpace(bits);
		}
		if (sib1) bits.constrained(0, 39);
		int other = otherSi ? bits.constrained(0, 39) : -1;
		int pagingId = paging ? bits.constrained(0, 39) : -1;
		if (randomAccess) bits.constrained(0, 39);
		return new int[] { other, pagingId };
	}

	private static void skipSearchSpace(Bits bits) throws DecodeException {
		boolean coreset = bits.flag(), periodicity = bits.flag(), duration = bits.flag();
		boolean symbols = bits.flag(), candidates = bits.flag(), type = bits.flag();
		bits.constrained(0, 39);
		if (coreset) bits.constrained(0, 11);
		if (periodicity) {
			int[] periods = { 1, 2, 4, 5, 8, 10, 16, 20, 40, 80, 160, 320, 640, 1280, 2560 };
			int choice = bits.readEnum(periods.length);
			if (periods[choice] > 1) bits.constrained(0, periods[choice] - 1);
		}
		if (duration) bits.constrained(2, 2559);
		if (symbols) bits.skip(14);
		if (candidates) for (int i = 0; i < 5; i++) bits.readEnum(8);
		if (type) {
			if (bits.flag()) {
				bits.skip(2); // UE-specific extension flag and DCI-format enum
			} else {
				boolean[] present = new boolean[5];
				for (int i = 0; i < present.length; i++) present[i] = bits.flag();
				if (present[0]) bits.skip(1);
				for (int i = 1; i < present.length; i++) if (present[i]) throw new DecodeException();
			}
		}
	}

	private static void skipPdschCommon(Bits bits) throws DecodeException {
		if (bits.flag()) throw new DecodeException();
		if (!bits.flag()) return;
		int count = bits.length(1, 16);
		for (int i = 0; i < count; i++) {
			if (bits.flag()) bits.constrained(0, 32);
			bits.readEnum(2);
			bits.constrained(0, 127);
		}
	}

	private static int decodeRivBandwidth(int riv, int grid) {
		int quotient = riv / grid, remainder = riv % grid;
		return quotient <= grid / 2 ? quotient + 1 : grid - quotient + 1;
	}

	private static int channelBandwidthMhz(int scsKhz, int bandwidthRb, boolean fr2) {
		int[] rb;
		int[] mhz;
		if (fr2 && scsKhz == 60) {
			rb = new int[] { 66, 132, 264 };
			mhz = new int[] { 50, 100, 200 };
		} else if (fr2 && scsKhz == 120) {
			rb = new int[] { 32, 66, 132, 264 };
			mhz = new int[] { 50, 100, 200, 400 };
		} else if (scsKhz == 15) {
			rb = new int[] { 15, 25, 52, 79, 106, 133, 160, 188, 216, 242, 270 };
			mhz = new int[] { 3, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50 };
		} else if (scsKhz == 30) {
			rb = new int[] { 11, 24, 38, 51, 65, 78, 92, 106, 119, 133, 162, 189, 217, 245, 273 };
			mhz = new int[] { 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 60, 70, 80, 90, 100 };
		} else if (scsKhz == 60) {
			rb = new int[] { 11, 18, 24, 31, 38, 44, 51, 58, 65, 79, 93, 107, 121, 135 };
			mhz = new int[] { 10, 15, 20, 25, 30, 35, 40, 45, 50, 60, 70, 80, 90, 100 };
		} else {
			return -1;
		}
		for (int i = 0; i < rb.length; i++) if (rb[i] == bandwidthRb) return mhz[i];
		return -1;
	}

	private static String join(List<String> values) {
		StringBuilder text = new StringBuilder();
		for (String value : values) {
			if (text.length() > 0) text.append(", ");
			text.append(value);
		}
		return text.length() == 0 ? "no mapped SIB" : text.toString();
	}

	private static final class Scheduling {
		static final Scheduling EMPTY = new Scheduling(Collections.<String>emptyList(), -1);
		final List<String> entries;
		final int windowSlots;
		Scheduling(List<String> entries, int windowSlots) {
			this.entries = entries;
			this.windowSlots = windowSlots;
		}
	}

	private static final class ServingCell {
		static final ServingCell EMPTY = new ServingCell(Collections.<Integer>emptyList(), -1, -1,
				-1, -1, -1, -1, -1, -1, "", -1, -1, -1, -1);
		final List<Integer> bands;
		final int carrierBandwidthRb, carrierScsKhz, channelBandwidthMhz;
		final int initialBwpRb, initialBwpScsKhz, offsetToPointA, modificationPeriodCoefficient;
		final int pagingCycleFrames, pagingFrameOffset, pagingOccasions;
		final String pagingFrameOffsetType;
		final int otherSiSearchSpace, pagingSearchSpace;
		ServingCell(List<Integer> bands, int carrierBandwidthRb, int carrierScsKhz,
				int channelBandwidthMhz, int initialBwpRb, int initialBwpScsKhz, int offsetToPointA,
				int modificationPeriodCoefficient, int pagingCycleFrames, String pagingFrameOffsetType,
				int pagingFrameOffset, int pagingOccasions, int otherSiSearchSpace, int pagingSearchSpace) {
			this.bands = bands;
			this.carrierBandwidthRb = carrierBandwidthRb;
			this.carrierScsKhz = carrierScsKhz;
			this.channelBandwidthMhz = channelBandwidthMhz;
			this.initialBwpRb = initialBwpRb;
			this.initialBwpScsKhz = initialBwpScsKhz;
			this.offsetToPointA = offsetToPointA;
			this.modificationPeriodCoefficient = modificationPeriodCoefficient;
			this.pagingCycleFrames = pagingCycleFrames;
			this.pagingFrameOffsetType = pagingFrameOffsetType;
			this.pagingFrameOffset = pagingFrameOffset;
			this.pagingOccasions = pagingOccasions;
			this.otherSiSearchSpace = otherSiSearchSpace;
			this.pagingSearchSpace = pagingSearchSpace;
		}
	}

	private static final class Carrier {
		final int bandwidthRb, scsKhz;
		Carrier(int bandwidthRb, int scsKhz) {
			this.bandwidthRb = bandwidthRb;
			this.scsKhz = scsKhz;
		}
	}

	private static final class Identity {
		final List<String> plmns;
		final int tac;
		final long cellId;
		Identity(List<String> plmns, int tac, long cellId) {
			this.plmns = plmns;
			this.tac = tac;
			this.cellId = cellId;
		}
	}

	private static final class Bits {
		private final byte[] data;
		private int position;
		Bits(byte[] data) { this.data = data; }
		boolean flag() throws DecodeException { return read(1) != 0; }
		void skip(int count) throws DecodeException { readLong(count); }
		int read(int count) throws DecodeException { return (int)readLong(count); }
		long readLong(int count) throws DecodeException {
			if (count < 0 || count > 63 || position + count > data.length * 8) throw new DecodeException();
			long value = 0;
			for (int i = 0; i < count; i++, position++)
				value = (value << 1) | ((data[position >>> 3] >>> (7 - (position & 7))) & 1);
			return value;
		}
		int constrained(int minimum, int maximum) throws DecodeException {
			int range = maximum - minimum + 1;
			int encoded = read(bitCount(range));
			if (encoded >= range) throw new DecodeException();
			return minimum + encoded;
		}
		int length(int minimum, int maximum) throws DecodeException { return constrained(minimum, maximum); }
		int readEnum(int options) throws DecodeException {
			int value = read(bitCount(options));
			if (value >= options) throw new DecodeException();
			return value;
		}
		int remaining() { return data.length * 8 - position; }
		private static int bitCount(int values) {
			int bits = 0;
			for (int n = values - 1; n > 0; n >>>= 1) bits++;
			return bits;
		}
	}

	private static final class DecodeException extends Exception {
		private static final long serialVersionUID = 1L;
	}
}
