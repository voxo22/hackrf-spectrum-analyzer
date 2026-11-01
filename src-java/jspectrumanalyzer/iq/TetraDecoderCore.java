package jspectrumanalyzer.iq;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Streaming TETRA downlink decoder core shared by the live analyzer and the
 * offline probe.
 *
 * <p>The first stage deliberately stops at type-5 burst bits. It performs the
 * part which must be proven against a real recording before lower-MAC/FEC can
 * be trusted: channel filtering, differential pi/4-DQPSK symbol recovery,
 * training-sequence acquisition, burst classification and carrier-offset
 * estimation. Later stages consume {@link Burst#bits} without depending on a
 * Swing window or an SDR source.</p>
 */
final class TetraDecoderCore {
	static final double SYMBOL_RATE_HZ = 18_000d;
	static final int BURST_SYMBOLS = 255;
	static final int BURST_BITS = BURST_SYMBOLS * 2;
	private static final int OCCUPIED_BANDWIDTH_HZ = 25_000;
	private static final int CHANNEL_RATE_HZ = 96_000;
	private static final int MAX_EVENTS = 512;

	private final TetraBurstDetector detector = new TetraBurstDetector();
	private final TetraMacDecoder macDecoder = new TetraMacDecoder();
	private final ArrayDeque<Burst> pending = new ArrayDeque<Burst>();
	private IQSubchannelExtractor.Stream channelizer;
	private long configuredInputCenterHz = Long.MIN_VALUE;
	private long configuredChannelCenterHz = Long.MIN_VALUE;
	private int configuredInputRateHz;
	private double configuredDetectorRateHz;
	private long ndb1Count, ndb2Count, syncCount;
	private long decodedBschCount;
	private long decodedSchfCount;
	private TetraBschDecoder.SystemInfo systemInfo;
	private TetraSchfDecoder.DecodeAttempt lastSchf;
	private Result latest = Result.empty("Waiting for TETRA IQ samples");

	synchronized Result accept(long centerHz, int sampleRateHz, byte[] iq, int length,
			long captureMillis) {
		return accept(centerHz, centerHz, sampleRateHz, iq, length, captureMillis);
	}

	/** Decode one selected channel from a possibly wide IQ capture. */
	synchronized Result accept(long inputCenterHz, long channelCenterHz, int sampleRateHz,
			byte[] iq, int length, long captureMillis) {
		if (iq == null || sampleRateHz <= 0 || length < 2)
			return latest;
		int evenLength = Math.min(length, iq.length) & ~1;
		if (evenLength < 2) return latest;
		if (inputCenterHz != configuredInputCenterHz
				|| channelCenterHz != configuredChannelCenterHz
				|| sampleRateHz != configuredInputRateHz)
			configure(inputCenterHz, channelCenterHz, sampleRateHz);

		float[] channelIq;
		int samples;
		double detectorRate;
		if (channelizer != null) {
			IQSubchannelExtractor.Result channel = channelizer.process(iq, evenLength);
			channelIq = channel.iq;
			samples = channel.samples;
			detectorRate = channel.sampleRateHz;
		} else {
			samples = evenLength / 2;
			channelIq = new float[samples * 2];
			for (int n = 0; n < channelIq.length; n++) channelIq[n] = iq[n] / 128f;
			detectorRate = sampleRateHz;
		}
		if (samples <= 0) return latest;
		if (detectorRate != configuredDetectorRateHz) {
			detector.reset(detectorRate);
			configuredDetectorRateHz = detectorRate;
		}
		List<Burst> bursts = detector.accept(channelIq, samples, captureMillis);
		for (Burst burst : bursts) {
			if (burst.type == BurstType.NDB1) ndb1Count++;
			else if (burst.type == BurstType.NDB2) ndb2Count++;
			else if (burst.type == BurstType.SYNC) syncCount++;
			if (burst.type == BurstType.SYNC) {
				TetraBschDecoder.DecodeAttempt attempt = TetraBschDecoder.analyzeBurst(burst);
				TetraBschDecoder.SystemInfo decoded = attempt == null ? null : attempt.systemInfo;
				if (decoded != null) {
					systemInfo = decoded;
					decodedBschCount++;
					macDecoder.synchronize(burst, decoded, detectorRate);
				}
			}
			if (burst.type == BurstType.NDB1 && systemInfo != null) {
				TetraSchfDecoder.DecodeAttempt decoded =
						TetraSchfDecoder.analyzeBurst(burst, systemInfo);
				if (decoded != null && decoded.crcOk) {
					decodedSchfCount++;
					lastSchf = decoded;
					macDecoder.accept(burst, decoded);
				}
			} else if (burst.type == BurstType.NDB2 && systemInfo != null) {
				for (int half = 0; half < 2; half++) {
					TetraSchfDecoder.DecodeAttempt decoded =
							TetraSchfDecoder.analyzeHalfBurst(burst, systemInfo, half);
					if (decoded != null && decoded.crcOk) {
						decodedSchfCount++;
						lastSchf = decoded;
						macDecoder.accept(burst, decoded);
					}
				}
			}
			pending.addLast(burst);
			while (pending.size() > MAX_EVENTS) pending.removeFirst();
		}
		TetraBurstDetector.Snapshot state = detector.snapshot();
		long total = ndb1Count + ndb2Count + syncCount;
		String status;
		if (sampleRateHz < 36_000) status = "TETRA needs at least 36 kS/s complex IQ";
		else if (total == 0) status = "Searching for TETRA training sequences";
		else if (state.lastBurst == null) status = "TETRA burst history available; reacquiring";
		else status = "TETRA " + state.lastBurst.type + " burst lock";
		latest = new Result(status, channelCenterHz, sampleRateHz, detectorRate, total,
				ndb1Count, ndb2Count, syncCount, state.bestCorrelation,
				state.lastBurst == null ? Double.NaN : state.lastBurst.frequencyErrorHz,
				state.lastBurst, detector.bufferedSamples(), detector.scannedSamples(),
				decodedBschCount, systemInfo, decodedSchfCount, lastSchf,
				macDecoder.clearResources(), macDecoder.encryptedResources(),
				macDecoder.fragmentStarts(), macDecoder.fragmentEnds(),
				macDecoder.sdsMessages(), macDecoder.latestSds(),
				macDecoder.cellInfo(channelCenterHz));
		return latest;
	}

	synchronized void reset() {
		channelizer = null;
		configuredInputCenterHz = Long.MIN_VALUE;
		configuredChannelCenterHz = Long.MIN_VALUE;
		configuredInputRateHz = 0;
		configuredDetectorRateHz = 0d;
		detector.reset(0d);
		macDecoder.reset();
		pending.clear();
		ndb1Count = ndb2Count = syncCount = 0L;
		decodedBschCount = 0L;
		decodedSchfCount = 0L;
		systemInfo = null;
		lastSchf = null;
		latest = Result.empty("TETRA stream reset; waiting for IQ samples");
	}

	synchronized Result latest() { return latest; }

	synchronized List<Burst> takeBursts() {
		if (pending.isEmpty()) return Collections.emptyList();
		List<Burst> result = new ArrayList<Burst>(pending);
		pending.clear();
		return result;
	}

	synchronized List<TetraMacDecoder.SdsMessage> takeSdsMessages() {
		return macDecoder.takeMessages();
	}

	synchronized List<TetraMacDecoder.TetraMessage> takeMessages() {
		return macDecoder.takeEvents();
	}

	private void configure(long inputCenterHz, long channelCenterHz, int inputRateHz) {
		configuredInputCenterHz = inputCenterHz;
		configuredChannelCenterHz = channelCenterHz;
		configuredInputRateHz = inputRateHz;
		/* Never carry the previous carrier's cell identity into a new channel. */
		systemInfo = null;
		lastSchf = null;
		macDecoder.resetCellInfo();
		/* The IQ Analyzer normally supplies an already selected 25 kHz channel.
		 * Keep that path untouched; only protect the decoder when it is fed a
		 * wideband capture directly by the offline probe or by a user preset. */
		channelizer = inputRateHz > 192_000
				? new IQSubchannelExtractor.Stream(inputRateHz,
						channelCenterHz - inputCenterHz,
						OCCUPIED_BANDWIDTH_HZ, CHANNEL_RATE_HZ) : null;
		configuredDetectorRateHz = channelizer == null ? inputRateHz : channelizer.sampleRateHz();
		detector.reset(configuredDetectorRateHz);
	}

	enum BurstType { NDB1, NDB2, SYNC }

	static final class Burst {
		final long timestampMillis;
		final long sampleIndex;
		final BurstType type;
		final double correlation;
		final double frequencyErrorHz;
		final byte[] bits;
		final float[] bitReliability;

		Burst(long timestampMillis, long sampleIndex, BurstType type, double correlation,
				double frequencyErrorHz, byte[] bits, float[] bitReliability) {
			this.timestampMillis = timestampMillis;
			this.sampleIndex = sampleIndex;
			this.type = type;
			this.correlation = correlation;
			this.frequencyErrorHz = frequencyErrorHz;
			this.bits = bits;
			this.bitReliability = bitReliability;
		}

		String bitPreview() {
			StringBuilder text = new StringBuilder();
			int count = Math.min(48, bits.length);
			for (int n = 0; n < count; n++) text.append((char)('0' + bits[n]));
			if (bits.length > count) text.append("...");
			return text.toString();
		}
	}

	static final class Result {
		final String state;
		final long centerHz;
		final int inputRateHz;
		final double processingRateHz;
		final long bursts, ndb1Bursts, ndb2Bursts, syncBursts;
		final double bestCorrelation, frequencyErrorHz;
		final Burst lastBurst;
		final int bufferedSamples;
		final long scannedSamples;
		final long decodedBsch;
		final TetraBschDecoder.SystemInfo systemInfo;
		final long decodedSchf;
		final TetraSchfDecoder.DecodeAttempt lastSchf;
		final long clearResources, encryptedResources, fragmentStarts, fragmentEnds;
		final long sdsMessages;
		final TetraMacDecoder.SdsMessage latestSds;
		final TetraCellInfo.Snapshot cellInfo;

		Result(String state, long centerHz, int inputRateHz, double processingRateHz,
				long bursts, long ndb1Bursts, long ndb2Bursts, long syncBursts,
				double bestCorrelation, double frequencyErrorHz, Burst lastBurst,
				int bufferedSamples, long scannedSamples, long decodedBsch,
				TetraBschDecoder.SystemInfo systemInfo, long decodedSchf,
				TetraSchfDecoder.DecodeAttempt lastSchf, long clearResources,
				long encryptedResources, long fragmentStarts, long fragmentEnds,
				long sdsMessages, TetraMacDecoder.SdsMessage latestSds,
				TetraCellInfo.Snapshot cellInfo) {
			this.state = state;
			this.centerHz = centerHz;
			this.inputRateHz = inputRateHz;
			this.processingRateHz = processingRateHz;
			this.bursts = bursts;
			this.ndb1Bursts = ndb1Bursts;
			this.ndb2Bursts = ndb2Bursts;
			this.syncBursts = syncBursts;
			this.bestCorrelation = bestCorrelation;
			this.frequencyErrorHz = frequencyErrorHz;
			this.lastBurst = lastBurst;
			this.bufferedSamples = bufferedSamples;
			this.scannedSamples = scannedSamples;
			this.decodedBsch = decodedBsch;
			this.systemInfo = systemInfo;
			this.decodedSchf = decodedSchf;
			this.lastSchf = lastSchf;
			this.clearResources = clearResources;
			this.encryptedResources = encryptedResources;
			this.fragmentStarts = fragmentStarts;
			this.fragmentEnds = fragmentEnds;
			this.sdsMessages = sdsMessages;
			this.latestSds = latestSds;
			this.cellInfo = cellInfo == null ? TetraCellInfo.Snapshot.EMPTY : cellInfo;
		}

		static Result empty(String state) {
			return new Result(state, 0L, 0, 0d, 0L, 0L, 0L, 0L,
					0d, Double.NaN, null, 0, 0L, 0L, null, 0L, null,
					0L, 0L, 0L, 0L, 0L, null, TetraCellInfo.Snapshot.EMPTY);
		}

		String summary() {
			return String.format(Locale.US,
					"%s   bursts %d (NDB1 %d / NDB2 %d / SYNC %d)   correlation %.1f%%%s",
					state, bursts, ndb1Bursts, ndb2Bursts, syncBursts,
					bestCorrelation * 100d, Double.isNaN(frequencyErrorHz) ? ""
							: String.format(Locale.US, "   CFO %+.0f Hz", frequencyErrorHz));
		}
	}

	/** TMO type-5 burst acquisition on differential phase samples. */
	static final class TetraBurstDetector {
		private static final int[] NTS1 = {
				1,1, 0,1, 0,0, 0,0, 1,1, 1,0, 1,0, 0,1, 1,1, 0,1, 0,0 };
		private static final int[] NTS2 = {
				0,1, 1,1, 1,0, 1,0, 0,1, 0,0, 0,0, 1,1, 0,1, 1,1, 1,0 };
		private static final int[] STS = {
				1,1, 0,0, 0,0, 0,1, 1,0, 0,1, 1,1, 0,0, 1,1, 1,0,
				1,0, 0,1, 1,1, 1,0, 0,0, 0,0, 0,1, 1,0, 0,1 };
		private static final int NTS_OFFSET_SYMBOLS = 122;
		private static final int STS_OFFSET_SYMBOLS = 107;
		private static final double NORMAL_THRESHOLD = .79d;
		private static final double SYNC_THRESHOLD = .64d;
		private static final double CONFIRM_THRESHOLD = .52d;
		private static final double CONFIRM_CFO_TOLERANCE_HZ = 1_500d;
		private static final int CONFIRM_SLOTS = 2;
		private static final int MAX_BUFFERED_BURSTS = 8;

		private float[] history = new float[0];
		private int historySamples;
		private long historyStartSample, streamSamples, scanSample;
		private double sampleRateHz, samplesPerSymbol;
		private long streamEpochMillis;
		private double bestCorrelation;
		private Burst lastBurst;
		private double[] filterTaps = new double[] { 1d };
		private double[] filterI = new double[] { 0d }, filterQ = new double[] { 0d };
		private int filterIndex;

		void reset(double rateHz) {
			historySamples = 0;
			historyStartSample = streamSamples = scanSample = 0L;
			sampleRateHz = rateHz;
			samplesPerSymbol = rateHz <= 0d ? 0d : rateHz / SYMBOL_RATE_HZ;
			designChannelFilter();
			streamEpochMillis = 0L;
			bestCorrelation = 0d;
			lastBurst = null;
		}

		List<Burst> accept(float[] iq, int samples, long captureMillis) {
			if (iq == null || samples <= 0 || sampleRateHz < 36_000d)
				return Collections.emptyList();
			int count = Math.min(samples, iq.length / 2);
			if (count <= 0) return Collections.emptyList();
			if (streamEpochMillis == 0L)
				streamEpochMillis = captureMillis - Math.round(streamSamples * 1000d / sampleRateHz);
			ensureCapacity(historySamples + count);
			appendFiltered(iq, count);
			historySamples += count;
			streamSamples += count;

			List<Burst> found = scan();
			trimHistory();
			return found;
		}

		private void designChannelFilter() {
			filterIndex = 0;
			if (sampleRateHz <= 0d) {
				filterTaps = new double[] { 1d };
				filterI = filterQ = new double[] { 0d };
				return;
			}
			int count = Math.max(17, Math.min(129,
					(int)Math.ceil(10d * sampleRateHz / SYMBOL_RATE_HZ)));
			if ((count & 1) == 0) count++;
			filterTaps = new double[count];
			filterI = new double[count];
			filterQ = new double[count];
			int middle = count / 2;
			double normalizedCutoff = Math.min(.45d, 13_500d / sampleRateHz);
			double sum = 0d;
			for (int tap = 0; tap < count; tap++) {
				int n = tap - middle;
				double sinc = n == 0 ? 2d * normalizedCutoff
						: Math.sin(2d * Math.PI * normalizedCutoff * n) / (Math.PI * n);
				double window = .42d - .5d * Math.cos(2d * Math.PI * tap / (count - 1d))
						+ .08d * Math.cos(4d * Math.PI * tap / (count - 1d));
				filterTaps[tap] = sinc * window;
				sum += filterTaps[tap];
			}
			for (int tap = 0; tap < count; tap++) filterTaps[tap] /= sum;
		}

		private void appendFiltered(float[] iq, int count) {
			for (int sample = 0; sample < count; sample++) {
				filterI[filterIndex] = iq[sample * 2];
				filterQ[filterIndex] = iq[sample * 2 + 1];
				if (++filterIndex == filterTaps.length) filterIndex = 0;
				double outI = 0d, outQ = 0d;
				int source = filterIndex;
				for (int tap = 0; tap < filterTaps.length; tap++) {
					outI += filterI[source] * filterTaps[tap];
					outQ += filterQ[source] * filterTaps[tap];
					if (++source == filterTaps.length) source = 0;
				}
				int destination = (historySamples + sample) * 2;
				history[destination] = (float)outI;
				history[destination + 1] = (float)outQ;
			}
		}

		Snapshot snapshot() { return new Snapshot(bestCorrelation, lastBurst); }
		int bufferedSamples() { return historySamples; }
		long scannedSamples() { return Math.max(0L, scanSample); }

		private List<Burst> scan() {
			int required = (int)Math.ceil((BURST_SYMBOLS + 2d) * samplesPerSymbol) + 3;
			if (historySamples < required) return Collections.emptyList();
			long minimum = historyStartSample + (long)Math.ceil(samplesPerSymbol) + 1L;
			/* A continuous TMO downlink has one 255-symbol burst in every slot.
			 * Keep two following slots available and use them to reject accidental
			 * 11-symbol NTS matches before publishing a burst. */
			long confirmationSpan = (long)Math.ceil(CONFIRM_SLOTS
					* BURST_SYMBOLS * samplesPerSymbol);
			long maximum = historyStartSample + historySamples - required - confirmationSpan;
			long candidate = Math.max(scanSample, minimum);
			List<Burst> result = new ArrayList<Burst>();
			while (candidate <= maximum) {
				Match match = bestMatch(candidate);
				if (match.correlation > bestCorrelation)
					bestCorrelation = match.correlation;
				double threshold = match.type == BurstType.SYNC ? SYNC_THRESHOLD : NORMAL_THRESHOLD;
				if (match.correlation >= threshold) {
					long radius = Math.max(1L, Math.round(samplesPerSymbol * 1.5d));
					long from = Math.max(minimum, candidate - radius);
					long to = Math.min(maximum, candidate + radius);
					long bestAt = candidate;
					for (long refine = from; refine <= to; refine++) {
						Match next = bestMatch(refine);
						if (next.correlation > match.correlation) {
							match = next;
							bestAt = refine;
						}
					}
					threshold = match.type == BurstType.SYNC ? SYNC_THRESHOLD : NORMAL_THRESHOLD;
					if (match.correlation >= threshold && confirmsSlotGrid(bestAt, match)) {
						Burst burst = createBurst(bestAt, match);
						result.add(burst);
						lastBurst = burst;
						bestCorrelation = Math.max(bestCorrelation, match.correlation);
						candidate = bestAt + Math.max(1L, Math.round(BURST_SYMBOLS * samplesPerSymbol * .75d));
						continue;
					}
				}
				candidate++;
			}
			scanSample = candidate;
			return result;
		}

		private boolean confirmsSlotGrid(long firstStart, Match first) {
			long previousStart = firstStart;
			double previousCfo = first.frequencyErrorHz;
			for (int slot = 1; slot <= CONFIRM_SLOTS; slot++) {
				double expected = firstStart + slot * BURST_SYMBOLS * samplesPerSymbol;
				long centre = Math.round(expected);
				long radius = Math.max(2L, Math.round(samplesPerSymbol * .65d));
				Match best = null;
				long bestAt = centre;
				for (long at = centre - radius; at <= centre + radius; at++) {
					Match candidate = bestMatch(at);
					if (best == null || candidate.correlation > best.correlation) {
						best = candidate;
						bestAt = at;
					}
				}
				if (best == null || best.correlation < CONFIRM_THRESHOLD
						|| Math.abs(best.frequencyErrorHz - previousCfo)
								> CONFIRM_CFO_TOLERANCE_HZ)
					return false;
				previousStart = bestAt;
				previousCfo = best.frequencyErrorHz;
			}
			return previousStart > firstStart;
		}

		private Match bestMatch(long burstStart) {
			Match first = correlate(burstStart, BurstType.NDB1, NTS1, NTS_OFFSET_SYMBOLS);
			Match second = correlate(burstStart, BurstType.NDB2, NTS2, NTS_OFFSET_SYMBOLS);
			Match sync = correlate(burstStart, BurstType.SYNC, STS, STS_OFFSET_SYMBOLS);
			/* STS is 19 symbols long, versus 11 for either NTS.  Once it clears
			 * its own threshold it is substantially less likely to be accidental,
			 * even if a short NTS happens to have a slightly larger score. */
			if (sync.correlation >= SYNC_THRESHOLD) return sync;
			return first.correlation >= second.correlation
					? (first.correlation >= sync.correlation ? first : sync)
					: (second.correlation >= sync.correlation ? second : sync);
		}

		private Match correlate(long burstStart, BurstType type, int[] bits, int symbolOffset) {
			double sumCos = 0d, sumSin = 0d;
			int symbols = bits.length / 2;
			for (int symbol = 0; symbol < symbols; symbol++) {
				double observed = differentialPhase(burstStart + (symbolOffset + symbol) * samplesPerSymbol);
				double expected = angle(bits[symbol * 2], bits[symbol * 2 + 1]);
				double error = wrap(observed - expected);
				sumCos += Math.cos(error);
				sumSin += Math.sin(error);
			}
			/* Do not rotate an arbitrary candidate into the training sequence.  The
			 * magnitude of the complex sum made short (11-symbol) NTS matches almost
			 * rotation invariant, so ordinary traffic data produced thousands of
			 * convincing false bursts.  A centred TETRA channel has only a small
			 * residual carrier error; the in-phase score preserves that fact while
			 * commonPhase still measures the residual CFO for demodulation. */
			double correlation = sumCos / symbols;
			double commonPhase = Math.atan2(sumSin, sumCos);
			double cfo = commonPhase * SYMBOL_RATE_HZ / (2d * Math.PI);
			return new Match(type, correlation, commonPhase, cfo);
		}

		private Burst createBurst(long start, Match match) {
			byte[] bits = new byte[BURST_BITS];
			float[] reliability = new float[BURST_BITS];
			for (int symbol = 0; symbol < BURST_SYMBOLS; symbol++) {
				double phase = wrap(differentialPhase(start + symbol * samplesPerSymbol)
						- match.commonPhase);
				bits[symbol * 2] = (byte)(phase < 0d ? 1 : 0);
				bits[symbol * 2 + 1] = (byte)(Math.abs(phase) > Math.PI / 2d ? 1 : 0);
				double d00 = phaseDistanceSquared(phase, Math.PI / 4d);
				double d01 = phaseDistanceSquared(phase, 3d * Math.PI / 4d);
				double d10 = phaseDistanceSquared(phase, -Math.PI / 4d);
				double d11 = phaseDistanceSquared(phase, -3d * Math.PI / 4d);
				reliability[symbol * 2] = (float)(Math.min(d10, d11) - Math.min(d00, d01));
				reliability[symbol * 2 + 1] = (float)(Math.min(d01, d11) - Math.min(d00, d10));
			}
			long timestamp = streamEpochMillis + Math.round(start * 1000d / sampleRateHz);
			return new Burst(timestamp, start, match.type, match.correlation,
					match.frequencyErrorHz, bits, reliability);
		}

		private static double phaseDistanceSquared(double observed, double expected) {
			double distance = wrap(observed - expected);
			return distance * distance;
		}

		private double differentialPhase(double absoluteSample) {
			double local = absoluteSample - historyStartSample;
			double previous = local - samplesPerSymbol;
			double i = interpolate(local, 0), q = interpolate(local, 1);
			double pi = interpolate(previous, 0), pq = interpolate(previous, 1);
			return Math.atan2(q * pi - i * pq, i * pi + q * pq);
		}

		private double interpolate(double sample, int component) {
			int left = (int)Math.floor(sample);
			if (left < 0) left = 0;
			if (left >= historySamples - 1) left = historySamples - 1;
			int right = Math.min(historySamples - 1, left + 1);
			double fraction = Math.max(0d, Math.min(1d, sample - left));
			double a = history[left * 2 + component];
			return a + (history[right * 2 + component] - a) * fraction;
		}

		private void trimHistory() {
			int keep = Math.max(4096, (int)Math.ceil(MAX_BUFFERED_BURSTS * BURST_SYMBOLS * samplesPerSymbol));
			if (historySamples <= keep) return;
			int remove = historySamples - keep;
			System.arraycopy(history, remove * 2, history, 0, keep * 2);
			historySamples = keep;
			historyStartSample += remove;
			if (scanSample < historyStartSample) scanSample = historyStartSample;
		}

		private void ensureCapacity(int samples) {
			int required = samples * 2;
			if (history.length >= required) return;
			int capacity = Math.max(required, Math.max(8192, history.length * 2));
			float[] larger = new float[capacity];
			System.arraycopy(history, 0, larger, 0, historySamples * 2);
			history = larger;
		}

		static int[] trainingBits(BurstType type) {
			int[] source = type == BurstType.NDB1 ? NTS1 : type == BurstType.NDB2 ? NTS2 : STS;
			return source.clone();
		}

		static int trainingOffset(BurstType type) {
			return type == BurstType.SYNC ? STS_OFFSET_SYMBOLS : NTS_OFFSET_SYMBOLS;
		}

		private static double angle(int firstBit, int secondBit) {
			double magnitude = secondBit == 1 ? 3d * Math.PI / 4d : Math.PI / 4d;
			return firstBit == 1 ? -magnitude : magnitude;
		}

		private static double wrap(double angle) {
			while (angle > Math.PI) angle -= 2d * Math.PI;
			while (angle <= -Math.PI) angle += 2d * Math.PI;
			return angle;
		}

		static final class Snapshot {
			final double bestCorrelation;
			final Burst lastBurst;
			Snapshot(double bestCorrelation, Burst lastBurst) {
				this.bestCorrelation = bestCorrelation;
				this.lastBurst = lastBurst;
			}
		}

		private static final class Match {
			final BurstType type;
			final double correlation, commonPhase, frequencyErrorHz;
			Match(BurstType type, double correlation, double commonPhase, double frequencyErrorHz) {
				this.type = type;
				this.correlation = correlation;
				this.commonPhase = commonPhase;
				this.frequencyErrorHz = frequencyErrorHz;
			}
		}
	}
}
