package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Conservative PHY classifier for the two recurring GFSK waveforms observed
 * directly on 868.5 MHz.  The long profile matches the published Wavenis PHY,
 * but remains a candidate until its interleaving/BCH frame is validated.
 */
final class Ism8685Classifier {
	static final long CHANNEL_HZ=868_500_000L;
	private static final double BLOCK_SECONDS=.0001d;
	private static final double BRIDGE_SECONDS=.0007d;

	List<Candidate> classify(byte[] iq,int length,double sampleRateHz){
		if(iq==null||sampleRateHz<180_000d||sampleRateHz>2_500_000d)
			return Collections.emptyList();
		int samples=Math.min(length,iq.length)/2;
		if(samples<sampleRateHz*.010d)return Collections.emptyList();
		int block=Math.max(16,(int)Math.round(sampleRateHz*BLOCK_SECONDS));
		int blocks=(samples+block-1)/block,stride=Math.max(1,(int)Math.round(sampleRateHz/125_000d));
		double[] powers=new double[blocks];int[] counts=new int[blocks];
		for(int at=0;at<samples;at+=stride){double i=iq[at*2]/128d,q=iq[at*2+1]/128d;
			int b=at/block;powers[b]+=i*i+q*q;counts[b]++;}
		for(int n=0;n<blocks;n++)powers[n]/=Math.max(1,counts[n]);
		double[] sorted=powers.clone();Arrays.sort(sorted);double noise=percentilePositive(sorted,.50d);
		double peak=percentilePositive(sorted,.999d);if(peak<noise*2d+1e-12d)return Collections.emptyList();
		double gate=noise>1e-15d?Math.max(noise*1.5d,noise+(peak-noise)*.045d):peak*.09d;
		int bridge=Math.max(1,(int)Math.round(BRIDGE_SECONDS*sampleRateHz/block));
		List<Candidate> out=new ArrayList<Candidate>();int first=-1,last=-bridge-1;
		for(int n=0;n<=blocks;n++){boolean active=n<blocks&&powers[n]>=gate;
			if(active){if(first<0)first=n;last=n;}
			if(first>=0&&(n-last>bridge||n==blocks)){
				double duration=(last-first+1)*block/sampleRateHz;
				if(duration>=.010d&&duration<=.070d){Candidate value=analyze(iq,samples,
						first*block,Math.min(samples,(last+1)*block),sampleRateHz,noise,gate);
					if(value!=null)out.add(value);}
				first=-1;last=-bridge-1;
			}
		}
		return out;
	}

	private static Candidate analyze(byte[] iq,int total,int first,int last,double rate,
			double noise,double blockGate){
		first=Math.max(1,first);last=Math.min(total,last);int count=last-first;if(count<64)return null;
		double[] power=new double[count],raw=new double[count],frequency=new double[count];double level=0d;
		for(int at=0;at<count;at++){int n=first+at;double i=iq[n*2]/128d,q=iq[n*2+1]/128d;
			power[at]=i*i+q*q;level+=power[at];double pi=iq[(n-1)*2]/128d,pq=iq[(n-1)*2+1]/128d;
			raw[at]=Math.atan2(q*pi-i*pq,i*pi+q*pq)*rate/(2d*Math.PI);}
		int smooth=Math.max(2,(int)Math.round(rate*4e-6d));double re=0d,im=0d;
		for(int n=0;n<count;n++){double angle=raw[n]*2d*Math.PI/rate;re+=Math.cos(angle);im+=Math.sin(angle);
			if(n>=smooth){double old=raw[n-smooth]*2d*Math.PI/rate;re-=Math.cos(old);im-=Math.sin(old);}
			frequency[n]=Math.atan2(im,re)*rate/(2d*Math.PI);}
		double sampleGate=Math.max(noise*1.35d,blockGate*.5d);double[] active=new double[count];int activeCount=0;
		int activeFirst=-1,activeLast=-1;for(int n=smooth;n<count;n++)if(power[n]>=sampleGate){
			active[activeCount++]=frequency[n];if(activeFirst<0)activeFirst=n;activeLast=n;}
		if(activeCount<count/3||activeFirst<0)return null;double[] tones=twoMeans(active,activeCount);
		double separation=tones[1]-tones[0],center=(tones[0]+tones[1])/2d;
		if(Math.abs(center)>35_000d||separation<30_000d||separation>62_000d)return null;
		int middle=0;double halfWindow=separation*.16d;for(int n=0;n<activeCount;n++)
			if(Math.abs(active[n]-center)<halfWindow)middle++;
		if(middle>activeCount*.30d)return null;
		double durationMs=(activeLast-activeFirst+1)*1000d/rate;Family family;double symbols,confidence;
		if(durationMs>=48d&&durationMs<=63d&&separation>=33_000d&&separation<=46_000d){
			family=Family.WAVENIS;symbols=9_600d;confidence=91d;
		}else if(durationMs>=12d&&durationMs<=15.5d&&separation>=46_000d&&separation<=58_000d){
			family=Family.PROPRIETARY_20K;symbols=20_000d;confidence=82d;
		}else return null;
		int frameBits=(int)Math.round(durationMs*symbols/1000d);
		double average=level/count,levelDbfs=10d*Math.log10(Math.max(1e-15,average));
		double snrDb=10d*Math.log10(Math.max(1e-15,average)/Math.max(1e-15,noise));
		return new Candidate(family,center,separation,symbols,durationMs,frameBits,
				levelDbfs,snrDb,confidence);
	}

	private static double[] twoMeans(double[] values,int length){Arrays.sort(values,0,length);
		double low=percentile(values,length,.20d),high=percentile(values,length,.80d);
		for(int loop=0;loop<10;loop++){double split=(low+high)/2d,ls=0d,hs=0d;int lc=0,hc=0;
			for(int n=0;n<length;n++)if(values[n]<split){ls+=values[n];lc++;}else{hs+=values[n];hc++;}
			if(lc==0||hc==0)break;low=ls/lc;high=hs/hc;}
		return new double[]{Math.min(low,high),Math.max(low,high)};
	}
	private static double percentilePositive(double[] sorted,double fraction){int first=0;
		while(first<sorted.length&&sorted[first]<=1e-15d)first++;return first==sorted.length?0d:
			sorted[first+Math.max(0,Math.min(sorted.length-first-1,
			(int)Math.round((sorted.length-first-1)*fraction)))];}
	private static double percentile(double[] sorted,int length,double fraction){return length==0?0d:
		sorted[Math.max(0,Math.min(length-1,(int)Math.round((length-1)*fraction)))];}

	enum Family{WAVENIS,PROPRIETARY_20K}
	static final class Candidate{
		final Family family;final double carrierOffsetHz,toneSeparationHz,symbolRate,burstMillis;
		final double levelDbfs,snrDb,confidence;final int frameBits;
		Candidate(Family family,double carrierOffsetHz,double toneSeparationHz,double symbolRate,
				double burstMillis,int frameBits,double levelDbfs,double snrDb,double confidence){
			this.family=family;this.carrierOffsetHz=carrierOffsetHz;this.toneSeparationHz=toneSeparationHz;
			this.symbolRate=symbolRate;this.burstMillis=burstMillis;this.frameBits=frameBits;
			this.levelDbfs=levelDbfs;this.snrDb=snrDb;this.confidence=confidence;}
	}
}
