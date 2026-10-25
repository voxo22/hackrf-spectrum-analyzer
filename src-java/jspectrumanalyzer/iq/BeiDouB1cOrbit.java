package jspectrumanalyzer.iq;

/** BeiDou-3 B-CNAV1 broadcast orbit and B1C data-channel clock correction. */
final class BeiDouB1cOrbit {
	private static final double MU = 3.986004418e14, RELATIVITY = -4.442807309e-10, WEEK = 604_800d;
	static State atSatelliteTime(BeiDouB1cNavigationData.Ephemeris eph, double satelliteTime) {
		if (eph == null || !Double.isFinite(satelliteTime) || eph.semiMajorAxis < 20_000_000d
				|| eph.semiMajorAxis > 50_000_000d || eph.eccentricity < 0d || eph.eccentricity >= 1d) return null;
		double bdt = satelliteTime; State state = null;
		for (int iteration = 0; iteration < 3; iteration++) {
			state = atBdt(eph, bdt); if (state == null) return null; bdt = satelliteTime - state.clockSeconds;
		}
		return state;
	}
	static State atBdt(BeiDouB1cNavigationData.Ephemeris eph, double bdt) {
		double tk = weekDifference(bdt - eph.toeSeconds); if (Math.abs(tk) > 14_400d) return null;
		double axis = eph.semiMajorAxis + eph.semiMajorAxisRate * tk; if (axis <= 0d) return null;
		double meanMotion = Math.sqrt(MU / (axis * axis * axis));
		double mean = eph.meanAnomaly + (meanMotion + eph.deltaN) * tk + .5d * eph.deltaNDot * tk * tk;
		double eccentric = mean;
		for (int iteration = 0; iteration < 15; iteration++) {
			double step = (eccentric - eph.eccentricity * Math.sin(eccentric) - mean)
					/ (1d - eph.eccentricity * Math.cos(eccentric)); eccentric -= step;
			if (Math.abs(step) < 1e-13) break; if (iteration == 14) return null;
		}
		double sinE=Math.sin(eccentric), cosE=Math.cos(eccentric);
		double phi=Math.atan2(Math.sqrt(1d-eph.eccentricity*eph.eccentricity)*sinE,cosE-eph.eccentricity)+eph.argumentPerigee;
		double sin2=Math.sin(2d*phi), cos2=Math.cos(2d*phi);
		double u=phi+eph.cus*sin2+eph.cuc*cos2;
		double radius=axis*(1d-eph.eccentricity*cosE)+eph.crs*sin2+eph.crc*cos2;
		double inclination=eph.inclination+eph.inclinationDot*tk+eph.cis*sin2+eph.cic*cos2;
		double node=eph.omega0+(eph.omegaDot-GpsL1Orbit.EARTH_ROTATION)*tk-GpsL1Orbit.EARTH_ROTATION*eph.toeSeconds;
		double orbitalX=radius*Math.cos(u), orbitalY=radius*Math.sin(u), cosNode=Math.cos(node), sinNode=Math.sin(node);
		double xg=orbitalX*cosNode-orbitalY*Math.cos(inclination)*sinNode;
		double yg=orbitalX*sinNode+orbitalY*Math.cos(inclination)*cosNode;
		double zg=orbitalY*Math.sin(inclination), x=xg, y=yg, z=zg;
		if (eph.satelliteType == 1) {
			double rotation=GpsL1Orbit.EARTH_ROTATION*tk, cr=Math.cos(rotation), sr=Math.sin(rotation);
			double tilt=Math.toRadians(-5d), ct=Math.cos(tilt), st=Math.sin(tilt);
			x=xg*cr+yg*sr*ct+zg*sr*st; y=-xg*sr+yg*cr*ct+zg*cr*st; z=-yg*st+zg*ct;
		}
		double dt=weekDifference(bdt-eph.tocSeconds);
		double clock=eph.af0+eph.af1*dt+eph.af2*dt*dt
				+RELATIVITY*eph.eccentricity*Math.sqrt(axis)*sinE-eph.tgdB1cp-eph.iscB1cd;
		return new State(x,y,z,clock);
	}
	static double weekDifference(double seconds) {
		seconds%=WEEK; if(seconds>WEEK/2d)seconds-=WEEK; if(seconds<-WEEK/2d)seconds+=WEEK; return seconds;
	}
	static final class State { final double x,y,z,clockSeconds; State(double x,double y,double z,double clock){this.x=x;this.y=y;this.z=z;clockSeconds=clock;} }
	private BeiDouB1cOrbit() { }
}
