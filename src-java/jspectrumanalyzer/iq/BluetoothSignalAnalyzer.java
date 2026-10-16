package jspectrumanalyzer.iq;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Streaming BLE advertising/data and Classic BR/EDR analyzer for the current IQ passband. */
final class BluetoothSignalAnalyzer {
	static final long BAND_LOW_HZ=2_402_000_000L,BAND_HIGH_HZ=2_480_000_000L;
	private static final int OUTPUT_RATE_HZ=4_000_000;
	static final int PACKET_MIN_RATE_HZ=1_100_000;
	private static final int OCCUPIED_BANDWIDTH_HZ=1_400_000;
	private static final long[] PRIMARY_FREQUENCIES={2_402_000_000L,2_426_000_000L,2_480_000_000L};
	private static final int[] PRIMARY_CHANNELS={37,38,39};
	private static final int[] BLE_FREQUENCY_ORDER={37,0,1,2,3,4,5,6,7,8,9,10,38,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35,36,39};

	private final List<ChannelState> channels=new ArrayList<ChannelState>();
	private final List<DataChannelState> dataChannels=new ArrayList<DataChannelState>();
	private final Map<Long,BluetoothLeConnectionTracker.Context> connections=new java.util.LinkedHashMap<Long,BluetoothLeConnectionTracker.Context>();
	private final BluetoothNarrowbandDetector narrowbandDetector=new BluetoothNarrowbandDetector();
	private final BluetoothClassicAnalyzer classicAnalyzer=new BluetoothClassicAnalyzer();
	private final ArrayDeque<BluetoothLePacket> observations=new ArrayDeque<BluetoothLePacket>();
	private final ArrayDeque<BluetoothClassicPacket> classicObservations=new ArrayDeque<BluetoothClassicPacket>();
	private final ArrayDeque<BluetoothLeDataPacket> dataObservations=new ArrayDeque<BluetoothLeDataPacket>();
	private long configuredCenterHz=Long.MIN_VALUE,decodedPackets,decodedDataPackets,reassembledL2capPackets,lastDecodedMillis;
	private BluetoothLePacket lastPacket;
	private BluetoothLeDataPacket lastDataPacket;
	private int configuredRateHz,queueResets;
	private BluetoothClassicAnalyzer.Result latestClassic=BluetoothClassicAnalyzer.Result.empty();
	private volatile Result latest=Result.empty("Waiting for Bluetooth-band IQ samples");

	synchronized Result analyze(long centerFreqHz,byte[] iq,int length,int sampleRateHz,long captureMillis) {
		if(iq==null||length<2||sampleRateHz<50_000)return latest;
		Coverage coverage=coverage(centerFreqHz,sampleRateHz);
		double halfRate=sampleRateHz/2d;
		if(centerFreqHz+halfRate<BAND_LOW_HZ||centerFreqHz-halfRate>BAND_HIGH_HZ){
			latest=result("IQ window does not overlap the 2.4 GHz Bluetooth band",
					channelSummary(centerFreqHz,sampleRateHz),decodedPackets,lastDecodedMillis,null,
					queueResets,BluetoothNarrowbandDetector.Result.empty(),coverage,latestClassic);return latest;}
		latestClassic=classicAnalyzer.analyze(centerFreqHz,iq,length,sampleRateHz,captureMillis);
		classicObservations.addAll(latestClassic.packets);
		if(!latestClassic.packets.isEmpty())lastDecodedMillis=System.currentTimeMillis();
		BluetoothNarrowbandDetector.Result activity;
		if(sampleRateHz<PACKET_MIN_RATE_HZ){narrowbandDetector.accept(iq,length,sampleRateHz,System.currentTimeMillis());
			activity=narrowbandDetector.snapshot();}else activity=BluetoothNarrowbandDetector.Result.empty();
		if(centerFreqHz!=configuredCenterHz||sampleRateHz!=configuredRateHz)configure(centerFreqHz,sampleRateHz);
		if(channels.isEmpty()&&dataChannels.isEmpty()) {
			String state=activity.bursts>0?"BT/BLE-channel RF bursts detected; no primary advertising channel center is in this IQ window":
					"No BLE primary advertising channel center is inside this IQ window";
			if(!latestClassic.packets.isEmpty()){BluetoothClassicPacket classic=latestClassic.latest;state=classic.fhsValid
					?"Classic BR inquiry FHS decoded: "+classic.address+" ("+classic.deviceType+")":classic.headerValid
					?"Classic BR "+classic.classicType+" header decoded on channel "+classic.channel
					:"Classic BR access code validated; no BLE primary channel decoder in this IQ window";}
			latest=result(state,channelSummary(centerFreqHz,sampleRateHz),decodedPackets,
					lastDecodedMillis,null,queueResets,activity,coverage,latestClassic);
			return latest;
		}
		if(sampleRateHz<PACKET_MIN_RATE_HZ){
			String state=activity.bursts==0?String.format(Locale.US,
					"Narrowband mode at %.0f kS/s; searching for RF bursts",sampleRateHz/1000d):
					String.format(Locale.US,"Narrowband BT/BLE candidate activity at %.0f kS/s; PHY/PDU decode needs >= %.1f MS/s",
							sampleRateHz/1000d,PACKET_MIN_RATE_HZ/1e6);
			latest=result(state,activeChannels(),decodedPackets,lastDecodedMillis,lastPacket,queueResets,activity,coverage,latestClassic);
			return latest;
		}
		BluetoothLePacket newest=null;
		for(int channelIndex=0;channelIndex<channels.size();channelIndex++) {ChannelState state=channels.get(channelIndex);
			IQSubchannelExtractor.Result extracted=state.extractor.process(iq,length);
			List<BluetoothLePacket> packets=state.decoder.accept(extracted.iq,extracted.samples,
					extracted.sampleRateHz,state.channel,state.frequencyHz,captureMillis);
			for(BluetoothLePacket packet:packets) { observations.addLast(packet);decodedPackets++;
				lastDecodedMillis=System.currentTimeMillis();newest=packet;lastPacket=packet;
				if(packet.connectionData.valid())registerConnection(packet);
				if(packet.auxChannel>=0)ensureAuxDecoder(packet.auxChannel,packet.auxPhy);}
		}
		BluetoothLeDataPacket newestData=null;List<BluetoothLeConnectionTracker.Context> activeConnections=new ArrayList<BluetoothLeConnectionTracker.Context>(connections.values());
		for(DataChannelState data:dataChannels){IQSubchannelExtractor.Result extracted=data.extractor.process(iq,length);
			for(BluetoothLeDataPacket packet:data.decoder.accept(extracted.iq,extracted.samples,extracted.sampleRateHz,
					data.channel,data.frequencyHz,captureMillis,activeConnections)){BluetoothLeConnectionTracker.Context connection=
						connections.get(Long.valueOf(packet.accessAddress));if(connection!=null&&connection.observe(packet))reassembledL2capPackets++;
				dataObservations.addLast(packet);decodedDataPackets++;lastDecodedMillis=System.currentTimeMillis();lastDataPacket=newestData=packet;}}
		ensureDataDecoders();
		for(java.util.Iterator<Map.Entry<Long,BluetoothLeConnectionTracker.Context>> iterator=connections.entrySet().iterator();iterator.hasNext();)
			if(iterator.next().getValue().terminated())iterator.remove();
		String state=newest==null?"Searching for CRC-valid BLE advertising/data or validated Classic BR access codes":
				newest.phy+" advertising decoded on channel "+newest.channel;
		if(newestData!=null)state=newestData.packetType+" decoded on BLE data channel "+newestData.channel;
		else if(newest==null&&!latestClassic.packets.isEmpty())state=latestClassic.latest.fhsValid
				?"Classic BR inquiry FHS decoded: "+latestClassic.latest.address+" ("+latestClassic.latest.deviceType+")"
				:latestClassic.latest.headerValid?"Classic BR "+latestClassic.latest.classicType+" header decoded on channel "+latestClassic.latest.channel
				:"Classic BR access code validated on channel "+latestClassic.latest.channel;
		latest=result(state,activeChannels(),decodedPackets,lastDecodedMillis,lastPacket,queueResets,activity,coverage,latestClassic);
		return latest;
	}
	private Result result(String state,String channelText,long decoded,long lastMillis,BluetoothLePacket packet,int resets,
			BluetoothNarrowbandDetector.Result activity,Coverage coverage,BluetoothClassicAnalyzer.Result classic){return new Result(
				state,channelText,decoded,decodedDataPackets,reassembledL2capPackets,lastMillis,packet,lastDataPacket,connections.size(),resets,activity,coverage,classic);}

	synchronized void discardOverlap(){for(ChannelState channel:channels)channel.decoder.reset();for(DataChannelState channel:dataChannels)channel.decoder.reset();classicAnalyzer.reset();queueResets++;}
	synchronized List<BluetoothLePacket> takeObservations(){List<BluetoothLePacket> out=new ArrayList<BluetoothLePacket>(observations);observations.clear();return out;}
	synchronized List<BluetoothClassicPacket> takeClassicObservations(){List<BluetoothClassicPacket> out=new ArrayList<BluetoothClassicPacket>(classicObservations);classicObservations.clear();return out;}
	synchronized List<BluetoothLeDataPacket> takeDataObservations(){List<BluetoothLeDataPacket> out=new ArrayList<BluetoothLeDataPacket>(dataObservations);dataObservations.clear();return out;}
	synchronized Result latest(){return latest;}

	private void configure(long centerFreqHz,int sampleRateHz) {
		channels.clear();dataChannels.clear();configuredCenterHz=centerFreqHz;configuredRateHz=sampleRateHz;
		double half=sampleRateHz/2d;
		for(int n=0;n<PRIMARY_FREQUENCIES.length;n++) {
			long frequency=PRIMARY_FREQUENCIES[n];
			double edgeGuard=Math.min(850_000d,sampleRateHz*.45d);
			if(Math.abs(frequency-centerFreqHz)+edgeGuard>half)continue;
			channels.add(new ChannelState(PRIMARY_CHANNELS[n],frequency,
					new IQSubchannelExtractor.Stream(sampleRateHz,frequency-centerFreqHz,
							OCCUPIED_BANDWIDTH_HZ,OUTPUT_RATE_HZ),"LE 1M",new Le1MDecoder()));
		}
		ensureDataDecoders();
	}

	private void registerConnection(BluetoothLePacket packet){BluetoothLePacket.ConnectionData data=packet.connectionData;
		Long key=Long.valueOf(data.accessAddress);connections.put(key,new BluetoothLeConnectionTracker.Context(packet));
		while(connections.size()>16){Long first=connections.keySet().iterator().next();connections.remove(first);}
		ensureDataDecoders();
	}
	private void ensureDataDecoders(){if(configuredRateHz<PACKET_MIN_RATE_HZ)return;double half=configuredRateHz/2d;
		for(BluetoothLeConnectionTracker.Context connection:connections.values())for(int channel=0;channel<=36;channel++)if(connection.usesChannel(channel))
			for(String phy:new String[]{"LE 1M","LE 2M","LE Coded"})if(connection.usesPhy(phy)){
				boolean exists=false;for(DataChannelState current:dataChannels)if(current.channel==channel&&phy.equals(current.phy)){exists=true;break;}
				if(exists)continue;int occupied="LE 2M".equals(phy)?2_500_000:OCCUPIED_BANDWIDTH_HZ;
				int output="LE 2M".equals(phy)?8_000_000:OUTPUT_RATE_HZ;double minimum="LE 2M".equals(phy)?2_200_000d:PACKET_MIN_RATE_HZ;
				if(configuredRateHz<minimum)continue;long frequency=frequencyForChannel(channel);double guard=Math.min(occupied/2d,configuredRateHz*.45d);
				if(Math.abs(frequency-configuredCenterHz)+guard>half)continue;DataDecoder decoder="LE Coded".equals(phy)?new LeCodedDataDecoder():
						new LeDataDecoder("LE 2M".equals(phy)?2_000_000:1_000_000,"LE 2M".equals(phy)?16:8,phy);
				dataChannels.add(new DataChannelState(channel,frequency,phy,new IQSubchannelExtractor.Stream(configuredRateHz,
						frequency-configuredCenterHz,occupied,output),decoder));}}

	private void ensureAuxDecoder(int channel,String phy) {
		if(channel<0||channel>36||phy==null)return;
		for(ChannelState existing:channels)if(existing.channel==channel&&phy.equals(existing.phy))return;
		int symbolRate,outputRate,occupied;if("LE 1M".equals(phy)){symbolRate=1_000_000;outputRate=OUTPUT_RATE_HZ;occupied=OCCUPIED_BANDWIDTH_HZ;}
		else if("LE 2M".equals(phy)){symbolRate=2_000_000;outputRate=8_000_000;occupied=2_500_000;}
		else if("LE Coded".equals(phy)){symbolRate=1_000_000;outputRate=OUTPUT_RATE_HZ;occupied=OCCUPIED_BANDWIDTH_HZ;}
		else return;
		if(configuredRateHz<Math.round(symbolRate*1.1d))return;long frequency=frequencyForChannel(channel);
		double half=configuredRateHz/2d,guard=Math.min(occupied/2d,configuredRateHz*.45d);
		if(Math.abs(frequency-configuredCenterHz)+guard>half)return;
		AdvertisingDecoder decoder="LE Coded".equals(phy)?new LeCodedAdvertisingDecoder():
				new Le1MDecoder(symbolRate,"LE 2M".equals(phy)?16:8,phy);
		channels.add(new ChannelState(channel,frequency,new IQSubchannelExtractor.Stream(configuredRateHz,
				frequency-configuredCenterHz,occupied,outputRate),phy,decoder));
	}

	private String activeChannels(){StringBuilder out=new StringBuilder();for(ChannelState channel:channels){if(out.length()>0)out.append(", ");out.append(channel.phy).append(" ch ").append(channel.channel).append(" (").append(String.format(Locale.US,"%.0f MHz",channel.frequencyHz/1e6)).append(')');}
		if(!dataChannels.isEmpty()){if(out.length()>0)out.append("; ");out.append("LE data ");for(int n=0;n<dataChannels.size();n++){
			if(n>0)out.append(", ");out.append(dataChannels.get(n).phy).append(" ch ").append(dataChannels.get(n).channel);}}return out.toString();}
	private static String channelSummary(long center,int rate){long low=Math.round((center-rate/2d)/1e6),high=Math.round((center+rate/2d)/1e6);return low+"-"+high+" MHz; primary channels are 37/2402, 38/2426, 39/2480 MHz";}

	static Coverage coverage(long centerHz,int sampleRateHz) {
		double low=centerHz-sampleRateHz/2d,high=centerHz+sampleRateHz/2d;
		List<Integer> ble=new ArrayList<Integer>();long bleLow=Long.MAX_VALUE,bleHigh=Long.MIN_VALUE;
		for(int channel:BLE_FREQUENCY_ORDER){long frequency=frequencyForChannel(channel);if(frequency>=low&&frequency<=high){
			ble.add(Integer.valueOf(channel));bleLow=Math.min(bleLow,frequency);bleHigh=Math.max(bleHigh,frequency);}}
		List<Integer> classic=new ArrayList<Integer>();long classicLow=Long.MAX_VALUE,classicHigh=Long.MIN_VALUE;
		for(int channel=0;channel<79;channel++){long frequency=classicFrequencyForChannel(channel);
			if(frequency>=low&&frequency<=high){classic.add(Integer.valueOf(channel));classicLow=Math.min(classicLow,frequency);classicHigh=Math.max(classicHigh,frequency);}}
		return new Coverage(String.format(Locale.US,"%.6f-%.6f MHz",low/1e6,high/1e6),
				coverageText(ble,bleLow,bleHigh),coverageText(classic,classicLow,classicHigh));
	}

	private static String coverageText(List<Integer> channels,long low,long high) {
		if(channels.isEmpty())return "none";StringBuilder out=new StringBuilder();int first=channels.get(0),previous=first;
		for(int n=1;n<=channels.size();n++){int next=n<channels.size()?channels.get(n):-999;
			if(n<channels.size()&&next==previous+1){previous=next;continue;}
			if(out.length()>0)out.append(", ");out.append(first);if(previous!=first)out.append('-').append(previous);
			if(n<channels.size()){first=previous=next;}}
		out.append(String.format(Locale.US," (%.0f-%.0f MHz centers)",low/1e6,high/1e6));return out.toString();
	}

	static long frequencyForChannel(int channel){if(channel==37)return 2_402_000_000L;if(channel==38)return 2_426_000_000L;if(channel==39)return 2_480_000_000L;if(channel>=0&&channel<=10)return 2_404_000_000L+channel*2_000_000L;if(channel>=11&&channel<=36)return 2_428_000_000L+(channel-11)*2_000_000L;return-1L;}
	static long classicFrequencyForChannel(int channel){return channel>=0&&channel<=78?2_402_000_000L+channel*1_000_000L:-1L;}

	static final class Coverage {final String iqRange,bleChannels,classicChannels;
		Coverage(String iqRange,String bleChannels,String classicChannels){this.iqRange=iqRange;this.bleChannels=bleChannels;this.classicChannels=classicChannels;}}

	static final class Result {
		final String state,channels;final long decodedPackets,decodedDataPackets,reassembledL2capPackets,lastDecodedMillis;final BluetoothLePacket latestPacket;final BluetoothLeDataPacket latestDataPacket;final int connectionCount,queueResets;final BluetoothNarrowbandDetector.Result activity;final Coverage coverage;final long classicAccessCodes,classicHeaders,classicPayloads,classicFhsPackets;final BluetoothClassicPacket latestClassicPacket;final String classicState,classicScannedChannels;
		Result(String state,String channels,long decodedPackets,long decodedDataPackets,long reassembledL2capPackets,long lastDecodedMillis,
				BluetoothLePacket latestPacket,BluetoothLeDataPacket latestDataPacket,int connectionCount,int queueResets,
				BluetoothNarrowbandDetector.Result activity,Coverage coverage,BluetoothClassicAnalyzer.Result classic){this.state=state;
			this.channels=channels;this.decodedPackets=decodedPackets;this.decodedDataPackets=decodedDataPackets;
			this.reassembledL2capPackets=reassembledL2capPackets;
			this.lastDecodedMillis=lastDecodedMillis;this.latestPacket=latestPacket;this.latestDataPacket=latestDataPacket;
			this.connectionCount=connectionCount;this.queueResets=queueResets;this.activity=activity;this.coverage=coverage;
			this.classicAccessCodes=classic.totalAccessCodes;this.classicHeaders=classic.totalHeaders;this.classicPayloads=classic.totalPayloads;this.classicFhsPackets=classic.totalFhsPackets;
			this.latestClassicPacket=classic.latest;this.classicState=classic.state;this.classicScannedChannels=classic.scannedChannels;}
		static Result empty(String state){return new Result(state,"--",0L,0L,0L,0L,null,null,0,0,
				BluetoothNarrowbandDetector.Result.empty(),new Coverage("--","--","--"),BluetoothClassicAnalyzer.Result.empty());}
	}

	private interface AdvertisingDecoder {void reset();List<BluetoothLePacket> accept(float[] next,int nextSamples,double rate,int channel,
			long frequency,long captureMillis);}
	private static final class ChannelState {
		final int channel;final long frequencyHz;final IQSubchannelExtractor.Stream extractor;final String phy;final AdvertisingDecoder decoder;
		ChannelState(int channel,long frequencyHz,IQSubchannelExtractor.Stream extractor,String phy,AdvertisingDecoder decoder){this.channel=channel;this.frequencyHz=frequencyHz;this.extractor=extractor;this.phy=phy;this.decoder=decoder;}
	}
	private interface DataDecoder {void reset();List<BluetoothLeDataPacket> accept(float[] next,int nextSamples,double rate,int channel,
			long frequency,long captureMillis,List<BluetoothLeConnectionTracker.Context> connections);}
	private static final class DataChannelState {final int channel;final long frequencyHz;final String phy;final IQSubchannelExtractor.Stream extractor;
		final DataDecoder decoder;DataChannelState(int channel,long frequencyHz,String phy,IQSubchannelExtractor.Stream extractor,
				DataDecoder decoder){this.channel=channel;this.frequencyHz=frequencyHz;this.phy=phy;this.extractor=extractor;this.decoder=decoder;}}

	/** LE 1M data-channel decoder for access addresses learned from CONNECT_IND. */
	static final class LeDataDecoder implements DataDecoder {
		private final int symbolRateHz,preambleBits;private final String phy;
		private float[] carry=new float[0];private long totalSamples;private final Map<Integer,Long> recent=new HashMap<Integer,Long>();
		LeDataDecoder(){this(1_000_000,8,"LE 1M");}
		LeDataDecoder(int symbolRateHz,int preambleBits,String phy){this.symbolRateHz=symbolRateHz;this.preambleBits=preambleBits;this.phy=phy;}
		public void reset(){carry=new float[0];totalSamples=0;recent.clear();}
		public List<BluetoothLeDataPacket> accept(float[] next,int nextSamples,double rate,int channel,long frequency,long captureMillis,
				List<BluetoothLeConnectionTracker.Context> connections){List<BluetoothLeDataPacket> found=new ArrayList<BluetoothLeDataPacket>();
			if(next==null||nextSamples<2||rate<symbolRateHz*1.1d||connections.isEmpty())return found;int old=carry.length/2;
			int samples=old+Math.min(nextSamples,next.length/2);float[] iq=new float[samples*2];System.arraycopy(carry,0,iq,0,carry.length);
			System.arraycopy(next,0,iq,carry.length,(samples-old)*2);long absoluteStart=totalSamples-old;totalSamples+=samples-old;
			double sps=rate/symbolRateHz;int phases=Math.max(8,(int)Math.ceil(sps*2d));for(int timing=0;timing<phases;timing++){
				double phase=timing*sps/phases;int bitCount=(int)Math.floor((samples-phase-1)/sps);if(bitCount<80)continue;
				byte[] normal=demodulate(iq,samples,sps,phase,bitCount);for(boolean invert:new boolean[]{false,true})
					for(BluetoothLeConnectionTracker.Context connection:connections)if(connection.usesChannel(channel)&&connection.usesPhy(phy))scan(normal,invert,iq,samples,rate,sps,phase,absoluteStart,channel,
							frequency,captureMillis,connection,found);}
			int keep=Math.min(samples,Math.max(4096,(int)Math.ceil(rate*.003d)));carry=new float[keep*2];
			System.arraycopy(iq,(samples-keep)*2,carry,0,carry.length);if(recent.size()>2048)recent.clear();return found;}
		static byte[] demodulate(float[] iq,int samples,double sps,double phase,int count){byte[] values=new byte[count];
			for(int bit=0;bit<count;bit++){int first=Math.max(1,(int)Math.round(phase+bit*sps));
				int end=Math.min(samples,(int)Math.round(phase+(bit+1)*sps));double imaginary=0d;for(int n=first;n<end;n++){
					double pi=iq[(n-1)*2],pq=iq[(n-1)*2+1],ci=iq[n*2],cq=iq[n*2+1];imaginary+=cq*pi-ci*pq;}
				values[bit]=(byte)(imaginary>=0d?1:0);}return values;}
		private void scan(byte[] bits,boolean invert,float[] iq,int samples,double rate,double sps,double phase,long absoluteStart,
				int channel,long frequency,long captureMillis,BluetoothLeConnectionTracker.Context connection,List<BluetoothLeDataPacket> found){
			for(int at=0;at+preambleBits+32+40<=bits.length;at++){if(!matches(bits,at,invert,connection.accessAddress,preambleBits))continue;
				int bodyAt=at+preambleBits+32,available=bits.length-bodyAt;int[] clear=dewhiten(bits,bodyAt,available,channel,invert);
				int length=byteLsb(clear,8);if(length>251)continue;int bytes=2+length,needed=bytes*8+24;if(available<needed)continue;
				byte[] pdu=new byte[bytes];for(int n=0;n<bytes;n++)pdu[n]=(byte)byteLsb(clear,n*8);int received=0;
				for(int n=0;n<24;n++)received=(received<<1)|clear[bytes*8+n];if(crc24(pdu,connection.crcInit)!=received)continue;
				long packetAt=absoluteStart+Math.round(phase+at*sps);int signature=31*channel+Arrays.hashCode(pdu)
					+(int)(connection.accessAddress^(connection.accessAddress>>>32))+31*phy.hashCode();Long previous=recent.get(Integer.valueOf(signature));
				if(previous!=null&&Math.abs(packetAt-previous.longValue())<rate*.002d)continue;recent.put(Integer.valueOf(signature),Long.valueOf(packetAt));
				double[] metric=metrics(iq,samples,phase+at*sps,needed+preambleBits+32,sps,rate);BluetoothLeDataPacket packet=BluetoothLeDataPacket.parse(
						channel,frequency,phy,connection.accessAddress,pdu,connection.initiator,connection.advertiser,
						metric[0],metric[1],metric[2],captureMillis>0?captureMillis:System.currentTimeMillis());if(packet!=null)found.add(packet);
				at+=needed+39;}}
		private static boolean matches(byte[] bits,int at,boolean invert,long access,int preambleBits){int first=(int)(access&1L);
			for(int n=0;n<preambleBits;n++)if(((bits[at+n]^(invert?1:0))&1)!=(first^(n&1)))return false;int cursor=at+preambleBits;
			for(int n=0;n<32;n++)if(((bits[cursor++]^(invert?1:0))&1)!=((access>>>n)&1L))return false;return true;}
		private static int[] dewhiten(byte[] bits,int at,int count,int channel,boolean invert){int[] out=new int[count];
			int lfsr=(channel&0x3f)|0x40;for(int n=0;n<count;n++){int whitening=lfsr&1;out[n]=((bits[at+n]^(invert?1:0))&1)^whitening;
				lfsr>>>=1;if(whitening!=0)lfsr^=0x44;}return out;}
		private static int byteLsb(int[] bits,int at){int value=0;for(int n=0;n<8;n++)value|=(bits[at+n]&1)<<n;return value;}
		static int crc24(byte[] pdu,int init){int state=init&0xffffff;for(byte value:pdu)for(int bit=0;bit<8;bit++){
			int feedback=((state>>>23)&1)^((value>>>bit)&1);state=state<<1&0xffffff;if(feedback!=0)state^=0x00065b;}return state;}
		static double[] metrics(float[] iq,int samples,double packetStart,int packetBits,double sps,double rate){
			int first=Math.max(1,(int)Math.floor(packetStart)),end=Math.min(samples,(int)Math.ceil(packetStart+packetBits*sps));
			double power=0d,real=0d,imag=0d;int count=0;for(int n=first;n<end;n++){double pi=iq[(n-1)*2],pq=iq[(n-1)*2+1],ci=iq[n*2],cq=iq[n*2+1];
				power+=ci*ci+cq*cq;real+=ci*pi+cq*pq;imag+=cq*pi-ci*pq;count++;}double dbfs=count==0?-120d:
					10d*Math.log10(Math.max(1e-15,power/(count*2d)));double cfo=Math.atan2(imag,real)*rate/(2d*Math.PI);
			return new double[]{dbfs,cfo,Math.min(100d,Math.max(0d,60d+20d*Math.min(2d,power*Math.max(1,count))))};}
	}

	/** LE Coded S=2/S=8 data-channel decoder with pattern demapping and rate-1/2 Viterbi FEC. */
	static final class LeCodedDataDecoder implements DataDecoder {
		static final int PREAMBLE_SYMBOLS=80,BLOCK1_SYMBOLS=296;
		private float[] carry=new float[0];private long totalSamples;private final Map<Integer,Long> recent=new HashMap<Integer,Long>();
		public void reset(){carry=new float[0];totalSamples=0;recent.clear();}
		public List<BluetoothLeDataPacket> accept(float[] next,int nextSamples,double rate,int channel,long frequency,long captureMillis,
				List<BluetoothLeConnectionTracker.Context> connections){List<BluetoothLeDataPacket> found=new ArrayList<BluetoothLeDataPacket>();
			if(next==null||nextSamples<2||rate<1_100_000d||connections.isEmpty())return found;int old=carry.length/2;
			int samples=old+Math.min(nextSamples,next.length/2);float[] iq=new float[samples*2];System.arraycopy(carry,0,iq,0,carry.length);
			System.arraycopy(next,0,iq,carry.length,(samples-old)*2);long absoluteStart=totalSamples-old;totalSamples+=samples-old;
			double sps=rate/1_000_000d;int phases=Math.max(8,(int)Math.ceil(sps*2d));for(int timing=0;timing<phases;timing++){
				double phase=timing*sps/phases;int count=(int)Math.floor((samples-phase-1)/sps);if(count<PREAMBLE_SYMBOLS+BLOCK1_SYMBOLS+32)continue;
				byte[] symbols=LeDataDecoder.demodulate(iq,samples,sps,phase,count);for(boolean invert:new boolean[]{false,true})
					for(BluetoothLeConnectionTracker.Context connection:connections)if(connection.usesChannel(channel)&&connection.usesPhy("LE Coded"))
						scan(symbols,invert,iq,samples,rate,sps,phase,absoluteStart,channel,frequency,captureMillis,connection,found);}
			int keep=Math.min(samples,Math.max(8192,(int)Math.ceil(rate*.020d)));carry=new float[keep*2];
			System.arraycopy(iq,(samples-keep)*2,carry,0,carry.length);if(recent.size()>2048)recent.clear();return found;}

		private void scan(byte[] symbols,boolean invert,float[] iq,int samples,double rate,double sps,double phase,long absoluteStart,
				int channel,long frequency,long captureMillis,BluetoothLeConnectionTracker.Context connection,List<BluetoothLeDataPacket> found){
			for(int at=0;at+PREAMBLE_SYMBOLS+BLOCK1_SYMBOLS+32<=symbols.length;at++){if(!codedPreamble(symbols,at,invert))continue;
				CodedPacket decoded=decodePacket(symbols,at,invert,connection.accessAddress,connection.crcInit,channel);if(decoded==null)continue;
				long packetAt=absoluteStart+Math.round((phase+at*sps));int signature=31*channel+Arrays.hashCode(decoded.pdu)
						+(int)(connection.accessAddress^(connection.accessAddress>>>32))+0x4c4543;Long previous=recent.get(Integer.valueOf(signature));
				if(previous!=null&&Math.abs(packetAt-previous.longValue())<rate*.004d)continue;recent.put(Integer.valueOf(signature),Long.valueOf(packetAt));
				double[] metric=LeDataDecoder.metrics(iq,samples,phase+at*sps,decoded.symbols,sps,rate);
				BluetoothLeDataPacket packet=BluetoothLeDataPacket.parse(channel,frequency,decoded.pattern==1?"LE Coded S=2":"LE Coded S=8",
						connection.accessAddress,decoded.pdu,connection.initiator,connection.advertiser,metric[0],metric[1],metric[2],
						captureMillis>0?captureMillis:System.currentTimeMillis());if(packet!=null)found.add(packet);at+=decoded.symbols-1;}}

		static CodedPacket decodePacket(byte[] symbols,int at,boolean invert,long accessAddress,int crcInit,int channel){
			if(!codedPreamble(symbols,at,invert))return null;int[] block1Coded=demap(symbols,at+PREAMBLE_SYMBOLS,74,4,invert);
			if(block1Coded==null)return null;int[] block1=viterbi(block1Coded,37,true);if(block1==null||readLongLsb(block1,0,32)!=accessAddress
					||block1[34]!=0||block1[35]!=0||block1[36]!=0)return null;int pattern;
			if(block1[32]==0&&block1[33]==0)pattern=4;else if(block1[32]==1&&block1[33]==0)pattern=1;else return null;
			int block2At=at+PREAMBLE_SYMBOLS+BLOCK1_SYMBOLS;if(block2At+32*pattern>symbols.length)return null;
			int[] firstCoded=demap(symbols,block2At,32,pattern,invert),first=viterbi(firstCoded,16,false);if(first==null)return null;
			dewhiten(first,16,channel);int length=readLsb(first,8,8);if(length>251)return null;int pduBits=(2+length)*8;
			int inputBits=pduBits+24+3,codedBits=inputBits*2,neededSymbols=codedBits*pattern;if(block2At+neededSymbols>symbols.length)return null;
			int[] coded=demap(symbols,block2At,codedBits,pattern,invert),clear=viterbi(coded,inputBits,true);
			if(clear==null||clear[inputBits-1]!=0||clear[inputBits-2]!=0||clear[inputBits-3]!=0)return null;dewhiten(clear,pduBits+24,channel);
			byte[] pdu=new byte[2+length];for(int n=0;n<pdu.length;n++)pdu[n]=(byte)readLsb(clear,n*8,8);int received=0;
			for(int n=0;n<24;n++)received=(received<<1)|clear[pduBits+n];if(LeDataDecoder.crc24(pdu,crcInit)!=received)return null;
			return new CodedPacket(pdu,pattern,PREAMBLE_SYMBOLS+BLOCK1_SYMBOLS+neededSymbols);}
		static final class CodedPacket {final byte[] pdu;final int pattern,symbols;CodedPacket(byte[] pdu,int pattern,int symbols){
			this.pdu=pdu;this.pattern=pattern;this.symbols=symbols;}}

		private static boolean codedPreamble(byte[] symbols,int at,boolean invert){int errors=0;int[] pattern={0,0,1,1,1,1,0,0};
			for(int n=0;n<PREAMBLE_SYMBOLS;n++)if((((symbols[at+n]^(invert?1:0))&1)!=pattern[n&7])&&++errors>6)return false;return true;}
		private static int[] demap(byte[] symbols,int at,int codedBits,int pattern,boolean invert){if(at+codedBits*pattern>symbols.length)return null;
			int[] out=new int[codedBits];for(int bit=0;bit<codedBits;bit++){if(pattern==1){out[bit]=(symbols[at+bit]^(invert?1:0))&1;continue;}
				int zero=0,one=0;int[] zeroPattern={0,0,1,1},onePattern={1,1,0,0};for(int n=0;n<4;n++){
					int value=(symbols[at+bit*4+n]^(invert?1:0))&1;if(value!=zeroPattern[n])zero++;if(value!=onePattern[n])one++;}
				out[bit]=one<zero?1:0;}return out;}
		static int[] viterbi(int[] coded,int inputBits,boolean forceZeroEnd){if(coded==null||coded.length<inputBits*2)return null;
			final int inf=1<<20;int[] metric=new int[8],next=new int[8];Arrays.fill(metric,inf);metric[0]=0;
			byte[][] previous=new byte[inputBits][8],chosen=new byte[inputBits][8];for(int at=0;at<inputBits;at++){Arrays.fill(next,inf);
				for(int state=0;state<8;state++)if(metric[state]<inf)for(int input=0;input<2;input++){int target=(state>>>1)|(input<<2);
					int g0=input^((state>>>2)&1)^((state>>>1)&1)^(state&1),g1=input^((state>>>1)&1)^(state&1);
					int cost=metric[state]+(g0==coded[at*2]?0:1)+(g1==coded[at*2+1]?0:1);if(cost<next[target]){
						next[target]=cost;previous[at][target]=(byte)state;chosen[at][target]=(byte)input;}}
				int[] swap=metric;metric=next;next=swap;}int state=0;if(!forceZeroEnd){for(int n=1;n<8;n++)if(metric[n]<metric[state])state=n;}
			if(metric[state]>=inf)return null;int[] out=new int[inputBits];for(int at=inputBits-1;at>=0;at--){out[at]=chosen[at][state];state=previous[at][state]&0xff;}return out;}
		private static void dewhiten(int[] bits,int count,int channel){int lfsr=(channel&0x3f)|0x40;for(int n=0;n<count;n++){
			int whitening=lfsr&1;bits[n]^=whitening;lfsr>>>=1;if(whitening!=0)lfsr^=0x44;}}
		private static int readLsb(int[] bits,int at,int count){int value=0;for(int n=0;n<count;n++)value|=(bits[at+n]&1)<<n;return value;}
		private static long readLongLsb(int[] bits,int at,int count){long value=0L;for(int n=0;n<count;n++)value|=(long)(bits[at+n]&1)<<n;return value;}
	}

	/** LE Coded secondary/periodic advertising decoder using the advertising Access Address and CRCInit. */
	static final class LeCodedAdvertisingDecoder implements AdvertisingDecoder {
		private static final long ADVERTISING_ACCESS=0x8e89bed6L;private static final int ADVERTISING_CRC_INIT=0x555555;
		private float[] carry=new float[0];private long totalSamples;private final Map<Integer,Long> recent=new HashMap<Integer,Long>();
		public void reset(){carry=new float[0];totalSamples=0;recent.clear();}
		public List<BluetoothLePacket> accept(float[] next,int nextSamples,double rate,int channel,long frequency,long captureMillis){
			List<BluetoothLePacket> found=new ArrayList<BluetoothLePacket>();if(next==null||nextSamples<2||rate<1_100_000d)return found;
			int old=carry.length/2,samples=old+Math.min(nextSamples,next.length/2);float[] iq=new float[samples*2];
			System.arraycopy(carry,0,iq,0,carry.length);System.arraycopy(next,0,iq,carry.length,(samples-old)*2);
			long absoluteStart=totalSamples-old;totalSamples+=samples-old;double sps=rate/1_000_000d;
			int phases=Math.max(8,(int)Math.ceil(sps*2d));for(int timing=0;timing<phases;timing++){double phase=timing*sps/phases;
				int count=(int)Math.floor((samples-phase-1)/sps);if(count<LeCodedDataDecoder.PREAMBLE_SYMBOLS+LeCodedDataDecoder.BLOCK1_SYMBOLS+32)continue;
				byte[] symbols=LeDataDecoder.demodulate(iq,samples,sps,phase,count);for(boolean invert:new boolean[]{false,true})
					scan(symbols,invert,iq,samples,rate,sps,phase,absoluteStart,channel,frequency,captureMillis,found);}
			int keep=Math.min(samples,Math.max(8192,(int)Math.ceil(rate*.020d)));carry=new float[keep*2];
			System.arraycopy(iq,(samples-keep)*2,carry,0,carry.length);if(recent.size()>1024)recent.clear();return found;}
		private void scan(byte[] symbols,boolean invert,float[] iq,int samples,double rate,double sps,double phase,long absoluteStart,
				int channel,long frequency,long captureMillis,List<BluetoothLePacket> found){int minimum=LeCodedDataDecoder.PREAMBLE_SYMBOLS+
				LeCodedDataDecoder.BLOCK1_SYMBOLS+32;for(int at=0;at+minimum<=symbols.length;at++){
				LeCodedDataDecoder.CodedPacket decoded=LeCodedDataDecoder.decodePacket(symbols,at,invert,ADVERTISING_ACCESS,
						ADVERTISING_CRC_INIT,channel);if(decoded==null)continue;long packetAt=absoluteStart+Math.round(phase+at*sps);
				int signature=31*channel+Arrays.hashCode(decoded.pdu)+decoded.pattern*65537;Long previous=recent.get(Integer.valueOf(signature));
				if(previous!=null&&Math.abs(packetAt-previous.longValue())<rate*.004d)continue;recent.put(Integer.valueOf(signature),Long.valueOf(packetAt));
				double[] metric=LeDataDecoder.metrics(iq,samples,phase+at*sps,decoded.symbols,sps,rate);BluetoothLePacket packet=
						BluetoothLePacket.parse(channel,frequency,decoded.pattern==1?"LE Coded S=2":"LE Coded S=8",decoded.pdu,
								metric[0],metric[1],metric[2],captureMillis>0?captureMillis:System.currentTimeMillis());
				if(packet!=null)found.add(packet);at+=decoded.symbols-1;}}
	}

	/** LE 1M discriminator, timing search, de-whitener and CRC checker. */
	static final class Le1MDecoder implements AdvertisingDecoder {
		private static final int[] ACCESS_BYTES={0xd6,0xbe,0x89,0x8e};
		private final int symbolRateHz,preambleBits;private final String phy;
		private float[] carry=new float[0];private long totalSamples;
		private final Map<Integer,Long> recent=new HashMap<Integer,Long>();
		Le1MDecoder(){this(1_000_000,8,"LE 1M");}
		Le1MDecoder(int symbolRateHz,int preambleBits,String phy){this.symbolRateHz=symbolRateHz;this.preambleBits=preambleBits;this.phy=phy;}

		public void reset(){carry=new float[0];totalSamples=0;recent.clear();}

		public List<BluetoothLePacket> accept(float[] next,int nextSamples,double rate,int channel,
				long frequency,long captureMillis) {
			List<BluetoothLePacket> found=new ArrayList<BluetoothLePacket>();
			if(next==null||nextSamples<2||rate<symbolRateHz*1.1d)return found;
			int old=carry.length/2,samples=old+Math.min(nextSamples,next.length/2);
			float[] iq=new float[samples*2];System.arraycopy(carry,0,iq,0,carry.length);
			System.arraycopy(next,0,iq,carry.length,(samples-old)*2);
			long absoluteStart=totalSamples-old;totalSamples+=samples-old;
			double samplesPerSymbol=rate/symbolRateHz;int phases=Math.max(8,(int)Math.ceil(samplesPerSymbol*2d));
			for(int timing=0;timing<phases;timing++){double phase=timing*samplesPerSymbol/phases;
				for(boolean invert:new boolean[]{false,true})
					scan(iq,samples,rate,samplesPerSymbol,phase,invert,absoluteStart,channel,frequency,captureMillis,found);}
			int keep=Math.min(samples,Math.max(4096,(int)Math.ceil(rate*.003d)));
			carry=new float[keep*2];System.arraycopy(iq,(samples-keep)*2,carry,0,carry.length);
			if(recent.size()>1024)recent.clear();return found;
		}

		private void scan(float[] iq,int samples,double rate,double sps,double phase,boolean invert,
				long absoluteStart,int channel,long frequency,long captureMillis,List<BluetoothLePacket> found) {
			int bits=(int)Math.floor((samples-phase-1)/sps);if(bits<preambleBits+72)return;
			byte[] values=new byte[bits];
			for(int bit=0;bit<bits;bit++) {
				int first=Math.max(1,(int)Math.round(phase+bit*sps));
				int end=Math.min(samples,(int)Math.round(phase+(bit+1)*sps));double imaginary=0d;
				for(int n=first;n<end;n++){double pi=iq[(n-1)*2],pq=iq[(n-1)*2+1],ci=iq[n*2],cq=iq[n*2+1];imaginary+=cq*pi-ci*pq;}
				values[bit]=(byte)(((imaginary>=0d)^invert)?1:0);
			}
			for(int at=0;at+preambleBits+72<=bits;at++) {
				if(!matchesPreambleAndAccess(values,at))continue;
				int accessEnd=at+preambleBits+32,available=bits-accessEnd;int[] clear=dewhiten(values,accessEnd,available,channel);
				if(clear.length<40)continue;int firstByte=byteLsb(clear,0),secondByte=byteLsb(clear,8);
				int type=firstByte&15,length=type==7?secondByte:(secondByte&0x3f);
				if(length>255||(type!=7&&length>37))continue;int pduBytes=2+length,needed=pduBytes*8+24;
				if(clear.length<needed)continue;byte[] pdu=new byte[pduBytes];for(int n=0;n<pduBytes;n++)pdu[n]=(byte)byteLsb(clear,n*8);
				int received=0;for(int n=0;n<24;n++)received=(received<<1)|clear[pduBytes*8+n];
				if(crc24(pdu)!=received)continue;
				long packetAt=absoluteStart+Math.round(phase+at*sps);int signature=31*channel+Arrays.hashCode(pdu);
				Long previous=recent.get(signature);if(previous!=null&&Math.abs(packetAt-previous)<rate*.002d)continue;
				recent.put(signature,packetAt);double[] metrics=metrics(iq,samples,phase+at*sps,needed+preambleBits+32,sps,rate);
				BluetoothLePacket packet=BluetoothLePacket.parse(channel,frequency,phy,pdu,metrics[0],metrics[1],metrics[2],captureMillis>0?captureMillis:System.currentTimeMillis());
				if(packet!=null)found.add(packet);at+=needed+preambleBits+31;
			}
		}

		private boolean matchesPreambleAndAccess(byte[] bits,int at){for(int n=0;n<preambleBits;n++)if(bits[at+n]!=(n&1))return false;int cursor=at+preambleBits;for(int value:ACCESS_BYTES)for(int n=0;n<8;n++)if(bits[cursor++]!=((value>>>n)&1))return false;return true;}
		private static int[] dewhiten(byte[] bits,int at,int count,int channel){int[] out=new int[count];int lfsr=(channel&0x3f)|0x40;for(int n=0;n<count;n++){int whitening=lfsr&1;out[n]=(bits[at+n]&1)^whitening;lfsr>>>=1;if(whitening!=0)lfsr^=0x44;}return out;}
		private static int byteLsb(int[] bits,int at){int value=0;for(int n=0;n<8;n++)value|=(bits[at+n]&1)<<n;return value;}

		/** Bluetooth LE CRC state; CRC bits are transmitted from bit 23 down to bit 0. */
		static int crc24(byte[] pdu){int state=0x555555;for(byte value:pdu)for(int bit=0;bit<8;bit++){int feedback=((state>>>23)&1)^((value>>>bit)&1);state=state<<1&0xffffff;if(feedback!=0)state^=0x00065b;}return state;}

		private static double[] metrics(float[] iq,int samples,double packetStart,int packetBits,double sps,double rate){int first=Math.max(1,(int)Math.floor(packetStart)),end=Math.min(samples,(int)Math.ceil(packetStart+packetBits*sps));double power=0d;int count=0;for(int n=first;n<end;n++){double i=iq[n*2],q=iq[n*2+1];power+=i*i+q*q;count++;}double dbfs=count==0?-120d:10d*Math.log10(Math.max(1e-15,power/(count*2d)));int accessEnd=Math.min(end,(int)Math.ceil(packetStart+40*sps));double real=0d,imag=0d;for(int n=first;n<accessEnd;n++){double pi=iq[(n-1)*2],pq=iq[(n-1)*2+1],ci=iq[n*2],cq=iq[n*2+1];real+=ci*pi+cq*pq;imag+=cq*pi-ci*pq;}double cfo=Math.atan2(imag,real)*rate/(2d*Math.PI);double quality=Math.min(100d,Math.max(0d,60d+20d*Math.min(2d,power*Math.max(1,count))));return new double[]{dbfs,cfo,quality};}
	}
}
