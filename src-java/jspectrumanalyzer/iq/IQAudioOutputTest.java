package jspectrumanalyzer.iq;

public final class IQAudioOutputTest {
	private IQAudioOutputTest() {
	}

	public static void main(String[] args) {
		double fullScale = Math.sqrt(2d * 128d * 128d);
		assertClose(46d / fullScale, IQAudioOutput.demodulateAmSample(102d / fullScale, 56d / fullScale),
				1e-12, "AM preserves envelope level");
		assertClose(0.5d, IQAudioOutput.demodulateFmSample(100, 0, 0, 100, 1),
				1e-12, "FM natural phase deviation");
		assertClose(0.1d, IQAudioOutput.demodulateFmSample(100, 0, 0, 100, 5),
				1e-12, "FM internal stride compensation");
		assertClose(0d, IQAudioOutput.volumeGainForPercent(0), 0d, "mute");
		assertClose(0.1d, IQAudioOutput.volumeGainForPercent(75), 1e-12, "attenuated volume");
		assertClose(1d, IQAudioOutput.volumeGainForPercent(150), 1e-12, "unity volume");
		assertClose(Math.sqrt(2d), IQAudioOutput.volumeGainForPercent(175), 1e-12, "boosted volume");
		assertClose(2d, IQAudioOutput.volumeGainForPercent(200), 1e-12, "200% volume");
		assertClose(-20d, IQAudioOutput.volumeDbForPercent(75), 1e-12, "attenuated volume dB");
		assertClose(0d, IQAudioOutput.volumeDbForPercent(150), 1e-12, "unity volume dB");
		assertClose(20d * Math.log10(2d), IQAudioOutput.volumeDbForPercent(200),
				1e-12, "200% volume dB");
		assertEquals("Mute", IQAudioOutput.formatVolumeDb(0), "mute tooltip");
		assertEquals("-8 dB", IQAudioOutput.formatVolumeDb(120), "attenuation tooltip");
		assertEquals("0 dB", IQAudioOutput.formatVolumeDb(150), "unity tooltip");
		assertEquals("+6 dB", IQAudioOutput.formatVolumeDb(200), "boost tooltip");
		System.out.println("IQ audio output tests passed");
	}

	private static void assertEquals(String expected, String actual, String message) {
		if (!expected.equals(actual)) {
			throw new AssertionError(message + ": expected " + expected + ", got " + actual);
		}
	}

	private static void assertClose(double expected, double actual, double tolerance, String message) {
		if (Math.abs(expected - actual) > tolerance) {
			throw new AssertionError(message + ": expected " + expected + ", got " + actual);
		}
	}
}
