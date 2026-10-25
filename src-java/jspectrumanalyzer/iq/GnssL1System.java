package jspectrumanalyzer.iq;

/** Mutually exclusive GNSS L1 decoder pipelines offered by the analyzer. */
enum GnssL1System {
	GPS("GPS L1 C/A"),
	GALILEO("Galileo E1"),
	BEIDOU("BeiDou B1C");

	final String label;
	GnssL1System(String label) { this.label = label; }
}
