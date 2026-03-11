package com.hoho.android.usbserial.driver;

import android.util.Log;

import java.io.IOException;

final class FtdiNativeBridge {

    static final int RESULT_UNSUPPORTED = Integer.MIN_VALUE;
    static final int RESULT_INVALID_ARGUMENT = Integer.MIN_VALUE + 1;

    private static final String TAG = FtdiNativeBridge.class.getSimpleName();
    private static final String LIB_NAME = "usbserial_ftdi";

    private static final LoadStatus LOAD_STATUS = loadLibrary();
    private static final boolean AVAILABLE = LOAD_STATUS.available;
    private static final String AVAILABILITY_MESSAGE = LOAD_STATUS.message;

    private FtdiNativeBridge() {
    }

    static boolean isAvailable() {
        return AVAILABLE;
    }

    static String getAvailabilityMessage() {
        return AVAILABILITY_MESSAGE;
    }

    static long open(int fd, byte[] rawDescriptors, int portNumber, int interfaceId,
                     int readEndpointAddress, int writeEndpointAddress,
                     int readMaxPacketSize, int writeMaxPacketSize,
                     boolean baudRateWithPort, boolean dtr, boolean rts,
                     int flowControlOrdinal) throws IOException {
        if (!AVAILABLE) {
            return 0;
        }
        try {
            long handle = nativeOpen(fd, rawDescriptors, portNumber, interfaceId,
                    readEndpointAddress, writeEndpointAddress,
                    readMaxPacketSize, writeMaxPacketSize,
                    baudRateWithPort, dtr, rts, flowControlOrdinal);
            if (handle < 0) {
                throw new IOException("nativeOpen failed: result=" + handle);
            }
            return handle;
        } catch (UnsatisfiedLinkError e) {
            throw new IOException("nativeOpen unavailable", e);
        }
    }

    static void close(long handle) throws IOException {
        if (handle == 0 || !AVAILABLE) {
            return;
        }
        try {
            nativeClose(handle);
        } catch (UnsatisfiedLinkError e) {
            throw new IOException("nativeClose unavailable", e);
        }
    }

    static int read(long handle, byte[] dest, int length, int timeout) throws IOException {
        try {
            return translateResult("nativeRead", nativeRead(handle, dest, length, timeout));
        } catch (UnsatisfiedLinkError e) {
            throw new IOException("nativeRead unavailable", e);
        }
    }

    static int write(long handle, byte[] src, int length, int timeout) throws IOException {
        try {
            return translateResult("nativeWrite", nativeWrite(handle, src, length, timeout));
        } catch (UnsatisfiedLinkError e) {
            throw new IOException("nativeWrite unavailable", e);
        }
    }

    static void setParameters(long handle, int baudRate, int config) throws IOException {
        try {
            translateResult("nativeSetParameters", nativeSetParameters(handle, baudRate, config));
        } catch (UnsatisfiedLinkError e) {
            throw new IOException("nativeSetParameters unavailable", e);
        }
    }

    static int getStatus(long handle) throws IOException {
        try {
            return translateResult("nativeGetStatus", nativeGetStatus(handle));
        } catch (UnsatisfiedLinkError e) {
            throw new IOException("nativeGetStatus unavailable", e);
        }
    }

    static void setDtr(long handle, boolean value) throws IOException {
        try {
            translateResult("nativeSetDtr", nativeSetDtr(handle, value));
        } catch (UnsatisfiedLinkError e) {
            throw new IOException("nativeSetDtr unavailable", e);
        }
    }

    static void setRts(long handle, boolean value) throws IOException {
        try {
            translateResult("nativeSetRts", nativeSetRts(handle, value));
        } catch (UnsatisfiedLinkError e) {
            throw new IOException("nativeSetRts unavailable", e);
        }
    }

    static void setFlowControl(long handle, int flowControlOrdinal, int xon, int xoff) throws IOException {
        try {
            translateResult("nativeSetFlowControl", nativeSetFlowControl(handle, flowControlOrdinal, xon, xoff));
        } catch (UnsatisfiedLinkError e) {
            throw new IOException("nativeSetFlowControl unavailable", e);
        }
    }

    static void purgeHwBuffers(long handle, boolean purgeWriteBuffers, boolean purgeReadBuffers) throws IOException {
        try {
            translateResult("nativePurgeHwBuffers", nativePurgeHwBuffers(handle, purgeWriteBuffers, purgeReadBuffers));
        } catch (UnsatisfiedLinkError e) {
            throw new IOException("nativePurgeHwBuffers unavailable", e);
        }
    }

    static void setBreak(long handle, int config) throws IOException {
        try {
            translateResult("nativeSetBreak", nativeSetBreak(handle, config));
        } catch (UnsatisfiedLinkError e) {
            throw new IOException("nativeSetBreak unavailable", e);
        }
    }

    static void setLatencyTimer(long handle, int latencyTime) throws IOException {
        try {
            translateResult("nativeSetLatencyTimer", nativeSetLatencyTimer(handle, latencyTime));
        } catch (UnsatisfiedLinkError e) {
            throw new IOException("nativeSetLatencyTimer unavailable", e);
        }
    }

    static int getLatencyTimer(long handle) throws IOException {
        try {
            return translateResult("nativeGetLatencyTimer", nativeGetLatencyTimer(handle));
        } catch (UnsatisfiedLinkError e) {
            throw new IOException("nativeGetLatencyTimer unavailable", e);
        }
    }

    private static int translateResult(String operation, int result) throws IOException {
        if (result >= 0) {
            return result;
        }
        switch (result) {
            case RESULT_UNSUPPORTED:
                throw new UnsupportedOperationException(operation + " not supported");
            case RESULT_INVALID_ARGUMENT:
                throw new IllegalArgumentException(operation + " invalid argument");
            default:
                throw new IOException(operation + " failed: result=" + result);
        }
    }

    private static LoadStatus loadLibrary() {
        try {
            System.loadLibrary(LIB_NAME);
            String message = "loaded " + System.mapLibraryName(LIB_NAME);
            Log.i(TAG, "FTDI native bridge available: " + message);
            return new LoadStatus(true, message);
        } catch (UnsatisfiedLinkError | SecurityException e) {
            String message = e.getClass().getSimpleName() + ": " + e.getMessage();
            Log.i(TAG, "FTDI native bridge unavailable, using Java fallback: " + message);
            return new LoadStatus(false, message);
        }
    }

    private static final class LoadStatus {
        final boolean available;
        final String message;

        LoadStatus(boolean available, String message) {
            this.available = available;
            this.message = message;
        }
    }

    private static native long nativeOpen(int fd, byte[] rawDescriptors, int portNumber, int interfaceId,
                                          int readEndpointAddress, int writeEndpointAddress,
                                          int readMaxPacketSize, int writeMaxPacketSize,
                                          boolean baudRateWithPort, boolean dtr, boolean rts,
                                          int flowControlOrdinal);

    private static native void nativeClose(long handle);

    private static native int nativeRead(long handle, byte[] dest, int length, int timeout);

    private static native int nativeWrite(long handle, byte[] src, int length, int timeout);

    private static native int nativeSetParameters(long handle, int baudRate, int config);

    private static native int nativeGetStatus(long handle);

    private static native int nativeSetDtr(long handle, boolean value);

    private static native int nativeSetRts(long handle, boolean value);

    private static native int nativeSetFlowControl(long handle, int flowControlOrdinal, int xon, int xoff);

    private static native int nativePurgeHwBuffers(long handle, boolean purgeWriteBuffers, boolean purgeReadBuffers);

    private static native int nativeSetBreak(long handle, int config);

    private static native int nativeSetLatencyTimer(long handle, int latencyTime);

    private static native int nativeGetLatencyTimer(long handle);
}