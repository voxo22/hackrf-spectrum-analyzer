package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Connection state and Bluetooth Core CSA #1/#2 channel prediction for passively observed LE links. */
final class BluetoothLeConnectionTracker {
	private BluetoothLeConnectionTracker(){ }

	static int channelForEvent(long accessAddress,boolean csa2,int hop,long channelMap,int eventCounter){
		return csa2?csa2Channel(accessAddress,channelMap,eventCounter):csa1Channel(hop,channelMap,eventCounter);
	}

	static int csa1Channel(int hop,long channelMap,int eventCounter){
		int unmapped=(int)(((long)(eventCounter+1)*(hop&31))%37L);
		if(isUsed(channelMap,unmapped))return unmapped;int[] used=usedChannels(channelMap);
		return used.length==0?-1:used[unmapped%used.length];
	}

	static int csa2Channel(long accessAddress,long channelMap,int eventCounter){
		int identifier=(int)(((accessAddress>>>16)^accessAddress)&0xffffL);
		int prn=(eventCounter&0xffff)^identifier;
		for(int n=0;n<3;n++){prn=permute(prn);prn=(17*prn+identifier)&0xffff;}
		int prnE=prn^identifier,unmapped=prnE%37;if(isUsed(channelMap,unmapped))return unmapped;
		int[] used=usedChannels(channelMap);if(used.length==0)return-1;
		return used[(used.length*prnE)>>>16];
	}

	private static int permute(int value){return reverse8(value&0xff)|(reverse8((value>>>8)&0xff)<<8);}
	private static int reverse8(int value){int reversed=0;for(int n=0;n<8;n++)reversed=(reversed<<1)|((value>>>n)&1);return reversed;}
	private static boolean isUsed(long map,int channel){return channel>=0&&channel<37&&(map&(1L<<channel))!=0;}
	private static int[] usedChannels(long map){int count=Long.bitCount(map&0x1fffffffffL),at=0;int[] out=new int[count];
		for(int channel=0;channel<37;channel++)if(isUsed(map,channel))out[at++]=channel;return out;}

	static final class Context {
		final long accessAddress;final int crcInit,hop;final boolean csa2;final String initiator,advertiser;
		final long registeredMillis;long channelMap;int intervalUnits,latency,timeoutUnits;
		private int eventCounter=-1,lastChannel=-1;private long lastPacketMillis;private long packets;
		private long pendingChannelMap=-1L;private int pendingMapInstant=-1;
		private int pendingInterval=-1,pendingLatency,pendingTimeout,pendingConnectionInstant=-1;
		private String centralPhy="LE 1M",peripheralPhy="LE 1M",pendingCentralPhy="--",pendingPeripheralPhy="--";
		private int pendingPhyInstant=-1;
		private boolean encrypted,terminated;
		private int directionEvent=-1;private boolean nextFromCentral=true;
		private final BluetoothL2capReassembler l2cap=new BluetoothL2capReassembler();

		Context(BluetoothLePacket packet){BluetoothLePacket.ConnectionData data=packet.connectionData;
			accessAddress=data.accessAddress;crcInit=data.crcInit;channelMap=data.channelMap;hop=data.hop;
			csa2=data.channelSelection2;intervalUnits=data.interval;latency=data.latency;timeoutUnits=data.timeout;
			initiator=packet.peerAddress;advertiser=packet.address;registeredMillis=packet.decodedMillis;
		}

		boolean usesChannel(int channel){return !terminated&&isUsed(channelMap|(pendingChannelMap<0L?0L:pendingChannelMap),channel);}
		boolean usesPhy(String phy){if(terminated||phy==null)return false;return phy.equals(centralPhy)||phy.equals(peripheralPhy)
				||phy.equals(pendingCentralPhy)||phy.equals(pendingPeripheralPhy);}
		boolean terminated(){return terminated;}
		boolean observe(BluetoothLeDataPacket packet){
			int counter=matchEventCounter(packet.channel,packet.decodedMillis);int predicted=predictedChannel(counter);
			activatePending(counter);boolean match=predicted==packet.channel;
			eventCounter=counter;lastChannel=packet.channel;lastPacketMillis=packet.decodedMillis;packets++;
			packet.connectionEvent=counter;packet.predictedChannel=predicted;packet.channelPredictionMatches=match;
			boolean fromCentral=inferDirection(counter),complete=false;
			boolean wasEncrypted=encrypted;if(wasEncrypted&&packet.payloadLength>0){packet.direction=fromCentral?
					"Central -> Peripheral (inferred)":"Peripheral -> Central (inferred)";packet.markEncrypted();}
			else {applyControl(packet);complete=l2cap.accept(packet,fromCentral);}
			packet.trackingInfo=String.format(Locale.US,"event ~%d, CSA#%d, expected ch %d%s, interval %.2f ms%s",
					counter,csa2?2:1,predicted,match?"":" (observed "+packet.channel+")",intervalUnits*1.25d,
					encrypted?", encrypted":"");
			return complete;
		}
		private boolean inferDirection(int counter){if(directionEvent!=counter){directionEvent=counter;nextFromCentral=false;return true;}
			boolean result=nextFromCentral;nextFromCentral=!nextFromCentral;return result;}

		private int matchEventCounter(int observedChannel,long timeMillis){
			int estimate;if(eventCounter<0){double interval=Math.max(1.25d,intervalUnits*1.25d);
				estimate=(int)Math.max(0L,Math.round(Math.max(0L,timeMillis-registeredMillis)/interval));}
			else {double interval=Math.max(1.25d,intervalUnits*1.25d);long elapsed=Math.max(0L,timeMillis-lastPacketMillis);
				int advance=elapsed<interval*.55d&&observedChannel==lastChannel?0:Math.max(1,(int)Math.round(elapsed/interval));
				estimate=(eventCounter+advance)&0xffff;}
			if(predictedChannel(estimate)==observedChannel)return estimate;
			for(int distance=1;distance<=512;distance++){
				int forward=(estimate+distance)&0xffff;
				if(predictedChannel(forward)==observedChannel)return forward;
				if(eventCounter<0&&estimate>=distance){int backward=estimate-distance;
					if(predictedChannel(backward)==observedChannel)return backward;}
			}
			return estimate;
		}
		private int predictedChannel(int counter){long map=pendingMapInstant>=0&&instantReached(counter,pendingMapInstant)
				?pendingChannelMap:channelMap;return channelForEvent(accessAddress,csa2,hop,map,counter);}

		private void activatePending(int counter){
			if(pendingMapInstant>=0&&instantReached(counter,pendingMapInstant)){channelMap=pendingChannelMap;
				pendingChannelMap=-1L;pendingMapInstant=-1;}
			if(pendingConnectionInstant>=0&&instantReached(counter,pendingConnectionInstant)){intervalUnits=pendingInterval;
				latency=pendingLatency;timeoutUnits=pendingTimeout;pendingInterval=-1;pendingConnectionInstant=-1;}
			if(pendingPhyInstant>=0&&instantReached(counter,pendingPhyInstant)){centralPhy=pendingCentralPhy;
				peripheralPhy=pendingPeripheralPhy;pendingPhyInstant=-1;pendingCentralPhy=pendingPeripheralPhy="--";}
		}
		private static boolean instantReached(int current,int instant){return ((current-instant)&0xffff)<0x8000;}

		private void applyControl(BluetoothLeDataPacket packet){if(packet.llid!=3||packet.payloadLength<1)return;
			byte[] pdu=packet.pdu;int opcode=pdu[2]&0xff;
			if(opcode==0&&packet.payloadLength>=12&&pdu.length>=14){pendingInterval=u16(pdu,6);pendingLatency=u16(pdu,8);
				pendingTimeout=u16(pdu,10);pendingConnectionInstant=u16(pdu,12);}
			else if(opcode==1&&packet.payloadLength>=8&&pdu.length>=10){long map=0L;for(int n=0;n<5;n++)map|=(long)(pdu[3+n]&0xff)<<(8*n);
				if(Long.bitCount(map&0x1fffffffffL)>=2){pendingChannelMap=map&0x1fffffffffL;pendingMapInstant=u16(pdu,8);}}
			else if(opcode==2)terminated=true;
			else if(opcode==6)encrypted=true;
			else if(opcode==11)encrypted=false;
			else if(opcode==24&&packet.payloadLength>=5&&pdu.length>=7){pendingCentralPhy=phyName(pdu[3]&0xff,centralPhy);
				pendingPeripheralPhy=phyName(pdu[4]&0xff,peripheralPhy);
				pendingPhyInstant=u16(pdu,5);}
		}
		private static int u16(byte[] data,int at){return(data[at]&0xff)|((data[at+1]&0xff)<<8);}
		private static String phyName(int value){return value==1?"LE 1M":value==2?"LE 2M":value==3?"LE Coded":"unchanged";}
		private static String phyName(int value,String current){return value==0?current:phyName(value);}

		String summary(){List<String> values=new ArrayList<String>();values.add(String.format(Locale.US,"AA 0x%08X",accessAddress));
			values.add("CSA#"+(csa2?2:1));values.add(Long.bitCount(channelMap&0x1fffffffffL)+" channels");
			values.add(String.format(Locale.US,"%.2f ms",intervalUnits*1.25d));if(eventCounter>=0)values.add("event ~"+eventCounter);
			if(pendingMapInstant>=0)values.add("map update @"+pendingMapInstant);
			if(pendingConnectionInstant>=0)values.add("connection update @"+pendingConnectionInstant);
			if(!"LE 1M".equals(centralPhy)||!"LE 1M".equals(peripheralPhy))values.add("PHY "+centralPhy+" / "+peripheralPhy);
			if(pendingPhyInstant>=0)values.add("PHY "+pendingCentralPhy+" / "+pendingPeripheralPhy+" @"+pendingPhyInstant);
			if(encrypted)values.add("encrypted");if(terminated)values.add("terminated");return join(values);}
		private static String join(List<String> values){StringBuilder out=new StringBuilder();for(String value:values){if(out.length()>0)out.append(", ");out.append(value);}return out.toString();}
	}
}
