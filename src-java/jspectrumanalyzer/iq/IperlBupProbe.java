package jspectrumanalyzer.iq;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** Offline physical-layer probe for proprietary Sensus iPERL BUP recordings. */
public final class IperlBupProbe {
	private static final double ENERGY_BLOCK_SECONDS=.0001d;
	private static final double MIN_BURST_SECONDS=.0001d;
	private static final double BRIDGE_SECONDS=.0005d;

	private IperlBupProbe(){ }

	public static void main(String[] args)throws Exception{
		if(args.length==0){
			System.err.println("Usage: IperlBupProbe <iq.cs8|iq.cu8|iq.pcm|iq.wav> [...]");
			return;
		}
		for(String argument:args)probe(new File(argument));
	}

	private static void probe(File file)throws Exception{
		try(IQReplayFile replay=IQReplayFile.open(file)){
			int rate=replay.getSampleRateHz();long totalSamples=replay.getTotalIqSamples();
			int blockSamples=Math.max(16,(int)Math.round(rate*ENERGY_BLOCK_SECONDS));
			int chunkSamples=Math.max(blockSamples,rate/4);
			DoubleList powers=new DoubleList(),frequencies=new DoubleList();
			byte[] iq=new byte[chunkSamples*2];long consumed=0;
			double sumPower=0d,sumRe=0d,sumIm=0d;int inBlock=0;double previousI=0d,previousQ=0d;
			boolean havePrevious=false;
			while(consumed<totalSamples){
				int samples=(int)Math.min(chunkSamples,totalSamples-consumed);
				if(iq.length!=samples*2)iq=new byte[samples*2];
				replay.readLoopedSigned(iq);consumed+=samples;
				for(int n=0;n<samples;n++){
					double i=iq[n*2]/128d,q=iq[n*2+1]/128d;sumPower+=i*i+q*q;
					if(havePrevious){sumRe+=i*previousI+q*previousQ;sumIm+=q*previousI-i*previousQ;}
					previousI=i;previousQ=q;havePrevious=true;inBlock++;
					if(inBlock==blockSamples){
						powers.add(sumPower/inBlock);
						frequencies.add(Math.atan2(sumIm,sumRe)*rate/(2d*Math.PI));
						sumPower=sumRe=sumIm=0d;inBlock=0;havePrevious=false;
					}
				}
			}
			if(inBlock>0){powers.add(sumPower/inBlock);
				frequencies.add(Math.atan2(sumIm,sumRe)*rate/(2d*Math.PI));}
			double[] sorted=powers.toArray();Arrays.sort(sorted);DoubleList positive=new DoubleList();
			for(double value:sorted)if(value>1e-15d)positive.add(value);
			double[] nonzero=positive.toArray();
			double noise=percentile(sorted,.50d),p95=percentile(sorted,.95d),p999=percentile(sorted,.999d);
			/* The recordings contain sparse bursts.  This gate remains close enough to
			 * the noise floor to retain weak packet tails, while the duration filter
			 * rejects isolated impulsive blocks. */
			double gate=noise>1e-15d?Math.max(noise*1.45d,noise+(p999-noise)*.035d)
					:Math.max(1e-15d,percentile(nonzero,.10d)*.45d);
			List<Burst> bursts=findBursts(powers.values,powers.size,blockSamples,rate,gate);
			System.out.println(String.format(Locale.US,
					"%s: center %.6f MHz, %.3f MS/s, %.3f s | noise %.1f dBFS, p95 %.1f, p99.9 %.1f, gate %.1f | %d burst(s)",
					file.getName(),replay.getCenterFrequencyHz()/1e6,rate/1e6,totalSamples/(double)rate,
					db(noise),db(p95),db(p999),db(gate),bursts.size())+
					(sorted.length==nonzero.length?"":" | nonzero blocks "+nonzero.length+"/"+sorted.length));
			int shown=0;
			for(Burst burst:bursts){
				if(shown++>=24){System.out.println("  ... "+(bursts.size()-24)+" additional bursts");break;}
				analyzeBurst(file,replay,burst,blockSamples,rate,noise,gate);
			}
		}
	}

	private static List<Burst> findBursts(double[] powers,int count,int blockSamples,int rate,double gate){
		List<Burst> out=new ArrayList<Burst>();int bridge=Math.max(1,(int)Math.round(BRIDGE_SECONDS*rate/blockSamples));
		int minimum=Math.max(1,(int)Math.round(MIN_BURST_SECONDS*rate/blockSamples));int start=-1,last=-bridge-1;
		for(int n=0;n<=count;n++){boolean active=n<count&&powers[n]>gate;
			if(active){if(start<0)start=n;last=n;}
			if(start>=0&&(n-last>bridge||n==count)){
				if(last-start+1>=minimum)out.add(new Burst((long)start*blockSamples,
						Math.min((long)count*blockSamples,(long)(last+1)*blockSamples)));
				start=-1;last=-bridge-1;
			}
		}
		return out;
	}

	private static void analyzeBurst(File file,IQReplayFile replay,Burst burst,int blockSamples,
			int rate,double noise,double gate)throws Exception{
		long pad=Math.round(rate*.002d),first=Math.max(0L,burst.first-pad),last=Math.min(
				replay.getTotalIqSamples(),burst.last+pad);int samples=(int)Math.min(Integer.MAX_VALUE/2-1,last-first);
		replay.seekMillis(Math.round(first*1000d/rate));byte[] bytes=new byte[samples*2];replay.readLoopedSigned(bytes);
		double[] rawFrequency=new double[samples],power=new double[samples];double peak=0d;
		for(int n=0;n<samples;n++){double i=bytes[n*2]/128d,q=bytes[n*2+1]/128d;
			power[n]=i*i+q*q;peak=Math.max(peak,power[n]);if(n>0){double pi=bytes[(n-1)*2]/128d,pq=bytes[(n-1)*2+1]/128d;
				rawFrequency[n]=Math.atan2(q*pi-i*pq,i*pi+q*pq)*rate/(2d*Math.PI);}}
		int smooth=Math.max(2,(int)Math.round(rate*4e-6d));double[] frequency=new double[samples];
		double re=0d,im=0d;for(int n=1;n<samples;n++){double angle=rawFrequency[n]*2d*Math.PI/rate;
			re+=Math.cos(angle);im+=Math.sin(angle);if(n>=smooth){double old=rawFrequency[n-smooth+1]*2d*Math.PI/rate;
				re-=Math.cos(old);im-=Math.sin(old);}frequency[n]=Math.atan2(im,re)*rate/(2d*Math.PI);}
		double sampleGate=Math.max(noise*1.35d,gate*.55d);DoubleList active=new DoubleList();
		for(int n=smooth;n<samples;n++)if(power[n]>=sampleGate)active.add(frequency[n]);
		if(active.size<32){System.out.println(String.format(Locale.US,"  %7.3f s  %6.2f ms  weak/unresolved",
				burst.first/(double)rate,(burst.last-burst.first)*1000d/rate));return;}
		double[] tones=twoMeans(active.toArray());double split=(tones[0]+tones[1])/2d;
		List<Run> runs=runs(frequency,power,smooth,samples,split,sampleGate,rate);
		double unit=estimateUnitMicros(runs);String bits=sampleRunBits(runs,unit);
		double durationMs=(burst.last-burst.first)*1000d/rate;
		boolean likelyBup=durationMs>=1.2d&&durationMs<=2.5d&&tones[1]-tones[0]>=60_000d
				&&tones[1]-tones[0]<=105_000d&&runs.size()>=70;
		System.out.println(String.format(Locale.US,
				"  %7.3f s  %6.2f ms  peak %6.1f dBFS | tones %+8.1f/%+8.1f kHz, sep %.1f kHz | unit %.1f us, %d runs%s | %s",
				burst.first/(double)rate,durationMs,db(peak),tones[0]/1000d,
				tones[1]/1000d,(tones[1]-tones[0])/1000d,unit,runs.size(),likelyBup?" | BUP PHY":"",bits));
		if(likelyBup){System.out.println("             runs "+runSummary(runs));
			System.out.println("             "+clockedChipSummary(frequency,power,smooth,samples,
					split,sampleGate,rate));}
	}

	private static List<Run> runs(double[] frequency,double[] power,int first,int last,double split,
			double powerGate,int rate){
		List<Run> out=new ArrayList<Run>();boolean state=false;int start=-1,lastStrong=-1;
		int bridge=Math.max(1,(int)Math.round(rate*80e-6d));
		for(int n=first;n<=last;n++){boolean strong=n<last&&power[n]>=powerGate;
			if(strong){boolean next=frequency[n]>=split;if(start<0){state=next;start=n;}
				else if(next!=state&&n-lastStrong<=bridge){out.add(new Run(state,n-start,rate));state=next;start=n;}
				lastStrong=n;}
			if(start>=0&&(!strong&&n-lastStrong>bridge||n==last)){out.add(new Run(state,lastStrong-start+1,rate));start=-1;}
		}
		/* Suppress single-sample discriminator glitches without erasing real short symbols. */
		for(int n=1;n+1<out.size();)if(out.get(n).micros<7d&&out.get(n-1).high==out.get(n+1).high){
			Run merged=new Run(out.get(n-1).high,out.get(n-1).samples+out.get(n).samples+out.get(n+1).samples,rate);
			out.set(n-1,merged);out.remove(n+1);out.remove(n);
		}else n++;
		return out;
	}

	private static String runSummary(List<Run> runs){StringBuilder out=new StringBuilder();
		for(int n=0;n<runs.size()&&n<80;n++){if(n>0)out.append(' ');Run run=runs.get(n);
			out.append(run.high?'H':'L').append(String.format(Locale.US,"%.0f",run.micros));}
		if(runs.size()>80)out.append(" ...");return out.toString();}

	private static double estimateUnitMicros(List<Run> runs){
		DoubleList values=new DoubleList();for(Run run:runs)if(run.micros>=6d&&run.micros<=24d)values.add(run.micros);
		if(values.size==0)return Double.NaN;double[] sorted=values.toArray();Arrays.sort(sorted);
		return sorted[sorted.length/2];
	}

	private static String alignedChipSummary(List<Run> runs,double unitMicros){
		StringBuilder chips=new StringBuilder();for(Run run:runs){int count=(int)Math.round(run.micros/unitMicros);
			if(count<1||count>80)continue;for(int n=0;n<count;n++)chips.append(run.high?'1':'0');}
		int bestStart=0,bestLength=0,start=0;for(int n=1;n<=chips.length();n++){
			if(n<chips.length()&&chips.charAt(n)!=chips.charAt(n-1))continue;
			int length=n-start;if(length>bestLength){bestStart=start;bestLength=length;}start=n;
		}
		int payload=Math.min(chips.length(),bestStart+bestLength);String tail=chips.substring(payload);
		String aes=aesProbe(tail);
		return "chips="+chips.length()+", alternating="+bestLength+"@"+bestStart+
				", post-preamble="+tail.substring(0,Math.min(96,tail.length()))+
				" | aligned hex "+bestHex(tail)+(aes.length()==0?"":" | "+aes);
	}

	private static String clockedChipSummary(double[] frequency,double[] power,int first,int last,
			double split,double powerGate,int rate){
		int activeFirst=-1,activeLast=-1;for(int n=first;n<last;n++)if(power[n]>=powerGate){
			if(activeFirst<0)activeFirst=n;activeLast=n;}
		if(activeFirst<0)return "clock recovery failed";Clocked best=null;
		for(double micros=9.95d;micros<=10.05d;micros+=.005d){double period=rate*micros*1e-6d;
			for(double phase=0d;phase<period;phase+=.25d){double origin=activeFirst+phase;
				StringBuilder bits=new StringBuilder();double quality=0d;int strong=0;
				for(double center=origin;center<=activeLast;center+=period){int at=(int)Math.round(center);
					int from=Math.max(first,(int)Math.ceil(center-period*.22d));
					int to=Math.min(last-1,(int)Math.floor(center+period*.22d));double weighted=0d,weight=0d;
					for(int n=from;n<=to;n++)if(power[n]>=powerGate*.55d){double w=Math.sqrt(power[n]);
						weighted+=(frequency[n]-split)*w;weight+=w;}
					if(weight==0d){bits.append('x');continue;}double value=weighted/weight;
					bits.append(value>=0d?'1':'0');quality+=Math.abs(value);strong++;}
				Alternating alternating=longestAlternating(bits);if(alternating.length<28)continue;
				double score=alternating.length*1e9d+(strong==0?0d:quality/strong);
				if(best==null||score>best.score)best=new Clocked(bits.toString(),alternating,micros,phase,score);
			}
		}
		if(best==null)return "clock recovery failed";String clean=best.bits.replace('x','0');
		String tail=clean.substring(Math.max(0,clean.length()-128));String aes=aesProbe(tail);
		return String.format(Locale.US,"clock %.3f us phase %.2f, chips=%d, alternating=%d@%d, final-frame=%s | frame hex %s%s",
				best.micros,best.phase,best.bits.length(),best.alternating.length,best.alternating.start,
				tail.substring(0,Math.min(96,tail.length())),bestHex(tail),aes.length()==0?"":" | "+aes);
	}

	private static Alternating longestAlternating(CharSequence bits){int bestStart=0,bestLength=0,start=0;
		for(int n=1;n<=bits.length();n++){boolean continues=n<bits.length()&&bits.charAt(n)!='x'
				&&bits.charAt(n-1)!='x'&&bits.charAt(n)!=bits.charAt(n-1);if(continues)continue;
			int length=n-start;if(length>bestLength){bestStart=start;bestLength=length;}start=n;}
		return new Alternating(bestStart,bestLength);}

	private static String aesProbe(String bits){String keyHex=System.getProperty("iperl.key","").replaceAll("[^0-9A-Fa-f]","");
		if(keyHex.length()!=32||bits.length()<128)return "";byte[] key=hexBytes(keyHex);
		long expected=-1;try{String id=System.getProperty("iperl.id","").trim();if(id.length()>0)expected=Long.parseLong(id);}
		catch(NumberFormatException ignored){ }
		try{Cipher aes=Cipher.getInstance("AES/ECB/NoPadding");aes.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"));
			for(int offset=0;offset<8&&offset+128<=bits.length();offset++){
				byte[] direct=bitBytes(bits,offset),inverted=invert(direct);
				byte[][] variants={direct,inverted,reverseBytes(direct),reverseBits(direct),
						reverseBytes(reverseBits(direct)),differential(direct,0),differential(direct,1)};
				for(int variant=0;variant<variants.length;variant++){byte[] clear=aes.doFinal(variants[variant]);
					if(expected>=0&&containsId(clear,expected))return "AES MATCH offset="+offset+", variant="+variant+
							", clear="+hex(clear);}
			}
			return "AES tried: no requested ID match";
		}catch(Exception error){return "AES probe failed: "+error.getClass().getSimpleName();}
	}

	private static boolean containsId(byte[] bytes,long id){byte[] be={(byte)(id>>>24),(byte)(id>>>16),(byte)(id>>>8),(byte)id};
		for(int at=0;at+4<=bytes.length;at++){boolean forward=true,reverse=true;for(int n=0;n<4;n++){
			forward&=bytes[at+n]==be[n];reverse&=bytes[at+n]==be[3-n];}if(forward||reverse)return true;}return false;}
	private static byte[] bitBytes(String bits,int offset){byte[] out=new byte[16];for(int b=0;b<16;b++){int value=0;
		for(int n=0;n<8;n++)value=(value<<1)|(bits.charAt(offset+b*8+n)-'0');out[b]=(byte)value;}return out;}
	private static byte[] invert(byte[] value){byte[] out=value.clone();for(int n=0;n<out.length;n++)out[n]=(byte)~out[n];return out;}
	private static byte[] reverseBytes(byte[] value){byte[] out=new byte[value.length];for(int n=0;n<out.length;n++)out[n]=value[value.length-1-n];return out;}
	private static byte[] reverseBits(byte[] value){byte[] out=new byte[value.length];for(int n=0;n<out.length;n++)out[n]=(byte)(Integer.reverse(value[n]&255)>>>24);return out;}
	private static byte[] differential(byte[] value,int seed){byte[] out=new byte[value.length];int previous=seed;
		for(int bit=0;bit<value.length*8;bit++){int next=(value[bit/8]>>>(7-bit%8))&1,diff=next^previous;
			out[bit/8]|=diff<<(7-bit%8);previous=next;}return out;}
	private static byte[] hexBytes(String value){byte[] out=new byte[value.length()/2];for(int n=0;n<out.length;n++)
		out[n]=(byte)Integer.parseInt(value.substring(n*2,n*2+2),16);return out;}
	private static String hex(byte[] value){StringBuilder out=new StringBuilder();for(byte next:value)out.append(String.format(Locale.US,"%02X",next&255));return out.toString();}

	private static String bestHex(String bits){StringBuilder all=new StringBuilder();
		for(int offset=0;offset<8&&offset<bits.length();offset++){if(offset>0)all.append(' ');all.append(offset).append(':');
			for(int at=offset;at+8<=bits.length();at+=8){int value=0;for(int n=0;n<8;n++)value=(value<<1)|(bits.charAt(at+n)-'0');
				all.append(String.format(Locale.US,"%02X",value));}}
		return all.toString();
	}

	private static String sampleRunBits(List<Run> runs,double unitMicros){
		if(!Double.isFinite(unitMicros)||unitMicros<=0d)return "no stable clock";
		StringBuilder bits=new StringBuilder();for(Run run:runs){int count=(int)Math.round(run.micros/unitMicros);
			if(count<1||count>40)continue;for(int n=0;n<count&&bits.length()<192;n++)bits.append(run.high?'1':'0');
			if(bits.length()>=192)break;}
		if(bits.length()<16)return "short bit train";StringBuilder hex=new StringBuilder();
		for(int at=0;at+8<=bits.length();at+=8){int value=0;for(int n=0;n<8;n++)value=(value<<1)|(bits.charAt(at+n)-'0');
			hex.append(String.format(Locale.US,"%02X",value));}
		return "bits "+bits.substring(0,Math.min(64,bits.length()))+" | hex "+hex;
	}

	private static double[] twoMeans(double[] values){Arrays.sort(values);double low=percentile(values,.20d),high=percentile(values,.80d);
		for(int loop=0;loop<12;loop++){double split=(low+high)/2d,ls=0d,hs=0d;int lc=0,hc=0;
			for(double value:values)if(value<split){ls+=value;lc++;}else{hs+=value;hc++;}
			if(lc==0||hc==0)break;low=ls/lc;high=hs/hc;}
		return new double[]{Math.min(low,high),Math.max(low,high)};
	}

	private static double percentile(double[] sorted,double fraction){if(sorted.length==0)return 0d;
		return sorted[Math.max(0,Math.min(sorted.length-1,(int)Math.round((sorted.length-1)*fraction)))];}
	private static double db(double power){return 10d*Math.log10(Math.max(1e-15,power));}

	private static final class Burst{final long first,last;Burst(long first,long last){this.first=first;this.last=last;}}
	private static final class Alternating{final int start,length;Alternating(int start,int length){this.start=start;this.length=length;}}
	private static final class Clocked{final String bits;final Alternating alternating;final double micros,phase,score;
		Clocked(String bits,Alternating alternating,double micros,double phase,double score){this.bits=bits;
			this.alternating=alternating;this.micros=micros;this.phase=phase;this.score=score;}}
	private static final class Run{final boolean high;final int samples;final double micros;
		Run(boolean high,int samples,int rate){this.high=high;this.samples=samples;micros=samples*1e6d/rate;}}
	private static final class DoubleList{double[] values=new double[16_384];int size;
		void add(double value){if(size==values.length)values=Arrays.copyOf(values,values.length*2);values[size++]=value;}
		double[] toArray(){return Arrays.copyOf(values,size);}}
}
