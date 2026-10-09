package jspectrumanalyzer.iq;

import java.io.File;
import java.util.Locale;

/** Small command-line probe for locating and validating WH65B bursts in IQ recordings. */
final class FineOffsetWh65bProbe {
	private FineOffsetWh65bProbe() { }

	public static void main(String[] args)throws Exception{
		if(args.length==0){System.err.println("Usage: FineOffsetWh65bProbe <iq file> [second ...]");return;}
		if(args.length==2&&"--session".equals(args[1])){
			try(IQReplayFile replay=IQReplayFile.open(new File(args[0]))){probeSession(replay);}return;
		}
		double[] seconds=new double[Math.max(1,args.length-1)];
		if(args.length==1)seconds[0]=0d;else for(int n=1;n<args.length;n++)seconds[n-1]=Double.parseDouble(args[n]);
		try(IQReplayFile replay=IQReplayFile.open(new File(args[0]))){
			for(double second:seconds)probe(replay,second);
		}
	}

	private static void probeSession(IQReplayFile replay)throws Exception{
		int rate=replay.getSampleRateHz();long center=replay.getCenterFrequencyHz();
		int blockSamples=Math.max(4096,rate/20),steps=(int)(replay.getDurationMillis()/50L);
		byte[] block=new byte[blockSamples*2];IQPolyphaseChannelizer pfb=new IQPolyphaseChannelizer(center,rate);
		Ism868SignalAnalyzer analyzer=new Ism868SignalAnalyzer();Ism868SignalTablePanel table=new Ism868SignalTablePanel();
		RenaultTpmsDecoder renault=new RenaultTpmsDecoder();
		for(int step=0;step<steps;step++){
			replay.readLoopedSigned(block);pfb.accept(block,block.length);if(step%5!=4)continue;
			java.util.List<IQDynamicChannelizer.Capture> captures=pfb.snapshotAndKeep();
			for(IQDynamicChannelizer.Capture capture:captures){
				for(RenaultTpmsDecoder.Decoded decoded:renault.decodeAll(capture.iq,capture.sampleRateHz))
					System.out.println(String.format(Locale.US,"session t=%.2f Renault f=%.6f ID=%s P=%.1f T=%d payload=%s",
							(step+1)*.05d,capture.frequencyHz/1e6,decoded.id,decoded.pressureKpa,decoded.temperatureC,decoded.payloadHex));
			}
			Ism868SignalAnalyzer.ChannelizedWmbus channels=new Ism868SignalAnalyzer.ChannelizedWmbus(
					new float[0],0d,new float[0],0d,captures);
			Ism868SignalAnalyzer.Result result=analyzer.analyze(center,rate,block,block.length,
					(step+1L)*50L,channels);table.observe(result);table.refresh(result);
			for(Ism868SignalAnalyzer.Observation observation:result.observations)
				if("Fineoffset-WH65B".equals(observation.protocol))System.out.println(String.format(Locale.US,
						"session t=%.2f s ID=%s received=%s period=%s payload=%s",
						(step+1)*.05d,observation.meterId,table.cellForMeterForTest(observation.meterId,"Received"),
						table.cellForMeterForTest(observation.meterId,"Tx period"),observation.payloadHex));
		}
	}

	private static void probe(IQReplayFile replay,double second)throws Exception{
		int rate=replay.getSampleRateHz();long center=replay.getCenterFrequencyHz();
		replay.seekMillis(Math.max(0L,Math.round((second-.18d)*1000d)));
		int bytes=(int)Math.min(4_000_000L,Math.round(rate*.36d)*2L)&~1;
		byte[] iq=new byte[bytes];replay.readLoopedSigned(iq);
		FineOffsetWh65bDecoder decoder=new FineOffsetWh65bDecoder();boolean found=false;
		for(long frequency=868_180_000L;frequency<=868_460_000L;frequency+=10_000L){
			IQSubchannelExtractor.Result channel=IQSubchannelExtractor.extract(iq,iq.length,rate,
					frequency-center,170_000,250_000);
			for(FineOffsetWh65bDecoder.Decoded decoded:decoder.decodeAll(channel.iq,channel.sampleRateHz)){
				long measured=Math.round(frequency+decoded.carrierOffsetHz);found=true;
				System.out.println(String.format(Locale.US,
						"t=%.2f s f=%.6f MHz ID=%d temp=%.1f C humidity=%d %% wind=%.1f/%.1f m/s dir=%d rain=%.1f mm UV=%d UVI=%d light=%.1f lux battery=%s bits=%.2f us dev=%.1f kHz payload=%s",
						second,measured/1e6,decoded.id,decoded.temperatureC,decoded.humidity,
						decoded.windAverageMs,decoded.windGustMs,decoded.windDirection,decoded.rainMm,
						decoded.uvRaw,decoded.uvIndex,decoded.lightLux,decoded.batteryOk?"OK":"LOW",
						decoded.bitMicros,decoded.deviationHz/1000d,decoded.payloadHex));
			}
		}
		IQDynamicChannelizer dynamic=new IQDynamicChannelizer(center,rate);
		for(int at=0;at<iq.length;at+=8192){int count=Math.min(8192,iq.length-at);
			byte[] block=new byte[count];System.arraycopy(iq,at,block,0,count);dynamic.accept(block,count);}
		for(IQDynamicChannelizer.Capture capture:dynamic.snapshotAndKeep())
			for(FineOffsetWh65bDecoder.Decoded decoded:decoder.decodeAll(capture.iq,capture.sampleRateHz))
				System.out.println(String.format(Locale.US,"dynamic f=%.6f MHz track=%.6f MHz ID=%d payload=%s",
						(capture.iqCenterFrequencyHz==capture.frequencyHz
								?capture.frequencyHz+decoded.carrierOffsetHz:capture.frequencyHz)/1e6,capture.frequencyHz/1e6,
						decoded.id,decoded.payloadHex));
		IQPolyphaseChannelizer pfb=new IQPolyphaseChannelizer(center,rate);pfb.accept(iq,iq.length);
		RenaultTpmsDecoder renault=new RenaultTpmsDecoder();
		for(IQDynamicChannelizer.Capture capture:pfb.snapshotAndKeep()){
			for(FineOffsetWh65bDecoder.Decoded decoded:decoder.decodeAll(capture.iq,capture.sampleRateHz))
				System.out.println(String.format(Locale.US,"PFB f=%.6f MHz track=%.6f MHz ID=%d payload=%s",
						(capture.iqCenterFrequencyHz==capture.frequencyHz
								?capture.frequencyHz+decoded.carrierOffsetHz:capture.frequencyHz)/1e6,capture.frequencyHz/1e6,
						decoded.id,decoded.payloadHex));
			for(RenaultTpmsDecoder.Decoded decoded:renault.decodeAll(capture.iq,capture.sampleRateHz))
				System.out.println(String.format(Locale.US,"Renault track=%.6f MHz ID=%s P=%.1f kPa T=%d C payload=%s",
						capture.frequencyHz/1e6,decoded.id,decoded.pressureKpa,decoded.temperatureC,decoded.payloadHex));
		}
		if(!found)System.out.println(String.format(Locale.US,"t=%.2f s: no CRC-valid WH65 frame",second));
	}
}
