package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Synthetic PHY and analyzer regressions for imported rtl_433 protocols. */
public final class IsmRtl433DecoderTest {
	private IsmRtl433DecoderTest(){ }
	public static void main(String[] args){testRenault();
		System.out.println("IsmRtl433DecoderTest: OK");}

	private static void testRenault(){byte[] payload=new byte[9];int pressureRaw=320,flags=0x12;
		payload[0]=(byte)((flags<<2)|(pressureRaw>>>8));payload[1]=(byte)pressureRaw;payload[2]=55;
		payload[3]=(byte)0xbc;payload[4]=0x3a;payload[5]=0x12;payload[6]=(byte)0xff;payload[7]=(byte)0xff;
		payload[8]=(byte)FskPcmDecoderSupport.crc8(payload,8,0x07,0);
		List<Boolean> bits=new ArrayList<Boolean>();appendByte(bits,0xaa);appendByte(bits,0xa9);
		for(byte value:payload)for(int bit=7;bit>=0;bit--){boolean one=((value>>>bit)&1)!=0;
			bits.add(Boolean.valueOf(!one));bits.add(Boolean.valueOf(one));}
		for(int n=0;n<24;n++)bits.add(Boolean.valueOf((n&1)!=0));
		float[] iq=modulate(bits,52d);List<RenaultTpmsDecoder.Decoded> decoded=new RenaultTpmsDecoder().decodeAll(iq,250_000d);
		check(decoded.size()==1,"Renault frame");RenaultTpmsDecoder.Decoded value=decoded.get(0);
		check("123abc".equals(value.id),"Renault ID");near(240d,value.pressureKpa,.001d,"Renault pressure");
		check(value.temperatureC==25,"Renault temperature");check(value.flags==flags,"Renault flags");
		Ism868SignalAnalyzer.Result result=analyze(new IQDynamicChannelizer.Capture(
				868_320_000L,110_000,-40d,20d,250_000d,iq));
		Ism868SignalTablePanel panel=new Ism868SignalTablePanel();panel.observe(result);panel.refresh(result);
		check(panel.cellForMeterForTest("123abc","Decoded values").contains("pressure=240.0 kPa"),
				"Renault table decoded values");
		check("25 C".equals(panel.cellForMeterForTest("123abc","Temperature")),"Renault table temperature");
	}

	private static Ism868SignalAnalyzer.Result analyze(IQDynamicChannelizer.Capture capture){
		Ism868SignalAnalyzer.ChannelizedWmbus channels=new Ism868SignalAnalyzer.ChannelizedWmbus(
				new float[0],0d,new float[0],0d,Collections.singletonList(capture));
		return new Ism868SignalAnalyzer().analyze(868_000_000L,1_000_000,new byte[8192],8192,1_000L,channels);
	}
	private static float[] modulate(List<Boolean> bits,double bitMicros){double rate=250_000d;
		if(bitMicros<40d)rate=500_000d;int lead=(int)(rate*.008d),tail=lead;
		int signal=(int)Math.ceil(bits.size()*bitMicros*1e-6d*rate);float[] iq=new float[(lead+signal+tail)*2];double phase=0d;
		for(int n=0;n<signal;n++){int bit=Math.min(bits.size()-1,(int)Math.floor(n/(bitMicros*1e-6d*rate)));
			double frequency=bits.get(bit)?35_000d:-35_000d;phase+=2d*Math.PI*frequency/rate;
			iq[(lead+n)*2]=(float)(.8d*Math.cos(phase));iq[(lead+n)*2+1]=(float)(.8d*Math.sin(phase));}return iq;}
	private static void appendByte(List<Boolean> bits,int value){for(int bit=7;bit>=0;bit--)
		bits.add(Boolean.valueOf((value&(1<<bit))!=0));}
	private static void near(double expected,double actual,double tolerance,String label){if(Math.abs(expected-actual)>tolerance)
		throw new AssertionError(label+": expected "+expected+", got "+actual);}
	private static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
}
