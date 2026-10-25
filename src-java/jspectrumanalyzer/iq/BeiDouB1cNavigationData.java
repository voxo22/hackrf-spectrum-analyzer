package jspectrumanalyzer.iq;

import java.util.Arrays;

/** Decodes one aligned 18-second B-CNAV1 frame and retains validated navigation data. */
final class BeiDouB1cNavigationData {
	private static final int FRAME_SYMBOLS = 1800, SF1_SYMBOLS = 72, SF2_SYMBOLS = 1200, SF3_SYMBOLS = 528;
	private static final int SF2_BITS = 600, SF3_BITS = 264, CRC_BITS = 24;
	private static final double A_REF_MEO = 27_906_100d, A_REF_IGSO = 42_162_200d;
	private int frameCount; private long lastFrameSymbol = Long.MIN_VALUE;
	private FullFrame latest = FullFrame.waiting(); private Ephemeris ephemeris;

	void reset() { frameCount = 0; lastFrameSymbol = Long.MIN_VALUE; latest = FullFrame.waiting(); ephemeris = null; }
	void accept(double[] symbols, int expectedPrn, long frameStartSymbol) {
		FullFrame decoded = decode(symbols, expectedPrn, frameStartSymbol);
		if (!decoded.valid) return;
		latest = decoded; lastFrameSymbol = frameStartSymbol + FRAME_SYMBOLS - 1L; frameCount++;
		if (decoded.ephemeris != null) ephemeris = decoded.ephemeris;
	}
	Snapshot snapshot(long nowSymbol) {
		long age = lastFrameSymbol == Long.MIN_VALUE ? Long.MAX_VALUE : nowSymbol - lastFrameSymbol;
		boolean fresh = age >= 0L && age <= 18_000L;
		return new Snapshot(fresh ? latest : FullFrame.waiting(), frameCount, ephemeris);
	}

	static FullFrame decode(double[] symbols, int expectedPrn, long frameStartSymbol) {
		if (symbols == null || symbols.length < FRAME_SYMBOLS) return FullFrame.waiting();
		FullFrame normal = decodePolarity(symbols, expectedPrn, frameStartSymbol, 1d);
		return normal.valid ? normal : decodePolarity(symbols, expectedPrn, frameStartSymbol, -1d);
	}
	private static FullFrame decodePolarity(double[] symbols, int expectedPrn, long frameStartSymbol, double polarity) {
		double sum = 0d; for (int i = 0; i < FRAME_SYMBOLS; i++) { if (!Double.isFinite(symbols[i])) return FullFrame.waiting(); sum += Math.abs(symbols[i]); }
		if (sum <= 0d) return FullFrame.waiting();
		double scale = polarity * 4d * FRAME_SYMBOLS / sum; double[] llr = new double[FRAME_SYMBOLS];
		for (int i = 0; i < FRAME_SYMBOLS; i++) llr[i] = symbols[i] * scale;
		BeiDouB1cNavDecoder.Frame sf1 = BeiDouB1cNavDecoder.decode(llr, expectedPrn, frameStartSymbol);
		if (!sf1.valid) return FullFrame.waiting();
		double[] sf2 = new double[SF2_SYMBOLS], sf3 = new double[SF3_SYMBOLS]; deinterleave(llr, sf2, sf3);
		int[] word2 = BeiDouB1cLdpc.decodeLong(sf2), word3 = BeiDouB1cLdpc.decodeShort(sf3);
		int[] data2 = crcHalf(word2, SF2_BITS); if (data2 == null) return FullFrame.waiting();
		int[] data3 = crcHalf(word3, SF3_BITS); boolean sf3Valid = data3 != null;
		Ephemeris eph = parseEphemeris(data2, sf1, expectedPrn);
		int page = sf3Valid ? (int) unsigned(data3, 0, 6) : -1;
		int health = sf3Valid ? (int) unsigned(data3, 6, 2) : -1;
		return new FullFrame(true, sf1.prn, sf1.soh, sf1.secondsOfHour, frameStartSymbol,
				true, sf3Valid, page, health, eph, data2, sf3Valid ? data3 : null);
	}
	private static void deinterleave(double[] frame, double[] sf2, double[] sf3) {
		for (int input = 0; input < 1728; input++) {
			int row = input % 36, column = input / 36;
			if (row < 33) {
				int group = row / 3, remainder = row % 3;
				if (remainder < 2) sf2[(group * 2 + remainder) * 48 + column] = frame[SF1_SYMBOLS + input];
				else sf3[group * 48 + column] = frame[SF1_SYMBOLS + input];
			} else sf2[(22 + row - 33) * 48 + column] = frame[SF1_SYMBOLS + input];
		}
	}
	private static int[] crcHalf(int[] codeword, int length) {
		if (codeword == null || codeword.length < length * 2) return null;
		int[] first = Arrays.copyOfRange(codeword, 0, length); if (crcValid(first)) return first;
		int[] second = Arrays.copyOfRange(codeword, length, length * 2); return crcValid(second) ? second : null;
	}
	static boolean crcValid(int[] bits) {
		if (bits == null || bits.length <= CRC_BITS) return false;
		int crc = 0; for (int i = 0; i < bits.length - CRC_BITS; i++) {
			int feedback = ((crc >>> 23) & 1) ^ (bits[i] & 1); crc = (crc << 1) & 0xffffff;
			if (feedback != 0) crc ^= 0x864cfb;
		}
		return crc == (int) unsigned(bits, bits.length - CRC_BITS, CRC_BITS);
	}

	private static Ephemeris parseEphemeris(int[] bits, BeiDouB1cNavDecoder.Frame sf1, int expectedPrn) {
		int offset = 0, week = (int) unsigned(bits, offset, 13); offset += 13;
		int hour = (int) unsigned(bits, offset, 8); offset += 8;
		int iodc = (int) unsigned(bits, offset, 10); offset += 10;
		int iode = (int) unsigned(bits, offset, 8); offset += 8;
		int toe = (int) unsigned(bits, offset, 11) * 300; offset += 11;
		int satelliteType = (int) unsigned(bits, offset, 2); offset += 2;
		double deltaA = Math.scalb((double) signed(bits, offset, 26), -9); offset += 26;
		double aDot = Math.scalb((double) signed(bits, offset, 25), -21); offset += 25;
		double deltaN = Math.scalb((double) signed(bits, offset, 17), -44) * Math.PI; offset += 17;
		double deltaNDot = Math.scalb((double) signed(bits, offset, 23), -57) * Math.PI; offset += 23;
		double meanAnomaly = Math.scalb((double) signed(bits, offset, 33), -32) * Math.PI; offset += 33;
		double eccentricity = Math.scalb((double) unsigned(bits, offset, 33), -34); offset += 33;
		double argumentPerigee = Math.scalb((double) signed(bits, offset, 33), -32) * Math.PI; offset += 33;
		double omega0 = Math.scalb((double) signed(bits, offset, 33), -32) * Math.PI; offset += 33;
		double inclination = Math.scalb((double) signed(bits, offset, 33), -32) * Math.PI; offset += 33;
		double omegaDot = Math.scalb((double) signed(bits, offset, 19), -44) * Math.PI; offset += 19;
		double inclinationDot = Math.scalb((double) signed(bits, offset, 15), -44) * Math.PI; offset += 15;
		double cis = Math.scalb((double) signed(bits, offset, 16), -30); offset += 16;
		double cic = Math.scalb((double) signed(bits, offset, 16), -30); offset += 16;
		double crs = Math.scalb((double) signed(bits, offset, 24), -8); offset += 24;
		double crc = Math.scalb((double) signed(bits, offset, 24), -8); offset += 24;
		double cus = Math.scalb((double) signed(bits, offset, 21), -30); offset += 21;
		double cuc = Math.scalb((double) signed(bits, offset, 21), -30); offset += 21;
		int toc = (int) unsigned(bits, offset, 11) * 300; offset += 11;
		double af0 = Math.scalb((double) signed(bits, offset, 25), -34); offset += 25;
		double af1 = Math.scalb((double) signed(bits, offset, 22), -50); offset += 22;
		double af2 = Math.scalb((double) signed(bits, offset, 11), -66); offset += 11;
		double tgdB2ap = Math.scalb((double) signed(bits, offset, 12), -34); offset += 12;
		double iscB1cd = Math.scalb((double) signed(bits, offset, 12), -34); offset += 12;
		double tgdB1cp = Math.scalb((double) signed(bits, offset, 12), -34);
		if (iode != (iodc & 0xff) || eccentricity < 0d || eccentricity >= 1d) return null;
		double referenceA = satelliteType == 1 || satelliteType == 2 ? A_REF_IGSO : A_REF_MEO;
		return new Ephemeris(expectedPrn, week, hour * 3600 + sf1.secondsOfHour, iodc, iode,
				toe, toc, satelliteType, referenceA + deltaA, aDot, deltaN, deltaNDot,
				meanAnomaly, eccentricity, argumentPerigee, omega0, inclination, omegaDot,
				inclinationDot, cis, cic, crs, crc, cus, cuc, af0, af1, af2,
				tgdB2ap, iscB1cd, tgdB1cp);
	}
	private static long unsigned(int[] bits, int offset, int count) {
		long value = 0L; for (int index = 0; index < count; index++) value = (value << 1) | (bits[offset + index] & 1); return value;
	}
	private static long signed(int[] bits, int offset, int count) {
		long value = unsigned(bits, offset, count), sign = 1L << (count - 1); return (value & sign) == 0L ? value : value - (1L << count);
	}

	static final class Snapshot {
		final FullFrame frame; final int frameCount; final Ephemeris ephemeris;
		Snapshot(FullFrame frame, int frameCount, Ephemeris ephemeris) { this.frame = frame; this.frameCount = frameCount; this.ephemeris = ephemeris; }
		static Snapshot waiting() { return new Snapshot(FullFrame.waiting(), 0, null); }
		boolean hasValidatedFrame() { return frameCount > 0; }
		String healthText() { return frame.health < 0 ? "--" : frame.health == 0 ? "Signal OK" : "Health " + frame.health; }
		String ephemerisText() { return ephemeris == null ? hasValidatedFrame() ? "SF2 CRC valid / IOD mismatch" : "--" : "Complete"; }
	}
	static final class FullFrame {
		final boolean valid, sf2CrcValid, sf3CrcValid; final int prn, soh, secondsOfHour, pageId, health;
		final long frameStartSymbol; final Ephemeris ephemeris; final int[] sf2, sf3;
		FullFrame(boolean valid, int prn, int soh, int secondsOfHour, long frameStartSymbol,
				boolean sf2CrcValid, boolean sf3CrcValid, int pageId, int health,
				Ephemeris ephemeris, int[] sf2, int[] sf3) {
			this.valid = valid; this.prn = prn; this.soh = soh; this.secondsOfHour = secondsOfHour;
			this.frameStartSymbol = frameStartSymbol; this.sf2CrcValid = sf2CrcValid;
			this.sf3CrcValid = sf3CrcValid; this.pageId = pageId; this.health = health;
			this.ephemeris = ephemeris; this.sf2 = sf2; this.sf3 = sf3;
		}
		static FullFrame waiting() { return new FullFrame(false, -1, -1, -1, -1L, false, false, -1, -1, null, null, null); }
	}
	/** B-CNAV1 angles/rates are radians and rad/s; distances metres; times BDT seconds. */
	static final class Ephemeris {
		final int prn, week, towSeconds, iodc, iode, toeSeconds, tocSeconds, satelliteType;
		final double semiMajorAxis, semiMajorAxisRate, deltaN, deltaNDot, meanAnomaly,
			eccentricity, argumentPerigee, omega0, inclination, omegaDot, inclinationDot,
			cis, cic, crs, crc, cus, cuc, af0, af1, af2, tgdB2ap, iscB1cd, tgdB1cp;
		Ephemeris(int prn, int week, int towSeconds, int iodc, int iode, int toeSeconds, int tocSeconds,
				int satelliteType, double semiMajorAxis, double semiMajorAxisRate, double deltaN,
				double deltaNDot, double meanAnomaly, double eccentricity, double argumentPerigee,
				double omega0, double inclination, double omegaDot, double inclinationDot,
				double cis, double cic, double crs, double crc, double cus, double cuc,
				double af0, double af1, double af2, double tgdB2ap, double iscB1cd, double tgdB1cp) {
			this.prn=prn; this.week=week; this.towSeconds=towSeconds; this.iodc=iodc; this.iode=iode;
			this.toeSeconds=toeSeconds; this.tocSeconds=tocSeconds; this.satelliteType=satelliteType;
			this.semiMajorAxis=semiMajorAxis; this.semiMajorAxisRate=semiMajorAxisRate;
			this.deltaN=deltaN; this.deltaNDot=deltaNDot; this.meanAnomaly=meanAnomaly;
			this.eccentricity=eccentricity; this.argumentPerigee=argumentPerigee; this.omega0=omega0;
			this.inclination=inclination; this.omegaDot=omegaDot; this.inclinationDot=inclinationDot;
			this.cis=cis; this.cic=cic; this.crs=crs; this.crc=crc; this.cus=cus; this.cuc=cuc;
			this.af0=af0; this.af1=af1; this.af2=af2; this.tgdB2ap=tgdB2ap; this.iscB1cd=iscB1cd; this.tgdB1cp=tgdB1cp;
		}
	}
}
