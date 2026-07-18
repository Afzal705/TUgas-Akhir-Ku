package main.java.com.broadcast.adpcm.network.udp;

import java.io.IOException;
import java.net.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * UDPBroadcastSender - Mengirim paket ADPCM ke multiple client via UDP.
 * Mendukung unicast, broadcast, dan multicast transmission.
 */
public final class UDPBroadcastSender {
    
    // Default configuration
    private static final int DEFAULT_PORT = 8888;
    private static final int DEFAULT_TTL = 64;
    private static final int SO_TIMEOUT_MS = 100;
    private static final int PACKET_BUFFER_SIZE = 65507; // Max UDP packet size
    
    // Network configuration
    private final int port;
    private final int ttl;
    private final boolean useBroadcast;
    private final boolean useMulticast;
    private final InetAddress multicastAddress;
    private final NetworkInterface networkInterface;
    
    // Socket and sending components
    private DatagramSocket socket;
    private volatile boolean isRunning;
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    
    // Statistics
    private final AtomicLong packetsSent = new AtomicLong(0);
    private final AtomicLong bytesSent = new AtomicLong(0);
    private final AtomicLong errorsCount = new AtomicLong(0);
    private final AtomicLong lastPacketTime = new AtomicLong(0);
    
    // Client tracking for unicast mode
    private final ConcurrentLinkedQueue<InetSocketAddress> clients;
    
    /**
     * Constructor untuk broadcast mode.
     */
    public UDPBroadcastSender(int port) throws SocketException {
        this(port, false, null, null, DEFAULT_TTL);
    }
    
    /**
     * Constructor untuk broadcast dengan custom TTL.
     */
    public UDPBroadcastSender(int port, int ttl) throws SocketException {
        this(port, false, null, null, ttl);
    }
    
    /**
     * Constructor untuk multicast mode.
     */
    public UDPBroadcastSender(String multicastAddress, int port, NetworkInterface netIf, int ttl) 
            throws SocketException, UnknownHostException {
        this(port, true, InetAddress.getByName(multicastAddress), netIf, ttl);
    }
    
    /**
     * Constructor untuk unicast mode (send to specific clients).
     */
    public UDPBroadcastSender(int port, boolean enableBroadcast) throws SocketException {
        this(port, enableBroadcast, null, null, DEFAULT_TTL);
    }
    
    /**
     * Constructor utama dengan semua parameter.
     */
    private UDPBroadcastSender(int port, boolean useMulticast, InetAddress multicastAddr, 
                               NetworkInterface netIf, int ttl) throws SocketException {
        this.port = port;
        this.useMulticast = useMulticast;
        this.useBroadcast = !useMulticast;
        this.multicastAddress = multicastAddr;
        this.networkInterface = netIf;
        this.ttl = ttl;
        this.clients = new ConcurrentLinkedQueue<>();
        
        initializeSocket();
    }
    
    /**
     * Initialize dan konfigurasi socket.
     */
    private void initializeSocket() throws SocketException {
        socket = new DatagramSocket();
        socket.setBroadcast(useBroadcast && !useMulticast);
        socket.setSoTimeout(SO_TIMEOUT_MS);
        socket.setReuseAddress(true);
        
        // Set send buffer size
        socket.setSendBufferSize(PACKET_BUFFER_SIZE);
        
        if (useMulticast && multicastAddress != null) {
            // Configure multicast TTL
            try {
                socket.setOption(StandardSocketOptions.IP_MULTICAST_TTL, ttl);
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            
            if (networkInterface != null) {
                try {
                    socket.setOption(StandardSocketOptions.IP_MULTICAST_IF, networkInterface);
                } catch (IOException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        }
        
        initialized.set(true);
        System.out.println("UDPBroadcastSender initialized on port " + port + 
                          (useMulticast ? " (multicast mode)" : 
                           (useBroadcast ? " (broadcast mode)" : " (unicast mode)")));
    }
    
    /**
     * Start sender thread untuk continuous transmission.
     */
    public void start() {
        if (!initialized.get()) {
            throw new IllegalStateException("Sender not initialized");
        }
        
        if (isRunning) {
            System.out.println("Sender already running");
            return;
        }
        
        isRunning = true;
        System.out.println("UDPBroadcastSender started");
    }
    
    /**
     * Stop sender.
     */
    public void stop() {
        isRunning = false;
        
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        
        clients.clear();
        System.out.println("UDPBroadcastSender stopped. Stats: packets=" + packetsSent.get() + 
                          ", bytes=" + bytesSent.get() + ", errors=" + errorsCount.get());
    }
    
    /**
     * Send single ADPCM packet to all clients.
     */
    public boolean sendPacket(byte[] adpcmData, int offset, int length) {
        if (!isRunning || socket == null || socket.isClosed()) {
            errorsCount.incrementAndGet();
            return false;
        }
        
        if (adpcmData == null || length <= 0) {
            errorsCount.incrementAndGet();
            return false;
        }
        
        try {
            if (useMulticast && multicastAddress != null) {
                // Send via multicast
                DatagramPacket packet = new DatagramPacket(adpcmData, offset, length, 
                                                          multicastAddress, port);
                socket.send(packet);
                updateStats(length);
                return true;
                
            } else if (useBroadcast) {
                // Send via broadcast (255.255.255.255 or subnet broadcast)
                InetAddress broadcastAddr = InetAddress.getByName("255.255.255.255");
                DatagramPacket packet = new DatagramPacket(adpcmData, offset, length, 
                                                          broadcastAddr, port);
                socket.send(packet);
                updateStats(length);
                return true;
                
            } else {
                // Send to all registered clients (unicast)
                if (clients.isEmpty()) {
                    return false;
                }
                
                boolean allSuccess = true;
                for (InetSocketAddress client : clients) {
                    if (!sendToClient(adpcmData, offset, length, client)) {
                        allSuccess = false;
                    }
                }
                return allSuccess;
            }
            
        } catch (IOException e) {
            errorsCount.incrementAndGet();
            System.err.println("Failed to send UDP packet: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Send packet with sequence number and timestamp header.
     */
    // ✅ SESUDAH — sesuai standar SimplePacketFormatter
    public boolean sendPacketWithHeader(byte[] adpcmData, int offset, int length,
                                        int sequenceNumber, long timestamp) {
        byte[] packetWithHeader = new byte[length + 12];

        // Timestamp (8 byte) — DULU, sesuai SimplePacketFormatter
        packetWithHeader[0] = (byte) (timestamp >> 56);
        packetWithHeader[1] = (byte) (timestamp >> 48);
        packetWithHeader[2] = (byte) (timestamp >> 40);
        packetWithHeader[3] = (byte) (timestamp >> 32);
        packetWithHeader[4] = (byte) (timestamp >> 24);
        packetWithHeader[5] = (byte) (timestamp >> 16);
        packetWithHeader[6] = (byte) (timestamp >> 8);
        packetWithHeader[7] = (byte) timestamp;

        // Sequence number (4 byte) — BELAKANGAN, sesuai SimplePacketFormatter
        packetWithHeader[8]  = (byte) (sequenceNumber >> 24);
        packetWithHeader[9]  = (byte) (sequenceNumber >> 16);
        packetWithHeader[10] = (byte) (sequenceNumber >> 8);
        packetWithHeader[11] = (byte) sequenceNumber;

        System.arraycopy(adpcmData, offset, packetWithHeader, 12, length);

        return sendPacket(packetWithHeader, 0, packetWithHeader.length);
    }
    
    /**
     * Send to specific client (unicast).
     */
    private boolean sendToClient(byte[] data, int offset, int length, InetSocketAddress client) {
        try {
            DatagramPacket packet = new DatagramPacket(data, offset, length, client);
            socket.send(packet);
            updateStats(length);
            return true;
        } catch (IOException e) {
            errorsCount.incrementAndGet();
            return false;
        }
    }
    
    /**
     * Add client for unicast mode.
     */
    public void addClient(String host, int port) {
        try {
            InetAddress address = InetAddress.getByName(host);
            clients.add(new InetSocketAddress(address, port));
            System.out.println("Client added: " + host + ":" + port);
        } catch (UnknownHostException e) {
            System.err.println("Invalid client address: " + host);
        }
    }
    
    /**
     * Add client by InetSocketAddress.
     */
    public void addClient(InetSocketAddress client) {
        clients.add(client);
        System.out.println("Client added: " + client);
    }
    
    /**
     * Remove client.
     */
    public boolean removeClient(String host, int port) {
        try {
            InetAddress address = InetAddress.getByName(host);
            return clients.remove(new InetSocketAddress(address, port));
        } catch (UnknownHostException e) {
            return false;
        }
    }
    
    /**
     * Remove all clients.
     */
    public void clearClients() {
        clients.clear();
        System.out.println("All clients removed");
    }
    
    /**
     * Get number of registered clients (unicast mode only).
     */
    public int getClientCount() {
        return clients.size();
    }
    
    /**
     * Get list of all clients.
     */
    public ConcurrentLinkedQueue<InetSocketAddress> getClients() {
        return new ConcurrentLinkedQueue<>(clients);
    }
    
    /**
     * Update transmission statistics.
     */
    private void updateStats(int bytes) {
        packetsSent.incrementAndGet();
        bytesSent.addAndGet(bytes);
        lastPacketTime.set(System.currentTimeMillis());
    }
    
    /**
     * Get packets sent count.
     */
    public long getPacketsSent() {
        return packetsSent.get();
    }
    
    /**
     * Get bytes sent count.
     */
    public long getBytesSent() {
        return bytesSent.get();
    }
    
    /**
     * Get errors count.
     */
    public long getErrorsCount() {
        return errorsCount.get();
    }
    
    /**
     * Get last packet time in milliseconds.
     */
    public long getLastPacketTime() {
        return lastPacketTime.get();
    }
    
    /**
     * Get current send rate (packets per second).
     */
    public double getSendRate() {
        long packets = packetsSent.get();
        long lastTime = lastPacketTime.get();
        
        if (packets == 0 || lastTime == 0) {
            return 0;
        }
        
        long elapsed = System.currentTimeMillis() - lastTime;
        if (elapsed <= 0) {
            return 0;
        }
        
        return (double) packets / (elapsed / 1000.0);
    }
    
    /**
     * Reset statistics.
     */
    public void resetStats() {
        packetsSent.set(0);
        bytesSent.set(0);
        errorsCount.set(0);
        lastPacketTime.set(0);
    }
    
    /**
     * Check if sender is running.
     */
    public boolean isRunning() {
        return isRunning;
    }
    
    /**
     * Check if socket is healthy.
     */
    public boolean isHealthy() {
        return isRunning && socket != null && !socket.isClosed() && !socket.isConnected();
    }
    
    /**
     * Get local port.
     */
    public int getLocalPort() {
        return socket != null ? socket.getLocalPort() : -1;
    }
    
    /**
     * Get local address.
     */
    public InetAddress getLocalAddress() {
        return socket != null ? socket.getLocalAddress() : null;
    }
    
    /**
     * Get multicast address (if in multicast mode).
     */
    public InetAddress getMulticastAddress() {
        return multicastAddress;
    }
    
    /**
     * Builder pattern untuk kemudahan konfigurasi.
     */
    public static class Builder {
        private int port = DEFAULT_PORT;
        private int ttl = DEFAULT_TTL;
        private String multicastAddress = null;
        private NetworkInterface networkInterface = null;
        private boolean enableBroadcast = false;
        
        public Builder port(int port) {
            this.port = port;
            return this;
        }
        
        public Builder ttl(int ttl) {
            this.ttl = ttl;
            return this;
        }
        
        public Builder multicast(String address) {
            this.multicastAddress = address;
            return this;
        }
        
        public Builder networkInterface(NetworkInterface netIf) {
            this.networkInterface = netIf;
            return this;
        }
        
        public Builder broadcast(boolean enable) {
            this.enableBroadcast = enable;
            return this;
        }
        
        public UDPBroadcastSender build() throws SocketException, UnknownHostException {
            if (multicastAddress != null) {
                return new UDPBroadcastSender(multicastAddress, port, networkInterface, ttl);
            } else if (enableBroadcast) {
                return new UDPBroadcastSender(port, ttl);
            } else {
                return new UDPBroadcastSender(port);
            }
        }
    }
    
    /**
     * Test method untuk verifikasi connectivity.
     */
    public boolean testConnectivity() {
        if (!isRunning || socket == null) {
            return false;
        }
        
        byte[] testPacket = "TEST".getBytes();
        return sendPacket(testPacket, 0, testPacket.length);
    }
    
    @Override
    public String toString() {
        return String.format(
            "UDPBroadcastSender[port=%d, mode=%s, packets=%d, bytes=%d, errors=%d, running=%s]",
            port,
            useMulticast ? "MULTICAST" : (useBroadcast ? "BROADCAST" : "UNICAST"),
            packetsSent.get(),
            bytesSent.get(),
            errorsCount.get(),
            isRunning
        );
    }
}