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

(The existing `Makefile` is left over from an earlier shaded-jar build and will not work against this pom — replace or delete as you prefer.)

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
