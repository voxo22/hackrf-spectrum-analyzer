package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared clock/frequency recovery for short NRZ FSK-PCM protocols. */
final class FskPcmDecoderSupport {
	private FskPcmDecoderSupport(){ }

	interface FrameDecoder<T>{T decode(boolean[] bits,Metrics metrics);String key(T decoded);}

	static <T> List<T> decodeAll(float[] iq,double sampleRateHz,double bitMicros,int minimumBits,
			FrameDecoder<T> decoder){
		if(iq==null||iq.length<minimumBits*2||sampleRateHz<80_000d)return Collections.emptyList();
		int samples=iq.length/2;double[] power=new double[samples],frequency=new double[samples];
		for(int n=0;n<samples;n++){double i=iq[n*2],q=iq[n*2+1];power[n]=i*i+q*q;
			if(n>0){double pi=iq[(n-1)*2],pq=iq[(n-1)*2+1];
				frequency[n]=Math.atan2(q*pi-i*pq,i*pi+q*pq)*sampleRateHz/(2d*Math.PI);}}
		double[] sorted=power.clone();Arrays.sort(sorted);double noise=percentile(sorted,.50d),strong=percentile(sorted,.995d);
		if(strong<noise*2.0d+1e-12d)return Collections.emptyList();double gate=noise+(strong-noise)*.10d;
		int bridge=Math.max(2,(int)Math.round(sampleRateHz*.0015d));
		int minimum=Math.max(48,(int)Math.round(sampleRateHz*bitMicros*1e-6d*minimumBits*.65d));
		Map<String,T> found=new LinkedHashMap<String,T>();int start=-1,last=-bridge-1;
		for(int n=1;n<=samples;n++){boolean active=n<samples&&power[n]>=gate;
			if(active){if(start<0)start=n;last=n;}
			if(start>=0&&n-last>bridge){if(last-start+1>=minimum)
				decodeBurst(frequency,power,start,last,sampleRateHz,bitMicros,minimumBits,decoder,found);
				start=-1;last=-bridge-1;}}
		return new ArrayList<T>(found.values());
	}

	private static <T> void decodeBurst(double[] frequency,double[] power,int first,int last,double rate,
			double bitMicros,int minimumBits,FrameDecoder<T> decoder,Map<String,T> found){
		first=Math.max(1,first-(int)Math.round(rate*.003d));
		last=Math.min(frequency.length-1,last+(int)Math.round(rate*.003d));
		double[] burstPower=new double[last-first+1];for(int n=first;n<=last;n++)burstPower[n-first]=power[n];
		Arrays.sort(burstPower);double powerGate=percentile(burstPower,.30d);
		double[] values=new double[last-first+1];int count=0;
		for(int n=first;n<=last;n++)if(power[n]>=powerGate)values[count++]=frequency[n];
		if(count<minimumBits)return;Arrays.sort(values,0,count);
		double low=percentile(values,count,.20d),high=percentile(values,count,.80d);
		for(int iteration=0;iteration<8;iteration++){double split=(low+high)/2d,ls=0d,hs=0d;int lc=0,hc=0;
			for(int n=0;n<count;n++)if(values[n]<split){ls+=values[n];lc++;}else{hs+=values[n];hc++;}
			if(lc==0||hc==0)return;low=ls/lc;high=hs/hc;}
		if(high-low<6_000d)return;double threshold=(low+high)/2d,nominal=rate*bitMicros*1e-6d;
		for(double width=nominal*.975d;width<=nominal*1.025d;width+=.08d){int phases=Math.max(4,(int)Math.ceil(width*2d));
			for(int phase=0;phase<phases;phase++){double origin=first+phase*.5d;
				int bitCount=(int)((last-origin+1)/width);if(bitCount<minimumBits)continue;
				boolean[] bits=new boolean[bitCount];
				for(int bit=0;bit<bitCount;bit++){double center=origin+(bit+.5d)*width;
					int from=Math.max(first,(int)Math.ceil(center-width*.28d));
					int to=Math.min(last,(int)Math.floor(center+width*.28d));double sum=0d,weight=0d;
					for(int n=from;n<=to;n++){double w=Math.sqrt(Math.max(0d,power[n]));sum+=(frequency[n]-threshold)*w;weight+=w;}
					bits[bit]=weight>0d&&sum>=0d;}
				for(int polarity=0;polarity<2;polarity++){boolean[] candidate=polarity==0?bits:invert(bits);
					T decoded=decoder.decode(candidate,new Metrics(threshold,high-low,width/rate*1e6d));
					if(decoded!=null){found.put(decoder.key(decoded),decoded);return;}}
			}
		}
	}

	private static boolean[] invert(boolean[] input){boolean[] out=new boolean[input.length];
		for(int n=0;n<input.length;n++)out[n]=!input[n];return out;}
	static int byteAt(boolean[] bits,int at){if(at<0||at+8>bits.length)return-1;int value=0;
		for(int n=0;n<8;n++)value=(value<<1)|(bits[at+n]?1:0);return value;}
	static int reverse8(int value){return Integer.reverse(value)>>>24;}
	static int crc8(byte[] bytes,int length,int polynomial,int initial){int crc=initial&255;
		for(int n=0;n<length;n++){crc^=bytes[n]&255;for(int bit=0;bit<8;bit++)
			crc=(crc&0x80)!=0?((crc<<1)^polynomial)&255:(crc<<1)&255;}return crc;}
	private static double percentile(double[] sorted,double fraction){return percentile(sorted,sorted.length,fraction);}
	private static double percentile(double[] sorted,int length,double fraction){return length==0?0d:
			sorted[Math.max(0,Math.min(length-1,(int)Math.round((length-1)*fraction)))];}

	static final class Metrics{final double carrierOffsetHz,deviationHz,bitMicros;
		Metrics(double carrierOffsetHz,double deviationHz,double bitMicros){this.carrierOffsetHz=carrierOffsetHz;
			this.deviationHz=deviationHz;this.bitMicros=bitMicros;}}
}
