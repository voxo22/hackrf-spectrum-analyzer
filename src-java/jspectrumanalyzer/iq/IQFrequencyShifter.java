package jspectrumanalyzer.iq;

public class IQFrequencyShifter {
	private int sampleRateHz;
	private double offsetHz;
	private double oscillatorCos = 1d;
	private double oscillatorSin = 0d;

	public IQFrequencyShifter(int sampleRateHz, double offsetHz) {
		configure(sampleRateHz, offsetHz);
	}

	public synchronized void configure(int sampleRateHz, double offsetHz) {
		this.sampleRateHz = Math.max(1, sampleRateHz);
		this.offsetHz = offsetHz;
		this.oscillatorCos = 1d;
		this.oscillatorSin = 0d;
	}

	public synchronized int process(byte[] input, int inputBytes, byte[] output) {
		if (input == null || output == null || inputBytes < 2) {
			return 0;
		}
		int bytes = Math.min(inputBytes & ~1, output.length & ~1);
		double phaseStep = -2d * Math.PI * offsetHz / sampleRateHz;
		double stepCos = Math.cos(phaseStep);
		double stepSin = Math.sin(phaseStep);
		double oscCos = oscillatorCos;
		double oscSin = oscillatorSin;

		for (int index = 0; index < bytes; index += 2) {
			double i = input[index] / 128d;
			double q = input[index + 1] / 128d;
			output[index] = clampToByte((i * oscCos - q * oscSin) * 128d);
			output[index + 1] = clampToByte((i * oscSin + q * oscCos) * 128d);

			double nextCos = oscCos * stepCos - oscSin * stepSin;
			oscSin = oscSin * stepCos + oscCos * stepSin;
			oscCos = nextCos;
			if ((index & 0x1fff) == 0) {
				double norm = Math.sqrt(oscCos * oscCos + oscSin * oscSin);
				if (norm > 0d) {
					oscCos /= norm;
					oscSin /= norm;
				}
			}
		}

		oscillatorCos = oscCos;
		oscillatorSin = oscSin;
		return bytes;
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
