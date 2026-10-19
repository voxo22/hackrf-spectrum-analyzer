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
		testJablotronCandidate();
		test8685Candidates();
		testCompletedBurstsAreNotDeduplicated();

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

		IQDynamicChannelizer.Capture psk=new IQDynamicChannelizer.Capture(866_500_000L,90_000,
				-41d,19d,rate,psk(rate,10_000d,12_000));
		profile=Ism868ProtocolDispatcher.profileForTest(psk);
		if(!"PSK".equals(profile.modulation)||Math.abs(profile.symbolRate-10_000d)>1_500d)
			throw new AssertionError("PSK profile "+profile.modulation+" "+profile.symbolRate);

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

	private static void testCompletedBurstsAreNotDeduplicated(){
		final double rate=250_000d;float[] waveform=fsk(rate,10_000d,20_000d,12_000);
		IQDynamicChannelizer.Capture first=new IQDynamicChannelizer.Capture(868_300_000L,100_000,
				-45d,12d,rate,waveform);
		IQDynamicChannelizer.Capture second=new IQDynamicChannelizer.Capture(868_300_000L,100_000,
				-35d,22d,rate,waveform);
		java.util.List<Ism868ProtocolDispatcher.Dispatch> routed=new Ism868ProtocolDispatcher().dispatch(
				java.util.Arrays.asList(first,second),1_000L);
		if(routed.size()!=2)throw new AssertionError("completed same-frequency bursts were merged");
	}

	private static void testJablotronCandidate(){
		final int rate=500_000,samples=25_000,burstFirst=5_000,burstSamples=4_500;
		byte[] iq=new byte[samples*2];Random random=new Random(100L);double phase=0d;
		for(int n=0;n<samples;n++){double i=random.nextGaussian()*1.5d,q=random.nextGaussian()*1.5d;
			if(n>=burstFirst&&n<burstFirst+burstSamples){int symbol=(int)Math.floor(
					(n-burstFirst)*40_000d/rate);boolean high=symbol<40?(symbol&1)!=0:
					((symbol*73+19)&8)!=0;double offset=-3_000d+(high?17_000d:-17_000d);
				phase+=2d*Math.PI*offset/rate;i+=62d*Math.cos(phase);q+=62d*Math.sin(phase);}
			iq[n*2]=(byte)Math.max(-127,Math.min(127,Math.round(i)));
			iq[n*2+1]=(byte)Math.max(-127,Math.min(127,Math.round(q)));}
		java.util.List<JablotronJa100Classifier.Candidate> candidates=
				new JablotronJa100Classifier().classify(iq,iq.length,rate);
		if(candidates.isEmpty())throw new AssertionError("synthetic JA-100 PHY was not classified");
		JablotronJa100Classifier.Candidate candidate=candidates.get(0);
		if(Math.abs(candidate.symbolRate-40_000d)>2_000d||candidate.preambleBits<32)
			throw new AssertionError("wrong JA-100 PHY estimate: "+candidate.symbolRate+" / "+candidate.preambleBits);
		Ism868SignalAnalyzer.Result result=new Ism868SignalAnalyzer().analyze(
				868_100_000L,rate,iq,iq.length,1234L);
		for(Ism868SignalAnalyzer.Observation observation:result.observations)
			if("Jablotron JA-100 candidate".equals(observation.protocol))return;
		throw new AssertionError("JA-100 candidate did not reach table observations: "+dump(result));
	}

	private static void test8685Candidates(){
		byte[] longBurst=fskBurst(500_000,180d,30d,56d,9_600d,-4_500d,19_500d,201L);
		java.util.List<Ism8685Classifier.Candidate> candidates=
				new Ism8685Classifier().classify(longBurst,longBurst.length,500_000d);
		if(candidates.size()!=1||candidates.get(0).family!=Ism8685Classifier.Family.WAVENIS)
			throw new AssertionError("Wavenis PHY candidate not recognized: "+candidates.size());
		Ism8685Classifier.Candidate value=candidates.get(0);
		if(Math.abs(value.symbolRate-9_600d)>100d||Math.abs(value.burstMillis-56d)>2d)
			throw new AssertionError("wrong Wavenis PHY metrics: "+value.symbolRate+" / "+value.burstMillis);
		byte[] shortBurst=fskBurst(500_000,80d,20d,13.5d,20_000d,-2_000d,26_000d,202L);
		candidates=new Ism8685Classifier().classify(shortBurst,shortBurst.length,500_000d);
		if(candidates.size()!=1||candidates.get(0).family!=Ism8685Classifier.Family.PROPRIETARY_20K)
			throw new AssertionError("20 kbit/s 868.5 candidate not recognized: "+candidates.size());
	}

	private static byte[] fskBurst(int rate,double totalMs,double startMs,double durationMs,
			double symbols,double center,double deviation,long seed){
		int samples=(int)Math.round(rate*totalMs/1000d),first=(int)Math.round(rate*startMs/1000d);
		int active=(int)Math.round(rate*durationMs/1000d);byte[] iq=new byte[samples*2];
		Random random=new Random(seed);double phase=0d;for(int n=0;n<samples;n++){
			double i=random.nextGaussian()*1.4d,q=random.nextGaussian()*1.4d;
			if(n>=first&&n<first+active){int symbol=(int)Math.floor((n-first)*symbols/rate);
				boolean high=((symbol*73+19)&8)!=0;double offset=center+(high?deviation:-deviation);
				phase+=2d*Math.PI*offset/rate;i+=62d*Math.cos(phase);q+=62d*Math.sin(phase);}
			iq[n*2]=(byte)Math.max(-127,Math.min(127,Math.round(i)));
			iq[n*2+1]=(byte)Math.max(-127,Math.min(127,Math.round(q)));}
		return iq;
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

	private static float[] psk(double rate,double symbols,int samples){
		float[] iq=new float[samples*2];int samplesPerSymbol=(int)Math.round(rate/symbols);
		int state=0;for(int n=0;n<samples;n++){if(n%samplesPerSymbol==0&&((n/samplesPerSymbol*73+19)&3)!=0)
			state^=1;double phase=state==0?0d:Math.PI;iq[n*2]=(float)Math.cos(phase);
			iq[n*2+1]=(float)Math.sin(phase);}return iq;
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
