package jspectrumanalyzer.iq;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Collects parity-checked LNAV subframes and assembles one coherent CEI set.
 * Bit fields and scales follow IS-GPS-200, section 20.3.3 and figure 20-1. */
final class GpsL1NavigationData {
	private static final long MAX_AGE_MS = 90_000L;
	private static final long MAX_UTC_AGE_MS = 1_800_000L;
	private static final int WEEK_SECONDS = 604_800;
	private final GpsL1LnavDecoder.Result[] frames = new GpsL1LnavDecoder.Result[3];
	private final long[] receivedAtMs = new long[3];
	private UtcModel utcModel;
	private long utcReceivedAtMs;

	void accept(GpsL1LnavDecoder.Result frame, long nowMs) {
		if (frame == null || !frame.valid) return;
		if (frame.subframeId == 4 && unsigned(frame, 48, 2) == 1 && unsigned(frame, 50, 6) == 56) {
			utcModel = new UtcModel(frame); utcReceivedAtMs = nowMs; return;
		}
		if (frame.subframeId < 1 || frame.subframeId > 3) return;
		int index = frame.subframeId - 1;
		frames[index] = frame;
		receivedAtMs[index] = nowMs;
	}

	Snapshot snapshot(long nowMs) {
		GpsL1LnavDecoder.Result[] valid = new GpsL1LnavDecoder.Result[3];
		int count = 0;
		for (int i = 0; i < valid.length; i++) {
			long age = nowMs - receivedAtMs[i];
			if (frames[i] != null && age >= 0 && age <= MAX_AGE_MS) { valid[i] = frames[i]; count++; }
		}
		int health = valid[0] == null ? -1 : (int) unsigned(valid[0], 64, 6);
		long utcAge = nowMs - utcReceivedAtMs;
		UtcModel utc = utcModel != null && utcAge >= 0 && utcAge <= MAX_UTC_AGE_MS ? utcModel : null;
		if (count != 3) return new Snapshot(health, count, false, false, null, utc);
		int iodc = ((int) unsigned(valid[0], 70, 2) << 8) | (int) unsigned(valid[0], 168, 8);
		int iode2 = (int) unsigned(valid[1], 48, 8), iode3 = (int) unsigned(valid[2], 216, 8);
		boolean issueMismatch = iode2 != iode3 || iode2 != (iodc & 0xff);
		boolean timeMismatch = distance(valid[0].timeOfWeekSeconds, valid[1].timeOfWeekSeconds) > 120
				|| distance(valid[0].timeOfWeekSeconds, valid[2].timeOfWeekSeconds) > 120
				|| distance(valid[1].timeOfWeekSeconds, valid[2].timeOfWeekSeconds) > 120;
		return new Snapshot(health, count, issueMismatch, timeMismatch,
				issueMismatch || timeMismatch ? null : new Ephemeris(valid[0], valid[1], valid[2], iodc, iode2), utc);
	}

	private static int distance(int a, int b) {
		int distance = Math.abs(a - b) % WEEK_SECONDS;
		return Math.min(distance, WEEK_SECONDS - distance);
	}
	/* The offsets below address the 24 data bits of each word, excluding parity. */
	private static long unsigned(GpsL1LnavDecoder.Result frame, int offset, int count) {
		long value = 0L;
		for (int bit = 0; bit < count; bit++) {
			int position = offset + bit;
			value = (value << 1) | frame.dataWords[position / 24][position % 24];
		}
		return value;
	}
	private static long signed(GpsL1LnavDecoder.Result frame, int offset, int count) {
		long value = unsigned(frame, offset, count);
		return (value & (1L << (count - 1))) == 0 ? value : value - (1L << count);
	}
	private static double scaled(GpsL1LnavDecoder.Result frame, int offset, int count, int exponent) {
		return Math.scalb((double) signed(frame, offset, count), exponent);
	}
	private static double angle(GpsL1LnavDecoder.Result frame, int offset, int count, int exponent) {
		return scaled(frame, offset, count, exponent) * Math.PI;
	}

	/** Calendar representation of GPS time, not UTC; LNAV supplies only ten week bits. */
	static String formatGpsTime(int weekModulo1024, double receiverTow) {
		return formatGpsTime(weekModulo1024, receiverTow, System.currentTimeMillis());
	}
	static String formatGpsTime(int weekModulo1024, double receiverTow, long referenceEpochMillis) {
		if (weekModulo1024 < 0 || weekModulo1024 >= 1024 || !Double.isFinite(receiverTow)
				|| Math.abs(receiverTow) > WEEK_SECONDS * 2d) return null;
		int weekOffset = (int) Math.floor(receiverTow / WEEK_SECONDS);
		double tow = receiverTow - weekOffset * (double) WEEK_SECONDS;
		long baseMillis = UtcModel.GPS_EPOCH_MILLIS + (weekModulo1024 + (long) weekOffset) * UtcModel.WEEK_MILLIS
				+ Math.round(tow * 1000d);
		long era = Math.round(((double) referenceEpochMillis - baseMillis) / UtcModel.ERA_MILLIS);
		if (era < 0 || era > 1000) return null;
		return GPS_FORMAT.format(Instant.ofEpochMilli(baseMillis + era * UtcModel.ERA_MILLIS));
	}
	private static final DateTimeFormatter GPS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS 'GPS'", Locale.US)
			.withZone(ZoneOffset.UTC);

	static final class Snapshot {
		final int healthCode, subframeCount;
		final boolean issueMismatch, timeMismatch;
		final Ephemeris ephemeris;
		final UtcModel utc;
		Snapshot(int healthCode, int subframeCount, boolean issueMismatch, boolean timeMismatch, Ephemeris ephemeris, UtcModel utc) {
			this.healthCode = healthCode; this.subframeCount = subframeCount;
			this.issueMismatch = issueMismatch; this.timeMismatch = timeMismatch; this.ephemeris = ephemeris; this.utc = utc;
		}
		String healthText() { return healthCode < 0 ? "--" : healthCode == 0 ? "Healthy" : String.format(java.util.Locale.US, "0x%02X", healthCode); }
		String ephemerisText() {
			if (ephemeris != null) return "Complete";
			if (issueMismatch) return "IOD mismatch";
			if (timeMismatch) return "Time mismatch";
			return subframeCount == 0 ? "--" : subframeCount + "/3 SF";
		}
	}

	/** UTC parameters from LNAV SF4 page 18; dates need an external week-era reference. */
	static final class UtcModel {
		private static final long GPS_EPOCH_MILLIS = 315_964_800_000L;
		private static final long WEEK_MILLIS = WEEK_SECONDS * 1000L;
		private static final long ERA_MILLIS = 1024L * WEEK_MILLIS;
		private static final DateTimeFormatter UTC_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS 'UTC'", Locale.US)
				.withZone(ZoneOffset.UTC);
		final double a0, a1;
		final int referenceSeconds, referenceWeek, leapSeconds, futureLeapSeconds;
		UtcModel(GpsL1LnavDecoder.Result frame) {
			a1 = scaled(frame, 120, 24, -50); a0 = scaled(frame, 144, 32, -30);
			referenceSeconds = (int) unsigned(frame, 176, 8) * 4096;
			referenceWeek = (int) unsigned(frame, 184, 8);
			leapSeconds = (int) signed(frame, 192, 8); futureLeapSeconds = (int) signed(frame, 216, 8);
		}
		String format(int weekModulo1024, double receiverTow) {
			return format(weekModulo1024, receiverTow, System.currentTimeMillis());
		}
		String format(int weekModulo1024, double receiverTow, long referenceEpochMillis) {
			if (weekModulo1024 < 0 || weekModulo1024 >= 1024 || !Double.isFinite(receiverTow)
					|| Math.abs(receiverTow) > WEEK_SECONDS * 2d || referenceSeconds >= WEEK_SECONDS || leapSeconds != futureLeapSeconds)
				return null;
			int weekOffset = (int) Math.floor(receiverTow / WEEK_SECONDS);
			double tow = receiverTow - weekOffset * (double) WEEK_SECONDS;
			int weekDifference = ((weekModulo1024 + weekOffset - referenceWeek + 128) & 255) - 128;
			double delta = leapSeconds + a0 + a1 * (tow - referenceSeconds + (double) WEEK_SECONDS * weekDifference);
			if (!Double.isFinite(delta) || Math.abs(delta) > 128d) return null;
			long millis = Math.round((tow - delta) * 1000d);
			long baseMillis = GPS_EPOCH_MILLIS + (weekModulo1024 + (long) weekOffset) * WEEK_MILLIS + millis;
			// LNAV only broadcasts 10 week bits. Select the 1024-week era closest to the reference clock.
			long era = Math.round(((double) referenceEpochMillis - baseMillis) / ERA_MILLIS);
			if (era < 0 || era > 1000) return null;
			return UTC_FORMAT.format(Instant.ofEpochMilli(baseMillis + era * ERA_MILLIS));
		}
	}

	/** Angles are radians, angular rates rad/s, distances metres, times seconds. */
	static final class Ephemeris {
		final int weekModulo1024, healthCode, uraIndex, iodc, iode, toeSeconds, tocSeconds;
		final boolean extendedFit;
		final double tgdSeconds, af0, af1, af2;
		final double crs, deltaN, meanAnomaly, cuc, eccentricity, cus, sqrtA;
		final double cic, omega0, cis, inclination, crc, argumentPerigee, omegaDot, inclinationDot;
		Ephemeris(GpsL1LnavDecoder.Result s1, GpsL1LnavDecoder.Result s2, GpsL1LnavDecoder.Result s3, int iodc, int iode) {
			weekModulo1024 = (int) unsigned(s1, 48, 10); uraIndex = (int) unsigned(s1, 60, 4);
			healthCode = (int) unsigned(s1, 64, 6); this.iodc = iodc; this.iode = iode;
			tgdSeconds = scaled(s1, 160, 8, -31); tocSeconds = (int) unsigned(s1, 176, 16) * 16;
			af2 = scaled(s1, 192, 8, -55); af1 = scaled(s1, 200, 16, -43); af0 = scaled(s1, 216, 22, -31);
			crs = scaled(s2, 56, 16, -5); deltaN = angle(s2, 72, 16, -43); meanAnomaly = angle(s2, 88, 32, -31);
			cuc = scaled(s2, 120, 16, -29); eccentricity = Math.scalb((double) unsigned(s2, 136, 32), -33);
			cus = scaled(s2, 168, 16, -29); sqrtA = Math.scalb((double) unsigned(s2, 184, 32), -19);
			toeSeconds = (int) unsigned(s2, 216, 16) * 16; extendedFit = unsigned(s2, 232, 1) != 0;
			cic = scaled(s3, 48, 16, -29); omega0 = angle(s3, 64, 32, -31);
			cis = scaled(s3, 96, 16, -29); inclination = angle(s3, 112, 32, -31);
			crc = scaled(s3, 144, 16, -5); argumentPerigee = angle(s3, 160, 32, -31);
			omegaDot = angle(s3, 192, 24, -43); inclinationDot = angle(s3, 224, 14, -43);
		}
	}
}
