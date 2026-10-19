package jspectrumanalyzer.iq;

import com.sun.jna.NativeLibrary;

/** End-to-end Java-to-C BiasT routing using the native fake hardware bridge. */
public final class IQBiasMockNativeTest {
	public static void main(String[] args) {
		HackRFIQDataCallback callback = (center, rate, iq) -> { };
		NativeLibrary library = HackRFIQNativeBridge.JNA_NATIVE_LIB;
		check(HackRFIQNativeBridge.start(callback, 100_000_000L, 2_600_000, 0, 16, 20, false, Boolean.TRUE) == 0, "start with BiasT on");
		check(count(library, "mock_bias_calls") == 1 && count(library, "mock_bias_value") == 1, "on passed through Java and C");
		check(HackRFIQNativeBridge.start(callback, 100_000_000L, 2_600_000, 0, 16, 20, false, Boolean.FALSE) == 0, "start with BiasT off");
		check(count(library, "mock_bias_calls") == 2 && count(library, "mock_bias_value") == 0, "off passed through Java and C");
		check(HackRFIQNativeBridge.start(callback, 100_000_000L, 2_600_000, 0, 16, 20, false) == 0, "legacy start");
		check(count(library, "mock_bias_calls") == 2 && count(library, "mock_rx_calls") == 3, "legacy leaves BiasT unchanged");
		System.out.println("IQ Java-to-native BiasT routing test passed");
	}
	private static int count(NativeLibrary library, String name) { return library.getFunction(name).invokeInt(new Object[0]); }
	private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
