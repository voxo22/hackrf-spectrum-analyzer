package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Single-frequency GPS/Galileo code-position solution; no atmosphere model or RAIM. */
final class GpsL1PositionSolver {
	private static final double C = GpsL1Orbit.LIGHT_SPEED;
	private static final double A = 6378137d, E2 = 6.69437999014e-3;

	static final class Measurement {
		final int prn;
		final String satelliteId;
		final boolean secondaryClock;
		final double x, y, z, range;
		Measurement(int prn, double x, double y, double z, double correctedPseudorange) {
			this("G" + prn, prn, false, x, y, z, correctedPseudorange);
		}
		Measurement(String satelliteId, int satelliteNumber, boolean secondaryClock,
				double x, double y, double z, double correctedPseudorange) {
			this.satelliteId = satelliteId; this.prn = satelliteNumber; this.secondaryClock = secondaryClock;
			this.x = x; this.y = y; this.z = z; this.range = correctedPseudorange;
		}
	}
	static final class Solution {
		final boolean converged, plausible;
		final String state;
		final double latitude, longitude, altitude, clockMeters, interSystemBiasMeters;
		final double pdop, hdop, vdop, residualRms, receiverTow;
		final List<Integer> usedPrns;
		final List<Integer> usedGalileoSvids;
		final List<String> usedSatellites;
		Solution(boolean converged, boolean plausible, String state, double latitude, double longitude, double altitude,
				double clockMeters, double pdop, double hdop, double vdop, double residualRms, double receiverTow, List<Integer> prns) {
			this(converged, plausible, state, latitude, longitude, altitude, clockMeters, Double.NaN,
					pdop, hdop, vdop, residualRms, receiverTow, prns, Collections.<Integer>emptyList(), gpsIds(prns));
		}
		private Solution(boolean converged, boolean plausible, String state, double latitude, double longitude, double altitude,
				double clockMeters, double interSystemBiasMeters, double pdop, double hdop, double vdop,
				double residualRms, double receiverTow, List<Integer> prns, List<Integer> galileoSvids,
				List<String> satellites) {
			this.converged = converged; this.plausible = plausible; this.state = state; this.latitude = latitude; this.longitude = longitude;
			this.altitude = altitude; this.clockMeters = clockMeters; this.interSystemBiasMeters = interSystemBiasMeters;
			this.pdop = pdop; this.hdop = hdop; this.vdop = vdop;
			this.residualRms = residualRms; this.receiverTow = receiverTow;
			this.usedPrns = Collections.unmodifiableList(new ArrayList<Integer>(prns));
			this.usedGalileoSvids = Collections.unmodifiableList(new ArrayList<Integer>(galileoSvids));
			this.usedSatellites = Collections.unmodifiableList(new ArrayList<String>(satellites));
		}
		static Solution waiting(String reason) {
			return new Solution(false, false, reason, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
					Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Collections.<Integer>emptyList(),
					Collections.<Integer>emptyList(), Collections.<String>emptyList());
		}
		private static List<String> gpsIds(List<Integer> prns) {
			List<String> result = new ArrayList<String>();
			for (Integer prn : prns) result.add("G" + prn);
			return result;
		}
	}

	/** rxTow is the arbitrary common receive-clock time used to form ranges. */
	static Solution solve(List<Measurement> measurements, double rxTow) {
		return solve(measurements, rxTow, false);
	}

	/** Solve with an independent range bias for measurements from a second time scale. */
	static Solution solve(List<Measurement> measurements, double rxTow, boolean estimateInterSystemBias) {
		int unknowns = estimateInterSystemBias ? 5 : 4;
		if (measurements == null || measurements.size() < unknowns)
			return Solution.waiting("Waiting for " + unknowns + " timed satellites");
		if (estimateInterSystemBias) {
			boolean primary = false, secondary = false;
			for (Measurement measurement : measurements) {
				if (measurement.secondaryClock) secondary = true; else primary = true;
			}
			if (!primary || !secondary) return Solution.waiting("Mixed fix needs GPS and Galileo timing");
		}
		double[] position = new double[unknowns];
		double[][] normal = null;
		double residualSum = 0d;
		boolean converged = false;
		for (int iteration = 0; iteration < 20; iteration++) {
			normal = new double[unknowns][unknowns]; double[] rhs = new double[unknowns]; residualSum = 0d;
			for (Measurement m : measurements) {
				if (!Double.isFinite(m.range) || !Double.isFinite(m.x) || !Double.isFinite(m.y) || !Double.isFinite(m.z))
					return Solution.waiting("Invalid satellite observation");
				/* Transform the emission-epoch ECEF coordinate into reception-epoch
				 * ECEF to account for Earth's rotation during signal propagation. */
				double receiverBias = position[3]
						+ (estimateInterSystemBias && m.secondaryClock ? position[4] : 0d);
				double travel = Math.max(.05d, Math.min(.12d, (m.range - receiverBias) / C));
				double angle = GpsL1Orbit.EARTH_ROTATION * travel, cos = Math.cos(angle), sin = Math.sin(angle);
				double sx = cos * m.x + sin * m.y, sy = -sin * m.x + cos * m.y;
				double dx = position[0] - sx, dy = position[1] - sy, dz = position[2] - m.z;
				double geometric = Math.sqrt(dx * dx + dy * dy + dz * dz);
				if (geometric < 1d) return Solution.waiting("Invalid satellite geometry");
				double[] h = estimateInterSystemBias
						? new double[] { dx / geometric, dy / geometric, dz / geometric, 1d, m.secondaryClock ? 1d : 0d }
						: new double[] { dx / geometric, dy / geometric, dz / geometric, 1d };
				double residual = m.range - geometric - receiverBias; residualSum += residual * residual;
				for (int row = 0; row < unknowns; row++) {
					rhs[row] += h[row] * residual;
					for (int col = 0; col < unknowns; col++) normal[row][col] += h[row] * h[col];
				}
			}
			double[][] covariance = inverse(normal);
			if (covariance == null) return Solution.waiting("Satellite geometry is singular");
			double max = 0d;
			for (int row = 0; row < unknowns; row++) {
				double delta = 0d; for (int col = 0; col < unknowns; col++) delta += covariance[row][col] * rhs[col];
				position[row] += delta; max = Math.max(max, Math.abs(delta));
			}
			if (max < .01d) { converged = true; break; }
		}
		if (!converged) return Solution.waiting("Position solution did not converge");
		double[][] q = inverse(normal);
		if (q == null) return Solution.waiting("Satellite geometry is singular");
		double[] lla = ecefToGeodetic(position[0], position[1], position[2]);
		double lat = lla[0], lon = lla[1], sinLat = Math.sin(lat), cosLat = Math.cos(lat);
		double[] up = { cosLat * Math.cos(lon), cosLat * Math.sin(lon), sinLat };
		double vertical = 0d; for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) vertical += up[i] * q[i][j] * up[j];
		double spatial = q[0][0] + q[1][1] + q[2][2];
		double pdop = Math.sqrt(Math.max(0d, spatial)), vdop = Math.sqrt(Math.max(0d, vertical));
		double hdop = Math.sqrt(Math.max(0d, spatial - vertical));
		double rms = Math.sqrt(residualSum / measurements.size());
		boolean plausible = Double.isFinite(lla[2]) && lla[2] >= -1500d && lla[2] <= 30000d && pdop < 100d
				&& (measurements.size() == unknowns || rms < 500d);
		List<Integer> prns = new ArrayList<Integer>(), galileo = new ArrayList<Integer>();
		List<String> satellites = new ArrayList<String>();
		for (Measurement m : measurements) {
			satellites.add(m.satelliteId);
			if (m.satelliteId.startsWith("E")) galileo.add(Integer.valueOf(m.prn));
			else prns.add(Integer.valueOf(m.prn));
		}
		String state = !plausible ? "Rejected: implausible position or geometry"
				: measurements.size() == unknowns ? "Position (" + unknowns + " satellites; no redundancy)" : "Code position";
		return new Solution(true, plausible, state, Math.toDegrees(lat), Math.toDegrees(lon), lla[2], position[3],
				estimateInterSystemBias ? position[4] : Double.NaN, pdop, hdop, vdop, rms,
				rxTow - position[3] / C, prns, galileo, satellites);
	}

	private static double[] ecefToGeodetic(double x, double y, double z) {
		double p = Math.hypot(x, y), lon = Math.atan2(y, x);
		if (p < 1d) return new double[] { Math.copySign(Math.PI / 2d, z), lon, Math.abs(z) - A * Math.sqrt(1d - E2) };
		double lat = Math.atan2(z, p * (1d - E2)), height = 0d;
		for (int i = 0; i < 12; i++) {
			double sin = Math.sin(lat), n = A / Math.sqrt(1d - E2 * sin * sin);
			height = p / Math.cos(lat) - n;
			double next = Math.atan2(z, p * (1d - E2 * n / (n + height)));
			if (Math.abs(next - lat) < 1e-13) { lat = next; break; }
			lat = next;
		}
		double sin = Math.sin(lat), n = A / Math.sqrt(1d - E2 * sin * sin);
		return new double[] { lat, lon, p / Math.cos(lat) - n };
	}
	private static double[][] inverse(double[][] input) {
		int size = input.length;
		if (size == 0) return null;
		double[][] m = new double[size][size * 2];
		for (int i = 0; i < size; i++) { if (input[i].length != size) return null;
			System.arraycopy(input[i], 0, m[i], 0, size); m[i][i + size] = 1d; }
		for (int col = 0; col < size; col++) {
			int pivot = col; for (int row = col + 1; row < size; row++) if (Math.abs(m[row][col]) > Math.abs(m[pivot][col])) pivot = row;
			if (Math.abs(m[pivot][col]) < 1e-12) return null;
			double[] swap = m[col]; m[col] = m[pivot]; m[pivot] = swap;
			double divisor = m[col][col]; for (int j = 0; j < size * 2; j++) m[col][j] /= divisor;
			for (int row = 0; row < size; row++) if (row != col) {
				double factor = m[row][col]; for (int j = 0; j < size * 2; j++) m[row][j] -= factor * m[col][j];
			}
		}
		double[][] result = new double[size][size];
		for (int row = 0; row < size; row++) System.arraycopy(m[row], size, result[row], 0, size);
		return result;
	}
}
