package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Conservative waveform classifier for automotive key-fob-like transmissions.
 * It recognises a long, regular 2-FSK/biphase synchronisation train but does not
 * claim a vehicle brand, protocol identity, or decoded rolling-code payload.
 */
final class AutomotiveKeyfobClassifier {
	private static final double BLOCK_SECONDS=.00005d;

	List<Decoded> decodeAll(float[] iq,double sampleRateHz){
		List<Decoded> out=new ArrayList<Decoded>();
		if(iq==null||sampleRateHz<100_000d||iq.length<sampleRateHz*.08d*2d)return out;
		int samples=iq.length/2,block=Math.max(8,(int)Math.round(sampleRateHz*BLOCK_SECONDS));
		int count=(samples+block-1)/block;double[] power=new double[count];
		for(int b=0;b<count;b++){int from=b*block,to=Math.min(samples,from+block);double sum=0d;
			for(int n=from;n<to;n++){double i=iq[n*2],q=iq[n*2+1];sum+=i*i+q*q;}
			power[b]=sum/Math.max(1,to-from);}
		double[] sorted=power.clone();Arrays.sort(sorted);double noise=sorted[(int)(count*.50d)];
		double strong=sorted[Math.min(count-1,(int)(count*.995d))];double gate=noise+(strong-noise)*.18d;
		boolean nearlyContinuous=strong>0d&&noise/strong>.45d;int bridge=Math.max(1,(int)Math.round(.020d/BLOCK_SECONDS));
		int start=-1,last=-bridge-1;
		for(int b=0;b<=count;b++){boolean active=b<count&&(nearlyContinuous||power[b]>=gate);
			if(active){if(start<0)start=b;last=b;}
			if(start>=0&&(!active&&b-last>bridge||b==count)){
				Decoded decoded=analyze(iq,sampleRateHz,start*block,Math.min(samples,(last+1)*block),block);
				if(decoded!=null)out.add(decoded);start=-1;last=-bridge-1;}
		}
		return out;
	}

	private static Decoded analyze(float[] iq,double rate,int first,int last,int block){
		double durationMs=(last-first)*1000d/rate;if(durationMs<105d||durationMs>260d)return null;
		int count=Math.max(0,(last-first)/block);if(count<100)return null;double[] frequency=new double[count];
		for(int b=0;b<count;b++){int from=first+b*block,to=Math.min(last,from+block);double re=0d,im=0d;
			for(int n=Math.max(from+1,first+1);n<to;n++){double pi=iq[(n-1)*2],pq=iq[(n-1)*2+1];
				double i=iq[n*2],q=iq[n*2+1];re+=i*pi+q*pq;im+=q*pi-i*pq;}
			frequency[b]=Math.atan2(im,re)*rate/(2d*Math.PI);
		}
		double[] sorted=frequency.clone();Arrays.sort(sorted);double low=sorted[(int)(count*.20d)],high=sorted[(int)(count*.80d)];
		for(int loop=0;loop<7;loop++){double split=(low+high)/2d,ls=0d,hs=0d;int lc=0,hc=0;
			for(double value:frequency)if(value<split){ls+=value;lc++;}else{hs+=value;hc++;}
			if(lc==0||hc==0)return null;low=ls/lc;high=hs/hc;}
		double separation=high-low;if(separation<20_000d||separation>90_000d)return null;double threshold=(low+high)/2d;
		boolean[] states=new boolean[count];for(int n=0;n<count;n++){
			double a=frequency[Math.max(0,n-1)],b=frequency[n],c=frequency[Math.min(count-1,n+1)];
			states[n]=median(a,b,c)>=threshold;}
		List<Integer> runs=new ArrayList<Integer>();boolean state=states[0];int length=1;
		for(int n=1;n<count;n++)if(states[n]==state)length++;else{runs.add(Integer.valueOf(length));state=states[n];length=1;}
		runs.add(Integer.valueOf(length));mergeGlitches(runs);
		double blockMs=block*1000d/rate;int longest=0,current=0;List<Double> units=new ArrayList<Double>();
		for(int run:runs){double ms=run*blockMs;if(ms>=.28d&&ms<=.52d){current++;longest=Math.max(longest,current);units.add(Double.valueOf(ms));}else current=0;}
		if(longest<100||units.size()<140)return null;double[] unitValues=new double[units.size()];
		for(int n=0;n<unitValues.length;n++)unitValues[n]=units.get(n).doubleValue();Arrays.sort(unitValues);
		double symbolMicros=unitValues[unitValues.length/2]*1000d;
		return new Decoded(durationMs,(low+high)/2d,separation,symbolMicros,longest);
	}

	private static double median(double a,double b,double c){return a>b?(b>c?b:Math.min(a,c)):(a>c?a:Math.min(b,c));}
	private static void mergeGlitches(List<Integer> runs){for(int n=1;n+1<runs.size();)if(runs.get(n).intValue()<=1){
		runs.set(n-1,Integer.valueOf(runs.get(n-1)+runs.get(n)+runs.get(n+1)));runs.remove(n+1);runs.remove(n);}else n++;}

	static final class Decoded {
		final double durationMs,carrierOffsetHz,toneSeparationHz,symbolMicros;final int preambleTransitions;
		Decoded(double durationMs,double carrierOffsetHz,double toneSeparationHz,double symbolMicros,int preambleTransitions){
			this.durationMs=durationMs;this.carrierOffsetHz=carrierOffsetHz;this.toneSeparationHz=toneSeparationHz;
			this.symbolMicros=symbolMicros;this.preambleTransitions=preambleTransitions;}
	}
}
