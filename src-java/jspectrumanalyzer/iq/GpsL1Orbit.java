package jspectrumanalyzer.iq;

/** GPS broadcast-ephemeris orbit and L1 C/A clock correction, in ECEF metres.
 * Equations: IS-GPS-200, sections 20.3.3.3.3 and 20.3.3.4.3. */
final class GpsL1Orbit {
	static final double LIGHT_SPEED = 299_792_458d;
	static final double EARTH_ROTATION = 7.2921151467e-5;
	private static final double MU = 3.986005e14;
	private static final double WEEK = 604_800d;

	static State atSatelliteTime(GpsL1NavigationData.Ephemeris eph, double satelliteTime) {
		if (eph == null || !Double.isFinite(satelliteTime) || eph.healthCode != 0 || eph.sqrtA < 4000d || eph.sqrtA > 6000d
				|| eph.eccentricity < 0d || eph.eccentricity >= 1d) return null;
		double gpsTime = satelliteTime;
		State state = null;
		for (int i = 0; i < 3; i++) {
			state = atGpsTime(eph, gpsTime);
			if (state == null) return null;
			gpsTime = satelliteTime - state.clockSeconds;
		}
		return state;
	}

	static State atGpsTime(GpsL1NavigationData.Ephemeris eph, double gpsTime) {
		if (eph == null || !Double.isFinite(gpsTime)) return null;
		double a = eph.sqrtA * eph.sqrtA, e = eph.eccentricity;
		if (a <= 0d || e < 0d || e >= 1d) return null;
		double tk = weekDifference(gpsTime - eph.toeSeconds);
		/* Limit extrapolation even for extended fit, until its IODC-dependent
		 * interval is interpreted; an old set must never become a valid fix. */
		if (Math.abs(tk) > 7200d) return null;
		double mean = eph.meanAnomaly + (Math.sqrt(MU / (a * a * a)) + eph.deltaN) * tk;
		double eccentric = mean;
		for (int i = 0; i < 15; i++) {
			double step = (eccentric - e * Math.sin(eccentric) - mean) / (1d - e * Math.cos(eccentric));
			eccentric -= step;
			if (Math.abs(step) < 1e-13) break;
			if (i == 14) return null;
		}
		double sinE = Math.sin(eccentric), cosE = Math.cos(eccentric);
		double phi = Math.atan2(Math.sqrt(1d - e * e) * sinE, cosE - e) + eph.argumentPerigee;
		double sin2 = Math.sin(2d * phi), cos2 = Math.cos(2d * phi);
		double u = phi + eph.cus * sin2 + eph.cuc * cos2;
		double radius = a * (1d - e * cosE) + eph.crs * sin2 + eph.crc * cos2;
		double inclination = eph.inclination + eph.inclinationDot * tk + eph.cis * sin2 + eph.cic * cos2;
		double node = eph.omega0 + (eph.omegaDot - EARTH_ROTATION) * tk - EARTH_ROTATION * eph.toeSeconds;
		double x = radius * Math.cos(u), y = radius * Math.sin(u);
		double sinNode = Math.sin(node), cosNode = Math.cos(node), cosI = Math.cos(inclination);
		double dt = weekDifference(gpsTime - eph.tocSeconds);
		double clock = eph.af0 + eph.af1 * dt + eph.af2 * dt * dt
				- 2d * Math.sqrt(MU * a) * e * sinE / (LIGHT_SPEED * LIGHT_SPEED) - eph.tgdSeconds;
		return new State(x * cosNode - y * cosI * sinNode, x * sinNode + y * cosI * cosNode,
				y * Math.sin(inclination), clock);
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
