# HackRF Spectrum Analyzer for Windows

### Screenshots:
![spectrum_demo](https://github.com/voxo22/hackrf-spectrum-analyzer/blob/master/docs/images/FULLSCR_758-788+791-821+925-960+1805-1880+2110-2170+2630-2690_MHz.gif)
![IQ_replay_demo](https://github.com/voxo22/hackrf-spectrum-analyzer/blob/master/docs/images/FULLSCR_223-231_MHz.gif)
![Time-domain_analyzer](https://github.com/voxo22/hackrf-spectrum-analyzer/blob/master/docs/images/951800000Hz_BW-400k.gif)
![DVB-T2_analyzer](https://github.com/voxo22/hackrf-spectrum-analyzer/blob/master/docs/images/DVB-T2_analyzer.jpg)

### Download:
[Download the latest version](https://github.com/voxo22/hackrf-spectrum-analyzer/releases) 

### Detailed user manual with images:
See [Wiki](https://github.com/voxo22/hackrf-spectrum-analyzer/wiki)

### Features:
- Make your HackRF a semi-profi spectrum / time-domain analyzer
- LIVE spectrum RBW from 2.4 kHz to 2 MHz, I/Q replay RBW down to 50 Hz
- Real-time, peak, average, max-hold, min-hold and persistence traces
- Up to 5 simultaneous Peak and Max Hold markers
- Customizable frequency-band presets with frequency range, RBW, frequency shift and amplitude offset; new presets can be added directly from the settings panel
- Custom multicolor frequency-allocation overlays loaded from editable tables -> make your own!
- Adjustable high resolution Waterfall Plot
- Multi-range sweep support, automatic compression of X-axis (stitching ranges together)
- Configurable screen recording to GIF or MP4, with optional MP4 audio
- CSV statistics logging at configurable intervals  (minutes, seconds, fractions), including peak frequency, total power and peak power
- Recording and replay of live spectrum-bin data in the binary HSR format, preserving original timestamps and supporting pause
- Level triggers for real-time and peak traces, with audio alerts and CSV event logging
- Data replay progress bar with click to seek function, quick search, infinite loop, automatic trigger-event search
- Calibrated power-flux-density readout in µW/m2
- DC-spur suppression for live and replayed spectra
- Left/right navigation buttons and x-axis dragging for convenient frequency-range selection
- Spectrum zooming by mouse dragging, mouse wheel for quick zooming in and out
- Adjustable amplitude and offset of Average trace
- Selectable Frequency Shift for up/down-converters
- Time-Domain Analyzer window accessed directly from Spectrum Analyzer, using right mouse click and drag in spectrum (red highlight)
- Draggable channel spectrum sub-window
- Narrowband (channel) view (up to 1 MS/s) with decimation feature + raw wideband view (up to 20 MS/s)
- Raw I/Q waveforms or envelope-only (|IQ|) view, plus FSK/FM deviation and burst detection
- Automatic I/Q level scaling with clipping indication
- Built-in Signal Analyzer for live HackRF input and recorded I/Q replay: Generic QAM, FM RDS, DAB, DVB-T/T2, ISM 868, GSM 2G, UMTS 3G, LTE 4G, 5G NR and Wi-Fi
- Signal-quality views with spectrum and constellation plots, lock/quality indicators, SNR, EVM/MER where applicable, carrier-frequency offset and protocol-specific parameters
- Broadcast signal decoding: FM RDS data, DAB ensemble and service information, DVB-T TPS and DVB-T2 P1/L1 signalling
- Cellular downlink analysis with cell discovery, operator/PLMN lookup and decoded system information for GSM, UMTS, LTE and 5G NR, plus load and paging statistics where supported
- Wi-Fi OFDM and DSSS analysis with decoded frame details, SSID/channel discovery, AP/client tables and manufacturer lookup
- Multi-channel ISM 868 analysis with protocol detection and decoding for Wireless M-Bus, LoRa/LoRaWAN, Fine Offset weather stations and Renault TPMS, plus automotive key-fob candidate classification
- Copyable signal-analyzer snapshots and built-in context-sensitive help for controls and decoded parameters
- Switchable AM/OOK and FM (NFM/WFM) audio demodulation of selected range, also in spectrum I/Q replay mode
- Recording of demodulated audio to 48 kHz, 16-bit WAV files
- Recording of unsigned 8-bit stereo I/Q data to WAV files
- Automatic and one-shot trigger modes with an adjustable, color-coded threshold marker
- Automatic saving and restoring of sweep parameters in an INI file, with one-click reset to defaults
- Switchable timestamp and information overlays
- Replay of WAV, PCM and CU8 I/Q recordings without HackRF hardware
- Direct transmission of replayed WAV, PCM and CU8 I/Q files through HackRF, with original or custom center frequency and adjustable TX gain
- Native hackrf_sweep and hackrf_iq back ends integrated as shared libraries

You can customize "presets.csv" file by adding or removing entries. Follow the structure and column meaning.
Additionally, in "freq" folder you can edit frequency allocation tables or make your own. A slash (/) in a text column inserts a line break.

### Requirements:
* HackRF One with [Firmware 2023.01.1](https://github.com/mossmann/hackrf/releases/tag/v2023.01.1) or newer 

### Installation:
Make sure HackRF is using at least the minimum firmware version (see above) 

1. Supported OS: Windows 7 x64 or newer
2. Minimum 64-bit Java 8 JRE
3. [Download the latest version of Spectrum Analyzer](https://github.com/voxo22/hackrf-spectrum-analyzer/releases) and unzip
4. Connect and install HackRF as a libusb device
    - [Download Zadig](https://zadig.akeo.ie/) and install
    - Go to Options and check List All Devices
    - Find "HackRF One" and select Driver "WinUSB" and click install
5. Run "hackrf-spectrum-analyzer.jar"

### License:
GPL v3
