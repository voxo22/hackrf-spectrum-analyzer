# HackRF IQ bridge

Experimental native bridge for continuous HackRF RX streaming.

The goal is to provide a second mode next to `hackrf_sweep`: fixed-frequency
raw IQ capture for time-domain analysis and later demodulation.

## Native API

`src-c/hackrf_iq.h` exports:

- `hackrf_iq_lib_start(...)`
- `hackrf_iq_lib_stop()`

The callback receives interleaved signed 8-bit IQ bytes:

```text
I0, Q0, I1, Q1, ...
```

`hackrf_iq_lib_start(...)` is blocking, matching the existing sweep bridge
style. Run it from a worker thread and call `hackrf_iq_lib_stop()` from another
thread to stop streaming.

## Build

The top-level `Makefile` now has targets for:

- `build/lib/win32-x86-64/hackrf-iq.dll`
- `build/lib/linux-x86-64/libhackrf-iq.so`

It needs the same native build toolchain as the existing sweep library:

- MinGW-w64 for Windows builds
- GCC for Linux builds
- libusb headers/import library
- local `libhackrf` sources already present in this repository

## Java bridge

Java classes are under:

- `src-java/hackrfiq/HackRFIQLibrary.java`
- `src-java/jspectrumanalyzer/iq/HackRFIQNativeBridge.java`
- `src-java/jspectrumanalyzer/iq/HackRFIQDataCallback.java`

The next layer should be a ring buffer and a minimal `IQAnalyzerApp` window.

## Time-domain viewer

Compile the current IQ Java module:

```bash
javac -source 1.8 -target 1.8 -cp lib/jna/jna-4.5.1.jar -d bin \
  src-java/hackrfiq/HackRFIQLibrary.java \
  src-java/jspectrumanalyzer/iq/*.java
```

Run the first standalone viewer:

```bash
java -cp "bin;lib\jna\jna-4.5.1.jar" jspectrumanalyzer.iq.IQAnalyzerApp 100MHz 10000000 16 20
```

Arguments are:

```text
center-frequency sample-rate-hz lna-gain vga-gain
```

For narrow channels, switch `Mode` from `Raw IQ` to `Channel IQ` and set:

- `Offset`: channel offset from the HackRF center frequency, for example `250kHz` or `-600kHz`
- `BW`: channel bandwidth, for example `200 kHz` for GSM-like channels
- `Out`: displayed decimated IQ rate, for example `250 kS/s` or `500 kS/s`

Press `Start` after changing these DSP settings.

## Smoke test

After building `hackrf-iq.dll`, copy it next to the existing native runtime
libraries:

```bash
cp build/lib/win32-x86-64/hackrf-iq.dll win32-x86-64/
```

Compile the Java smoke test from the repository root:

```bash
javac -source 1.8 -target 1.8 -cp lib/jna/jna-4.5.1.jar -d bin \
  src-java/hackrfiq/HackRFIQLibrary.java \
  src-java/jspectrumanalyzer/iq/HackRFIQDataCallback.java \
  src-java/jspectrumanalyzer/iq/HackRFIQNativeBridge.java \
  src-java/jspectrumanalyzer/iq/IQBridgeSmokeTest.java
```

Run it from the repository root:

```bash
java -cp "bin;lib\jna\jna-4.5.1.jar" jspectrumanalyzer.iq.IQBridgeSmokeTest 100MHz 10000000 5
```

Arguments are:

```text
center-frequency sample-rate-hz seconds lna-gain vga-gain
```

Only `center-frequency` is human-friendly (`100MHz`, `868.3MHz`, `145000000`).
