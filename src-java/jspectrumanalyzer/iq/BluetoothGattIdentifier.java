package jspectrumanalyzer.iq;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Stateful passive ATT/GATT identification for clear-text LE connections. */
final class BluetoothGattIdentifier {
	private final Map<Long,Link> links=new LinkedHashMap<Long,Link>();

	List<Identity> observe(BluetoothLeDataPacket packet) {
		List<Identity> out=new ArrayList<Identity>();
		if(packet==null)return out;
		Link link=links.get(Long.valueOf(packet.accessAddress));
		if(link==null){link=new Link(packet.initiatorAddress,packet.advertiserAddress);links.put(Long.valueOf(packet.accessAddress),link);}
		boolean fromCentral=packet.direction!=null&&packet.direction.startsWith("Central");
		boolean fromPeripheral=packet.direction!=null&&packet.direction.startsWith("Peripheral");
		if(packet.llid==3&&(fromCentral||fromPeripheral)){Identity control=linkControl(packet,fromCentral);if(control!=null)out.add(control);}
		if(packet.l2capCid!=4||packet.l2capPdu==null||packet.l2capPdu.length<5||(!fromCentral&&!fromPeripheral))return out;
		byte[] data=packet.l2capPdu;int opcode=data[4]&0x3f;
		Server server=fromCentral?link.peripheral:link.central;
		if(isRequest(opcode)) {
			Request request=parseRequest(data,opcode,server);
			if(request!=null){if(fromCentral)link.responseFromPeripheral=request;else link.responseFromCentral=request;}
			if(opcode==0x12||opcode==0x52){int handle=u16(data,5);Identity update=server.value(handle,slice(data,7,data.length),"GATT write");if(update!=null)out.add(update);}
			return out;
		}
		if(opcode==0x1b||opcode==0x1d) {int handle=u16(data,5);Identity update=server.value(handle,slice(data,7,data.length),opcode==0x1b?"GATT notification":"GATT indication");if(update!=null)out.add(update);return out;}
		Request request=fromCentral?link.responseFromCentral:link.responseFromPeripheral;
		if(request==null)return out;
		if(fromCentral)link.responseFromCentral=null;else link.responseFromPeripheral=null;
		Identity update=response(data,opcode,request);if(update!=null)out.add(update);return out;
	}

	private static boolean isRequest(int opcode){return opcode==0x04||opcode==0x08||opcode==0x0a||opcode==0x0c
			||opcode==0x10||opcode==0x12||opcode==0x52;}
	private static Request parseRequest(byte[] data,int opcode,Server server) {
		if((opcode==0x04||opcode==0x0a||opcode==0x0c||opcode==0x12||opcode==0x52)&&data.length<7)return null;
		if((opcode==0x08||opcode==0x10)&&data.length<11)return null;
		int handle=(opcode==0x0a||opcode==0x0c||opcode==0x12||opcode==0x52)?u16(data,5):-1;
		int offset=opcode==0x0c&&data.length>=9?u16(data,7):0,uuid=-1;
		if(opcode==0x08||opcode==0x10)uuid=data.length==11?u16(data,9):-2;
		return new Request(opcode,handle,offset,uuid,server);
	}

	private static Identity response(byte[] data,int opcode,Request request) {
		if(opcode==0x01)return null;
		Server server=request.server;
		if(opcode==0x05&&request.opcode==0x04&&data.length>=6) {
			int format=data[5]&0xff,step=format==1?4:format==2?18:0;
			for(int at=6;step>0&&at+step<=data.length;at+=step){int handle=u16(data,at);server.handles.put(Integer.valueOf(handle),
					format==1?Uuid.of16(u16(data,at+2)):Uuid.of128(data,at+2));}
			return null;
		}
		if(opcode==0x09&&request.opcode==0x08&&data.length>=6) {
			int step=data[5]&0xff;if(step<3)return null;Identity latest=null;
			for(int at=6;at+step<=data.length;at+=step){int handle=u16(data,at);
				if(request.uuid==0x2803&&step>=7){int valueHandle=u16(data,at+3);Uuid uuid=step==7?Uuid.of16(u16(data,at+5)):Uuid.of128(data,at+5);server.handles.put(Integer.valueOf(valueHandle),uuid);}
				else if(request.uuid>=0){server.handles.put(Integer.valueOf(handle),Uuid.of16(request.uuid));Identity found=server.value(handle,slice(data,at+2,at+step),"GATT read by type");if(found!=null)latest=found;}
			}return latest;
		}
		if(opcode==0x11&&request.opcode==0x10&&data.length>=6) {
			int step=data[5]&0xff;if(step<6)return null;
			for(int at=6;at+step<=data.length;at+=step){Uuid uuid=step==6?Uuid.of16(u16(data,at+4)):Uuid.of128(data,at+4);server.addService(uuid);}
			return server.snapshot("GATT service discovery","Medium");
		}
		if(opcode==0x0b&&request.opcode==0x0a)return server.value(request.handle,slice(data,5,data.length),"GATT read");
		if(opcode==0x0d&&request.opcode==0x0c)return server.blob(request.handle,request.offset,slice(data,5,data.length));
		return null;
	}

	private static Identity linkControl(BluetoothLeDataPacket packet,boolean fromCentral) {
		if(packet.pdu.length<3)return null;int opcode=packet.pdu[2]&0xff;
		String address=fromCentral?packet.initiatorAddress:packet.advertiserAddress;if(!usable(address))return null;
		if(opcode==12&&packet.pdu.length>=8){int version=packet.pdu[3]&0xff,company=u16(packet.pdu,4),sub=u16(packet.pdu,6);String controller=BluetoothAssignedNumbers.company(company);
			Identity out=new Identity(address);out.confidence="Medium";
			out.bluetoothVersion=BluetoothAssignedNumbers.bluetoothVersion(version);
			out.details=String.format(Locale.US,"LL version 0x%02X; controller %s; subversion 0x%04X",version,controller,sub);
			out.evidence="LL controller "+controller;return out;}
		return null;
	}

	static final class Identity {
		final String address;String name="--",deviceType="--",company="--",services="--",model="--",bluetoothVersion="--",confidence="--",evidence="--",details="--";
		Identity(String address){this.address=address;}
	}

	private static final class Link {
		final Server central,peripheral;Request responseFromCentral,responseFromPeripheral;
		Link(String centralAddress,String peripheralAddress){central=new Server(centralAddress);peripheral=new Server(peripheralAddress);}
	}
	private static final class Request {
		final int opcode,handle,offset,uuid;final Server server;
		Request(int opcode,int handle,int offset,int uuid,Server server){this.opcode=opcode;this.handle=handle;this.offset=offset;this.uuid=uuid;this.server=server;}
	}
	private static final class Server {
		final String address;final Map<Integer,Uuid> handles=new LinkedHashMap<Integer,Uuid>();
		final Map<Integer,byte[]> blobs=new LinkedHashMap<Integer,byte[]>();final Set<String> services=new LinkedHashSet<String>();
		final Set<String> evidence=new LinkedHashSet<String>();String name="--",type="--",company="--",model="--",serial="--",firmware="--",hardware="--",software="--",systemId="--",pnp="--";
		Server(String address){this.address=address;}
		void addService(Uuid uuid){String service=uuid.name(true);services.add(service);if(uuid.uuid16>=0){String inferred=BluetoothAssignedNumbers.deviceTypeForService(uuid.uuid16);if(!"--".equals(inferred))type=inferred;if(uuid.uuid16==0x1812)evidence.add("HID service");}evidence.add("GATT services");}
		Identity value(int handle,byte[] value,String source){Uuid uuid=handles.get(Integer.valueOf(handle));if(uuid==null||uuid.uuid16<0)return null;
			int id=uuid.uuid16;String text=text(value);switch(id){case 0x2a00:name=text;evidence.add("GATT name");break;
			case 0x2a01:if(value.length>=2){int appearance=u16(value,0);type=BluetoothAssignedNumbers.appearanceType(appearance);evidence.add(String.format(Locale.US,"Appearance 0x%04X",appearance));}break;
			case 0x2a23:systemId=hex(value,16);evidence.add("System ID");break;case 0x2a24:model=text;evidence.add("Model");break;
			case 0x2a25:serial=text;evidence.add("Serial");break;case 0x2a26:firmware=text;evidence.add("Firmware");break;
			case 0x2a27:hardware=text;evidence.add("Hardware");break;case 0x2a28:software=text;evidence.add("Software");break;
			case 0x2a29:company=text;evidence.add("Manufacturer name");break;case 0x2a4a:case 0x2a4b:type="Human interface / remote";evidence.add(id==0x2a4a?"HID information":"HID report map");break;
			case 0x2a50:pnp=pnp(value);if(value.length>=7&&value[0]==1)company=BluetoothAssignedNumbers.company(u16(value,1));evidence.add("PnP ID");break;default:return null;}
			return snapshot(source,(id==0x2a00||id==0x2a01||id==0x2a24||id==0x2a29||id==0x2a50)?"High":"Medium");}
		Identity blob(int handle,int offset,byte[] value){if(offset==0)blobs.put(Integer.valueOf(handle),value);else {byte[] old=blobs.get(Integer.valueOf(handle));if(old==null||old.length!=offset)return null;byte[] joined=new byte[old.length+value.length];System.arraycopy(old,0,joined,0,old.length);System.arraycopy(value,0,joined,old.length,value.length);blobs.put(Integer.valueOf(handle),joined);}return value(handle,blobs.get(Integer.valueOf(handle)),"GATT blob read");}
		Identity snapshot(String source,String confidence){if(!usable(address))return null;Identity out=new Identity(address);out.name=name;out.deviceType=type;out.company=company;out.services=join(services);out.model=modelSummary();out.confidence=confidence;out.evidence=join(evidence);out.details=source+": "+out.evidence;return out;}
		String modelSummary(){List<String> values=new ArrayList<String>();add(values,"Model",model);add(values,"FW",firmware);add(values,"HW",hardware);add(values,"SW",software);add(values,"PnP",pnp);add(values,"System",systemId);add(values,"Serial",serial);return join(values);}
	}
	private static final class Uuid {final int uuid16;final String uuid128;private Uuid(int uuid16,String uuid128){this.uuid16=uuid16;this.uuid128=uuid128;}static Uuid of16(int value){return new Uuid(value,null);}static Uuid of128(byte[] data,int at){StringBuilder out=new StringBuilder();for(int n=15;n>=0;n--){out.append(String.format(Locale.US,"%02x",data[at+n]&0xff));if(n==12||n==10||n==8||n==6)out.append('-');}return new Uuid(-1,out.toString());}String name(boolean service){return uuid16>=0?(service?BluetoothAssignedNumbers.service(uuid16):BluetoothAssignedNumbers.characteristic(uuid16)):uuid128;}}

	private static String pnp(byte[] value){if(value.length<7)return hex(value,16);return String.format(Locale.US,"%s 0x%04X / product 0x%04X / version 0x%04X",value[0]==1?"Bluetooth SIG company":"USB vendor",u16(value,1),u16(value,3),u16(value,5));}
	private static String text(byte[] value){String out=new String(value,StandardCharsets.UTF_8).trim().replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]","?");return out.length()==0?"--":out;}
	private static byte[] slice(byte[] data,int from,int to){int end=Math.max(from,Math.min(to,data.length));byte[] out=new byte[end-from];System.arraycopy(data,from,out,0,out.length);return out;}
	private static int u16(byte[] data,int at){return at+1<data.length?(data[at]&0xff)|((data[at+1]&0xff)<<8):0;}
	private static String hex(byte[] data,int max){StringBuilder out=new StringBuilder();for(int n=0;n<data.length&&n<max;n++){if(n>0)out.append(' ');out.append(String.format(Locale.US,"%02X",data[n]&0xff));}if(data.length>max)out.append(" ...");return out.toString();}
	private static void add(List<String> out,String label,String value){if(value!=null&&!"--".equals(value))out.add(label+" "+value);}
	private static String join(Iterable<String> values){StringBuilder out=new StringBuilder();for(String value:values){if(value==null||value.length()==0||"--".equals(value))continue;if(out.length()>0)out.append(", ");out.append(value);}return out.length()==0?"--":out.toString();}
	private static boolean usable(String value){return value!=null&&!"--".equals(value)&&!"00:00:00:00:00:00".equals(value)&&!"FF:FF:FF:FF:FF:FF".equals(value);}
}
