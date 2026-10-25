package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.List;

/** BeiDou-only B1C code-position solution in BDT. */
final class BeiDouB1cPositioning {
	private static final double MAX_EPOCH_SEPARATION_SECONDS=2d;
	static GpsL1PositionSolver.Solution solve(List<BeiDouB1cStreamTracker.Observation> observations) {
		int count=observations==null?0:observations.size();
		if(count<4)return GpsL1PositionSolver.Solution.waiting("Waiting for 4 timed BeiDou satellites ("+count+"/4)");
		int week=observations.get(0).week; double earliest=Double.POSITIVE_INFINITY,common=Double.NEGATIVE_INFINITY;
		for(BeiDouB1cStreamTracker.Observation observation:observations){if(observation.week!=week)return GpsL1PositionSolver.Solution.waiting("Inconsistent BeiDou week numbers");earliest=Math.min(earliest,observation.receptionSeconds);common=Math.max(common,observation.receptionSeconds);}
		if(common-earliest>MAX_EPOCH_SEPARATION_SECONDS)return GpsL1PositionSolver.Solution.waiting("Waiting for a common BeiDou measurement epoch");
		double base=observations.get(0).satelliteTime,latest=base;double[] times=new double[count];
		for(int i=0;i<count;i++){BeiDouB1cStreamTracker.Observation observation=observations.get(i);times[i]=base+BeiDouB1cOrbit.weekDifference(observation.satelliteTime-base)+common-observation.receptionSeconds;latest=Math.max(latest,times[i]);}
		double receiverTime=latest+.070d;List<GpsL1PositionSolver.Measurement> ranges=new ArrayList<GpsL1PositionSolver.Measurement>();
		for(int i=0;i<count;i++){BeiDouB1cStreamTracker.Observation observation=observations.get(i);double relative=receiverTime-times[i];if(relative<.04d||relative>.12d)return GpsL1PositionSolver.Solution.waiting("Inconsistent BeiDou satellite timing");BeiDouB1cOrbit.State state=BeiDouB1cOrbit.atSatelliteTime(observation.ephemeris,times[i]);if(state==null)return GpsL1PositionSolver.Solution.waiting("BeiDou orbit or ephemeris unavailable");double range=GpsL1Orbit.LIGHT_SPEED*(relative+state.clockSeconds);ranges.add(new GpsL1PositionSolver.Measurement("C"+observation.prn,observation.prn,false,state.x,state.y,state.z,range));}
		return GpsL1PositionSolver.solve(ranges,receiverTime,false);
	}
	private BeiDouB1cPositioning(){ }
}
