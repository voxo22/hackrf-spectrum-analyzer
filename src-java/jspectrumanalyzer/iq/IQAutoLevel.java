package jspectrumanalyzer.iq;

public class IQAutoLevel {
	private static final double TARGET_RMS = 58d;
	private static final double MIN_GAIN = 0.15d;
	private static final double MAX_GAIN = 24d;
	private static final double CENTER_SMOOTHING = 0.04d;

	private double gain = 1d;
	private double centerI = 0d;
	private double centerQ = 0d;
	private boolean centerInitialized = false;

	public synchronized void reset() {
		gain = 1d;
		centerI = 0d;
		centerQ = 0d;
		centerInitialized = false;
	}

	public synchronized void process(byte[] iqData, int length) {
		if (iqData == null || length < 2) {
			return;
		}
		int samples = length / 2;
		double sumI = 0d;
		double sumQ = 0d;
		for (int sample = 0; sample < samples; sample++) {
			sumI += iqData[sample * 2];
			sumQ += iqData[sample * 2 + 1];
		}
		double blockCenterI = sumI / Math.max(1, samples);
		double blockCenterQ = sumQ / Math.max(1, samples);
		if (!centerInitialized) {
			centerI = blockCenterI;
			centerQ = blockCenterQ;
			centerInitialized = true;
		} else {
			centerI = centerI * (1d - CENTER_SMOOTHING) + blockCenterI * CENTER_SMOOTHING;
			centerQ = centerQ * (1d - CENTER_SMOOTHING) + blockCenterQ * CENTER_SMOOTHING;
		}

		double sum = 0;
		for (int sample = 0; sample < samples; sample++) {
			double i = iqData[sample * 2] - centerI;
			double q = iqData[sample * 2 + 1] - centerQ;
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
			double center = (i & 1) == 0 ? centerI : centerQ;
			iqData[i] = clamp((iqData[i] - center) * gain);
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
