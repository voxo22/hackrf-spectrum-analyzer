package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.List;

/** DVB-T2 P1 A-B-C correlator used by the one-shot signalling worker. */
final class Dvbt2P1Decoder {
	private static final double BASE_RATE = 64_000_000d / 7d;
	private static final int A = 1024, B = 482, C = 542, LENGTH = 2048;
	private static final int RESAMPLE_PHASES=256,RESAMPLE_TAPS=64;
	private static final double[][] RESAMPLE_KERNELS=createResampleKernels();
	/* ETSI EN 302 755 P1 carrier set. Kept explicitly so P1 can be decoded
	 * without constructing the much heavier payload deinterleavers. */
	private static final int[] ACTIVE = {
		44,45,47,51,54,59,62,64,65,66,70,75,78,80,81,82,84,85,87,88,89,90,94,96,97,98,102,107,110,112,113,114,
		116,117,119,120,121,122,124,125,127,131,132,133,135,136,137,138,142,144,145,146,148,149,151,152,153,154,158,160,161,162,166,171,
		172,173,175,179,182,187,190,192,193,194,198,203,206,208,209,210,212,213,215,216,217,218,222,224,225,226,230,235,238,240,241,242,
		244,245,247,248,249,250,252,253,255,259,260,261,263,264,265,266,270,272,273,274,276,277,279,280,281,282,286,288,289,290,294,299,
		300,301,303,307,310,315,318,320,321,322,326,331,334,336,337,338,340,341,343,344,345,346,350,352,353,354,358,363,364,365,367,371,
		374,379,382,384,385,386,390,395,396,397,399,403,406,411,412,413,415,419,420,421,423,424,425,426,428,429,431,435,438,443,446,448,
		449,450,454,459,462,464,465,466,468,469,471,472,473,474,478,480,481,482,486,491,494,496,497,498,500,501,503,504,505,506,508,509,
		511,515,516,517,519,520,521,522,526,528,529,530,532,533,535,536,537,538,542,544,545,546,550,555,558,560,561,562,564,565,567,568,
		569,570,572,573,575,579,580,581,583,584,585,586,588,589,591,595,598,603,604,605,607,611,612,613,615,616,617,618,622,624,625,626,
		628,629,631,632,633,634,636,637,639,643,644,645,647,648,649,650,654,656,657,658,660,661,663,664,665,666,670,672,673,674,678,683,
		684,689,692,696,698,699,701,702,703,704,706,707,708,712,714,715,717,718,719,720,722,723,725,726,727,729,733,734,735,736,738,739,
		740,744,746,747,748,753,756,760,762,763,765,766,767,768,770,771,772,776,778,779,780,785,788,792,794,795,796,801,805,806,807,809
	};
	private static final int[][] S1_PATTERNS = {
		{0x12,0x47,0x21,0x74,0x1d,0x48,0x2e,0x7b},{0x47,0x12,0x74,0x21,0x48,0x1d,0x7b,0x2e},
		{0x21,0x74,0x12,0x47,0x2e,0x7b,0x1d,0x48},{0x74,0x21,0x47,0x12,0x7b,0x2e,0x48,0x1d},
		{0x1d,0x48,0x2e,0x7b,0x12,0x47,0x21,0x74},{0x48,0x1d,0x7b,0x2e,0x47,0x12,0x74,0x21},
		{0x2e,0x7b,0x1d,0x48,0x21,0x74,0x12,0x47},{0x7b,0x2e,0x48,0x1d,0x74,0x21,0x47,0x12}
	};
	private static final int[][] S2_PATTERNS = {
		{0x12,0x1d,0x47,0x48,0x21,0x2e,0x74,0x7b,0x1d,0x12,0x48,0x47,0x2e,0x21,0x7b,0x74,0x12,0xe2,0x47,0xb7,0x21,0xd1,0x74,0x84,0x1d,0xed,0x48,0xb8,0x2e,0xde,0x7b,0x8b},
		{0x47,0x48,0x12,0x1d,0x74,0x7b,0x21,0x2e,0x48,0x47,0x1d,0x12,0x7b,0x74,0x2e,0x21,0x47,0xb7,0x12,0xe2,0x74,0x84,0x21,0xd1,0x48,0xb8,0x1d,0xed,0x7b,0x8b,0x2e,0xde},
		{0x21,0x2e,0x74,0x7b,0x12,0x1d,0x47,0x48,0x2e,0x21,0x7b,0x74,0x1d,0x12,0x48,0x47,0x21,0xd1,0x74,0x84,0x12,0xe2,0x47,0xb7,0x2e,0xde,0x7b,0x8b,0x1d,0xed,0x48,0xb8},
		{0x74,0x7b,0x21,0x2e,0x47,0x48,0x12,0x1d,0x7b,0x74,0x2e,0x21,0x48,0x47,0x1d,0x12,0x74,0x84,0x21,0xd1,0x47,0xb7,0x12,0xe2,0x7b,0x8b,0x2e,0xde,0x48,0xb8,0x1d,0xed},
		{0x1d,0x12,0x48,0x47,0x2e,0x21,0x7b,0x74,0x12,0x1d,0x47,0x48,0x21,0x2e,0x74,0x7b,0x1d,0xed,0x48,0xb8,0x2e,0xde,0x7b,0x8b,0x12,0xe2,0x47,0xb7,0x21,0xd1,0x74,0x84},
		{0x48,0x47,0x1d,0x12,0x7b,0x74,0x2e,0x21,0x47,0x48,0x12,0x1d,0x74,0x7b,0x21,0x2e,0x48,0xb8,0x1d,0xed,0x7b,0x8b,0x2e,0xde,0x47,0xb7,0x12,0xe2,0x74,0x84,0x21,0xd1},
		{0x2e,0x21,0x7b,0x74,0x1d,0x12,0x48,0x47,0x21,0x2e,0x74,0x7b,0x12,0x1d,0x47,0x48,0x2e,0xde,0x7b,0x8b,0x1d,0xed,0x48,0xb8,0x21,0xd1,0x74,0x84,0x12,0xe2,0x47,0xb7},
		{0x7b,0x74,0x2e,0x21,0x48,0x47,0x1d,0x12,0x74,0x7b,0x21,0x2e,0x47,0x48,0x12,0x1d,0x7b,0x8b,0x2e,0xde,0x48,0xb8,0x1d,0xed,0x74,0x84,0x21,0xd1,0x47,0xb7,0x12,0xe2},
		{0x12,0xe2,0x47,0xb7,0x21,0xd1,0x74,0x84,0x1d,0xed,0x48,0xb8,0x2e,0xde,0x7b,0x8b,0x12,0x1d,0x47,0x48,0x21,0x2e,0x74,0x7b,0x1d,0x12,0x48,0x47,0x2e,0x21,0x7b,0x74},
		{0x47,0xb7,0x12,0xe2,0x74,0x84,0x21,0xd1,0x48,0xb8,0x1d,0xed,0x7b,0x8b,0x2e,0xde,0x47,0x48,0x12,0x1d,0x74,0x7b,0x21,0x2e,0x48,0x47,0x1d,0x12,0x7b,0x74,0x2e,0x21},
		{0x21,0xd1,0x74,0x84,0x12,0xe2,0x47,0xb7,0x2e,0xde,0x7b,0x8b,0x1d,0xed,0x48,0xb8,0x21,0x2e,0x74,0x7b,0x12,0x1d,0x47,0x48,0x2e,0x21,0x7b,0x74,0x1d,0x12,0x48,0x47},
		{0x74,0x84,0x21,0xd1,0x47,0xb7,0x12,0xe2,0x7b,0x8b,0x2e,0xde,0x48,0xb8,0x1d,0xed,0x74,0x7b,0x21,0x2e,0x47,0x48,0x12,0x1d,0x7b,0x74,0x2e,0x21,0x48,0x47,0x1d,0x12},
		{0x1d,0xed,0x48,0xb8,0x2e,0xde,0x7b,0x8b,0x12,0xe2,0x47,0xb7,0x21,0xd1,0x74,0x84,0x1d,0x12,0x48,0x47,0x2e,0x21,0x7b,0x74,0x12,0x1d,0x47,0x48,0x21,0x2e,0x74,0x7b},
		{0x48,0xb8,0x1d,0xed,0x7b,0x8b,0x2e,0xde,0x47,0xb7,0x12,0xe2,0x74,0x84,0x21,0xd1,0x48,0x47,0x1d,0x12,0x7b,0x74,0x2e,0x21,0x47,0x48,0x12,0x1d,0x74,0x7b,0x21,0x2e},
		{0x2e,0xde,0x7b,0x8b,0x1d,0xed,0x48,0xb8,0x21,0xd1,0x74,0x84,0x12,0xe2,0x47,0xb7,0x2e,0x21,0x7b,0x74,0x1d,0x12,0x48,0x47,0x21,0x2e,0x74,0x7b,0x12,0x1d,0x47,0x48},
		{0x7b,0x8b,0x2e,0xde,0x48,0xb8,0x1d,0xed,0x74,0x84,0x21,0xd1,0x47,0xb7,0x12,0xe2,0x7b,0x74,0x2e,0x21,0x48,0x47,0x1d,0x12,0x74,0x7b,0x21,0x2e,0x47,0x48,0x12,0x1d}
	};

	Result detect(byte[] iq, int length, int sampleRateHz) {
		return detect(iq,length,sampleRateHz,null);
	}

	Result synchronize(byte[] iq,int length,int sampleRateHz,Decoded known) {
		return detect(iq,length,sampleRateHz,known);
	}

	private Result detect(byte[] iq, int length, int sampleRateHz,Decoded known) {
		if (iq == null || length < 8192) return new Result(new int[0], new double[0], "--");
		int sourceCount = Math.min(length, iq.length) / 2;
		int count = (int)Math.floor(sourceCount * BASE_RATE / sampleRateHz);
		double[] re = new double[count], im = new double[count];
		double step = sampleRateHz / BASE_RATE;
		for (int n=0;n<count;n++) { double source=n*step; int a=Math.min(sourceCount-2,(int)source); double f=source-a;
			re[n]=(iq[a*2]*(1-f)+iq[(a+1)*2]*f)/128d; im[n]=(iq[a*2+1]*(1-f)+iq[(a+1)*2+1]*f)/128d; }
		double[] bRe=new double[count],bIm=new double[count],cRe=new double[count],cIm=new double[count];
		double angleStep=Math.PI/(2d*1024d);
		for(int n=0;n<count;n++){double angle=n*angleStep,shRe=re[n]*Math.sin(angle)-im[n]*Math.cos(angle),shIm=re[n]*Math.cos(angle)+im[n]*Math.sin(angle);
			if(n>=B){bRe[n]=shRe*re[n-B]+shIm*im[n-B];bIm[n]=shIm*re[n-B]-shRe*im[n-B];}
			if(n>=C){double oldAngle=(n-C)*angleStep,oldRe=re[n-C]*Math.sin(oldAngle)-im[n-C]*Math.cos(oldAngle),oldIm=re[n-C]*Math.cos(oldAngle)+im[n-C]*Math.sin(oldAngle);cRe[n]=re[n]*oldRe+im[n]*oldIm;cIm[n]=im[n]*oldRe-re[n]*oldIm;}}
		runningInPlace(bRe,B);runningInPlace(bIm,B);runningInPlace(cRe,C);runningInPlace(cIm,C);
		double[] score=new double[count];double mean=0;int meanCount=0;
		for(int n=2*B;n<count;n++){int delayed=n-2*B;double bp=bRe[n]*bRe[n]+bIm[n]*bIm[n],cp=cRe[delayed]*cRe[delayed]+cIm[delayed]*cIm[delayed];score[n]=Math.sqrt(bp*cp)/(B*(double)C+1e-12);mean+=score[n];meanCount++;}
		mean/=Math.max(1,meanCount);List<Integer> peaks=new ArrayList<>();List<Double> strengths=new ArrayList<>();int separation=LENGTH/2;
		for(int n=2*B+1;n<count-1;n++)if(score[n]>mean*6&&score[n]>=score[n-1]&&score[n]>score[n+1]){if(peaks.isEmpty()||n-peaks.get(peaks.size()-1)>separation){peaks.add(n);strengths.add(score[n]/Math.max(1e-12,mean));}else if(score[n]>score[peaks.get(peaks.size()-1)]){peaks.set(peaks.size()-1,n);strengths.set(strengths.size()-1,score[n]/Math.max(1e-12,mean));}}
		int[] positions=new int[peaks.size()];double[] ratios=new double[peaks.size()];Decoded decoded=null;
		for(int n=0;n<positions.length;n++){positions[n]=(int)Math.round(peaks.get(n)*(double)sampleRateHz/BASE_RATE);ratios[n]=strengths.get(n);if(decoded==null){if(known==null)decoded=findAndDecode(re,im,peaks.get(n));else decoded=decodeKnown(re,im,peaks.get(n)-1541,known);}}
		if(decoded!=null)decoded=new Decoded(decoded.preamble,decoded.s2,decoded.fftMode,decoded.baseSampleStart,decoded.carrierShift,filteredP1Points(iq,sourceCount,step,decoded.baseSampleStart,decoded.carrierShift));
		return new Result(positions,ratios,decoded==null?"P1 candidates":"P1 locked",decoded);
	}
	private float[] filteredP1Points(byte[] iq,int sourceCount,double step,int baseStart,int shift){double[] re=new double[A],im=new double[A];int half=RESAMPLE_TAPS/2;for(int n=0;n<A;n++){double source=(baseStart+n)*step;int center=(int)Math.floor(source),phase=(int)Math.round((source-center)*RESAMPLE_PHASES);if(phase==RESAMPLE_PHASES){phase=0;center++;}double sr=0,si=0,sw=0;for(int tap=0;tap<RESAMPLE_TAPS;tap++){int index=center+tap-half+1;if(index<0||index>=sourceCount)continue;double weight=RESAMPLE_KERNELS[phase][tap];sr+=iq[index*2]*weight;si+=iq[index*2+1]*weight;sw+=weight;}re[n]=sr/(128d*Math.max(1e-12,sw));im[n]=si/(128d*Math.max(1e-12,sw));}fft(re,im);return p1Points(re,im,shift);}
	private Decoded decodeKnown(double[] inputRe,double[] inputIm,int expected,Decoded known){
		/* A correlation peak alone is not a valid live P1 update. Validate the
		 * complete S1/S2 codewords at the already-known carrier shift, otherwise a
		 * false peak would replace the last good constellation with FFT noise. */
		for(int distance=0;distance<=80;distance++)for(int side=0;side<(distance==0?1:2);side++){
			int start=expected+(side==0?-distance:distance);if(start<0||start+A>inputRe.length)continue;
			double[] re=new double[A],im=new double[A];System.arraycopy(inputRe,start,re,0,A);System.arraycopy(inputIm,start,im,0,A);fft(re,im);
			Decoded decoded=decode(re,im,known.carrierShift,start);
			if(decoded!=null&&decoded.preamble==known.preamble&&decoded.s2==known.s2)
				return refineTiming(inputRe,inputIm,expected,decoded);
		}
		return null;
	}

	private Decoded findAndDecode(double[] inputRe,double[] inputIm,int peak) {
		/* The correlator maximum is deliberately allowed a wide margin. P1 runs
		 * only in the one-shot signalling worker, and the repeated 64 P1 bits make
		 * a false lock vanishingly unlikely. */
		/* The A start is 1541 base-rate samples before the A-B-C correlation
		 * maximum (the few-sample margin covers resampler/timing rounding). */
		int expected=peak-1541;
		/* Prefer the correlation-derived timing. The previous ascending scan could
		 * return the first still-decodable window 80 samples early; that linear
		 * phase ramp turned the C receiver's P1 arcs into several false circles. */
		for(int distance=0;distance<=80;distance++)for(int side=0;side<(distance==0?1:2);side++){
			int start=expected+(side==0?-distance:distance);if(start<0||start+A>inputRe.length)continue;
			double[] re=new double[A],im=new double[A];System.arraycopy(inputRe,start,re,0,A);System.arraycopy(inputIm,start,im,0,A);fft(re,im);
			for(int shift=76;shift<96;shift++){Decoded d=decode(re,im,shift,start);if(d!=null)return refineTiming(inputRe,inputIm,expected,d);}
		}
		return null;
	}
	private Decoded refineTiming(double[] inputRe,double[] inputIm,int expected,Decoded decoded){int bestStart=decoded.baseSampleStart;double best=-1;for(int start=Math.max(0,expected-80);start<=Math.min(inputRe.length-A,expected+80);start++){double[] re=new double[A],im=new double[A];System.arraycopy(inputRe,start,re,0,A);System.arraycopy(inputIm,start,im,0,A);fft(re,im);double score=p1SquaredCoherence(re,im,decoded.carrierShift);if(score>best){best=score;bestStart=start;}}return new Decoded(decoded.preamble,decoded.s2,decoded.fftMode,bestStart,decoded.carrierShift);}
	private double p1SquaredCoherence(double[] re,double[] im,int shift){double sr=0,si=0;int count=0;for(int carrier:ACTIVE){int bin=(shift+carrier+A/2)&(A-1);double x=re[bin],y=im[bin],power=x*x+y*y;if(power<1e-12)continue;sr+=(x*x-y*y)/power;si+=2*x*y/power;count++;}return count==0?0:Math.hypot(sr,si)/count;}

	private Decoded decode(double[] re,double[] im,int shift,int start) {
		int[] random=new int[ACTIVE.length];int sr=0x4e46;
		for(int n=0;n<random.length;n++){int bit=(sr^(sr>>1))&1;random[n]=bit==0?1:-1;sr>>=1;if(bit!=0)sr|=0x4000;}
		int[] differential=new int[ACTIVE.length];int old=-1;differential[0]=old*random[0];
		for(int n=1;n<ACTIVE.length;n++){int a=(shift+ACTIVE[n]+A/2)&(A-1),b=(shift+ACTIVE[n-1]+A/2)&(A-1);double dr=re[a]*re[b]+im[a]*im[b],di=im[a]*re[b]-re[a]*im[b];int current=Math.abs(Math.atan2(di,dr))>Math.PI/2?-old:old;old=current;differential[n]=current*random[n];}
		byte[] data=new byte[48];old=1;
		for(int n=0;n<ACTIVE.length;n++){int bit=differential[n]==old?0:1;old=differential[n];data[n>>3]=(byte)((data[n>>3]<<1)|bit);}
		int s1=-1,s2=-1,bestS1=999,secondS1=999,bestS2=999,secondS2=999;
		for(int candidate=0;candidate<S1_PATTERNS.length;candidate++){int errors=0;for(int n=0;n<8;n++){errors+=Integer.bitCount((data[n]&255)^S1_PATTERNS[candidate][n]);errors+=Integer.bitCount((data[n+40]&255)^S1_PATTERNS[candidate][n]);}if(errors<bestS1){secondS1=bestS1;bestS1=errors;s1=candidate;}else if(errors<secondS1)secondS1=errors;}
		for(int candidate=0;candidate<S2_PATTERNS.length;candidate++){int errors=0;for(int n=0;n<32;n++)errors+=Integer.bitCount((data[n+8]&255)^S2_PATTERNS[candidate][n]);if(errors<bestS2){secondS2=bestS2;bestS2=errors;s2=candidate;}else if(errors<secondS2)secondS2=errors;}
		if(s1<0||s1>4||s2<0||bestS1>24||bestS2>48||secondS1-bestS1<8||secondS2-bestS2<16)return null;
		String[] modes={"2K","8K","4K","1K","16K","32K","8K T2-GI","32K T2-GI"};
		return new Decoded(s1,s2,modes[s2>>1],start,shift,p1Points(re,im,shift));
	}
	/* Match the C receiver's P1 plot exactly: raw FFT values on the 384
	 * active P1 carriers, not normalized differential products. */
	private float[] p1Points(double[] re,double[] im,int shift){float[] points=new float[ACTIVE.length*2];int out=0;for(int carrier:ACTIVE){int bin=(shift+carrier+A/2)&(A-1);points[out++]=(float)re[bin];points[out++]=(float)im[bin];}return points;}

	private void fft(double[] real,double[] imag){int size=real.length;for(int i=1,j=0;i<size;i++){int bit=size>>1;for(;(j&bit)!=0;bit>>=1)j^=bit;j^=bit;if(i<j){double v=real[i];real[i]=real[j];real[j]=v;v=imag[i];imag[i]=imag[j];imag[j]=v;}}for(int len=2;len<=size;len<<=1){double angle=-2*Math.PI/len,cr=Math.cos(angle),ci=Math.sin(angle);for(int st=0;st<size;st+=len){double wr=1,wi=0;for(int j=0;j<len/2;j++){int x=st+j,y=x+len/2;double yr=real[y]*wr-imag[y]*wi,yi=real[y]*wi+imag[y]*wr;real[y]=real[x]-yr;imag[y]=imag[x]-yi;real[x]+=yr;imag[x]+=yi;double nr=wr*cr-wi*ci;wi=wr*ci+wi*cr;wr=nr;}}}}

	private void runningInPlace(double[] values,int window){double[] delayed=new double[window];double sum=0;for(int n=0;n<values.length;n++){int slot=n%window;double old=delayed[slot],value=values[n];delayed[slot]=value;sum+=value;if(n>=window)sum-=old;values[n]=sum;}}
	private static double[][] createResampleKernels(){double[][] kernels=new double[RESAMPLE_PHASES][RESAMPLE_TAPS];int half=RESAMPLE_TAPS/2;for(int phase=0;phase<RESAMPLE_PHASES;phase++){double fraction=phase/(double)RESAMPLE_PHASES,sum=0;for(int tap=0;tap<RESAMPLE_TAPS;tap++){double distance=fraction-(tap-half+1),x=Math.PI*.97*distance,sinc=Math.abs(x)<1e-12?.97:.97*Math.sin(x)/x,position=Math.abs(distance)/half,window=position>=1?0:.42+.5*Math.cos(Math.PI*position)+.08*Math.cos(2*Math.PI*position);kernels[phase][tap]=sinc*window;sum+=kernels[phase][tap];}for(int tap=0;tap<RESAMPLE_TAPS;tap++)kernels[phase][tap]/=sum;}return kernels;}

	static final class Decoded { final int preamble,s2;final String fftMode;final int baseSampleStart,carrierShift;final float[] points;
		Decoded(int p,int s,String f,int start,int shift){this(p,s,f,start,shift,new float[0]);}
		Decoded(int p,int s,String f,int start,int shift,float[] constellation){preamble=p;s2=s;fftMode=f;baseSampleStart=start;carrierShift=shift;points=constellation;} }
	static final class Result { final int[] samplePositions; final double[] peakRatios; final String state;final Decoded decoded;
		Result(int[] p,double[] r,String s){this(p,r,s,null);} Result(int[] p,double[] r,String s,Decoded d){samplePositions=p;peakRatios=r;state=s;decoded=d;} }
}
