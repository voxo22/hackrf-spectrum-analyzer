package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.List;

/** Form common-epoch relative pseudoranges and solve receiver ECEF position. */
final class GpsL1Positioning {
	static GpsL1PositionSolver.Solution solve(List<GpsL1StreamTracker.Observation> observations) {
		if (observations == null || observations.size() < 4)
			return GpsL1PositionSolver.Solution.waiting("Waiting for 4 timed ephemerides (" + (observations == null ? 0 : observations.size()) + "/4)");
		double base = observations.get(0).satelliteTime, latest = base;
		int week = observations.get(0).ephemeris.weekModulo1024;
		for (GpsL1StreamTracker.Observation observation : observations) {
			if (observation.ephemeris.weekModulo1024 != week)
				return GpsL1PositionSolver.Solution.waiting("Inconsistent GPS week numbers");
			double time = base + GpsL1Orbit.weekDifference(observation.satelliteTime - base);
			latest = Math.max(latest, time);
		}
		/* The initial common reception time is arbitrary; the unknown receiver
		 * clock offset is estimated together with position. */
		double receiverTime = latest + .070d;
		List<GpsL1PositionSolver.Measurement> ranges = new ArrayList<GpsL1PositionSolver.Measurement>();
		for (GpsL1StreamTracker.Observation observation : observations) {
			double satelliteTime = base + GpsL1Orbit.weekDifference(observation.satelliteTime - base);
			double relative = receiverTime - satelliteTime;
			if (relative < .04d || relative > .12d) return GpsL1PositionSolver.Solution.waiting("Inconsistent satellite timing");
			GpsL1Orbit.State state = GpsL1Orbit.atSatelliteTime(observation.ephemeris, satelliteTime);
			if (state == null) return GpsL1PositionSolver.Solution.waiting("Satellite orbit or ephemeris unavailable");
			double range = GpsL1Orbit.LIGHT_SPEED * (relative + state.clockSeconds);
			ranges.add(new GpsL1PositionSolver.Measurement(observation.prn, state.x, state.y, state.z, range));
		}
		return GpsL1PositionSolver.solve(ranges, receiverTime);
	}
}
