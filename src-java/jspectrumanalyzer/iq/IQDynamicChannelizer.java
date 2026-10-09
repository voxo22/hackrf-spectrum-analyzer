package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Streaming activity detector and on-demand arbitrary-frequency channel bank.
 *
 * <p>A cheap continuous FFT detector watches every input sample.  New bursts
 * get a stateful mixer/FIR/decimator and a short raw-IQ pre-trigger, so a
 * protocol decoder receives the beginning of the burst as well as subsequent
 * live blocks.  Channels disappear after inactivity and are intentionally
 * protocol agnostic.</p>
 */
final class IQDynamicChannelizer {
	private static final int FFT_SIZE=512;
	private static final int MAX_CHANNELS=8;
	private static final double DETECT_SNR_DB=9d;
	private static final double MIN_LEVEL_DBFS=-82d;
	private static final double PRETRIGGER_SECONDS=.025d;
	private static final double CHANNEL_HOLD_SECONDS=.25d;
	private final long inputCenterHz;
	private final int inputRateHz;
	private final byte[] scanIq=new byte[FFT_SIZE*2];
	private final double[] scanReal=new double[FFT_SIZE],scanImag=new double[FFT_SIZE];
	private int scanLength;
	private boolean skipScanWindow;
	private long inputSamples;
	private byte[] pretrigger;
	private int pretriggerLength;
	private final List<Track> tracks=new ArrayList<Track>();

	IQDynamicChannelizer(long inputCenterHz,int inputRateHz){
		if(inputRateHz<=0)throw new IllegalArgumentException("input sample rate must be positive");
		this.inputCenterHz=inputCenterHz;this.inputRateHz=inputRateHz;
		int bytes=(int)Math.min(4_000_000L,Math.max(FFT_SIZE*2L,
				Math.round(inputRateHz*PRETRIGGER_SECONDS*2d)))&~1;
		pretrigger=new byte[bytes];
	}

	synchronized void accept(byte[] iq,int length){
		if(iq==null)return;int bytes=Math.min(length,iq.length)&~1;if(bytes<2)return;
		long blockFirstSample=inputSamples;int blockSamples=bytes/2;
		List<Detection> detections=scan(iq,bytes);
		List<Track> existing=new ArrayList<Track>(tracks);
		for(Detection detection:detections){
			Track match=matching(detection);
			if(match!=null){match.observe(detection,inputSamples+blockSamples);continue;}
			if(tracks.size()>=MAX_CHANNELS&&!makeRoom(detection))continue;
			tracks.add(new Track(detection,inputSamples+blockSamples));
		}
		/* Tracks present before this block consume only this block. New tracks are
		 * primed below from the raw ring after it includes this block. */
		for(Track track:existing)if(tracks.contains(track))track.append(iq,bytes);
		appendPretrigger(iq,bytes);
		byte[] priming=null;
		for(Track track:tracks)if(!existing.contains(track)){
			if(priming==null)priming=pretriggerSnapshot();track.append(priming,priming.length);
		}
		inputSamples=blockFirstSample+blockSamples;
	}

	synchronized List<Capture> snapshotAndKeep(){
		List<Capture> out=new ArrayList<Capture>();
		long hold=Math.round(inputRateHz*CHANNEL_HOLD_SECONDS);
		for(int n=tracks.size()-1;n>=0;n--){Track track=tracks.get(n);
			Capture capture=track.snapshot();if(capture!=null)out.add(capture);
			if(inputSamples-track.lastSeenSample>hold)tracks.remove(n);
		}
		return out;
	}

	synchronized int channelCountForTest(){return tracks.size();}
	boolean matches(long centerHz,int sampleRateHz){return inputCenterHz==centerHz&&inputRateHz==sampleRateHz;}

	private List<Detection> scan(byte[] iq,int bytes){
		List<Detection> found=new ArrayList<Detection>();
		for(int at=0;at<bytes;){int copy=Math.min(bytes-at,scanIq.length-scanLength);
			System.arraycopy(iq,at,scanIq,scanLength,copy);scanLength+=copy;at+=copy;
			if(scanLength==scanIq.length){if(skipScanWindow)skipScanWindow=false;
				else{scanWindow(found);skipScanWindow=true;}scanLength=0;}
		}
		return found;
	}

	private void scanWindow(List<Detection> found){
		double meanI=0d,meanQ=0d;
		for(int n=0;n<FFT_SIZE;n++){meanI+=scanIq[n*2];meanQ+=scanIq[n*2+1];}
		meanI/=FFT_SIZE;meanQ/=FFT_SIZE;
		for(int n=0;n<FFT_SIZE;n++){double taper=.5d-.5d*Math.cos(2d*Math.PI*n/(FFT_SIZE-1));
			scanReal[n]=(scanIq[n*2]-meanI)*taper/128d;
			scanImag[n]=(scanIq[n*2+1]-meanQ)*taper/128d;}
		fft(scanReal,scanImag);double[] db=new double[FFT_SIZE],visible=new double[FFT_SIZE];int visibleCount=0;
		for(int shifted=0;shifted<FFT_SIZE;shifted++){int signed=shifted-FFT_SIZE/2;
			int raw=signed<0?signed+FFT_SIZE:signed;double frequency=inputCenterHz+signed*inputRateHz/(double)FFT_SIZE;
			double power=(scanReal[raw]*scanReal[raw]+scanImag[raw]*scanImag[raw])/(FFT_SIZE*FFT_SIZE*.25d);
			db[shifted]=10d*Math.log10(Math.max(1e-15,power));
			if(frequency>=Ism868SignalAnalyzer.BAND_START_HZ&&frequency<=Ism868SignalAnalyzer.BAND_END_HZ
					&&Ism868SignalAnalyzer.outsideDcGuard(signed,inputRateHz,FFT_SIZE))
				visible[visibleCount++]=db[shifted];
		}
		if(visibleCount<8)return;Arrays.sort(visible,0,visibleCount);double noise=visible[visibleCount/2];
		double threshold=Math.max(MIN_LEVEL_DBFS,noise+DETECT_SNR_DB);int start=-1;
		for(int shifted=0;shifted<=FFT_SIZE;shifted++){boolean active=false;
			if(shifted<FFT_SIZE){int signed=shifted-FFT_SIZE/2;
				double frequency=inputCenterHz+signed*inputRateHz/(double)FFT_SIZE;
				active=Ism868SignalAnalyzer.outsideDcGuard(signed,inputRateHz,FFT_SIZE)
						&&frequency>=Ism868SignalAnalyzer.BAND_START_HZ
						&&frequency<=Ism868SignalAnalyzer.BAND_END_HZ&&db[shifted]>=threshold;}
			if(active&&start<0)start=shifted;
			if(!active&&start>=0){addDetection(found,start,shifted-1,db,noise);start=-1;}
		}
	}

	private void addDetection(List<Detection> found,int start,int end,double[] db,double noise){
		double total=0d,weighted=0d,peak=-150d;
		for(int shifted=start;shifted<=end;shifted++){double power=Math.pow(10d,db[shifted]/10d);
			total+=power;weighted+=(shifted-FFT_SIZE/2)*power;peak=Math.max(peak,db[shifted]);}
		if(total<=0d)return;long frequency=Math.round(inputCenterHz+weighted/total*inputRateHz/FFT_SIZE);
		int bins=end-start+1;int bandwidth=Math.max(inputRateHz/FFT_SIZE,
				(int)Math.round((bins+1d)*inputRateHz/FFT_SIZE));
		Detection next=new Detection(frequency,bandwidth,10d*Math.log10(total),peak-noise);
		for(int n=0;n<found.size();n++){Detection old=found.get(n);
			long tolerance=Math.max(100_000L,Math.max(old.bandwidthHz,next.bandwidthHz)/2L);
			if(Math.abs(old.frequencyHz-next.frequencyHz)<=tolerance){
				long low=Math.min(old.frequencyHz-old.bandwidthHz/2L,next.frequencyHz-next.bandwidthHz/2L);
				long high=Math.max(old.frequencyHz+old.bandwidthHz/2L,next.frequencyHz+next.bandwidthHz/2L);
				found.set(n,new Detection((low+high)/2L,(int)Math.min(2_000_000L,high-low),
						Math.max(old.levelDbfs,next.levelDbfs),Math.max(old.snrDb,next.snrDb)));return;}}
		found.add(next);
	}

	private Track matching(Detection detection){Track best=null;long distance=Long.MAX_VALUE;
		for(Track track:tracks){long next=Math.abs(track.frequencyHz-detection.frequencyHz);
			long tolerance=Math.max(100_000L,Math.max(track.bandwidthHz,detection.bandwidthHz)/2L);
			if(next<=tolerance&&next<distance){best=track;distance=next;}}
		return best;
	}

	private boolean makeRoom(Detection detection){int weakest=-1;double weakestSnr=Double.POSITIVE_INFINITY;
		long hold=Math.round(inputRateHz*CHANNEL_HOLD_SECONDS);
		for(int n=0;n<tracks.size();n++){Track track=tracks.get(n);
			if(inputSamples-track.lastSeenSample>hold){tracks.remove(n);return true;}
			if(track.snrDb<weakestSnr){weakestSnr=track.snrDb;weakest=n;}}
		if(weakest>=0&&detection.snrDb>weakestSnr+3d){tracks.remove(weakest);return true;}
		return false;
	}

	private void appendPretrigger(byte[] iq,int bytes){int copy=Math.min(bytes,pretrigger.length)&~1;
		if(copy==pretrigger.length){System.arraycopy(iq,bytes-copy,pretrigger,0,copy);pretriggerLength=copy;return;}
		int overflow=Math.max(0,pretriggerLength+copy-pretrigger.length)&~1;
		if(overflow>0){System.arraycopy(pretrigger,overflow,pretrigger,0,pretriggerLength-overflow);
			pretriggerLength-=overflow;}
		System.arraycopy(iq,bytes-copy,pretrigger,pretriggerLength,copy);pretriggerLength+=copy;
	}

	private byte[] pretriggerSnapshot(){byte[] out=new byte[pretriggerLength];
		System.arraycopy(pretrigger,0,out,0,out.length);return out;}

	private static void fft(double[] real,double[] imag){int size=real.length;
		for(int i=1,j=0;i<size;i++){int bit=size>>1;for(;(j&bit)!=0;bit>>=1)j^=bit;j^=bit;
			if(i<j){double swap=real[i];real[i]=real[j];real[j]=swap;
				swap=imag[i];imag[i]=imag[j];imag[j]=swap;}}
		for(int length=2;length<=size;length<<=1){double angle=-2d*Math.PI/length;
			double stepReal=Math.cos(angle),stepImag=Math.sin(angle);
			for(int start=0;start<size;start+=length){double wr=1d,wi=0d;
				for(int n=0;n<length/2;n++){int even=start+n,odd=even+length/2;
					double or=real[odd]*wr-imag[odd]*wi,oi=real[odd]*wi+imag[odd]*wr;
					real[odd]=real[even]-or;imag[odd]=imag[even]-oi;real[even]+=or;imag[even]+=oi;
					double next=wr*stepReal-wi*stepImag;wi=wr*stepImag+wi*stepReal;wr=next;}}}
	}

	private final class Track{
		final long frequencyHz;int bandwidthHz;double levelDbfs,snrDb;long lastSeenSample;
		final IQSubchannelExtractor.Stream stream;final FloatHistory history=new FloatHistory();
		Track(Detection detection,long seenSample){frequencyHz=detection.frequencyHz;
			bandwidthHz=detection.bandwidthHz;levelDbfs=detection.levelDbfs;snrDb=detection.snrDb;
			lastSeenSample=seenSample;int occupied=Math.max(12_000,Math.min(1_500_000,detection.bandwidthHz));
			int requested=Math.min(inputRateHz,Math.max(250_000,occupied*3));
			stream=new IQSubchannelExtractor.Stream(inputRateHz,frequencyHz-inputCenterHz,occupied,requested);}
		void observe(Detection detection,long seenSample){bandwidthHz=Math.max(bandwidthHz,
				(int)Math.min(2_000_000L,2L*Math.abs(frequencyHz-detection.frequencyHz)+detection.bandwidthHz));
			levelDbfs=levelDbfs*.7d+detection.levelDbfs*.3d;snrDb=Math.max(snrDb*.9d,detection.snrDb);
			lastSeenSample=seenSample;}
		void append(byte[] iq,int bytes){history.append(stream.process(iq,bytes));}
		Capture snapshot(){float[] iq=history.snapshotAndKeep();return iq.length==0?null:
				new Capture(frequencyHz,bandwidthHz,levelDbfs,snrDb,stream.sampleRateHz(),iq);}
	}

	private static final class FloatHistory{
		float[] values=new float[0];int length;double rate;boolean changed;
		void append(IQSubchannelExtractor.Result block){if(block.iq.length==0)return;rate=block.sampleRateHz;
			int capacity=(int)Math.min(3_000_000L,Math.max(16_384L,Math.round(rate*.7d)))&~1;
			if(values.length!=capacity){float[] next=new float[capacity];int keep=Math.min(length,capacity)&~1;
				if(keep>0)System.arraycopy(values,length-keep,next,0,keep);values=next;length=keep;}
			int copy=Math.min(block.iq.length,capacity)&~1;if(copy==capacity){
				System.arraycopy(block.iq,block.iq.length-copy,values,0,copy);length=copy;changed=true;return;}
			int overflow=Math.max(0,length+copy-capacity)&~1;if(overflow>0){
				System.arraycopy(values,overflow,values,0,length-overflow);length-=overflow;}
			System.arraycopy(block.iq,block.iq.length-copy,values,length,copy);length+=copy;changed=true;}
		float[] snapshotAndKeep(){if(!changed)return new float[0];float[] out=Arrays.copyOf(values,length);
			int overlap=Math.min(length,(int)Math.round(rate*.3d))&~1;
			if(overlap>0)System.arraycopy(values,length-overlap,values,0,overlap);length=overlap;changed=false;return out;}
	}

	private static final class Detection{
		final long frequencyHz;final int bandwidthHz;final double levelDbfs,snrDb;
		Detection(long frequencyHz,int bandwidthHz,double levelDbfs,double snrDb){this.frequencyHz=frequencyHz;
			this.bandwidthHz=bandwidthHz;this.levelDbfs=levelDbfs;this.snrDb=snrDb;}
	}

	static final class Capture{
		final long frequencyHz,iqCenterFrequencyHz;final int bandwidthHz;final double levelDbfs,snrDb,sampleRateHz;
		final float[] iq;final boolean complete;
		Capture(long frequencyHz,int bandwidthHz,double levelDbfs,double snrDb,double sampleRateHz,float[] iq){
			this(frequencyHz,frequencyHz,bandwidthHz,levelDbfs,snrDb,sampleRateHz,iq,true);}
		Capture(long frequencyHz,long iqCenterFrequencyHz,int bandwidthHz,double levelDbfs,double snrDb,
				double sampleRateHz,float[] iq){
			this(frequencyHz,iqCenterFrequencyHz,bandwidthHz,levelDbfs,snrDb,sampleRateHz,iq,true);}
		Capture(long frequencyHz,long iqCenterFrequencyHz,int bandwidthHz,double levelDbfs,double snrDb,
				double sampleRateHz,float[] iq,boolean complete){
			this.frequencyHz=frequencyHz;this.bandwidthHz=bandwidthHz;this.levelDbfs=levelDbfs;
			this.iqCenterFrequencyHz=iqCenterFrequencyHz;
			this.snrDb=snrDb;this.sampleRateHz=sampleRateHz;this.iq=iq;this.complete=complete;}
	}
}
