package jspectrumanalyzer.iq;

/** Galileo broadcast-ephemeris orbit and single-frequency E1 clock correction. */
final class GalileoE1Orbit {
	private static final double MU = 3.986004418e14;
	private static final double RELATIVITY = -4.442807309e-10;
	private static final double WEEK = 604_800d;

	static State atSatelliteTime(GalileoNavigationData.Ephemeris eph, double satelliteTime,
			double bgdE1E5bSeconds) {
		if (eph == null || !Double.isFinite(satelliteTime) || !Double.isFinite(bgdE1E5bSeconds)
				|| eph.sqrtA < 4000d || eph.sqrtA > 6000d
				|| eph.eccentricity < 0d || eph.eccentricity >= 1d) return null;
		double gst = satelliteTime;
		State state = null;
		for (int iteration = 0; iteration < 3; iteration++) {
			state = atGst(eph, gst, bgdE1E5bSeconds);
			if (state == null) return null;
			gst = satelliteTime - state.clockSeconds;
		}
		return state;
	}

	static State atGst(GalileoNavigationData.Ephemeris eph, double gst, double bgdE1E5bSeconds) {
		if (eph == null || !Double.isFinite(gst) || !Double.isFinite(bgdE1E5bSeconds)) return null;
		double a = eph.sqrtA * eph.sqrtA, eccentricity = eph.eccentricity;
		if (a <= 0d || eccentricity < 0d || eccentricity >= 1d) return null;
		double tk = weekDifference(gst - eph.toeSeconds);
		if (Math.abs(tk) > 14_400d) return null;
		double mean = eph.meanAnomaly + (Math.sqrt(MU / (a * a * a)) + eph.deltaN) * tk;
		double eccentric = mean;
		for (int iteration = 0; iteration < 15; iteration++) {
			double step = (eccentric - eccentricity * Math.sin(eccentric) - mean)
					/ (1d - eccentricity * Math.cos(eccentric));
			eccentric -= step;
			if (Math.abs(step) < 1e-13) break;
			if (iteration == 14) return null;
		}
		double sinE = Math.sin(eccentric), cosE = Math.cos(eccentric);
		double phi = Math.atan2(Math.sqrt(1d - eccentricity * eccentricity) * sinE,
				cosE - eccentricity) + eph.argumentPerigee;
		double sin2 = Math.sin(2d * phi), cos2 = Math.cos(2d * phi);
		double u = phi + eph.cus * sin2 + eph.cuc * cos2;
		double radius = a * (1d - eccentricity * cosE) + eph.crs * sin2 + eph.crc * cos2;
		double inclination = eph.inclination + eph.inclinationDot * tk + eph.cis * sin2 + eph.cic * cos2;
		double node = eph.omega0 + (eph.omegaDot - GpsL1Orbit.EARTH_ROTATION) * tk
				- GpsL1Orbit.EARTH_ROTATION * eph.toeSeconds;
		double orbitalX = radius * Math.cos(u), orbitalY = radius * Math.sin(u);
		double sinNode = Math.sin(node), cosNode = Math.cos(node), cosI = Math.cos(inclination);
		double dt = weekDifference(gst - eph.tocSeconds);
		double clock = eph.af0 + eph.af1 * dt + eph.af2 * dt * dt
				+ RELATIVITY * eccentricity * eph.sqrtA * sinE - bgdE1E5bSeconds;
		return new State(orbitalX * cosNode - orbitalY * cosI * sinNode,
				orbitalX * sinNode + orbitalY * cosI * cosNode,
				orbitalY * Math.sin(inclination), clock);
	}

	static double weekDifference(double seconds) {
		seconds %= WEEK;
		if (seconds > WEEK / 2d) seconds -= WEEK;
		if (seconds < -WEEK / 2d) seconds += WEEK;
		return seconds;
	}

	static final class State {
		final double x, y, z, clockSeconds;
		State(double x, double y, double z, double clockSeconds) {
			this.x = x; this.y = y; this.z = z; this.clockSeconds = clockSeconds;
		}
	}
}
