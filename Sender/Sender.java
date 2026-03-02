import java.net.*;
import java.io.*;
import java.util.*;

/**
 * FTP Sender
 * ---------------
 * Transfers files using stop and wait or Go-back-N (window size arg)
 * Usage:
 *  java Sender <rcv_ip> <rcv_data_port> <sender_ack_port>
 *      <input_file> <timeout_ms> [window_size]
 * */

public class Sender {

    private static final int MAX_TIMEOUTS = 3;

    public static void main(String[] args) throws Exception {

        // arg parsing
        if (args.length < 5 || args.length > 6) {
            System.err.println("Usage: java Sender <rcv_ip> <rcv_data_port> <sender_ack_port> "
                                            + "<input_file> <timeout_ms> [window_size]");
            System.exit(1);
        }

        String rcvIP = args[0];
        int rcvDataPort = Integer.parseInt(args[1]);
        int senderAckPort = Integer.parseInt(args[2]);
        String inputFile = args[3];
        int timeoutMS = Integer.parseInt(args[4]);
        boolean isGBN = (args.length == 6);
        int windowSize = isGBN ? Integer.parseInt(args[5]) : 1;

        System.out.println("[SENDER] Mode        : " + 
                            (isGBN ? "Go-Back-N (N=" + windowSize + ")" : "Stop-and-Wait"));
        System.out.println("[SENDER] Destination : " + rcvIP + ": " + rcvDataPort);
        System.out.println("[SENDER] ACK port    : " + senderAckPort);
        System.out.println("[SENDER] Timeout     : " + timeoutMS + "ms");

        //SOCKET_SETUP
        InetAddress rcvAddress = InetAddress.getByName(rcvIP);
        DatagramSocket socket = new DatagramSocket(senderAckPort);
        socket.setSoTimeout(timeoutMS);
        
        //build packet from file
        byte[] fileData = readFile(inputFile);
        List<DSPacket> dataPackets = buildDataPackets(fileData);
        int total = dataPackets.size();
        int eotSeq = (total == 0) ? 1 : (total % 128 + 1) % 128;
        System.out.println("[SENDER] File size: " + fileData.length + "bytes -> " + total
                            + " DATA Packets, EOT seq= " + eotSeq);

        DSPacket sotPacket = new DSPacket(DSPacket.TYPE_SOT, 0, null);
        sendPacket(socket, sotPacket, rcvAddress, rcvDataPort);
        long startTime = System.currentTimeMillis();
        System.out.println("[SEND] SOT Seq=0");
        int sotTimeouts = 0;
        boolean handshakeDone = false;
        while (!handshakeDone) {
            try {
                DSPacket ack = receivePacket(socket);
                
            }
        }
      }





    //Util methods

    private static byte[] readFile(String path) throws IOException {
        File f = new File(path);
        byte[] data = new byte[(int) f.length()];
        try (FileInputStream fis = new FileInputStream(f)) {
            int done = 0;
            while (done < data.length) {
                int n = fis.read(data, done, data.length - done);
                if (n < 0) break;
                done += n;
            }
        }
        return data;
    }

    private static List<DSPacket> buildDataPackets(byte[] fileData) {
        List<DSPacket> pkts = new ArrayList<>();
        if (fileData.length == 0) return pkts;
        int seq = 1;
        int offset = 0;
        while (offset < fileData.length) {
            int len = Math.min(DSPacket.MAX_PAYLOAD_SIZE, fileData.length - offset);
            byte[] chunk = Arrays.copyOfRange(fileData, offset, offset + len);
            pkts.add(new DSPacket(DSPacket.TYPE_DATA, seq, chunk));
            seq = (seq + 1) % 128;
            offset += len;
        }
        return pkts;
    }

    private static void sendPacket(DatagramSocket socket, DSPacket pkt,
        InetAddress addr, int port) throws IOException {
        byte[] data = pkt.toBytes();
        socket.send(new DatagramPacket(data, data.length, addr, port));
    }

    private static DSPacket receivePacket(DatagramSocket socket) throws Exception {
        byte[]         buf = new byte[DSPacket.MAX_PACKET_SIZE];
        DatagramPacket dp  = new DatagramPacket(buf, buf.length);
        socket.receive(dp);
        return new DSPacket(buf);
    }

    private static void criticalFailure(DatagramSocket socket) {
        System.out.println("Unable to transfer file.");
        socket.close();
        System.exit(1);
    }
    

}