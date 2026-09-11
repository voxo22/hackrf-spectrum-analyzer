package jspectrumanalyzer.iq;

import java.io.File;
import java.util.Locale;

final class UmtsProbe {
	public static void main(String[] args) throws Exception {
		if (args.length == 0) {
			System.err.println("Usage: UmtsProbe <iq.wav> [maxMillis] [stepMillis] [windowMillis]");
			return;
		}
		File file = new File(args[0]);
		int maxMillis = args.length > 1 ? Integer.parseInt(args[1]) : 20_000;
		int stepMillis = args.length > 2 ? Integer.parseInt(args[2]) : 1_000;
		int windowMillis = args.length > 3 ? Integer.parseInt(args[3]) : 80;
		try (IQReplayFile replay = IQReplayFile.open(file)) {
			int rate = replay.getSampleRateHz();
			int windowSamples = (int)Math.min(Integer.MAX_VALUE / 2,
					Math.max(65_536L, (long)rate * windowMillis / 1000L));
			byte[] iq = new byte[windowSamples * 2];
			UmtsSignalAnalyzer analyzer = new UmtsSignalAnalyzer();
			int uarfcn = UmtsSignalAnalyzer.uarfcnFromFrequency(replay.getCenterFrequencyHz());
			System.out.println(String.format(Locale.US,
					"%s center %.6f MHz UARFCN %s rate %.3f MS/s duration %d ms",
					file.getName(), replay.getCenterFrequencyHz() / 1e6,
					uarfcn >= 0 ? Integer.toString(uarfcn) : "--",
					rate / 1e6, replay.getDurationMillis()));
			for (int position = 0; position < Math.min(maxMillis, replay.getDurationMillis()); position += stepMillis) {
				replay.seekMillis(position);
				replay.readLoopedSigned(iq);
				UmtsSignalAnalyzer.Result result = analyzer.analyze(iq, iq.length, rate);
				if (result.candidates.isEmpty()) {
					System.out.println(String.format(Locale.US,
							"%5d ms no PSC candidate state %s P-SCH %.1f%% offset %+.1f Hz",
							position, result.state, result.pschCorrelation * 100d, result.cfoHz));
					continue;
				}
				for (UmtsSignalAnalyzer.Candidate c : result.candidates) {
					UmtsSystemInformationDecoder.Snapshot si = c.systemInformation;
					System.out.println(String.format(Locale.US,
							"%5d ms PSC %3d group %2d CPICH %.1f%% Ec/N0 %.1f dB RSCP %.1f dBFS frameSlot %2d offset %+.1f Hz %s BCH %s SI %s",
							position, c.psc, c.codeGroup, c.cpichCorrelation * 100d, c.ecNoDb,
							c.rscpDbfs, c.frameSlotPhase, c.cfoHz,
							c.iqOrientation > 0 ? "normal" : "conjugated",
							c.bch.valid ? "CRC OK " + c.bch.payloadHex() + " [frame "
									+ c.bch.firstFrame + ", SFN " + c.bch.sfn + ", " + c.bch.payloadType()
									+ ", try " + c.bch.attempts + "]"
									: "waiting",
							si.mibValid ? si.mcc + "-" + si.mnc + " LAC "
									+ (si.sib1Valid ? si.lac : "--") + " Cell "
									+ (si.sib3Valid ? si.cellIdentity : "--") + " [" + si.receivedBlocks() + "]"
									: si.hasAny() ? "[" + si.receivedBlocks() + "]" : "waiting"));
					if (si.hasBlock(5) && Boolean.getBoolean("umts.probe.sib5"))
						System.out.println("        SIB5 " + si.blockBits(5) + " bits " + si.blockHex(5)
								+ sib5Summary(si.sib5));
					if (c.load.valid && Boolean.getBoolean("umts.probe.load"))
						System.out.printf(Locale.US, "        Air load %.2f%% (%d/%d, %d blocks)%n",
								c.load.percent, c.load.activeCodeSamples,
								c.load.totalCodeSamples, c.load.blocks);
					if (Boolean.getBoolean("umts.probe.pch")) {
						System.out.printf(Locale.US, "        PCH %s; checked %d frames; CRC blocks %d%n",
								c.paging.state, c.paging.checkedFrames, c.paging.blocks.size());
						for (UmtsPchDecoder.Block block : c.paging.blocks)
							System.out.printf(Locale.US, "          SFN %d metric %.3f paging records %d%n",
									block.sfn, block.metric, block.pagingEvents);
						for (UmtsPagingDecoder.Event event : c.paging.paging.events)
							System.out.printf(Locale.US, "            UE %d %s %s %s home %s-%s%n",
									event.clientNumber, event.identityType, event.domain, event.cause,
									event.homeMcc.length() == 0 ? "--" : event.homeMcc,
									event.homeMnc.length() == 0 ? "--" : event.homeMnc);
					}
				}
			}
		}
	}

	private static String sib5Summary(UmtsSib5Decoder.Config config) {
		if (!config.valid) return " [configuration parse failed]";
		UmtsSib5Decoder.Channel channel = config.pagingChannel;
		if (channel == null) return " [no PICH-associated S-CCPCH]";
		UmtsSib5Decoder.Transport transport = channel.pagingTransport();
		UmtsSib5Decoder.DynamicFormat dynamic = transport == null ? null : transport.format.primary();
		return String.format(Locale.US, " [S-CCPCH SF%d/%d timing %d; PICH %d/%d; PCH TTI %s TB %s CRC %s]",
				channel.spreadingFactor, channel.code, channel.timingOffset,
				channel.pichCode, channel.indicatorsPerFrame,
				transport == null ? "--" : transport.format.ttiMs,
				dynamic == null ? "--" : dynamic.blockBits,
				transport == null ? "--" : transport.format.crcBits);
	}
}
