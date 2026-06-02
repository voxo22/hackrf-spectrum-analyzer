package hackrfiq;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Pointer;

public class HackRFIQLibrary implements Library {
	public interface hackrf_iq_callback extends Callback {
		void apply(long centerFreqHz, int sampleRateHz, int validLength, Pointer iqData);
	}

	public static native int hackrf_iq_lib_start(
			hackrf_iq_callback callback,
			long centerFreqHz,
			int sampleRateHz,
			int basebandFilterHz,
			int lnaGain,
			int vgaGain,
			int ampEnable);

	public static native void hackrf_iq_lib_stop();
}
