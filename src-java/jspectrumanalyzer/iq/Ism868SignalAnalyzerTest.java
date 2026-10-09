package jspectrumanalyzer.iq;

import java.util.Collections;
import java.util.Random;

/** Deterministic synthetic RF regression test; no SDR hardware is required. */
public final class Ism868SignalAnalyzerTest {
	private static final long CENTER_HZ = 866_500_000L;
	private static final int RATE_HZ = 8_000_000;

	public static void main(String[] args) {
		byte[] iq = compositeCapture(262_144);
		Ism868SignalAnalyzer.Result result = new Ism868SignalAnalyzer().analyze(
				CENTER_HZ, RATE_HZ, iq, iq.length, 1234L);
		boolean wmbus = false, lora = false;
		for (Ism868SignalAnalyzer.Observation observation : result.observations) {
			if (observation.protocol.startsWith("W-MBus C/T")) wmbus = true;
			if (observation.protocol.startsWith("LoRa")) lora = true;
		}
		if (!wmbus) throw new AssertionError("Synthetic 868.95 MHz FSK signal was not classified: " + dump(result));
		if (!lora) throw new AssertionError("Synthetic 867.5 MHz chirp was not classified: " + dump(result));
		testDcSpikeSuppression();
		testDynamicChannelPromotion();
		testProtocolDispatcherProfiles();

		Ism868SignalAnalyzer.Result outside = new Ism868SignalAnalyzer().analyze(
				433_920_000L, 1_000_000, iq, iq.length, 1234L);
		if (!outside.observations.isEmpty() || outside.state.indexOf("does not overlap") < 0)
			throw new AssertionError("Out-of-band IQ was not rejected");
		System.out.println("Ism868SignalAnalyzerTest OK: detected W-MBus C/T and LoRa candidates");
	}

	private static void testDcSpikeSuppression(){
		byte[] iq=new byte[131_072*2];Random random=new Random(42L);
		double wantedPhase=0d,dcSkirtPhase=0d;
		for(int n=0;n<iq.length/2;n++){
			wantedPhase+=2d*Math.PI*35_000d/RATE_HZ;
			dcSkirtPhase+=2d*Math.PI*7_000d/RATE_HZ;
			double i=48d+24d*Math.cos(dcSkirtPhase)+30d*Math.cos(wantedPhase)+random.nextGaussian();
			double q=-36d+24d*Math.sin(dcSkirtPhase)+30d*Math.sin(wantedPhase)+random.nextGaussian();
			iq[n*2]=(byte)Math.max(-127,Math.min(127,Math.round(i)));
			iq[n*2+1]=(byte)Math.max(-127,Math.min(127,Math.round(q)));
		}
		Ism868SignalAnalyzer.Result result=new Ism868SignalAnalyzer().analyze(
				CENTER_HZ,RATE_HZ,iq,iq.length,1234L);
		boolean adjacentSignal=false;
		for(Ism868SignalAnalyzer.Observation observation:result.observations){
			long offset=Math.abs(observation.frequencyHz-CENTER_HZ);
			if(offset<15_000L)throw new AssertionError("DC spur reached table: "+dump(result));
			if(Math.abs(observation.frequencyHz-(CENTER_HZ+35_000L))<12_000L)adjacentSignal=true;
		}
		if(!adjacentSignal)throw new AssertionError("near-center RF signal was suppressed: "+dump(result));

		byte[] constant=new byte[131_072*2];
		for(int n=0;n<constant.length;n+=2){constant[n]=52;constant[n+1]=-37;}
		IQPolyphaseChannelizer channelizer=new IQPolyphaseChannelizer(CENTER_HZ,RATE_HZ);
		channelizer.accept(constant,constant.length);
		if(!channelizer.snapshotAndKeep().isEmpty())
			throw new AssertionError("constant I/Q offset activated the center PFB channel");
	}

	private static void testDynamicChannelPromotion(){
		long frequency=866_123_000L;float[] filtered=new float[4096];
		IQDynamicChannelizer.Capture capture=new IQDynamicChannelizer.Capture(
				frequency,42_000,-43d,18d,250_000d,filtered);
		Ism868SignalAnalyzer.ChannelizedWmbus channels=new Ism868SignalAnalyzer.ChannelizedWmbus(
				new float[0],0d,new float[0],0d,Collections.singletonList(capture));
		byte[] quiet=new byte[16_384];
		Ism868SignalAnalyzer.Result result=new Ism868SignalAnalyzer().analyze(
				CENTER_HZ,RATE_HZ,quiet,quiet.length,1234L,channels);
		for(Ism868SignalAnalyzer.Observation observation:result.observations)
			if(Math.abs(observation.frequencyHz-frequency)<2_000L
					&&observation.details.indexOf("streaming channel")>=0)return;
		throw new AssertionError("dynamic streaming channel did not reach analyzer table");
	}

	private static void testProtocolDispatcherProfiles(){
		final double rate=250_000d;
		IQDynamicChannelizer.Capture fsk=new IQDynamicChannelizer.Capture(866_100_000L,100_000,
				-40d,20d,rate,fsk(rate,10_000d,20_000d,12_000));
		Ism868ProtocolDispatcher.Profile profile=Ism868ProtocolDispatcher.profileForTest(fsk);
		if(!"FSK".equals(profile.modulation)||Math.abs(profile.symbolRate-10_000d)>1_500d)
			throw new AssertionError("FSK profile "+profile.modulation+" "+profile.symbolRate);

		IQDynamicChannelizer.Capture ook=new IQDynamicChannelizer.Capture(866_300_000L,80_000,
				-42d,18d,rate,ook(rate,5_000d,12_000));
		profile=Ism868ProtocolDispatcher.profileForTest(ook);
		if(!"OOK".equals(profile.modulation)||Math.abs(profile.symbolRate-5_000d)>900d)
			throw new AssertionError("OOK profile "+profile.modulation+" "+profile.symbolRate);

		IQDynamicChannelizer.Capture css=new IQDynamicChannelizer.Capture(868_100_000L,150_000,
				-39d,22d,rate,chirp(rate,125_000d,512,8));
		profile=Ism868ProtocolDispatcher.profileForTest(css);
		if(!"CSS".equals(profile.modulation)||profile.occupiedBandwidthHz!=125_000)
			throw new AssertionError("CSS profile "+profile.modulation+" "+profile.occupiedBandwidthHz);
		Ism868ProtocolDispatcher dispatcher=new Ism868ProtocolDispatcher();
		Ism868ProtocolDispatcher.Dispatch routed=dispatcher.dispatch(
				Collections.singletonList(css),1_000L).get(0);
		if(!"LoRa candidate".equals(routed.protocol)||!"LoRa".equals(routed.decoder)||!routed.decoderEligible)
			throw new AssertionError("CSS was not routed to LoRa: "+routed.protocol+" / "+routed.decoder);
	}

	private static float[] fsk(double rate,double symbols,double deviation,int samples){
		float[] iq=new float[samples*2];double phase=0d;int samplesPerSymbol=(int)Math.round(rate/symbols);
		for(int n=0;n<samples;n++){double bit=((n/samplesPerSymbol)&1)==0?-1d:1d;
			phase+=2d*Math.PI*bit*deviation/rate;iq[n*2]=(float)Math.cos(phase);iq[n*2+1]=(float)Math.sin(phase);}
		return iq;
	}

	private static float[] ook(double rate,double symbols,int samples){
		float[] iq=new float[samples*2];double phase=0d;int samplesPerSymbol=(int)Math.round(rate/symbols);
		for(int n=0;n<samples;n++){double amplitude=((n/samplesPerSymbol)&1)==0?1d:.025d;
			phase+=2d*Math.PI*4_000d/rate;iq[n*2]=(float)(amplitude*Math.cos(phase));
			iq[n*2+1]=(float)(amplitude*Math.sin(phase));}
		return iq;
	}

	private static float[] chirp(double rate,double bandwidth,int period,int symbols){
		int samples=period*symbols;float[] iq=new float[samples*2];double phase=0d;
		for(int n=0;n<samples;n++){double position=(n%period)/(double)period;
			phase+=2d*Math.PI*((position-.5d)*bandwidth)/rate;
			iq[n*2]=(float)Math.cos(phase);iq[n*2+1]=(float)Math.sin(phase);}
		return iq;
	}

	private static String dump(Ism868SignalAnalyzer.Result result) {
		StringBuilder out=new StringBuilder(result.state);
		for(Ism868SignalAnalyzer.Observation o:result.observations)
			out.append(" | ").append(o.frequencyHz).append(' ').append(o.bandwidthHz).append(' ').append(o.protocol);
		return out.toString();
	}

	private static byte[] compositeCapture(int samples) {
		byte[] out = new byte[samples * 2];
		Random random = new Random(868L);
		double fskPhase = 0d, chirpPhase = 0d;
		for (int n=0;n<samples;n++) {
			double fskBit = ((n / 80) * 0x9e3779b9 & 4) == 0 ? -1d : 1d;
			double fskOffset = 868_950_000d - CENTER_HZ + fskBit * 42_000d;
			fskPhase += 2d * Math.PI * fskOffset / RATE_HZ;
			double chirpPosition = (n % 4096) / 4096d;
			double chirpOffset = 867_500_000d - CENTER_HZ + (chirpPosition - .5d) * 125_000d;
			chirpPhase += 2d * Math.PI * chirpOffset / RATE_HZ;
			double i = 44d*Math.cos(fskPhase) + 35d*Math.cos(chirpPhase) + random.nextGaussian()*2.3d;
			double q = 44d*Math.sin(fskPhase) + 35d*Math.sin(chirpPhase) + random.nextGaussian()*2.3d;
			out[n*2]=(byte)Math.max(-127,Math.min(127,Math.round(i)));
			out[n*2+1]=(byte)Math.max(-127,Math.min(127,Math.round(q)));
		}
		return out;
	}
}
