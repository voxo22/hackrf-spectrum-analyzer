package jspectrumanalyzer.ui;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class ContextHelpLibrary {
	private static final Map<String, String> TEXTS = createTexts();

	private ContextHelpLibrary() {
	}

	public static String get(String key) {
		if (key == null) {
			return null;
		}
		return TEXTS.get(key);
	}

	private static Map<String, String> createTexts() {
		Map<String, String> texts = new HashMap<>();

		texts.put("spectrum.help",
				"Context help mode. Move the question-mark cursor over a control and wait briefly, or click a control to show help immediately. Press Esc to cancel.");
		texts.put("spectrum.chart",
				"Spectrum chart. Shows signal level versus frequency.\n- Mouse wheel zooms/unzooms around the cursor.\n- Drag with the LEFT mouse button to zoom.\n- Drag with the RIGHT mouse button to select a signal for time-domain analysis (max 20 MHz).\n- Double-click the RIGHT mouse button to select 20 MHz bandwidth around center or full WAV/PCM/CU8/CS8 REPLAY bandwidth for time-domain analysis.\n- Hold CTRL and drag with the LEFT mouse button to measure delta frequency and amplitude.\n- Hold SHIFT and drag with LEFT or RIGHT mouse button to select a signal from its center frequency.\n- Hold ALT while dragging to bypass snap.\n- Drag X-axis with the LEFT mouse button to pan the spectrum.");
		texts.put("spectrum.waterfall",
				"Waterfall display. Shows spectrum history over time, with newer samples scrolling through the image. Palette controls define how signal strength maps to colors. You can change parameters of the waterfall flow in Waterfall/REC tab.\n- In REPLAY mode, there is a green Progress Bar at the top of the waterfall that shows the current replay position, including total duration of recording. You can click anywhere on the progress bar to jump to a different position in the replay file. You can STOP or PAUSE the replay at any time by click on STOP or PAUSE button on the toolbar.");
		texts.put("spectrum.iqReplayZoomFull",
				"Restores the I/Q REPLAY spectrum view to the full recorded bandwidth.");
		texts.put("spectrum.tabs",
				"Settings tabs. Sweep contains live receiver and chart controls, Params contains display and marker behavior, and Waterfall/REC contains waterfall and recording settings.");

		texts.put("frequency.start",
				"Start frequency of the sweep, in MHz. This is the displayed frequency; if Shift is active the hardware frequency is converted internally.");
		texts.put("frequency.stop",
				"Stop frequency of the sweep, in MHz. It must be higher than the start frequency. With shifted operation this field still shows the displayed frequency.");
		texts.put("frequency.panLeft",
				"Moves the current sweep range DOWN in frequency by roughly 90%	of the visible span.");
		texts.put("frequency.panRight",
				"Moves the current sweep range UP in frequency by roughly 90% of the visible span.");
		texts.put("frequency.ranges",
				"Manual sweep range entry. Use start-stop in MHz, for example 920-960. Multiple compressed ranges can be entered with commas, for example 920-960,1805-1880,2620-2690, sweep will skip unused gaps and will be much faster. Values are displayed with Shift applied, but HackRF receives unshifted hardware frequencies.");
		texts.put("frequency.presets",
				"Loads a saved sweep preset including frequency range, RBW, frequency shift, and amplitude offset. Presets are stored in presets.csv delimited by semicolons and can be edited with a text editor.\n\nNote: Compressed ranges are defined by start frequency = -1 and stop frequency by ranges with commas, for example -1;1805-1880,2620-2690.");
		texts.put("frequency.savePreset",
				"Saves the current sweep setup as a preset in presets.csv. To remove unwanted presets use a text editor to delete them from the file.");

		texts.put("capture.pause",
				"Pauses or resumes LIVE capture/REPLAY and chart updates without closing the application.");
		texts.put("capture.trigger",
				"Opens TRIGGER window for detecting signal events.");
		texts.put("capture.replay",
				"Opens a recorded HSR, I/Q WAV, raw PCM, CU8, or CS8 file for replay.\n- WAV, PCM, CU8, and CS8 filenames must contain the center frequency as the first standalone number in the name. A standalone number starts the name or follows a space, underscore, or hyphen; digits attached to text identifiers such as g001 are ignored. A number without a unit is MHz; suffixes M, k, MHz, kHz, and Hz are accepted. Examples: IQ_868.3_test.wav, g001_TCH_868.9M_14_250k.cu8, radio-433920kHz.wav.\n- PCM, CU8, and CS8 filenames must additionally end with the sample rate immediately before the extension. A plain value is Ms/s and suffix k means ks/s. Examples: IQ_868.3_2.4.pcm, g001_TCH_868.9M_14_250k.cu8, and 433735000Hz_500k.cs8.\n- Raw files are 8-bit unless 16b occurs in the filename. CU8 is unsigned; CS8 is signed.\n- During REPLAY the frequency panel shows the recorded display range, recorded date/time and progress bar with actual and total recording duration.\n- You can click anywhere on the progress bar to jump to a different position in the replay file. You can STOP or PAUSE the replay at any time by click on STOP or PAUSE button on the toolbar.");
		texts.put("capture.video",
				"Starts or stops screen VIDEO/SCREEN recording. The recording area, format, resolution, and frame rate are configured on the Waterfall/REC tab. Usable formats are MP4 and animated GIF. MP4 is recommended for longer recordings, while GIF is suitable for short clips, preferably for web presentations.");
		texts.put("capture.stats",
				"Starts or stops CSV statistics logging for the current spectrum. The CSV file contains time, frequency, and level information for each sweep. The logging interval is configured on the Waterfall/REC tab.");
		texts.put("capture.data",
				"Starts or stops native HSR FULL SPECTRUM data recording. HSR can be replayed later with the REPLAY button as it was LIVE captured, including waterfall, spectrogram data and date/time information.\n- Note: Native HSR format does not contain any I/Q samples, so it cannot be used for zooming, time-domain analysis or demodulation. For this purpose, use I/Q recording in the time-domain analyzer window.");

		texts.put("sweep.gain",
				"Overall receiver gain preset. It adjusts the HackRF gain distribution used for LIVE capture.");
		texts.put("sweep.lnaGain",
				"LNA gain. Higher values increase front-end sensitivity but can overload on strong signals.");
		texts.put("sweep.vgaGain",
				"VGA gain. Raises or lowers baseband gain after the RF front end.");
		texts.put("sweep.rfAmp",
				"Enables the HackRF RF amplifier.\nUse carefully!\nIt can improve weak signals but may overload, or even be damaged with strong local signals.");
		texts.put("sweep.biasT",
				"Enables antenna 3.3V bias power for active antennas or external devices that require DC power.");
		texts.put("sweep.noDc",
				"Suppresses DC spur artifacts in the LIVE or REPLAYed spectrum chart.");
		texts.put("chart.realTime",
				"Shows the latest LIVE spectrum trace (WHITE color).");
		texts.put("chart.persist",
				"Enables persistent display so older signal traces fade out gradually instead of disappearing immediately. The most recent trace is always shown in RED, while older traces fade from YELLOW to BLUE and then disappear. The persistence decay time is configured on the Params tab.");
		texts.put("chart.average",
				"Shows an AVERAGED LIVE spectrum trace (YELLOW color). Averaging can be used to reduce noise and improve the visibility of constant signals. Average settings are configured on the Params tab.");
		texts.put("chart.peak",
				"Shows PEAK detection on the spectrum (GREEN color). Peak detection finds the highest signal level for each frequency bin and displays it as a separate trace. Peak settings are configured on the Params tab.");
		texts.put("chart.maxHold",
				"Shows maximum PEAK HOLD (RED color), keeping the highest observed level for each frequency bin.");
		texts.put("marker.peak",
				"Shows up to 5 simultaneous labels for detected PEAK markers.");
		texts.put("marker.hold",
				"Shows up to 5 simultaneous labels for maximum PEAK HOLD markers.");
		texts.put("freq.rbw",
				"Resolution bandwidth in kHz. Lower values give finer frequency detail. The RBW is applied to the LIVE spectrum and I/Q replay spectrum.");
		texts.put("freq.shift",
				"Frequency shift in MHz. Useful for SAT LNBs and upconverters. The chart, selectors, ranges, and filenames use shifted display frequencies, while HackRF tuning remains unshifted internally.");
		texts.put("amplitude.offset",
				"Adds a display offset to measured amplitude values in dB.");
		texts.put("power.calibration",
				"Power calibration value used when converting spectrum levels for power/flux related display calculations.");
		texts.put("iq.audio",
				"Enables audio demodulation of the whole spectrum or selected channel/spectrum interval.");
		texts.put("iq.audioVolume",
				"Audio output volume.");
		texts.put("iq.audioMode",
				"Demodulation mode for I/Q audio.");
		texts.put("iq.burstDetect",
				"Enables burst detection during I/Q replay analysis.");
		texts.put("iq.customTxFrequency",
				"Allows a custom transmit center frequency during I/Q replay transmit mode. When disabled, the HackRF will transmit at the same frequency as the recorded center frequency.");
		texts.put("iq.tx",
				"Enables transmit mode of I/Q replay samples directly with connected HackRF device.\nUse carefully!\nThe HackRF device will transmit the I/Q samples at the original or custom center frequency and power level. Make sure you comply with local regulations and avoid transmitting on restricted frequencies.");
		texts.put("iq.txPower",
				"Transmit power setting for I/Q replay output. For higher power levels, you can use also internal RF amplifier by checking the RF AMP box.\nUse carefully!.");
		texts.put("iq.txCenter",
				"Custom transmit center frequency for I/Q replay output. Check the Custom TX Frequency box to enable this option. When disabled, the HackRF will transmit at the same frequency as the recorded center frequency.");

		texts.put("params.reset",
				"Restores the main spectrum analyzer parameters to their default/preset values. Useful if you have changed many settings and want to start over.\n- Note: This resets last used values from previous session.");
		texts.put("params.spline",
				"Draws spectrum traces with spline interpolation for smoother curves.\n- Note: Spline interpolation is slower than linear interpolation, especially for large spectrum traces.");
		texts.put("params.lineThickness",
				"Sets the line thickness used for spectrum traces.");
		texts.put("params.peakFallTime",
				"Controls how quickly PEAK trace fall after a signal drops.");
		texts.put("params.peakFallThreshold",
				"Minimum drop threshold before PEAK fall behavior is applied.");
		texts.put("params.peakHoldTime",
				"How long PEAK values are held before falling in seconds. Useful for observing short-duration signals that may be missed in a single sweep, e.g. in CSV logging with large intervals.");
		texts.put("params.markerCount",
				"Number of markers shown for PEAK or MAX-HOLD values.");
		texts.put("params.persistDecay",
				"Decay time for PERSISTENT display traces.");
		texts.put("params.avgIterations",
				"Number of samples used for spectrum averaging in AVG trace mode. Higher values reduce noise/increase smoothness, but also reduce responsiveness to signal changes.");
		texts.put("params.avgOffset",
				"Amplitude offset applied to the averaged spectrum trace in AVG trace mode.");
		texts.put("params.allocations",
				"Frequency allocation table used to annotate known bands on the spectrum display. The table is loaded from \"freq\" folder containing CSV files. CSV file with allocation data can be created/edited or customized with a text editor.\n- The format of the CSV file is: start frequency - stop frequency; label; color (optional).\n- Use slash (/) to separate multiple rows of label for the same frequency range. For example: \"778.000 - 788.000 MHz\";\"LTE700/Orange/downlink\";\"#ff6500\"");

		texts.put("waterfall.date",
				"Shows date/time information on chart's left upper corner. The date/time is taken from the system clock when the LIVE sweep is running, or from the recording file when in REPLAY mode. Useful for logging or video recording purposes.");
		texts.put("waterfall.infoBox",
				"Shows or hides the sweep information box overlay in the waterfall's lower right corner.");
		texts.put("waterfall.enable",
				"Starts or stops the waterfall display.");
		texts.put("waterfall.paletteStart",
				"Lower dB value of the waterfall color palette.");
		texts.put("waterfall.paletteLength",
				"Dynamic range, in dB, covered by the waterfall color palette.");
		texts.put("waterfall.speed",
				"Controls how fast the waterfall scrolls.");
		texts.put("record.statsInterval",
				"Interval used for STATS CSV logging: fractional updates, seconds, or minutes. You may want to adjust PEAK trace hold time for longer logging intervals.");
		texts.put("record.dataFps",
				"Frame rate for native HSR spectrum data recording. FULL records every available frame.");
		texts.put("record.videoArea",
				"Part of the application captured by video recording. You can select the whole application window, or just the spectrum chart and waterfall area.");
		texts.put("record.videoResolution",
				"Vertical video resolution for screen recordings.");
		texts.put("record.videoFps",
				"Video recording frame rate. Higher frame rates give smoother motion but require more processing and disk space.");
		texts.put("record.videoFormat",
				"Video recording format. MP4 is suitable for longer recordings, while animated GIF is suitable for short clips, preferably for web presentations.");

		texts.put("trigger.help",
				"Trigger window context help. Hover over a trigger control or click it with the question-mark cursor.");
		texts.put("trigger.enabled",
				"Enables trigger event detection. When disabled, trigger conditions are ignored.");
		texts.put("trigger.range",
				"Frequency range monitored by the trigger, in MHz. The trigger can be set to monitor the whole sweep range, or a specific frequency range of interest.");
		texts.put("trigger.cooldown",
				"Minimum time after a trigger event before another event can be logged, in seconds.");
		texts.put("trigger.source",
				"Signal source used for trigger decisions: realtime spectrum (WHITE trace) or detected peaks (GREEN trace).");
		texts.put("trigger.match",
				"Controls whether ALL enabled trigger conditions must match, or ANY one condition is enough.");
		texts.put("trigger.maxLevel",
				"Triggers from the STRONGEST LEVEL found in the selected frequency range.");
		texts.put("trigger.totalPower",
				"Triggers from the TOTAL POWER across the selected frequency range.");
		texts.put("trigger.operator",
				"Comparison operator used for the selected trigger threshold.");
		texts.put("trigger.threshold",
				"Trigger threshold in dBm.");
		texts.put("trigger.sound",
				"Plays a sound when a trigger event is detected.");
		texts.put("trigger.log",
				"Writes trigger events to a CSV log.");
		texts.put("trigger.clear",
				"Clears the trigger event list.");
		texts.put("trigger.findReplay",
				"Quickly scans the current replay file for matching trigger events.");
		texts.put("trigger.events",
				"List of detected trigger events with time, replay offset, frequency, and level. In REPLAY mode, the trigger events are displayed also on the progress bar as orange line markers.");

		texts.put("iq.help",
				"Time-domain analyzer context help. Hover over a control or click it with the question-mark cursor.");
		texts.put("iq.timePlot",
				"Time-domain analyzer main display. Shows incoming I/Q samples, envelope, deviation, trigger state, and burst activity.\nThe time-domain view is limited to a maximum of HackRF's 20 MHz bandwidth in LIVE mode, or the full recorded bandwidth for I/Q replay.\n\nThe first upper row contains current time-view parameters, followed by RMS/PEAK level meter (YELLOW/GREEN strips). Level meter is very useful for setting appropriate levels for I/Q recording or signal analysis, as signal should not be clipped or distorted (RED peak levels).\n- The time-domain view can be zoomed in or out with mouse wheel or by \"+\" and \"-\" buttons on the right side of the main display.\n- Click the LEFT mouse button to display delta time measurements from the pre-trigger marker.\n- Click and drag the LEFT mouse button to display delta time measurementsbetween any two points. Click RIGHT mouse button to clear the measurements display.\n- In case ot activated trigger, orange dashed line shows the trigger level, while the trigger state is shown in the right upper corner of the time-domain view.");
		texts.put("iq.spectrumPlot",
				"I/Q spectrum display. Shows the selected channel spectrum. Drag the spectrum to adjust exact offset.");
		texts.put("iq.center",
				"Receiver center frequency for the time-domain analysis. In LIVE capture mode, it can be corrected manually to exact value.");
		texts.put("iq.sampleRate",
				"HackRF I/Q sample rate. Higher rates cover more bandwidth but require more processing.");
		texts.put("iq.lna",
				"LNA gain for time-domain analysis. The value is received from the spectrum sweep and can be adjusted manually to match appropriate signal levels.");
		texts.put("iq.vga",
				"VGA gain defaults to 32. Can be adjusted manually for fine-tuning of PEAK signal levels.");
		texts.put("iq.rfAmp",
				"Enables the HackRF RF amplifier. Setting is received from the spectrum sweep.\nUse carefully!\nIt can improve weak signals but may overload, or even damage RF amplifier with strong local signals.");
		texts.put("iq.run",
				"Starts or stops the time-domain trace flow.");
		texts.put("iq.zoomPreset",
				"Applies a channel preset, bandwidth, output rate, and time view setup.");
		texts.put("iq.viewMode",
				"Wideband analyses the whole source; Narrowband extracts a channel around the selected offset. Narrowband mode is useful for bandwidths under 2 MHz, as 2 Msps is the lowest HackRF's sample rate.");
		texts.put("iq.channelOffset",
				"Channel offset relative to the receiver center frequency. The offset can be adjusted by dragging the spectrum display or by entering a specific value.");
		texts.put("iq.dcOffset",
				"Moves the selected channel away from the center DC artifact. This is useful for avoiding DC offset interference in the time-domain view.");
		texts.put("iq.dcShift",
				"Amount of low-IF shift used when DC offset avoidance is enabled. In the Narrowband view, low-IF shift is calculated automatically, in the Wideband view it can be adjusted manually up to half the bandwidth.");
		texts.put("iq.bandwidth",
				"Channel bandwidth used for filtering, demodulation, recordings, and signal analysis. Useful in Narrowband mode. Channel bandwidth is displayed as highlight of the part on spectrum display");
		texts.put("iq.outputRate",
				"Output sample rate after channel filtering. This setting is useful for I/Q recording or demodulation, as it can reduce the amount of data and processing required.");
		texts.put("iq.signalMode",
				"Signal type selection for the Signal Analyzer. Choose the appropriate decoder to perform signal quality analysis and measurements.");
		texts.put("iq.signalTest",
				"Opens the Signal Analyzer window for the selected signal type.");
		texts.put("iq.timeView",
				"Number of samples visible in the time-domain plot.");
		texts.put("iq.envelope",
				"Shows the amplitude envelope (YELLOW line) instead of raw I/Q waveform detail. If not checked, the time-domain view shows the raw I/Q waveform detail. GREEN line represents In-phase (I) component and RED line represents Quadrature (Q) component.");
		texts.put("iq.deviation",
				"Shows FSK/FM deviation view (VIOLET line).");
		texts.put("iq.burst",
				"Highlights burst-like signal activity. Every burst is displayed as ORANGE overline above the I/Q envelope line.\n- Burst statistics info displays above the time-domain plot. It contains information about number of bursts captured in actual window, bursts interval/frequency and their width.");
		texts.put("iq.autoLevel",
				"Automatically scales the signal RMS level for time-domain view for readable amplitude. This function DOES NOT affect level for Signal Analyzer, but DOES affect level for I/Q recording or demodulation!\n- Use carefully, as it can clip the signal PEAKs! Watch the RMS/PEAK level meter above the time-domain plot to avoid signal clipping.");
		texts.put("iq.audioRecord",
				"Records demodulated audio to a WAV file (16-bit, 48 kHz). The audio is extracted from the selected channel and can be used for further analysis or listening.");
		texts.put("iq.videoRecord",
				"Records the time-domain analyzer window to video, including the demodulated audio (only in MP4 format). The recording area, format, resolution, and frame rate are configured on the Waterfall/REC tab in spectrum analyzer. Usable formats are MP4 and animated GIF. MP4 is recommended for longer recordings or recording with audio, while GIF is suitable for short clips, preferably for web presentations.");
		texts.put("iq.iqRecord",
				"Records filtered I/Q samples to a WAV/PCM-style I/Q file.\n- The recorded I/Q file can be replayed later with the REPLAY button in spectrum analyzer to investigate further.\n- Note: AUTO LEVEL setting DOES affect the level of recorded I/Q samples, so it should be disabled for proper I/Q recording!");
		texts.put("iq.triggerEnable",
				"Enables burst trigger detection in the time-domain view. The trigger state is shown in the right upper corner of the time-domain view.");
		texts.put("iq.singleTrigger",
				"Arms a one-shot trigger capture. When a trigger event is detected, the time-domain view will stop and display the captured signal. You can then analyze the captured signal. Every change of trigger settings or ZOOM will automatically re-arm the one-shot trigger.");
		texts.put("iq.triggerPre",
				"Amount of pre-trigger history retained around a trigger event. This allows you to see what happened before the trigger event occurred.");
		texts.put("iq.triggerLevel",
				"Burst trigger threshold. Lower values are more sensitive. The trigger level is displayed as an orange dashed line in the time-domain view.");

		return Collections.unmodifiableMap(texts);
	}
}
