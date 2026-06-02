package jspectrumanalyzer.iq;

import com.sun.jna.CallbackThreadInitializer;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;

import hackrfiq.HackRFIQLibrary;
import hackrfiq.HackRFIQLibrary.hackrf_iq_callback;

public class HackRFIQNativeBridge {
	public static final String JNA_LIBRARY_NAME = "hackrf-iq";
	public static final NativeLibrary JNA_NATIVE_LIB;

	static {
		String pathPrefix = "./" + Platform.RESOURCE_PREFIX + "/";
		System.setProperty("jna.boot.library.path", pathPrefix);
		System.setProperty("jna.nosys", "true");

		NativeLibrary.addSearchPath(JNA_LIBRARY_NAME, pathPrefix);
		JNA_NATIVE_LIB = NativeLibrary.getInstance(JNA_LIBRARY_NAME);
		Native.register(HackRFIQLibrary.class, JNA_NATIVE_LIB);
	}

	public static synchronized int start(HackRFIQDataCallback dataCallback, long centerFreqHz, int sampleRateHz,
			int basebandFilterHz, int lnaGain, int vgaGain, boolean ampEnable) {
		hackrf_iq_callback callback = new hackrf_iq_callback() {
			@Override
			public void apply(long callbackCenterFreqHz, int callbackSampleRateHz, int validLength, Pointer iqData) {
				if (validLength <= 0 || iqData == null) {
					return;
				}
				byte[] copy = iqData.getByteArray(0, validLength);
				dataCallback.newIQData(callbackCenterFreqHz, callbackSampleRateHz, copy);
			}
		};
		Native.setCallbackThreadInitializer(callback, new CallbackThreadInitializer(true));

		return HackRFIQLibrary.hackrf_iq_lib_start(callback, centerFreqHz, sampleRateHz, basebandFilterHz, lnaGain,
				vgaGain, ampEnable ? 1 : 0);
	}

	public static void stop() {
		HackRFIQLibrary.hackrf_iq_lib_stop();
	}
}
