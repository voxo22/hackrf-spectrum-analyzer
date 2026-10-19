package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Synthetic PHY and analyzer regressions for imported rtl_433 protocols. */
public final class IsmRtl433DecoderTest {
	private IsmRtl433DecoderTest(){ }
	public static void main(String[] args){testRenault();testTapo(false);testTapo(true);
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

	private static void testTapo(boolean manchester){byte[] frame=hex(manchester
			?"2AE9F0A73187A160F0A731879B4E028010000D14E3A2AF51C949772E5B55980CE8AECE404732927878E5AA"
			:"1000F0A73187A160030180107A0101C00F");int crc=TapoSubGDecoder.crc16X25(frame,frame.length-2);
		check(crc==(((frame[frame.length-2]&255)<<8)|(frame[frame.length-1]&255)),"Tapo test CRC");
		List<Boolean> bits=new ArrayList<Boolean>();
		for(int n=0;n<4;n++)appendByte(bits,0xaa);appendByte(bits,0x66);appendByte(bits,0x59);
		appendByte(bits,0x5a);appendByte(bits,0x99);
		if(manchester)for(byte value:frame)for(int bit=7;bit>=0;bit--){boolean one=((value>>>bit)&1)!=0;
			bits.add(Boolean.valueOf(one));bits.add(Boolean.valueOf(!one));}
		else for(byte value:frame)appendByte(bits,value&255);
		for(int n=0;n<24;n++)bits.add(Boolean.valueOf((n&1)!=0));float[] iq=modulate(bits,20d);
		List<TapoSubGDecoder.Decoded> decoded=new TapoSubGDecoder().decodeAll(iq,500_000d);
		check(decoded.size()==1,"Tapo "+(manchester?"Manchester":"NRZ")+" frame");
		TapoSubGDecoder.Decoded value=decoded.get(0);check(value.manchester==manchester,"Tapo encoding");
		check("F0:A7:31:87:A1:60".equals(value.firstAddress),"Tapo address");
		check((manchester?"F0:A7:31:87:9B:4E":"").equals(value.secondAddress),"Tapo peer address");
		near(20d*Math.log10(.8d),value.levelDbfs,.05d,"Tapo frame RSSI");
		Ism868SignalAnalyzer.Result result=analyze(new IQDynamicChannelizer.Capture(
				864_350_000L,200_000,-32d,28d,500_000d,iq));boolean found=false;
		for(Ism868SignalAnalyzer.Observation observation:result.observations)
			if("Tapo Sub-GHz".equals(observation.protocol)&&observation.checksumValid){found=true;
				near(value.levelDbfs,observation.levelDbfs,.001d,"Tapo analyzer frame RSSI");}
		check(found,"Tapo analyzer promotion");
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
	private static byte[] hex(String text){byte[] out=new byte[text.length()/2];for(int n=0;n<out.length;n++)
		out[n]=(byte)Integer.parseInt(text.substring(n*2,n*2+2),16);return out;}
	private static void near(double expected,double actual,double tolerance,String label){if(Math.abs(expected-actual)>tolerance)
		throw new AssertionError(label+": expected "+expected+", got "+actual);}
	private static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
}
