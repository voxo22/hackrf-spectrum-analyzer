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

	public ModelValueInt getIqReplayRbwHz();

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
	
	public ModelValueInt getPeakHoldTime();

	public ModelValueInt getMarkerCount();
	
	public ModelValueInt getAvgIterations();
	
	public ModelValueInt getAvgOffset();
	
	public ModelValueInt getPowerFluxCal();
	
	public ModelValue<FrequencyAllocationTable> getFrequencyAllocationTable();

	public ModelValue<BigDecimal> getSpectrumLineThickness();

	public ModelValueBoolean isSpectrumSpline();
	
	public ModelValue<String> getLogDetail();
	
	public ModelValue<String> getVideoArea();
	
	public ModelValue<String> getVideoFormat();
	
	public ModelValue<String> getparamFreqRange();

	public ModelValue<String> getDisplayFreqRange();
	
	public ModelValueInt getVideoResolution();
	
	public ModelValueInt getVideoFrameRate();

	public ModelValue<String> getSpectrumRecordFrameRate();
	
	public ModelValueInt getGainVGA();

	public ModelValueBoolean isCapturingPaused();
	
	public ModelValueBoolean isRecordedVideo();
	
	public ModelValueBoolean isRecordedData();

	public ModelValueBoolean isRecordedSpectrum();

	public ModelValueBoolean isPlayingSpectrum();

	public ModelValue<String> getReplayType();

	public ModelValueBoolean isIqReplayAudioEnabled();

	public ModelValueInt getIqReplayAudioVolume();

	public ModelValue<String> getIqReplayAudioMode();

	public ModelValueBoolean isIqReplayBurstCaptureEnabled();

	public ModelValueBoolean isIqReplayTxEnabled();

	public ModelValueInt getIqReplayTxPower();

	public ModelValue<String> getIqReplayTxCenter();

	public ModelValueBoolean isIqReplayTxCustomFrequencyEnabled();
	
	public ModelValueBoolean isChartsRealtimeVisible();
	
	public ModelValueBoolean isChartsAverageVisible();

	public ModelValueBoolean isChartsPeaksVisible();
	
	public ModelValueBoolean isChartsMaxHoldVisible();
	
	public ModelValueBoolean isInfoBoxVisible();
	
	public ModelValueBoolean isPeakMarkerVisible();
	
	public ModelValueBoolean isHoldMarkerVisible();

	public ModelValueBoolean isFilterSpectrum();

	public ModelValueBoolean isSpurRemoval();

	public void showTriggerDialog();


	public void registerListener(HackRFEventListener listener);

	public void removeListener(HackRFEventListener listener);
}
