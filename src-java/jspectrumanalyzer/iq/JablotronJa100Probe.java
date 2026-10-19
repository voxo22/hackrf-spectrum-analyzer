package jspectrumanalyzer.iq;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Fast offline inventory of JA-100-like physical-layer bursts in an IQ recording. */
public final class JablotronJa100Probe {
	private static final double CHUNK_SECONDS=.25d;
	private JablotronJa100Probe(){ }

	public static void main(String[] args)throws Exception{
		if(args.length==0){System.err.println("Usage: JablotronJa100Probe <iq.wav|iq.pcm|iq.cu8> [...]");return;}
		for(String argument:args)probe(new File(argument));
	}

	private static void probe(File file)throws Exception{
		try(IQReplayFile replay=IQReplayFile.open(file)){
			int rate=replay.getSampleRateHz(),chunkSamples=Math.max(4096,(int)Math.round(rate*CHUNK_SECONDS));
			long total=replay.getTotalIqSamples(),consumed=0L,center=replay.getCenterFrequencyHz();
			JablotronJa100Classifier classifier=new JablotronJa100Classifier();List<Group> groups=new ArrayList<Group>();
			System.out.println(String.format(Locale.US,"%s: center %.6f MHz, %.3f MS/s, %.3f s",
					file.getName(),center/1e6,rate/1e6,total/(double)rate));
			while(consumed<total){int samples=(int)Math.min(chunkSamples,total-consumed);byte[] iq=new byte[samples*2];
				replay.readLoopedSigned(iq);for(JablotronJa100Classifier.Candidate candidate:
						classifier.classify(iq,iq.length,rate)){
					long frequency=Math.round(center+candidate.carrierOffsetHz);double seconds=consumed/(double)rate;
					System.out.println(String.format(Locale.US,
							"  %7.3f s  %.6f MHz  %5.2f ms  %.2f ksym/s  tones %.1f kHz  preamble %d  bits %d  next %s  confidence %.0f%%",
							seconds,frequency/1e6,candidate.burstMillis,candidate.symbolRate/1000d,
							candidate.toneSeparationHz/1000d,candidate.preambleBits,candidate.frameBits,
							candidate.fingerprint,candidate.confidence));
					Group group=matching(groups,frequency);if(group==null){group=new Group();groups.add(group);}
					group.add(frequency,candidate);
				}consumed+=samples;
			}
			System.out.println("  summary:");for(Group group:groups)System.out.println(group.line());
		}
	}

	private static Group matching(List<Group> groups,long frequency){Group best=null;long distance=Long.MAX_VALUE;
		for(Group group:groups){long next=Math.abs(group.frequencyHz-frequency);
			if(next<=12_000L&&next<distance){best=group;distance=next;}}return best;}

	private static final class Group{
		long frequencyHz;int count;double separation,symbolRate,burst,confidence;
		void add(long frequency,JablotronJa100Classifier.Candidate value){frequencyHz=count==0?frequency:
				Math.round((frequencyHz*(double)count+frequency)/(count+1d));separation+=value.toneSeparationHz;
			symbolRate+=value.symbolRate;burst+=value.burstMillis;confidence+=value.confidence;count++;}
		String line(){return String.format(Locale.US,
				"    %.6f MHz: %d frame(s), mean %.2f ksym/s, tones %.1f kHz, burst %.2f ms, confidence %.0f%%",
				frequencyHz/1e6,count,symbolRate/count/1000d,separation/count/1000d,burst/count,confidence/count);}
	}
}
