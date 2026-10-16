package jspectrumanalyzer.iq;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.LinkedHashSet;

/** Command-line diagnostic for Bluetooth IQ WAV/RAW recordings. */
public final class BluetoothProbe {
	public static void main(String[] args) throws Exception {
		if(args.length==0){System.err.println("Usage: BluetoothProbe <IQ.wav> [...]");System.exit(2);}
		for(String value:args)probe(new File(value));
	}

	private static void probe(File file)throws Exception{
		try(IQReplayFile replay=IQReplayFile.open(file)){
			BluetoothSignalAnalyzer analyzer=new BluetoothSignalAnalyzer();int rate=replay.getSampleRateHz();
			int blockBytes=Math.max(32_768,Math.min(524_288,rate/20*2))&~1;
			byte[] iq=new byte[blockBytes];long duration=replay.getDurationMillis(),processed=0;
			long started=System.nanoTime();
			while(processed<duration){int read=replay.readLoopedSigned(iq);if(read<=0)break;
				analyzer.analyze(replay.getCenterFrequencyHz(),iq,read,rate,System.currentTimeMillis());
				processed+=Math.round(read/2d/rate*1000d);if(processed>=duration)break;}
			List<BluetoothLePacket> packets=analyzer.takeObservations();List<BluetoothLeDataPacket> dataPackets=analyzer.takeDataObservations();
			BluetoothSignalAnalyzer.Result result=analyzer.latest();
			System.out.println(String.format(Locale.US,"%s: %.3f MS/s, %.3f s, %s, advertising %d, data %d, CPU %.3f s",
					file.getName(),rate/1e6,duration/1000d,result.state,packets.size(),dataPackets.size(),(System.nanoTime()-started)/1e9));
			System.out.println("  coverage: IQ "+result.coverage.iqRange+", BLE "+result.coverage.bleChannels
					+", Classic "+result.coverage.classicChannels);
			System.out.println("  Classic: "+result.classicAccessCodes+" BCH-valid access codes, "+result.classicHeaders
					+" HEC-valid headers, "+result.classicPayloads+" CRC-valid payloads, "+result.classicFhsPackets+" FHS identities, "+result.classicState
					+(result.latestClassicPacket==null?"":"; latest "+result.latestClassicPacket.packetType+" "
							+result.latestClassicPacket.identityText()+" ch "+result.latestClassicPacket.channel
							+(result.latestClassicPacket.fhsValid?" "+result.latestClassicPacket.deviceType:"")));
			System.out.println("  LE links: "+result.connectionCount+" CONNECT_IND contexts, "+result.decodedDataPackets
					+" CRC-valid data-channel packets, "+result.reassembledL2capPackets+" complete L2CAP PDUs");
			BluetoothNarrowbandDetector.Result a=result.activity;
			if(rate<BluetoothSignalAnalyzer.PACKET_MIN_RATE_HZ)System.out.println(String.format(Locale.US,
					"  activity: bursts %d (%d broad BLE-duration, %d LE 1M adv-duration candidates), %.2f/s, duty %.2f%%, duration median %.3f ms [%.3f..%.3f], event interval median %.3f ms, noise %.1f dBFS, peak %.1f dBFS",
					a.bursts,a.packetLikeBursts,a.legacyAdvertisingBursts,a.burstsPerSecond,a.dutyCyclePercent,a.medianDurationMs,
					a.minimumDurationMs,a.maximumDurationMs,a.medianIntervalMs,a.noiseDbfs,a.peakDbfs));
			Map<String,Integer> types=new TreeMap<String,Integer>();Set<String> addresses=new LinkedHashSet<String>();
			Set<String> advertisers=new LinkedHashSet<String>(),scanners=new LinkedHashSet<String>(),deviceTypes=new LinkedHashSet<String>();
			Set<String> names=new LinkedHashSet<String>(),services=new LinkedHashSet<String>(),companies=new LinkedHashSet<String>();
			Map<String,Integer> adTypes=new TreeMap<String,Integer>();
			for(BluetoothLePacket p:packets){Integer count=types.get(p.pduType);types.put(p.pduType,count==null?1:count+1);
				if(!"--".equals(p.address))addresses.add(p.address);if(!"--".equals(p.peerAddress))addresses.add(p.peerAddress);
				if("Advertiser".equals(p.role))advertisers.add(p.address);if("Advertiser".equals(p.peerRole))advertisers.add(p.peerAddress);
				if("Scanner".equals(p.role))scanners.add(p.address);if(!"--".equals(p.deviceType))deviceTypes.add(p.deviceType);
				if(!"--".equals(p.localName))names.add(p.localName);if(!"--".equals(p.services))services.add(p.services);
				if(!"--".equals(p.manufacturer))companies.add(p.manufacturer);collectAdTypes(p,adTypes);}
			if(!packets.isEmpty())System.out.println("  types "+types+", unique addresses "+addresses.size()+
					" (advertisers "+advertisers.size()+", scanners "+scanners.size()+"), names "+names+
					", device types "+deviceTypes+", companies "+companies+", services "+services+", AD types "+adTypes);
			for(int n=0;n<Math.min(12,packets.size());n++){BluetoothLePacket p=packets.get(n);
				System.out.println(String.format(Locale.US,"  ch %d %-18s %-17s %-20s %.1f dBFS %s",
						p.channel,p.pduType,p.address,p.localName,p.levelDbfs,p.services));}
		}
	}

	private static void collectAdTypes(BluetoothLePacket packet,Map<String,Integer> counts){int type=packet.pdu[0]&15;
		if(type!=0&&type!=2&&type!=4&&type!=6)return;int at=8,end=Math.min(packet.pdu.length,2+packet.payloadLength);
		while(at<end){int size=packet.pdu[at]&0xff;if(size<1||at+size+1>end)break;String key=String.format(Locale.US,"0x%02X",packet.pdu[at+1]&0xff);
			Integer count=counts.get(key);counts.put(key,count==null?1:count+1);at+=size+1;}}
}
