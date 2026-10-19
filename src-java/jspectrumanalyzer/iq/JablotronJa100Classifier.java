package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Conservative physical-layer classifier for JABLOTRON 100-family radio bursts.
 *
 * <p>The public product documentation identifies the radio as 868.1 MHz GFSK
 * with occupied bandwidth below 80 kHz, but does not publish the frame format.
 * Consequently this class reports candidates only. It additionally requires
 * the stable 40 ksym/s two-tone fingerprint and alternating preamble observed
 * in captures; it never treats frequency alone as identification.</p>
 */
final class JablotronJa100Classifier {
	static final long CHANNEL_HZ=868_100_000L;
	private static final double BLOCK_SECONDS=.0001d;
	private static final double MIN_BURST_SECONDS=.0035d;
	private static final double MAX_BURST_SECONDS=.030d;
	private static final double BRIDGE_SECONDS=.0005d;

	List<Candidate> classify(byte[] iq,int length,double sampleRateHz){
		if(iq==null||sampleRateHz<200_000d||sampleRateHz>2_500_000d)return Collections.emptyList();
		int samples=Math.min(length,iq.length)/2;
		if(samples<sampleRateHz*MIN_BURST_SECONDS)return Collections.emptyList();
		int block=Math.max(16,(int)Math.round(sampleRateHz*BLOCK_SECONDS));
		int blocks=(samples+block-1)/block,stride=Math.max(1,(int)Math.round(sampleRateHz/125_000d));
		double[] powers=new double[blocks];int[] powerCounts=new int[blocks];
		/* A dozen evenly spaced values per 100 us block are enough for the cheap
		 * activity gate. Full-rate power and discriminator work is then limited to
		 * the few millisecond bursts which passed it. */
		for(int at=0;at<samples;at+=stride){double i=iq[at*2]/128d,q=iq[at*2+1]/128d;
			int atBlock=at/block;powers[atBlock]+=i*i+q*q;powerCounts[atBlock]++;}
		for(int n=0;n<blocks;n++)powers[n]/=Math.max(1,powerCounts[n]);
		double[] sorted=powers.clone();Arrays.sort(sorted);double noise=percentilePositive(sorted,.50d);
		double peak=percentilePositive(sorted,.999d);
		if(peak<noise*2d+1e-12d)return Collections.emptyList();
		double gate=noise>1e-15d?Math.max(noise*1.45d,noise+(peak-noise)*.035d):peak*.08d;
		int bridge=Math.max(1,(int)Math.round(BRIDGE_SECONDS*sampleRateHz/block));
		int minimum=Math.max(1,(int)Math.round(MIN_BURST_SECONDS*sampleRateHz/block));
		int maximum=Math.max(minimum,(int)Math.round(MAX_BURST_SECONDS*sampleRateHz/block));
		List<Candidate> out=new ArrayList<Candidate>();int first=-1,last=-bridge-1;
		for(int n=0;n<=blocks;n++){boolean active=n<blocks&&powers[n]>=gate;
			if(active){if(first<0)first=n;last=n;}
			if(first>=0&&(n-last>bridge||n==blocks)){
				int span=last-first+1;if(span>=minimum&&span<=maximum){Candidate value=analyze(iq,samples,
						first*block,Math.min(samples,(last+1)*block),sampleRateHz,noise,gate);
					if(value!=null)out.add(value);}
				first=-1;last=-bridge-1;
			}
		}
		return out;
	}

	private static Candidate analyze(byte[] iq,int total,int first,int last,double rate,double noise,double gate){
		first=Math.max(1,first);last=Math.min(total,last);int count=last-first;if(count<64)return null;
		double[] power=new double[count],raw=new double[count],frequency=new double[count];double level=0d;
		for(int at=0;at<count;at++){int n=first+at;double i=iq[n*2]/128d,q=iq[n*2+1]/128d;
			power[at]=i*i+q*q;level+=power[at];double pi=iq[(n-1)*2]/128d,pq=iq[(n-1)*2+1]/128d;
			raw[at]=Math.atan2(q*pi-i*pq,i*pi+q*pq)*rate/(2d*Math.PI);}
		int smooth=Math.max(2,(int)Math.round(rate*4e-6d));double re=0d,im=0d;
		for(int n=0;n<count;n++){double angle=raw[n]*2d*Math.PI/rate;re+=Math.cos(angle);im+=Math.sin(angle);
			if(n>=smooth){double old=raw[n-smooth]*2d*Math.PI/rate;re-=Math.cos(old);im-=Math.sin(old);}
			frequency[n]=Math.atan2(im,re)*rate/(2d*Math.PI);}
		double sampleGate=Math.max(noise*1.35d,gate*.55d);double[] active=new double[count];int activeCount=0;
		int activeFirst=-1,activeLast=-1;for(int n=smooth;n<count;n++)if(power[n]>=sampleGate){
			active[activeCount++]=frequency[n];if(activeFirst<0)activeFirst=n;activeLast=n;}
		if(activeCount<count/5||activeFirst<0)return null;double[] tones=twoMeans(active,activeCount);
		double separation=tones[1]-tones[0],center=(tones[0]+tones[1])/2d;
		if(separation<25_000d||separation>60_000d||Math.abs(center)>55_000d)return null;
		Clock clock=clock(frequency,power,activeFirst,activeLast,(tones[0]+tones[1])/2d,sampleGate,rate);
		if(clock==null||clock.alternating<24)return null;
		double duration=(activeLast-activeFirst+1)*1000d/rate;
		if(duration<MIN_BURST_SECONDS*1000d||duration>MAX_BURST_SECONDS*1000d)return null;
		double confidence=Math.min(94d,78d+Math.min(12d,(clock.alternating-24)*.6d)
				+Math.max(0d,4d-Math.abs(center)/12_000d));
		return new Candidate(center,separation,1e6d/clock.bitMicros,duration,clock.alternating,
				clock.bits.length(),fingerprint(clock),10d*Math.log10(Math.max(1e-15,level/count)),
				10d*Math.log10(Math.max(1e-15,level/count)/Math.max(1e-15,noise)),confidence);
	}

	private static Clock clock(double[] frequency,double[] power,int first,int last,double split,
			double gate,double rate){Clock best=null;
		for(double micros=24d;micros<=26.0001d;micros+=.1d){double period=rate*micros*1e-6d;
			for(double phase=0d;phase<period;phase+=.5d){StringBuilder bits=new StringBuilder();
				for(double center=first+phase+period*.5d;center<=last;center+=period){
					int from=Math.max(first,(int)Math.ceil(center-period*.24d));
					int to=Math.min(last,(int)Math.floor(center+period*.24d));double sum=0d,weight=0d;
					for(int n=from;n<=to;n++)if(power[n]>=gate*.65d){double w=Math.sqrt(power[n]);
						sum+=(frequency[n]-split)*w;weight+=w;}
					bits.append(weight>0d&&sum>=0d?'1':'0');
				}
				Alternating alternating=longestAlternating(bits);if(alternating.length<24)continue;
				double score=alternating.length*100d-Math.abs(micros-25d)*2d;
				if(best==null||score>best.score)best=new Clock(bits.toString(),micros,
						alternating.start,alternating.length,score);
			}
		}
		return best;
	}

	private static Alternating longestAlternating(CharSequence bits){int bestStart=0,bestLength=0,start=0;
		for(int n=1;n<=bits.length();n++){if(n<bits.length()&&bits.charAt(n)!=bits.charAt(n-1))continue;
			int length=n-start;if(length>bestLength){bestStart=start;bestLength=length;}start=n;}
		return new Alternating(bestStart,bestLength);
	}

	private static String fingerprint(Clock clock){int at=clock.alternatingStart+clock.alternating;
		StringBuilder out=new StringBuilder();for(int byteAt=0;byteAt<8&&at+8<=clock.bits.length();byteAt++,at+=8){
			int value=0;for(int bit=0;bit<8;bit++)value=(value<<1)|(clock.bits.charAt(at+bit)-'0');
			out.append(String.format("%02X",value));}
		return out.toString();
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

	static final class Candidate{
		final double carrierOffsetHz,toneSeparationHz,symbolRate,burstMillis,levelDbfs,snrDb,confidence;
		final int preambleBits,frameBits;final String fingerprint;
		Candidate(double carrierOffsetHz,double toneSeparationHz,double symbolRate,double burstMillis,
				int preambleBits,int frameBits,String fingerprint,double levelDbfs,double snrDb,double confidence){
			this.carrierOffsetHz=carrierOffsetHz;this.toneSeparationHz=toneSeparationHz;
			this.symbolRate=symbolRate;this.burstMillis=burstMillis;this.preambleBits=preambleBits;
			this.frameBits=frameBits;this.fingerprint=fingerprint;this.levelDbfs=levelDbfs;
			this.snrDb=snrDb;this.confidence=confidence;}
	}
	private static final class Alternating{final int start,length;
		Alternating(int start,int length){this.start=start;this.length=length;}}
	private static final class Clock{final String bits;final double bitMicros,score;
		final int alternatingStart,alternating;Clock(String bits,double bitMicros,int alternatingStart,
				int alternating,double score){this.bits=bits;this.bitMicros=bitMicros;
			this.alternatingStart=alternatingStart;this.alternating=alternating;this.score=score;}}
}
