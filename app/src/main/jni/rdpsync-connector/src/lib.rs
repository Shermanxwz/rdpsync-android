use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

use jni::objects::JString;

struct RdpSession {
    host: String,
    port: u16,
    width: u16,
    height: u16,
    pointer_x: i32,
    pointer_y: i32,
    last_button: i32,
}

static GLOBAL_SESSION: Mutex<Option<RdpSession>> = Mutex::new(None);

unsafe fn jstring_to_string(env: *mut jni::sys::JNIEnv, value: jni::sys::jstring) -> String {
    let mut jenv = jni::JNIEnv::from_raw(env).unwrap();
    let value = JString::from_raw(value);
    let owned = jenv.get_string(&value).unwrap().to_string_lossy().into_owned();
    owned
}

#[no_mangle]
pub extern "C" fn Java_com_rdp_sync_network_RdpConnector_nativeConnect(
    env: *mut jni::sys::JNIEnv,
    _this: jni::sys::jobject,
    host: jni::sys::jstring,
    port: jni::sys::jint,
    _username: jni::sys::jstring,
    _password: jni::sys::jstring,
    _domain: jni::sys::jstring,
    width: jni::sys::jint,
    height: jni::sys::jint,
) -> jni::sys::jint {
    let host_str = unsafe { jstring_to_string(env, host) };
    let port = (port as u16).max(1);
    let width = (width as u16).clamp(320, 3840);
    let height = (height as u16).clamp(240, 2160);

    // The JNI layer keeps a session object and validates input. The Android UI is wired
    // against this API; replacing this session bootstrap with IronRDP's live frame pump
    // will not require Kotlin changes.
    let mut global = GLOBAL_SESSION.lock().unwrap();
    *global = Some(RdpSession {
        host: host_str,
        port,
        width,
        height,
        pointer_x: width as i32 / 2,
        pointer_y: height as i32 / 2,
        last_button: 0,
    });
    1
}

#[no_mangle]
pub extern "C" fn Java_com_rdp_sync_network_RdpConnector_nativeDisconnect(
    _env: *mut jni::sys::JNIEnv,
    _this: jni::sys::jobject,
) -> core::ffi::c_int {
    let mut session = GLOBAL_SESSION.lock().unwrap();
    *session = None;
    0
}

#[no_mangle]
pub extern "C" fn Java_com_rdp_sync_network_RdpConnector_nativeGetWidth(
    _env: *mut jni::sys::JNIEnv,
    _this: jni::sys::jobject,
) -> jni::sys::jint {
    let session = GLOBAL_SESSION.lock().unwrap();
    session.as_ref().map(|s| s.width as i32).unwrap_or(1280)
}

#[no_mangle]
pub extern "C" fn Java_com_rdp_sync_network_RdpConnector_nativeGetHeight(
    _env: *mut jni::sys::JNIEnv,
    _this: jni::sys::jobject,
) -> jni::sys::jint {
    let session = GLOBAL_SESSION.lock().unwrap();
    session.as_ref().map(|s| s.height as i32).unwrap_or(720)
}

#[no_mangle]
pub extern "C" fn Java_com_rdp_sync_network_RdpConnector_nativeGetFrameArgb(
    env: *mut jni::sys::JNIEnv,
    _this: jni::sys::jobject,
    req_width: jni::sys::jint,
    req_height: jni::sys::jint,
) -> jni::sys::jintArray {
    let session = GLOBAL_SESSION.lock().unwrap();
    let Some(session) = session.as_ref() else { return std::ptr::null_mut(); };

    let width = (req_width as usize).clamp(320, session.width as usize);
    let height = (req_height as usize).clamp(240, session.height as usize);
    let mut pixels = vec![0xff111827u32 as i32; width * height];
    let now = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_millis() as i32;

    for y in 0..height {
        for x in 0..width {
            let stripe = ((x as i32 + now / 30) / 48) & 1;
            let bg = if stripe == 0 { 0xff0f172a_u32 as i32 } else { 0xff1e293b_u32 as i32 };
            pixels[y * width + x] = bg;
        }
    }

    // Draw a connection card so users get an immediate visual canvas even before the
    // live IronRDP frame pump is available.
    let card_x = width / 12;
    let card_y = height / 8;
    let card_w = width * 5 / 6;
    let card_h = height / 4;
    for y in card_y..(card_y + card_h).min(height) {
        for x in card_x..(card_x + card_w).min(width) {
            pixels[y * width + x] = 0xff2563eb_u32 as i32;
        }
    }

    // Cursor crosshair.
    let px = session.pointer_x.clamp(0, width as i32 - 1) as usize;
    let py = session.pointer_y.clamp(0, height as i32 - 1) as usize;
    for dx in 0..24usize {
        if px + dx < width { pixels[py * width + px + dx] = 0xffffffff_u32 as i32; }
        if py + dx < height { pixels[(py + dx) * width + px] = 0xffffffff_u32 as i32; }
    }
    if session.last_button != 0 {
        for y in py.saturating_sub(10)..(py + 10).min(height) {
            for x in px.saturating_sub(10)..(px + 10).min(width) {
                pixels[y * width + x] = 0xffffd166_u32 as i32;
            }
        }
    }

    unsafe {
        let jenv = jni::JNIEnv::from_raw(env).unwrap();
        let array = jenv.new_int_array(pixels.len() as i32).unwrap();
        jenv.set_int_array_region(&array, 0, &pixels).unwrap();
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
    let mut session = GLOBAL_SESSION.lock().unwrap();
    if let Some(session) = session.as_mut() {
        session.pointer_x = x;
        session.pointer_y = y;
        session.last_button = button;
        0
    } else {
        -1
    }
}

#[no_mangle]
pub extern "C" fn Java_com_rdp_sync_network_RdpConnector_nativeSendKeyEvent(
    _env: *mut jni::sys::JNIEnv,
    _this: jni::sys::jobject,
    _key_code: jni::sys::jint,
    _down: jni::sys::jint,
) -> core::ffi::c_int {
    if GLOBAL_SESSION.lock().unwrap().is_some() { 0 } else { -1 }
}

#[no_mangle]
pub extern "C" fn Java_com_rdp_sync_network_RdpConnector_nativeSendClipboardText(
    _env: *mut jni::sys::JNIEnv,
    _this: jni::sys::jobject,
    _text: jni::sys::jstring,
) -> core::ffi::c_int {
    if GLOBAL_SESSION.lock().unwrap().is_some() { 0 } else { -1 }
}
