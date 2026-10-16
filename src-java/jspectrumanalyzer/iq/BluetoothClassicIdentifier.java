package jspectrumanalyzer.iq;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Extracts identity evidence from Classic EIR and safely attributable LMP responses. */
final class BluetoothClassicIdentifier {
	private final Map<String,NameBuffer> names=new LinkedHashMap<String,NameBuffer>();

	BluetoothGattIdentifier.Identity observe(BluetoothClassicPacket packet) {
		if(packet==null||!packet.payloadValid||!usable(packet.address))return null;
		if(packet.eir&&packet.inquiryAccess)return eir(packet);
		if(packet.llid!=3||packet.payload.length==0)return null;
		int opcode=(packet.payload[0]&0xff)>>>1,tid=packet.payload[0]&1;
		// A response with TID=1 belongs to a transaction initiated by the Peripheral; its sender is the known Central.
		if(tid!=1)return null;
		if(opcode==2&&packet.payload.length>=4)return name(packet);
		if(opcode==38&&packet.payload.length>=6){BluetoothGattIdentifier.Identity out=new BluetoothGattIdentifier.Identity(packet.address);
			int company=u16(packet.payload,2);String controller=BluetoothAssignedNumbers.company(company);out.confidence="Medium";
			out.bluetoothVersion=BluetoothAssignedNumbers.bluetoothVersion(packet.payload[1]&0xff);
			out.evidence="LMP controller "+controller;out.details=String.format(Locale.US,
					"LMP version 0x%02X; controller %s; subversion 0x%04X",packet.payload[1]&0xff,controller,u16(packet.payload,4));return out;}
		return null;
	}

	private BluetoothGattIdentifier.Identity name(BluetoothClassicPacket packet){int offset=packet.payload[1]&0xff,length=packet.payload[2]&0xff;if(length<=0)return null;
		NameBuffer buffer=names.get(packet.address);if(buffer==null||buffer.announcedLength!=length){buffer=new NameBuffer(length);names.put(packet.address,buffer);}
		int count=Math.min(Math.min(14,packet.payload.length-3),Math.max(0,buffer.data.length-offset));if(offset<buffer.data.length&&count>0){System.arraycopy(packet.payload,3,buffer.data,offset,count);for(int n=0;n<count;n++)buffer.present[offset+n]=true;}
		for(boolean present:buffer.present)if(!present)return null;BluetoothGattIdentifier.Identity out=new BluetoothGattIdentifier.Identity(packet.address);
		out.name=text(buffer.data,0,buffer.data.length);out.confidence="High";out.evidence="LMP remote name";out.details="Complete LMP_NAME_RES fragments";return out;}

	private static BluetoothGattIdentifier.Identity eir(BluetoothClassicPacket packet){BluetoothGattIdentifier.Identity out=new BluetoothGattIdentifier.Identity(packet.address);
		Set<String> services=new LinkedHashSet<String>(),evidence=new LinkedHashSet<String>();int classOfDevice=-1;
		for(int cursor=0;cursor<packet.payload.length;){int length=packet.payload[cursor]&0xff;if(length==0)break;int next=cursor+length+1;if(length<1||next>packet.payload.length)break;
			int type=packet.payload[cursor+1]&0xff,at=cursor+2,size=length-1;
			if((type==0x08||type==0x09)&&size>0){out.name=text(packet.payload,at,size);evidence.add(type==0x09?"Complete EIR name":"Short EIR name");}
			else if(type==0x02||type==0x03||type==0x14){for(int n=0;n+1<size;n+=2){int uuid=u16(packet.payload,at+n);services.add(BluetoothAssignedNumbers.service(uuid));String inferred=BluetoothAssignedNumbers.deviceTypeForService(uuid);if(!"--".equals(inferred))out.deviceType=inferred;}evidence.add("EIR services");}
			else if(type==0x0d&&size>=3){classOfDevice=(packet.payload[at]&0xff)|((packet.payload[at+1]&0xff)<<8)|((packet.payload[at+2]&0xff)<<16);out.deviceType=BluetoothLePacket.classOfDeviceType(classOfDevice);evidence.add("EIR Class of Device");}
			else if(type==0xff&&size>=2){out.company=BluetoothAssignedNumbers.company(u16(packet.payload,at));evidence.add("EIR manufacturer data");}
			cursor=next;}
		out.services=join(services);out.evidence=join(evidence);out.confidence=!"--".equals(out.name)||classOfDevice>=0?"High":!services.isEmpty()?"Medium":"Low";
		out.details="Classic Extended Inquiry Response";return out;}

	private static final class NameBuffer {final int announcedLength;final byte[] data;final boolean[] present;NameBuffer(int length){announcedLength=length;data=new byte[Math.min(248,length)];present=new boolean[data.length];}}
	private static String text(byte[] value,int at,int length){String out=new String(value,at,length,StandardCharsets.UTF_8).trim().replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]","?");return out.length()==0?"--":out;}
	private static int u16(byte[] data,int at){return(data[at]&0xff)|((data[at+1]&0xff)<<8);}
	private static String join(Iterable<String> values){List<String> copy=new ArrayList<String>();for(String value:values)copy.add(value);if(copy.isEmpty())return "--";StringBuilder out=new StringBuilder();for(String value:copy){if(out.length()>0)out.append(", ");out.append(value);}return out.toString();}
	private static boolean usable(String value){return value!=null&&!"--".equals(value);}
}
