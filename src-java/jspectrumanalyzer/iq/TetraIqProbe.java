package jspectrumanalyzer.iq;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Command-line TETRA burst-acquisition diagnostic for IQ WAV/RAW recordings. */
public final class TetraIqProbe {
	private TetraIqProbe() { }

	public static void main(String[] args) throws Exception {
		if (args.length == 0) {
			System.err.println("Usage: TetraIqProbe [--start seconds] [--duration seconds] "
					+ "[--valid-only|--sds-only] "
					+ "<IQ.wav|IQ.pcm|IQ.cu8|IQ.cs8> [...]");
			System.exit(2);
		}
		long startMillis = 0L, limitMillis = Long.MAX_VALUE;
		boolean validOnly = false;
		boolean sdsOnly = false;
		List<File> files = new ArrayList<File>();
		for (int n = 0; n < args.length; n++) {
			String value = args[n];
			if ("--start".equals(value) && n + 1 < args.length)
				startMillis = Math.max(0L, Math.round(Double.parseDouble(args[++n]) * 1000d));
			else if ("--duration".equals(value) && n + 1 < args.length)
				limitMillis = Math.max(1L, Math.round(Double.parseDouble(args[++n]) * 1000d));
			else if ("--valid-only".equals(value)) validOnly = true;
			else if ("--sds-only".equals(value)) sdsOnly = true;
			else files.add(new File(value));
		}
		for (File file : files) probe(file, startMillis, limitMillis, validOnly, sdsOnly);
	}

	private static void probe(File file, long requestedStart, long requestedLimit,
			boolean validOnly, boolean sdsOnly) throws Exception {
		try (IQReplayFile replay = IQReplayFile.open(file)) {
			TetraDecoderCore decoder = new TetraDecoderCore();
			int rate = replay.getSampleRateHz();
			int blockBytes = Math.max(32_768, Math.min(524_288, rate / 10 * 2)) & ~1;
			byte[] iq = new byte[blockBytes];
			long fileDuration = replay.getDurationMillis();
			long startOffset = Math.min(requestedStart, Math.max(0L, fileDuration - 1L));
			long endOffset = requestedLimit == Long.MAX_VALUE ? fileDuration
					: Math.min(fileDuration, startOffset + requestedLimit);
			replay.seekMillis(startOffset);
			long processed = startOffset;
			long recordingStart = IQReplayFile.getRecordingStartEpochMillis(file);
			long started = System.nanoTime();
			long emitted = 0L;
			long decodedBsch = 0L;
			long decodedSchf = 0L;
			TetraBschDecoder.SystemInfo currentSystem = null;
			while (processed < endOffset) {
				int read = replay.readLoopedSigned(iq);
				if (read <= 0) break;
				decoder.accept(replay.getCenterFrequencyHz(), rate, iq, read,
						recordingStart + processed);
				List<TetraDecoderCore.Burst> bursts = decoder.takeBursts();
				List<TetraMacDecoder.SdsMessage> messages = decoder.takeSdsMessages();
				for (TetraMacDecoder.SdsMessage message : messages)
					System.out.println("  " + message.timestampMillis + "  " + message);
				for (TetraDecoderCore.Burst burst : bursts) {
					emitted++;
					if (sdsOnly) continue;
					TetraBschDecoder.DecodeAttempt bsch = burst.type == TetraDecoderCore.BurstType.SYNC
							? TetraBschDecoder.analyzeBurst(burst) : null;
					TetraBschDecoder.SystemInfo sysinfo = bsch == null ? null : bsch.systemInfo;
					if (sysinfo != null) {
						decodedBsch++;
						currentSystem = sysinfo;
					}
					TetraSchfDecoder.DecodeAttempt schf = currentSystem != null
							&& burst.type == TetraDecoderCore.BurstType.NDB1
							? TetraSchfDecoder.analyzeBurst(burst, currentSystem) : null;
					if (schf != null && schf.crcOk) decodedSchf++;
					if (validOnly && sysinfo == null && (schf == null || !schf.crcOk)) continue;
					System.out.println(String.format(Locale.US,
							"  %d  %-4s corr %5.1f%% CFO %+8.1f Hz  %s%s%s",
							burst.timestampMillis, burst.type, burst.correlation * 100d,
							burst.frequencyErrorHz, burst.bitPreview(), bsch == null ? ""
									: sysinfo == null ? String.format(Locale.US,
										"  BSCH CRC fail %04X metric %d", bsch.crc, bsch.metric)
									: String.format(Locale.US,
										"  BSCH MCC %d MNC %d CC %d TS %d FN %d MN %d metric %d",
										sysinfo.mcc, sysinfo.mnc, sysinfo.colourCode,
										sysinfo.timeslot, sysinfo.frame, sysinfo.multiframe,
										sysinfo.metric), schf == null ? ""
									: schf.crcOk ? String.format(Locale.US,
										"  SCH/F CRC OK shift %+d metric %.2f payload %s",
										schf.alignmentShift, schf.metric, schf.payloadHex())
									: String.format(Locale.US,
										"  SCH/F CRC fail %04X metric %.2f",
										schf.crc, schf.metric)));
				}
				processed += Math.round(read / 2d / rate * 1000d);
			}
			TetraDecoderCore.Result result = decoder.latest();
			System.out.println(String.format(Locale.US,
					"%s: %.3f MS/s, %.3f s, processing %.3f kS/s, bursts %d "
					+ "(NDB1 %d / NDB2 %d / SYNC %d), emitted %d, BSCH CRC OK %d, "
					+ "SCH CRC OK %d, MAC clear/encrypted %d/%d, fragments %d/%d, "
					+ "D-SDS-DATA %d, best %.1f%%, CPU %.3f s",
					file.getName(), rate / 1e6, (endOffset - startOffset) / 1000d,
					result.processingRateHz / 1e3,
					result.bursts, result.ndb1Bursts, result.ndb2Bursts, result.syncBursts,
					emitted, sdsOnly ? result.decodedBsch : decodedBsch,
					sdsOnly ? result.decodedSchf : decodedSchf,
					result.clearResources, result.encryptedResources,
					result.fragmentStarts, result.fragmentEnds, result.sdsMessages,
					result.bestCorrelation * 100d,
					(System.nanoTime() - started) / 1e9));
			TetraCellInfo.Snapshot cell = result.cellInfo;
			if (cell.hasSysInfo) {
				System.out.println(String.format(Locale.US,
						"  Cell: role %s, tuned %.6f MHz, SYSINFO main %.6f MHz "
						+ "(carrier %d, band %d, offset %d), neighbours %d",
						cell.role, cell.tunedHz / 1e6, cell.mainCarrierHz / 1e6,
						cell.mainCarrier, cell.frequencyBand, cell.offset, cell.neighbours.size()));
				for (TetraCellInfo.Neighbour neighbour : cell.neighbours)
					System.out.println(String.format(Locale.US,
							"    Neighbour %d: %.6f MHz (carrier %d band %d offset %d)",
							neighbour.cellId, neighbour.carrierHz / 1e6,
							neighbour.mainCarrier, neighbour.frequencyBand, neighbour.offset));
			}
		}
	}
}
