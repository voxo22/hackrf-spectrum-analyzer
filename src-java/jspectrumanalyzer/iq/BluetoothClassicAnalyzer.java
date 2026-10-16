package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Streaming, round-robin BR access-code detector for Classic channels in the current IQ window. */
final class BluetoothClassicAnalyzer {
	private static final int MAX_CHANNELS_PER_BLOCK=4,OUTPUT_RATE_HZ=4_000_000;
	private final List<Integer> channels=new ArrayList<Integer>();
	private final Map<Integer,Long> recent=new HashMap<Integer,Long>();
	private final Map<Integer,BluetoothClassicPacket.FhsData> identities=new HashMap<Integer,BluetoothClassicPacket.FhsData>();
	private final Map<Integer,BluetoothClassicPacket.FhsData> inquiryIdentities=new HashMap<Integer,BluetoothClassicPacket.FhsData>();
	private long configuredCenter=Long.MIN_VALUE,totalAccessCodes,totalHeaders,totalPayloads,totalFhsPackets;private int configuredRate,roundRobin;
	private BluetoothClassicPacket latest;

	Result analyze(long centerHz,byte[] iq,int length,int rate,long captureMillis){
		if(iq==null||length<2||rate<BluetoothSignalAnalyzer.PACKET_MIN_RATE_HZ)return result("Classic BR PHY needs >= 1.1 MS/s","--",new ArrayList<BluetoothClassicPacket>());
		if(centerHz!=configuredCenter||rate!=configuredRate)configure(centerHz,rate);
		List<BluetoothClassicPacket> found=new ArrayList<BluetoothClassicPacket>();
		if(channels.isEmpty())return result("No Classic channel center is sufficiently inside the IQ passband","none",found);
		int count=Math.min(MAX_CHANNELS_PER_BLOCK,channels.size());StringBuilder scanned=new StringBuilder();
		for(int n=0;n<count;n++){int channel=channels.get((roundRobin+n)%channels.size());
			if(scanned.length()>0)scanned.append(',');scanned.append(channel);long frequency=BluetoothSignalAnalyzer.classicFrequencyForChannel(channel);
			IQSubchannelExtractor.Result extracted=IQSubchannelExtractor.extract(iq,length,rate,frequency-centerHz,1_300_000,OUTPUT_RATE_HZ);
			for(BluetoothClassicPacket packet:BrAccessDecoder.decode(extracted.iq,extracted.samples,extracted.sampleRateHz,
					channel,frequency,captureMillis,identities,inquiryIdentities)){int key=31*channel+packet.lap+(packet.trailerPresent?0x40000000:0)
						+(packet.fhsValid?0x20000000:0)+(packet.headerValid?packet.header*131:0);
				Long previous=recent.get(Integer.valueOf(key));if(previous!=null&&Math.abs(packet.decodedMillis-previous.longValue())<2L)continue;
				recent.put(Integer.valueOf(key),Long.valueOf(packet.decodedMillis));found.add(packet);latest=packet;totalAccessCodes++;
				if(packet.headerValid)totalHeaders++;if(packet.payloadValid&&!packet.fhsValid)totalPayloads++;if(packet.fhsValid){totalFhsPackets++;
					BluetoothClassicPacket.FhsData identity=packetIdentity(packet);identities.put(Integer.valueOf(identity.lap),identity);}}}
		roundRobin=(roundRobin+count)%channels.size();if(recent.size()>2048)recent.clear();
		return new Result(found,totalAccessCodes,totalHeaders,totalPayloads,totalFhsPackets,latest,"Classic BR scan channels "+scanned+" this IQ block",scanned.toString());
	}
	private static BluetoothClassicPacket.FhsData packetIdentity(BluetoothClassicPacket packet){String[] bytes=packet.address.split(":");
		int nap=(Integer.parseInt(bytes[0],16)<<8)|Integer.parseInt(bytes[1],16),uap=Integer.parseInt(bytes[2],16);
		int lap=(Integer.parseInt(bytes[3],16)<<16)|(Integer.parseInt(bytes[4],16)<<8)|Integer.parseInt(bytes[5],16);
		int sr="R0".equals(packet.scanRepetition)?0:"R1".equals(packet.scanRepetition)?1:"R2".equals(packet.scanRepetition)?2:3;
		return new BluetoothClassicPacket.FhsData(packet.header,packet.whiteningSeed,packet.payloadCorrections,lap,uap,nap,
				packet.classOfDevice,packet.eir,sr,packet.nativeClock);}

	private void configure(long center,int rate){configuredCenter=center;configuredRate=rate;roundRobin=0;channels.clear();inquiryIdentities.clear();
		double half=rate/2d,guard=Math.min(650_000d,rate*.45d);for(int channel=0;channel<79;channel++){
			long frequency=BluetoothSignalAnalyzer.classicFrequencyForChannel(channel);
			if(Math.abs(frequency-center)+guard<=half)channels.add(Integer.valueOf(channel));}}
	private Result result(String state,String scanned,List<BluetoothClassicPacket> found){return new Result(found,totalAccessCodes,totalHeaders,totalPayloads,totalFhsPackets,latest,state,scanned);}
	void reset(){recent.clear();inquiryIdentities.clear();}

	static final class Result {final List<BluetoothClassicPacket> packets;final long totalAccessCodes,totalHeaders,totalPayloads,totalFhsPackets;
		final BluetoothClassicPacket latest;final String state,scannedChannels;
		Result(List<BluetoothClassicPacket> packets,long totalAccessCodes,long totalHeaders,long totalPayloads,long totalFhsPackets,BluetoothClassicPacket latest,String state,String scannedChannels){
			this.packets=packets;this.totalAccessCodes=totalAccessCodes;this.totalHeaders=totalHeaders;this.totalPayloads=totalPayloads;
			this.totalFhsPackets=totalFhsPackets;this.latest=latest;this.state=state;this.scannedChannels=scannedChannels;}
		static Result empty(){return new Result(new ArrayList<BluetoothClassicPacket>(),0L,0L,0L,0L,null,"Waiting for Classic BR IQ","--");}}

	/** Hard-decision GFSK demodulator followed by Bluetooth (64,30) access-code validation. */
	static final class BrAccessDecoder {
		private static final long GENERATOR=0x585713da9L;
		private static final byte[] PN=bitsFromHex("3F2A33DD69B121C1");
		private static final int[] FEC_CODEWORDS=buildFecCodewords();

		static List<BluetoothClassicPacket> decode(float[] iq,int samples,double rate,int channel,long frequency,long captureMillis){
			return decode(iq,samples,rate,channel,frequency,captureMillis,new HashMap<Integer,BluetoothClassicPacket.FhsData>(),
					new HashMap<Integer,BluetoothClassicPacket.FhsData>());}
		static List<BluetoothClassicPacket> decode(float[] iq,int samples,double rate,int channel,long frequency,long captureMillis,
				Map<Integer,BluetoothClassicPacket.FhsData> identities,Map<Integer,BluetoothClassicPacket.FhsData> inquiryIdentities){
			List<BluetoothClassicPacket> out=new ArrayList<BluetoothClassicPacket>();if(iq==null||samples<70||rate<1_100_000d)return out;
			double sps=rate/1_000_000d;int phases=rate>=3_000_000d?4:8;Map<Integer,Long> local=new HashMap<Integer,Long>();
			Map<Integer,Integer> localIndexes=new HashMap<Integer,Integer>();
			for(int timing=0;timing<phases;timing++){double phase=timing*sps/phases;int count=(int)Math.floor((samples-phase-1)/sps);if(count<68)continue;
				byte[] values=demodulate(iq,samples,sps,phase,count);for(boolean invert:new boolean[]{false,true})
					scan(values,invert,iq,samples,rate,sps,phase,channel,frequency,captureMillis,identities,inquiryIdentities,local,localIndexes,out);}
			return out;
		}

		private static byte[] demodulate(float[] iq,int samples,double sps,double phase,int count){byte[] values=new byte[count];
			for(int bit=0;bit<count;bit++){int first=Math.max(1,(int)Math.round(phase+bit*sps));
				int end=Math.min(samples,(int)Math.round(phase+(bit+1)*sps));double imaginary=0d;
				for(int n=first;n<end;n++){double pi=iq[(n-1)*2],pq=iq[(n-1)*2+1],ci=iq[n*2],cq=iq[n*2+1];imaginary+=cq*pi-ci*pq;}
				values[bit]=(byte)(imaginary>=0d?1:0);}return values;}

		private static void scan(byte[] values,boolean invert,float[] iq,int samples,double rate,double sps,double phase,
				int channel,long frequency,long captureMillis,Map<Integer,BluetoothClassicPacket.FhsData> identities,
				Map<Integer,BluetoothClassicPacket.FhsData> inquiryIdentities,
				Map<Integer,Long> local,Map<Integer,Integer> localIndexes,
				List<BluetoothClassicPacket> out){
			for(int at=0;at+68<=values.length;at++){
				int first=(values[at]^(invert?1:0))&1;if(((values[at+1]^(invert?1:0))&1)==first
						||((values[at+2]^(invert?1:0))&1)!=first||((values[at+3]^(invert?1:0))&1)==first)continue;
				int lap=0;for(int n=0;n<24;n++)lap|=((values[at+4+34+n]^(invert?1:0))&1)<<n;
				int[] b=(lap&0x800000)==0?new int[]{0,0,1,1,0,1}:new int[]{1,1,0,0,1,0};int barkerErrors=0;
				for(int n=0;n<6;n++)if(((values[at+62+n]^(invert?1:0))&1)!=b[n])barkerErrors++;
				if(barkerErrors>1)continue;
				byte[] expected=accessBits(lap,true);int errors=0;for(int n=0;n<68;n++)if(((values[at+n]^(invert?1:0))&1)!=expected[n]&&++errors>2)break;
				if(errors>2)continue;boolean trailer=false;if(at+72<=values.length){int trailerErrors=0;for(int n=68;n<72;n++)if(((values[at+n]^(invert?1:0))&1)!=expected[n])trailerErrors++;trailer=trailerErrors<=1;}
				BluetoothClassicPacket.FhsData fhs=lap==0x9e8b33&&trailer?decodeInquiryFhs(values,invert,at):null;
				if(fhs!=null&&fhs.eir)inquiryIdentities.put(Integer.valueOf(channel),fhs);
				BluetoothClassicPacket.FhsData identity=lap==0x9e8b33?inquiryIdentities.get(Integer.valueOf(channel)):identities.get(Integer.valueOf(lap));int knownUap=lap==0x9e8b33?0:identity==null?-1:identity.uap;
				BluetoothClassicPacket.HeaderData header=fhs==null&&trailer&&knownUap>=0?decodeHeader(values,invert,at,knownUap):null;
				long sample=Math.round(phase+at*sps),signature=((long)lap<<1)|(trailer?1:0);Integer key=Integer.valueOf((int)(signature^(signature>>>32)));
				Long old=local.get(key);if(old!=null&&Math.abs(sample-old.longValue())<rate*.002d){Integer index=localIndexes.get(key);
					if((fhs==null&&header==null)||index==null||out.get(index.intValue()).fhsValid||out.get(index.intValue()).headerValid)continue;double[] replacementMetric=metrics(iq,samples,
							phase+at*sps,366*sps,rate);out.set(index.intValue(),packet(channel,frequency,lap,errors,trailer,
							replacementMetric,captureMillis,fhs,header,identity));continue;}
				local.put(key,Long.valueOf(sample));localIndexes.put(key,Integer.valueOf(out.size()));
				double[] metric=metrics(iq,samples,phase+at*sps,(fhs!=null?366:(header!=null?126:(trailer?72:68)))*sps,rate);
				out.add(packet(channel,frequency,lap,errors,trailer,metric,captureMillis,fhs,header,identity));
				at+=fhs!=null?365:header!=null?125:67;}
		}
		private static BluetoothClassicPacket packet(int channel,long frequency,int lap,int errors,boolean trailer,
				double[] metric,long captureMillis,BluetoothClassicPacket.FhsData fhs,BluetoothClassicPacket.HeaderData header,
				BluetoothClassicPacket.FhsData identity){long decoded=captureMillis>0?captureMillis:System.currentTimeMillis();
			return fhs!=null?new BluetoothClassicPacket(channel,frequency,lap,errors,trailer,metric[0],metric[1],
					Math.max(0d,100d-errors*20d),decoded,fhs):new BluetoothClassicPacket(channel,frequency,lap,errors,trailer,
					metric[0],metric[1],Math.max(0d,100d-errors*20d),decoded,header,identity);}

		/** Decode a piconet header only when its UAP has already been learned from an FHS identity. */
		private static BluetoothClassicPacket.HeaderData decodeHeader(byte[] values,boolean invert,int at,int uap){
			final int headerAt=at+72;if(headerAt+54>values.length)return null;int[] encoded=new int[18];
			for(int n=0;n<18;n++){int ones=0;for(int r=0;r<3;r++)ones+=(values[headerAt+n*3+r]^(invert?1:0))&1;
				encoded[n]=ones>=2?1:0;}
			BluetoothClassicPacket.HeaderData sole=null,verified=null;int matches=0,verifiedMatches=0;
			for(int x=0;x<32;x++){Whitening whitening=new Whitening(0x60|x);int[] clear=new int[18];
				for(int n=0;n<18;n++)clear[n]=encoded[n]^whitening.next();int header=readLsb(clear,0,10);
				if(readLsb(clear,10,8)!=hec8(header,uap))continue;matches++;int type=(header>>>3)&15;
				BluetoothClassicPacket.HeaderData candidate=decodeAcl(values,invert,headerAt+54,header,x,uap,type);
				if(candidate==null)candidate=new BluetoothClassicPacket.HeaderData(header,x,uap,typeName(type),0,-1,-1,
						false,false,false,"HEC-valid packet header",new byte[0]);sole=candidate;
				if(candidate.payloadValid){verified=candidate;verifiedMatches++;}}
			if(verifiedMatches==1)return verified;return matches==1?sole:null;
		}

		private static BluetoothClassicPacket.HeaderData decodeAcl(byte[] values,boolean invert,int payloadAt,int header,
				int whiteningSeed,int uap,int type){boolean fec;int headerBytes,maxLength;
			switch(type){case 3:fec=true;headerBytes=1;maxLength=17;break;case 4:fec=false;headerBytes=1;maxLength=27;break;
				case 10:fec=true;headerBytes=2;maxLength=121;break;case 11:fec=false;headerBytes=2;maxLength=183;break;
				case 14:fec=true;headerBytes=2;maxLength=224;break;case 15:fec=false;headerBytes=2;maxLength=339;break;
				default:return new BluetoothClassicPacket.HeaderData(header,whiteningSeed,uap,typeName(type),0,-1,-1,
						false,false,false,"HEC-valid packet header",new byte[0]);}
			int maxBits=(headerBytes+maxLength+4+2)*8,available=Math.max(0,values.length-payloadAt),corrections=0;
			int clearCount=fec?Math.min(((maxBits+9)/10)*10,(available/15)*10):Math.min(maxBits,available);if(clearCount<headerBytes*8)return null;
			int[] clear=new int[clearCount];if(fec){int blocks=clearCount/10;for(int block=0;block<blocks;block++){
				int received=0;for(int n=0;n<15;n++)received|=((values[payloadAt+block*15+n]^(invert?1:0))&1)<<n;
				int best=-1,distance=16;for(int info=0;info<1024;info++){int d=Integer.bitCount(received^FEC_CODEWORDS[info]);
					if(d<distance){distance=d;best=info;if(d==0)break;}}
				if(distance>1)return null;corrections+=distance;for(int n=0;n<10;n++)clear[block*10+n]=(best>>>n)&1;}}
			else for(int n=0;n<clearCount;n++)clear[n]=(values[payloadAt+n]^(invert?1:0))&1;
			Whitening whitening=new Whitening(0x60|whiteningSeed);for(int n=0;n<18;n++)whitening.next();
			for(int n=0;n<clear.length;n++)clear[n]^=whitening.next();int llid=readLsb(clear,0,2);
			boolean payloadFlow=clear[2]!=0;int length=readLsb(clear,3,headerBytes==1?5:10);
			if(length>maxLength)return null;int protectedBits=(headerBytes+length)*8;boolean valid=false,mic=false;
			if(clear.length>=protectedBits+16&&crc16(clear,0,protectedBits,uap)==readLsb(clear,protectedBits,16))valid=true;
			else if(clear.length>=protectedBits+48&&crc16(clear,0,protectedBits+32,uap)==readLsb(clear,protectedBits+32,16)){valid=true;mic=true;}
			byte[] payload=clear.length>=protectedBits?bytes(clear,headerBytes*8,length):new byte[0];String name=valid?brAclType(type):typeName(type);
			String info=valid?payloadInfo(llid,payload,payloadFlow,mic):"HEC-valid "+typeName(type)+" header; payload CRC unavailable/invalid";
			return new BluetoothClassicPacket.HeaderData(header,whiteningSeed,uap,name,corrections,llid,length,payloadFlow,
					valid,mic,info,payload);
		}
		private static byte[] bytes(int[] bits,int at,int count){byte[] out=new byte[count];for(int b=0;b<count;b++)
			out[b]=(byte)readLsb(bits,at+b*8,8);return out;}
		private static String payloadInfo(int llid,byte[] payload,boolean flow,boolean mic){String suffix=", flow "+(flow?"GO":"STOP")+(mic?", AES-CCM MIC present":"");
			if(llid==1)return "CRC-valid ACL-U L2CAP continuation, "+payload.length+" byte(s)"+suffix;
			if(llid==2){if(payload.length>=4){int length=(payload[0]&0xff)|((payload[1]&0xff)<<8),cid=(payload[2]&0xff)|((payload[3]&0xff)<<8);
				return String.format(Locale.US,"CRC-valid ACL-U L2CAP start, length %d, CID 0x%04X (%s)%s",length,cid,
						BluetoothLeDataPacket.cidName(cid),suffix);}return "CRC-valid ACL-U L2CAP start, "+payload.length+" byte(s)"+suffix;}
			if(llid==3){int opcode=payload.length==0?-1:(payload[0]&0xff)>>>1;return "CRC-valid ACL-C LMP"
					+(opcode<0?"":String.format(Locale.US," opcode 0x%02X",opcode))+suffix;}
			return "CRC-valid ACL payload with reserved LLID"+suffix;}
		private static String brAclType(int type){switch(type){case 3:return"DM1";case 4:return"DH1";case 10:return"DM3";
			case 11:return"DH3";case 14:return"DM5";case 15:return"DH5";default:return typeName(type);}}
		private static String typeName(int type){switch(type){case 0:return"NULL";case 1:return"POLL";case 2:return"FHS";
			case 3:return"DM1";case 4:return"DH1 / 2-DH1";case 5:return"HV1";case 6:return"HV2 / 2-EV3";
			case 7:return"HV3 / EV3 / 3-EV3";case 8:return"DV / 3-DH1";case 9:return"AUX1";
			case 10:return"DM3 / 2-DH3";case 11:return"DH3 / 3-DH3";case 12:return"EV4 / 2-EV5";
			case 13:return"EV5 / 3-EV5";case 14:return"DM5 / 2-DH5";case 15:return"DH5 / 3-DH5";
			default:return"TYPE "+type;}}

		/** Decode the inquiry-response FHS following a GIAC. HEC and payload CRC must both pass. */
		private static BluetoothClassicPacket.FhsData decodeInquiryFhs(byte[] values,boolean invert,int at){
			final int headerAt=at+72,payloadAt=headerAt+54;if(payloadAt+240>values.length)return null;
			int[] encodedHeader=new int[18];for(int n=0;n<18;n++){int ones=0;for(int r=0;r<3;r++)
				ones+=(values[headerAt+n*3+r]^(invert?1:0))&1;encodedHeader[n]=ones>=2?1:0;}
			for(int x=0;x<32;x++){Whitening whitening=new Whitening(0x60|x);int[] headerBits=new int[18];
				for(int n=0;n<18;n++)headerBits[n]=encodedHeader[n]^whitening.next();int header=readLsb(headerBits,0,10);
				if(((header>>>3)&15)!=2)continue;int hec=hec8(header,0),hecErrors=0;
				for(int n=0;n<8;n++)if(headerBits[10+n]!=((hec>>>n)&1))hecErrors++;
				if(hecErrors!=0)continue;int[] payload=new int[160];int corrections=0;boolean valid=true;
				for(int block=0;block<16;block++){int received=0;for(int n=0;n<15;n++)
					received|=((values[payloadAt+block*15+n]^(invert?1:0))&1)<<n;
					int best=-1,distance=16;for(int info=0;info<1024;info++){int d=Integer.bitCount(received^FEC_CODEWORDS[info]);
						if(d<distance){distance=d;best=info;if(d==0)break;}}
					if(distance>1){valid=false;break;}corrections+=distance;for(int n=0;n<10;n++)payload[block*10+n]=(best>>>n)&1;}
				if(!valid)continue;for(int n=0;n<payload.length;n++)payload[n]^=whitening.next();
				int expectedCrc=crc16(payload,0,144,0),receivedCrc=readLsb(payload,144,16);
				if(expectedCrc!=receivedCrc)continue;int fhsLap=readLsb(payload,34,24),sp=readLsb(payload,62,2);
				if(fhsLap==0||sp!=2)continue;int uap=readLsb(payload,64,8),nap=readLsb(payload,72,16);
				int deviceClass=readLsb(payload,88,24),sr=readLsb(payload,60,2);long clock=readLsb(payload,115,26)&0x3ffffffL;
				return new BluetoothClassicPacket.FhsData(header,x,corrections,fhsLap,uap,nap,deviceClass,
						payload[58]!=0,sr,clock);}
			return null;
		}

		/** Header Error Check in the byte notation used by the Core sample vectors. */
		static int hec8(int header,int uap){int state=uap&0xff;for(int n=0;n<10;n++){int feedback=((state>>>7)&1)^((header>>>n)&1);
			state=(state<<1)&0xff;if(feedback!=0)state^=0xa7;}return reverse(state,8);}
		/** BR/EDR CRC-CCITT; input and returned value both use over-the-air LSB-first field notation. */
		static int crc16(int[] bits,int at,int count,int uap){int state=uap&0xff;for(int n=0;n<count;n++){
			int feedback=((state>>>15)&1)^(bits[at+n]&1);state=(state<<1)&0xffff;if(feedback!=0)state^=0x1021;}
			return reverse(state,16);}
		static int fecCodeword(int info){int state=0,word=info&0x3ff;for(int n=0;n<10;n++){int feedback=((state>>>4)&1)^((info>>>n)&1);
			state=(state<<1)&31;if(feedback!=0)state^=0x15;}for(int n=0;n<5;n++)word|=((state>>>(4-n))&1)<<(10+n);return word;}
		private static int[] buildFecCodewords(){int[] words=new int[1024];for(int n=0;n<words.length;n++)words[n]=fecCodeword(n);return words;}
		static int[] whiteningBits(int seed,int count){Whitening w=new Whitening(seed);int[] out=new int[count];for(int n=0;n<count;n++)out[n]=w.next();return out;}
		private static int readLsb(int[] bits,int at,int count){int value=0;for(int n=0;n<count;n++)value|=(bits[at+n]&1)<<n;return value;}
		private static int reverse(int value,int bits){int out=0;for(int n=0;n<bits;n++)out=(out<<1)|((value>>>n)&1);return out;}
		private static final class Whitening {int state;Whitening(int seed){state=seed&0x7f;}int next(){int out=(state>>>6)&1;
			state=((state<<1)&0x7f)|out;if(out!=0)state^=0x10;return out;}}

		static byte[] accessBits(int lap,boolean trailer){int a23=(lap>>>23)&1;byte[] x=new byte[30];
			for(int n=0;n<24;n++)x[n]=(byte)((lap>>>n)&1);int[] b=a23==0?new int[]{0,0,1,1,0,1}:new int[]{1,1,0,0,1,0};
			for(int n=0;n<6;n++)x[24+n]=(byte)b[n];long dividend=0L;
			for(int n=0;n<30;n++)if((x[n]^PN[34+n])!=0)dividend|=1L<<(n+34);
			for(int degree=63;degree>=34;degree--)if((dividend&(1L<<degree))!=0)dividend^=GENERATOR<<(degree-34);
			byte[] bits=new byte[trailer?72:68];int c0=(int)(dividend&1L);int[] preamble=c0==0?new int[]{0,1,0,1}:new int[]{1,0,1,0};
			for(int n=0;n<4;n++)bits[n]=(byte)preamble[n];for(int n=0;n<64;n++){
				int code=n<34?(int)((dividend>>>n)&1L):(x[n-34]^PN[n]);bits[4+n]=(byte)(code^PN[n]);}
			if(trailer){int[] tail=a23==0?new int[]{0,1,0,1}:new int[]{1,0,1,0};for(int n=0;n<4;n++)bits[68+n]=(byte)tail[n];}
			return bits;
		}

		private static byte[] bitsFromHex(String hex){byte[] out=new byte[hex.length()*4];int at=0;for(int n=0;n<hex.length();n++){
			int value=Character.digit(hex.charAt(n),16);for(int bit=3;bit>=0;bit--)out[at++]=(byte)((value>>>bit)&1);}return out;}
		private static double[] metrics(float[] iq,int samples,double start,double span,double rate){int first=Math.max(1,(int)Math.floor(start)),end=Math.min(samples,(int)Math.ceil(start+span));double power=0d,real=0d,imag=0d;int count=0;
			for(int n=first;n<end;n++){double pi=iq[(n-1)*2],pq=iq[(n-1)*2+1],ci=iq[n*2],cq=iq[n*2+1];power+=ci*ci+cq*cq;real+=ci*pi+cq*pq;imag+=cq*pi-ci*pq;count++;}
			return new double[]{count==0?-120d:10d*Math.log10(Math.max(1e-15,power/(count*2d))),Math.atan2(imag,real)*rate/(2d*Math.PI)};}
	}
}
