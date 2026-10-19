#define ADD_EXPORTS
#include "../src-c/hackrf_iq.h"
#include <hackrf.h>
#include <stdio.h>

/* Fake hardware exercises the real native bridge, without connecting a HackRF. */
static int bias_calls, bias_value, bias_failure, rx_calls, closes;
static int mock_device;
static void IQCALL callback(uint64_t f, uint32_t r, uint32_t n, int8_t* data) { (void)f; (void)r; (void)n; (void)data; }
int hackrf_init(void) { return 0; }
int hackrf_exit(void) { return 0; }
int hackrf_open(hackrf_device** d) { *d = (hackrf_device*)&mock_device; return 0; }
int hackrf_close(hackrf_device* d) { (void)d; closes++; return 0; }
int hackrf_set_antenna_enable(hackrf_device* d, uint8_t v) { (void)d; bias_calls++; bias_value = v; return bias_failure; }
int hackrf_set_sample_rate(hackrf_device* d, double r) { (void)d; (void)r; return 0; }
uint32_t hackrf_compute_baseband_filter_bw_round_down_lt(uint32_t r) { return r; }
int hackrf_set_baseband_filter_bandwidth(hackrf_device* d, uint32_t b) { (void)d; (void)b; return 0; }
int hackrf_set_freq(hackrf_device* d, uint64_t f) { (void)d; (void)f; return 0; }
int hackrf_set_amp_enable(hackrf_device* d, uint8_t v) { (void)d; (void)v; return 0; }
int hackrf_set_lna_gain(hackrf_device* d, uint32_t v) { (void)d; (void)v; return 0; }
int hackrf_set_vga_gain(hackrf_device* d, uint32_t v) { (void)d; (void)v; return 0; }
int hackrf_set_txvga_gain(hackrf_device* d, uint32_t v) { (void)d; (void)v; return 0; }
int hackrf_start_rx(hackrf_device* d, hackrf_sample_block_cb_fn c, void* ctx) { (void)d; (void)c; (void)ctx; rx_calls++; return 0; }
int hackrf_stop_rx(hackrf_device* d) { (void)d; return 0; }
int hackrf_start_tx(hackrf_device* d, hackrf_sample_block_cb_fn c, void* ctx) { (void)d; (void)c; (void)ctx; return 0; }
int hackrf_stop_tx(hackrf_device* d) { (void)d; return 0; }
int hackrf_is_streaming(hackrf_device* d) { (void)d; return 0; }
const char* hackrf_error_name(enum hackrf_error e) { (void)e; return "mock"; }

#ifdef BIAS_IQ_MOCK_LIBRARY
IQAPI int IQCALL mock_bias_calls(void) { return bias_calls; }
IQAPI int IQCALL mock_bias_value(void) { return bias_value; }
IQAPI int IQCALL mock_rx_calls(void) { return rx_calls; }
#endif

int main(void) {
	int result = hackrf_iq_lib_start_with_bias(callback, 100000000, 2600000, 0, 16, 20, 1, 1);
	if (result != 0 || bias_calls != 1 || bias_value != 1 || rx_calls != 1 || closes != 1) return 1;
	result = hackrf_iq_lib_start_with_bias(callback, 100000000, 2600000, 0, 16, 20, 1, 0);
	if (result != 0 || bias_calls != 2 || bias_value != 0 || rx_calls != 2 || closes != 2) return 2;
	result = hackrf_iq_lib_start(callback, 100000000, 2600000, 0, 16, 20, 1);
	if (result != 0 || bias_calls != 2 || rx_calls != 3 || closes != 3) return 3;
	bias_failure = HACKRF_ERROR_OTHER;
	result = hackrf_iq_lib_start_with_bias(callback, 100000000, 2600000, 0, 16, 20, 1, 1);
	if (result != HACKRF_ERROR_OTHER || bias_calls != 3 || rx_calls != 3 || closes != 4) return 4;
	puts("IQ BiasT on, off, legacy and error tests passed");
	return 0;
}
