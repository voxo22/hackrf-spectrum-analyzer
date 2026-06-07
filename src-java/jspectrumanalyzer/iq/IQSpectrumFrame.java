package jspectrumanalyzer.iq;

public final class IQSpectrumFrame {
	private final double[] real;
	private final double[] imaginary;

	public IQSpectrumFrame(int fftSize) {
		if (Integer.bitCount(fftSize) != 1) {
			throw new IllegalArgumentException("FFT size must be a power of two");
		}
		real = new double[fftSize];
		imaginary = new double[fftSize];
	}

	public int size() {
		return real.length;
	}

	public float[] compute(byte[] signedIq, int offsetBytes) {
		int size = real.length;
		if (signedIq == null || offsetBytes < 0 || offsetBytes + size * 2 > signedIq.length) {
			throw new IllegalArgumentException("Not enough IQ samples for FFT");
		}
		for (int i = 0; i < size; i++) {
			double window = 0.5d - 0.5d * Math.cos(2d * Math.PI * i / (size - 1));
			real[i] = signedIq[offsetBytes + i * 2] / 128d * window;
			imaginary[i] = signedIq[offsetBytes + i * 2 + 1] / 128d * window;
		}
		fft(real, imaginary);
		float[] spectrum = new float[size];
		double scale = 2d / size;
		for (int i = 0; i < size; i++) {
			int shifted = (i + size / 2) % size;
			double magnitude = Math.hypot(real[shifted], imaginary[shifted]) * scale;
			spectrum[i] = (float) (20d * Math.log10(magnitude + 1e-12));
		}
		return spectrum;
	}

	public static int chooseSize(int sampleRateHz, int requestedBinHz) {
		double target = sampleRateHz / (double) Math.max(1, requestedBinHz);
		int size = 32;
		while (size < target && size < 262144) {
			size <<= 1;
		}
		return size;
	}

	private static void fft(double[] real, double[] imaginary) {
		int size = real.length;
		for (int i = 1, j = 0; i < size; i++) {
			int bit = size >> 1;
			for (; (j & bit) != 0; bit >>= 1) {
				j ^= bit;
			}
			j ^= bit;
			if (i < j) {
				double realValue = real[i];
				real[i] = real[j];
				real[j] = realValue;
				double imaginaryValue = imaginary[i];
				imaginary[i] = imaginary[j];
				imaginary[j] = imaginaryValue;
			}
		}
		for (int length = 2; length <= size; length <<= 1) {
			double angle = -2d * Math.PI / length;
			double stepReal = Math.cos(angle);
			double stepImaginary = Math.sin(angle);
			for (int start = 0; start < size; start += length) {
				double phaseReal = 1d;
				double phaseImaginary = 0d;
				for (int i = 0; i < length / 2; i++) {
					int even = start + i;
					int odd = even + length / 2;
					double oddReal = real[odd] * phaseReal - imaginary[odd] * phaseImaginary;
					double oddImaginary = real[odd] * phaseImaginary + imaginary[odd] * phaseReal;
					real[odd] = real[even] - oddReal;
					imaginary[odd] = imaginary[even] - oddImaginary;
					real[even] += oddReal;
					imaginary[even] += oddImaginary;
					double nextPhaseReal = phaseReal * stepReal - phaseImaginary * stepImaginary;
					phaseImaginary = phaseReal * stepImaginary + phaseImaginary * stepReal;
					phaseReal = nextPhaseReal;
				}
			}
		}
	}
}
