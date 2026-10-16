package jspectrumanalyzer.iq;

import java.io.File;
import java.util.List;

import javax.swing.SortOrder;

/** Deterministic PHY and advertising parser checks; no HackRF is required. */
public final class BluetoothSignalAnalyzerTest {
	private static final String OFFICIAL_CHANNEL_38_PACKET=
			"01010101"+"01101011"+"01111101"+"10010001"+"01110001"+
			"00101001"+"00110011"+"01000111"+"10100001"+"10111111"+
			"10111110"+"11000010"+"01110010"+"01011000"+"11100101"+
			"00110101"+"11110111"+"11110011"+"10100101";

	public static void main(String[] args) {
		testBluetoothSigSamplePacket();
		testChannelCoverage();
		testWidebandOffsetChannel();
		testLe2MDecoder();
		testLeCodedOfficialFecVector();
		testLeCodedAdvertisingDecoder();
		testClassicAccessCode();
		testClassicCodingReferences();
		testClassicInquiryFhs();
		testClassicLearnedPiconetPayload();
		testClassicIdentityData();
		testCentered1250kCapture();
		testPacketAcrossIqBlocks();
		testNarrowbandActivityMode();
		testAdvertisingDataParser();
		testAppearanceParser();
		testClassOfDeviceParser();
		testAuxPointerParser();
		testConnectIndParser();
		testChannelSelectionAlgorithms();
		testChannelMapInstant();
		testL2capReassembly();
		testGattIdentification();
		testBluetoothVersionLabels();
		testConnectedDataChannel();
		testLe2MDataChannel();
		testLeCodedDataChannel();
		testLastSeenUsesArrivalTime();
		testSelectedDeviceNotification();
		testTableLayoutPersistence();
		testCrcReference();
		System.out.println("Bluetooth analyzer tests passed");
	}

	private static void testClassicAccessCode() {
		byte[] access=BluetoothClassicAnalyzer.BrAccessDecoder.accessBits(0x9e8b33,true);
		String expected="0101"+hexBits("475c58cc73345e72")+"1010";
		assertTrue(expected.equals(bitString(access)),"Classic GIAC construction mismatch: "+bitString(access));
		byte[] iq=gfsk(bitString(access),4_000_000,0d,160_000d,64,64);
		BluetoothClassicAnalyzer analyzer=new BluetoothClassicAnalyzer();
		BluetoothClassicAnalyzer.Result result=analyzer.analyze(2_402_000_000L,iq,iq.length,4_000_000,8L);
		assertTrue(!result.packets.isEmpty(),"Classic GIAC access code was not detected");
		assertTrue(result.packets.get(0).inquiryAccess,"Classic GIAC classification missing");
		assertTrue(result.packets.get(0).trailerPresent,"Classic access-code trailer missing");
		assertTrue(result.scannedChannels.matches("\\d+(,\\d+)*"),"Classic scanned-channel summary is not numeric: "+result.scannedChannels);
		BluetoothSignalAnalyzer combined=new BluetoothSignalAnalyzer();BluetoothSignalAnalyzer.Result combinedResult=
				combined.analyze(2_402_000_000L,iq,iq.length,4_000_000,8L);
		assertTrue("Classic access".equals(SignalQualityTesterFrame.bluetoothShortStatus(combinedResult,false)),
				"unidentified Classic access code was presented as a device: "+combinedResult.state);
	}

	private static void testClassicCodingReferences() {
		assertTrue(BluetoothClassicAnalyzer.BrAccessDecoder.hec8(0x123,0)==0xe1,"Classic HEC DCI vector mismatch");
		assertTrue(BluetoothClassicAnalyzer.BrAccessDecoder.hec8(0x123,0x47)==0x06,"Classic HEC UAP vector mismatch");
		int[] data=new int[80];int[] bytes={0x4e,1,2,3,4,5,6,7,8,9};for(int b=0;b<bytes.length;b++)
			for(int n=0;n<8;n++)data[b*8+n]=(bytes[b]>>>n)&1;
		assertTrue(BluetoothClassicAnalyzer.BrAccessDecoder.crc16(data,0,data.length,0x47)==0xd26d,
				"Classic CRC vector mismatch");
		assertTrue(BluetoothClassicAnalyzer.BrAccessDecoder.fecCodeword(bits("0111010010"))==bits("011101001011001"),
				"Classic (15,10) FEC vector mismatch");
		int[] whitening=BluetoothClassicAnalyzer.BrAccessDecoder.whiteningBits(0x7f,16);
		assertTrue("1110001110110001".equals(bitString(whitening)),"Classic whitening vector mismatch: "+bitString(whitening));
	}

	private static void testClassicInquiryFhs() {
		int deviceClass=(1<<21)|(4<<8)|(9<<2),fhsLap=0x123456,uap=0xab,nap=0xcdef;
		String packet=classicInquiryFhs(11,fhsLap,uap,nap,deviceClass,true,1,0x1234567);
		byte[] iq=gfsk(packet,4_000_000,0d,160_000d,64,64);BluetoothClassicAnalyzer analyzer=new BluetoothClassicAnalyzer();
		BluetoothClassicAnalyzer.Result result=analyzer.analyze(2_402_000_000L,iq,iq.length,4_000_000,9L);
		BluetoothClassicPacket decoded=null;for(BluetoothClassicPacket p:result.packets)if(p.fhsValid)decoded=p;
		assertTrue(decoded!=null,"Classic inquiry FHS was not decoded");
		assertTrue("CD:EF:AB:12:34:56".equals(decoded.address),"FHS BD_ADDR mismatch: "+decoded.address);
		assertTrue(decoded.deviceType.contains("set-top box"),"FHS Class of Device mismatch: "+decoded.deviceType);
		assertTrue(!decoded.deviceType.contains("Class of Device"),"Class of Device details leaked into the device-type text");
		assertTrue(decoded.eir&&"R1".equals(decoded.scanRepetition),"FHS discovery fields missing");
	}

	private static void testClassicLearnedPiconetPayload(){
		int lap=0x123456,uap=0xab,nap=0xcdef,deviceClass=(1<<21)|(4<<8)|(9<<2);
		BluetoothClassicAnalyzer analyzer=new BluetoothClassicAnalyzer();byte[] fhsIq=gfsk(
				classicInquiryFhs(11,lap,uap,nap,deviceClass,true,1,0x1234567),4_000_000,0d,160_000d,64,64);
		analyzer.analyze(2_402_000_000L,fhsIq,fhsIq.length,4_000_000,100L);
		byte[] body={0x01,0x00,0x04,0x00,0x0a};int header=3|(4<<3)|(1<<7)|(1<<8);
		byte[] packetIq=gfsk(classicBrAclPacket(lap,uap,7,header,2,true,body,false),4_000_000,0d,160_000d,64,64);
		BluetoothClassicAnalyzer.Result result=analyzer.analyze(2_402_000_000L,packetIq,packetIq.length,4_000_000,200L);
		BluetoothClassicPacket decoded=null;for(BluetoothClassicPacket packet:result.packets)if(packet.payloadValid)decoded=packet;
		assertTrue(decoded!=null,"known Classic piconet DH1 payload was not decoded");
		assertTrue("DH1".equals(decoded.classicType)&&decoded.ltAddr==3&&decoded.uap==uap,"Classic header fields are wrong");
		assertTrue(decoded.flow&&decoded.arqn&&!decoded.seqn,"Classic FLOW/ARQN/SEQN fields are wrong");
		assertTrue(decoded.llid==2&&decoded.payloadLength==5&&decoded.payloadInfo.contains("CID 0x0004 (ATT)"),
				"Classic ACL/L2CAP classification missing: "+decoded.payloadInfo);
		assertTrue("CD:EF:AB:12:34:56".equals(decoded.address),"Classic piconet was not correlated with its FHS identity");
		assertTrue(result.totalHeaders>=2&&result.totalPayloads==1,"Classic header/payload counters are wrong");
		int dm1Header=3|(3<<3)|(1<<7)|(1<<9);byte[] dm1Iq=gfsk(
				classicBrAclPacket(lap,uap,9,dm1Header,3,true,new byte[]{0x02,0x01},true),4_000_000,0d,160_000d,64,64);
		BluetoothClassicAnalyzer.Result dm1Result=analyzer.analyze(2_402_000_000L,dm1Iq,dm1Iq.length,4_000_000,300L);
		BluetoothClassicPacket dm1=null;for(BluetoothClassicPacket packet:dm1Result.packets)if(packet.payloadValid)dm1=packet;
		assertTrue(dm1!=null&&"DM1".equals(dm1.classicType)&&dm1.llid==3&&dm1.payloadInfo.contains("ACL-C LMP"),
				"FEC 2/3 DM1/LMP payload was not decoded");
	}

	private static void testClassicIdentityData(){
		BluetoothClassicPacket.FhsData identity=new BluetoothClassicPacket.FhsData(0,7,0,0x123456,0xab,0xcdef,
				(1<<21)|(4<<8)|(9<<2),true,1,0x1234567);
		byte[] eir={0x0a,0x09,'L','i','v','i','n','g',' ','T','V',0x03,0x03,0x12,0x18,0};
		BluetoothClassicPacket.HeaderData eirHeader=new BluetoothClassicPacket.HeaderData(4<<3,7,0,"DH1",0,2,eir.length,
				false,true,false,"CRC-valid EIR",eir);
		BluetoothClassicPacket eirPacket=new BluetoothClassicPacket(37,2_439_000_000L,0x9e8b33,0,true,-20d,0d,100d,1L,eirHeader,identity);
		BluetoothClassicIdentifier decoder=new BluetoothClassicIdentifier();BluetoothGattIdentifier.Identity identified=decoder.observe(eirPacket);
		assertTrue(eirPacket.eir&&identified!=null&&"Living TV".equals(identified.name)
				&&"Human interface / remote".equals(identified.deviceType),"Classic EIR identity missing");
		byte[] namePayload=new byte[17];namePayload[0]=(byte)((2<<1)|1);namePayload[1]=0;namePayload[2]=7;
		System.arraycopy(new byte[]{'H','e','a','d','s','e','t'},0,namePayload,3,7);
		BluetoothClassicPacket.HeaderData lmpHeader=new BluetoothClassicPacket.HeaderData((3)|(3<<3),7,0xab,"DM1",0,3,
				namePayload.length,false,true,false,"LMP name response",namePayload);
		BluetoothClassicPacket lmpPacket=new BluetoothClassicPacket(0,2_402_000_000L,0x123456,0,true,-20d,0d,100d,2L,lmpHeader,identity);
		BluetoothGattIdentifier.Identity lmpName=decoder.observe(lmpPacket);
		assertTrue(lmpName!=null&&"Headset".equals(lmpName.name),"Classic LMP remote name missing");
	}

	private static void testLe2MDecoder() {
		byte[] pdu={0x02,0x06,1,2,3,4,5,(byte)0xc6};int channel=5;
		byte[] iq=gfsk(encodedAdvertisingPacket(pdu,channel,16),8_000_000,0d,500_000d,64,64,2_000_000d);
		float[] samples=new float[iq.length];for(int n=0;n<iq.length;n++)samples[n]=iq[n]/128f;
		BluetoothSignalAnalyzer.Le1MDecoder decoder=new BluetoothSignalAnalyzer.Le1MDecoder(2_000_000,16,"LE 2M");
		List<BluetoothLePacket> packets=decoder.accept(samples,samples.length/2,8_000_000d,channel,
				BluetoothSignalAnalyzer.frequencyForChannel(channel),7L);
		assertTrue(packets.size()==1,"LE 2M packet was not decoded: "+packets.size());
		assertTrue("LE 2M".equals(packets.get(0).phy),"LE 2M PHY label missing");
	}

	private static void testLeCodedAdvertisingDecoder(){byte[] pdu={0x02,0x06,1,2,3,4,5,(byte)0xc6};int channel=5;
		byte[] iq=gfsk(encodedCodedDataPacket(pdu,channel,0x8e89bed6L,0x555555,false),4_000_000,0d,250_000d,64,64);
		BluetoothSignalAnalyzer.LeCodedAdvertisingDecoder decoder=new BluetoothSignalAnalyzer.LeCodedAdvertisingDecoder();
		List<BluetoothLePacket> packets=decoder.accept(floatIq(iq),iq.length/2,4_000_000d,channel,
				BluetoothSignalAnalyzer.frequencyForChannel(channel),7L);
		assertTrue(packets.size()==1,"LE Coded auxiliary advertising packet was not decoded: "+packets.size());
		assertTrue("LE Coded S=8".equals(packets.get(0).phy),"LE Coded advertising PHY label missing");
	}
	private static void testLeCodedOfficialFecVector(){String input="01101011011111011001000101110001";
		String coded="0011010111010010011110100101101110010000101111111000101010001111";
		int[] decoded=BluetoothSignalAnalyzer.LeCodedDataDecoder.viterbi(intBits(coded),32,false);
		assertTrue(input.equals(bitString(decoded)),"LE Coded Bluetooth SIG FEC vector mismatch: "+bitString(decoded));}

	private static void testChannelCoverage() {
		BluetoothSignalAnalyzer.Coverage narrow=BluetoothSignalAnalyzer.coverage(2_402_000_000L,250_000);
		assertTrue(narrow.bleChannels.startsWith("37 "),"narrow BLE coverage wrong: "+narrow.bleChannels);
		assertTrue(narrow.classicChannels.startsWith("0 "),"narrow Classic coverage wrong: "+narrow.classicChannels);
		BluetoothSignalAnalyzer.Coverage wide=BluetoothSignalAnalyzer.coverage(2_412_000_000L,20_000_000);
		assertTrue(wide.bleChannels.startsWith("37, 0-9 "),"wide BLE coverage wrong: "+wide.bleChannels);
		assertTrue(wide.classicChannels.startsWith("0-20 "),"wide Classic coverage wrong: "+wide.classicChannels);
	}

	private static void testBluetoothSigSamplePacket() {
		byte[] iq=gfsk(OFFICIAL_CHANNEL_38_PACKET,4_000_000,0d,250_000d,64,64);
		BluetoothSignalAnalyzer analyzer=new BluetoothSignalAnalyzer();
		BluetoothSignalAnalyzer.Result result=analyzer.analyze(2_426_000_000L,iq,iq.length,4_000_000,123456L);
		List<BluetoothLePacket> packets=analyzer.takeObservations();
		assertTrue(result.decodedPackets==1,"official packet was not decoded: "+result.state);
		assertTrue("BLE packet".equals(SignalQualityTesterFrame.bluetoothShortStatus(result,false)),
				"BLE packet status is misleading: "+result.state);
		assertTrue(packets.size()==1,"expected one de-duplicated packet, got "+packets.size());
		BluetoothLePacket packet=packets.get(0);
		assertTrue("ADV_NONCONN_IND".equals(packet.pduType),"wrong PDU type "+packet.pduType);
		assertTrue("C1:A2:A3:A4:A5:A6".equals(packet.address),"wrong address "+packet.address);
		assertTrue("Random static".equals(packet.addressType),"wrong random-address class "+packet.addressType);
		assertTrue(packet.channel==38,"wrong channel");
	}

	private static void testWidebandOffsetChannel() {
		byte[] iq=gfsk(OFFICIAL_CHANNEL_38_PACKET,20_000_000,6_000_000d,250_000d,96,96);
		BluetoothSignalAnalyzer analyzer=new BluetoothSignalAnalyzer();
		BluetoothSignalAnalyzer.Result result=analyzer.analyze(2_420_000_000L,iq,iq.length,20_000_000,2L);
		assertTrue(result.decodedPackets==1,"20 MHz offset channel was not decoded: "+result.state);
		assertTrue(analyzer.takeObservations().get(0).channel==38,"wideband decoder selected wrong channel");
	}

	private static void testCentered1250kCapture() {
		byte[] iq=gfsk(OFFICIAL_CHANNEL_38_PACKET,1_250_000,0d,250_000d,96,96);
		BluetoothSignalAnalyzer analyzer=new BluetoothSignalAnalyzer();
		BluetoothSignalAnalyzer.Result result=analyzer.analyze(2_426_000_000L,iq,iq.length,1_250_000,6L);
		assertTrue(result.decodedPackets==1,"centered 1.25 MS/s BLE packet was not decoded: "+result.state);
	}

	private static void testPacketAcrossIqBlocks() {
		byte[] iq=gfsk(OFFICIAL_CHANNEL_38_PACKET,4_000_000,0d,250_000d,32,32);
		int split=(iq.length/2)&~1;byte[] first=new byte[split],second=new byte[iq.length-split];
		System.arraycopy(iq,0,first,0,first.length);System.arraycopy(iq,split,second,0,second.length);
		BluetoothSignalAnalyzer analyzer=new BluetoothSignalAnalyzer();
		analyzer.analyze(2_426_000_000L,first,first.length,4_000_000,3L);
		BluetoothSignalAnalyzer.Result result=analyzer.analyze(2_426_000_000L,second,second.length,4_000_000,4L);
		assertTrue(result.decodedPackets==1,"packet split across I/Q blocks was lost");
		assertTrue(analyzer.takeObservations().size()==1,"split packet was duplicated");
	}

	private static void testNarrowbandActivityMode() {
		int rate=250_000,samples=rate/10;byte[] iq=new byte[samples*2];double phase=0d;
		for(int n=0;n<samples;n++){int position=n%(rate/100);boolean burst=position>=rate/250&&position<rate/250+rate/2000;if(burst){phase+=2d*Math.PI*25_000d/rate;iq[n*2]=(byte)Math.round(80d*Math.cos(phase));iq[n*2+1]=(byte)Math.round(80d*Math.sin(phase));}}
		BluetoothSignalAnalyzer analyzer=new BluetoothSignalAnalyzer();
		BluetoothSignalAnalyzer.Result result=analyzer.analyze(2_402_000_000L,iq,iq.length,rate,5L);
		assertTrue(result.decodedPackets==0,"narrowband mode must not claim packet decoding");
		assertTrue(result.activity.bursts>=8,"narrowband bursts were not detected: "+result.activity.bursts
				+", "+result.activity.state);
		assertTrue(result.activity.legacyAdvertisingBursts>=8,"short BLE advertising-duration candidates missing: "
				+result.activity.legacyAdvertisingBursts+", range "+result.activity.minimumDurationMs+".."+result.activity.maximumDurationMs);
		assertTrue(result.state.contains("Narrowband"),"narrowband limitation missing from state");
	}

	private static void testAdvertisingDataParser() {
		byte[] payload={0x56,0x34,0x12,(byte)0xcd,(byte)0xab,0x00,
				0x02,0x01,0x06,
				0x07,0x09,'S','e','n','s','o','r',
				0x03,0x03,0x0d,0x18,
				0x05,(byte)0xff,0x4c,0x00,0x02,0x15};
		byte[] pdu=new byte[payload.length+2];pdu[0]=0;pdu[1]=(byte)payload.length;System.arraycopy(payload,0,pdu,2,payload.length);
		BluetoothLePacket packet=BluetoothLePacket.parse(37,2_402_000_000L,pdu,-31.5d,1200d,98d,1L);
		assertTrue(packet!=null,"parser rejected valid ADV_IND");
		assertTrue("00:AB:CD:12:34:56".equals(packet.address),"public address byte order");
		assertTrue("Sensor".equals(packet.localName),"local name missing");
		assertTrue("Heart-rate sensor".equals(packet.deviceType),"service device type missing: "+packet.deviceType);
		assertTrue(packet.services.contains("Heart Rate"),"16-bit service UUID missing");
		assertTrue(packet.manufacturerData.startsWith("0x004C"),"manufacturer data missing");
		assertTrue("Apple, Inc.".equals(packet.manufacturer),"company ID lookup missing");
		assertTrue(packet.connectable&&packet.scannable,"ADV_IND properties missing");
		byte[] manufacturerOnlyPayload={1,2,3,4,5,6, 0x05,(byte)0xff,0x4c,0x00,0x02,0x15};
		byte[] manufacturerOnlyPdu=new byte[manufacturerOnlyPayload.length+2];manufacturerOnlyPdu[0]=0x42;
		manufacturerOnlyPdu[1]=(byte)manufacturerOnlyPayload.length;System.arraycopy(manufacturerOnlyPayload,0,
				manufacturerOnlyPdu,2,manufacturerOnlyPayload.length);
		BluetoothLePacket manufacturerOnly=BluetoothLePacket.parse(37,2_402_000_000L,manufacturerOnlyPdu,-31.5d,0d,98d,1L);
		assertTrue("Apple, Inc.".equals(manufacturerOnly.manufacturer),"manufacturer-only company missing");
		assertTrue("--".equals(manufacturerOnly.deviceType),"manufacturer name leaked into device type: "+manufacturerOnly.deviceType);
	}

	private static void testAppearanceParser() {
		byte[] payload={1,2,3,4,5,6, 0x03,0x19,0x01,0x0a};
		byte[] pdu=new byte[payload.length+2];pdu[0]=2;pdu[1]=(byte)payload.length;System.arraycopy(payload,0,pdu,2,payload.length);
		BluetoothLePacket packet=BluetoothLePacket.parse(37,2_402_000_000L,pdu,-20d,0d,100d,1L);
		assertTrue("Television".equals(packet.deviceType),"appearance device type was not shortened: "+packet.deviceType);
		assertTrue("Television (0x0A01)".equals(packet.appearance),"separate appearance evidence missing: "+packet.appearance);
	}

	private static void testClassOfDeviceParser(){
		byte[] payload={1,2,3,4,5,6, 0x04,0x0d,0x24,0x04,0x24};
		byte[] pdu=new byte[payload.length+2];pdu[0]=2;pdu[1]=(byte)payload.length;System.arraycopy(payload,0,pdu,2,payload.length);
		BluetoothLePacket packet=BluetoothLePacket.parse(37,2_402_000_000L,pdu,-20d,0d,100d,1L);
		assertTrue("0x240424".equals(packet.classOfDevice),"separate Class of Device value missing: "+packet.classOfDevice);
		assertTrue(packet.deviceType.contains("set-top box")&&!packet.deviceType.contains("Class of Device"),
				"Class of Device was not separated from device type: "+packet.deviceType);
	}

	private static void testAuxPointerParser() {
		byte[] pdu={0x07,0x05, 0x04,0x10, 0x05,0x0a,0x20};
		BluetoothLePacket packet=BluetoothLePacket.parse(37,2_402_000_000L,pdu,-20d,0d,100d,1L);
		assertTrue(packet.auxChannel==5,"AuxPtr channel missing: "+packet.auxChannel);
		assertTrue("LE 2M".equals(packet.auxPhy),"AuxPtr PHY missing: "+packet.auxPhy);
		assertTrue(packet.auxOffsetMicros==300d,"AuxPtr offset missing: "+packet.auxOffsetMicros);
	}

	private static void testCrcReference() {
		byte[] pdu={0x42,0x09,(byte)0xa6,(byte)0xa5,(byte)0xa4,(byte)0xa3,(byte)0xa2,(byte)0xc1,0x01,0x02,0x03};
		assertTrue(BluetoothSignalAnalyzer.Le1MDecoder.crc24(pdu)==0xb52dd7,"Bluetooth SIG CRC reference mismatch");
	}

	private static void testConnectIndParser() {
		byte[] pdu=new byte[36];pdu[0]=0x05;pdu[1]=34;
		byte[] payload={1,2,3,4,5,6, 10,11,12,13,14,15,
				0x44,0x33,0x22,0x11, (byte)0xa1,(byte)0xb2,(byte)0xc3,
				2, 3,0, 24,0, 4,0, 100,0,
				(byte)0xff,(byte)0xff,0x00,0x00,0x00, (byte)(5|(3<<5))};
		System.arraycopy(payload,0,pdu,2,payload.length);
		BluetoothLePacket packet=BluetoothLePacket.parse(37,2_402_000_000L,pdu,-20d,0d,100d,1L);
		assertTrue(packet.connectionInfo.contains("AA 0x11223344"),"connection access address missing");
		assertTrue(packet.connectionInfo.contains("interval 30.00 ms"),"connection interval offset wrong");
		assertTrue(packet.connectionInfo.contains("latency 4"),"connection latency offset wrong");
		assertTrue(packet.connectionInfo.contains("timeout 1000 ms"),"connection timeout offset wrong");
		assertTrue(packet.connectionInfo.contains("hop 5"),"hop increment offset wrong");
		assertTrue(packet.connectionData.accessAddress==0x11223344L,"structured connection access address missing");
		assertTrue(packet.connectionData.crcInit==0xc3b2a1,"structured connection CRCInit missing");
	}

	private static void testConnectedDataChannel() {
		byte[] connect=new byte[36];connect[0]=0x05;connect[1]=34;byte[] payload={1,2,3,4,5,6, 10,11,12,13,14,15,
				0x44,0x33,0x22,0x11, (byte)0xa1,(byte)0xb2,(byte)0xc3, 2,3,0,24,0,4,0,100,0,
				(byte)0xff,(byte)0xff,0,0,0,(byte)(5|(3<<5))};System.arraycopy(payload,0,connect,2,payload.length);
		BluetoothSignalAnalyzer analyzer=new BluetoothSignalAnalyzer();byte[] connectIq=gfsk(encodedAdvertisingPacket(connect,37,8),
				4_000_000,0d,250_000d,64,64);analyzer.analyze(2_402_000_000L,connectIq,connectIq.length,4_000_000,10L);
		byte[] data={0x03,0x06,0x0c,0x0b,0x4c,0x00,0x34,0x12};byte[] dataIq=gfsk(encodedDataPacket(data,5,
				0x11223344L,0xc3b2a1),4_000_000,0d,250_000d,64,64);
		BluetoothSignalAnalyzer.Result result=analyzer.analyze(BluetoothSignalAnalyzer.frequencyForChannel(5),dataIq,dataIq.length,4_000_000,11L);
		List<BluetoothLeDataPacket> packets=analyzer.takeDataObservations();assertTrue(result.connectionCount==1,"connection was not retained");
		assertTrue(packets.size()==1,"BLE data-channel packet was not decoded: "+packets.size());
		assertTrue(packets.get(0).packetType.contains("VERSION_IND"),"LL control type missing: "+packets.get(0).packetType);
		assertTrue(packets.get(0).details.contains("company 0x004C"),"LL VERSION_IND details missing");
		assertTrue(packets.get(0).connectionEvent==0&&packets.get(0).channelPredictionMatches,
				"CSA#1 connection event was not tracked: "+packets.get(0).trackingInfo);
	}

	private static void testLe2MDataChannel(){BluetoothLeConnectionTracker.Context context=contextWithPhy(2,4000L);
		byte[] pdu={0x02,0x05,0x01,0x00,0x04,0x00,0x0a};int channel=5;
		byte[] iq=gfsk(encodedDataPacket(pdu,channel,0x11223344L,0xc3b2a1,16),8_000_000,0d,500_000d,64,64,2_000_000d);
		float[] samples=floatIq(iq);BluetoothSignalAnalyzer.LeDataDecoder decoder=new BluetoothSignalAnalyzer.LeDataDecoder(2_000_000,16,"LE 2M");
		List<BluetoothLeDataPacket> packets=decoder.accept(samples,samples.length/2,8_000_000d,channel,
				BluetoothSignalAnalyzer.frequencyForChannel(channel),4031L,java.util.Collections.singletonList(context));
		assertTrue(packets.size()==1,"LE 2M data-channel packet was not decoded: "+packets.size());
		assertTrue("LE 2M".equals(packets.get(0).phy)&&packets.get(0).packetType.contains("ATT"),"LE 2M data metadata is wrong");
	}

	private static void testLeCodedDataChannel(){for(boolean s2:new boolean[]{false,true}){BluetoothLeConnectionTracker.Context context=contextWithPhy(3,s2?6000L:5000L);
		int channel=5;byte[] pdu={0x01,0x00};byte[] iq=gfsk(encodedCodedDataPacket(pdu,channel,0x11223344L,0xc3b2a1,s2),
				4_000_000,0d,250_000d,64,64);float[] samples=floatIq(iq);BluetoothSignalAnalyzer.LeCodedDataDecoder decoder=
				new BluetoothSignalAnalyzer.LeCodedDataDecoder();List<BluetoothLeDataPacket> packets=decoder.accept(samples,samples.length/2,
				4_000_000d,channel,BluetoothSignalAnalyzer.frequencyForChannel(channel),s2?6031L:5031L,
				java.util.Collections.singletonList(context));
		assertTrue(packets.size()==1,"LE Coded "+(s2?"S=2":"S=8")+" packet was not decoded: "+packets.size());
		assertTrue((s2?"LE Coded S=2":"LE Coded S=8").equals(packets.get(0).phy),"LE Coded PHY label is wrong");}}

	private static BluetoothLeConnectionTracker.Context contextWithPhy(int phy,long baseMillis){BluetoothLeConnectionTracker.Context context=
			testConnectionContext(baseMillis);BluetoothLeDataPacket update=dataPacket(new byte[]{0x03,0x05,0x18,(byte)phy,(byte)phy,0x01,0x00},5,baseMillis+1);
		context.observe(update);BluetoothLeDataPacket activate=dataPacket(new byte[]{0x01,0x00},10,baseMillis+31);context.observe(activate);
		assertTrue(context.usesPhy(phy==2?"LE 2M":"LE Coded"),"PHY update was not activated");return context;}

	private static void testChannelSelectionAlgorithms(){
		long allChannels=(1L<<37)-1L,accessAddress=0x8e89bed6L;
		int[] expected={25,20,6,21};for(int event=0;event<expected.length;event++)
			assertTrue(BluetoothLeConnectionTracker.csa2Channel(accessAddress,allChannels,event)==expected[event],
					"CSA#2 official all-channel vector mismatch at event "+event);
		long sparse=0L;for(int channel:new int[]{9,10,21,22,23,33,34,35,36})sparse|=1L<<channel;
		assertTrue(BluetoothLeConnectionTracker.csa2Channel(accessAddress,sparse,6)==23,"CSA#2 sparse event 6 mismatch");
		assertTrue(BluetoothLeConnectionTracker.csa2Channel(accessAddress,sparse,7)==9,"CSA#2 sparse event 7 mismatch");
		assertTrue(BluetoothLeConnectionTracker.csa2Channel(accessAddress,sparse,8)==34,"CSA#2 sparse event 8 mismatch");
		assertTrue(BluetoothLeConnectionTracker.csa1Channel(5,allChannels,0)==5,"CSA#1 first event mismatch");
		assertTrue(BluetoothLeConnectionTracker.csa1Channel(5,allChannels,1)==10,"CSA#1 second event mismatch");
	}

	private static void testChannelMapInstant(){
		byte[] connect=new byte[36];connect[0]=0x05;connect[1]=34;byte[] payload={1,2,3,4,5,6,10,11,12,13,14,15,
				0x44,0x33,0x22,0x11,(byte)0xa1,(byte)0xb2,(byte)0xc3,2,3,0,24,0,4,0,100,0,
				(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff,0x1f,(byte)(5|(3<<5))};
		System.arraycopy(payload,0,connect,2,payload.length);BluetoothLePacket connectPacket=BluetoothLePacket.parse(37,
				2_402_000_000L,connect,-20d,0d,100d,1000L);BluetoothLeConnectionTracker.Context context=
				new BluetoothLeConnectionTracker.Context(connectPacket);
		byte[] update={0x03,0x08,0x01,0x06,0,0,0,0,0x02,0};BluetoothLeDataPacket updatePacket=
				BluetoothLeDataPacket.parse(5,BluetoothSignalAnalyzer.frequencyForChannel(5),"LE 1M",0x11223344L,update,
						connectPacket.peerAddress,connectPacket.address,-20d,0d,100d,1001L);context.observe(updatePacket);
		assertTrue(updatePacket.connectionEvent==0&&updatePacket.channelPredictionMatches,"channel-map update event was not tracked");
		BluetoothLeDataPacket before=BluetoothLeDataPacket.parse(10,BluetoothSignalAnalyzer.frequencyForChannel(10),"LE 1M",
				0x11223344L,new byte[]{0x01,0},connectPacket.peerAddress,connectPacket.address,-20d,0d,100d,1031L);
		context.observe(before);assertTrue(before.connectionEvent==1&&before.channelPredictionMatches,"old map was not used before Instant");
		BluetoothLeDataPacket after=BluetoothLeDataPacket.parse(2,BluetoothSignalAnalyzer.frequencyForChannel(2),"LE 1M",
				0x11223344L,new byte[]{0x01,0},connectPacket.peerAddress,connectPacket.address,-20d,0d,100d,1061L);
		context.observe(after);assertTrue(after.connectionEvent==2&&after.channelPredictionMatches,
				"new map was not activated at Instant: "+after.trackingInfo);
		assertTrue(context.summary().contains("2 channels")&&!context.summary().contains("map update"),
				"applied channel-map state is wrong: "+context.summary());
		BluetoothLeDataPacket startEncryption=BluetoothLeDataPacket.parse(1,BluetoothSignalAnalyzer.frequencyForChannel(1),"LE 1M",
				0x11223344L,new byte[]{0x03,0x01,0x06},connectPacket.peerAddress,connectPacket.address,-20d,0d,100d,1091L);
		context.observe(startEncryption);assertTrue(startEncryption.trackingInfo.contains("encrypted"),"encryption start was not tracked");
		BluetoothLeDataPacket encrypted=BluetoothLeDataPacket.parse(2,BluetoothSignalAnalyzer.frequencyForChannel(2),"LE 1M",
				0x11223344L,new byte[]{0x02,0x02,0x55,0x66},connectPacket.peerAddress,connectPacket.address,-20d,0d,100d,1121L);
		context.observe(encrypted);assertTrue("Encrypted LL data".equals(encrypted.packetType),"encrypted payload was parsed as clear text");
	}

	private static void testL2capReassembly(){
		BluetoothLeConnectionTracker.Context context=testConnectionContext(1000L);
		byte[] start={0x02,0x06, 0x05,0x00,0x04,0x00,0x12,0x34};
		BluetoothLeDataPacket first=dataPacket(start,5,1001L);assertTrue(!context.observe(first),"partial L2CAP start was reported complete");
		assertTrue(first.l2capStatus.contains("6/9 bytes"),"L2CAP fragment progress missing: "+first.l2capStatus);
		BluetoothLeDataPacket peripheral=dataPacket(new byte[]{0x01,0x00},5,1001L);context.observe(peripheral);
		BluetoothLeDataPacket retransmission=dataPacket(start,5,1001L);assertTrue(!context.observe(retransmission),"retransmission completed L2CAP");
		assertTrue(retransmission.retransmission,"same-SN retransmission was not suppressed");
		BluetoothLeDataPacket continuation=dataPacket(new byte[]{0x09,0x03,0x56,0x78,(byte)0x9a},10,1031L);
		assertTrue(context.observe(continuation),"fragmented L2CAP PDU was not completed");
		assertTrue(continuation.l2capCid==4&&continuation.l2capLength==5&&continuation.l2capFragments==2,
				"reassembled ATT metadata is wrong: "+continuation.details);
		assertTrue(continuation.l2capPdu.length==9&&"L2CAP ATT".equals(continuation.packetType)
				&&continuation.details.contains("ATT Write Request"),"reassembled ATT payload was not classified: "+continuation.details);

		BluetoothLeConnectionTracker.Context directions=testConnectionContext(2000L);
		BluetoothLeDataPacket centralStart=dataPacket(new byte[]{0x02,0x05, 0x02,0,0x04,0,0x0a},5,2001L);
		assertTrue(!directions.observe(centralStart),"central partial PDU was reported complete");
		BluetoothLeDataPacket peripheralComplete=dataPacket(new byte[]{0x02,0x05, 0x01,0,0x06,0,0x01},5,2001L);
		assertTrue(directions.observe(peripheralComplete),"peripheral complete PDU was mixed with central fragments");
		assertTrue(peripheralComplete.direction.startsWith("Peripheral"),"inferred link direction missing");
		BluetoothLeConnectionTracker.Context orphanContext=testConnectionContext(3000L);
		BluetoothLeDataPacket orphan=dataPacket(new byte[]{0x01,0x01,0x55},5,3001L);orphanContext.observe(orphan);
		assertTrue(orphan.l2capStatus.contains("Orphan"),"orphan continuation was not identified");
	}

	private static void testGattIdentification(){
		BluetoothGattIdentifier identifier=new BluetoothGattIdentifier();
		identifier.observe(attPacket(true,new byte[]{0x10,0x01,0x00,(byte)0xff,(byte)0xff,0x00,0x28}));
		List<BluetoothGattIdentifier.Identity> service=identifier.observe(attPacket(false,
				new byte[]{0x11,0x06,0x01,0x00,0x08,0x00,0x12,0x18}));
		assertTrue(service.size()==1&&service.get(0).services.contains("Human Interface Device")
				&&"Human interface / remote".equals(service.get(0).deviceType),"GATT service identity missing");
		identifier.observe(attPacket(true,new byte[]{0x08,0x01,0x00,(byte)0xff,(byte)0xff,0x03,0x28}));
		identifier.observe(attPacket(false,new byte[]{0x09,0x07,0x02,0x00,0x02,0x03,0x00,0x00,0x2a}));
		identifier.observe(attPacket(true,new byte[]{0x0a,0x03,0x00}));
		List<BluetoothGattIdentifier.Identity> named=identifier.observe(attPacket(false,
				new byte[]{0x0b,'T','V',' ','R','e','m','o','t','e'}));
		assertTrue(named.size()==1&&"TV Remote".equals(named.get(0).name)&&"High".equals(named.get(0).confidence)
				&&named.get(0).evidence.contains("GATT name"),"GATT Device Name correlation missing");
		identifier.observe(attPacket(true,new byte[]{0x08,0x01,0x00,(byte)0xff,(byte)0xff,0x24,0x2a}));
		List<BluetoothGattIdentifier.Identity> model=identifier.observe(attPacket(false,
				new byte[]{0x09,0x08,0x04,0x00,'R','C','-','1','0','0'}));
		assertTrue(model.size()==1&&model.get(0).model.contains("RC-100"),"GATT model number missing");
		BluetoothLeDataPacket version=dataPacket(new byte[]{0x03,0x06,0x0c,0x0d,0x4c,0x00,0x34,0x12},5,4000L);
		version.direction="Peripheral -> Central (inferred)";List<BluetoothGattIdentifier.Identity> controller=identifier.observe(version);
		assertTrue(controller.size()==1&&controller.get(0).evidence.contains("Apple, Inc.")
				&&controller.get(0).details.contains("subversion 0x1234"),"LL_VERSION_IND identity missing");
	}

	private static void testBluetoothVersionLabels(){
		assertTrue("Bluetooth 5.0".equals(BluetoothAssignedNumbers.bluetoothVersion(0x09)),"Bluetooth 5.0 version label missing");
		assertTrue("Bluetooth 6.1".equals(BluetoothAssignedNumbers.bluetoothVersion(0x0f)),"Bluetooth 6.1 version label missing");
		BluetoothGattIdentifier identifier=new BluetoothGattIdentifier();BluetoothLeDataPacket packet=dataPacket(
				new byte[]{0x03,0x06,0x0c,0x09,0x4c,0x00,0x34,0x12},5,4100L);
		packet.direction="Peripheral -> Central (inferred)";List<BluetoothGattIdentifier.Identity> identities=identifier.observe(packet);
		assertTrue(identities.size()==1&&"Bluetooth 5.0".equals(identities.get(0).bluetoothVersion),"LL version label was not retained");
		BluetoothDeviceTablePanel panel=new BluetoothDeviceTablePanel();
		assertTrue(panel.viewColumnForTest("Name")==0&&panel.viewColumnForTest("PHY")==1&&panel.viewColumnForTest("Company / ID")==2
				&&panel.viewColumnForTest("ID")==3&&panel.viewColumnForTest("Model / version")==4
				&&panel.viewColumnForTest("RSSI")==5&&panel.viewColumnForTest("BT version")==6,
				"Bluetooth default column order is incorrect");
		panel.sortByColumnsForTest("RSSI",SortOrder.DESCENDING,"Name",SortOrder.ASCENDING);
		assertTrue(panel.headerTextForTest("RSSI").endsWith("\u25BC1")&&panel.headerTextForTest("Name").endsWith("\u25B22"),
				"Bluetooth sort headers do not show direction and priority");
		panel.rightClickHeaderForTest("Name");
		assertTrue(panel.sortKeyCountForTest()==1&&"Name".equals(panel.headerTextForTest("Name"))&&panel.headerTextForTest("RSSI").endsWith("\u25BC"),
				"Right-click did not remove only the selected sort key");
		panel.rightClickHeaderForTest("Company / ID");
		assertTrue(panel.sortKeyCountForTest()==0&&"RSSI".equals(panel.headerTextForTest("RSSI")),
				"Right-click on an unsorted header did not clear sorting");
	}

	private static BluetoothLeDataPacket attPacket(boolean fromCentral,byte[] att){
		byte[] ll=new byte[6+att.length];ll[0]=0x02;ll[1]=(byte)(4+att.length);ll[2]=(byte)att.length;ll[3]=0;ll[4]=0x04;ll[5]=0;
		System.arraycopy(att,0,ll,6,att.length);BluetoothLeDataPacket packet=dataPacket(ll,5,3500L);
		packet.direction=fromCentral?"Central -> Peripheral (inferred)":"Peripheral -> Central (inferred)";
		packet.l2capCid=4;packet.l2capLength=att.length;packet.l2capPdu=new byte[4+att.length];
		System.arraycopy(ll,2,packet.l2capPdu,0,packet.l2capPdu.length);return packet;
	}

	private static BluetoothLeConnectionTracker.Context testConnectionContext(long decodedMillis){
		byte[] connect=new byte[36];connect[0]=0x05;connect[1]=34;byte[] payload={1,2,3,4,5,6,10,11,12,13,14,15,
				0x44,0x33,0x22,0x11,(byte)0xa1,(byte)0xb2,(byte)0xc3,2,3,0,24,0,4,0,100,0,
				(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff,0x1f,(byte)(5|(3<<5))};
		System.arraycopy(payload,0,connect,2,payload.length);return new BluetoothLeConnectionTracker.Context(BluetoothLePacket.parse(37,
				2_402_000_000L,connect,-20d,0d,100d,decodedMillis));
	}
	private static BluetoothLeDataPacket dataPacket(byte[] pdu,int channel,long decodedMillis){return BluetoothLeDataPacket.parse(channel,
			BluetoothSignalAnalyzer.frequencyForChannel(channel),"LE 1M",0x11223344L,pdu,"06:05:04:03:02:01",
			"0F:0E:0D:0C:0B:0A",-20d,0d,100d,decodedMillis);}

	private static void testLastSeenUsesArrivalTime() {
		byte[] pdu={0x00,0x06,1,2,3,4,5,6};BluetoothLePacket packet=BluetoothLePacket.parse(37,2_402_000_000L,
				pdu,-20d,0d,100d,1L);BluetoothDeviceTablePanel panel=new BluetoothDeviceTablePanel();long before=System.currentTimeMillis();
		panel.observe(packet);long lastSeen=panel.lastSeenForTest("06:05:04:03:02:01");
		assertTrue(lastSeen>=before&&lastSeen<=System.currentTimeMillis(),"Bluetooth Last seen used capture time instead of arrival time");
	}

	private static void testSelectedDeviceNotification() {
		byte[] pdu={0x00,0x06,1,2,3,4,5,6};BluetoothLePacket packet=BluetoothLePacket.parse(37,2_402_000_000L,
				pdu,-20d,0d,100d,1L);BluetoothDeviceTablePanel panel=new BluetoothDeviceTablePanel();panel.setAudibleNotificationsForTest(false);
		panel.observe(packet);panel.refresh();panel.selectAddressForTest("06:05:04:03:02:01");panel.observe(packet);
		assertTrue(panel.notificationCountForTest()==0L,"ordinary Bluetooth selection incorrectly enabled chime tracking");
		panel.rightClickAddressForTest("06:05:04:03:02:01");panel.observe(packet);
		assertTrue(panel.notificationCountForTest()==1L&&panel.addressTrackedForTest("06:05:04:03:02:01"),"right-click did not enable Bluetooth chime tracking");
		panel.rightClickAddressForTest("06:05:04:03:02:01");panel.observe(packet);
		assertTrue(panel.notificationCountForTest()==1L&&!panel.addressTrackedForTest("06:05:04:03:02:01"),"second right-click did not disable Bluetooth chime tracking");
	}

	private static void testTableLayoutPersistence(){
		try{File settings=File.createTempFile("hackrf-table-layout-",".ini");settings.deleteOnExit();
			WifiNetworkTablePanel wifi=new WifiNetworkTablePanel(settings);wifi.moveColumnForTest("RSSI",0);
			wifi.setColumnWidthForTest("RSSI",177);wifi.sortByColumnForTest("Country",SortOrder.ASCENDING);wifi.saveTableLayoutForTest();
			BluetoothDeviceTablePanel bluetooth=new BluetoothDeviceTablePanel(settings);bluetooth.moveColumnForTest("Address",0);
			bluetooth.setColumnWidthForTest("Address",188);bluetooth.sortByColumnForTest("Name",SortOrder.ASCENDING);bluetooth.saveTableLayoutForTest();
			WifiNetworkTablePanel restoredWifi=new WifiNetworkTablePanel(settings);restoredWifi.restoreTableLayoutForTest();
			assertTrue(restoredWifi.viewColumnForTest("RSSI")==0,"Wi-Fi column order was not restored");
			assertTrue(restoredWifi.columnWidthForTest("RSSI")==177,"Wi-Fi column width was not restored");
			assertTrue("Country".equals(restoredWifi.primarySortColumnForTest())&&restoredWifi.primarySortOrderForTest()==SortOrder.ASCENDING,
					"Wi-Fi sorting was not restored");
			BluetoothDeviceTablePanel restoredBluetooth=new BluetoothDeviceTablePanel(settings);restoredBluetooth.restoreTableLayoutForTest();
			assertTrue(restoredBluetooth.viewColumnForTest("Address")==0,"Bluetooth column order was not restored");
			assertTrue(restoredBluetooth.columnWidthForTest("Address")==188,"Bluetooth column width was not restored");
			assertTrue("Name".equals(restoredBluetooth.primarySortColumnForTest())&&restoredBluetooth.primarySortOrderForTest()==SortOrder.ASCENDING,
					"Bluetooth sorting was not restored");
		}catch(java.io.IOException error){throw new AssertionError("Table layout test setup failed",error);}
	}

	private static byte[] gfsk(String packetBits,int rate,double carrierOffset,double deviation,int prefixBits,int suffixBits) {
		return gfsk(packetBits,rate,carrierOffset,deviation,prefixBits,suffixBits,1_000_000d);
	}

	private static byte[] gfsk(String packetBits,int rate,double carrierOffset,double deviation,int prefixBits,int suffixBits,double symbolRate) {
		StringBuilder bits=new StringBuilder();for(int n=0;n<prefixBits;n++)bits.append('0');bits.append(packetBits);for(int n=0;n<suffixBits;n++)bits.append('0');
		double samplesPerBit=rate/symbolRate;int total=(int)Math.round(bits.length()*samplesPerBit);byte[] iq=new byte[total*2];double phase=0,carrierStep=2d*Math.PI*carrierOffset/rate,step=2d*Math.PI*deviation/rate;int out=0;
		for(int sample=0;sample<total;sample++){int bit=Math.min(bits.length()-1,(int)Math.floor(sample/samplesPerBit));double direction=bits.charAt(bit)=='1'?1d:-1d;phase+=carrierStep+direction*step;iq[out++]=(byte)Math.round(82d*Math.cos(phase));iq[out++]=(byte)Math.round(82d*Math.sin(phase));}
		return iq;
	}

	private static String encodedAdvertisingPacket(byte[] pdu,int channel,int preambleBits) {
		StringBuilder bits=new StringBuilder();for(int n=0;n<preambleBits;n++)bits.append((char)('0'+(n&1)));
		int[] access={0xd6,0xbe,0x89,0x8e};for(int value:access)appendByteLsb(bits,value);
		StringBuilder clear=new StringBuilder();for(byte value:pdu)appendByteLsb(clear,value&0xff);
		int crc=BluetoothSignalAnalyzer.Le1MDecoder.crc24(pdu);for(int n=23;n>=0;n--)clear.append((char)('0'+((crc>>>n)&1)));
		int lfsr=(channel&0x3f)|0x40;for(int n=0;n<clear.length();n++){int whitening=lfsr&1;
			bits.append((char)('0'+((clear.charAt(n)-'0')^whitening)));lfsr>>>=1;if(whitening!=0)lfsr^=0x44;}
		return bits.toString();
	}
	private static String classicInquiryFhs(int x,int lap,int uap,int nap,int deviceClass,boolean eir,int sr,int clock) {
		StringBuilder out=new StringBuilder(bitString(BluetoothClassicAnalyzer.BrAccessDecoder.accessBits(0x9e8b33,true)));
		int[] clearHeader=new int[18];int header=2<<3;for(int n=0;n<10;n++)clearHeader[n]=(header>>>n)&1;
		int hec=BluetoothClassicAnalyzer.BrAccessDecoder.hec8(header,0);for(int n=0;n<8;n++)clearHeader[10+n]=(hec>>>n)&1;
		int[] whitening=BluetoothClassicAnalyzer.BrAccessDecoder.whiteningBits(0x60|(x&31),178);int whiteningAt=0;
		for(int bit:clearHeader){int encoded=bit^whitening[whiteningAt++];out.append(encoded).append(encoded).append(encoded);}
		int[] payload=new int[160];putLsb(payload,34,24,lap);payload[58]=eir?1:0;putLsb(payload,60,2,sr);
		putLsb(payload,62,2,2);putLsb(payload,64,8,uap);putLsb(payload,72,16,nap);putLsb(payload,88,24,deviceClass);
		putLsb(payload,115,26,clock);int crc=BluetoothClassicAnalyzer.BrAccessDecoder.crc16(payload,0,144,0);putLsb(payload,144,16,crc);
		for(int block=0;block<16;block++){int info=0;for(int n=0;n<10;n++)info|=(payload[block*10+n]^whitening[whiteningAt++])<<n;
			int word=BluetoothClassicAnalyzer.BrAccessDecoder.fecCodeword(info);for(int n=0;n<15;n++)out.append((char)('0'+((word>>>n)&1)));}
		return out.toString();
	}
	private static String classicBrAclPacket(int lap,int uap,int x,int header,int llid,boolean flow,byte[] body,boolean fec){
		StringBuilder out=new StringBuilder(bitString(BluetoothClassicAnalyzer.BrAccessDecoder.accessBits(lap,true)));
		int payloadHeader=(llid&3)|(flow?4:0)|((body.length&31)<<3),protectedBits=(1+body.length)*8;
		int[] payload=new int[protectedBits+16];putLsb(payload,0,8,payloadHeader);
		for(int b=0;b<body.length;b++)putLsb(payload,8+b*8,8,body[b]&0xff);
		putLsb(payload,protectedBits,16,BluetoothClassicAnalyzer.BrAccessDecoder.crc16(payload,0,protectedBits,uap));
		int[] clearHeader=new int[18];for(int n=0;n<10;n++)clearHeader[n]=(header>>>n)&1;
		int hec=BluetoothClassicAnalyzer.BrAccessDecoder.hec8(header,uap);for(int n=0;n<8;n++)clearHeader[10+n]=(hec>>>n)&1;
		int whitenedPayloadBits=fec?((payload.length+9)/10)*10:payload.length;
		int[] whitening=BluetoothClassicAnalyzer.BrAccessDecoder.whiteningBits(0x60|(x&31),18+whitenedPayloadBits);
		for(int n=0;n<18;n++){int bit=clearHeader[n]^whitening[n];out.append(bit).append(bit).append(bit);}
		if(fec){int blocks=(payload.length+9)/10;for(int block=0;block<blocks;block++){int info=0;for(int n=0;n<10;n++){
			int at=block*10+n,bit=(at<payload.length?payload[at]:0)^whitening[18+at];info|=bit<<n;}
			int word=BluetoothClassicAnalyzer.BrAccessDecoder.fecCodeword(info);for(int n=0;n<15;n++)out.append((char)('0'+((word>>>n)&1)));}}
		else for(int n=0;n<payload.length;n++)out.append(payload[n]^whitening[18+n]);return out.toString();
	}
	private static String encodedDataPacket(byte[] pdu,int channel,long accessAddress,int crcInit){return encodedDataPacket(pdu,channel,accessAddress,crcInit,8);}
	private static String encodedDataPacket(byte[] pdu,int channel,long accessAddress,int crcInit,int preambleBits){StringBuilder bits=new StringBuilder();
		int first=(int)(accessAddress&1L);for(int n=0;n<preambleBits;n++)bits.append((char)('0'+(first^(n&1))));
		for(int n=0;n<32;n++)bits.append((char)('0'+((accessAddress>>>n)&1L)));StringBuilder clear=new StringBuilder();
		for(byte value:pdu)appendByteLsb(clear,value&0xff);int crc=crc24(pdu,crcInit);for(int n=23;n>=0;n--)clear.append((char)('0'+((crc>>>n)&1)));
		int lfsr=(channel&0x3f)|0x40;for(int n=0;n<clear.length();n++){int whitening=lfsr&1;
			bits.append((char)('0'+((clear.charAt(n)-'0')^whitening)));lfsr>>>=1;if(whitening!=0)lfsr^=0x44;}return bits.toString();}
	private static String encodedCodedDataPacket(byte[] pdu,int channel,long accessAddress,int crcInit,boolean s2){StringBuilder out=new StringBuilder();
		for(int n=0;n<10;n++)out.append("00111100");StringBuilder block1=new StringBuilder();
		for(int n=0;n<32;n++)block1.append((char)('0'+((accessAddress>>>n)&1L)));block1.append(s2?'1':'0').append('0').append("000");
		appendCoded(out,block1,4);StringBuilder clear=new StringBuilder();for(byte value:pdu)appendByteLsb(clear,value&0xff);
		int crc=crc24(pdu,crcInit);for(int n=23;n>=0;n--)clear.append((char)('0'+((crc>>>n)&1)));int lfsr=(channel&0x3f)|0x40;
		StringBuilder block2=new StringBuilder();for(int n=0;n<clear.length();n++){int whitening=lfsr&1;block2.append((char)('0'+((clear.charAt(n)-'0')^whitening)));
			lfsr>>>=1;if(whitening!=0)lfsr^=0x44;}block2.append("000");appendCoded(out,block2,s2?1:4);return out.toString();}
	private static void appendCoded(StringBuilder out,CharSequence input,int pattern){int state=0;for(int n=0;n<input.length();n++){
		int bit=input.charAt(n)-'0',g0=bit^((state>>>2)&1)^((state>>>1)&1)^(state&1),g1=bit^((state>>>1)&1)^(state&1);
		appendPattern(out,g0,pattern);appendPattern(out,g1,pattern);state=(state>>>1)|(bit<<2);}}
	private static void appendPattern(StringBuilder out,int bit,int pattern){if(pattern==1)out.append((char)('0'+bit));else out.append(bit==0?"0011":"1100");}
	private static int crc24(byte[] pdu,int init){int state=init&0xffffff;for(byte value:pdu)for(int bit=0;bit<8;bit++){
		int feedback=((state>>>23)&1)^((value>>>bit)&1);state=state<<1&0xffffff;if(feedback!=0)state^=0x00065b;}return state;}
	private static void putLsb(int[] bits,int at,int count,int value){for(int n=0;n<count;n++)bits[at+n]=(value>>>n)&1;}
	private static int bits(String value){int out=0;for(int n=0;n<value.length();n++)out|=(value.charAt(n)-'0')<<n;return out;}
	private static int[] intBits(String value){int[] out=new int[value.length()];for(int n=0;n<out.length;n++)out[n]=value.charAt(n)-'0';return out;}
	private static void appendByteLsb(StringBuilder out,int value){for(int n=0;n<8;n++)out.append((char)('0'+((value>>>n)&1)));}
	private static String bitString(byte[] bits){StringBuilder out=new StringBuilder();for(byte bit:bits)out.append((char)('0'+(bit&1)));return out.toString();}
	private static String bitString(int[] bits){StringBuilder out=new StringBuilder();for(int bit:bits)out.append((char)('0'+(bit&1)));return out.toString();}
	private static float[] floatIq(byte[] iq){float[] out=new float[iq.length];for(int n=0;n<iq.length;n++)out[n]=iq[n]/128f;return out;}
	private static String hexBits(String hex){StringBuilder out=new StringBuilder();for(int n=0;n<hex.length();n++){int value=Character.digit(hex.charAt(n),16);for(int bit=3;bit>=0;bit--)out.append((char)('0'+((value>>>bit)&1)));}return out.toString();}

	private static void assertTrue(boolean value,String message){if(!value)throw new AssertionError(message);}
}
