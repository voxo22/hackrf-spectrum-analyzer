package jspectrumanalyzer.iq;

import java.util.Arrays;
import java.util.Collections;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.text.View;

/** Headless regression for the compact GPS overview at its default window width. */
public final class GpsL1ParameterLayoutTest {
	public static void main(String[] args) throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			GpsL1PositionSolver.Solution position = new GpsL1PositionSolver.Solution(true, true,
					"Position (4 satellites; no redundancy)", 49.18774898, 20.23309268, 1755.4, 0,
					5.3, 3.1, 4.3, 1.4, 327659.93, Arrays.asList(5, 14, 20, 30));
			GpsL1SignalAnalyzer.Result fix = new GpsL1SignalAnalyzer.Result(1575420000L, 2600000, 2600,
					"11 GPS L1 C/A satellites acquired; prompt lock 11; LNAV parity valid 6",
					Collections.<GpsL1SignalAnalyzer.Satellite>emptyList(), 11, 11, position, "2026-07-01 19:00:42.000 UTC",
					"2026-07-01 19:01:00.000 GPS");
			String details = SignalQualityTesterFrame.gpsParameterDetails(fix, 1575420000L, 2600000, 0);
			if (!details.contains("UTC|2026-07-01 19:00:42.000 UTC") || !details.contains("System time|2026-07-01 19:01:00.000 GPS")
					|| !details.contains("Channel|11 GPS L1 C/A satellites acquired\n|prompt lock 11\n|LNAV parity valid 6"))
				throw new AssertionError("GPS details or status lines missing");
			checkHeight(details);
			GpsL1SignalAnalyzer.Result gpsWithoutUtc = new GpsL1SignalAnalyzer.Result(1575420000L, 2600000, 2600,
					"GPS acquired", Collections.<GpsL1SignalAnalyzer.Satellite>emptyList(), 4, 4, position,
					"Waiting for LNAV UTC parameters", "2026-07-01 19:01:00.000 GPS");
			String gpsOnly = SignalQualityTesterFrame.gpsParameterDetails(gpsWithoutUtc, 1575420000L, 2600000, 0);
			if (!gpsOnly.contains("UTC|Waiting for UTC data") || !gpsOnly.contains("System time|2026-07-01 19:01:00.000 GPS"))
				throw new AssertionError("GPS time must be visible even when UTC parameters are missing");
			checkHeight(gpsOnly);
			checkHeight(SignalQualityTesterFrame.gpsParameterDetails(GpsL1SignalAnalyzer.Result.empty(1575420000L, 2600000,
					"No GPS L1 C/A PRN acquired yet; showing strongest candidates"), 1575420000L, 2600000, 0));
			checkHeight(SignalQualityTesterFrame.gpsParameterDetails(GpsL1SignalAnalyzer.Result.empty(1575420000L, 1000000,
					"IQ window must cover GPS L1 at 1575.420 MHz (at least 2.2 MS/s)"), 1575420000L, 1000000, 0));
		});
		System.out.println("GPS compact overview layout and status formatting tests passed");
	}
	private static void checkHeight(String details) {
		JLabel label = new JLabel(); label.setFont(label.getFont().deriveFont(12f));
		label.setText(SignalQualityTesterFrame.parameterTable("GPS L1 C/A ACQUISITION", details, "Waiting"));
		View view = (View) label.getClientProperty(BasicHTML.propertyKey);
		view.setSize(820, 1000);
		float height = view.getPreferredSpan(View.Y_AXIS);
		if (height > 180) throw new AssertionError("GPS overview exceeds available height: " + height);
	}
}
