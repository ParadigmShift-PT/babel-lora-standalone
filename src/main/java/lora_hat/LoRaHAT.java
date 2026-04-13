package lora_hat;

import com.fazecast.jSerialComm.SerialPort;
import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.DigitalOutput;
import com.pi4j.io.gpio.digital.DigitalState;
import lora_hat.LoRaHAT.E22Config.AirSpeed;
import lora_hat.LoRaHAT.E22Config.Baud;
import lora_hat.LoRaHAT.E22Config.BufferSize;
import lora_hat.LoRaHAT.E22Config.Power;
import lora_hat.LoRaHAT.E22Config.TransferMethod;

public class LoRaHAT {
    private static final int START_FREQ = 850;
    private static final int FREQ_MHZ = 868;
    private static final int FREQ_OFFSET = FREQ_MHZ - START_FREQ;
    private static final int BROADCAST_ADDR = 0xFFFF;
    private static final boolean PACKET_RSSI = true;
    private final short ownAddr;

    private final SerialPort port;
    private volatile boolean running = false;
    private Thread readerThread;

    private DigitalOutput m0;
    private static final int M0_PIN = 22;
    private DigitalOutput m1;
    private static final int M1_PIN = 27;

    private final E22Config cfg;

	public LoRaHAT(Context pi4j, int ownAddr, String serialPort) {
        this(pi4j, (short)ownAddr, serialPort);
    }

    public LoRaHAT(Context pi4j, short ownAddr, String serialPort) {
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

        this.cfg = new E22Config.Builder()
                            .persist(true)
                            .transferMethod(TransferMethod.FIXED)
                            .ownAddress(ownAddr)
                            .netId(0x00)
                            .baud(Baud.B9600)
                            .airSpeed(AirSpeed.BPS_2400)
                            .bufferSize(BufferSize.BYTES_240)
                            .power(Power.DBM_22)
                            .channelRssi(true)
                            .channel(18)
                            .packetRssi(true)
                            .crypt(0x0000)
                            .build();

        this.ownAddr = ownAddr;
        this.port = SerialPort.getCommPort(serialPort);
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

        byte[] cfg_bytes = cfg.toBytes();
        System.out.print("Config bytes: ");
        for (byte b : cfg_bytes)
            System.out.printf("%02X ", b);
        System.out.println();

        int written = port.writeBytes(cfg_bytes, cfg_bytes.length);
        System.out.println("Wrote " + written + " of " + cfg_bytes.length +
                           " bytes");
        Thread.sleep(200);

        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 1000, 0);
        byte[] response = new byte[12];
        int read = port.readBytes(response, 12);
        System.out.println("Read " + read + " bytes, first: " +
                           String.format("0x%02X", response[0] & 0xFF));
        port.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);
        // int available = port.bytesAvailable();
        // if (available > 0) {
        // byte[] response = new byte[available];
        // port.readBytes(response, available);
        // if (response[0] != (byte)0xC1) {
        // throw new RuntimeException(
        // "Config failed, unexpected response: " +
        // String.format("0x%02X", response[0] & 0xFF));
        // }
        // } else {
        // throw new RuntimeException(
        // "No response from hat during configuration");
        // }

        m0.state(DigitalState.LOW);
        m1.state(DigitalState.LOW);
        Thread.sleep(100);

        port.flushIOBuffers();

        running = true;
        readerThread = new Thread(this::readLoop, "lora-reader");
        readerThread.setDaemon(true);
        readerThread.start();
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
        long lastByteTime = 0;
        final long FRAME_TIMEOUT_MS = 50;

        try {
            while (running && !Thread.currentThread().isInterrupted()) {
                int available = port.bytesAvailable();
                if (available > 0) {
                    byte[] chunk = new byte[available];
                    port.readBytes(chunk, available);
                    System.arraycopy(chunk, 0, accumulator, acc, available);
                    acc += available;
                    lastByteTime = System.currentTimeMillis();
                } else if (acc > 0 &&
                           System.currentTimeMillis() - lastByteTime >
                               FRAME_TIMEOUT_MS) {
                    onPacketReceived(accumulator, acc);
                    acc = 0;
                } else {
                    Thread.sleep(5);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void onPacketReceived(byte[] raw, int len) {
        int payloadLen = len - (PACKET_RSSI ? 1 : 0);
        if (payloadLen < 0) {
            System.err.println("Frame too short");
            return;
        }

        byte[] payload = new byte[payloadLen];
        System.arraycopy(raw, 0, payload, 0, payloadLen);

        System.out.printf("Payload (%d bytes): %s%n", payloadLen,
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

    public E22Config getE22Config() {
		return cfg;
	}

    public static class E22Config {

        public enum Baud {
            B1200(0x00),
            B2400(0x20),
            B4800(0x40),
            B9600(0x60),
            B19200(0x80),
            B38400(0xA0),
            B57600(0xC0),
            B115200(0xE0);

            final int value;

            Baud(int value) { this.value = value; }
        }

        public enum AirSpeed {
            BPS_300(0x00),
            BPS_1200(0x01),
            BPS_2400(0x02),
            BPS_4800(0x03),
            BPS_9600(0x04),
            BPS_19200(0x05),
            BPS_38400(0x06),
            BPS_62500(0x07);

            final int value;

            AirSpeed(int value) { this.value = value; }
        }

        public enum BufferSize {
            BYTES_240(0x00),
            BYTES_128(0x40),
            BYTES_64(0x80),
            BYTES_32(0xC0);

            final int value;

            BufferSize(int value) { this.value = value; }
        }

        public enum Power {
            DBM_22(0x00),
            DBM_17(0x01),
            DBM_13(0x02),
            DBM_10(0x03);

            final int value;

            Power(int value) { this.value = value; }
        }

        public enum TransferMethod {
            TRANSPARENT(0x00),
            FIXED(0x40);

            final int value;

            TransferMethod(int value) { this.value = value; }
        }

        private final boolean persist;
        private final int ownAddr;
        private final int netId;
        private final Baud baud;
        private final AirSpeed airSpeed;
        private final BufferSize bufferSize;
        private final Power power;
        private final boolean channelRssi;
        private final int channel;
        private final TransferMethod transferMethod;
        private final boolean packetRssi;
        private final int crypt;

        private E22Config(Builder b) {
            this.persist = b.persist;
            this.ownAddr = b.ownAddr;
            this.netId = b.netId;
            this.baud = b.baud;
            this.airSpeed = b.airSpeed;
            this.bufferSize = b.bufferSize;
            this.power = b.power;
            this.channelRssi = b.channelRssi;
            this.channel = b.channel;
            this.transferMethod = b.transferMethod;
            this.packetRssi = b.packetRssi;
            this.crypt = b.crypt;
        }

        public byte[] toBytes() {
            int cmd = persist ? 0xC0 : 0xC2;
            int reg3 = baud.value | airSpeed.value;
            int reg4 =
                bufferSize.value | power.value | (channelRssi ? 0x20 : 0x00);
            int reg6 = (packetRssi ? 0x80 : 0x00) | transferMethod.value | 0x43;

            int useAddr = transferMethod == TransferMethod.TRANSPARENT ? 0x000 : ownAddr;

            return new byte[] {(byte)cmd,
                               (byte)0x00,
                               (byte)0x09,
                               (byte)(useAddr >> 8),
                               (byte)(useAddr & 0xFF),
                               (byte)(netId & 0xFF),
                               (byte)reg3,
                               (byte)reg4,
                               (byte)(channel & 0xFF),
                               (byte)reg6,
                               (byte)(crypt >> 8),
                               (byte)(crypt & 0xFF)};
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            byte[] b = toBytes();
            sb.append(String.format("cmd          : 0x%02X (%s)\n", b[0] & 0xFF,
                                    persist ? "persist" : "temporary"));
            sb.append(String.format("own address  : 0x%04X\n", ownAddr));
            if (transferMethod == TransferMethod.TRANSPARENT) {
                sb.append(String.format("\t\t^ unused due to transfer method\n"));
            }
            sb.append(String.format("net id       : 0x%02X\n", netId));
            sb.append(String.format("baud         : %s\n", baud));
            sb.append(String.format("air speed    : %s\n", airSpeed));
            sb.append(String.format("buffer size  : %s\n", bufferSize));
            sb.append(String.format("power        : %s\n", power));
            sb.append(String.format("channel rssi : %s\n", channelRssi));
            sb.append(String.format("channel      : %d (%.3f MHz)\n", channel,
                                    850.125 + channel));
            sb.append(String.format("transfer     : %s\n", transferMethod));
            sb.append(String.format("packet rssi  : %s\n", packetRssi));
            sb.append(String.format("crypt        : 0x%04X\n", crypt));
            sb.append("raw bytes    : ");
            for (byte byt : b)
                sb.append(String.format("%02X ", byt & 0xFF));
            return sb.toString().trim();
        }

        public static class Builder {
            private boolean persist = false;
            private int ownAddr = 0x0000;
            private int netId = 0x00;
            private Baud baud = Baud.B9600;
            private AirSpeed airSpeed = AirSpeed.BPS_2400;
            private BufferSize bufferSize = BufferSize.BYTES_240;
            private Power power = Power.DBM_22;
            private boolean channelRssi = false;
            private int channel = 18;
            private TransferMethod transferMethod = TransferMethod.FIXED;
            private boolean packetRssi = false;
            private int crypt = 0x0000;

            public Builder persist(boolean persist) {
                this.persist = persist;
                return this;
            }

            public Builder ownAddress(int addr) {
                this.ownAddr = addr & 0xFFFF;
                return this;
            }

            public Builder netId(int netId) {
                this.netId = netId & 0xFF;
                return this;
            }

            public Builder baud(Baud baud) {
                this.baud = baud;
                return this;
            }

            public Builder airSpeed(AirSpeed airSpeed) {
                this.airSpeed = airSpeed;
                return this;
            }

            public Builder bufferSize(BufferSize bufferSize) {
                this.bufferSize = bufferSize;
                return this;
            }

            public Builder power(Power power) {
                this.power = power;
                return this;
            }

            public Builder channelRssi(boolean enabled) {
                this.channelRssi = enabled;
                return this;
            }

            public Builder channel(int channel) {
                this.channel = channel & 0xFF;
                return this;
            }

            public Builder transferMethod(TransferMethod method) {
                this.transferMethod = method;
                return this;
            }

            public Builder packetRssi(boolean enabled) {
                this.packetRssi = enabled;
                return this;
            }

            public Builder crypt(int crypt) {
                this.crypt = crypt & 0xFFFF;
                return this;
            }

            public E22Config build() { return new E22Config(this); }
        }
    }
}
