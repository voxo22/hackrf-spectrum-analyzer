package jspectrumanalyzer.iq;

import java.io.File;
import java.util.Locale;

final class NrProbe {
	public static void main(String[] args) throws Exception {
		if (args.length == 0) {
			System.err.println("Usage: NrProbe <iq.wav> [maxMillis] [stepMillis] [windowMillis] [expectedPci] [startMillis] [surveyOnly]");
			return;
		}
		File file = new File(args[0]);
		int maxMillis = args.length > 1 ? Integer.parseInt(args[1]) : 10_000;
		int stepMillis = args.length > 2 ? Integer.parseInt(args[2]) : 500;
		int windowMillis = args.length > 3 ? Integer.parseInt(args[3]) : 40;
		int expectedPci = args.length > 4 ? Integer.parseInt(args[4]) : -1;
		int startMillis = args.length > 5 ? Integer.parseInt(args[5]) : 0;
		boolean surveyOnly = args.length <= 6 || Boolean.parseBoolean(args[6]);
		try (IQReplayFile replay = IQReplayFile.open(file)) {
			int rate = replay.getSampleRateHz();
			int windowSamples = (int)Math.min(Integer.MAX_VALUE / 2,
					Math.max(65_536L, (long)rate * windowMillis / 1000L));
			byte[] iq = new byte[windowSamples * 2];
			NrSignalAnalyzer analyzer = new NrSignalAnalyzer();
			double[] combinedSib1 = null;
			int combinedSib1Count = 0;
			System.out.println(String.format(Locale.US, "%s center %.6f MHz rate %.3f MS/s duration %d ms",
					file.getName(), replay.getCenterFrequencyHz() / 1e6, rate / 1e6, replay.getDurationMillis()));
			for (int position = Math.max(0, startMillis);
					position < Math.min(maxMillis, replay.getDurationMillis()); position += stepMillis) {
				replay.seekMillis(position);
				replay.readLoopedSigned(iq);
				NrSignalAnalyzer.PssSurvey survey = analyzer.surveyPss(iq, iq.length, rate);
				System.out.println(String.format(Locale.US, "%5d ms wide PSS survey: %d CP candidates",
						position, survey.cpCandidates));
				for (NrSignalAnalyzer.PssSurveyCandidate c : survey.candidates) {
					System.out.println(String.format(Locale.US,
							"%5d ms PSS NID2 %d score %.1f CP %.1f repeats %d mean %.1f offset %+7.1f kHz CFO %+6.1f Hz %s SSS %.1f/%.1f rawNID1 %d PCI %s dt %+d (%d bursts) DMRS %.1f/%.1f iBar %d iSSB %d nHF %d %s (%d bursts)",
							position, c.nid2, c.pssCorrelation * 100d, c.cpCorrelation * 100d,
							c.periodicRepeats, c.periodicCorrelation * 100d, c.freqOffsetHz / 1000d,
							c.cfoHz, c.iqOrientation > 0 ? "normal" : "conjugated",
							c.sssCorrelation * 100d, c.secondSssCorrelation * 100d, c.rawNid1,
							c.pci >= 0 ? Integer.toString(c.pci) : "--", c.sssTimingDelta, c.sssRepeats,
							c.dmrsCorrelation * 100d, c.secondDmrsCorrelation * 100d, c.iBarSsb,
							c.iBarSsb >= 0 ? c.iBarSsb & 3 : -1, c.iBarSsb >= 0 ? c.iBarSsb / 4 : -1,
							c.dmrsConfirmed ? "CONFIRMED" : "unconfirmed", c.dmrsBursts));
					if (c.dmrsConfirmed) {
						System.out.println(String.format(Locale.US,
								"          PBCH EVM %.1f%% CRC %s MIB %s",
								c.pbchEvm * 100d, c.pbchCrcOk ? "OK" : "fail",
								c.mib.valid ? String.format(Locale.US,
										"SFN %d SCS %d kHz kSSB %d DMRS-pos %d PDCCH 0x%02X barred %s",
										c.mib.systemFrameNumber, c.mib.subcarrierSpacingCommonKhz,
										c.mib.ssbSubcarrierOffset, c.mib.dmrsTypeAPosition,
										c.mib.pdcchConfigSib1, c.mib.cellBarred) : "--"));
					}
					if (c.pdcch.slot >= 0) {
						System.out.println(String.format(Locale.US,
								"          CORESET0 48 RB x 1 symbol, slot %d, DMRS %.1f%%, first bin %+d (%d pilots)",
								c.pdcch.slot, c.pdcch.correlation * 100d, c.pdcch.coresetBin0,
								c.pdcch.pilots));
					}
					if (c.pdcch.dci.valid) {
						NrPdcchDecoder.Dci d = c.pdcch.dci;
						System.out.println(String.format(Locale.US,
								"          SI-RNTI DCI 1_0 CRC OK: AL%d nCCE%d RB %d+%d time %d VRB %d MCS %d RV %d SII %d reserved 0x%X IQ swap%d sign%+d",
								d.aggregation, d.ncce, d.rbStart, d.rbLength, d.timeAllocation,
								d.vrbMapping, d.mcs, d.rv, d.systemInformationIndicator, d.reserved,
								d.qpskSwap, d.qpskSign));
					}
					if (c.pdcch.pdsch.demodulated) {
						NrPdschDecoder.Decoded p = c.pdcch.pdsch;
						System.out.println(String.format(Locale.US,
								"          PDSCH SIB1: symbols 1+13 DMRS 2/7/11 coherence %.1f%% QPSK %.1f%% EVM %.1f%% RE %d E %d TBS %d bits repeats %.1f%% LDPC/CRC %s (%d iterations)",
								p.dmrsCoherence * 100d, p.qpskCoherence * 100d, p.evm * 100d, p.dataResourceElements,
								p.codedBits, p.transportBlockBits, p.transportBlock.repetitionAgreement * 100d,
								p.transportBlock.crcOk ? "OK" : "FAILED", p.transportBlock.iterations));
						if (p.transportBlock.crcOk) {
							StringBuilder hex = new StringBuilder();
							for (byte value : p.transportBlock.payload) hex.append(String.format("%02X", value & 0xff));
							System.out.println("          BCCH-DL-SCH " + hex);
							NrSignalAnalyzer.Sib1Data sib1 = NrSib1Decoder.decode(p.transportBlock.payload);
							System.out.println(sib1.valid
									? "          SIB1 ASN.1: PLMN " + sib1.plmns + " TAC " + sib1.trackingAreaCode
											+ " NCI " + sib1.cellIdentity + " bands " + sib1.frequencyBands
											+ " carrier " + sib1.channelBandwidthMhz + " MHz ("
											+ sib1.carrierBandwidthRb + " RB @ " + sib1.carrierScsKhz + " kHz)"
											+ " BWP " + sib1.initialBwpRb + " RB @ " + sib1.initialBwpScsKhz
											+ " kHz SI " + sib1.schedules + " window " + sib1.siWindowSlots
											+ " paging RF" + sib1.pagingCycleFrames + " "
											+ sib1.pagingFrameOffsetType + "+" + sib1.pagingFrameOffset
											+ " ns " + sib1.pagingOccasions
									: "          SIB1 ASN.1: decode failed");
						}
						if (p.rateMatchedLlrs.length > 0) {
							if (combinedSib1 == null) combinedSib1 = new double[p.rateMatchedLlrs.length];
							if (combinedSib1.length == p.rateMatchedLlrs.length) {
								for (int i = 0; i < combinedSib1.length; i++)
									combinedSib1[i] += p.rateMatchedLlrs[i];
								combinedSib1Count++;
								NrLdpcDecoder.Result combined = NrLdpcDecoder.decode(combinedSib1,
										p.transportBlockBits, c.pdcch.dci.rv);
								System.out.println(String.format(Locale.US,
										"          combined SIB1 x%d repeats %.1f%% LDPC/CRC %s",
										combinedSib1Count, combined.repetitionAgreement * 100d,
										combined.crcOk ? "OK" : "FAILED"));
							}
						}
					}
				}
				if (surveyOnly) continue;
				NrSignalAnalyzer.Result result = analyzer.analyze(iq, iq.length, rate);
				System.out.println(String.format(Locale.US,
						"%5d ms result: %s, PCI %s, DMRS %.1f/%.1f, iSSB %d, nHF %d, airload %s",
						position, result.state, result.pci >= 0 ? Integer.toString(result.pci) : "--",
						result.dmrsCorrelation * 100d, result.secondDmrsCorrelation * 100d,
						result.iBarSsb >= 0 ? result.iBarSsb & 3 : -1,
						result.iBarSsb >= 0 ? result.iBarSsb / 4 : -1,
						result.load.valid ? String.format(Locale.US, "%.1f%% (%d symbols)",
								result.load.percent, result.load.symbols) : "--"));
				if (expectedPci >= 0) {
					NrSignalAnalyzer.ExpectedScore expected = analyzer.scoreExpectedPci(iq, iq.length, rate,
							expectedPci, result);
					System.out.println(String.format(Locale.US,
							"%5d ms expected PCI %d PSS %.1f SSS %.1f offset %+6.1f kHz CFO %+6.1f Hz CP %d %s%s rawBest %d",
							position, expected.expectedPci, expected.pssCorrelation * 100d,
							expected.sssCorrelation * 100d, expected.freqOffsetHz / 1000d, expected.cfoHz,
							expected.cpSamples, expected.iqOrientation > 0 ? "normal" : "conjugated",
							expected.reversed ? " reversed" : "", expected.bestRawPci));
				}
				if (result.candidates.isEmpty()) {
					System.out.println(String.format(Locale.US, "%5d ms no NR PCI candidate state %s",
							position, result.state));
					continue;
				}
				for (NrSignalAnalyzer.Candidate c : result.candidates) {
					System.out.println(String.format(Locale.US,
							"%5d ms PCI %4s NID1 %3s NID2 %d rawPCI %4d rawNID1 %3d rawNID2 %d PSS %.1f SSS %.1f offset %+6.1f kHz CFO %+6.1f Hz CP %d %s",
							position, c.pci >= 0 ? Integer.toString(c.pci) : "--",
							c.nid1 >= 0 ? Integer.toString(c.nid1) : "--", c.nid2,
							c.rawPci, c.rawNid1, c.rawNid2, c.pssCorrelation * 100d, c.sssCorrelation * 100d,
							c.freqOffsetHz / 1000d, c.cfoHz, c.cpSamples,
							c.iqOrientation > 0 ? "normal" : "conjugated"));
				}
			}
		}
	}
}
