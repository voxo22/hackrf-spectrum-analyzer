package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.List;

/** Analytic orbit and known-receiver synthetic pseudorange regressions. */
public final class GpsL1PositionSolverTest {
	public static void main(String[] args) {
		testCircularOrbit();
		testReceiverSolution();
		System.out.println("GPS orbit and synthetic pseudorange positioning tests passed");
	}
	private static void testCircularOrbit() {
		int[][] s1 = new int[10][24], s2 = new int[10][24], s3 = new int[10][24];
		put(s2, 184, 32, 2147483648L); // sqrt(A) = 4096 m^(1/2), circular, node=0
		GpsL1NavigationData cache = new GpsL1NavigationData();
		cache.accept(decode(1, 6, s1), 0); cache.accept(decode(2, 12, s2), 1000); cache.accept(decode(3, 18, s3), 2000);
		GpsL1NavigationData.Ephemeris eph = cache.snapshot(2000).ephemeris;
		if (eph == null) throw new AssertionError("Test ephemeris absent");
		GpsL1Orbit.State state = GpsL1Orbit.atSatelliteTime(eph, 0d);
		if (state == null) throw new AssertionError("Circular orbit rejected");
		near(state.x, 16777216d, .001d, "circular x"); near(state.y, 0d, .001d, "circular y"); near(state.z, 0d, .001d, "circular z");
		near(GpsL1Orbit.weekDifference(1d - 604799d), 2d, 0d, "week rollover");
		if (GpsL1Orbit.atGpsTime(eph, 7201d) != null) throw new AssertionError("Expired orbit was accepted");
	}
	private static void testReceiverSolution() {
		double lat = Math.toRadians(40d), lon = Math.toRadians(-75d), height = 150d;
		double a = 6378137d, e2 = 6.69437999014e-3, n = a / Math.sqrt(1d - e2 * Math.sin(lat) * Math.sin(lat));
		double rx = (n + height) * Math.cos(lat) * Math.cos(lon), ry = (n + height) * Math.cos(lat) * Math.sin(lon);
		double rz = (n * (1d - e2) + height) * Math.sin(lat), bias = 80000d;
		double[][] satellites = { {15600000, 0, 21000000}, {-15600000, 14000000, 21000000},
				{18760000, 15000000, 17000000}, {-13000000, -17000000, 17000000},
				{21000000, -16000000, 0}, {-21000000, 8000000, -15000000} };
		List<GpsL1PositionSolver.Measurement> observations = new ArrayList<GpsL1PositionSolver.Measurement>();
		for (int i = 0; i < satellites.length; i++) {
			double[] sat = satellites[i]; double range = 23000000d;
			for (int iteration = 0; iteration < 5; iteration++) {
				double angle = GpsL1Orbit.EARTH_ROTATION * range / GpsL1Orbit.LIGHT_SPEED;
				double sx = Math.cos(angle) * sat[0] + Math.sin(angle) * sat[1];
				double sy = -Math.sin(angle) * sat[0] + Math.cos(angle) * sat[1];
				range = Math.sqrt((rx - sx) * (rx - sx) + (ry - sy) * (ry - sy) + (rz - sat[2]) * (rz - sat[2]));
			}
			observations.add(new GpsL1PositionSolver.Measurement(i + 1, sat[0], sat[1], sat[2], range + bias));
		}
		GpsL1PositionSolver.Solution solution = GpsL1PositionSolver.solve(observations, 123456d);
		if (!solution.plausible) throw new AssertionError("Known position rejected: " + solution.state);
		if (!"Code position".equals(solution.state)) throw new AssertionError("Unexpected position label: " + solution.state);
		near(solution.latitude, 40d, 1e-6, "latitude"); near(solution.longitude, -75d, 1e-6, "longitude");
		near(solution.altitude, height, .1d, "altitude"); near(solution.clockMeters, bias, .1d, "receiver clock");
		if (solution.residualRms > .1d || !Double.isFinite(solution.pdop)) throw new AssertionError("Residual or DOP");
		GpsL1PositionSolver.Solution four = GpsL1PositionSolver.solve(observations.subList(0, 4), 123456d);
		if (!four.plausible || !"Position (4 satellites; no redundancy)".equals(four.state))
			throw new AssertionError("Four-satellite position or its warning is incorrect: " + four.state);
		if (GpsL1PositionSolver.solve(observations.subList(0, 3), 0d).plausible) throw new AssertionError("Three satellites accepted");
		GpsL1PositionSolver.Measurement bad = observations.get(0);
		observations.set(0, new GpsL1PositionSolver.Measurement(bad.prn, bad.x, bad.y, bad.z, bad.range + 100000d));
		if (GpsL1PositionSolver.solve(observations, 0d).plausible) throw new AssertionError("Inconsistent ranges accepted");
	}
	private static GpsL1LnavDecoder.Result decode(int id, int tow, int[][] words) {
		GpsL1LnavDecoder.Result result = GpsL1LnavDecoder.decodeSubframe(GpsL1LnavDecoderTest.makeSubframe(id, tow, words));
		if (!result.valid) throw new AssertionError("Invalid test frame"); return result;
	}
	private static void put(int[][] words, int start, int count, long value) {
		for (int bit = count - 1; bit >= 0; bit--) { int pos = start + bit; words[pos / 24][pos % 24] = (int) (value & 1L); value >>= 1; }
	}
	private static void near(double actual, double expected, double tolerance, String field) {
		if (Math.abs(actual - expected) > tolerance) throw new AssertionError(field + ": " + actual + " != " + expected);
	}
}
