package jspectrumanalyzer.iq;

import java.io.IOException;

public interface Pcm16AudioSink {
	void writePcm16(byte[] data, int offset, int length) throws IOException;
}
