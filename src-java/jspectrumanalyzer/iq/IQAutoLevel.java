package jspectrumanalyzer.iq;

public class IQAutoLevel {
	private static final double TARGET_RMS = 58d;
	private static final double MIN_GAIN = 0.15d;
	private static final double MAX_GAIN = 24d;

	private double gain = 1d;

	public synchronized void reset() {
		gain = 1d;
	}

	public synchronized void process(byte[] iqData, int length) {
		if (iqData == null || length < 2) {
			return;
		}
		int samples = length / 2;
		double sum = 0;
		for (int sample = 0; sample < samples; sample++) {
			int i = iqData[sample * 2];
			int q = iqData[sample * 2 + 1];
			sum += i * i + q * q;
		}
		double rms = Math.sqrt(sum / Math.max(1, samples));
		if (rms > 0.001d) {
			double wantedGain = TARGET_RMS / rms;
			if (wantedGain < MIN_GAIN) {
				wantedGain = MIN_GAIN;
			} else if (wantedGain > MAX_GAIN) {
				wantedGain = MAX_GAIN;
			}
			gain = gain * 0.92d + wantedGain * 0.08d;
		}

		for (int i = 0; i < length; i++) {
			iqData[i] = clamp(iqData[i] * gain);
		}
	}

	public synchronized double getGain() {
		return gain;
	}

	private byte clamp(double value) {
		if (value > 127) {
			return 127;
		}
		if (value < -128) {
			return -128;
		}
		return (byte) Math.round(value);
	}
}
