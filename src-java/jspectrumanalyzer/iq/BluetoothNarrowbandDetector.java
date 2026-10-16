package jspectrumanalyzer.iq;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Locale;

/** Streaming RF-burst measurements for Bluetooth-band IQ that is too narrow for packet decoding. */
final class BluetoothNarrowbandDetector {
	private static final double BLOCK_SECONDS=100e-6;
	private final ArrayDeque<Double> durationsMs=new ArrayDeque<Double>();
	private final ArrayDeque<Double> intervalsMs=new ArrayDeque<Double>();
	private int sampleRateHz,blockSamples,quietBlocks;
	private double noisePower=Double.NaN,dcI,dcQ,burstPeakPower,overallPeakPower;
	private boolean dcReady,active;
	private long totalSamples,activeSamples,burstStartSample,lastBurstStartSample=-1L,bursts,packetLikeBursts,
			legacyAdvertisingBursts,lastActivityMillis;
	private double minimumDurationMs=Double.POSITIVE_INFINITY,maximumDurationMs;

	void reset(int rate){sampleRateHz=rate;blockSamples=Math.max(4,(int)Math.round(rate*BLOCK_SECONDS));
		quietBlocks=0;noisePower=Double.NaN;dcReady=active=false;dcI=dcQ=0d;
		totalSamples=activeSamples=burstStartSample=bursts=packetLikeBursts=legacyAdvertisingBursts=lastActivityMillis=0L;lastBurstStartSample=-1L;
		burstPeakPower=overallPeakPower=0d;minimumDurationMs=Double.POSITIVE_INFINITY;maximumDurationMs=0d;durationsMs.clear();intervalsMs.clear();}

	void accept(byte[] iq,int length,int rate,long nowMillis){if(iq==null||length<2||rate<50_000)return;
		if(rate!=sampleRateHz)reset(rate);int samples=Math.min(length,iq.length)/2;
		int blocks=(samples+blockSamples-1)/blockSamples;double[] powers=new double[blocks];int[] counts=new int[blocks];
		int block=0;double sum=0;int count=0;
		for(int n=0;n<samples;n++){double i=iq[n*2]/128d,q=iq[n*2+1]/128d;if(!dcReady){dcI=i;dcQ=q;dcReady=true;}
			// Keep DC tracking much slower than a packet. A fast tracker creates a false
			// decaying tail after every burst, which inflates the measured duration.
			dcI+=(i-dcI)/65536d;dcQ+=(q-dcQ)/65536d;i-=dcI;q-=dcQ;sum+=i*i+q*q;count++;
			if(count>=blockSamples||n==samples-1){powers[block]=sum/Math.max(1,count);counts[block]=count;block++;sum=0d;count=0;}}
		double[] sorted=Arrays.copyOf(powers,block);Arrays.sort(sorted);double estimate=sorted[Math.min(block-1,Math.max(0,block/5))];
		if(!Double.isFinite(noisePower))noisePower=Math.max(1e-5,estimate);else {double alpha=estimate<noisePower?.35d:.04d;noisePower+=alpha*(estimate-noisePower);noisePower=Math.max(1e-5,noisePower);}
		double on=noisePower*3.981071706d,off=noisePower*1.995262315d;
		for(int n=0;n<block;n++){double power=powers[n];long start=totalSamples;totalSamples+=counts[n];
			if(!active&&power>=on){active=true;quietBlocks=0;burstStartSample=start;burstPeakPower=power;
				if(lastBurstStartSample>=0){double interval=(start-lastBurstStartSample)*1000d/sampleRateHz;
					if(interval>=2d&&interval<=10_000d){intervalsMs.addLast(interval);while(intervalsMs.size()>257)intervalsMs.removeFirst();}}
				lastBurstStartSample=start;}
			if(active){burstPeakPower=Math.max(burstPeakPower,power);if(power>=off){quietBlocks=0;activeSamples+=counts[n];lastActivityMillis=nowMillis;}
				else if(++quietBlocks>=2){long end=Math.max(burstStartSample,totalSamples-quietBlocks*(long)blockSamples);finishBurst(end);active=false;quietBlocks=0;}}
		}
	}

	Result snapshot(){double seconds=sampleRateHz<=0?0d:totalSamples/(double)sampleRateHz;
		double duty=totalSamples==0?0d:activeSamples*100d/totalSamples;double median=medianDuration();
		String state=bursts==0?"No narrowband burst activity above the adaptive noise floor":
				String.format(Locale.US,"Narrowband BT/BLE-channel activity: %d bursts, median %.3f ms",bursts,median);
		return new Result(state,bursts,packetLikeBursts,legacyAdvertisingBursts,db(noisePower),db(overallPeakPower),median,median(intervalsMs),
				Double.isFinite(minimumDurationMs)?minimumDurationMs:Double.NaN,
				maximumDurationMs,duty,seconds<=0?0d:bursts/seconds,lastActivityMillis);
	}

	private void finishBurst(long endSample){double ms=(endSample-burstStartSample)*1000d/sampleRateHz;if(ms<.04d)return;
		overallPeakPower=Math.max(overallPeakPower,burstPeakPower);
		bursts++;if(ms>=.068d&&ms<=17.1d)packetLikeBursts++;
		// A primary-channel LE 1M packet occupies roughly 80..376 us. The wider guard
		// allows for the 100-us envelope blocks; duration alone is never a positive ID.
		if(ms>=.060d&&ms<=.600d)legacyAdvertisingBursts++;
		minimumDurationMs=Math.min(minimumDurationMs,ms);
		maximumDurationMs=Math.max(maximumDurationMs,ms);durationsMs.addLast(ms);while(durationsMs.size()>257)durationsMs.removeFirst();}
	private double medianDuration(){return median(durationsMs);}
	private static double median(ArrayDeque<Double> source){if(source.isEmpty())return Double.NaN;double[] values=new double[source.size()];int n=0;for(Double value:source)values[n++]=value;Arrays.sort(values);return values[values.length/2];}
	private static double db(double power){return !Double.isFinite(power)||power<=0d?Double.NaN:10d*Math.log10(power/2d);}

	static final class Result {
		final String state;final long bursts,packetLikeBursts,legacyAdvertisingBursts,lastActivityMillis;final double noiseDbfs,peakDbfs,
				medianDurationMs,medianIntervalMs,minimumDurationMs,maximumDurationMs,dutyCyclePercent,burstsPerSecond;
		Result(String state,long bursts,long packetLikeBursts,long legacyAdvertisingBursts,double noiseDbfs,double peakDbfs,double medianDurationMs,double medianIntervalMs,
				double minimumDurationMs,double maximumDurationMs,double dutyCyclePercent,double burstsPerSecond,long lastActivityMillis){this.state=state;this.bursts=bursts;this.packetLikeBursts=packetLikeBursts;this.legacyAdvertisingBursts=legacyAdvertisingBursts;this.noiseDbfs=noiseDbfs;this.peakDbfs=peakDbfs;this.medianDurationMs=medianDurationMs;this.medianIntervalMs=medianIntervalMs;this.minimumDurationMs=minimumDurationMs;this.maximumDurationMs=maximumDurationMs;this.dutyCyclePercent=dutyCyclePercent;this.burstsPerSecond=burstsPerSecond;this.lastActivityMillis=lastActivityMillis;}
		static Result empty(){return new Result("Waiting for IQ samples",0,0,0,Double.NaN,Double.NaN,Double.NaN,Double.NaN,Double.NaN,Double.NaN,0d,0d,0L);}
	}
}
