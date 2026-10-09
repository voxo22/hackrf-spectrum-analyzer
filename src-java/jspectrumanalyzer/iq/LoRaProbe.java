package jspectrumanalyzer.iq;

import java.io.File;
import java.util.List;
import java.util.Locale;

/** Command-line probe for LoRa IQ recordings. */
final class LoRaProbe {
	private LoRaProbe(){ }
	public static void main(String[] args)throws Exception{
		if(args.length==0){System.err.println("Usage: LoRaProbe <iq.wav|iq.pcm|iq.cu8>");return;}
		for(String argument:args)probe(new File(argument));
	}
	private static void probe(File file)throws Exception{
		try(IQReplayFile replay=IQReplayFile.open(file)){
			long sampleCount=Math.round(replay.getDurationMillis()*replay.getSampleRateHz()/1000d);
			int bytes=(int)Math.min(Integer.MAX_VALUE-8L,sampleCount*2L)&~1;
			byte[] raw=new byte[bytes];replay.readLoopedSigned(raw);float[] iq=new float[raw.length];
			for(int n=0;n<raw.length;n++)iq[n]=raw[n]/128f;
			LoRaPhyDecoder decoder=new LoRaPhyDecoder();
			List<LoRaPhyDecoder.Lock> locks=decoder.findLocks(iq,replay.getSampleRateHz());
			System.out.println(String.format(Locale.US,"%s: %.6f MHz, %.3f MS/s, %.1f ms, %d lock(s)",
					file.getName(),replay.getCenterFrequencyHz()/1e6,replay.getSampleRateHz()/1e6,
					replay.getDurationMillis()*1d,locks.size()));
			for(LoRaPhyDecoder.Lock lock:locks)System.out.println("  "+lock+decoder.trace(iq,replay.getSampleRateHz(),lock));
			for(LoRaPhyDecoder.Decoded decoded:decoder.decodeAll(iq,replay.getSampleRateHz()))
				System.out.println("  DECODED: "+decoded);
			Ism868SignalAnalyzer.Result analyzed=new Ism868SignalAnalyzer().analyze(
					replay.getCenterFrequencyHz(),replay.getSampleRateHz(),raw,raw.length,
					IQReplayFile.getRecordingStartEpochMillis(file));
			for(Ism868SignalAnalyzer.Observation observation:analyzed.observations)
				if(observation.protocol.startsWith("LoRa"))System.out.println("  ANALYZER: "+observation.protocol+
						", ID "+observation.meterId+", "+observation.details+", "+observation.application.summary());
			if(replay.getSampleRateHz()>=1_000_000&&!locks.isEmpty())probePfb(raw,replay,locks.get(0),decoder);
		}
	}
	private static void probePfb(byte[] raw,IQReplayFile replay,LoRaPhyDecoder.Lock lock,
			LoRaPhyDecoder decoder){int rate=replay.getSampleRateHz();double lockSeconds=lock.startSample/lock.sampleRateHz;
		int from=Math.max(0,(int)Math.round((lockSeconds-.25d)*rate))*2;
		int to=Math.min(raw.length,(int)Math.round((lockSeconds+2.25d)*rate)*2)&~1;
		IQPolyphaseChannelizer pfb=new IQPolyphaseChannelizer(replay.getCenterFrequencyHz(),rate);
		Ism868SignalAnalyzer analyzer=new Ism868SignalAnalyzer();
		long wallStart=System.nanoTime();
		long fed=0,next=rate/4;for(int at=from;at<to;){int count=Math.min(131_072,to-at)&~1;
			byte[] block=new byte[count];System.arraycopy(raw,at,block,0,count);pfb.accept(block,count);
			at+=count;fed+=count/2;if(fed<next&&at<to)continue;next+=rate/4;
			List<IQDynamicChannelizer.Capture> captures=pfb.snapshotAndKeep();
			for(IQDynamicChannelizer.Capture capture:captures){
				Ism868ProtocolDispatcher.Profile profile=Ism868ProtocolDispatcher.profileForTest(capture);
				System.out.println(String.format(Locale.US,
						"  PFB %.3fs: %.6f MHz, %.0f ms IQ, %s BW %.0f kHz, burst %.0f ms, SNR %.1f",
						fed/(double)rate,capture.frequencyHz/1e6,capture.iq.length/2d*1000d/capture.sampleRateHz,
						profile.modulation,profile.occupiedBandwidthHz/1000d,profile.burstMillis,capture.snrDb));
			}
			Ism868SignalAnalyzer.ChannelizedWmbus channels=new Ism868SignalAnalyzer.ChannelizedWmbus(
					new float[0],0d,new float[0],0d,captures);
			Ism868SignalAnalyzer.Result result=analyzer.analyze(replay.getCenterFrequencyHz(),rate,
					new byte[8192],8192,Math.round(fed*1000d/rate),channels);
			for(Ism868SignalAnalyzer.Observation observation:result.observations)
				if(observation.protocol.startsWith("LoRa"))System.out.println("    PFB ANALYZER: "+
						observation.protocol+" "+observation.meterId+" "+observation.details);
		}
		System.out.println(String.format(Locale.US,"  PFB wall %.2f s for %.2f s IQ",
				(System.nanoTime()-wallStart)/1e9,(to-from)/2d/rate));
	}
}
