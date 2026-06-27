# HackRF Spectrum Analyzer for Windows

### Screenshots:
![FULL_920-960MHz_demo](https://github.com/user-attachments/assets/63bb1506-ebf1-4002-b53a-cc934590743b)
![FULL_2400-2483MHz_demo3](https://github.com/user-attachments/assets/699d551f-88f3-4ad6-a107-120bf3b513e4)

### Download:
[Download the latest version](https://github.com/voxo22/hackrf-spectrum-analyzer/releases) 

### User manual:
See Wiki

### Features:
- Make your HackRF a nearly-profi spectrum / time-domain analyzer
- LIVE spectrum RBW from 2.4 kHz to 2 MHz, I/Q replay RBW down to 50 Hz
- Adjustable Realtime / Peak / Average / Max Hold / Min Hold / Persistent chart
- Up to 5 simultaneous Peak and Max Hold markers
- Customizable Frequency band presets with detail setting, add new preset option directly from settings panel
- Customizable multicolored Frequency allocation bands -> you can make your own!
- Adjustable high resolution Waterfall Plot
- Multi-range sweep support, automatic compression of X-axis (stitching ranges together)
- Widely adjustable live screen recording into GIF or MP4 video (MP4 incl. audio)
- Stats recording feature into CSV file with MaxFreq, TotalPower, PeakPower + adjustable timing (minutes, seconds, fractions)
- Data record and replay feature of live Spectrum data (bins) into binary HSR file, featuring real datestamp and replay pause
- Realtime/peaks trigger level feature with audio alert and CSV logging
- Data replay progress bar with click to seek function, quick search, infinite loop, autoFIND trigger events
- Power Calibration adjustment for RF Power Flux Density sum reading in µW/m²
- Spur filter (no DC) for removing spur artifacts
- Arrow left/right button, X-axis mouse drag for comfortable frequency range setting
- Spectrum zooming by mouse dragging, mouse wheel for quick zooming/unzooming
- Adjustable amplitude and average chart offset
- Selectable Frequency Shift for up/down-converters
- Time-Domain Analyzer window accessed directly from Spectrum Analyzer, using right mouse click and drag in spectrum (red highlight)
- Draggable channel spectrum sub-window
- Narrowband (channel) view (up to 1 Ms/s) with decimation feature + raw wideband view (up to 20 Ms/s)
- I+Q, |IQ| or envelope only views, FSK deviation chart, burst detector with additional info
- Auto-level I/Q analyze feature
- Switchable AM/OOK-NFM/WFM audio demodulation of selected range, also in spectrum I/Q replay mode
- Audio recording feature into 48 kHz/16bit WAV file
- I/Q recording feature into 8-bit WAV file (with header)
- Auto + One shot trigger feature with adjustable level (orange/green markline on the graph)
- Auto save of all sweep parameters into .ini file / auto restore + RESET to default button
- Switchable Datestamp and Infobox
- Replay option of WAV/PCM I/Q files, can be used without HackRF HW, just to analyze any I/Q records
- Direct HackRF HW Tx option of WAV/PCM IQ files while REPLAY in spectrum mode (orig/custom center freq setting, Tx power slider)
- hackrf_sweep integrated as a shared library

You can customize "presets.csv" file by adding or deleting requested rows. Follow the structure and column meaning.
Additionaly, in "freq" folder you can edit frequency allocation tables or make your own. "Slash" character (/) in text columns hyphenates rows.

### Requirements:
* HackRF One with [Firmware 2023.01.1](https://github.com/mossmann/hackrf/releases/tag/v2023.01.1) or newer 

### Installation:
Make sure HackRF is using at least the minimum firmware version (see above) 

1. Minimum Windows 7 x64 or Vista x64 with extended kernel
2. Minimum Java JRE 64bit v1.8 ([Java JRE for Windows x64](http://www.oracle.com/technetwork/java/javase/downloads/jre8-downloads-2133155.html)) 
3. [Download the latest version of Spectrum Analyzer](https://github.com/voxo22/hackrf-spectrum-analyzer/releases) and unzip
4. Connect and install HackRF as a libusb device
    - [Download Zadig](https://zadig.akeo.ie/) (or use packed one) and install
    - Goto Options and check List All Devices
    - Find "HackRF One" and select Driver "WinUSB" and click install
5. Run "v2.21.jar"

### License:
GPL v3
