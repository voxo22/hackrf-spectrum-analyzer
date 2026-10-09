package jspectrumanalyzer.iq;

import java.util.List;
import java.util.Collections;

/** Standalone regression tests; run with: java jspectrumanalyzer.iq.FineOffsetWh65bDecoderTest */
public final class FineOffsetWh65bDecoderTest {
	private FineOffsetWh65bDecoderTest(){ }
	public static void main(String[] args){
		byte[] payload=hex("244A1182743F13030E51022A03FB9C5746");
		FineOffsetWh65bDecoder.Decoded fields=FineOffsetWh65bDecoder.fromPayload(payload,0d,70_000d,58d);
		check(fields!=null,"known real payload CRC/checksum");
		equal(74,fields.id,"ID");near(22.8d,fields.temperatureC,.001d,"temperature");
		equal(63,fields.humidity,"humidity");equal(273,fields.windDirection,"wind direction");
		near(1.21125d,fields.windAverageMs,.001d,"wind average");near(1.53d,fields.windGustMs,.001d,"gust");
		near(930.91d,fields.rainMm,.01d,"rain");equal(554,fields.uvRaw,"UV");equal(1,fields.uvIndex,"UVI");
		near(26102d,fields.lightLux,.01d,"light");check(fields.batteryOk,"battery");
		byte[] broken=payload.clone();broken[5]^=1;
		check(FineOffsetWh65bDecoder.fromPayload(broken,0d,0d,58d)==null,"corrupt payload rejected");

		float[] iq=modulate(payload,250_000d);
		List<FineOffsetWh65bDecoder.Decoded> decoded=new FineOffsetWh65bDecoder().decodeAll(iq,250_000d);
		check(decoded.size()==1,"synthetic FSK frame count");equal(74,decoded.get(0).id,"synthetic ID");
		IQDynamicChannelizer.Capture capture=new IQDynamicChannelizer.Capture(
				868_320_000L,120_000,-42d,19d,250_000d,iq);
		Ism868SignalAnalyzer.ChannelizedWmbus channels=new Ism868SignalAnalyzer.ChannelizedWmbus(
				new float[0],0d,new float[0],0d,Collections.singletonList(capture));
		Ism868SignalAnalyzer.Result result=new Ism868SignalAnalyzer().analyze(868_000_000L,
				1_000_000,new byte[8192],8192,1_000L,channels);
		Ism868SignalAnalyzer.Observation observation=null;
		for(Ism868SignalAnalyzer.Observation next:result.observations)
			if("Fineoffset-WH65B".equals(next.protocol))observation=next;
		check(observation!=null,"analyzer promotion");check(observation.checksumValid,"analyzer CRC");
		equal(74,Integer.parseInt(observation.meterId),"analyzer device ID");
		Ism868SignalTablePanel panel=new Ism868SignalTablePanel();panel.observe(result);panel.refresh(result);
		check("22.8 C".equals(panel.cellForMeterForTest("74","Temperature")),"table temperature");
		check("63 %".equals(panel.cellForMeterForTest("74","Humidity")),"table humidity");
		check("fineoffset_wh65b".equals(panel.cellForMeterForTest("74","Driver")),"table driver");
		/* IQ replay returns to the same source timestamp on every loop. Repeated
		 * packets must still advance their period using monotonic arrival time. */
		Ism868SignalTablePanel looped=new Ism868SignalTablePanel();
		looped.observeAtForTest(result,1_000_000_000L);
		looped.observeAtForTest(result,17_000_000_000L);
		looped.observeAtForTest(result,33_000_000_000L);
		equal(3,(int)looped.seenForMeterForTest("Fine Offset","74"),"looped replay count");
		near(16d,looped.periodForMeterForTest("Fine Offset","74"),.001d,"looped replay period");
		System.out.println("FineOffsetWh65bDecoderTest: OK");
	}

	private static float[] modulate(byte[] payload,double rate){
		byte[] frame=new byte[5+2+payload.length];for(int n=0;n<5;n++)frame[n]=(byte)0xaa;
		frame[5]=0x2d;frame[6]=(byte)0xd4;System.arraycopy(payload,0,frame,7,payload.length);
		int lead=2_500,tail=2_500,signal=(int)Math.ceil(frame.length*8*58e-6d*rate);
		float[] iq=new float[(lead+signal+tail)*2];double phase=0d;
		for(int n=0;n<signal;n++){
			int bit=Math.min(frame.length*8-1,(int)Math.floor(n/(58e-6d*rate)));
			boolean one=((frame[bit/8]>>(7-bit%8))&1)!=0;double frequency=one?35_000d:-35_000d;
			phase+=2d*Math.PI*frequency/rate;iq[(lead+n)*2]=(float)(.75d*Math.cos(phase));
			iq[(lead+n)*2+1]=(float)(.75d*Math.sin(phase));
		}
		return iq;
	}
	private static byte[] hex(String text){byte[] out=new byte[text.length()/2];
		for(int n=0;n<out.length;n++)out[n]=(byte)Integer.parseInt(text.substring(n*2,n*2+2),16);return out;}
	private static void near(double expected,double actual,double tolerance,String label){
		if(Math.abs(expected-actual)>tolerance)throw new AssertionError(label+": expected "+expected+", got "+actual);}
	private static void equal(int expected,int actual,String label){if(expected!=actual)
		throw new AssertionError(label+": expected "+expected+", got "+actual);}
	private static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
}
