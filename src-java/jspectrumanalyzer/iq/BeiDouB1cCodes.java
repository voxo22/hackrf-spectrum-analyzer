package jspectrumanalyzer.iq;

/** BeiDou B1C truncated-Weil primary-code generator (BDS-SIS-ICD-B1C-1.0, Tables 5-2/5-3). */
final class BeiDouB1cCodes {
	static final int PRN_COUNT = 63, CODE_CHIPS = 10_230;
	private static final int WEIL_LENGTH = 10_243;
	private static final int[] DATA_W = {
		2678,4802,958,859,3843,2232,124,4352,1816,1126,1860,4800,2267,424,4192,4333,
		2656,4148,243,1330,1593,1470,882,3202,5095,2546,1733,4795,4577,1627,3638,2553,
		3646,1087,1843,216,2245,726,1966,670,4130,53,4830,182,2181,2006,1080,2288,
		2027,271,915,497,139,3693,2054,4342,3342,2592,1007,310,4203,455,4318 };
	private static final int[] DATA_P = {
		699,694,7318,2127,715,6682,7850,5495,1162,7682,6792,9973,6596,2092,19,10151,
		6297,5766,2359,7136,1706,2128,6827,693,9729,1620,6805,534,712,1929,5355,6139,
		6339,1470,6867,7851,1162,7659,1156,2672,6043,2862,180,2663,6940,1645,1582,951,
		6878,7701,1823,2391,2606,822,6403,239,442,6769,2560,2502,5072,7268,341 };
	private static final int[] PILOT_W = {
		796,156,4198,3941,1374,1338,1833,2521,3175,168,2715,4408,3160,2796,459,3594,
		4813,586,1428,2371,2285,3377,4965,3779,4547,1646,1430,607,2118,4709,1149,3283,
		2473,1006,3670,1817,771,2173,740,1433,2458,3459,2155,1205,413,874,2463,1106,
		1590,3873,4026,4272,3556,128,1200,130,4494,1871,3073,4386,4098,1923,1176 };
	private static final int[] PILOT_P = {
		7575,2369,5688,539,2270,7306,6457,6254,5644,7119,1402,5557,5764,1073,7001,5910,
		10060,2710,1546,6887,1883,5613,5062,1038,10170,6484,1718,2535,1158,526,7331,
		5844,6423,6968,1280,1838,1989,6468,2091,1581,1453,6252,7122,7711,7216,2113,
		1095,1628,1713,6102,6123,6070,1115,8047,6795,2575,53,1729,6388,682,5565,7160,2277 };
	private static final int[] LEGENDRE = legendre();
	private static final int[][] DATA = new int[PRN_COUNT][], PILOT = new int[PRN_COUNT][];

	private BeiDouB1cCodes() { }

	static synchronized int[] primaryCode(int prn, boolean pilot) {
		if (prn < 1 || prn > PRN_COUNT) throw new IllegalArgumentException("BeiDou B1C PRN must be 1..63");
		int[][] cache = pilot ? PILOT : DATA; int index = prn - 1;
		if (cache[index] == null) cache[index] = generate(pilot ? PILOT_W[index] : DATA_W[index],
				pilot ? PILOT_P[index] : DATA_P[index]);
		return cache[index].clone();
	}

	private static int[] generate(int phaseDifference, int truncationPoint) {
		int[] code = new int[CODE_CHIPS];
		for (int chip = 0; chip < code.length; chip++) {
			int position = (chip + truncationPoint - 1) % WEIL_LENGTH;
			int bit = LEGENDRE[position] ^ LEGENDRE[(position + phaseDifference) % WEIL_LENGTH];
			code[chip] = bit == 0 ? 1 : -1;
		}
		return code;
	}

	private static int[] legendre() {
		int[] sequence = new int[WEIL_LENGTH];
		for (int value = 1; value < WEIL_LENGTH; value++)
			sequence[(int) ((long) value * value % WEIL_LENGTH)] = 1;
		return sequence;
	}
}
