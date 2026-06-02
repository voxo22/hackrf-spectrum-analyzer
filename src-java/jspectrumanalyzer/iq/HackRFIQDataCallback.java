package jspectrumanalyzer.iq;

public interface HackRFIQDataCallback {
	/**
	 * Called with interleaved signed 8-bit IQ bytes: I0, Q0, I1, Q1, ...
	 */
	void newIQData(long centerFreqHz, int sampleRateHz, byte[] iqData);
}
