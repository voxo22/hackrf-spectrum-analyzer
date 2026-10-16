package jspectrumanalyzer.iq;

import java.util.Arrays;
import java.util.Locale;

/** Reassembles clear-text L2CAP PDUs independently in both inferred LE link directions. */
final class BluetoothL2capReassembler {
	private static final int BASIC_HEADER_BYTES=4,MAX_PDU_BYTES=65539;
	private final Stream centralToPeripheral=new Stream();
	private final Stream peripheralToCentral=new Stream();

	boolean accept(BluetoothLeDataPacket packet,boolean fromCentral){
		packet.direction=fromCentral?"Central -> Peripheral (inferred)":"Peripheral -> Central (inferred)";
		if(packet.llid!=1&&packet.llid!=2)return false;
		return (fromCentral?centralToPeripheral:peripheralToCentral).accept(packet);
	}

	private static final class Stream {
		private byte[] buffer;private int size,expected,fragments,lastSn=-1,lastHash;

		boolean accept(BluetoothLeDataPacket packet){
			int length=packet.payloadLength;if(length==0){packet.l2capStatus="Empty LL acknowledgement";return false;}
			int hash=Arrays.hashCode(packet.pdu),sn=packet.sn?1:0;
			if(lastSn==sn&&lastHash==hash){packet.retransmission=true;packet.l2capStatus="Retransmission ignored";
				packet.details="Retransmitted LL data; not added to L2CAP";return false;}
			lastSn=sn;lastHash=hash;
			if(packet.llid==2)return start(packet);
			return continuePdu(packet);
		}

		private boolean start(BluetoothLeDataPacket packet){
			if(packet.payloadLength<BASIC_HEADER_BYTES){reset();packet.l2capStatus="Invalid L2CAP start";
				packet.details="L2CAP start is shorter than its 4-byte basic header";return false;}
			int l2capLength=u16(packet.pdu,2),total=BASIC_HEADER_BYTES+l2capLength;
			if(total>BASIC_HEADER_BYTES+0xffff||total>MAX_PDU_BYTES){reset();packet.l2capStatus="Invalid L2CAP length";return false;}
			buffer=new byte[total];expected=total;size=0;fragments=1;
			return append(packet,2,packet.payloadLength);
		}

		private boolean continuePdu(BluetoothLeDataPacket packet){
			if(buffer==null){packet.l2capStatus="Orphan continuation";
				packet.details="LL continuation without a captured L2CAP start";return false;}
			fragments++;return append(packet,2,packet.payloadLength);
		}

		private boolean append(BluetoothLeDataPacket packet,int offset,int length){
			if(length>expected-size){packet.l2capStatus="L2CAP overflow";
				packet.details="LL fragments exceed the announced L2CAP length";reset();return false;}
			System.arraycopy(packet.pdu,offset,buffer,size,length);size+=length;
			if(size<expected){packet.l2capStatus=String.format(Locale.US,"L2CAP fragment %d: %d/%d bytes",fragments,size,expected);
				packet.packetType="L2CAP fragment";packet.details=packet.l2capStatus;return false;}
			int cid=u16(buffer,2),payloadLength=u16(buffer,0);packet.l2capPdu=buffer;packet.l2capCid=cid;
			packet.l2capLength=payloadLength;packet.l2capFragments=fragments;
			String protocol=BluetoothLeDataPacket.cidName(cid);packet.packetType="L2CAP "+protocol;
			packet.l2capStatus=String.format(Locale.US,"Complete L2CAP %s: %d-byte payload in %d LL fragment(s)",
					protocol,payloadLength,fragments);packet.details=String.format(Locale.US,
					"L2CAP length %d, CID 0x%04X (%s), %d LL fragment(s)",payloadLength,cid,protocol,fragments);
			if(cid==4&&payloadLength>0)packet.details+=", "+BluetoothLeDataPacket.attName(buffer[4]&0xff);
			reset();return true;
		}

		private void reset(){buffer=null;size=expected=fragments=0;}
		private static int u16(byte[] data,int at){return(data[at]&0xff)|((data[at+1]&0xff)<<8);}
	}
}
