# Babel LoRa

A Java driver for the [Waveshare SX126X LoRa HAT](https://www.waveshare.com/sx1262-868m-lora-hat.htm) (EByte **E22-900T22S** module) on a Raspberry Pi. It configures the radio over UART, manages the M0/M1 mode-select pins via Pi4J, and exposes a small packet API to send and receive LoRa frames from Java code running on the Pi.

The artifact is intended to back a future Babel protocol providing LoRa connectivity to swarm-style applications on Raspberry Pi gateways. Today it is a self-contained driver — there is no Babel dependency yet.

**Group ID:** `pt.paradigmshift.iot`
**Artifact ID:** `babel-lora`
**Current version:** `0.2.0`
**Tested on:** Raspberry Pi 4 / 5 with the Waveshare SX126X 868 MHz HAT.

---

## Hardware

| Item | Value |
|---|---|
| Radio module | EByte E22-900T22S (Semtech SX1262) |
| Band | 868 MHz (EU) — `channel = 18`, `startFreq = 850 MHz` |
| Tx power | Up to +22 dBm |
| UART device | `/dev/ttyAMA0` @ 9600 baud, 8N1 |
| Mode-select pin M0 | GPIO 22 |
| Mode-select pin M1 | GPIO 27 |
| Pi4J providers | `raspberrypi`, `linuxfs`, `gpiod` (Spi + DigitalOutput) |

The driver puts the radio into **configuration mode** (M0=LOW, M1=HIGH) to write a 12-byte register block on startup, then drops to **normal mode** (M0=LOW, M1=LOW) and spawns a daemon reader thread.

### Raspberry Pi prerequisites

Enable the **serial port hardware** but keep the **serial console** disabled, otherwise the Pi will hold `/dev/ttyAMA0` for getty and the driver will not be able to open it:

```bash
sudo raspi-config
#   3 Interface Options
#   → I6 Serial Port
#       "Would you like a login shell to be accessible over serial?" → No
#       "Would you like the serial port hardware to be enabled?"     → Yes
sudo reboot
```

After reboot, `ls -l /dev/ttyAMA0` should show the device and `systemctl status serial-getty@ttyAMA0` should report it as disabled / inactive.

On Raspberry Pi 5 (and recent Pi OS images) `raspi-config` alone does not always wire `/dev/ttyAMA0` to the primary UART — append the following block to the end of `/boot/firmware/config.txt` and reboot:

```ini
[all]
dtparam=uart0=on
enable_uart=1
```

## Wire format

`LoRaPacket` carries an 8-byte header followed by the payload:

```c
typedef struct __attribute__((packed)) {
    uint16_t dest_addr;       // little-endian, 0xFFFF = broadcast
    uint8_t  channel;         // channel index (default 18 → 868 MHz)
    uint16_t origin_addr;     // little-endian
    uint16_t prev_hop_addr;   // little-endian
    uint8_t  payload_len;
    uint8_t  payload[];
} lora_frame_t;
```

When the E22 is configured with `packetRssi = true` (the default), each received frame is followed by one extra RSSI byte parsed as `(byte & 0xFF) − 256` dBm.

In **FIXED** transfer mode (the default), `LoRaHAT.transmit()` additionally prepends the 3-byte E22 routing header (`dest_hi`, `dest_lo`, `channel`) to the on-air frame; the air-side stack strips that prefix on delivery, so the receiver sees only the `lora_frame_t` above plus the trailing RSSI byte.

---

## Usage

Add to your `pom.xml`:

```xml
<repositories>
    <repository>
        <id>paradigmshift-repository</id>
        <name>ParadigmShift Repository</name>
        <url>https://maven.paradigmshift.pt/releases</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>pt.paradigmshift.iot</groupId>
        <artifactId>babel-lora</artifactId>
        <version>0.2.0</version>
    </dependency>
</dependencies>
```

### Minimal example

```java
Context pi4j = Pi4J.newContextBuilder()
        .noAutoDetect()
        .add(new RaspberryPiPlatform() {
            @Override
            protected String[] getProviders() { return new String[]{}; }
        })
        .add(GpioDDigitalInputProvider.newInstance(),
             GpioDDigitalOutputProvider.newInstance(),
             LinuxFsI2CProvider.newInstance(),
             RpiSpiProvider.newInstance())
        .build();

LoRaHAT hat = new LoRaHAT(pi4j, /* ownAddress */ 0x4321, "/dev/ttyAMA0");
hat.init();

// Broadcast
LoRaPacket bcast = new LoRaPacket.Builder()
        .origin(0x4321)
        .payload("hello world")
        .build();
hat.transmit(bcast);

// Addressed transmission
LoRaPacket unicast = new LoRaPacket.Builder()
        .origin(0x4321)
        .destination(0x8AEF)
        .payload("hello, peer")
        .build();
hat.transmit(unicast);
```

Incoming frames are delivered to a `Consumer<LoRaPacket>` callback registered via `setPacketHandler(...)`; if no handler is registered the reader thread falls back to printing every parsed packet to `System.out` (useful for the smoke test). Higher-level integrations — such as the `babel-lora-protocol` Babel protocol — install their own handler:

```java
hat.setPacketHandler(packet -> {
    // runs on the LoRa reader thread; hand off to your own loop/queue
});
```

### Receive behaviour and loss guarantees

The reader thread uses **semi-blocking serial reads** combined with **length-prefixed framing** off the `payload_len` byte at offset 7 of the wire format. Concretely:

- **Direct reads, no `bytesAvailable()` polling.** The serial port is configured for SEMI_BLOCKING mode (100 ms read timeout). Each iteration calls `readBytes()` directly: it returns as soon as the kernel UART buffer has at least one byte, or after the timeout. This deliberately bypasses `bytesAvailable()` — jSerialComm's cached available-byte counter has been observed to stick at zero on Linux under sustained traffic, silently starving the reader. Asking the OS for bytes on every iteration avoids that failure mode.
- **Back-to-back frames are all delivered.** When the kernel UART buffer hands the reader several concatenated frames in a single chunk (e.g. after a brief JVM stall), every complete frame is carved out and delivered in order; only a trailing partial frame stays in the accumulator until its remaining bytes arrive.
- **The accumulator is 1 KiB.** The hard upper bound on a single E22 frame is 249 bytes (240-byte max payload + 8-byte `lora_frame_t` header + 1 RSSI byte), so 1 KiB gives several frames of headroom. Reads are clamped to the free space remaining in the accumulator — if a stalled JVM wakes up to a full kernel queue, the leftover bytes simply stay in the kernel and are picked up on the next iteration.
- **Two safety nets exist, neither of which can drop deliverable data.** A *buffer-overflow* path resets the accumulator when a partial frame somehow grows past 1 KiB (only reachable with a corrupt `payload_len` byte or sustained line noise). An *idle-timeout* path (200 ms — well above the ~1 ms inter-byte gap on a 9600-baud UART) drops stranded partial-frame bytes once the radio has clearly stopped streaming them. In both cases, every complete frame already in the buffer has been delivered before the drop is considered.
- **The reader thread survives transient errors.** Any unexpected exception inside the loop is logged, the accumulator is reset, and reception continues. A single bad iteration no longer silently kills the thread.

### Debug logging

If reception still stalls, run the smoke test with `-Dlora.debug=true`:

```bash
java -Dlora.debug=true -jar target/babel-lora-0.2.0-executable.jar
```

The reader thread will then emit a line every 100 iterations reporting the most recent `readBytes()` return value and the current accumulator depth. This makes it possible to distinguish *"the thread died"* (no log lines at all) from *"the OS keeps returning zero bytes"* (`read=0` indefinitely), which point at very different root causes — the former is a Java-side bug, the latter is a kernel UART or radio-side issue.

### Custom radio configuration

The default constructor uses sensible defaults for the 868 MHz EU band. To override anything (air speed, Tx power, channel, AES key, etc.), build an `E22Config` explicitly:

```java
LoRaHAT.E22Config cfg = new LoRaHAT.E22Config.Builder()
        .persist(true)
        .ownAddress(0x4321)
        .channel(18)                              // 868 MHz
        .airSpeed(LoRaHAT.E22Config.AirSpeed.BPS_2400)
        .power(LoRaHAT.E22Config.Power.DBM_22)
        .transferMethod(LoRaHAT.E22Config.TransferMethod.FIXED)
        .packetRssi(true)
        .channelRssi(true)
        .build();

LoRaHAT hat = new LoRaHAT(pi4j, cfg, "/dev/ttyAMA0");
hat.init();
```

> **Hardware note:** the library compiles anywhere but will fail at runtime off a Raspberry Pi — the underlying Pi4J native providers (`gpiod`, `linuxfs`) require Linux GPIO/UART devices.

---

## Building

Requires Java 17 and Maven 3.6+.

```bash
mvn verify    # compile (no tests yet)
mvn package   # produces JAR, sources JAR, and Javadoc JAR
mvn install   # also install to ~/.m2/
mvn deploy    # publish to maven.paradigmshift.pt (requires REPOSILITE_TOKEN)
```

### Smoke test on a Pi

`Main.java` is a smoke-test entry point — it builds a Pi4J context, opens the HAT at `ownAddress = 0x4321`, and transmits a packet to `0x8AEF` every second. To run it on a Pi after building:

```bash
mvn exec:java -Dexec.mainClass=Main
```

Alternatively, the `executable` Maven profile produces a self-contained fat JAR with `Main` wired as the entry point in the manifest. This jar is intended **only for local testing on a Pi** — the profile disables `install`/`deploy`, so it is never published to the ParadigmShift Maven repository:

```bash
mvn clean package -P executable
java -jar target/babel-lora-0.2.0-executable.jar
```

The accompanying `Makefile` is a shortcut for the same flow:

```bash
make build    # mvn clean package -P executable
make run      # java -jar target/babel-lora-0.2.0-executable.jar
make          # build + run
```

## Releasing

Push a version tag — the GitHub Actions CI workflow builds and deploys automatically (mirroring the other ParadigmShift Maven libs):

```bash
git tag v0.2.0
git push origin v0.2.0
```

---

## License

Copyright (c) 2026 ParadigmShift, Lda. See [LICENSE](LICENSE) for full terms.

This artifact is developed and maintained exclusively by ParadigmShift, Lda. It has no relationship to NOVA FCT or the TaRDIS European research project.

Commercial use outside of ParadigmShift requires a written licence.
Contact: [info@paradigmshift.pt](mailto:info@paradigmshift.pt)
