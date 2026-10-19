package jspectrumanalyzer.iq;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Wide-band activity detector for the European 863-870 MHz SRD/ISM band.
 *
 * <p>This class deliberately calls frequency/modulation matches candidates.
 * A protocol name without a valid PHY frame and checksum would be misleading,
 * especially around 868.3 MHz where Wireless M-Bus and LoRa deployments can
 * overlap.  Protocol decoders can attach a payload to an {@link Observation}
 * once they have actually locked.</p>
 */
final class Ism868SignalAnalyzer {
	static final long BAND_START_HZ = 863_000_000L;
	static final long BAND_END_HZ = 870_000_000L;
	private static final int FFT_SIZE = 4096;
	private static final int MAX_FFT_WINDOWS = 12;
	private static final long DC_GUARD_HZ = 12_000L;
	private static final double MIN_SNR_DB = 8.0d;
	private static final double MIN_LEVEL_DBFS = -78d;
	private static final long[] LORA_EU868_CHANNELS = {
			867_100_000L, 867_300_000L, 867_500_000L, 867_700_000L, 867_900_000L,
			868_100_000L, 868_300_000L, 868_500_000L, 869_525_000L
	};
	private final WmbusPhyDecoder wmbusDecoder = new WmbusPhyDecoder();
	private final FineOffsetWh65bDecoder fineOffsetDecoder=new FineOffsetWh65bDecoder();
	private final RenaultTpmsDecoder renaultDecoder=new RenaultTpmsDecoder();
	private final TapoSubGDecoder tapoDecoder=new TapoSubGDecoder();
	private final JablotronJa100Classifier jablotronClassifier=new JablotronJa100Classifier();
	private final Ism8685Classifier ism8685Classifier=new Ism8685Classifier();
	private final LoRaPhyDecoder loraDecoder=new LoRaPhyDecoder();
	private final AutomotiveKeyfobClassifier keyfobClassifier=new AutomotiveKeyfobClassifier();
	private final Ism868ProtocolDispatcher protocolDispatcher=new Ism868ProtocolDispatcher();
	private final Map<Long,LoRaLockState> loraPreambleLocks=new HashMap<Long,LoRaLockState>();

	Result analyze(long centerFrequencyHz, int sampleRateHz, byte[] iq, int length,
			long captureMillis) {
		return analyze(centerFrequencyHz,sampleRateHz,iq,length,captureMillis,null);
	}

	Result analyze(long centerFrequencyHz,int sampleRateHz,byte[] iq,int length,
			long captureMillis,ChannelizedWmbus channelized) {
		if (iq == null || sampleRateHz <= 0) return Result.empty(centerFrequencyHz, sampleRateHz,
				"Waiting for IQ samples");
		int samples = Math.min(length, iq.length) / 2;
		if (samples < FFT_SIZE) return Result.empty(centerFrequencyHz, sampleRateHz,
				"Collecting a wider IQ window");
		double visibleStart = centerFrequencyHz - sampleRateHz / 2d;
		double visibleEnd = centerFrequencyHz + sampleRateHz / 2d;
		if (visibleEnd < BAND_START_HZ || visibleStart > BAND_END_HZ)
			return Result.empty(centerFrequencyHz, sampleRateHz,
					"The current IQ window does not overlap 863-870 MHz");

		double[] accumulated = new double[FFT_SIZE];
		double[] real = new double[FFT_SIZE];
		double[] imag = new double[FFT_SIZE];
		int availableWindows = samples / FFT_SIZE;
		int windows = Math.min(MAX_FFT_WINDOWS, availableWindows);
		for (int window = 0; window < windows; window++) {
			int first = windows == 1 ? samples - FFT_SIZE
					: (int) ((long) window * (samples - FFT_SIZE) / (windows - 1));
			double meanI = 0d, meanQ = 0d;
			for (int n = 0; n < FFT_SIZE; n++) {
				meanI += iq[(first + n) * 2];
				meanQ += iq[(first + n) * 2 + 1];
			}
			meanI /= FFT_SIZE; meanQ /= FFT_SIZE;
			for (int n = 0; n < FFT_SIZE; n++) {
				double taper = .5d - .5d * Math.cos(2d * Math.PI * n / (FFT_SIZE - 1));
				real[n] = (iq[(first + n) * 2] - meanI) * taper / 128d;
				imag[n] = (iq[(first + n) * 2 + 1] - meanQ) * taper / 128d;
			}
			fft(real, imag);
			for (int bin = 0; bin < FFT_SIZE; bin++)
				accumulated[bin] += (real[bin] * real[bin] + imag[bin] * imag[bin])
						/ (FFT_SIZE * FFT_SIZE * .25d);
		}

		double[] power = new double[FFT_SIZE];
		for (int bin = 0; bin < FFT_SIZE; bin++) {
			double smoothed = accumulated[bin];
			for (int side = 1; side <= 2; side++)
				smoothed += .55d / side * (accumulated[(bin + side) & (FFT_SIZE - 1)]
						+ accumulated[(bin - side) & (FFT_SIZE - 1)]);
			power[bin] = smoothed / windows;
		}
		double noiseDb = estimateNoiseFloor(power, centerFrequencyHz, sampleRateHz);
		double thresholdDb = Math.max(MIN_LEVEL_DBFS, noiseDb + MIN_SNR_DB);
		double threshold = Math.pow(10d, thresholdDb / 10d);
		List<Cluster> raw = clusters(power, threshold, centerFrequencyHz, sampleRateHz);
		List<Cluster> merged = mergeNearby(raw, sampleRateHz);
		List<Observation> observations = new ArrayList<Observation>();
		for (Cluster cluster : merged) {
			Observation observation = describe(cluster, power, noiseDb, centerFrequencyHz,
					sampleRateHz, captureMillis);
			if (observation != null) observations.add(observation);
		}
		if(channelized!=null)for(Ism868ProtocolDispatcher.Dispatch dispatch:
				protocolDispatcher.dispatch(channelized.dynamic,captureMillis,channelized.exhaustive))
			mergeDynamicChannel(observations,dispatch,captureMillis);
		/* The JA-100 waveform sits directly around 868.1 MHz, where a wide-IQ
		 * centre/DC guard or PFB boundary can obscure its narrow GFSK tones. This
		 * block-gated probe still requires a 40 ksym/s alternating-preamble PHY. */
		if(visible(centerFrequencyHz,sampleRateHz,JablotronJa100Classifier.CHANNEL_HZ))
			for(JablotronJa100Classifier.Candidate candidate:
					jablotronClassifier.classify(iq,length,sampleRateHz))
				mergeJablotronCandidate(observations,candidate,centerFrequencyHz,captureMillis);
		if(visible(centerFrequencyHz,sampleRateHz,Ism8685Classifier.CHANNEL_HZ))
			for(Ism8685Classifier.Candidate candidate:ism8685Classifier.classify(iq,length,sampleRateHz))
				merge8685Candidate(observations,candidate,centerFrequencyHz,captureMillis);
		/* A transmitter exactly at the wide-IQ centre straddles two PFB branches.
		 * Decode the three fixed Tapo channels from the undivided window as a
		 * fallback; the sync + length + CRC gate makes this non-speculative. */
		long tapoChannel=visibleTapoChannel(centerFrequencyHz,sampleRateHz);
		if(tapoChannel>0L&&hasCandidateNear(observations,tapoChannel,220_000L)
				&&!hasProtocol(observations,"Tapo Sub-GHz")){
			float[] tapoIq=new float[Math.min(length,iq.length)&~1];
			for(int n=0;n<tapoIq.length;n++)tapoIq[n]=iq[n]/128f;
			double level=Double.NaN,snr=Double.NaN;for(Observation candidate:observations)
				if(Math.abs(candidate.frequencyHz-tapoChannel)<=220_000L){level=candidate.levelDbfs;snr=candidate.snrDb;break;}
			IQDynamicChannelizer.Capture rawCapture=new IQDynamicChannelizer.Capture(tapoChannel,
					tapoChannel,200_000,level,snr,sampleRateHz,tapoIq,true);
			for(TapoSubGDecoder.Decoded decoded:tapoDecoder.decodeAll(tapoIq,sampleRateHz))
				confirmTapo(observations,rawCapture,decoded,captureMillis);
		}
		/* Do not gate protocol acquisition on the FFT candidates. A short meter
		 * burst can fall between the selected FFT windows, while a valid PHY sync
		 * plus every EN 13757 CRC is already a much stronger detector. */
		List<WmbusPhyDecoder.Decoded> ctFrames=channelized!=null&&channelized.ctIq.length>0
				?wmbusDecoder.decodeCtBasebandAll(channelized.ctIq,channelized.ctIq.length/2,
						channelized.ctRateHz)
				:wmbusDecoder.decodeCtAll(centerFrequencyHz,sampleRateHz,iq,length);
		for(WmbusPhyDecoder.Decoded ct:ctFrames)
			confirmWmbus(observations,ct,WmbusPhyDecoder.CT_FREQUENCY_HZ,captureMillis);
		List<WmbusPhyDecoder.Decoded> sFrames=channelized!=null&&channelized.sIq.length>0
				?wmbusDecoder.decodeSBasebandAll(channelized.sIq,channelized.sIq.length/2,
						channelized.sRateHz)
				:wmbusDecoder.decodeSAll(centerFrequencyHz,sampleRateHz,iq,length);
		for(WmbusPhyDecoder.Decoded s:sFrames)
			confirmWmbus(observations,s,WmbusPhyDecoder.S_FREQUENCY_HZ,captureMillis);
		/* Narrow replay/live windows bypass the wide-band PFB.  Decode CSS from
		 * the retained raw history when its spectrum has already produced a LoRa
		 * candidate; this keeps the expensive SF search away from unrelated IQ. */
		if(sampleRateHz<=1_000_000&&hasLoraCandidate(observations)){
			float[] loraIq=new float[Math.min(length,iq.length)&~1];
			for(int n=0;n<loraIq.length;n++)loraIq[n]=iq[n]/128f;
			for(LoRaPhyDecoder.Decoded decoded:loraDecoder.decodeAll(loraIq,sampleRateHz))
				if(!decoded.hasCrc||decoded.crcValid)confirmLoRa(observations,null,decoded,
						centerFrequencyHz,captureMillis);
		}
		Collections.sort(observations, new Comparator<Observation>() {
			@Override public int compare(Observation left, Observation right) {
				return Double.compare(right.levelDbfs, left.levelDbfs);
			}
		});
		/* Never discard CRC-valid telegrams merely because a busy band also
		 * produced many spectral candidates.  Only the speculative rows are
		 * capped; decoded meter IDs must all reach the aggregation table. */
		if(observations.size()>24){
			List<Observation> limited=new ArrayList<Observation>();int candidates=0;
			for(Observation observation:observations)
				if(isIdentified(observation)||candidates++<24)limited.add(observation);
			observations=limited;
		}
		String state = observations.isEmpty()
				? String.format(Locale.US, "No activity above %.1f dB SNR", MIN_SNR_DB)
				: observations.size() + " active signal candidate" + (observations.size() == 1 ? "" : "s");
		return new Result(centerFrequencyHz, sampleRateHz, visibleStart, visibleEnd, noiseDb,
				observations, state);
	}

	static final class ChannelizedWmbus{
		final float[] ctIq,sIq;final double ctRateHz,sRateHz;
		final List<IQDynamicChannelizer.Capture> dynamic;
		final boolean exhaustive;
		ChannelizedWmbus(float[] ctIq,double ctRateHz,float[] sIq,double sRateHz){
			this(ctIq,ctRateHz,sIq,sRateHz,Collections.<IQDynamicChannelizer.Capture>emptyList());
		}
		ChannelizedWmbus(float[] ctIq,double ctRateHz,float[] sIq,double sRateHz,
				List<IQDynamicChannelizer.Capture> dynamic){
			this(ctIq,ctRateHz,sIq,sRateHz,dynamic,false);
		}
		ChannelizedWmbus(float[] ctIq,double ctRateHz,float[] sIq,double sRateHz,
				List<IQDynamicChannelizer.Capture> dynamic,boolean exhaustive){
			this.ctIq=ctIq==null?new float[0]:ctIq;this.ctRateHz=ctRateHz;
			this.sIq=sIq==null?new float[0]:sIq;this.sRateHz=sRateHz;
			this.dynamic=dynamic==null?Collections.<IQDynamicChannelizer.Capture>emptyList():dynamic;
			this.exhaustive=exhaustive;
		}
	}

	private void mergeDynamicChannel(List<Observation> observations,
			Ism868ProtocolDispatcher.Dispatch dispatch,long captureMillis){
		IQDynamicChannelizer.Capture capture=dispatch.capture;
		if(dispatch.decoderEligible){
			boolean confirmed=false,routedLoRa="LoRa".equals(dispatch.decoder);
			double captureSeconds=capture.iq.length/2d/capture.sampleRateHz;
			/* Tapo's short 200 kHz GFSK bursts can look CSS-like or unknown while
			 * they are still growing.  Its 32-bit sync, declared length and X.25
			 * CRC make a completed decode safe without trusting that profile. */
			if(capture.complete&&captureSeconds<=.45d&&nearTapoChannel(capture.frequencyHz)){
				for(TapoSubGDecoder.Decoded decoded:tapoDecoder.decodeAll(capture.iq,capture.sampleRateHz)){
					confirmTapo(observations,capture,decoded,captureMillis);confirmed=true;}
				if(confirmed)return;
			}
			/* This fallback is intentionally narrow and one-shot.  It exists for the
			 * LSE waveform whose clock is about 6.75 kppm high and whose PFB-edge
			 * image profiles as half-rate CSS.  Running it on growing or ordinary
			 * FSK captures starves the normal C/T/S receiver. */
			if("W-MBus S clock-drift".equals(dispatch.decoder)&&capture.complete
					&&captureSeconds>=.03d&&captureSeconds<=.45d){
				float[] centered=recenter(capture.iq,capture.sampleRateHz,
						capture.frequencyHz-capture.iqCenterFrequencyHz);
				for(WmbusPhyDecoder.Decoded decoded:wmbusDecoder.decodeSBasebandAllClockTolerant(centered,
						centered.length/2,capture.sampleRateHz)){
					confirmWmbus(observations,decoded,capture.frequencyHz,captureMillis);confirmed=true;}
				if(confirmed)return;
			}
			boolean suspiciousWideFsk="FSK".equals(dispatch.profile.modulation)&&captureSeconds>=.25d
					&&capture.snrDb>=10d&&dispatch.profile.occupiedBandwidthHz>capture.bandwidthHz*1.05d;
			if(routedLoRa||suspiciousWideFsk){long lockKey=Math.round(capture.iqCenterFrequencyHz/25_000d);
				int captureSamples=capture.iq.length/2;LoRaLockState state=loraPreambleLocks.get(Long.valueOf(lockKey));
				/* A growing PFB capture keeps the same start.  If its length stops
				 * growing, a queue reset or a new burst has started and the old lock's
				 * sample index is no longer valid. */
				if(state!=null&&captureSamples<=state.lastCaptureSamples+
						Math.round(capture.sampleRateHz*.05d)){loraPreambleLocks.remove(Long.valueOf(lockKey));state=null;}
				LoRaPhyDecoder.Lock lock=state==null?null:state.lock;
				if(lock==null){List<LoRaPhyDecoder.Lock> found=loraDecoder.findLocks(capture.iq,capture.sampleRateHz);
					if(!found.isEmpty()){lock=found.get(0);state=new LoRaLockState(lock,captureSamples);
						loraPreambleLocks.put(Long.valueOf(lockKey),state);}}
				else state.lastCaptureSamples=captureSamples;
				if(lock!=null&&!capture.complete){
					if(lock.quality>=20d)confirmLoRaPreamble(observations,capture,lock,captureMillis);
					return;}
				List<LoRaPhyDecoder.Lock> locks=lock==null?Collections.<LoRaPhyDecoder.Lock>emptyList():
						Collections.singletonList(lock);
				for(LoRaPhyDecoder.Decoded decoded:loraDecoder.decodeAll(capture.iq,capture.sampleRateHz,locks))
					if(!decoded.hasCrc||decoded.crcValid){confirmLoRa(observations,capture,decoded,
							capture.frequencyHz,captureMillis);confirmed=true;}
				if(capture.complete)loraPreambleLocks.remove(Long.valueOf(lockKey));
				if(confirmed)return;
				if(lock!=null){if(lock.quality>=20d)confirmLoRaPreamble(observations,capture,lock,captureMillis);return;}
			}
			if(confirmed)return;
			/* Expensive proprietary FSK decoders only get a completed, plausibly
			 * short burst.  Running them on every growing 250 ms snapshot caused
			 * wideband analysis to fall several replay loops behind real time. */
			if(capture.complete&&"FSK".equals(dispatch.profile.modulation)&&captureSeconds<=.45d){
				List<FineOffsetWh65bDecoder.Decoded> weather=fineOffsetDecoder.decodeAll(
						capture.iq,capture.sampleRateHz);
				for(FineOffsetWh65bDecoder.Decoded decoded:weather){
					confirmFineOffset(observations,capture,decoded,captureMillis);confirmed=true;}
				for(RenaultTpmsDecoder.Decoded decoded:renaultDecoder.decodeAll(capture.iq,capture.sampleRateHz)){
					confirmRenault(observations,capture,decoded,captureMillis);confirmed=true;}
				if(!confirmed&&captureSeconds>=.12d)for(AutomotiveKeyfobClassifier.Decoded decoded:
						keyfobClassifier.decodeAll(capture.iq,capture.sampleRateHz)){
					confirmAutomotiveKeyfob(observations,capture,decoded,captureMillis);confirmed=true;}
			}
			if(confirmed)return;
		}
		String details=dispatch.details+" | "+String.format(Locale.US,"%.0f kS/s channel",
				capture.sampleRateHz/1000d)+(dispatch.decoderEligible?"":" | decoder deferred by CPU budget");
		Observation streamed=new Observation(capture.frequencyHz,dispatch.profile.occupiedBandwidthHz,
				capture.levelDbfs,capture.snrDb,dispatch.priority,dispatch.protocol,
				dispatch.profile.displayModulation,details,"",false,captureMillis,"","",
				dispatch.profile.symbolRate,dispatch.profile.burstMillis,
				dispatch.profile.toneSeparationHz,dispatch.profile.confidence);
		int best=-1;long distance=Long.MAX_VALUE;
		for(int n=0;n<observations.size();n++){Observation old=observations.get(n);if(isIdentified(old))continue;
			long next=Math.abs(old.frequencyHz-capture.frequencyHz);
			long tolerance=Math.max(25_000L,Math.max(old.bandwidthHz,capture.bandwidthHz)/2L);
			if(next<=tolerance&&next<distance){best=n;distance=next;}}
		if(best<0)observations.add(streamed);else observations.set(best,streamed);
	}

	private static float[] recenter(float[] iq,double sampleRateHz,double offsetHz){
		if(iq==null||iq.length<2||sampleRateHz<=0d||Math.abs(offsetHz)<1d)return iq;
		float[] shifted=new float[iq.length];double angle=-2d*Math.PI*offsetHz/sampleRateHz;
		double stepCos=Math.cos(angle),stepSin=Math.sin(angle),oscillatorCos=1d,oscillatorSin=0d;
		for(int sample=0;sample<iq.length/2;sample++){
			double sourceI=iq[sample*2],sourceQ=iq[sample*2+1];
			shifted[sample*2]=(float)(sourceI*oscillatorCos-sourceQ*oscillatorSin);
			shifted[sample*2+1]=(float)(sourceI*oscillatorSin+sourceQ*oscillatorCos);
			double nextCos=oscillatorCos*stepCos-oscillatorSin*stepSin;
			oscillatorSin=oscillatorSin*stepCos+oscillatorCos*stepSin;oscillatorCos=nextCos;
			if((sample&0x3fff)==0){double magnitude=Math.sqrt(oscillatorCos*oscillatorCos
					+oscillatorSin*oscillatorSin);if(magnitude>0d){oscillatorCos/=magnitude;
					oscillatorSin/=magnitude;}}
		}
		return shifted;
	}

	private static boolean hasLoraCandidate(List<Observation> observations){
		for(Observation observation:observations)if(observation.protocol.startsWith("LoRa"))return true;
		return false;
	}
	private static boolean hasProtocol(List<Observation> observations,String protocol){for(Observation value:observations)
		if(protocol.equals(value.protocol))return true;return false;}
	private static boolean hasCandidateNear(List<Observation> observations,long frequency,long tolerance){
		for(Observation value:observations)if(Math.abs(value.frequencyHz-frequency)<=tolerance)return true;return false;}
	private static boolean visible(long center,int rate,long frequency){return Math.abs(frequency-center)<rate/2d;}
	private static long visibleTapoChannel(long center,int rate){long[] channels={863_350_000L,864_350_000L,868_350_000L};
		long best=-1L,distance=Long.MAX_VALUE;for(long channel:channels){long next=Math.abs(channel-center);
			if(next<=220_000L&&next<rate/2d&&next<distance){best=channel;distance=next;}}return best;}

	private static void confirmLoRa(List<Observation> observations,IQDynamicChannelizer.Capture capture,
			LoRaPhyDecoder.Decoded decoded,long fallbackFrequency,long captureMillis){
		long frequency=capture==null?fallbackFrequency:capture.frequencyHz;
		double level=Double.NaN,snr=Double.NaN;
		if(capture!=null){level=capture.levelDbfs;snr=capture.snrDb;}
		else{long distance=Long.MAX_VALUE;for(Observation candidate:observations){long next=Math.abs(
				candidate.frequencyHz-frequency);if(next<distance){distance=next;level=candidate.levelDbfs;snr=candidate.snrDb;}}}
		String details=String.format(Locale.US,"LoRa BW %d kHz, SF%d, CR 4/%d, sync %02X/%02X | explicit header OK%s",
				decoded.bandwidthHz/1000,decoded.spreadingFactor,decoded.codingRate+4,decoded.netId1,decoded.netId2,
				decoded.hasCrc?", payload CRC OK":"");
		String id=decoded.deviceId(),manufacturer=decoded.isLoRaWan()?"LoRaWAN":"LoRa";
		WmbusApplicationDecoder.Result values=new WmbusApplicationDecoder.Result("lora_phy",
				decoded.isLoRaWan()?"LoRaWAN end device":"LoRa device","","","","","","","",decoded.summary());
		Observation confirmed=new Observation(frequency,decoded.bandwidthHz,level,snr,100d,
				decoded.protocol(),"CSS",details,decoded.payloadHex(),true,captureMillis,manufacturer,id,
				decoded.isLoRaWan()?"end device":"LoRa device",-1,-1,-1,-1,"explicit PHY header",
				decoded.security(),values);
		mergeConfirmed(observations,confirmed,Math.max(90_000L,decoded.bandwidthHz));
	}

	private static void confirmLoRaPreamble(List<Observation> observations,
			IQDynamicChannelizer.Capture capture,LoRaPhyDecoder.Lock lock,long captureMillis){
		String details=String.format(Locale.US,
				"LoRa preamble lock: BW %d kHz, SF%d, peak ratio %.1f | collecting complete PHY frame",
				lock.bandwidthHz/1000,lock.spreadingFactor,lock.quality);
		WmbusApplicationDecoder.Result values=WmbusApplicationDecoder.Result.identified(
				"lora_preamble","LoRa signal");
		Observation candidate=new Observation(capture.frequencyHz,lock.bandwidthHz,capture.levelDbfs,
				capture.snrDb,98d,"LoRa candidate","CSS",details,"",false,captureMillis,"LoRa","",
				"LoRa signal",-1,-1,-1,-1,"preamble lock","unknown",values);
		mergeConfirmed(observations,candidate,Math.max(90_000L,lock.bandwidthHz));
	}

	private static void confirmFineOffset(List<Observation> observations,
			IQDynamicChannelizer.Capture capture,FineOffsetWh65bDecoder.Decoded decoded,
			long captureMillis){
		long frequency=capture.iqCenterFrequencyHz==capture.frequencyHz
				?Math.round(capture.frequencyHz+decoded.carrierOffsetHz):capture.frequencyHz;
		int best=-1;long distance=Long.MAX_VALUE;
		for(int n=0;n<observations.size();n++){
			Observation candidate=observations.get(n);if(candidate.checksumValid)continue;
			long next=Math.abs(candidate.frequencyHz-frequency);
			if(next<distance&&next<180_000L){best=n;distance=next;}
		}
		String details=String.format(Locale.US,
				"Fine Offset FSK PCM, %.2f us/bit, deviation %.1f kHz | CRC-8 and checksum OK",
				decoded.bitMicros,decoded.deviationHz/1000d);
		Observation confirmed=new Observation(frequency,Math.max(capture.bandwidthHz,
				(int)Math.round(decoded.deviationHz+35_000d)),capture.levelDbfs,capture.snrDb,100d,
				"Fineoffset-WH65B","FSK PCM, 58 us/bit",details,decoded.payloadHex,true,
				captureMillis,"Fine Offset",Integer.toString(decoded.id),"weather station",-1,-1,-1,-1,
				"FSK PCM","clear",WmbusApplicationDecoder.Result.identified(
						"fineoffset_wh65b","weather station"),decoded,null);
		if(best<0)observations.add(confirmed);else observations.set(best,confirmed);
	}

	private static void confirmRenault(List<Observation> observations,IQDynamicChannelizer.Capture capture,
			RenaultTpmsDecoder.Decoded decoded,long captureMillis){
		long frequency=decodedFrequency(capture,decoded.carrierOffsetHz);
		String details=String.format(Locale.US,"Renault TPMS FSK Manchester, %.2f us/bit | CRC-8 OK",
				decoded.bitMicros);
		Observation confirmed=new Observation(frequency,Math.max(capture.bandwidthHz,
				(int)Math.round(decoded.deviationHz+30_000d)),capture.levelDbfs,capture.snrDb,100d,
				"Renault TPMS","FSK Manchester, 52 us/bit",details,decoded.payloadHex,true,captureMillis,
				"Renault",decoded.id,"tyre pressure sensor",-1,-1,-1,-1,"FSK Manchester","clear",
				WmbusApplicationDecoder.Result.identified("renault_tpms","tyre pressure sensor"),null,decoded);
		mergeConfirmed(observations,confirmed,180_000L);
	}

	private static void confirmTapo(List<Observation> observations,IQDynamicChannelizer.Capture capture,
			TapoSubGDecoder.Decoded decoded,long captureMillis){
		long measured=decodedFrequency(capture,decoded.carrierOffsetHz);
		long frequency=nearestTapoChannel(measured);
		String encoding=decoded.manchester?"Manchester payload":"NRZ payload";
		String details=String.format(Locale.US,
				"TP-Link/Tapo Sub-GHz GFSK, %.2f us/bit, deviation %.1f kHz, offset %+.1f kHz, %s, type 0x%02X, sync errors %d | CRC-16/X-25 OK%s",
				decoded.bitMicros,decoded.deviationHz/1000d,decoded.carrierOffsetHz/1000d,
				encoding,decoded.type,decoded.syncErrors,
				decoded.secondAddress.length()==0?"":" | link peer "+decoded.firstAddress);
		String id=decoded.secondAddress.length()>0?decoded.secondAddress:decoded.firstAddress;
		double level=Double.isNaN(decoded.levelDbfs)?capture.levelDbfs:decoded.levelDbfs;
		double snr=Double.isNaN(decoded.snrDb)?capture.snrDb:decoded.snrDb;
		Observation confirmed=new Observation(frequency,Math.max(capture.bandwidthHz,
				(int)Math.round(decoded.deviationHz+50_000d)),level,snr,100d,
				"Tapo Sub-GHz","GFSK, 50 kbit/s",details,decoded.payloadHex,true,captureMillis,
				"TP-Link/Tapo",id,"Sub-GHz hub/device",-1,decoded.type,-1,-1,
				encoding,"proprietary; application payload not interpreted",
				WmbusApplicationDecoder.Result.identified("tapo_subg","TP-Link/Tapo Sub-GHz device"));
		mergeConfirmed(observations,confirmed,180_000L);
	}

	private static void confirmAutomotiveKeyfob(List<Observation> observations,IQDynamicChannelizer.Capture capture,
			AutomotiveKeyfobClassifier.Decoded decoded,long captureMillis){
		long frequency=decodedFrequency(capture,decoded.carrierOffsetHz);
		String details=String.format(Locale.US,
				"Automotive keyfob-like 2-FSK/biphase signature, %.1f ms burst, %.0f us sync symbols, %.1f kHz tone spacing | candidate classification only",
				decoded.durationMs,decoded.symbolMicros,decoded.toneSeparationHz/1000d);
		WmbusApplicationDecoder.Result values=new WmbusApplicationDecoder.Result("automotive_keyfob_probe",
				"vehicle keyfob","","","","","","","",String.format(Locale.US,
				"FSK/biphase sync signature (%d transitions); payload not interpreted",decoded.preambleTransitions));
		Observation identified=new Observation(frequency,Math.max(capture.bandwidthHz,
				(int)Math.round(decoded.toneSeparationHz+30_000d)),capture.levelDbfs,capture.snrDb,96d,
				"Automotive keyfob candidate","2-FSK/biphase",details,"",false,captureMillis,
				"","","vehicle keyfob candidate",-1,-1,-1,-1,"FSK/biphase",
				"unknown; often rolling code",values);
		mergeConfirmed(observations,identified,180_000L);
	}

	private static long decodedFrequency(IQDynamicChannelizer.Capture capture,double offsetHz){return
			capture.iqCenterFrequencyHz==capture.frequencyHz?Math.round(capture.frequencyHz+offsetHz):capture.frequencyHz;}
	private static boolean nearTapoChannel(long frequencyHz){return Math.abs(frequencyHz-863_350_000L)<=180_000L
			||Math.abs(frequencyHz-864_350_000L)<=180_000L||Math.abs(frequencyHz-868_350_000L)<=180_000L;}
	private static long nearestTapoChannel(long frequencyHz){long[] channels={863_350_000L,864_350_000L,868_350_000L};
		long best=channels[0],distance=Math.abs(frequencyHz-best);for(int n=1;n<channels.length;n++){
			long next=Math.abs(frequencyHz-channels[n]);if(next<distance){best=channels[n];distance=next;}}
		return distance<=220_000L?best:frequencyHz;}
	private static void mergeJablotronCandidate(List<Observation> observations,
			JablotronJa100Classifier.Candidate candidate,long centerFrequencyHz,long captureMillis){
		long frequency=Math.round(centerFrequencyHz+candidate.carrierOffsetHz);
		int bandwidth=(int)Math.round(Math.min(80_000d,
				Math.max(35_000d,candidate.toneSeparationHz+8_000d)));
		String details=String.format(Locale.US,
				"868.1 MHz GFSK fingerprint | %.1f ksym/s, tone spacing %.1f kHz, burst %.2f ms, alternating preamble %d bit, frame ~%d bit%s | candidate only; payload format/CRC not confirmed",
				candidate.symbolRate/1000d,candidate.toneSeparationHz/1000d,candidate.burstMillis,
				candidate.preambleBits,candidate.frameBits,candidate.fingerprint.length()==0?"":
				", post-preamble "+candidate.fingerprint);
		Observation value=new Observation(frequency,bandwidth,candidate.levelDbfs,candidate.snrDb,
				candidate.confidence,"Jablotron JA-100 candidate","GFSK candidate, 40 kbit/s",
				details,"",false,captureMillis,"Jablotron?","",candidate.symbolRate,
				candidate.burstMillis,candidate.toneSeparationHz,candidate.confidence);
		int best=-1;long distance=Long.MAX_VALUE;for(int n=0;n<observations.size();n++){
			Observation old=observations.get(n);if(isIdentified(old)||old.protocol.startsWith("Jablotron"))continue;
			long next=Math.abs(old.frequencyHz-frequency);
			if(next<=18_000L&&next<distance){best=n;distance=next;}}
		if(best<0)observations.add(value);else observations.set(best,value);
	}
	private static void merge8685Candidate(List<Observation> observations,
			Ism8685Classifier.Candidate candidate,long centerFrequencyHz,long captureMillis){
		long frequency=Math.round(centerFrequencyHz+candidate.carrierOffsetHz);
		int bandwidth=(int)Math.round(candidate.toneSeparationHz+10_000d);
		boolean wavenis=candidate.family==Ism8685Classifier.Family.WAVENIS;
		String protocol=wavenis?"Wavenis / Coronis candidate":"868.5 proprietary GFSK";
		String modulation=String.format(Locale.US,"GFSK candidate, %.1f kbit/s",candidate.symbolRate/1000d);
		String details=wavenis?String.format(Locale.US,
				"868.5 MHz Wavenis PHY fingerprint | %.1f ksym/s, tone spacing %.1f kHz, burst %.2f ms, frame ~%d symbols | published Wavenis PHY uses GFSK 9.6 kb/s with interleaving and BCH(31,21); frame CRC/BCH not yet validated",
				candidate.symbolRate/1000d,candidate.toneSeparationHz/1000d,candidate.burstMillis,candidate.frameBits):
			String.format(Locale.US,
				"868.5 MHz proprietary GFSK fingerprint | %.1f ksym/s, tone spacing %.1f kHz, burst %.2f ms, frame ~%d symbols | repeating AA/55 preamble; protocol and checksum unknown",
				candidate.symbolRate/1000d,candidate.toneSeparationHz/1000d,candidate.burstMillis,candidate.frameBits);
		Observation value=new Observation(frequency,bandwidth,candidate.levelDbfs,candidate.snrDb,
				candidate.confidence,protocol,modulation,details,"",false,captureMillis,
				wavenis?"Elster/Coronis?":"","",candidate.symbolRate,candidate.burstMillis,
				candidate.toneSeparationHz,candidate.confidence);
		int best=-1;long distance=Long.MAX_VALUE;for(int n=0;n<observations.size();n++){
			Observation old=observations.get(n);if(isIdentified(old)||old.protocol.startsWith("Wavenis")
					||old.protocol.startsWith("868.5 proprietary"))continue;
			long next=Math.abs(old.frequencyHz-frequency);if(next<=25_000L&&next<distance){best=n;distance=next;}}
		if(best<0)observations.add(value);else observations.set(best,value);
	}
	private static boolean isIdentified(Observation observation){return observation.checksumValid
			||(observation.application!=null&&observation.application.driver.length()>0);}
	private static void mergeConfirmed(List<Observation> observations,Observation confirmed,long tolerance){
		int best=-1;long distance=Long.MAX_VALUE;for(int n=0;n<observations.size();n++){
			Observation candidate=observations.get(n);if(candidate.checksumValid)continue;
			long next=Math.abs(candidate.frequencyHz-confirmed.frequencyHz);if(next<tolerance&&next<distance){best=n;distance=next;}}
		if(best<0)observations.add(confirmed);else observations.set(best,confirmed.withPhyFallback(observations.get(best)));
	}

	private static void confirmWmbus(List<Observation> observations, WmbusPhyDecoder.Decoded telegram,
			long channelFrequencyHz, long captureMillis) {
		int best=-1;long distance=Long.MAX_VALUE;
		for(int n=0;n<observations.size();n++){
			Observation candidate=observations.get(n);
			if(candidate.checksumValid)continue;
			long next=Math.abs(candidate.frequencyHz-channelFrequencyHz);
			if(next<distance&&next<250_000L){best=n;distance=next;}
		}
		String modulation="S".equals(telegram.mode)?"Manchester FSK, 32.768 kchip/s"
				:"2-FSK, 100 kbit/s";
		double frameLevel=telegram.levelDbfs;
		if(best<0){
			observations.add(new Observation(channelFrequencyHz,0,frameLevel,Double.NaN,100d,
					"W-MBus "+telegram.mode,modulation,telegram.details+" | EN 13757 CRC OK",
					telegram.payloadHex,true,captureMillis,telegram.manufacturer,telegram.id,
					telegram.deviceTypeName,telegram.version,telegram.control,telegram.ci,
					telegram.accessNumber,telegram.format,telegram.security,telegram.application));
			return;
		}
		Observation candidate=observations.get(best);
		observations.set(best,new Observation(candidate.frequencyHz,candidate.bandwidthHz,
				Double.isNaN(frameLevel)?candidate.levelDbfs:frameLevel,candidate.snrDb,100d,"W-MBus "+telegram.mode,
				modulation,telegram.details+" | EN 13757 CRC OK",
				telegram.payloadHex,true,captureMillis,telegram.manufacturer,telegram.id,
				telegram.deviceTypeName,telegram.version,telegram.control,telegram.ci,
				telegram.accessNumber,telegram.format,telegram.security,telegram.application));
	}

	private static List<Cluster> clusters(double[] power, double threshold, long centerHz, int rate) {
		List<Cluster> out = new ArrayList<Cluster>();
		int start = -1;
		for (int shifted = 0; shifted <= FFT_SIZE; shifted++) {
			boolean active = false;
			if (shifted < FFT_SIZE) {
				int signedBin = shifted - FFT_SIZE / 2;
				double frequency = centerHz + signedBin * rate / (double) FFT_SIZE;
				int rawBin = signedBin < 0 ? signedBin + FFT_SIZE : signedBin;
				active = frequency >= BAND_START_HZ && frequency <= BAND_END_HZ
						&& outsideDcGuard(signedBin, rate, FFT_SIZE) && power[rawBin] >= threshold;
			}
			if (active && start < 0) start = shifted;
			if (!active && start >= 0) {
				if (shifted - start >= 1) out.add(new Cluster(start, shifted - 1));
				start = -1;
			}
		}
		return out;
	}

	private static List<Cluster> mergeNearby(List<Cluster> input, int sampleRateHz) {
		if (input.isEmpty()) return input;
		int joinBins = Math.max(2, (int) Math.ceil(65_000d * FFT_SIZE / sampleRateHz));
		List<Cluster> out = new ArrayList<Cluster>();
		Cluster current = input.get(0);
		for (int n = 1; n < input.size(); n++) {
			Cluster next = input.get(n);
			if (next.start - current.end - 1 <= joinBins) current = new Cluster(current.start, next.end);
			else { out.add(current); current = next; }
		}
		out.add(current);
		return out;
	}

	private static Observation describe(Cluster cluster, double[] power, double noiseDb,
			long centerHz, int rate, long captureMillis) {
		double weighted = 0d, total = 0d;
		int strongest = cluster.start;
		double strongestPower = 0d;
		for (int shifted = cluster.start; shifted <= cluster.end; shifted++) {
			int signedBin = shifted - FFT_SIZE / 2;
			int rawBin = signedBin < 0 ? signedBin + FFT_SIZE : signedBin;
			double value = power[rawBin];
			weighted += signedBin * value; total += value;
			if (value > strongestPower) { strongestPower = value; strongest = shifted; }
		}
		if (total <= 0d) return null;
		double signedCenterBin = weighted / total;
		long frequencyHz = Math.round(centerHz + signedCenterBin * rate / FFT_SIZE);
		int bins = Math.max(1, cluster.end - cluster.start + 1);
		int bandwidthHz = Math.max(rate / FFT_SIZE, (int) Math.round(bins * rate / (double) FFT_SIZE));
		double levelDbfs = 10d * Math.log10(total);
		double peakDbfs = 10d * Math.log10(Math.max(1e-15, strongestPower));
		double snrDb = peakDbfs - noiseDb;
		if (snrDb < MIN_SNR_DB || levelDbfs < MIN_LEVEL_DBFS) return null;
		Description description = classify(frequencyHz, bandwidthHz);
		double confidence = Math.max(5d, Math.min(99d, 35d + (snrDb - MIN_SNR_DB) * 3d
				+ description.planConfidence * 25d));
		return new Observation(frequencyHz, bandwidthHz, levelDbfs, snrDb, confidence,
				description.protocol, description.modulation, description.details, "", false,
				captureMillis <= 0L ? System.currentTimeMillis() : captureMillis,"","");
	}

	private static Description classify(long frequencyHz, int bandwidthHz) {
		long fromCt = Math.abs(frequencyHz - 868_950_000L);
		long fromS = Math.abs(frequencyHz - 868_300_000L);
		long loraChannel = nearest(frequencyHz, LORA_EU868_CHANNELS);
		long fromLora = Math.abs(frequencyHz - loraChannel);
		boolean loraWidth = bandwidthHz >= 80_000 && bandwidthHz <= 600_000;
		if (fromCt <= 135_000L && bandwidthHz <= 500_000)
			return new Description("W-MBus C/T candidate", "2-FSK, 100 kbit/s",
					"868.95 MHz uplink; awaiting sync word and CRC", .95d);
		if (fromS <= 85_000L && bandwidthHz <= 125_000)
			return new Description("W-MBus S candidate", "Manchester FSK, 32.768 kchip/s",
					"868.3 MHz; awaiting Manchester sync and CRC", .9d);
		if (fromLora <= Math.max(75_000L, bandwidthHz / 2L) && loraWidth)
			return new Description("LoRa candidate", "CSS",
					String.format(Locale.US, "EU868 channel %.3f MHz; awaiting chirp/header lock",
						loraChannel / 1e6), .75d);
		if (frequencyHz >= 869_400_000L && frequencyHz <= 869_650_000L)
			return new Description("SRD alarm / W-MBus downlink candidate", "FSK/OOK",
					"869.4-869.65 MHz high-duty-cycle sub-band", .55d);
		return new Description("SRD / rtl_433 candidate",
				bandwidthHz < 45_000 ? "narrow FSK/OOK" : "unknown digital",
				"No CRC-valid protocol frame yet", .25d);
	}

	private static long nearest(long value, long[] candidates) {
		long best = candidates[0], distance = Math.abs(value - best);
		for (int n = 1; n < candidates.length; n++) {
			long next = Math.abs(value - candidates[n]);
			if (next < distance) { best = candidates[n]; distance = next; }
		}
		return best;
	}

	private static double estimateNoiseFloor(double[] power, long centerHz, int rate) {
		double[] values = new double[FFT_SIZE]; int count = 0;
		for (int shifted = 0; shifted < FFT_SIZE; shifted++) {
			int signed = shifted - FFT_SIZE / 2;
			double frequency = centerHz + signed * rate / (double) FFT_SIZE;
			if (frequency < BAND_START_HZ || frequency > BAND_END_HZ
					|| !outsideDcGuard(signed, rate, FFT_SIZE)) continue;
			int raw = signed < 0 ? signed + FFT_SIZE : signed;
			values[count++] = 10d * Math.log10(Math.max(1e-15, power[raw]));
		}
		if (count == 0) return -120d;
		java.util.Arrays.sort(values, 0, count);
		return values[count / 2];
	}

	static boolean outsideDcGuard(int signedBin, int sampleRateHz, int fftSize) {
		/* Keep the historical three-bin exclusion at coarse FFT resolutions and
		 * use a small frequency-based guard at finer resolutions.  This removes
		 * the HackRF zero-IF spur without blanking an entire RF channel. */
		return Math.abs(signedBin) > 2 && Math.abs((long) signedBin * sampleRateHz)
				> DC_GUARD_HZ * fftSize;
	}

	private static void fft(double[] real, double[] imag) {
		int size = real.length;
		for (int i = 1, j = 0; i < size; i++) {
			int bit = size >> 1;
			for (; (j & bit) != 0; bit >>= 1) j ^= bit;
			j ^= bit;
			if (i < j) {
				double swap = real[i]; real[i] = real[j]; real[j] = swap;
				swap = imag[i]; imag[i] = imag[j]; imag[j] = swap;
			}
		}
		for (int length = 2; length <= size; length <<= 1) {
			double angle = -2d * Math.PI / length;
			double stepReal = Math.cos(angle), stepImag = Math.sin(angle);
			for (int start = 0; start < size; start += length) {
				double wReal = 1d, wImag = 0d;
				for (int n = 0; n < length / 2; n++) {
					int even = start + n, odd = even + length / 2;
					double oddReal = real[odd] * wReal - imag[odd] * wImag;
					double oddImag = real[odd] * wImag + imag[odd] * wReal;
					real[odd] = real[even] - oddReal; imag[odd] = imag[even] - oddImag;
					real[even] += oddReal; imag[even] += oddImag;
					double next = wReal * stepReal - wImag * stepImag;
					wImag = wReal * stepImag + wImag * stepReal; wReal = next;
				}
			}
		}
	}

	private static final class Cluster {
		final int start, end;
		Cluster(int start, int end) { this.start = start; this.end = end; }
	}
	private static final class Description {
		final String protocol, modulation, details; final double planConfidence;
		Description(String protocol, String modulation, String details, double planConfidence) {
			this.protocol=protocol; this.modulation=modulation; this.details=details;
			this.planConfidence=planConfidence;
		}
	}
	private static final class LoRaLockState{
		final LoRaPhyDecoder.Lock lock;int lastCaptureSamples;
		LoRaLockState(LoRaPhyDecoder.Lock lock,int lastCaptureSamples){this.lock=lock;
			this.lastCaptureSamples=lastCaptureSamples;}
	}

	static final class Observation {
		final long frequencyHz;
		final int bandwidthHz;
		final double levelDbfs, snrDb, confidence;
		final double symbolRate,burstMillis,toneSeparationHz,phyConfidence;
		final String protocol, modulation, details, payloadHex, manufacturer, meterId;
		final String deviceTypeName,frameFormat,security;
		final WmbusApplicationDecoder.Result application;
		final FineOffsetWh65bDecoder.Decoded weather;
		final RenaultTpmsDecoder.Decoded tpms;
		final int meterVersion,controlField,ciField,accessNumber;
		final boolean checksumValid;
		final long captureMillis;
		Observation(long frequencyHz, int bandwidthHz, double levelDbfs, double snrDb,
				double confidence, String protocol, String modulation, String details,
				String payloadHex, boolean checksumValid, long captureMillis,
				String manufacturer, String meterId) {
			this(frequencyHz,bandwidthHz,levelDbfs,snrDb,confidence,protocol,modulation,details,
					payloadHex,checksumValid,captureMillis,manufacturer,meterId,"",-1,-1,-1,-1,"","",
					WmbusApplicationDecoder.Result.NONE,null,null,defaultSymbolRate(modulation),Double.NaN,
					Double.NaN,checksumValid?100d:Double.NaN);
		}
		Observation(long frequencyHz, int bandwidthHz, double levelDbfs, double snrDb,
				double confidence, String protocol, String modulation, String details,
				String payloadHex, boolean checksumValid, long captureMillis,
				String manufacturer, String meterId,double symbolRate,double burstMillis,
				double toneSeparationHz,double phyConfidence) {
			this(frequencyHz,bandwidthHz,levelDbfs,snrDb,confidence,protocol,modulation,details,
					payloadHex,checksumValid,captureMillis,manufacturer,meterId,"",-1,-1,-1,-1,"","",
					WmbusApplicationDecoder.Result.NONE,null,null,symbolRate,burstMillis,
					toneSeparationHz,phyConfidence);
		}
		Observation(long frequencyHz, int bandwidthHz, double levelDbfs, double snrDb,
				double confidence, String protocol, String modulation, String details,
				String payloadHex, boolean checksumValid, long captureMillis,
				String manufacturer, String meterId, String deviceTypeName, int meterVersion,
				int controlField, int ciField, int accessNumber, String frameFormat, String security,
				WmbusApplicationDecoder.Result application) {
			this(frequencyHz,bandwidthHz,levelDbfs,snrDb,confidence,protocol,modulation,details,
					payloadHex,checksumValid,captureMillis,manufacturer,meterId,deviceTypeName,meterVersion,
					controlField,ciField,accessNumber,frameFormat,security,application,null,null,
					defaultSymbolRate(modulation),Double.NaN,Double.NaN,checksumValid?100d:Double.NaN);
		}
		Observation(long frequencyHz, int bandwidthHz, double levelDbfs, double snrDb,
				double confidence, String protocol, String modulation, String details,
				String payloadHex, boolean checksumValid, long captureMillis,
				String manufacturer, String meterId, String deviceTypeName, int meterVersion,
				int controlField, int ciField, int accessNumber, String frameFormat, String security,
				WmbusApplicationDecoder.Result application,FineOffsetWh65bDecoder.Decoded weather,
				RenaultTpmsDecoder.Decoded tpms) {
			this(frequencyHz,bandwidthHz,levelDbfs,snrDb,confidence,protocol,modulation,details,
					payloadHex,checksumValid,captureMillis,manufacturer,meterId,deviceTypeName,meterVersion,
					controlField,ciField,accessNumber,frameFormat,security,application,weather,tpms,
					defaultSymbolRate(modulation),Double.NaN,Double.NaN,checksumValid?100d:Double.NaN);
		}
		private Observation(long frequencyHz, int bandwidthHz, double levelDbfs, double snrDb,
				double confidence, String protocol, String modulation, String details,
				String payloadHex, boolean checksumValid, long captureMillis,
				String manufacturer, String meterId, String deviceTypeName, int meterVersion,
				int controlField, int ciField, int accessNumber, String frameFormat, String security,
				WmbusApplicationDecoder.Result application,FineOffsetWh65bDecoder.Decoded weather,
				RenaultTpmsDecoder.Decoded tpms,double symbolRate,double burstMillis,
				double toneSeparationHz,double phyConfidence) {
			this.frequencyHz=frequencyHz; this.bandwidthHz=bandwidthHz; this.levelDbfs=levelDbfs;
			this.snrDb=snrDb; this.confidence=confidence; this.protocol=protocol;
			this.modulation=modulation; this.details=details; this.payloadHex=payloadHex;
			this.checksumValid=checksumValid; this.captureMillis=captureMillis;
			this.manufacturer=manufacturer; this.meterId=meterId;
			this.deviceTypeName=deviceTypeName;this.meterVersion=meterVersion;
			this.controlField=controlField;this.ciField=ciField;this.accessNumber=accessNumber;
			this.frameFormat=frameFormat;
			this.security=security;this.application=application;this.weather=weather;
			this.tpms=tpms;this.symbolRate=symbolRate;this.burstMillis=burstMillis;
			this.toneSeparationHz=toneSeparationHz;this.phyConfidence=phyConfidence;
		}
		private Observation withPhyFallback(Observation fallback){return new Observation(frequencyHz,
				bandwidthHz,levelDbfs,snrDb,confidence,protocol,modulation,details,payloadHex,checksumValid,
				captureMillis,manufacturer,meterId,deviceTypeName,meterVersion,controlField,ciField,
				accessNumber,frameFormat,security,application,weather,tpms,
				finite(symbolRate)?symbolRate:fallback.symbolRate,
				finite(burstMillis)?burstMillis:fallback.burstMillis,
				finite(toneSeparationHz)?toneSeparationHz:fallback.toneSeparationHz,
				finite(phyConfidence)?phyConfidence:fallback.phyConfidence);}
		private static boolean finite(double value){return !Double.isNaN(value)&&!Double.isInfinite(value);}
		private static double defaultSymbolRate(String modulation){
			if(modulation==null)return Double.NaN;
			if(modulation.indexOf("100 kbit/s")>=0)return 100_000d;
			if(modulation.indexOf("50 kbit/s")>=0)return 50_000d;
			if(modulation.indexOf("32.768 kchip/s")>=0)return 32_768d;
			if(modulation.indexOf("58 us/bit")>=0)return 1e6d/58d;
			if(modulation.indexOf("52 us/bit")>=0)return 1e6d/52d;
			return Double.NaN;
		}
	}

	static final class Result {
		final long centerFrequencyHz;
		final int sampleRateHz;
		final double visibleStartHz, visibleEndHz, noiseFloorDbfs;
		final List<Observation> observations;
		final String state;
		Result(long centerFrequencyHz, int sampleRateHz, double visibleStartHz,
				double visibleEndHz, double noiseFloorDbfs, List<Observation> observations, String state) {
			this.centerFrequencyHz=centerFrequencyHz; this.sampleRateHz=sampleRateHz;
			this.visibleStartHz=visibleStartHz; this.visibleEndHz=visibleEndHz;
			this.noiseFloorDbfs=noiseFloorDbfs;
			this.observations=Collections.unmodifiableList(new ArrayList<Observation>(observations));
			this.state=state;
		}
		static Result empty(long center, int rate, String state) {
			return new Result(center, rate, center-rate/2d, center+rate/2d, -120d,
					Collections.<Observation>emptyList(), state);
		}
	}
}
