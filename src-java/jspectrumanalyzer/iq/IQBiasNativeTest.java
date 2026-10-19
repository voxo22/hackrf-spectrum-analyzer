package jspectrumanalyzer.iq;

/** Loads the built native bridge and checks both entry points without using hardware. */
public final class IQBiasNativeTest {
	public static void main(String[] args) {
		HackRFIQDataCallback callback = (center, rate, iq) -> { };
		for (Boolean bias : new Boolean[] { null, Boolean.FALSE, Boolean.TRUE }) {
			int result = HackRFIQNativeBridge.start(callback, 0L, 2_600_000, 0, 16, 20, false, bias);
			if (result != -2) throw new AssertionError("Unexpected native result with BiasT " + bias + ": " + result);
		}
		System.out.println("IQ native legacy and BiasT entry points load correctly");
	}
}
