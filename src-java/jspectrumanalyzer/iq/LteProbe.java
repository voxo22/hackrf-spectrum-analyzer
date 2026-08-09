package jspectrumanalyzer.iq;

import java.io.File;
import java.util.Locale;

final class LteProbe {
	public static void main(String[] args) throws Exception {
		if (args.length == 0) {
			System.err.println("Usage: LteProbe <iq.wav> [maxMillis] [stepMillis] [windowMillis]");
			return;
		}
		File file = new File(args[0]);
		int maxMillis = args.length > 1 ? Integer.parseInt(args[1]) : 20_000;
		int stepMillis = args.length > 2 ? Integer.parseInt(args[2]) : 1_000;
		int windowMillis = args.length > 3 ? Integer.parseInt(args[3]) : 50;
		try (IQReplayFile replay = IQReplayFile.open(file)) {
			int rate = replay.getSampleRateHz();
			int windowSamples = (int) Math.min(Integer.MAX_VALUE / 2,
					Math.max(65_536L, (long) rate * windowMillis / 1000L));
			byte[] iq = new byte[windowSamples * 2];
			LteSignalAnalyzer analyzer = new LteSignalAnalyzer();
			for (int position = 0; position < Math.min(maxMillis, replay.getDurationMillis()); position += stepMillis) {
				replay.seekMillis(position);
				replay.readLoopedSigned(iq);
				LteSignalAnalyzer.Result result = analyzer.analyze(iq, iq.length, rate);
				boolean printed = false;
				for (LteSignalAnalyzer.Candidate c : result.candidates) {
					if (c.pci < 0) {
						continue;
					}
					printed = true;
					String dci = c.sib1Pdcch.systemDci.format1A
							? ("L" + c.sib1Pdcch.systemDci.aggregation + "/CCE" + c.sib1Pdcch.systemDci.firstCce
									+ "/RB" + c.sib1Pdcch.systemDci.rbStart + "+" + c.sib1Pdcch.systemDci.rbLength
									+ "/MCS" + c.sib1Pdcch.systemDci.mcs + "/RV" + c.sib1Pdcch.systemDci.rv
									+ "/N1A" + c.sib1Pdcch.systemDci.nPrb1a)
							: "--";
					int transportBits = c.sib1Transport.transportBlockBits;
					System.out.println(String.format(Locale.US,
							"%5d ms PCI %3d MIB %s %dRB %dp SSS %.1f CFI %s PDCCH %s DCI %s PDSCH %dRE EVM %.1f TB %db K %s CRC %s PLMN %s hint %s PBCH %s",
							position, c.pci, c.mib.valid ? "OK" : "--", c.mib.bandwidthRb, c.mib.antennaPorts,
							c.sssCorrelation * 100, c.sib1Cfi.valid ? Integer.toString(c.sib1Cfi.cfi) : "--",
							c.sib1Pdcch.valid ? String.format(Locale.US, "EVM %.1f", c.sib1Pdcch.qpskEvm * 100) : "--",
							dci, c.sib1Pdsch.resourceElements, c.sib1Pdsch.qpskEvm * 100,
							transportBits, transportBits >= 0 ? Integer.toString(transportBits + 24) : "--",
							c.sib1Transport.valid ? "OK" : "fail",
							c.sib1.valid && !c.sib1.plmns.isEmpty() ? c.sib1.plmns.get(0) : "--",
							LteSignalAnalyzer.sib1DciDiagnostics(), LteSignalAnalyzer.pbchDiagnostics()));
				}
				if (!printed) {
					System.out.println(String.format(Locale.US, "%5d ms no PCI/MIB candidate state %s", position,
							result.state));
				}
			}
		}
	}
}
