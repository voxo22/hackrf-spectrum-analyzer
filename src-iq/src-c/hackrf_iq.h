#ifndef HACKRF_IQ_H_
#define HACKRF_IQ_H_

#include <stdint.h>

#ifdef _WIN32
  #ifdef ADD_EXPORTS
    #define IQAPI __declspec(dllexport)
  #else
    #define IQAPI __declspec(dllimport)
  #endif
  #define IQCALL __cdecl
#else
  #define IQAPI
  #define IQCALL
#endif

typedef void (IQCALL *hackrf_iq_callback)(
	uint64_t center_freq_hz,
	uint32_t sample_rate_hz,
	uint32_t valid_length,
	int8_t* iq_data);

typedef int (IQCALL *hackrf_iq_tx_callback)(
	uint32_t requested_length,
	int8_t* iq_data);

/**
 * Starts a blocking HackRF RX stream and forwards raw interleaved signed
 * 8-bit IQ bytes to the callback. Only one active stream is supported.
 */
IQAPI int IQCALL hackrf_iq_lib_start(
	hackrf_iq_callback callback,
	uint64_t center_freq_hz,
	uint32_t sample_rate_hz,
	uint32_t baseband_filter_hz,
	unsigned int lna_gain,
	unsigned int vga_gain,
	unsigned int amp_enable);

IQAPI void IQCALL hackrf_iq_lib_stop(void);

IQAPI int IQCALL hackrf_iq_lib_start_tx(
	hackrf_iq_tx_callback callback,
	uint64_t center_freq_hz,
	uint32_t sample_rate_hz,
	uint32_t baseband_filter_hz,
	unsigned int txvga_gain,
	unsigned int amp_enable);

IQAPI void IQCALL hackrf_iq_lib_stop_tx(void);

#endif /* HACKRF_IQ_H_ */
