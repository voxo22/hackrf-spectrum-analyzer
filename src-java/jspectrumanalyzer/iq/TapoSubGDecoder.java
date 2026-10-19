package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Physical/link-layer decoder for TP-Link/Tapo 50 kbit/s Sub-GHz frames. */
final class TapoSubGDecoder {
	private static final int SYNC=0x66595A99;
	private static final int MAX_SYNC_ERRORS=4;

	List<Decoded> decodeAll(float[] iq,double sampleRateHz){
		float[] working=iq;double rate=sampleRateHz;
		if(sampleRateHz>=1_000_000d){int divisor=(int)Math.round(sampleRateHz/500_000d);
			if(divisor>=2&&divisor<=16){working=lowPassDecimate(iq,divisor);rate=sampleRateHz/divisor;}}
		return FskPcmDecoderSupport.decodeAll(working,rate,20d,96,
				new FskPcmDecoderSupport.FrameDecoder<Decoded>(){
			@Override public Decoded decode(boolean[] bits,FskPcmDecoderSupport.Metrics metrics){
				return frame(bits,metrics);}
			@Override public String key(Decoded value){return value.payloadHex;}
		});
	}

	private static float[] lowPassDecimate(float[] iq,int divisor){int samples=iq.length/2;
		int taps=divisor*16+1,half=taps/2,outSamples=Math.max(0,(samples-taps)/divisor);
		float[] out=new float[outSamples*2];double[] kernel=new double[taps];double cutoff=.28d/divisor,sum=0d;
		for(int n=0;n<taps;n++){int x=n-half;double sinc=x==0?2d*cutoff:
			Math.sin(2d*Math.PI*cutoff*x)/(Math.PI*x);double window=.54d-.46d*Math.cos(2d*Math.PI*n/(taps-1));
			kernel[n]=sinc*window;sum+=kernel[n];}
		for(int n=0;n<taps;n++)kernel[n]/=sum;
		for(int n=0;n<outSamples;n++){int center=n*divisor+half;double si=0d,sq=0d;
			for(int k=0;k<taps;k++){int at=center+k-half;si+=iq[at*2]*kernel[k];sq+=iq[at*2+1]*kernel[k];}
			out[n*2]=(float)si;out[n*2+1]=(float)sq;}
		return out;
	}

	private static Decoded frame(boolean[] bits,FskPcmDecoderSupport.Metrics metrics){
		for(int at=0;at+32+24<=bits.length;at++){
			int errors=0;for(int n=0;n<32;n++)if(bits[at+n]!=(((SYNC>>>(31-n))&1)!=0))errors++;
			if(errors>MAX_SYNC_ERRORS)continue;
			Decoded raw=raw(bits,at+32,metrics,errors);if(raw!=null)return raw;
			for(int phase=0;phase<2;phase++)for(int mapping=0;mapping<2;mapping++){
				Decoded manchester=manchester(bits,at+32+phase,mapping!=0,metrics,errors);
				if(manchester!=null)return manchester;
			}
		}
		return null;
	}

	private static Decoded raw(boolean[] bits,int first,FskPcmDecoderSupport.Metrics metrics,
			int syncErrors){
		int length=FskPcmDecoderSupport.byteAt(bits,first);if(length<4||length>192)return null;
		int bytes=length+1;if(first+bytes*8>bits.length)return null;byte[] frame=new byte[bytes];
		for(int n=0;n<bytes;n++)frame[n]=(byte)FskPcmDecoderSupport.byteAt(bits,first+n*8);
		return checked(frame,false,metrics,syncErrors);
	}

	private static Decoded manchester(boolean[] bits,int first,boolean invert,
			FskPcmDecoderSupport.Metrics metrics,int syncErrors){
		if(first+16>bits.length)return null;List<Boolean> decoded=new ArrayList<Boolean>();
		List<Integer> invalid=new ArrayList<Integer>();
		for(int at=first;at+1<bits.length;at+=2){boolean a=bits[at],b=bits[at+1];
			if(a==b)invalid.add(Integer.valueOf(decoded.size()));boolean value=a&&!b;
			decoded.add(Boolean.valueOf(invert?!value:value));}
		int length=byteAt(decoded,0);if(length<4||length>192)return null;int bytes=length+1,bitsNeeded=bytes*8;
		if(decoded.size()<bitsNeeded)return null;List<Integer> uncertain=new ArrayList<Integer>();
		for(Integer position:invalid)if(position.intValue()<bitsNeeded)uncertain.add(position);
		if(uncertain.size()>4)return null;byte[] frame=bytes(decoded,bytes);
		int combinations=1<<uncertain.size();for(int mask=0;mask<combinations;mask++){
			byte[] candidate=frame.clone();for(int n=0;n<uncertain.size();n++){int bit=uncertain.get(n);
				int byteAt=bit/8,flag=1<<(7-bit%8);if((mask&(1<<n))==0)candidate[byteAt]&=~flag;
				else candidate[byteAt]|=flag;}
			Decoded checked=checked(candidate,true,metrics,syncErrors);if(checked!=null)return checked;
		}
		return null;
	}

	private static int byteAt(List<Boolean> bits,int first){int value=0;
		for(int n=0;n<8;n++)value=(value<<1)|(bits.get(first+n).booleanValue()?1:0);return value;}
	private static byte[] bytes(List<Boolean> bits,int count){byte[] out=new byte[count];
		for(int n=0;n<count;n++)out[n]=(byte)byteAt(bits,n*8);return out;}

	private static Decoded checked(byte[] frame,boolean manchester,
			FskPcmDecoderSupport.Metrics metrics,int syncErrors){
		if(frame.length<5||(frame[0]&255)!=frame.length-1)return null;int length=frame.length;
		int expected=((frame[length-2]&255)<<8)|(frame[length-1]&255);
		if(crc16X25(frame,length-2)!=expected)return null;
		String firstAddress=address(frame,2),secondAddress=manchester&&frame.length>=16?address(frame,8):"";
		return new Decoded(hex(frame),frame[1]&255,manchester,firstAddress,secondAddress,
				metrics.carrierOffsetHz,metrics.deviationHz,metrics.bitMicros,metrics.levelDbfs,
				metrics.snrDb,syncErrors);
	}

	static int crc16X25(byte[] bytes,int length){int crc=0xffff;
		for(int n=0;n<length;n++){crc^=bytes[n]&255;for(int bit=0;bit<8;bit++)
			crc=(crc&1)!=0?(crc>>>1)^0x8408:crc>>>1;}return (crc^0xffff)&0xffff;}
	private static String address(byte[] frame,int first){if(first+6>frame.length-2)return "";
		StringBuilder out=new StringBuilder(17);for(int n=0;n<6;n++){if(n>0)out.append(':');
			out.append(String.format(Locale.US,"%02X",frame[first+n]&255));}return out.toString();}
	private static String hex(byte[] frame){StringBuilder out=new StringBuilder(frame.length*2);
		for(byte value:frame)out.append(String.format(Locale.US,"%02X",value&255));return out.toString();}

	static final class Decoded{
		final String payloadHex,firstAddress,secondAddress;final int type,syncErrors;
		final boolean manchester;final double carrierOffsetHz,deviationHz,bitMicros,levelDbfs,snrDb;
		Decoded(String payloadHex,int type,boolean manchester,String firstAddress,String secondAddress,
				double carrierOffsetHz,double deviationHz,double bitMicros,double levelDbfs,double snrDb,
				int syncErrors){
			this.payloadHex=payloadHex;this.type=type;this.manchester=manchester;
			this.firstAddress=firstAddress;this.secondAddress=secondAddress;
			this.carrierOffsetHz=carrierOffsetHz;this.deviationHz=deviationHz;
			this.bitMicros=bitMicros;this.levelDbfs=levelDbfs;this.snrDb=snrDb;
			this.syncErrors=syncErrors;
		}
	}
}
