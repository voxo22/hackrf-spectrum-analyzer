package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.List;

/** Galileo-only E1 code-position solution, independent of the GPS decoder lifecycle. */
final class GalileoE1Positioning {
	private static final double MAX_EPOCH_SEPARATION_SECONDS = 2d;

	static GpsL1PositionSolver.Solution solve(List<GalileoE1StreamTracker.Observation> observations) {
		int count = observations == null ? 0 : observations.size();
		if (count < 4) return GpsL1PositionSolver.Solution.waiting(
				"Waiting for 4 timed Galileo satellites (" + count + "/4)");
		int week = observations.get(0).weekModulo4096;
		double earliestReception = Double.POSITIVE_INFINITY, commonReception = Double.NEGATIVE_INFINITY;
		for (GalileoE1StreamTracker.Observation observation : observations) {
			if (observation.weekModulo4096 != week)
				return GpsL1PositionSolver.Solution.waiting("Inconsistent Galileo week numbers");
			earliestReception = Math.min(earliestReception, observation.receptionSeconds);
			commonReception = Math.max(commonReception, observation.receptionSeconds);
		}
		if (commonReception - earliestReception > MAX_EPOCH_SEPARATION_SECONDS)
			return GpsL1PositionSolver.Solution.waiting("Waiting for a common Galileo measurement epoch");
		double base = observations.get(0).satelliteTime, latest = base;
		double[] satelliteTimes = new double[count];
		for (int index = 0; index < count; index++) {
			GalileoE1StreamTracker.Observation observation = observations.get(index);
			double satelliteTime = base + GalileoE1Orbit.weekDifference(observation.satelliteTime - base)
					+ commonReception - observation.receptionSeconds;
			satelliteTimes[index] = satelliteTime; latest = Math.max(latest, satelliteTime);
		}
		double receiverTime = latest + .070d;
		List<GpsL1PositionSolver.Measurement> ranges = new ArrayList<GpsL1PositionSolver.Measurement>();
		for (int index = 0; index < count; index++) {
			GalileoE1StreamTracker.Observation observation = observations.get(index);
			double relative = receiverTime - satelliteTimes[index];
			if (relative < .04d || relative > .12d)
				return GpsL1PositionSolver.Solution.waiting("Inconsistent Galileo satellite timing");
			GalileoE1Orbit.State state = GalileoE1Orbit.atSatelliteTime(observation.ephemeris,
					satelliteTimes[index], observation.bgdE1E5bSeconds);
			if (state == null) return GpsL1PositionSolver.Solution.waiting("Galileo orbit or ephemeris unavailable");
			double range = GpsL1Orbit.LIGHT_SPEED * (relative + state.clockSeconds);
			ranges.add(new GpsL1PositionSolver.Measurement("E" + observation.svid,
					observation.svid, false, state.x, state.y, state.z, range));
		}
		return GpsL1PositionSolver.solve(ranges, receiverTime, false);
	}

	private GalileoE1Positioning() { }
}
