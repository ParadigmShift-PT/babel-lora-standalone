import com.pi4j.Pi4J;
import com.pi4j.context.Context;
import com.pi4j.plugin.gpiod.provider.gpio.digital.GpioDDigitalInputProvider;
import com.pi4j.plugin.gpiod.provider.gpio.digital.GpioDDigitalOutputProvider;
import com.pi4j.plugin.linuxfs.provider.i2c.LinuxFsI2CProvider;
import com.pi4j.plugin.raspberrypi.platform.RaspberryPiPlatform;
import com.pi4j.plugin.raspberrypi.provider.spi.RpiSpiProvider;
import lora.LoRaHAT;
import lora.LoRaPacket;

public class Main {

    static final int OWN_ADDR = 0x4321;
    static final int BROADCAST_ADDR = 0xFFFF;

    public static void main(String[] args) throws Exception {
        // By default the smoke test exercises both sides of the radio: every
        // second it transmits a small broadcast packet while the reader thread
        // prints any frames that arrive over the air.
        //
        // Flags (any order):
        //   --rx-only | rx-only | no-tx   skip transmission (pure receiver)
        //   --dest-addr <hex>              unicast to the given 16-bit address
        //                                  instead of broadcasting (e.g. 0x8AEF
        //                                  or 8AEF). Ignored when --rx-only is set.
        Args parsed = Args.parse(args);
        boolean transmit = parsed.transmit;
        Integer destAddr = parsed.destAddr; // null = broadcast

        if (transmit) {
            System.out.println(destAddr == null
                    ? String.format(
                            "Mode: TX+RX  (broadcasting to 0x%04X every 1 s)",
                            BROADCAST_ADDR)
                    : String.format(
                            "Mode: TX+RX  (unicasting to 0x%04X every 1 s)",
                            destAddr));
        } else {
            System.out.println("Mode: RX-only  (no transmissions)");
            if (destAddr != null) {
                System.out.println(
                        "Note: --dest-addr is ignored in rx-only mode.");
            }
        }

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

        if (!transmit) {
            // The reader thread is a daemon, so the JVM would exit immediately
            // if main returned. Park the main thread instead — Ctrl-C still
            // terminates the process cleanly.
            while (true) {
                Thread.sleep(60_000);
            }
        }

        LoRaPacket.Builder pktBuilder = new LoRaPacket.Builder()
                .origin(OWN_ADDR)
                .payload("hello");
        if (destAddr != null) {
            pktBuilder.destination(destAddr);
        }
        LoRaPacket packet = pktBuilder.build();

        int counter = 0;
        while (true) {
            counter++;
            System.out.println("[smoke-test] TX #" + counter);
            hat.transmit(packet);
            Thread.sleep(1000);
        }
    }

    private static final class Args {
        final boolean transmit;
        final Integer destAddr;

        private Args(boolean transmit, Integer destAddr) {
            this.transmit = transmit;
            this.destAddr = destAddr;
        }

        static Args parse(String[] args) {
            boolean transmit = true;
            Integer destAddr = null;
            for (int i = 0; i < args.length; i++) {
                String a = args[i].toLowerCase();
                switch (a) {
                    case "rx-only":
                    case "--rx-only":
                    case "no-tx":
                        transmit = false;
                        break;
                    case "--dest-addr":
                        if (i + 1 >= args.length) {
                            throw new IllegalArgumentException(
                                    "--dest-addr requires a hex address argument");
                        }
                        destAddr = parseHexAddr(args[++i]);
                        break;
                    default:
                        throw new IllegalArgumentException(
                                "Unknown argument: " + args[i]);
                }
            }
            return new Args(transmit, destAddr);
        }

        private static int parseHexAddr(String s) {
            String h = s.toLowerCase().startsWith("0x") ? s.substring(2) : s;
            try {
                return Integer.parseInt(h, 16) & 0xFFFF;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "Invalid --dest-addr value (expected hex, e.g. 0x8AEF): "
                                + s);
            }
        }
    }
}
