package jspectrumanalyzer.iq;

/** Lightweight GSM downlink acquisition based on the FCCH frequency burst. */
final class GsmSignalAnalyzer {
	static final double SYMBOL_RATE_HZ = 1625_000d / 6d;
	static final double FCCH_TONE_HZ = SYMBOL_RATE_HZ / 4d;
	private static final double SLOT_SYMBOLS = 156.25d;
	private static final int[] SCH_TRAINING = bits(
			"1011100101100010000001000000111100101101010001010111011000011011");
	private static final int[][] NORMAL_TRAINING = {
		bits("00100101110000100010010111"), bits("00101101110111100010110111"),
		bits("01000011101110100100001110"), bits("01000111101101000100011110"),
		bits("00011010111001000001101011"), bits("01001110101100000100111010"),
		bits("10100111110110001010011111"), bits("11101111000100101110111100")
	};
	/* TS 45.002 5.2.6: complete 148-bit standard dummy burst. Newer networks
	 * may use other permitted idle fillers; those deliberately remain unknown. */
	private static final int[] DUMMY_BURST = bits("0001111101101110110000010100100111000001001000100000001111100011100010111000101110001010111010010100011001100111001111010011111000100101111101010000");
	private static final double[][] DUMMY_REFERENCE = gmskReference(DUMMY_BURST);
	private static final double[][] SCH_REFERENCE = gmskReference(SCH_TRAINING);
	private static final int[] SCH_PARITY_FOR_EFFECT = parityEffectTable();
	private BcchData latestSystemInformation = BcchData.EMPTY;
	private final java.util.Map<Integer,SystemInformation> systemInformationMessages =
			new java.util.LinkedHashMap<Integer,SystemInformation>();
	private final java.util.List<PagingEvent> pagingEvents = new java.util.ArrayList<>();
	private final java.util.LinkedHashSet<Long> observedControlFrames = new java.util.LinkedHashSet<>();
	private final java.util.Map<String,Integer> pagingClients = new java.util.LinkedHashMap<>();
	private final byte[] pagingSessionKey = createPagingSessionKey();
	private long pagingSequence;
	private static final long TIMESLOT_WINDOW_MS = 3_000L;
	private final long[] timeslotNonDummy = new long[8];
	private final long[] timeslotDummy = new long[8];
	private final long[] timeslotUnknown = new long[8];
	private final java.util.ArrayDeque<TimeslotObservation> timeslotObservations =
			new java.util.ArrayDeque<TimeslotObservation>();

	Result analyze(byte[] iq, int length, int sampleRateHz) {
		int samples = Math.min(length, iq == null ? 0 : iq.length) / 2;
		if (sampleRateHz < 200_000 || samples < sampleRateHz / 200) {
			return Result.empty("need >= 200 kS/s GSM channel IQ");
		}

		/* The useful part of an FCCH burst is about 0.55 ms. A shorter window
		 * tolerates channelizer transients and still rejects ordinary GMSK data. */
		int window = Math.max(48, (int)Math.round(sampleRateHz * 0.00042d));
		int step = Math.max(8, window / 6);
		double[] dots = new double[window];
		double[] crosses = new double[window];
		double[] weights = new double[window];
		double[] powers = new double[window];
		double bestScore = 0;
		double bestFrequency = 0;
		double bestCoherence = 0;
		double bestPower = 0;
		int bestStart = -1;
		int candidates = 0;

		double dot = 0, cross = 0, weight = 0, power = 0;
		for (int n = 1; n < samples; n++) {
			int slot = n % window;
			if (n > window) {
				dot -= dots[slot]; cross -= crosses[slot]; weight -= weights[slot]; power -= powers[slot];
			}
			double previousI = iq[(n - 1) * 2], previousQ = iq[(n - 1) * 2 + 1];
			double currentI = iq[n * 2], currentQ = iq[n * 2 + 1];
			double nextDot = previousI * currentI + previousQ * currentQ;
			double nextCross = previousI * currentQ - previousQ * currentI;
			double nextWeight = Math.sqrt((previousI * previousI + previousQ * previousQ)
					* (currentI * currentI + currentQ * currentQ));
			double nextPower = currentI * currentI + currentQ * currentQ;
			dots[slot] = nextDot; crosses[slot] = nextCross; weights[slot] = nextWeight; powers[slot] = nextPower;
			dot += nextDot; cross += nextCross; weight += nextWeight; power += nextPower;
			if (n < window || (n - window) % step != 0) continue;
			if (weight < window * 16d) continue;
			double coherence = Math.hypot(dot, cross) / weight;
			double frequency = Math.atan2(cross, dot) * sampleRateHz / (2d * Math.PI);
			double toneError = Math.min(Math.abs(frequency - FCCH_TONE_HZ),
					Math.abs(frequency + FCCH_TONE_HZ));
			double frequencyScore = Math.max(0, 1d - toneError / 18_000d);
			double score = coherence * frequencyScore;
			if (coherence >= 0.72d && toneError <= 12_000d) candidates++;
			if (score > bestScore) {
				bestScore = score;
				bestFrequency = frequency;
				bestCoherence = coherence;
				bestPower = Math.sqrt(power / (window * 2d));
				bestStart = n - window + 1;
			}
		}

		boolean locked = bestStart >= 0 && bestCoherence >= 0.72d && candidates >= 2
				&& Math.abs(Math.abs(bestFrequency) - FCCH_TONE_HZ) <= 12_000d;
		double orientation = bestFrequency < 0 ? -1d : 1d;
		double cfoHz = locked ? bestFrequency - orientation * FCCH_TONE_HZ : 0;
		double frequencyQuality = Math.max(0, 1d - Math.abs(cfoHz) / 12_000d);
		double quality = locked ? Math.min(100, 100d * (0.72d * bestCoherence
				+ 0.28d * frequencyQuality)) : Math.min(35, bestScore * 35d);
		String state = locked ? "GSM FCCH locked" : bestScore >= 0.45d
				? "possible GSM FCCH" : "searching GSM FCCH";
		SchDetection sch = locked && sampleRateHz >= 400_000
				? detectSch(iq, samples, sampleRateHz, bestStart) : SchDetection.EMPTY;
		SchData schData = sch.detected ? decodeSch(iq, samples, sampleRateHz, sch.sample) : SchData.EMPTY;
		/* The four bursts following any SCH form either BCCH or CCCH. Decode
		 * them regardless of FN; the CRC-valid RR message type identifies it. */
		BcchData bcch = schData.valid
				? decodeBcch(iq, samples, sampleRateHz, sch.sample, schData.bcc, schData.frameNumber) : BcchData.EMPTY;
		if (schData.valid) observeTimeslots(iq, samples, sampleRateHz, sch.sample, schData.bcc);
		if (bcch.messageType == 0x1b) latestSystemInformation = bcch;
		BcchData systemInformation = latestSystemInformation;
		if (schData.valid) {
			quality = Math.min(100, quality * 0.55d + sch.correlation * 100d * 0.45d);
			state = systemInformation.valid ? "GSM System Information 3 decoded"
					: bcch.valid ? "GSM control channel decoded (FIRE CRC OK)" : "GSM SCH decoded (CRC OK)";
		} else if (sch.detected) {
			quality = Math.min(100, quality * 0.55d + sch.correlation * 100d * 0.45d);
			state = "GSM FCCH + SCH synchronized; checking data CRC";
		} else if (locked && sampleRateHz < 400_000) {
			state = "GSM FCCH locked; need >= 400 kS/s for SCH";
		} else if (locked) {
			state = "GSM FCCH locked; searching SCH";
		}
		return new Result(locked, sch.detected, schData.valid, quality, cfoHz, bestFrequency, bestCoherence,
				bestPower <= 0 ? -120d : 20d * Math.log10(bestPower / 128d),
				bestStart, candidates, sch.correlation, sch.sample, schData.bsic, schData.ncc, schData.bcc,
				schData.frameNumber, systemInformation.valid, bcch.messageType, systemInformation.mcc,
				systemInformation.mnc, systemInformation.lac, systemInformation.cellId,
				new java.util.ArrayList<SystemInformation>(systemInformationMessages.values()),
				new java.util.ArrayList<PagingEvent>(pagingEvents), timeslotLoad(), state);
	}

	private void observeTimeslots(byte[] iq, int samples, int sampleRateHz, int schTrainingSample, int bcc) {
		double sps = sampleRateHz / SYMBOL_RATE_HZ;
		int maximumFrames = Math.min(52, (int)Math.floor((samples - schTrainingSample) / (1250d * sps)));
		long[] nonDummy=new long[8], dummyCount=new long[8], unknown=new long[8];
		for (int frame = 0; frame < maximumFrames; frame++) for (int tn = 0; tn < 8; tn++) {
			int training = (int)Math.round(schTrainingSample + (frame * 1250d + tn * SLOT_SYMBOLS + 19d) * sps);
			int burstStart = (int)Math.round(training - 61d * sps);
			if (burstStart < 1 || burstStart + Math.ceil(148d * sps) >= samples) continue;
			double dummy = referenceCorrelation(iq, samples, burstStart, sps, DUMMY_REFERENCE, 3, 145);
			if (dummy >= 0.62d) dummyCount[tn]++;
			else if (findNormalTraining(iq, samples, sps, training, NORMAL_TRAINING[bcc]) >= 0) nonDummy[tn]++;
			else unknown[tn]++;
		}
		if (maximumFrames > 0) {
			TimeslotObservation observation=new TimeslotObservation(System.currentTimeMillis(),nonDummy,dummyCount,unknown);
			timeslotObservations.addLast(observation); addObservation(observation,1);
		}
		evictOldTimeslotObservations(System.currentTimeMillis());
	}

	private static double referenceCorrelation(byte[] iq, int samples, int start, double sps,
			double[][] reference, int first, int limit) {
		double real=0, imag=0, power=0; int count=0;
		for (int bit=first; bit<limit; bit++) {
			int sample=(int)Math.round(start+bit*sps);
			if (sample < 0 || sample >= samples) continue;
			double ir=iq[sample*2], ii=iq[sample*2+1], rr=reference[0][bit], ri=reference[1][bit];
			real += rr*ir+ri*ii; imag += ri*ir-rr*ii; power += ir*ir+ii*ii; count++;
		}
		return count == 0 ? 0 : Math.hypot(real,imag)/Math.sqrt(Math.max(1e-9,count*power));
	}

	private TimeslotLoad timeslotLoad() {
		evictOldTimeslotObservations(System.currentTimeMillis());
		return new TimeslotLoad(timeslotNonDummy.clone(), timeslotDummy.clone(), timeslotUnknown.clone());
	}

	private void evictOldTimeslotObservations(long now) {
		while (!timeslotObservations.isEmpty() && now-timeslotObservations.peekFirst().timestampMillis>TIMESLOT_WINDOW_MS)
			addObservation(timeslotObservations.removeFirst(),-1);
	}

	private void addObservation(TimeslotObservation observation, int direction) {
		for (int tn=0;tn<8;tn++) {
			timeslotNonDummy[tn]+=direction*observation.nonDummy[tn];
			timeslotDummy[tn]+=direction*observation.dummy[tn];
			timeslotUnknown[tn]+=direction*observation.unknown[tn];
		}
	}

	private BcchData decodeBcch(byte[] iq, int samples, int sampleRateHz,
			int schTrainingSample, int bcc, long schFrameNumber) {
		BcchData firstControl = BcchData.EMPTY;
		BcchData systemInformation = BcchData.EMPTY;
		for (int frameOffset = 1; frameOffset <= 51; frameOffset++) {
			long controlFrame = (schFrameNumber + frameOffset) % 2_715_648L;
			BcchData decoded = decodeControlBlock(iq, samples, sampleRateHz,
					schTrainingSample, bcc, frameOffset, controlFrame);
			if (!decoded.valid) continue;
			if (!firstControl.valid) firstControl = decoded;
			if (decoded.messageType == 0x1b) systemInformation = decoded;
		}
		return systemInformation.valid ? systemInformation : firstControl;
	}

	private BcchData decodeControlBlock(byte[] iq, int samples, int sampleRateHz,
			int schTrainingSample, int bcc, int frameOffset, long controlFrame) {
		double sps = sampleRateHz / SYMBOL_RATE_HZ;
		double[][] burstBits = new double[4][114];
		for (int burst = 0; burst < 4; burst++) {
			int expectedTraining = (int)Math.round(schTrainingSample + (frameOffset * 1250d + 19d + burst * 1250d) * sps);
			int trainingSample = findNormalTraining(iq, samples, sps, expectedTraining, NORMAL_TRAINING[bcc]);
			if (trainingSample < 0) return BcchData.EMPTY;
			int burstStart = (int)Math.round(trainingSample - 61d * sps);
			if (burstStart < 0 || burstStart + Math.ceil(148d * sps) >= samples) return BcchData.EMPTY;
			double[] soft = directBitMetrics(iq, burstStart, sps, 61, NORMAL_TRAINING[bcc]);
			for (int j = 0; j < 57; j++) burstBits[burst][j] = soft[3 + j];
			for (int j = 57; j < 114; j++) burstBits[burst][j] = soft[31 + j];
		}
		double[] coded = new double[456];
		for (int k = 0; k < coded.length; k++) {
			int burst = k & 3;
			int j = 2 * ((49 * k) % 57) + ((k & 7) / 4);
			coded[k] = burstBits[burst][j];
		}
		int[] decoded = viterbiControl(coded);
		if (!fireCrcValid(decoded)) return BcchData.EMPTY;
		byte[] message = new byte[23];
		for (int bit = 0; bit < 184; bit++) if (decoded[bit] != 0) message[bit >> 3] |= 1 << (bit & 7);
		int messageType = message[2] & 0xff;
		if (isSystemInformation(messageType))
			systemInformationMessages.put(messageType, new SystemInformation(messageType, message.clone()));
		if (observedControlFrames.add(controlFrame)) {
			observePaging(message);
			while (observedControlFrames.size() > 4096)
				observedControlFrames.remove(observedControlFrames.iterator().next());
		}
		return parseBcch(message);
	}

	private static boolean isSystemInformation(int type) {
		return type == 0x00 || type == 0x02 || type == 0x03 || type == 0x07
				|| (type >= 0x18 && type <= 0x1f);
	}

	private void observePaging(byte[] message) {
		if (message.length < 5) return;
		int type = message[2] & 0xff;
		if (type == 0x21) {
			int length1 = message[4] & 0xff;
			observeMobileIdentity(message, 5, length1);
			int length2At = 5 + length1;
			if (length2At < message.length) observeMobileIdentity(message, length2At + 1, message[length2At] & 0xff);
		} else if (type == 0x22) {
			addPagingEvent("Temporary identity", "", "", message, 4, 4);
			addPagingEvent("Temporary identity", "", "", message, 8, 4);
			if (message.length > 14) observeMobileIdentity(message, 14, message[13] & 0xff);
		}
	}

	private void observeMobileIdentity(byte[] message, int offset, int length) {
		if (length <= 0 || offset < 0 || offset + length > message.length) return;
		int identityType = message[offset] & 7;
		if (identityType != 1) {
			addPagingEvent("Temporary identity", "", "", message, offset, length);
			return;
		}
		StringBuilder prefix = new StringBuilder(6);
		int firstDigit = (message[offset] >> 4) & 15;
		if (firstDigit <= 9) prefix.append(firstDigit);
		for (int i = 1; i < length && prefix.length() < 6; i++) {
			int value = message[offset + i] & 0xff;
			int low = value & 15, high = (value >> 4) & 15;
			if (low <= 9) prefix.append(low);
			if (high <= 9 && prefix.length() < 6) prefix.append(high);
		}
		if (prefix.length() < 5) return;
		String mcc = prefix.substring(0, 3), mnc = prefix.substring(3, 5);
		if (prefix.length() >= 6 && PlmnDatabase.lookup(mcc, mnc) == null
				&& PlmnDatabase.lookup(mcc, prefix.substring(3, 6)) != null) mnc = prefix.substring(3, 6);
		addPagingEvent("Permanent identity", mcc, mnc, message, offset, length);
	}

	private void addPagingEvent(String identityType, String homeMcc, String homeMnc,
			byte[] identity, int offset, int length) {
		PlmnDatabase.Entry network = homeMcc.length() == 0 ? null : PlmnDatabase.lookup(homeMcc, homeMnc);
		if (network == null) return;
		String token = pagingToken(identity, offset, length);
		Integer clientNumber = pagingClients.get(token);
		if (clientNumber == null) {
			clientNumber = pagingClients.size() + 1;
			pagingClients.put(token, clientNumber);
		}
		pagingEvents.add(new PagingEvent(++pagingSequence, System.currentTimeMillis(), identityType,
				homeMcc, homeMnc, network == null ? "" : network.country,
				network == null ? "" : network.networkName(), clientNumber));
	}

	private static byte[] createPagingSessionKey() {
		byte[] key = new byte[32];
		new java.security.SecureRandom().nextBytes(key);
		return key;
	}

	private String pagingToken(byte[] identity, int offset, int length) {
		try {
			javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
			mac.init(new javax.crypto.spec.SecretKeySpec(pagingSessionKey, "HmacSHA256"));
			mac.update(identity, offset, length);
			byte[] digest = mac.doFinal();
			StringBuilder token = new StringBuilder(32);
			for (int i = 0; i < 16; i++) token.append(String.format(java.util.Locale.ROOT, "%02x", digest[i] & 0xff));
			return token.toString();
		} catch (java.security.GeneralSecurityException exception) {
			throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
		}
	}

	private int findNormalTraining(byte[] iq, int samples, double sps, int expected, int[] training) {
		int radius = Math.max(5, (int)Math.ceil(3d * sps));
		double best = 0; int bestSample = -1;
		for (int start = expected - radius; start <= expected + radius; start++) {
			if (start < 1 || start + Math.ceil(training.length * sps) >= samples) continue;
			double real = 0, imag = 0, power = 0;
			for (int bit = 0; bit < training.length; bit++) {
				int sample = (int)Math.round(start + bit * sps);
				double[] rotated = derotate(iq[sample * 2], iq[sample * 2 + 1], 61 + bit);
				double sign = training[bit] == 0 ? -1d : 1d;
				real += sign * rotated[0]; imag += sign * rotated[1];
				power += rotated[0] * rotated[0] + rotated[1] * rotated[1];
			}
			double score = Math.hypot(real, imag) / Math.sqrt(Math.max(1e-9, training.length * power));
			if (score > best) { best = score; bestSample = start; }
		}
		return best >= 0.45d ? bestSample : -1;
	}

	private int[] viterbiControl(double[] coded) {
		final int states = 16, steps = 228; final double negativeInfinity = -1e100;
		double[] metric = new double[states]; java.util.Arrays.fill(metric, negativeInfinity); metric[0] = 0;
		int[][] previousState = new int[steps][states], previousBit = new int[steps][states];
		for (int step = 0; step < steps; step++) {
			double[] next = new double[states]; java.util.Arrays.fill(next, negativeInfinity);
			for (int state = 0; state < states; state++) if (metric[state] > negativeInfinity / 2) {
				for (int bit = 0; bit < 2; bit++) {
					int o0 = bit ^ ((state >> 2) & 1) ^ ((state >> 3) & 1);
					int o1 = bit ^ (state & 1) ^ ((state >> 2) & 1) ^ ((state >> 3) & 1);
					double candidate = metric[state] + (o0 == 0 ? -coded[step * 2] : coded[step * 2])
							+ (o1 == 0 ? -coded[step * 2 + 1] : coded[step * 2 + 1]);
					int target = ((state << 1) & 14) | bit;
					if (candidate > next[target]) { next[target] = candidate; previousState[step][target] = state; previousBit[step][target] = bit; }
				}
			}
			metric = next;
		}
		int[] decoded = new int[steps]; int state = 0;
		for (int step = steps - 1; step >= 0; step--) { decoded[step] = previousBit[step][state]; state = previousState[step][state]; }
		return decoded;
	}

	private boolean fireCrcValid(int[] decoded) {
		if (decoded == null || decoded.length < 228) return false;
		for (int i = 224; i < 228; i++) if (decoded[i] != 0) return false;
		int[] remainder = java.util.Arrays.copyOf(decoded, 224);
		int[] generatorOffsets = { 0, 14, 17, 23, 37, 40 };
		for (int position = 0; position < 184; position++) if (remainder[position] != 0)
			for (int offset : generatorOffsets) remainder[position + offset] ^= 1;
		for (int position = 184; position < 224; position++) if (remainder[position] != 1) return false;
		return true;
	}

	private BcchData parseBcch(byte[] message) {
		int messageType = message[2] & 0xff;
		if (messageType != 0x1b || message.length < 11) return new BcchData(true, messageType, "", "", -1, -1);
		int cellId = ((message[3] & 0xff) << 8) | (message[4] & 0xff);
		int p0 = message[5] & 0xff, p1 = message[6] & 0xff, p2 = message[7] & 0xff;
		String mcc = "" + (p0 & 15) + ((p0 >> 4) & 15) + (p1 & 15);
		int mnc3 = (p1 >> 4) & 15;
		String mnc = "" + (p2 & 15) + ((p2 >> 4) & 15) + (mnc3 == 15 ? "" : Integer.toString(mnc3));
		int lac = ((message[8] & 0xff) << 8) | (message[9] & 0xff);
		return new BcchData(true, messageType, mcc, mnc, lac, cellId);
	}

	private SchData decodeSch(byte[] iq, int samples, int sampleRateHz, int trainingSample) {
		double sps = sampleRateHz / SYMBOL_RATE_HZ;
		int burstStart = (int)Math.round(trainingSample - 42d * sps);
		if (burstStart < 0 || burstStart + Math.ceil(148d * sps) >= samples) return SchData.EMPTY;
		double[] phase = new double[148];
		for (int bit = 1; bit < phase.length; bit++) {
			int a = (int)Math.round(burstStart + (bit - 1d) * sps);
			int b = (int)Math.round(burstStart + bit * sps);
			double ai = iq[a * 2], aq = iq[a * 2 + 1], bi = iq[b * 2], bq = iq[b * 2 + 1];
			double weight = Math.sqrt((ai * ai + aq * aq) * (bi * bi + bq * bq));
			phase[bit] = weight <= 1e-9 ? 0 : (ai * bq - aq * bi) / weight;
		}
		double orientationScore = 0;
		for (int bit = 1; bit < SCH_TRAINING.length; bit++) {
			int expected = SCH_TRAINING[bit] == SCH_TRAINING[bit - 1] ? 1 : -1;
			orientationScore += expected * phase[42 + bit];
		}
		double[] bitSoft = directBitMetrics(iq, burstStart, sps, 42, SCH_TRAINING);
		int[] softDecoded = viterbiSchBits(bitSoft);
		if (softDecoded != null && schCrcValid(softDecoded)) return parseSch(softDecoded);
		int[] crcAided = softDecoded == null ? null
				: crcAidedSch(phase, orientationScore < 0 ? -1d : 1d, softDecoded, 4);
		if (crcAided != null && schCrcValid(crcAided)) return parseSch(crcAided);

		/* Retain the inexpensive hard decision as a fallback for exceptionally
		 * clean captures; the soft trellis above is the normal decoding path. */
		int[][] alternatives = new int[2][148];
		int bestTrainingErrors = Integer.MAX_VALUE, bestOrientation = 0;
		for (int orientation = 0; orientation < 2; orientation++) {
			int previous = -1;
			alternatives[orientation][0] = 0;
			for (int bit = 1; bit < 148; bit++) {
				int a = (int)Math.round(burstStart + (bit - 1d) * sps);
				int b = (int)Math.round(burstStart + bit * sps);
				double cross = iq[a * 2] * (double)iq[b * 2 + 1] - iq[a * 2 + 1] * (double)iq[b * 2];
				int encoded = cross >= 0 ? 1 : -1;
				if (orientation != 0) encoded = -encoded;
				int current = encoded * previous;
				alternatives[orientation][bit] = current > 0 ? 1 : 0;
				previous = current;
			}
			int errors = 0;
			for (int i = 0; i < 64; i++) if (alternatives[orientation][42 + i] != SCH_TRAINING[i]) errors++;
			if (errors < bestTrainingErrors) { bestTrainingErrors = errors; bestOrientation = orientation; }
		}
		int[] burst = alternatives[bestOrientation];
		int[] coded = new int[78];
		System.arraycopy(burst, 3, coded, 0, 39);
		System.arraycopy(burst, 106, coded, 39, 39);
		for (int swap = 0; swap < 2; swap++) {
			int[] decoded = viterbi(coded, swap != 0);
			if (decoded != null && schCrcValid(decoded)) return parseSch(decoded);
		}
		return SchData.EMPTY;
	}

	private double[] directBitMetrics(byte[] iq, int burstStart, double sps, int trainingOffset, int[] training) {
		double channelReal = 0, channelImag = 0;
		for (int bit = 0; bit < training.length; bit++) {
			int physical = trainingOffset + bit;
			int sample = (int)Math.round(burstStart + physical * sps);
			double real = iq[sample * 2], imag = iq[sample * 2 + 1];
			double[] rotated = derotate(real, imag, physical);
			double sign = training[bit] == 0 ? -1d : 1d;
			channelReal += sign * rotated[0]; channelImag += sign * rotated[1];
		}
		double norm = Math.max(1e-9, Math.hypot(channelReal, channelImag));
		double[] soft = new double[148];
		for (int physical = 0; physical < soft.length; physical++) {
			int sample = (int)Math.round(burstStart + physical * sps);
			double[] rotated = derotate(iq[sample * 2], iq[sample * 2 + 1], physical);
			soft[physical] = (rotated[0] * channelReal + rotated[1] * channelImag) / norm;
		}
		return soft;
	}

	private double[] derotate(double real, double imag, int symbol) {
		switch (symbol & 3) {
		case 1: return new double[] { imag, -real };
		case 2: return new double[] { -real, -imag };
		case 3: return new double[] { -imag, real };
		default: return new double[] { real, imag };
		}
	}

	private int[] viterbiSchBits(double[] soft) {
		final int states = 16, steps = 39;
		final double negativeInfinity = -1e100;
		double[] metric = new double[states];
		java.util.Arrays.fill(metric, negativeInfinity); metric[0] = 0;
		int[][] previousState = new int[steps][states], previousBit = new int[steps][states];
		for (int step = 0; step < steps; step++) {
			double[] next = new double[states]; java.util.Arrays.fill(next, negativeInfinity);
			for (int state = 0; state < states; state++) if (metric[state] > negativeInfinity / 2) {
				for (int bit = 0; bit < 2; bit++) {
					int output0 = bit ^ ((state >> 2) & 1) ^ ((state >> 3) & 1);
					int output1 = bit ^ (state & 1) ^ ((state >> 2) & 1) ^ ((state >> 3) & 1);
					int coded0 = step * 2, coded1 = coded0 + 1;
					int physical0 = coded0 < 39 ? 3 + coded0 : 106 + coded0 - 39;
					int physical1 = coded1 < 39 ? 3 + coded1 : 106 + coded1 - 39;
					double candidate = metric[state] + (output0 == 0 ? -soft[physical0] : soft[physical0])
							+ (output1 == 0 ? -soft[physical1] : soft[physical1]);
					int target = ((state << 1) & 14) | bit;
					if (candidate > next[target]) {
						next[target] = candidate; previousState[step][target] = state; previousBit[step][target] = bit;
					}
				}
			}
			metric = next;
		}
		if (metric[0] <= negativeInfinity / 2) return null;
		int[] decoded = new int[steps]; int state = 0;
		for (int step = steps - 1; step >= 0; step--) {
			decoded[step] = previousBit[step][state]; state = previousState[step][state];
		}
		return decoded;
	}

	private int[] viterbiSch(double[] phase, double orientation) {
		final int encoderStates = 16, combinedStates = 32, steps = 39;
		final double negativeInfinity = -1e100;
		double[] metric = new double[combinedStates];
		java.util.Arrays.fill(metric, negativeInfinity);
		metric[0] = 0; // convolutional state zero, preceding tail bit zero
		int[][] previousState = new int[steps][combinedStates];
		int[][] previousBit = new int[steps][combinedStates];
		for (int step = 0; step < steps; step++) {
			double[] next = new double[combinedStates];
			java.util.Arrays.fill(next, negativeInfinity);
			for (int combined = 0; combined < combinedStates; combined++) {
				if (metric[combined] <= negativeInfinity / 2) continue;
				int state = combined >> 1, previousCoded = combined & 1;
				for (int bit = 0; bit < 2; bit++) {
					int previous1 = state & 1, previous3 = (state >> 2) & 1, previous4 = (state >> 3) & 1;
					int output0 = bit ^ previous3 ^ previous4;
					int output1 = bit ^ previous1 ^ previous3 ^ previous4;
					int coded0 = step * 2, coded1 = coded0 + 1;
					int physical0 = coded0 < 39 ? 3 + coded0 : 106 + coded0 - 39;
					int physical1 = coded1 < 39 ? 3 + coded1 : 106 + coded1 - 39;
					int before0 = coded0 == 39 ? SCH_TRAINING[63] : previousCoded;
					int before1 = coded1 == 39 ? SCH_TRAINING[63] : output0;
					double branch = transitionScore(before0, output0, phase[physical0], orientation)
							+ transitionScore(before1, output1, phase[physical1], orientation);
					int targetEncoder = ((state << 1) & 14) | bit;
					int target = (targetEncoder << 1) | output1;
					double candidate = metric[combined] + branch;
					if (candidate > next[target]) {
						next[target] = candidate;
						previousState[step][target] = combined;
						previousBit[step][target] = bit;
					}
				}
			}
			metric = next;
		}
		int state = metric[0] >= metric[1] ? 0 : 1; // encoder state must terminate at zero
		if (metric[state] <= negativeInfinity / 2) return null;
		int[] decoded = new int[steps];
		for (int step = steps - 1; step >= 0; step--) {
			decoded[step] = previousBit[step][state];
			state = previousState[step][state];
		}
		return decoded;
	}

	private double transitionScore(int previous, int current, double observed, double orientation) {
		return (previous == current ? 1d : -1d) * observed * orientation;
	}

	private int[] crcAidedSch(double[] phase, double orientation, int[] seed, int maximumFlips) {
		SchCandidate best = new SchCandidate();
		int[] information = java.util.Arrays.copyOf(seed, 25);
		for (int flips = 0; flips <= maximumFlips; flips++)
			searchSchCandidates(phase, orientation, information, 0, flips, flips, best);
		return best.block;
	}

	private void searchSchCandidates(double[] phase, double orientation, int[] information,
			int nextBit, int flipsLeft, int totalFlips, SchCandidate best) {
		if (flipsLeft == 0) {
			int[] block = makeSchBlock(information);
			double score = scoreSchBlock(phase, orientation, block);
			if (score > best.score) { best.score = score; best.block = block; best.corrections = totalFlips; }
			return;
		}
		for (int bit = nextBit; bit <= information.length - flipsLeft; bit++) {
			information[bit] ^= 1;
			searchSchCandidates(phase, orientation, information, bit + 1, flipsLeft - 1, totalFlips, best);
			information[bit] ^= 1;
		}
	}

	private int[] makeSchBlock(int[] information) {
		int[] block = new int[39];
		System.arraycopy(information, 0, block, 0, 25);
		int register = 0;
		for (int i = 0; i < 35; i++) {
			int input = i < 25 ? information[i] : 0;
			int feedback = ((register >> 9) & 1) ^ input;
			register = (register << 1) & 0x3ff;
			if (feedback != 0) register ^= 0x175;
		}
		int parity = SCH_PARITY_FOR_EFFECT[register ^ 0x3ff];
		for (int i = 0; i < 10; i++) block[25 + i] = (parity >> (9 - i)) & 1;
		return block;
	}

	private double scoreSchBlock(double[] phase, double orientation, int[] block) {
		int previous1 = 0, previous2 = 0, previous3 = 0, previous4 = 0;
		int previousCoded = 0;
		double score = 0;
		for (int step = 0; step < 39; step++) {
			int bit = block[step];
			int output0 = bit ^ previous3 ^ previous4;
			int output1 = bit ^ previous1 ^ previous3 ^ previous4;
			int coded0 = step * 2, coded1 = coded0 + 1;
			int physical0 = coded0 < 39 ? 3 + coded0 : 106 + coded0 - 39;
			int physical1 = coded1 < 39 ? 3 + coded1 : 106 + coded1 - 39;
			score += transitionScore(previousCoded, output0, phase[physical0], orientation);
			score += transitionScore(coded1 == 39 ? SCH_TRAINING[63] : output0,
					output1, phase[physical1], orientation);
			previousCoded = output1;
			previous4 = previous3; previous3 = previous2; previous2 = previous1; previous1 = bit;
		}
		return score;
	}

	private static final class SchCandidate {
		double score = -Double.MAX_VALUE;
		int[] block;
		int corrections;
	}

	private int[] viterbi(int[] coded, boolean swapPairs) {
		final int states = 16, steps = 39, infinity = 1_000_000;
		int[] metric = new int[states];
		java.util.Arrays.fill(metric, infinity); metric[0] = 0;
		int[][] previousState = new int[steps][states];
		int[][] previousBit = new int[steps][states];
		for (int step = 0; step < steps; step++) {
			int[] next = new int[states]; java.util.Arrays.fill(next, infinity);
			int received0 = coded[step * 2 + (swapPairs ? 1 : 0)];
			int received1 = coded[step * 2 + (swapPairs ? 0 : 1)];
			for (int state = 0; state < states; state++) if (metric[state] < infinity) {
				for (int bit = 0; bit < 2; bit++) {
					/* TS 45.003: G0=1+D^3+D^4, G1=1+D+D^3+D^4.
					 * State bits contain u(k-1)..u(k-4), least recent first. */
					int previous1 = state & 1;
					int previous3 = (state >> 2) & 1;
					int previous4 = (state >> 3) & 1;
					int output0 = bit ^ previous3 ^ previous4;
					int output1 = bit ^ previous1 ^ previous3 ^ previous4;
					int target = ((state << 1) & 14) | bit;
					int candidate = metric[state] + (output0 == received0 ? 0 : 1) + (output1 == received1 ? 0 : 1);
					if (candidate < next[target]) {
						next[target] = candidate; previousState[step][target] = state; previousBit[step][target] = bit;
					}
				}
			}
			metric = next;
		}
		if (metric[0] >= infinity) return null;
		int[] decoded = new int[steps]; int state = 0;
		for (int step = steps - 1; step >= 0; step--) {
			decoded[step] = previousBit[step][state]; state = previousState[step][state];
		}
		return decoded;
	}

	private boolean schCrcValid(int[] decoded) {
		if (decoded.length < 39 || decoded[35] != 0 || decoded[36] != 0 || decoded[37] != 0 || decoded[38] != 0)
			return false;
		int register = 0;
		for (int i = 0; i < 35; i++) {
			int feedback = ((register >> 9) & 1) ^ decoded[i];
			register = (register << 1) & 0x3ff;
			if (feedback != 0) register ^= 0x175;
		}
		return register == 0x3ff;
	}

	private SchData parseSch(int[] d) {
		int ncc=(d[7]<<2)|(d[6]<<1)|d[5], bcc=(d[4]<<2)|(d[3]<<1)|d[2];
		int t1=(d[1]<<10)|(d[0]<<9)|(d[15]<<8)|(d[14]<<7)|(d[13]<<6)|(d[12]<<5)
				|(d[11]<<4)|(d[10]<<3)|(d[9]<<2)|(d[8]<<1)|d[23];
		int t2=(d[22]<<4)|(d[21]<<3)|(d[20]<<2)|(d[19]<<1)|d[18];
		int t3p=(d[17]<<2)|(d[16]<<1)|d[24], t3=10*t3p+1;
		long fn=51L*26L*t1+51L*((t3-t2+26)%26)+t3;
		return new SchData(true,(ncc<<3)|bcc,ncc,bcc,fn);
	}

	private SchDetection detectSch(byte[] iq, int samples, int sampleRateHz, int fcchWindowStart) {
		double samplesPerSymbol = sampleRateHz / SYMBOL_RATE_HZ;
		/* FCCH is in TS0 of frame N and SCH in TS0 of frame N+1. */
		int expected = (int)Math.round(fcchWindowStart + (8d * SLOT_SYMBOLS + 42d) * samplesPerSymbol);
		int radius = Math.max(16, (int)Math.round(SLOT_SYMBOLS * samplesPerSymbol * 0.55d));
		double best = 0;
		int bestSample = -1;
		for (int start = Math.max((int)Math.ceil(samplesPerSymbol), expected - radius);
				start <= Math.min(samples - (int)Math.ceil((SCH_TRAINING.length + 1) * samplesPerSymbol),
						expected + radius); start++) {
			double directCorrelation = 0, differentialCorrelation = 0, magnitude = 0;
			for (int bit = 0; bit < SCH_TRAINING.length; bit++) {
				int current = (int)Math.round(start + bit * samplesPerSymbol);
				int previous = (int)Math.round(start + (bit - 1d) * samplesPerSymbol);
				double pi = iq[previous * 2], pq = iq[previous * 2 + 1];
				double ci = iq[current * 2], cq = iq[current * 2 + 1];
				double cross = pi * cq - pq * ci;
				double weight = Math.sqrt((pi * pi + pq * pq) * (ci * ci + cq * cq));
				if (weight <= 1e-9) continue;
				double phaseMetric = cross / weight;
				directCorrelation += (SCH_TRAINING[bit] == 0 ? -1d : 1d) * phaseMetric;
				if (bit > 0) {
					boolean transition = SCH_TRAINING[bit] != SCH_TRAINING[bit - 1];
					differentialCorrelation += (transition ? -1d : 1d) * phaseMetric;
				}
				magnitude += Math.abs(phaseMetric);
			}
			if (magnitude <= 1e-9) continue;
			double referenceReal = 0, referenceImag = 0, referencePower = 0;
			for (int bit = 5; bit < SCH_TRAINING.length - 5; bit++) {
				int sample = (int)Math.round(start + bit * samplesPerSymbol);
				double ir = iq[sample * 2], ii = iq[sample * 2 + 1];
				double sr = SCH_REFERENCE[0][bit], si = SCH_REFERENCE[1][bit];
				referenceReal += sr * ir + si * ii;
				referenceImag += si * ir - sr * ii;
				referencePower += ir * ir + ii * ii;
			}
			double referenceScore = Math.hypot(referenceReal, referenceImag)
					/ Math.sqrt(Math.max(1e-9, (SCH_TRAINING.length - 10d) * referencePower));
			double score = Math.max(referenceScore,
					Math.max(Math.abs(directCorrelation), Math.abs(differentialCorrelation)) / magnitude);
			if (score > best) { best = score; bestSample = start; }
		}
		return new SchDetection(best >= 0.58d, best, bestSample);
	}

	private static int[] bits(String value) {
		int[] result = new int[value.length()];
		for (int i = 0; i < result.length; i++) result[i] = value.charAt(i) == '1' ? 1 : 0;
		return result;
	}

	private static int[] parityEffectTable() {
		int[] result = new int[1024];
		for (int parity = 0; parity < 1024; parity++) {
			int register = 0;
			for (int bit = 9; bit >= 0; bit--) {
				int feedback = ((register >> 9) & 1) ^ ((parity >> bit) & 1);
				register = (register << 1) & 0x3ff;
				if (feedback != 0) register ^= 0x175;
			}
			result[register] = parity;
		}
		return result;
	}


	private static double[][] gmskReference(int[] input) {
		double[][] output = new double[2][input.length];
		output[1][0] = -1d;
		int previous = 2 * input[0] - 1;
		for (int i = 1; i < input.length; i++) {
			int current = 2 * input[i] - 1;
			int encoded = current * previous;
			double previousReal = output[0][i - 1], previousImag = output[1][i - 1];
			output[0][i] = -encoded * previousImag;
			output[1][i] = encoded * previousReal;
			previous = current;
		}
		return output;
	}

	private static final class SchDetection {
		static final SchDetection EMPTY = new SchDetection(false, 0, -1);
		final boolean detected;
		final double correlation;
		final int sample;
		SchDetection(boolean detected, double correlation, int sample) {
			this.detected = detected; this.correlation = correlation; this.sample = sample;
		}
	}

	private static final class SchData {
		static final SchData EMPTY = new SchData(false,-1,-1,-1,-1);
		final boolean valid; final int bsic,ncc,bcc; final long frameNumber;
		SchData(boolean valid,int bsic,int ncc,int bcc,long frameNumber) {
			this.valid=valid;this.bsic=bsic;this.ncc=ncc;this.bcc=bcc;this.frameNumber=frameNumber;
		}
	}

	private static final class BcchData {
		static final BcchData EMPTY = new BcchData(false, -1, "", "", -1, -1);
		final boolean valid; final int messageType; final String mcc, mnc; final int lac, cellId;
		BcchData(boolean valid, int messageType, String mcc, String mnc, int lac, int cellId) {
			this.valid=valid; this.messageType=messageType; this.mcc=mcc; this.mnc=mnc; this.lac=lac; this.cellId=cellId;
		}
	}

	static final class SystemInformation {
		final int messageType;
		final byte[] message;
		SystemInformation(int messageType, byte[] message) {
			this.messageType = messageType;
			this.message = message;
		}
	}

	static final class PagingEvent {
		final long sequence, timestampMillis;
		final String identityType, homeMcc, homeMnc, country, operator;
		final int clientNumber;
		PagingEvent(long sequence, long timestampMillis, String identityType, String homeMcc,
				String homeMnc, String country, String operator, int clientNumber) {
			this.sequence=sequence; this.timestampMillis=timestampMillis; this.identityType=identityType;
			this.homeMcc=homeMcc; this.homeMnc=homeMnc; this.country=country; this.operator=operator;
			this.clientNumber=clientNumber;
		}
	}

	static final class TimeslotLoad {
		final long[] nonDummy, dummy, unknown;
		TimeslotLoad(long[] nonDummy, long[] dummy, long[] unknown) {
			this.nonDummy=nonDummy; this.dummy=dummy; this.unknown=unknown;
		}
		static TimeslotLoad empty() { return new TimeslotLoad(new long[8],new long[8],new long[8]); }
	}

	private static final class TimeslotObservation {
		final long timestampMillis; final long[] nonDummy,dummy,unknown;
		TimeslotObservation(long timestampMillis,long[] nonDummy,long[] dummy,long[] unknown) {
			this.timestampMillis=timestampMillis; this.nonDummy=nonDummy; this.dummy=dummy; this.unknown=unknown;
		}
	}

	static final class Result {
		final boolean fcchLocked;
		final boolean schDetected;
		final boolean schDecoded;
		final double quality;
		final double cfoHz;
		final double toneHz;
		final double coherence;
		final double burstDbfs;
		final int burstSample;
		final int candidates;
		final double schCorrelation;
		final int schSample;
		final int bsic, ncc, bcc;
		final long frameNumber;
		final boolean bcchDecoded;
		final int bcchMessageType;
		final String mcc, mnc;
		final int lac, cellId;
		final java.util.List<SystemInformation> systemInformation;
		final java.util.List<PagingEvent> pagingEvents;
		final TimeslotLoad timeslotLoad;
		final String state;

		Result(boolean fcchLocked, boolean schDetected, boolean schDecoded, double quality, double cfoHz, double toneHz,
				double coherence, double burstDbfs, int burstSample, int candidates, double schCorrelation, int schSample,
				int bsic, int ncc, int bcc, long frameNumber, boolean bcchDecoded, int bcchMessageType,
				String mcc, String mnc, int lac, int cellId,
				java.util.List<SystemInformation> systemInformation,
				java.util.List<PagingEvent> pagingEvents, TimeslotLoad timeslotLoad, String state) {
			this.fcchLocked = fcchLocked;
			this.schDetected = schDetected;
			this.schDecoded = schDecoded;
			this.quality = quality;
			this.cfoHz = cfoHz;
			this.toneHz = toneHz;
			this.coherence = coherence;
			this.burstDbfs = burstDbfs;
			this.burstSample = burstSample;
			this.candidates = candidates;
			this.schCorrelation = schCorrelation;
			this.schSample = schSample;
			this.bsic=bsic; this.ncc=ncc; this.bcc=bcc; this.frameNumber=frameNumber;
			this.bcchDecoded=bcchDecoded; this.bcchMessageType=bcchMessageType;
			this.mcc=mcc; this.mnc=mnc; this.lac=lac; this.cellId=cellId;
			this.systemInformation=java.util.Collections.unmodifiableList(systemInformation);
			this.pagingEvents=java.util.Collections.unmodifiableList(pagingEvents);
			this.timeslotLoad=timeslotLoad;
			this.state = state;
		}

		static Result empty(String state) {
			return new Result(false, false, false, 0, 0, 0, 0, -120, -1, 0, 0, -1,
					-1, -1, -1, -1, false, -1, "", "", -1, -1,
					java.util.Collections.<SystemInformation>emptyList(),
					java.util.Collections.<PagingEvent>emptyList(), TimeslotLoad.empty(), state);
		}
	}
}
