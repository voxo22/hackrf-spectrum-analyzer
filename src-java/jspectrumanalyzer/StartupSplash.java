package jspectrumanalyzer;

import java.awt.GraphicsEnvironment;
import java.awt.SplashScreen;
import java.net.URL;

import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;

/**
 * Shows the application splash when the JVM was not started from a JAR
 * manifest, for example when launched directly from an IDE.
 */
final class StartupSplash {
	private static final String IMAGE_RESOURCE = "/jspectrumanalyzer/resources/hackrf-spectrum-analyzer-splash.png";

	private static JWindow window;

	private StartupSplash() {
	}

	static void show() {
		if (GraphicsEnvironment.isHeadless() || hasNativeSplash())
			return;

		runOnEventDispatchThread(() -> {
			if (window != null)
				return;

			URL imageUrl = StartupSplash.class.getResource(IMAGE_RESOURCE);
			if (imageUrl == null) {
				System.err.println("Splash image resource not found: " + IMAGE_RESOURCE);
				return;
			}

			JWindow splashWindow = new JWindow();
			splashWindow.getContentPane().add(new JLabel(new ImageIcon(imageUrl)));
			splashWindow.setFocusableWindowState(false);
			splashWindow.pack();
			splashWindow.setLocationRelativeTo(null);
			splashWindow.setVisible(true);
			window = splashWindow;
		});
	}

	static void hide() {
		closeNativeSplash();
		runOnEventDispatchThread(() -> {
			if (window != null) {
				window.dispose();
				window = null;
			}
		});
	}

	private static boolean hasNativeSplash() {
		try {
			SplashScreen splash = SplashScreen.getSplashScreen();
			return splash != null && splash.isVisible();
		} catch (RuntimeException e) {
			return false;
		}
	}

	private static void closeNativeSplash() {
		try {
			SplashScreen splash = SplashScreen.getSplashScreen();
			if (splash != null && splash.isVisible())
				splash.close();
		} catch (RuntimeException e) {
			// The platform does not provide native splash-screen support.
		}
	}

	private static void runOnEventDispatchThread(Runnable task) {
		if (SwingUtilities.isEventDispatchThread()) {
			task.run();
			return;
		}
		try {
			SwingUtilities.invokeAndWait(task);
		} catch (Exception e) {
			System.err.println("Unable to update splash screen: " + e.getMessage());
		}
	}
}
