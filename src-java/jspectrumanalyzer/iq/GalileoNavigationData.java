package jspectrumanalyzer.iq;

/**
 * Collects CRC-checked Galileo E1-B I/NAV words and assembles one coherent
 * clock-and-ephemeris data set. Bit fields and scales follow the Galileo OS
 * SIS ICD, word types 1-5 and tables 67, 69 and 70.
 */
final class GalileoNavigationData {
	private static final long MAX_AGE_SYMBOLS = 90L * 250L;
	private final int expectedSvid;
	private final GalileoInavDecoder.Page[] ephemerisWords = new GalileoInavDecoder.Page[4];
	private final long[] ephemerisReceivedAt = new long[4];
	private GalileoInavDecoder.Page statusWord;
	private long statusReceivedAt;
	private GalileoInavDecoder.Page latestWord;
	private long latestReceivedAt;

	GalileoNavigationData(int expectedSvid) { this.expectedSvid = expectedSvid; }

	void accept(GalileoInavDecoder.Page page, long nowSymbols) {
		if (page == null || !page.valid || page.word.length < 128) return;
		latestWord = page; latestReceivedAt = nowSymbols;
		if (page.wordType >= 1 && page.wordType <= 4) {
			int index = page.wordType - 1;
			ephemerisWords[index] = page; ephemerisReceivedAt[index] = nowSymbols;
		} else if (page.wordType == 5) {
			statusWord = page; statusReceivedAt = nowSymbols;
		}
	}

	Snapshot snapshot(long nowSymbols) {
		GalileoInavDecoder.Page[] words = new GalileoInavDecoder.Page[4];
		int count = 0, referenceIod = -1;
		boolean issueMismatch = false;
		for (int index = 0; index < words.length; index++) {
			if (!fresh(ephemerisWords[index], ephemerisReceivedAt[index], nowSymbols)) continue;
			words[index] = ephemerisWords[index]; count++;
			int iod = (int) unsigned(words[index].word, 6, 10);
			if (referenceIod < 0) referenceIod = iod;
			else if (iod != referenceIod) issueMismatch = true;
		}
		boolean svidMismatch = words[3] != null && expectedSvid > 0
				&& unsigned(words[3].word, 16, 6) != expectedSvid;
		Ephemeris ephemeris = count == 4 && !issueMismatch && !svidMismatch
				? new Ephemeris(words[0], words[1], words[2], words[3]) : null;

		int health = -1, dataValidity = -1, week = -1, tow = -1;
		double bgdE1E5a = Double.NaN, bgdE1E5b = Double.NaN;
		if (fresh(statusWord, statusReceivedAt, nowSymbols)) {
			bgdE1E5a = scaled(statusWord.word, 47, 10, -32);
			bgdE1E5b = scaled(statusWord.word, 57, 10, -32);
			health = (int) unsigned(statusWord.word, 69, 2);
			dataValidity = (int) unsigned(statusWord.word, 72, 1);
			week = (int) unsigned(statusWord.word, 73, 12);
			tow = (int) unsigned(statusWord.word, 85, 20);
		}
		int lastWordType = fresh(latestWord, latestReceivedAt, nowSymbols) ? latestWord.wordType : -1;
		return new Snapshot(lastWordType, count, issueMismatch, svidMismatch,
				health, dataValidity, week, tow, bgdE1E5a, bgdE1E5b, ephemeris);
	}

	private static boolean fresh(GalileoInavDecoder.Page page, long receivedAt, long nowSymbols) {
		long age = nowSymbols - receivedAt;
		return page != null && page.valid && age >= 0L && age <= MAX_AGE_SYMBOLS;
	}

	private static long unsigned(int[] bits, int offset, int count) {
		long value = 0L;
		for (int index = 0; index < count; index++) value = (value << 1) | bits[offset + index];
		return value;
	}

	private static long signed(int[] bits, int offset, int count) {
		long value = unsigned(bits, offset, count);
		return (value & (1L << (count - 1))) == 0L ? value : value - (1L << count);
	}

	private static double scaled(int[] bits, int offset, int count, int exponent) {
		return Math.scalb((double) signed(bits, offset, count), exponent);
	}

	private static double angle(int[] bits, int offset, int count, int exponent) {
		return scaled(bits, offset, count, exponent) * Math.PI;
	}

	static final class Snapshot {
		final int lastWordType, wordCount, healthCode, dataValidity, weekModulo4096, towSeconds;
		final double bgdE1E5aSeconds, bgdE1E5bSeconds;
		final boolean issueMismatch, svidMismatch;
		final Ephemeris ephemeris;
		Snapshot(int lastWordType, int wordCount, boolean issueMismatch, boolean svidMismatch,
				int healthCode, int dataValidity, int weekModulo4096, int towSeconds,
				double bgdE1E5aSeconds, double bgdE1E5bSeconds, Ephemeris ephemeris) {
			this.lastWordType = lastWordType; this.wordCount = wordCount;
			this.issueMismatch = issueMismatch; this.svidMismatch = svidMismatch;
			this.healthCode = healthCode; this.dataValidity = dataValidity;
			this.weekModulo4096 = weekModulo4096; this.towSeconds = towSeconds;
			this.bgdE1E5aSeconds = bgdE1E5aSeconds; this.bgdE1E5bSeconds = bgdE1E5bSeconds;
			this.ephemeris = ephemeris;
		}
		static Snapshot waiting() { return new Snapshot(-1, 0, false, false, -1, -1, -1, -1,
				Double.NaN, Double.NaN, null); }
		boolean hasValidatedWord() { return lastWordType >= 0; }
		String healthText() {
			if (healthCode < 0) return "--";
			String health = healthCode == 0 ? "Signal OK" : healthCode == 1 ? "Out of service"
					: healthCode == 2 ? "EOM" : "Test";
			return health + (dataValidity == 0 ? " / data valid" : " / no guarantee");
		}
		String ephemerisText() {
			if (ephemeris != null) return "Complete";
			if (issueMismatch) return "IOD mismatch";
			if (svidMismatch) return "SVID mismatch";
			return wordCount == 0 ? "--" : wordCount + "/4 words";
		}
	}

	/** Angles are radians, angular rates rad/s, distances metres, times seconds. */
	static final class Ephemeris {
		final int svid, iodNav, toeSeconds, tocSeconds, sisaIndex;
		final double meanAnomaly, eccentricity, sqrtA;
		final double omega0, inclination, argumentPerigee, inclinationDot;
		final double omegaDot, deltaN, cuc, cus, crc, crs, cic, cis;
		final double af0, af1, af2;
		Ephemeris(GalileoInavDecoder.Page w1, GalileoInavDecoder.Page w2,
				GalileoInavDecoder.Page w3, GalileoInavDecoder.Page w4) {
			iodNav = (int) unsigned(w1.word, 6, 10);
			toeSeconds = (int) unsigned(w1.word, 16, 14) * 60;
			meanAnomaly = angle(w1.word, 30, 32, -31);
			eccentricity = Math.scalb((double) unsigned(w1.word, 62, 32), -33);
			sqrtA = Math.scalb((double) unsigned(w1.word, 94, 32), -19);

			omega0 = angle(w2.word, 16, 32, -31);
			inclination = angle(w2.word, 48, 32, -31);
			argumentPerigee = angle(w2.word, 80, 32, -31);
			inclinationDot = angle(w2.word, 112, 14, -43);

			omegaDot = angle(w3.word, 16, 24, -43);
			deltaN = angle(w3.word, 40, 16, -43);
			cuc = scaled(w3.word, 56, 16, -29);
			cus = scaled(w3.word, 72, 16, -29);
			crc = scaled(w3.word, 88, 16, -5);
			crs = scaled(w3.word, 104, 16, -5);
			sisaIndex = (int) unsigned(w3.word, 120, 8);

			svid = (int) unsigned(w4.word, 16, 6);
			cic = scaled(w4.word, 22, 16, -29);
			cis = scaled(w4.word, 38, 16, -29);
			tocSeconds = (int) unsigned(w4.word, 54, 14) * 60;
			af0 = scaled(w4.word, 68, 31, -34);
			af1 = scaled(w4.word, 99, 21, -46);
			af2 = scaled(w4.word, 120, 6, -59);
		}
	}
}
