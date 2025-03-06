package jspectrumanalyzer.core;

public class Preset {

	private String presetName;
	private int startFreq;
	private int stopFreq;
	private String fftBinWidth;
	private String freqShift;
	private int amplitudeOffset;
	
	public Preset(String presetName, int startFreq, int stopFreq, String fftBinWidth, String freqShift, int amplitudeOffset) {
		this.presetName = presetName;
		this.startFreq = startFreq;
		this.stopFreq = stopFreq;
		this.fftBinWidth = fftBinWidth;
		this.freqShift = freqShift;
		this.amplitudeOffset = amplitudeOffset;
	}
	
	@Override
	public String toString() {
		return presetName;
	}
	
	public int getStopFreq() {
		return stopFreq - Integer.parseInt(freqShift);
	}

	public int getStartFreq() {
		return startFreq - Integer.parseInt(freqShift);
	}
	
	public String getFFTBinWidth() {
		return fftBinWidth;
	}
	
	public String getFreqShift() {
		return freqShift;
	}
	
	public int getAmplitudeOffset() {
		return amplitudeOffset;
	}
}

