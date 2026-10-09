package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Decoder for Fine Offset WH24/WH65/WS69 58 us FSK-PCM weather frames. */
final class FineOffsetWh65bDecoder {
	private static final int PAYLOAD_BYTES=17;
	private static final int[] UVI_UPPER={432,851,1210,1570,2017,2450,2761,3100,3512,3918,4277,4650,5029};

	List<Decoded> decodeAll(float[] iq,double sampleRateHz){
		if(iq==null||iq.length<400||sampleRateHz<80_000d)return Collections.emptyList();
		int samples=iq.length/2;
		double[] power=new double[samples],frequency=new double[samples];
		for(int n=0;n<samples;n++){
			double i=iq[n*2],q=iq[n*2+1];power[n]=i*i+q*q;
			if(n>0){double pi=iq[(n-1)*2],pq=iq[(n-1)*2+1];
				frequency[n]=Math.atan2(q*pi-i*pq,i*pi+q*pq)*sampleRateHz/(2d*Math.PI);}
		}
		double[] sortedPower=power.clone();Arrays.sort(sortedPower);
		double noise=percentile(sortedPower,.50d),strong=percentile(sortedPower,.995d);
		if(strong<noise*2.2d+1e-12d)return Collections.emptyList();
		double gate=noise+(strong-noise)*.12d;
		int bridge=Math.max(2,(int)Math.round(sampleRateHz*.0015d));
		int minimum=Math.max(64,(int)Math.round(sampleRateHz*.006d));
		List<Decoded> out=new ArrayList<Decoded>();int start=-1,last=-bridge-1;
		for(int n=1;n<=samples;n++){
			boolean active=n<samples&&power[n]>=gate;
			if(active){if(start<0)start=n;last=n;}
			if(start>=0&&n-last>bridge){
				if(last-start+1>=minimum)decodeBurst(frequency,power,start,last,sampleRateHz,out);
				start=-1;last=-bridge-1;
			}
		}
		return out;
	}

	private static void decodeBurst(double[] frequency,double[] power,int first,int last,
			double sampleRateHz,List<Decoded> out){
		first=Math.max(1,first-(int)Math.round(sampleRateHz*.004d));
		last=Math.min(frequency.length-1,last+(int)Math.round(sampleRateHz*.004d));
		double[] candidates=new double[last-first+1];int count=0;
		double[] burstPower=new double[last-first+1];
		for(int n=first;n<=last;n++)burstPower[n-first]=power[n];
		Arrays.sort(burstPower);double powerGate=percentile(burstPower,.35d);
		for(int n=first;n<=last;n++)if(power[n]>=powerGate)candidates[count++]=frequency[n];
		if(count<100)return;
		Arrays.sort(candidates,0,count);
		double low=percentile(candidates,count,.20d),high=percentile(candidates,count,.80d);
		for(int iteration=0;iteration<8;iteration++){
			double split=(low+high)/2d,lowSum=0d,highSum=0d;int lowCount=0,highCount=0;
			for(int n=0;n<count;n++)if(candidates[n]<split){lowSum+=candidates[n];lowCount++;}
				else{highSum+=candidates[n];highCount++;}
			if(lowCount==0||highCount==0)return;low=lowSum/lowCount;high=highSum/highCount;
		}
		if(high-low<8_000d)return;
		double threshold=(low+high)/2d;
		double nominal=sampleRateHz*58e-6d;
		/* Crystal/sample-clock error is small. A tight clock search is important:
		 * this decoder runs on several simultaneous channelizer tracks. */
		for(double bitSamples=nominal*.98d;bitSamples<=nominal*1.02d;bitSamples+=.08d){
			int phases=Math.max(4,(int)Math.ceil(bitSamples*2d));
			for(int phase=0;phase<phases;phase++){
				double origin=first+phase*.5d;int bitCount=(int)((last-origin+1)/bitSamples);
				if(bitCount<160)continue;boolean[] bits=new boolean[bitCount];
				for(int bit=0;bit<bitCount;bit++){
					double center=origin+(bit+.5d)*bitSamples;
					int from=Math.max(first,(int)Math.ceil(center-bitSamples*.30d));
					int to=Math.min(last,(int)Math.floor(center+bitSamples*.30d));
					double weighted=0d,weight=0d;
					for(int n=from;n<=to;n++){double w=Math.sqrt(Math.max(0d,power[n]));
						weighted+=(frequency[n]-threshold)*w;weight+=w;}
					bits[bit]=weight>0d&&weighted>=0d;
				}
				Decoded decoded=findFrame(bits,false,low,high,bitSamples,sampleRateHz);
				if(decoded==null)decoded=findFrame(bits,true,low,high,bitSamples,sampleRateHz);
				if(decoded!=null&&!contains(out,decoded)){out.add(decoded);return;}
			}
		}
	}

	private static Decoded findFrame(boolean[] bits,boolean invert,double low,double high,
			double bitSamples,double sampleRateHz){
		/* The final AA byte is deliberately part of the search pattern so a random
		 * 2D D4 sequence in noise cannot create decoder work. */
		int sync=0xAA2DD4;
		for(int at=0;at+24+PAYLOAD_BYTES*8<=bits.length;at++){
			int word=0;for(int n=0;n<24;n++)word=(word<<1)|((bits[at+n]^invert)?1:0);
			if(word!=sync)continue;byte[] payload=new byte[PAYLOAD_BYTES];int data=at+24;
			for(int b=0;b<PAYLOAD_BYTES;b++){int value=0;
				for(int n=0;n<8;n++)value=(value<<1)|((bits[data+b*8+n]^invert)?1:0);
				payload[b]=(byte)value;}
			Decoded decoded=fromPayload(payload,(low+high)/2d,high-low,
					bitSamples/sampleRateHz*1e6d);if(decoded!=null)return decoded;
		}
		return null;
	}

	static Decoded fromPayload(byte[] b,double carrierOffsetHz,double deviationHz,double bitMicros){
		if(b==null||b.length<17||(b[0]&255)!=0x24||crc8(b,16)!=0)return null;
		int sum=0;for(int n=0;n<16;n++)sum=(sum+(b[n]&255))&255;
		if(sum!=(b[16]&255))return null;
		int id=b[1]&255,windDirection=(b[2]&255)|((b[3]&0x80)<<1);
		boolean batteryOk=(b[3]&0x08)==0;
		int temperatureRaw=((b[3]&0x07)<<8)|(b[4]&255),humidity=b[5]&255;
		int windRaw=(b[6]&255)|((b[3]&0x10)<<4),gustRaw=b[7]&255;
		int rainRaw=((b[8]&255)<<8)|(b[9]&255),uvRaw=((b[10]&255)<<8)|(b[11]&255);
		int lightRaw=((b[12]&255)<<16)|((b[13]&255)<<8)|(b[14]&255),uvi=0;
		while(uvi<UVI_UPPER.length&&UVI_UPPER[uvi]<uvRaw)uvi++;
		return new Decoded(payloadHex(b),id,batteryOk,
				temperatureRaw==0x7ff?Double.NaN:(temperatureRaw-400)*.1d,
				humidity==0xff?-1:humidity,windDirection==0x1ff?-1:windDirection,
				windRaw==0x1ff?Double.NaN:windRaw*.125d*.51d,
				gustRaw==0xff?Double.NaN:gustRaw*.51d,rainRaw*.254d,
				uvRaw==0xffff?-1:uvRaw,uvRaw==0xffff?-1:uvi,
				lightRaw==0xffffff?Double.NaN:lightRaw*.1d,carrierOffsetHz,deviationHz,bitMicros);
	}

	private static int crc8(byte[] bytes,int length){int crc=0;
		for(int n=0;n<length;n++){crc^=bytes[n]&255;for(int bit=0;bit<8;bit++)
			crc=(crc&0x80)!=0?((crc<<1)^0x31)&255:(crc<<1)&255;}return crc;}
	private static boolean contains(List<Decoded> values,Decoded next){for(Decoded old:values)
		if(old.payloadHex.equals(next.payloadHex))return true;return false;}
	private static double percentile(double[] sorted,double fraction){return percentile(sorted,sorted.length,fraction);}
	private static double percentile(double[] sorted,int length,double fraction){if(length==0)return 0d;
		return sorted[Math.max(0,Math.min(length-1,(int)Math.round((length-1)*fraction)))];}
	private static String payloadHex(byte[] bytes){StringBuilder out=new StringBuilder(bytes.length*2);
		for(byte value:bytes)out.append(String.format(java.util.Locale.US,"%02X",value&255));return out.toString();}

	static final class Decoded{
		final String payloadHex;final int id,humidity,windDirection,uvRaw,uvIndex;
		final boolean batteryOk;final double temperatureC,windAverageMs,windGustMs,rainMm,lightLux;
		final double carrierOffsetHz,deviationHz,bitMicros;
		Decoded(String payloadHex,int id,boolean batteryOk,double temperatureC,int humidity,
				int windDirection,double windAverageMs,double windGustMs,double rainMm,int uvRaw,
				int uvIndex,double lightLux,double carrierOffsetHz,double deviationHz,double bitMicros){
			this.payloadHex=payloadHex;this.id=id;this.batteryOk=batteryOk;this.temperatureC=temperatureC;
			this.humidity=humidity;this.windDirection=windDirection;this.windAverageMs=windAverageMs;
			this.windGustMs=windGustMs;this.rainMm=rainMm;this.uvRaw=uvRaw;this.uvIndex=uvIndex;
			this.lightLux=lightLux;this.carrierOffsetHz=carrierOffsetHz;this.deviationHz=deviationHz;
			this.bitMicros=bitMicros;
		}
	}
}
