package lora;

import com.fazecast.jSerialComm.SerialPort;
import com.pi4j.context.Context;
import com.pi4j.io.gpio.digital.DigitalOutput;
import com.pi4j.io.gpio.digital.DigitalState;
import java.util.function.Consumer;
import lora.LoRaHAT.E22Config.AirSpeed;
import lora.LoRaHAT.E22Config.Baud;
import lora.LoRaHAT.E22Config.BufferSize;
import lora.LoRaHAT.E22Config.Power;
import lora.LoRaHAT.E22Config.TransferMethod;

public class LoRaHAT {
    private static final int START_FREQ = 850;
    private static final int FREQ_MHZ = 868;
    private static final int FREQ_OFFSET = FREQ_MHZ - START_FREQ;
    private static final int BROADCAST_ADDR = 0xFFFF;
    private static final boolean PACKET_RSSI = true;

    private final SerialPort port;
    private volatile boolean running = false;
    private Thread readerThread;

    private final DigitalOutput m0;
    private static final int M0_PIN = 22;
    private final DigitalOutput m1;
    private static final int M1_PIN = 27;

    private final E22Config cfg;

    private volatile Consumer<LoRaPacket> packetHandler;

    public LoRaHAT(Context pi4j, E22Config cfg, String serialPort) {
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
        this.cfg = cfg;
        this.port = SerialPort.getCommPort(serialPort);
        this.port.setBaudRate(9600);
        this.port.setNumDataBits(8);
        this.port.setNumStopBits(SerialPort.ONE_STOP_BIT);
        this.port.setParity(SerialPort.NO_PARITY);
        this.port.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);
    }

    public LoRaHAT(Context pi4j, short ownAddr, String serialPort) {
        this(pi4j,
             new E22Config.Builder()
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
                 .packetRssi(PACKET_RSSI)
                 .crypt(0x0000)
                 .build(),
             serialPort);
    }

    public LoRaHAT(Context pi4j, int ownAddr, String serialPort) {
        this(pi4j, (short)ownAddr, serialPort);
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

        // Switch to SEMI_BLOCKING for the reader thread: readBytes() returns
        // when at least one byte is available, or when the read timeout
        // (RX_READ_TIMEOUT_MS) expires. This bypasses bytesAvailable() —
        // jSerialComm's cached available-byte count has been observed to get
        // stuck at zero on Linux after sustained reads, even though the
        // kernel UART buffer still has data. Direct reads do not rely on
        // that count.
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING,
                                RX_READ_TIMEOUT_MS, 0);

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
        System.out.println("Sending packet to: " +
                           String.format("0x%02X", recipientAddr));
        byte[] frame = new byte[3 + payload.length];
        frame[0] = (byte)(recipientAddr >> 8);
        frame[1] = (byte)(recipientAddr & 0xFF);
        frame[2] = (byte)(cfg.channel & 0xFF);
        System.arraycopy(payload, 0, frame, 3, payload.length);
        port.writeBytes(frame, frame.length);
    }

    private void transmitFixed(LoRaPacket packet) {
        byte[] packetBytes = packet.toBytes();
        byte[] frame = new byte[3 + packetBytes.length];
        frame[0] = (byte)(packet.getDestAddr() >> 8);
        frame[1] = (byte)(packet.getDestAddr() & 0xFF);
        frame[2] = (byte)(packet.getChannel() & 0xFF);

        System.arraycopy(packetBytes, 0, frame, 3, packetBytes.length);
        port.writeBytes(frame, frame.length);
    }

    public void transmit(LoRaPacket packet) {
        if (this.cfg.transferMethod == TransferMethod.FIXED) {
            transmitFixed(packet);
            return;
        }

        byte[] packetBytes = packet.toBytes();
        port.writeBytes(packetBytes, packetBytes.length);
    }

    public void broadcast(byte[] payload) { send(BROADCAST_ADDR, payload); }

    // The accumulator only ever needs to hold one in-flight frame, plus a few
    // back-to-back frames that the kernel UART buffer flushed in a single
    // chunk. The hard upper bound on a single E22 frame is the largest payload
    // (240 bytes when bufferSize=BYTES_240) plus the 8-byte lora_frame_t
    // header plus an optional 1-byte trailing RSSI marker — 249 bytes. 1 KiB
    // gives ~4 frames of headroom, well above anything we have seen on the
    // wire in practice.
    private static final int RX_BUFFER_SIZE = 1024;

    // SEMI_BLOCKING read timeout: how long readBytes() will wait for at least
    // one byte before returning 0. 100 ms is short enough that interruption /
    // shutdown is responsive, and long enough that the loop does not spin
    // when the radio is genuinely idle.
    private static final int RX_READ_TIMEOUT_MS = 100;

    // How long we tolerate a partial (un-parseable) buffer before declaring it
    // garbage and resyncing. At 9600 baud each UART byte arrives ~1 ms apart,
    // so a 200 ms gap with bytes still buffered means the radio has stopped
    // streaming this frame — either it was a corrupt header, line noise, or
    // we got out of sync. Generous enough to swallow any inter-byte jitter
    // while still recovering quickly from real corruption.
    private static final long FRAME_IDLE_TIMEOUT_MS = 200;

    // Verbose per-iteration logging. Toggle with -Dlora.debug=true at JVM
    // startup. Off by default; useful when diagnosing why the reader is not
    // producing frames.
    private static final boolean DEBUG = Boolean.getBoolean("lora.debug");

    /**
     * Reader-thread loop. Drains the serial port, parses one or more
     * {@link LoRaPacket}s out of every chunk read, and delivers them to the
     * registered packet handler (or {@link System#out} if none is set).
     *
     * <h3>Why direct reads instead of bytesAvailable()</h3>
     * The serial port is configured for SEMI_BLOCKING reads in
     * {@link #init()}: {@code port.readBytes()} returns as soon as at least
     * one byte is available, or after {@link #RX_READ_TIMEOUT_MS} ms with no
     * data. This deliberately avoids {@code bytesAvailable()}, whose cached
     * available-byte count has been observed to stick at zero on Linux even
     * while the kernel UART FIFO still has bytes — which would silently
     * starve the reader. Direct reads ask the OS for bytes every iteration
     * and never get out of sync.
     *
     * <h3>Framing</h3>
     * The wire format is length-prefixed: byte 7 of every frame carries the
     * payload length, so a complete frame is exactly
     * {@code 8 + payload_len (+1 RSSI)} bytes. {@link #tryDeliverOneFrame}
     * uses this to carve out frames deterministically, which means
     * back-to-back frames concatenated in the kernel UART buffer are all
     * delivered in order — no message is lost when several frames pile up
     * between reader iterations (e.g. after a brief JVM stall).
     *
     * <h3>Loss guarantees</h3>
     * Every byte read from the serial port is either parsed into a delivered
     * frame, retained in the accumulator awaiting more bytes, or dropped only
     * via one of two safety nets that cannot fire on deliverable data:
     * <ul>
     *   <li><b>Buffer overflow</b> ({@code room <= 0}): a partial frame has
     *   grown past {@link #RX_BUFFER_SIZE}. The frame drainer runs every
     *   iteration, so any complete frame already in the buffer has been
     *   delivered before we reach this branch — the only thing that can sit
     *   in the accumulator that long is a partial frame whose header asks for
     *   more bytes than the buffer can hold (i.e. a corrupt payload_len byte
     *   or genuine noise). Dropping it is the only safe recovery.</li>
     *   <li><b>Idle timeout</b> ({@link #FRAME_IDLE_TIMEOUT_MS}): bytes sit in
     *   the accumulator for longer than the gap that could ever occur within
     *   a single UART-streamed frame. Same reasoning: complete frames have
     *   already been drained, so what remains is necessarily incomplete and
     *   no further bytes are coming to complete it.</li>
     * </ul>
     * The thread also survives any unexpected exception by logging, resetting
     * the accumulator, and continuing — a single bad iteration no longer
     * kills the reader.
     */
    private void readLoop() {
        byte[] accumulator = new byte[RX_BUFFER_SIZE];
        byte[] chunk = new byte[RX_BUFFER_SIZE];
        int acc = 0;              // valid bytes currently in `accumulator`
        long lastByteTime = 0;    // timestamp of the most recent serial read
        long iterations = 0;      // diagnostic counter — only used with DEBUG

        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                // 1) Reserve space in the accumulator. A partial frame that
                //    has somehow grown past RX_BUFFER_SIZE is, by definition,
                //    not parseable (max legitimate frame is 249 bytes) — log
                //    and resync. The drain loop runs on every iteration where
                //    we read bytes, so no complete frames can be sitting in
                //    the accumulator when this fires.
                int room = RX_BUFFER_SIZE - acc;
                if (room <= 0) {
                    System.err.println("LoRa reader: RX buffer wedged on a " +
                                       "partial frame > " + RX_BUFFER_SIZE +
                                       " bytes, resyncing");
                    acc = 0;
                    room = RX_BUFFER_SIZE;
                }

                // 2) Semi-blocking read: returns as soon as >=1 byte is
                //    available, or after RX_READ_TIMEOUT_MS with no data.
                //    No reliance on bytesAvailable(); no busy-wait sleep.
                int read = port.readBytes(chunk, room);

                if (DEBUG && (++iterations % 100) == 0) {
                    System.err.println("[lora-reader] read=" + read +
                                       " acc=" + acc + " iter=" + iterations);
                }

                if (read > 0) {
                    System.arraycopy(chunk, 0, accumulator, acc, read);
                    acc += read;
                    lastByteTime = System.currentTimeMillis();

                    // 3) Drain every complete frame in the accumulator. This
                    //    is the key to lossless delivery when several frames
                    //    arrive back-to-back: we keep slicing off complete
                    //    frames (and compacting the remainder to the front
                    //    of the buffer) until only a partial frame — or
                    //    nothing — is left.
                    int consumed;
                    while ((consumed = tryDeliverOneFrame(accumulator, acc)) >
                           0) {
                        int remaining = acc - consumed;
                        if (remaining > 0) {
                            System.arraycopy(accumulator, consumed,
                                             accumulator, 0, remaining);
                        }
                        acc = remaining;
                    }
                } else if (acc > 0 &&
                           System.currentTimeMillis() - lastByteTime >
                               FRAME_IDLE_TIMEOUT_MS) {
                    // 4) The read timeout fired and the bytes we already
                    //    have have not been touched for too long — they
                    //    cannot grow into a complete frame. Drop them and
                    //    resync.
                    System.err.println("LoRa reader: dropping " + acc +
                                       " unparseable byte(s) after idle");
                    acc = 0;
                }
                // If read == 0 and acc == 0, the radio is simply idle —
                // loop and wait for the next read timeout.
            } catch (Exception e) {
                // Catching inside the loop is deliberate: an arraycopy or
                // parse error must not terminate the reader thread (which is
                // what would silently kill all reception). Resync and keep
                // going.
                System.err.println("LoRa reader: iteration failed, resyncing");
                e.printStackTrace();
                acc = 0;
            }
        }
    }

    /**
     * Attempts to carve one complete {@code lora_frame_t} out of the front of
     * {@code buf}. Returns the number of bytes consumed (header + payload +
     * optional RSSI) on success, or {@code 0} if the buffer does not yet hold
     * a complete frame. The caller is responsible for compacting any residual
     * bytes back to the front of the buffer.
     */
    private int tryDeliverOneFrame(byte[] buf, int len) {
        // Need at least the 8-byte header before we can know the frame size.
        if (len < 8) return 0;
        int payloadLen = buf[7] & 0xFF;
        int frameLen = 8 + payloadLen + (PACKET_RSSI ? 1 : 0);
        // Header says the frame is bigger than what is currently buffered —
        // wait for the rest of the bytes to arrive.
        if (len < frameLen) return 0;
        onPacketReceived(buf, frameLen);
        return frameLen;
    }

    /**
     * Registers a callback that will receive every successfully parsed
     * {@link LoRaPacket} delivered by the reader thread. The callback runs on
     * that reader thread; consumers must therefore be thread-safe (or hand off
     * to their own queue/loop). Passing {@code null} clears the handler and
     * restores the default {@code System.out} dump behaviour.
     */
    public void setPacketHandler(Consumer<LoRaPacket> handler) {
        this.packetHandler = handler;
    }

    private void onPacketReceived(byte[] raw, int len) {
        LoRaPacket packet = LoRaPacket.fromBytes(raw, len, PACKET_RSSI);
        if (packet == null) {
            System.err.println("Frame too short");
            return;
        }
        Consumer<LoRaPacket> h = this.packetHandler;
        if (h != null) {
            try {
                h.accept(packet);
            } catch (Exception e) {
                System.err.println("LoRa packet handler threw: " + e);
                e.printStackTrace();
            }
        } else {
            System.out.println(packet);
        }
    }

    public E22Config getE22Config() { return cfg; }

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
            int reg6 = (packetRssi ? 0x80 : 0x00) | transferMethod.value;

            return new byte[] {(byte)cmd,
                               (byte)0x00,
                               (byte)0x09,
                               (byte)(ownAddr >> 8),
                               (byte)(ownAddr & 0xFF),
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
            sb.append("E22Config:\n");
            sb.append(String.format("Cmd          : 0x%02X (%s)\n", b[0] & 0xFF,
                                    persist ? "persist" : "temporary"));
            sb.append(String.format("Own address  : 0x%04X\n", ownAddr));
            sb.append(String.format("Net id       : 0x%02X\n", netId));
            sb.append(String.format("Baud         : %s\n", baud));
            sb.append(String.format("Air speed    : %s\n", airSpeed));
            sb.append(String.format("Buffer size  : %s\n", bufferSize));
            sb.append(String.format("Power        : %s\n", power));
            sb.append(String.format("Channel RSSI : %s\n", channelRssi));
            sb.append(String.format("Channel      : %d (%.3f MHz)\n", channel,
                                    850.125 + channel));
            sb.append(String.format("Transfer     : %s\n", transferMethod));
            sb.append(String.format("Packet RSSI  : %s\n", packetRssi));
            sb.append(String.format("Crypt        : 0x%04X\n", crypt));
            sb.append("Raw bytes    : ");
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
