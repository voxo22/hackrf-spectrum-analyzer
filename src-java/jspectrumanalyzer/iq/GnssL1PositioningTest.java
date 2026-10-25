package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.List;

/** Galileo orbit and independent GPS/Galileo clock-bias positioning regressions. */
public final class GnssL1PositioningTest {
	private GnssL1PositioningTest() { }

	public static void main(String[] args) {
		testGalileoCircularOrbit();
		testBeiDouCircularOrbit();
		testMixedClockSolver();
		System.out.println("Galileo/BeiDou orbit and mixed GPS/Galileo positioning tests passed");
	}

	private static void testBeiDouCircularOrbit() {
		BeiDouB1cNavigationData.Ephemeris ephemeris = new BeiDouB1cNavigationData.Ephemeris(
				1, 0, 0, 0, 0, 0, 0, 3, 27_906_100d, 0d, 0d, 0d, 0d, 0d, 0d,
				0d, 0d, 0d, 0d, 0d, 0d, 0d, 0d, 0d, 0d, 0d, 0d, 0d, 0d, 0d, 0d);
		BeiDouB1cOrbit.State state = BeiDouB1cOrbit.atSatelliteTime(ephemeris, 0d);
		if (state == null) throw new AssertionError("BeiDou circular orbit rejected");
		near(state.x, 27_906_100d, .001d, "BeiDou circular x");
		near(state.y, 0d, .001d, "BeiDou circular y"); near(state.z, 0d, .001d, "BeiDou circular z");
		near(state.clockSeconds, 0d, 1e-18, "BeiDou clock");
		near(BeiDouB1cOrbit.weekDifference(1d - 604799d), 2d, 0d, "BeiDou week rollover");
		if (BeiDouB1cOrbit.atBdt(ephemeris, 14_401d) != null)
			throw new AssertionError("Expired BeiDou orbit was accepted");
	}

	private static void testGalileoCircularOrbit() {
		GalileoNavigationData.Ephemeris ephemeris = circularEphemeris();
		GalileoE1Orbit.State state = GalileoE1Orbit.atSatelliteTime(ephemeris, 0d, 0d);
		if (state == null) throw new AssertionError("Galileo circular orbit rejected");
		near(state.x, 16_777_216d, .001d, "Galileo circular x");
		near(state.y, 0d, .001d, "Galileo circular y");
		near(state.z, 0d, .001d, "Galileo circular z");
		near(state.clockSeconds, 0d, 1e-18, "Galileo clock");
		near(GalileoE1Orbit.weekDifference(1d - 604799d), 2d, 0d, "Galileo week rollover");
		if (GalileoE1Orbit.atGst(ephemeris, 14_401d, 0d) != null)
			throw new AssertionError("Expired Galileo orbit was accepted");
	}

	private static void testMixedClockSolver() {
		double latitude = Math.toRadians(48.1486d), longitude = Math.toRadians(17.1077d), height = 180d;
		double a = 6_378_137d, e2 = 6.69437999014e-3;
		double n = a / Math.sqrt(1d - e2 * Math.sin(latitude) * Math.sin(latitude));
		double rx = (n + height) * Math.cos(latitude) * Math.cos(longitude);
		double ry = (n + height) * Math.cos(latitude) * Math.sin(longitude);
		double rz = (n * (1d - e2) + height) * Math.sin(latitude);
		double receiverBias = 82_000d, galileoBias = 41_000d;
		double[][] satellites = {
				{ 15_600_000, 0, 21_000_000 }, { -15_600_000, 14_000_000, 21_000_000 },
				{ 18_760_000, 15_000_000, 17_000_000 }, { -13_000_000, -17_000_000, 17_000_000 },
				{ 21_000_000, -16_000_000, 0 }, { -21_000_000, 8_000_000, -15_000_000 }
		};
		List<GpsL1PositionSolver.Measurement> measurements = new ArrayList<GpsL1PositionSolver.Measurement>();
		for (int index = 0; index < satellites.length; index++) {
			double[] satellite = satellites[index];
			double range = 23_000_000d;
			for (int iteration = 0; iteration < 5; iteration++) {
				double angle = GpsL1Orbit.EARTH_ROTATION * range / GpsL1Orbit.LIGHT_SPEED;
				double sx = Math.cos(angle) * satellite[0] + Math.sin(angle) * satellite[1];
				double sy = -Math.sin(angle) * satellite[0] + Math.cos(angle) * satellite[1];
				range = Math.sqrt((rx - sx) * (rx - sx) + (ry - sy) * (ry - sy)
						+ (rz - satellite[2]) * (rz - satellite[2]));
			}
			boolean galileo = index >= 3;
			measurements.add(new GpsL1PositionSolver.Measurement((galileo ? "E" : "G") + (index + 1),
					index + 1, galileo, satellite[0], satellite[1], satellite[2],
					range + receiverBias + (galileo ? galileoBias : 0d)));
		}
		GpsL1PositionSolver.Solution solution = GpsL1PositionSolver.solve(measurements, 345678d, true);
		if (!solution.plausible) throw new AssertionError("Mixed position rejected: " + solution.state);
		near(solution.latitude, 48.1486d, 1e-6, "mixed latitude");
		near(solution.longitude, 17.1077d, 1e-6, "mixed longitude");
		near(solution.altitude, height, .1d, "mixed altitude");
		near(solution.clockMeters, receiverBias, .1d, "GPS receiver clock");
		near(solution.interSystemBiasMeters, galileoBias, .1d, "Galileo inter-system bias");
		if (solution.usedPrns.size() != 3 || solution.usedGalileoSvids.size() != 3
				|| solution.usedSatellites.size() != 6)
			throw new AssertionError("Mixed satellite identity bookkeeping");
		if (GpsL1PositionSolver.solve(measurements.subList(0, 4), 0d, true).plausible)
			throw new AssertionError("Four mixed measurements accepted for five unknowns");
	}

	private static GalileoNavigationData.Ephemeris circularEphemeris() {
		int[] one = word(1), two = word(2), three = word(3), four = word(4);
		putUnsigned(one, 94, 32, 2_147_483_648L); // sqrt(A) = 4096 m^(1/2)
		putUnsigned(four, 16, 6, 1L);
		return new GalileoNavigationData.Ephemeris(page(1, one), page(2, two), page(3, three), page(4, four));
	}

	private static int[] word(int type) {
		int[] bits = new int[128]; putUnsigned(bits, 0, 6, type); return bits;
	}
	private static GalileoInavDecoder.Page page(int type, int[] bits) {
		return new GalileoInavDecoder.Page(true, type, bits, 1d, 1d);
	}
	private static void putUnsigned(int[] bits, int offset, int count, long value) {
		for (int index = 0; index < count; index++)
			bits[offset + index] = (int) ((value >>> (count - 1 - index)) & 1L);
	}
	private static void near(double actual, double expected, double tolerance, String field) {
		if (Math.abs(actual - expected) > tolerance)
			throw new AssertionError(field + ": " + actual + " != " + expected);
	}
}
