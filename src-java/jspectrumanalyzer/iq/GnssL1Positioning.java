package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.List;

/** Builds GPS-only, Galileo-only, or mixed GPS/Galileo L1 code-position solutions. */
final class GnssL1Positioning {
	private static final double MAX_RESULT_EPOCH_SEPARATION_SECONDS = 2d;

	static GpsL1SignalAnalyzer.Result combine(GpsL1SignalAnalyzer.Result gps,
			GalileoE1SignalAnalyzer.Result galileo) {
		if (gps == null) return null;
		List<GpsL1StreamTracker.Observation> gpsObservations = gps.observations;
		List<GalileoE1StreamTracker.Observation> galileoObservations = galileo == null
				? new ArrayList<GalileoE1StreamTracker.Observation>() : galileo.observations;
		if (galileoObservations.isEmpty()) return gps;
		GpsL1PositionSolver.Solution solution = solve(gpsObservations, galileoObservations);
		if (!solution.plausible && gps.position.plausible) return gps;
		String systemTime = formatSystemTime(solution, gpsObservations, galileoObservations);
		String utc = formatUtc(solution, gpsObservations);
		return gps.withPosition(solution, utc, systemTime);
	}

	static GpsL1PositionSolver.Solution solve(List<GpsL1StreamTracker.Observation> gps,
			List<GalileoE1StreamTracker.Observation> galileo) {
		int gpsCount = gps == null ? 0 : gps.size(), galileoCount = galileo == null ? 0 : galileo.size();
		boolean mixed = gpsCount > 0 && galileoCount > 0;
		int required = mixed ? 5 : 4, available = gpsCount + galileoCount;
		if (available < required) return GpsL1PositionSolver.Solution.waiting(mixed
				? "Waiting for 5 timed GPS/Galileo satellites (" + available + "/5)"
				: "Waiting for 4 timed Galileo satellites (" + available + "/4)");
		if (!consistentWeeks(gps, galileo))
			return GpsL1PositionSolver.Solution.waiting("Inconsistent GPS/Galileo week numbers");

		double earliestReception = Double.POSITIVE_INFINITY, commonReception = Double.NEGATIVE_INFINITY;
		if (gps != null) for (GpsL1StreamTracker.Observation observation : gps) {
			earliestReception = Math.min(earliestReception, observation.receptionSeconds);
			commonReception = Math.max(commonReception, observation.receptionSeconds);
		}
		if (galileo != null) for (GalileoE1StreamTracker.Observation observation : galileo) {
			earliestReception = Math.min(earliestReception, observation.receptionSeconds);
			commonReception = Math.max(commonReception, observation.receptionSeconds);
		}
		if (!Double.isFinite(commonReception) || commonReception - earliestReception > MAX_RESULT_EPOCH_SEPARATION_SECONDS)
			return GpsL1PositionSolver.Solution.waiting("Waiting for a common GPS/Galileo measurement epoch");

		double base = gpsCount > 0 ? gps.get(0).satelliteTime : galileo.get(0).satelliteTime;
		double latest = base;
		List<TimedMeasurement> timed = new ArrayList<TimedMeasurement>();
		if (gps != null) for (GpsL1StreamTracker.Observation observation : gps) {
			double satelliteTime = base + GpsL1Orbit.weekDifference(observation.satelliteTime - base)
					+ commonReception - observation.receptionSeconds;
			latest = Math.max(latest, satelliteTime);
			timed.add(TimedMeasurement.gps(observation, satelliteTime));
		}
		if (galileo != null) for (GalileoE1StreamTracker.Observation observation : galileo) {
			double satelliteTime = base + GalileoE1Orbit.weekDifference(observation.satelliteTime - base)
					+ commonReception - observation.receptionSeconds;
			latest = Math.max(latest, satelliteTime);
			timed.add(TimedMeasurement.galileo(observation, satelliteTime, mixed));
		}

		double receiverTime = latest + .070d;
		List<GpsL1PositionSolver.Measurement> ranges = new ArrayList<GpsL1PositionSolver.Measurement>();
		for (TimedMeasurement measurement : timed) {
			double relative = receiverTime - measurement.satelliteTime;
			if (relative < .04d || relative > .12d)
				return GpsL1PositionSolver.Solution.waiting("Inconsistent satellite timing");
			if (measurement.gps != null) {
				GpsL1Orbit.State state = GpsL1Orbit.atSatelliteTime(measurement.gps.ephemeris, measurement.satelliteTime);
				if (state == null) return GpsL1PositionSolver.Solution.waiting("GPS orbit or ephemeris unavailable");
				double range = GpsL1Orbit.LIGHT_SPEED * (relative + state.clockSeconds);
				ranges.add(new GpsL1PositionSolver.Measurement("G" + measurement.gps.prn,
						measurement.gps.prn, false, state.x, state.y, state.z, range));
			} else {
				GalileoE1Orbit.State state = GalileoE1Orbit.atSatelliteTime(measurement.galileo.ephemeris,
						measurement.satelliteTime, measurement.galileo.bgdE1E5bSeconds);
				if (state == null) return GpsL1PositionSolver.Solution.waiting("Galileo orbit or ephemeris unavailable");
				double range = GpsL1Orbit.LIGHT_SPEED * (relative + state.clockSeconds);
				ranges.add(new GpsL1PositionSolver.Measurement("E" + measurement.galileo.svid,
						measurement.galileo.svid, measurement.secondaryClock, state.x, state.y, state.z, range));
			}
		}
		return GpsL1PositionSolver.solve(ranges, receiverTime, mixed);
	}

	private static boolean consistentWeeks(List<GpsL1StreamTracker.Observation> gps,
			List<GalileoE1StreamTracker.Observation> galileo) {
		int gpsWeek = -1, galileoWeek = -1;
		if (gps != null) for (GpsL1StreamTracker.Observation observation : gps) {
			int week = observation.ephemeris.weekModulo1024;
			if (gpsWeek < 0) gpsWeek = week; else if (week != gpsWeek) return false;
		}
		if (galileo != null) for (GalileoE1StreamTracker.Observation observation : galileo) {
			if (galileoWeek < 0) galileoWeek = observation.weekModulo4096;
			else if (observation.weekModulo4096 != galileoWeek) return false;
		}
		return gpsWeek < 0 || galileoWeek < 0 || (galileoWeek & 1023) == gpsWeek;
	}

	private static String formatSystemTime(GpsL1PositionSolver.Solution solution,
			List<GpsL1StreamTracker.Observation> gps,
			List<GalileoE1StreamTracker.Observation> galileo) {
		if (!solution.plausible) return "--";
		if (gps != null && !gps.isEmpty()) {
			String value = GpsL1NavigationData.formatGpsTime(gps.get(0).ephemeris.weekModulo1024, solution.receiverTow);
			return value == null ? "--" : value;
		}
		if (galileo != null && !galileo.isEmpty())
			return String.format(java.util.Locale.US, "GST week %d / %.3f s",
					galileo.get(0).weekModulo4096, wrapWeek(solution.receiverTow));
		return "--";
	}

	private static String formatUtc(GpsL1PositionSolver.Solution solution,
			List<GpsL1StreamTracker.Observation> gps) {
		if (!solution.plausible) return "Waiting for LNAV UTC parameters";
		if (gps == null || gps.isEmpty()) return "Waiting for Galileo GST-UTC parameters";
		for (GpsL1StreamTracker.Observation observation : gps) {
			if (observation.utc == null) continue;
			String value = observation.utc.format(observation.ephemeris.weekModulo1024, solution.receiverTow);
			if (value != null) return value;
		}
		return "UTC parameters unavailable or leap change pending";
	}

	private static double wrapWeek(double seconds) {
		return (seconds % 604_800d + 604_800d) % 604_800d;
	}

	private static final class TimedMeasurement {
		final GpsL1StreamTracker.Observation gps;
		final GalileoE1StreamTracker.Observation galileo;
		final double satelliteTime;
		final boolean secondaryClock;
		private TimedMeasurement(GpsL1StreamTracker.Observation gps,
				GalileoE1StreamTracker.Observation galileo, double satelliteTime, boolean secondaryClock) {
			this.gps = gps; this.galileo = galileo; this.satelliteTime = satelliteTime;
			this.secondaryClock = secondaryClock;
		}
		static TimedMeasurement gps(GpsL1StreamTracker.Observation value, double satelliteTime) {
			return new TimedMeasurement(value, null, satelliteTime, false);
		}
		static TimedMeasurement galileo(GalileoE1StreamTracker.Observation value, double satelliteTime,
				boolean secondaryClock) {
			return new TimedMeasurement(null, value, satelliteTime, secondaryClock);
		}
	}
}
