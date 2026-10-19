package jspectrumanalyzer.iq;

/** Old libraries keep the original entry point but must reject the new handoff. */
public final class IQBiasLegacyNativeTest {
	public static void main(String[] args) {
		HackRFIQDataCallback callback = (center, rate, iq) -> { };
		if (HackRFIQNativeBridge.start(callback, 0L, 2_600_000, 0, 16, 20, false) != -2)
			throw new AssertionError("Legacy entry point stopped working");
		try {
			HackRFIQNativeBridge.start(callback, 0L, 2_600_000, 0, 16, 20, false, Boolean.TRUE);
			throw new AssertionError("Outdated library silently accepted BiasT");
		} catch (UnsatisfiedLinkError expected) {
			System.out.println("Outdated IQ library rejects BiasT without affecting the legacy entry point");
		}
	}
}
