package jspectrumanalyzer.iq;

import java.io.File;
import java.util.Locale;

final class NrProbe {
	public static void main(String[] args) throws Exception {
		if (args.length == 0) {
			System.err.println("Usage: NrProbe <iq.wav> [maxMillis] [stepMillis] [windowMillis] [expectedPci] [startMillis] [surveyOnly] [fastAnalyze] [contextMillis]");
			return;
		}
		File file = new File(args[0]);
		int maxMillis = args.length > 1 ? Integer.parseInt(args[1]) : 10_000;
		int stepMillis = args.length > 2 ? Integer.parseInt(args[2]) : 500;
		int windowMillis = args.length > 3 ? Integer.parseInt(args[3]) : 40;
		int expectedPci = args.length > 4 ? Integer.parseInt(args[4]) : -1;
		int startMillis = args.length > 5 ? Integer.parseInt(args[5]) : 0;
		boolean surveyOnly = args.length <= 6 || Boolean.parseBoolean(args[6]);
		boolean fastAnalyze = args.length > 7 && Boolean.parseBoolean(args[7]);
		int contextMillis = args.length > 8 ? Integer.parseInt(args[8]) : windowMillis;
		try (IQReplayFile replay = IQReplayFile.open(file)) {
			int rate = replay.getSampleRateHz();
			int windowSamples = (int)Math.min(Integer.MAX_VALUE / 2,
					Math.max(65_536L, (long)rate * windowMillis / 1000L));
			byte[] iq = new byte[windowSamples * 2];
			int contextSamples = (int)Math.min(Integer.MAX_VALUE / 2,
					Math.max(windowSamples, (long)rate * contextMillis / 1000L));
			byte[] context = contextSamples > windowSamples ? new byte[contextSamples * 2] : iq;
			NrSignalAnalyzer analyzer = new NrSignalAnalyzer();
			NrLdpcDecoder.SoftBuffer combinedSib1 = null;
			System.out.println(String.format(Locale.US, "%s center %.6f MHz rate %.3f MS/s duration %d ms",
					file.getName(), replay.getCenterFrequencyHz() / 1e6, rate / 1e6, replay.getDurationMillis()));
			for (int position = Math.max(0, startMillis);
				position < Math.min(maxMillis, replay.getDurationMillis()); position += stepMillis) {
				replay.seekMillis(position);
				replay.readLoopedSigned(context);
				if (context != iq)
					System.arraycopy(context, context.length - iq.length, iq, 0, iq.length);
				NrSignalAnalyzer.PssSurvey survey = fastAnalyze ? null : analyzer.surveyPss(iq, iq.length, rate);
				if (survey != null) {
					System.out.println(String.format(Locale.US, "%5d ms wide PSS survey: %d CP candidates",
							position, survey.cpCandidates));
				for (NrSignalAnalyzer.PssSurveyCandidate c : survey.candidates) {
					System.out.println(String.format(Locale.US,
							"%5d ms PSS SCS %d kHz NID2 %d score %.1f CP %.1f repeats %d mean %.1f offset %+7.1f kHz CFO %+6.1f Hz %s SSS %.1f/%.1f rawNID1 %d PCI %s dt %+d (%d bursts) DMRS %.1f/%.1f iBar %d iSSB %d nHF %d %s (%d bursts)",
							position, c.ssbSubcarrierSpacingKhz, c.nid2, c.pssCorrelation * 100d, c.cpCorrelation * 100d,
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
								"          CORESET0 %d RB x %d symbol%s, slot %d, DMRS %.1f%%, first bin %+d (%d pilots)",
								c.pdcch.coresetRb, c.pdcch.coresetDuration,
								c.pdcch.coresetDuration == 1 ? "" : "s", c.pdcch.slot,
								c.pdcch.correlation * 100d, c.pdcch.coresetBin0, c.pdcch.pilots));
						if (c.pdcch.debug.length() > 0) {
							for (String line : c.pdcch.debug.split("\\n"))
								System.out.println("          PDCCH debug " + line);
						}
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
						StringBuilder dmrsSymbols = new StringBuilder();
						for (int symbol : p.dmrsSymbols) {
							if (dmrsSymbols.length() > 0) dmrsSymbols.append('/');
							dmrsSymbols.append(symbol);
						}
						String ldpcStatus = NrLdpcDecoder.supportsTransportBlock(p.transportBlockBits)
								? (p.transportBlock.crcOk ? "OK" : "FAILED")
								: "UNSUPPORTED";
						System.out.println(String.format(Locale.US,
								"          PDSCH SIB1: symbols %d+%d DMRS %s coherence %.1f%% QPSK %.1f%% EVM %.1f%% RE %d E %d TBS %d bits repeats %.1f%% LDPC/CRC %s (%d iterations, syndrome %d)",
								p.startSymbol, p.symbolLength, dmrsSymbols,
								p.dmrsCoherence * 100d, p.qpskCoherence * 100d,
								p.evm * 100d, p.dataResourceElements, p.codedBits,
								p.transportBlockBits, p.transportBlock.repetitionAgreement * 100d,
								ldpcStatus, p.transportBlock.iterations,
								p.transportBlock.syndromeErrors));
						StringBuilder symbolQuality = new StringBuilder("          QPSK by symbol:");
						for (int symbol = 1; symbol < p.symbolQpskCoherence.length; symbol++)
							if (p.symbolQpskCoherence[symbol] > 0)
								symbolQuality.append(String.format(Locale.US, " %d=%.0f%%", symbol,
										p.symbolQpskCoherence[symbol] * 100d));
						System.out.println(symbolQuality);
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
						if (p.rateMatchedLlrs.length > 0
								&& NrLdpcDecoder.supportsTransportBlock(p.transportBlockBits)) {
							if (combinedSib1 == null || combinedSib1.transportBlockBits
									!= p.transportBlockBits)
								combinedSib1 = NrLdpcDecoder.softBuffer(p.transportBlockBits);
							if (combinedSib1 != null
									&& combinedSib1.add(p.rateMatchedLlrs, c.pdcch.dci.rv)) {
								NrLdpcDecoder.Result combined = combinedSib1.decode();
								System.out.println(String.format(Locale.US,
										"          combined SIB1 x%d repeats %.1f%% overlap %.1f%%/%d LDPC/CRC %s, syndrome %d",
										combinedSib1.count(), combined.repetitionAgreement * 100d,
										combinedSib1.lastOverlapAgreement() * 100d,
										combinedSib1.lastOverlapBits(),
										combined.crcOk ? "OK" : "FAILED", combined.syndromeErrors));
							}
						}
					}
				}
				}
				if (surveyOnly) continue;
				NrSignalAnalyzer.Result result = fastAnalyze && context != iq
						? analyzer.analyzeFastWithContext(iq, iq.length, context, context.length,
								context.length - iq.length, rate)
						: fastAnalyze ? analyzer.analyzeFast(iq, iq.length, rate)
						: analyzer.analyze(iq, iq.length, rate);
				System.out.println(String.format(Locale.US,
						"%5d ms result: %s, PCI %s, DMRS %.1f/%.1f, iSSB %d, nHF %d, airload %s",
						position, result.state, result.pci >= 0 ? Integer.toString(result.pci) : "--",
						result.dmrsCorrelation * 100d, result.secondDmrsCorrelation * 100d,
						result.iBarSsb >= 0 ? result.iBarSsb & 3 : -1,
						result.iBarSsb >= 0 ? result.iBarSsb / 4 : -1,
						result.load.valid ? String.format(Locale.US, "%.1f%% (%d symbols)",
								result.load.percent, result.load.symbols) : "--"));
				if (result.mib.valid) {
					int[] coreset = coreset0Geometry(result.mib, result.freqOffsetHz);
					System.out.println(String.format(Locale.US,
							"%5d ms MIB SFN %d SCS %d kHz kSSB %d DMRS-pos %d PDCCH 0x%02X barred %s%s",
							position, result.mib.systemFrameNumber, result.mib.subcarrierSpacingCommonKhz,
							result.mib.ssbSubcarrierOffset, result.mib.dmrsTypeAPosition,
							result.mib.pdcchConfigSib1, result.mib.cellBarred,
							coreset == null ? "" : String.format(Locale.US,
									", CORESET0 %d RB x %d symbols approx %+5.2f..%+5.2f MHz from capture center",
									coreset[0], coreset[1], coreset[2] / 1000d,
									coreset[3] / 1000d)));
				}
				if (result.pdcch.slot >= 0) {
					System.out.println(String.format(Locale.US,
							"%5d ms context CORESET0 %d RB x %d symbol%s, slot %d, DMRS %.1f%%, first bin %+d (%d pilots), DCI %s",
							position, result.pdcch.coresetRb, result.pdcch.coresetDuration,
							result.pdcch.coresetDuration == 1 ? "" : "s", result.pdcch.slot,
							result.pdcch.correlation * 100d, result.pdcch.coresetBin0,
							result.pdcch.pilots, result.pdcch.dci.valid ? "CRC OK" : "not decoded"));
					if (result.pdcch.debug.length() > 0)
						for (String line : result.pdcch.debug.split("\\n"))
							System.out.println("          context PDCCH debug " + line);
				}
				if (result.pdcch.dci.valid) {
					NrPdschDecoder.Decoded p = result.pdcch.pdsch;
					System.out.println(String.format(Locale.US,
							"%5d ms context DCI AL%d nCCE%d map%d swap%d sign%+d RB %d+%d time %d VRB %d MCS %d RV %d, TBS %d, CRC %s, syndrome %d",
							position, result.pdcch.dci.aggregation, result.pdcch.dci.ncce,
							result.pdcch.dci.mapping, result.pdcch.dci.qpskSwap,
							result.pdcch.dci.qpskSign,
							result.pdcch.dci.rbStart, result.pdcch.dci.rbLength,
							result.pdcch.dci.timeAllocation, result.pdcch.dci.vrbMapping,
							result.pdcch.dci.mcs,
							result.pdcch.dci.rv, p.transportBlockBits,
							p.transportBlock.crcOk ? "OK" : "fail",
							p.transportBlock.syndromeErrors));
					if (p.demodulated) {
						StringBuilder dmrsSymbols = new StringBuilder();
						for (int symbol : p.dmrsSymbols) {
							if (dmrsSymbols.length() > 0) dmrsSymbols.append('/');
							dmrsSymbols.append(symbol);
						}
						System.out.println(String.format(Locale.US,
								"          context PDSCH symbols %d+%d DMRS %s coherence %.1f%% QPSK %.1f%% EVM %.1f%% RE %d E %d repeats %.1f%%",
								p.startSymbol, p.symbolLength, dmrsSymbols,
								p.dmrsCoherence * 100d, p.qpskCoherence * 100d,
								p.evm * 100d, p.dataResourceElements, p.codedBits,
								p.transportBlock.repetitionAgreement * 100d));
					}
				}
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

	private static int[] coreset0Geometry(NrSignalAnalyzer.MibData mib, int ssbOffsetHz) {
		int coreSetIndex = (mib.pdcchConfigSib1 >>> 4) & 15;
		int[][] table = mib.subcarrierSpacingCommonKhz == 30 ? CORESET_30_30 : CORESET_15_15;
		if (coreSetIndex < 0 || coreSetIndex >= table.length || table[coreSetIndex][0] <= 0)
			return null;
		int rb = table[coreSetIndex][0], symbols = table[coreSetIndex][1], offsetRb = table[coreSetIndex][2];
		int kSsbBins = mib.ssbSubcarrierOffset * 15 / mib.subcarrierSpacingCommonKhz;
		int startKhz = (int)Math.round(ssbOffsetHz / 1000d
				- (119 + kSsbBins + offsetRb * 12) * mib.subcarrierSpacingCommonKhz);
		int endKhz = startKhz + rb * 12 * mib.subcarrierSpacingCommonKhz;
		return new int[] { rb, symbols, startKhz, endKhz };
	}

	private static final int[][] CORESET_15_15 = {
		{ 24, 2, 0 }, { 24, 2, 2 }, { 24, 2, 4 }, { 24, 3, 0 },
		{ 24, 3, 2 }, { 24, 3, 4 }, { 48, 1, 12 }, { 48, 1, 16 },
		{ 48, 2, 12 }, { 48, 2, 16 }, { 48, 3, 12 }, { 48, 3, 16 },
		{ 96, 1, 38 }, { 96, 2, 38 }, { 96, 3, 38 }, { 0, 0, 0 }
	};

	private static final int[][] CORESET_30_30 = {
		{ 24, 2, 0 }, { 24, 2, 1 }, { 24, 2, 2 }, { 24, 2, 3 },
		{ 24, 2, 4 }, { 24, 3, 0 }, { 24, 3, 1 }, { 24, 3, 2 },
		{ 24, 3, 3 }, { 24, 3, 4 }, { 48, 1, 12 }, { 48, 1, 14 },
		{ 48, 1, 16 }, { 48, 2, 12 }, { 48, 2, 14 }, { 48, 2, 16 }
	};
}
