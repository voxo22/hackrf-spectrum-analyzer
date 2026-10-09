package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * LoRa CSS physical-layer receiver.
 *
 * <p>The synchronizer follows the usual down-chirp/dechirp FFT receiver.  It
 * intentionally requires a repeated preamble and later a valid explicit PHY
 * header before a signal may be presented as decoded LoRa.</p>
 */
final class LoRaPhyDecoder {
	private static final int[] BANDWIDTHS={125_000,250_000};
	private static final int MIN_SF=7,MAX_SF=12;
	private static final double MIN_PEAK_RATIO=5.0d;

	List<Decoded> decodeAll(float[] iq,double sampleRateHz){
		return decodeAll(iq,sampleRateHz,findLocks(iq,sampleRateHz));
	}

	List<Decoded> decodeAll(float[] iq,double sampleRateHz,List<Lock> locks){
		List<Decoded> decoded=new ArrayList<Decoded>();
		for(Lock lock:locks){
			Decoded value=decode(iq,sampleRateHz,lock);
			if(value!=null&&value.headerValid)decoded.add(value);
		}
		return decoded;
	}

	private static Decoded decode(float[] input,double inputRate,Lock lock){
		float[] iq=resampleForDetection(input,inputRate,lock.bandwidthHz*2d);
		int sf=lock.spreadingFactor,chips=1<<sf,size=chips*2,samples=iq.length/2;
		double[] downI=new double[size],downQ=new double[size],upI=new double[size],upQ=new double[size];
		chirpReference(downI,downQ,lock.sampleRateHz,lock.bandwidthHz,sf,true);
		chirpReference(upI,upQ,lock.sampleRateHz,lock.bandwidthHz,sf,false);
		int signedBin=lock.preambleBin>size/2?lock.preambleBin-size:lock.preambleBin;
		int aligned=Math.max(0,lock.startSample-2*signedBin),sfd=-1;
		for(int n=4;n<24;n++){int at=aligned+n*size;if(at+size>samples)break;
			Peak up=dechirp(iq,at,downI,downQ),down=dechirp(iq,at,upI,upQ);
			if(down.ratio>12d&&down.ratio>up.ratio*2d){sfd=at;break;}
		}
		if(sfd<0)return null;
		int dataStart=(int)Math.round(sfd+2.25d*size);
		if(dataStart<0||dataStart+8*size>samples)return null;
		boolean ldro=chips/(double)lock.bandwidthHz>0.016d;
		int[] first=demodulate(iq,dataStart,8,size,chips,downI,downQ);
		if(ldro)compensateLowRate(first,chips);
		Header header=decodeHeader(first,sf);
		if(!header.valid||header.length<0||header.length>255||header.codingRate<1||header.codingRate>4)return null;
		int denominator=sf-(ldro?2:0);
		int numerator=2*header.length-sf+7+(header.hasCrc?4:0);
		int symbolCount=8+(4+header.codingRate)*Math.max(0,(numerator+denominator-1)/denominator);
		if(dataStart+symbolCount*size>samples)return null;
		int[] measured=demodulate(iq,dataStart,symbolCount,size,chips,downI,downQ);
		if(ldro)compensateLowRate(measured,chips);
		byte[] bytes=decodePayload(measured,sf,ldro,header);
		if(bytes==null)return null;
		byte[] payload=new byte[header.length];System.arraycopy(bytes,0,payload,0,payload.length);
		int received=-1,calculated=-1;
		boolean crcValid=!header.hasCrc;
		if(header.hasCrc&&bytes.length>=header.length+2){
			received=(bytes[header.length]&255)|((bytes[header.length+1]&255)<<8);
			calculated=sx1272Checksum(payload);crcValid=received==calculated;
		}
		int netId1=symbolAt(iq,sfd-2*size,size,chips,downI,downQ);
		int netId2=symbolAt(iq,sfd-size,size,chips,downI,downQ);
		return new Decoded(lock.bandwidthHz,sf,header.codingRate,header.hasCrc,header.valid,
				crcValid,payload,received,calculated,netId1,netId2,dataStart/lock.sampleRateHz);
	}

	private static void compensateLowRate(int[] symbols,int modulus){
		int offset=0,last=1;
		for(int n=0;n<symbols.length;n++){int value=symbols[n];int delta=(value-last)%4;if(delta<0)delta+=4;
			if(delta<2)offset-=delta;else offset+=4-delta;
			last=value;int corrected=(value+offset)%modulus;if(corrected<0)corrected+=modulus;symbols[n]=corrected;
		}
	}

	private static int symbolAt(float[] iq,int at,int size,int chips,double[] ri,double[] rq){
		if(at<0||at+size>iq.length/2)return -1;return dechirp(iq,at,ri,rq).bin%chips;}

	private static int[] demodulate(float[] iq,int first,int count,int size,int chips,
			double[] ri,double[] rq){
		int[] result=new int[count];for(int n=0;n<count;n++)result[n]=symbolAt(iq,first+n*size,size,chips,ri,rq);
		return result;
	}

	private static byte[] decodePayload(int[] measured,int sf,boolean ldro,Header header){
		int[] gray=new int[measured.length];
		for(int n=0;n<measured.length;n++){
			int reduced=n<8||ldro?measured[n]/4:(measured[n]-1+(1<<sf))%(1<<sf);
			gray[n]=reduced^(reduced>>>1);
		}
		int[] headerWords=deinterleave(java.util.Arrays.copyOfRange(gray,0,8),sf-2);
		int[] headerNibbles=hammingDecode(headerWords,8);
		List<Integer> nibbles=new ArrayList<Integer>();
		for(int n=5;n<headerNibbles.length;n++)nibbles.add(Integer.valueOf(headerNibbles[n]));
		int rdd=header.codingRate+4,ppm=sf-(ldro?2:0);
		for(int at=8;at+rdd<=gray.length;at+=rdd){
			int[] words=deinterleave(java.util.Arrays.copyOfRange(gray,at,at+rdd),ppm);
			int[] next=hammingDecode(words,rdd);for(int value:next)nibbles.add(Integer.valueOf(value));
		}
		int wanted=header.length+(header.hasCrc?2:0);
		if(nibbles.size()<wanted*2)return null;
		byte[] bytes=new byte[wanted];for(int n=0;n<wanted;n++)
			bytes[n]=(byte)(nibbles.get(n*2).intValue()|(nibbles.get(n*2+1).intValue()<<4));
		/* SX127x whitening continues over the optional PHY CRC bytes.  Keeping
		 * the LFSR running here is also a useful end-to-end discriminator: an
		 * apparent header lock cannot accidentally manufacture a valid CRC. */
		int reg=0xff;for(int n=0;n<bytes.length;n++){
			bytes[n]=(byte)((bytes[n]&255)^reg);
			int feedback=((reg>>>7)^(reg>>>5)^(reg>>>4)^(reg>>>3))&1;reg=((reg<<1)&255)|feedback;
		}
		return bytes;
	}

	private static int sx1272Checksum(byte[] data){
		int result=0,value=0xff;
		for(byte datum:data){int crc=result;
			for(int bit=0;bit<8;bit++)crc=(crc&0x8000)!=0?((crc<<1)^0x1021)&0xffff:(crc<<1)&0xffff;
			value=parity8(value&0xb8)|(value<<1)&255;result=crc^(datum&255);
		}
		result^=value;value=parity8(value&0xb8)|(value<<1)&255;result^=value<<8;return result&0xffff;
	}

	private static int parity8(int value){value^=value>>>4;value^=value>>>2;value^=value>>>1;return value&1;}

	List<Lock> findLocks(float[] iq,double sampleRateHz){
		if(iq==null||iq.length<512||sampleRateHz<=0d)return Collections.emptyList();
		List<Lock> locks=new ArrayList<Lock>();
		for(int bandwidth:BANDWIDTHS){
			if(sampleRateHz+1d<bandwidth*2d)continue;
			float[] working=resampleForDetection(iq,sampleRateHz,bandwidth*2d);
			double rate=bandwidth*2d;
			for(int sf=MIN_SF;sf<=MAX_SF;sf++)locks.addAll(findLocks(working,rate,bandwidth,sf,false));
		}
		Collections.sort(locks,new Comparator<Lock>(){
			@Override public int compare(Lock a,Lock b){return Double.compare(b.quality,a.quality);}});
		return suppressDuplicates(locks);
	}

	String trace(float[] iq,double sampleRateHz,Lock lock){
		float[] working=resampleForDetection(iq,sampleRateHz,lock.bandwidthHz*2d);
		int size=(1<<lock.spreadingFactor)*2;
		double[] downI=new double[size],downQ=new double[size],upI=new double[size],upQ=new double[size];
		chirpReference(downI,downQ,lock.sampleRateHz,lock.bandwidthHz,lock.spreadingFactor,true);
		chirpReference(upI,upQ,lock.sampleRateHz,lock.bandwidthHz,lock.spreadingFactor,false);
		int signedBin=lock.preambleBin>size/2?lock.preambleBin-size:lock.preambleBin;
		int aligned=Math.max(0,lock.startSample-2*signedBin);
		StringBuilder out=new StringBuilder(String.format(Locale.US,"\n    coarse aligned %.3f ms",aligned*1000d/lock.sampleRateHz));
		for(int n=0;n<18;n++){int at=lock.startSample+n*size;if(at+size>working.length/2)break;
			Peak up=dechirp(working,at,downI,downQ),down=dechirp(working,at,upI,upQ);
			out.append(String.format(Locale.US,"\n    %2d @ %.3f ms: up %4d/%5.1f, down %4d/%5.1f",
					n,at*1000d/lock.sampleRateHz,up.bin,up.ratio,down.bin,down.ratio));
		}
		out.append("\n    aligned grid:");
		for(int n=0;n<15;n++){int at=aligned+n*size;if(at+size>working.length/2)break;
			Peak up=dechirp(working,at,downI,downQ),down=dechirp(working,at,upI,upQ);
			out.append(String.format(Locale.US,"\n    %2d @ %.3f ms: up %4d/%5.1f, down %4d/%5.1f",
					n,at*1000d/lock.sampleRateHz,up.bin,up.ratio,down.bin,down.ratio));
		}
		int dataStart=(int)Math.round(aligned+12.25d*size);
		out.append(String.format(Locale.US,"\n    data start %.3f ms, symbols:",dataStart*1000d/lock.sampleRateHz));
		int[] symbols=new int[12];
		for(int n=0;n<12;n++){int at=dataStart+n*size;if(at+size>working.length/2)break;
			Peak up=dechirp(working,at,downI,downQ);
			symbols[n]=up.bin%(1<<lock.spreadingFactor);
			out.append(String.format(Locale.US," %d/%1.0f",up.bin,up.ratio));
		}
		Header header=decodeHeader(symbols,lock.spreadingFactor);
		out.append("\n    header: ").append(header);
		return out.toString();
	}

	private static Header decodeHeader(int[] measured,int sf){
		if(measured.length<8)return Header.INVALID;
		int[] gray=new int[8];
		for(int n=0;n<8;n++){int reduced=measured[n]/4;gray[n]=reduced^(reduced>>>1);}
		int[] codewords=deinterleave(gray,sf-2);
		int[] nibbles=hammingDecode(codewords,8);
		if(nibbles.length<5)return Header.INVALID;
		int length=nibbles[0]*16+nibbles[1],crc=nibbles[2]&1,cr=nibbles[2]>>>1;
		int[] bits=new int[12];int at=0;
		for(int n=0;n<3;n++)for(int bit=3;bit>=0;bit--)bits[at++]=(nibbles[n]>>>bit)&1;
		int[][] matrix={{1,1,1,1,0,0,0,0,0,0,0,0},{1,0,0,0,1,1,1,0,0,0,0,1},
				{0,1,0,0,1,0,0,1,1,0,1,0},{0,0,1,0,0,1,0,1,0,1,1,1},
				{0,0,0,1,0,0,1,0,1,1,1,1}};
		int checksum=0;for(int row=0;row<5;row++){int value=0;for(int col=0;col<12;col++)value^=matrix[row][col]&bits[col];
			checksum=(checksum<<1)|value;}
		int received=((nibbles[3]&1)<<4)|(nibbles[4]&15);
		return new Header(length,cr,crc!=0,checksum==received,received,checksum,nibbles);
	}

	private static int[] deinterleave(int[] symbols,int ppm){
		int rows=symbols.length;int[] result=new int[ppm];
		for(int col=0;col<ppm;col++){int word=0;
			for(int row=0;row<rows;row++){
				int originalCol=(col+row)%ppm;
				int bit=(symbols[row]>>>(ppm-1-originalCol))&1;
				word|=bit<<row;
			}
			result[ppm-1-col]=word;
		}
		return result;
	}

	private static int[] hammingDecode(int[] codewords,int rdd){
		int[] result=new int[codewords.length];
		for(int n=0;n<codewords.length;n++){int word=codewords[n];
			if(rdd>=7){int p2=parity(word,7,4,2,1),p3=parity(word,5,3,2,1),p5=parity(word,6,4,3,2);
				int syndrome=p2*4+p3*2+p5;
				if(syndrome==3)word^=4;else if(syndrome==5)word^=8;
				else if(syndrome==6)word^=1;else if(syndrome==7)word^=2;
			}
			result[n]=word&15;
		}
		return result;
	}

	private static int parity(int word,int...positions){int value=0;
		for(int position:positions)value^=(word>>>(position-1))&1;return value;}

	private static List<Lock> findLocks(float[] iq,double rate,int bandwidth,int sf,boolean inverted){
		int chips=1<<sf,symbolSamples=chips*2,samples=iq.length/2;
		if(samples<symbolSamples*7)return Collections.emptyList();
		double[] refI=new double[symbolSamples],refQ=new double[symbolSamples];
		chirpReference(refI,refQ,rate,bandwidth,sf,!inverted);
		List<Lock> result=new ArrayList<Lock>();
		int consecutive=0,previousBin=-1,runStart=0;
		for(int at=0;at+symbolSamples<=samples;at+=symbolSamples){
			Peak peak=dechirp(iq,at,refI,refQ);
			boolean strong=peak.ratio>=MIN_PEAK_RATIO;
			if(strong&&previousBin>=0&&circularDistance(peak.bin,previousBin,symbolSamples)<=2){
				consecutive++;
			}else{consecutive=strong?1:0;runStart=at;}
			previousBin=strong?peak.bin:-1;
			if(consecutive>=5){
				result.add(new Lock(bandwidth,sf,rate,runStart,peak.bin,peak.ratio,inverted));
				consecutive=0;previousBin=-1;
				at+=symbolSamples*5;
			}
		}
		return result;
	}

	private static List<Lock> suppressDuplicates(List<Lock> input){
		List<Lock> output=new ArrayList<Lock>();
		for(Lock next:input){boolean duplicate=false;
			for(Lock old:output)if(old.bandwidthHz==next.bandwidthHz&&old.spreadingFactor==next.spreadingFactor
					&&Math.abs(old.startSample-next.startSample)<(1<<next.spreadingFactor)*8){duplicate=true;break;}
			if(!duplicate)output.add(next);
		}
		return output;
	}

	private static float[] resampleForDetection(float[] iq,double inputRate,double outputRate){
		int divisor=(int)Math.round(inputRate/outputRate);
		if(divisor<=1||Math.abs(inputRate/divisor-outputRate)>outputRate*.001d)return iq;
		int inSamples=iq.length/2,outSamples=inSamples/divisor;
		float[] out=new float[outSamples*2];
		/* The arithmetic average is a deliberately small anti-alias filter.  The
		 * channelizer already supplies a band-limited stream in live operation;
		 * this path mainly handles full-rate replay WAV files. */
		for(int n=0;n<outSamples;n++){double si=0d,sq=0d;int first=n*divisor;
			for(int k=0;k<divisor;k++){si+=iq[(first+k)*2];sq+=iq[(first+k)*2+1];}
			out[n*2]=(float)(si/divisor);out[n*2+1]=(float)(sq/divisor);
		}
		return out;
	}

	private static void chirpReference(double[] ri,double[] rq,double rate,int bandwidth,int sf,
			boolean down){
		int chips=1<<sf;double duration=chips/(double)bandwidth;
		double slope=(down?-1d:1d)*bandwidth/duration;
		double start=down?bandwidth/2d:-bandwidth/2d;
		for(int n=0;n<ri.length;n++){double t=n/rate;
			double phase=2d*Math.PI*(start*t+.5d*slope*t*t);
			ri[n]=Math.cos(phase);rq[n]=Math.sin(phase);
		}
	}

	private static Peak dechirp(float[] iq,int first,double[] refI,double[] refQ){
		int size=refI.length;double[] real=new double[size],imag=new double[size];
		for(int n=0;n<size;n++){double i=iq[(first+n)*2],q=iq[(first+n)*2+1];
			real[n]=i*refI[n]-q*refQ[n];imag[n]=i*refQ[n]+q*refI[n];}
		fft(real,imag);int best=0;double bestPower=-1d,sum=0d;
		for(int n=0;n<size;n++){double power=real[n]*real[n]+imag[n]*imag[n];sum+=power;
			if(power>bestPower){bestPower=power;best=n;}}
		double rest=Math.max(1e-12d,(sum-bestPower)/Math.max(1,size-1));
		return new Peak(best,Math.sqrt(bestPower/rest));
	}

	private static int circularDistance(int a,int b,int size){int d=Math.abs(a-b);return Math.min(d,size-d);}

	private static void fft(double[] real,double[] imag){
		int n=real.length;
		for(int i=1,j=0;i<n;i++){int bit=n>>1;for(;j>=bit;bit>>=1)j-=bit;j+=bit;
			if(i<j){double t=real[i];real[i]=real[j];real[j]=t;t=imag[i];imag[i]=imag[j];imag[j]=t;}}
		for(int length=2;length<=n;length<<=1){double angle=-2d*Math.PI/length,wr0=Math.cos(angle),wi0=Math.sin(angle);
			for(int first=0;first<n;first+=length){double wr=1d,wi=0d;int half=length>>1;
				for(int k=0;k<half;k++){int even=first+k,odd=even+half;
					double tr=wr*real[odd]-wi*imag[odd],ti=wr*imag[odd]+wi*real[odd];
					real[odd]=real[even]-tr;imag[odd]=imag[even]-ti;real[even]+=tr;imag[even]+=ti;
					double next=wr*wr0-wi*wi0;wi=wr*wi0+wi*wr0;wr=next;}
			}
		}
	}

	static final class Lock{
		final int bandwidthHz,spreadingFactor;final double sampleRateHz;
		final int startSample,preambleBin;final double quality;final boolean inverted;
		Lock(int bandwidthHz,int spreadingFactor,double sampleRateHz,int startSample,int preambleBin,
				double quality,boolean inverted){this.bandwidthHz=bandwidthHz;this.spreadingFactor=spreadingFactor;
			this.sampleRateHz=sampleRateHz;this.startSample=startSample;this.preambleBin=preambleBin;
			this.quality=quality;this.inverted=inverted;}
		@Override public String toString(){return String.format(Locale.US,
				"BW %d kHz, SF%d, start %.3f ms, bin %d, peak %.1f",
				bandwidthHz/1000,spreadingFactor,startSample*1000d/sampleRateHz,preambleBin,quality);}
	}
	static final class Decoded{
		final int bandwidthHz,spreadingFactor,codingRate,receivedCrc,calculatedCrc,netId1,netId2;
		final boolean hasCrc,headerValid,crcValid;final byte[] payload;final double startSeconds;
		Decoded(int bandwidthHz,int spreadingFactor,int codingRate,boolean hasCrc,boolean headerValid,
				boolean crcValid,byte[] payload,int receivedCrc,int calculatedCrc,int netId1,int netId2,
				double startSeconds){this.bandwidthHz=bandwidthHz;this.spreadingFactor=spreadingFactor;
			this.codingRate=codingRate;this.hasCrc=hasCrc;this.headerValid=headerValid;this.crcValid=crcValid;
			this.payload=payload;this.receivedCrc=receivedCrc;this.calculatedCrc=calculatedCrc;
			this.netId1=netId1;this.netId2=netId2;this.startSeconds=startSeconds;}
		String payloadHex(){StringBuilder out=new StringBuilder(payload.length*2);for(byte value:payload)
			out.append(String.format(Locale.US,"%02x",value&255));return out.toString();}
		boolean isLoRaWan(){return payload.length>0&&(payload[0]&3)==0;}
		String protocol(){return isLoRaWan()?"LoRaWAN":"LoRa PHY";}
		String deviceId(){
			if(!isLoRaWan())return "";int type=(payload[0]>>>5)&7;
			if(type>=2&&type<=5&&payload.length>=8)return String.format(Locale.US,"%08X",
					(payload[1]&255)|((payload[2]&255)<<8)|((payload[3]&255)<<16)|((payload[4]&255)<<24));
			if(type==0&&payload.length>=19){StringBuilder out=new StringBuilder(16);
				for(int n=16;n>=9;n--)out.append(String.format(Locale.US,"%02X",payload[n]&255));return out.toString();}
			return "";
		}
		String security(){int type=payload.length==0?-1:(payload[0]>>>5)&7;
			return type>=2&&type<=5?"encrypted (session keys required)":type==1?"encrypted":"clear / protocol dependent";}
		String summary(){
			if(!isLoRaWan())return "PHY payload "+payload.length+" bytes";
			int type=(payload[0]>>>5)&7;String[] names={"Join-request","Join-accept","Unconfirmed uplink",
					"Unconfirmed downlink","Confirmed uplink","Confirmed downlink","RFU","Proprietary"};
			StringBuilder out=new StringBuilder(names[type]);
			if(type>=2&&type<=5&&payload.length>=12){int fctrl=payload[5]&255,options=fctrl&15;
				int counter=(payload[6]&255)|((payload[7]&255)<<8);out.append("; DevAddr=").append(deviceId())
					.append("; FCnt=").append(counter).append("; FCtrl=").append(String.format(Locale.US,"0x%02X",fctrl));
				int portAt=8+options;if(portAt<payload.length-4)out.append("; FPort=").append(payload[portAt]&255);
				if(options>0)out.append("; FOpts=").append(options).append(" bytes");
				out.append("; MIC present (not verified without session keys)");
			}
			return out.toString();
		}
		@Override public String toString(){return String.format(Locale.US,
				"LoRa BW %d kHz SF%d CR 4/%d, sync %02x/%02x, header OK, payload CRC %s (%04x/%04x), %d bytes: %s",
				bandwidthHz/1000,spreadingFactor,codingRate+4,netId1,netId2,hasCrc?(crcValid?"OK":"FAIL"):"not present",
				receivedCrc,calculatedCrc,payload.length,payloadHex());}
	}
	private static final class Header{
		static final Header INVALID=new Header(0,0,false,false,0,0,new int[0]);
		final int length,codingRate,receivedChecksum,calculatedChecksum;final boolean hasCrc,valid;final int[] nibbles;
		Header(int length,int codingRate,boolean hasCrc,boolean valid,int receivedChecksum,
				int calculatedChecksum,int[] nibbles){this.length=length;this.codingRate=codingRate;
			this.hasCrc=hasCrc;this.valid=valid;this.receivedChecksum=receivedChecksum;
			this.calculatedChecksum=calculatedChecksum;this.nibbles=nibbles;}
		@Override public String toString(){return String.format(Locale.US,
				"len %d, CR 4/%d, CRC %s, checksum %02x/%02x %s, nibbles %s",length,codingRate+4,
				hasCrc,receivedChecksum,calculatedChecksum,valid?"OK":"FAIL",java.util.Arrays.toString(nibbles));}
	}
	private static final class Peak{final int bin;final double ratio;Peak(int bin,double ratio){this.bin=bin;this.ratio=ratio;}}
}
