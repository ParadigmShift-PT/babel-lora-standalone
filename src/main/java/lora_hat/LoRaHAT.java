package lora_hat;

import com.fazecast.jSerialComm.SerialPort;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class LoRaHAT {
    private final SerialPort serial;

    private volatile boolean running = false;
    private Thread reader;

    public LoRaHAT(SerialPort serial) { this.serial = serial; }
}
