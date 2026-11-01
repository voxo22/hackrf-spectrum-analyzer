package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Decoded TETRA cell parameters learned from CRC-verified clear MAC broadcasts. */
final class TetraCellInfo {
	private static final long HALF_CARRIER_HZ = 12_500L;
	private static final int MAX_NEIGHBOURS = 32;
	private Snapshot latest = Snapshot.EMPTY;

	/**
	 * Parse MAC-SYSINFO using the complete field order used by the SDR# TETRA/TTT
	 * decoder. A truncated PDU is ignored rather than replacing a known-good cell.
	 */
	void acceptSysInfo(int[] bits, long timestampMillis) {
		if (bits == null || bits.length < 124 || read(bits, 0, 2) != 2
				|| read(bits, 2, 2) != 0) return;
		int carrier = read(bits, 4, 12);
		int band = read(bits, 16, 4);
		int offset = read(bits, 20, 2);
		int duplex = read(bits, 22, 3);
		int reverse = read(bits, 25, 1);
		int commonSc = read(bits, 26, 2);
		int maxPower = read(bits, 28, 3);
		int minRx = read(bits, 31, 4);
		int access = read(bits, 35, 4);
		int timeout = read(bits, 39, 4);
		int hyperframeFlag = read(bits, 43, 1);
		int hyperframeOrCipherKey = read(bits, 44, 16);
		int optionalFlag = read(bits, 60, 2);
		int authenticationRequired = optionalFlag == 3 ? read(bits, 62, 1) : -1;
		int securityClass1 = optionalFlag == 3 ? read(bits, 63, 1) : -1;
		int securityClass3 = optionalFlag == 3 ? read(bits, 64, 1) : -1;
		/* Networks may alternate SYSINFO optional-field variants. Keep the last
		 * explicitly broadcast security capabilities instead of blanking three UI
		 * rows until the security variant cycles around again. */
		if (optionalFlag != 3 && latest.system != null) {
			authenticationRequired = latest.system.authenticationRequired;
			securityClass1 = latest.system.securityClass1Supported;
			securityClass3 = latest.system.securityClass3Supported;
		}
		int optionalValue = optionalFlag == 3 ? read(bits, 65, 17) : read(bits, 62, 20);
		int locationArea = read(bits, 82, 14);
		int subscriberClass = read(bits, 96, 16);
		int registrationRequired = read(bits, 112, 1);
		int deregistrationRequired = read(bits, 113, 1);
		int priorityCell = read(bits, 114, 1);
		int neverMinimumMode = read(bits, 115, 1);
		int migrationSupported = read(bits, 116, 1);
		int systemWideServices = read(bits, 117, 1);
		int voiceService = read(bits, 118, 1);
		int circuitModeData = read(bits, 119, 1);
		int sndcpService = read(bits, 121, 1);
		int airInterfaceEncryption = read(bits, 122, 1);
		int advancedLink = read(bits, 123, 1);
		long mainHz = carrierFrequency(carrier, band, offset);
		SystemParameters system = new SystemParameters(timestampMillis, carrier, band,
				offset, duplex, reverse != 0, mainHz, commonSc, maxPower, minRx,
				access, timeout, hyperframeFlag, hyperframeOrCipherKey, optionalFlag,
				optionalValue, authenticationRequired, securityClass1, securityClass3,
				locationArea, subscriberClass, registrationRequired,
				deregistrationRequired, priorityCell, neverMinimumMode,
				migrationSupported, systemWideServices, voiceService, circuitModeData,
				sndcpService, airInterfaceEncryption, advancedLink);
		latest = new Snapshot(system, latest.network, 0L, Role.UNKNOWN,
				resolveNeighbours(system, latest.neighbours));
	}

	private static List<Neighbour> resolveNeighbours(SystemParameters system,
			List<Neighbour> neighbours) {
		List<Neighbour> resolved = new ArrayList<Neighbour>(neighbours.size());
		for (Neighbour neighbour : neighbours) {
			if (neighbour.frequencyBand >= 0) {
				resolved.add(neighbour);
				continue;
			}
			resolved.add(new Neighbour(neighbour.timestampMillis, neighbour.cellId,
					neighbour.mainCarrier, system.frequencyBand, system.offset,
					system.duplexSpacing, neighbour.synchronizedCell,
					carrierFrequency(neighbour.mainCarrier, system.frequencyBand, system.offset),
					system.reverseOperation, neighbour.reselectionTypes, neighbour.serviceLevel,
					neighbour.carrierExtension, neighbour.mcc, neighbour.mnc,
					neighbour.locationArea, neighbour.maxTransmitPower,
					neighbour.minRxAccessLevel, neighbour.subscriberClass,
					neighbour.serviceDetails, neighbour.timeshare, neighbour.tdmaFrameOffset));
		}
		return resolved;
	}

	/** Parse and retain D-NWRK-BROADCAST parameters and merge neighbour cells by Cell ID. */
	void acceptNeighbourBroadcast(int[] bits, int start, int end, long timestampMillis) {
		if (bits == null || end > bits.length || start < 0 || end - start < 19) return;
		Cursor cursor = new Cursor(bits, start, end);
		int reselection = cursor.take(16);
		int serviceLevel = cursor.take(2);
		if (!cursor.ok()) return;

		int networkTime = -1, localOffsetSign = -1, localOffset = -1;
		int neighbourCount = -1;
		boolean options = cursor.take(1) != 0;
		if (options) {
			if (cursor.take(1) == 1) {
				networkTime = cursor.take(24);
				localOffsetSign = cursor.take(1);
				localOffset = cursor.take(23);
			}
			if (!cursor.ok()) return;
			if (cursor.take(1) == 1) neighbourCount = cursor.take(3);
		}
		if (!cursor.ok()) return;

		NetworkBroadcast network = new NetworkBroadcast(timestampMillis, reselection,
				serviceLevel, networkTime, localOffsetSign, localOffset, neighbourCount);
		Map<Integer, Neighbour> merged = new LinkedHashMap<Integer, Neighbour>();
		for (Neighbour neighbour : latest.neighbours) merged.put(neighbour.cellId, neighbour);
		if (neighbourCount >= 0) {
			for (int n = 0; n < neighbourCount && cursor.ok(); n++) {
				Neighbour neighbour = parseNeighbour(cursor, timestampMillis);
				if (neighbour == null) break;
				merged.put(neighbour.cellId, mergeNeighbour(merged.get(neighbour.cellId), neighbour));
				while (merged.size() > MAX_NEIGHBOURS) {
					Integer first = merged.keySet().iterator().next();
					merged.remove(first);
				}
			}
		}
		latest = new Snapshot(latest.system, network, latest.tunedHz, latest.role,
				new ArrayList<Neighbour>(merged.values()));
	}

	/** A short neighbour element must not erase extensions learned earlier. */
	private static Neighbour mergeNeighbour(Neighbour previous, Neighbour current) {
		if (previous == null) return current;
		boolean retainCarrierExtension = current.carrierExtension < 0
				&& previous.carrierExtension >= 0;
		int band = retainCarrierExtension ? previous.frequencyBand
				: prefer(current.frequencyBand, previous.frequencyBand);
		int offset = retainCarrierExtension ? previous.offset
				: prefer(current.offset, previous.offset);
		int duplex = retainCarrierExtension ? previous.duplexSpacing
				: prefer(current.duplexSpacing, previous.duplexSpacing);
		boolean reverse = retainCarrierExtension ? previous.reverseOperation
				: current.reverseOperation;
		long frequency = band >= 0 && offset >= 0
				? carrierFrequency(current.mainCarrier, band, offset) : previous.carrierHz;
		return new Neighbour(current.timestampMillis, current.cellId, current.mainCarrier,
				band, offset, duplex,
				current.synchronizedCell,
				frequency,
				reverse, current.reselectionTypes, current.serviceLevel,
				prefer(current.carrierExtension, previous.carrierExtension),
				prefer(current.mcc, previous.mcc), prefer(current.mnc, previous.mnc),
				prefer(current.locationArea, previous.locationArea),
				prefer(current.maxTransmitPower, previous.maxTransmitPower),
				prefer(current.minRxAccessLevel, previous.minRxAccessLevel),
				prefer(current.subscriberClass, previous.subscriberClass),
				prefer(current.serviceDetails, previous.serviceDetails),
				prefer(current.timeshare, previous.timeshare),
				prefer(current.tdmaFrameOffset, previous.tdmaFrameOffset));
	}

	private static int prefer(int current, int previous) {
		return current >= 0 ? current : previous;
	}

	private Neighbour parseNeighbour(Cursor cursor, long timestampMillis) {
		int cellId = cursor.take(5);
		int reselectionTypes = cursor.take(2);
		boolean synchronizedCell = cursor.take(1) != 0;
		int serviceLevel = cursor.take(2);
		int carrier = cursor.take(12);
		int band = latest.hasSysInfo ? latest.frequencyBand : -1;
		int offset = latest.hasSysInfo ? latest.offset : -1;
		int duplex = latest.hasSysInfo ? latest.duplexSpacing : -1;
		boolean reverse = latest.hasSysInfo && latest.reverseOperation;
		int mcc = -1, mnc = -1, locationArea = -1, maxPower = -1;
		int minRx = -1, subscriberClass = -1, serviceDetails = -1;
		int timeshare = -1, frameOffset = -1, extension = -1;
		if (cursor.take(1) == 1) {
			if (cursor.take(1) == 1) {
				extension = cursor.take(10);
				band = (extension >>> 6) & 0xf;
				offset = (extension >>> 4) & 0x3;
				duplex = (extension >>> 1) & 0x7;
				reverse = (extension & 1) != 0;
			}
			if (cursor.take(1) == 1) mcc = cursor.take(10);
			if (cursor.take(1) == 1) mnc = cursor.take(14);
			if (cursor.take(1) == 1) locationArea = cursor.take(14);
			if (cursor.take(1) == 1) maxPower = cursor.take(3);
			if (cursor.take(1) == 1) minRx = cursor.take(4);
			if (cursor.take(1) == 1) subscriberClass = cursor.take(16);
			if (cursor.take(1) == 1) serviceDetails = cursor.take(12);
			if (cursor.take(1) == 1) timeshare = cursor.take(5);
			if (cursor.take(1) == 1) frameOffset = cursor.take(6);
		}
		if (!cursor.ok()) return null;
		long carrierHz = band >= 0 && offset >= 0
				? carrierFrequency(carrier, band, offset) : 0L;
		return new Neighbour(timestampMillis, cellId, carrier, band, offset, duplex,
				synchronizedCell, carrierHz, reverse, reselectionTypes, serviceLevel,
				extension, mcc, mnc, locationArea, maxPower, minRx, subscriberClass,
				serviceDetails, timeshare, frameOffset);
	}

	Snapshot snapshot(long tunedHz) {
		if (!latest.hasSysInfo || tunedHz <= 0L)
			return new Snapshot(latest.system, latest.network, tunedHz, Role.UNKNOWN,
					latest.neighbours);
		long delta = tunedHz - latest.mainCarrierHz;
		Role role = Math.abs(delta) < HALF_CARRIER_HZ ? Role.MAIN : Role.OTHER;
		return new Snapshot(latest.system, latest.network, tunedHz, role, latest.neighbours);
	}

	void reset() { latest = Snapshot.EMPTY; }

	static long offsetHz(int code) {
		switch (code) {
		case 0: return 0L;
		case 1: return 6_250L;
		case 2: return -6_250L;
		case 3: return 12_500L;
		default: return 0L;
		}
	}

	private static long carrierFrequency(int carrier, int band, int offset) {
		return (long)band * 100_000_000L + carrier * 25_000L + offsetHz(offset);
	}

	private static int read(int[] bits, int start, int length) {
		if (start < 0 || length < 0 || start + length > bits.length) return -1;
		int value = 0;
		for (int n = 0; n < length; n++) value = (value << 1) | (bits[start + n] & 1);
		return value;
	}

	enum Role { UNKNOWN, MAIN, OTHER }

	static final class Snapshot {
		static final Snapshot EMPTY = new Snapshot(null, null, 0L, Role.UNKNOWN,
				Collections.<Neighbour>emptyList());
		final SystemParameters system;
		final NetworkBroadcast network;
		final boolean hasSysInfo, reverseOperation;
		final long timestampMillis, mainCarrierHz, tunedHz;
		final int mainCarrier, frequencyBand, offset, duplexSpacing;
		final int commonSecondaryChannels, maxTransmitPower, minRxAccessLevel;
		final int accessParameter, downlinkTimeout;
		final Role role;
		final List<Neighbour> neighbours;

		Snapshot(SystemParameters system, NetworkBroadcast network, long tunedHz,
				Role role, List<Neighbour> neighbours) {
			this.system = system;
			this.network = network;
			this.hasSysInfo = system != null;
			this.reverseOperation = system != null && system.reverseOperation;
			this.timestampMillis = system == null ? 0L : system.timestampMillis;
			this.mainCarrierHz = system == null ? 0L : system.mainCarrierHz;
			this.tunedHz = tunedHz;
			this.mainCarrier = system == null ? -1 : system.mainCarrier;
			this.frequencyBand = system == null ? -1 : system.frequencyBand;
			this.offset = system == null ? -1 : system.offset;
			this.duplexSpacing = system == null ? -1 : system.duplexSpacing;
			this.commonSecondaryChannels = system == null ? -1 : system.commonSecondaryChannels;
			this.maxTransmitPower = system == null ? -1 : system.maxTransmitPower;
			this.minRxAccessLevel = system == null ? -1 : system.minRxAccessLevel;
			this.accessParameter = system == null ? -1 : system.accessParameter;
			this.downlinkTimeout = system == null ? -1 : system.downlinkTimeout;
			this.role = role;
			this.neighbours = Collections.unmodifiableList(new ArrayList<Neighbour>(neighbours));
		}
	}

	static final class SystemParameters {
		final long timestampMillis, mainCarrierHz;
		final int mainCarrier, frequencyBand, offset, duplexSpacing;
		final boolean reverseOperation;
		final int commonSecondaryChannels, maxTransmitPower, minRxAccessLevel;
		final int accessParameter, downlinkTimeout, hyperframeOrCipherKeyFlag;
		final int hyperframeOrCipherKey, optionalFieldFlag, optionalFieldValue;
		final int authenticationRequired, securityClass1Supported, securityClass3Supported;
		final int locationArea, subscriberClass, registrationRequired;
		final int deregistrationRequired, priorityCell, neverMinimumMode;
		final int migrationSupported, systemWideServices, voiceService;
		final int circuitModeDataService, sndcpService, airInterfaceEncryption;
		final int advancedLinkSupported;

		SystemParameters(long timestampMillis, int mainCarrier, int frequencyBand,
				int offset, int duplexSpacing, boolean reverseOperation, long mainCarrierHz,
				int commonSecondaryChannels, int maxTransmitPower, int minRxAccessLevel,
				int accessParameter, int downlinkTimeout, int hyperframeOrCipherKeyFlag,
				int hyperframeOrCipherKey, int optionalFieldFlag, int optionalFieldValue,
				int authenticationRequired, int securityClass1Supported,
				int securityClass3Supported, int locationArea, int subscriberClass,
				int registrationRequired, int deregistrationRequired, int priorityCell,
				int neverMinimumMode, int migrationSupported, int systemWideServices,
				int voiceService, int circuitModeDataService, int sndcpService,
				int airInterfaceEncryption, int advancedLinkSupported) {
			this.timestampMillis = timestampMillis; this.mainCarrier = mainCarrier;
			this.frequencyBand = frequencyBand; this.offset = offset;
			this.duplexSpacing = duplexSpacing; this.reverseOperation = reverseOperation;
			this.mainCarrierHz = mainCarrierHz;
			this.commonSecondaryChannels = commonSecondaryChannels;
			this.maxTransmitPower = maxTransmitPower; this.minRxAccessLevel = minRxAccessLevel;
			this.accessParameter = accessParameter; this.downlinkTimeout = downlinkTimeout;
			this.hyperframeOrCipherKeyFlag = hyperframeOrCipherKeyFlag;
			this.hyperframeOrCipherKey = hyperframeOrCipherKey;
			this.optionalFieldFlag = optionalFieldFlag; this.optionalFieldValue = optionalFieldValue;
			this.authenticationRequired = authenticationRequired;
			this.securityClass1Supported = securityClass1Supported;
			this.securityClass3Supported = securityClass3Supported;
			this.locationArea = locationArea; this.subscriberClass = subscriberClass;
			this.registrationRequired = registrationRequired;
			this.deregistrationRequired = deregistrationRequired;
			this.priorityCell = priorityCell; this.neverMinimumMode = neverMinimumMode;
			this.migrationSupported = migrationSupported;
			this.systemWideServices = systemWideServices; this.voiceService = voiceService;
			this.circuitModeDataService = circuitModeDataService;
			this.sndcpService = sndcpService;
			this.airInterfaceEncryption = airInterfaceEncryption;
			this.advancedLinkSupported = advancedLinkSupported;
		}
	}

	static final class NetworkBroadcast {
		final long timestampMillis;
		final int cellReselectionParameters, cellServiceLevel;
		final int networkTime, localTimeOffsetSign, localTimeOffset, neighbourCount;
		NetworkBroadcast(long timestampMillis, int cellReselectionParameters,
				int cellServiceLevel, int networkTime, int localTimeOffsetSign,
				int localTimeOffset, int neighbourCount) {
			this.timestampMillis = timestampMillis;
			this.cellReselectionParameters = cellReselectionParameters;
			this.cellServiceLevel = cellServiceLevel; this.networkTime = networkTime;
			this.localTimeOffsetSign = localTimeOffsetSign;
			this.localTimeOffset = localTimeOffset; this.neighbourCount = neighbourCount;
		}
	}

	static final class Neighbour {
		final long timestampMillis;
		final int cellId, mainCarrier, frequencyBand, offset, duplexSpacing;
		final boolean synchronizedCell, reverseOperation;
		final long carrierHz;
		final int reselectionTypes, serviceLevel, carrierExtension, mcc, mnc, locationArea;
		final int maxTransmitPower, minRxAccessLevel, subscriberClass, serviceDetails;
		final int timeshare, tdmaFrameOffset;
		Neighbour(long timestampMillis, int cellId, int mainCarrier, int frequencyBand,
				int offset, int duplexSpacing, boolean synchronizedCell, long carrierHz,
				boolean reverseOperation, int reselectionTypes, int serviceLevel,
				int carrierExtension, int mcc, int mnc, int locationArea,
				int maxTransmitPower, int minRxAccessLevel, int subscriberClass,
				int serviceDetails, int timeshare, int tdmaFrameOffset) {
			this.timestampMillis = timestampMillis; this.cellId = cellId;
			this.mainCarrier = mainCarrier; this.frequencyBand = frequencyBand;
			this.offset = offset; this.duplexSpacing = duplexSpacing;
			this.synchronizedCell = synchronizedCell; this.carrierHz = carrierHz;
			this.reverseOperation = reverseOperation;
			this.reselectionTypes = reselectionTypes; this.serviceLevel = serviceLevel;
			this.carrierExtension = carrierExtension; this.mcc = mcc; this.mnc = mnc;
			this.locationArea = locationArea; this.maxTransmitPower = maxTransmitPower;
			this.minRxAccessLevel = minRxAccessLevel; this.subscriberClass = subscriberClass;
			this.serviceDetails = serviceDetails; this.timeshare = timeshare;
			this.tdmaFrameOffset = tdmaFrameOffset;
		}
	}

	private static final class Cursor {
		final int[] bits; final int end; int position; boolean valid = true;
		Cursor(int[] bits, int start, int end) {
			this.bits = bits; this.position = start; this.end = end;
		}
		int take(int count) {
			if (!valid || count < 0 || position + count > end) {
				valid = false; return 0;
			}
			int value = 0;
			for (int n = 0; n < count; n++) value = (value << 1) | (bits[position++] & 1);
			return value;
		}
		boolean ok() { return valid; }
	}
}
