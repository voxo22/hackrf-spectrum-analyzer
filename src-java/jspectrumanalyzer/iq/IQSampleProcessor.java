package jspectrumanalyzer.iq;

public interface IQSampleProcessor {
	int process(byte[] input, int inputBytes, byte[] output);

	int getActualOutputRateHz();

	int getDecimation();
}
