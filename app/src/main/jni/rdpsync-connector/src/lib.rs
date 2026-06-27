use std::sync::{Arc, Condvar, Mutex};
use std::thread;
use std::time::Duration;

use ironrdp::connector::{self, Credentials};
use ironrdp::input::{Database, MouseButton, MousePosition, Operation, Scancode};
use ironrdp::pdu::gcc;
use ironrdp::pdu::rdp::capability_sets::{client_codecs_capabilities, MajorPlatformType};
use ironrdp::pdu::rdp::client_info::{PerformanceFlags, TimezoneInfo};
use ironrdp_client::config::{ClipboardType, Config, Destination};
use ironrdp_client::rdp::{DvcPipeProxyFactory, RdpClient, RdpInputEvent, RdpOutputEvent};
use jni::objects::{JObject, JString};
use tokio::sync::mpsc;

#[derive(Default)]
struct FrameBuffer {
    width: u16,
    height: u16,
    pixels: Vec<i32>,
    frame_id: u64,
}

#[derive(Default)]
struct ConnectionState {
    status: String,
    connected: bool,
    last_error: String,
    terminated: bool,
}

struct AndroidRdpSession {
    frame: Mutex<FrameBuffer>,
    state: Mutex<ConnectionState>,
    state_cv: Condvar,
    input_tx: Mutex<Option<tokio::sync::mpsc::UnboundedSender<RdpInputEvent>>>,
    input_db: Mutex<Database>,
    last_button: Mutex<Option<MouseButton>>,
}

static GLOBAL_SESSION: Mutex<Option<Arc<AndroidRdpSession>>> = Mutex::new(None);

unsafe fn jstring_to_string(env: *mut jni::sys::JNIEnv, value: jni::sys::jstring) -> String {
    let mut jenv = jni::JNIEnv::from_raw(env).unwrap();
    let value = JString::from_raw(value);
    let owned = jenv.get_string(&value).unwrap().to_string_lossy().into_owned();
    owned
}

fn set_status(session: &AndroidRdpSession, status: impl Into<String>, connected: bool, terminated: bool) {
    let mut state = session.state.lock().unwrap();
    state.status = status.into();
    state.connected = connected;
    state.terminated = terminated;
    session.state_cv.notify_all();
}

fn humanize_rdp_error(error: impl Into<String>) -> String {
    let raw = error.into();
    let lower = raw.to_lowercase();
    if lower.contains("server requires enhanced rdp security with credssp") {
        format!("服务器强制要求 NLA/CredSSP 认证，已启用 CredSSP 后请重试；如果仍失败，请检查用户名/密码/域是否正确。原始错误: {raw}")
    } else if lower.contains("credssp") {
        format!("CredSSP/NLA 认证失败：请检查 Windows 用户名、密码、域；如果用户名是 域\\用户，请把域填到“域”输入框，用户名只填用户本身。原始错误: {raw}")
    } else if lower.contains("tcp connect") {
        format!("TCP 连接失败：请检查主机/IP、端口 3389、手机网络/VPN、Windows 防火墙和远程桌面是否已开启。原始错误: {raw}")
    } else if lower.contains("negotiation failure") {
        format!("RDP 协议协商失败：服务端安全策略和客户端认证模式不匹配。原始错误: {raw}")
    } else {
        format!("连接失败: {raw}")
    }
}

fn set_error(session: &AndroidRdpSession, error: impl Into<String>) {
    let mut state = session.state.lock().unwrap();
    state.last_error = humanize_rdp_error(error);
    state.connected = false;
    state.terminated = true;
    state.status = "连接失败".to_owned();
    session.state_cv.notify_all();
}

fn current_session() -> Option<Arc<AndroidRdpSession>> {
    GLOBAL_SESSION.lock().unwrap().as_ref().cloned()
}

fn build_config(
    host: String,
    port: u16,
    username: String,
    password: String,
    domain: String,
    width: u16,
    height: u16,
    enable_credssp: bool,
) -> Result<Config, String> {
    let codecs = client_codecs_capabilities(&[]).map_err(|e| e.to_string())?;
    let bitmap = connector::BitmapConfig {
        color_depth: 32,
        lossy_compression: true,
        codecs,
    };

    let connector = connector::Config {
        credentials: Credentials::UsernamePassword { username, password },
        domain: (!domain.trim().is_empty()).then_some(domain),
        enable_tls: true,
        enable_credssp,
        keyboard_type: gcc::KeyboardType::IbmEnhanced,
        keyboard_subtype: 0,
        keyboard_layout: 0,
        keyboard_functional_keys_count: 12,
        ime_file_name: String::new(),
        dig_product_id: String::new(),
        desktop_size: connector::DesktopSize { width, height },
        desktop_scale_factor: 0,
        bitmap: Some(bitmap),
        client_build: 100,
        client_name: "RDPSYNC".to_owned(),
        client_dir: "C:\\Windows\\System32\\mstscax.dll".to_owned(),
        platform: MajorPlatformType::ANDROID,
        hardware_id: None,
        license_cache: None,
        enable_server_pointer: false,
        autologon: !enable_credssp,
        enable_audio_playback: false,
        request_data: None,
        pointer_software_rendering: true,
        multitransport_flags: None,
        compression_type: None,
        performance_flags: PerformanceFlags::DISABLE_WALLPAPER
            | PerformanceFlags::DISABLE_FULLWINDOWDRAG
            | PerformanceFlags::DISABLE_MENUANIMATIONS
            | PerformanceFlags::DISABLE_THEMING,
        timezone_info: TimezoneInfo::default(),
        alternate_shell: String::new(),
        work_dir: String::new(),
    };

    Ok(Config {
        log_file: None,
        gw: None,
        kerberos_config: None,
        destination: Destination::from_parts(host, port),
        connector,
        clipboard_type: ClipboardType::Disable,
        rdcleanpath: None,
        fake_events_interval: None,
        dvc_pipe_proxies: Vec::new(),
    })
}

fn spawn_client(session: Arc<AndroidRdpSession>, config: Config) {
    thread::spawn(move || {
        set_status(&session, "正在建立 RDP 会话（NLA/CredSSP）", false, false);
        let rt = match tokio::runtime::Builder::new_current_thread().enable_all().build() {
            Ok(rt) => rt,
            Err(e) => {
                set_error(&session, format!("创建运行时失败: {e}"));
                return;
            }
        };

        rt.block_on(async move {
            let (input_tx, input_rx) = RdpInputEvent::create_channel();
            *session.input_tx.lock().unwrap() = Some(input_tx.clone());
            let (output_tx, mut output_rx) = mpsc::channel::<RdpOutputEvent>(16);
            let dvc_factory = DvcPipeProxyFactory::new(input_tx);
            let client = RdpClient {
                config,
                output_event_sender: output_tx,
                input_event_receiver: input_rx,
                cliprdr_factory: None,
                dvc_pipe_proxy_factory: dvc_factory,
            };

            let session_for_output = session.clone();
            let output_loop = async move {
                while let Some(event) = output_rx.recv().await {
                    match event {
                        RdpOutputEvent::Image { buffer, width, height } => {
                            let mut frame = session_for_output.frame.lock().unwrap();
                            frame.width = width.get();
                            frame.height = height.get();
                            frame.pixels.clear();
                            frame.pixels.reserve(buffer.len());
                            for pixel in buffer {
                                let argb = 0xff00_0000_u32 | (pixel & 0x00ff_ffff);
                                frame.pixels.push(argb as i32);
                            }
                            frame.frame_id = frame.frame_id.wrapping_add(1);
                            drop(frame);
                            set_status(&session_for_output, "已连接", true, false);
                        }
                        RdpOutputEvent::ConnectionFailure(e) => {
                            set_error(&session_for_output, format!("连接失败: {e}"));
                            break;
                        }
                        RdpOutputEvent::Terminated(result) => {
                            match result {
                                Ok(reason) => set_error(&session_for_output, format!("连接已结束: {reason:?}")),
                                Err(e) => set_error(&session_for_output, format!("会话错误: {e}")),
                            }
                            break;
                        }
                        RdpOutputEvent::PointerDefault
                        | RdpOutputEvent::PointerHidden
                        | RdpOutputEvent::PointerPosition { .. }
                        | RdpOutputEvent::PointerBitmap(_) => {}
                    }
                }
            };

            tokio::join!(async move { client.run().await }, output_loop);

            let _ = session
                .input_tx
                .lock()
                .unwrap()
                .take()
                .map(|tx| tx.send(RdpInputEvent::Close));
        });
    });
}

fn send_operation(session: &AndroidRdpSession, operation: Operation) -> i32 {
    let events = session.input_db.lock().unwrap().apply([operation]);
    if events.is_empty() {
        return 0;
    }
    let guard = session.input_tx.lock().unwrap();
    if let Some(tx) = guard.as_ref() {
        tx.send(RdpInputEvent::FastPath(events)).map(|_| 0).unwrap_or(-1)
    } else {
        -1
    }
}

fn button_from_i32(button: i32) -> Option<MouseButton> {
    match button {
        1 => Some(MouseButton::Left),
        2 => Some(MouseButton::Right),
        3 => Some(MouseButton::Middle),
        _ => None,
    }
}

#[no_mangle]
pub extern "C" fn Java_com_rdp_sync_network_RdpConnector_nativeConnect(
    env: *mut jni::sys::JNIEnv,
    _this: jni::sys::jobject,
    host: jni::sys::jstring,
    port: jni::sys::jint,
    username: jni::sys::jstring,
    password: jni::sys::jstring,
    domain: jni::sys::jstring,
    width: jni::sys::jint,
    height: jni::sys::jint,
) -> jni::sys::jint {
    let host_str = unsafe { jstring_to_string(env, host) };
    let mut username_str = unsafe { jstring_to_string(env, username) };
    let password_str = unsafe { jstring_to_string(env, password) };
    let mut domain_str = unsafe { jstring_to_string(env, domain) };
    if domain_str.trim().is_empty() {
        if let Some((domain_part, user_part)) = username_str.split_once('\\') {
            domain_str = domain_part.trim().to_owned();
            username_str = user_part.trim().to_owned();
        }
    }
    let port = (port as u16).max(1);
    let width = (width as u16).clamp(320, 3840);
    let height = (height as u16).clamp(240, 2160);

    let session = Arc::new(AndroidRdpSession {
        frame: Mutex::new(FrameBuffer { width, height, pixels: Vec::new(), frame_id: 0 }),
        state: Mutex::new(ConnectionState { status: "正在连接".to_owned(), connected: false, last_error: String::new(), terminated: false }),
        state_cv: Condvar::new(),
        input_tx: Mutex::new(None),
        input_db: Mutex::new(Database::new()),
        last_button: Mutex::new(None),
    });

    if let Some(old) = GLOBAL_SESSION.lock().unwrap().replace(session.clone()) {
        if let Some(tx) = old.input_tx.lock().unwrap().take() {
            let _ = tx.send(RdpInputEvent::Close);
        }
    }

    match build_config(host_str, port, username_str, password_str, domain_str, width, height, true) {
        Ok(config) => spawn_client(session.clone(), config),
        Err(e) => {
            set_error(&session, e);
            return 0;
        }
    }

    // Wait briefly until the worker has installed the input channel or reports an immediate error.
    let guard = session.state.lock().unwrap();
    let _ = session.state_cv.wait_timeout(guard, Duration::from_millis(300));
    1
}

#[no_mangle]
pub extern "C" fn Java_com_rdp_sync_network_RdpConnector_nativeDisconnect(
    _env: *mut jni::sys::JNIEnv,
    _this: jni::sys::jobject,
) -> core::ffi::c_int {
    if let Some(session) = GLOBAL_SESSION.lock().unwrap().take() {
        if let Some(tx) = session.input_tx.lock().unwrap().take() {
            let _ = tx.send(RdpInputEvent::Close);
        }
        set_status(&session, "已断开", false, true);
    }
    0
}

#[no_mangle]
pub extern "C" fn Java_com_rdp_sync_network_RdpConnector_nativeIsConnected(
    _env: *mut jni::sys::JNIEnv,
    _this: jni::sys::jobject,
) -> jni::sys::jboolean {
    current_session()
        .map(|s| if s.state.lock().unwrap().connected { 1 } else { 0 })
        .unwrap_or(0)
}

#[no_mangle]
pub extern "C" fn Java_com_rdp_sync_network_RdpConnector_nativeGetStatus(
    env: *mut jni::sys::JNIEnv,
    _this: jni::sys::jobject,
) -> jni::sys::jstring {
    let text = current_session()
        .map(|s| {
            let state = s.state.lock().unwrap();
            if !state.last_error.is_empty() {
                state.last_error.clone()
            } else {
                state.status.clone()
            }
        })
        .unwrap_or_else(|| "未连接".to_owned());
    unsafe {
        let jenv = jni::JNIEnv::from_raw(env).unwrap();
        jenv.new_string(text)
            .map(|s| s.into_raw())
            .unwrap_or_else(|_| JObject::null().into_raw() as jni::sys::jstring)
    }
}

#[no_mangle]
pub extern "C" fn Java_com_rdp_sync_network_RdpConnector_nativeGetWidth(
    _env: *mut jni::sys::JNIEnv,
    _this: jni::sys::jobject,
) -> jni::sys::jint {
    current_session()
        .map(|s| s.frame.lock().unwrap().width as i32)
        .unwrap_or(1280)
}

#[no_mangle]
pub extern "C" fn Java_com_rdp_sync_network_RdpConnector_nativeGetHeight(
    _env: *mut jni::sys::JNIEnv,
    _this: jni::sys::jobject,
) -> jni::sys::jint {
    current_session()
        .map(|s| s.frame.lock().unwrap().height as i32)
        .unwrap_or(720)
}

#[no_mangle]
pub extern "C" fn Java_com_rdp_sync_network_RdpConnector_nativeGetFrameArgb(
    env: *mut jni::sys::JNIEnv,
    _this: jni::sys::jobject,
    _req_width: jni::sys::jint,
    _req_height: jni::sys::jint,
) -> jni::sys::jintArray {
    let Some(session) = current_session() else { return std::ptr::null_mut(); };
    let frame = session.frame.lock().unwrap();
    if frame.pixels.is_empty() {
        return std::ptr::null_mut();
    }
    unsafe {
        let jenv = jni::JNIEnv::from_raw(env).unwrap();
        let array = jenv.new_int_array(frame.pixels.len() as i32).unwrap();
        jenv.set_int_array_region(&array, 0, &frame.pixels).unwrap();
        array.into_raw()
    }
}

#[no_mangle]
pub extern "C" fn Java_com_rdp_sync_network_RdpConnector_nativeSendPointerEvent(
    _env: *mut jni::sys::JNIEnv,
    _this: jni::sys::jobject,
    x: jni::sys::jint,
    y: jni::sys::jint,
    button: jni::sys::jint,
) -> core::ffi::c_int {
    let Some(session) = current_session() else { return -1; };
    let frame = session.frame.lock().unwrap();
    let width = frame.width.max(1);
    let height = frame.height.max(1);
    drop(frame);
    let x = (x as i32).clamp(0, i32::from(width - 1)) as u16;
    let y = (y as i32).clamp(0, i32::from(height - 1)) as u16;
    let _ = send_operation(&session, Operation::MouseMove(MousePosition { x, y }));

    let mut last = session.last_button.lock().unwrap();
    let new_button = button_from_i32(button);
    if *last != new_button {
        if let Some(old_button) = *last {
            let _ = send_operation(&session, Operation::MouseButtonReleased(old_button));
        }
        if let Some(new_button) = new_button {
            let _ = send_operation(&session, Operation::MouseButtonPressed(new_button));
        }
        *last = new_button;
    }
    0
}

#[no_mangle]
pub extern "C" fn Java_com_rdp_sync_network_RdpConnector_nativeSendKeyEvent(
    _env: *mut jni::sys::JNIEnv,
    _this: jni::sys::jobject,
    key_code: jni::sys::jint,
    down: jni::sys::jint,
) -> core::ffi::c_int {
    let Some(session) = current_session() else { return -1; };
    let raw = if key_code == 0x53 { 0xE053 } else { key_code as u16 };
    let scancode = Scancode::from_u16(raw);
    let op = if down != 0 {
        Operation::KeyPressed(scancode)
    } else {
        Operation::KeyReleased(scancode)
    };
    send_operation(&session, op)
}

#[no_mangle]
pub extern "C" fn Java_com_rdp_sync_network_RdpConnector_nativeSendClipboardText(
    env: *mut jni::sys::JNIEnv,
    _this: jni::sys::jobject,
    text: jni::sys::jstring,
) -> core::ffi::c_int {
    let Some(session) = current_session() else { return -1; };
    let text = unsafe { jstring_to_string(env, text) };
    for ch in text.chars() {
        let _ = send_operation(&session, Operation::UnicodeKeyPressed(ch));
        let _ = send_operation(&session, Operation::UnicodeKeyReleased(ch));
    }
    0
}
