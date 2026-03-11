package com.hoho.android.usbserial.driver;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbRequest;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class FtdiSerialDriverTest {

    private final UsbDevice usbDevice = mock(UsbDevice.class);
    private final UsbEndpoint readEndpoint = mock(UsbEndpoint.class);
    private final UsbEndpoint writeEndpoint = mock(UsbEndpoint.class);

    private void initBuf(byte[] buf) {
        for(int i=0; i<buf.length; i++)
            buf[i] = (byte) i;
    }
    private boolean testBuf(byte[] buf, int len) {
        byte j = 2;
        for(int i=0; i<len; i++) {
            if(buf[i]!=j)
                return false;
            j++;
            if(j % 64 == 0)
                j+=2;
        }
        return true;
    }

    private FtdiSerialDriver.FtdiSerialPort createPort() {
        when(usbDevice.getInterfaceCount()).thenReturn(1);
        FtdiSerialDriver driver = new FtdiSerialDriver(usbDevice);
        return (FtdiSerialDriver.FtdiSerialPort) driver.getPorts().get(0);
    }

    private void setNativeHandle(FtdiSerialDriver.FtdiSerialPort port, long handle) throws Exception {
        Field field = FtdiSerialDriver.FtdiSerialPort.class.getDeclaredField("nativeHandle");
        field.setAccessible(true);
        field.setLong(port, handle);
    }

    private IOException invokeRunNativeIo(FtdiSerialDriver.FtdiSerialPort port, Object operation) throws Exception {
        Class<?> operationClass = Class.forName("com.hoho.android.usbserial.driver.FtdiSerialDriver$NativeIoOperation");
        Method method = FtdiSerialDriver.FtdiSerialPort.class.getDeclaredMethod("runNativeIo", operationClass);
        method.setAccessible(true);
        try {
            method.invoke(port, operation);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                return (IOException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new AssertionError("Unexpected checked exception", cause);
        }
        throw new AssertionError("IOException expected");
    }

    private Object createNativeIoOperation(NativeIoAction action) throws Exception {
        Class<?> operationClass = Class.forName("com.hoho.android.usbserial.driver.FtdiSerialDriver$NativeIoOperation");
        return Proxy.newProxyInstance(
                operationClass.getClassLoader(),
                new Class<?>[]{operationClass},
                (proxy, method, args) -> action.run((Long) args[0]));
    }

    private interface NativeIoAction {
        Object run(long handle) throws Exception;
    }

    @Test
    public void nativeIoDisabledBeforeOpen() {
        FtdiSerialDriver.FtdiSerialPort port = createPort();
        assertFalse(port.isUsingNativeIo());
    }

    @Test
    public void closedNativeIoReportsConnectionClosed() throws Exception {
        FtdiSerialDriver.FtdiSerialPort port = createPort();
        setNativeHandle(port, 1L);

        assertEquals("Connection closed",
                assertThrows(IOException.class, () -> port.read(new byte[64], 100)).getMessage());
        assertEquals("Connection closed",
                assertThrows(IOException.class, () -> port.write(new byte[]{0x01}, 1, 100)).getMessage());
        assertEquals("Connection closed",
                assertThrows(IOException.class, () -> port.setParameters(9600, UsbSerialPort.DATABITS_8,
                        UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)).getMessage());
        assertEquals("Connection closed",
                assertThrows(IOException.class, () -> port.setRTS(true)).getMessage());
    }

    @Test
    public void runNativeIoWithoutHandleReportsConnectionClosed() throws Exception {
        FtdiSerialDriver.FtdiSerialPort port = createPort();
        AtomicBoolean called = new AtomicBoolean(false);
        port.mReadRequest = mock(UsbRequest.class);

        IOException ex = invokeRunNativeIo(port, createNativeIoOperation(handle -> {
            called.set(true);
            return null;
        }));

        assertEquals("Connection closed", ex.getMessage());
        assertFalse(called.get());
    }

    @Test
    public void runNativeIoMapsConcurrentCloseToConnectionClosed() throws Exception {
        FtdiSerialDriver.FtdiSerialPort port = createPort();
        port.mReadRequest = mock(UsbRequest.class);
        setNativeHandle(port, 1L);

        IOException ex = invokeRunNativeIo(port, createNativeIoOperation(handle -> {
            port.mReadRequest = null;
            setNativeHandle(port, 0L);
            throw new IOException("boom");
        }));

        assertEquals("Connection closed", ex.getMessage());
    }

    @Test
    public void readFilter() throws Exception {
        byte[] buf = new byte[2048];
        int len;

        when(readEndpoint.getMaxPacketSize()).thenReturn(64);
        FtdiSerialDriver.FtdiSerialPort port = createPort();
        port.mReadEndpoint = readEndpoint;

        len = port.readFilter(buf, 0);
        assertEquals(len, 0);

        assertThrows(IOException.class, () -> port.readFilter(buf, 1));

        initBuf(buf);
        len = port.readFilter(buf, 2);
        assertEquals(len, 0);

        initBuf(buf);
        len = port.readFilter(buf, 3);
        assertEquals(len, 1);
        assertTrue(testBuf(buf, len));

        initBuf(buf);
        len = port.readFilter(buf, 4);
        assertEquals(len, 2);
        assertTrue(testBuf(buf, len));

        initBuf(buf);
        len = port.readFilter(buf, 64);
        assertEquals(len, 62);
        assertTrue(testBuf(buf, len));

        assertThrows(IOException.class, () -> port.readFilter(buf, 65));

        initBuf(buf);
        len = port.readFilter(buf, 66);
        assertEquals(len, 62);
        assertTrue(testBuf(buf, len));

        initBuf(buf);
        len = port.readFilter(buf, 68);
        assertEquals(len, 64);
        assertTrue(testBuf(buf, len));

        initBuf(buf);
        len = port.readFilter(buf, 16*64+11);
        assertEquals(len, 16*62+9);
        assertTrue(testBuf(buf, len));
    }

    @Test
    public void nativeWriteHonorsConfiguredWriteBufferSize() throws Exception {
        when(usbDevice.getInterfaceCount()).thenReturn(1);
        when(writeEndpoint.getMaxPacketSize()).thenReturn(64);
        List<Integer> requestLengths = new ArrayList<>();
        FtdiSerialDriver driver = new FtdiSerialDriver(usbDevice);
        FtdiSerialDriver.FtdiSerialPort port = driver.new FtdiSerialPort(usbDevice, 0) {
            @Override
            int nativeWriteRequest(long handle, byte[] src, int length, int timeout) {
                requestLengths.add(length);
                return length;
            }
        };
        UsbDeviceConnection connection = mock(UsbDeviceConnection.class);
        when(connection.controlTransfer(anyInt(), anyInt(), anyInt(), anyInt(), any(byte[].class), anyInt(), anyInt())).thenReturn(2);
        port.mConnection = connection;
        port.mReadRequest = mock(UsbRequest.class);
        port.mWriteEndpoint = writeEndpoint;
        port.setWriteBufferSize(10);
        setNativeHandle(port, 1L);

        port.write(new byte[25], 25, 100);

        assertEquals(List.of(10, 10, 5), requestLengths);
    }

    @Test
    public void nativeWriteTimeoutReportsCommonStyleBytesTransferred() throws Exception {
        when(usbDevice.getInterfaceCount()).thenReturn(1);
        when(writeEndpoint.getMaxPacketSize()).thenReturn(64);
        AtomicInteger callCount = new AtomicInteger();
        FtdiSerialDriver driver = new FtdiSerialDriver(usbDevice);
        FtdiSerialDriver.FtdiSerialPort port = driver.new FtdiSerialPort(usbDevice, 0) {
            @Override
            int nativeWriteRequest(long handle, byte[] src, int length, int timeout) {
                return callCount.getAndIncrement() == 0 ? length : 0;
            }
        };
        UsbDeviceConnection connection = mock(UsbDeviceConnection.class);
        when(connection.controlTransfer(anyInt(), anyInt(), anyInt(), anyInt(), any(byte[].class), anyInt(), anyInt())).thenReturn(2);
        port.mConnection = connection;
        port.mReadRequest = mock(UsbRequest.class);
        port.mWriteEndpoint = writeEndpoint;
        port.setWriteBufferSize(10);
        setNativeHandle(port, 1L);

        SerialTimeoutException ex = assertThrows(SerialTimeoutException.class,
                () -> port.write(new byte[25], 25, 100));

        assertEquals(10, ex.bytesTransferred);
        assertTrue(ex.getMessage(), ex.getMessage().endsWith("rc=-1"));
    }
}