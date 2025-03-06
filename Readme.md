# HackRF Spectrum Analyzer for Windows
based on Pavsa's HackRF hackrf_sweep Spectrum Analyzer

![# SP+W 920-960 MHz](https://github.com/user-attachments/assets/e2437acd-83d6-4ac5-9b82-443aa10c15ec)

### Download:
[Download the latest version](https://github.com/voxo22/hackrf-spectrum-analyzer/releases) 

### Features:
- Make your HackRF a semi-profi spectrum analyzer
- RBW from 3 kHz to 2 MHz
- Realtime / Peak / Average / Max Hold / Persistent display with adjustable timings
- Customizable Frequency band presets with detail setting
- Customizable multicolored Frequency allocation bands -> you can make your own!
- Adjustable high resolution Waterfall Plot
- Widely adjustable live screen recording into GIF or MP4 video
- Data recording feature into CSV file with MaxFreq, TotalPower, PeakPower + adjustable timing (minutes, seconds, fractions)
- Power Calibration adjustment for RF Power Flux density sum reading in µW/m²
- Spur filter (no DC) for removing spur artifacts
- Arrow left/right button for comfortable tuning
- Adjustable amplitude and average chart offset
- Selectable Frequency Shift for up/down-converters
- Switchable Datestamp
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

### Screenshots:
![screen3](https://github.com/user-attachments/assets/e814fb42-3696-4fcb-b692-c714d128670e)

![screenshot](spectrum_1805-1880.gif "screenshot")
