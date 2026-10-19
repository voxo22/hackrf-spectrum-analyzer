package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lightweight protocol-agnostic profiler and decoder router for PFB captures.
 * It intentionally does not perform a speculative payload decode: its job is
 * to keep expensive protocol decoders away from physically incompatible IQ.
 */
final class Ism868ProtocolDispatcher {
	private static final int MAX_PROFILE_SAMPLES=65_536;
	private static final int MAX_DECODER_JOBS_PER_WINDOW=6;
	private static final long STATE_LIFETIME_MS=120_000L;
	private final Map<String,History> history=new HashMap<String,History>();

	synchronized List<Dispatch> dispatch(List<IQDynamicChannelizer.Capture> captures,long captureMillis){
		return dispatch(captures,captureMillis,false);
	}

	synchronized List<Dispatch> dispatch(List<IQDynamicChannelizer.Capture> captures,long captureMillis,
			boolean exhaustive){
		if(captures==null||captures.isEmpty())return Collections.emptyList();
		long now=captureMillis>0L?captureMillis:System.currentTimeMillis();
		Map<String,Dispatch> strongest=new LinkedHashMap<String,Dispatch>();
		List<Dispatch> completed=new ArrayList<Dispatch>();
		for(IQDynamicChannelizer.Capture capture:captures){
			Profile profile=profile(capture);String key=key(capture.frequencyHz,profile.modulation);
			History old=history.get(key);long period=0L;long seen=1L;
			if(old==null){old=new History();history.put(key,old);}
			else{seen=old.seen+1L;if(now>old.lastMillis)period=now-old.lastMillis;}
			old.lastMillis=now;old.seen=seen;
			if(period>0L)old.periodMillis=old.periodMillis<=0d?period:old.periodMillis*.75d+period*.25d;
			Dispatch next=route(capture,profile,seen,old.periodMillis);
			/* Every frozen falling-edge capture is a distinct burst.  Deduplicating it
			 * by frequency can replace a weak LSE frame with a later, stronger signal
			 * before the CRC-backed decoder gets to see it. */
			if(capture.complete){completed.add(next);continue;}
			Dispatch previous=strongest.get(key);
			if(previous==null||next.priority>previous.priority)strongest.put(key,next);
		}
		for(java.util.Iterator<Map.Entry<String,History>> it=history.entrySet().iterator();it.hasNext();)
			if(now-it.next().getValue().lastMillis>STATE_LIFETIME_MS)it.remove();
		List<Dispatch> result=new ArrayList<Dispatch>(completed);
		result.addAll(strongest.values());
		Collections.sort(result,new Comparator<Dispatch>(){
			@Override public int compare(Dispatch left,Dispatch right){return Double.compare(right.priority,left.priority);}});
		int extraClockDriftJobs=0;
		for(int n=0;n<result.size();n++){
			Dispatch dispatch=result.get(n);
			boolean completedClockDrift="W-MBus S clock-drift".equals(dispatch.decoder)
					&&dispatch.capture.complete;
			/* A valid but weak S-mode burst must not lose its only decode attempt just
			 * because six simultaneous C/T bursts have higher SNR.  Permit at most one
			 * extra completed fallback job, so the GUI budget remains bounded. */
			dispatch.decoderEligible=exhaustive||n<MAX_DECODER_JOBS_PER_WINDOW
					||(completedClockDrift&&extraClockDriftJobs++==0);
		}
		return result;
	}

	static Profile profileForTest(IQDynamicChannelizer.Capture capture){return profile(capture);}

	private static Dispatch route(IQDynamicChannelizer.Capture capture,Profile profile,long seen,double periodMillis){
		long ctDistance=Math.abs(capture.frequencyHz-WmbusPhyDecoder.CT_FREQUENCY_HZ);
		long sDistance=Math.abs(capture.frequencyHz-WmbusPhyDecoder.S_FREQUENCY_HZ);
		String protocol,decoder;
		/* An S-mode Manchester stream at a PFB/Nyquist edge can resemble alternating
		 * chirps at half its 32.768 kchip/s clock.  Route that narrow signature to
		 * the CRC-backed W-MBus decoder before accepting the heuristic CSS label. */
		if("CSS".equals(profile.modulation)&&sDistance<=400_000L
				&&profile.symbolRate>=12_000d&&profile.symbolRate<=20_000d){
			protocol="W-MBus S candidate";decoder="W-MBus S clock-drift";
		}else if("CSS".equals(profile.modulation)){protocol="LoRa candidate";decoder="LoRa";}
		/* A faded/partially clipped LSE burst sometimes has too few clean frequency
		 * samples for the CSS-edge signature.  Its short, wide PFB image close to
		 * 868.3 MHz is still distinctive enough for one CRC-backed fallback try. */
		else if("unknown".equals(profile.modulation)&&sDistance<=100_000L
				&&profile.burstMillis>=20d&&profile.burstMillis<=120d
				&&profile.occupiedBandwidthHz>=200_000){
			protocol="W-MBus S candidate";decoder="W-MBus S clock-drift";
		}
		else if("FSK".equals(profile.modulation)&&ctDistance<=150_000L){
			protocol="W-MBus C/T candidate";decoder="W-MBus C/T";
		}else if("FSK".equals(profile.modulation)&&sDistance<=100_000L){
			protocol="W-MBus S candidate";decoder="W-MBus S";
		}else if("OOK".equals(profile.modulation)){protocol="SRD / rtl_433 candidate";decoder="rtl_433 OOK";}
		else if("FSK".equals(profile.modulation)){protocol="SRD / rtl_433 candidate";decoder="rtl_433 FSK";}
		else{protocol="Unclassified ISM burst";decoder="signal probe";}
		double known="signal probe".equals(decoder)?0d:18d;
		double priority=Math.max(0d,Math.min(100d,25d+capture.snrDb*2d+profile.confidence*.35d+known));
		StringBuilder details=new StringBuilder("streaming channel PFB | ").append(profile.displayModulation);
		if(profile.occupiedBandwidthHz>0)details.append(String.format(Locale.US,", BW ~%.0f kHz",profile.occupiedBandwidthHz/1000d));
		if(profile.symbolRate>0d)details.append(String.format(Locale.US,", rate ~%.0f sym/s",profile.symbolRate));
		if(profile.burstMillis>0d)details.append(String.format(Locale.US,", active span %.0f ms",profile.burstMillis));
		details.append(" | route ").append(decoder).append(String.format(Locale.US," (priority %.0f)",priority));
		if(seen>1L){details.append(" | seen #").append(seen);
			if(periodMillis>0d)details.append(String.format(Locale.US,", observation gap %.1f s",periodMillis/1000d));}
		return new Dispatch(capture,profile,protocol,decoder,details.toString(),priority);
	}

	private static Profile profile(IQDynamicChannelizer.Capture capture){
		float[] iq=capture.iq;int available=iq==null?0:iq.length/2;
		if(available<32||capture.sampleRateHz<=0d)return new Profile("unknown","Unknown",0d,
				capture.bandwidthHz,0d,5d);
		int samples=Math.min(available,MAX_PROFILE_SAMPLES),first=available-samples;
		double[] amplitude=new double[samples];
		for(int n=0;n<samples;n++){double i=iq[(first+n)*2],q=iq[(first+n)*2+1];
			amplitude[n]=Math.sqrt(i*i+q*q);}
		double[] sortedAmplitude=amplitude.clone();Arrays.sort(sortedAmplitude);
		double a10=percentile(sortedAmplitude,.10d),a20=percentile(sortedAmplitude,.20d);
		double a50=percentile(sortedAmplitude,.50d),a90=percentile(sortedAmplitude,.90d);
		double amplitudeDepth=20d*Math.log10((a90+1e-9d)/(a10+1e-9d));
		double activeThreshold=a10+(a90-a10)*.25d;int activeFirst=-1,activeLast=-1;
		for(int n=0;n<samples;n++){
			if(amplitude[n]>activeThreshold){if(activeFirst<0)activeFirst=n;activeLast=n;}}
		double burstMillis=(activeFirst<0?0d:(activeLast-activeFirst+1)*1000d/capture.sampleRateHz);
		int interior=Math.max(0,activeLast-activeFirst+1),low=0,high=0;
		for(int n=Math.max(0,activeFirst);n<=activeLast;n++){if(amplitude[n]<=a20)low++;
			if(amplitude[n]>=a90*.8d)high++;}
		/* Leading/trailing silence belongs to the capture envelope, not to OOK.
		 * Judge amplitude keying only inside the detected burst itself. */
		boolean ook=interior>0&&amplitudeDepth>9d&&low>interior/12&&high>interior/12;

		double gate=Math.max(a50*.18d,a90*.06d),scale=capture.sampleRateHz/(2d*Math.PI);
		double[] frequency=new double[Math.max(0,samples-1)];int frequencyCount=0;
		for(int n=1;n<samples;n++)if(amplitude[n]>=gate&&amplitude[n-1]>=gate){
			double pi=iq[(first+n-1)*2],pq=iq[(first+n-1)*2+1];
			double ci=iq[(first+n)*2],cq=iq[(first+n)*2+1];
			frequency[frequencyCount++]=Math.atan2(cq*pi-ci*pq,ci*pi+cq*pq)*scale;
		}
		if(frequencyCount<24){double rate=ook?symbolRate(amplitude,activeThreshold,capture.sampleRateHz):0d;
			return new Profile(ook?"OOK":"unknown",ook?"ASK/OOK":"Unknown",rate,
				ook?ookBandwidth(rate,capture.bandwidthHz):capture.bandwidthHz,burstMillis,ook?75d:10d);}
		double[] sortedFrequency=Arrays.copyOf(frequency,frequencyCount);Arrays.sort(sortedFrequency);
		double f01=percentile(sortedFrequency,.01d),f05=percentile(sortedFrequency,.05d),f25=percentile(sortedFrequency,.25d);
		double f50=percentile(sortedFrequency,.50d),f75=percentile(sortedFrequency,.75d);
		double f95=percentile(sortedFrequency,.95d),f99=percentile(sortedFrequency,.99d),spread=f95-f05;
		Chirp chirp=chirp(frequency,frequencyCount,capture.sampleRateHz,spread);
		/* The PFB deliberately keeps pre-trigger and post-trigger silence.  Its
		 * amplitude depth therefore says nothing about whether the active signal
		 * is constant-envelope CSS; the interior OOK test above is the guard. */
		if(!ook&&spread>=45_000d&&chirp.valid){
			int bandwidth=snapLoraBandwidth(spread);
			return new Profile("CSS","CSS / chirp",chirp.rate,bandwidth,burstMillis,92d);
		}
		double threshold=(f25+f75)/2d,lowSum=0d,highSum=0d;int lowCount=0,highCount=0;
		for(int n=0;n<frequencyCount;n++)if(frequency[n]<threshold){lowSum+=frequency[n];lowCount++;}
			else{highSum+=frequency[n];highCount++;}
		double lowMean=lowCount==0?f25:lowSum/lowCount,highMean=highCount==0?f75:highSum/highCount;
		double variance=0d;
		for(int n=0;n<frequencyCount;n++){double center=frequency[n]<threshold?lowMean:highMean;
			variance+=(frequency[n]-center)*(frequency[n]-center);}
		double within=Math.sqrt(variance/frequencyCount),separation=highMean-lowMean;
		boolean fsk=lowCount>frequencyCount/10&&highCount>frequencyCount/10
				&&separation>=4_000d&&separation>within*2.2d;
		if(fsk){double rate=symbolRate(frequency,threshold,capture.sampleRateHz);
			int bandwidth=(int)Math.round(Math.max(5_000d,separation+Math.max(0d,rate)*2d));
			return new Profile("FSK","2-FSK / GFSK",rate,Math.min(1_500_000,bandwidth),burstMillis,
					Math.min(90d,60d+separation/Math.max(1d,within)*4d),separation);}
		/* Deep fading can make a constant-envelope FSK burst look amplitude keyed.
		 * Prefer two statistically separated frequency states; a real OOK carrier
		 * has only one gated frequency cluster and therefore cannot pass this test. */
		if(ook){double rate=symbolRate(amplitude,activeThreshold,capture.sampleRateHz);
			return new Profile("OOK","ASK/OOK",rate,ookBandwidth(rate,capture.bandwidthHz),burstMillis,82d);}
		double pskRate=pskSymbolRate(frequency,frequencyCount,f50,f75-f25,capture.sampleRateHz);
		double pskSpread=f99-f01;
		if(!ook&&amplitudeDepth<7d&&pskSpread>=18_000d&&pskRate>0d)
			return new Profile("PSK","PSK candidate",pskRate,
					(int)Math.round(Math.max(5_000d,Math.min(capture.bandwidthHz,pskSpread))),burstMillis,58d);
		String family=spread<2_500d?"CW":"unknown";
		return new Profile(family,"CW".equals(family)?"CW":"Unknown",0d,
				(int)Math.round(Math.max(2_000d,Math.min(capture.bandwidthHz,spread))),burstMillis,25d);
	}

	private static double pskSymbolRate(double[] frequency,int count,double center,double centralWidth,
			double sampleRate){
		double threshold=Math.max(15_000d,Math.max(2_000d,centralWidth)*5d);
		List<Integer> edges=new ArrayList<Integer>();boolean inEdge=false;
		for(int n=0;n<count;n++){boolean edge=Math.abs(frequency[n]-center)>=threshold;
			if(edge&&!inEdge)edges.add(Integer.valueOf(n));inEdge=edge;}
		if(edges.size()<4||edges.size()>count/4)return 0d;
		double[] intervals=new double[edges.size()-1];for(int n=1;n<edges.size();n++)
			intervals[n-1]=edges.get(n).intValue()-edges.get(n-1).intValue();
		Arrays.sort(intervals);double samplesPerSymbol=percentile(intervals,.18d);
		if(samplesPerSymbol<3d)return 0d;double rate=sampleRate/samplesPerSymbol;
		return rate>=100d&&rate<=250_000d?rate:0d;
	}

	private static Chirp chirp(double[] frequency,int count,double sampleRate,double spread){
		if(count<128||spread<45_000d)return Chirp.NONE;
		double[] differences=new double[count-1],absolute=new double[count-1];
		for(int n=1;n<count;n++){differences[n-1]=frequency[n]-frequency[n-1];absolute[n-1]=Math.abs(differences[n-1]);}
		Arrays.sort(absolute);double ordinary=percentile(absolute,.55d);
		double threshold=Math.max(12_000d,Math.max(spread*.35d,ordinary*10d));
		List<Integer> positive=new ArrayList<Integer>(),negative=new ArrayList<Integer>();int lastPositive=-10,lastNegative=-10;
		for(int n=0;n<differences.length;n++)if(differences[n]>threshold){
			if(n-lastPositive>4)positive.add(Integer.valueOf(n));lastPositive=n;
		}else if(differences[n]<-threshold){if(n-lastNegative>4)negative.add(Integer.valueOf(n));lastNegative=n;}
		List<Integer> jumps=positive.size()>=negative.size()?positive:negative;
		if(jumps.size()<3)return Chirp.NONE;
		double[] intervals=new double[jumps.size()-1];for(int n=1;n<jumps.size();n++)intervals[n-1]=jumps.get(n)-jumps.get(n-1);
		Arrays.sort(intervals);double period=percentile(intervals,.50d),deviation=0d;
		for(double interval:intervals)deviation+=Math.abs(interval-period);
		deviation/=intervals.length;if(period<16d||deviation>period*.22d)return Chirp.NONE;
		return new Chirp(true,sampleRate/period);
	}

	private static double symbolRate(double[] values,double threshold,double sampleRate){
		if(values.length<16)return 0d;List<Integer> runs=new ArrayList<Integer>();
		boolean state=values[0]>=threshold;int length=1;
		for(int n=1;n<values.length;n++){boolean next=values[n]>=threshold;
			if(next==state)length++;else{if(length>=2)runs.add(Integer.valueOf(length));state=next;length=1;}}
		if(length>=2)runs.add(Integer.valueOf(length));if(runs.size()<3)return 0d;
		Collections.sort(runs);int at=Math.min(runs.size()-1,Math.max(0,runs.size()/5));
		double samplesPerSymbol=runs.get(at).intValue();return samplesPerSymbol<=0d?0d:sampleRate/samplesPerSymbol;
	}

	private static int ookBandwidth(double rate,int fallback){return rate>0d
			?(int)Math.round(Math.max(5_000d,Math.min(500_000d,rate*2.5d))):Math.min(500_000,fallback);}
	private static int snapLoraBandwidth(double spread){int[] widths={125_000,250_000,500_000};int best=widths[0];
		for(int width:widths)if(Math.abs(width-spread)<Math.abs(best-spread))best=width;return best;}
	private static double percentile(double[] sorted,double fraction){if(sorted.length==0)return 0d;
		int at=(int)Math.round((sorted.length-1)*fraction);return sorted[Math.max(0,Math.min(sorted.length-1,at))];}
	private static String key(long frequency,String modulation){return Math.round(frequency/25_000d)+":"+modulation;}

	static final class Profile{
		final String modulation,displayModulation;final double symbolRate;final int occupiedBandwidthHz;
		final double burstMillis,confidence,toneSeparationHz;
		Profile(String modulation,String displayModulation,double symbolRate,int occupiedBandwidthHz,
				double burstMillis,double confidence){this(modulation,displayModulation,symbolRate,
				occupiedBandwidthHz,burstMillis,confidence,Double.NaN);}
		Profile(String modulation,String displayModulation,double symbolRate,int occupiedBandwidthHz,
				double burstMillis,double confidence,double toneSeparationHz){
			this.modulation=modulation;this.displayModulation=displayModulation;this.symbolRate=symbolRate;
			this.occupiedBandwidthHz=occupiedBandwidthHz;this.burstMillis=burstMillis;
			this.confidence=confidence;this.toneSeparationHz=toneSeparationHz;}
	}
	static final class Dispatch{
		final IQDynamicChannelizer.Capture capture;final Profile profile;
		final String protocol,decoder,details;final double priority;boolean decoderEligible;
		Dispatch(IQDynamicChannelizer.Capture capture,Profile profile,String protocol,String decoder,
				String details,double priority){this.capture=capture;this.profile=profile;this.protocol=protocol;
			this.decoder=decoder;this.details=details;this.priority=priority;}
	}
	private static final class Chirp{static final Chirp NONE=new Chirp(false,0d);
		final boolean valid;final double rate;Chirp(boolean valid,double rate){this.valid=valid;this.rate=rate;}}
	private static final class History{long lastMillis,seen;double periodMillis;}
}
