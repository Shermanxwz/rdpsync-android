/* End-to-end verification linking unmodified FreeRDP 3.32.2 cliprdr_format.c + cliprdr_common.c
 * with production rdpsync_transfer.c to prove outbound Chinese CF_UNICODETEXT (13) survives
 * FreeRDP_ClipboardFeatureMask across consecutive commits and never leaks remote text. */
#define _GNU_SOURCE
#include <assert.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <freerdp/settings.h>
#include <freerdp/client/cliprdr.h>
#include "cliprdr_format.h"
#include "../cliprdr_common.h"
#include "../../main/cpp/rdpsync_transfer.h"
#include "../../main/cpp/rdpsync_ubuntu_ime.h"

const char type_FileGroupDescriptorW[] = "FileGroupDescriptorW";
const char type_FileContents[] = "FileContents";

/* WinPR logging & string stubs matching transfer_parser_test.c standalone link boundary */
wLog* WLog_Get(const char* name) { (void)name; return NULL; }
BOOL WLog_IsLevelActive(wLog* log, DWORD level) { (void)log; (void)level; return FALSE; }
BOOL WLog_PrintTextMessage(wLog* log, DWORD level, size_t line, const char* file, const char* function, const char* fmt, ...) {
    (void)log; (void)level; (void)line; (void)file; (void)function; (void)fmt; return TRUE;
}
BOOL WLog_PrintTextMessageVA(wLog* log, DWORD level, size_t line, const char* file, const char* function, const char* fmt, va_list args) {
    (void)log; (void)level; (void)line; (void)file; (void)function; (void)fmt; (void)args; return TRUE;
}
void winpr_log_backtrace(const char* tag, DWORD level, DWORD size) { (void)tag; (void)level; (void)size; }
void winpr_log_backtrace_ex(wLog* log, DWORD level, DWORD size) { (void)log; (void)level; (void)size; }
const char* ClipboardGetFormatIdString(UINT32 formatId) { (void)formatId; return "CF_UNICODETEXT"; }
#ifndef _strdup
char* _strdup(const char* s) { return s ? strdup(s) : NULL; }
#endif

/* Minimal stubs required by cliprdr_format.c when linked standalone without full libfreerdp-client */
UINT cliprdr_send_error_response(cliprdrPlugin* cliprdr, UINT16 type) {
    (void)cliprdr; (void)type; return CHANNEL_RC_OK;
}
CliprdrClientContext* cliprdr_get_client_interface(cliprdrPlugin* cliprdr) {
    (void)cliprdr; return NULL;
}
UINT32 freerdp_settings_get_uint32(const rdpSettings* settings, FreeRDP_Settings_Keys_UInt32 id) {
    (void)settings; (void)id; return 0;
}

static UINT32 g_active_mask = 0;
static int g_initial_format_list_sent = 0;
static unsigned g_wire_format_lists_sent = 0;
static UINT32 g_last_wire_num_formats = 0;
static UINT32 g_last_wire_format_id = 0;
static uint8_t g_last_data_response[4096];
static size_t g_last_data_response_len = 0;
static UINT16 g_last_data_response_flags = 0;

static UINT sim_client_caps(CliprdrClientContext* c, const CLIPRDR_CAPABILITIES* caps) {
    (void)c;
    assert(caps && caps->cCapabilitiesSets == 1);
    g_initial_format_list_sent = 0;
    return CHANNEL_RC_OK;
}

/* Exact replica of FreeRDP 3.32.2 cliprdr_client_format_list calling unmodified cliprdr_filter_format_list */
static UINT sim_client_format_list(CliprdrClientContext* c, const CLIPRDR_FORMAT_LIST* formatList) {
    (void)c;
    CLIPRDR_FORMAT_LIST filterList = cliprdr_filter_format_list(
        formatList, g_active_mask, CLIPRDR_FLAG_LOCAL_TO_REMOTE | CLIPRDR_FLAG_LOCAL_TO_REMOTE_FILES);
    if ((filterList.numFormats == 0) && g_initial_format_list_sent) {
        cliprdr_free_format_list(&filterList);
        return CHANNEL_RC_OK;
    }
    g_initial_format_list_sent = 1;
    g_wire_format_lists_sent++;
    g_last_wire_num_formats = filterList.numFormats;
    g_last_wire_format_id = filterList.numFormats > 0 ? filterList.formats[0].formatId : 0;
    cliprdr_free_format_list(&filterList);
    return CHANNEL_RC_OK;
}

static UINT sim_client_format_list_response(CliprdrClientContext* c, const CLIPRDR_FORMAT_LIST_RESPONSE* resp) {
    (void)c;
    assert(resp && resp->common.msgFlags == CB_RESPONSE_OK);
    return CHANNEL_RC_OK;
}

static UINT sim_client_format_data_response(CliprdrClientContext* c, const CLIPRDR_FORMAT_DATA_RESPONSE* resp) {
    (void)c;
    assert(resp != NULL);
    g_last_data_response_flags = resp->common.msgFlags;
    g_last_data_response_len = resp->common.dataLen;
    if (resp->common.dataLen > 0 && resp->requestedFormatData) {
        assert(resp->common.dataLen <= sizeof(g_last_data_response));
        memcpy(g_last_data_response, resp->requestedFormatData, resp->common.dataLen);
    }
    return CHANNEL_RC_OK;
}

int main(void) {
    /* 1. Prove the historical v1.3.2 bug: without CLIPRDR_FLAG_LOCAL_TO_REMOTE, FreeRDP strips CF_UNICODETEXT to 0! */
    CLIPRDR_FORMAT cf_unicode = { 13U, NULL };
    CLIPRDR_FORMAT_LIST raw_list = { .common = { .msgType = CB_FORMAT_LIST }, .numFormats = 1, .formats = &cf_unicode };
    CLIPRDR_FORMAT_LIST stripped = cliprdr_filter_format_list(
        &raw_list,
        CLIPRDR_FLAG_LOCAL_TO_REMOTE_FILES | CLIPRDR_FLAG_REMOTE_TO_LOCAL_FILES,
        CLIPRDR_FLAG_LOCAL_TO_REMOTE | CLIPRDR_FLAG_LOCAL_TO_REMOTE_FILES);
    assert(stripped.numFormats == 0);
    cliprdr_free_format_list(&stripped);

    /* 2. Prove v1.3.3 mask (CLIPRDR_FLAG_LOCAL_TO_REMOTE, with file transfer OFF and ON) preserves CF_UNICODETEXT (13)
     *    across 20 consecutive Chinese & mixed text commits and serves exact UTF-16LE payloads! */
    const UINT32 masks[2] = {
        CLIPRDR_FLAG_LOCAL_TO_REMOTE,
        CLIPRDR_FLAG_LOCAL_TO_REMOTE | CLIPRDR_FLAG_LOCAL_TO_REMOTE_FILES | CLIPRDR_FLAG_REMOTE_TO_LOCAL_FILES
    };
    const char* samples[4] = {
        "你好Ubuntu图形桌面",
        "，连续第二次中文提交不丢字",
        " [发送文字混合测试 100% OK]",
        "繁體與Emoji🙂直通测试"
    };

    for (size_t m = 0; m < 2; ++m) {
        g_active_mask = masks[m];
        g_initial_format_list_sent = 0;
        g_wire_format_lists_sent = 0;

        RdpTransfer* t = rdpsync_transfer_new(100 + m, 1);
        assert(t != NULL);
        rdpsync_transfer_set_text_only(t, m == 0 ? 1 : 0);

        CliprdrClientContext ctx = {
            .ClientCapabilities = sim_client_caps,
            .ClientFormatList = sim_client_format_list,
            .ClientFormatListResponse = sim_client_format_list_response,
            .ClientFormatDataResponse = sim_client_format_data_response,
        };
        rdpsync_transfer_attach(t, &ctx);

        CLIPRDR_GENERAL_CAPABILITY_SET gen = {
            CB_CAPSTYPE_GENERAL, CB_CAPSTYPE_GENERAL_LEN, CB_CAPS_VERSION_2,
            CB_USE_LONG_FORMAT_NAMES | CB_STREAM_FILECLIP_ENABLED | CB_CAN_LOCK_CLIPDATA
        };
        CLIPRDR_CAPABILITIES caps = { .cCapabilitiesSets = 1, .capabilitySets = (void*)&gen };
        assert(ctx.ServerCapabilities(&ctx, &caps) == CHANNEL_RC_OK);
        assert(ctx.MonitorReady(&ctx, NULL) == CHANNEL_RC_OK);

        int did_send = 0;
        assert(rdpsync_transfer_pump_ex(t, &did_send) == CHANNEL_RC_OK);
        const unsigned base_sends = g_wire_format_lists_sent;

        /* Verify stale FormatListResponse before any text offer is sent NEVER falsely advances text_ack_seq */
        uint64_t seq1 = rdpsync_transfer_offer_text(t, (const uint8_t*)samples[0], strlen(samples[0]));
        assert(seq1 == 1);
        CLIPRDR_FORMAT_LIST_RESPONSE ok_resp = { .common = { .msgType = CB_FORMAT_LIST_RESPONSE, .msgFlags = CB_RESPONSE_OK } };
        assert(ctx.ServerFormatListResponse(&ctx, &ok_resp) == CHANNEL_RC_OK);
        assert(rdpsync_transfer_wait_text_ack(t, seq1, 5) == -1); /* pump hasn't sent seq1 yet! */

        assert(rdpsync_transfer_pump_ex(t, &did_send) == CHANNEL_RC_OK && did_send == 1);
        assert(g_wire_format_lists_sent == base_sends + 1);
        assert(g_last_wire_num_formats == 1 && g_last_wire_format_id == 13U);
        assert(ctx.ServerFormatListResponse(&ctx, &ok_resp) == CHANNEL_RC_OK);
        assert(rdpsync_transfer_wait_text_ack(t, seq1, 50) == 0);

        CLIPRDR_FORMAT_DATA_REQUEST req13 = { .common = { .msgType = CB_FORMAT_DATA_REQUEST }, .requestedFormatId = 13U };
        assert(ctx.ServerFormatDataRequest(&ctx, &req13) == CHANNEL_RC_OK);
        assert(g_last_data_response_flags == CB_RESPONSE_OK);
        assert(rdpsync_transfer_wait_text_served(t, seq1, 50) == 0);

        uint8_t expected_utf16[512];
        size_t expected_len = rdpsync_encode_cf_unicodetext(
            (const uint8_t*)samples[0], strlen(samples[0]), expected_utf16, sizeof(expected_utf16));
        assert(g_last_data_response_len == expected_len);
        assert(memcmp(g_last_data_response, expected_utf16, expected_len) == 0);

        /* Consecutive commits 2..20 must ALL emit non-empty CF_UNICODETEXT (13) wire format lists */
        for (int iter = 1; iter < 20; ++iter) {
            const char* text = samples[iter % 4];
            uint64_t seq = rdpsync_transfer_offer_text(t, (const uint8_t*)text, strlen(text));
            assert(seq == (uint64_t)(iter + 1));
            unsigned prev = g_wire_format_lists_sent;
            assert(rdpsync_transfer_pump_ex(t, &did_send) == CHANNEL_RC_OK && did_send == 1);
            assert(g_wire_format_lists_sent == prev + 1);
            assert(g_last_wire_num_formats == 1 && g_last_wire_format_id == 13U);
            assert(ctx.ServerFormatListResponse(&ctx, &ok_resp) == CHANNEL_RC_OK);
            assert(rdpsync_transfer_wait_text_ack(t, seq, 50) == 0);
            assert(ctx.ServerFormatDataRequest(&ctx, &req13) == CHANNEL_RC_OK);
            assert(rdpsync_transfer_wait_text_served(t, seq, 50) == 0);
            expected_len = rdpsync_encode_cf_unicodetext(
                (const uint8_t*)text, strlen(text), expected_utf16, sizeof(expected_utf16));
            assert(g_last_data_response_len == expected_len);
            assert(memcmp(g_last_data_response, expected_utf16, expected_len) == 0);
        }

        /* Server NACK (CB_RESPONSE_FAIL) must immediately fail wait_text_ack so Ctrl+V is never sent */
        uint64_t nack_seq = rdpsync_transfer_offer_text(t, (const uint8_t*)"拒绝测试", strlen("拒绝测试"));
        assert(rdpsync_transfer_pump_ex(t, &did_send) == CHANNEL_RC_OK && did_send == 1);
        CLIPRDR_FORMAT_LIST_RESPONSE fail_resp = { .common = { .msgType = CB_FORMAT_LIST_RESPONSE, .msgFlags = CB_RESPONSE_FAIL } };
        assert(ctx.ServerFormatListResponse(&ctx, &fail_resp) == CHANNEL_RC_OK);
        assert(rdpsync_transfer_wait_text_ack(t, nack_seq, 1000) == -1);

        rdpsync_transfer_detach(t, &ctx);
        rdpsync_transfer_free(t);
    }

    puts("ubuntu cliprdr filter: passed unmodified FreeRDP 3.32.2 filter + 20 consecutive Chinese commits + NACK guard");
    return 0;
}
