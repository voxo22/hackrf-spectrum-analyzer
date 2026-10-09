package jspectrumanalyzer.iq;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Standalone real-capture regression test. */
public final class AutomotiveKeyfobClassifierTest {
	private AutomotiveKeyfobClassifierTest(){ }
	public static void main(String[] args)throws Exception{
		File file=new File("868300000Hz_Audi_keyfob_2.wav");
		if(!file.isFile()){System.out.println("AutomotiveKeyfobClassifierTest: sample absent, skipped");return;}
		try(IQReplayFile replay=IQReplayFile.open(file)){
			int rate=replay.getSampleRateHz();int bytes=(int)Math.min(10L*rate*2L,
					Math.round(replay.getDurationMillis()*rate/500d));byte[] raw=new byte[bytes&~1];
			replay.readLoopedSigned(raw);float[] iq=new float[raw.length];for(int n=0;n<raw.length;n++)iq[n]=raw[n]/128f;
			List<AutomotiveKeyfobClassifier.Decoded> decoded=new AutomotiveKeyfobClassifier().decodeAll(iq,replay.getSampleRateHz());
			check(decoded.size()==4,"four repeated keyfob-like bursts");for(AutomotiveKeyfobClassifier.Decoded frame:decoded){
				check(frame.durationMs>175d&&frame.durationMs<187d,"burst duration");
				check(frame.preambleTransitions>=100,"long FBS sync train");
				check(frame.toneSeparationHz>20_000d&&frame.toneSeparationHz<90_000d,"2-FSK separation");}
			/* Exercise one continuous PFB, preserving channel state between all
			 * four bursts and taking one live-style snapshot after each. */
			double[] starts={2.94d,4.70d,6.41d,8.40d};int promoted=0;
			IQPolyphaseChannelizer pfb=new IQPolyphaseChannelizer(replay.getCenterFrequencyHz(),rate);
			Ism868SignalAnalyzer analyzer=new Ism868SignalAnalyzer();
			Ism868SignalTablePanel table=new Ism868SignalTablePanel();
			StringBuilder trace=new StringBuilder();
			for(double seconds:starts){int from=(int)Math.round(seconds*rate)*2;
				int length=Math.min((int)Math.round(.25d*rate)*2,raw.length-from)&~1;
				byte[] chunk=new byte[length];System.arraycopy(raw,from,chunk,0,length);pfb.accept(chunk,length);
				List<IQDynamicChannelizer.Capture> captures=new ArrayList<IQDynamicChannelizer.Capture>(pfb.snapshotAndKeep());
				Ism868SignalAnalyzer.ChannelizedWmbus channels=new Ism868SignalAnalyzer.ChannelizedWmbus(
						new float[0],0d,new float[0],0d,captures);
				Ism868SignalAnalyzer.Result result=analyzer.analyze(replay.getCenterFrequencyHz(),rate,
						new byte[8192],8192,1_000L+promoted*1_000L,channels);
				boolean hit=false;trace.append(String.format("%.2f: captures=%d [",seconds,captures.size()));
				for(Ism868SignalAnalyzer.Observation observation:result.observations){
					trace.append(observation.protocol).append('@').append(observation.frequencyHz).append(", ");
					if("Automotive keyfob candidate".equals(observation.protocol))hit=true;}
				trace.append("]\n");
				if(hit){promoted++;table.observeAtForTest(result,promoted*1_000_000_000L);}
			}
			if(promoted!=4)System.err.print(trace);
			check(promoted==4,"all four bursts promoted through streaming PFB");
			check(table.receivedForProtocolForTest("Automotive keyfob candidate")==4,
					"four candidates aggregated in one table row");
		}
		System.out.println("AutomotiveKeyfobClassifierTest: OK");
	}
	private static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
}
