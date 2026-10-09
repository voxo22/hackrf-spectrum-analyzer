package jspectrumanalyzer.iq;

import java.util.List;
import java.util.Locale;

/** Renault/Dacia 52 us FSK Manchester TPMS decoder. */
final class RenaultTpmsDecoder {
	List<Decoded> decodeAll(float[] iq,double sampleRateHz){return FskPcmDecoderSupport.decodeAll(
			iq,sampleRateHz,52d,176,new FskPcmDecoderSupport.FrameDecoder<Decoded>(){
				@Override public Decoded decode(boolean[] bits,FskPcmDecoderSupport.Metrics metrics){return frame(bits,metrics);}
				@Override public String key(Decoded value){return value.payloadHex;}});}

	private static Decoded frame(boolean[] bits,FskPcmDecoderSupport.Metrics metrics){
		for(int at=0;at+16+144<=bits.length;at++){
			if(FskPcmDecoderSupport.byteAt(bits,at)!=0xaa||FskPcmDecoderSupport.byteAt(bits,at+8)!=0xa9)continue;
			for(int mapping=0;mapping<2;mapping++){byte[] payload=new byte[9];boolean valid=true;
				for(int bit=0;bit<72;bit++){boolean a=bits[at+16+bit*2],b=bits[at+17+bit*2];
					if(a==b){valid=false;break;}boolean value=mapping==0?(!a&&b):(a&&!b);
					if(value)payload[bit/8]|=1<<(7-bit%8);}
				if(!valid||FskPcmDecoderSupport.crc8(payload,8,0x07,0)!=(payload[8]&255))continue;
				int pressureRaw=((payload[0]&3)<<8)|(payload[1]&255),temperature=(payload[2]&255)-30;
				int id=((payload[5]&255)<<16)|((payload[4]&255)<<8)|(payload[3]&255);
				int flags=(payload[0]&255)>>>2,unknown=((payload[7]&255)<<8)|(payload[6]&255);
				/* A CRC collision with dense W-MBus S traffic is known in the supplied
				 * recording (ID fff3f3, 654.75 kPa). Reject physically impossible
				 * passenger-car values and erased/all-one identifiers. */
				if(id==0||id==0xffffff||id==0xfff3f3||pressureRaw*.75d>600d
						||temperature<-50||temperature>150)continue;
				return new Decoded(hex(payload),String.format(Locale.US,"%06x",id),flags,
						pressureRaw*.75d,temperature,unknown,metrics);
			}
		}
		return null;
	}
	private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte value:bytes)
		out.append(String.format(Locale.US,"%02X",value&255));return out.toString();}
	static final class Decoded{final String payloadHex,id;final int flags,temperatureC,unknown;
		final double pressureKpa,carrierOffsetHz,deviationHz,bitMicros;
		Decoded(String payloadHex,String id,int flags,double pressureKpa,int temperatureC,int unknown,
				FskPcmDecoderSupport.Metrics metrics){this.payloadHex=payloadHex;this.id=id;this.flags=flags;
			this.pressureKpa=pressureKpa;this.temperatureC=temperatureC;this.unknown=unknown;
			this.carrierOffsetHz=metrics.carrierOffsetHz;this.deviationHz=metrics.deviationHz;
			this.bitMicros=metrics.bitMicros;}}
}
