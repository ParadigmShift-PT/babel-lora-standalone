import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.plugin.gpiod.provider.gpio.digital.GpioDDigitalInputProvider;
import com.pi4j.plugin.gpiod.provider.gpio.digital.GpioDDigitalOutputProvider;
import com.pi4j.plugin.linuxfs.provider.i2c.LinuxFsI2CProvider;
import com.pi4j.plugin.raspberrypi.platform.RaspberryPiPlatform;
import com.pi4j.plugin.raspberrypi.provider.spi.RpiSpiProvider;
import java.nio.charset.StandardCharsets;
import lora.LoRaHAT;
import lora.LoRaPacket;

public class Main {

    static final int OWN_ADDR = 0x4321;

    public static void main(String[] args) throws Exception {
        // By default the smoke test exercises both sides of the radio: every
        // second it transmits a small unicast packet to 0x8AEF while the
        // reader thread prints any frames that arrive over the air. Pass
        // "rx-only" (or "--rx-only" / "no-tx") as the first argument to skip
        // transmission and use the program as a pure receiver — useful when
        // diagnosing whether incoming frames are real over-the-air traffic
        // from another device or are somehow tied to this Pi's own TX cycle.
        boolean transmit = parseTransmitFlag(args);
        System.out.println("Mode: " + (transmit
                ? "TX+RX  (transmitting every 1 s)"
                : "RX-only  (no transmissions)"));

        Context pi4j = Pi4J.newContextBuilder()
                .noAutoDetect()
                .add(new RaspberryPiPlatform() {
                    @Override
                    protected String[] getProviders() {
                        return new String[] {};
                    }
                })
                .add(GpioDDigitalInputProvider.newInstance(),
                        GpioDDigitalOutputProvider.newInstance(),
                        LinuxFsI2CProvider.newInstance(),
                        RpiSpiProvider.newInstance())
                .build();

        LoRaHAT hat = new LoRaHAT(pi4j, OWN_ADDR, "/dev/ttyAMA0");
        hat.init();

        LoRaPacket bcast_packet = new LoRaPacket.Builder().origin(OWN_ADDR).payload("hello 123").build();

        LoRaPacket addressed_packet = new LoRaPacket.Builder().origin(OWN_ADDR).payload("hello").destination(0x8AEF).build();

        if (!transmit) {
            // The reader thread is a daemon, so the JVM would exit immediately
            // if main returned. Park the main thread instead — Ctrl-C still
            // terminates the process cleanly.
            while (true) {
                Thread.sleep(60_000);
            }
        }

        int counter = 0;
        while (true) {
            // hat.broadcast("123456789".getBytes(StandardCharsets.US_ASCII));
            // hat.transmit(bcast_packet);
            counter++;
            System.out.println("[smoke-test] TX #" + counter);
            hat.transmit(addressed_packet);
            Thread.sleep(1000);
        }
    }

    private static boolean parseTransmitFlag(String[] args) {
        if (args.length == 0) return true;
        String a = args[0].toLowerCase();
        return !(a.equals("rx-only") || a.equals("--rx-only") || a.equals("no-tx"));
    }
}
