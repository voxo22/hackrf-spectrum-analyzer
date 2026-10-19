package jspectrumanalyzer.iq;

/** Parity-checked SF 1/2/3 assembly, signed fields, issue and lifetime tests. */
public final class GpsL1NavigationDataTest {
	public static void main(String[] args) {
		GpsL1NavigationData cache = new GpsL1NavigationData();
		GpsL1LnavDecoder.Result s1 = sf1(345600, 0x2a5, 0), s2 = sf2(345606, 0xa5), s3 = sf3(345612, 0xa5);
		cache.accept(s1, 1000); cache.accept(s2, 2000);
		GpsL1NavigationData.Snapshot partial = cache.snapshot(2000);
		check(partial.subframeCount == 2 && partial.ephemeris == null && "Healthy".equals(partial.healthText()), "partial set");
		cache.accept(s3, 3000);
		GpsL1NavigationData.Snapshot complete = cache.snapshot(3000);
		GpsL1NavigationData.Ephemeris e = complete.ephemeris;
		check(e != null && e.iode == 0xa5 && e.iodc == 0x2a5 && e.weekModulo1024 == 400 && e.uraIndex == 3, "issue and clock fields");
		check(e.toeSeconds == 320000 && e.tocSeconds == 318400 && e.extendedFit, "reference times and fit");
		near(e.crs, -31.25, "signed Crs"); near(e.deltaN, Math.scalb(-1000d, -43) * Math.PI, "deltaN");
		near(e.meanAnomaly, Math.scalb(-123456789d, -31) * Math.PI, "split signed M0");
		near(e.cuc, Math.scalb(-123d, -29), "Cuc"); near(e.cus, Math.scalb(456d, -29), "Cus");
		near(e.eccentricity, Math.scalb(150000000L, -33), "split eccentricity");
		near(e.sqrtA, Math.scalb(2700000000L, -19), "unsigned split sqrtA");
		near(e.omega0, Math.scalb(-777777777d, -31) * Math.PI, "split signed omega0");
		near(e.cic, Math.scalb(-234d, -29), "Cic"); near(e.cis, Math.scalb(567d, -29), "Cis");
		near(e.inclination, Math.scalb(654321987d, -31) * Math.PI, "inclination"); near(e.crc, Math.scalb(-876d, -5), "Crc");
		near(e.argumentPerigee, Math.scalb(-987654321d, -31) * Math.PI, "argument of perigee");
		near(e.omegaDot, Math.scalb(-123456d, -43) * Math.PI, "omega dot");
		near(e.inclinationDot, Math.scalb(-2000d, -43) * Math.PI, "signed IDOT");
		near(e.af0, Math.scalb(-12345d, -31), "signed clock bias");
		near(e.af1, Math.scalb(-100d, -43), "clock drift"); near(e.af2, Math.scalb(-3d, -55), "clock drift rate");
		near(e.tgdSeconds, Math.scalb(-10d, -31), "group delay");
		cache.accept(sf3(345612, 0xa6), 4000);
		check(cache.snapshot(4000).issueMismatch && cache.snapshot(4000).ephemeris == null, "mismatched IODE");
		cache.accept(sf3(345900, 0xa5), 5000);
		check(cache.snapshot(5000).timeMismatch && cache.snapshot(5000).ephemeris == null, "inconsistent HOW time");
		check(cache.snapshot(100001).subframeCount == 0, "expired frames");
		GpsL1NavigationData rollover = new GpsL1NavigationData();
		rollover.accept(sf1(604794, 0x2a5, 1), 0); rollover.accept(sf2(0, 0xa5), 1000); rollover.accept(sf3(6, 0xa5), 2000);
		check(rollover.snapshot(2000).ephemeris != null && "0x01".equals(rollover.snapshot(2000).healthText()), "week rollover and nonzero health");
		int[] corrupt = GpsL1LnavDecoderTest.makeSubframe(1, 345600); corrupt[100] ^= 1;
		GpsL1NavigationData empty = new GpsL1NavigationData(); empty.accept(GpsL1LnavDecoder.decodeSubframe(corrupt), 0);
		check(empty.snapshot(0).subframeCount == 0, "invalid parity ignored");
		testUtc();
		testGpsTime();
		System.out.println("GPS LNAV ephemeris assembly, scaling, IOD, freshness and rollover tests passed");
	}

	private static void testGpsTime() {
		long reference = java.time.Instant.parse("2026-12-09T00:00:00Z").toEpochMilli();
		check("2026-12-10 00:00:00.000 GPS".equals(GpsL1NavigationData.formatGpsTime(400, 345600d, reference)), "GPS time without UTC page");
		check("2026-12-06 00:00:10.000 GPS".equals(GpsL1NavigationData.formatGpsTime(400, 10d, reference)), "GPS week start");
		check("2026-12-06 00:00:10.000 GPS".equals(GpsL1NavigationData.formatGpsTime(399, 604810d, reference)), "GPS week crossing");
		check("2026-12-06 00:00:00.000 GPS".equals(GpsL1NavigationData.formatGpsTime(399, 604799.9996d, reference)), "millisecond rounding across week");
		long rolloverReference = java.time.Instant.parse("2019-04-07T00:00:00Z").toEpochMilli();
		check("2019-04-07 00:00:10.000 GPS".equals(GpsL1NavigationData.formatGpsTime(0, 10d, rolloverReference)), "GPS era rollover");
		check("2019-04-07 00:00:10.000 GPS".equals(GpsL1NavigationData.formatGpsTime(1023, 604810d, rolloverReference)), "TOW across GPS era rollover");
		check("2019-04-06 23:59:59.990 GPS".equals(GpsL1NavigationData.formatGpsTime(0, -.01d, rolloverReference)), "negative TOW");
		check(GpsL1NavigationData.formatGpsTime(-1, 1d, reference) == null
				&& GpsL1NavigationData.formatGpsTime(1024, 1d, reference) == null
				&& GpsL1NavigationData.formatGpsTime(1, Double.NaN, reference) == null
				&& GpsL1NavigationData.formatGpsTime(1, Double.POSITIVE_INFINITY, reference) == null
				&& GpsL1NavigationData.formatGpsTime(1, 1_209_601d, reference) == null, "invalid GPS time rejected");
		System.out.println("GPS time, week and era rollover tests passed");
	}

	private static void testUtc() {
		GpsL1NavigationData cache = new GpsL1NavigationData();
		cache.accept(utcPage(55, 18, 18), 1000);
		check(cache.snapshot(1000).utc == null, "other SF4 pages are not UTC");
		cache.accept(utcPage(56, 18, 18), 2000);
		GpsL1NavigationData.UtcModel model = cache.snapshot(2000).utc;
		check(model != null, "UTC page available");
		near(model.a0, Math.scalb(-1024d, -30), "signed UTC A0");
		near(model.a1, Math.scalb(17d, -50), "signed UTC A1");
		check(model.referenceSeconds == 409600 && model.referenceWeek == 144 && model.leapSeconds == 18, "UTC fields");
		long reference = java.time.Instant.parse("2026-12-09T00:00:00Z").toEpochMilli();
		check("2026-12-09 23:59:42.000 UTC".equals(model.format(400, 345600d, reference)), "UTC date and leap seconds");
		check("2026-12-05 23:59:52.000 UTC".equals(model.format(400, 10d, reference)), "UTC week rollover");
		check("2026-12-05 23:59:52.000 UTC".equals(model.format(399, 604810d, reference)), "GPS week rollover");
		long rolloverReference = java.time.Instant.parse("2019-04-07T00:00:00Z").toEpochMilli();
		check("2019-04-06 23:59:52.000 UTC".equals(model.format(0, 10d, rolloverReference)), "1024-week era rollover");
		check("2019-04-06 23:59:52.000 UTC".equals(model.format(1023, 604810d, rolloverReference)), "TOW across era rollover");
		check(model.format(1024, 10d, reference) == null, "invalid week rejected");
		check(cache.snapshot(1_802_001L).utc == null && cache.snapshot(1999).utc == null, "UTC freshness");
		cache.accept(utcPage(56, 18, 19), 3000);
		check(cache.snapshot(3000).utc.format(400, 345600d) == null, "pending leap is not guessed");
		System.out.println("GPS LNAV UTC page, scaling, freshness and rollover tests passed");
	}
	private static GpsL1LnavDecoder.Result utcPage(int page, int leap, int futureLeap) {
		int[][] words = new int[10][24]; put(words, 48, 2, 1); put(words, 50, 6, page);
		put(words, 120, 24, 17); put(words, 144, 32, -1024); put(words, 176, 8, 100);
		put(words, 184, 8, 144); put(words, 192, 8, leap); put(words, 216, 8, futureLeap);
		return decode(4, 345600, words);
	}

	static GpsL1LnavDecoder.Result sf1(int tow, int iodc, int health) {
		int[][] words = new int[10][24]; put(words, 48, 10, 400); put(words, 60, 4, 3); put(words, 64, 6, health);
		put(words, 70, 2, iodc >>> 8); put(words, 168, 8, iodc); put(words, 176, 16, 19900);
		put(words, 160, 8, -10); put(words, 192, 8, -3); put(words, 200, 16, -100); put(words, 216, 22, -12345);
		return decode(1, tow, words);
	}
	static GpsL1LnavDecoder.Result sf2(int tow, int iode) {
		int[][] words = new int[10][24]; put(words, 48, 8, iode); put(words, 56, 16, -1000); put(words, 72, 16, -1000);
		put(words, 88, 32, -123456789); put(words, 120, 16, -123); put(words, 136, 32, 150000000);
		put(words, 168, 16, 456); put(words, 184, 32, 2700000000L);
		put(words, 216, 16, 20000); put(words, 232, 1, 1); return decode(2, tow, words);
	}
	static GpsL1LnavDecoder.Result sf3(int tow, int iode) {
		int[][] words = new int[10][24]; put(words, 48, 16, -234); put(words, 64, 32, -777777777); put(words, 96, 16, 567);
		put(words, 112, 32, 654321987); put(words, 144, 16, -876); put(words, 160, 32, -987654321);
		put(words, 192, 24, -123456); put(words, 216, 8, iode); put(words, 224, 14, -2000);
		return decode(3, tow, words);
	}
	private static GpsL1LnavDecoder.Result decode(int id, int tow, int[][] words) {
		GpsL1LnavDecoder.Result frame = GpsL1LnavDecoder.decodeSubframe(GpsL1LnavDecoderTest.makeSubframe(id, tow, words));
		check(frame.valid, "fixture parity"); return frame;
	}
	private static void put(int[][] words, int offset, int length, long value) {
		for (int bit = length - 1; bit >= 0; bit--) { int pos = offset + bit; words[pos / 24][pos % 24] = (int) (value & 1L); value >>= 1; }
	}
	private static void near(double actual, double expected, String message) {
		if (Math.abs(actual - expected) > Math.max(1e-20, Math.abs(expected) * 1e-12))
			throw new AssertionError(message + ": expected " + expected + ", got " + actual);
	}
	private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
