package jspectrumanalyzer.iq;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/** LoRa PHY/LoRaWAN regression, including the supplied real SF12 recording when present. */
public final class LoRaPhyDecoderTest {
	private LoRaPhyDecoderTest(){ }
	public static void main(String[] args)throws Exception{
		byte[] known=hex("40a4f0acde804a00010eca3b0fdec634eee490b58d9e6687842a59");
		LoRaPhyDecoder.Decoded fields=new LoRaPhyDecoder.Decoded(125_000,12,1,true,true,true,
				known,0x080a,0x080a,0x18,0x20,1.6682d);
		check(fields.isLoRaWan(),"LoRaWAN recognition");equal("DEACF0A4",fields.deviceId(),"DevAddr");
		check(fields.summary().contains("FCnt=74"),"frame counter");
		check(fields.summary().contains("FPort=1"),"FPort");

		File sample=new File("867900000Hz_LoRa_500k.wav");
		if(sample.isFile())testRecording(sample);
		File wideband=new File("868250000Hz_LoRa_8.wav");
		if(wideband.isFile())testWidebandRecording(wideband);
		System.out.println("LoRaPhyDecoderTest: OK");
	}

	private static void testRecording(File sample)throws Exception{
		try(IQReplayFile replay=IQReplayFile.open(sample)){
			int bytes=(int)Math.round(replay.getDurationMillis()*replay.getSampleRateHz()/500d)&~1;
			byte[] raw=new byte[bytes];replay.readLoopedSigned(raw);float[] iq=new float[bytes];
			for(int n=0;n<bytes;n++)iq[n]=raw[n]/128f;
			List<LoRaPhyDecoder.Decoded> frames=new LoRaPhyDecoder().decodeAll(iq,replay.getSampleRateHz());
			check(frames.size()==1,"real frame count");LoRaPhyDecoder.Decoded decoded=frames.get(0);
			check(decoded.headerValid,"real explicit header");check(decoded.crcValid,"real payload CRC");
			equal("DEACF0A4",decoded.deviceId(),"real DevAddr");equal("40a4f0acde804a00010eca3b0fdec634eee490b58d9e6687842a59",
					decoded.payloadHex(),"real payload");
			Ism868SignalAnalyzer.Result result=new Ism868SignalAnalyzer().analyze(replay.getCenterFrequencyHz(),
					replay.getSampleRateHz(),raw,raw.length,1_000L);
			Ism868SignalAnalyzer.Observation observation=null;for(Ism868SignalAnalyzer.Observation value:result.observations)
				if("LoRaWAN".equals(value.protocol))observation=value;
			check(observation!=null,"analyzer promotion");equal("DEACF0A4",observation.meterId,"analyzer ID");
			Ism868SignalTablePanel panel=new Ism868SignalTablePanel();panel.observe(result);panel.refresh(result);
			equal("lora_phy",panel.cellForMeterForTest("DEACF0A4","Driver"),"table driver");
			check(panel.cellForMeterForTest("DEACF0A4","Decoded values").contains("FCnt=74"),"table values");
			/* A live narrowband analysis receives a rolling 2.2 s overlap rather
			 * than the complete replay file.  It must still contain this SF12 frame. */
			int from=((int)Math.round(1.10d*replay.getSampleRateHz())*2)&~1;
			int to=Math.min(raw.length,from+((int)Math.round(2.40d*replay.getSampleRateHz())*2));
			byte[] rolling=Arrays.copyOfRange(raw,from,to&~1);
			Ism868SignalAnalyzer.Result live=new Ism868SignalAnalyzer().analyze(replay.getCenterFrequencyHz(),
					replay.getSampleRateHz(),rolling,rolling.length,2_000L);
			boolean promoted=false;for(Ism868SignalAnalyzer.Observation value:live.observations)
				if("LoRaWAN".equals(value.protocol)&&"DEACF0A4".equals(value.meterId))promoted=true;
			check(promoted,"rolling narrowband SF12 promotion");
		}
	}

	private static void testWidebandRecording(File sample)throws Exception{
		try(IQReplayFile replay=IQReplayFile.open(sample)){
			int rate=replay.getSampleRateHz();int wanted=(int)Math.round(3.25d*rate)*2;
			byte[] raw=new byte[wanted&~1];replay.readLoopedSigned(raw);
			int from=(int)Math.round(1.19d*rate)*2,middle=(int)Math.round(1.75d*rate)*2;
			IQPolyphaseChannelizer pfb=new IQPolyphaseChannelizer(replay.getCenterFrequencyHz(),rate);
			feed(pfb,raw,from,middle);Ism868SignalAnalyzer analyzer=new Ism868SignalAnalyzer();
			Ism868SignalAnalyzer.Result preamble=analyzePfb(analyzer,pfb,replay,1_750L);
			boolean candidate=false;for(Ism868SignalAnalyzer.Observation value:preamble.observations)
				if("LoRa candidate".equals(value.protocol))candidate=true;
			check(candidate,"8 MS/s PFB LoRa preamble candidate");
			feed(pfb,raw,middle,raw.length);Ism868SignalAnalyzer.Result decoded=analyzePfb(analyzer,pfb,replay,3_250L);
			boolean promoted=false;for(Ism868SignalAnalyzer.Observation value:decoded.observations)
				if("LoRaWAN".equals(value.protocol)&&"DEACF0A4".equals(value.meterId))promoted=true;
			check(promoted,"8 MS/s PFB LoRaWAN promotion");
		}
	}
	private static void feed(IQPolyphaseChannelizer pfb,byte[] raw,int from,int to){
		for(int at=from;at<to;){int count=Math.min(131_072,to-at)&~1;byte[] block=Arrays.copyOfRange(raw,at,at+count);
			pfb.accept(block,count);at+=count;}
	}
	private static Ism868SignalAnalyzer.Result analyzePfb(Ism868SignalAnalyzer analyzer,
			IQPolyphaseChannelizer pfb,IQReplayFile replay,long millis){
		Ism868SignalAnalyzer.ChannelizedWmbus channels=new Ism868SignalAnalyzer.ChannelizedWmbus(
				new float[0],0d,new float[0],0d,pfb.snapshotAndKeep());
		return analyzer.analyze(replay.getCenterFrequencyHz(),replay.getSampleRateHz(),
				new byte[8192],8192,millis,channels);
	}

	private static byte[] hex(String text){byte[] out=new byte[text.length()/2];for(int n=0;n<out.length;n++)
		out[n]=(byte)Integer.parseInt(text.substring(n*2,n*2+2),16);return out;}
	private static void equal(String expected,String actual,String label){if(!expected.equals(actual))
		throw new AssertionError(label+": expected "+expected+", got "+actual);}
	private static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
}
