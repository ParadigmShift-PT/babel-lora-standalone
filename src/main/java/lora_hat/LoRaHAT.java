package lora_hat;

import com.fazecast.jSerialComm.SerialPort;
import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.DigitalOutput;
import com.pi4j.io.gpio.digital.DigitalState;

public class LoRaHAT {
    private static final int START_FREQ = 850;
    private static final int FREQ_MHZ = 868;
    private static final int FREQ_OFFSET = FREQ_MHZ - START_FREQ;
    private static final int BROADCAST_ADDR = 0xFFFF;
    private static final boolean PACKET_RSSI = true;

    private final SerialPort port;
    private volatile boolean running = false;
    private Thread readerThread;

    private DigitalOutput m0;
    private static final int M0_PIN = 22;
    private DigitalOutput m1;
    private static final int M1_PIN = 27;

    public LoRaHAT(Context pi4j, String portName) {
        this.m0 = pi4j.create(DigitalOutput.newConfigBuilder(pi4j)
                                  .id("M0")
                                  .name("M0")
                                  .address(M0_PIN)
                                  .initial(DigitalState.LOW)
                                  .build());

        this.m1 = pi4j.create(DigitalOutput.newConfigBuilder(pi4j)
                                  .id("M1")
                                  .name("M1")
                                  .address(M1_PIN)
                                  .initial(DigitalState.LOW)
                                  .build());

        this.port = SerialPort.getCommPort(portName);
        this.port.setBaudRate(9600);
        this.port.setNumDataBits(8);
        this.port.setNumStopBits(SerialPort.ONE_STOP_BIT);
        this.port.setParity(SerialPort.NO_PARITY);
        this.port.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);
    }

    public void init() throws InterruptedException {
        if (!port.openPort()) {
            throw new RuntimeException("Failed to open port: " +
                                       port.getSystemPortName());
        }

        m0.state(DigitalState.LOW);
        m1.state(DigitalState.HIGH);
        Thread.sleep(1000);

        port.flushIOBuffers();

        byte[] cfg = buildConfigRegister();
        int written = port.writeBytes(cfg, cfg.length);
        System.out.println("Wrote " + written + " of " + cfg.length + " bytes");
        Thread.sleep(200);

        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 1000, 0);
byte[] response = new byte[12];
int read = port.readBytes(response, 12);
System.out.println("Read " + read + " bytes, first: " + 
        String.format("0x%02X", response[0] & 0xFF));
port.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);
        // int available = port.bytesAvailable();
        // if (available > 0) {
        //     byte[] response = new byte[available];
        //     port.readBytes(response, available);
        //     if (response[0] != (byte)0xC1) {
        //         throw new RuntimeException(
        //             "Config failed, unexpected response: " +
        //             String.format("0x%02X", response[0] & 0xFF));
        //     }
        // } else {
        //     throw new RuntimeException(
        //         "No response from hat during configuration");
        // }

        m0.state(DigitalState.LOW);
        m1.state(DigitalState.LOW);
        Thread.sleep(100);

        running = true;
        readerThread = new Thread(this::readLoop, "lora-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private byte[] buildConfigRegister() {
        // addr=0, net_id=0, 868MHz, 22dBm, 2400 air rate, 240 byte buffer,
        // channel RSSI enabled (0x20), packet RSSI enabled (0x80), no crypt
        return new byte[] {
            (byte)0xC2, // [0] command: write config, don't persist on power off
            (byte)0x00, // [1] starting register address
            (byte)0x09, // [2] number of registers to write
            (byte)0x00, // [3] addr high
            (byte)0x00, // [4] addr low
            (byte)0x00, // [5] net id
            (byte)0x62, // [6] UART 9600 (0x60) + air rate 2400 (0x02)
            (byte)0x20, // [7] buffer 240 (0x00) + power 22dBm (0x00) + channel RSSI (0x20)
            (byte)0x12, // [8] freq offset: 868 - 850 = 18 = 0x12
            (byte)0xC3, // [9] 0x43 + packet RSSI (0x80)
            (byte)0x00, // [10] crypt high
            (byte)0x00  // [11] crypt low
        };
    }

    public void stop() {
        running = false;
        if (readerThread != null) {
            readerThread.interrupt();
        }
        port.closePort();
    }

    public void send(int recipientAddr, byte[] payload) {
        byte[] frame = new byte[3 + payload.length];
        frame[0] = (byte)(recipientAddr >> 8);
        frame[1] = (byte)(recipientAddr & 0xFF);
        frame[2] = (byte)FREQ_OFFSET;
        System.arraycopy(payload, 0, frame, 3, payload.length);
        port.writeBytes(frame, frame.length);
    }

    public void broadcast(byte[] payload) { send(BROADCAST_ADDR, payload); }

    private void readLoop() {
        byte[] accumulator = new byte[512];
        int acc = 0;

        try {
            while (running && !Thread.currentThread().isInterrupted()) {
                int available = port.bytesAvailable();
                if (available > 0) {
                    byte[] chunk = new byte[available];
                    port.readBytes(chunk, available);

                    System.arraycopy(chunk, 0, accumulator, acc, available);
                    acc += available;

                    acc = tryParseFrames(accumulator, acc);
                } else {
                    Thread.sleep(10);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private int tryParseFrames(byte[] buf, int len) {
        // minimum 3 header bytes + 1 payload byte + 1 RSSI byte (if enabled)
        int minFrame = 3 + (PACKET_RSSI ? 1 : 0) + 1;

        if (len < minFrame) {
            return len;
        }

        onPacketReceived(buf, len);
        return 0;
    }

    private void onPacketReceived(byte[] raw, int len) {
        if (len < 3) {
            System.err.println("Frame too short: " + len + " bytes");
            return;
        }

        int senderAddr = ((raw[0] & 0xFF) << 8) | (raw[1] & 0xFF);
        int freqOffset = raw[2] & 0xFF;

        int payloadLen = len - 3 - (PACKET_RSSI ? 1 : 0);
        if (payloadLen < 0) {
            System.err.println(
                "Frame too short to contain payload with RSSI enabled");
            return;
        }

        byte[] payload = new byte[payloadLen];
        System.arraycopy(raw, 3, payload, 0, payloadLen);

        System.out.printf("From: 0x%04X  Freq: %dMHz  Payload (%d bytes): %s%n",
                          senderAddr, START_FREQ + freqOffset, payloadLen,
                          bytesToHex(payload));

        if (PACKET_RSSI) {
            int rssi = -(256 - (raw[len - 1] & 0xFF));
            System.out.printf("Packet RSSI: %d dBm%n", rssi);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }
}

