package jspectrumanalyzer.iq;

import com.sun.jna.CallbackThreadInitializer;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;

import hackrfiq.HackRFIQLibrary;
import hackrfiq.HackRFIQLibrary.hackrf_iq_callback;
import hackrfiq.HackRFIQLibrary.hackrf_iq_tx_callback;

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

	public static synchronized int startTx(HackRFIQTxDataProvider dataProvider, long centerFreqHz, int sampleRateHz,
			int basebandFilterHz, int txVgaGain, boolean ampEnable) {
		hackrf_iq_tx_callback callback = new hackrf_iq_tx_callback() {
			private byte[] buffer = new byte[0];

			@Override
			public int apply(int requestedLength, Pointer iqData) {
				try {
					if (requestedLength <= 0 || iqData == null) {
						return -1;
					}
				if (buffer.length < requestedLength) {
					buffer = new byte[requestedLength];
				}
					int copied = dataProvider.fillTxBuffer(buffer, requestedLength);
					if (copied <= 0) {
						return -1;
					}
					iqData.write(0, buffer, 0, Math.min(copied, requestedLength));
					return copied;
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return -1;
				} catch (RuntimeException e) {
					return -1;
				}
			}
		};
		Native.setCallbackThreadInitializer(callback, new CallbackThreadInitializer(true));

		return HackRFIQLibrary.hackrf_iq_lib_start_tx(callback, centerFreqHz, sampleRateHz, basebandFilterHz,
				txVgaGain, ampEnable ? 1 : 0);
	}

	public static void stopTx() {
		HackRFIQLibrary.hackrf_iq_lib_stop_tx();
	}
}
