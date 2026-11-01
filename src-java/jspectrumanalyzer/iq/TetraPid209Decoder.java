package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Decoder for the observed PID-209 passenger-information update format. */
final class TetraPid209Decoder {
	private static final int ABSOLUTE_TIME_MARKER = 0x1000;
	private static final int ABSOLUTE_TIME_MASK = 0xf000;
	private static final int MINUTE_OF_DAY_MASK = 0x0fff;
	private static final int MINUTES_PER_DAY = 24 * 60;

	private TetraPid209Decoder() { }

	/** Returns the scheduled minute after midnight, or -1 for a relative/unknown value. */
	static int scheduledMinuteOfDay(int timeValue) {
		if ((timeValue & ABSOLUTE_TIME_MASK) != ABSOLUTE_TIME_MARKER) return -1;
		int minute = timeValue & MINUTE_OF_DAY_MASK;
		return minute < MINUTES_PER_DAY ? minute : -1;
	}

	static Update decode(TetraMacDecoder.SdsMessage message) {
		if (message == null || message.protocol != 209 || (message.lengthBits & 7) != 0)
			return null;
		byte[] data = pack(message.userDataBits);
		/* Observed arrival-list telegrams:
		 * D1 02 sequence 01 {0C|14}, seven-octet rows, 0A;
		 * D1 02 sequence 01 15, nine-octet rows; or
		 * D1 02 sequence 01 21 variant, nine-octet rows, 0A. */
		if (data.length < 6 || unsigned(data[0]) != 0xd1 || unsigned(data[1]) != 0x02
				|| unsigned(data[3]) != 0x01
				|| unsigned(data[data.length - 1]) != 0x0a) return null;
		int format = unsigned(data[4]);
		int rowLength = format == 0x15 || format == 0x21 ? 9
				: format == 0x14 || format == 0x0c ? 7 : 0;
		int rowStart = format == 0x21 ? 6 : 5;
		int variant = format == 0x21 ? unsigned(data[5]) : -1;
		if (rowLength == 0 || (data.length - rowStart - 1) % rowLength != 0) return null;
		List<Arrival> arrivals = new ArrayList<Arrival>();
		for (int offset = rowStart; offset + rowLength <= data.length - 1; offset += rowLength) {
			arrivals.add(new Arrival(u16(data, offset), u16(data, offset + 2),
					u16(data, offset + 4), unsigned(data[offset + 6]),
					rowLength == 9 ? u16(data, offset + 7) : -1));
		}
		return new Update(unsigned(data[2]), format, variant, arrivals);
	}

	private static byte[] pack(int[] bits) {
		byte[] result = new byte[(bits.length + 7) / 8];
		for (int n = 0; n < bits.length; n++)
			result[n / 8] |= (byte)((bits[n] & 1) << (7 - (n & 7)));
		return result;
	}

	private static int u16(byte[] data, int offset) {
		return unsigned(data[offset]) << 8 | unsigned(data[offset + 1]);
	}

	private static int unsigned(byte value) { return value & 0xff; }

	static final class Update {
		final int counterCandidate, formatByte, variantByte;
		final List<Arrival> arrivals;
		Update(int counterCandidate, int formatByte, int variantByte, List<Arrival> arrivals) {
			this.counterCandidate = counterCandidate;
			this.formatByte = formatByte;
			this.variantByte = variantByte;
			this.arrivals = Collections.unmodifiableList(new ArrayList<Arrival>(arrivals));
		}
		String summary() {
			StringBuilder text = new StringBuilder(String.format(Locale.US,
					"PID209 counter? %d format-byte 0x%02X candidate-rows", counterCandidate,
					formatByte));
			if (variantByte >= 0) text.append(String.format(Locale.US,
					" variant-byte 0x%02X", variantByte));
			for (Arrival arrival : arrivals) text.append(' ').append(arrival);
			return text.toString();
		}
	}

	static final class Arrival {
		final int fieldA, fieldB, timeValue, flags, extensionCode;
		Arrival(int fieldA, int fieldB, int timeValue, int flags,
				int extensionCode) {
			this.fieldA = fieldA;
			this.fieldB = fieldB;
			this.timeValue = timeValue;
			this.flags = flags;
			this.extensionCode = extensionCode;
		}
		@Override public String toString() {
			String time = timeValue <= 0xff ? Integer.toString(timeValue)
					: String.format(Locale.US, "0x%04X", timeValue);
			if (extensionCode < 0) return String.format(Locale.US,
					"[A? 0x%04X B? 0x%04X time? %s flags 0x%02X]",
					fieldA, fieldB, time, flags);
			if (extensionCode >= 0x20 && extensionCode <= 0x7e) return String.format(Locale.US,
					"[A? 0x%04X B? 0x%04X time? %s flags 0x%02X extra? %c]",
					fieldA, fieldB, time, flags, (char)extensionCode);
			return String.format(Locale.US,
					"[A? 0x%04X B? 0x%04X time? %s flags 0x%02X extra? 0x%04X]",
					fieldA, fieldB, time, flags, extensionCode);
		}
	}
}
