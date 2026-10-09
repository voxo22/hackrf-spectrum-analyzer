package jspectrumanalyzer.iq;

/**
 * Extracts an arbitrary complex sub-channel from signed 8-bit interleaved IQ.
 *
 * <p>The oscillator, anti-alias filter and decimator deliberately operate in
 * floating point.  Re-quantizing a weak filtered signal to 8-bit IQ would lose
 * part of the processing gain that the channel filter is meant to recover.</p>
 */
final class IQSubchannelExtractor {
	private static final int MIN_TAPS = 17;
	private static final int MAX_TAPS = 511;
	private static final double KAISER_BETA = 5.65d; // approximately 60 dB stop band

	private IQSubchannelExtractor() { }

	/** Stateful mixer/FIR/decimator for live IQ blocks. Each input sample is
	 * filtered exactly once and oscillator/filter history survives block edges. */
	static final class Stream {
		private final int inputRateHz,decimation;
		private final double outputRateHz,stepCos,stepSin;
		private final double[] taps,historyI,historyQ;
		private int historyIndex,decimationPhase,writtenSinceNormalize;
		private double oscillatorCos=1d,oscillatorSin;

		Stream(int inputRateHz,double offsetHz,int occupiedBandwidthHz,int requestedOutputRateHz){
			if(inputRateHz<=0)throw new IllegalArgumentException("input sample rate must be positive");
			this.inputRateHz=inputRateHz;
			int outputTarget=Math.max(1,Math.min(inputRateHz,requestedOutputRateHz));
			decimation=Math.max(1,inputRateHz/outputTarget);outputRateHz=inputRateHz/(double)decimation;
			taps=decimation==1?new double[]{1d}:designFilter(inputRateHz,outputRateHz,occupiedBandwidthHz);
			historyI=new double[taps.length];historyQ=new double[taps.length];
			decimationPhase=decimation-1;
			double angle=-2d*Math.PI*offsetHz/inputRateHz;
			stepCos=Math.cos(angle);stepSin=Math.sin(angle);
		}

		double sampleRateHz(){return outputRateHz;}

		Result process(byte[] input,int inputBytes){
			if(input==null||inputBytes<2)return Result.empty(outputRateHz);
			int inputSamples=Math.min(inputBytes,input.length)/2;
			float[] output=new float[((inputSamples+decimation-1)/decimation+1)*2];int written=0;
			for(int sample=0;sample<inputSamples;sample++){
				double sourceI=input[sample*2]/128d,sourceQ=input[sample*2+1]/128d;
				double mixedI=sourceI*oscillatorCos-sourceQ*oscillatorSin;
				double mixedQ=sourceI*oscillatorSin+sourceQ*oscillatorCos;
				historyI[historyIndex]=mixedI;historyQ[historyIndex]=mixedQ;
				if(++historyIndex==taps.length)historyIndex=0;
				double nextCos=oscillatorCos*stepCos-oscillatorSin*stepSin;
				oscillatorSin=oscillatorSin*stepCos+oscillatorCos*stepSin;oscillatorCos=nextCos;
				if(++writtenSinceNormalize>=16384){writtenSinceNormalize=0;
					double magnitude=Math.sqrt(oscillatorCos*oscillatorCos+oscillatorSin*oscillatorSin);
					if(magnitude>0d){oscillatorCos/=magnitude;oscillatorSin/=magnitude;}}
				if(++decimationPhase<decimation)continue;decimationPhase=0;
				double filteredI=0d,filteredQ=0d;int first=historyIndex;
				int last=first+taps.length-1;if(last>=taps.length)last-=taps.length;
				for(int tap=0;tap<taps.length/2;tap++){
					filteredI+=(historyI[first]+historyI[last])*taps[tap];
					filteredQ+=(historyQ[first]+historyQ[last])*taps[tap];
					if(++first==taps.length)first=0;if(--last<0)last=taps.length-1;
				}
				filteredI+=historyI[first]*taps[taps.length/2];
				filteredQ+=historyQ[first]*taps[taps.length/2];
				output[written*2]=(float)filteredI;output[written*2+1]=(float)filteredQ;written++;
			}
			if(written*2!=output.length){float[] exact=new float[written*2];
				System.arraycopy(output,0,exact,0,exact.length);output=exact;}
			return new Result(output,written,outputRateHz,decimation,taps.length);
		}
	}

	static Result extract(byte[] input, int inputBytes, int inputRateHz, double offsetHz,
			int occupiedBandwidthHz, int requestedOutputRateHz) {
		if (input == null || inputRateHz <= 0 || inputBytes < 2)
			return Result.empty(Math.max(1, inputRateHz));
		int inputSamples = Math.min(inputBytes, input.length) / 2;
		if (inputSamples == 0) return Result.empty(inputRateHz);

		int outputTarget = Math.max(1, Math.min(inputRateHz, requestedOutputRateHz));
		int decimation = Math.max(1, inputRateHz / outputTarget);
		double outputRateHz = inputRateHz / (double) decimation;
		/* With no rate reduction the source has already selected the channel
		 * bandwidth.  Mixing is still required, but another very sharp FIR would
		 * add a long transient without providing anti-alias protection. */
		double[] taps = decimation == 1 ? new double[] { 1d }
				: designFilter(inputRateHz, outputRateHz, occupiedBandwidthHz);
		int tailSamples = taps.length / 2;
		int maximumOutputSamples = (inputSamples + tailSamples + decimation - 1) / decimation + 1;
		float[] output = new float[maximumOutputSamples * 2];
		double[] historyI = new double[taps.length];
		double[] historyQ = new double[taps.length];
		int historyIndex = 0;
		int phase = decimation - 1;
		int written = 0;

		double angle = -2d * Math.PI * offsetHz / inputRateHz;
		double stepCos = Math.cos(angle), stepSin = Math.sin(angle);
		double oscillatorCos = 1d, oscillatorSin = 0d;
		for (int sample = 0; sample < inputSamples + tailSamples; sample++) {
			double sourceI = 0d, sourceQ = 0d;
			if (sample < inputSamples) {
				sourceI = input[sample * 2] / 128d;
				sourceQ = input[sample * 2 + 1] / 128d;
			}
			double mixedI = sourceI * oscillatorCos - sourceQ * oscillatorSin;
			double mixedQ = sourceI * oscillatorSin + sourceQ * oscillatorCos;
			historyI[historyIndex] = mixedI;
			historyQ[historyIndex] = mixedQ;
			historyIndex++;
			if (historyIndex == taps.length) historyIndex = 0;

			double nextCos = oscillatorCos * stepCos - oscillatorSin * stepSin;
			oscillatorSin = oscillatorSin * stepCos + oscillatorCos * stepSin;
			oscillatorCos = nextCos;
			if ((sample & 0x3fff) == 0) {
				double magnitude = Math.sqrt(oscillatorCos * oscillatorCos
						+ oscillatorSin * oscillatorSin);
				if (magnitude > 0d) { oscillatorCos /= magnitude; oscillatorSin /= magnitude; }
			}

			if (++phase < decimation) continue;
			phase = 0;
			double filteredI = 0d, filteredQ = 0d;
			int first = historyIndex;
			int last = first + taps.length - 1;
			if (last >= taps.length) last -= taps.length;
			for (int tap = 0; tap < taps.length / 2; tap++) {
				filteredI += (historyI[first] + historyI[last]) * taps[tap];
				filteredQ += (historyQ[first] + historyQ[last]) * taps[tap];
				if (++first == taps.length) first = 0;
				if (--last < 0) last = taps.length - 1;
			}
			filteredI += historyI[first] * taps[taps.length / 2];
			filteredQ += historyQ[first] * taps[taps.length / 2];
			output[written * 2] = (float) filteredI;
			output[written * 2 + 1] = (float) filteredQ;
			written++;
		}
		if (written * 2 != output.length) {
			float[] exact = new float[written * 2];
			System.arraycopy(output, 0, exact, 0, exact.length);
			output = exact;
		}
		return new Result(output, written, outputRateHz, decimation, taps.length);
	}

	private static double[] designFilter(int inputRateHz, double outputRateHz,
			int occupiedBandwidthHz) {
		double nyquist = outputRateHz / 2d;
		double passEdge = Math.min(Math.max(1d, occupiedBandwidthHz / 2d), nyquist * .72d);
		double stopEdge = nyquist * .94d;
		if (stopEdge <= passEdge) passEdge = stopEdge * .75d;
		double transitionHz = Math.max(1d, stopEdge - passEdge);
		double transitionRadians = 2d * Math.PI * transitionHz / inputRateHz;
		int order = (int) Math.ceil((60d - 8d) / (2.285d * transitionRadians));
		int count = Math.max(MIN_TAPS, Math.min(MAX_TAPS, order + 1));
		if ((count & 1) == 0) count++;
		if (count > MAX_TAPS) count = MAX_TAPS;
		double cutoff = (passEdge + stopEdge) / (2d * inputRateHz);
		double[] taps = new double[count];
		double denominator = besselI0(KAISER_BETA);
		double sum = 0d;
		int middle = count / 2;
		for (int tap = 0; tap < count; tap++) {
			int n = tap - middle;
			double sinc = n == 0 ? 2d * cutoff
					: Math.sin(2d * Math.PI * cutoff * n) / (Math.PI * n);
			double ratio = n / (double) middle;
			double window = besselI0(KAISER_BETA * Math.sqrt(Math.max(0d, 1d - ratio * ratio)))
					/ denominator;
			taps[tap] = sinc * window;
			sum += taps[tap];
		}
		for (int tap = 0; tap < taps.length; tap++) taps[tap] /= sum;
		return taps;
	}

	private static double besselI0(double value) {
		double sum = 1d, term = 1d;
		double quarter = value * value / 4d;
		for (int n = 1; n < 30; n++) {
			term *= quarter / (n * (double) n);
			sum += term;
			if (term < sum * 1e-15d) break;
		}
		return sum;
	}

	static final class Result {
		final float[] iq;
		final int samples;
		final double sampleRateHz;
		final int decimation, filterTaps;

		Result(float[] iq, int samples, double sampleRateHz, int decimation, int filterTaps) {
			this.iq=iq; this.samples=samples; this.sampleRateHz=sampleRateHz;
			this.decimation=decimation; this.filterTaps=filterTaps;
		}

		private static Result empty(double sampleRateHz) {
			return new Result(new float[0], 0, sampleRateHz, 1, 1);
		}
	}
}
