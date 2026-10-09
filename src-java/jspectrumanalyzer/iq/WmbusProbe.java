package jspectrumanalyzer.iq;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Command-line regression probe for real Wireless M-Bus IQ recordings. */
final class WmbusProbe {
	private WmbusProbe() { }

	public static void main(String[] args) throws Exception {
		if(args.length==0){System.err.println("Usage: WmbusProbe <iq.wav|iq.pcm|iq.cu8> [...]");return;}
		for(String argument:args)probe(new File(argument));
	}

	private static void probe(File file)throws Exception{
		try(IQReplayFile replay=IQReplayFile.open(file)){
			int rate=replay.getSampleRateHz();
			long duration=Math.max(1L,replay.getDurationMillis()-1L);
			int bytes=(int)Math.min(16_000_000L,Math.max(2L,duration*rate/500L))&~1;
			while(bytes>2&&replay.wouldLoopOnNextRead(bytes))bytes-=2;
			byte[] iq=new byte[bytes];replay.readLoopedSigned(iq);
			WmbusPhyDecoder decoder=new WmbusPhyDecoder();
			long started=System.nanoTime();
			List<WmbusPhyDecoder.Decoded> ct=decoder.decodeCtAll(
					replay.getCenterFrequencyHz(),rate,iq,iq.length);
			long ctFinished=System.nanoTime();
			List<WmbusPhyDecoder.Decoded> s=decoder.decodeSAll(
					replay.getCenterFrequencyHz(),rate,iq,iq.length);
			long finished=System.nanoTime();
			double seconds=(finished-started)/1e9;
			double captureSeconds=iq.length/(rate*2d);
			System.out.println(String.format(Locale.US,"%s: center %.6f MHz, %.3f MS/s, %.1f ms, %d IQ bytes, %.3f s decode (%.2fx realtime; C/T %.1f ms, S %.1f ms)",
					file.getName(),replay.getCenterFrequencyHz()/1e6,rate/1e6,
					iq.length*500d/rate,iq.length,seconds,seconds/Math.max(1e-9,captureSeconds),
					(ctFinished-started)/1e6,(finished-ctFinished)/1e6));
			Set<String> meters=new LinkedHashSet<String>();
			print("C/T",ct,meters);print("S",s,meters);
			System.out.println("  summary: "+(ct.size()+s.size())+" distinct CRC-valid frame(s), "
					+meters.size()+" meter ID(s): "+meters);
		}
	}

	private static void print(String receiver,List<WmbusPhyDecoder.Decoded> decoded,Set<String> meters){
		if(decoded.isEmpty()){System.out.println("  "+receiver+": no CRC-valid frame");return;}
		for(WmbusPhyDecoder.Decoded value:decoded){
			meters.add(value.manufacturer+":"+value.id);
			System.out.println("  "+receiver+": mode "+value.mode+", format "+value.format+", "
					+value.details+(value.accessNumber<0?"":", AC "+value.accessNumber)
					+(value.application.driver.length()==0?"":", driver "+value.application.driver)
					+(value.application.summary().length()==0?"":", values "+value.application.summary())
					+(Boolean.getBoolean("wmbus.raw")?", payload "+value.payloadHex:""));
		}
	}
}
