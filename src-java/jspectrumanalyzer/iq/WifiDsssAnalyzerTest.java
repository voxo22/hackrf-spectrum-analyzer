package jspectrumanalyzer.iq;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

public final class WifiDsssAnalyzerTest {
	private static final int[] BARKER = {1,-1,1,1,-1,1,1,1,-1,-1,-1};

	private WifiDsssAnalyzerTest() { }

	public static void main(String[] args) {
		byte[] packet = createBeacon((byte)0x55);
		WifiDsssAnalyzer analyzer = new WifiDsssAnalyzer();
		WifiDsssAnalyzer.Result result = analyzer.analyze(
				2_448_000_000L, packet, packet.length, 16_000_000);
		assertTrue(result.fcsValid, result.state);
		assertTrue("Beacon".equals(result.frameType), result.frameType);
		assertTrue("Voxo".equals(result.ssid), result.ssid);
		assertTrue(result.channel == 8, "channel " + result.channel);
		assertTrue("802.11b".equals(result.mac.standards), result.mac.standards);
		assertTrue("11 Mb/s".equals(result.mac.maxAdvertisedPhy), result.mac.maxAdvertisedPhy);
		assertTrue(Double.isFinite(result.levelDbfs), "missing packet level");
		assertTrue(analyzer.takeObservations().size() == 1, "beacon observation missing");
		testFrameClassification();
		testAdvertisedPhyRates();
		testCountryElement();
		testProbeRequestClients();
		testBssidGrouping(result);
		System.out.println("Wi-Fi DSSS analyzer tests passed");
	}

	private static void testCountryElement() {
		byte[] country = {7,6,'A','T',' ',1,13,20};
		WifiMacFrame frame = WifiMacFrame.parse(createCapabilityBeacon(country), 2_437_000_000L);
		assertTrue("AT".equals(frame.country), "country " + frame.country);
		byte[] shortCountry = {7,2,'A','T'};
		WifiMacFrame shortFrame = WifiMacFrame.parse(createCapabilityBeacon(shortCountry), 2_437_000_000L);
		assertTrue("AT".equals(shortFrame.country), "short country " + shortFrame.country);
	}

	private static void testProbeRequestClients() {
		WifiNetworkTablePanel panel = new WifiNetworkTablePanel();
		panel.observe(resultFor(createProbeRequest("Home")));
		panel.observe(resultFor(createProbeRequest("Office")));
		panel.observe(resultFor(createProbeRequest("")));
		assertTrue(panel.clientCount() == 1, "probe requests from one MAC must share a client row");
		panel.refresh();
		WifiNetworkTablePanel.NetworkDetails details = panel.selectedNetwork();
		assertTrue(details != null && "Client".equals(details.network.role), "client row missing");
		assertTrue(details.network.requestedSsids.contains("Home")
				&& details.network.requestedSsids.contains("Office")
				&& details.network.requestedSsids.contains("<any network>"), "requested SSIDs not aggregated");
		assertTrue(details.network.macVendor.contains("Cisco"), "OUI lookup " + details.network.macVendor);
		assertTrue(details.network.probeRequests == 3, "probe request count " + details.network.probeRequests);
		assertTrue("Randomized / local".equals(WifiOuiLookup.company("02:11:22:33:44:55")),
				"local MAC must not receive a fabricated company");
	}

	private static WifiDsssAnalyzer.Result resultFor(byte[] psdu) {
		WifiMacFrame mac = WifiMacFrame.parse(psdu, 2_437_000_000L);
		return new WifiDsssAnalyzer.Result(true, true, "test", 100d, 1d, 0d, -20d,
				"1 Mb/s", psdu.length, mac, 1, 1, System.currentTimeMillis());
	}

	private static byte[] createProbeRequest(String requestedSsid) {
		byte[] name = requestedSsid.getBytes(StandardCharsets.UTF_8);
		byte[] frame = new byte[24 + 2 + name.length + 6 + 4];
		frame[0] = 0x40;
		Arrays.fill(frame, 4, 10, (byte)0xff);
		byte[] source = {0x00,0x00,0x0c,0x12,0x34,0x56};
		System.arraycopy(source, 0, frame, 10, 6);
		Arrays.fill(frame, 16, 22, (byte)0xff);
		int at = 24; frame[at++] = 0; frame[at++] = (byte)name.length;
		System.arraycopy(name, 0, frame, at, name.length); at += name.length;
		frame[at++] = 1; frame[at++] = 4; frame[at++] = (byte)0x82; frame[at++] = (byte)0x84;
		frame[at++] = (byte)0x8b; frame[at] = (byte)0x96;
		int bodyLength = frame.length - 4; long fcs = crc32(frame, 0, bodyLength);
		for (int n = 0; n < 4; n++) frame[bodyLength + n] = (byte)(fcs >>> (n * 8));
		return frame;
	}

	private static void testAdvertisedPhyRates() {
		byte[] ht = new byte[28];
		ht[0] = 45; ht[1] = 26; ht[2] = 0x62;
		ht[5] = (byte)0xff; ht[6] = (byte)0xff;
		WifiMacFrame htFrame = WifiMacFrame.parse(createCapabilityBeacon(ht), 2_437_000_000L);
		assertTrue("300 Mb/s".equals(htFrame.maxAdvertisedPhy), htFrame.maxAdvertisedPhy);

		byte[] vht = new byte[14];
		vht[0] = (byte)191; vht[1] = 12; vht[2] = 0x20;
		vht[6] = (byte)0xfa; vht[7] = (byte)0xff;
		WifiMacFrame vhtFrame = WifiMacFrame.parse(createCapabilityBeacon(vht), 5_180_000_000L);
		assertTrue("866.7 Mb/s".equals(vhtFrame.maxAdvertisedPhy), vhtFrame.maxAdvertisedPhy);
	}

	private static byte[] createCapabilityBeacon(byte[] informationElements) {
		byte[] frame = new byte[36 + informationElements.length + 4];
		frame[0] = (byte)0x80;
		Arrays.fill(frame, 4, 10, (byte)0xff);
		byte[] bssid = {0x02,0x11,0x22,0x33,0x44,0x55};
		System.arraycopy(bssid, 0, frame, 10, 6);
		System.arraycopy(bssid, 0, frame, 16, 6);
		frame[32] = 100;
		System.arraycopy(informationElements, 0, frame, 36, informationElements.length);
		int bodyLength = frame.length - 4;
		long fcs = crc32(frame, 0, bodyLength);
		for (int n = 0; n < 4; n++) frame[bodyLength + n] = (byte)(fcs >>> (n * 8));
		return frame;
	}

	private static void testFrameClassification() {
		assertFrameType(0x40, 24, "Probe request", "Management");
		assertFrameType(0x50, 36, "Probe response", "Management");
		assertFrameType(0xb4, 16, "RTS", "Control");
		assertFrameType(0xd4, 10, "ACK", "Control");
		assertFrameType(0x08, 24, "Data", "Data");
		assertFrameType(0x88, 26, "QoS data", "Data");
	}

	private static void assertFrameType(int frameControl, int bodyLength,
			String expectedType, String expectedClass) {
		byte[] frame = new byte[bodyLength + 4];
		frame[0] = (byte)frameControl;
		long fcs = crc32(frame, 0, bodyLength);
		for (int n = 0; n < 4; n++) frame[bodyLength + n] = (byte)(fcs >>> (n * 8));
		WifiMacFrame parsed = WifiMacFrame.parse(frame, 2_437_000_000L);
		assertTrue(parsed.fcsValid, expectedType + " FCS");
		assertTrue(expectedType.equals(parsed.frameType), parsed.frameType);
		assertTrue(expectedClass.equals(parsed.frameClass), parsed.frameClass);
	}

	private static void testBssidGrouping(WifiDsssAnalyzer.Result first) {
		WifiNetworkTablePanel panel = new WifiNetworkTablePanel();
		panel.observe(first);
		byte[] packet = createBeacon((byte)0x66);
		WifiDsssAnalyzer.Result second = new WifiDsssAnalyzer().analyze(
				2_448_000_000L, packet, packet.length, 16_000_000);
		panel.observe(second);
		long replayTime = 1_600_000_000_000L;
		panel.observe(first, replayTime);
		assertTrue(panel.networkCount() == 2,
				"equal SSIDs with different BSSIDs must use separate rows");
		panel.refresh();
		WifiNetworkTablePanel.NetworkDetails selected = panel.selectedNetwork();
		assertTrue(selected != null, "first SSID row must be selected automatically");
		assertTrue(selected.network.lastCaptureMillis == replayTime,
				"replay capture time was not retained");
	}

	private static byte[] createBeacon(byte bssidSuffix) {
		byte[] body = new byte[24 + 12 + 6 + 6 + 3];
		body[0] = (byte)0x80;
		Arrays.fill(body, 4, 10, (byte)0xff);
		byte[] bssid = {0x02,0x11,0x22,0x33,0x44,bssidSuffix};
		System.arraycopy(bssid, 0, body, 10, 6);
		System.arraycopy(bssid, 0, body, 16, 6);
		body[32] = 100;
		body[34] = 0x01;
		int at = 36;
		body[at++] = 0; body[at++] = 4;
		byte[] ssid = "Voxo".getBytes(StandardCharsets.UTF_8);
		System.arraycopy(ssid, 0, body, at, ssid.length); at += ssid.length;
		body[at++] = 1; body[at++] = 4;
		body[at++] = (byte)0x82; body[at++] = (byte)0x84;
		body[at++] = (byte)0x8b; body[at++] = (byte)0x96;
		body[at++] = 3; body[at++] = 1; body[at] = 8;
		byte[] psdu = Arrays.copyOf(body, body.length + 4);
		long fcs = crc32(psdu, 0, body.length);
		for (int n = 0; n < 4; n++) psdu[body.length + n] = (byte)(fcs >>> (n * 8));

		int payloadBits = psdu.length * 8, payloadAt = 191;
		int[] plain = new int[payloadAt + payloadBits];
		Arrays.fill(plain, 0, 127, 1);
		putPattern(plain, 127, "0000010111001111");
		putByte(plain, 143, 0x0a);
		putByte(plain, 151, 0);
		putByte(plain, 159, payloadBits & 0xff);
		putByte(plain, 167, payloadBits >>> 8);
		for (int n = 0; n < psdu.length; n++) putByte(plain, payloadAt + n * 8, psdu[n] & 0xff);
		int[] scrambled = new int[plain.length];
		Arrays.fill(scrambled, 0, 7, 1);
		for (int n = 7; n < scrambled.length; n++)
			scrambled[n] = plain[n] ^ scrambled[n - 4] ^ scrambled[n - 7];
		int[] symbols = new int[scrambled.length + 1];
		symbols[0] = 1;
		for (int n = 0; n < scrambled.length; n++)
			symbols[n + 1] = scrambled[n] == 0 ? symbols[n] : -symbols[n];

		int prefix = 16_000;
		int signalSamples = (int)Math.ceil(symbols.length * 11d * 16d / 11d);
		byte[] iq = new byte[(prefix + signalSamples + 16_000) * 2];
		Random random = new Random(80211);
		for (int n = 0; n < iq.length / 2; n++) {
			double base = random.nextGaussian();
			int relative = n - prefix;
			if (relative >= 0 && relative < signalSamples) {
				int chip = (int)Math.floor(relative * 11d / 16d);
				base += symbols[Math.min(symbols.length - 1, chip / 11)] * BARKER[chip % 11] * 50d;
			}
			double angle = -2d * Math.PI * 1_000_000d * n / 16_000_000d;
			iq[n * 2] = (byte)Math.round(base * Math.cos(angle));
			iq[n * 2 + 1] = (byte)Math.round(base * Math.sin(angle));
		}
		return iq;
	}

	private static void putPattern(int[] bits, int at, String pattern) {
		for (int n = 0; n < pattern.length(); n++) bits[at + n] = pattern.charAt(n) - '0';
	}

	private static void putByte(int[] bits, int at, int value) {
		for (int n = 0; n < 8; n++) bits[at + n] = value >>> n & 1;
	}

	private static long crc32(byte[] data, int offset, int length) {
		long crc = 0xffffffffL;
		for (int n = 0; n < length; n++) {
			crc ^= data[offset + n] & 0xff;
			for (int bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) != 0 ? 0xedb88320L : 0);
		}
		return (crc ^ 0xffffffffL) & 0xffffffffL;
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
