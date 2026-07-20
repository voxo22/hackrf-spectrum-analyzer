package jspectrumanalyzer.iq;

import java.util.Arrays;

/** Realtime DVB-T2 OFDM/QAM monitor. It intentionally stops before FEC/TS decoding. */
final class Dvbt2SignalAnalyzer {
	private static final double BASE_RATE_HZ = 64_000_000d / 7d;
	private static final int[] FFT_SIZES = {32768, 16384};
	private static final int[][] GUARDS = {{1,4},{1,8},{1,16},{1,32},{1,128},{19,128},{19,256}};
	private static final int MAX_POINTS = 2400;
	private static final int RESAMPLE_PHASES = 256;
	private static final int RESAMPLE_TAPS = 64;
	private static final double[][] RESAMPLE_KERNELS = createResampleKernels();
	private String stableModulation, candidateModulation;
	private int candidateHits;
	private double normalizationScale = Double.NaN;
	private String stableMode, stableGuard, syncCandidate;
	private int syncCandidateHits;
	private boolean syncLocked;
	private boolean wideCarrierSearch;
	private int stableBinOffset;
	private int pilotCandidate = -1, pilotCandidateOffset, pilotCandidateHits;
	private volatile int forcedPilotPattern = -1;
	private volatile Dvbt2P2Decoder.Result lockedParameters;

	void setLockedParameters(Dvbt2P2Decoder.Result parameters) {
		lockedParameters = parameters;
		if (parameters != null && parameters.pilotPattern >= 0 && parameters.pilotPattern < 8)
			setPilotPattern(parameters.pilotPattern);
		syncLocked = false;
	}

	void setPilotPattern(int pattern) {
		forcedPilotPattern = pattern >= 0 && pattern < 8 ? pattern : -1;
		pilotCandidate = -1;
		pilotCandidateHits = 0;
		wideCarrierSearch = false;
	}

	Result analyze(byte[] iq, int length, int sampleRateHz) {
		if (sampleRateHz < 8_000_000 || length < 65_536) return Result.empty("need >= 8 MS/s IQ");
		/* Two 32K symbols (including the longest common guard) are sufficient for
		 * live pilot tracking; keeping more only makes every refresh more expensive. */
		int sourceSamples = Math.min(length / 2, (int)Math.ceil(sampleRateHz * 0.009));
		int sourceStart = length / 2 - sourceSamples;
		int count = (int)Math.floor(sourceSamples * BASE_RATE_HZ / sampleRateHz);
		double[] inI = new double[count], inQ = new double[count];
		double step = sampleRateHz / BASE_RATE_HZ;
		resample(iq,length/2,sourceStart,step,inI,inQ);
		Sync best=null;
		Dvbt2P2Decoder.Result locked = lockedParameters;
		for(int size:FFT_SIZES) for(int[] ratio:GUARDS) {
			if (locked != null && (size != 32768 || size*ratio[0]/ratio[1] != locked.guardSamples)) continue;
			if(syncLocked && !(stableMode.equals(size==32768?"32K":"16K")
					&& stableGuard.equals(ratio[0]+"/"+ratio[1]))) continue;
			int guard=size*ratio[0]/ratio[1]; Sync sync=findSync(inI,inQ,size,guard);
			if(best==null || sync.score>best.score+0.008 || Math.abs(sync.score-best.score)<=0.008 && guard>best.guard) best=sync;
		}
		if(best==null || best.score<0.08 || best.start+best.size>count) return Result.empty("searching DVB-T2 OFDM");
		return constellation(inI,inQ,best);
	}

	private void resample(byte[] iq,int totalSamples,int sourceStart,double step,double[] outI,double[] outQ){
		final int half=RESAMPLE_TAPS/2;
		for(int n=0;n<outI.length;n++){double source=sourceStart+n*step;int center=(int)Math.floor(source);double fraction=source-center;int phase=(int)Math.round(fraction*RESAMPLE_PHASES);if(phase==RESAMPLE_PHASES){phase=0;center++;}double[] kernel=RESAMPLE_KERNELS[phase];double sumI=0,sumQ=0,sumW=0;
			for(int tap=0;tap<RESAMPLE_TAPS;tap++){int index=center+tap-half+1;if(index<0||index>=totalSamples)continue;double weight=kernel[tap];sumI+=iq[index*2]*weight;sumQ+=iq[index*2+1]*weight;sumW+=weight;}
			outI[n]=sumI/(128d*Math.max(1e-12,sumW));outQ[n]=sumQ/(128d*Math.max(1e-12,sumW));}
	}

	private static double[][] createResampleKernels(){double[][] kernels=new double[RESAMPLE_PHASES][RESAMPLE_TAPS];int half=RESAMPLE_TAPS/2;double cutoff=.97d;for(int phase=0;phase<RESAMPLE_PHASES;phase++){double fraction=phase/(double)RESAMPLE_PHASES;double sum=0;for(int tap=0;tap<RESAMPLE_TAPS;tap++){double distance=fraction-(tap-half+1);double x=Math.PI*cutoff*distance;double sinc=Math.abs(x)<1e-12?cutoff:cutoff*Math.sin(x)/x;double position=Math.abs(distance)/half;double window=position>=1?0:.42+.5*Math.cos(Math.PI*position)+.08*Math.cos(2*Math.PI*position);kernels[phase][tap]=sinc*window;sum+=kernels[phase][tap];}if(Math.abs(sum)>1e-12)for(int tap=0;tap<RESAMPLE_TAPS;tap++)kernels[phase][tap]/=sum;}return kernels;}

	private Sync findSync(double[] i,double[] q,int size,int guard) {
		int last=i.length-size-guard-1;
		if(last<=0)return new Sync(size,guard,0,0,0);
		int first=Math.max(0,last-2*(size+guard)), stride=Math.max(1,guard/80);
		Sync best=new Sync(size,guard,first+guard,0,0);
		for(int prefix=first;prefix<=last;prefix+=Math.max(1,guard/20)) {
			Sync value=correlate(i,q,size,guard,prefix,stride); if(value.score>best.score)best=value;
		}
		int center=best.start-guard, radius=Math.max(2,guard/20);
		for(int prefix=Math.max(first,center-radius);prefix<=Math.min(last,center+radius);prefix+=Math.max(1,guard/256)) {
			Sync value=correlate(i,q,size,guard,prefix,Math.max(1,guard/512)); if(value.score>best.score)best=value;
		}
		return best;
	}

	private Sync correlate(double[] i,double[] q,int size,int guard,int prefix,int stride) {
		double re=0,im=0,e1=0,e2=0;
		for(int n=0;n<guard;n+=stride){int a=prefix+n,b=a+size;re+=i[a]*i[b]+q[a]*q[b];im+=q[a]*i[b]-i[a]*q[b];e1+=i[a]*i[a]+q[a]*q[a];e2+=i[b]*i[b]+q[b]*q[b];}
		double score=Math.sqrt(re*re+im*im)/Math.max(1e-12,Math.sqrt(e1*e2));
		return new Sync(size,guard,prefix+guard,score,-Math.atan2(im,re)/(2*Math.PI));
	}

	private Result constellation(double[] inI,double[] inQ,Sync sync) {
		double[] re=new double[sync.size],im=new double[sync.size];
		for(int n=0;n<sync.size;n++){double a=-2*Math.PI*sync.cfoBins*n/sync.size,c=Math.cos(a),s=Math.sin(a);re[n]=inI[sync.start+n]*c-inQ[sync.start+n]*s;im[n]=inI[sync.start+n]*s+inQ[sync.start+n]*c;}
		fft(re,im);
		double[] previousRe=null,previousIm=null;
		int previousStart=sync.start-sync.size-sync.guard;
		if(previousStart>=0){previousRe=new double[sync.size];previousIm=new double[sync.size];for(int n=0;n<sync.size;n++){double a=-2*Math.PI*sync.cfoBins*n/sync.size,c=Math.cos(a),s=Math.sin(a);previousRe[n]=inI[previousStart+n]*c-inQ[previousStart+n]*s;previousIm[n]=inI[previousStart+n]*s+inQ[previousStart+n]*c;}fft(previousRe,previousIm);}
		PilotFit pilots=findPilots(re,im,previousRe,previousIm,sync.size);
		if (pilots == null) return Result.empty("searching DVB-T2 data pilots");
		if(pilots.pattern==pilotCandidate&&Math.abs(pilots.binOffset-pilotCandidateOffset)<=2)pilotCandidateHits++;
		else{if(pilotCandidate>=0)wideCarrierSearch=true;pilotCandidate=pilots.pattern;pilotCandidateOffset=pilots.binOffset;pilotCandidateHits=1;}
		if(Math.abs(pilots.binOffset)>=44)wideCarrierSearch=true;
		if(pilotCandidateHits>=5){stableBinOffset=pilots.binOffset;wideCarrierSearch=false;}
		Dvbt2P2Decoder.Result locked=lockedParameters;
		String[] knownMods={"QPSK","16-QAM","64-QAM","256-QAM"};
		String modulation=locked!=null&&locked.plpMod>=0&&locked.plpMod<knownMods.length?knownMods[locked.plpMod]:"--";
		double cp=Math.max(0,Math.min(1,(sync.score-0.12)/0.68));
		double pilot=Math.max(0,Math.min(1,(pilots.coherence-.25)/.7));
		double tracking=100*(.55*sync.score+.45*pilots.coherence),quality=Math.max(0,Math.min(100,(tracking-96)*25));if(sync.score<0.12||pilots.coherence<.18)quality=0;
		if(pilotCandidateHits<3)quality=Math.min(quality,35);else if(pilotCandidateHits<6)quality=Math.min(quality,55);
		if(forcedPilotPattern<0)quality=Math.min(quality,40);
		String state=quality>=55?"DVB-T2 OFDM locked":quality>=25?"weak DVB-T2":"searching DVB-T2";
		String mode=sync.size==32768?"32K":"16K", guard=guardName(sync);
		String combined=mode+" "+guard;
		if(combined.equals(syncCandidate))syncCandidateHits++;else{syncCandidate=combined;syncCandidateHits=1;}
		if(stableMode==null || (!syncLocked && syncCandidateHits>=3)){stableMode=mode;stableGuard=guard;}
		if(syncCandidateHits>=3 && sync.score>.25 && pilots.coherence>.55) syncLocked=true;
		return new Result(new float[0],quality,0,sync.score,pilots.coherence,
				(sync.cfoBins+pilots.binOffset)*BASE_RATE_HZ/sync.size,
				stableMode,stableGuard,modulation,"PP"+(pilots.pattern+1)+(forcedPilotPattern<0?"?":""),state);
	}

	private PilotFit findPilots(double[] re,double[] im,double[] previousRe,double[] previousIm,int size){PilotFit best=null;int[] dx={3,6,6,12,12,24,24,6},dy={4,2,4,2,4,2,4,16};int[] actives=size==32768?new int[]{27265,27841}:new int[]{13633,13921};
		for(int active:actives){Dvbt2P2Decoder.Result locked=lockedParameters;if(locked!=null&&((locked.extended&&active!=(size==32768?27841:13921))||(!locked.extended&&active!=(size==32768?27265:13633))))continue;int extension=(active-(size==32768?27265:13633))/2,kOffset=extension==0?(size==32768?288:144):0;int[] prbs=prbs(active+kOffset);int half=active/2;
			int offsetFirst=pilotCandidateHits>=5?stableBinOffset-4:wideCarrierSearch?-320:-48;
			int offsetLast=pilotCandidateHits>=5?stableBinOffset+4:wideCarrierSearch?320:48;
			int offsetStep=pilotCandidateHits>=5?1:wideCarrierSearch?4:2;
			for(int pattern=0;pattern<8;pattern++){if(forcedPilotPattern>=0&&pattern!=forcedPilotPattern)continue;if(forcedPilotPattern<0&&pilotCandidateHits>=5&&pattern!=pilotCandidate)continue;for(int phase=0;phase<dy[pattern];phase++)for(int binOffset=offsetFirst;binOffset<=offsetLast;binOffset+=offsetStep){int spacing=dx[pattern]*dy[pattern],first=extension+dx[pattern]*phase;int count=Math.max(0,(active-1-first)/spacing+1);if(count<3)continue;int[] carriers=new int[count];Complex[] channel=new Complex[count];double sr=0,si=0;
				for(int p=0,k=first;p<count;p++,k+=spacing){int bin=(k-half+size+binOffset)%size,sign=prbs[k+kOffset]==0?1:-1;carriers[p]=k;channel[p]=new Complex(re[bin]*sign,im[bin]*sign);if(p>0){Complex a=channel[p-1],b=channel[p];double den=Math.sqrt((a.real*a.real+a.imag*a.imag)*(b.real*b.real+b.imag*b.imag));if(den>1e-12){sr+=(a.real*b.real+a.imag*b.imag)/den;si+=(a.real*b.imag-a.imag*b.real)/den;}}}
				double coherence=Math.sqrt(sr*sr+si*si)/(count-1);if(previousRe!=null){int previousPhase=(phase+dy[pattern]-1)%dy[pattern];double previous=pilotCoherence(previousRe,previousIm,size,active,extension,kOffset,dx[pattern],dy[pattern],previousPhase,binOffset,prbs);coherence=Math.sqrt(Math.max(0,coherence*previous));}PilotFit fit=new PilotFit(active,pattern,phase,dx[pattern],dy[pattern],binOffset,carriers,channel,coherence);if(best==null||fit.coherence>best.coherence+.002||(Math.abs(fit.coherence-best.coherence)<=.002&&fit.carriers.length>best.carriers.length))best=fit;}}}
		return best;}
	private double pilotCoherence(double[]re,double[]im,int size,int active,int extension,int kOffset,int dx,int dy,int phase,int binOffset,int[]prbs){int spacing=dx*dy,first=extension+dx*phase,half=active/2;double sr=0,si=0;Complex old=null;int pairs=0;for(int k=first;k<active;k+=spacing){int bin=(k-half+size+binOffset)%size,sign=prbs[k+kOffset]==0?1:-1;Complex value=new Complex(re[bin]*sign,im[bin]*sign);if(old!=null){double den=Math.sqrt((old.real*old.real+old.imag*old.imag)*(value.real*value.real+value.imag*value.imag));if(den>1e-12){sr+=(old.real*value.real+old.imag*value.imag)/den;si+=(old.real*value.imag-old.imag*value.real)/den;pairs++;}}old=value;}return pairs==0?0:Math.sqrt(sr*sr+si*si)/pairs;}
	private int[] prbs(int length){int[] bits=new int[length];int sr=0x7ff;for(int n=0;n<length;n++){bits[n]=sr&1;int b=(sr^(sr>>2))&1;sr>>=1;if(b!=0)sr|=0x400;}return bits;}

	private String guardName(Sync s){for(int[] r:GUARDS)if(s.guard==s.size*r[0]/r[1])return r[0]+"/"+r[1];return "--";}
	private String stabilize(String value){if(value.equals(candidateModulation))candidateHits++;else{candidateModulation=value;candidateHits=1;}if(stableModulation==null||candidateHits>=3)stableModulation=value;return stableModulation;}
	private double rotationAngle(int modulation){double[] degrees={29.0,16.8,8.6,3.58};return modulation>=0&&modulation<degrees.length?-Math.toRadians(degrees[modulation]):0;}
	private void rotate(float[] points,double angle){double c=Math.cos(angle),s=Math.sin(angle);for(int n=0;n+1<points.length;n+=2){double x=points[n],y=points[n+1];points[n]=(float)(x*c-y*s);points[n+1]=(float)(x*s+y*c);}}

	private Qam fit(float[] p){Qam best=null;for(int levels:new int[]{2,4,8,16}){Qam q=fit(p,levels);if(best==null||q.mer>best.mer)best=q;}return best;}
	private Qam fit(float[] p,int levels){double power=0;for(float v:p)power+=v*v;double rms=Math.sqrt(power/Math.max(1,p.length)),target=Math.sqrt((levels*levels-1d)/3d),scale=target/Math.max(1e-12,rms),error=0,signal=0;
		for(float raw:p){double v=raw*scale,n=Math.max(-(levels-1),Math.min(levels-1,2*Math.rint((v-1)/2)+1));error+=(v-n)*(v-n);signal+=n*n;}double mer=10*Math.log10(signal/Math.max(1e-12,error));return new Qam(levels==2?"QPSK":levels==4?"16-QAM":levels==8?"64-QAM":"256-QAM",mer);}
	private void normalize(float[] p,String modulation){if(p.length==0)return;int levels="256-QAM".equals(modulation)?16:"64-QAM".equals(modulation)?8:"16-QAM".equals(modulation)?4:2;float[] abs=new float[p.length];for(int n=0;n<p.length;n++)abs[n]=Math.abs(p[n]);Arrays.sort(abs);double median=abs[abs.length/2],target=levels/2d,candidate=target/Math.max(1e-12,median);if(Double.isNaN(normalizationScale))normalizationScale=candidate;else{candidate=Math.max(normalizationScale*.9,Math.min(normalizationScale*1.1,candidate));normalizationScale=.98*normalizationScale+.02*candidate;}for(int n=0;n<p.length;n++)p[n]*=normalizationScale;}

	private void fft(double[] real,double[] imag){int size=real.length;for(int i=1,j=0;i<size;i++){int bit=size>>1;for(;(j&bit)!=0;bit>>=1)j^=bit;j^=bit;if(i<j){double v=real[i];real[i]=real[j];real[j]=v;v=imag[i];imag[i]=imag[j];imag[j]=v;}}for(int len=2;len<=size;len<<=1){double a=-2*Math.PI/len,cr=Math.cos(a),ci=Math.sin(a);for(int st=0;st<size;st+=len){double wr=1,wi=0;for(int j=0;j<len/2;j++){int x=st+j,y=x+len/2;double yr=real[y]*wr-imag[y]*wi,yi=real[y]*wi+imag[y]*wr;real[y]=real[x]-yr;imag[y]=imag[x]-yi;real[x]+=yr;imag[x]+=yi;double nr=wr*cr-wi*ci;wi=wr*ci+wi*cr;wr=nr;}}}}
	private static final class Sync{final int size,guard,start;final double score,cfoBins;Sync(int z,int g,int s,double q,double c){size=z;guard=g;start=s;score=q;cfoBins=c;}}
	private static final class Complex{final double real,imag;Complex(double r,double i){real=r;imag=i;}}
	private static final class PilotFit{final int active,pattern,phase,dx,dy,binOffset;final int[] carriers;final Complex[] channel;final double coherence;PilotFit(int a,int p,int ph,int x,int y,int o,int[]c,Complex[]h,double q){active=a;pattern=p;phase=ph;dx=x;dy=y;binOffset=o;carriers=c;channel=h;coherence=q;}
		boolean isPilot(int k){int spacing=dx*dy,extension=(active-(active>20000?27265:13633))/2;int delta=k-extension-dx*phase;return delta>=0&&delta%spacing==0;}
		Complex channelAt(int k){int idx=Arrays.binarySearch(carriers,k);if(idx>=0)return channel[idx];idx=-idx-1;if(idx<=0)return channel[0];if(idx>=carriers.length)return channel[channel.length-1];int a=idx-1,b=idx;double t=(k-carriers[a])/(double)(carriers[b]-carriers[a]);return new Complex(channel[a].real+(channel[b].real-channel[a].real)*t,channel[a].imag+(channel[b].imag-channel[a].imag)*t);}}
	private static final class Qam{final String name;final double mer;Qam(String n,double m){name=n;mer=m;}}
	static final class Result{final float[] points,p2Points,l1PostPoints;final double quality,merDb,cpCorrelation,pilotCoherence,cfoHz;final String fftMode,guard,modulation,pilotPattern,state,codeRate,transmissionDetails,l1PreDetails,l1PostDetails,l1PostConstellation;final boolean l1Locked,l1PostLocked;
		Result(float[]p,double q,double m,double c,double pc,double f,String fft,String g,String mod,String pp,String s){this(p,q,m,c,pc,f,fft,g,mod,pp,s,"--","",new float[0],"","",false,false,new float[0],"--");}
		Result(float[]p,double q,double m,double c,double pc,double f,String fft,String g,String mod,String pp,String s,String rate){this(p,q,m,c,pc,f,fft,g,mod,pp,s,rate,"",new float[0],"","",false,false,new float[0],"--");}
		Result(float[]p,double q,double m,double c,double pc,double f,String fft,String g,String mod,String pp,String s,String rate,String details,float[]p2,String pre,String post,boolean locked,boolean postLocked,float[]postPoints,String postMod){points=p;quality=q;merDb=m;cpCorrelation=c;pilotCoherence=pc;cfoHz=f;fftMode=fft;guard=g;modulation=mod;pilotPattern=pp;state=s;codeRate=rate;transmissionDetails=details;p2Points=p2;l1PreDetails=pre;l1PostDetails=post;l1Locked=locked;l1PostLocked=postLocked;l1PostPoints=postPoints;l1PostConstellation=postMod;}
		Result waitingForSignalling(){return waitingForSignalling("waiting for DVB-T2 L1 lock");}
		Result waitingForSignalling(String status){return new Result(new float[0],0,merDb,cpCorrelation,pilotCoherence,cfoHz,fftMode,guard,"--",pilotPattern,status,"--");}
		Result withSignalling(Dvbt2P2Decoder.Result sig){
			if(sig==null)return waitingForSignalling();
			String[] rates={"1/2","3/5","2/3","3/4","4/5","5/6"};
			String[] mods={"QPSK","16-QAM","64-QAM","256-QAM"};
			String[] l1Mods={"BPSK","QPSK","16-QAM","64-QAM"};
			String[] paprNames={"Off","ACE","TR","ACE + TR"};
			String[] versions={"1.1.1","1.2.1","1.3.1"};
			String rate=name(sig.plpCodeRate,rates),mod=name(sig.plpMod,mods);
			boolean live=cpCorrelation>.08&&pilotCoherence>.18,postLocked=sig.l1PostValid;
			if(!postLocked){rate="--";mod=modulation;}
			double usedMer=0;
			double tracking=100*Math.max(0,Math.min(1,.55*cpCorrelation+.45*pilotCoherence));
			double q=live?Math.max(0,Math.min(100,(tracking-96)*25)):0;
			String preamble=sig.preamble==0?"T2-Base SISO":sig.preamble==1?"T2-Base MISO":"P1 type "+sig.preamble;
			String carriers=sig.bwtExt==1?"Extended":sig.bwtExt==0?"Normal":sig.extended?"Extended":"Normal";
			String papr=name(sig.papr,paprNames),version=name(sig.t2Version,versions),l1Mod=name(sig.l1PostMod,l1Mods);
			String fec=sig.plpFec==1?"Normal FEC frame (64K)":sig.plpFec==0?"Short FEC frame (16K)":"Reserved ["+sig.plpFec+"]";
			String details=preamble+"   "+sig.fftMode+"   GI "+guardName(sig.guard)+"   "+carriers+"   PP"+(sig.pilotPattern+1)+(postLocked?"   "+mod+"   code "+rate:"");
			String pre="Profile / preamble|"+preamble+raw(sig.s1)
				+"\nFFT size|"+sig.fftMode
				+"\nGuard interval (GI)|"+guardName(sig.guard)+raw(sig.guard)
				+"\nCarrier mode|"+carriers+raw(sig.bwtExt)
				+"\nPAPR reduction|"+papr+raw(sig.papr)
				+"\nPilot pattern|PP"+(sig.pilotPattern+1)+raw(sig.pilotPattern)
				+"\nFEF present|"+yesNo(sig.fefPresent)
				+"\nDVB-T2 version|"+version+raw(sig.t2Version)
				+"\nBase/Lite flag|"+(sig.t2BaseLite==1?"T2-Lite":"T2-Base")
				+"\n\nL1-post constellation|"+l1Mod+raw(sig.l1PostMod)
				+"\nL1 code rate|"+(sig.l1Code==0?"1/2":"Reserved")+raw(sig.l1Code)
				+"\nL1 FEC type|"+(sig.l1Fec==0?"16K LDPC":"Reserved")+raw(sig.l1Fec)
				+"\nL1 repetition|"+yesNo(sig.l1Repetition)
				+"\nL1-post scrambled|"+yesNo(sig.l1PostScrambled)
				+"\nL1-post extension|"+yesNo(sig.l1PostExtension)
				+"\nL1-post size|"+sig.l1PostSize+" cells"
				+"\nL1-post information size|"+sig.l1PostInfoSize+" bits"
				+"\n\nCell ID|"+sig.cellId
				+"\nNetwork ID|"+sig.networkId
				+"\nT2 system ID|"+sig.systemId
				+"\nT2 frames per superframe|"+sig.numT2Frames
				+"\nData symbols per frame|"+sig.numDataSymbols
				+"\nNumber of RF channels|"+sig.numRf
				+"\nCurrent RF index|"+sig.currentRfIndex
				+"\nTX ID availability|"+sig.txIdAvailability
				+"\nL1 TYPE field|"+sig.l1Type;
			String post="";
			if(postLocked){
				String ti=sig.timeIlType==0?"One TI block per T2 frame":sig.timeIlType==1?"Multiple TI blocks per T2 frame":"--";
				String plpMode=sig.plpMode==0?"Not specified":sig.plpMode==1?"Normal mode":sig.plpMode==2?"High-efficiency mode":"Reserved";
				post="Sub-slices per frame|"+sig.subSlicesPerFrame
					+"\nNumber of PLPs|"+sig.numPlp
					+"\n\nPLP ID|"+sig.plpId
					+"\nPLP type|"+plpType(sig.plpType)+raw(sig.plpType)
					+"\nPayload type|"+payloadType(sig.plpPayloadType)+raw(sig.plpPayloadType)
					+"\nConstellation|"+mod+raw(sig.plpMod)
					+"\nCode rate|"+rate+raw(sig.plpCodeRate)
					+"\nRotated constellation|"+yesNo(sig.plpRotation)
					+"\nFEC frame|"+fec+raw(sig.plpFec)
					+"\nMaximum FEC blocks|"+sig.plpNumBlocksMax
					+"\nPLP group ID|"+sig.plpGroupId
					+"\nFirst RF / frame index|"+sig.firstRfIndex+" / "+sig.firstFrameIndex
					+"\nFrame interval|"+sig.frameInterval
					+"\nTime interleaving length|"+sig.timeIlLength
					+"\nTime interleaving type|"+ti+raw(sig.timeIlType)
					+"\nIn-band A / B|"+yesNo(sig.inBandA)+" / "+yesNo(sig.inBandB)
					+"\nPLP mode|"+plpMode+raw(sig.plpMode)
					+"\nStatic / static padding|"+yesNo(sig.staticFlag)+" / "+yesNo(sig.staticPaddingFlag)
					+"\n\nRF index|"+sig.rfIndex
					+"\nRF frequency|"+sig.rfFrequency
					+"\nFEF type|"+sig.fefType
					+"\nFEF length|"+sig.fefLength
					+"\nFEF interval|"+sig.fefInterval
					+"\nFEF length MSB|"+sig.fefLengthMsb
					+"\nReserved 2|"+sig.reserved2
					+"\nNumber of AUX streams|"+sig.numAux
					+"\nAUX config RFU|"+sig.auxConfigRfu;
			}
			return new Result(new float[0],q,usedMer,cpCorrelation,pilotCoherence,cfoHz,sig.fftMode,guardName(sig.guard),mod,"PP"+(sig.pilotPattern+1),live?"DVB-T2 live tracking":"DVB-T2 L1 locked",rate,details,sig.p2Points,pre,post,true,postLocked,sig.l1PostPoints,l1Mod);
		}
		private static String name(int value,String[] names){return value>=0&&value<names.length?names[value]:"--";}
		private static String raw(int value){return value<0?"":"  ["+value+"]";}
		private static String yesNo(int value){return value==1?"Yes":value==0?"No":"--";}
		private static int modulationIndex(String value){return "256-QAM".equals(value)?3:"64-QAM".equals(value)?2:"16-QAM".equals(value)?1:0;}
		private static double demodQuality(double mer,int modulation,int codeRate){double[] low={2,6,10,14},high={18,24,30,34};int m=Math.max(0,Math.min(3,modulation));double adjustment=codeRate<0?0:Math.max(0,codeRate-2)*.7,score=(mer-(low[m]+adjustment))*100/Math.max(1,high[m]-low[m]);return Math.max(0,Math.min(100,score));}
		private static String plpType(int type){return type==0?"Common PLP":type==1?"Data PLP type 1":type==2?"Data PLP type 2":"--";}
		private static String payloadType(int type){return type==3?"Transport Stream (TS)":type==0?"Generic Packetized Stream":type==1?"Generic Continuous Stream":type==2?"GSE":"type "+type;}
		private static String guardName(int mode){String[] names={"1/32","1/16","1/8","1/4","1/128","19/128","19/256"};return mode>=0&&mode<names.length?names[mode]:"--";}
		static Result empty(String state){return new Result(new float[0],0,0,0,0,0,"--","--","--","--",state);}}
}
