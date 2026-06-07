package jspectrumanalyzer.iq;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;

public final class IQFftChannelProcessor implements IQSampleProcessor, AutoCloseable {
	private interface FftwLibrary extends Library {
		Pointer fftwf_plan_dft_1d(int size, Pointer input, Pointer output, int sign, int flags);

		void fftwf_execute(Pointer plan);

		void fftwf_destroy_plan(Pointer plan);
	}

	private static final int FFT_SIZE = 65_536;
	private static final int TAPS = 65;
	private static final int OVERLAP = TAPS - 1;
	private static final int NEW_SAMPLES_PER_BLOCK = FFT_SIZE - OVERLAP;
	private static final int FFTW_FORWARD = -1;
	private static final int FFTW_BACKWARD = 1;
	private static final int FFTW_ESTIMATE = 1 << 6;
	private static final float INPUT_SCALE = 1f / 128f;
	private static final FftwLibrary FFTW = loadFftw();

	private final int sampleRateHz;
	private final int bandwidthHz;
	private final int decimation;
	private final double offsetHz;
	private final Memory fftBuffer = new Memory(FFT_SIZE * 2L * 4L);
	private final float[] block = new float[FFT_SIZE * 2];
	private final float[] spectrum = new float[FFT_SIZE * 2];
	private final float[] filterSpectrum = new float[FFT_SIZE * 2];
	private final float[] overlap = new float[OVERLAP * 2];
	private final Pointer forwardPlan;
	private final Pointer inversePlan;
	private int newSamplesInBlock;
	private int warmupRemaining = OVERLAP;
	private int decimationCounter;
	private double oscillatorCos = 1d;
	private double oscillatorSin;
	private boolean closed;

	public static boolean isAvailable() {
		return FFTW != null;
	}

	public IQFftChannelProcessor(int sampleRateHz, double offsetHz, int bandwidthHz, int outputRateHz) {
		if (FFTW == null) {
			throw new IllegalStateException("FFTW is not available");
		}
		this.sampleRateHz = Math.max(1, sampleRateHz);
		this.offsetHz = offsetHz;
		this.bandwidthHz = Math.max(1, bandwidthHz);
		this.decimation = Math.max(1, Math.round(this.sampleRateHz / (float) Math.max(1, outputRateHz)));
		this.forwardPlan = FFTW.fftwf_plan_dft_1d(FFT_SIZE, fftBuffer, fftBuffer, FFTW_FORWARD, FFTW_ESTIMATE);
		this.inversePlan = FFTW.fftwf_plan_dft_1d(FFT_SIZE, fftBuffer, fftBuffer, FFTW_BACKWARD, FFTW_ESTIMATE);
		if (forwardPlan == null || inversePlan == null) {
			close();
			throw new IllegalStateException("Cannot create FFTW plans");
		}
		designFilter();
	}

	@Override
	public synchronized int process(byte[] input, int inputBytes, byte[] output) {
		if (closed || input == null || output == null || inputBytes < 2) {
			return 0;
		}
		int inputSamples = inputBytes / 2;
		int inputSample = 0;
		int outputSamples = 0;
		int maximumOutputSamples = output.length / 2;
		double phaseStep = -2d * Math.PI * offsetHz / sampleRateHz;
		double stepCos = Math.cos(phaseStep);
		double stepSin = Math.sin(phaseStep);
		boolean mixRequired = offsetHz != 0d;

		while (inputSample < inputSamples) {
			if (newSamplesInBlock == 0) {
				System.arraycopy(overlap, 0, block, 0, overlap.length);
			}
			int copySamples = Math.min(inputSamples - inputSample, NEW_SAMPLES_PER_BLOCK - newSamplesInBlock);
			int destinationSample = OVERLAP + newSamplesInBlock;
			for (int i = 0; i < copySamples; i++) {
				float sourceI = input[(inputSample + i) * 2] * INPUT_SCALE;
				float sourceQ = input[(inputSample + i) * 2 + 1] * INPUT_SCALE;
				float mixedI = sourceI;
				float mixedQ = sourceQ;
				if (mixRequired) {
					mixedI = (float) (sourceI * oscillatorCos - sourceQ * oscillatorSin);
					mixedQ = (float) (sourceI * oscillatorSin + sourceQ * oscillatorCos);
					double nextCos = oscillatorCos * stepCos - oscillatorSin * stepSin;
					oscillatorSin = oscillatorSin * stepCos + oscillatorCos * stepSin;
					oscillatorCos = nextCos;
				}
				int destination = (destinationSample + i) * 2;
				block[destination] = mixedI;
				block[destination + 1] = mixedQ;
			}
			if (mixRequired) {
				normalizeOscillator();
			}
			inputSample += copySamples;
			newSamplesInBlock += copySamples;
			if (newSamplesInBlock < NEW_SAMPLES_PER_BLOCK) {
				continue;
			}

			System.arraycopy(block, (FFT_SIZE - OVERLAP) * 2, overlap, 0, overlap.length);
			int remainingCapacity = maximumOutputSamples - outputSamples;
			int written = processFullBlock(output, outputSamples, remainingCapacity);
			outputSamples += written;
			newSamplesInBlock = 0;
		}
		return outputSamples * 2;
	}

	@Override
	public int getActualOutputRateHz() {
		return sampleRateHz / decimation;
	}

	@Override
	public int getDecimation() {
		return decimation;
	}

	@Override
	public synchronized void close() {
		if (closed) {
			return;
		}
		closed = true;
		if (FFTW != null) {
			if (forwardPlan != null) {
				FFTW.fftwf_destroy_plan(forwardPlan);
			}
			if (inversePlan != null) {
				FFTW.fftwf_destroy_plan(inversePlan);
			}
		}
	}

	private int processFullBlock(byte[] output, int outputOffsetSamples, int maximumOutputSamples) {
		fftBuffer.write(0, block, 0, block.length);
		FFTW.fftwf_execute(forwardPlan);
		fftBuffer.read(0, spectrum, 0, spectrum.length);
		for (int bin = 0; bin < FFT_SIZE; bin++) {
			int index = bin * 2;
			float real = spectrum[index];
			float imaginary = spectrum[index + 1];
			float filterReal = filterSpectrum[index];
			float filterImaginary = filterSpectrum[index + 1];
			spectrum[index] = real * filterReal - imaginary * filterImaginary;
			spectrum[index + 1] = real * filterImaginary + imaginary * filterReal;
		}
		fftBuffer.write(0, spectrum, 0, spectrum.length);
		FFTW.fftwf_execute(inversePlan);
		fftBuffer.read(0, block, 0, block.length);

		float scale = 128f / FFT_SIZE;
		int written = 0;
		for (int sample = OVERLAP; sample < FFT_SIZE; sample++) {
			if (warmupRemaining > 0) {
				warmupRemaining--;
				continue;
			}
			if (decimationCounter > 0) {
				decimationCounter--;
				continue;
			}
			decimationCounter = decimation - 1;
			if (written >= maximumOutputSamples) {
				break;
			}
			int source = sample * 2;
			int destination = (outputOffsetSamples + written) * 2;
			output[destination] = clampToByte(block[source] * scale);
			output[destination + 1] = clampToByte(block[source + 1] * scale);
			written++;
		}
		return written;
	}

	private void designFilter() {
		float[] taps = new float[TAPS];
		double cutoffHz = Math.min(bandwidthHz / 2d, sampleRateHz / 2d * 0.9d);
		double normalizedCutoff = cutoffHz / sampleRateHz;
		double sum = 0d;
		int middle = TAPS / 2;
		for (int i = 0; i < TAPS; i++) {
			int n = i - middle;
			double sinc = n == 0 ? 2d * normalizedCutoff
					: Math.sin(2d * Math.PI * normalizedCutoff * n) / (Math.PI * n);
			double window = 0.54d - 0.46d * Math.cos(2d * Math.PI * i / (TAPS - 1));
			taps[i] = (float) (sinc * window);
			sum += taps[i];
		}
		for (int i = 0; i < TAPS; i++) {
			block[i * 2] = (float) (taps[i] / sum);
			block[i * 2 + 1] = 0f;
		}
		fftBuffer.write(0, block, 0, block.length);
		FFTW.fftwf_execute(forwardPlan);
		fftBuffer.read(0, filterSpectrum, 0, filterSpectrum.length);
		for (int i = 0; i < block.length; i++) {
			block[i] = 0f;
		}
	}

	private void normalizeOscillator() {
		double norm = Math.sqrt(oscillatorCos * oscillatorCos + oscillatorSin * oscillatorSin);
		if (norm > 0d) {
			oscillatorCos /= norm;
			oscillatorSin /= norm;
		}
	}

	private static byte clampToByte(float value) {
		if (value > 127f) {
			return 127;
		}
		if (value < -128f) {
			return -128;
		}
		return (byte) Math.round(value);
	}

	private static FftwLibrary loadFftw() {
		String pathPrefix = "./" + Platform.RESOURCE_PREFIX + "/";
		String[] names = Platform.isWindows()
				? new String[] { pathPrefix + "libfftw3f-3.dll", "fftw3f-3", "libfftw3f-3" }
				: new String[] { "fftw3f", "libfftw3f" };
		for (String name : names) {
			try {
				NativeLibrary.addSearchPath(name, pathPrefix);
				return (FftwLibrary) Native.loadLibrary(name, FftwLibrary.class);
			} catch (Throwable ignored) {
			}
		}
		return null;
	}
}
