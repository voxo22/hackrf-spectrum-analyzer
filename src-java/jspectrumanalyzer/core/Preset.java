package jspectrumanalyzer.core;

public class Preset {

	private String presetName;
	private int startFreq;
	private String stopFreq;
	private String fftBinWidth;
	private String freqShift;
	private int amplitudeOffset;
	
	public Preset(String presetName, int startFreq, String stopFreq, String fftBinWidth, String freqShift, int amplitudeOffset) {
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
	
    public int getStartFreq() {
        if (startFreq == -1) {
            String[] ranges = stopFreq.split(",");
            String firstRange = ranges[0];
            String[] parts = firstRange.split("-");
            return Integer.parseInt(parts[0]) - Integer.parseInt(freqShift);
        }
        return startFreq/* - Integer.parseInt(freqShift)*/;
    }

    public int getStopFreq() {
        if (startFreq == -1) {
            String[] ranges = stopFreq.split(",");
            String lastRange = ranges[ranges.length - 1];
            String[] parts = lastRange.split("-");
            return Integer.parseInt(parts[1]) - Integer.parseInt(freqShift);
        }
        return Integer.parseInt(stopFreq) - Integer.parseInt(freqShift);
    }


    public String getFreqRange() {
        if (startFreq == -1) {
            return stopFreq;
        } else {
            int shiftedStart = startFreq - Integer.parseInt(freqShift);
            int shiftedStop = Integer.parseInt(stopFreq) - Integer.parseInt(freqShift);
            return shiftedStart + "-" + shiftedStop;
        }
    }

	/*
	public int getStopFreq() {
		return stopFreq - Integer.parseInt(freqShift);
	}

	public int getStartFreq() {
		return startFreq - Integer.parseInt(freqShift);
	}
	*/
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

