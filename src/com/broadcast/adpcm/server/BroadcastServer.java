package com.broadcast.adpcm.server;

import javax.sound.sampled.LineUnavailableException;
import java.io.IOException;

/**
 * BroadcastServer - Entry point utama sistem broadcast ADPCM.
 * Inisialisasi dan menjalankan server broadcast.
 */
public final class BroadcastServer {
    
    private static BroadcastEngine engine;
    private static ServerConfig config;
    private static volatile boolean shutdownRequested = false;
    
    static {
        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutdown hook triggered");
            shutdownRequested = true;
            if (engine != null) {
                engine.stop();
            }
        }));
    }
    
    public static void main(String[] args) {
        System.out.println("=== ADPCM G.726 Broadcast Server ===");
        System.out.println("Version: 1.0");
        System.out.println("Protocol: G.726 32 kbps");
        System.out.println();
        
        // Parse command line arguments
        config = parseArguments(args);
        
        if (config == null) {
            printUsage();
            System.exit(1);
        }
        
        System.out.println("Configuration: " + config);
        System.out.println();
        
        try {
            // Initialize and start engine
            engine = new BroadcastEngine(config);
            engine.start();
            
            System.out.println();
            System.out.println("Server is running. Press Ctrl+C to stop...");
            System.out.println();
            
            // Wait for shutdown
            waitForShutdown();
            
        } catch (LineUnavailableException e) {
            System.err.println("ERROR: Gagal membuka sumber audio (mikrofon tidak tersedia/dipakai aplikasi lain, ATAU file audio tidak ditemukan)");
            System.err.println("Detail: " + e.getMessage());
            e.printStackTrace();
            System.exit(2);
        } catch (IOException e) {
            System.err.println("ERROR: Failed to initialize server: " + e.getMessage());
            e.printStackTrace();
            System.exit(3);
        }
    }
    
    /**
     * Parse command line arguments.
     */
    private static ServerConfig parseArguments(String[] args) {
        ServerConfig.Builder builder = new ServerConfig.Builder();
        
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            
            switch (arg) {
                case "-p":
                case "--port":
                    if (i + 1 < args.length) {
                        builder.udpPort(Integer.parseInt(args[++i]));
                    }
                    break;
                    
                case "-m":
                case "--multicast":
                    builder.useMulticast(true);
                    builder.useBroadcast(false);
                    if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                        builder.multicastAddress(args[++i]);
                    }
                    break;
                    
                case "-b":
                case "--broadcast":
                    builder.useMulticast(false);
                    builder.useBroadcast(true);
                    break;
                    
                case "-u":
                case "--unicast":
                    builder.useMulticast(false);
                    builder.useBroadcast(false);
                    break;
                    
                case "-r":
                case "--samplerate":
                    if (i + 1 < args.length) {
                        builder.sampleRate(Integer.parseInt(args[++i]));
                    }
                    break;
                    
                case "-f":
                case "--frame":
                    if (i + 1 < args.length) {
                        builder.frameSizeMs(Integer.parseInt(args[++i]));
                    }
                    break;
                    
                case "-i":
                case "--interval":
                    if (i + 1 < args.length) {
                        builder.sendIntervalMs(Integer.parseInt(args[++i]));
                    }
                    break;
                    
                case "--no-heartbeat":
                    builder.sendHeartbeat(false);
                    break;
                    
                case "--checksum":
                    builder.enableChecksum(true);
                    break;
                    
                case "-v":
                case "--verbose":
                    builder.verboseLogging(true);
                    break;
                    
                case "--save-pcm":
                    if (i + 1 < args.length) {
                        builder.saveRawPCM(args[++i]);
                    }
                    break;
                    
                case "--save-adpcm":
                    if (i + 1 < args.length) {
                        builder.saveADPCM(args[++i]);
                    }
                    break;
                    
                case "-c":
                case "--codec":
                    if (i + 1 < args.length) {
                        String codecArg = args[++i].toLowerCase();
                        if (codecArg.equals("dpcm")) {
                            builder.codecType(ServerConfig.CodecType.DPCM);
                        } else if (codecArg.equals("adpcm") || codecArg.equals("g726")) {
                            builder.codecType(ServerConfig.CodecType.ADPCM_G726);
                        } else {
                            System.err.println("Unknown codec: " + codecArg + " (gunakan 'adpcm' atau 'dpcm')");
                            return null;
                        }
                    }
                    break;
                    
                case "--input-file":
                    if (i + 1 < args.length) {
                        builder.audioFilePath(args[++i]);
                    }
                    break;
                    
                case "-h":
                case "--help":
                    return null;
                    
                default:
                    System.err.println("Unknown argument: " + arg);
                    return null;
            }
        }
        
        return builder.build();
    }
    
    /**
     * Print usage information.
     */
    private static void printUsage() {
        System.out.println("Usage: java -jar adpcm-server.jar [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -p, --port <port>           UDP port (default: 50005)");
        System.out.println("  -m, --multicast [addr]      Use multicast mode (default: 239.1.2.3)");
        System.out.println("  -b, --broadcast             Use broadcast mode (default)");
        System.out.println("  -u, --unicast               Use unicast mode (add clients via API)");
        System.out.println("  -c, --codec <adpcm|dpcm>     Compression codec (default: adpcm)");
        System.out.println("  --input-file <path>         Use PCM file as audio source instead of microphone");
        System.out.println("  -r, --samplerate <hz>       Sample rate (default: 8000)");
        System.out.println("  -f, --frame <ms>            Frame size in ms (default: 10)");
        System.out.println("  -i, --interval <ms>         Send interval in ms (default: 10)");
        System.out.println("  --no-heartbeat              Disable heartbeat packets");
        System.out.println("  --checksum                  Enable packet checksum");
        System.out.println("  -v, --verbose               Enable verbose logging");
        System.out.println("  --save-pcm <file>           Save raw PCM to file (debug)");
        System.out.println("  --save-adpcm <file>         Save ADPCM to file (debug)");
        System.out.println("  -h, --help                  Show this help");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  # Broadcast mode (default)");
        System.out.println("  java -jar adpcm-server.jar");
        System.out.println();
        System.out.println("  # Multicast mode");
        System.out.println("  java -jar adpcm-server.jar -m 239.1.2.3 -p 8888");
        System.out.println();
        System.out.println("  # Custom settings");
        System.out.println("  java -jar adpcm-server.jar -b -r 16000 -f 20 -v");
        System.out.println();
        System.out.println("  # DPCM sebagai metode pembanding (Sub-bab 3.3.3)");
        System.out.println("  java -jar adpcm-server.jar -c dpcm");
        System.out.println();
        System.out.println("  # Pengujian terkontrol pakai file PCM, bukan mic (Skenario A)");
        System.out.println("  java -jar adpcm-server.jar -c dpcm --input-file variasi1_take1.pcm");
        System.out.println();
        System.out.println("  # Debug with file output");
        System.out.println("  java -jar adpcm-server.jar --save-pcm output.raw --save-adpcm output.adpcm");
    }
    
    /**
     * Wait for shutdown signal.
     */
    private static void waitForShutdown() {
        while (!shutdownRequested) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        System.out.println("Shutting down...");
    }
    
    /**
     * Programmatic API to get server instance.
     */
    public static BroadcastEngine getEngine() {
        return engine;
    }
    
    /**
     * Programmatic API to get server config.
     */
    public static ServerConfig getConfig() {
        return config;
    }
}