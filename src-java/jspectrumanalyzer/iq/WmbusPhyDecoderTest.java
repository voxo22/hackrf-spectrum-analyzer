package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Deterministic S/C/T PHY, format A/B and EN 13757 CRC regression tests. */
public final class WmbusPhyDecoderTest {
	private static final int[] THREE_OF_SIX = {
		0x16,0x0d,0x0e,0x0b,0x1c,0x19,0x1a,0x13,
		0x2c,0x25,0x26,0x23,0x34,0x31,0x32,0x29
	};

	public static void main(String[] args)throws Exception{
		testCapturedFormatATelegram();
		testFormatABlocksAndCrcRejection();
		testFormatBBlocksAndCrcRejection();
		testTransportMetadata();
		testDeviceTypeNames();
		testRtl433DecimalMeterId();
		testFhkvDataIIIApplication();
		testQcaloricAndOmsApplications();
		testIstaKamstrupAndApatorApplications();
		testForwardedLseApplication();
		testCFormatA();
		testCAt250kSampleRate();
		testCAtArbitraryWidebandOffset();
		testStreamingWidebandChannelizer();
		testDynamicArbitraryChannelizer();
		testPolyphaseChannelizer();
		testPolyphaseCompletedBurstQueue();
		testCFormatB();
		testTFormatA();
		testTAt250kSampleRate();
		testAllFramesInCapture();
		testSFormatA();
		testSAt250kSampleRate();
		testSAtArbitraryWidebandOffset();
		testDriftingDirectLseHca();
		testAnalyzerPromotion();
		testMeterTableGrouping();
		testTableRowTints();
		testTableLayoutPersistence();
		testStructuredApplicationColumns();
		testAccessCounterRate();
		testGenericSignalRateAndFlash();
		testSelectedMeterNotification();
		System.out.println("WmbusPhyDecoderTest OK: S/C/T PHY, CRC, streaming/PFB channelizers and Tx-period tracking");
	}

	private static void testCapturedFormatATelegram(){
		/* Public Qundis capture with data-link CRC bytes retained:
		 * https://github.com/wmbusmeters/wmbusmeters/issues/1237 */
		byte[] raw=fromHex("49449344713180141A0763FB780DFF5F350082C30035AE4F225B383E1A19394980"
				+"BB13B4CBE18D33E03772CD137E1AD00C95A74D9445050DD7D0F2263E25E46FF7"
				+"B89F584E890CBEA985ED94046D320B0634798D");
		WmbusPhyDecoder.Decoded decoded=WmbusPhyDecoder.parseFormatA(raw);
		valid(decoded,"captured format A telegram");
		if(decoded.payloadHex.length()!=148)throw new AssertionError("captured CRC stripping length");
		raw[30]^=1;invalid(WmbusPhyDecoder.parseFormatA(raw),"captured telegram with damaged block");
	}

	private static void testDeviceTypeNames(){
		byte[] radio=telegram(new byte[]{0x78});radio[9]=0x37;
		WmbusPhyDecoder.Decoded decoded=WmbusPhyDecoder.parseFormatA(formatA(radio));
		valid(decoded,"radio converter device type");
		equal("radio converter (meter side)",decoded.deviceTypeName,"extended device type");
		byte[] reserved=telegram(new byte[]{0x78});reserved[9]=(byte)0x80;
		decoded=WmbusPhyDecoder.parseFormatA(formatA(reserved));
		valid(decoded,"reserved device type");
		equal("reserved device type (0x80)",decoded.deviceTypeName,"reserved device type");
	}

	private static void testFormatABlocksAndCrcRejection(){
		byte[] payload=new byte[25];for(int n=0;n<payload.length;n++)payload[n]=(byte)(0x70+n);
		byte[] clean=telegram(payload);byte[] raw=formatA(clean);
		WmbusPhyDecoder.Decoded decoded=WmbusPhyDecoder.parseFormatA(raw);
		valid(decoded,"format A multi-block");equal(hex(withLength(clean,"A")),decoded.payloadHex,"format A CRC stripping");
		raw[14]^=1;invalid(WmbusPhyDecoder.parseFormatA(raw),"format A damaged data CRC");
	}

	private static void testFormatBBlocksAndCrcRejection(){
		byte[] shortClean=telegram(new byte[]{0x7a,1,2,3,4});
		byte[] shortRaw=formatB(shortClean);WmbusPhyDecoder.Decoded shortFrame=WmbusPhyDecoder.parseFormatB(shortRaw);
		valid(shortFrame,"format B short");equal(hex(withLength(shortClean,"B")),shortFrame.payloadHex,"format B short CRC stripping");
		shortRaw[4]^=1;invalid(WmbusPhyDecoder.parseFormatB(shortRaw),"format B damaged CRC");

		byte[] longClean=new byte[140];System.arraycopy(baseHeader(),0,longClean,0,10);
		for(int n=10;n<longClean.length;n++)longClean[n]=(byte)(n*13+7);
		byte[] longRaw=formatB(longClean);WmbusPhyDecoder.Decoded longFrame=WmbusPhyDecoder.parseFormatB(longRaw);
		valid(longFrame,"format B long");equal(hex(withLength(longClean,"B")),longFrame.payloadHex,"format B two-block CRC stripping");
		longRaw[135]^=1;invalid(WmbusPhyDecoder.parseFormatB(longRaw),"format B damaged second CRC");
	}

	private static void testTransportMetadata(){
		WmbusPhyDecoder.Decoded encrypted=WmbusPhyDecoder.parseFormatA(formatA(telegram(
				new byte[]{0x7a,0x2a,0x00,0x50,0x05,0x11})));
		valid(encrypted,"encrypted short TPL header");
		equal("encrypted (mode 5)",encrypted.security,"TPL security mode");
		if(encrypted.accessNumber!=0x2a)throw new AssertionError("short TPL access number");
		WmbusPhyDecoder.Decoded clear=WmbusPhyDecoder.parseFormatA(formatA(telegram(
				new byte[]{0x7a,0x2b,0x00,0x00,0x00,0x22})));
		equal("clear",clear.security,"clear TPL security");
		if(clear.accessNumber!=0x2b)throw new AssertionError("clear TPL access number");
	}

	private static void testRtl433DecimalMeterId(){
		byte[] clean=telegram(new byte[]{0x7a,1,2,3});
		clean[4]=0x3f;clean[5]=0x65;clean[6]=0x70;clean[7]=0;
		WmbusPhyDecoder.Decoded decoded=WmbusPhyDecoder.parseFormatA(formatA(clean));
		valid(decoded,"rtl_433 decimal ID");equal("706545",decoded.id,"rtl_433 decimal ID");
	}

	private static void testFhkvDataIIIApplication(){
		byte[] published=fromHex("34446850226677116980A0119F27020480048300C408F709143C003D341A2B0B2A0707000000000000062D114457563D71A1850000");
		WmbusApplicationDecoder.Result result=WmbusApplicationDecoder.decode(
				published,"TCH",0x69,0x80,0xa0);
		equal("fhkvdataiii",result.driver,"Techem driver");
		equal("131 HCA",result.currentReading,"current HCA");
		equal("2020-02-08",result.currentDate,"current date");
		equal("1026 HCA",result.previousReading,"previous HCA");
		equal("2019-12-31",result.previousDate,"previous date");
		equal("22.44 C",result.roomTemperature,"room temperature");
		equal("25.51 C",result.radiatorTemperature,"radiator temperature");
		equal("",result.unparsedValues,"parsed driver leftovers");

		byte[] capture=fromHex("32446850343301546980A0119F2B0009F0158D025A083308040000000200000000000000000003150D5C5E9AB05EBEC1BDCA8F");
		result=WmbusApplicationDecoder.decode(capture,"TCH",0x69,0x80,0xa0);
		equal("653 HCA",result.currentReading,"captured current HCA");
		equal("2022-10-31",result.currentDate,"captured current date");
		equal("2304 HCA",result.previousReading,"captured previous HCA");
		equal("21.38 C",result.roomTemperature,"captured room temperature");
		equal("20.99 C",result.radiatorTemperature,"captured radiator temperature");
	}

	private static void testQcaloricAndOmsApplications(){
		byte[] walkBy=fromHex("49449344876624263508780DFF5F350082B60000100007B06EFFFF35020000FF2C550500001D323502000017000100000000000000000001000000390097008AFE36002F046D18080233");
		WmbusApplicationDecoder.Result result=WmbusApplicationDecoder.decode(walkBy,"QDS",0x35,0x08,0x78);
		equal("qcaloric",result.driver,"QDS WalkBy driver");
		equal("235 HCA",result.currentReading,"QDS current HCA");
		equal("555 HCA\n235 HCA",result.previousReading,"QDS previous HCA history");
		equal("2023-12-31\n2024-02-29",result.previousDate,"QDS previous date history");
		equal("2024-03-02 08:24",result.currentDate,"QDS meter datetime");

		byte[] standard=fromHex("314493446813132735087A6A0000200B6E8700004B6E430200426CFF2CCB086E870000C2086C1D32326CFFFF046D19080233");
		result=WmbusApplicationDecoder.decode(standard,"QDS",0x35,0x08,0x7a);
		equal("87 HCA",result.currentReading,"OMS current HCA");
		equal("243 HCA\n87 HCA",result.previousReading,"OMS previous HCA history");
		equal("2023-12-31\n2024-02-29",result.previousDate,"OMS previous date history");

		byte[] water=fromHex("2D4465329933961318067ADA0000000C13567100004C1300000000426CFFFF02BB560000326CFFFF046D2307A12C");
		result=WmbusApplicationDecoder.decode(water,"LSE",0x18,0x06,0x7a);
		equal("lse_07_17",result.driver,"LSE water driver");
		equal("7.156 m3",result.currentReading,"LSE water total");
		equal("0 m3",result.previousReading,"LSE water due value");
		equal("2021-12-01 07:35",result.currentDate,"LSE water datetime");
	}

	private static void testIstaKamstrupAndApatorApplications(){
		byte[] apator=fromHex("19440186475036350408A0A100003200000000002D310815B018");
		WmbusApplicationDecoder.Result result=WmbusApplicationDecoder.decode(
				apator,"APA",0x04,0x08,0xa0);
		equal("apatoreitn",result.driver,"Apator E-ITN driver");
		equal("0 HCA",result.currentReading,"Apator current HCA");
		equal("50 HCA",result.previousReading,"Apator previous HCA");
		equal("2024-09-13",result.currentDate,"Apator current date");
		equal("2016-05-01",result.previousDate,"Apator season start date");
		equal("24.69 C",result.roomTemperature,"Apator average room temperature");
		if(result.unparsedValues.indexOf("temp_room_previous=21.03 C")<0)
			throw new AssertionError("Apator previous average room temperature missing");

		byte[] clearKam=telegram(fromHex("8D20390102030400007803062C0000"));
		clearKam[8]=0x35;clearKam[9]=0x04;
		result=WmbusApplicationDecoder.decode(clearKam,"KAM",0x35,0x04,0x8d);
		equal("kamheat",result.driver,"Kamstrup heat driver");
		equal("44 kWh",result.currentReading,"clear ELL Kamstrup energy");

		byte[] encryptedKam=telegram(fromHex("8D20F46033C8228E37C3743DB574B0E0"));
		encryptedKam[8]=0x35;encryptedKam[9]=0x04;
		WmbusPhyDecoder.Decoded decoded=WmbusPhyDecoder.parseFormatA(formatA(encryptedKam));
		equal("encrypted (ELL AES-CTR)",decoded.security,"Kamstrup ELL security");
		equal("kamheat",decoded.application.driver,"encrypted Kamstrup driver identification");

		byte[] ista=fromHex("09447426819216323108");
		decoded=WmbusPhyDecoder.parseFormatA(formatA(ista));
		equal("ista-hca",decoded.application.driver,"ISTA header-only HCA identification");
		equal("",decoded.application.currentReading,"ISTA header-only has no invented value");
	}

	private static void testForwardedLseApplication(){
		byte[] outer=fromHex("43C465B23F657000F0BFA0830600001E002031446532666158802C087A7CF80000046D2601ED270B6E095000426CDF2C4B6E654500326C0B2282046CFE268B046E085000");
		WmbusPhyDecoder.Decoded decoded=WmbusPhyDecoder.parseFormatA(formatA(outer));
		valid(decoded,"forwarded LSE telegram");equal("LSE",decoded.manufacturer,"forwarded manufacturer");
		equal("A (forwarded)",decoded.format,"forwarded frame marker");
		equal("80586166",decoded.id,"forwarded meter ID");equal("qcaloric",decoded.application.driver,"forwarded driver");
		equal("5009 HCA",decoded.application.currentReading,"forwarded current HCA");
		equal("4565 HCA\n5008 HCA",decoded.application.previousReading,"forwarded previous HCA history");
		equal("2022-12-31\n2023-06-30",decoded.application.previousDate,"forwarded previous date history");
		equal("error_date=2016-02-11",decoded.application.unparsedValues,"forwarded parsed leftovers");
		Ism868SignalAnalyzer.Observation observation=new Ism868SignalAnalyzer.Observation(
				868_250_000L,100_000,-40d,20d,100d,"W-MBus S","2-FSK",decoded.details,
				decoded.payloadHex,true,10_000L,decoded.manufacturer,decoded.id,decoded.deviceTypeName,
				decoded.version,decoded.control,decoded.ci,decoded.accessNumber,decoded.format,
				decoded.security,decoded.application);
		Ism868SignalAnalyzer.Result tableResult=result(observation);
		Ism868SignalTablePanel panel=new Ism868SignalTablePanel();panel.observe(tableResult);panel.refresh(tableResult);
		equal("4565 HCA\n5008 HCA",panel.cellForMeterForTest("80586166","Previous reading"),
				"forwarded table previous readings");
		equal("2022-12-31\n2023-06-30",panel.cellForMeterForTest("80586166","Previous date"),
				"forwarded table previous dates");
		if(panel.rowHeightForMeterForTest("80586166")<=23)
			throw new AssertionError("multi-value table row was not expanded before rendering");
		panel.moveColumnForTest("RSSI",0);
		equal("-40.0 dBFS",panel.renderedCellForMeterForTest("80586166","RSSI"),
				"moved table column formatting");

		byte[] directHcaWrapper=fromHex("43C465B23F65F00FF309A0830600AD169E0031446532145458802C087A85000000046D090709360B6E603400426CFF2C4B6E553400326CFFFF82046C1F358B046E603400");
		decoded=WmbusPhyDecoder.parseFormatA(formatA(directHcaWrapper));
		valid(decoded,"LSE HCA wrapper with outer type 09");
		equal("A (forwarded)",decoded.format,"LSE HCA wrapper frame marker");
		equal("80585414",decoded.id,"LSE HCA wrapper meter ID");
		equal("heat cost allocator",decoded.deviceTypeName,"LSE HCA wrapper device type");
		equal("qcaloric",decoded.application.driver,"LSE HCA wrapper driver");
		if(decoded.accessNumber!=133)throw new AssertionError("LSE HCA wrapper access number");
		equal("3460 HCA",decoded.application.currentReading,"LSE HCA wrapper current reading");
		equal("3455 HCA\n3460 HCA",decoded.application.previousReading,"LSE HCA wrapper history");
		equal("2023-12-31\n2024-05-31",decoded.application.previousDate,"LSE HCA wrapper history dates");
	}

	private static void testCFormatA(){
		byte[] clean=telegram(new byte[]{0x7a,0x11,0x22,0x33});
		boolean[] air=concat(bits(new byte[]{0x54,0x3d,0x54,(byte)0xcd}),bits(formatA(clean)));
		byte[] iq=modulate(air,2_000_000,100_000,45_000,13757L);
		WmbusPhyDecoder.Decoded decoded=new WmbusPhyDecoder().decodeCt(
				WmbusPhyDecoder.CT_FREQUENCY_HZ,2_000_000,iq,iq.length);
		assertIdentity(decoded,"C","A",clean);
	}

	private static void testCAt250kSampleRate(){
		byte[] clean=telegram(new byte[]{0x7a,0x15,0x25,0x35});
		boolean[] air=concat(bits(new byte[]{0x54,0x3d,0x54,(byte)0xcd}),bits(formatA(clean)));
		byte[] iq=modulate(air,250_000,100_000,45_000,50_000d,13762L);
		WmbusPhyDecoder.Decoded decoded=new WmbusPhyDecoder().decodeCt(
				868_900_000L,250_000,iq,iq.length);
		assertIdentity(decoded,"C","A",clean);
	}

	private static void testCAtArbitraryWidebandOffset(){
		byte[] clean=telegram(new byte[]{0x7a,0x16,0x26,0x36});
		boolean[] air=concat(bits(new byte[]{0x54,0x3d,0x54,(byte)0xcd}),bits(formatA(clean)));
		int rate=8_000_000;long center=866_500_000L;
		byte[] iq=modulate(air,rate,100_000,45_000,
				WmbusPhyDecoder.CT_FREQUENCY_HZ-center,13765L);
		addTone(iq,rate,-2_650_000d,48d);
		WmbusPhyDecoder.Decoded decoded=new WmbusPhyDecoder().decodeCt(center,rate,iq,iq.length);
		assertIdentity(decoded,"C","A",clean);
	}

	private static void testStreamingWidebandChannelizer(){
		byte[] clean=telegram(new byte[]{0x7a,0x19,0x29,0x39});
		boolean[] air=concat(bits(new byte[]{0x54,0x3d,0x54,(byte)0xcd}),bits(formatA(clean)));
		int rate=8_000_000;long center=866_500_000L;
		byte[] iq=modulate(air,rate,100_000,45_000,WmbusPhyDecoder.CT_FREQUENCY_HZ-center,13769L);
		addTone(iq,rate,-2_650_000d,48d);
		IQSubchannelExtractor.Stream stream=new IQSubchannelExtractor.Stream(rate,
				WmbusPhyDecoder.CT_FREQUENCY_HZ-center,WmbusPhyDecoder.CT_OCCUPIED_BANDWIDTH,
				WmbusPhyDecoder.CHANNEL_OUTPUT_RATE);
		float[] joined=new float[iq.length];int written=0;
		for(int at=0;at<iq.length;){int count=Math.min(12_346,iq.length-at)&~1;
			byte[] block=Arrays.copyOfRange(iq,at,at+count);
			IQSubchannelExtractor.Result output=stream.process(block,block.length);
			System.arraycopy(output.iq,0,joined,written,output.iq.length);written+=output.iq.length;at+=count;}
		List<WmbusPhyDecoder.Decoded> values=new WmbusPhyDecoder().decodeCtBasebandAll(
				joined,written/2,stream.sampleRateHz());
		if(values.size()!=1)throw new AssertionError("streaming channelizer decoded "+values.size()+" frames");
		assertIdentity(values.get(0),"C","A",clean);
		if(Double.isNaN(values.get(0).levelDbfs)||values.get(0).levelDbfs<=-120d)
			throw new AssertionError("per-frame level was not measured");
	}

	private static void testDynamicArbitraryChannelizer(){
		int rate=8_000_000;long center=866_500_000L,target=867_420_000L;
		boolean[] pattern=new boolean[600];for(int n=0;n<pattern.length;n++)pattern[n]=(n%7)<3;
		byte[] iq=modulate(pattern,rate,100_000,35_000,target-center,13770L);
		IQDynamicChannelizer channelizer=new IQDynamicChannelizer(center,rate);
		for(int at=0;at<iq.length;){int count=Math.min(65_536,iq.length-at)&~1;
			channelizer.accept(Arrays.copyOfRange(iq,at,at+count),count);at+=count;}
		List<IQDynamicChannelizer.Capture> captures=channelizer.snapshotAndKeep();
		for(IQDynamicChannelizer.Capture capture:captures)if(Math.abs(capture.frequencyHz-target)<100_000L){
			if(capture.iq.length<256||capture.sampleRateHz>=rate)
				throw new AssertionError("arbitrary channel was not filtered/decimated");return;}
		throw new AssertionError("arbitrary 867.42 MHz channel was not created");
	}

	private static void testPolyphaseChannelizer(){
		int rate=8_000_000;long center=866_500_000L,target=867_420_000L;
		boolean[] pattern=new boolean[800];for(int n=0;n<pattern.length;n++)pattern[n]=(n&1)==0;
		byte[] iq=modulate(pattern,rate,100_000,35_000,target-center,13771L);
		IQPolyphaseChannelizer channelizer=new IQPolyphaseChannelizer(center,rate);
		if(channelizer.channelsForTest()!=16||Math.round(channelizer.outputRateForTest())!=500_000L)
			throw new AssertionError("unexpected 8 MS/s PFB geometry");
		for(int at=0;at<iq.length;){int count=Math.min(65_536,iq.length-at)&~1;
			byte[] block=Arrays.copyOfRange(iq,at,at+count);
			channelizer.accept(block,block.length);at+=count;}
		/* The UI snapshots at 250 ms cadence; a short burst must not disappear
		 * merely because it occurred early in that interval. */
		byte[] quiet=new byte[65_536];for(int n=0;n<49;n++)channelizer.accept(quiet,quiet.length);
		List<IQDynamicChannelizer.Capture> captures=channelizer.snapshotAndKeep();
		for(IQDynamicChannelizer.Capture capture:captures)if(Math.abs(capture.frequencyHz-target)<60_000L){
			if(capture.iq.length<256||Math.round(capture.sampleRateHz)!=500_000L||
					Double.isNaN(capture.snrDb))throw new AssertionError("invalid PFB capture");return;}
		throw new AssertionError("PFB did not retain the arbitrary 867.42 MHz channel: "+captures.size());
	}

	private static void testPolyphaseCompletedBurstQueue(){
		int rate=8_000_000;long center=866_500_000L,target=867_420_000L;
		boolean[] pattern=new boolean[800];for(int n=0;n<pattern.length;n++)pattern[n]=(n&1)==0;
		byte[] burst=modulate(pattern,rate,100_000,35_000,target-center,13772L);
		IQPolyphaseChannelizer channelizer=new IQPolyphaseChannelizer(center,rate);
		channelizer.accept(burst,burst.length);
		channelizer.accept(new byte[(int)Math.round(rate*.030d)*2],(int)Math.round(rate*.030d)*2);
		/* Reactivate the same coarse PFB channel before the GUI takes a snapshot.
		 * The first completed burst must remain independently available. */
		channelizer.accept(burst,burst.length);
		for(IQDynamicChannelizer.Capture capture:channelizer.snapshotAndKeep())
			if(capture.complete&&Math.abs(capture.frequencyHz-target)<60_000L)return;
		throw new AssertionError("completed PFB burst was overwritten by a later burst");
	}

	private static void testCFormatB(){
		byte[] clean=telegram(new byte[]{0x72,0x21,0x22,0x23,0x24});
		boolean[] air=concat(bits(new byte[]{0x54,0x3d,0x54,0x3d}),bits(formatB(clean)));
		byte[] iq=modulate(air,2_000_000,100_000,45_000,13758L);
		WmbusPhyDecoder.Decoded decoded=new WmbusPhyDecoder().decodeCt(
				WmbusPhyDecoder.CT_FREQUENCY_HZ,2_000_000,iq,iq.length);
		assertIdentity(decoded,"C","B",clean);
	}

	private static void testTFormatA(){
		byte[] clean=telegram(new byte[]{0x7a,0x31,0x32,0x33});
		boolean[] air=concat(bits(new byte[]{0x54,0x3d}),threeOfSix(formatA(clean)));
		byte[] iq=modulate(air,2_000_000,100_000,45_000,13759L);
		WmbusPhyDecoder.Decoded decoded=new WmbusPhyDecoder().decodeCt(
				WmbusPhyDecoder.CT_FREQUENCY_HZ,2_000_000,iq,iq.length);
		assertIdentity(decoded,"T","A",clean);
	}

	private static void testTAt250kSampleRate(){
		byte[] clean=telegram(new byte[]{0x7a,0x36,0x37,0x38});
		boolean[] air=concat(bits(new byte[]{0x54,0x3d}),threeOfSix(formatA(clean)));
		byte[] iq=modulate(air,250_000,100_000,45_000,50_000d,13763L);
		WmbusPhyDecoder.Decoded decoded=new WmbusPhyDecoder().decodeCt(
				868_900_000L,250_000,iq,iq.length);
		assertIdentity(decoded,"T","A",clean);
	}

	private static void testAllFramesInCapture(){
		byte[] first=telegram(new byte[]{0x7a,0x37,0x47,0x57});
		byte[] second=telegram(new byte[]{0x7a,0x38,0x48,0x58});second[4]^=1;
		boolean[] firstAir=concat(bits(new byte[]{0x54,0x3d,0x54,(byte)0xcd}),bits(formatA(first)));
		boolean[] secondAir=concat(bits(new byte[]{0x54,0x3d,0x54,(byte)0xcd}),bits(formatA(second)));
		byte[] firstIq=modulate(firstAir,2_000_000,100_000,45_000,13767L);
		byte[] secondIq=modulate(secondAir,2_000_000,100_000,45_000,13768L);
		byte[] capture=new byte[firstIq.length+secondIq.length];
		System.arraycopy(firstIq,0,capture,0,firstIq.length);
		System.arraycopy(secondIq,0,capture,firstIq.length,secondIq.length);
		List<WmbusPhyDecoder.Decoded> decoded=new WmbusPhyDecoder().decodeCtAll(
				WmbusPhyDecoder.CT_FREQUENCY_HZ,2_000_000,capture,capture.length);
		if(decoded.size()!=2)throw new AssertionError("two-frame capture decoded "+decoded.size()+" frames");
		Ism868SignalAnalyzer.Result analyzed=new Ism868SignalAnalyzer().analyze(
				WmbusPhyDecoder.CT_FREQUENCY_HZ,2_000_000,capture,capture.length,1234L);
		int promoted=0;
		for(Ism868SignalAnalyzer.Observation observation:analyzed.observations)
			if(observation.checksumValid){promoted++;
				if(Double.isNaN(observation.levelDbfs)||observation.levelDbfs<=-120d)
					throw new AssertionError("decoded frame retained placeholder RSSI");}
		if(promoted!=2)throw new AssertionError("analyzer promoted "+promoted+" of two frames");
	}

	private static void testSFormatA(){
		byte[] clean=telegram(new byte[]{0x7a,0x41,0x42,0x43});
		boolean[] air=concat(bits(new byte[]{0x54,0x76,(byte)0x96}),manchester(bits(formatA(clean))));
		int rate=1_048_576;byte[] iq=modulate(air,rate,32_768,50_000,13760L);
		WmbusPhyDecoder.Decoded decoded=new WmbusPhyDecoder().decodeS(
				WmbusPhyDecoder.S_FREQUENCY_HZ,rate,iq,iq.length);
		assertIdentity(decoded,"S","A",clean);
		if(decoded.accessNumber!=0x41)throw new AssertionError("S-mode access number");
	}

	private static void testSAt250kSampleRate(){
		byte[] clean=telegram(new byte[]{0x7a,0x46,0x47,0x48});
		boolean[] air=concat(bits(new byte[]{0x54,0x76,(byte)0x96}),manchester(bits(formatA(clean))));
		byte[] iq=modulate(air,250_000,32_768,50_000,0d,13764L);
		WmbusPhyDecoder.Decoded decoded=new WmbusPhyDecoder().decodeS(
				WmbusPhyDecoder.S_FREQUENCY_HZ,250_000,iq,iq.length);
		assertIdentity(decoded,"S","A",clean);
	}

	private static void testSAtArbitraryWidebandOffset(){
		byte[] clean=telegram(new byte[]{0x7a,0x47,0x49,0x4b});
		boolean[] air=concat(bits(new byte[]{0x54,0x76,(byte)0x96}),manchester(bits(formatA(clean))));
		int rate=8_000_000;long center=866_500_000L;
		byte[] iq=modulate(air,rate,32_768,50_000,
				WmbusPhyDecoder.S_FREQUENCY_HZ-center,13766L);
		addTone(iq,rate,-2_450_000d,48d);
		WmbusPhyDecoder.Decoded decoded=new WmbusPhyDecoder().decodeS(center,rate,iq,iq.length);
		assertIdentity(decoded,"S","A",clean);
	}

	private static void testDriftingDirectLseHca(){
		byte[] clean=fromHex("26446532256158802C087A51040000046D260F4C3A0B6E507000426C3F3C4B6E456700326CFFFF");
		boolean[] air=concat(bits(new byte[]{0x54,0x76,(byte)0x96}),manchester(bits(formatA(clean))));
		byte[] burst=modulate(air,1_000_000,32_990,50_000,80586125L);
		/* Reproduce the supplied capture: one short meter frame in almost a
		 * second of IQ, with a clock outside the old +/-1500 ppm acquisition. */
		byte[] sparse=new byte[1_900_000];System.arraycopy(burst,0,sparse,900_000,burst.length);
		float[] baseband=new float[sparse.length];
		for(int n=0;n<sparse.length;n++)baseband[n]=sparse[n]/128f;
		List<WmbusPhyDecoder.Decoded> found=new WmbusPhyDecoder()
				.decodeSBasebandAllClockTolerant(baseband,baseband.length/2,1_000_000d);
		WmbusPhyDecoder.Decoded decoded=found.isEmpty()?WmbusPhyDecoder.Decoded.NONE:found.get(0);
		valid(decoded,"direct drifting LSE HCA");equal("S",decoded.mode,"direct LSE mode");
		equal("A",decoded.format,"direct LSE is not a forwarded frame");
		equal("LSE",decoded.manufacturer,"direct LSE manufacturer");
		equal("80586125",decoded.id,"direct LSE meter ID");
		equal("heat cost allocator",decoded.deviceTypeName,"direct LSE device type");
		equal("qcaloric",decoded.application.driver,"direct LSE driver");
		equal("7050 HCA",decoded.application.currentReading,"direct LSE current reading");
		equal("6745 HCA",decoded.application.previousReading,"direct LSE previous reading");
	}

	private static void testAnalyzerPromotion(){
		byte[] clean=telegram(new byte[]{0x7a,0x51,0x52,0x53});
		boolean[] air=concat(bits(new byte[]{0x54,0x3d,0x54,(byte)0xcd}),bits(formatA(clean)));
		byte[] iq=Arrays.copyOf(modulate(air,2_000_000,100_000,45_000,13761L),16_384);
		Ism868SignalAnalyzer.Result result=new Ism868SignalAnalyzer().analyze(
				WmbusPhyDecoder.CT_FREQUENCY_HZ,2_000_000,iq,iq.length,1234L);
		for(Ism868SignalAnalyzer.Observation observation:result.observations)
			if("W-MBus C".equals(observation.protocol)&&observation.checksumValid
					&&hex(withLength(clean,"A")).equals(observation.payloadHex))return;
		throw new AssertionError("CRC-valid C telegram was not promoted into the ISM table");
	}

	private static void testMeterTableGrouping(){
		Ism868SignalTablePanel panel=new Ism868SignalTablePanel();
		panel.observe(result(observation("W-MBus C","TCH","54013334",1000L)));
		panel.observe(result(observation("W-MBus T","TCH","54013334",2000L)));
		panel.refresh(result(observation("W-MBus T","TCH","54013334",2000L)));
		panel.selectMeterForTest("54013334");
		if(panel.signalCountForTest()!=1||panel.seenForMeterForTest("TCH","54013334")!=2L)
			throw new AssertionError("same W-MBus meter was not grouped across C/T telegrams");
		panel.observe(result(observation("W-MBus C","TCH","54013335",3000L)));
		panel.observe(result(observation("W-MBus C","ABC","54013334",4000L)));
		panel.refresh(result(observation("W-MBus C","ABC","54013334",4000L)));
		if(!panel.meterSelectedForTest("54013334"))
			throw new AssertionError("selected meter row lost its highlight during refresh");
		if(panel.signalCountForTest()!=3)throw new AssertionError("distinct meter identities were merged");
		panel.observe(result(new Ism868SignalAnalyzer.Observation(868_950_000L,100_000,-40d,20d,
				70d,"W-MBus C/T candidate","2-FSK","candidate","",false,5000L,"","")));
		if(panel.signalCountForTest()!=4)throw new AssertionError("spectral candidate absorbed a decoded meter row");
		Ism868SignalTablePanel promoted=new Ism868SignalTablePanel();
		promoted.observe(result(new Ism868SignalAnalyzer.Observation(868_950_000L,100_000,-40d,20d,
				70d,"W-MBus S candidate","Manchester FSK","candidate","",false,5000L,"","")));
		promoted.observe(result(observation("W-MBus S","LSE","80586125",6000L)));
		if(promoted.signalCountForTest()!=1)
			throw new AssertionError("confirmed W-MBus row retained its earlier candidate alias");
	}

	private static void testTableRowTints(){
		java.awt.Color tchC=Ism868SignalTablePanel.rowTintForTest("TCH","W-MBus C");
		java.awt.Color tchT=Ism868SignalTablePanel.rowTintForTest("TCH","W-MBus T");
		java.awt.Color qdsC=Ism868SignalTablePanel.rowTintForTest("QDS","W-MBus C");
		java.awt.Color lora=Ism868SignalTablePanel.rowTintForTest("--","LoRa candidate");
		if(tchC.equals(tchT)||tchC.equals(qdsC)||isBlack(tchC)||isBlack(lora))
			throw new AssertionError("manufacturer/protocol row tints are not distinct");
		if(tchT.getRed()<=tchT.getGreen()||tchC.getRed()<=tchC.getGreen()
				||tchC.getGreen()<=tchT.getGreen())
			throw new AssertionError("Techem C/T colours are not red/orange variants");
		if(lora.getRed()+lora.getGreen()+lora.getBlue()
				<=tchT.getRed()+tchT.getGreen()+tchT.getBlue())
			throw new AssertionError("LoRa tint is not visibly lighter than W-MBus T");
		java.awt.Color loraHighlight=Ism868SignalTablePanel.highlightTintForTest("--","LoRa candidate");
		if(loraHighlight.getRed()!=0xee||loraHighlight.getGreen()!=0xee||loraHighlight.getBlue()!=0xee)
			throw new AssertionError("tracked row colour is not #eeeeee");
		if(!isBlack(Ism868SignalTablePanel.rowTintForTest("--","SRD / rtl_433 candidate")))
			throw new AssertionError("unclassified row should remain uncoloured");
	}

	private static boolean isBlack(java.awt.Color value){return value.getRGB()==java.awt.Color.BLACK.getRGB();}

	private static void testTableLayoutPersistence()throws Exception{
		java.io.File settings=java.io.File.createTempFile("ism868-table-",".ini");settings.deleteOnExit();
		Ism868SignalTablePanel defaults=new Ism868SignalTablePanel(settings);
		if(!"Frequency".equals(defaults.primarySortColumnForTest())
				||defaults.primarySortOrderForTest()!=javax.swing.SortOrder.ASCENDING)
			throw new AssertionError("default table sort is not ascending frequency");
		defaults.moveColumnForTest("RSSI",0);defaults.setColumnWidthForTest("RSSI",177);
		defaults.sortByColumnForTest("Received",javax.swing.SortOrder.DESCENDING);
		defaults.saveTableLayoutForTest();
		Ism868SignalTablePanel restored=new Ism868SignalTablePanel(settings);restored.restoreTableLayoutForTest();
		if(restored.viewColumnForTest("RSSI")!=0||restored.columnWidthForTest("RSSI")!=177)
			throw new AssertionError("868 table column layout was not restored");
		if(!"Received".equals(restored.primarySortColumnForTest())
				||restored.primarySortOrderForTest()!=javax.swing.SortOrder.DESCENDING)
			throw new AssertionError("868 table sorting was not restored");
		settings.delete();

		java.io.File legacySettings=java.io.File.createTempFile("ism868-table-legacy-",".ini");
		legacySettings.deleteOnExit();java.util.Properties legacy=new java.util.Properties();
		legacy.setProperty("ism868.table.columnOrder",
				"28,0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,29,30,31,32,33,34");
		legacy.setProperty("ism868.table.columnWidths",
				"105,105,92,125,145,68,62,62,58,75,150,125,105,92,105,92,82,92,92,78,92,92,78,88,92,92,72,240,177,74,72,62,72,55,82");
		legacy.setProperty("ism868.table.sortKeys","34:D");
		try(java.io.FileOutputStream output=new java.io.FileOutputStream(legacySettings)){
			legacy.store(output,"legacy 35-column layout");}
		Ism868SignalTablePanel migrated=new Ism868SignalTablePanel(legacySettings);
		migrated.restoreTableLayoutForTest();
		if(migrated.viewColumnForTest("RSSI")!=0||migrated.columnWidthForTest("RSSI")!=177)
			throw new AssertionError("legacy 868 table layout was not migrated");
		if(!"Last seen".equals(migrated.primarySortColumnForTest())
				||migrated.primarySortOrderForTest()!=javax.swing.SortOrder.DESCENDING)
			throw new AssertionError("legacy 868 table sort index was not migrated");
		legacySettings.delete();
	}

	private static void testAccessCounterRate(){
		Ism868SignalTablePanel panel=new Ism868SignalTablePanel();
		panel.observe(result(observationWithAccess(254,10_000L)));
		panel.observe(result(observationWithAccess(254,10_200L))); // overlapping IQ window
		panel.observe(result(observationWithAccess(255,20_000L)));
		panel.observe(result(observationWithAccess(255,30_000L))); // real retransmission, unchanged AC
		panel.observe(result(observationWithAccess(2,50_000L)));   // wrap and two missed frames
		if(panel.seenForMeterForTest("TCH","54013334")!=4L)
			throw new AssertionError("Tx count did not distinguish overlap from repeated AC");
		if(panel.missedForMeterForTest("TCH","54013334")!=2L)
			throw new AssertionError("AC gaps were not counted across rollover");
		double txPeriod=panel.periodForMeterForTest("TCH","54013334");
		if(Math.abs(txPeriod-40d/3d)>.0001d)throw new AssertionError("observed Tx period "+txPeriod);
		double acPeriod=panel.acPeriodForMeterForTest("TCH","54013334");
		if(Math.abs(acPeriod-10d)>.0001d)throw new AssertionError("AC-corrected period "+acPeriod);
		equal("10 s",Ism868SignalTablePanel.formatPeriodForTest(acPeriod),"seconds period formatting");
		equal("2 min 17 s",Ism868SignalTablePanel.formatPeriodForTest(137.4d),"minutes period formatting");
		panel.refresh(result(observationWithAccess(2,50_000L)));
		equal("13 s",panel.renderedCellForMeterForTest("54013334","Tx period"),"Tx period column");
		equal("10 s",panel.renderedCellForMeterForTest("54013334","AC period"),"AC period column");
		panel.moveColumnForTest("AC period",0);
		equal("10 s",panel.renderedCellForMeterForTest("54013334","AC period"),
				"moved AC period formatting");
	}

	private static void testSelectedMeterNotification(){
		Ism868SignalTablePanel panel=new Ism868SignalTablePanel();panel.setAudibleNotificationsForTest(false);
		Ism868SignalAnalyzer.Observation first=observationWithAccess(10,10_000L);
		panel.observe(result(first));panel.refresh(result(first));panel.rightClickMeterForTest("54013334");
		panel.observe(result(observationWithAccess(11,20_000L)));
		panel.observe(result(observationWithAccess(11,20_200L)));
		if(panel.notificationCountForTest()!=1L)
			throw new AssertionError("selected meter notification did not reject a duplicate AC");
	}

	private static void testGenericSignalRateAndFlash(){
		Ism868SignalTablePanel panel=new Ism868SignalTablePanel();panel.setAudibleNotificationsForTest(false);
		Ism868SignalAnalyzer.Observation candidate=new Ism868SignalAnalyzer.Observation(
				866_120_000L,35_000,-45d,14d,70d,"SRD / rtl_433 candidate","narrow FSK/OOK",
				"candidate","",false,5_000L,"","");
		Ism868SignalAnalyzer.Result result=result(candidate);long before=System.currentTimeMillis();
		panel.observeAtForTest(result,1_000_000_000L);panel.observeAtForTest(result,5_000_000_000L);
		if(panel.receivedForProtocolForTest("SRD / rtl_433 candidate")!=2L)
			throw new AssertionError("generic signal updates were not counted");
		if(Math.abs(panel.periodForProtocolForTest("SRD / rtl_433 candidate")-4d)>.001d)
			throw new AssertionError("generic signal Tx period was not calculated");
		if(panel.updateFlashCountForTest()!=1L)throw new AssertionError("generic row update did not flash");
		long seen=panel.lastSeenForProtocolForTest("SRD / rtl_433 candidate");
		if(seen<before||seen>System.currentTimeMillis())
			throw new AssertionError("868 last-seen used recording timestamp instead of table arrival");
		panel.refresh(result);panel.rightClickProtocolForTest("SRD / rtl_433 candidate");
		panel.refresh(result);
		if(!panel.protocolTrackedForTest("SRD / rtl_433 candidate"))
			throw new AssertionError("tracked row highlight did not survive table refresh");
		panel.observeAtForTest(result,9_000_000_000L);
		if(panel.notificationCountForTest()!=1L)
			throw new AssertionError("selected generic row update did not chime");
		if(!new java.awt.Color(0xeeeeee).equals(panel.renderedBackgroundForProtocolForTest(
				"SRD / rtl_433 candidate")))
			throw new AssertionError("update flash obscured the tracked-row highlight");
		if(panel.statusTextForTest().indexOf("right-click a row")<0)
			throw new AssertionError("tracking/chime help is missing from status line");
	}

	private static void testStructuredApplicationColumns(){
		byte[] capture=fromHex("32446850343301546980A0119F2B0009F0158D025A083308040000000200000000000000000003150D5C5E9AB05EBEC1BDCA8F");
		WmbusApplicationDecoder.Result application=WmbusApplicationDecoder.decode(
				capture,"TCH",0x69,0x80,0xa0);
		Ism868SignalAnalyzer.Observation observation=new Ism868SignalAnalyzer.Observation(
				868_950_000L,100_000,-35d,25d,100d,"W-MBus T","2-FSK","decoded",
				"AA",true,10_000L,"TCH","54013334","heat cost allocator",0x69,
				0x44,0xa0,-1,"A","clear (manufacturer-specific)",application);
		Ism868SignalAnalyzer.Result result=result(observation);
		Ism868SignalTablePanel panel=new Ism868SignalTablePanel();panel.observe(result);panel.refresh(result);
		equal("653 HCA",panel.cellForMeterForTest("54013334","Current reading"),"table current reading");
		equal("2022-10-31",panel.cellForMeterForTest("54013334","Current date"),"table current date");
		equal("2304 HCA",panel.cellForMeterForTest("54013334","Previous reading"),"table previous reading");
		equal("2021-12-31",panel.cellForMeterForTest("54013334","Previous date"),"table previous date");
		equal("21.38 C",panel.cellForMeterForTest("54013334","Room temp"),"table room temperature");
		equal("20.99 C",panel.cellForMeterForTest("54013334","Radiator temp"),"table radiator temperature");
		equal("--",panel.cellForMeterForTest("54013334","Decoded values"),"table unparsed values");
	}

	private static Ism868SignalAnalyzer.Observation observation(String protocol,String manufacturer,
			String id,long timestamp){
		return new Ism868SignalAnalyzer.Observation(868_950_000L,100_000,-35d,25d,100d,
				protocol,"2-FSK","decoded","0102",true,timestamp,manufacturer,id);
	}

	private static Ism868SignalAnalyzer.Observation observationWithAccess(int access,long timestamp){
		return new Ism868SignalAnalyzer.Observation(868_950_000L,100_000,-35d,25d,100d,
				"W-MBus T","2-FSK","decoded","AA",true,timestamp,"TCH","54013334",
				"heat cost allocator",0x69,0x44,0x7a,access,"A","clear",
				WmbusApplicationDecoder.Result.NONE);
	}

	private static Ism868SignalAnalyzer.Result result(Ism868SignalAnalyzer.Observation observation){
		return new Ism868SignalAnalyzer.Result(868_950_000L,1_000_000,868_450_000d,869_450_000d,
				-90d,Arrays.asList(observation),"test");
	}

	private static byte[] baseHeader(){
		return new byte[]{0,0x44,0x2d,0x2c,0x78,0x56,0x34,0x12,1,7};
	}

	private static byte[] telegram(byte[] payload){
		byte[] clean=new byte[10+payload.length];System.arraycopy(baseHeader(),0,clean,0,10);
		System.arraycopy(payload,0,clean,10,payload.length);return clean;
	}

	private static byte[] formatA(byte[] clean){
		clean=clean.clone();clean[0]=(byte)(clean.length-1);
		List<Byte> out=new ArrayList<Byte>();append(out,clean,0,10);appendCrc(out,clean,0,10);
		for(int at=10;at<clean.length;at+=16){int count=Math.min(16,clean.length-at);
			append(out,clean,at,count);appendCrc(out,clean,at,count);}
		return array(out);
	}

	private static byte[] formatB(byte[] input){
		byte[] clean=input.clone();boolean longFrame=clean.length>126;
		int l=clean.length+(longFrame?3:1);clean[0]=(byte)l;
		List<Byte> out=new ArrayList<Byte>();int first=Math.min(clean.length,126);
		append(out,clean,0,first);appendCrc(out,clean,0,first);
		if(longFrame){append(out,clean,first,clean.length-first);appendCrc(out,clean,first,clean.length-first);}
		return array(out);
	}

	private static void append(List<Byte> out,byte[] bytes,int offset,int length){
		for(int n=0;n<length;n++)out.add(Byte.valueOf(bytes[offset+n]));
	}
	private static void appendCrc(List<Byte> out,byte[] bytes,int offset,int length){
		int crc=WmbusPhyDecoder.crc(bytes,offset,length);out.add(Byte.valueOf((byte)(crc>>8)));
		out.add(Byte.valueOf((byte)crc));
	}
	private static byte[] array(List<Byte> values){byte[] out=new byte[values.size()];
		for(int n=0;n<out.length;n++)out[n]=values.get(n).byteValue();return out;}

	private static boolean[] bits(byte[] bytes){boolean[] out=new boolean[bytes.length*8];
		for(int n=0;n<out.length;n++)out[n]=((bytes[n/8]>>(7-n%8))&1)!=0;return out;}
	private static boolean[] threeOfSix(byte[] bytes){boolean[] out=new boolean[bytes.length*12];int at=0;
		for(byte value:bytes){int high=THREE_OF_SIX[(value>>4)&15],low=THREE_OF_SIX[value&15];
			for(int bit=5;bit>=0;bit--)out[at++]=((high>>bit)&1)!=0;
			for(int bit=5;bit>=0;bit--)out[at++]=((low>>bit)&1)!=0;}return out;}
	private static boolean[] manchester(boolean[] data){boolean[] out=new boolean[data.length*2];
		for(int n=0;n<data.length;n++){out[n*2]=data[n];out[n*2+1]=!data[n];}return out;}
	private static boolean[] concat(boolean[]... arrays){int size=0;for(boolean[] a:arrays)size+=a.length;
		boolean[] out=new boolean[size];int at=0;for(boolean[] a:arrays){System.arraycopy(a,0,out,at,a.length);at+=a.length;}return out;}

	private static byte[] modulate(boolean[] bits,int rate,int bitRate,int deviation,long seed){
		return modulate(bits,rate,bitRate,deviation,0d,seed);
	}

	private static byte[] modulate(boolean[] bits,int rate,int bitRate,int deviation,
			double frequencyOffset,long seed){
		int padding=Math.max(20,(int)Math.ceil(rate/(double)bitRate)*7+3);
		int activeSamples=(int)Math.ceil(bits.length*(double)rate/bitRate);
		int total=padding+activeSamples+padding;byte[] out=new byte[total*2];
		Random random=new Random(seed);double phase=0d;
		for(int n=0;n<total;n++){int relative=n-padding;boolean active=relative>=0&&relative<activeSamples;
			int bitIndex=active?(int)Math.floor(relative*(double)bitRate/rate):-1;
			if(bitIndex>=bits.length)bitIndex=bits.length-1;
			boolean bit=active&&bits[bitIndex];
			if(active)phase+=2d*Math.PI*(frequencyOffset+(bit?deviation:-deviation))/rate;
			double amplitude=active?68d:0d;
			out[n*2]=(byte)Math.max(-127,Math.min(127,Math.round(amplitude*Math.cos(phase)+random.nextGaussian()*1.2d)));
			out[n*2+1]=(byte)Math.max(-127,Math.min(127,Math.round(amplitude*Math.sin(phase)+random.nextGaussian()*1.2d)));}
		return out;
	}

	private static void addTone(byte[] iq,int rate,double frequency,double amplitude){
		double phase=0d,step=2d*Math.PI*frequency/rate;
		for(int n=0;n<iq.length/2;n++){
			int i=(int)Math.round(iq[n*2]+amplitude*Math.cos(phase));
			int q=(int)Math.round(iq[n*2+1]+amplitude*Math.sin(phase));
			iq[n*2]=(byte)Math.max(-127,Math.min(127,i));
			iq[n*2+1]=(byte)Math.max(-127,Math.min(127,q));
			phase+=step;
		}
	}

	private static void assertIdentity(WmbusPhyDecoder.Decoded decoded,String mode,String format,byte[] clean){
		valid(decoded,mode+" format "+format);equal(mode,decoded.mode,"mode");equal(format,decoded.format,"format");
		equal("KAM",decoded.manufacturer,"manufacturer");equal("12345678",decoded.id,"meter ID");
		equal(hex(withLength(clean,format)),decoded.payloadHex,"CRC-stripped telegram");
	}
	private static byte[] withLength(byte[] clean,String format){byte[] copy=clean.clone();
		copy[0]=(byte)(copy.length+("B".equals(format)&&copy.length>126?3:1)-("A".equals(format)?2:0));return copy;}
	private static void valid(WmbusPhyDecoder.Decoded value,String what){if(!value.valid)throw new AssertionError(what+" did not decode");}
	private static void invalid(WmbusPhyDecoder.Decoded value,String what){if(value.valid)throw new AssertionError(what+" was accepted");}
	private static void equal(String expected,String actual,String what){if(!expected.equals(actual))throw new AssertionError(what+": expected "+expected+" got "+actual);}
	private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.US,"%02X",b&255));return out.toString();}
	private static byte[] fromHex(String value){byte[] out=new byte[value.length()/2];
		for(int n=0;n<out.length;n++)out[n]=(byte)Integer.parseInt(value.substring(n*2,n*2+2),16);return out;}
}
