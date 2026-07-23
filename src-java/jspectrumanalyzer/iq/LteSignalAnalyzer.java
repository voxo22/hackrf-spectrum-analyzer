package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** First LTE acquisition stage: time-domain search for the three LTE PSS sequences. */
final class LteSignalAnalyzer {
	private static final int[] ROOT = { 25, 29, 34 };
	private static int diagnosticDistance,diagnosticMode,diagnosticPorts,diagnosticFrames,diagnosticQuarter;
	private static double diagnosticLlr;
	private static String diagnosticSi="";
	private static final double[] diagnosticEvm=new double[3];
	private static final double[] diagnosticNoise=new double[4];
	private static final Map<Long,ResampleKernel> RESAMPLE_KERNELS=new LinkedHashMap<Long,ResampleKernel>();
	private static double[] diagnosticConstellation=new double[0];
	private static int diagnosticConstellationPorts;
	private static double diagnosticConstellationEvm;
	private final Map<Integer,SiData> siCache=new LinkedHashMap<Integer,SiData>();
	private final Map<Integer,CellLock> cellLocks=new LinkedHashMap<Integer,CellLock>();
	private int analysesSinceFullSearch;

	Result analyze(byte[] iq, int length, int sampleRateHz) {
		resetPbchDiagnostics();
		int inputSamples=Math.min(length, iq == null ? 0 : iq.length)/2;
		if (sampleRateHz < 1_100_000 || inputSamples < sampleRateHz/80)
			return Result.empty("Need at least 1.1 MS/s and 12.5 ms of LTE IQ");
		int rate=1_920_000,decimation=Math.max(1,(int)Math.round(sampleRateHz/(double)rate));
		double sampleScale=sampleRateHz/(double)rate;
		double[][] resampled=resample(iq,inputSamples,sampleRateHz,rate);
		int samples=resampled[0].length;double[] inI=resampled[0],inQ=resampled[1];
		int fft=(int)Math.round(rate/15_000d);
		if(fft < 96 || samples < fft*4) return Result.empty("LTE PSS sampling geometry unavailable");
		double[][][] references=new double[3][][];
		for(int id=0;id<3;id++) references[id]=reference(ROOT[id], fft);
		/* Coarse search the complete capture, then refine the best peak sample by
		 * sample. This preserves good later frames without the old brute-force cost. */
		/* PSS repeats every 5 ms. A 20 ms acquisition aperture contains four
		 * occurrences of every cell; the remaining capture stays available for
		 * PBCH, SIB, paging and load decoding. */
		int step=Math.max(2,fft/32),acquisitionSamples=Math.min(samples,rate/50);
		double best=0; int bestId=-1,bestAt=-1,bestOrientation=1; int peaks=0;
		List<Candidate> candidates=new ArrayList<Candidate>();
		boolean fullAcquisition=cellLocks.isEmpty()||analysesSinceFullSearch>=20;
		boolean[][] search=new boolean[3][2];
		if(fullAcquisition){for(int id=0;id<3;id++)java.util.Arrays.fill(search[id],true);analysesSinceFullSearch=0;}
		else for(CellLock lock:cellLocks.values())if(lock.nid2>=0&&lock.nid2<3)search[lock.nid2][lock.orientation<0?1:0]=true;
		analysesSinceFullSearch++;
		for(int id=0;id<3;id++) for(int orientation=0;orientation<2;orientation++) {
			if(!search[id][orientation])continue;
			List<PssPeak> pssPeaks=findPssPeaks(inI,inQ,references[id],orientation,fft,rate,acquisitionSamples,step,fullAcquisition?5:8);
			for(int peakIndex=0;peakIndex<pssPeaks.size();peakIndex++) {
				PssPeak peak=pssPeaks.get(peakIndex);int localAt=peak.sample;double confirmed=peak.correlation;
				if(confirmed<=0.10)continue;
				List<SssDetection> sssCandidates=detectSssCandidates(inI,inQ,localAt,fft,id,orientation,4);
				if(confirmed>best){best=confirmed;bestId=id;bestAt=(int)Math.round(localAt*sampleScale);bestOrientation=orientation==0?1:-1;}
				if(sssCandidates.isEmpty()){candidates.add(new Candidate(id,confirmed,(int)Math.round(localAt*sampleScale),orientation==0?1:-1,-1,0,-1,false,-1,-1,MibData.EMPTY,peak.halfFrameSamples,peak.samplingPpm,peak.cfoHz));peaks++;continue;}
				for(int sssIndex=0;sssIndex<sssCandidates.size();sssIndex++){SssDetection sss=sssCandidates.get(sssIndex);
				int pbch=-1,frameStart=-1;
				if(sss.detected) {
					int cp=sss.extendedCp?fft/4:Math.max(1,(int)Math.round(fft*10d/128d));
					pbch=(int)Math.round((localAt+fft+cp)*sampleScale);
					int shortCp=sss.extendedCp?fft/4:Math.max(1,(int)Math.round(fft*9d/128d));
					frameStart=(int)Math.round((localAt-(sss.subframe==5?rate/200d:0d)
							-(6d*fft+cp+6d*shortCp))*sampleScale);
				}
				MibData mib=MibData.EMPTY;
				candidates.add(new Candidate(id,confirmed,(int)Math.round(localAt*sampleScale),orientation==0?1:-1,
						sss.detected ? 3*sss.nid1+id : -1,sss.correlation,sss.subframe,sss.extendedCp,
						frameStart,pbch,mib,peak.halfFrameSamples,peak.samplingPpm,peak.cfoHz)); peaks++;
				if(confirmed>best){best=confirmed;bestId=id;bestAt=(int)Math.round(localAt*sampleScale);bestOrientation=orientation==0?1:-1;}
				}
			}
		}
		Collections.sort(candidates,(a,b)->{
			if(a.mib.valid!=b.mib.valid)return a.mib.valid?-1:1;
			if((a.pci>=0)!=(b.pci>=0)) return a.pci>=0?-1:1;
			return Double.compare(a.pci>=0?b.sssCorrelation:b.correlation,
					a.pci>=0?a.sssCorrelation:a.correlation);
		});
		List<Candidate> unique=new ArrayList<Candidate>();for(Candidate candidate:candidates){boolean duplicate=false;for(Candidate kept:unique)if(candidate.pci>=0&&candidate.pci==kept.pci){duplicate=true;break;}if(!duplicate)unique.add(candidate);if(unique.size()>=8)break;}candidates=unique;
		for(int index=0,decoded=0;index<candidates.size()&&decoded<3;index++){
			Candidate candidate=candidates.get(index);if(candidate.pci<0)continue;
			CellLock lock=cellLocks.get(candidate.pci);
			int pssAt=(int)Math.round(candidate.sample/sampleScale),mibPssAt=findMibPssAt(inI,inQ,pssAt,rate,fft,candidate);
			if(mibPssAt>=0)mibPssAt=refinePssAt(inI,inQ,reference(ROOT[candidate.nid2],fft),candidate.iqOrientation>0?0:1,mibPssAt,fft);
			/* MIB is static apart from SFN. Keep the decoded radio configuration, but
			 * refresh PBCH while system information is still missing because its SFN
			 * selects the SIB windows. */
			boolean needFreshMib=lock==null||!lock.mib.valid||!lock.sib1.valid||!lock.si.valid;
			MibData fresh=needFreshMib&&mibPssAt>=0
					?decodeMibFrames(inI,inQ,mibPssAt,rate,fft,candidate.pci,candidate.iqOrientation>0?0:1,candidate.extendedCp,candidate.halfFrameSamples)
					:MibData.EMPTY;
			MibData mib=fresh.valid?fresh:lock!=null?lock.mib:MibData.EMPTY;
			Candidate timed=candidate;
			if(mibPssAt>=0){int cp=candidate.extendedCp?fft/4:Math.max(1,(int)Math.round(fft*10d/128d)),shortCp=candidate.extendedCp?fft/4:Math.max(1,(int)Math.round(fft*9d/128d));int refinedFrame=(int)Math.round((mibPssAt-(6d*fft+cp+6d*shortCp))*sampleScale),refinedPbch=(int)Math.round((mibPssAt+fft+cp)*sampleScale);timed=candidate.withTiming(refinedFrame,refinedPbch);}
			Candidate decodedCandidate=timed.withMib(mib);
			if(mib.valid){
				if(lock!=null)lock.restore(decodedCandidate);
				decodedCandidate.cfi=decodeCfi(iq,inputSamples,sampleRateHz,decodedCandidate);
				decodedCandidate.control=controlRegion(decodedCandidate);
				decodedCandidate.pdcch=extractPdcch(iq,inputSamples,sampleRateHz,decodedCandidate);
				if(!decodedCandidate.sib1.valid)scanSib1Pdcch(iq,inputSamples,sampleRateHz,decodedCandidate);
				if(decodedCandidate.sib1.valid&&!decodedCandidate.si.valid)scanFirstSiWindow(iq,inputSamples,sampleRateHz,decodedCandidate);
				if(decodedCandidate.si.valid)siCache.put(decodedCandidate.pci,decodedCandidate.si);
				else if(siCache.containsKey(decodedCandidate.pci))decodedCandidate.si=siCache.get(decodedCandidate.pci);
				if(decodedCandidate.si.valid)scanPagingPdcch(iq,inputSamples,sampleRateHz,decodedCandidate);
				decodedCandidate.load=estimateDownlinkLoad(iq,inputSamples,sampleRateHz,decodedCandidate);
				if(lock==null){lock=new CellLock();cellLocks.put(decodedCandidate.pci,lock);}
				lock.capture(decodedCandidate);
			}
			candidates.set(index,decodedCandidate);decoded++;
		}
		Collections.sort(candidates,(a,b)->{if(a.mib.valid!=b.mib.valid)return a.mib.valid?-1:1;if((a.pci>=0)!=(b.pci>=0))return a.pci>=0?-1:1;return Double.compare(b.sssCorrelation,a.sssCorrelation);});
		boolean detected=best>=0.16;
		double quality=Math.min(100, best*300d);
		Candidate primary=candidates.isEmpty()?null:candidates.get(0);
		String state=primary!=null&&primary.mib.valid?"LTE MIB decoded (CRC OK)":primary!=null && primary.pci>=0 ? "LTE PSS + SSS decoded" : detected ? "LTE PSS detected" : best>=0.10 ? "Possible LTE PSS" : "Searching LTE PSS";
		return new Result(detected,primary==null?bestId:primary.nid2,primary==null?best:primary.correlation,
				primary==null?bestAt:primary.sample,primary==null?bestOrientation:primary.iqOrientation,quality,peaks,decimation,fft,
				primary==null?-1:primary.pci,primary==null?0:primary.sssCorrelation,
				primary==null?-1:primary.subframe,primary!=null&&primary.extendedCp,
				primary==null?-1:primary.frameStartSample,primary==null?-1:primary.pbchSample,
				primary==null?MibData.EMPTY:primary.mib,
				Collections.unmodifiableList(candidates),state,diagnosticConstellation.clone(),
				diagnosticConstellationPorts,diagnosticConstellationEvm);
	}

	private static MibData decodeMibFrames(double[] inI,double[] inQ,int pssAt,int rate,int fft,int pci,int orientation,boolean extendedCp,double halfFrameSamples){
		double frame=2d*halfFrameSamples;double[][] pssReference=reference(ROOT[pci%3],fft);
		double[][][] cached=new double[7][][];
		for(int offset=-3;offset<=3;offset++){int expected=(int)Math.round(pssAt+offset*frame),at=refinePssAt(inI,inQ,pssReference,orientation,expected,fft);if(at>=fft&&at+6*fft<inI.length)cached[offset+3]=extractPbch(inI,inQ,at,fft,pci,orientation,extendedCp);}
		/* First try every complete, consecutive four-frame PBCH cycle. */
		for(int first=0;first+3<cached.length;first++){boolean complete=true;for(int n=0;n<4;n++)if(cached[first+n]==null){complete=false;break;}if(!complete)continue;for(int mode=0;mode<cached[first].length;mode++){double[][] values=new double[4][];for(int n=0;n<4;n++)values[n]=cached[first+n][mode];MibData result=decodePbchFrames(values,pci,mode,4);if(result.valid)return result;}}
		/* At capture edges keep only genuinely consecutive fallback runs. */
		for(int first=0;first<cached.length;){while(first<cached.length&&cached[first]==null)first++;int end=first;while(end<cached.length&&cached[end]!=null)end++;int run=Math.min(3,end-first);if(run>0)for(int offset=0;offset+run<=end-first;offset++)for(int mode=0;mode<cached[first+offset].length;mode++){double[][] values=new double[run][];for(int n=0;n<run;n++)values[n]=cached[first+offset+n][mode];MibData result=decodePbchFrames(values,pci,mode,1);if(result.valid)return result;}first=Math.max(end,first+1);}
		return MibData.EMPTY;
	}

	private static int findMibPssAt(double[] inI,double[] inQ,int pssAt,int rate,int fft,Candidate candidate){
		if(candidate.subframe==0)return pssAt;if(candidate.subframe!=5)return -1;int period=(int)Math.round(candidate.halfFrameSamples),nid1=candidate.pci/3,orientation=candidate.iqOrientation>0?0:1;
		for(int direction:new int[]{-1,1}){int adjacent=pssAt+direction*period;if(adjacent<fft||adjacent+2*fft>=inI.length)continue;for(SssDetection detection:detectSssCandidates(inI,inQ,adjacent,fft,candidate.nid2,orientation,8))if(detection.detected&&detection.subframe==0&&detection.nid1==nid1)return adjacent;}return -1;
	}

	private static int refinePssAt(double[] inI,double[] inQ,double[][] reference,int orientation,int expected,int fft){
		int bestAt=expected;double best=-1;for(int at=Math.max(0,expected-24);at<=Math.min(inI.length-fft,expected+24);at++){double score=correlation(inI,inQ,reference,orientation,at,fft);if(score>best){best=score;bestAt=at;}}return bestAt;
	}

	private static List<PssPeak> findPssPeaks(double[] inI,double[] inQ,double[][] reference,int orientation,int fft,int rate,int samples,int step,int limit){
		int period=(int)Math.round(rate/200d),bins=(period+step-1)/step;double[] scores=new double[bins];int[] locations=new int[bins];java.util.Arrays.fill(locations,-1);
		for(int at=0;at+fft<=samples;at+=step){double score=correlation(inI,inQ,reference,orientation,at,fft),phase=Math.floorMod(at,period);int bin=Math.min(bins-1,(int)Math.round(phase/(double)step));if(score>scores[bin]){scores[bin]=score;locations[bin]=at;}}
		List<PssPeak> result=new ArrayList<PssPeak>();boolean[] blocked=new boolean[bins];int guard=Math.max(2,fft/(2*step));
		for(int pick=0;pick<limit;pick++){int selected=-1;double selectedScore=0;for(int bin=0;bin<bins;bin++)if(!blocked[bin]&&scores[bin]>selectedScore){selectedScore=scores[bin];selected=bin;}if(selected<0)break;int coarse=locations[selected],bestAt=coarse;double bestScore=0;for(int at=Math.max(0,coarse-step);at<=Math.min(samples-fft,coarse+step);at++){double score=correlation(inI,inQ,reference,orientation,at,fft);if(score>bestScore){bestScore=score;bestAt=at;}}double repeat=repeatScore(inI,inQ,reference,orientation,bestAt,fft,rate),measuredPeriod=measurePssPeriod(inI,inQ,reference,orientation,bestAt,fft,period);double ppm=(measuredPeriod/period-1d)*1e6;if(!Double.isFinite(ppm)||Math.abs(ppm)>100){measuredPeriod=period;ppm=0;}int cp=Math.max(1,(int)Math.round(fft*9d/128d));double omega=cyclicPrefixOffset(inI,inQ,bestAt,fft,cp),cfo=omega*rate/(2d*Math.PI)*(orientation==0?1:-1);result.add(new PssPeak(bestAt,Math.min(bestScore,repeat),measuredPeriod,ppm,cfo));for(int delta=-guard;delta<=guard;delta++)blocked[Math.floorMod(selected+delta,bins)]=true;}
		Collections.sort(result,(a,b)->Double.compare(b.correlation,a.correlation));return result;
	}

	private static double measurePssPeriod(double[] inI,double[] inQ,double[][] reference,int orientation,int anchor,int fft,int nominal){double sx=0,sy=0,sxx=0,sxy=0;int count=0;for(int multiple=-8;multiple<=8;multiple++){int expected=anchor+multiple*nominal;if(expected<0||expected+fft>inI.length)continue;int bestAt=expected;double best=0;for(int at=Math.max(0,expected-64);at<=Math.min(inI.length-fft,expected+64);at++){double score=correlation(inI,inQ,reference,orientation,at,fft);if(score>best){best=score;bestAt=at;}}if(best<.10)continue;sx+=multiple;sy+=bestAt;sxx+=multiple*multiple;sxy+=multiple*(double)bestAt;count++;}double den=count*sxx-sx*sx;return count<3||Math.abs(den)<1e-9?nominal:(count*sxy-sx*sy)/den;}

	private static double[][] resample(byte[] iq,int inputSamples,int inputRate,int outputRate){
		int outputSamples=(int)Math.floor(inputSamples*(outputRate/(double)inputRate));double[] re=new double[outputSamples],im=new double[outputSamples];
		if(inputRate==outputRate){for(int n=0;n<outputSamples;n++){re[n]=iq[2*n]/128d;im[n]=iq[2*n+1]/128d;}return new double[][]{re,im};}
		double ratio=inputRate/(double)outputRate;ResampleKernel kernel=resampleKernel(inputRate,outputRate);
		for(int n=0;n<outputSamples;n++){double position=n*ratio;int center=(int)Math.floor(position),phase=(int)Math.round((position-center)*ResampleKernel.PHASES);if(phase==ResampleKernel.PHASES){phase=0;center++;}double sum=0,ar=0,ai=0;double[] weights=kernel.weights[phase];int first=center-kernel.radius+1;for(int tap=0;tap<weights.length;tap++){int k=first+tap;if(k<0||k>=inputSamples)continue;double w=weights[tap];ar+=w*iq[2*k];ai+=w*iq[2*k+1];sum+=w;}if(Math.abs(sum)>1e-12){re[n]=ar/(128d*sum);im[n]=ai/(128d*sum);}}
		return new double[][]{re,im};
	}
	private static synchronized ResampleKernel resampleKernel(int inputRate,int outputRate){long key=((long)inputRate<<32)|(outputRate&0xffffffffL);ResampleKernel result=RESAMPLE_KERNELS.get(key);if(result==null){result=new ResampleKernel(inputRate/(double)outputRate);RESAMPLE_KERNELS.put(key,result);}return result;}
	private static final class ResampleKernel {
		static final int PHASES=256;final int radius;final double[][] weights;
		ResampleKernel(double ratio){radius=Math.max(12,(int)Math.ceil(12*ratio));double cutoff=Math.min(.48,.48/ratio);weights=new double[PHASES][2*radius];for(int phase=0;phase<PHASES;phase++){double fraction=phase/(double)PHASES;for(int tap=0;tap<2*radius;tap++){double d=tap-radius+1-fraction,x=2d*cutoff*d,sinc=Math.abs(x)<1e-12?1:Math.sin(Math.PI*x)/(Math.PI*x),window=Math.abs(d)>radius?0:.5+.5*Math.cos(Math.PI*d/radius);weights[phase][tap]=2d*cutoff*sinc*window;}}}
	}

	private static CfiData decodeCfi(byte[] iq,int inputSamples,int inputRate,Candidate cell){
		return decodeCfiAt(iq,inputSamples,inputRate,cell,cell.frameStartSample,0);
	}
	private static CfiData decodeCfiAt(byte[] iq,int inputSamples,int inputRate,Candidate cell,double frameStart,int subframe){
		if(!cell.mib.valid||frameStart<0)return CfiData.EMPTY;
		int rb=cell.mib.bandwidthRb,nsc=rb*12,nfft=lteFftSize(rb),longCp=cell.extendedCp?nfft/4:nfft/128*10;
		double usefulStart=frameStart+subframe*inputRate/1000d+longCp*(inputRate/(nfft*15000d));
		if(usefulStart<0||usefulStart+nfft*(inputRate/(nfft*15000d))>=inputSamples)return CfiData.EMPTY;
		int[][] regCarriers=new int[4][4];int kbar=6*Math.floorMod(cell.pci,2*rb);
		for(int reg=0;reg<4;reg++){
			int base=Math.floorMod(kbar+6*(reg*rb/2),nsc),out=0;
			for(int d=0;d<6;d++){int absolute=Math.floorMod(base+d,nsc);if(Math.floorMod(absolute-cell.pci,3)==0)continue;if(out<4)regCarriers[reg][out++]=absolute-nsc/2+(absolute>=nsc/2?1:0);}
			if(out!=4)return CfiData.EMPTY;
		}
		int[] dataCarriers=new int[16];for(int reg=0;reg<4;reg++)System.arraycopy(regCarriers[reg],0,dataCarriers,reg*4,4);
		int pilotCount=2*rb;int[][] pilotCarriers=new int[2][pilotCount];for(int port=0;port<2;port++)for(int j=0;j<pilotCount;j++){int absolute=Math.floorMod(cell.pci+3*port,6)+6*j;pilotCarriers[port][j]=absolute-nsc/2+(absolute>=nsc/2?1:0);}
		int[] all=new int[dataCarriers.length+2*pilotCount];System.arraycopy(dataCarriers,0,all,0,dataCarriers.length);System.arraycopy(pilotCarriers[0],0,all,dataCarriers.length,pilotCount);System.arraycopy(pilotCarriers[1],0,all,dataCarriers.length+pilotCount,pilotCount);
		double[][] bins=targetedBins(iq,inputSamples,inputRate,usefulStart,nfft,all,cell.iqOrientation<0,cell.cfoHz);
		double[][][] pilots=new double[2][3][pilotCount];int[] sequence=gold((1<<10)*(7*(2*subframe+1)+1)*(2*cell.pci+1)+2*cell.pci+(cell.extendedCp?0:1),440);int sequenceStart=110-rb;
		for(int port=0;port<2;port++)for(int j=0;j<pilotCount;j++){double rr=(1-2*sequence[2*(sequenceStart+j)])/Math.sqrt(2d),ri=(1-2*sequence[2*(sequenceStart+j)+1])/Math.sqrt(2d),yr=bins[0][16+port*pilotCount+j],yi=bins[1][16+port*pilotCount+j];pilots[port][0][j]=pilotCarriers[port][j];pilots[port][1][j]=yr*rr+yi*ri;pilots[port][2][j]=yi*rr-yr*ri;}
		double[] equalized=new double[32];if(cell.mib.antennaPorts==1){for(int i=0;i<16;i++){double[] h=interpolatedChannel(pilots[0],dataCarriers[i]);double den=h[0]*h[0]+h[1]*h[1];if(den<1e-9)den=1;equalized[2*i]=(bins[0][i]*h[0]+bins[1][i]*h[1])/den;equalized[2*i+1]=(bins[1][i]*h[0]-bins[0][i]*h[1])/den;}}
		else for(int i=0;i<16;i+=2){double[] h0=averageChannel(pilots[0],dataCarriers[i],dataCarriers[i+1]),h1=averageChannel(pilots[1],dataCarriers[i],dataCarriers[i+1]);double[] pair=equalizeTransmitDiversityPair(bins[0][i],bins[1][i],bins[0][i+1],bins[1][i+1],h0[0],h0[1],h1[0],h1[1]);System.arraycopy(pair,0,equalized,2*i,4);}
		int[] scrambling=gold((subframe+1)*(1<<9)*(2*cell.pci+1)+cell.pci,32);for(int i=0;i<32;i++)if(scrambling[i]!=0)equalized[i]=-equalized[i];
		int bestCfi=-1;double best=-Double.MAX_VALUE,second=-Double.MAX_VALUE;for(int cfi=1;cfi<=4;cfi++){double score=0;for(int i=0;i<32;i++){int bit=cfi==4?0:new int[][]{{0,1,1},{1,0,1},{1,1,0}}[cfi-1][i%3];score+=(bit==0?1:-1)*equalized[i];}if(score>best){second=best;best=score;bestCfi=cfi;}else if(score>second)second=score;}
		double energy=0;for(double value:equalized)energy+=Math.abs(value);double confidence=energy<1e-9?0:Math.max(0,Math.min(1,(best-second)/energy));return new CfiData(confidence>.08,bestCfi,confidence);
	}

	private static int lteFftSize(int rb){switch(rb){case 6:return 128;case 15:return 256;case 25:return 512;case 50:return 1024;case 75:return 1536;case 100:return 2048;default:return 128;}}
	private static void scanSib1Pdcch(byte[] iq,int inputSamples,int inputRate,Candidate cell){
		double frame=inputRate/100d;PdcchData best=PdcchData.EMPTY;CfiData bestCfi=CfiData.EMPTY;int bestOffset=0;List<PdschData> pdschList=new ArrayList<PdschData>();List<DciData> dciList=new ArrayList<DciData>();
		for(int offset=-2;offset<=12;offset++){
			int sfn=Math.floorMod(cell.mib.systemFrameNumber+offset,1024);if((sfn&1)!=0)continue;
			double start=cell.frameStartSample+offset*frame,subframeStart=start+5*inputRate/1000d;
			if(subframeStart<0||subframeStart+inputRate/1000d>=inputSamples)continue;
			CfiData indicated=decodeCfiAt(iq,inputSamples,inputRate,cell,start,5);
			int preferred=indicated.valid?indicated.cfi:1;
			for(int attempt=0;attempt<3;attempt++){
				int cfiValue=attempt==0?preferred:(preferred==1?attempt+1:attempt==1?1:preferred==2?3:2);
				CfiData cfi=indicated.valid&&indicated.cfi==cfiValue?indicated:new CfiData(true,cfiValue,0);
				ControlRegionData control=controlRegion(cell,cfi);
				PdcchData pdcch=extractPdcchAt(iq,inputSamples,inputRate,cell,start,5,cfi,control);
				DciData sibDci=findSib1Dci(pdcch.llr,pdcch.cceCount,cell.mib.bandwidthRb);
				if(sibDci.valid)pdcch=pdcch.withSystemDci(sibDci);
				boolean candidate=pdcch.systemDci.format1A;
				boolean better=pdcch.valid&&(!best.valid||(candidate&&!best.systemDci.format1A)
						||(candidate==best.systemDci.format1A&&pdcch.meanReliability>best.meanReliability));
				if(better){best=pdcch;bestCfi=cfi;bestOffset=offset;}
				if(candidate&&!pdcch.systemDci.distributed){
					PdschData pdsch=extractSib1Pdsch(iq,inputSamples,inputRate,cell,start,cfi,pdcch.systemDci);
					if(pdsch.valid){pdschList.add(pdsch);dciList.add(pdcch.systemDci);if(offset==bestOffset)cell.sib1Pdsch=pdsch;}
				}
				if(candidate)break;
			}
		}
		cell.sib1Cfi=bestCfi;cell.sib1Pdcch=best;cell.sib1FrameOffset=bestOffset;if(!best.systemDci.format1A)return;if(!cell.sib1Pdsch.valid)cell.sib1Pdsch=extractSib1Pdsch(iq,inputSamples,inputRate,cell,cell.frameStartSample+bestOffset*frame,bestCfi,best.systemDci);cell.sib1Transport=decodeSib1Transports(pdschList,dciList);cell.sib1=decodeSib1(cell.sib1Transport);
	}
	private static void scanFirstSiWindow(byte[] iq,int inputSamples,int inputRate,Candidate cell){
		double frame=inputRate/100d;PdcchData best=PdcchData.EMPTY;CfiData bestCfi=CfiData.EMPTY;PdschData bestPdsch=PdschData.EMPTY;int bestOffset=0,bestSubframe=-1;
		List<PdschData> pdschList=new ArrayList<PdschData>();List<DciData> dciList=new ArrayList<DciData>();
		for(int offset=-2;offset<=12;offset++){
			int sfn=Math.floorMod(cell.mib.systemFrameNumber+offset,1024);if((sfn&7)!=0)continue;
			double start=cell.frameStartSample+offset*frame;
			for(int subframe=0;subframe<10;subframe++){
				if(subframe==5)continue; // SIB1 has its own fixed allocation in even radio frames.
				double subframeStart=start+subframe*inputRate/1000d;if(subframeStart<0||subframeStart+inputRate/1000d>=inputSamples)continue;
				CfiData indicated=decodeCfiAt(iq,inputSamples,inputRate,cell,start,subframe);int preferred=indicated.valid?indicated.cfi:1;
				for(int attempt=0;attempt<3;attempt++){
					int cfiValue=attempt==0?preferred:(preferred==1?attempt+1:attempt==1?1:preferred==2?3:2);
					CfiData cfi=indicated.valid&&indicated.cfi==cfiValue?indicated:new CfiData(true,cfiValue,0);
					PdcchData pdcch=extractPdcchAt(iq,inputSamples,inputRate,cell,start,subframe,cfi,controlRegion(cell,cfi));
					if(pdcch.systemDci.format1A&&!pdcch.systemDci.distributed){
						PdschData pdsch=extractSiPdsch(iq,inputSamples,inputRate,cell,start,subframe,cfi,pdcch.systemDci);
						if(pdsch.valid){pdschList.add(pdsch);dciList.add(pdcch.systemDci);}
						if(!best.systemDci.format1A||pdcch.meanReliability>best.meanReliability){best=pdcch;bestCfi=cfi;bestPdsch=pdsch;bestOffset=offset;bestSubframe=subframe;}
						break;
					}
				}
			}
		}
		cell.siCfi=bestCfi;cell.siPdcch=best;cell.siPdsch=bestPdsch;cell.siFrameOffset=bestOffset;cell.siSubframe=bestSubframe;
		if(best.systemDci.format1A&&bestPdsch.valid){cell.siTransport=decodeSiTransports(pdschList,dciList,best.systemDci);cell.si=decodeFirstSiHeader(cell.siTransport);}
	}
	private static void scanPagingPdcch(byte[] iq,int inputSamples,int inputRate,Candidate cell){
		int t=cell.si.pagingCycle,nBIndex=cell.si.pagingNb;if(t<=0||nBIndex<0)return;
		double factor=new double[]{4,2,1,.5,.25,.125,.0625,.03125}[nBIndex];
		int nB=Math.max(1,(int)Math.round(t*factor)),n=Math.min(t,nB),frameInterval=Math.max(1,t/n);
		int ns=Math.max(1,nB/t);int[] occasions=ns>=4?new int[]{0,4,5,9}:ns>=2?new int[]{4,9}:new int[]{9};
		double frame=inputRate/100d;
		/* Inspect one paging occasion per live pass. Repeating all possible frames
		 * in one pass delayed the constellation and load monitor by several seconds. */
		for(int step=0;step<15;step++){int offset=-2+Math.floorMod(cell.pagingScanCursor+step,15),sfn=Math.floorMod(cell.mib.systemFrameNumber+offset,1024);if(sfn%frameInterval!=0)continue;double start=cell.frameStartSample+offset*frame;
			for(int subframe:occasions){double at=start+subframe*inputRate/1000d;if(at<0||at+inputRate/1000d>=inputSamples)continue;CfiData indicated=decodeCfiAt(iq,inputSamples,inputRate,cell,start,subframe);int preferred=indicated.valid?indicated.cfi:1;
				for(int attempt=0;attempt<3;attempt++){int cfiValue=attempt==0?preferred:(preferred==1?attempt+1:attempt==1?1:preferred==2?3:2);CfiData cfi=indicated.valid&&indicated.cfi==cfiValue?indicated:new CfiData(true,cfiValue,0);PdcchData pdcch=extractPdcchAt(iq,inputSamples,inputRate,cell,start,subframe,cfi,controlRegion(cell,cfi));DciData paging=findCommonDci(pdcch.llr,pdcch.cceCount,0xfffe);if(paging.valid){parseDci1A(paging,cell.mib.bandwidthRb);if(paging.format1A){cell.pagingEvents.add(sfn*10+subframe);break;}}}
				cell.pagingScanCursor=Math.floorMod(offset+3,15);return;
			}
		}
	}
	private static LoadData estimateDownlinkLoad(byte[] iq,int inputSamples,int inputRate,Candidate cell){
		int rb=cell.mib.bandwidthRb,nfft=lteFftSize(rb),nsc=12*rb,guard=Math.min(24,Math.max(8,(nfft-nsc)/4));
		int[] carriers=new int[nsc+2*guard];for(int a=0;a<nsc;a++)carriers[a]=a-nsc/2+(a>=nsc/2?1:0);
		for(int q=0;q<guard;q++){carriers[nsc+q]=-nsc/2-guard+q;carriers[nsc+guard+q]=nsc/2+1+q;}
		int longCp=cell.extendedCp?nfft/4:nfft*10/128,shortCp=cell.extendedCp?nfft/4:nfft*9/128;double scale=inputRate/(nfft*15000d);
		int active=0,total=0,subframes=0;
		for(int sf=0;sf<10;sf++){double useful=cell.frameStartSample+sf*inputRate/1000d+(longCp+3d*(nfft+shortCp))*scale;if(useful<0||useful+nfft*scale>=inputSamples)continue;
			double[][] bins=ofdmBins(iq,inputSamples,inputRate,useful,nfft,carriers,cell.iqOrientation<0,cell.cfoHz);double noise=0;for(int q=nsc;q<carriers.length;q++)noise+=bins[0][q]*bins[0][q]+bins[1][q]*bins[1][q];noise/=Math.max(1,2*guard);double threshold=Math.max(1e-12,noise*3.5);
			for(int prb=0;prb<rb;prb++){double power=0;for(int q=0;q<12;q++){int at=prb*12+q;power+=bins[0][at]*bins[0][at]+bins[1][at]*bins[1][at];}power/=12d;if(power>threshold)active++;total++;}subframes++;
		}
		return total==0?LoadData.EMPTY:new LoadData(true,active,total,subframes,100d*active/total);
	}
	private static double[][] ofdmBins(byte[] iq,int samples,int inputRate,double usefulStart,int nfft,int[] carriers,boolean conjugate,double cfoHz){
		/* The load meter needs a complete symbol. Resample it once and use an FFT
		 * instead of evaluating every requested carrier with a separate DFT. */
		if((nfft&(nfft-1))!=0)return targetedBins(iq,samples,inputRate,usefulStart,nfft,carriers,conjugate,cfoHz);
		double[] re=new double[nfft],im=new double[nfft];double sourceStep=inputRate/(nfft*15000d);
		for(int n=0;n<nfft;n++){
			double source=usefulStart+n*sourceStep;int a=(int)Math.floor(source),b=Math.min(samples-1,a+1);
			if(a<0||a>=samples)return new double[2][carriers.length];
			double f=source-a,xr=(iq[2*a]*(1-f)+iq[2*b]*f)/128d,xi=(iq[2*a+1]*(1-f)+iq[2*b+1]*f)/128d;
			if(conjugate)xi=-xi;double phase=-2*Math.PI*cfoHz*(source-usefulStart)/inputRate,c=Math.cos(phase),s=Math.sin(phase);
			re[n]=xr*c-xi*s;im[n]=xr*s+xi*c;
		}
		fft(re,im);double[][] out=new double[2][carriers.length];
		for(int q=0;q<carriers.length;q++){int bin=Math.floorMod(carriers[q],nfft);out[0][q]=re[bin];out[1][q]=im[bin];}
		return out;
	}
	private static void fft(double[] re,double[] im){
		int size=re.length;for(int i=1,j=0;i<size;i++){int bit=size>>1;for(;(j&bit)!=0;bit>>=1)j^=bit;j^=bit;if(i<j){double v=re[i];re[i]=re[j];re[j]=v;v=im[i];im[i]=im[j];im[j]=v;}}
		for(int len=2;len<=size;len<<=1){double angle=-2*Math.PI/len,cr=Math.cos(angle),ci=Math.sin(angle);for(int start=0;start<size;start+=len){double wr=1,wi=0;for(int q=0;q<len/2;q++){int x=start+q,y=x+len/2;double yr=re[y]*wr-im[y]*wi,yi=re[y]*wi+im[y]*wr;re[y]=re[x]-yr;im[y]=im[x]-yi;re[x]+=yr;im[x]+=yi;double next=wr*cr-wi*ci;wi=wr*ci+wi*cr;wr=next;}}}
	}
	private static SiData decodeFirstSiHeader(Sib1TransportData transport){
		if(!transport.valid)return SiData.EMPTY;
		diagnosticSi="";
		try{BitReader r=new BitReader(transport.bits,transport.transportBlockBits);
			if(r.read(1)!=0||r.read(1)!=0||r.read(1)!=0)return SiData.EMPTY;
			boolean nonCriticalExtension=r.bit();int count=r.read(5)+1;
			if(r.bit())return SiData.EMPTY;int firstType=r.read(4);if(firstType!=0)return SiData.EMPTY;
			boolean sib2Extension=r.bit(),accessBarring=r.bit(),mbsfn=r.bit(),emergency=false,moSignalling=false,moData=false;int signallingFactor=-1,signallingTime=-1,dataFactor=-1,dataTime=-1;
			if(accessBarring){moSignalling=r.bit();moData=r.bit();emergency=r.bit();if(moSignalling){signallingFactor=r.read(4);signallingTime=r.read(3);r.skip(5);}if(moData){dataFactor=r.read(4);dataTime=r.read(3);r.skip(5);}}
			boolean rrExtension=r.bit(),rachExtension=r.bit();
			boolean groupA=r.bit();int rachPreambles=(r.enumValue(16)+1)*4;if(groupA){if(r.bit())throw new IllegalArgumentException("RACH group A extension");r.enumValue(15);r.enumValue(4);r.enumValue(8);}
			r.enumValue(4);r.enumValue(16);r.enumValue(11);r.enumValue(8);r.enumValue(8);r.constrained(1,8);if(rachExtension)skipExtensionGroups(r);
			int modificationPeriod=new int[]{2,4,8,16}[r.enumValue(4)];
			int pagingCycle=new int[]{32,64,128,256}[r.enumValue(4)],pagingNb=r.enumValue(8);
			int prachRoot=r.constrained(0,837),prachConfig=r.constrained(0,63);boolean prachHighSpeed=r.bit();int prachZeroZone=r.constrained(0,15),prachOffset=r.constrained(0,94);
			int referenceSignalPower=r.constrained(-60,50),pB=r.constrained(0,3);
			int puschSubbands=r.constrained(1,4);boolean puschIntraAndInter=r.bit();int puschHopOffset=r.constrained(0,98);boolean pusch64Qam=r.bit();
			boolean groupHopping=r.bit();int groupAssignment=r.constrained(0,29);boolean sequenceHopping=r.bit();int cyclicShift=r.constrained(0,7);
			int deltaPucchShift=r.enumValue(3)+1,nRbCqi=r.constrained(0,98),nCsAn=r.constrained(0,7),n1PucchAn=r.constrained(0,2047);
			boolean srsSetup=r.bit();if(srsSetup){r.bit();r.enumValue(8);r.enumValue(16);r.bit();}
			int p0Pusch=r.constrained(-126,24),alpha=r.enumValue(8),p0Pucch=r.constrained(-127,-96);for(int q=0;q<5;q++)r.enumValue(q==2?4:3);int deltaMsg3=r.constrained(-1,6);boolean extendedUlCp=r.bit();if(rrExtension)skipExtensionGroups(r);
			boolean timerExtension=r.bit();int t300=r.enumValue(8),t301=r.enumValue(8),t310=r.enumValue(7),n310=r.enumValue(8),t311=r.enumValue(7),n311=r.enumValue(8);if(timerExtension)skipExtensionGroups(r);
			boolean ulCarrierPresent=r.bit(),ulBwPresent=r.bit();int ulCarrier=ulCarrierPresent?r.constrained(0,65535):-1,ulBandwidth=ulBwPresent?r.enumValue(6):-1,additionalEmission=r.constrained(1,32);
			if(mbsfn)throw new IllegalArgumentException("MBSFN list not yet supported");int timeAlignment=r.enumValue(8);
			if(sib2Extension)skipExtensionGroups(r);
			boolean sib3Valid=false;int qHyst=-1,threshServingLow=-1,reselectionPriority=-1,qRxLevMin=-1,allowedBw=-1,tReselection=-1;boolean antennaPort1=false;
			if(count>1){if(r.bit())throw new IllegalArgumentException("extended SIB type");int secondType=r.read(4);if(secondType!=1)throw new IllegalArgumentException("second SI item is not SIB3");
				boolean sib3Extension=r.bit(),speedState=r.bit();qHyst=r.enumValue(16);if(speedState){r.skip(3+3+2+2);}
				boolean nonIntra=r.bit();if(nonIntra)r.constrained(0,31);threshServingLow=r.constrained(0,31);reselectionPriority=r.constrained(0,7);
				boolean pMax=r.bit(),intraSearch=r.bit(),allowedMeasBw=r.bit(),tReselSf=r.bit();qRxLevMin=r.constrained(-70,-22);if(pMax)r.constrained(-30,33);if(intraSearch)r.constrained(0,31);if(allowedMeasBw)allowedBw=r.enumValue(6);
				antennaPort1=r.bit();r.skip(2);tReselection=r.constrained(0,7);if(tReselSf)r.skip(2+2);if(sib3Extension)skipExtensionGroups(r);sib3Valid=true;
			}
			return new SiData(true,count,nonCriticalExtension,sib2Extension,accessBarring,mbsfn,emergency,moSignalling,moData,signallingFactor,signallingTime,dataFactor,dataTime,
					modificationPeriod,pagingCycle,pagingNb,rachPreambles,prachRoot,prachConfig,prachHighSpeed,prachZeroZone,prachOffset,referenceSignalPower,pB,puschSubbands,puschIntraAndInter,puschHopOffset,pusch64Qam,
					groupHopping,groupAssignment,sequenceHopping,cyclicShift,deltaPucchShift,nRbCqi,nCsAn,n1PucchAn,srsSetup,p0Pusch,alpha,p0Pucch,deltaMsg3,extendedUlCp,
					t300,t301,t310,n310,t311,n311,ulCarrier,ulBandwidth,additionalEmission,timeAlignment,sib3Valid,qHyst,threshServingLow,reselectionPriority,qRxLevMin,allowedBw,antennaPort1,tReselection);
		}catch(IllegalArgumentException ex){diagnosticSi=ex.getMessage();return SiData.EMPTY;}
	}
	private static void skipExtensionGroups(BitReader r){if(r.bit())throw new IllegalArgumentException("large extension bitmap");int groups=r.read(6)+1;boolean[] present=new boolean[groups];for(int q=0;q<groups;q++)present[q]=r.bit();for(boolean value:present)if(value){int bytes;if(!r.bit())bytes=r.read(7);else{if(r.bit())throw new IllegalArgumentException("large open type");bytes=r.read(14);}r.skip(bytes*8);}}
	static String siDiagnostics(){return diagnosticSi;}
	private static Sib1Data decodeSib1(Sib1TransportData transport){
		if(!transport.valid)return Sib1Data.EMPTY;
		try{BitReader r=new BitReader(transport.bits,transport.transportBlockBits);if(r.read(1)!=0||r.read(1)!=1)return Sib1Data.EMPTY;
			boolean pMax=r.bit(),tdd=r.bit(),extension=r.bit(),csgId=r.bit();int count=r.read(3)+1;List<String> plmns=new ArrayList<String>();String inheritedMcc=null;
			for(int n=0;n<count;n++){String mcc=inheritedMcc;if(r.bit()){StringBuilder v=new StringBuilder();for(int q=0;q<3;q++)v.append(r.read(4));mcc=v.toString();inheritedMcc=mcc;}int mncDigits=r.read(1)+2;StringBuilder mnc=new StringBuilder();for(int q=0;q<mncDigits;q++)mnc.append(r.read(4));r.read(1);if(mcc!=null)plmns.add(mcc+"-"+mnc);}
			int tac=r.read(16),cellId=r.read(28);boolean barred=r.read(1)==0,intraFreqAllowed=r.read(1)==0,csg=r.bit();if(csgId)r.skip(27);boolean rxOffset=r.bit();int qRxLevMin=r.read(6)-70;if(rxOffset)r.skip(3);if(pMax)r.skip(6);int band=r.read(6)+1;
			int scheduleCount=r.read(5)+1;List<String> schedules=new ArrayList<String>();int[] periods={8,16,32,64,128,256,512};String[] sibNames={"SIB3","SIB4","SIB5","SIB6","SIB7","SIB8","SIB9","SIB10","SIB11","SIB12","SIB13","SIB14","SIB15","SIB16","SIB17","SIB18","SIB19","SIB20","SIB21","SIB24","SIB25","SIB26","SIB26a","SIB27","SIB28","SIB29"};
			for(int n=0;n<scheduleCount;n++){int periodicity=r.read(3);if(periodicity>=periods.length)throw new IllegalArgumentException("invalid SI periodicity");int mapped=r.read(5);List<String> sibs=new ArrayList<String>();if(n==0)sibs.add("SIB2");for(int q=0;q<mapped;q++){int type;if(!r.bit())type=r.read(4);else{if(r.bit())throw new IllegalArgumentException("large SIB extension index");type=16+r.read(6);}sibs.add(type<sibNames.length?sibNames[type]:"SIB?");}schedules.add("RF"+periods[periodicity]+": "+String.join(", ",sibs));}
			int tddAssignment=-1,tddSpecialPattern=-1;if(tdd){tddAssignment=r.read(3);tddSpecialPattern=r.read(4);if(tddAssignment>6||tddSpecialPattern>8)throw new IllegalArgumentException("invalid TDD configuration");}
			int[] windows={1,2,5,10,15,20,40};int windowIndex=r.read(3);if(windowIndex>=windows.length)throw new IllegalArgumentException("invalid SI window");int valueTag=r.read(5);
			return new Sib1Data(true,plmns,tac,cellId,barred,intraFreqAllowed,csg,qRxLevMin,band,tdd,tddAssignment,tddSpecialPattern,extension,schedules,windows[windowIndex],valueTag);
		}catch(IllegalArgumentException ex){return Sib1Data.EMPTY;}
	}
	private static PdschData extractSib1Pdsch(byte[] iq,int inputSamples,int inputRate,Candidate cell,double frameStart,CfiData cfi,DciData dci){return extractSiPdsch(iq,inputSamples,inputRate,cell,frameStart,5,cfi,dci);}
	@SuppressWarnings("unchecked") private static PdschData extractSiPdsch(byte[] iq,int inputSamples,int inputRate,Candidate cell,double frameStart,int subframe,CfiData cfi,DciData dci){int rb=cell.mib.bandwidthRb,nfft=lteFftSize(rb),nsc=12*rb,first=dci.rbStart*12,count=dci.rbLength*12;if(first<0||count<=0||first+count>nsc)return PdschData.EMPTY;int[] carriers=new int[count];for(int q=0;q<count;q++){int a=first+q;carriers[q]=a-nsc/2+(a>=nsc/2?1:0);}int longCp=cell.extendedCp?nfft/4:nfft*10/128,shortCp=cell.extendedCp?nfft/4:nfft*9/128;double scale=inputRate/(nfft*15000d),useful=frameStart+subframe*inputRate/1000d+longCp*scale;double[][][] grid=new double[14][][];for(int symbol=0;symbol<14;symbol++){if(symbol>0)useful+=(nfft+(symbol==7?longCp:shortCp))*scale;grid[symbol]=targetedBins(iq,inputSamples,inputRate,useful,nfft,carriers,cell.iqOrientation<0,cell.cfoHz);}List<PilotRow>[] rows=new List[4];for(int port=0;port<4;port++)rows[port]=new ArrayList<PilotRow>();for(int symbol:new int[]{0,1,4,7,8,11}){int slot=2*subframe+symbol/7,l=symbol%7;if(l==1&&cell.mib.antennaPorts<4)continue;if(l!=1&&symbol!=0&&symbol!=4&&symbol!=7&&symbol!=11)continue;int cinit=(1<<10)*(7*(slot+1)+l+1)*(2*cell.pci+1)+2*cell.pci+(cell.extendedCp?0:1);int[] seq=gold(cinit,440);int firstPort=l==1?2:0,lastPort=l==1?Math.min(4,cell.mib.antennaPorts):Math.min(2,cell.mib.antennaPorts);for(int port=firstPort;port<lastPort;port++){int v=port==0?(l==0?0:3):port==1?(l==0?3:0):port==2?0:3,residue=Math.floorMod(cell.pci+v,6),pilots=0;for(int q=0;q<count;q++)if(Math.floorMod(first+q,6)==residue)pilots++;double[][] channel=new double[3][pilots];int out=0;for(int q=0;q<count;q++){int absolute=first+q;if(Math.floorMod(absolute,6)!=residue)continue;int ordinal=(absolute-residue)/6,sequenceIndex=110-rb+ordinal;double rr=(1-2*seq[2*sequenceIndex])/Math.sqrt(2d),ri=(1-2*seq[2*sequenceIndex+1])/Math.sqrt(2d),yr=grid[symbol][0][q],yi=grid[symbol][1][q];channel[0][out]=carriers[q];channel[1][out]=yr*rr+yi*ri;channel[2][out]=yi*rr-yr*ri;out++;}if(out>0)rows[port].add(new PilotRow(symbol,channel));}}
		List<double[]> received=new ArrayList<double[]>();for(int symbol=cfi.cfi;symbol<14;symbol++)for(int q=0;q<count;q++){int absolute=first+q,l=symbol%7;if((l==0||l==4||(cell.mib.antennaPorts==4&&l==1))&&Math.floorMod(absolute-cell.pci,3)==0)continue;
			// In subframe 0 the central six RB are reserved by PSS/SSS and PBCH
			// during symbols 5..10 and are not part of the PDSCH rate matching.
			if(subframe==0&&symbol>=5&&symbol<=10&&absolute>=nsc/2-36&&absolute<nsc/2+36)continue;
			received.add(new double[]{grid[symbol][0][q],grid[symbol][1][q],carriers[q],symbol});}int group=cell.mib.antennaPorts==4?4:cell.mib.antennaPorts==2?2:1;while(received.size()%group!=0)received.remove(received.size()-1);double[] soft=new double[received.size()*2];if(cell.mib.antennaPorts==1){for(int i=0;i<received.size();i++){double[] a=received.get(i),h=estimateRows(rows[0],(int)a[3],(int)a[2],(int)a[2]);double den=h[0]*h[0]+h[1]*h[1];if(den<1e-9)den=1;soft[2*i]=(a[0]*h[0]+a[1]*h[1])/den;soft[2*i+1]=(a[1]*h[0]-a[0]*h[1])/den;}}else for(int i=0;i<received.size();i+=2){double[] a=received.get(i),b=received.get(i+1);int within=cell.mib.antennaPorts==4?i%4:0,portA=cell.mib.antennaPorts==4&&within==2?1:0,portB=cell.mib.antennaPorts==4?(within==2?3:2):1;double[] h0=estimateRows(rows[portA],(int)a[3],(int)a[2],(int)b[2]),h1=estimateRows(rows[portB],(int)a[3],(int)a[2],(int)b[2]),pair=equalizeTransmitDiversityPair(a[0],a[1],b[0],b[1],h0[0],h0[1],h1[0],h1[1]);System.arraycopy(pair,0,soft,2*i,4);}int[] scrambling=gold(0xffff*(1<<14)+subframe*(1<<9)+cell.pci,soft.length);for(int i=0;i<soft.length;i++)if(scrambling[i]!=0)soft[i]=-soft[i];double sum=0;for(double value:soft)sum+=Math.abs(value);return new PdschData(true,received.size(),soft,sum/Math.max(1,soft.length),qpskEvm(soft));}
	private static double[] estimateRows(List<PilotRow> rows,int row,int carrier0,int carrier1){if(rows.isEmpty())return new double[]{0,0};PilotRow before=null,after=null;for(PilotRow pilot:rows){if(pilot.row<=row)before=pilot;if(pilot.row>=row){after=pilot;break;}}if(before==null)before=rows.get(0);if(after==null)after=rows.get(rows.size()-1);double[] a=averageChannel(before.channel,carrier0,carrier1);if(before==after)return a;double[] b=averageChannel(after.channel,carrier0,carrier1);double weight=(row-before.row)/(double)(after.row-before.row);return new double[]{a[0]+weight*(b[0]-a[0]),a[1]+weight*(b[1]-a[1])};}

	private static Sib1TransportData decodeSib1Transport(PdschData pdsch,DciData dci){
		if(!pdsch.valid||dci.mcs<0)return Sib1TransportData.EMPTY;int tbs=sib1TransportBlockSize(dci);if(tbs<0)return new Sib1TransportData(false,tbs,new int[0],0);
		int k=tbs+24;TurboParameters parameters=turboParameters(k);if(parameters==null)return new Sib1TransportData(false,tbs,new int[0],0);
		double[][] streams=turboDeratematch(pdsch.llr,k,dci.rv);return decodeTurboCandidates(streams,tbs,parameters,1);
	}
	private static Sib1TransportData decodeSiTransport(PdschData pdsch,DciData dci){
		if(!pdsch.valid||dci.mcs<0)return Sib1TransportData.EMPTY;
		// For SI-RNTI DCI 1A, N_PRB_1A selects TBS-table column 2 or 3,
		// independently of the physical RB allocation carried by the RIV.
		int tbs=siTransportBlockSize(dci);
		if(tbs<0)return new Sib1TransportData(false,tbs,new int[0],0);
		int k=tbs+24;TurboParameters parameters=turboParameters(k);if(parameters==null)return new Sib1TransportData(false,tbs,new int[0],0);
		double[][] streams=turboDeratematch(pdsch.llr,k,dci.rv);return decodeTurboCandidates(streams,tbs,parameters,1);
	}
	private static Sib1TransportData decodeSiTransports(List<PdschData> pdschList,List<DciData> dciList,DciData preferred){
		if(pdschList.isEmpty())return Sib1TransportData.EMPTY;int tbs=siTransportBlockSize(preferred);if(tbs<0)return decodeSiTransport(pdschList.get(0),dciList.get(0));
		int k=tbs+24;TurboParameters parameters=turboParameters(k);if(parameters==null)return new Sib1TransportData(false,tbs,new int[0],0);
		double[][] combined=new double[3][k+4];int used=0;Sib1TransportData best=Sib1TransportData.EMPTY;
		for(int q=0;q<pdschList.size();q++){DciData dci=dciList.get(q);if(siTransportBlockSize(dci)!=tbs)continue;double[][] one=turboDeratematch(pdschList.get(q).llr,k,dci.rv);for(int s=0;s<3;s++)for(int i=0;i<k+4;i++)combined[s][i]+=one[s][i];used++;Sib1TransportData decoded=decodeTurboCandidates(combined,tbs,parameters,used);if(decoded.valid)return decoded;best=decoded;}
		return best;
	}
	private static int siTransportBlockSize(DciData dci){return dci.mcs==11?(dci.nPrb1a==0?376:dci.nPrb1a==1?584:-1):-1;}
	private static Sib1TransportData decodeSib1Transports(List<PdschData> pdschList,List<DciData> dciList){
		if(pdschList.isEmpty())return Sib1TransportData.EMPTY;Sib1TransportData best=Sib1TransportData.EMPTY;int tbs=sib1TransportBlockSize(dciList.get(0));if(tbs<0)return decodeSib1Transport(pdschList.get(0),dciList.get(0));int k=tbs+24;TurboParameters parameters=turboParameters(k);if(parameters==null)return new Sib1TransportData(false,tbs,new int[0],0);double[][] combined=new double[3][k+4];int used=0;
		for(int q=0;q<pdschList.size();q++){DciData dci=dciList.get(q);if(sib1TransportBlockSize(dci)!=tbs)continue;double[][] one=turboDeratematch(pdschList.get(q).llr,k,dci.rv);for(int s=0;s<3;s++)for(int i=0;i<k+4;i++)combined[s][i]+=one[s][i];used++;Sib1TransportData decoded=decodeTurboCandidates(combined,tbs,parameters,used);if(decoded.valid)return decoded;best=decoded;}
		return best;
	}
	private static Sib1TransportData decodeTurboCandidates(double[][] streams,int tbs,TurboParameters parameters,int transmissions){int k=tbs+24,feedbackMask=6,parityMask=5;int[] bits=turboDecode(streams,k,parameters.f1,parameters.f2,8,feedbackMask,parityMask);return new Sib1TransportData(crc24aValid(bits,tbs),tbs,bits,8,transmissions,feedbackMask,parityMask);}
	private static int sib1TransportBlockSize(DciData dci){
		// For SI-RNTI DCI 1A, N_PRB_1A selects TBS-table column 2 or 3; it is not the allocation length.
		if(dci.mcs==6&&dci.nPrb1a==0)return 176;if(dci.mcs==6&&dci.nPrb1a==1)return 256;return -1;
	}
	private static TurboParameters turboParameters(int k){if(k==200)return new TurboParameters(13,50);if(k==280)return new TurboParameters(103,210);if(k==400)return new TurboParameters(151,40);if(k==608)return new TurboParameters(37,76);if(k==624)return new TurboParameters(41,234);return null;}
	private static double[][] turboDeratematch(double[] received,int k,int rv){
		int d=k+4,rows=(d+31)/32,kpi=rows*32,ncb=3*kpi;int[] map=turboRateMap(d,received.length,rv);double[][] streams=new double[3][d];for(int q=0;q<received.length;q++){int code=map[q];if(code<0)continue;int stream=code/d,index=code%d;streams[stream][index]+=received[q];}return streams;
	}
	private static int[] turboRateMap(int d,int output,int rv){
		int rows=(d+31)/32,kpi=rows*32,dummy=kpi-d,ncb=3*kpi;int[] p={0,16,8,24,4,20,12,28,2,18,10,26,6,22,14,30,1,17,9,25,5,21,13,29,3,19,11,27,7,23,15,31};int[][] v=new int[3][kpi];
		for(int s=0;s<2;s++)for(int c=0;c<32;c++)for(int r=0;r<rows;r++){int input=r*32+p[c];v[s][c*rows+r]=input<dummy?-1:s*d+input-dummy;}
		for(int q=0;q<kpi;q++){int source=(p[q/rows]+32*(q%rows)+1)%kpi;v[2][q]=source<dummy?-1:2*d+source-dummy;}
		int[] w=new int[ncb];for(int q=0;q<kpi;q++){w[q]=v[0][q];w[kpi+2*q]=v[1][q];w[kpi+2*q+1]=v[2][q];}
		int k0=rows*(2*((ncb+8*rows-1)/(8*rows))*Math.max(0,Math.min(3,rv))+2),at=Math.floorMod(k0,ncb);int[] result=new int[output];for(int q=0;q<output;){int code=w[at];at=(at+1)%ncb;if(code>=0)result[q++]=code;}return result;
	}
	private static int[] turboDecode(double[][] streams,int k,int f1,int f2,int iterations){return turboDecode(streams,k,f1,f2,iterations,6,5);}
	private static int[] turboDecode(double[][] streams,int k,int f1,int f2,int iterations,int feedbackMask,int parityMask){
		double[] systematic=new double[k+3],parity1=new double[k+3],interleavedSystematic=new double[k+3],parity2=new double[k+3];System.arraycopy(streams[0],0,systematic,0,k);System.arraycopy(streams[1],0,parity1,0,k);int[] pi=new int[k];for(int i=0;i<k;i++){pi[i]=(int)(((long)f1*i+(long)f2*i*i)%k);interleavedSystematic[i]=systematic[pi[i]];parity2[i]=streams[2][i];}
		systematic[k]=streams[0][k];parity1[k]=streams[1][k];systematic[k+1]=streams[1][k+1];parity1[k+1]=streams[0][k+1];systematic[k+2]=streams[2][k];parity1[k+2]=streams[2][k+1];
		interleavedSystematic[k]=streams[0][k+2];parity2[k]=streams[1][k+2];interleavedSystematic[k+1]=streams[1][k+3];parity2[k+1]=streams[0][k+3];interleavedSystematic[k+2]=streams[2][k+2];parity2[k+2]=streams[2][k+3];
		double[] apriori=new double[k],secondExtrinsic=new double[k];for(int iteration=0;iteration<iterations;iteration++){double[] firstExtrinsic=constituentExtrinsic(systematic,parity1,apriori,feedbackMask,parityMask);double[] interleavedApriori=new double[k];for(int i=0;i<k;i++)interleavedApriori[i]=firstExtrinsic[pi[i]];double[] interleavedExtrinsic=constituentExtrinsic(interleavedSystematic,parity2,interleavedApriori,feedbackMask,parityMask);for(int i=0;i<k;i++)secondExtrinsic[pi[i]]=interleavedExtrinsic[i];apriori=secondExtrinsic.clone();}
		int[] bits=new int[k];for(int i=0;i<k;i++)bits[i]=systematic[i]+apriori[i]<0?1:0;return bits;
	}
	private static double[] constituentExtrinsic(double[] systematic,double[] parity,double[] apriori,int feedbackMask,int parityMask){
		int n=systematic.length,information=apriori.length;double neg=-1e100;double[][] alpha=new double[n+1][8],beta=new double[n+1][8];java.util.Arrays.fill(alpha[0],neg);alpha[0][0]=0;java.util.Arrays.fill(beta[n],neg);beta[n][0]=0;for(int i=0;i<n;i++){java.util.Arrays.fill(alpha[i+1],neg);double a=i<information?apriori[i]:0;for(int state=0;state<8;state++)for(int bit=0;bit<2;bit++){int next=rscNext(state,bit,feedbackMask),p=rscParity(state,bit,feedbackMask,parityMask);double metric=.5*((bit==0?1:-1)*(systematic[i]+a)+(p==0?1:-1)*parity[i]);alpha[i+1][next]=Math.max(alpha[i+1][next],alpha[i][state]+metric);}normalize(alpha[i+1]);}
		for(int i=n-1;i>=0;i--){java.util.Arrays.fill(beta[i],neg);double a=i<information?apriori[i]:0;for(int state=0;state<8;state++)for(int bit=0;bit<2;bit++){int next=rscNext(state,bit,feedbackMask),p=rscParity(state,bit,feedbackMask,parityMask);double metric=.5*((bit==0?1:-1)*(systematic[i]+a)+(p==0?1:-1)*parity[i]);beta[i][state]=Math.max(beta[i][state],metric+beta[i+1][next]);}normalize(beta[i]);}
		double[] extrinsic=new double[information];for(int i=0;i<information;i++){double zero=neg,one=neg;for(int state=0;state<8;state++)for(int bit=0;bit<2;bit++){int next=rscNext(state,bit,feedbackMask),p=rscParity(state,bit,feedbackMask,parityMask);double metric=.5*((bit==0?1:-1)*(systematic[i]+apriori[i])+(p==0?1:-1)*parity[i]);double value=alpha[i][state]+metric+beta[i+1][next];if(bit==0)zero=Math.max(zero,value);else one=Math.max(one,value);}extrinsic[i]=Math.max(-50,Math.min(50,zero-one-systematic[i]-apriori[i]));}return extrinsic;
	}
	private static int rscFeedback(int state,int bit,int mask){return bit^(Integer.bitCount(state&mask)&1);}
	private static int rscNext(int state,int bit,int mask){return ((state<<1)|rscFeedback(state,bit,mask))&7;}
	private static int rscParity(int state,int bit,int feedbackMask,int parityMask){int feedback=rscFeedback(state,bit,feedbackMask);return feedback^(Integer.bitCount(state&parityMask)&1);}
	private static void normalize(double[] values){double max=-Double.MAX_VALUE;for(double value:values)max=Math.max(max,value);if(max>-1e90)for(int i=0;i<values.length;i++)values[i]-=max;}
	private static boolean crc24aValid(int[] bits,int payload){if(bits.length<payload+24)return false;int polynomial=0x864cfb,crc=0;for(int i=0;i<payload+24;i++){int feedback=((crc>>>23)&1)^bits[i];crc=(crc<<1)&0xffffff;if(feedback!=0)crc^=polynomial;}return crc==0;}
	static int[] turboRateMapForTest(int d,int output,int rv){return turboRateMap(d,output,rv);}
	static double[][] turboDeratematchForTest(double[] received,int k,int rv){return turboDeratematch(received,k,rv);}
	static int[] turboDecodeForTest(double[][] streams,int k,int f1,int f2,int iterations){return turboDecode(streams,k,f1,f2,iterations);}
	private static final class TurboParameters{final int f1,f2;TurboParameters(int f1,int f2){this.f1=f1;this.f2=f2;}}
	private static ControlRegionData controlRegion(Candidate cell){return controlRegion(cell,cell.cfi);}
	private static ControlRegionData controlRegion(Candidate cell,CfiData cfi){if(!cell.mib.valid||!cfi.valid)return ControlRegionData.EMPTY;int symbols=cfi.cfi+(cell.mib.bandwidthRb<=10?1:0),rb=cell.mib.bandwidthRb,total=0;for(int symbol=0;symbol<symbols;symbol++){if(symbol==0)total+=2*rb;else if(symbol==1)total+=(cell.mib.antennaPorts==4?2:3)*rb;else if(symbol==3&&cell.extendedCp)total+=2*rb;else total+=3*rb;}double[] ng={1d/6d,.5,1,2};int groups=(int)Math.ceil(ng[Math.max(0,Math.min(3,cell.mib.phichResource))]*rb/8d);if(cell.extendedCp)groups*=2;int usable=Math.max(0,total-4-3*groups);return new ControlRegionData(true,symbols,total,groups,usable,usable/9);}

	private static PdcchData extractPdcch(byte[] iq,int inputSamples,int inputRate,Candidate cell){
		return extractPdcchAt(iq,inputSamples,inputRate,cell,cell.frameStartSample,0,cell.cfi,cell.control);
	}
	private static PdcchData extractPdcchAt(byte[] iq,int inputSamples,int inputRate,Candidate cell,double frameStart,int subframe,CfiData cfi,ControlRegionData control){
		if(!control.valid||cell.mib.phichExtended||frameStart<0)return PdcchData.EMPTY;
		int rb=cell.mib.bandwidthRb,nsc=12*rb,nfft=lteFftSize(rb),symbols=control.symbols,longCp=cell.extendedCp?nfft/4:nfft*10/128,shortCp=cell.extendedCp?nfft/4:nfft*9/128;double rateScale=inputRate/(nfft*15000d);
		int gridSymbols=Math.max(symbols,5);double[] useful=new double[gridSymbols];useful[0]=frameStart+subframe*inputRate/1000d+longCp*rateScale;for(int l=1;l<gridSymbols;l++)useful[l]=useful[l-1]+(nfft+shortCp)*rateScale;
		int[] carriers=new int[nsc];for(int a=0;a<nsc;a++)carriers[a]=a-nsc/2+(a>=nsc/2?1:0);double[][][] grid=new double[gridSymbols][][];for(int l=0;l<gridSymbols;l++)grid[l]=targetedBins(iq,inputSamples,inputRate,useful[l],nfft,carriers,cell.iqOrientation<0,cell.cfoHz);
		@SuppressWarnings("unchecked") List<PilotRow>[] channelRows=new List[4];for(int port=0;port<4;port++)channelRows[port]=new ArrayList<PilotRow>();
		for(int l:new int[]{0,1,4}){int slot=2*subframe,cinit=(1<<10)*(7*(slot+1)+l+1)*(2*cell.pci+1)+2*cell.pci+(cell.extendedCp?0:1),sequenceStart=110-rb;int[] sequence=gold(cinit,440);
			int firstPort=l==1?2:0,lastPort=l==1?Math.min(4,cell.mib.antennaPorts):Math.min(2,cell.mib.antennaPorts);
			for(int port=firstPort;port<lastPort;port++){int v=port==0?(l==0?0:3):port==1?(l==0?3:0):port==2?0:3,residue=Math.floorMod(cell.pci+v,6),pilotCount=0;for(int a=0;a<nsc;a++)if(Math.floorMod(a,6)==residue)pilotCount++;double[][] channel=new double[3][pilotCount];int out=0;
				for(int a=residue;a<nsc;a+=6){int ordinal=(a-residue)/6,index=sequenceStart+ordinal;double rr=(1-2*sequence[2*index])/Math.sqrt(2d),ri=(1-2*sequence[2*index+1])/Math.sqrt(2d),yr=grid[l][0][a],yi=grid[l][1][a];channel[0][out]=carriers[a];channel[1][out]=yr*rr+yi*ri;channel[2][out]=yi*rr-yr*ri;out++;}channelRows[port].add(new PilotRow(l,channel));}}
		List<ControlReg> regs=new ArrayList<ControlReg>();for(int prb=0;prb<rb;prb++){int base=12*prb;for(int l=0;l<symbols;l++){int width=l==0?6:4,groups=l==0?2:3;if((l==1&&cell.mib.antennaPorts==4)||(l==3&&cell.extendedCp)){width=6;groups=2;}for(int g=0;g<groups;g++){int start=base+g*width;int[] re=new int[4];int out=0;for(int d=0;d<width;d++){int a=start+d;if(l==0&&Math.floorMod(a-cell.pci,3)==0)continue;if(l==1&&cell.mib.antennaPorts==4&&Math.floorMod(a-cell.pci,3)==0)continue;if(out<4)re[out++]=a;}if(out==4)regs.add(new ControlReg(l,start,re));}}}
		Collections.sort(regs,(a,b)->a.start!=b.start?Integer.compare(a.start,b.start):Integer.compare(a.symbol,b.symbol));boolean[] removed=new boolean[regs.size()];int kbar=6*Math.floorMod(cell.pci,2*rb);for(int q=0;q<4;q++){int base=Math.floorMod(kbar+6*(q*rb/2),nsc);for(int i=0;i<regs.size();i++)if(regs.get(i).symbol==0&&regs.get(i).start==base)removed[i]=true;}
		List<Integer> firstSymbol=new ArrayList<Integer>();for(int i=0;i<regs.size();i++)if(regs.get(i).symbol==0&&!removed[i])firstSymbol.add(i);int n0=firstSymbol.size();for(int group=0;group<control.phichGroups;group++)for(int q=0;q<3;q++){int number=Math.floorMod(cell.pci+group+q*(n0/3),n0);removed[firstSymbol.get(number)]=true;}
		double[][] symbolMetric=new double[2][symbols],bandMetric=new double[2][3];List<double[]> mapped=new ArrayList<double[]>();List<ControlReg> mappedRegs=new ArrayList<ControlReg>();for(int index=0;index<regs.size();index++)if(!removed[index]){ControlReg reg=regs.get(index);double[] soft=new double[8];if(cell.mib.antennaPorts==1){for(int q=0;q<4;q++){int a=reg.re[q];double[] h=estimateRows(channelRows[0],reg.symbol,carriers[a],carriers[a]);double den=h[0]*h[0]+h[1]*h[1];if(den<1e-9)den=1;soft[2*q]=(grid[reg.symbol][0][a]*h[0]+grid[reg.symbol][1][a]*h[1])/den;soft[2*q+1]=(grid[reg.symbol][1][a]*h[0]-grid[reg.symbol][0][a]*h[1])/den;}}else for(int q=0;q<4;q+=2){int a=reg.re[q],b=reg.re[q+1],portA=cell.mib.antennaPorts==4&&q==2?1:0,portB=cell.mib.antennaPorts==4?(q==2?3:2):1;double[] h0=estimateRows(channelRows[portA],reg.symbol,carriers[a],carriers[b]),h1=estimateRows(channelRows[portB],reg.symbol,carriers[a],carriers[b]);double[] pair=equalizeTransmitDiversityPair(grid[reg.symbol][0][a],grid[reg.symbol][1][a],grid[reg.symbol][0][b],grid[reg.symbol][1][b],h0[0],h0[1],h1[0],h1[1]);System.arraycopy(pair,0,soft,2*q,4);}mapped.add(soft);mappedRegs.add(reg);}
		double[][] phaseStats=correctBlindQpskPhase(mapped,mappedRegs,symbols);for(int index=0;index<mapped.size();index++){ControlReg reg=mappedRegs.get(index);double[] soft=mapped.get(index);int band=Math.min(2,Math.max(0,3*reg.start/nsc));accumulateQpskMetric(soft,symbolMetric,reg.symbol);accumulateQpskMetric(soft,bandMetric,band);}
		int m=mapped.size();if(m!=control.pdcchRegs)return PdcchData.EMPTY;double[][] shifted=new double[m][];for(int i=0;i<m;i++)shifted[i]=mapped.get(Math.floorMod(i-cell.pci,m));double[][] ordered=new double[m][];int rows=(m+31)/32,dummy=rows*32-m,out=0;int[] permutation={1,17,9,25,5,21,13,29,3,19,11,27,7,23,15,31,0,16,8,24,4,20,12,28,2,18,10,26,6,22,14,30};for(int column=0;column<32;column++)for(int row=0;row<rows;row++){int original=row*32+permutation[column];if(original>=dummy)ordered[original-dummy]=shifted[out++];}
		double[] llr=new double[control.cceCount*72];int[] scramble=gold(subframe*512+cell.pci,llr.length);for(int reg=0;reg<control.cceCount*9;reg++)for(int bit=0;bit<8;bit++){int at=reg*8+bit;llr[at]=ordered[reg][bit]*(scramble[at]==0?1:-1);}double sum=0;for(double value:llr)sum+=Math.abs(value);DciData dci=findSystemDci(llr,control.cceCount);if(dci.valid)parseDci1A(dci,rb);return new PdcchData(true,control.cceCount,llr,sum/Math.max(1,llr.length),qpskEvm(llr),metricEvm(symbolMetric),metricEvm(bandMetric),phaseStats[0],phaseStats[1],phaseStats[2],dci);
	}
	private static double[][] correctBlindQpskPhase(List<double[]> values,List<ControlReg> regs,int symbols){double[] real=new double[symbols],imag=new double[symbols],sumAmp=new double[symbols],sumAmp2=new double[symbols],count=new double[symbols];for(int r=0;r<values.size();r++){double[] soft=values.get(r);int symbol=regs.get(r).symbol;for(int q=0;q+1<soft.length;q+=2){double re=soft[q],im=soft[q+1],amp=Math.hypot(re,im);if(amp<1e-12)continue;double angle=4*Math.atan2(im,re);real[symbol]+=Math.cos(angle);imag[symbol]+=Math.sin(angle);sumAmp[symbol]+=amp;sumAmp2[symbol]+=amp*amp;count[symbol]++;}}double[] phase=new double[symbols],coherence=new double[symbols],amplitudeCv=new double[symbols];for(int s=0;s<symbols;s++){if(count[s]<1)continue;phase[s]=Math.atan2(Math.sin(Math.atan2(imag[s],real[s])-Math.PI),Math.cos(Math.atan2(imag[s],real[s])-Math.PI))/4;coherence[s]=Math.hypot(real[s],imag[s])/count[s];double mean=sumAmp[s]/count[s];amplitudeCv[s]=mean<1e-12?0:Math.sqrt(Math.max(0,sumAmp2[s]/count[s]-mean*mean))/mean;}for(int r=0;r<values.size();r++){int symbol=regs.get(r).symbol;if(coherence[symbol]<.10)continue;double c=Math.cos(phase[symbol]),s=Math.sin(phase[symbol]);double[] soft=values.get(r);for(int q=0;q+1<soft.length;q+=2){double re=soft[q],im=soft[q+1];soft[q]=re*c+im*s;soft[q+1]=im*c-re*s;}}return new double[][]{phase,coherence,amplitudeCv};}
	private static void accumulateQpskMetric(double[] soft,double[][] metric,int group){for(int q=0;q+1<soft.length;q+=2){double ar=Math.abs(soft[q]),ai=Math.abs(soft[q+1]),d=ar-ai;metric[0][group]+=d*d;metric[1][group]+=ar*ar+ai*ai;}}
	private static double[] metricEvm(double[][] metric){double[] result=new double[metric[0].length];for(int q=0;q<result.length;q++)result[q]=metric[1][q]<1e-12?Double.POSITIVE_INFINITY:Math.sqrt(metric[0][q]/metric[1][q]);return result;}
	private static final class ControlReg {final int symbol,start;final int[] re;ControlReg(int symbol,int start,int[] re){this.symbol=symbol;this.start=start;this.re=re;}}
	static int[] pdcchRegMapForTest(int rb,int pci,int ports,boolean extendedCp,int cfi,int phichResource){
		int symbols=cfi+(rb<=10?1:0),nsc=12*rb;List<ControlReg> regs=new ArrayList<ControlReg>();
		for(int prb=0;prb<rb;prb++){int base=12*prb;for(int l=0;l<symbols;l++){int width=l==0?6:4,groups=l==0?2:3;if((l==1&&ports==4)||(l==3&&extendedCp)){width=6;groups=2;}for(int g=0;g<groups;g++){int start=base+g*width;int[] re=new int[4];int out=0;for(int d=0;d<width;d++){int a=start+d;if(l==0&&Math.floorMod(a-pci,3)==0)continue;if(l==1&&ports==4&&Math.floorMod(a-pci,3)==0)continue;if(out<4)re[out++]=a;}if(out==4)regs.add(new ControlReg(l,start,re));}}}
		Collections.sort(regs,(a,b)->a.start!=b.start?Integer.compare(a.start,b.start):Integer.compare(a.symbol,b.symbol));boolean[] removed=new boolean[regs.size()];int kbar=6*Math.floorMod(pci,2*rb);
		for(int q=0;q<4;q++){int base=Math.floorMod(kbar+6*(q*rb/2),nsc);for(int i=0;i<regs.size();i++)if(regs.get(i).symbol==0&&regs.get(i).start==base)removed[i]=true;}
		List<Integer> firstSymbol=new ArrayList<Integer>();for(int i=0;i<regs.size();i++)if(regs.get(i).symbol==0&&!removed[i])firstSymbol.add(i);
		double[] ng={1d/6d,.5,1,2};int groups=(int)Math.ceil(ng[Math.max(0,Math.min(3,phichResource))]*rb/8d);if(extendedCp)groups*=2;int n0=firstSymbol.size();
		for(int group=0;group<groups;group++)for(int q=0;q<3;q++){int number=Math.floorMod(pci+group+q*(n0/3),n0);removed[firstSymbol.get(number)]=true;}
		List<ControlReg> available=new ArrayList<ControlReg>();for(int i=0;i<regs.size();i++)if(!removed[i])available.add(regs.get(i));int m=available.size(),rows=(m+31)/32,dummy=rows*32-m,out=0;ControlReg[] ordered=new ControlReg[m];int[] permutation={1,17,9,25,5,21,13,29,3,19,11,27,7,23,15,31,0,16,8,24,4,20,12,28,2,18,10,26,6,22,14,30};
		for(int column=0;column<32;column++)for(int row=0;row<rows;row++){int original=row*32+permutation[column];if(original>=dummy){int position=original-dummy;ordered[position]=available.get(Math.floorMod(out-pci,m));out++;}}
		int useful=m/9*9;int[] result=new int[useful];for(int q=0;q<useful;q++)result[q]=ordered[q].symbol*nsc+ordered[q].start;return result;
	}
	private static DciData findSystemDci(double[] control,int cceCount){return findCommonDci(control,cceCount,0xffff);}
	private static DciData findSib1Dci(double[] control,int cceCount,int rb){for(int aggregation:new int[]{8,4}){int candidates=aggregation==8?2:4;for(int m=0;m<candidates;m++){int first=m*aggregation;if(first+aggregation>Math.min(16,cceCount))continue;double[] received=new double[aggregation*72];System.arraycopy(control,first*72,received,0,received.length);for(int payload=16;payload<=32;payload++){int length=payload+16;double[][] streams=convDeratematch(received,length);int[] bits=viterbiTailBiting(streams,new int[]{0155,0117,0127});if(crcValidMasked(bits,payload,0xffff)){DciData dci=new DciData(true,first,aggregation,payload,bits,0);parseDci1A(dci,rb);if(dci.format1A&&!dci.distributed&&dci.mcs==6)return dci;}}}}return DciData.EMPTY;}
	private static DciData findCommonDci(double[] control,int cceCount,int rnti){for(int aggregation:new int[]{8,4}){int candidates=aggregation==8?2:4;for(int m=0;m<candidates;m++){int first=m*aggregation;if(first+aggregation>Math.min(16,cceCount))continue;double[] received=new double[aggregation*72];System.arraycopy(control,first*72,received,0,received.length);for(int payload=16;payload<=32;payload++){int length=payload+16;double[][] streams=convDeratematch(received,length);int[] bits=viterbiTailBiting(streams,new int[]{0155,0117,0127});if(crcValidMasked(bits,payload,rnti)){double reliability=0;for(double value:received)reliability+=Math.abs(value);return new DciData(true,first,aggregation,payload,bits,reliability/received.length);}}}}return DciData.EMPTY;}
	static DciData decodeSystemDciForTest(double[] control,int cceCount){return findSystemDci(control,cceCount);}
	private static double[][] convDeratematch(double[] received,int length){int[] map=convRateMap(length,received.length);double[][] streams=new double[3][length];int[][] count=new int[3][length];for(int i=0;i<received.length;i++){int code=map[i],s=code/length,b=code%length;streams[s][b]+=received[i];count[s][b]++;}for(int s=0;s<3;s++)for(int b=0;b<length;b++)if(count[s][b]>1)streams[s][b]/=count[s][b];return streams;}
	private static int[] convRateMap(int length,int outputLength){int rows=(length+31)/32,dummy=rows*32-length;int[] permutation={1,17,9,25,5,21,13,29,3,19,11,27,7,23,15,31,0,16,8,24,4,20,12,28,2,18,10,26,6,22,14,30};int block=rows*32;int[][] v=new int[3][block];for(int s=0;s<3;s++)for(int r=0;r<rows;r++)for(int c=0;c<32;c++){int input=r*32+permutation[c];v[s][c*rows+r]=input<dummy?-1:s*length+input-dummy;}int[] w=new int[3*block];for(int s=0;s<3;s++)System.arraycopy(v[s],0,w,s*block,block);int[] map=new int[outputLength];for(int k=0,j=0;k<outputLength;){int value=w[j++%w.length];if(value>=0)map[k++]=value;}return map;}
	private static boolean crcValidMasked(int[] bits,int payload,int mask){int[] work=bits.clone();for(int i=0;i<16;i++)work[payload+i]^=(mask>>>(15-i))&1;for(int i=0;i<payload;i++)if(work[i]!=0){work[i]^=1;work[i+4]^=1;work[i+11]^=1;work[i+16]^=1;}for(int i=payload;i<payload+16;i++)if(work[i]!=0)return false;return true;}
	private static void parseDci1A(DciData dci,int rb){int allocationBits=0,values=rb*(rb+1)/2;while((1<<allocationBits)<values)allocationBits++;int needed=1+1+allocationBits+5+3+1+2+2;if(dci.payloadBits<needed||dci.bits[0]!=1)return;int at=1;dci.distributed=dci.bits[at++]!=0;int riv=readBits(dci.bits,at,allocationBits);at+=allocationBits;dci.riv=riv;dci.mcs=readBits(dci.bits,at,5);at+=5;dci.harq=readBits(dci.bits,at,3);at+=3;dci.ndi=dci.bits[at++];dci.rv=readBits(dci.bits,at,2);at+=2;dci.tpc=readBits(dci.bits,at,2);dci.nPrb1a=dci.bits[at+1];int q=riv/rb,r=riv%rb,start=r,length=q+1;if(length>rb-start){length=rb-q+1;start=rb-1-r;}if(start>=0&&length>0&&start+length<=rb){dci.rbStart=start;dci.rbLength=length;dci.format1A=true;}}
	private static int readBits(int[] bits,int at,int count){int value=0;for(int i=0;i<count;i++)value=(value<<1)|bits[at+i];return value;}
	private static double[][] targetedBins(byte[] iq,int samples,int inputRate,double usefulStart,int fft,int[] carriers,boolean conjugate,double cfoHz){
		double[][] out=new double[2][carriers.length];double nominalRate=fft*15000d;
		if(inputRate<nominalRate*.99){
			double step=inputRate/nominalRate,cutoff=.48;int radius=12;
			for(int n=0;n<fft;n++){double position=usefulStart+n*step;int center=(int)Math.floor(position);double ar=0,ai=0,sum=0;
				for(int k=center-radius+1;k<=center+radius;k++){if(k<0||k>=samples)continue;double d=k-position,x=2*cutoff*d,sinc=Math.abs(x)<1e-12?1:Math.sin(Math.PI*x)/(Math.PI*x),window=.5+.5*Math.cos(Math.PI*d/radius),w=2*cutoff*sinc*window;ar+=w*iq[2*k];ai+=w*iq[2*k+1];sum+=w;}
				if(Math.abs(sum)>1e-12){ar/=128d*sum;ai/=128d*sum;}if(conjugate)ai=-ai;
				double correction=-2*Math.PI*cfoHz*(position-usefulStart)/inputRate,ca=Math.cos(correction),sa=Math.sin(correction),xr=ar*ca-ai*sa,xi=ar*sa+ai*ca;
				for(int m=0;m<carriers.length;m++){double phase=-2*Math.PI*carriers[m]*n/fft,c=Math.cos(phase),s=Math.sin(phase);out[0][m]+=xr*c-xi*s;out[1][m]+=xr*s+xi*c;}
			}
			return out;
		}
		double usefulSamples=inputRate/15000d;
		int first=Math.max(0,(int)Math.ceil(usefulStart)),last=Math.min(samples,(int)Math.ceil(usefulStart+usefulSamples));
		for(int at=first;at<last;at++){
			double elapsed=at-usefulStart,ar=iq[2*at]/128d,ai=iq[2*at+1]/128d;if(conjugate)ai=-ai;
			double correction=-2*Math.PI*cfoHz*elapsed/inputRate,ca=Math.cos(correction),sa=Math.sin(correction),xr=ar*ca-ai*sa,xi=ar*sa+ai*ca;
			for(int m=0;m<carriers.length;m++){double phase=-2*Math.PI*carriers[m]*15000d*elapsed/inputRate,c=Math.cos(phase),s=Math.sin(phase);out[0][m]+=xr*c-xi*s;out[1][m]+=xr*s+xi*c;}
		}
		return out;
	}

	private static double[][] extractPbch(double[] inI,double[] inQ,int pssAt,int fft,int pci,int orientation,boolean extendedCp) {
		OfdmGrid grid=OfdmGrid.build(inI,inQ,pssAt,fft,orientation,extendedCp);if(grid==null)return null;
		double[][] pss=grid.pss62(),received=new double[2][240]; int[] carriers=new int[240]; int out=0;
		int pbchRow=grid.pssRow+1;
		for(int symbol=0;symbol<4;symbol++) {
			double[][] data=grid.symbol(pbchRow+symbol);
			for(int m=0;m<72;m++) {
				int k=m<36?m-36:m-35;
				if(symbol<2&&Math.floorMod(m-pci,3)==0)continue;
				received[0][out]=data[0][m];received[1][out]=data[1][m];carriers[out++]=k;
			}
		}
		if(out!=240)return null;
		double[] scalar=new double[480],pssScalar=new double[480];double[][] knownPss=pssFrequency(ROOT[pci%3]);for(int i=0;i<240;i++){int k=carriers[i],p=Math.max(0,Math.min(61,k<0?k+31:k+30));double yr=pss[0][p],yi=pss[1][p],xr=knownPss[0][p],xi=knownPss[1][p],hr=yr*xr+yi*xi,hi=yi*xr-yr*xi,den=hr*hr+hi*hi;if(den<1e-9)den=1;pssScalar[2*i]=(received[0][i]*hr+received[1][i]*hi)/den;pssScalar[2*i+1]=(received[1][i]*hr-received[0][i]*hi)/den;}
		CrsChannelMap channelMap=CrsChannelMap.build(grid,pci,extendedCp);
		double[] diversity=new double[480];if(channelMap!=null)
		for(int i=0;i+1<240;i+=2){int k0=carriers[i],k1=carriers[i+1],symbol=i<48?0:i<96?1:i<168?2:3,row=pbchRow+symbol;for(int q=i;q<=i+1;q++){double[] hs=channelMap.estimate(0,row,carriers[q],carriers[q]);double den=hs[0]*hs[0]+hs[1]*hs[1];if(den<1e-9)den=1;scalar[2*q]=(received[0][q]*hs[0]+received[1][q]*hs[1])/den;scalar[2*q+1]=(received[1][q]*hs[0]-received[0][q]*hs[1])/den;}double[] h0=channelMap.estimate(0,row,k0,k1),h1=channelMap.estimate(1,row,k0,k1);double y0r=received[0][i],y0i=received[1][i],y1r=received[0][i+1],y1i=received[1][i+1];
			double[] pair=equalizeTransmitDiversityPair(y0r,y0i,y1r,y1i,h0[0],h0[1],h1[0],h1[1]);System.arraycopy(pair,0,diversity,2*i,4);
		}
		double[] diversity4=new double[480];
		if(channelMap!=null)for(int i=0;i+1<240;i+=2){int k0=carriers[i],k1=carriers[i+1],symbol=i<48?0:i<96?1:i<168?2:3,row=pbchRow+symbol;double[] a,b;int portA,portB;if((i&2)==0){portA=0;portB=2;}else{portA=1;portB=3;}a=channelMap.estimate(portA,row,k0,k1);b=channelMap.estimate(portB,row,k0,k1);double[] pair=equalizeTransmitDiversityPair(received[0][i],received[1][i],received[0][i+1],received[1][i+1],a[0],a[1],b[0],b[1]);System.arraycopy(pair,0,diversity4,2*i,4);}
		double[][] result={scalar,diversity,diversity4,pssScalar};for(int mode=0;mode<result.length;mode++){double evm=qpskEvm(result[mode]);if(mode<3)diagnosticEvm[mode]=Math.min(diagnosticEvm[mode],evm);if(evm<diagnosticConstellationEvm){diagnosticConstellationEvm=evm;diagnosticConstellation=result[mode].clone();diagnosticConstellationPorts=mode==0||mode==3?1:mode==1?2:4;}}return result;
	}

	private static final class OfdmGrid {
		private final double[][][] symbols;private final int pssRow;
		private OfdmGrid(double[][][] symbols,int pssRow){this.symbols=symbols;this.pssRow=pssRow;}
		static OfdmGrid build(double[] inI,double[] inQ,int pssAt,int fft,int orientation,boolean extendedCp){int symbolsPerSlot=extendedCp?6:7,pssRow=symbolsPerSlot-1,longCp=extendedCp?fft/4:Math.max(1,(int)Math.round(fft*10d/128d)),shortCp=extendedCp?fft/4:Math.max(1,(int)Math.round(fft*9d/128d));int[] starts=new int[pssRow+6];starts[pssRow]=pssAt;for(int row=pssRow-1;row>=0;row--){int next=row+1;starts[row]=starts[next]-fft-(next%symbolsPerSlot==0?longCp:shortCp);}for(int row=pssRow+1;row<starts.length;row++)starts[row]=starts[row-1]+fft+(row%symbolsPerSlot==0?longCp:shortCp);if(starts[0]<0||starts[starts.length-1]+fft>inI.length)return null;double omega=cyclicPrefixOffset(inI,inQ,pssAt,fft,shortCp);double[][][] values=new double[starts.length][][];for(int row=0;row<starts.length;row++)values[row]=bins72(inI,inQ,starts[row],fft,orientation,omega,pssAt);return new OfdmGrid(values,pssRow);}
		double[][] symbol(int row){return row<0||row>=symbols.length?null:symbols[row];}
		double[][] pss62(){double[][] result=new double[2][62],source=symbols[pssRow];for(int m=0;m<62;m++){result[0][m]=source[0][m+5];result[1][m]=source[1][m+5];}return result;}
	}

	private static final class CrsChannelMap {
		private final List<PilotRow>[] rows;
		private final double[] noisePower;
		private CrsChannelMap(List<PilotRow>[] rows,double[] noisePower){this.rows=rows;this.noisePower=noisePower;}
		@SuppressWarnings("unchecked") static CrsChannelMap build(OfdmGrid grid,int pci,boolean extendedCp){List<PilotRow>[] rows=new List[4];double[] noise=new double[4];for(int port=0;port<4;port++)rows[port]=new ArrayList<PilotRow>();int symbolsPerSlot=extendedCp?6:7,lastPilot=extendedCp?3:4;for(int slot=0;slot<2;slot++){int base=slot*symbolsPerSlot;for(int port=0;port<2;port++){rows[port].add(pilot(grid.symbol(base),base,pci,extendedCp,slot,0,port));rows[port].add(pilot(grid.symbol(base+lastPilot),base+lastPilot,pci,extendedCp,slot,lastPilot,port));}for(int port=2;port<4;port++)rows[port].add(pilot(grid.symbol(base+1),base+1,pci,extendedCp,slot,1,port));}for(int port=0;port<4;port++){rows[port].removeAll(Collections.singleton(null));noise[port]=filter(rows[port]);diagnosticNoise[port]=noise[port];}return new CrsChannelMap(rows,noise);}
		private static PilotRow pilot(double[][] symbol,int row,int pci,boolean extendedCp,int slot,int l,int port){if(symbol==null)return null;int cinit=(1<<10)*(7*(slot+1)+l+1)*(2*pci+1)+2*pci+(extendedCp?0:1),v;if(port==0)v=l==0?0:3;else if(port==1)v=l==0?3:0;else v=port==2?0:3;int residue=Math.floorMod(pci+v,6),j=0;double[][] channel=new double[3][12];int[] c=gold(cinit,440);for(int m=0;m<72&&j<12;m++){if(Math.floorMod(m,6)!=residue)continue;int k=m<36?m-36:m-35,sequence=104+j;double rr=(1-2*c[2*sequence])/Math.sqrt(2d),ri=(1-2*c[2*sequence+1])/Math.sqrt(2d),yr=symbol[0][m],yi=symbol[1][m];channel[0][j]=k;channel[1][j]=yr*rr+yi*ri;channel[2][j]=yi*rr-yr*ri;j++;}return j==12?new PilotRow(row,channel):null;}
		private static double filter(List<PilotRow> rows){if(rows.isEmpty())return Double.POSITIVE_INFINITY;double error=0;int errors=0;for(int r=0;r<rows.size();r++){PilotRow target=rows.get(r);double[][] filtered=new double[3][12];System.arraycopy(target.channel[0],0,filtered[0],0,12);for(int j=0;j<12;j++){double carrier=target.channel[0][j],ar=0,ai=0;int count=0;for(int rr=Math.max(0,r-1);rr<=Math.min(rows.size()-1,r+1);rr++){PilotRow source=rows.get(rr);for(int q=0;q<12;q++){double distance=Math.abs(source.channel[0][q]-carrier);if((rr==r&&distance<=6)||(rr!=r&&distance<=7)){ar+=source.channel[1][q];ai+=source.channel[2][q];count++;}}}filtered[1][j]=ar/Math.max(1,count);filtered[2][j]=ai/Math.max(1,count);double dr=target.channel[1][j]-filtered[1][j],di=target.channel[2][j]-filtered[2][j];error+=dr*dr+di*di;errors++;}target.filtered=filtered;}for(PilotRow row:rows)row.useFiltered();return error/Math.max(1,errors);}
		double[] estimate(int port,int row,int carrier0,int carrier1){List<PilotRow> source=rows[port];if(source.isEmpty())return new double[]{0,0};PilotRow before=null,after=null;for(PilotRow pilot:source){if(pilot.row<=row)before=pilot;if(pilot.row>=row){after=pilot;break;}}if(before==null)before=source.get(0);if(after==null)after=source.get(source.size()-1);double[] a=averageChannel(before.channel,carrier0,carrier1);if(before==after)return a;double[] b=averageChannel(after.channel,carrier0,carrier1);double weight=(row-before.row)/(double)(after.row-before.row);return new double[]{a[0]+weight*(b[0]-a[0]),a[1]+weight*(b[1]-a[1])};}
	}
	private static final class PilotRow {final int row;final double[][] raw;double[][] channel,filtered;PilotRow(int row,double[][] channel){this.row=row;this.raw=channel;this.channel=channel;}void useFiltered(){if(filtered!=null)channel=filtered;}}

	private static double qpskEvm(double[] symbols){double error=0,power=0;for(int i=0;i+1<symbols.length;i+=2){double ar=Math.abs(symbols[i]),ai=Math.abs(symbols[i+1]);double d=ar-ai;error+=d*d;power+=ar*ar+ai*ai;}return power<1e-12?Double.POSITIVE_INFINITY:Math.sqrt(error/power);}

	static double[] equalizeTransmitDiversityPair(double y0r,double y0i,double y1r,double y1i,double h0r,double h0i,double h1r,double h1i){
		double den=h0r*h0r+h0i*h0i+h1r*h1r+h1i*h1i;if(den<1e-9)den=1;
		return new double[]{(h0r*y0r+h0i*y0i+h1r*y1r+h1i*y1i)/den,(h0r*y0i-h0i*y0r-h1r*y1i+h1i*y1r)/den,(-h1r*y0r-h1i*y0i+h0r*y1r+h0i*y1i)/den,(h1r*y0i-h1i*y0r+h0r*y1i-h0i*y1r)/den};
	}

	private static double cyclicPrefixOffset(double[] inI,double[] inQ,int fftAt,int fft,int cp){
		if(fftAt-cp<0||fftAt+fft>inI.length)return 0;double re=0,im=0;
		for(int n=0;n<cp;n++){double ar=inI[fftAt-cp+n],ai=inQ[fftAt-cp+n],br=inI[fftAt+fft-cp+n],bi=inQ[fftAt+fft-cp+n];re+=br*ar+bi*ai;im+=bi*ar-br*ai;}
		return Math.atan2(im,re)/fft;
	}

	private static double[] interpolatedChannel(double[][] channel,int carrier){
		int upper=0;while(upper<channel[0].length&&channel[0][upper]<carrier)upper++;
		if(upper<=0)return new double[]{channel[1][0],channel[2][0]};
		if(upper>=channel[0].length){int last=channel[0].length-1;return new double[]{channel[1][last],channel[2][last]};}
		int lower=upper-1;double span=channel[0][upper]-channel[0][lower],weight=span==0?0:(carrier-channel[0][lower])/span;
		return new double[]{channel[1][lower]+weight*(channel[1][upper]-channel[1][lower]),channel[2][lower]+weight*(channel[2][upper]-channel[2][lower])};
	}
	private static double[] averageChannel(double[][] channel,int carrier0,int carrier1){double[] a=interpolatedChannel(channel,carrier0),b=interpolatedChannel(channel,carrier1);return new double[]{(a[0]+b[0])*.5,(a[1]+b[1])*.5};}

	private static double[][] bins72(double[] inI,double[] inQ,int at,int fft,int orientation) {
		return bins72(inI,inQ,at,fft,orientation,0,at);
	}
	private static double[][] bins72(double[] inI,double[] inQ,int at,int fft,int orientation,double omega,int reference) {
		double[][] result=new double[2][72];
		for(int m=0;m<72;m++){int k=m<36?m-36:m-35;double re=0,im=0;
			for(int n=0;n<fft;n++){double angle=-omega*(at+n-reference),ca=Math.cos(angle),sa=Math.sin(angle),a=inI[at+n]*ca-inQ[at+n]*sa,b=inI[at+n]*sa+inQ[at+n]*ca;if(orientation!=0)b=-b;double p=-2d*Math.PI*k*n/fft;re+=a*Math.cos(p)-b*Math.sin(p);im+=a*Math.sin(p)+b*Math.cos(p);}result[0][m]=re;result[1][m]=im;
		}return result;
	}

	static MibData decodePbchBits(double[] raw,int pci) {
		return decodePbchFrames(new double[][]{raw},pci,-1);
	}

	private static MibData decodePbchFrames(double[][] frames,int pci,int mode) {
		return decodePbchFrames(frames,pci,mode,1);
	}
	private static MibData decodePbchFrames(double[][] frames,int pci,int mode,int minimumCount) {
		if(frames.length==0)return MibData.EMPTY;
		int[] scramble=gold(pci,1920),map=rateMatchMap();
		for(int count=frames.length;count>=minimumCount;count--)for(int first=0;first+count<=frames.length;first++)for(int quarter=0;quarter<4;quarter++){
			double[][] streams=new double[3][40];int[][] observations=new int[3][40];
			for(int f=0;f<count;f++){int q=(quarter+f)&3;double[] raw=frames[first+f];double power=0;for(double value:raw)power+=value*value;double scale=Math.sqrt(power/raw.length);if(scale<1e-9)scale=1;for(int i=0;i<480;i++){double value=raw[i]/scale*(scramble[q*480+i]==0?1:-1);int code=map[q*480+i],stream=code/40,bit=code%40;streams[stream][bit]+=value;observations[stream][bit]++;}}
			for(int stream=0;stream<3;stream++)for(int bit=0;bit<40;bit++)if(observations[stream][bit]>1)streams[stream][bit]/=observations[stream][bit];
			double llr=0;for(double[] stream:streams)for(double value:stream)llr+=Math.abs(value);llr/=120d;
			int[] bits=viterbiTailBiting(streams,new int[]{0155,0117,0127});
			int[] portGuesses=mode>=0?new int[]{mode==0||mode==3?1:mode==1?2:4}:new int[]{1,2,4};for(int ports:portGuesses){updatePbchDiagnostics(crcDistance(bits,ports),mode,ports,count,quarter,llr);if(crcValid(bits,ports)){int bw=(bits[0]<<2)|(bits[1]<<1)|bits[2],sfn=0;for(int i=6;i<14;i++)sfn=(sfn<<1)|bits[i];sfn=(sfn<<2)|quarter;
				int[] rb={6,15,25,50,75,100};if(bw>=rb.length)continue;return new MibData(true,rb[bw],ports,sfn,bits[3]!=0,(bits[4]<<1)|bits[5]);}
			}
		}return MibData.EMPTY;
	}

	private static void resetPbchDiagnostics(){diagnosticDistance=17;diagnosticMode=-1;diagnosticPorts=0;diagnosticFrames=0;diagnosticQuarter=0;diagnosticLlr=0;java.util.Arrays.fill(diagnosticEvm,Double.POSITIVE_INFINITY);java.util.Arrays.fill(diagnosticNoise,Double.POSITIVE_INFINITY);diagnosticConstellation=new double[0];diagnosticConstellationPorts=0;diagnosticConstellationEvm=Double.POSITIVE_INFINITY;}
	private static void updatePbchDiagnostics(int distance,int mode,int ports,int frames,int quarter,double llr){if(distance<diagnosticDistance||(distance==diagnosticDistance&&llr>diagnosticLlr)){diagnosticDistance=distance;diagnosticMode=mode;diagnosticPorts=ports;diagnosticFrames=frames;diagnosticQuarter=quarter;diagnosticLlr=llr;}}
	static String pbchDiagnostics(){String[] modes={"1-port CRS equalizer","2-port equalizer","4-port equalizer","1-port PSS equalizer"};String mode=diagnosticMode>=0&&diagnosticMode<modes.length?modes[diagnosticMode]:"bit codec";return "best CRC distance="+diagnosticDistance+"/16, "+mode+", CRC mask="+diagnosticPorts+" ports, frames="+diagnosticFrames+", quarter="+diagnosticQuarter+", mean |LLR|="+String.format(java.util.Locale.US,"%.3f",diagnosticLlr)+", EVM 1/2/4="+String.format(java.util.Locale.US,"%.1f/%.1f/%.1f%%",diagnosticEvm[0]*100,diagnosticEvm[1]*100,diagnosticEvm[2]*100)+", CRS noise="+String.format(java.util.Locale.US,"%.3g/%.3g/%.3g/%.3g",diagnosticNoise[0],diagnosticNoise[1],diagnosticNoise[2],diagnosticNoise[3]);}

	private static int crcDistance(int[] bits,int ports){int[] expected=new int[40];System.arraycopy(bits,0,expected,0,24);for(int i=0;i<24;i++)if(expected[i]!=0){expected[i]^=1;expected[i+4]^=1;expected[i+11]^=1;expected[i+16]^=1;}int distance=0;for(int i=0;i<16;i++){int value=expected[24+i];if(ports==2||(ports==4&&(i&1)==1))value^=1;if(value!=bits[24+i])distance++;}return distance;}

	private static int[] gold(int init,int length){int total=1600+length+31;int[] x1=new int[total],x2=new int[total];x1[0]=1;for(int i=0;i<31;i++)x2[i]=(init>>>i)&1;for(int n=0;n<1600+length;n++){x1[n+31]=(x1[n+3]^x1[n]);x2[n+31]=(x2[n+3]^x2[n+2]^x2[n+1]^x2[n]);}int[] c=new int[length];for(int n=0;n<length;n++)c[n]=x1[n+1600]^x2[n+1600];return c;}

	private static int[] rateMatchMap(){int[] permutation={1,17,9,25,5,21,13,29,3,19,11,27,7,23,15,31,0,16,8,24,4,20,12,28,2,18,10,26,6,22,14,30};int[][] v=new int[3][64];for(int s=0;s<3;s++)for(int r=0;r<2;r++)for(int c=0;c<32;c++){int input=r*32+permutation[c];v[s][c*2+r]=input<24?-1:s*40+input-24;}int[] w=new int[192];for(int k=0;k<64;k++){w[k]=v[0][k];w[64+k]=v[1][k];w[128+k]=v[2][k];}int[] map=new int[1920];int j=0;for(int k=0;k<map.length;){int value=w[j++%192];if(value>=0)map[k++]=value;}return map;}

	private static int[] viterbiTailBiting(double[][] llr,int[] polys){int length=llr[0].length;double best=-1e100;int[] answer=new int[length];for(int initial=0;initial<64;initial++){double[] metric=new double[64];java.util.Arrays.fill(metric,-1e100);metric[initial]=0;int[][] prev=new int[length][64],bitAt=new int[length][64];for(int t=0;t<length;t++){double[] next=new double[64];java.util.Arrays.fill(next,-1e100);for(int state=0;state<64;state++)if(metric[state]>-1e90)for(int bit=0;bit<2;bit++){int reg=(state<<1)|bit,target=reg&63;double score=metric[state];for(int s=0;s<3;s++)score+=(Integer.bitCount(reg&polys[s])&1)==0?llr[s][t]:-llr[s][t];if(score>next[target]){next[target]=score;prev[t][target]=state;bitAt[t][target]=bit;}}metric=next;}if(metric[initial]>best){best=metric[initial];int state=initial;for(int t=length-1;t>=0;t--){answer[t]=bitAt[t][state];state=prev[t][state];}}}return answer;}

	private static boolean crcValid(int[] input,int ports){int[] work=input.clone();for(int i=0;i<16;i++)if(ports==2||(ports==4&&(i&1)==1))work[24+i]^=1;for(int i=0;i<24;i++)if(work[i]!=0){work[i]^=1;work[i+4]^=1;work[i+11]^=1;work[i+16]^=1;}for(int i=24;i<40;i++)if(work[i]!=0)return false;return true;}

	private static double correlation(double[] inI,double[] inQ,double[][] reference,int orientation,int at,int fft) {
		double cr=0,ci=0,power=0;
		for(int n=0;n<fft;n++) { double a=inI[at+n],b=inQ[at+n],rr=reference[0][n],ri=orientation==0?reference[1][n]:-reference[1][n]; cr+=a*rr+b*ri;ci+=b*rr-a*ri;power+=a*a+b*b; }
		return power<=1e-12?0:Math.hypot(cr,ci)/Math.sqrt(power);
	}

	private static SssDetection detectSss(double[] inI,double[] inQ,int pssAt,int fft,int nid2,int orientation) {
		List<SssDetection> candidates=detectSssCandidates(inI,inQ,pssAt,fft,nid2,orientation,1);return candidates.isEmpty()?SssDetection.EMPTY:candidates.get(0);
	}

	private static List<SssDetection> detectSssCandidates(double[] inI,double[] inQ,int pssAt,int fft,int nid2,int orientation,int limit) {
		double[][] pssBins=bins(inI,inQ,pssAt,fft,orientation),pssReference=pssFrequency(ROOT[nid2]); SssDetection best=SssDetection.EMPTY;
		SssDetection[] byNid1=new SssDetection[168];
		int[] cps={Math.max(1,(int)Math.round(fft*9d/128d)),Math.max(1,fft/4)};
		for(int cpIndex=0;cpIndex<cps.length;cpIndex++) {
			int sssAt=pssAt-fft-cps[cpIndex]; if(sssAt<0) continue;
			double[][] sssBins=bins(inI,inQ,sssAt,fft,orientation);
			for(int nid1=0;nid1<168;nid1++) for(int half=0;half<2;half++) {
				int[] ref=sssSequence(nid1,nid2,half); double dot=0,energy=0;
				for(int m=0;m<62;m++) {
					double xr=sssBins[0][m],xi=sssBins[1][m],pr=pssBins[0][m],pi=pssBins[1][m];
					double zr=xr*pr+xi*pi,zi=xi*pr-xr*pi;
					double value=zr*pssReference[0][m]-zi*pssReference[1][m]; dot+=ref[m]*value; energy+=value*value;
				}
				double score=energy<=1e-12?0:Math.abs(dot)/Math.sqrt(62d*energy);
				SssDetection candidate=new SssDetection(score>=0.60,nid1,score,half==0?0:5,cpIndex==1);if(byNid1[nid1]==null||score>byNid1[nid1].correlation)byNid1[nid1]=candidate;if(score>best.correlation)best=candidate;
			}
		}
		List<SssDetection> result=new ArrayList<SssDetection>();for(SssDetection candidate:byNid1)if(candidate!=null&&candidate.detected)result.add(candidate);Collections.sort(result,(a,b)->Double.compare(b.correlation,a.correlation));if(result.size()>limit)return new ArrayList<SssDetection>(result.subList(0,limit));return result;
	}

	private static double[][] pssFrequency(int root) {
		double[][] result=new double[2][62];
		for(int m=0;m<62;m++) { double phase=m<31?-Math.PI*root*m*(m+1)/63d:-Math.PI*root*(m+1)*(m+2)/63d;
			result[0][m]=Math.cos(phase); result[1][m]=Math.sin(phase); }
		return result;
	}

	private static double[][] bins(double[] inI,double[] inQ,int at,int fft,int orientation) {
		return bins(inI,inQ,at,fft,orientation,0,at);
	}
	private static double[][] bins(double[] inI,double[] inQ,int at,int fft,int orientation,double omega,int reference) {
		double[][] result=new double[2][62];
		for(int m=0;m<62;m++) { int k=m<31?m-31:m-30; double re=0,im=0;
			for(int n=0;n<fft;n++) { double angle=-omega*(at+n-reference),ca=Math.cos(angle),sa=Math.sin(angle),a=inI[at+n]*ca-inQ[at+n]*sa,b=inI[at+n]*sa+inQ[at+n]*ca;if(orientation!=0)b=-b;double p=-2d*Math.PI*k*n/fft; re+=a*Math.cos(p)-b*Math.sin(p);im+=a*Math.sin(p)+b*Math.cos(p); }
			result[0][m]=re;result[1][m]=im;
		} return result;
	}

	static int[] sssSequence(int nid1,int nid2,int half) {
		int qPrime=nid1/30,q=(nid1+qPrime*(qPrime+1)/2)/30,mPrime=nid1+q*(q+1)/2;
		int m0=mPrime%31,m1=(m0+mPrime/31+1)%31;
		int[] xs=new int[31],xc=new int[31],xz=new int[31]; xs[4]=xc[4]=xz[4]=1;
		for(int i=0;i<26;i++){xs[i+5]=(xs[i+2]+xs[i])&1;xc[i+5]=(xc[i+3]+xc[i])&1;xz[i+5]=(xz[i+4]+xz[i+2]+xz[i+1]+xz[i])&1;}
		int[] d=new int[62];
		for(int n=0;n<31;n++){int s0=1-2*xs[(n+m0)%31],s1=1-2*xs[(n+m1)%31],c0=1-2*xc[(n+nid2)%31],c1=1-2*xc[(n+nid2+3)%31];
			int z0=1-2*xz[(n+(m0%8))%31],z1=1-2*xz[(n+(m1%8))%31];
			d[2*n]=(half==0?s0:s1)*c0; d[2*n+1]=(half==0?s1*z0:s0*z1)*c1;
		} return d;
	}

	private static double repeatScore(double[] inI,double[] inQ,double[][] reference,int orientation,
			int first,int fft,int rate) {
		int period=(int)Math.round(rate/200d), radius=Math.max(2,fft/64), samples=inI.length;
		double best=0;
		for(int direction : new int[]{-1,1}) {
			int expected=first+direction*period;
			for(int at=expected-radius;at<=expected+radius;at++) {
				if(at<0 || at+fft>samples) continue;
				double cr=0,ci=0,power=0;
				for(int n=0;n<fft;n++) {
					double a=inI[at+n],b=inQ[at+n],rr=reference[0][n],ri=orientation==0?reference[1][n]:-reference[1][n];
					cr+=a*rr+b*ri; ci+=b*rr-a*ri; power+=a*a+b*b;
				}
				if(power>1e-12) best=Math.max(best,Math.hypot(cr,ci)/Math.sqrt(power));
			}
		}
		return best;
	}

	private static double[][] reference(int root,int size) {
		double[] real=new double[size],imag=new double[size];
		for(int m=0;m<62;m++) {
			int n=m<31?m:m+1;
			double phase=m<31 ? -Math.PI*root*m*(m+1)/63d : -Math.PI*root*(m+1)*(m+2)/63d;
			double dr=Math.cos(phase),di=Math.sin(phase);
			int k=n-31; // -31..-1, +1..+31
			for(int t=0;t<size;t++) {
				double p=2d*Math.PI*k*t/size;
				real[t]+=dr*Math.cos(p)-di*Math.sin(p); imag[t]+=dr*Math.sin(p)+di*Math.cos(p);
			}
		}
		double energy=0; for(int i=0;i<size;i++) energy+=real[i]*real[i]+imag[i]*imag[i];
		double scale=energy<=0?1:1/Math.sqrt(energy);
		for(int i=0;i<size;i++){real[i]*=scale;imag[i]*=scale;}
		return new double[][]{real,imag};
	}

	static final class Candidate {
		final int nid2,sample,iqOrientation,pci,subframe,frameStartSample,pbchSample; final double correlation,sssCorrelation,halfFrameSamples,samplingPpm,cfoHz; final boolean extendedCp; final MibData mib;
		CfiData cfi=CfiData.EMPTY;
		LoadData load=LoadData.EMPTY;
		ControlRegionData control=ControlRegionData.EMPTY;
		PdcchData pdcch=PdcchData.EMPTY;
		CfiData sib1Cfi=CfiData.EMPTY;PdcchData sib1Pdcch=PdcchData.EMPTY;int sib1FrameOffset;
		PdschData sib1Pdsch=PdschData.EMPTY;Sib1TransportData sib1Transport=Sib1TransportData.EMPTY;Sib1Data sib1=Sib1Data.EMPTY;
		CfiData siCfi=CfiData.EMPTY;PdcchData siPdcch=PdcchData.EMPTY;PdschData siPdsch=PdschData.EMPTY;Sib1TransportData siTransport=Sib1TransportData.EMPTY;SiData si=SiData.EMPTY;int siFrameOffset,siSubframe=-1;
		int pagingScanCursor;
		final List<Integer> pagingEvents=new ArrayList<Integer>();
		Candidate(int nid2,double correlation,int sample,int iqOrientation,int pci,double sssCorrelation,int subframe,boolean extendedCp,int frameStartSample,int pbchSample,MibData mib,double halfFrameSamples,double samplingPpm,double cfoHz){this.nid2=nid2;this.correlation=correlation;this.sample=sample;this.iqOrientation=iqOrientation;this.pci=pci;this.sssCorrelation=sssCorrelation;this.subframe=subframe;this.extendedCp=extendedCp;this.frameStartSample=frameStartSample;this.pbchSample=pbchSample;this.mib=mib;this.halfFrameSamples=halfFrameSamples;this.samplingPpm=samplingPpm;this.cfoHz=cfoHz;}
		Candidate withTiming(int frameStart,int pbch){Candidate result=new Candidate(nid2,correlation,sample,iqOrientation,pci,sssCorrelation,subframe,extendedCp,frameStart,pbch,mib,halfFrameSamples,samplingPpm,cfoHz);result.load=load;result.cfi=cfi;result.control=control;result.pdcch=pdcch;result.sib1Cfi=sib1Cfi;result.sib1Pdcch=sib1Pdcch;result.sib1FrameOffset=sib1FrameOffset;result.sib1Pdsch=sib1Pdsch;result.sib1Transport=sib1Transport;result.sib1=sib1;result.siCfi=siCfi;result.siPdcch=siPdcch;result.siPdsch=siPdsch;result.siTransport=siTransport;result.si=si;result.siFrameOffset=siFrameOffset;result.siSubframe=siSubframe;result.pagingScanCursor=pagingScanCursor;result.pagingEvents.addAll(pagingEvents);return result;}
		Candidate withMib(MibData value){Candidate result=new Candidate(nid2,correlation,sample,iqOrientation,pci,sssCorrelation,subframe,extendedCp,frameStartSample,pbchSample,value,halfFrameSamples,samplingPpm,cfoHz);result.load=load;result.cfi=cfi;result.control=control;result.pdcch=pdcch;result.sib1Cfi=sib1Cfi;result.sib1Pdcch=sib1Pdcch;result.sib1FrameOffset=sib1FrameOffset;result.sib1Pdsch=sib1Pdsch;result.sib1Transport=sib1Transport;result.sib1=sib1;result.siCfi=siCfi;result.siPdcch=siPdcch;result.siPdsch=siPdsch;result.siTransport=siTransport;result.si=si;result.siFrameOffset=siFrameOffset;result.siSubframe=siSubframe;result.pagingScanCursor=pagingScanCursor;result.pagingEvents.addAll(pagingEvents);return result;}
	}
	private static final class CellLock {
		MibData mib=MibData.EMPTY;Sib1Data sib1=Sib1Data.EMPTY;SiData si=SiData.EMPTY;
		int nid2=-1,orientation=1,pagingScanCursor;
		void capture(Candidate cell){nid2=cell.nid2;orientation=cell.iqOrientation;pagingScanCursor=cell.pagingScanCursor;if(cell.mib.valid)mib=cell.mib;if(cell.sib1.valid)sib1=cell.sib1;if(cell.si.valid)si=cell.si;}
		void restore(Candidate cell){cell.pagingScanCursor=pagingScanCursor;if(sib1.valid)cell.sib1=sib1;if(si.valid)cell.si=si;}
	}
	private static final class PssPeak { final int sample;final double correlation,halfFrameSamples,samplingPpm,cfoHz;PssPeak(int sample,double correlation,double halfFrameSamples,double samplingPpm,double cfoHz){this.sample=sample;this.correlation=correlation;this.halfFrameSamples=halfFrameSamples;this.samplingPpm=samplingPpm;this.cfoHz=cfoHz;} }
	private static final class SssDetection {
		static final SssDetection EMPTY=new SssDetection(false,-1,0,-1,false);
		final boolean detected;final int nid1,subframe;final double correlation;final boolean extendedCp;
		SssDetection(boolean detected,int nid1,double correlation,int subframe,boolean extendedCp){this.detected=detected;this.nid1=nid1;this.correlation=correlation;this.subframe=subframe;this.extendedCp=extendedCp;}
	}
	static final class MibData {
		static final MibData EMPTY=new MibData(false,-1,-1,-1,false,-1);
		final boolean valid;final int bandwidthRb,antennaPorts,systemFrameNumber,phichResource;final boolean phichExtended;
		MibData(boolean valid,int bandwidthRb,int antennaPorts,int systemFrameNumber,boolean phichExtended,int phichResource){this.valid=valid;this.bandwidthRb=bandwidthRb;this.antennaPorts=antennaPorts;this.systemFrameNumber=systemFrameNumber;this.phichExtended=phichExtended;this.phichResource=phichResource;}
	}
	static final class CfiData {static final CfiData EMPTY=new CfiData(false,-1,0);final boolean valid;final int cfi;final double confidence;CfiData(boolean valid,int cfi,double confidence){this.valid=valid;this.cfi=cfi;this.confidence=confidence;}}
	static final class ControlRegionData {static final ControlRegionData EMPTY=new ControlRegionData(false,0,0,0,0,0);final boolean valid;final int symbols,totalRegs,phichGroups,pdcchRegs,cceCount;ControlRegionData(boolean valid,int symbols,int totalRegs,int phichGroups,int pdcchRegs,int cceCount){this.valid=valid;this.symbols=symbols;this.totalRegs=totalRegs;this.phichGroups=phichGroups;this.pdcchRegs=pdcchRegs;this.cceCount=cceCount;}}
	static final class PdcchData {static final PdcchData EMPTY=new PdcchData(false,0,new double[0],0,Double.POSITIVE_INFINITY,new double[0],new double[0],new double[0],new double[0],new double[0],DciData.EMPTY);final boolean valid;final int cceCount;final double[] llr,symbolEvm,bandEvm,symbolPhase,symbolCoherence,symbolAmplitudeCv;final double meanReliability,qpskEvm;final DciData systemDci;PdcchData(boolean valid,int cceCount,double[] llr,double meanReliability,double qpskEvm,double[] symbolEvm,double[] bandEvm,double[] symbolPhase,double[] symbolCoherence,double[] symbolAmplitudeCv,DciData systemDci){this.valid=valid;this.cceCount=cceCount;this.llr=llr;this.meanReliability=meanReliability;this.qpskEvm=qpskEvm;this.symbolEvm=symbolEvm;this.bandEvm=bandEvm;this.symbolPhase=symbolPhase;this.symbolCoherence=symbolCoherence;this.symbolAmplitudeCv=symbolAmplitudeCv;this.systemDci=systemDci;}PdcchData withSystemDci(DciData dci){return new PdcchData(valid,cceCount,llr,meanReliability,qpskEvm,symbolEvm,bandEvm,symbolPhase,symbolCoherence,symbolAmplitudeCv,dci);}}
	static final class DciData {static final DciData EMPTY=new DciData(false,-1,0,0,new int[0],0);final boolean valid;final int firstCce,aggregation,payloadBits;final int[] bits;final double reliability;boolean format1A,distributed;int riv=-1,rbStart=-1,rbLength=-1,mcs=-1,harq=-1,ndi=-1,rv=-1,tpc=-1,nPrb1a=-1;DciData(boolean valid,int firstCce,int aggregation,int payloadBits,int[] bits,double reliability){this.valid=valid;this.firstCce=firstCce;this.aggregation=aggregation;this.payloadBits=payloadBits;this.bits=bits;this.reliability=reliability;}}
	static final class PdschData {static final PdschData EMPTY=new PdschData(false,0,new double[0],0,Double.POSITIVE_INFINITY);final boolean valid;final int resourceElements;final double[] llr;final double meanReliability,qpskEvm;PdschData(boolean valid,int resourceElements,double[] llr,double meanReliability,double qpskEvm){this.valid=valid;this.resourceElements=resourceElements;this.llr=llr;this.meanReliability=meanReliability;this.qpskEvm=qpskEvm;}}
	static final class LoadData {static final LoadData EMPTY=new LoadData(false,0,0,0,0);final boolean valid;final int activePrbSamples,totalPrbSamples,subframes;final double percent;LoadData(boolean valid,int activePrbSamples,int totalPrbSamples,int subframes,double percent){this.valid=valid;this.activePrbSamples=activePrbSamples;this.totalPrbSamples=totalPrbSamples;this.subframes=subframes;this.percent=percent;}}
	static final class Sib1TransportData {static final Sib1TransportData EMPTY=new Sib1TransportData(false,-1,new int[0],0,0,0,0);final boolean valid;final int transportBlockBits,iterations,transmissions,feedbackMask,parityMask;final int[] bits;Sib1TransportData(boolean valid,int transportBlockBits,int[] bits,int iterations){this(valid,transportBlockBits,bits,iterations,bits.length==0?0:1,0,0);}Sib1TransportData(boolean valid,int transportBlockBits,int[] bits,int iterations,int transmissions){this(valid,transportBlockBits,bits,iterations,transmissions,0,0);}Sib1TransportData(boolean valid,int transportBlockBits,int[] bits,int iterations,int transmissions,int feedbackMask,int parityMask){this.valid=valid;this.transportBlockBits=transportBlockBits;this.bits=bits;this.iterations=iterations;this.transmissions=transmissions;this.feedbackMask=feedbackMask;this.parityMask=parityMask;}}
	static final class Sib1Data {static final Sib1Data EMPTY=new Sib1Data(false,Collections.<String>emptyList(),-1,-1,false,false,false,0,-1,false,-1,-1,false,Collections.<String>emptyList(),-1,-1);final boolean valid,cellBarred,intraFrequencyReselection,csgIndication,tddConfigPresent,extensionPresent;final List<String> plmns,schedules;final int trackingAreaCode,cellIdentity,qRxLevMin,frequencyBand,tddSubframeAssignment,tddSpecialSubframePattern,siWindowMs,systemInfoValueTag;Sib1Data(boolean valid,List<String> plmns,int trackingAreaCode,int cellIdentity,boolean cellBarred,boolean intraFrequencyReselection,boolean csgIndication,int qRxLevMin,int frequencyBand,boolean tddConfigPresent,int tddSubframeAssignment,int tddSpecialSubframePattern,boolean extensionPresent,List<String> schedules,int siWindowMs,int systemInfoValueTag){this.valid=valid;this.plmns=Collections.unmodifiableList(new ArrayList<String>(plmns));this.trackingAreaCode=trackingAreaCode;this.cellIdentity=cellIdentity;this.cellBarred=cellBarred;this.intraFrequencyReselection=intraFrequencyReselection;this.csgIndication=csgIndication;this.qRxLevMin=qRxLevMin;this.frequencyBand=frequencyBand;this.tddConfigPresent=tddConfigPresent;this.tddSubframeAssignment=tddSubframeAssignment;this.tddSpecialSubframePattern=tddSpecialSubframePattern;this.extensionPresent=extensionPresent;this.schedules=Collections.unmodifiableList(new ArrayList<String>(schedules));this.siWindowMs=siWindowMs;this.systemInfoValueTag=systemInfoValueTag;}}
	static final class SiData {
		static final SiData EMPTY=new SiData();
		boolean valid,nonCriticalExtension,sib2Extension,accessBarring,mbsfn,emergencyBarred,moSignallingBarred,moDataBarred,prachHighSpeed,puschIntraAndInter,pusch64Qam,groupHopping,sequenceHopping,srsSetup,extendedUlCp,sib3Valid,antennaPort1;
		int itemCount,signallingFactor=-1,signallingTime=-1,dataFactor=-1,dataTime=-1,modificationPeriod=-1,pagingCycle=-1,pagingNb=-1,rachPreambles=-1,prachRoot=-1,prachConfig=-1,prachZeroZone=-1,prachOffset=-1,referenceSignalPower=-1,pB=-1,puschSubbands=-1,puschHopOffset=-1,groupAssignment=-1,cyclicShift=-1,deltaPucchShift=-1,nRbCqi=-1,nCsAn=-1,n1PucchAn=-1,p0Pusch=-1,alpha=-1,p0Pucch=-1,deltaMsg3=-1,t300=-1,t301=-1,t310=-1,n310=-1,t311=-1,n311=-1,ulCarrier=-1,ulBandwidth=-1,additionalEmission=-1,timeAlignment=-1,qHyst=-1,threshServingLow=-1,reselectionPriority=-1,qRxLevMin=-1,allowedBw=-1,tReselection=-1;
		SiData(){}
		SiData(boolean valid,int itemCount,boolean nonCriticalExtension,boolean sib2Extension,boolean accessBarring,boolean mbsfn,boolean emergencyBarred,boolean moSignallingBarred,boolean moDataBarred,int signallingFactor,int signallingTime,int dataFactor,int dataTime,
				int modificationPeriod,int pagingCycle,int pagingNb,int rachPreambles,int prachRoot,int prachConfig,boolean prachHighSpeed,int prachZeroZone,int prachOffset,int referenceSignalPower,int pB,int puschSubbands,boolean puschIntraAndInter,int puschHopOffset,boolean pusch64Qam,
				boolean groupHopping,int groupAssignment,boolean sequenceHopping,int cyclicShift,int deltaPucchShift,int nRbCqi,int nCsAn,int n1PucchAn,boolean srsSetup,int p0Pusch,int alpha,int p0Pucch,int deltaMsg3,boolean extendedUlCp,
				int t300,int t301,int t310,int n310,int t311,int n311,int ulCarrier,int ulBandwidth,int additionalEmission,int timeAlignment,boolean sib3Valid,int qHyst,int threshServingLow,int reselectionPriority,int qRxLevMin,int allowedBw,boolean antennaPort1,int tReselection){
			this.valid=valid;this.itemCount=itemCount;this.nonCriticalExtension=nonCriticalExtension;this.sib2Extension=sib2Extension;this.accessBarring=accessBarring;this.mbsfn=mbsfn;this.emergencyBarred=emergencyBarred;this.moSignallingBarred=moSignallingBarred;this.moDataBarred=moDataBarred;this.signallingFactor=signallingFactor;this.signallingTime=signallingTime;this.dataFactor=dataFactor;this.dataTime=dataTime;
			this.modificationPeriod=modificationPeriod;this.pagingCycle=pagingCycle;this.pagingNb=pagingNb;this.rachPreambles=rachPreambles;this.prachRoot=prachRoot;this.prachConfig=prachConfig;this.prachHighSpeed=prachHighSpeed;this.prachZeroZone=prachZeroZone;this.prachOffset=prachOffset;this.referenceSignalPower=referenceSignalPower;this.pB=pB;this.puschSubbands=puschSubbands;this.puschIntraAndInter=puschIntraAndInter;this.puschHopOffset=puschHopOffset;this.pusch64Qam=pusch64Qam;
			this.groupHopping=groupHopping;this.groupAssignment=groupAssignment;this.sequenceHopping=sequenceHopping;this.cyclicShift=cyclicShift;this.deltaPucchShift=deltaPucchShift;this.nRbCqi=nRbCqi;this.nCsAn=nCsAn;this.n1PucchAn=n1PucchAn;this.srsSetup=srsSetup;this.p0Pusch=p0Pusch;this.alpha=alpha;this.p0Pucch=p0Pucch;this.deltaMsg3=deltaMsg3;this.extendedUlCp=extendedUlCp;
			this.t300=t300;this.t301=t301;this.t310=t310;this.n310=n310;this.t311=t311;this.n311=n311;this.ulCarrier=ulCarrier;this.ulBandwidth=ulBandwidth;this.additionalEmission=additionalEmission;this.timeAlignment=timeAlignment;this.sib3Valid=sib3Valid;this.qHyst=qHyst;this.threshServingLow=threshServingLow;this.reselectionPriority=reselectionPriority;this.qRxLevMin=qRxLevMin;this.allowedBw=allowedBw;this.antennaPort1=antennaPort1;this.tReselection=tReselection;
		}
	}
	private static final class BitReader {final int[] bits;final int limit;int at;BitReader(int[] bits,int limit){this.bits=bits;this.limit=Math.min(bits.length,limit);}boolean bit(){return read(1)!=0;}int read(int count){if(count<0||at+count>limit)throw new IllegalArgumentException("SIB truncated");int value=0;while(count-->0)value=(value<<1)|bits[at++];return value;}void skip(int count){read(count);}int enumValue(int count){int value=constrained(0,count-1);if(value>=count)throw new IllegalArgumentException("invalid enum");return value;}int constrained(int min,int max){int values=max-min+1,bits=0;while((1<<bits)<values)bits++;int value=bits==0?0:read(bits);if(value>=values)throw new IllegalArgumentException("invalid constrained integer");return min+value;}}
	static final class Result {
		final boolean detected; final int nid2,sample,iqOrientation,peaks,decimation,fftSize,pci,subframe,frameStartSample,pbchSample;
		final double correlation,quality,sssCorrelation,pbchEvm; final boolean extendedCp; final MibData mib; final List<Candidate> candidates; final String state; final double[] pbchConstellation; final int pbchEqualizerPorts;
		Result(boolean detected,int nid2,double correlation,int sample,int iqOrientation,double quality,int peaks,
				int decimation,int fftSize,int pci,double sssCorrelation,int subframe,boolean extendedCp,int frameStartSample,int pbchSample,MibData mib,List<Candidate> candidates,String state,double[] pbchConstellation,int pbchEqualizerPorts,double pbchEvm){this.detected=detected;this.nid2=nid2;
			this.correlation=correlation;this.sample=sample;this.iqOrientation=iqOrientation;this.quality=quality;
			this.peaks=peaks;this.decimation=decimation;this.fftSize=fftSize;this.pci=pci;this.sssCorrelation=sssCorrelation;this.subframe=subframe;this.extendedCp=extendedCp;this.frameStartSample=frameStartSample;this.pbchSample=pbchSample;this.mib=mib;this.candidates=candidates;this.state=state;this.pbchConstellation=pbchConstellation;this.pbchEqualizerPorts=pbchEqualizerPorts;this.pbchEvm=pbchEvm;}
		static Result empty(String state){return new Result(false,-1,0,-1,1,0,0,1,0,-1,0,-1,false,-1,-1,MibData.EMPTY,Collections.<Candidate>emptyList(),state,new double[0],0,Double.POSITIVE_INFINITY);}
	}
}
