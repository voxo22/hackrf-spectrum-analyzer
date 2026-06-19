package jspectrumanalyzer.iq;

public interface HackRFIQTxDataProvider {
	public int fillTxBuffer(byte[] output, int requestedLength) throws InterruptedException;
}
