package jspectrumanalyzer.iq;

import java.util.Random;

public final class WifiSignalAnalyzerTest {
	private static final int[] DATA_CARRIERS = {
			-26,-25,-24,-23,-22, -20,-19,-18,-17,-16,-15,-14,-13,-12,-11,-10,-9,-8,
			-6,-5,-4,-3,-2,-1, 1,2,3,4,5,6, 8,9,10,11,12,13,14,15,16,17,18,19,20,
			22,23,24,25,26
	};
	private static final int[] LONG_TRAINING = {
			1,1,-1,-1,1,1,-1,1,-1,1,1,1,1,1,1,-1,-1,1,1,-1,1,-1,1,1,1,1,
			0,
			1,-1,-1,1,1,-1,1,-1,1,-1,-1,-1,-1,-1,1,1,-1,-1,1,-1,1,-1,1,1,1,1
	};

	private WifiSignalAnalyzerTest() { }

	public static void main(String[] args) {
		byte[] iq = createPacket(0xb, 321, 73_000d, .002d);
		WifiSignalAnalyzer analyzer = new WifiSignalAnalyzer();
		/* Split inside the preamble to verify streaming overlap handling. */
		WifiSignalAnalyzer.Result first = analyzer.analyze(iq, 470, WifiSignalAnalyzer.SAMPLE_RATE_HZ);
		assertTrue(!first.lSigValid, "partial packet must not decode");
		byte[] secondBlock = new byte[iq.length - 470];
		System.arraycopy(iq, 470, secondBlock, 0, secondBlock.length);
		WifiSignalAnalyzer.Result result = analyzer.analyze(secondBlock, secondBlock.length,
				WifiSignalAnalyzer.SAMPLE_RATE_HZ);
		assertTrue(result.detected, "Wi-Fi preamble not detected: " + result.state);
		assertTrue(result.lSigValid, "L-SIG not decoded: " + result.state);
		assertTrue("6 Mb/s".equals(result.rate), "rate " + result.rate);
		assertTrue(result.lengthBytes == 321, "length " + result.lengthBytes);
		assertTrue(Math.abs(result.cfoHz - 73_000d) < 4_000d, "CFO " + result.cfoHz);
		assertTrue(result.ltfCorrelation > .8d, "LTF correlation " + result.ltfCorrelation);
		assertTrue(result.decodedPackets == 1, "decoded count " + result.decodedPackets);
		int[] codes = {0xb, 0xf, 0xa, 0xe, 0x9, 0xd, 0x8, 0xc};
		String[] rates = {"6 Mb/s", "9 Mb/s", "12 Mb/s", "18 Mb/s",
				"24 Mb/s", "36 Mb/s", "48 Mb/s", "54 Mb/s"};
		for (int n = 0; n < codes.length; n++) {
			byte[] ratePacket = createPacket(codes[n], 100 + n, -41_000d, .001d);
			WifiSignalAnalyzer.Result rateResult = new WifiSignalAnalyzer().analyze(ratePacket,
					ratePacket.length, WifiSignalAnalyzer.SAMPLE_RATE_HZ);
			assertTrue(rateResult.lSigValid, rates[n] + " L-SIG did not decode");
			assertTrue(rates[n].equals(rateResult.rate), "rate " + rateResult.rate + ", expected " + rates[n]);
			assertTrue(rateResult.lengthBytes == 100 + n, "length " + rateResult.lengthBytes);
		}
		byte[] packet16Msps = resample(createPacket(0xb, 417, 52_000d, .001d),
				WifiSignalAnalyzer.SAMPLE_RATE_HZ, 16_000_000);
		WifiSignalAnalyzer.Result result16Msps = new WifiSignalAnalyzer().analyze(packet16Msps,
				packet16Msps.length, 16_000_000);
		assertTrue(result16Msps.lSigValid, "16 MS/s L-SIG did not decode: " + result16Msps.state);
		assertTrue("6 Mb/s".equals(result16Msps.rate), "16 MS/s rate " + result16Msps.rate);
		assertTrue(result16Msps.lengthBytes == 417, "16 MS/s length " + result16Msps.lengthBytes);
		testOfdmBeacon();
		System.out.println("Wi-Fi analyzer tests passed");
	}

	private static void testOfdmBeacon() {
		byte[] psdu = createBeaconPsdu();
		byte[] packet = createOfdmBeaconPacket(psdu, 31_000d, .001d);
		WifiSignalAnalyzer analyzer = new WifiSignalAnalyzer();
		WifiSignalAnalyzer.Result result = analyzer.analyze(2_437_000_000L,
				packet, packet.length, WifiSignalAnalyzer.SAMPLE_RATE_HZ);
		assertTrue(result.lSigValid, "OFDM beacon L-SIG did not decode: " + result.state);
		java.util.List<WifiSignalAnalyzer.Observation> frames = analyzer.takeObservations();
		assertTrue(frames.size() == 1, "OFDM beacon MAC/FCS observation missing");
		WifiMacFrame frame = frames.get(0).mac;
		assertTrue("Beacon".equals(frame.frameType), frame.frameType);
		assertTrue("TestNet".equals(frame.ssid), frame.ssid);
		assertTrue("02:11:22:33:44:77".equals(frame.bssid), frame.bssid);
		assertTrue(frame.channel == 6, "channel " + frame.channel);
		assertTrue("802.11g".equals(frame.standards), frame.standards);
		assertTrue("18 Mb/s".equals(frame.maxAdvertisedPhy), frame.maxAdvertisedPhy);
	}

	private static byte[] createBeaconPsdu() {
		byte[] body = new byte[24 + 12 + 2 + 7 + 2 + 4 + 3];
		body[0] = (byte)0x80;
		java.util.Arrays.fill(body, 4, 10, (byte)0xff);
		byte[] bssid = {0x02,0x11,0x22,0x33,0x44,0x77};
		System.arraycopy(bssid,0,body,10,6);System.arraycopy(bssid,0,body,16,6);
		body[32]=100;body[34]=0x01;
		int at=36;body[at++]=0;body[at++]=7;
		byte[] ssid="TestNet".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		System.arraycopy(ssid,0,body,at,ssid.length);at+=ssid.length;
		body[at++]=1;body[at++]=4;body[at++]=(byte)0x8c;body[at++]=0x12;body[at++]=(byte)0x98;body[at++]=0x24;
		body[at++]=3;body[at++]=1;body[at]=6;
		byte[] psdu=java.util.Arrays.copyOf(body,body.length+4);long fcs=crc32(psdu,0,body.length);
		for(int n=0;n<4;n++)psdu[body.length+n]=(byte)(fcs>>>(8*n));
		return psdu;
	}

	private static byte[] createOfdmBeaconPacket(byte[] psdu,double cfoHz,double noiseSigma) {
		int symbols=(16+psdu.length*8+6+23)/24,prefix=96,count=prefix+400+symbols*80+160;
		double[] outI=new double[count],outQ=new double[count];Random random=new Random(80212);
		double[] shortI=new double[16],shortQ=new double[16];
		for(int n=0;n<16;n++){double phase=2d*Math.PI*random.nextInt(16)/16d;shortI[n]=.11d*Math.cos(phase);shortQ[n]=.11d*Math.sin(phase);}
		for(int n=0;n<160;n++){outI[prefix+n]=shortI[n&15];outQ[prefix+n]=shortQ[n&15];}
		double[] longI=new double[64],longQ=new double[64];
		for(int carrier=-26;carrier<=26;carrier++)longI[bin(carrier)]=LONG_TRAINING[carrier+26];ifft(longI,longQ);
		for(int n=0;n<32;n++)copy(outI,outQ,prefix+160+n,longI,longQ,32+n);
		for(int n=0;n<64;n++){copy(outI,outQ,prefix+192+n,longI,longQ,n);copy(outI,outQ,prefix+256+n,longI,longQ,n);}
		int[] signalBits=signalBits(0xb,psdu.length),signalCoded=convolutionalEncode(signalBits),signalInterleaved=new int[48];
		for(int k=0;k<48;k++)signalInterleaved[3*(k&15)+k/16]=signalCoded[k];
		double[] signalI=new double[64],signalQ=new double[64];
		for(int n=0;n<48;n++)signalI[bin(DATA_CARRIERS[n])]=signalInterleaved[n]==0?-1d:1d;
		int[] pilots={-21,-7,7,21},signs={1,1,1,-1};for(int n=0;n<4;n++)signalI[bin(pilots[n])]=signs[n];ifft(signalI,signalQ);
		for(int n=0;n<16;n++)copy(outI,outQ,prefix+320+n,signalI,signalQ,48+n);
		for(int n=0;n<64;n++)copy(outI,outQ,prefix+336+n,signalI,signalQ,n);

		int dataBits=symbols*24;int[] plain=new int[dataBits];
		for(int n=0;n<psdu.length;n++)for(int bit=0;bit<8;bit++)plain[16+n*8+bit]=psdu[n]>>>bit&1;
		int[] scrambled=new int[dataBits];int state=93;
		for(int n=0;n<dataBits;n++){int feedback=(state>>6^state>>3)&1;scrambled[n]=plain[n]^feedback;state=(state<<1&0x7e)|feedback;}
		int tail=16+psdu.length*8;for(int n=0;n<6;n++)scrambled[tail+n]=0;
		int[] coded=convolutionalEncode(scrambled);
		for(int symbol=0;symbol<symbols;symbol++){
			int[] interleaved=new int[48];for(int k=0;k<48;k++)interleaved[3*(k&15)+k/16]=coded[symbol*48+k];
			double[] dataI=new double[64],dataQ=new double[64];
			for(int n=0;n<48;n++)dataI[bin(DATA_CARRIERS[n])]=interleaved[n]==0?-1d:1d;
			for(int n=0;n<4;n++)dataI[bin(pilots[n])]=signs[n];ifft(dataI,dataQ);
			int start=prefix+400+symbol*80;for(int n=0;n<16;n++)copy(outI,outQ,start+n,dataI,dataQ,48+n);
			for(int n=0;n<64;n++)copy(outI,outQ,start+16+n,dataI,dataQ,n);
		}
		double peak=0;for(int n=0;n<count;n++)peak=Math.max(peak,Math.max(Math.abs(outI[n]),Math.abs(outQ[n])));
		byte[] iq=new byte[count*2];for(int n=0;n<count;n++){double angle=2d*Math.PI*cfoHz*n/WifiSignalAnalyzer.SAMPLE_RATE_HZ;
			double cos=Math.cos(angle),sin=Math.sin(angle);double real=(outI[n]*cos-outQ[n]*sin)/peak*.72d+random.nextGaussian()*noiseSigma;
			double imag=(outI[n]*sin+outQ[n]*cos)/peak*.72d+random.nextGaussian()*noiseSigma;
			iq[n*2]=(byte)Math.round(Math.max(-1d,Math.min(1d,real))*120d);iq[n*2+1]=(byte)Math.round(Math.max(-1d,Math.min(1d,imag))*120d);}
		return iq;
	}

	private static long crc32(byte[] data,int offset,int length){long crc=0xffffffffL;for(int n=0;n<length;n++){crc^=data[offset+n]&0xff;for(int bit=0;bit<8;bit++)crc=crc>>>1^((crc&1)!=0?0xedb88320L:0);}return(crc^0xffffffffL)&0xffffffffL;}

	private static byte[] resample(byte[] input, int inputRateHz, int outputRateHz) {
		int inputSamples = input.length / 2;
		int outputSamples = (int)Math.floor(inputSamples * (double)outputRateHz / inputRateHz);
		byte[] output = new byte[outputSamples * 2];
		double step = inputRateHz / (double)outputRateHz;
		for (int n = 0; n < outputSamples; n++) {
			double position = n * step;
			int left = Math.min(inputSamples - 1, (int)position);
			int right = Math.min(inputSamples - 1, left + 1);
			double fraction = position - left;
			for (int component = 0; component < 2; component++) {
				double value = input[left * 2 + component] * (1d - fraction)
						+ input[right * 2 + component] * fraction;
				output[n * 2 + component] = (byte)Math.round(value);
			}
		}
		return output;
	}

	private static byte[] createPacket(int rateCode, int lengthBytes, double cfoHz, double noiseSigma) {
		int prefix = 96, suffix = 160, count = prefix + 400 + suffix;
		double[] outI = new double[count], outQ = new double[count];
		Random random = new Random(80211);
		double[] shortI = new double[16], shortQ = new double[16];
		for (int n = 0; n < 16; n++) {
			double phase = 2d * Math.PI * random.nextInt(16) / 16d;
			shortI[n] = .11d * Math.cos(phase);
			shortQ[n] = .11d * Math.sin(phase);
		}
		for (int n = 0; n < 160; n++) {
			outI[prefix + n] = shortI[n & 15];
			outQ[prefix + n] = shortQ[n & 15];
		}

		double[] longI = new double[64], longQ = new double[64];
		for (int carrier = -26; carrier <= 26; carrier++) longI[bin(carrier)] = LONG_TRAINING[carrier + 26];
		ifft(longI, longQ);
		for (int n = 0; n < 32; n++) copy(outI, outQ, prefix + 160 + n, longI, longQ, 32 + n);
		for (int n = 0; n < 64; n++) {
			copy(outI, outQ, prefix + 192 + n, longI, longQ, n);
			copy(outI, outQ, prefix + 256 + n, longI, longQ, n);
		}

		int[] signalBits = signalBits(rateCode, lengthBytes);
		int[] coded = convolutionalEncode(signalBits);
		int[] interleaved = new int[48];
		for (int k = 0; k < 48; k++) interleaved[3 * (k & 15) + k / 16] = coded[k];
		double[] signalI = new double[64], signalQ = new double[64];
		for (int n = 0; n < 48; n++) signalI[bin(DATA_CARRIERS[n])] = interleaved[n] == 0 ? -1d : 1d;
		int[] pilots = {-21,-7,7,21}, signs = {1,1,1,-1};
		for (int n = 0; n < pilots.length; n++) signalI[bin(pilots[n])] = signs[n];
		ifft(signalI, signalQ);
		for (int n = 0; n < 16; n++) copy(outI, outQ, prefix + 320 + n, signalI, signalQ, 48 + n);
		for (int n = 0; n < 64; n++) copy(outI, outQ, prefix + 336 + n, signalI, signalQ, n);

		double peak = 0;
		for (int n = 0; n < count; n++) peak = Math.max(peak, Math.max(Math.abs(outI[n]), Math.abs(outQ[n])));
		byte[] iq = new byte[count * 2];
		for (int n = 0; n < count; n++) {
			double angle = 2d * Math.PI * cfoHz * n / WifiSignalAnalyzer.SAMPLE_RATE_HZ;
			double cos = Math.cos(angle), sin = Math.sin(angle);
			double real = (outI[n] * cos - outQ[n] * sin) / peak * .72d + random.nextGaussian() * noiseSigma;
			double imag = (outI[n] * sin + outQ[n] * cos) / peak * .72d + random.nextGaussian() * noiseSigma;
			iq[n * 2] = (byte)Math.round(Math.max(-1d, Math.min(1d, real)) * 120d);
			iq[n * 2 + 1] = (byte)Math.round(Math.max(-1d, Math.min(1d, imag)) * 120d);
		}
		return iq;
	}

	private static int[] signalBits(int rateCode, int lengthBytes) {
		int[] bits = new int[24];
		for (int n = 0; n < 4; n++) bits[n] = rateCode >> n & 1;
		for (int n = 0; n < 12; n++) bits[5 + n] = lengthBytes >> n & 1;
		int parity = 0;
		for (int n = 0; n < 17; n++) parity ^= bits[n];
		bits[17] = parity;
		return bits;
	}

	private static int[] convolutionalEncode(int[] bits) {
		int[] output = new int[bits.length * 2];
		int state = 0;
		for (int n = 0; n < bits.length; n++) {
			int register = state << 1 | bits[n];
			output[n * 2] = parity(register & 0x6d);
			output[n * 2 + 1] = parity(register & 0x4f);
			state = register & 63;
		}
		return output;
	}

	private static int parity(int value) {
		value ^= value >>> 16; value ^= value >>> 8; value ^= value >>> 4;
		return (0x6996 >>> (value & 15)) & 1;
	}

	private static void copy(double[] outI, double[] outQ, int output, double[] inI, double[] inQ, int input) {
		outI[output] = inI[input]; outQ[output] = inQ[input];
	}

	private static int bin(int carrier) { return (carrier + 64) & 63; }

	private static void ifft(double[] real, double[] imag) {
		for (int n = 0; n < imag.length; n++) imag[n] = -imag[n];
		fft(real, imag);
		for (int n = 0; n < imag.length; n++) { real[n] /= real.length; imag[n] = -imag[n] / real.length; }
	}

	private static void fft(double[] real, double[] imag) {
		int size = real.length;
		for (int i=1,j=0;i<size;i++){int bit=size>>1;for(;(j&bit)!=0;bit>>=1)j^=bit;j^=bit;if(i<j){double v=real[i];real[i]=real[j];real[j]=v;v=imag[i];imag[i]=imag[j];imag[j]=v;}}
		for (int length=2;length<=size;length<<=1){double angle=-2*Math.PI/length,br=Math.cos(angle),bi=Math.sin(angle);for(int start=0;start<size;start+=length){double wr=1,wi=0;for(int n=0;n<length/2;n++){int a=start+n,b=a+length/2;double xr=real[b]*wr-imag[b]*wi,xi=real[b]*wi+imag[b]*wr;real[b]=real[a]-xr;imag[b]=imag[a]-xi;real[a]+=xr;imag[a]+=xi;double next=wr*br-wi*bi;wi=wr*bi+wi*br;wr=next;}}}
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
