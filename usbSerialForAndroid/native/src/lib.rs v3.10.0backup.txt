#![allow(non_camel_case_types)]

use std::cmp::min;
use std::ffi::CString;
use std::mem;
use std::os::raw::{c_char, c_int, c_uint, c_void};
use std::sync::Mutex;
use std::time::{Duration, Instant};

type JNIEnv = *mut *const c_void;
type jarray = *mut c_void;
type jbyte = i8;
type jbyteArray = *mut c_void;
type jboolean = u8;
type jclass = *mut c_void;
type jint = i32;
type jlong = i64;
type jsize = jint;

const RESULT_UNSUPPORTED: jint = i32::MIN;
const RESULT_INVALID_ARGUMENT: jint = i32::MIN + 1;

// JNINativeInterface_ starts with 4 reserved slots before GetVersion.
// Keep these indices aligned with the Android NDK jni.h layout.
const JNI_EXCEPTION_OCCURRED_INDEX: usize = 15;
const JNI_GET_ARRAY_LENGTH_INDEX: usize = 171;
const JNI_GET_BYTE_ARRAY_REGION_INDEX: usize = 200;
const JNI_SET_BYTE_ARRAY_REGION_INDEX: usize = 208;

const USB_WRITE_TIMEOUT_MILLIS: u32 = 5000;
const USB_CONNECTION_PROBE_TIMEOUT_MILLIS: u32 = 200;
const READ_HEADER_LENGTH: usize = 2;

const REQTYPE_STANDARD_DEVICE_TO_HOST: u8 = 0x80;
const REQTYPE_HOST_TO_DEVICE: u8 = 0x40;
const REQTYPE_DEVICE_TO_HOST: u8 = 0xc0;

const GET_STATUS_REQUEST: u8 = 0;
const RESET_REQUEST: u8 = 0;
const MODEM_CONTROL_REQUEST: u8 = 1;
const SET_FLOW_CONTROL_REQUEST: u8 = 2;
const SET_BAUD_RATE_REQUEST: u8 = 3;
const SET_DATA_REQUEST: u8 = 4;
const GET_MODEM_STATUS_REQUEST: u8 = 5;
const SET_LATENCY_TIMER_REQUEST: u8 = 9;
const GET_LATENCY_TIMER_REQUEST: u8 = 10;

const MODEM_CONTROL_DTR_ENABLE: u16 = 0x0101;
const MODEM_CONTROL_DTR_DISABLE: u16 = 0x0100;
const MODEM_CONTROL_RTS_ENABLE: u16 = 0x0202;
const MODEM_CONTROL_RTS_DISABLE: u16 = 0x0200;

const RESET_PURGE_RX: u16 = 1;
const RESET_PURGE_TX: u16 = 2;

const ANDROID_LOG_INFO: c_int = 4;
const ANDROID_LOG_ERROR: c_int = 6;
const LOG_TAG: &[u8] = b"usbserial_ftdi\0";

const ETIMEDOUT: c_int = 110;
const EINTR: c_int = 4;

const IOC_NRBITS: u32 = 8;
const IOC_TYPEBITS: u32 = 8;
const IOC_SIZEBITS: u32 = 14;
const IOC_NRSHIFT: u32 = 0;
const IOC_TYPESHIFT: u32 = IOC_NRSHIFT + IOC_NRBITS;
const IOC_SIZESHIFT: u32 = IOC_TYPESHIFT + IOC_TYPEBITS;
const IOC_DIRSHIFT: u32 = IOC_SIZESHIFT + IOC_SIZEBITS;
const IOC_WRITE: u32 = 1;
const IOC_READ: u32 = 2;

#[repr(C)]
struct usbdevfs_ctrltransfer {
    b_request_type: u8,
    b_request: u8,
    w_value: u16,
    w_index: u16,
    w_length: u16,
    timeout: u32,
    data: *mut c_void,
}

#[repr(C)]
struct usbdevfs_bulktransfer {
    ep: c_uint,
    len: c_uint,
    timeout: c_uint,
    data: *mut c_void,
}

#[derive(Default)]
struct FtdiSession {
    fd: c_int,
    port_number: u16,
    _interface_id: jint,
    read_endpoint_address: u32,
    write_endpoint_address: u32,
    read_max_packet_size: usize,
    baud_rate_with_port: bool,
    dtr: bool,
    rts: bool,
    flow_control_ordinal: jint,
    read_buffer: Vec<u8>,
    write_buffer: Vec<u8>,
}

extern "C" {
    fn ioctl(fd: c_int, request: c_uint, ...) -> c_int;
    fn __errno() -> *mut c_int;
}

#[link(name = "log")]
extern "C" {
    fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
}

type ExceptionOccurredFn = unsafe extern "system" fn(JNIEnv) -> *mut c_void;
type GetArrayLengthFn = unsafe extern "system" fn(JNIEnv, jarray) -> jsize;
type GetByteArrayRegionFn = unsafe extern "system" fn(JNIEnv, jbyteArray, jsize, jsize, *mut jbyte);
type SetByteArrayRegionFn = unsafe extern "system" fn(JNIEnv, jbyteArray, jsize, jsize, *const jbyte);

const fn ioc(dir: u32, ty: u32, nr: u32, size: u32) -> c_uint {
    ((dir << IOC_DIRSHIFT) | (ty << IOC_TYPESHIFT) | (nr << IOC_NRSHIFT) | (size << IOC_SIZESHIFT)) as c_uint
}

const fn iowr(ty: u32, nr: u32, size: u32) -> c_uint {
    ioc(IOC_READ | IOC_WRITE, ty, nr, size)
}

const USBDEVFS_CONTROL: c_uint = iowr(b'U' as u32, 0, mem::size_of::<usbdevfs_ctrltransfer>() as u32);
const USBDEVFS_BULK: c_uint = iowr(b'U' as u32, 2, mem::size_of::<usbdevfs_bulktransfer>() as u32);

unsafe fn jni_fn<T: Copy>(env: JNIEnv, index: usize) -> T {
    let table = *env as *const *const c_void;
    mem::transmute_copy(&*table.add(index))
}

fn has_pending_exception(env: JNIEnv) -> bool {
    if env.is_null() {
        return false;
    }
    let exception_occurred: ExceptionOccurredFn = unsafe { jni_fn(env, JNI_EXCEPTION_OCCURRED_INDEX) };
    unsafe { !exception_occurred(env).is_null() }
}

fn byte_array_length(env: JNIEnv, array: jbyteArray) -> Result<usize, jint> {
    if env.is_null() || array.is_null() {
        return Err(RESULT_INVALID_ARGUMENT);
    }
    let get_array_length: GetArrayLengthFn = unsafe { jni_fn(env, JNI_GET_ARRAY_LENGTH_INDEX) };
    let length = unsafe { get_array_length(env, array) };
    if length < 0 {
        return Err(io_error_code());
    }
    usize::try_from(length).map_err(|_| RESULT_INVALID_ARGUMENT)
}

fn get_byte_array_region(env: JNIEnv, array: jbyteArray, offset: usize, buffer: &mut [u8]) -> Result<(), jint> {
    let get_region: GetByteArrayRegionFn = unsafe { jni_fn(env, JNI_GET_BYTE_ARRAY_REGION_INDEX) };
    let start = jint::try_from(offset).map_err(|_| RESULT_INVALID_ARGUMENT)?;
    let len = jint::try_from(buffer.len()).map_err(|_| RESULT_INVALID_ARGUMENT)?;
    unsafe {
        get_region(env, array, start, len, buffer.as_mut_ptr() as *mut jbyte);
    }
    if has_pending_exception(env) {
        return Err(io_error_code());
    }
    Ok(())
}

fn set_byte_array_region(env: JNIEnv, array: jbyteArray, buffer: &[u8]) -> Result<(), jint> {
    let set_region: SetByteArrayRegionFn = unsafe { jni_fn(env, JNI_SET_BYTE_ARRAY_REGION_INDEX) };
    let len = jint::try_from(buffer.len()).map_err(|_| RESULT_INVALID_ARGUMENT)?;
    unsafe {
        set_region(env, array, 0, len, buffer.as_ptr() as *const jbyte);
    }
    if has_pending_exception(env) {
        return Err(io_error_code());
    }
    Ok(())
}

fn bool_from_jboolean(value: jboolean) -> bool {
    value != 0
}

fn current_errno() -> c_int {
    unsafe { *__errno() }
}

fn io_error_code() -> jint {
    let err = current_errno();
    if err > 0 { -err } else { -1 }
}

fn android_log(priority: c_int, message: &str) {
    if let Ok(text) = CString::new(message) {
        unsafe {
            __android_log_write(
                priority,
                LOG_TAG.as_ptr() as *const c_char,
                text.as_ptr(),
            );
        }
    }
}

fn android_log_info(message: &str) {
    android_log(ANDROID_LOG_INFO, message);
}

fn android_log_error(message: &str) {
    android_log(ANDROID_LOG_ERROR, message);
}

fn with_session<T>(handle: jlong, f: impl FnOnce(&mut FtdiSession) -> Result<T, jint>) -> Result<T, jint> {
    if handle == 0 {
        return Err(RESULT_INVALID_ARGUMENT);
    }
    let session_mutex = unsafe { &*(handle as *mut Mutex<FtdiSession>) };
    let mut guard = match session_mutex.lock() {
        Ok(guard) => guard,
        Err(poisoned) => poisoned.into_inner(),
    };
    f(&mut guard)
}

fn control_index(port_number: u16) -> u16 {
    port_number + 1
}

fn control_transfer(
    fd: c_int,
    request_type: u8,
    request: u8,
    value: u16,
    index: u16,
    buffer: Option<&mut [u8]>,
    timeout: u32,
) -> Result<usize, jint> {
    let (data_ptr, length) = match buffer {
        Some(slice) => (slice.as_mut_ptr() as *mut c_void, slice.len()),
        None => (std::ptr::null_mut(), 0),
    };
    let mut transfer = usbdevfs_ctrltransfer {
        b_request_type: request_type,
        b_request: request,
        w_value: value,
        w_index: index,
        w_length: u16::try_from(length).map_err(|_| RESULT_INVALID_ARGUMENT)?,
        timeout,
        data: data_ptr,
    };
    loop {
        let rc = unsafe { ioctl(fd, USBDEVFS_CONTROL, &mut transfer) };
        if rc >= 0 {
            return usize::try_from(rc).map_err(|_| -1);
        }
        let err = current_errno();
        if err == EINTR {
            continue;
        }
        return Err(if err > 0 { -err } else { -1 });
    }
}

fn bulk_transfer(fd: c_int, endpoint: u32, buffer: &mut [u8], timeout: u32) -> Result<usize, jint> {
    let mut transfer = usbdevfs_bulktransfer {
        ep: endpoint,
        len: c_uint::try_from(buffer.len()).map_err(|_| RESULT_INVALID_ARGUMENT)?,
        timeout,
        data: buffer.as_mut_ptr() as *mut c_void,
    };
    loop {
        let rc = unsafe { ioctl(fd, USBDEVFS_BULK, &mut transfer) };
        if rc >= 0 {
            return usize::try_from(rc).map_err(|_| -1);
        }
        let err = current_errno();
        if err == EINTR {
            continue;
        }
        if err == ETIMEDOUT {
            return Ok(0);
        }
        android_log_error(&format!(
            "USBDEVFS_BULK failed fd={} ep=0x{:02x} len={} timeout={} errno={}",
            fd,
            endpoint,
            buffer.len(),
            timeout,
            err
        ));
        return Err(if err > 0 { -err } else { -1 });
    }
}

fn filter_ftdi_read(buffer: &mut [u8], total_bytes_read: usize, max_packet_size: usize) -> Result<usize, jint> {
    let mut dest_pos = 0usize;
    let mut src_pos = 0usize;
    while src_pos < total_bytes_read {
        let packet_end = min(src_pos + max_packet_size, total_bytes_read);
        let payload_start = src_pos + READ_HEADER_LENGTH;
        if payload_start > packet_end {
            return Err(-1);
        }
        if payload_start < packet_end {
            buffer.copy_within(payload_start..packet_end, dest_pos);
            dest_pos += packet_end - payload_start;
        }
        src_pos += max_packet_size;
    }
    Ok(dest_pos)
}

fn required_read_buffer_len(requested_payload_len: usize, max_packet_size: usize) -> Result<usize, jint> {
    if max_packet_size <= READ_HEADER_LENGTH {
        return Err(RESULT_INVALID_ARGUMENT);
    }
    if requested_payload_len == 0 {
        return Ok(0);
    }
    let packet_payload_len = max_packet_size - READ_HEADER_LENGTH;
    let packets = requested_payload_len
        .checked_add(packet_payload_len - 1)
        .ok_or(RESULT_INVALID_ARGUMENT)?
        / packet_payload_len;
    requested_payload_len
        .checked_add(
            packets
                .checked_mul(READ_HEADER_LENGTH)
                .ok_or(RESULT_INVALID_ARGUMENT)?,
        )
        .ok_or(RESULT_INVALID_ARGUMENT)
}

fn remaining_timeout(deadline: Option<Instant>) -> u32 {
    match deadline {
        None => 0,
        Some(deadline) => match deadline.checked_duration_since(Instant::now()) {
            Some(remaining) => {
                let millis = remaining.as_millis();
                if millis == 0 { 1 } else { min(millis, u128::from(u32::MAX)) as u32 }
            }
            None => 0,
        },
    }
}

fn probe_connection(session: &mut FtdiSession) -> Result<(), jint> {
    let mut data = [0u8; 2];
    let result = control_transfer(
        session.fd,
        REQTYPE_STANDARD_DEVICE_TO_HOST,
        GET_STATUS_REQUEST,
        0,
        0,
        Some(&mut data),
        USB_CONNECTION_PROBE_TIMEOUT_MILLIS,
    )?;
    if result == data.len() {
        Ok(())
    } else {
        Err(-1)
    }
}

fn compute_baud_rate(baud_rate: jint, baud_rate_with_port: bool, port_number: u16) -> Result<(u16, u16), jint> {
    if baud_rate <= 0 {
        return Err(RESULT_INVALID_ARGUMENT);
    }
    let (divisor, subdivisor, effective_baud_rate) = if baud_rate > 3_500_000 {
        return Err(RESULT_UNSUPPORTED);
    } else if baud_rate >= 2_500_000 {
        (0i32, 0i32, 3_000_000i32)
    } else if baud_rate >= 1_750_000 {
        (1i32, 0i32, 2_000_000i32)
    } else {
        let mut divisor = (24_000_000i32 << 1) / baud_rate;
        divisor = (divisor + 1) >> 1;
        let subdivisor = divisor & 0x07;
        divisor >>= 3;
        if divisor > 0x3fff {
            return Err(RESULT_UNSUPPORTED);
        }
        let mut effective = (24_000_000i32 << 1) / ((divisor << 3) + subdivisor);
        effective = (effective + 1) >> 1;
        (divisor, subdivisor, effective)
    };
    let baud_rate_error = (1.0f64 - (effective_baud_rate as f64 / baud_rate as f64)).abs();
    if baud_rate_error >= 0.031 {
        return Err(RESULT_UNSUPPORTED);
    }
    let mut value = divisor as u16;
    let mut index = 0u16;
    match subdivisor {
        0 => {}
        4 => value |= 0x4000,
        2 => value |= 0x8000,
        1 => value |= 0xc000,
        3 => index |= 1,
        5 => { value |= 0x4000; index |= 1; }
        6 => { value |= 0x8000; index |= 1; }
        7 => { value |= 0xc000; index |= 1; }
        _ => return Err(RESULT_UNSUPPORTED),
    }
    if baud_rate_with_port {
        index <<= 8;
        index |= control_index(port_number);
    }
    Ok((value, index))
}

fn apply_flow_control(session: &mut FtdiSession, flow_control_ordinal: jint, xon: jint, xoff: jint) -> Result<(), jint> {
    let mut value = 0u16;
    let mut index = control_index(session.port_number);
    match flow_control_ordinal {
        0 => {}
        1 => index |= 0x100,
        2 => index |= 0x200,
        4 => {
            value = ((xon & 0xff) | ((xoff & 0xff) << 8)) as u16;
            index |= 0x400;
        }
        _ => return Err(RESULT_UNSUPPORTED),
    }
    let result = control_transfer(session.fd, REQTYPE_HOST_TO_DEVICE, SET_FLOW_CONTROL_REQUEST, value, index, None, USB_WRITE_TIMEOUT_MILLIS)?;
    if result != 0 {
        return Err(-1);
    }
    session.flow_control_ordinal = flow_control_ordinal;
    Ok(())
}

fn set_parameters_impl(session: &mut FtdiSession, baud_rate: jint, config: jint) -> Result<(), jint> {
    let (value, index) = compute_baud_rate(baud_rate, session.baud_rate_with_port, session.port_number)?;
    let result = control_transfer(session.fd, REQTYPE_HOST_TO_DEVICE, SET_BAUD_RATE_REQUEST, value, index, None, USB_WRITE_TIMEOUT_MILLIS)?;
    if result != 0 {
        return Err(-1);
    }
    let result = control_transfer(
        session.fd,
        REQTYPE_HOST_TO_DEVICE,
        SET_DATA_REQUEST,
        config as u16,
        control_index(session.port_number),
        None,
        USB_WRITE_TIMEOUT_MILLIS,
    )?;
    if result != 0 {
        return Err(-1);
    }
    Ok(())
}

#[no_mangle]
pub extern "system" fn Java_com_hoho_android_usbserial_driver_FtdiNativeBridge_nativeOpen(
    env: JNIEnv,
    _class: jclass,
    fd: jint,
    raw_descriptors: jbyteArray,
    port_number: jint,
    interface_id: jint,
    read_endpoint_address: jint,
    write_endpoint_address: jint,
    read_max_packet_size: jint,
    write_max_packet_size: jint,
    baud_rate_with_port: jboolean,
    dtr: jboolean,
    rts: jboolean,
    flow_control_ordinal: jint,
) -> jlong {
    if fd < 0 || port_number < 0 || read_max_packet_size <= READ_HEADER_LENGTH as jint || write_max_packet_size <= 0 {
        return RESULT_INVALID_ARGUMENT as jlong;
    }
    if !raw_descriptors.is_null() && byte_array_length(env, raw_descriptors).is_err() {
        return RESULT_INVALID_ARGUMENT as jlong;
    }
    let read_endpoint_address = read_endpoint_address as u32;
    let write_endpoint_address = write_endpoint_address as u32;
    let baud_rate_with_port = bool_from_jboolean(baud_rate_with_port);
    let dtr = bool_from_jboolean(dtr);
    let rts = bool_from_jboolean(rts);
    let session = FtdiSession {
        fd,
        port_number: port_number as u16,
        _interface_id: interface_id,
        read_endpoint_address,
        write_endpoint_address,
        read_max_packet_size: read_max_packet_size as usize,
        baud_rate_with_port,
        dtr,
        rts,
        flow_control_ordinal,
        read_buffer: Vec::new(),
        write_buffer: Vec::new(),
    };
    let handle = Box::into_raw(Box::new(Mutex::new(session))) as jlong;
    android_log_info(&format!(
        "nativeOpen success handle={} fd={} port={} interface={} readEp=0x{:02x}/{} writeEp=0x{:02x}/{} baudRateWithPort={} dtr={} rts={} flowControl={}",
        handle,
        fd,
        port_number,
        interface_id,
        read_endpoint_address,
        read_max_packet_size,
        write_endpoint_address,
        write_max_packet_size,
        baud_rate_with_port,
        dtr,
        rts,
        flow_control_ordinal,
    ));
    handle
}

#[no_mangle]
pub extern "system" fn Java_com_hoho_android_usbserial_driver_FtdiNativeBridge_nativeClose(
    _env: JNIEnv,
    _class: jclass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    android_log_info(&format!(
        "nativeClose handle={} (session retained to avoid concurrent close/use-after-free)",
        handle,
    ));
    // Keep the session allocation alive after close. Java clears the handle immediately and the
    // underlying UsbDeviceConnection.close() invalidates the shared file descriptor, which lets
    // in-flight native I/O unwind without racing a use-after-free on this session pointer.
}

#[no_mangle]
pub extern "system" fn Java_com_hoho_android_usbserial_driver_FtdiNativeBridge_nativeRead(
    env: JNIEnv,
    _class: jclass,
    handle: jlong,
    dest: jbyteArray,
    length: jint,
    timeout: jint,
) -> jint {
    if length <= READ_HEADER_LENGTH as jint || timeout < 0 {
        return RESULT_INVALID_ARGUMENT;
    }
    let dest_len = match byte_array_length(env, dest) {
        Ok(len) => len,
        Err(err) => return err,
    };
    let requested_len = match usize::try_from(length) {
        Ok(len) => len,
        Err(_) => return RESULT_INVALID_ARGUMENT,
    };
    if requested_len > dest_len {
        return RESULT_INVALID_ARGUMENT;
    }
    let result = with_session(handle, |session| {
        let raw_buffer_len = required_read_buffer_len(requested_len, session.read_max_packet_size)?;
        session.read_buffer.resize(raw_buffer_len, 0);
        let deadline = if timeout == 0 { None } else { Some(Instant::now() + Duration::from_millis(timeout as u64)) };
        loop {
            let transfer_timeout = remaining_timeout(deadline);
            if timeout != 0 && transfer_timeout == 0 {
                return Ok(0usize);
            }
            let bytes_read = bulk_transfer(session.fd, session.read_endpoint_address, &mut session.read_buffer, transfer_timeout)?;
            if bytes_read == 0 {
                if timeout == 0 || remaining_timeout(deadline) != 0 {
                    probe_connection(session)?;
                }
                return Ok(0usize);
            }
            let filtered = filter_ftdi_read(&mut session.read_buffer, bytes_read, session.read_max_packet_size)?;
            if filtered == 0 {
                if timeout == 0 { continue; }
                if remaining_timeout(deadline) == 0 { return Ok(0usize); }
                continue;
            }
            return Ok(filtered);
        }
    });
    let bytes_read = match result {
        Ok(value) => value,
        Err(err) => return err,
    };
    if bytes_read == 0 {
        return 0;
    }
    match with_session(handle, |session| {
        set_byte_array_region(env, dest, &session.read_buffer[..bytes_read])?;
        Ok(())
    }) {
        Ok(()) => bytes_read as jint,
        Err(err) => err,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_hoho_android_usbserial_driver_FtdiNativeBridge_nativeWrite(
    env: JNIEnv,
    _class: jclass,
    handle: jlong,
    src: jbyteArray,
    length: jint,
    timeout: jint,
) -> jint {
    if length < 0 || timeout < 0 {
        return RESULT_INVALID_ARGUMENT;
    }
    let src_len = match byte_array_length(env, src) {
        Ok(len) => len,
        Err(err) => return err,
    };
    let requested_len = match usize::try_from(length) {
        Ok(len) => len,
        Err(_) => return RESULT_INVALID_ARGUMENT,
    };
    if requested_len > src_len {
        return RESULT_INVALID_ARGUMENT;
    }
    if requested_len == 0 {
        return 0;
    }
    match with_session(handle, |session| {
        session.write_buffer.resize(requested_len, 0);
        get_byte_array_region(env, src, 0, &mut session.write_buffer)?;
        let actual = bulk_transfer(
            session.fd,
            session.write_endpoint_address,
            &mut session.write_buffer,
            timeout as u32,
        )?;
        if actual == 0 {
            probe_connection(session)?;
        }
        Ok(actual)
    }) {
        Ok(written) => written as jint,
        Err(err) => err,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_hoho_android_usbserial_driver_FtdiNativeBridge_nativeSetParameters(
    _env: JNIEnv,
    _class: jclass,
    handle: jlong,
    baud_rate: jint,
    config: jint,
) -> jint {
    match with_session(handle, |session| set_parameters_impl(session, baud_rate, config)) {
        Ok(()) => 0,
        Err(err) => err,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_hoho_android_usbserial_driver_FtdiNativeBridge_nativeGetStatus(
    _env: JNIEnv,
    _class: jclass,
    handle: jlong,
) -> jint {
    match with_session(handle, |session| {
        let mut data = [0u8; 2];
        let result = control_transfer(session.fd, REQTYPE_DEVICE_TO_HOST, GET_MODEM_STATUS_REQUEST, 0, control_index(session.port_number), Some(&mut data), USB_WRITE_TIMEOUT_MILLIS)?;
        if result != data.len() {
            return Err(-1);
        }
        Ok((data[0] as i8) as jint)
    }) {
        Ok(status) => status,
        Err(err) => err,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_hoho_android_usbserial_driver_FtdiNativeBridge_nativeSetDtr(
    _env: JNIEnv,
    _class: jclass,
    handle: jlong,
    value: jboolean,
) -> jint {
    match with_session(handle, |session| {
        let result = control_transfer(
            session.fd,
            REQTYPE_HOST_TO_DEVICE,
            MODEM_CONTROL_REQUEST,
            if bool_from_jboolean(value) { MODEM_CONTROL_DTR_ENABLE } else { MODEM_CONTROL_DTR_DISABLE },
            control_index(session.port_number),
            None,
            USB_WRITE_TIMEOUT_MILLIS,
        )?;
        if result != 0 {
            return Err(-1);
        }
        session.dtr = bool_from_jboolean(value);
        Ok(())
    }) {
        Ok(()) => 0,
        Err(err) => err,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_hoho_android_usbserial_driver_FtdiNativeBridge_nativeSetRts(
    _env: JNIEnv,
    _class: jclass,
    handle: jlong,
    value: jboolean,
) -> jint {
    match with_session(handle, |session| {
        let result = control_transfer(
            session.fd,
            REQTYPE_HOST_TO_DEVICE,
            MODEM_CONTROL_REQUEST,
            if bool_from_jboolean(value) { MODEM_CONTROL_RTS_ENABLE } else { MODEM_CONTROL_RTS_DISABLE },
            control_index(session.port_number),
            None,
            USB_WRITE_TIMEOUT_MILLIS,
        )?;
        if result != 0 {
            return Err(-1);
        }
        session.rts = bool_from_jboolean(value);
        Ok(())
    }) {
        Ok(()) => 0,
        Err(err) => err,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_hoho_android_usbserial_driver_FtdiNativeBridge_nativeSetFlowControl(
    _env: JNIEnv,
    _class: jclass,
    handle: jlong,
    flow_control_ordinal: jint,
    xon: jint,
    xoff: jint,
) -> jint {
    match with_session(handle, |session| apply_flow_control(session, flow_control_ordinal, xon, xoff)) {
        Ok(()) => 0,
        Err(err) => err,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_hoho_android_usbserial_driver_FtdiNativeBridge_nativePurgeHwBuffers(
    _env: JNIEnv,
    _class: jclass,
    handle: jlong,
    purge_write_buffers: jboolean,
    purge_read_buffers: jboolean,
) -> jint {
    match with_session(handle, |session| {
        if bool_from_jboolean(purge_write_buffers) {
            let result = control_transfer(session.fd, REQTYPE_HOST_TO_DEVICE, RESET_REQUEST, RESET_PURGE_RX, control_index(session.port_number), None, USB_WRITE_TIMEOUT_MILLIS)?;
            if result != 0 {
                return Err(-1);
            }
        }
        if bool_from_jboolean(purge_read_buffers) {
            let result = control_transfer(session.fd, REQTYPE_HOST_TO_DEVICE, RESET_REQUEST, RESET_PURGE_TX, control_index(session.port_number), None, USB_WRITE_TIMEOUT_MILLIS)?;
            if result != 0 {
                return Err(-1);
            }
        }
        Ok(())
    }) {
        Ok(()) => 0,
        Err(err) => err,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_hoho_android_usbserial_driver_FtdiNativeBridge_nativeSetBreak(
    _env: JNIEnv,
    _class: jclass,
    handle: jlong,
    config: jint,
) -> jint {
    match with_session(handle, |session| {
        let result = control_transfer(session.fd, REQTYPE_HOST_TO_DEVICE, SET_DATA_REQUEST, config as u16, control_index(session.port_number), None, USB_WRITE_TIMEOUT_MILLIS)?;
        if result != 0 {
            return Err(-1);
        }
        Ok(())
    }) {
        Ok(()) => 0,
        Err(err) => err,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_hoho_android_usbserial_driver_FtdiNativeBridge_nativeSetLatencyTimer(
    _env: JNIEnv,
    _class: jclass,
    handle: jlong,
    latency_time: jint,
) -> jint {
    match with_session(handle, |session| {
        let result = control_transfer(session.fd, REQTYPE_HOST_TO_DEVICE, SET_LATENCY_TIMER_REQUEST, latency_time as u16, control_index(session.port_number), None, USB_WRITE_TIMEOUT_MILLIS)?;
        if result != 0 {
            return Err(-1);
        }
        Ok(())
    }) {
        Ok(()) => 0,
        Err(err) => err,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_hoho_android_usbserial_driver_FtdiNativeBridge_nativeGetLatencyTimer(
    _env: JNIEnv,
    _class: jclass,
    handle: jlong,
) -> jint {
    match with_session(handle, |session| {
        let mut data = [0u8; 1];
        let result = control_transfer(session.fd, REQTYPE_DEVICE_TO_HOST, GET_LATENCY_TIMER_REQUEST, 0, control_index(session.port_number), Some(&mut data), USB_WRITE_TIMEOUT_MILLIS)?;
        if result != data.len() {
            return Err(-1);
        }
        Ok((data[0] as i8) as jint)
    }) {
        Ok(value) => value,
        Err(err) => err,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn init_buf(buf: &mut [u8]) {
        for (i, b) in buf.iter_mut().enumerate() {
            *b = i as u8;
        }
    }

    fn test_buf(buf: &[u8], len: usize) -> bool {
        let mut expected = 2u8;
        for actual in &buf[..len] {
            if *actual != expected {
                return false;
            }
            expected = expected.wrapping_add(1);
            if expected % 64 == 0 {
                expected = expected.wrapping_add(2);
            }
        }
        true
    }

    #[test]
    fn filter_ftdi_read_matches_java_behavior() {
        let mut buf = vec![0u8; 2048];

        assert_eq!(filter_ftdi_read(&mut buf, 0, 64).unwrap(), 0);
        assert!(filter_ftdi_read(&mut buf, 1, 64).is_err());

        init_buf(&mut buf);
        assert_eq!(filter_ftdi_read(&mut buf, 2, 64).unwrap(), 0);

        init_buf(&mut buf);
        let len = filter_ftdi_read(&mut buf, 3, 64).unwrap();
        assert_eq!(len, 1);
        assert!(test_buf(&buf, len));

        init_buf(&mut buf);
        let len = filter_ftdi_read(&mut buf, 64, 64).unwrap();
        assert_eq!(len, 62);
        assert!(test_buf(&buf, len));

        assert!(filter_ftdi_read(&mut buf, 65, 64).is_err());

        init_buf(&mut buf);
        let len = filter_ftdi_read(&mut buf, 68, 64).unwrap();
        assert_eq!(len, 64);
        assert!(test_buf(&buf, len));

        init_buf(&mut buf);
        let len = filter_ftdi_read(&mut buf, 16 * 64 + 11, 64).unwrap();
        assert_eq!(len, 16 * 62 + 9);
        assert!(test_buf(&buf, len));
    }

    #[test]
    fn compute_baud_rate_handles_boundaries_and_exact_values() {
        assert_eq!(compute_baud_rate(0, false, 0), Err(RESULT_INVALID_ARGUMENT));
        assert_eq!(compute_baud_rate(183, false, 0), Err(RESULT_UNSUPPORTED));
        assert!(compute_baud_rate(184, false, 0).is_ok());

        assert_eq!(compute_baud_rate(9_600, false, 0).unwrap(), (0x4138, 0));
        assert_eq!(compute_baud_rate(2_000_000, true, 1).unwrap(), (1, 2));
        assert_eq!(compute_baud_rate(3_000_000, true, 0).unwrap(), (0, 1));

        assert_eq!(compute_baud_rate((2_000_000.0 / 1.04) as i32, false, 0), Err(RESULT_UNSUPPORTED));
        assert!(compute_baud_rate((2_000_000.0 / 1.03) as i32, false, 0).is_ok());
        assert!(compute_baud_rate((2_000_000.0 * 1.03) as i32, false, 0).is_ok());
        assert_eq!(compute_baud_rate((2_000_000.0 * 1.04) as i32, false, 0), Err(RESULT_UNSUPPORTED));
        assert_eq!(compute_baud_rate(4_000_000, false, 0), Err(RESULT_UNSUPPORTED));
    }

    #[test]
    fn required_read_buffer_len_accounts_for_ftdi_headers() {
        assert_eq!(required_read_buffer_len(1, 64).unwrap(), 3);
        assert_eq!(required_read_buffer_len(62, 64).unwrap(), 64);
        assert_eq!(required_read_buffer_len(63, 64).unwrap(), 67);
        assert_eq!(required_read_buffer_len(64, 64).unwrap(), 68);
        assert_eq!(required_read_buffer_len(16 * 62 + 9, 64).unwrap(), 16 * 64 + 11);
        assert_eq!(required_read_buffer_len(1, 2), Err(RESULT_INVALID_ARGUMENT));
    }

}