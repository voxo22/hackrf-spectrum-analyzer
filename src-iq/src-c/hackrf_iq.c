#define ADD_EXPORTS

#include "hackrf_iq.h"

#include <hackrf.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
  #include <windows.h>
  static void sleep_millis(int millis)
  {
	  Sleep((DWORD) millis);
  }
#else
  #include <unistd.h>
  static void sleep_millis(int millis)
  {
	  usleep((useconds_t) millis * 1000);
  }
#endif

static volatile int do_exit = 0;
static hackrf_device* device = NULL;
static hackrf_iq_callback iq_callback = NULL;
static hackrf_iq_tx_callback tx_callback_provider = NULL;
static uint64_t active_center_freq_hz = 0;
static uint32_t active_sample_rate_hz = 0;

static int rx_callback(hackrf_transfer* transfer)
{
	if (do_exit) {
		return -1;
	}
	if (iq_callback == NULL || transfer == NULL || transfer->buffer == NULL || transfer->valid_length <= 0) {
		return 0;
	}

	iq_callback(
		active_center_freq_hz,
		active_sample_rate_hz,
		(uint32_t) transfer->valid_length,
		(int8_t*) transfer->buffer);

	return 0;
}

static int tx_callback(hackrf_transfer* transfer)
{
	if (do_exit) {
		return -1;
	}
	if (tx_callback_provider == NULL || transfer == NULL || transfer->buffer == NULL || transfer->valid_length <= 0) {
		return -1;
	}

	int copied = tx_callback_provider(
		(uint32_t) transfer->valid_length,
		(int8_t*) transfer->buffer);

	if (copied <= 0) {
		return -1;
	}
	if (copied < transfer->valid_length) {
		memset(transfer->buffer + copied, 0, transfer->valid_length - copied);
	}
	return 0;
}

IQAPI void IQCALL hackrf_iq_lib_stop(void)
{
	do_exit = 1;
}

IQAPI void IQCALL hackrf_iq_lib_stop_tx(void)
{
	do_exit = 1;
}

IQAPI int IQCALL hackrf_iq_lib_start(
	hackrf_iq_callback callback,
	uint64_t center_freq_hz,
	uint32_t sample_rate_hz,
	uint32_t baseband_filter_hz,
	unsigned int lna_gain,
	unsigned int vga_gain,
	unsigned int amp_enable)
{
	int result = HACKRF_SUCCESS;
	uint32_t chosen_filter_hz = baseband_filter_hz;

	if (callback == NULL) {
		fprintf(stderr, "hackrf_iq: callback must not be NULL\n");
		return HACKRF_ERROR_INVALID_PARAM;
	}
	if (center_freq_hz == 0 || sample_rate_hz == 0) {
		fprintf(stderr, "hackrf_iq: center frequency and sample rate must be non-zero\n");
		return HACKRF_ERROR_INVALID_PARAM;
	}
	if (lna_gain % 8) {
		fprintf(stderr, "hackrf_iq: lna_gain must be a multiple of 8\n");
		return HACKRF_ERROR_INVALID_PARAM;
	}
	if (vga_gain % 2) {
		fprintf(stderr, "hackrf_iq: vga_gain must be a multiple of 2\n");
		return HACKRF_ERROR_INVALID_PARAM;
	}

	do_exit = 0;
	iq_callback = callback;
	active_center_freq_hz = center_freq_hz;
	active_sample_rate_hz = sample_rate_hz;

	result = hackrf_init();
	if (result != HACKRF_SUCCESS) {
		fprintf(stderr, "hackrf_iq: hackrf_init() failed: %s (%d)\n", hackrf_error_name(result), result);
		goto cleanup;
	}

	result = hackrf_open(&device);
	if (result != HACKRF_SUCCESS) {
		fprintf(stderr, "hackrf_iq: hackrf_open() failed: %s (%d)\n", hackrf_error_name(result), result);
		goto cleanup;
	}

	result = hackrf_set_sample_rate(device, (double) sample_rate_hz);
	if (result != HACKRF_SUCCESS) {
		fprintf(stderr, "hackrf_iq: hackrf_set_sample_rate() failed: %s (%d)\n", hackrf_error_name(result), result);
		goto cleanup;
	}

	if (chosen_filter_hz == 0) {
		chosen_filter_hz = hackrf_compute_baseband_filter_bw_round_down_lt(sample_rate_hz);
	}
	result = hackrf_set_baseband_filter_bandwidth(device, chosen_filter_hz);
	if (result != HACKRF_SUCCESS) {
		fprintf(stderr, "hackrf_iq: hackrf_set_baseband_filter_bandwidth() failed: %s (%d)\n", hackrf_error_name(result), result);
		goto cleanup;
	}

	result = hackrf_set_freq(device, center_freq_hz);
	if (result != HACKRF_SUCCESS) {
		fprintf(stderr, "hackrf_iq: hackrf_set_freq() failed: %s (%d)\n", hackrf_error_name(result), result);
		goto cleanup;
	}

	result = hackrf_set_amp_enable(device, amp_enable ? 1 : 0);
	if (result != HACKRF_SUCCESS) {
		fprintf(stderr, "hackrf_iq: hackrf_set_amp_enable() failed: %s (%d)\n", hackrf_error_name(result), result);
		goto cleanup;
	}

	result = hackrf_set_lna_gain(device, lna_gain);
	if (result != HACKRF_SUCCESS) {
		fprintf(stderr, "hackrf_iq: hackrf_set_lna_gain() failed: %s (%d)\n", hackrf_error_name(result), result);
		goto cleanup;
	}

	result = hackrf_set_vga_gain(device, vga_gain);
	if (result != HACKRF_SUCCESS) {
		fprintf(stderr, "hackrf_iq: hackrf_set_vga_gain() failed: %s (%d)\n", hackrf_error_name(result), result);
		goto cleanup;
	}

	result = hackrf_start_rx(device, rx_callback, NULL);
	if (result != HACKRF_SUCCESS) {
		fprintf(stderr, "hackrf_iq: hackrf_start_rx() failed: %s (%d)\n", hackrf_error_name(result), result);
		goto cleanup;
	}

	while (!do_exit && hackrf_is_streaming(device) == HACKRF_TRUE) {
		sleep_millis(20);
	}

	result = HACKRF_SUCCESS;

cleanup:
	if (device != NULL) {
		if (hackrf_is_streaming(device) == HACKRF_TRUE) {
			int stop_result = hackrf_stop_rx(device);
			if (stop_result != HACKRF_SUCCESS) {
				fprintf(stderr, "hackrf_iq: hackrf_stop_rx() failed: %s (%d)\n", hackrf_error_name(stop_result), stop_result);
			}
		}
		hackrf_close(device);
		device = NULL;
	}
	hackrf_exit();

	iq_callback = NULL;
	active_center_freq_hz = 0;
	active_sample_rate_hz = 0;
	do_exit = 0;

	return result;
}

IQAPI int IQCALL hackrf_iq_lib_start_tx(
	hackrf_iq_tx_callback callback,
	uint64_t center_freq_hz,
	uint32_t sample_rate_hz,
	uint32_t baseband_filter_hz,
	unsigned int txvga_gain,
	unsigned int amp_enable)
{
	int result = HACKRF_SUCCESS;
	uint32_t chosen_filter_hz = baseband_filter_hz;

	if (callback == NULL) {
		fprintf(stderr, "hackrf_iq tx: callback must not be NULL\n");
		return HACKRF_ERROR_INVALID_PARAM;
	}
	if (center_freq_hz == 0 || sample_rate_hz == 0) {
		fprintf(stderr, "hackrf_iq tx: center frequency and sample rate must be non-zero\n");
		return HACKRF_ERROR_INVALID_PARAM;
	}
	if (txvga_gain > 47) {
		fprintf(stderr, "hackrf_iq tx: txvga_gain must be 0..47\n");
		return HACKRF_ERROR_INVALID_PARAM;
	}

	do_exit = 0;
	tx_callback_provider = callback;
	active_center_freq_hz = center_freq_hz;
	active_sample_rate_hz = sample_rate_hz;

	result = hackrf_init();
	if (result != HACKRF_SUCCESS) {
		fprintf(stderr, "hackrf_iq tx: hackrf_init() failed: %s (%d)\n", hackrf_error_name(result), result);
		goto cleanup;
	}

	result = hackrf_open(&device);
	if (result != HACKRF_SUCCESS) {
		fprintf(stderr, "hackrf_iq tx: hackrf_open() failed: %s (%d)\n", hackrf_error_name(result), result);
		goto cleanup;
	}

	result = hackrf_set_sample_rate(device, (double) sample_rate_hz);
	if (result != HACKRF_SUCCESS) {
		fprintf(stderr, "hackrf_iq tx: hackrf_set_sample_rate() failed: %s (%d)\n", hackrf_error_name(result), result);
		goto cleanup;
	}

	if (chosen_filter_hz != 0) {
		result = hackrf_set_baseband_filter_bandwidth(device, chosen_filter_hz);
		if (result != HACKRF_SUCCESS) {
			fprintf(stderr, "hackrf_iq tx: hackrf_set_baseband_filter_bandwidth() failed: %s (%d)\n", hackrf_error_name(result), result);
			goto cleanup;
		}
	}

	result = hackrf_set_txvga_gain(device, txvga_gain);
	if (result != HACKRF_SUCCESS) {
		fprintf(stderr, "hackrf_iq tx: hackrf_set_txvga_gain() failed: %s (%d)\n", hackrf_error_name(result), result);
		goto cleanup;
	}

	result = hackrf_start_tx(device, tx_callback, NULL);
	if (result != HACKRF_SUCCESS) {
		fprintf(stderr, "hackrf_iq tx: hackrf_start_tx() failed: %s (%d)\n", hackrf_error_name(result), result);
		goto cleanup;
	}

	result = hackrf_set_freq(device, center_freq_hz);
	if (result != HACKRF_SUCCESS) {
		fprintf(stderr, "hackrf_iq tx: hackrf_set_freq() failed: %s (%d)\n", hackrf_error_name(result), result);
		goto cleanup;
	}

	result = hackrf_set_amp_enable(device, amp_enable ? 1 : 0);
	if (result != HACKRF_SUCCESS) {
		fprintf(stderr, "hackrf_iq tx: hackrf_set_amp_enable() failed: %s (%d)\n", hackrf_error_name(result), result);
		goto cleanup;
	}

	while (!do_exit && hackrf_is_streaming(device) == HACKRF_TRUE) {
		sleep_millis(20);
	}

	result = HACKRF_SUCCESS;

cleanup:
	if (device != NULL) {
		if (hackrf_is_streaming(device) == HACKRF_TRUE) {
			int stop_result = hackrf_stop_tx(device);
			if (stop_result != HACKRF_SUCCESS) {
				fprintf(stderr, "hackrf_iq tx: hackrf_stop_tx() failed: %s (%d)\n", hackrf_error_name(stop_result), stop_result);
			}
		}
		hackrf_close(device);
		device = NULL;
	}
	hackrf_exit();

	tx_callback_provider = NULL;
	active_center_freq_hz = 0;
	active_sample_rate_hz = 0;
	do_exit = 0;

	return result;
}
