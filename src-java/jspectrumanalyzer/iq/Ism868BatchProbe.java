package jspectrumanalyzer.iq;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Offline, whole-recording probe for wide-band 863-870 MHz IQ files.
 *
 * <p>The GUI analyzer deliberately shows only a recent live window.  This
 * command-line companion streams the complete recording through the same PFB,
 * modulation profiler and protocol decoders, then merges repeated observations
 * into a compact inventory.  A protocol is reported as confirmed only when its
 * decoder supplied the corresponding PHY/checksum evidence.</p>
 */
public final class Ism868BatchProbe {
	private static final double BLOCK_SECONDS=.050d;
	private static final double ANALYSIS_SECONDS=.250d;
	private static final int MIN_ANALYSIS_BYTES=8192;

	private Ism868BatchProbe(){ }

	public static void main(String[] args)throws Exception{
		if(args.length==0){
			System.err.println("Usage: Ism868BatchProbe <iq.wav|iq.pcm|iq.cu8> [...]");
			return;
		}
		for(String argument:args)probe(new File(argument));
	}

	private static void probe(File file)throws Exception{
		try(IQReplayFile replay=IQReplayFile.open(file)){
			long center=replay.getCenterFrequencyHz();int rate=replay.getSampleRateHz();
			long recordingStart=IQReplayFile.getRecordingStartEpochMillis(file);
			int blockBytes=even(Math.max(MIN_ANALYSIS_BYTES,
					(int)Math.min(Integer.MAX_VALUE-8L,Math.round(rate*BLOCK_SECONDS*2d))));
			int analysisBytes=even(Math.max(blockBytes,
					(int)Math.min(Integer.MAX_VALUE-8L,Math.round(rate*ANALYSIS_SECONDS*2d))));
			byte[] wide=new byte[analysisBytes];int wideLength=0;
			IQPolyphaseChannelizer pfb=new IQPolyphaseChannelizer(center,rate);
			IQSubchannelExtractor.Stream ct=visible(center,rate,WmbusPhyDecoder.CT_FREQUENCY_HZ)
					?new IQSubchannelExtractor.Stream(rate,WmbusPhyDecoder.CT_FREQUENCY_HZ-center,
							WmbusPhyDecoder.CT_OCCUPIED_BANDWIDTH,WmbusPhyDecoder.CHANNEL_OUTPUT_RATE):null;
			IQSubchannelExtractor.Stream s=visible(center,rate,WmbusPhyDecoder.S_FREQUENCY_HZ)
					?new IQSubchannelExtractor.Stream(rate,WmbusPhyDecoder.S_FREQUENCY_HZ-center,
							WmbusPhyDecoder.S_OCCUPIED_BANDWIDTH,WmbusPhyDecoder.CHANNEL_OUTPUT_RATE):null;
			FloatHistory ctHistory=new FloatHistory(),sHistory=new FloatHistory();
			Ism868SignalAnalyzer analyzer=new Ism868SignalAnalyzer();
			Inventory inventory=new Inventory(recordingStart);
			long samples=0L,nextAnalysis=Math.max(1L,Math.round(rate*ANALYSIS_SECONDS));
			long durationMillis=replay.getDurationMillis(),nextProgressMillis=5_000L;
			long wallStart=System.nanoTime();
			System.out.println(String.format(Locale.US,
					"%s: center %.6f MHz, %.3f MS/s, %.3f s, visible %.3f-%.3f MHz",
					file.getName(),center/1e6,rate/1e6,durationMillis/1000d,
					(center-rate/2d)/1e6,(center+rate/2d)/1e6));
			while(true){
				int count=nonLoopingBytes(replay,blockBytes);if(count<2)break;
				byte[] block=count==blockBytes?new byte[blockBytes]:new byte[count];
				replay.readLoopedSigned(block);
				pfb.accept(block,block.length);
				if(ct!=null)ctHistory.append(ct.process(block,block.length));
				if(s!=null)sHistory.append(s.process(block,block.length));
				int copy=Math.min(block.length,wide.length-wideLength);
				System.arraycopy(block,0,wide,wideLength,copy);wideLength+=copy;
				samples+=block.length/2L;
				if(samples>=nextAnalysis){
					analyze(analyzer,pfb,ctHistory,sHistory,inventory,center,rate,wide,wideLength,
							recordingStart+Math.round(samples*1000d/rate));
					wideLength=0;while(nextAnalysis<=samples)nextAnalysis+=Math.max(1L,
							Math.round(rate*ANALYSIS_SECONDS));
				}
				long elapsedMillis=Math.round(samples*1000d/rate);
				if(elapsedMillis>=nextProgressMillis){
					System.err.println(String.format(Locale.US,"  scanned %.1f / %.1f s",
							elapsedMillis/1000d,durationMillis/1000d));
					while(nextProgressMillis<=elapsedMillis)nextProgressMillis+=5_000L;
				}
			}
			if(wideLength>=MIN_ANALYSIS_BYTES){
				analyze(analyzer,pfb,ctHistory,sHistory,inventory,center,rate,wide,wideLength,
						recordingStart+Math.round(samples*1000d/rate));
			}
			/* Complete a carrier that happens to reach EOF so burst decoders receive
			 * the same falling edge they would see in a live session. */
			byte[] silence=new byte[even(Math.max(MIN_ANALYSIS_BYTES,(int)Math.round(rate*.050d)*2))];
			pfb.accept(silence,silence.length);
			if(ct!=null)ctHistory.append(ct.process(silence,silence.length));
			if(s!=null)sHistory.append(s.process(silence,silence.length));
			analyze(analyzer,pfb,ctHistory,sHistory,inventory,center,rate,silence,silence.length,
					recordingStart+Math.round(samples*1000d/rate)+50L);
			inventory.print((System.nanoTime()-wallStart)/1e9);
		}
	}

	private static void analyze(Ism868SignalAnalyzer analyzer,IQPolyphaseChannelizer pfb,
			FloatHistory ct,FloatHistory s,Inventory inventory,long center,int rate,
			byte[] wide,int wideLength,long captureMillis){
		Ism868SignalAnalyzer.ChannelizedWmbus channels=new Ism868SignalAnalyzer.ChannelizedWmbus(
				ct.snapshotAndKeep(),ct.sampleRateHz,s.snapshotAndKeep(),s.sampleRateHz,
				pfb.snapshotAndKeep(),true);
		Ism868SignalAnalyzer.Result result=analyzer.analyze(center,rate,wide,wideLength,
				captureMillis,channels);
		inventory.add(result);
	}

	private static boolean visible(long center,int rate,long frequency){
		return Math.abs(frequency-center)<rate/2d;
	}

	private static int even(int value){return value&~1;}

	/** Largest output byte count that IQReplayFile can read without wrapping. */
	private static int nonLoopingBytes(IQReplayFile replay,int maximum){
		maximum=even(maximum);if(maximum<2)return 0;
		if(!replay.wouldLoopOnNextRead(maximum))return maximum;
		int low=0,high=maximum/2;
		while(low<high){int middle=(low+high+1)>>>1;
			if(replay.wouldLoopOnNextRead(middle*2))high=middle-1;else low=middle;}
		return low*2;
	}

	private static final class FloatHistory{
		float[] values=new float[0];int length;double sampleRateHz;
		void append(IQSubchannelExtractor.Result block){
			if(block==null||block.iq.length==0)return;sampleRateHz=block.sampleRateHz;
			int capacity=even((int)Math.min(2_000_000L,Math.max(16_384L,
					Math.round(sampleRateHz*.7d))));
			if(values.length!=capacity){float[] next=new float[capacity];int keep=Math.min(length,capacity)&~1;
				if(keep>0)System.arraycopy(values,length-keep,next,0,keep);values=next;length=keep;}
			int copy=Math.min(block.iq.length,capacity)&~1;
			if(copy==capacity){System.arraycopy(block.iq,block.iq.length-copy,values,0,copy);length=copy;return;}
			int overflow=Math.max(0,length+copy-capacity)&~1;
			if(overflow>0){System.arraycopy(values,overflow,values,0,length-overflow);length-=overflow;}
			System.arraycopy(block.iq,block.iq.length-copy,values,length,copy);length+=copy;
		}
		float[] snapshotAndKeep(){float[] out=Arrays.copyOf(values,length);
			int overlap=Math.min(length,(int)Math.round(sampleRateHz*.3d))&~1;
			if(overlap>0)System.arraycopy(values,length-overlap,values,0,overlap);length=overlap;return out;}
	}

	private static final class Inventory{
		final long recordingStart;final List<Finding> findings=new ArrayList<Finding>();
		Inventory(long recordingStart){this.recordingStart=recordingStart;}
		void add(Ism868SignalAnalyzer.Result result){
			for(Ism868SignalAnalyzer.Observation observation:result.observations){
				Finding match=matching(observation);if(match==null){match=new Finding(observation,recordingStart);
					findings.add(match);}else match.update(observation,recordingStart);
			}
		}
		private Finding matching(Ism868SignalAnalyzer.Observation observation){
			boolean identified=identified(observation);
			for(Finding finding:findings){if(finding.identified!=identified)continue;
				if(identified&&observation.meterId.length()>0){
					if(finding.protocol.equals(observation.protocol)&&finding.meterId.equals(observation.meterId)
							&&finding.manufacturer.equals(observation.manufacturer))return finding;
					continue;
				}
				if(identified&&!finding.driver.equals(driver(observation)))continue;
				if(!identified&&(!finding.protocol.equals(observation.protocol)
						||!finding.modulation.equals(observation.modulation)))continue;
				long tolerance=Math.max(35_000L,Math.max(finding.bandwidthHz,observation.bandwidthHz)/2L);
				if(Math.abs(finding.frequencyHz-observation.frequencyHz)<=tolerance)return finding;
			}
			return null;
		}
		void print(double wallSeconds){
			Collections.sort(findings,new Comparator<Finding>(){
				@Override public int compare(Finding left,Finding right){
					if(left.identified!=right.identified)return left.identified?-1:1;
					int confidence=Double.compare(right.confidence,left.confidence);
					return confidence!=0?confidence:Long.compare(left.frequencyHz,right.frequencyHz);
				}});
			int confirmed=0,classifierOnly=0;Map<String,Integer> modulationCounts=new LinkedHashMap<String,Integer>();
			for(Finding finding:findings){if(finding.checksumValid)confirmed++;
				else if(finding.identified)classifierOnly++;
				Integer old=modulationCounts.get(finding.modulation);
				modulationCounts.put(finding.modulation,Integer.valueOf(old==null?1:old.intValue()+1));}
			System.out.println(String.format(Locale.US,
					"  summary: %d merged finding(s), %d checksum/header-confirmed, %d classifier-only, wall %.2f s",
					findings.size(),confirmed,classifierOnly,wallSeconds));
			System.out.println("  candidate-label inventory (unconfirmed labels are heuristic): "+modulationCounts);
			System.out.println("  note: only [CONFIRMED] rows identify a protocol frame; candidate rows may include aliases or interference");
			for(Finding finding:findings)System.out.println(finding.line());
		}
		private static boolean identified(Ism868SignalAnalyzer.Observation observation){
			return observation.checksumValid||driver(observation).length()>0;
		}
		private static String driver(Ism868SignalAnalyzer.Observation observation){
			return observation.application==null?"":observation.application.driver;
		}
	}

	private static final class Finding{
		long frequencyHz;int bandwidthHz,count;double levelDbfs,snrDb,confidence;
		long firstMillis,lastMillis;final boolean identified,checksumValid;
		final String protocol,modulation,manufacturer,meterId,driver;
		String details,values,payload;
		Finding(Ism868SignalAnalyzer.Observation value,long recordingStart){
			identified=Inventory.identified(value);checksumValid=value.checksumValid;
			protocol=value.protocol;modulation=value.modulation;manufacturer=value.manufacturer;
			meterId=value.meterId;driver=Inventory.driver(value);count=0;update(value,recordingStart);
		}
		void update(Ism868SignalAnalyzer.Observation value,long recordingStart){
			frequencyHz=count==0?value.frequencyHz:Math.round((frequencyHz*(double)count+value.frequencyHz)/(count+1d));
			bandwidthHz=Math.max(bandwidthHz,value.bandwidthHz);levelDbfs=maxFinite(levelDbfs,value.levelDbfs,count);
			snrDb=maxFinite(snrDb,value.snrDb,count);confidence=Math.max(confidence,value.confidence);
			long relative=Math.max(0L,value.captureMillis-recordingStart);
			if(count==0)firstMillis=relative;lastMillis=relative;count++;
			details=value.details;payload=value.payloadHex;
			values=value.application==null?"":value.application.summary();
		}
		String line(){String evidence=checksumValid?"CONFIRMED":"candidate";
			StringBuilder out=new StringBuilder(String.format(Locale.US,
					"  [%s] %.6f MHz, BW %.0f kHz, %s, %s, seen %d, %.2f-%.2f s",
					evidence,frequencyHz/1e6,bandwidthHz/1000d,modulation,protocol,count,
					firstMillis/1000d,lastMillis/1000d));
			if(!Double.isNaN(snrDb))out.append(String.format(Locale.US,", max SNR %.1f dB",snrDb));
			if(manufacturer.length()>0)out.append(" | maker ").append(manufacturer);
			if(meterId.length()>0)out.append(" | ID ").append(meterId);
			if(driver.length()>0)out.append(" | driver ").append(driver);
			if(values.length()>0)out.append(" | values ").append(values);
			out.append(" | ").append(details);
			if(Boolean.getBoolean("ism868.raw")&&payload.length()>0)out.append(" | payload ").append(payload);
			return out.toString();
		}
		private static double maxFinite(double old,double next,int count){
			if(Double.isNaN(next))return count==0?Double.NaN:old;
			return count==0||Double.isNaN(old)?next:Math.max(old,next);
		}
	}
}
