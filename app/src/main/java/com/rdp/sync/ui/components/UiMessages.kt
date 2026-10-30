package com.rdp.sync.ui.components

import android.content.Context
import com.rdp.sync.R

/** Presentation-only translation of existing safe, fixed backend status vocabulary.
 * Protocol state machines keep their original values. Raw diagnostics are never translated.
 * Unknown details remain intact rather than being guessed or hidden.
 */
internal object UiMessages {
    private val known = listOf(
        "已上传 " to R.string.status_uploaded_prefix,
        " 台设备至 " to R.string.status_profiles_to,
        "含明文密码" to R.string.status_includes_passwords,
        "不含密码，旧版文件未改动" to R.string.status_excludes_passwords,
        "已导入 " to R.string.status_imported_prefix,
        " 台设备；未匹配的本机设备保留，缺失密码不覆盖本机密码" to R.string.status_imported_suffix,
        "已合并 " to R.string.status_merged_prefix,
        " 台设备（相同设备以本机为准）；文件：" to R.string.status_merged_suffix,
        "云端没有 " to R.string.status_cloud_missing,
        "。旧版备份请明确选择「旧版含密码文件」；本机设备未改动。" to R.string.status_cloud_legacy_help,
        "远程桌面运行检查失败（阶段：" to R.string.status_runtime_check,
        "无法创建远程桌面会话（阶段：" to R.string.status_runtime_create,

        "WebDAV 密码无法解密，请在设置中重新输入；原有数据已保留。" to R.string.status_the_webdav_password_cannot_be_decrypted_re_enter_it_in_settings_t,
        "WebDAV 设置已加密保存" to R.string.status_webdav_settings_saved_with_encrypted_credentials,
        "请先重新输入无法解密的 WebDAV 密码" to R.string.status_re_enter_the_unavailable_webdav_password_first,
        "同步失败: " to R.string.status_sync_failed,
        "WebDAV 测试失败: " to R.string.status_webdav_test_failed,
        "本机凭据无法解密或保存，请检查存储空间，必要时明确重新输入密码；原数据已保留。" to R.string.status_local_credentials_could_not_be_decrypted_or_saved_check_free_spac,
        "操作失败，请检查网络或可用存储空间后重试；未保存的更改不会标为成功。" to R.string.status_the_operation_failed_check_your_network_or_free_storage_and_try_a,
        "云端文件格式不正确，请检查备份格式；未写入云端或导入本机。" to R.string.status_the_cloud_file_format_is_invalid_check_the_backup_format_nothing,
        "请先填写有效的 WebDAV 地址、用户名和密码。" to R.string.status_enter_a_valid_webdav_url_username_and_password_first,
        "WebDAV 请求失败，请检查网络、HTTPS 证书和服务器地址后重试。" to R.string.status_the_webdav_request_failed_check_the_network_https_certificate_and,
        "认证失败或无权限，请检查用户名、密码和目录权限。" to R.string.status_authentication_failed_or_access_was_denied_check_your_username_pa,
        "WebDAV 路径不存在。" to R.string.status_the_webdav_path_does_not_exist,
        "服务端不允许当前方法，请检查 WebDAV 目录地址。" to R.string.status_the_server_does_not_allow_this_method_check_the_webdav_collection,
        "父目录不存在，请检查目录权限。" to R.string.status_the_parent_folder_does_not_exist_check_folder_permissions,
        "WebDAV 服务器请求失败。" to R.string.status_the_webdav_server_request_failed,
        "WebDAV 连接正常，目录可读写。" to R.string.status_webdav_connected_the_collection_is_readable_and_writable,
        "WebDAV 目录检查失败" to R.string.status_webdav_collection_check_failed,
        "WebDAV 读写测试失败" to R.string.status_webdav_read_write_test_failed,
        "远程桌面运行目录初始化失败，请检查本机可用存储空间。" to R.string.status_the_desktop_runtime_folder_could_not_be_initialized_check_local_f,
        "远程桌面运行目录未初始化，请重新打开应用并检查本机存储空间。" to R.string.status_the_desktop_runtime_folder_is_not_initialized_reopen_the_app_and,
        "正在检查文件传输支持" to R.string.status_checking_file_transfer_support,
        "已生成本机只读副本；尚未提供给远程端" to R.string.status_local_read_only_copies_prepared_not_yet_offered_remotely,
        "已提供文件列表；请回到远程文件管理器粘贴。读取字节数不代表远程已保存。" to R.string.status_file_list_offered_paste_in_the_remote_file_manager_bytes_read_do,
        "仅获取文件列表，尚未读取内容；确认后才下载到本机暂存区" to R.string.status_only_the_file_list_has_been_read_not_the_contents_confirm_to_down,
        "已下载并计算本机 SHA-256；请选择目标目录导出。远程校验声明不作为可信完整性证明。" to R.string.status_downloaded_and_hashed_locally_with_sha_256_choose_an_export_folde,
        "已创建新文件并回读校验 SHA-256；暂存原件仍保留。云端持久化由文档提供方负责。" to R.string.status_a_new_file_was_created_and_read_back_to_verify_sha_256_the_staged,
        "已停止共享或取消传输；完整暂存文件保留至会话结束" to R.string.status_sharing_stopped_or_transfer_canceled_complete_staged_files_remain,
        "连接已变化，请重新打开文件传输" to R.string.status_the_connection_changed_reopen_file_transfer,
        "文件传输未启用；需要明确同意后重新连接" to R.string.status_file_transfer_is_disabled_explicit_consent_and_a_reconnect_are_re,
        "正在协商文件剪贴板功能，请稍后刷新" to R.string.status_negotiating_clipboard_file_support_refresh_shortly,
        "远程端未提供文件剪贴板流传输能力" to R.string.status_the_remote_server_does_not_offer_streamed_clipboard_file_transfer,
        "文件名不安全或无法兼容远程系统，请重命名后重试。" to R.string.status_the_filename_is_unsafe_or_incompatible_with_the_remote_system_ren,
        "文件名重复（含大小写或 Unicode 等价名称），请重命名后重试。" to R.string.status_filenames_collide_including_case_or_unicode_equivalents_rename_th,
        "超出文件传输限制：最多 64 个文件、单个 128 MiB、会话暂存共 256 MiB。" to R.string.status_transfer_limit_exceeded_up_to_64_files_128_mib_each_and_256_mib_t,
        "本次 RDP 连接剩余发送额度不足（累计最多 32 MiB，重复读取也计入）；取消不会重置，请自行重新连接后重试。" to R.string.status_this_connection_s_send_allowance_is_exhausted_32_mib_total_includ,
        "暂存空间不足，请释放空间后重试（保留至少 64 MiB）。" to R.string.status_insufficient_staging_space_free_some_storage_and_retry_at_least_6,
        "文件实际大小与声明不一致；未提供或导出不完整文件。" to R.string.status_the_actual_size_differs_from_the_declared_size_no_incomplete_file,
        "文件传输已取消；原始文件未修改。" to R.string.status_transfer_canceled_original_files_were_not_modified,
        "会话已结束，请重新选择文件。" to R.string.status_the_session_ended_select_the_files_again,
        "另一项文件操作尚未完成，请稍后重试。" to R.string.status_another_file_operation_is_running_try_again_shortly,
        "远程端不支持文件快照锁定；停止共享后再次发送或替换文件，需要你手动重新连接。不会自动重连。" to R.string.status_the_remote_server_does_not_support_file_snapshot_locking_after_st,
        "此连接不支持文件剪贴板传输。" to R.string.status_this_connection_does_not_support_clipboard_file_transfer,
        "目前仅支持普通文件；文件夹、快捷方式或虚拟文档不受支持。" to R.string.status_only_regular_files_are_supported_not_folders_shortcuts_or_virtual,
        "远程文件列表或会话已变化，请重新获取并确认。" to R.string.status_the_remote_file_list_or_session_changed_read_the_list_again_and_c,
        "文件读写失败，原始文件与完整暂存副本未修改，请检查权限或空间。" to R.string.status_file_access_failed_original_files_and_complete_staged_copies_were,
        "文档提供方无法确认安全的新建目标；未覆盖现有文件，但提供方可能已创建一个空文件。" to R.string.status_the_provider_could_not_verify_a_safe_new_destination_no_existing,
        "导出未完成；暂存原件仍保留，目标目录可能残留一个新建的不完整副本。" to R.string.status_export_did_not_complete_the_staged_original_remains_the_destinati,
        "自适应已关闭" to R.string.status_adaptive_quality_is_off,
        "等待连接" to R.string.status_waiting_for_connection,
        "等待有效网络测量（静止画面不会降级）" to R.string.status_waiting_for_valid_network_measurements_a_static_desktop_will_not,
        "等待有效网络测量" to R.string.status_waiting_for_valid_network_measurements,
        "网络测量不可用 / 等待中" to R.string.status_network_measurements_unavailable_pending,
        "等待新的网络测量" to R.string.status_waiting_for_new_network_measurements,
        "本机渲染压力（非网速判断）" to R.string.status_local_rendering_pressure_not_a_network_speed_estimate,
        "网络延迟 / 带宽压力" to R.string.status_network_latency_bandwidth_pressure,
        "网络稳定" to R.string.status_network_stable,
        "采样中" to R.string.status_measuring,
        "（等待服务器）" to R.string.status_waiting_for_server,
        "服务器未启用动态分辨率；重连应用设置" to R.string.status_dynamic_resolution_is_unavailable_reconnect_to_apply_settings,
        "服务器未确认新分辨率；保留当前画面，可手动重连应用设置" to R.string.status_the_server_did_not_confirm_the_new_resolution_the_current_desktop,
        "服务器尚未协商动态分辨率" to R.string.status_dynamic_resolution_has_not_been_negotiated,
        "目标分辨率超过服务器显示上限" to R.string.status_the_target_resolution_exceeds_the_server_display_limit,
        "动态分辨率请求失败；保留当前画面" to R.string.status_dynamic_resolution_request_failed_the_current_desktop_is_retained,
        "流畅 / 省流" to R.string.status_economy,
        "均衡" to R.string.status_balanced,
        "清晰" to R.string.status_sharp,
        "服务器桌面超出安全尺寸限制" to R.string.status_the_server_desktop_exceeds_safe_size_limits,
        "图形管线请求无效或超出安全内存限制" to R.string.status_the_graphics_request_is_invalid_or_exceeds_safe_memory_limits,
        "无法初始化远程图形管线，已阻止不完整会话" to R.string.status_graphics_initialization_failed_an_incomplete_session_was_blocked,
        "文件传输通道消息超出安全限制" to R.string.status_a_file_transfer_channel_message_exceeded_safety_limits,
        "文件传输通道收到未请求或超额的数据" to R.string.status_the_file_transfer_channel_received_unrequested_or_excess_data,
        "证书验证失败：连接目标已改变" to R.string.status_certificate_validation_failed_the_destination_changed,
        "证书验证失败：无法读取服务器证书" to R.string.status_certificate_validation_failed_the_server_certificate_could_not_be,
        "服务器证书已改变，已阻止连接，请核对 SHA-256 指纹" to R.string.status_the_server_certificate_changed_connection_blocked_verify_its_sha,
        "首次连接需核对服务器证书 SHA-256 指纹" to R.string.status_first_connection_verify_the_server_certificate_s_sha_256_fingerpr,
        "已阻止服务器重定向；请为新目标创建独立连接并验证证书" to R.string.status_server_redirection_blocked_create_a_separate_profile_for_the_new,
        "通道隐私设置不一致，已阻止连接" to R.string.status_channel_privacy_settings_do_not_match_connection_blocked,
        "正在连接..." to R.string.status_connecting,
        "证书验证上下文未就绪，已阻止连接" to R.string.status_certificate_verification_is_not_ready_connection_blocked,
        "连接失败" to R.string.status_connection_failed,
        "文件传输通道发送失败" to R.string.status_file_transfer_channel_send_failed,
        "输入发送失败，连接已停止" to R.string.status_input_send_failed_the_connection_was_stopped,
        "连接中断" to R.string.status_connection_interrupted,
        "连接事件等待失败" to R.string.status_connection_event_wait_failed,
        "已断开" to R.string.status_disconnected,
        "输入队列过载，会话已停止" to R.string.status_input_queue_overloaded_the_session_was_stopped,
        "远程桌面私有运行目录初始化失败" to R.string.status_private_desktop_runtime_folder_initialization_failed,
        "连接已断开" to R.string.status_connection_disconnected,
        "未连接" to R.string.status_not_connected,
        "已连接" to R.string.status_connected
    ).sortedByDescending { it.first.length }

    fun text(context: Context, value: String): String {
        if (context.resources.configuration.locales[0].language == "zh") return value
        return translateUiMessage(value) { source ->
            val resource = known.firstOrNull { it.first == source }?.second ?: return@translateUiMessage null
            val translated = context.getString(resource)
            val prefix = if (source.startsWith(" ") && !translated.startsWith(" ")) " " else ""
            val suffix = if (source.endsWith(" ") && !translated.endsWith(" ")) " " else ""
            prefix + translated + suffix
        }
    }
}
