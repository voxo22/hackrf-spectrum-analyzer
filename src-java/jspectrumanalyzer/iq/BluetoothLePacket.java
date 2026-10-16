package jspectrumanalyzer.iq;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Decoded, CRC-valid Bluetooth LE advertising-channel PDU. */
final class BluetoothLePacket {
	private static final String[] PDU_NAMES = {
			"ADV_IND", "ADV_DIRECT_IND", "ADV_NONCONN_IND", "SCAN_REQ",
			"SCAN_RSP", "CONNECT_IND", "ADV_SCAN_IND", "ADV_EXT_IND",
			"AUX_CONNECT_RSP", "Reserved", "Reserved", "Reserved",
			"Reserved", "Reserved", "Reserved", "Reserved"
	};

	final int channel, payloadLength, auxChannel;
	final long frequencyHz, decodedMillis;
	final String phy, pduType, address, addressType, peerAddress, peerAddressType, auxPhy;
	final String role, peerRole, localName, deviceType, classOfDevice, appearance, services, manufacturerData, manufacturer;
	final String flags, extendedInfo, connectionInfo;
	final ConnectionData connectionData;
	final boolean connectable, scannable, directed;
	final int txPower;
	final double levelDbfs, cfoHz, quality, auxOffsetMicros;
	final byte[] pdu;

	private BluetoothLePacket(int channel, long frequencyHz, String phy, String pduType, int payloadLength,
			String address, String addressType, String peerAddress, String peerAddressType,
			String role, String peerRole, String localName, String deviceType, String classOfDevice, String appearance, String services,
			String manufacturerData, String manufacturer, String flags, String extendedInfo,
			String connectionInfo, ConnectionData connectionData, boolean connectable, boolean scannable, boolean directed,
			int txPower, double levelDbfs, double cfoHz, double quality, int auxChannel,
			double auxOffsetMicros, String auxPhy, byte[] pdu,
			long decodedMillis) {
		this.channel=channel; this.frequencyHz=frequencyHz; this.phy=phy; this.pduType=pduType;
		this.payloadLength=payloadLength; this.address=address; this.addressType=addressType;
		this.peerAddress=peerAddress; this.peerAddressType=peerAddressType;
		this.role=role; this.peerRole=peerRole; this.localName=localName;
		this.deviceType=deviceType; this.classOfDevice=classOfDevice;this.appearance=appearance; this.services=services;
		this.manufacturerData=manufacturerData; this.manufacturer=manufacturer; this.flags=flags;
		this.extendedInfo=extendedInfo; this.connectionInfo=connectionInfo;
		this.connectionData=connectionData;
		this.connectable=connectable; this.scannable=scannable; this.directed=directed;
		this.txPower=txPower; this.levelDbfs=levelDbfs; this.cfoHz=cfoHz; this.quality=quality;
		this.auxChannel=auxChannel;this.auxOffsetMicros=auxOffsetMicros;this.auxPhy=auxPhy;
		this.pdu=pdu; this.decodedMillis=decodedMillis;
	}

	static BluetoothLePacket parse(int channel, long frequencyHz, byte[] pdu,
			double levelDbfs, double cfoHz, double quality, long decodedMillis) {
		return parse(channel,frequencyHz,"LE 1M",pdu,levelDbfs,cfoHz,quality,decodedMillis);
	}

	static BluetoothLePacket parse(int channel, long frequencyHz, String phy, byte[] pdu,
			double levelDbfs, double cfoHz, double quality, long decodedMillis) {
		if (pdu == null || pdu.length < 2) return null;
		int type=pdu[0]&15, length=type==7?(pdu[1]&0xff):(pdu[1]&0x3f);
		if (length > pdu.length-2) return null;
		boolean txRandom=(pdu[0]&0x40)!=0, rxRandom=(pdu[0]&0x80)!=0;
		String name=type<PDU_NAMES.length?PDU_NAMES[type]:"Reserved";
		String address="--",peer="--",addressType="Unknown",peerType="Unknown";
		String role="Unknown",peerRole="--",connection="--",extended="--";ConnectionData connectionData=ConnectionData.EMPTY;
		ExtendedHeader parsedExtended=ExtendedHeader.EMPTY;
		boolean connectable=type==0||type==1||type==5;
		boolean scannable=type==0||type==4||type==6;
		boolean directed=type==1;
		int adAt=-1,adLength=0;
		if ((type==0||type==2||type==4||type==6) && length>=6) {
			address=address(pdu,2); addressType=addressType(address,txRandom); role="Advertiser";
			adAt=8; adLength=length-6;
		} else if (type==1 && length>=12) {
			address=address(pdu,2); addressType=addressType(address,txRandom); role="Advertiser";
			peer=address(pdu,8); peerType=addressType(peer,rxRandom); peerRole="Target";
		} else if (type==3 && length>=12) {
			address=address(pdu,2); addressType=addressType(address,txRandom); role="Scanner";
			peer=address(pdu,8); peerType=addressType(peer,rxRandom); peerRole="Advertiser";
		} else if (type==5 && length>=34) {
			peer=address(pdu,2); peerType=addressType(peer,txRandom); peerRole="Initiator";
			address=address(pdu,8); addressType=addressType(address,rxRandom); role="Advertiser";
			connectionData=parseConnection(pdu,14,(pdu[0]&0x20)!=0);connection=connectionData.summary;
		} else if (type==7 && length>=1) {
			ExtendedHeader header=parseExtendedHeader(pdu,2,length,txRandom,rxRandom);parsedExtended=header;
			address=header.address; addressType=header.addressType; role="Advertiser";
			peer=header.peerAddress; peerType=header.peerAddressType; peerRole=header.peerRole;
			extended=header.summary; connectable=header.connectable; scannable=header.scannable;
			directed=!"--".equals(peer); adAt=header.dataAt; adLength=header.dataLength;
		}
		AdData ad=parseAdData(pdu,adAt,adLength);
		String vendor="--";
		if (!"--".equals(address) && "Public".equals(addressType)) vendor=WifiOuiLookup.company(address);
		if ("--".equals(vendor) && !"--".equals(ad.company)) vendor=ad.company;
		byte[] copy=new byte[2+length]; System.arraycopy(pdu,0,copy,0,copy.length);
		return new BluetoothLePacket(channel,frequencyHz,phy,name,length,address,addressType,peer,peerType,
				role,peerRole,ad.name,ad.deviceType,ad.classOfDevice,ad.appearance,ad.services,ad.manufacturerData,vendor,ad.flags,extended,
				connection,connectionData,connectable,scannable,directed,ad.txPower,levelDbfs,cfoHz,quality,
				parsedExtended.auxChannel,parsedExtended.auxOffsetMicros,parsedExtended.auxPhy,copy,decodedMillis);
	}

	private static ExtendedHeader parseExtendedHeader(byte[] pdu,int at,int length,
			boolean txRandom,boolean rxRandom) {
		int end=Math.min(pdu.length,at+length);
		if (at>=end) return ExtendedHeader.EMPTY;
		int first=pdu[at]&0xff,headerLength=first&0x3f,mode=first>>>6;
		boolean connectable=mode==1,scannable=mode==2;
		int headerEnd=Math.min(end,at+1+headerLength),cursor=at+1;
		if (cursor>=headerEnd) return new ExtendedHeader("--","Unknown","--","Unknown","--",
				connectable,scannable,headerEnd,end-headerEnd,"AdvMode "+mode,-1,Double.NaN,"--");
		int fields=pdu[cursor++]&0xff; String address="--",addressType="Unknown";
		String peer="--",peerType="Unknown",peerRole="--",auxPhy="--";int auxChannel=-1;double auxOffsetMicros=Double.NaN;
		List<String> info=new ArrayList<String>();
		info.add("AdvMode "+(mode==0?"non-connectable/non-scannable":mode==1?"connectable":
				mode==2?"scannable":"reserved"));
		if ((fields&1)!=0 && cursor+6<=headerEnd) { address=address(pdu,cursor);
			addressType=addressType(address,txRandom); cursor+=6; }
		if ((fields&2)!=0 && cursor+6<=headerEnd) { peer=address(pdu,cursor);
			peerType=addressType(peer,rxRandom); peerRole="Target"; cursor+=6; }
		if ((fields&4)!=0 && cursor<headerEnd) { info.add(String.format(Locale.US,"CTE 0x%02X",pdu[cursor]&0xff)); cursor++; }
		if ((fields&8)!=0 && cursor+2<=headerEnd) { int adi=u16(pdu,cursor);
			info.add("SID "+(adi>>>12)+", DID "+(adi&0xfff)); cursor+=2; }
		if ((fields&0x10)!=0 && cursor+3<=headerEnd) {
			int b0=pdu[cursor]&0xff,b1=pdu[cursor+1]&0xff,b2=pdu[cursor+2]&0xff;
			auxChannel=b0&0x3f;int offset=b1|((b2&0x1f)<<8),units=(b0&0x80)==0?30:300;
			int phy=(b2>>>5)&7;auxPhy=phy==0?"LE 1M":phy==1?"LE 2M":phy==2?"LE Coded":"PHY "+phy;
			auxOffsetMicros=offset*units;info.add(String.format(Locale.US,"AUX ch %d in %.2f ms (%s)",
					auxChannel,auxOffsetMicros/1000d,auxPhy));
			cursor+=3;
		}
		if ((fields&0x20)!=0 && cursor+18<=headerEnd) { info.add("SyncInfo present"); cursor+=18; }
		if ((fields&0x40)!=0 && cursor<headerEnd) { info.add("Tx "+pdu[cursor]+" dBm"); cursor++; }
		if ((fields&0x80)!=0 && cursor<headerEnd) info.add("ACAD "+(headerEnd-cursor)+" B");
		return new ExtendedHeader(address,addressType,peer,peerType,peerRole,connectable,scannable,
				headerEnd,Math.max(0,end-headerEnd),join(info,", "),auxChannel,auxOffsetMicros,auxPhy);
	}

	private static ConnectionData parseConnection(byte[] data,int at,boolean channelSelection2) {
		if (at+22>data.length) return ConnectionData.EMPTY;
		long access=u32(data,at); int crc=(data[at+4]&0xff)|((data[at+5]&0xff)<<8)|((data[at+6]&0xff)<<16);
		int windowSize=data[at+7]&0xff,windowOffset=u16(data,at+8),interval=u16(data,at+10),latency=u16(data,at+12),timeout=u16(data,at+14);
		long map=0; for(int n=0;n<5;n++)map|=(long)(data[at+16+n]&0xff)<<(8*n);
		int hop=data[at+21]&31,sca=(data[at+21]>>>5)&7;
		String summary=String.format(Locale.US,"AA 0x%08X, CRCInit 0x%06X, interval %.2f ms, latency %d, "
				+"timeout %d ms, hop %d, SCA %d, channels %s",access,crc,interval*1.25d,
				latency,timeout*10,hop,sca,channelMap(map))+", channel selection "+(channelSelection2?"#2":"#1");
		return new ConnectionData(access,crc,map,windowSize,windowOffset,interval,latency,timeout,hop,sca,channelSelection2,summary);
	}

	private static String channelMap(long map) {
		StringBuilder out=new StringBuilder(); int run=-1;
		for(int ch=0;ch<=37;ch++) {
			boolean on=ch<37&&(map&(1L<<ch))!=0;
			if(on&&run<0)run=ch;
			if(!on&&run>=0){if(out.length()>0)out.append(',');out.append(run);
				if(ch-1>run)out.append('-').append(ch-1);run=-1;}
		}
		return out.length()==0?"none":out.toString();
	}

	private static AdData parseAdData(byte[] data,int at,int length) {
		AdData result=new AdData();
		if(at<0||length<=0||at>=data.length)return result;
		int end=Math.min(data.length,at+length); Set<String> services=new LinkedHashSet<String>();
		Set<Integer> serviceIds=new LinkedHashSet<Integer>();
		for(int cursor=at;cursor<end;) {
			int size=data[cursor]&0xff;if(size==0)break;int next=cursor+size+1;
			if(next>end||size<1)break;int type=data[cursor+1]&0xff,p=cursor+2,n=size-1;
			if((type==0x08||type==0x09)&&n>0)result.name=text(data,p,n);
			else if(type==0x01&&n>0)result.flags=flags(data[p]&0xff);
			else if(type==0x0a&&n>0)result.txPower=data[p];
			else if(type==0x02||type==0x03||type==0x14)for(int q=0;q+1<n;q+=2) {
				int uuid=u16(data,p+q);serviceIds.add(Integer.valueOf(uuid));services.add(uuid16(uuid));}
			else if(type==0x04||type==0x05)for(int q=0;q+3<n;q+=4)
				services.add(String.format(Locale.US,"%08X",u32(data,p+q)));
			else if(type==0x06||type==0x07||type==0x15)for(int q=0;q+15<n;q+=16)
				services.add(uuid128(data,p+q));
			else if(type==0x16&&n>=2){int uuid=u16(data,p);serviceIds.add(Integer.valueOf(uuid));
				services.add(uuid16(uuid)+" service data");}
			else if(type==0x19&&n>=2){int value=u16(data,p);result.appearance=appearance(value);
				result.deviceType=appearanceType(value);}
			else if(type==0x0d&&n>=3){int value=(data[p]&0xff)|((data[p+1]&0xff)<<8)|((data[p+2]&0xff)<<16);
				result.classOfDevice=String.format(Locale.US,"0x%06X",value);
				if("--".equals(result.deviceType))result.deviceType=classOfDeviceType(value);}
			else if(type==0xff&&n>=2){int company=u16(data,p);result.company=company(company);
				result.manufacturerData=String.format(Locale.US,"0x%04X %s",company,hex(data,p+2,n-2,20));}
			cursor=next;
		}
		result.services=join(new ArrayList<String>(services),", ");
		if("--".equals(result.deviceType))result.deviceType=inferDeviceType(serviceIds);
		return result;
	}

	private static String inferDeviceType(Set<Integer> services) {
		if(services.contains(Integer.valueOf(0x1812)))return "Human interface / remote";
		if(services.contains(Integer.valueOf(0x180d)))return "Heart-rate sensor";
		if(services.contains(Integer.valueOf(0x1809)))return "Thermometer";
		if(services.contains(Integer.valueOf(0x1816)))return "Cycling sensor";
		if(services.contains(Integer.valueOf(0x1818)))return "Cycling power sensor";
		if(services.contains(Integer.valueOf(0x181a)))return "Environmental sensor";
		if(services.contains(Integer.valueOf(0x1826)))return "Fitness machine";
		if(services.contains(Integer.valueOf(0x1843))||services.contains(Integer.valueOf(0x1844))
				||services.contains(Integer.valueOf(0x1845))||services.contains(Integer.valueOf(0x1846))
				||services.contains(Integer.valueOf(0x1848))||services.contains(Integer.valueOf(0x1849))
				||services.contains(Integer.valueOf(0x184b))||services.contains(Integer.valueOf(0x184c))
				||services.contains(Integer.valueOf(0x184d))||services.contains(Integer.valueOf(0x184e))
				||services.contains(Integer.valueOf(0x184f))
				||services.contains(Integer.valueOf(0x1850))||services.contains(Integer.valueOf(0x1851))
				||services.contains(Integer.valueOf(0x1852))||services.contains(Integer.valueOf(0x1853))
				||services.contains(Integer.valueOf(0x1854))||services.contains(Integer.valueOf(0x1855))
				||services.contains(Integer.valueOf(0x1856))||services.contains(Integer.valueOf(0x1858)))
			return "LE Audio device";
		if(services.contains(Integer.valueOf(0xfeaa)))return "Eddystone beacon";
		if(services.contains(Integer.valueOf(0xfd6f)))return "Exposure notification";
		return "--";
	}

	private static String appearance(int value) {
		return appearanceType(value)+String.format(Locale.US," (0x%04X)",value);
	}

	private static String appearanceType(int value) {
		String exact;
		switch(value) {
		case 0x0180:exact="Remote control";break;case 0x0280:exact="Media player";break;
		case 0x03c1:exact="Keyboard";break;case 0x03c2:exact="Mouse";break;
		case 0x03c3:exact="Joystick";break;case 0x03c4:exact="Gamepad";break;
		case 0x0841:exact="Standalone speaker";break;case 0x0842:exact="Soundbar";break;
		case 0x0843:exact="Bookshelf speaker";break;case 0x0844:exact="Stand-mounted speaker";break;
		case 0x0845:exact="Speakerphone";break;case 0x0881:exact="Microphone";break;
		case 0x0885:exact="Broadcasting audio device";break;case 0x0941:exact="Earbud";break;
		case 0x0942:exact="Headset";break;case 0x0943:exact="Headphones";break;
		case 0x09c1:exact="Amplifier";break;case 0x09c2:exact="AV receiver";break;
		case 0x09c3:exact="Radio";break;case 0x09ca:exact="Set-top box";break;
		case 0x0a01:exact="Television";break;case 0x0a02:exact="Monitor";break;
		case 0x0a03:exact="Projector";break;default:exact=appearanceCategory(value>>>6);break;
		}
		return exact;
	}

	private static String appearanceCategory(int category) {switch(category) {
		case 0:return "Unknown appearance";case 1:return "Phone";case 2:return "Computer";
		case 3:return "Watch";case 4:return "Clock";case 5:return "Display";case 6:return "Remote control";
		case 7:return "Eye-glasses";case 8:return "Tag";case 9:return "Keyring";case 10:return "Media player";
		case 11:return "Barcode scanner";case 12:return "Thermometer";case 13:return "Heart-rate sensor";
		case 14:return "Blood-pressure device";case 15:return "Human interface device";
		case 16:return "Glucose meter";case 17:return "Running/walking sensor";case 18:return "Cycling device";
		case 33:return "Audio sink";case 34:return "Audio source";case 37:return "Wearable audio device";
		case 39:return "AV equipment";case 40:return "Display equipment";case 41:return "Hearing aid";
		case 42:return "Gaming device";default:return "Bluetooth device category "+category;
	}}

	/** Human-readable BR/EDR Class of Device, also shared by the Classic FHS decoder. */
	static String classOfDevice(int value) {
		String type=classOfDeviceType(value),services=classServices(value);
		return type+String.format(Locale.US," [Class of Device 0x%06X%s]",value,services.length()==0?"":"; "+services);
	}

	static String classOfDeviceType(int value) {
		int major=(value>>>8)&0x1f,minor=(value>>>2)&0x3f;String type;
		switch(major) {
		case 1:type="Computer - "+choice(minor,"uncategorized","desktop workstation","server","laptop",
				"handheld PC","palm-size PC/PDA","wearable computer","tablet");break;
		case 2:type="Phone - "+choice(minor,"uncategorized","cellular","cordless","smartphone",
				"wired modem/voice gateway","common ISDN access");break;
		case 3:type="Network access device";break;
		case 4:type="Audio/video - "+audioVideoMinor(minor);break;
		case 5:type="Peripheral - "+peripheralMinor(minor);break;
		case 6:type="Imaging - "+imagingMinor(minor);break;
		case 7:type="Wearable - "+choice(minor,"uncategorized","wristwatch","pager","jacket","helmet","glasses");break;
		case 8:type="Toy - "+choice(minor,"uncategorized","robot","vehicle","doll/action figure","controller","game");break;
		case 9:type="Health - "+choice(minor,"uncategorized","blood-pressure monitor","thermometer","weighing scale",
				"glucose meter","pulse oximeter","heart-rate monitor","health-data display","step counter",
				"body-composition analyzer","peak-flow monitor","medication monitor","knee prosthesis",
				"ankle prosthesis","generic health manager","personal mobility device");break;
		default:type="Bluetooth device";break;
		}
		return type;
	}

	private static String choice(int value,String... names){return value>=0&&value<names.length?names[value]:"class "+value;}
	private static String audioVideoMinor(int value){switch(value){case 0:return"uncategorized";case 1:return"wearable headset";
		case 2:return"hands-free";case 4:return"microphone";case 5:return"loudspeaker";case 6:return"headphones";
		case 7:return"portable audio";case 8:return"car audio";case 9:return"set-top box";case 10:return"Hi-Fi audio";
		case 11:return"VCR";case 12:return"video camera";case 13:return"camcorder";case 14:return"video monitor";
		case 15:return"video display and loudspeaker";case 16:return"video conferencing";case 18:return"gaming/toy";
		default:return"class "+value;}}
	private static String peripheralMinor(int value){int kind=value&15;String device=choice(kind,"uncategorized","joystick","gamepad",
			"remote control","sensing device","digitizer tablet","card reader","digital pen","handheld scanner",
			"handheld gestural input");int input=(value>>>4)&3;return input==1?"keyboard / "+device:
			input==2?"pointing device / "+device:input==3?"keyboard and pointing device / "+device:device;}
	private static String imagingMinor(int value){List<String> kinds=new ArrayList<String>();if((value&4)!=0)kinds.add("display");
		if((value&8)!=0)kinds.add("camera");if((value&16)!=0)kinds.add("scanner");if((value&32)!=0)kinds.add("printer");
		String joined=join(kinds,", ");return "--".equals(joined)?"uncategorized":joined;}
	private static String classServices(int value){List<String> services=new ArrayList<String>();
		if((value&(1<<13))!=0)services.add("limited discoverable");if((value&(1<<16))!=0)services.add("positioning");
		if((value&(1<<17))!=0)services.add("networking");if((value&(1<<18))!=0)services.add("rendering");
		if((value&(1<<19))!=0)services.add("capturing");if((value&(1<<20))!=0)services.add("object transfer");
		if((value&(1<<21))!=0)services.add("audio");if((value&(1<<22))!=0)services.add("telephony");
		if((value&(1<<23))!=0)services.add("information");return join(services,", ");}

	static String addressType(String address,boolean random) {
		if(!random)return "Public";
		if(address==null||address.length()<2)return "Random";
		int top=Integer.parseInt(address.substring(0,2),16)>>>6;
		return top==3?"Random static":top==1?"Resolvable private":top==0?"Non-resolvable private":"Random (reserved)";
	}

	private static String address(byte[] data,int at) {
		if(at<0||at+6>data.length)return "--";StringBuilder out=new StringBuilder();
		for(int n=5;n>=0;n--){if(out.length()>0)out.append(':');out.append(String.format(Locale.US,"%02X",data[at+n]&0xff));}
		return out.toString();
	}

	private static String flags(int value) {List<String> out=new ArrayList<String>();
		if((value&1)!=0)out.add("limited discoverable");if((value&2)!=0)out.add("general discoverable");
		if((value&4)!=0)out.add("BR/EDR unsupported");if((value&8)!=0)out.add("simultaneous LE/BR controller");
		if((value&0x10)!=0)out.add("simultaneous LE/BR host");return join(out,", ");}
	private static String uuid16(int uuid) {String known=uuid==0x1800?"Generic Access":uuid==0x1801?"Generic Attribute":
			uuid==0x1809?"Health Thermometer":uuid==0x180a?"Device Information":uuid==0x180d?"Heart Rate":
			uuid==0x180f?"Battery":uuid==0x1812?"Human Interface Device":uuid==0x1816?"Cycling Speed/Cadence":
			uuid==0x1818?"Cycling Power":uuid==0x181a?"Environmental Sensing":uuid==0x1826?"Fitness Machine":
			uuid==0x184e?"Audio Stream Control":uuid==0x184f?"Broadcast Audio Scan":
			uuid==0x1850?"Published Audio Capabilities":uuid==0x1851?"Basic Audio Announcement":
			uuid==0x1852?"Broadcast Audio Announcement":uuid==0xfeaa?"Eddystone":
			uuid==0xfd6f?"Exposure Notification":uuid==0xfef3||uuid==0xfcf1?"Google LLC assigned":"";
		return String.format(Locale.US,"0x%04X",uuid)+(known.length()==0?"":" ("+known+")");}
	private static String uuid128(byte[] d,int at){StringBuilder s=new StringBuilder();for(int n=15;n>=0;n--){s.append(String.format(Locale.US,"%02x",d[at+n]&0xff));if(n==12||n==10||n==8||n==6)s.append('-');}return s.toString();}
	private static String company(int id){switch(id){case 0x004c:return"Apple, Inc.";case 0x0006:return"Microsoft";
		case 0x000f:return"Broadcom";case 0x0059:return"Nordic Semiconductor";case 0x0075:return"Samsung Electronics";
		case 0x00e0:return"Google";case 0x0131:return"Cypress Semiconductor";default:return String.format(Locale.US,"Company ID 0x%04X",id);}}
	private static String text(byte[] data,int at,int size){String value=new String(data,at,size,StandardCharsets.UTF_8).trim();return value.length()==0?"--":value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]","?");}
	private static String hex(byte[] data,int at,int size,int max){StringBuilder out=new StringBuilder();int n=Math.min(size,max);for(int q=0;q<n;q++){if(q>0)out.append(' ');out.append(String.format(Locale.US,"%02X",data[at+q]&0xff));}if(size>max)out.append(" ...");return out.toString();}
	private static int u16(byte[] d,int at){return(d[at]&0xff)|((d[at+1]&0xff)<<8);}
	private static long u32(byte[] d,int at){return(d[at]&0xffL)|((d[at+1]&0xffL)<<8)|((d[at+2]&0xffL)<<16)|((d[at+3]&0xffL)<<24);}
	private static String join(List<String> values,String separator){StringBuilder out=new StringBuilder();for(String v:values){if(v==null||v.length()==0)continue;if(out.length()>0)out.append(separator);out.append(v);}return out.length()==0?"--":out.toString();}

	private static final class AdData {String name="--",deviceType="--",classOfDevice="--",appearance="--",services="--",manufacturerData="--",company="--",flags="--";int txPower=Integer.MIN_VALUE;}
	static final class ConnectionData {
		static final ConnectionData EMPTY=new ConnectionData(-1L,-1,0L,0,0,0,0,0,0,0,false,"--");
		final long accessAddress,channelMap;final int crcInit,windowSize,windowOffset,interval,latency,timeout,hop,sca;
		final boolean channelSelection2;final String summary;
		ConnectionData(long accessAddress,int crcInit,long channelMap,int windowSize,int windowOffset,int interval,
				int latency,int timeout,int hop,int sca,boolean channelSelection2,String summary){this.accessAddress=accessAddress;
			this.crcInit=crcInit;this.channelMap=channelMap;this.windowSize=windowSize;this.windowOffset=windowOffset;
			this.interval=interval;this.latency=latency;this.timeout=timeout;this.hop=hop;this.sca=sca;
			this.channelSelection2=channelSelection2;this.summary=summary;}
		boolean valid(){return accessAddress>=0&&crcInit>=0&&channelMap!=0;}
	}
	private static final class ExtendedHeader {
		static final ExtendedHeader EMPTY=new ExtendedHeader("--","Unknown","--","Unknown","--",false,false,-1,0,"--",-1,Double.NaN,"--");
		final String address,addressType,peerAddress,peerAddressType,peerRole,summary,auxPhy;final boolean connectable,scannable;final int dataAt,dataLength,auxChannel;final double auxOffsetMicros;
		ExtendedHeader(String a,String t,String p,String pt,String pr,boolean c,boolean s,int da,int dl,String summary,int auxChannel,double auxOffsetMicros,String auxPhy){address=a;addressType=t;peerAddress=p;peerAddressType=pt;peerRole=pr;connectable=c;scannable=s;dataAt=da;dataLength=dl;this.summary=summary;this.auxChannel=auxChannel;this.auxOffsetMicros=auxOffsetMicros;this.auxPhy=auxPhy;}
	}
}
