package jspectrumanalyzer.core;

import java.math.BigDecimal;

import shared.mvc.ModelValue;
import shared.mvc.ModelValue.ModelValueBoolean;
import shared.mvc.ModelValue.ModelValueInt;

public interface HackRFSettings {
	public static abstract class HackRFEventAdapter implements HackRFEventListener {
		@Override
		public void captureStateChanged(boolean isCapturing) {

		}

		@Override
		public void hardwareStatusChanged(boolean hardwareSendingData) {

		}
	}

	public static interface HackRFEventListener {
		public void captureStateChanged(boolean isCapturing);

		public void hardwareStatusChanged(boolean hardwareSendingData);
	}

	public ModelValueBoolean getAntennaPowerEnable();

	public ModelValueInt getFFTBinHz();

	public ModelValue<FrequencyRange> getFrequency();

	public ModelValueInt getGain();

	public ModelValueInt getGainLNA();
	
	public ModelValueBoolean getAntennaLNA();
	
	public ModelValueInt getPersistentDisplayDecayRate();
	
	public ModelValueBoolean isDebugDisplay();

	public ModelValueInt getSamples();
	
	public ModelValueInt getFreqShift();

	public ModelValueInt getSpectrumPaletteSize();
	
	public ModelValueInt getAmplitudeOffset();
	
	public ModelValueInt getWaterfallSpeed();
	
	public ModelValueBoolean isPersistentDisplayVisible();
	public ModelValueBoolean isWaterfallVisible();
	
	public ModelValueBoolean isDatestampVisible();

	public ModelValueInt getSpectrumPaletteStart();
	
	public ModelValueInt getPeakFallRate();
	
	public ModelValueInt getPeakFallTrs();
	
	public ModelValueInt getAvgIterations();
	
	public ModelValueInt getAvgOffset();
	
	public ModelValue<FrequencyAllocationTable> getFrequencyAllocationTable();

	public ModelValue<BigDecimal> getSpectrumLineThickness();
	
	public ModelValueInt getGainVGA();

	public ModelValueBoolean isCapturingPaused();
	
	public ModelValueBoolean isRecorded();
	
	public ModelValueBoolean isRecordedWF();
	
	public ModelValueBoolean isChartsRealtimeVisible();
	
	public ModelValueBoolean isChartsAverageVisible();

	public ModelValueBoolean isChartsPeaksVisible();
	
	public ModelValueBoolean isChartsMaxHoldVisible();

	public ModelValueBoolean isFilterSpectrum();

	public ModelValueBoolean isSpurRemoval();

	public void registerListener(HackRFEventListener listener);

	public void removeListener(HackRFEventListener listener);
}
