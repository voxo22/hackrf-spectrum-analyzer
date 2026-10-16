package jspectrumanalyzer.iq;

import java.util.Locale;

/** BCH-validated Bluetooth BR/EDR observation, optionally with a CRC-valid FHS payload. */
final class BluetoothClassicPacket {
	final int channel,lap,bitErrors;final long frequencyHz,decodedMillis;
	final String packetType,phy;final boolean trailerPresent,inquiryAccess;
	final double levelDbfs,cfoHz,quality;
	final boolean headerValid,fhsValid,eir;final int header,whiteningSeed,payloadCorrections,classOfDevice;
	final String address,deviceType,scanRepetition;final long nativeClock;
	final int uap,ltAddr,typeCode,llid,payloadLength;final boolean flow,arqn,seqn,payloadFlow,payloadValid,encryptedPayload;
	final String classicType,payloadInfo;final byte[] payload;
	BluetoothClassicPacket(int channel,long frequencyHz,int lap,int bitErrors,boolean trailerPresent,
			double levelDbfs,double cfoHz,double quality,long decodedMillis){this(channel,frequencyHz,lap,bitErrors,
				trailerPresent,levelDbfs,cfoHz,quality,decodedMillis,null);}
	BluetoothClassicPacket(int channel,long frequencyHz,int lap,int bitErrors,boolean trailerPresent,
			double levelDbfs,double cfoHz,double quality,long decodedMillis,FhsData fhs){this.channel=channel;
		this.frequencyHz=frequencyHz;this.lap=lap;this.bitErrors=bitErrors;this.trailerPresent=trailerPresent;
		this.inquiryAccess=lap==0x9e8b33;this.headerValid=fhs!=null;this.fhsValid=fhs!=null;
		this.header=fhs==null?-1:fhs.header;this.whiteningSeed=fhs==null?-1:fhs.whiteningSeed;
		this.payloadCorrections=fhs==null?0:fhs.payloadCorrections;this.classOfDevice=fhs==null?-1:fhs.classOfDevice;
		this.address=fhs==null?"--":address(fhs.nap,fhs.uap,fhs.lap);this.deviceType=fhs==null?"--":BluetoothLePacket.classOfDeviceType(fhs.classOfDevice);
		this.eir=fhs!=null&&fhs.eir;this.scanRepetition=fhs==null?"--":fhs.scanRepetition;this.nativeClock=fhs==null?-1L:fhs.nativeClock;
		this.uap=fhs==null?-1:0;this.ltAddr=fhs==null?-1:fhs.header&7;this.typeCode=fhs==null?-1:(fhs.header>>>3)&15;
		this.flow=fhs!=null&&(fhs.header&0x80)!=0;this.arqn=fhs!=null&&(fhs.header&0x100)!=0;
		this.seqn=fhs!=null&&(fhs.header&0x200)!=0;this.classicType=fhs==null?"--":"FHS";
		this.llid=-1;this.payloadLength=fhs==null?-1:18;this.payloadFlow=false;this.payloadValid=fhs!=null;
		this.encryptedPayload=false;this.payloadInfo=fhs==null?"--":"CRC-valid FHS payload";this.payload=new byte[0];
		this.packetType=fhs!=null?"FHS inquiry response":inquiryAccess
				?(trailerPresent?"GIAC + trailer (inquiry response/FHS candidate)":"GIAC inquiry ID")
				:(trailerPresent?"BR/EDR packet access":"BR/EDR shortened access");
		this.phy="Classic BR";this.levelDbfs=levelDbfs;this.cfoHz=cfoHz;this.quality=quality;
		this.decodedMillis=decodedMillis;}
	BluetoothClassicPacket(int channel,long frequencyHz,int lap,int bitErrors,boolean trailerPresent,
			double levelDbfs,double cfoHz,double quality,long decodedMillis,HeaderData decoded,FhsData identity){
		this.channel=channel;this.frequencyHz=frequencyHz;this.lap=lap;this.bitErrors=bitErrors;this.trailerPresent=trailerPresent;
		this.inquiryAccess=lap==0x9e8b33;this.headerValid=decoded!=null;this.fhsValid=false;this.eir=inquiryAccess&&decoded!=null
				&&decoded.payloadValid&&decoded.llid==2&&(decoded.header&7)==0&&identity!=null&&identity.eir
				&&decoded.whiteningSeed==identity.whiteningSeed;
		this.header=decoded==null?-1:decoded.header;this.whiteningSeed=decoded==null?-1:decoded.whiteningSeed;
		this.payloadCorrections=decoded==null?0:decoded.payloadCorrections;this.classOfDevice=identity==null?-1:identity.classOfDevice;
		this.address=identity==null?"--":address(identity.nap,identity.uap,identity.lap);
		this.deviceType=identity==null?"--":BluetoothLePacket.classOfDeviceType(identity.classOfDevice);
		this.scanRepetition=identity==null?"--":identity.scanRepetition;this.nativeClock=identity==null?-1L:identity.nativeClock;
		this.uap=decoded==null?-1:decoded.uap;this.ltAddr=decoded==null?-1:decoded.header&7;
		this.typeCode=decoded==null?-1:(decoded.header>>>3)&15;this.flow=decoded!=null&&(decoded.header&0x80)!=0;
		this.arqn=decoded!=null&&(decoded.header&0x100)!=0;this.seqn=decoded!=null&&(decoded.header&0x200)!=0;
		this.classicType=decoded==null?"--":decoded.classicType;this.llid=decoded==null?-1:decoded.llid;
		this.payloadLength=decoded==null?-1:decoded.payloadLength;this.payloadFlow=decoded!=null&&decoded.payloadFlow;
		this.payloadValid=decoded!=null&&decoded.payloadValid;this.encryptedPayload=decoded!=null&&decoded.encryptedPayload;
		this.payloadInfo=decoded==null?"--":decoded.payloadInfo;this.payload=decoded==null?new byte[0]:decoded.payload;
		this.packetType=decoded==null?(trailerPresent?"BR/EDR packet access":"BR/EDR shortened access"):
				decoded.classicType+(decoded.payloadValid?" payload":" header");
		this.phy="Classic BR";this.levelDbfs=levelDbfs;this.cfoHz=cfoHz;this.quality=quality;this.decodedMillis=decodedMillis;
	}
	String lapText(){return String.format(Locale.US,"LAP 0x%06X",lap);}
	String identityText(){return !"--".equals(address)?address:lapText();}
	private static String address(int nap,int uap,int lap){return String.format(Locale.US,"%02X:%02X:%02X:%02X:%02X:%02X",
			(nap>>>8)&0xff,nap&0xff,uap&0xff,(lap>>>16)&0xff,(lap>>>8)&0xff,lap&0xff);}

	static final class FhsData {
		final int header,whiteningSeed,payloadCorrections,lap,uap,nap,classOfDevice;final boolean eir;
		final String scanRepetition;final long nativeClock;
		FhsData(int header,int whiteningSeed,int payloadCorrections,int lap,int uap,int nap,int classOfDevice,
				boolean eir,int scanRepetition,long nativeClock){this.header=header;this.whiteningSeed=whiteningSeed;
			this.payloadCorrections=payloadCorrections;this.lap=lap;this.uap=uap;this.nap=nap;
			this.classOfDevice=classOfDevice;this.eir=eir;this.scanRepetition=scanRepetition==0?"R0":
					scanRepetition==1?"R1":scanRepetition==2?"R2":"reserved";this.nativeClock=nativeClock;}
	}
	static final class HeaderData {
		final int header,whiteningSeed,uap,payloadCorrections,llid,payloadLength;final String classicType,payloadInfo;
		final boolean payloadFlow,payloadValid,encryptedPayload;final byte[] payload;
		HeaderData(int header,int whiteningSeed,int uap,String classicType,int payloadCorrections,int llid,
				int payloadLength,boolean payloadFlow,boolean payloadValid,boolean encryptedPayload,String payloadInfo,byte[] payload){
			this.header=header;this.whiteningSeed=whiteningSeed;this.uap=uap;this.classicType=classicType;
			this.payloadCorrections=payloadCorrections;this.llid=llid;this.payloadLength=payloadLength;
			this.payloadFlow=payloadFlow;this.payloadValid=payloadValid;this.encryptedPayload=encryptedPayload;
			this.payloadInfo=payloadInfo;this.payload=payload==null?new byte[0]:payload;}
	}
}
