package jspectrumanalyzer.iq;

public class IQChannelProcessor implements IQSampleProcessor {
	private static final int TAPS = 65;

	private final float[] taps = new float[TAPS];
	private final float[] historyI = new float[TAPS * 2];
	private final float[] historyQ = new float[TAPS * 2];
	private int sampleRateHz;
	private int outputRateHz;
	private int bandwidthHz;
	private double offsetHz;
	private int decimation = 1;
	private double oscillatorCos = 1;
	private double oscillatorSin = 0;
	private int historyIndex = 0;
	private int historyFilled = 0;
	private int decimationCounter = 0;

	public IQChannelProcessor(int sampleRateHz, double offsetHz, int bandwidthHz, int outputRateHz) {
		configure(sampleRateHz, offsetHz, bandwidthHz, outputRateHz);
	}

	public synchronized void configure(int sampleRateHz, double offsetHz, int bandwidthHz, int outputRateHz) {
		this.sampleRateHz = Math.max(1, sampleRateHz);
		this.offsetHz = offsetHz;
		this.bandwidthHz = Math.max(1, bandwidthHz);
		this.outputRateHz = Math.max(1, outputRateHz);
		this.decimation = Math.max(1, Math.round(this.sampleRateHz / (float) this.outputRateHz));
		designLowPass();
		resetState();
	}

	public synchronized int process(byte[] input, int inputBytes, byte[] output) {
		if (input == null || output == null || inputBytes < 2) {
			return 0;
		}

		int inputSamples = inputBytes / 2;
		int outputIndex = 0;
		double phaseStep = -2d * Math.PI * offsetHz / sampleRateHz;
		double stepCos = Math.cos(phaseStep);
		double stepSin = Math.sin(phaseStep);
		double oscCos = oscillatorCos;
		double oscSin = oscillatorSin;
		boolean mixRequired = offsetHz != 0;
		int maxOutputSamples = output.length / 2;

		for (int sample = 0; sample < inputSamples; sample++) {
			float i = input[sample * 2] * (1f / 128f);
			float q = input[sample * 2 + 1] * (1f / 128f);
			float mixedI;
			float mixedQ;
			if (mixRequired) {
				mixedI = (float) (i * oscCos - q * oscSin);
				mixedQ = (float) (i * oscSin + q * oscCos);
			} else {
				mixedI = i;
				mixedQ = q;
			}
			historyI[historyIndex] = mixedI;
			historyI[historyIndex + TAPS] = mixedI;
			historyQ[historyIndex] = mixedQ;
			historyQ[historyIndex + TAPS] = mixedQ;
			historyIndex++;
			if (historyIndex == TAPS) {
				historyIndex = 0;
			}
			if (historyFilled < TAPS) {
				historyFilled++;
			}

			if (mixRequired) {
				double nextCos = oscCos * stepCos - oscSin * stepSin;
				oscSin = oscSin * stepCos + oscCos * stepSin;
				oscCos = nextCos;
				if ((sample & 0x0fff) == 0) {
					double norm = Math.sqrt(oscCos * oscCos + oscSin * oscSin);
					if (norm > 0) {
						oscCos /= norm;
						oscSin /= norm;
					}
				}
			}

			if (historyFilled < TAPS) {
				continue;
			}
			if (decimationCounter > 0) {
				decimationCounter--;
				continue;
			}
			decimationCounter = decimation - 1;
			if (outputIndex >= maxOutputSamples) {
				break;
			}

			double accI = 0;
			double accQ = 0;
			for (int tap = 0; tap < TAPS / 2; tap++) {
				int first = historyIndex + tap;
				int last = historyIndex + TAPS - 1 - tap;
				accI += (historyI[first] + historyI[last]) * taps[tap];
				accQ += (historyQ[first] + historyQ[last]) * taps[tap];
			}
			int middle = historyIndex + TAPS / 2;
			accI += historyI[middle] * taps[TAPS / 2];
			accQ += historyQ[middle] * taps[TAPS / 2];
			output[outputIndex * 2] = clampToByte(accI * 128d);
			output[outputIndex * 2 + 1] = clampToByte(accQ * 128d);
			outputIndex++;
		}

		oscillatorCos = oscCos;
		oscillatorSin = oscSin;
		return outputIndex * 2;
	}

	public synchronized int getActualOutputRateHz() {
		return sampleRateHz / decimation;
	}

	public synchronized int getDecimation() {
		return decimation;
	}

	private void designLowPass() {
		double cutoffHz = Math.min(bandwidthHz / 2d, sampleRateHz / 2d * 0.9d);
		double normalizedCutoff = cutoffHz / sampleRateHz;
		double sum = 0;
		int middle = TAPS / 2;
		for (int i = 0; i < TAPS; i++) {
			int n = i - middle;
			double sinc = n == 0 ? 2d * normalizedCutoff
					: Math.sin(2d * Math.PI * normalizedCutoff * n) / (Math.PI * n);
			double window = 0.54d - 0.46d * Math.cos(2d * Math.PI * i / (TAPS - 1));
			taps[i] = (float) (sinc * window);
			sum += taps[i];
		}
		if (sum == 0) {
			return;
		}
		for (int i = 0; i < TAPS; i++) {
			taps[i] /= sum;
		}
	}

	private void resetState() {
		for (int i = 0; i < historyI.length; i++) {
			historyI[i] = 0;
			historyQ[i] = 0;
		}
		historyIndex = 0;
		historyFilled = 0;
		decimationCounter = 0;
		oscillatorCos = 1;
		oscillatorSin = 0;
	}

	private byte clampToByte(double value) {
		if (value > 127) {
			return 127;
		}
		if (value < -128) {
			return -128;
		}
		return (byte) Math.round(value);
	}
}
