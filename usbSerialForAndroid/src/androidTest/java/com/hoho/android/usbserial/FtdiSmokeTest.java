package com.hoho.android.usbserial;

import android.content.Context;
import android.os.Debug;
import android.hardware.usb.UsbManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.runner.AndroidJUnit4;

import com.hoho.android.usbserial.driver.FtdiSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.UsbWrapper;

import java.io.ByteArrayOutputStream;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class FtdiSmokeTest {

    private static final String TAG = "FtdiSmokeTest";
    private static final int BENCHMARK_ITERATIONS = 100;
    private static final long BENCHMARK_INTERVAL_MS = 10;
    private static final int READ_RESPONSE_TIMEOUT_MS = 1500;
    private static final byte[] CHARGER_ID_QUERY = hexToBytes("FD 00 0D 0C 03 04 02 00 00 00 00 22 F8");
    private static final byte[] CHARGER_ID_RESPONSE = hexToBytes("0C 03 04 02 00 27 10 00 00 00 00");

    private static final class WriteBenchmarkResult {
        final String mode;
        final int iterations;
        final int payloadBytes;
        final long elapsedMs;
        final double activeWriteWallMs;
        final double avgWriteUs;
        final double maxWriteUs;
        final double threadCpuMs;
        final double processCpuMs;

        WriteBenchmarkResult(String mode, int iterations, int payloadBytes, long elapsedMs,
                             double activeWriteWallMs, double avgWriteUs, double maxWriteUs,
                             double threadCpuMs, double processCpuMs) {
            this.mode = mode;
            this.iterations = iterations;
            this.payloadBytes = payloadBytes;
            this.elapsedMs = elapsedMs;
            this.activeWriteWallMs = activeWriteWallMs;
            this.avgWriteUs = avgWriteUs;
            this.maxWriteUs = maxWriteUs;
            this.threadCpuMs = threadCpuMs;
            this.processCpuMs = processCpuMs;
        }

        String toLogLine() {
            double threadCpuPct = elapsedMs > 0 ? (threadCpuMs * 100.0 / elapsedMs) : 0.0;
            double processCpuPct = elapsedMs > 0 ? (processCpuMs * 100.0 / elapsedMs) : 0.0;
            return "BENCHMARK mode=" + mode
                    + " iterations=" + iterations
                    + " payloadBytes=" + payloadBytes
                    + " elapsedMs=" + elapsedMs
                    + " activeWriteWallMs=" + String.format(java.util.Locale.US, "%.3f", activeWriteWallMs)
                    + " avgWriteUs=" + String.format(java.util.Locale.US, "%.2f", avgWriteUs)
                    + " maxWriteUs=" + String.format(java.util.Locale.US, "%.2f", maxWriteUs)
                    + " threadCpuMs=" + String.format(java.util.Locale.US, "%.3f", threadCpuMs)
                    + " processCpuMs=" + String.format(java.util.Locale.US, "%.3f", processCpuMs)
                    + " threadCpuPct=" + String.format(java.util.Locale.US, "%.2f", threadCpuPct)
                    + " processCpuPct=" + String.format(java.util.Locale.US, "%.2f", processCpuPct);
        }
    }

    private FtdiSerialDriver findFtdiDriver(Context context) {
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);

        for (UsbSerialDriver candidate : availableDrivers) {
            if (candidate instanceof FtdiSerialDriver) {
                return (FtdiSerialDriver) candidate;
            }
        }
        return null;
    }

    private static byte[] hexToBytes(String hex) {
        String[] parts = hex.trim().split("\\s+");
        byte[] result = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = (byte) Integer.parseInt(parts[i], 16);
        }
        return result;
    }

    private static String toHex(byte[] data) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            if (i > 0) {
                builder.append(' ');
            }
            builder.append(String.format(java.util.Locale.US, "%02X", data[i] & 0xFF));
        }
        return builder.toString();
    }

    private long getNativeHandle(FtdiSerialDriver.FtdiSerialPort port) throws Exception {
        Field nativeHandleField = FtdiSerialDriver.FtdiSerialPort.class.getDeclaredField("nativeHandle");
        nativeHandleField.setAccessible(true);
        return nativeHandleField.getLong(port);
    }

    private UsbWrapper openConfiguredFtdi(Context context) throws Exception {
        FtdiSerialDriver driver = findFtdiDriver(context);
        Assume.assumeTrue("FTDI device not found", driver != null);

        UsbWrapper usb = new UsbWrapper(context, driver, 0);
        usb.setUp();
        usb.open(EnumSet.of(UsbWrapper.OpenCloseFlags.NO_IOMANAGER_THREAD));
        assertTrue(usb.serialPort.isOpen());
        usb.setParameters(115200, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
        usb.serialPort.setDTR(true);
        usb.serialPort.setRTS(true);
        usb.serialPort.purgeHwBuffers(true, true);
        return usb;
    }

    private void disableNativeIo(FtdiSerialDriver.FtdiSerialPort port) throws Exception {
        Field nativeHandleField = FtdiSerialDriver.FtdiSerialPort.class.getDeclaredField("nativeHandle");
        nativeHandleField.setAccessible(true);
        long nativeHandle = getNativeHandle(port);
        Assume.assumeTrue("Native FTDI bridge not active", nativeHandle != 0L);
        nativeHandleField.setLong(port, 0L);
        Log.i(TAG, "Disabled native FTDI path for Java fallback A/B test");
    }

    private void runWriteBurstAt100Hz(UsbWrapper usb) throws Exception {
        byte[] payload = "EVOBOT\r\n".getBytes();
        final int iterations = 300;
        final long intervalMs = 10;
        FtdiSerialDriver.FtdiSerialPort port = (FtdiSerialDriver.FtdiSerialPort) usb.serialPort;
        long startedAt = SystemClock.elapsedRealtime();
        long nextTick = startedAt;

        Log.i(TAG, "Starting 100Hz burst: nativeHandle=" + getNativeHandle(port) + ", iterations=" + iterations);

        for (int i = 0; i < iterations; i++) {
            try {
                usb.serialPort.write(payload, UsbWrapper.USB_WRITE_WAIT);
            } catch (IOException ex) {
                long elapsedMs = SystemClock.elapsedRealtime() - startedAt;
                long nativeHandle = getNativeHandle(port);
                Log.e(TAG, "100Hz write failed at iteration=" + i + " elapsedMs=" + elapsedMs + " nativeHandle=" + nativeHandle, ex);
                throw new AssertionError("100Hz write failed at iteration " + i + " after " + elapsedMs + "ms with nativeHandle=" + nativeHandle + ": " + ex.getMessage(), ex);
            }
            nextTick += intervalMs;
            long sleepMs = nextTick - SystemClock.elapsedRealtime();
            if (sleepMs > 0) {
                SystemClock.sleep(sleepMs);
            }
        }

        long elapsedMs = SystemClock.elapsedRealtime() - startedAt;
        assertTrue("100Hz write burst finished too slowly: " + elapsedMs + "ms", elapsedMs < 6000);
    }

    private WriteBenchmarkResult runWriteBenchmarkAt100Hz(UsbWrapper usb, String mode) throws Exception {
        byte[] payload = "EVOBOT\r\n".getBytes();
        FtdiSerialDriver.FtdiSerialPort port = (FtdiSerialDriver.FtdiSerialPort) usb.serialPort;
        long startedAt = SystemClock.elapsedRealtime();
        long nextTick = startedAt;
        long totalWriteWallNs = 0;
        long maxWriteWallNs = 0;
        long threadCpuStartNs = Debug.threadCpuTimeNanos();
        long processCpuStartMs = android.os.Process.getElapsedCpuTime();

        Log.i(TAG, "Starting benchmark: mode=" + mode + " nativeHandle=" + getNativeHandle(port)
                + " iterations=" + BENCHMARK_ITERATIONS);

        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            long writeStartedNs = System.nanoTime();
            try {
                usb.serialPort.write(payload, UsbWrapper.USB_WRITE_WAIT);
            } catch (IOException ex) {
                long elapsedMs = SystemClock.elapsedRealtime() - startedAt;
                throw new AssertionError("Benchmark failed for mode=" + mode + " at iteration=" + i
                        + " after " + elapsedMs + "ms: " + ex.getMessage(), ex);
            }
            long writeWallNs = System.nanoTime() - writeStartedNs;
            totalWriteWallNs += writeWallNs;
            maxWriteWallNs = Math.max(maxWriteWallNs, writeWallNs);

            nextTick += BENCHMARK_INTERVAL_MS;
            long sleepMs = nextTick - SystemClock.elapsedRealtime();
            if (sleepMs > 0) {
                SystemClock.sleep(sleepMs);
            }
        }

        long elapsedMs = SystemClock.elapsedRealtime() - startedAt;
        long threadCpuNs = Debug.threadCpuTimeNanos() - threadCpuStartNs;
        long processCpuMs = android.os.Process.getElapsedCpuTime() - processCpuStartMs;
        WriteBenchmarkResult result = new WriteBenchmarkResult(
                mode,
                BENCHMARK_ITERATIONS,
                payload.length,
                elapsedMs,
                totalWriteWallNs / 1_000_000.0,
                totalWriteWallNs / (double) BENCHMARK_ITERATIONS / 1_000.0,
                maxWriteWallNs / 1_000.0,
                threadCpuNs / 1_000_000.0,
                processCpuMs);
        Log.i(TAG, result.toLogLine());
        return result;
    }

    private byte[] readResponse(UsbSerialPort port, int timeoutMs, int minBytes) throws Exception {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        byte[] chunk = new byte[64];
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;

        while (SystemClock.elapsedRealtime() < deadline && response.size() < minBytes) {
            int remainingMs = (int) Math.max(1, deadline - SystemClock.elapsedRealtime());
            int len = port.read(chunk, Math.min(remainingMs, 200));
            if (len > 0) {
                response.write(chunk, 0, len);
            }
        }

        return response.toByteArray();
    }

    @Test
    public void openConfigureAndCloseFtdiDevice() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        FtdiSerialDriver driver = findFtdiDriver(context);
        Assume.assumeTrue("FTDI device not found", driver != null);

        UsbWrapper usb = new UsbWrapper(context, driver, 0);
        usb.setUp();
        try {
            usb.open(EnumSet.of(UsbWrapper.OpenCloseFlags.NO_IOMANAGER_THREAD));
            assertTrue(usb.serialPort.isOpen());

            usb.setParameters(115200, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            usb.serialPort.setDTR(true);
            usb.serialPort.setRTS(true);
            usb.serialPort.purgeHwBuffers(true, true);

            FtdiSerialDriver.FtdiSerialPort ftdiPort = (FtdiSerialDriver.FtdiSerialPort) usb.serialPort;
            int originalLatency = ftdiPort.getLatencyTimer();
            assertTrue("latency timer should be positive", originalLatency > 0);
            ftdiPort.setLatencyTimer(1);
            assertEquals(1, ftdiPort.getLatencyTimer());
            ftdiPort.setLatencyTimer(originalLatency);
            assertEquals(originalLatency, ftdiPort.getLatencyTimer());
        } finally {
            usb.tearDown();
        }

        assertFalse(usb.serialPort.isOpen());
    }

    @Test
    public void writeSmallPacketsAt100Hz() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        UsbWrapper usb = openConfiguredFtdi(context);
        try {
            runWriteBurstAt100Hz(usb);
            assertTrue(usb.serialPort.read(new byte[64], 20) >= 0);
        } finally {
            usb.tearDown();
        }

        assertFalse(usb.serialPort.isOpen());
    }

    @Test
    public void writeSmallPacketsAt100HzUsingJavaFallback() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        UsbWrapper usb = openConfiguredFtdi(context);
        try {
            disableNativeIo((FtdiSerialDriver.FtdiSerialPort) usb.serialPort);
            runWriteBurstAt100Hz(usb);
            assertTrue(usb.serialPort.read(new byte[64], 20) >= 0);
        } finally {
            usb.tearDown();
        }

        assertFalse(usb.serialPort.isOpen());
    }

    @Test
    public void benchmarkWriteSmallPacketsAt100HzNative() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        UsbWrapper usb = openConfiguredFtdi(context);
        try {
            WriteBenchmarkResult result = runWriteBenchmarkAt100Hz(usb, "native");
            assertTrue("native benchmark elapsed too slowly: " + result.elapsedMs + "ms", result.elapsedMs < 3000);
        } finally {
            usb.tearDown();
        }

        assertFalse(usb.serialPort.isOpen());
    }

    @Test
    public void benchmarkWriteSmallPacketsAt100HzUsingJavaFallback() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        UsbWrapper usb = openConfiguredFtdi(context);
        try {
            disableNativeIo((FtdiSerialDriver.FtdiSerialPort) usb.serialPort);
            WriteBenchmarkResult result = runWriteBenchmarkAt100Hz(usb, "java-fallback");
            assertTrue("java fallback benchmark elapsed too slowly: " + result.elapsedMs + "ms", result.elapsedMs < 3000);
        } finally {
            usb.tearDown();
        }

        assertFalse(usb.serialPort.isOpen());
    }

    @Test
    public void readKnownBusinessResponseUsingNativeIo() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        UsbWrapper usb = openConfiguredFtdi(context);
        try {
            FtdiSerialDriver.FtdiSerialPort port = (FtdiSerialDriver.FtdiSerialPort) usb.serialPort;
            long nativeHandle = getNativeHandle(port);
            assertTrue("Native FTDI bridge not active", nativeHandle != 0L);

            usb.serialPort.purgeHwBuffers(true, true);
            Log.i(TAG, "Sending native read-smoke query handle=" + nativeHandle + " payload=" + toHex(CHARGER_ID_QUERY));
            usb.serialPort.write(CHARGER_ID_QUERY, UsbWrapper.USB_WRITE_WAIT);

            byte[] response = readResponse(usb.serialPort, READ_RESPONSE_TIMEOUT_MS, CHARGER_ID_RESPONSE.length);
            Log.i(TAG, "Native read-smoke response len=" + response.length + " data=" + toHex(response));

            assertTrue("Expected at least " + CHARGER_ID_RESPONSE.length + " response bytes but got "
                    + response.length + ": " + toHex(response), response.length >= CHARGER_ID_RESPONSE.length);
            assertArrayEquals("Unexpected response prefix: " + toHex(response),
                    CHARGER_ID_RESPONSE,
                    Arrays.copyOf(response, CHARGER_ID_RESPONSE.length));
        } finally {
            usb.tearDown();
        }

        assertFalse(usb.serialPort.isOpen());
    }
}