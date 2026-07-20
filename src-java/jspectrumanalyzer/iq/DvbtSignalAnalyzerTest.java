package jspectrumanalyzer.iq;

import java.util.Random;

public final class DvbtSignalAnalyzerTest {
	private DvbtSignalAnalyzerTest() { }

	public static void main(String[] args) {
		byte[] iq = create2k64QamSignal();
		DvbtSignalAnalyzer.Result result = new DvbtSignalAnalyzer().analyze(iq, iq.length, 9_142_857);
		assertTrue("2K".equals(result.fftMode), "2K mode, got " + result.fftMode);
		assertTrue("1/8".equals(result.guard), "1/8 guard, got " + result.guard);
		assertTrue("64-QAM".equals(result.modulation), "64-QAM, got " + result.modulation);
		assertTrue("--".equals(result.codeRate), "TPS should be temporarily disabled");
		DvbtSignalAnalyzer.Result tpsResult = new DvbtSignalAnalyzer(true).analyze(iq, iq.length, 9_142_857);
		assertTrue("3/4".equals(tpsResult.codeRate), "3/4 code rate, got " + tpsResult.codeRate);
		assertTrue(result.cpCorrelation > 0.8, "CP correlation " + result.cpCorrelation);
		assertTrue(result.pilotCoherence > 0.8, "pilot coherence " + result.pilotCoherence);
		assertTrue(result.quality > 55, "quality " + result.quality);
		System.out.println("DVB-T analyzer tests passed");
	}

	private static byte[] create2k64QamSignal() {
		final int size = 2048, guard = 256, active = 1705, symbols = 82;
		double[] outputI = new double[(size + guard) * symbols];
		double[] outputQ = new double[outputI.length];
		Random random = new Random(744);
		int[] tpsBits = tpsBits();
		double tpsSign = 1d;
		for (int symbol = 0; symbol < symbols; symbol++) {
			if (symbol > 0 && tpsBits[symbol % 68] != 0) tpsSign = -tpsSign;
			double[] real = new double[size], imag = new double[size];
			int phase = symbol & 3;
			for (int k = 0; k < active; k++) {
				int bin = (k - active / 2 + size) % size;
				if ((k - 3 * phase) % 12 == 0) {
					real[bin] = 1.333333 * pilotPolarity(k);
				} else {
					real[bin] = randomLevel(random);
					imag[bin] = randomLevel(random);
				}
			}
			for (int carrier : new int[] {34, 209, 346}) {
				int bin = (carrier - active / 2 + size) % size;
				real[bin] = tpsSign;
				imag[bin] = 0;
			}
			ifft(real, imag);
			int start = symbol * (size + guard);
			for (int n = 0; n < guard; n++) {
				outputI[start + n] = real[size - guard + n]; outputQ[start + n] = imag[size - guard + n];
			}
			for (int n = 0; n < size; n++) {
				outputI[start + guard + n] = real[n]; outputQ[start + guard + n] = imag[n];
			}
		}
		double peak = 0;
		for (int n=0;n<outputI.length;n++) peak=Math.max(peak,Math.max(Math.abs(outputI[n]),Math.abs(outputQ[n])));
		byte[] iq = new byte[outputI.length * 2];
		for (int n=0;n<outputI.length;n++) { iq[n*2]=(byte)Math.round(outputI[n]/peak*105); iq[n*2+1]=(byte)Math.round(outputQ[n]/peak*105); }
		return iq;
	}

	private static int[] tpsBits() {
		int[] bits = new int[68];
		int sync = 0x35ee;
		for (int n = 0; n < 16; n++) bits[1 + n] = (sync >> (15 - n)) & 1;
		bits[17]=0; bits[18]=1; bits[19]=0; bits[20]=1; bits[21]=1; bits[22]=1;
		bits[25]=1; bits[26]=0; // 64-QAM
		bits[30]=0; bits[31]=1; bits[32]=0; // HP 3/4
		bits[36]=1; bits[37]=0; // guard 1/8
		bits[38]=0; bits[39]=0; // 2K
		return bits;
	}

	private static int randomLevel(Random random) { return 2 * random.nextInt(8) - 7; }

	private static double pilotPolarity(int carrier) {
		int register=0x7ff,bit=1;
		for(int k=0;k<=carrier;k++){bit=register&1;int feedback=((register>>0)^(register>>2))&1;register=(register>>1)|(feedback<<10);}
		return bit==0?1:-1;
	}

	private static void ifft(double[] real,double[] imag) {
		for(int n=0;n<imag.length;n++) imag[n]=-imag[n]; fft(real,imag);
		for(int n=0;n<imag.length;n++){real[n]/=real.length;imag[n]=-imag[n]/real.length;}
	}

	private static void fft(double[] real,double[] imag) {
		int size=real.length;
		for(int i=1,j=0;i<size;i++){int bit=size>>1;for(;(j&bit)!=0;bit>>=1)j^=bit;j^=bit;if(i<j){double v=real[i];real[i]=real[j];real[j]=v;v=imag[i];imag[i]=imag[j];imag[j]=v;}}
		for(int length=2;length<=size;length<<=1){double angle=-2*Math.PI/length,wr0=Math.cos(angle),wi0=Math.sin(angle);for(int start=0;start<size;start+=length){double wr=1,wi=0;for(int j=0;j<length/2;j++){int a=start+j,b=a+length/2;double br=real[b]*wr-imag[b]*wi,bi=real[b]*wi+imag[b]*wr;real[b]=real[a]-br;imag[b]=imag[a]-bi;real[a]+=br;imag[a]+=bi;double nr=wr*wr0-wi*wi0;wi=wr*wi0+wi*wr0;wr=nr;}}}
	}

	private static void assertTrue(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
