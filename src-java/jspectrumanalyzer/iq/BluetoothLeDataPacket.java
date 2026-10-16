package jspectrumanalyzer.iq;

import java.util.Locale;

/** CRC-valid Bluetooth LE data-channel PDU tied to an observed CONNECT_IND access address. */
final class BluetoothLeDataPacket {
	final int channel,llid,payloadLength;final long frequencyHz,accessAddress,decodedMillis;
	final String phy,initiatorAddress,advertiserAddress;String packetType,details,trackingInfo="--";
	final boolean nesn,sn,moreData,cteInfoPresent;final double levelDbfs,cfoHz,quality;
	int connectionEvent=-1,predictedChannel=-1;boolean channelPredictionMatches;
	String direction="--",l2capStatus="--";int l2capLength=-1,l2capCid=-1,l2capFragments;
	boolean retransmission;byte[] l2capPdu;
	final byte[] pdu;

	private BluetoothLeDataPacket(int channel,long frequencyHz,String phy,long accessAddress,byte[] pdu,
			String initiatorAddress,String advertiserAddress,double levelDbfs,double cfoHz,double quality,long decodedMillis){
		this.channel=channel;this.frequencyHz=frequencyHz;this.phy=phy;this.accessAddress=accessAddress;
		this.pdu=pdu;this.llid=pdu[0]&3;this.nesn=(pdu[0]&4)!=0;this.sn=(pdu[0]&8)!=0;
		this.moreData=(pdu[0]&0x10)!=0;this.cteInfoPresent=(pdu[0]&0x20)!=0;this.payloadLength=pdu[1]&0xff;
		String[] decoded=describe(pdu,llid,payloadLength);this.packetType=decoded[0];this.details=decoded[1];
		this.initiatorAddress=initiatorAddress;this.advertiserAddress=advertiserAddress;
		this.levelDbfs=levelDbfs;this.cfoHz=cfoHz;this.quality=quality;this.decodedMillis=decodedMillis;
	}

	static BluetoothLeDataPacket parse(int channel,long frequencyHz,String phy,long accessAddress,byte[] pdu,
			String initiatorAddress,String advertiserAddress,double levelDbfs,double cfoHz,double quality,long decodedMillis){
		if(pdu==null||pdu.length<2||(pdu[1]&0xff)>pdu.length-2)return null;
		byte[] copy=new byte[2+(pdu[1]&0xff)];System.arraycopy(pdu,0,copy,0,copy.length);
		return new BluetoothLeDataPacket(channel,frequencyHz,phy,accessAddress,copy,initiatorAddress,
				advertiserAddress,levelDbfs,cfoHz,quality,decodedMillis);
	}

	String accessText(){return String.format(Locale.US,"AA 0x%08X",accessAddress);}
	void markEncrypted(){packetType="Encrypted LL data";details="Encrypted payload, "+payloadLength+" byte(s)";}
	private static String[] describe(byte[] pdu,int llid,int length){
		if(length==0)return new String[]{"LL empty data","SN/NESN acknowledgement only"};
		if(llid==3){int opcode=pdu[2]&0xff;return new String[]{"LL Control "+controlName(opcode),controlDetails(pdu,opcode)};}
		if(llid==2&&length>=4){int l2len=u16(pdu,2),cid=u16(pdu,4);String protocol=cidName(cid),detail=String.format(Locale.US,
				"L2CAP length %d, CID 0x%04X (%s)",l2len,cid,protocol);if(cid==4&&length>=5)
				detail+=", "+attName(pdu[6]&0xff);return new String[]{"L2CAP "+protocol,detail};}
		if(llid==1)return new String[]{"LL continuation","Continuation fragment, "+length+" byte(s)"};
		return new String[]{"LL data","LLID "+llid+", "+length+" byte(s); contents may be encrypted"};
	}
	private static String controlName(int opcode){switch(opcode){case 0:return"CONNECTION_UPDATE_IND";case 1:return"CHANNEL_MAP_IND";
		case 2:return"TERMINATE_IND";case 3:return"ENC_REQ";case 4:return"ENC_RSP";case 5:return"START_ENC_REQ";
		case 6:return"START_ENC_RSP";case 7:return"UNKNOWN_RSP";case 8:return"FEATURE_REQ";case 9:return"FEATURE_RSP";
		case 10:return"PAUSE_ENC_REQ";case 11:return"PAUSE_ENC_RSP";case 12:return"VERSION_IND";case 13:return"REJECT_IND";
		case 14:return"PERIPHERAL_FEATURE_REQ";case 15:return"CONNECTION_PARAM_REQ";case 16:return"CONNECTION_PARAM_RSP";
		case 17:return"REJECT_EXT_IND";case 18:return"PING_REQ";case 19:return"PING_RSP";case 20:return"LENGTH_REQ";
		case 21:return"LENGTH_RSP";case 22:return"PHY_REQ";case 23:return"PHY_RSP";case 24:return"PHY_UPDATE_IND";
		case 25:return"MIN_USED_CHANNELS_IND";default:return String.format(Locale.US,"opcode 0x%02X",opcode);}}
	private static String controlDetails(byte[] pdu,int opcode){int length=pdu[1]&0xff;
		if(opcode==2&&length>=2)return String.format(Locale.US,"Reason 0x%02X",pdu[3]&0xff);
		if(opcode==12&&length>=6)return String.format(Locale.US,"Version 0x%02X, company 0x%04X, subversion 0x%04X",
				pdu[3]&0xff,u16(pdu,4),u16(pdu,6));
		if((opcode==20||opcode==21)&&length>=9)return String.format(Locale.US,
				"Rx octets %d / %d us, Tx octets %d / %d us",u16(pdu,3),u16(pdu,5),u16(pdu,7),u16(pdu,9));
		if((opcode==22||opcode==23)&&length>=3)return String.format(Locale.US,"Tx PHY mask 0x%02X, Rx PHY mask 0x%02X",pdu[3]&0xff,pdu[4]&0xff);
		if(opcode==24&&length>=5)return String.format(Locale.US,"central-to-peripheral PHY %s, peripheral-to-central PHY %s, instant %d",
				phyName(pdu[3]&0xff),phyName(pdu[4]&0xff),u16(pdu,5));
		return length>1?(length-1)+" control parameter byte(s)":"No parameters";
	}
	private static String phyName(int value){return value==1?"LE 1M":value==2?"LE 2M":value==3?"LE Coded":"unchanged/reserved";}
	static String cidName(int cid){switch(cid){case 1:return"Signaling";case 4:return"ATT";case 5:return"LE Signaling";
		case 6:return"SMP";case 7:return"SMP over BR/EDR";default:return cid>=0x40?"dynamic channel":"unknown fixed channel";}}
	static String attName(int opcode){int command=opcode&0x3f;switch(command){case 1:return"ATT Error Response";case 2:return"ATT Exchange MTU Request";
		case 3:return"ATT Exchange MTU Response";case 4:return"ATT Find Information Request";case 5:return"ATT Find Information Response";
		case 8:return"ATT Read By Type Request";case 9:return"ATT Read By Type Response";case 10:return"ATT Read Request";
		case 11:return"ATT Read Response";case 12:return"ATT Read Blob Request";case 13:return"ATT Read Blob Response";
		case 16:return"ATT Read By Group Type Request";case 17:return"ATT Read By Group Type Response";case 18:return"ATT Write Request";
		case 19:return"ATT Write Response";case 27:return"ATT Handle Value Notification";case 29:return"ATT Handle Value Indication";
		case 30:return"ATT Handle Value Confirmation";default:return String.format(Locale.US,"ATT opcode 0x%02X",opcode);}}
	private static int u16(byte[] data,int at){return at+1<data.length?(data[at]&0xff)|((data[at+1]&0xff)<<8):0;}
}
