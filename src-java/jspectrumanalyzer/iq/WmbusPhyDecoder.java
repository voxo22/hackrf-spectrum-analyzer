package jspectrumanalyzer.iq;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Self-contained EN 13757-4 S/C/T receive PHY and data-link CRC decoder. */
final class WmbusPhyDecoder {
	static final long S_FREQUENCY_HZ = 868_300_000L;
	static final long CT_FREQUENCY_HZ = 868_950_000L;
	private static final int CT_BIT_RATE = 100_000;
	private static final int S_CHIP_RATE = 32_768;
	static final int CHANNEL_OUTPUT_RATE = 500_000;
	static final int CT_OCCUPIED_BANDWIDTH = 240_000;
	static final int S_OCCUPIED_BANDWIDTH = 150_000;
	private static final int MAX_FRAME_BYTES = 300;
	private static final int[] NORMAL_CLOCK_PPM={0,-750,750,-1500,1500};
	/* The supplied LSE HCA capture runs about +6.75 kppm from the nominal
	 * 32.768 kchip/s clock.  rtl_433's edge slicer follows that drift; the
	 * integrate-and-dump receiver has to acquire it explicitly. */
	private static final int[] S_CLOCK_PPM={0,-750,750,-1500,1500,7500,-7500};
	private static final int[] THREE_OF_SIX = {
		0x16,0x0d,0x0e,0x0b,0x1c,0x19,0x1a,0x13,
		0x2c,0x25,0x26,0x23,0x34,0x31,0x32,0x29
	};

	Decoded decodeCt(long centerFrequencyHz, int sampleRateHz, byte[] iq, int length) {
		return decodeChannel(centerFrequencyHz, sampleRateHz, iq, length, CT_FREQUENCY_HZ,
				new int[] {CT_BIT_RATE}, false);
	}

	Decoded decodeS(long centerFrequencyHz, int sampleRateHz, byte[] iq, int length) {
		/* Mode S is 32.768 kchip/s on air. Manchester coding therefore carries
		 * 16.384 kbit/s of link data. */
		return decodeChannel(centerFrequencyHz, sampleRateHz, iq, length, S_FREQUENCY_HZ,
				new int[] {S_CHIP_RATE}, true);
	}

	List<Decoded> decodeCtAll(long centerFrequencyHz, int sampleRateHz, byte[] iq, int length) {
		return decodeChannelAll(centerFrequencyHz,sampleRateHz,iq,length,CT_FREQUENCY_HZ,
				CT_BIT_RATE,false);
	}

	List<Decoded> decodeSAll(long centerFrequencyHz, int sampleRateHz, byte[] iq, int length) {
		return decodeChannelAll(centerFrequencyHz,sampleRateHz,iq,length,S_FREQUENCY_HZ,
				S_CHIP_RATE,true);
	}

	List<Decoded> decodeCtBasebandAll(float[] iq,int samples,double sampleRateHz){
		return decodeBasebandAll(iq,samples,sampleRateHz,CT_BIT_RATE,false,false);
	}

	List<Decoded> decodeSBasebandAll(float[] iq,int samples,double sampleRateHz){
		return decodeBasebandAll(iq,samples,sampleRateHz,S_CHIP_RATE,true,false);
	}

	List<Decoded> decodeSBasebandAllClockTolerant(float[] iq,int samples,double sampleRateHz){
		return decodeBasebandAll(iq,samples,sampleRateHz,S_CHIP_RATE,true,true);
	}

	private static Decoded decodeChannel(long centerFrequencyHz, int sampleRateHz, byte[] iq,
			int length, long channelFrequencyHz, int[] bitRates, boolean sMode) {
		/* 250 ks/s captures are common for the 100 kchip/s C/T channel. This is
		 * only 2.5 samples/chip, but the fractional integrate-and-dump sampler
		 * below can still acquire it; do not reject it before attempting CRC. */
		if (iq == null || sampleRateHz < 200_000
				|| Math.abs(channelFrequencyHz-centerFrequencyHz) >= sampleRateHz/2L)
			return Decoded.NONE;
		int inputSamples=Math.min(length,iq.length)/2;
		if(inputSamples<256)return Decoded.NONE;
		Demodulated demodulated=demodulate(centerFrequencyHz,sampleRateHz,iq,inputSamples,
				channelFrequencyHz,sMode?S_OCCUPIED_BANDWIDTH:CT_OCCUPIED_BANDWIDTH);
		if(demodulated.length<128)return Decoded.NONE;
		for(int nominalRate:bitRates){
			for(int ppm:sMode?S_CLOCK_PPM:NORMAL_CLOCK_PPM){
				double bitRate=nominalRate*(1d+ppm/1_000_000d);
				double samplesPerBit=demodulated.rate/bitRate;
				int phases=Math.max(1,(int)Math.ceil(samplesPerBit));
				for(int phase=0;phase<phases;phase++){
					double[] soft=softBits(demodulated,phase,samplesPerBit);
					if(soft.length<(sMode?24+12*2*8:32+12*8))continue;
					for(double threshold:thresholds(soft)){
						boolean[] bits=hardBits(soft,threshold,false);
						Decoded decoded=sMode?searchS(bits):searchCt(bits);
						if(decoded.valid)return decoded;
						decoded=sMode?searchS(hardBits(soft,threshold,true))
								:searchCt(hardBits(soft,threshold,true));
						if(decoded.valid)return decoded;
					}
				}
			}
		}
		return Decoded.NONE;
	}

	private static List<Decoded> decodeChannelAll(long centerFrequencyHz,int sampleRateHz,byte[] iq,
			int length,long channelFrequencyHz,int bitRate,boolean sMode){
		List<Decoded> empty=new ArrayList<Decoded>();
		if(iq==null||sampleRateHz<200_000
				||Math.abs(channelFrequencyHz-centerFrequencyHz)>=sampleRateHz/2L)return empty;
		int inputSamples=Math.min(length,iq.length)/2;if(inputSamples<256)return empty;
		Demodulated demodulated=demodulate(centerFrequencyHz,sampleRateHz,iq,inputSamples,
				channelFrequencyHz,sMode?S_OCCUPIED_BANDWIDTH:CT_OCCUPIED_BANDWIDTH);
		/* The ordinary replay/live path must stay bounded.  Large clock-error and
		 * burst-local threshold searches are reserved for a positively routed,
		 * completed S-mode capture (decodeSBasebandAllClockTolerant). */
		return decodeDemodulatedAll(demodulated,bitRate,sMode,false);
	}

	private static List<Decoded> decodeBasebandAll(float[] iq,int samples,double sampleRateHz,
			int bitRate,boolean sMode,boolean clockTolerant){
		if(iq==null||samples<128||sampleRateHz<=0d)return new ArrayList<Decoded>();
		return decodeDemodulatedAll(demodulate(iq,Math.min(samples,iq.length/2),sampleRateHz),
				bitRate,sMode,clockTolerant);
	}

	private static List<Decoded> decodeDemodulatedAll(Demodulated demodulated,int bitRate,boolean sMode,
			boolean clockTolerant){
		if(demodulated.length<128)return new ArrayList<Decoded>();
		LinkedHashMap<String,Decoded> unique=new LinkedHashMap<String,Decoded>();
		int[] ppmValues=sMode&&clockTolerant?S_CLOCK_PPM:NORMAL_CLOCK_PPM;
		for(int ppmIndex=0;ppmIndex<ppmValues.length;ppmIndex++){
			double actualRate=bitRate*(1d+ppmValues[ppmIndex]/1_000_000d);
			double samplesPerBit=demodulated.rate/actualRate;
			int phases=Math.max(1,(int)Math.ceil(samplesPerBit));
			for(int phase=0;phase<phases;phase++){
				double[] soft=softBits(demodulated,phase,samplesPerBit);
				if(soft.length<(sMode?24+12*2*8:32+12*8))continue;
				for(double threshold:thresholds(soft)){
					collect(unique,sMode?searchSAll(hardBits(soft,threshold,false))
							:searchCtAll(hardBits(soft,threshold,false)),demodulated,phase,samplesPerBit);
					collect(unique,sMode?searchSAll(hardBits(soft,threshold,true))
							:searchCtAll(hardBits(soft,threshold,true)),demodulated,phase,samplesPerBit);
				}
			}
			/* A single S-mode meter telegram can occupy less than a tenth of a long
			 * replay/live history.  Its two FSK levels then disappear in the global
			 * discriminator quantiles.  Retry nominal timing with overlapping local
			 * windows, equivalent to deriving the slicer threshold from the burst. */
			if(sMode&&clockTolerant&&unique.isEmpty()
					&&(ppmIndex==0||Math.abs(ppmValues[ppmIndex])>=6000))
					for(int phase=0;phase<phases;phase++){
				double[] soft=softBits(demodulated,phase,samplesPerBit);
				collectSWindows(unique,soft,demodulated,phase,samplesPerBit);
			}
			/* Nominal timing decodes every standards-compliant transmitter and is
			 * substantially cheaper.  Alternate rates are a fallback for captures
			 * whose sample clock metadata is inaccurate. */
			if(ppmIndex==0&&!unique.isEmpty())break;
		}
		return new ArrayList<Decoded>(unique.values());
	}

	private static void collectSWindows(LinkedHashMap<String,Decoded> unique,double[] soft,
			Demodulated demodulated,int phase,double samplesPerBit){
		final int window=4096,step=window/2,minimum=24+12*2*8;
		if(soft.length<minimum)return;
		for(int start=0;;start+=step){
			int end=Math.min(soft.length,start+window);
			if(end-start<minimum){start=Math.max(0,soft.length-window);end=soft.length;}
			double[] local=Arrays.copyOfRange(soft,start,end);
			for(double threshold:thresholds(local)){
				collectShifted(unique,searchSAll(hardBits(local,threshold,false)),start,
						demodulated,phase,samplesPerBit);
				collectShifted(unique,searchSAll(hardBits(local,threshold,true)),start,
						demodulated,phase,samplesPerBit);
			}
			if(end==soft.length)break;
		}
	}

	private static void collectShifted(LinkedHashMap<String,Decoded> unique,List<Found> decoded,
			int bitShift,Demodulated demodulated,int phase,double samplesPerBit){
		List<Found> shifted=new ArrayList<Found>(decoded.size());
		for(Found value:decoded)shifted.add(new Found(value.decoded,value.bitOffset+bitShift));
		collect(unique,shifted,demodulated,phase,samplesPerBit);
	}

	private static void collect(LinkedHashMap<String,Decoded> unique,List<Found> decoded,
			Demodulated demodulated,int phase,double samplesPerBit){
		for(Found found:decoded){
			Decoded value=found.decoded.withLevel(frameLevelDbfs(demodulated,phase,samplesPerBit,
					found.bitOffset,found.decoded));
			String key=value.mode+":"+value.payloadHex;Decoded previous=unique.get(key);
			if(previous==null||Double.isNaN(previous.levelDbfs)||value.levelDbfs>previous.levelDbfs)
				unique.put(key,value);
		}
	}

	private static double frameLevelDbfs(Demodulated input,int phase,double samplesPerBit,
			int bitOffset,Decoded decoded){
		int cleanBytes=decoded.payloadHex.length()/2;
		int rawBytes=cleanBytes+2;
		if("A".equals(decoded.format))rawBytes+=Math.max(0,(cleanBytes-10+15)/16)*2;
		else if(cleanBytes>126)rawBytes+=2;
		double airBits;
		if("S".equals(decoded.mode))airBits=24d+rawBytes*16d;
		else if("T".equals(decoded.mode))airBits=16d+rawBytes*12d;
		else airBits=32d+rawBytes*8d;
		int first=Math.max(0,(int)Math.floor(phase+bitOffset*samplesPerBit));
		int last=Math.min(input.length,(int)Math.ceil(phase+(bitOffset+airBits)*samplesPerBit));
		if(last<=first)return Double.NaN;
		double sum=0d;for(int n=first;n<last;n++)sum+=input.level[n];
		return 10d*Math.log10(Math.max(1e-15,sum/(last-first)));
	}

	private static Demodulated demodulate(long centerFrequencyHz,int sampleRateHz,byte[] iq,
			int inputSamples,long channelFrequencyHz,int occupiedBandwidthHz){
		IQSubchannelExtractor.Result channel=IQSubchannelExtractor.extract(iq,inputSamples*2,
				sampleRateHz,channelFrequencyHz-centerFrequencyHz,occupiedBandwidthHz,
				CHANNEL_OUTPUT_RATE);
		return demodulate(channel.iq,channel.samples,channel.sampleRateHz);
	}

	private static Demodulated demodulate(float[] iq,int samples,double sampleRateHz){
		double[] discriminator=new double[samples];
		double[] level=new double[samples];
		for(int n=0;n<samples;n++){
			double re=iq[n*2],im=iq[n*2+1];
			level[n]=re*re+im*im;
			if(n>0){double previousRe=iq[(n-1)*2],previousIm=iq[(n-1)*2+1];
				discriminator[n]=Math.atan2(im*previousRe-re*previousIm,
						re*previousRe+im*previousIm);}
		}
		return new Demodulated(discriminator,level,samples,sampleRateHz);
	}

	private static double[] softBits(Demodulated input,int phase,double samplesPerBit){
		int count=Math.max(0,(int)((input.length-phase)/samplesPerBit));
		double[] soft=new double[count];
		for(int bit=0;bit<count;bit++){
			int first=(int)Math.ceil(phase+(bit+.16d)*samplesPerBit);
			int last=Math.min(input.length,(int)Math.floor(phase+(bit+.84d)*samplesPerBit));
			double sum=0d,weight=0d;
			for(int n=Math.max(1,first);n<last;n++){
				double w=Math.max(1e-9,Math.min(input.level[n],input.level[n-1]));
				sum+=input.discriminator[n]*w;weight+=w;
			}
			soft[bit]=weight==0d?0d:sum/weight;
		}
		return soft;
	}

	private static double[] thresholds(double[] soft){
		/* Quantiles do not need every bit from a long capture.  Sampling keeps the
		 * acquisition cost bounded when dozens of telegrams share one IQ window. */
		int count=Math.min(4096,soft.length);double[] sorted=new double[count];
		for(int n=0;n<count;n++)sorted[n]=soft[(int)((long)n*soft.length/count)];
		Arrays.sort(sorted);
		double low=sorted[sorted.length/5],high=sorted[sorted.length*4/5];
		double middle=(low+high)/2d,step=Math.max(.006d,(high-low)*.12d);
		return new double[]{middle,middle-step,middle+step,0d};
	}

	private static boolean[] hardBits(double[] soft,double threshold,boolean inverted){
		boolean[] bits=new boolean[soft.length];
		for(int n=0;n<bits.length;n++)bits[n]=(soft[n]>threshold)^inverted;
		return bits;
	}

	private static Decoded searchCt(boolean[] bits){
		for(int at=0;at+32+96<=bits.length;at++){
			if(read(bits,at,16)!=0x543d)continue;
			int marker=read(bits,at+16,16);
			if(marker==0x54cd){
				Decoded decoded=parseFormatA(bytes(bits,at+32));if(decoded.valid)return decoded.withMode("C");
			}else if(marker==0x543d){
				Decoded decoded=parseFormatB(bytes(bits,at+32));if(decoded.valid)return decoded.withMode("C");
			}
			/* Mode T shares 0x543d, followed immediately by a 3-of-6 telegram. */
			Decoded decoded=parseFormatA(decodeThreeOfSix(bits,at+16));
			if(decoded.valid)return decoded.withMode("T");
		}
		return Decoded.NONE;
	}

	private static List<Found> searchCtAll(boolean[] bits){
		List<Found> out=new ArrayList<Found>();
		for(int at=0;at+32+96<=bits.length;at++){
			if(read(bits,at,16)!=0x543d)continue;
			int marker=read(bits,at+16,16);
			if(marker==0x54cd){Decoded value=parseFormatA(bytes(bits,at+32));
				if(value.valid)out.add(new Found(value.withMode("C"),at));}
			else if(marker==0x543d){Decoded value=parseFormatB(bytes(bits,at+32));
				if(value.valid)out.add(new Found(value.withMode("C"),at));}
			Decoded value=parseFormatA(decodeThreeOfSix(bits,at+16));
			if(value.valid)out.add(new Found(value.withMode("T"),at));
		}
		return out;
	}

	private static Decoded searchS(boolean[] bits){
		for(int at=0;at+24+12*2*8<=bits.length;at++){
			if(read(bits,at,24)!=0x547696)continue;
			for(boolean reversed:new boolean[]{false,true}){
				boolean[] decodedBits=decodeManchester(bits,at+24,reversed);
				Decoded decoded=parseFormatA(bytes(decodedBits,0));
				if(decoded.valid)return decoded.withMode("S");
			}
		}
		return Decoded.NONE;
	}

	private static List<Found> searchSAll(boolean[] bits){
		List<Found> out=new ArrayList<Found>();
		for(int at=0;at+24+12*2*8<=bits.length;at++){
			if(read(bits,at,24)!=0x547696)continue;
			for(boolean reversed:new boolean[]{false,true}){
				boolean[] decodedBits=decodeManchester(bits,at+24,reversed);
				Decoded value=parseFormatA(bytes(decodedBits,0));
				if(value.valid)out.add(new Found(value.withMode("S"),at));
			}
		}
		return out;
	}

	private static boolean[] decodeManchester(boolean[] bits,int offset,boolean reversed){
		boolean[] out=new boolean[(bits.length-offset)/2];int count=0;
		for(int at=offset;at+1<bits.length;at+=2){
			boolean first=bits[at],second=bits[at+1];if(first==second)break;
			out[count++]=(first&&!second)^reversed;
		}
		return count==out.length?out:Arrays.copyOf(out,count);
	}

	private static byte[] bytes(boolean[] bits,int offset){
		int count=Math.min(MAX_FRAME_BYTES,(bits.length-offset)/8);byte[] out=new byte[count];
		for(int n=0;n<count;n++)out[n]=(byte)read(bits,offset+n*8,8);return out;
	}

	private static byte[] decodeThreeOfSix(boolean[] bits,int offset){
		int count=Math.min(MAX_FRAME_BYTES,(bits.length-offset)/12);byte[] out=new byte[count];
		for(int n=0;n<count;n++){
			int high=decodeSix(read(bits,offset+n*12,6));int low=decodeSix(read(bits,offset+n*12+6,6));
			if(high<0||low<0)return Arrays.copyOf(out,n);out[n]=(byte)((high<<4)|low);
		}
		return out;
	}

	private static int decodeSix(int value){for(int n=0;n<THREE_OF_SIX.length;n++)if(THREE_OF_SIX[n]==value)return n;return-1;}
	private static int read(boolean[] bits,int offset,int count){int value=0;for(int n=0;n<count;n++)value=(value<<1)|(bits[offset+n]?1:0);return value;}

	static Decoded parseFormatA(byte[] raw){
		if(raw.length<12)return Decoded.NONE;
		int l=raw[0]&255;if(l<9)return Decoded.NONE;
		int payloadBytes=l-9,blocks=(payloadBytes+15)/16;
		int transmitted=12+payloadBytes+blocks*2;
		if(transmitted>raw.length||!crcValid(raw,0,10))return Decoded.NONE;
		byte[] clean=new byte[10+payloadBytes];System.arraycopy(raw,0,clean,0,10);
		int source=12,destination=10,remaining=payloadBytes;
		while(remaining>0){int data=Math.min(16,remaining);if(!crcValid(raw,source,data))return Decoded.NONE;
			System.arraycopy(raw,source,clean,destination,data);source+=data+2;destination+=data;remaining-=data;}
		return metadata(clean,"A");
	}

	static Decoded parseFormatB(byte[] raw){
		if(raw.length<13)return Decoded.NONE;
		int l=raw[0]&255;if(l<12||l+1>raw.length||l==128||l==129)return Decoded.NONE;
		int firstData=Math.min(l-1,126);
		if(!crcValid(raw,0,firstData))return Decoded.NONE;
		if(l<=127)return metadata(Arrays.copyOf(raw,firstData),"B");
		int secondData=l-129,secondOffset=128;
		if(secondOffset+secondData+2>raw.length||!crcValid(raw,secondOffset,secondData))return Decoded.NONE;
		byte[] clean=new byte[firstData+secondData];System.arraycopy(raw,0,clean,0,firstData);
		System.arraycopy(raw,secondOffset,clean,firstData,secondData);
		return metadata(clean,"B");
	}

	private static Decoded metadata(byte[] telegram,String format){
		if(telegram.length<10)return Decoded.NONE;
		byte[] forwarded=forwardedLseTelegram(telegram);
		if(forwarded!=null){telegram=forwarded;format=format+" (forwarded)";}
		int manufacturerCode=(telegram[3]&255)<<8|(telegram[2]&255);
		String manufacturer=""+(char)(0x40+((manufacturerCode>>10)&31))
				+(char)(0x40+((manufacturerCode>>5)&31))+(char)(0x40+(manufacturerCode&31));
		String id=meterId(telegram);
		int control=telegram[1]&255,version=telegram[8]&255,type=telegram[9]&255;
		int ci=telegram.length>10?telegram[10]&255:-1;
		String typeName=deviceType(type),security=security(telegram,ci);
		int accessNumber=accessNumber(telegram,ci);
		WmbusApplicationDecoder.Result application=WmbusApplicationDecoder.decode(
				telegram,manufacturer,version,type,ci);
		if(application.medium.length()>0)typeName=application.medium;
		if(application.security.length()>0)security=application.security;
		String details=manufacturer+" "+id+" | "+typeName+" | version "+version
				+String.format(Locale.US," | C 0x%02X",control)
				+(ci<0?"":String.format(Locale.US," | CI 0x%02X",ci))+" | format "+format
				+" | security "+security;
		return new Decoded(true,"",format,manufacturer,id,type,typeName,version,control,ci,accessNumber,
				hex(telegram),security,application,details);
	}

	/** Some proprietary LSE S-mode frames wrap one complete, CRC-stripped
	 * meter telegram in their A0 payload.  The outer device-type byte varies
	 * in real captures and is therefore not a reliable concentrator marker. */
	private static byte[] forwardedLseTelegram(byte[] outer){
		if(outer.length<22||(outer[1]&255)!=0xc4||(outer[2]&255)!=0x65
				||(outer[3]&0x7f)!=0x32||(outer[10]&255)!=0xa0)return null;
		for(int at=11;at+10<=outer.length;at++){int length=(outer[at]&255)+1;
			if(length<11||at+length!=outer.length)continue;int control=outer[at+1]&255;
			if(control!=0x44&&control!=0x46&&control!=0x47)continue;
			int manufacturer=(outer[at+3]&255)<<8|(outer[at+2]&255);
			if(manufacturer!=0x3265)continue; // embedded LSE meter, little-endian 65 32
			int ci=outer[at+10]&255;
			if(ci!=0x72&&ci!=0x78&&ci!=0x7a&&ci!=0xa0&&ci!=0xa2)continue;
			return Arrays.copyOfRange(outer,at,outer.length);
		}
		return null;
	}

	private static String meterId(byte[] telegram){
		/* Match rtl_433's long-standing output exactly.  The address field is
		 * treated as four little-endian BCD pairs and emitted as a decimal
		 * integer, even when a proprietary frame contains a nibble A-F. */
		long value=bcdByte(telegram[7])*1_000_000L+bcdByte(telegram[6])*10_000L
				+bcdByte(telegram[5])*100L+bcdByte(telegram[4]);
		return Long.toString(value);
	}
	private static int bcdByte(byte value){int unsigned=value&255;
		return ((unsigned>>4)&15)*10+(unsigned&15);}

	private static int accessNumber(byte[] telegram,int ci){
		if(ci==0x7a)return telegram.length>11?telegram[11]&255:-1;
		if(ci==0x72)return telegram.length>19?telegram[19]&255:-1;
		if(ci==0x8c||ci==0x8d||ci==0x8e||ci==0x8f)
			return telegram.length>12?telegram[12]&255:-1;
		return -1;
	}

	private static String security(byte[] telegram,int ci){
		return security(telegram,10,0);
	}

	private static String security(byte[] telegram,int ciAt,int depth){
		if(depth>3||ciAt<0||ciAt>=telegram.length)return"unknown";
		int ci=telegram[ciAt]&255,ell=ellLength(ci);
		if(ell>=0){
			if(ciAt+1+ell>=telegram.length)return"unknown";
			if(ci==0x8d||ci==0x8f){int snAt=ciAt+(ci==0x8f?11:3);
				if(snAt+3>=telegram.length)return"unknown";
				long sn=(telegram[snAt]&255L)|((telegram[snAt+1]&255L)<<8)
						|((telegram[snAt+2]&255L)<<16)|((telegram[snAt+3]&255L)<<24);
				int mode=(int)((sn>>>29)&7);if(mode==1)return"encrypted (ELL AES-CTR)";
				if(mode!=0)return"protected/unknown (ELL mode "+mode+")";}
			return security(telegram,ciAt+1+ell,depth+1);
		}
		if(ci==0x90){if(ciAt+1>=telegram.length)return"unknown";int length=telegram[ciAt+1]&255;
			return security(telegram,ciAt+2+length,depth+1);}
		int configOffset;
		if(ci==0x7a)configOffset=ciAt+3;       // short TPL header
		else if(ci==0x72)configOffset=ciAt+11; // long TPL header
		else if(ci==0x78||ci==0x79)return "clear"; // no TPL header
		else return "unknown";
		if(configOffset+1>=telegram.length)return "unknown";
		int config=(telegram[configOffset]&255)|((telegram[configOffset+1]&255)<<8);
		int mode=(config>>8)&31;
		if(mode==0)return "clear";
		switch(mode){
		case 2:case 3:case 5:case 7:case 8:case 9:case 10:
			return "encrypted (mode "+mode+")";
		default:return "protected/unknown (mode "+mode+")";
		}
	}

	private static int ellLength(int ci){switch(ci){case 0x8c:return 2;case 0x8d:return 8;
		case 0x8e:return 10;case 0x8f:return 16;default:return-1;}}

	private static boolean crcValid(byte[] bytes,int offset,int length){
		if(offset<0||length<0||offset+length+2>bytes.length)return false;
		int expected=(bytes[offset+length]&255)<<8|(bytes[offset+length+1]&255);
		return crc(bytes,offset,length)==expected;
	}

	static int crc(byte[] bytes,int offset,int length){
		int crc=0;for(int n=0;n<length;n++){crc^=(bytes[offset+n]&255)<<8;
			for(int bit=0;bit<8;bit++)crc=(crc&0x8000)!=0?((crc<<1)^0x3d65):(crc<<1);crc&=0xffff;}
		return(~crc)&0xffff;
	}

	private static String hex(byte[] bytes){StringBuilder out=new StringBuilder(bytes.length*2);for(byte b:bytes)out.append(String.format(Locale.US,"%02X",b&255));return out.toString();}
	private static String deviceType(int type){switch(type){
		case 0x00:return"other meter";
		case 0x01:return"oil meter";
		case 0x02:return"electricity meter";
		case 0x03:return"gas meter";
		case 0x04:return"heat meter (outlet)";
		case 0x05:return"steam meter";
		case 0x06:return"warm-water meter";
		case 0x07:return"water meter";
		case 0x08:return"heat cost allocator";
		case 0x09:return"compressed-air meter";
		case 0x0a:return"cooling meter (outlet)";
		case 0x0b:return"cooling meter (inlet)";
		case 0x0c:return"heat meter (inlet)";
		case 0x0d:return"combined heat/cooling meter";
		case 0x0e:return"bus/system component";
		case 0x0f:return"unknown medium";
		case 0x14:return"calorific value";
		case 0x15:return"hot-water meter";
		case 0x16:return"cold-water meter";
		case 0x17:return"dual-register hot/cold-water meter";
		case 0x18:return"pressure meter";
		case 0x19:return"A/D converter";
		case 0x1a:return"smoke detector";
		case 0x1b:return"room sensor";
		case 0x1c:return"gas detector";
		case 0x20:return"breaker (electricity)";
		case 0x21:return"valve (gas or water)";
		case 0x25:return"customer unit (display device)";
		case 0x28:return"waste-water meter";
		case 0x29:return"waste meter";
		case 0x2a:return"carbon-dioxide sensor";
		case 0x31:return"communication controller";
		case 0x32:return"unidirectional repeater";
		case 0x33:return"bidirectional repeater";
		case 0x36:return"radio converter (system side)";
		case 0x37:return"radio converter (meter side)";
		default:return String.format(Locale.US,"reserved device type (0x%02X)",type);
	}}

	private static final class Demodulated{
		final double[] discriminator,level;final int length;final double rate;
		Demodulated(double[] discriminator,double[] level,int length,double rate){this.discriminator=discriminator;this.level=level;this.length=length;this.rate=rate;}
	}
	private static final class Found{
		final Decoded decoded;final int bitOffset;
		Found(Decoded decoded,int bitOffset){this.decoded=decoded;this.bitOffset=bitOffset;}
	}

	static final class Decoded{
		static final Decoded NONE=new Decoded(false,"","","","",-1,"",-1,-1,-1,-1,"","",
				WmbusApplicationDecoder.Result.NONE,"",Double.NaN);
		final boolean valid;final String mode,format,manufacturer,id,deviceTypeName,payloadHex;
		final String security,details;
		final WmbusApplicationDecoder.Result application;
		final int deviceType,version,control,ci,accessNumber;final double levelDbfs;
		Decoded(boolean valid,String mode,String format,String manufacturer,String id,int deviceType,
				String deviceTypeName,int version,int control,int ci,int accessNumber,String payloadHex,String security,
				WmbusApplicationDecoder.Result application,String details){this(valid,mode,format,manufacturer,id,
			deviceType,deviceTypeName,version,control,ci,accessNumber,payloadHex,security,application,details,Double.NaN);}
		Decoded(boolean valid,String mode,String format,String manufacturer,String id,int deviceType,
				String deviceTypeName,int version,int control,int ci,int accessNumber,String payloadHex,String security,
				WmbusApplicationDecoder.Result application,String details,double levelDbfs){this.valid=valid;this.mode=mode;
			this.format=format;this.manufacturer=manufacturer;this.id=id;this.deviceType=deviceType;
			this.deviceTypeName=deviceTypeName;this.version=version;this.control=control;this.ci=ci;
			this.accessNumber=accessNumber;
			this.payloadHex=payloadHex;this.security=security;this.application=application;this.levelDbfs=levelDbfs;
			this.details=details;}
		Decoded withMode(String value){return new Decoded(valid,value,format,manufacturer,id,deviceType,
				deviceTypeName,version,control,ci,accessNumber,payloadHex,security,application,details,levelDbfs);}
		Decoded withLevel(double value){return new Decoded(valid,mode,format,manufacturer,id,deviceType,
				deviceTypeName,version,control,ci,accessNumber,payloadHex,security,application,details,value);}
	}
}
