package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Targeted UPER decoder for the FDD paging-channel configuration in UMTS SIB5. */
final class UmtsSib5Decoder {
	private UmtsSib5Decoder() {}

	static Config decode(int[] data) {
		if (data == null) return Config.EMPTY;
		Bits bits = new Bits(data);
		try {
			boolean primaryCcpch = bits.flag();
			boolean cbsDrx = bits.flag();
			bits.flag(); // Later-release extension container; root paging fields precede it.
			bits.flag(); // SIB6 indicator.
			int pichPowerOffset = bits.integer(-10, 5);
			if (bits.choice(2) != 0) return Config.EMPTY;
			bits.integer(-22, 5); // AICH power offset.
			if (primaryCcpch) {
				if (bits.choice(2) != 0) return Config.EMPTY;
				bits.flag();
			}
			int prachCount = bits.length(16);
			for (int index = 0; index < prachCount; index++) skipPrach(bits);
			int channelCount = bits.length(16);
			List<Channel> channels = new ArrayList<Channel>();
			for (int index = 0; index < channelCount; index++) channels.add(readSccpch(bits));
			// CBS DRX follows all paging fields and is not needed for PCH acquisition.
			Channel paging = null;
			for (Channel channel : channels) if (channel.pichCode >= 0) {
				paging = channel;
				break;
			}
			return new Config(true, pichPowerOffset,
					Collections.unmodifiableList(channels), paging);
		} catch (DecodeException error) {
			if (Boolean.getBoolean("umts.sib5.debug"))
				System.err.println("UMTS SIB5 UPER decode stopped at bit " + bits.at + " of " + data.length);
			return Config.EMPTY;
		}
	}

	private static void skipPrach(Bits bits) throws DecodeException {
		boolean tfs = bits.flag(), tfcs = bits.flag(), partition = bits.flag();
		boolean persistence = bits.flag(), mapping = bits.flag();
		if (bits.choice(2) != 0) throw new DecodeException();
		bits.skip(16 + 2 + 4 + 4 + 12);
		bits.integer(1, 32);
		if (tfs) readTransportFormatSet(bits);
		if (tfcs) skipTfcs(bits);
		if (partition) {
			if (bits.choice(2) != 0) throw new DecodeException();
			int count = bits.length(8);
			for (int index = 0; index < count; index++) {
				boolean present = bits.flag();
				if (present) bits.skip(4 + 4 + 4);
			}
		}
		if (persistence) {
			int count = bits.length(6);
			bits.skip(count * 3);
		}
		if (mapping) bits.skip(7 * 3);
		if (bits.choice(2) != 0) return;
		boolean txPower = bits.flag(), constant = bits.flag(), powerOffset = bits.flag();
		boolean transmission = bits.flag(), aich = bits.flag();
		if (txPower) bits.integer(-10, 50);
		if (constant) bits.integer(-35, -10);
		if (powerOffset) {
			bits.integer(1, 8);
			bits.integer(1, 64);
		}
		if (transmission) {
			bits.integer(1, 32);
			bits.integer(0, 50);
			bits.integer(0, 50);
		}
		if (aich) bits.skip(8 + 1 + 1);
	}

	private static Channel readSccpch(Bits bits) throws DecodeException {
		boolean hasTfcs = bits.flag(), hasFachPch = bits.flag(), hasPich = bits.flag();
		if (bits.choice(2) != 0) throw new DecodeException();
		boolean secondaryCpich = bits.flag(), secondaryScrambling = bits.flag();
		boolean timingOffset = bits.flag();
		bits.flag(); // PCPICH may be used for channel estimation.
		if (secondaryCpich) {
			boolean secondaryCpichScrambling = bits.flag();
			if (secondaryCpichScrambling) bits.integer(1, 15);
			bits.integer(0, 255);
		}
		int scramblingCode = secondaryScrambling ? bits.integer(1, 15) : 0;
		boolean sttd = bits.flag();
		int sfChoice = bits.choice(7);
		int spreadingFactor = 4 << sfChoice;
		int code = bits.integer(0, spreadingFactor - 1);
		boolean pilots = bits.flag();
		boolean tfci = bits.flag();
		boolean flexible = bits.choice(2) != 0;
		int timing = timingOffset ? bits.integer(0, 149) : 0;
		if (hasTfcs) skipTfcs(bits);
		List<Transport> transports = new ArrayList<Transport>();
		if (hasFachPch) {
			int count = bits.length(8);
			for (int index = 0; index < count; index++) {
				TransportFormat format = readTransportFormatSet(bits);
				int identity = bits.integer(1, 32);
				boolean ctch = bits.flag();
				transports.add(new Transport(identity, ctch, format));
			}
		}
		int pichCode = -1, indicators = -1;
		if (hasPich) {
			if (bits.choice(2) != 0) throw new DecodeException();
			pichCode = bits.integer(0, 255);
			indicators = new int[] { 18, 36, 72, 144 }[bits.choice(4)];
			bits.flag(); // PICH STTD.
		}
		return new Channel(spreadingFactor, code, scramblingCode, timing, pilots, tfci,
				flexible, sttd, pichCode, indicators, Collections.unmodifiableList(transports));
	}

	private static TransportFormat readTransportFormatSet(Bits bits) throws DecodeException {
		if (bits.choice(2) != 1) throw new DecodeException();
		int ttiChoice = bits.choice(5);
		if (ttiChoice >= 4) throw new DecodeException();
		int count = bits.length(32);
		List<DynamicFormat> dynamic = new ArrayList<DynamicFormat>();
		for (int index = 0; index < count; index++) {
			if (bits.choice(2) != 0) throw new DecodeException();
			int sizeChoice = bits.choice(3);
			int blockBits;
			if (sizeChoice == 0) blockBits = bits.integer(0, 31) * 8 + 48;
			else if (sizeChoice == 1) blockBits = bits.integer(0, 63) * 16 + 312;
			else blockBits = bits.integer(0, 56) * 64 + 1384;
			int numberCount = bits.length(32), maximumBlocks = 0;
			for (int number = 0; number < numberCount; number++)
				maximumBlocks = Math.max(maximumBlocks, readTransportBlockCount(bits));
			int logicalChoice = bits.choice(3);
			if (logicalChoice == 2) {
				int logicalCount = bits.length(15);
				for (int logical = 0; logical < logicalCount; logical++) {
					boolean subchannel = bits.flag();
					bits.integer(1, 32);
					if (subchannel) bits.flag();
				}
			}
			dynamic.add(new DynamicFormat(blockBits, maximumBlocks));
		}
		int codingChoice = bits.choice(3), codingRate = 0;
		if (codingChoice == 1) codingRate = bits.choice(2) == 0 ? 2 : 3;
		int rateMatching = bits.integer(1, 256);
		int crcChoice = bits.choice(5);
		int crc = new int[] { 0, 8, 12, 16, 24 }[crcChoice];
		return new TransportFormat(new int[] { 10, 20, 40, 80 }[ttiChoice], codingChoice,
				codingRate, rateMatching, crc, Collections.unmodifiableList(dynamic));
	}

	private static int readTransportBlockCount(Bits bits) throws DecodeException {
		switch (bits.choice(4)) {
		case 0: return 0;
		case 1: return 1;
		case 2: return bits.integer(2, 17);
		default: return bits.integer(18, 512);
		}
	}

	private static void skipTfcs(Bits bits) throws DecodeException {
		if (bits.choice(2) != 0) throw new DecodeException();
		int operation = bits.choice(4);
		if (operation == 0 || operation == 1) skipTfcsAdd(bits);
		else if (operation == 2) skipTfcsRemoval(bits);
		else {
			skipTfcsRemoval(bits);
			skipTfcsAdd(bits);
		}
	}

	private static void skipTfcsAdd(Bits bits) throws DecodeException {
		int sizeChoice = bits.choice(7);
		int width = new int[] { 2, 4, 6, 8, 12, 16, 24 }[sizeChoice];
		int count = bits.length(1024);
		for (int index = 0; index < count; index++) {
			boolean power = bits.flag();
			bits.skip(width);
			if (power) skipPowerOffset(bits);
		}
	}

	private static void skipTfcsRemoval(Bits bits) throws DecodeException {
		bits.skip(bits.length(1024) * 10);
	}

	private static void skipPowerOffset(Bits bits) throws DecodeException {
		boolean ppM = bits.flag();
		if (bits.choice(2) == 0) {
			boolean reference = bits.flag();
			if (bits.choice(2) != 0) throw new DecodeException();
			bits.skip(4 + 4);
			if (reference) bits.skip(2);
		} else bits.skip(2);
		if (ppM) bits.integer(-5, 10);
	}

	static final class Config {
		static final Config EMPTY = new Config(false, 0, Collections.<Channel>emptyList(), null);
		final boolean valid;
		final int pichPowerOffset;
		final List<Channel> channels;
		final Channel pagingChannel;
		Config(boolean valid, int pichPowerOffset, List<Channel> channels, Channel pagingChannel) {
			this.valid = valid; this.pichPowerOffset = pichPowerOffset;
			this.channels = channels; this.pagingChannel = pagingChannel;
		}
	}

	static final class Channel {
		final int spreadingFactor, code, scramblingCode, timingOffset, pichCode, indicatorsPerFrame;
		final boolean pilots, tfci, flexible, sttd;
		final List<Transport> transports;
		Channel(int spreadingFactor, int code, int scramblingCode, int timingOffset,
				boolean pilots, boolean tfci, boolean flexible, boolean sttd,
				int pichCode, int indicatorsPerFrame, List<Transport> transports) {
			this.spreadingFactor = spreadingFactor; this.code = code;
			this.scramblingCode = scramblingCode; this.timingOffset = timingOffset;
			this.pilots = pilots; this.tfci = tfci; this.flexible = flexible; this.sttd = sttd;
			this.pichCode = pichCode; this.indicatorsPerFrame = indicatorsPerFrame;
			this.transports = transports;
		}
		Transport pagingTransport() {
			for (Transport transport : transports) {
				TransportFormat f = transport.format;
				if (!transport.ctch && f.ttiMs <= 20 && f.codingChoice == 1 && f.crcBits > 0)
					return transport;
			}
			return transports.isEmpty() ? null : transports.get(0);
		}
	}

	static final class Transport {
		final int identity;
		final boolean ctch;
		final TransportFormat format;
		Transport(int identity, boolean ctch, TransportFormat format) {
			this.identity = identity; this.ctch = ctch; this.format = format;
		}
	}

	static final class TransportFormat {
		final int ttiMs, codingChoice, codingRate, rateMatching, crcBits;
		final List<DynamicFormat> dynamic;
		TransportFormat(int ttiMs, int codingChoice, int codingRate, int rateMatching,
				int crcBits, List<DynamicFormat> dynamic) {
			this.ttiMs = ttiMs; this.codingChoice = codingChoice; this.codingRate = codingRate;
			this.rateMatching = rateMatching; this.crcBits = crcBits; this.dynamic = dynamic;
		}
		DynamicFormat primary() { return dynamic.isEmpty() ? null : dynamic.get(0); }
	}

	static final class DynamicFormat {
		final int blockBits, maximumBlocks;
		DynamicFormat(int blockBits, int maximumBlocks) {
			this.blockBits = blockBits; this.maximumBlocks = maximumBlocks;
		}
	}

	private static final class Bits {
		final int[] data;
		int at;
		Bits(int[] data) { this.data = data; }
		boolean flag() throws DecodeException { return read(1) != 0; }
		void skip(int count) throws DecodeException {
			if (count < 0 || at + count > data.length) throw new DecodeException();
			at += count;
		}
		int choice(int choices) throws DecodeException {
			int value = read(width(choices));
			if (value >= choices) throw new DecodeException();
			return value;
		}
		int length(int maximum) throws DecodeException { return integer(1, maximum); }
		int integer(int minimum, int maximum) throws DecodeException {
			int value = read(width(maximum - minimum + 1));
			if (value > maximum - minimum) throw new DecodeException();
			return minimum + value;
		}
		int read(int count) throws DecodeException {
			if (count < 0 || at + count > data.length || count > 30) throw new DecodeException();
			int value = 0;
			while (count-- > 0) value = value << 1 | data[at++];
			return value;
		}
		private static int width(int values) {
			int width = 0, range = Math.max(1, values - 1);
			while (range > 0) { width++; range >>>= 1; }
			return values <= 1 ? 0 : width;
		}
	}

	private static final class DecodeException extends Exception {
		private static final long serialVersionUID = 1L;
	}
}
