package jspectrumanalyzer.iq;

import java.util.Random;

public final class Dvbt2SignalAnalyzerTest {
	private Dvbt2SignalAnalyzerTest() { }
	public static void main(String[] args) {
		byte[] iq=createSignal();
		Dvbt2SignalAnalyzer analyzer=new Dvbt2SignalAnalyzer();
		analyzer.setPilotPattern(1); // PP2
		Dvbt2SignalAnalyzer.Result r=null;
		for(int attempt=0;attempt<6;attempt++)r=analyzer.analyze(iq,iq.length,9_142_857);
		check("32K".equals(r.fftMode),"mode "+r.fftMode);
		check("1/128".equals(r.guard),"guard "+r.guard);
		check("--".equals(r.modulation),"payload modulation must wait for L1-post: "+r.modulation);
		check(r.cpCorrelation>.75,"CP "+r.cpCorrelation);
		check(r.quality>45,"quality "+r.quality);
		Dvbt2P2Decoder.Result signalling=new Dvbt2P2Decoder.Result(4,1,3,0,0,100,80,256,false);
		signalling.l1PostValid=true;signalling.plpMod=3;signalling.plpCodeRate=3;signalling.plpRotation=1;signalling.plpFec=1;
		signalling.l1PostPoints=new float[]{-7,-7,7,7};
		analyzer.setLockedParameters(signalling);
		for(int attempt=0;attempt<3;attempt++)r=analyzer.analyze(iq,iq.length,9_142_857);
		Dvbt2SignalAnalyzer.Result live=r.withSignalling(signalling);
		check("DVB-T2 live tracking".equals(live.state),"live state "+live.state);
		check("256-QAM".equals(live.modulation),"locked payload modulation "+live.modulation);
		check(live.points.length==0,"payload constellation should remain disabled");
		check(live.l1PostPoints==signalling.l1PostPoints,"L1-post constellation snapshot missing");
		check(live.l1PreDetails.contains("L1-post constellation|64-QAM  [3]"),"wrong L1-post constellation name");
		check(live.l1PostDetails.contains("Constellation|256-QAM  [3]"),"wrong PLP constellation name");
		check(live.l1PostDetails.contains("Rotated constellation|Yes"),"rotation flag missing");
		System.out.println("DVB-T2 analyzer tests passed");
	}
	private static byte[] createSignal(){int size=32768,guard=size/128,active=27265,symbols=3;double[] oi=new double[(size+guard)*symbols],oq=new double[oi.length];Random random=new Random(202);
		int[] prbs=prbs(active+288);for(int symbol=0;symbol<symbols;symbol++){double[] re=new double[size],im=new double[size];for(int k=0;k<active;k++){int bin=(k-active/2+size)%size;re[bin]=2*random.nextInt(16)-15;im[bin]=2*random.nextInt(16)-15;}for(int k=6*(symbol%2);k<active;k+=12){int bin=(k-active/2+size)%size;re[bin]=prbs[k+288]==0?1.333:-1.333;im[bin]=0;}ifft(re,im);int start=symbol*(size+guard);for(int n=0;n<guard;n++){oi[start+n]=re[size-guard+n];oq[start+n]=im[size-guard+n];}for(int n=0;n<size;n++){oi[start+guard+n]=re[n];oq[start+guard+n]=im[n];}}
		double peak=0;for(int n=0;n<oi.length;n++)peak=Math.max(peak,Math.max(Math.abs(oi[n]),Math.abs(oq[n])));byte[] iq=new byte[oi.length*2];for(int n=0;n<oi.length;n++){iq[2*n]=(byte)Math.round(oi[n]/peak*110);iq[2*n+1]=(byte)Math.round(oq[n]/peak*110);}return iq;}
	private static void ifft(double[]r,double[]i){for(int n=0;n<i.length;n++)i[n]=-i[n];fft(r,i);for(int n=0;n<i.length;n++){r[n]/=r.length;i[n]=-i[n]/r.length;}}
	private static int[] prbs(int length){int[]b=new int[length];int sr=0x7ff;for(int n=0;n<length;n++){b[n]=sr&1;int f=(sr^(sr>>2))&1;sr>>=1;if(f!=0)sr|=0x400;}return b;}
	private static void fft(double[]r,double[]i){int z=r.length;for(int n=1,j=0;n<z;n++){int b=z>>1;for(;(j&b)!=0;b>>=1)j^=b;j^=b;if(n<j){double v=r[n];r[n]=r[j];r[j]=v;v=i[n];i[n]=i[j];i[j]=v;}}for(int l=2;l<=z;l<<=1){double a=-2*Math.PI/l,cr=Math.cos(a),ci=Math.sin(a);for(int s=0;s<z;s+=l){double wr=1,wi=0;for(int n=0;n<l/2;n++){int x=s+n,y=x+l/2;double yr=r[y]*wr-i[y]*wi,yi=r[y]*wi+i[y]*wr;r[y]=r[x]-yr;i[y]=i[x]-yi;r[x]+=yr;i[x]+=yi;double nr=wr*cr-wi*ci;wi=wr*ci+wi*cr;wr=nr;}}}}
	private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
