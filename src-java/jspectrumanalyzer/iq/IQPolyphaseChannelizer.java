package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Constant-work, always-on complex polyphase FFT analysis filter bank. */
final class IQPolyphaseChannelizer {
	private static final int TAPS_PER_PHASE=8;
	private static final double KAISER_BETA=5.0d;
	private static final double DETECT_SNR_DB=7d;
	private static final double CAPTURE_SECONDS=.10d;
	private static final double EXTENDED_CAPTURE_SECONDS=2.20d;
	private static final double EXTENDED_PRETRIGGER_SECONDS=.050d;
	private static final double BURST_END_SILENCE_SECONDS=.020d;
	private static final int MAX_EXTENDED_CHANNELS=8;
	private static final int MAX_CAPTURES=8;
	private static final double DC_TRACKING_ALPHA=1d/4096d;
	private final long centerHz;
	private final int inputRateHz,channels;
	private final double outputRateHz;
	private final double[] taps,historyI,historyQ,phaseI,phaseQ,powerSum,powerNow,sortedPower;
	private final double[] fftStepReal,fftStepImag;
	private final Channel[] output;
	private int historyAt,inputPhase,powerSamples;
	private long outputSamples;
	private double noisePower;
	private double dcI,dcQ;
	private boolean dcInitialized;

	IQPolyphaseChannelizer(long centerHz,int inputRateHz){
		if(inputRateHz<=0)throw new IllegalArgumentException("input sample rate must be positive");
		this.centerHz=centerHz;this.inputRateHz=inputRateHz;channels=channelCount(inputRateHz);
		outputRateHz=inputRateHz/(double)channels;taps=prototype(channels,TAPS_PER_PHASE);
		historyI=new double[taps.length];historyQ=new double[taps.length];
		phaseI=new double[channels];phaseQ=new double[channels];powerSum=new double[channels];
		powerNow=new double[channels];sortedPower=new double[channels];output=new Channel[channels];
		int fftStages=Integer.numberOfTrailingZeros(channels);
		fftStepReal=new double[fftStages];fftStepImag=new double[fftStages];
		for(int stage=0,length=2;stage<fftStages;stage++,length<<=1){
			double angle=2d*Math.PI/length;fftStepReal[stage]=Math.cos(angle);
			fftStepImag[stage]=Math.sin(angle);}
		int ringSamples=Math.max(4096,(int)Math.ceil(outputRateHz*.30d));
		for(int k=0;k<channels;k++)output[k]=new Channel(k,channelFrequency(k),ringSamples);
	}

	synchronized void accept(byte[] iq,int length){
		if(iq==null)return;int bytes=Math.min(length,iq.length)&~1;
		for(int at=0;at<bytes;at+=2){double nextI=iq[at]/128d,nextQ=iq[at+1]/128d;
			if(!dcInitialized){dcI=nextI;dcQ=nextQ;dcInitialized=true;}
			dcI+=DC_TRACKING_ALPHA*(nextI-dcI);dcQ+=DC_TRACKING_ALPHA*(nextQ-dcQ);
			historyI[historyAt]=nextI-dcI;historyQ[historyAt]=nextQ-dcQ;
			if(++historyAt==historyI.length)historyAt=0;
			if(++inputPhase<channels)continue;inputPhase=0;produce();}
	}

	synchronized List<IQDynamicChannelizer.Capture> snapshotAndKeep(){
		List<IQDynamicChannelizer.Capture> captures=new ArrayList<IQDynamicChannelizer.Capture>();
		for(Channel channel:output)if(channel.changed){float[] iq=channel.capture();channel.changed=false;
			long iqCenter=physicalChannelCenter(channel,iq);
			long frequency=refinedFrequency(iqCenter,iq);
			if(iq.length>0&&frequency>=Ism868SignalAnalyzer.BAND_START_HZ&&
					frequency<=Ism868SignalAnalyzer.BAND_END_HZ)
				captures.add(new IQDynamicChannelizer.Capture(frequency,iqCenter,
					(int)Math.round(outputRateHz*.90d),channel.levelDbfs,channel.snrDb,outputRateHz,iq,
					!channel.active));}
		Collections.sort(captures,new Comparator<IQDynamicChannelizer.Capture>(){
			@Override public int compare(IQDynamicChannelizer.Capture a,IQDynamicChannelizer.Capture b){
				return Double.compare(b.snrDb,a.snrDb);}});
		return captures.size()<=MAX_CAPTURES?captures:
				new ArrayList<IQDynamicChannelizer.Capture>(captures.subList(0,MAX_CAPTURES));
	}

	boolean matches(long centerHz,int sampleRateHz){return this.centerHz==centerHz&&inputRateHz==sampleRateHz;}
	int channelsForTest(){return channels;}
	double outputRateForTest(){return outputRateHz;}

	private void produce(){int newest=historyAt-1;if(newest<0)newest=historyI.length-1;
		for(int p=0;p<channels;p++){double si=0d,sq=0d;int sample=newest-p;
			while(sample<0)sample+=historyI.length;
			for(int tap=p;tap<taps.length;tap+=channels){double coefficient=taps[tap];
				si+=historyI[sample]*coefficient;sq+=historyQ[sample]*coefficient;
				sample-=channels;while(sample<0)sample+=historyI.length;}
			phaseI[p]=si;phaseQ[p]=sq;}
		inverseFft(phaseI,phaseQ);
		for(int k=0;k<channels;k++){double i=phaseI[k],q=phaseQ[k];output[k].append((float)i,(float)q);
			powerSum[k]+=i*i+q*q;}
		outputSamples++;if(++powerSamples>=Math.max(32,(int)Math.round(outputRateHz/1000d)))evaluatePower();
	}

	private void evaluatePower(){for(int k=0;k<channels;k++){powerNow[k]=powerSum[k]/powerSamples;
			sortedPower[k]=powerNow[k];powerSum[k]=0d;}powerSamples=0;
		Arrays.sort(sortedPower);double median=sortedPower[(channels-1)/2];
		if(noisePower<=0d)noisePower=Math.max(1e-15,median);
		else if(median<noisePower)noisePower=noisePower*.85d+median*.15d;
		else noisePower=noisePower*.99d+median*.01d;
		double threshold=noisePower*Math.pow(10d,DETECT_SNR_DB/10d);
		for(int k=0;k<channels;k++){Channel channel=output[k];
			if(powerNow[k]>threshold){beginExtendedCapture(channel);channel.active=true;
				channel.changed=true;channel.lastActiveSample=outputSamples;
				channel.levelDbfs=10d*Math.log10(Math.max(1e-15,powerNow[k]));
				channel.snrDb=10d*Math.log10(Math.max(1e-15,powerNow[k]/noisePower));
			}else if(channel.active&&outputSamples-channel.lastActiveSample>=
					Math.round(outputRateHz*BURST_END_SILENCE_SECONDS)){
				/* Publish once more after the carrier disappears.  Long frames (LoRa
				 * SF12) and burst classifiers need the completed capture, not merely
				 * the last 250 ms UI snapshot taken while it was still arriving. */
				channel.active=false;channel.changed=true;
			}}
	}

	private void beginExtendedCapture(Channel target){
		if(target.extended!=null)return;int count=0;Channel replace=null;
		for(Channel channel:output)if(channel.extended!=null){count++;
			if(replace==null||channel.lastActiveSample<replace.lastActiveSample)replace=channel;}
		if(count>=MAX_EXTENDED_CHANNELS){long stale=Math.round(outputRateHz*.35d);
			if(replace==null||outputSamples-replace.lastActiveSample<stale)return;replace.extended=null;
			replace.extendedAt=replace.extendedCount=0;}
		target.startExtended();
	}

	private long channelFrequency(int k){int signed=k<channels/2?k:k-channels;
		return Math.round(centerHz+signed*outputRateHz);}
	private long physicalChannelCenter(Channel channel,float[] iq){
		if(channel.index!=channels/2)return channel.frequencyHz;
		/* The complex Nyquist bin represents both input band edges. Its residual
		 * frequency disambiguates them: a negative residual belongs to +Fs/2,
		 * a positive residual to -Fs/2. */
		double offset=frequencyOffset(iq);
		return Math.round(centerHz+(offset<=0d?inputRateHz/2d:-inputRateHz/2d));
	}

	private long refinedFrequency(long nominal,float[] iq){
		return Math.round(nominal+frequencyOffset(iq));
	}
	private double frequencyOffset(float[] iq){
		if(iq.length<4)return 0d;double real=0d,imag=0d;
		for(int n=2;n<iq.length;n+=2){double pi=iq[n-2],pq=iq[n-1],ci=iq[n],cq=iq[n+1];
			real+=ci*pi+cq*pq;imag+=cq*pi-ci*pq;}
		if(real==0d&&imag==0d)return 0d;
		double offset=Math.atan2(imag,real)*outputRateHz/(2d*Math.PI);
		return Math.max(-outputRateHz*.49d,Math.min(outputRateHz*.49d,offset));
	}

	private static int channelCount(int rate){int count=1;
		while(count<32&&rate/(double)(count*2)>=350_000d)count*=2;return count;}

	private static double[] prototype(int channels,int phases){int count=channels*phases;
		double[] result=new double[count];double middle=(count-1)/2d,cutoff=.50d/channels;
		double denominator=besselI0(KAISER_BETA),sum=0d;
		for(int n=0;n<count;n++){double x=n-middle;double sinc=x==0d?2d*cutoff:
				Math.sin(2d*Math.PI*cutoff*x)/(Math.PI*x);double ratio=x/middle;
			double window=besselI0(KAISER_BETA*Math.sqrt(Math.max(0d,1d-ratio*ratio)))/denominator;
			result[n]=sinc*window;sum+=result[n];}
		for(int n=0;n<count;n++)result[n]/=sum;return result;
	}

	private static double besselI0(double value){double sum=1d,term=1d,quarter=value*value/4d;
		for(int n=1;n<30;n++){term*=quarter/(n*(double)n);sum+=term;if(term<sum*1e-15d)break;}return sum;}

	private void inverseFft(double[] real,double[] imag){int size=real.length;
		for(int i=1,j=0;i<size;i++){int bit=size>>1;for(;(j&bit)!=0;bit>>=1)j^=bit;j^=bit;
			if(i<j){double swap=real[i];real[i]=real[j];real[j]=swap;
				swap=imag[i];imag[i]=imag[j];imag[j]=swap;}}
		for(int length=2,stage=0;length<=size;length<<=1,stage++){
			double stepReal=fftStepReal[stage],stepImag=fftStepImag[stage];
			for(int start=0;start<size;start+=length){double wr=1d,wi=0d;
				for(int n=0;n<length/2;n++){int even=start+n,odd=even+length/2;
					double or=real[odd]*wr-imag[odd]*wi,oi=real[odd]*wi+imag[odd]*wr;
					real[odd]=real[even]-or;imag[odd]=imag[even]-oi;real[even]+=or;imag[even]+=oi;
					double next=wr*stepReal-wi*stepImag;wi=wr*stepImag+wi*stepReal;wr=next;}}}
	}

	private final class Channel{
		final int index;final long frequencyHz;final float[] ring;int at,count;boolean changed;
		float[] extended;int extendedAt,extendedCount;
		long lastActiveSample;boolean active;double levelDbfs=Double.NaN,snrDb=Double.NaN;
		Channel(int index,long frequencyHz,int samples){this.index=index;this.frequencyHz=frequencyHz;ring=new float[samples*2];}
		void append(float i,float q){ring[at*2]=i;ring[at*2+1]=q;if(++at==ring.length/2)at=0;
			if(count<ring.length/2)count++;if(extended!=null){extended[extendedAt*2]=i;extended[extendedAt*2+1]=q;
				if(++extendedAt==extended.length/2)extendedAt=0;if(extendedCount<extended.length/2)extendedCount++;}}
		void startExtended(){int samples=Math.max(ring.length/2,
				(int)Math.ceil(outputRateHz*EXTENDED_CAPTURE_SECONDS));extended=new float[samples*2];
			extendedAt=extendedCount=0;int pretrigger=Math.min(count,
					Math.max(1,(int)Math.round(outputRateHz*EXTENDED_PRETRIGGER_SECONDS)));
			int first=at-pretrigger;while(first<0)first+=ring.length/2;
			for(int n=0;n<pretrigger;n++){int source=first+n;if(source>=ring.length/2)source-=ring.length/2;
				extended[extendedAt*2]=ring[source*2];extended[extendedAt*2+1]=ring[source*2+1];
				if(++extendedAt==samples)extendedAt=0;if(extendedCount<samples)extendedCount++;}}
		float[] capture(){float[] sourceRing=extended==null?ring:extended;
			int sourceAt=extended==null?at:extendedAt,sourceCount=extended==null?count:extendedCount;
			int capacity=sourceRing.length/2;
			long endSample=Math.min(outputSamples,lastActiveSample+Math.round(outputRateHz*.005d));
			int age=(int)Math.min(Integer.MAX_VALUE,Math.max(0L,outputSamples-endSample));
			int available=Math.max(0,sourceCount-age);int requested=extended==null
					?Math.max(256,(int)Math.round(outputRateHz*CAPTURE_SECONDS)):capacity;
			int wanted=Math.min(available,requested);
			float[] result=new float[wanted*2];int end=sourceAt-age%capacity;if(end<0)end+=capacity;
			int first=end-wanted;while(first<0)first+=capacity;
			for(int n=0;n<wanted;n++){int source=first+n;if(source>=capacity)source-=capacity;
				result[n*2]=sourceRing[source*2];result[n*2+1]=sourceRing[source*2+1];}
			if(extended!=null&&!active){
				extended=null;extendedAt=extendedCount=0;}return result;}
	}
}
