package jspectrumanalyzer.iq;

/** Galileo I/NAV word 1-5 assembly, scaling, consistency and freshness tests. */
public final class GalileoNavigationDataTest {
	private GalileoNavigationDataTest() { }

	public static void main(String[] args) {
		GalileoNavigationData data = new GalileoNavigationData(12);
		data.accept(word1(341), 100L);
		data.accept(word2(341), 200L);
		GalileoNavigationData.Snapshot partial = data.snapshot(200L);
		require(partial.wordCount == 2 && partial.ephemeris == null, "partial ephemeris set");

		data.accept(word3(342), 300L);
		require(data.snapshot(300L).issueMismatch, "IODnav mismatch detected");
		data.accept(word3(341), 400L);
		data.accept(word4(341, 12), 500L);
		GalileoNavigationData.Snapshot complete = data.snapshot(500L);
		require(!complete.issueMismatch && !complete.svidMismatch, "coherent word set");
		require("Complete".equals(complete.ephemerisText()), "complete ephemeris label");
		GalileoNavigationData.Ephemeris e = complete.ephemeris;
		require(e != null && e.iodNav == 341 && e.svid == 12, "IODnav and SVID");
		require(e.toeSeconds == 6000 && e.tocSeconds == 6060, "Toe/Toc scaling");
		near(e.meanAnomaly, -.5d * Math.PI, 1e-12, "M0 scaling");
		near(e.eccentricity, .03125d, 1e-12, "eccentricity scaling");
		near(e.sqrtA, 5153.5d, 1e-9, "sqrt(A) scaling");
		near(e.omega0, .5d * Math.PI, 1e-12, "Omega0 scaling");
		near(e.inclination, .375d * Math.PI, 1e-12, "inclination scaling");
		near(e.argumentPerigee, -.25d * Math.PI, 1e-12, "argument of perigee scaling");
		near(e.crc, 320d, 1e-12, "Crc scaling");
		near(e.crs, -160d, 1e-12, "Crs scaling");
		near(e.af0, Math.scalb(-1234567d, -34), 1e-18, "af0 scaling");
		require(e.sisaIndex == 42, "SISA extraction");

		data.accept(word5(), 600L);
		GalileoNavigationData.Snapshot status = data.snapshot(600L);
		require(status.healthCode == 2 && status.dataValidity == 0, "E1-B SHS/DVS");
		require(status.weekModulo4096 == 1234 && status.towSeconds == 345678, "GST extraction");
		near(status.bgdE1E5bSeconds, Math.scalb(-20d, -32), 1e-20, "E1/E5b BGD scaling");
		require("EOM / data valid".equals(status.healthText()), "health label");

		GalileoNavigationData wrongSvid = new GalileoNavigationData(12);
		wrongSvid.accept(word1(7), 0L); wrongSvid.accept(word2(7), 0L);
		wrongSvid.accept(word3(7), 0L); wrongSvid.accept(word4(7, 11), 0L);
		require(wrongSvid.snapshot(0L).svidMismatch && wrongSvid.snapshot(0L).ephemeris == null,
				"word-4 SVID mismatch");

		GalileoNavigationData.Snapshot stale = data.snapshot(23_200L);
		require(stale.wordCount == 0 && stale.healthCode < 0 && stale.lastWordType < 0,
				"90-second navigation freshness");
		System.out.println("GalileoNavigationDataTest: OK");
	}

	private static GalileoInavDecoder.Page word1(int iod) {
		int[] bits = base(1, iod);
		putUnsigned(bits, 16, 14, 100); putSigned(bits, 30, 32, -1073741824L);
		putUnsigned(bits, 62, 32, 0x10000000L); putUnsigned(bits, 94, 32, Math.round(5153.5d * (1L << 19)));
		return page(1, bits);
	}

	private static GalileoInavDecoder.Page word2(int iod) {
		int[] bits = base(2, iod);
		putSigned(bits, 16, 32, 0x40000000L); putSigned(bits, 48, 32, 0x30000000L);
		putSigned(bits, 80, 32, -0x20000000L); putSigned(bits, 112, 14, -10L);
		return page(2, bits);
	}

	private static GalileoInavDecoder.Page word3(int iod) {
		int[] bits = base(3, iod);
		putSigned(bits, 16, 24, -1200L); putSigned(bits, 40, 16, 321L);
		putSigned(bits, 56, 16, 100L); putSigned(bits, 72, 16, -200L);
		putSigned(bits, 88, 16, 10240L); putSigned(bits, 104, 16, -5120L);
		putUnsigned(bits, 120, 8, 42L); return page(3, bits);
	}

	private static GalileoInavDecoder.Page word4(int iod, int svid) {
		int[] bits = base(4, iod);
		putUnsigned(bits, 16, 6, svid); putSigned(bits, 22, 16, 30L); putSigned(bits, 38, 16, -40L);
		putUnsigned(bits, 54, 14, 101L); putSigned(bits, 68, 31, -1234567L);
		putSigned(bits, 99, 21, 3456L); putSigned(bits, 120, 6, -5L); return page(4, bits);
	}

	private static GalileoInavDecoder.Page word5() {
		int[] bits = new int[128]; putUnsigned(bits, 0, 6, 5L);
		putSigned(bits, 47, 10, 12L); putSigned(bits, 57, 10, -20L);
		putUnsigned(bits, 69, 2, 2L); putUnsigned(bits, 72, 1, 0L);
		putUnsigned(bits, 73, 12, 1234L); putUnsigned(bits, 85, 20, 345678L);
		return page(5, bits);
	}

	private static int[] base(int type, int iod) {
		int[] bits = new int[128]; putUnsigned(bits, 0, 6, type); putUnsigned(bits, 6, 10, iod); return bits;
	}

	private static GalileoInavDecoder.Page page(int type, int[] bits) {
		return new GalileoInavDecoder.Page(true, type, bits, 1d, 1d);
	}

	private static void putSigned(int[] bits, int offset, int count, long value) {
		long mask = (1L << count) - 1L; putUnsigned(bits, offset, count, value & mask);
	}

	private static void putUnsigned(int[] bits, int offset, int count, long value) {
		for (int index = 0; index < count; index++) bits[offset + index] = (int) ((value >>> (count - 1 - index)) & 1L);
	}

	private static void near(double actual, double expected, double tolerance, String message) {
		if (Math.abs(actual - expected) > tolerance) throw new AssertionError(message + ": " + actual + " != " + expected);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
