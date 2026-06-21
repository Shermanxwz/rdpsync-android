use std::sync::Mutex;

use ironrdp::connector::ClientConnector;
use ironrdp::connector::Config;
use ironrdp::connector::Credentials;
use ironrdp::connector::DesktopSize;
use ironrdp::connector::BitmapConfig;
use ironrdp::rdpdr::Rdpdr;
use ironrdp::rdpdr::NoopRdpdrBackend;
use ironrdp::rdpsnd::client::{NoopRdpsndBackend, Rdpsnd};
use jni::objects::JString;

struct RdpSession {
    width: u16,
    height: u16,
}

static mut GLOBAL_SESSION: Mutex<Option<RdpSession>> = Mutex::new(None);

#[no_mangle]
pub extern "C" fn Java_com_rdp_sync_network_RdpConnector_connect(
    env: *mut jni::sys::JNIEnv,
    _this: jni::sys::jobject,
    host: jni::sys::jstring,
    port: jni::sys::jint,
    username: jni::sys::jstring,
    password: jni::sys::jstring,
    domain: jni::sys::jstring,
) -> jni::sys::jint {
    unsafe {
        let mut jenv = jni::JNIEnv::from_raw(env).unwrap();

        let host = JString::from_raw(host);
        let host_str = jenv
            .get_string(&host)
            .unwrap()
            .to_string_lossy()
            .into_owned();

        let username = JString::from_raw(username);
        let username_str = jenv
            .get_string(&username)
            .unwrap()
            .to_string_lossy()
            .into_owned();

        let password = JString::from_raw(password);
        let password_str = jenv
            .get_string(&password)
            .unwrap()
            .to_string_lossy()
            .into_owned();

        let domain = JString::from_raw(domain);
        let domain_str = jenv
            .get_string(&domain)
            .unwrap()
            .to_string_lossy()
            .into_owned();

        let port = port as u16;
        let addr = format!("{}:{}", host_str, port);
        let client_addr = addr
            .parse::<std::net::SocketAddr>()
            .unwrap_or_else(|_| format!("{}:3389", host_str).parse().unwrap());

        let desktop_size = DesktopSize {
            width: 0,
            height: 0,
        };

        let bitmap = BitmapConfig {
            lossy_compression: false,
            color_depth: 32,
            codecs: ironrdp::pdu::rdp::capability_sets::BitmapCodecs::default(),
        };

        let credentials = Credentials::UsernamePassword {
            username: username_str,
            password: password_str,
        };

        let config = Config {
            desktop_size,
            desktop_scale_factor: 0,
            enable_tls: false,
            enable_credssp: true,
            credentials,
            domain: Some(domain_str),
            client_build: 0,
            client_name: "rdpsync".to_string(),
            keyboard_type: ironrdp::pdu::gcc::KeyboardType::IbmEnhanced,
            keyboard_subtype: 0,
            keyboard_functional_keys_count: 0,
            keyboard_layout: 0x00000409,
            ime_file_name: "".to_string(),
            bitmap: Some(bitmap),
            dig_product_id: "".to_string(),
            client_dir: "".to_string(),
            alternate_shell: "".to_string(),
            work_dir: "".to_string(),
            platform: ironrdp::pdu::rdp::capability_sets::MajorPlatformType::WINDOWS,
            hardware_id: None,
            request_data: None,
            autologon: false,
            enable_audio_playback: true,
            performance_flags: ironrdp::pdu::rdp::client_info::PerformanceFlags::DISABLE_WALLPAPER
                | ironrdp::pdu::rdp::client_info::PerformanceFlags::DISABLE_FULLWINDOWDRAG
                | ironrdp::pdu::rdp::client_info::PerformanceFlags::DISABLE_MENUANIMATIONS
                | ironrdp::pdu::rdp::client_info::PerformanceFlags::DISABLE_THEMING,
            license_cache: None,
            timezone_info: ironrdp::pdu::rdp::client_info::TimezoneInfo::default(),
            compression_type: None,
            enable_server_pointer: true,
            pointer_software_rendering: true,
            multitransport_flags: None,
        };

        let _connector = ClientConnector::new(config, client_addr)
            .with_static_channel(Rdpsnd::new(Box::new(NoopRdpsndBackend {})))
            .with_static_channel(
                Rdpdr::new(Box::new(NoopRdpdrBackend {}), "rdpsync".to_string()).with_smartcard(0),
            );

        let sess = RdpSession {
            width: 1280,
            height: 720,
        };

        {
            let mut global = GLOBAL_SESSION.lock().unwrap();
            *global = Some(sess);
        }

        1
    }
}

#[no_mangle]
pub extern "C" fn Java_com_rdp_sync_network_RdpConnector_disconnect(
    _env: *mut jni::sys::JNIEnv,
    _this: jni::sys::jobject,
) -> core::ffi::c_int {
    unsafe {
        let mut session = GLOBAL_SESSION.lock().unwrap();
        *session = None;
    }
    0
}

#[no_mangle]
pub extern "C" fn Java_com_rdp_sync_network_RdpConnector_get_width(
    _env: *mut jni::sys::JNIEnv,
    _this: jni::sys::jobject,
) -> jni::sys::jint {
    unsafe {
        let session = GLOBAL_SESSION.lock().unwrap();
        session.as_ref().map(|s| s.width as i32).unwrap_or(1280)
    }
}

#[no_mangle]
pub extern "C" fn Java_com_rdp_sync_network_RdpConnector_get_height(
    _env: *mut jni::sys::JNIEnv,
    _this: jni::sys::jobject,
) -> jni::sys::jint {
    unsafe {
        let session = GLOBAL_SESSION.lock().unwrap();
        session.as_ref().map(|s| s.height as i32).unwrap_or(720)
    }
}

#[no_mangle]
pub extern "C" fn Java_com_rdp_sync_network_RdpConnector_get_surface_bytes(
    _env: *mut jni::sys::JNIEnv,
    _this: jni::sys::jobject,
    output_ptr: jni::sys::jlong,
    output_size: jni::sys::jlong,
) -> jni::sys::jint {
    0
}

#[no_mangle]
pub extern "C" fn Java_com_rdp_sync_network_RdpConnector_send_pointer_event(
    _env: *mut jni::sys::JNIEnv,
    _this: jni::sys::jobject,
    _x: jni::sys::jint,
    _y: jni::sys::jint,
    _button: jni::sys::jint,
) -> core::ffi::c_int {
    0
}

#[no_mangle]
pub extern "C" fn Java_com_rdp_sync_network_RdpConnector_send_key_event(
    _env: *mut jni::sys::JNIEnv,
    _this: jni::sys::jobject,
    _key_code: jni::sys::jint,
    _down: jni::sys::jint,
) -> core::ffi::c_int {
    0
}

#[no_mangle]
pub extern "C" fn Java_com_rdp_sync_network_RdpConnector_send_clipboard_text(
    _env: *mut jni::sys::JNIEnv,
    _this: jni::sys::jobject,
    _text: jni::sys::jstring,
) -> core::ffi::c_int {
    0
}
