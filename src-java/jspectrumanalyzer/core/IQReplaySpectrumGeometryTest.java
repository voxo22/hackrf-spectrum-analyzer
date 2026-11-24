package jspectrumanalyzer.core;

import jspectrumanalyzer.iq.IQSpectrumFrame;
import jspectrumanalyzer.core.jfc.XYSeriesImmutable;

/** Regression checks for narrow IQ replay spectra that cross whole-MHz boundaries. */
public final class IQReplaySpectrumGeometryTest {
	private IQReplaySpectrumGeometryTest() { }

	public static void main(String[] args) {
		testNarrowReplayUsesExactDatasetSize();
		testHighCenterPlotCoordinatesRemainDistinct();
		testWideReplayDoesNotLoseHighBins();
		System.out.println("IQ replay spectrum geometry tests passed");
	}

	private static void testHighCenterPlotCoordinatesRemainDistinct() {
		int sampleRateHz = 25_000;
		int fftSize = 512;
		float binHz = sampleRateHz / (float) fftSize;
		double[] frequencies = IQSpectrumFrame.createFrequencyCenters(2_600_000_000L, sampleRateHz, fftSize);
		DatasetSpectrumPeak spectrum = new DatasetSpectrumPeak(binHz,
				(long) Math.floor(frequencies[0]), fftSize, -120f, 6f, 1_000L, 0L, 0, 4, 0);
		assertStrictlyIncreasing(spectrum.createSpectrumDataset("2600 MHz replay"), "realtime");
		assertStrictlyIncreasing(spectrum.createPeaksDataset("2600 MHz replay peaks"), "peaks");
		assertStrictlyIncreasing(spectrum.createAverageDataset("2600 MHz replay average"), "average");
		assertStrictlyIncreasing(spectrum.createMaxHoldDataset("2600 MHz replay max hold"), "max hold");
		assertStrictlyIncreasing(spectrum.createMinHoldDataset("2600 MHz replay min hold"), "min hold");
	}

	private static void assertStrictlyIncreasing(XYSeriesImmutable plotted, String trace) {
		for (int i = 1; i < plotted.getItemCount(); i++) {
			if (!(plotted.getXX(i) > plotted.getXX(i - 1))) {
				throw new AssertionError(trace + " plot X coordinates must increase at bin " + i + ": "
						+ plotted.getXX(i - 1) + " >= " + plotted.getXX(i));
			}
		}
	}

	private static void testNarrowReplayUsesExactDatasetSize() {
		int sampleRateHz = 25_000;
		int fftSize = IQSpectrumFrame.chooseSize(sampleRateHz, 50);
		assertEquals(512, fftSize, "25 kS/s, 50 Hz FFT size");

		float binHz = sampleRateHz / (float) fftSize;
		double[] frequencies = IQSpectrumFrame.createFrequencyCenters(100_000_000L, sampleRateHz, fftSize);
		double firstBinHz = frequencies[0];
		long datasetStartHz = (long) Math.floor(firstBinHz);
		DatasetSpectrumPeak spectrum = new DatasetSpectrumPeak(binHz, datasetStartHz, fftSize,
				-120f, 6f, 1_000L, 0L, 0, 4, 0);
		assertEquals(512, spectrum.spectrumLength(), "exact replay dataset size");

		float[] powers = new float[fftSize];
		for (int i = 0; i < fftSize; i++) {
			powers[i] = -100f;
		}
		int peakIndex = fftSize / 2;
		powers[peakIndex] = -20f;
		spectrum.addNewData(new FFTBins(true, frequencies, binHz, powers));
		assertNear(-100d, spectrum.getPower(0), .001d, "first replay bin");
		assertNear(-20d, spectrum.getPower(peakIndex), .001d, "center replay bin");
		assertNear(-100d, spectrum.getPower(fftSize - 1), .001d, "last replay bin");

		spectrum.refreshPeakSpectrum();
		DatasetSpectrumPeak.SpectrumMarker[] markers = spectrum.calculatePeakMarkers(1);
		assertEquals(1, markers.length, "replay peak marker count");
		assertNear(spectrum.getFrequency(peakIndex) / 1_000_000d, markers[0].frequencyMHz,
				binHz / 1_000_000d, "replay peak marker frequency");

		// The spur filter must preserve the exact 512-point geometry as well.
		new SpurFilter(6f, 4f, 4, 25, spectrum).filterDataset();
	}

	private static void testWideReplayDoesNotLoseHighBins() {
		int sampleRateHz = 20_000_000;
		int fftSize = IQSpectrumFrame.chooseSize(sampleRateHz, 20_000);
		assertEquals(1024, fftSize, "20 MS/s, 20 kHz FFT size");
		float binHz = sampleRateHz / (float) fftSize;
		double[] frequencies = IQSpectrumFrame.createFrequencyCenters(2_467_750_000L, sampleRateHz, fftSize);
		DatasetSpectrumPeak spectrum = new DatasetSpectrumPeak(binHz,
				(long) Math.floor(frequencies[0]), fftSize, -120f, 6f, 1_000L, 0L, 0, 4, 0);
		float[] powers = new float[fftSize];
		java.util.Arrays.fill(powers, -70f);
		spectrum.addNewData(new FFTBins(true, frequencies, binHz, powers));
		for (int i = 0; i < fftSize; i++) {
			assertNear(-70d, spectrum.getPower(i), .001d, "wide replay bin " + i);
		}
	}

	private static void assertEquals(int expected, int actual, String message) {
		if (expected != actual)
			throw new AssertionError(message + ": expected " + expected + ", got " + actual);
	}

	private static void assertNear(double expected, double actual, double tolerance, String message) {
		if (Math.abs(expected - actual) > tolerance)
			throw new AssertionError(message + ": expected " + expected + ", got " + actual);
	}
}
