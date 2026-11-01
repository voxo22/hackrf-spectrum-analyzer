package jspectrumanalyzer.iq;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Random;
import java.util.Arrays;

import javax.swing.RowSorter;
import javax.swing.SortOrder;

/** Standalone deterministic tests for TETRA IQ burst acquisition. */
public final class TetraDecoderCoreTest {
	private static final int RATE = 72_000;
	private static final int SAMPLES_PER_SYMBOL = 4;

	private TetraDecoderCoreTest() { }

	public static void main(String[] args) {
		testTetraCellInformation();
		testSchHdSysInfoBlock();
		testMacNullPaddingIsNotSysInfo();
		testCmceAlertEvent();
		testCmceVoiceCallTable();
		testBurst(TetraDecoderCore.BurstType.NDB1, 275d);
		testBurst(TetraDecoderCore.BurstType.NDB2, -410d);
		testBurst(TetraDecoderCore.BurstType.SYNC, 620d);
		testChunkBoundary();
		testPid209ArrivalList();
		testPid209AlternateArrivalList();
		testPid209ExtendedArrivalList();
		testPid209VariantArrivalList();
		testPid209BoardAggregation();
		testPid209BoardSelectionSurvivesRefresh();
		testPid209ConfirmedMappings();
		testPid209InterleavedRowsAreGrouped();
		testPid209TableLayoutPersistence();
		testPid209ScheduledTimeEncoding();
		testTetraAnalyzerSdsEventsAndLog();
		System.out.println("TetraDecoderCoreTest: OK");
	}

	private static void testPid209ArrivalList() {
		String hex = "D102450114001E07DA000303001E07DA0008030A";
		int[] bits = new int[hex.length() * 4];
		for (int n = 0; n < hex.length(); n++) {
			int value = Character.digit(hex.charAt(n), 16);
			for (int bit = 0; bit < 4; bit++) bits[n * 4 + bit] = value >>> (3 - bit) & 1;
		}
		TetraMacDecoder.SdsMessage message = new TetraMacDecoder.SdsMessage(
				0L, 3015432, 9750004, 4, bits.length, 209, bits);
		TetraPid209Decoder.Update update = TetraPid209Decoder.decode(message);
		check(update != null, "PID 209 arrival update not decoded");
		check(update.counterCandidate == 0x45 && update.formatByte == 0x14
				&& update.arrivals.size() == 2,
				"PID 209 header/row count");
		check(update.arrivals.get(0).fieldA == 0x001e
				&& update.arrivals.get(0).fieldB == 0x07da
				&& update.arrivals.get(0).timeValue == 3,
				"PID 209 first arrival");
		check(update.arrivals.get(1).timeValue == 8, "PID 209 second arrival");
	}

	private static void testPid209AlternateArrivalList() {
		TetraPid209Decoder.Update update = decodePid209(
				"D1029D010C002807DC000103002008A0000203004708D6000503"
				+ "003D0800000703002008A0000F03002807DC0010030A");
		check(update != null && update.formatByte == 0x0c && update.arrivals.size() == 6,
				"PID 209 format 0x0C");
		check(update.arrivals.get(0).fieldA == 0x0028
				&& update.arrivals.get(0).fieldB == 0x07dc
				&& update.arrivals.get(0).timeValue == 1
				&& update.arrivals.get(0).extensionCode == -1,
				"PID 209 format 0x0C first arrival");
	}

	private static void testPid209ExtendedArrivalList() {
		TetraPid209Decoder.Update update = decodePid209(
				"D102BB0115001C07DE0002030047007508CF0004030046000A07FF0004030047"
				+ "001C07DE000E030047000A07FF000E030047007508CF00130300460A");
		check(update != null && update.formatByte == 0x15 && update.arrivals.size() == 6,
				"PID 209 format 0x15");
		check(update.arrivals.get(0).fieldA == 0x001c
				&& update.arrivals.get(0).fieldB == 0x07de
				&& update.arrivals.get(0).timeValue == 2
				&& update.arrivals.get(0).flags == 3
				&& update.arrivals.get(0).extensionCode == 0x0047,
				"PID 209 format 0x15 first arrival");
		check(update.arrivals.get(1).extensionCode == 0x0046,
				"PID 209 format 0x15 extension");
		check(update.summary().contains("extra? G"),
				"PID 209 printable stand label");
	}

	private static void testPid209VariantArrivalList() {
		TetraPid209Decoder.Update update = decodePid209(
				"D102E0012112001D07D20000010041001D07D30000010042"
				+ "002307F5000103004A0A");
		check(update != null && update.formatByte == 0x21 && update.variantByte == 0x12
				&& update.arrivals.size() == 3, "PID 209 format 0x21/0x12");
		check(update.arrivals.get(0).fieldA == 0x001d
				&& update.arrivals.get(0).fieldB == 0x07d2
				&& update.arrivals.get(0).timeValue == 0
				&& update.arrivals.get(0).flags == 1
				&& update.arrivals.get(0).extensionCode == 0x0041,
				"PID 209 format 0x21 first arrival");
	}

	private static TetraPid209Decoder.Update decodePid209(String hex) {
		return TetraPid209Decoder.decode(pid209Message(hex, 0L, 3015432));
	}

	private static void testPid209BoardAggregation() {
		TetraPid209BoardTablePanel panel = new TetraPid209BoardTablePanel();
		TetraMacDecoder.SdsMessage first = pid209Message(
				"D102450114001E07DA000303001E07DA0008030A", 1_000L, 3015432);
		TetraMacDecoder.SdsMessage repeated = pid209Message(
				"D102460114001E07DA000303001E07DA0008030A", 2_000L, 3015432);
		panel.accept(Arrays.asList(first, repeated));
		check(panel.boardCount() == 1, "PID 209 board grouping by destination SSI");
		check("3,8".equals(panel.departuresForBoard(3015432)),
				"PID 209 board departures");
		check(panel.lastChangeForBoard(3015432) == 1_000L,
				"PID 209 sequence-only update counted as content change");
		panel.accept(Arrays.asList(pid209Message(
				"D102520114001E07DA000303001E07DA0007030A", 3_000L, 3015432)));
		check("3,7".equals(panel.departuresForBoard(3015432)),
				"PID 209 changed board departures");
		check(panel.lastChangeForBoard(3015432) == 3_000L,
				"PID 209 content change time");
	}

	private static void testTetraCellInformation() {
		TetraCellInfo info = new TetraCellInfo();
		int[] sysInfo = new int[124];
		put(sysInfo, 0, 2, 2); // MAC-BROADCAST
		put(sysInfo, 2, 2, 0); // SYSINFO
		put(sysInfo, 4, 12, 953);
		put(sysInfo, 16, 4, 4);
		put(sysInfo, 20, 2, 0);
		put(sysInfo, 22, 3, 4);
		put(sysInfo, 43, 1, 0); // hyperframe branch
		put(sysInfo, 44, 16, 0x1234);
		put(sysInfo, 60, 2, 3); // security optional field
		put(sysInfo, 62, 1, 1);
		put(sysInfo, 63, 1, 1);
		put(sysInfo, 64, 1, 0);
		put(sysInfo, 82, 14, 3210);
		put(sysInfo, 96, 16, 0xa55a);
		put(sysInfo, 112, 1, 1);
		put(sysInfo, 118, 1, 1);
		put(sysInfo, 121, 1, 1);
		info.acceptSysInfo(sysInfo, 123L);
		TetraCellInfo.Snapshot current = info.snapshot(423_825_000L);
		check(current.hasSysInfo && current.mainCarrierHz == 423_825_000L,
				"SYSINFO carrier frequency calculation");
		check(current.role == TetraCellInfo.Role.MAIN,
				"tuned SYSINFO main carrier classification");
		check(current.system.locationArea == 3210
				&& current.system.subscriberClass == 0xa55a
				&& current.system.authenticationRequired == 1
				&& current.system.voiceService == 1
				&& current.system.sndcpService == 1,
				"complete TTT-compatible SYSINFO tail");
		int[] nonSecuritySysInfo = sysInfo.clone();
		put(nonSecuritySysInfo, 60, 2, 0);
		info.acceptSysInfo(nonSecuritySysInfo, 124L);
		check(info.snapshot(423_825_000L).system.authenticationRequired == 1
				&& info.snapshot(423_825_000L).system.securityClass1Supported == 1,
				"security capabilities were cleared by alternating SYSINFO variants");
		check(info.snapshot(423_837_499L).role == TetraCellInfo.Role.MAIN
				&& info.snapshot(423_837_500L).role == TetraCellInfo.Role.OTHER,
				"main-cell status follows the exact 25 kHz carrier range");

		int[] nwrk = new int[128];
		put(nwrk, 16 + 2, 1, 1); // optional elements present
		put(nwrk, 19, 1, 0); // no time fields
		put(nwrk, 20, 1, 1); // neighbour list present
		put(nwrk, 21, 3, 1); // one neighbour
		int p = 24;
		put(nwrk, p, 5, 7); p += 5;
		put(nwrk, p, 2, 1); p += 2;
		put(nwrk, p, 1, 1); p += 1;
		put(nwrk, p, 2, 0); p += 2;
		put(nwrk, p, 12, 1000); p += 12;
		put(nwrk, p, 1, 0); // no optional neighbour extensions
		info.acceptNeighbourBroadcast(nwrk, 0, nwrk.length, 456L);
		current = info.snapshot(423_825_000L);
		check(current.neighbours.size() == 1 && current.neighbours.get(0).cellId == 7,
				"D-NWRK-BROADCAST neighbour extraction");
		check(current.neighbours.get(0).carrierHz == 425_000_000L,
				"neighbour frequency inherits current band/offset");
		check(current.network != null && current.network.neighbourCount == 1,
				"D-NWRK-BROADCAST serving-cell parameters retained");
		int[] extendedNwrk = nwrk.clone();
		put(extendedNwrk, 46, 1, 1); // neighbour optional elements
		put(extendedNwrk, 47, 1, 1); // carrier extension present
		put(extendedNwrk, 48, 10, (5 << 6) | (2 << 4) | (3 << 1) | 1);
		put(extendedNwrk, 58, 1, 1); // MCC present
		put(extendedNwrk, 59, 10, 231);
		info.acceptNeighbourBroadcast(extendedNwrk, 0, extendedNwrk.length, 500L);
		info.acceptNeighbourBroadcast(nwrk, 0, nwrk.length, 501L);
		TetraCellInfo.Neighbour retained = info.snapshot(423_825_000L).neighbours.get(0);
		check(retained.mcc == 231 && retained.frequencyBand == 5 && retained.offset == 2
				&& retained.duplexSpacing == 3 && retained.reverseOperation
				&& retained.carrierHz == 524_993_750L,
				"short neighbour element erased a previously decoded optional field");

		int[] secondNwrk = nwrk.clone();
		put(secondNwrk, 24, 5, 8);
		put(secondNwrk, 34, 12, 1001);
		info.acceptNeighbourBroadcast(secondNwrk, 0, secondNwrk.length, 789L);
		current = info.snapshot(423_825_000L);
		check(current.neighbours.size() == 2
				&& current.neighbours.get(0).cellId == 7
				&& current.neighbours.get(1).cellId == 8,
				"neighbour broadcasts merge by Cell ID instead of replacing the table");

		TetraCellInfo outOfOrder = new TetraCellInfo();
		outOfOrder.acceptNeighbourBroadcast(nwrk, 0, nwrk.length, 900L);
		check(outOfOrder.snapshot(423_825_000L).neighbours.get(0).carrierHz == 0L,
				"neighbour without SYSINFO stays unresolved");
		outOfOrder.acceptSysInfo(sysInfo, 901L);
		check(outOfOrder.snapshot(423_825_000L).neighbours.get(0).carrierHz == 425_000_000L,
				"retained neighbour resolves when SYSINFO arrives later");
	}

	private static void put(int[] bits, int start, int length, int value) {
		for (int n = 0; n < length; n++) bits[start + n] = value >>> (length - n - 1) & 1;
	}

	private static void testMacNullPaddingIsNotSysInfo() {
		int[] payload = pid209Message(
				"2061FFFFFF0448009C0007400010800000000000000000000000000000000000000",
				0L, 0).userDataBits;
		TetraMacDecoder decoder = new TetraMacDecoder();
		byte[] burstBits = new byte[TetraDecoderCore.BURST_BITS];
		TetraDecoderCore.Burst burst = new TetraDecoderCore.Burst(1_000L, 0L,
				TetraDecoderCore.BurstType.NDB1, 1d, 0d, burstBits,
				new float[burstBits.length]);
		decoder.accept(burst, new TetraSchfDecoder.DecodeAttempt(
				true, 0x1d0f, 0d, 0, payload));
		check(!decoder.cellInfo(423_825_000L).hasSysInfo,
				"MAC null-resource fill was mistaken for an all-zero SYSINFO");
	}

	private static void testCmceAlertEvent() {
		int[] payload = new int[268];
		put(payload, 0, 2, 0);       // MAC-RESOURCE
		put(payload, 7, 6, 8);       // eight-octet MAC PDU
		put(payload, 13, 3, 1);      // SSI address
		put(payload, 16, 24, 3_012_345);
		int data = 43;               // no optional resource-header elements
		put(payload, data, 4, 2);    // LLC BL-UDATA
		put(payload, data + 4, 3, 2);// MLE discriminator CMCE
		put(payload, data + 7, 5, 0);// D-ALERT
		TetraMacDecoder decoder = new TetraMacDecoder();
		byte[] burstBits = new byte[TetraDecoderCore.BURST_BITS];
		TetraDecoderCore.Burst burst = new TetraDecoderCore.Burst(2_000L, 0L,
				TetraDecoderCore.BurstType.NDB1, 1d, 0d, burstBits, null);
		decoder.accept(burst, new TetraSchfDecoder.DecodeAttempt(
				true, 0x1d0f, 0d, 0, payload));
		List<TetraMacDecoder.TetraMessage> events = decoder.takeEvents();
		check(events.size() == 1 && "D-ALERT".equals(events.get(0).type)
				&& events.get(0).destination == 3_012_345,
				"CMCE D-ALERT for a non-3015 SSI was not emitted");
	}

	private static void testCmceVoiceCallTable() {
		int[] payload = new int[268];
		put(payload, 0, 2, 0);        // MAC-RESOURCE
		put(payload, 7, 6, 12);       // twelve-octet MAC PDU
		put(payload, 13, 3, 1);       // SSI address
		put(payload, 16, 24, 3_014_321);
		int data = 43;
		put(payload, data, 4, 2);     // LLC BL-UDATA
		put(payload, data + 4, 3, 2); // CMCE
		put(payload, data + 7, 5, 7); // D-SETUP
		put(payload, data + 12, 14, 321);
		put(payload, data + 26, 4, 4); // call time-out
		put(payload, data + 31, 1, 0); // simplex
		put(payload, data + 32, 8, 0x10); // speech, E2E encryption flag
		put(payload, data + 40, 2, 0); // transmission granted
		put(payload, data + 47, 1, 0); // no optional elements
		TetraMacDecoder decoder = new TetraMacDecoder();
		byte[] burstBits = new byte[TetraDecoderCore.BURST_BITS];
		TetraDecoderCore.Burst burst = new TetraDecoderCore.Burst(4_000L, 0L,
				TetraDecoderCore.BurstType.NDB1, 1d, 0d, burstBits, null);
		decoder.accept(burst, new TetraSchfDecoder.DecodeAttempt(
				true, 0x1d0f, 0d, 0, payload));
		List<TetraMacDecoder.TetraMessage> events = decoder.takeEvents();
		check(events.size() == 1 && events.get(0) instanceof TetraMacDecoder.CallEvent,
				"CMCE D-SETUP was not decoded as a call event");
		TetraMacDecoder.CallEvent setup = (TetraMacDecoder.CallEvent)events.get(0);
		check(setup.callIdentifier == 321 && setup.basicService == 0x10
				&& setup.encryptionControl == 1 && setup.simplex == 0,
				"D-SETUP call fields");

		TetraVoiceCallTablePanel panel = new TetraVoiceCallTablePanel();
		panel.accept(events);
		check(panel.callCount() == 1 && "SETUP".equals(panel.valueForTest(0, 2))
				&& "E2E ENCRYPTED".equals(panel.valueForTest(0, 9)),
				"voice-call setup table row");
		TetraMacDecoder.CallEvent granted = new TetraMacDecoder.CallEvent(5_000L,
				"D-TX-GRANTED", 3_014_321, 9_750_001, new int[0], 321, -1, 1,
				0, -1, -1, 2, 0, 1);
		panel.accept(Arrays.<TetraMacDecoder.TetraMessage>asList(granted));
		check(panel.callCount() == 1 && "ACTIVE".equals(panel.valueForTest(0, 2))
				&& "TRANSMITTING".equals(panel.valueForTest(0, 3))
				&& Integer.valueOf(9_750_001).equals(panel.valueForTest(0, 6)),
				"voice-call grant updates existing row");
		TetraMacDecoder.CallEvent released = new TetraMacDecoder.CallEvent(6_000L,
				"D-RELEASE", 3_014_321, -1, new int[0], 321, -1, -1,
				-1, -1, -1, 2, 0, 1);
		panel.accept(Arrays.<TetraMacDecoder.TetraMessage>asList(released));
		check(panel.callCount() == 1 && "ENDED".equals(panel.valueForTest(0, 2)),
				"voice-call release updates existing row");
	}

	private static void testSchHdSysInfoBlock() {
		int[] payload = new int[124];
		put(payload, 0, 2, 2); // MAC-BROADCAST
		put(payload, 2, 2, 0); // SYSINFO
		put(payload, 4, 12, 953);
		put(payload, 16, 4, 4);
		int[] input = new int[144];
		System.arraycopy(payload, 0, input, 0, payload.length);
		appendCrcField(input, 124);
		int[] coded = encodeSchHd(input, new TetraBschDecoder.SystemInfo(
				2, 1, 1, 1, 231, 1000, 0));
		byte[] burstBits = new byte[TetraDecoderCore.BURST_BITS];
		for (int n = 0; n < coded.length; n++) burstBits[14 + n] = (byte)coded[n];
		TetraDecoderCore.Burst burst = new TetraDecoderCore.Burst(1_000L, 0L,
				TetraDecoderCore.BurstType.NDB2, 1d, 300d, burstBits, null);
		TetraSchfDecoder.DecodeAttempt decoded = TetraSchfDecoder.analyzeHalfBurst(burst,
				new TetraBschDecoder.SystemInfo(2, 1, 1, 1, 231, 1000, 0), 0);
		check(decoded != null && decoded.crcOk && decoded.payloadBits[4] == 0
				&& readTest(decoded.payloadBits, 4, 12) == 953,
				"NDB2 SCH/HD SYSINFO block was not decoded");
	}

	private static void appendCrcField(int[] input, int payloadLength) {
		for (int candidate = 0; candidate <= 0xffff; candidate++) {
			put(input, payloadLength, 16, candidate);
			if (crcTest(input, payloadLength + 16) == 0x1d0f) return;
		}
		throw new AssertionError("unable to construct SCH/HD CRC field");
	}

	private static int crcTest(int[] bits, int length) {
		int crc = 0xffff;
		for (int n = 0; n < length; n++) {
			int feedback = bits[n] ^ (crc >>> 15 & 1);
			crc = crc << 1 & 0xffff;
			if (feedback != 0) crc ^= 0x1021;
		}
		return crc;
	}

	private static int[] encodeSchHd(int[] input, TetraBschDecoder.SystemInfo info) {
		int[] mother = new int[input.length * 4];
		int state = 0;
		int[] generators = { 0x13, 0x1d, 0x17, 0x1b };
		for (int time = 0; time < input.length; time++) {
			int register = state << 1 | input[time];
			for (int n = 0; n < generators.length; n++)
				mother[time * 4 + n] = Integer.bitCount(register & generators[n]) & 1;
			state = register & 0x0f;
		}
		int[] puncture = { 1, 1, 0, 0, 1, 0, 0, 0 };
		int[] type3 = new int[216];
		int target = 0;
		for (int n = 0; n < mother.length; n++)
			if (puncture[n & 7] != 0) type3[target++] = mother[n];
		int[] type4 = new int[216];
		for (int k = 1; k <= type3.length; k++)
			type4[101 * k % type3.length] = type3[k - 1];
		long lfsr = TetraSchfDecoder.scramblerInitial(info);
		int[] taps = { 32, 26, 23, 22, 16, 12, 11, 10, 8, 7, 5, 4, 2, 1 };
		for (int n = 0; n < type4.length; n++) {
			int feedback = 0;
			for (int tap : taps) feedback ^= (int)(lfsr >>> (32 - tap) & 1L);
			type4[n] ^= feedback;
			lfsr = lfsr >>> 1 | (long)feedback << 31;
		}
		return type4;
	}

	private static int readTest(int[] bits, int start, int length) {
		int value = 0;
		for (int n = 0; n < length; n++) value = value << 1 | bits[start + n];
		return value;
	}

	private static void testPid209BoardSelectionSurvivesRefresh() {
		TetraPid209BoardTablePanel panel = new TetraPid209BoardTablePanel();
		String firstData = "D102450114001E07DA000303001E07DA0008030A";
		String changedData = "D102520114001E07DA000303001E07DA0007030A";
		panel.accept(Arrays.asList(
				pid209Message(firstData, 1_000L, 3015432),
				pid209Message(firstData, 2_000L, 3015433)));
		check(panel.selectDestination(3015432), "PID 209 test board not selectable");
		panel.accept(Arrays.asList(pid209Message(changedData, 3_000L, 3015432)));
		check(panel.selectedDestination() == 3015432,
				"PID 209 selected board lost during table refresh");
	}

	private static void testPid209ConfirmedMappings() {
		check("Americké námestie → centrum".equals(TetraPid209Mappings.board(3015433)),
				"PID 209 confirmed board mapping");
		check("3".equals(TetraPid209Mappings.line(0x001e)),
				"PID 209 confirmed line mapping");
		check("Petržalka".equals(TetraPid209Mappings.destination(0x07d4)),
				"PID 209 confirmed destination mapping");
	}

	private static void testPid209InterleavedRowsAreGrouped() {
		TetraPid209BoardTablePanel panel = new TetraPid209BoardTablePanel();
		String first = "D102450114001E07D4000103001E07D5000203"
				+ "001E07D4000403001E07D50008030A";
		String second = "D102460114001E07D4000003001E07D5000103"
				+ "001E07D4000303001E07D50007030A";
		panel.accept(Arrays.asList(pid209Message(first, 1_000L, 3015433)));
		check("1,4 - 2,8".equals(panel.departuresForBoard(3015433)),
				"PID 209 interleaved departure groups");
		check("3 - 6".equals(panel.intervalForBoard(3015433)),
				"PID 209 grouped deltas");
		panel.accept(Arrays.asList(pid209Message(second, 2_000L, 3015433)));
		check("1,4 - 2,8".equals(panel.previousDeparturesForBoard(3015433)),
				"PID 209 grouped previous departures");
	}

	private static void testPid209TableLayoutPersistence() {
		File settings;
		try {
			settings = File.createTempFile("tetra-pid209-layout-", ".ini");
			if (!settings.delete()) throw new IOException("cannot prepare temporary settings file");
		} catch (IOException error) {
			throw new AssertionError("PID 209 temporary layout file", error);
		}
		try {
			TetraPid209BoardTablePanel panel = new TetraPid209BoardTablePanel(settings);
			panel.moveColumnForTest("Time values", 0);
			panel.setColumnWidthForTest("Time values", 177);
			panel.sortByColumnsForTest("Time values", SortOrder.DESCENDING,
					"DST SSI", SortOrder.ASCENDING);
			panel.saveTableLayoutForTest();

			TetraPid209BoardTablePanel restored = new TetraPid209BoardTablePanel(settings);
			restored.restoreTableLayoutForTest();
			check(restored.viewColumnForTest("Time values") == 0
					&& restored.columnWidthForTest("Time values") == 177,
					"PID 209 column order or width not restored");
			List<? extends RowSorter.SortKey> keys = restored.sortKeysForTest();
			check(keys.equals(Arrays.asList(
					new RowSorter.SortKey(10, SortOrder.DESCENDING),
					new RowSorter.SortKey(0, SortOrder.ASCENDING))),
					"PID 209 multi-column sort not restored");
			check(restored.headerTextForTest("Time values").contains("\u25BC1")
					&& restored.headerTextForTest("DST SSI").contains("\u25B22"),
					"PID 209 colored sort priorities not rendered");
		} finally {
			if (settings.exists() && !settings.delete()) settings.deleteOnExit();
		}
	}

	private static void testPid209ScheduledTimeEncoding() {
		check(TetraPid209Decoder.scheduledMinuteOfDay(0x111f) == 4 * 60 + 47,
				"PID 209 0x111F scheduled time");
		check(TetraPid209Decoder.scheduledMinuteOfDay(0x1130) == 5 * 60 + 4,
				"PID 209 0x1130 scheduled time");
		check(TetraPid209Decoder.scheduledMinuteOfDay(8) < 0,
				"PID 209 relative minute mistaken for scheduled time");
		TetraPid209BoardTablePanel panel = new TetraPid209BoardTablePanel();
		panel.accept(Arrays.asList(pid209Message(
				"D102450114001E07D4111F00001E07D41130000A", 1_000L, 3015433)));
		check("~04:47,~05:04".equals(panel.departuresForBoard(3015433)),
				"PID 209 scheduled times not formatted");
		check("17".equals(panel.intervalForBoard(3015433)),
				"PID 209 scheduled-time delta");
	}

	private static void testTetraAnalyzerSdsEventsAndLog() {
		TetraAnalyzerPanel panel = new TetraAnalyzerPanel();
		TetraMacDecoder.SdsMessage message = pid209Message(
				"D102450114001E07DA000303001E07DA0008030A", 1_000L, 3015432);
		byte[] burstBits = new byte[TetraDecoderCore.BURST_BITS];
		TetraDecoderCore.Burst burst = new TetraDecoderCore.Burst(900L, 0L,
				TetraDecoderCore.BurstType.NDB1, .9d, 25d, burstBits, new float[burstBits.length]);
		panel.refresh(TetraDecoderCore.Result.empty("test"), Arrays.asList(burst),
				Arrays.asList(message));
		check(panel.eventCountForTest() == 1, "TETRA main table includes non-SDS burst events");
		check(panel.sortableForTest(), "TETRA message table has no column sorter");
		check(Integer.valueOf(209).equals(panel.eventValueForTest(0, 1))
				&& Integer.valueOf(3015432).equals(panel.eventValueForTest(0, 2))
				&& message.dataHex().equals(panel.eventValueForTest(0, 6)),
				"TETRA SDS event columns");
		byte[] textBytes = "\u00c0Service suspended at Hlavna stanica".getBytes(StandardCharsets.UTF_8);
		int[] textBits = new int[textBytes.length * 8];
		for (int n = 0; n < textBytes.length; n++)
			for (int bit = 0; bit < 8; bit++)
				textBits[n * 8 + bit] = textBytes[n] >>> (7 - bit) & 1;
		TetraMacDecoder.SdsMessage textMessage = new TetraMacDecoder.SdsMessage(
				1_100L, 3015432, 9750004, 4, textBits.length, 192, textBits);
		panel.refresh(TetraDecoderCore.Result.empty("test"), null, Arrays.asList(textMessage));
		check(panel.eventValueForTest(1, 8).toString().contains("Service suspended"),
				"TETRA SDS text candidate");
		TetraMacDecoder.SdsMessage positionMessage = sdsMessage(
				"C20281801315232F341FA0BB574566449222000C335F206B1056144B2DF5BF4B",
				1_200L, 7010138, 9750001, 194);
		panel.refresh(TetraDecoderCore.Result.empty("test"), null,
				Arrays.asList(positionMessage));
		check("48.213866, 17.157193".equals(panel.eventValueForTest(2, 7)),
				"PID 194 AVL position decoding");

		File log = null;
		try {
			log = File.createTempFile("tetra-sds-log-", ".txt");
			panel.startLoggingForTest(log);
			panel.refresh(TetraDecoderCore.Result.empty("test"), null, Arrays.asList(message));
			panel.stopLoggingForTest();
			String text = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8);
			check(text.contains("Timestamp\tFrequency Hz\tPID\tDL SSI")
					&& text.contains("\t209\t3015432\t9750004\t4\t")
					&& text.contains(message.dataHex()), "TETRA SDS TSV logging");
		} catch (IOException error) {
			throw new AssertionError("TETRA SDS log test", error);
		} finally {
			panel.close();
			if (log != null && log.exists() && !log.delete()) log.deleteOnExit();
		}
	}

	private static TetraMacDecoder.SdsMessage pid209Message(String hex, long timestamp,
			int destination) {
		return sdsMessage(hex, timestamp, destination, 9750004, 209);
	}

	private static TetraMacDecoder.SdsMessage sdsMessage(String hex, long timestamp,
			int destination, int source, int protocol) {
		int[] bits = new int[hex.length() * 4];
		for (int n = 0; n < hex.length(); n++) {
			int value = Character.digit(hex.charAt(n), 16);
			for (int bit = 0; bit < 4; bit++) bits[n * 4 + bit] = value >>> (3 - bit) & 1;
		}
		return new TetraMacDecoder.SdsMessage(
				timestamp, destination, source, 4, bits.length, protocol, bits);
	}

	private static void testBurst(TetraDecoderCore.BurstType type, double cfoHz) {
		byte[] iq = synthesize(type, cfoHz, 19L);
		TetraDecoderCore decoder = new TetraDecoderCore();
		decoder.accept(390_000_000L, RATE, iq, iq.length, 1_000L);
		List<TetraDecoderCore.Burst> bursts = decoder.takeBursts();
		TetraDecoderCore.Burst found = find(bursts, type);
		check(found != null, "missing " + type + " burst");
		check(found.correlation > .95d, type + " correlation " + found.correlation);
		check(Math.abs(found.frequencyErrorHz - cfoHz) < 45d,
				type + " CFO " + found.frequencyErrorHz + " expected " + cfoHz);
		check(found.bits.length == TetraDecoderCore.BURST_BITS, "wrong type-5 length");
	}

	private static void testChunkBoundary() {
		byte[] iq = synthesize(TetraDecoderCore.BurstType.SYNC, 180d, 23L);
		TetraDecoderCore decoder = new TetraDecoderCore();
		int split = 733 * 2;
		byte[] first = new byte[split], second = new byte[iq.length - split];
		System.arraycopy(iq, 0, first, 0, first.length);
		System.arraycopy(iq, split, second, 0, second.length);
		decoder.accept(390_000_000L, RATE, first, first.length, 2_000L);
		decoder.accept(390_000_000L, RATE, second, second.length,
				2_000L + Math.round(first.length / 2d / RATE * 1000d));
		List<TetraDecoderCore.Burst> bursts = decoder.takeBursts();
		check(find(bursts, TetraDecoderCore.BurstType.SYNC) != null,
				"SYNC crossing IQ blocks was not acquired");
	}

	private static TetraDecoderCore.Burst find(List<TetraDecoderCore.Burst> bursts,
			TetraDecoderCore.BurstType type) {
		for (TetraDecoderCore.Burst burst : bursts) if (burst.type == type) return burst;
		return null;
	}

	private static byte[] synthesize(TetraDecoderCore.BurstType type, double cfoHz, long seed) {
		int guardSymbols = 12, tailSymbols = 20, burstCount = 3;
		int payloadSymbols = burstCount * TetraDecoderCore.BURST_SYMBOLS;
		int totalSymbols = guardSymbols + payloadSymbols + tailSymbols;
		int[] first = new int[payloadSymbols];
		int[] second = new int[payloadSymbols];
		Random random = new Random(seed);
		for (int n = 0; n < first.length; n++) {
			first[n] = random.nextBoolean() ? 1 : 0;
			second[n] = random.nextBoolean() ? 1 : 0;
		}
		int[] training = TetraDecoderCore.TetraBurstDetector.trainingBits(type);
		int offset = TetraDecoderCore.TetraBurstDetector.trainingOffset(type);
		for (int burst = 0; burst < burstCount; burst++) {
			int burstOffset = burst * TetraDecoderCore.BURST_SYMBOLS + offset;
			for (int n = 0; n < training.length / 2; n++) {
				first[burstOffset + n] = training[n * 2];
				second[burstOffset + n] = training[n * 2 + 1];
			}
		}

		double[] symbolPhase = new double[totalSymbols];
		double phase = .31d;
		for (int symbol = 1; symbol < totalSymbols; symbol++) {
			int burstSymbol = symbol - guardSymbols;
			double delta;
			if (burstSymbol >= 0 && burstSymbol < first.length) {
				double magnitude = second[burstSymbol] == 1 ? 3d * Math.PI / 4d : Math.PI / 4d;
				delta = first[burstSymbol] == 1 ? -magnitude : magnitude;
			} else delta = Math.PI / 4d;
			phase += delta;
			symbolPhase[symbol] = phase;
		}
		int samples = totalSymbols * SAMPLES_PER_SYMBOL;
		byte[] iq = new byte[samples * 2];
		for (int sample = 0; sample < samples; sample++) {
			int symbol = sample / SAMPLES_PER_SYMBOL;
			double carrier = 2d * Math.PI * cfoHz * sample / RATE;
			double angle = symbolPhase[symbol] + carrier;
			iq[sample * 2] = (byte)Math.round(92d * Math.cos(angle));
			iq[sample * 2 + 1] = (byte)Math.round(92d * Math.sin(angle));
		}
		return iq;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
