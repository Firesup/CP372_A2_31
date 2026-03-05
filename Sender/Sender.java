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

        //Handshake SOT (Seq=0) -> wait for ACK = 0
        DSPacket sotPacket = new DSPacket(DSPacket.TYPE_SOT, 0, null);
        sendPacket(socket, sotPacket, rcvAddress, rcvDataPort);
        long startTime = System.currentTimeMillis();
        System.out.println("[SEND] SOT Seq= 0");
        int sotTimeouts = 0;
        boolean handshakeDone = false;
        while (!handshakeDone) {
            try {
                DSPacket ack = receivePacket(socket);
                if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == 0) {
                    System.out.println("[ACK] Handshake ACK Seq=0 - Connection Established");
                    handshakeDone = true;
                }
            } catch (SocketTimeoutException e) {
                sotTimeouts++;
                if (sotTimeouts >= MAX_TIMEOUTS) critFailure(socket);
                System.out.println("[TIMEOUT] SOT timeout #" + sotTimeouts + " - retransmitting");
                sendPacket(socket, sotPacket, rcvAddress, rcvDataPort);
            }
            }

            //DATA TRANSFER (passed to S&W OR GBN)
            if (!isGBN) {
                transferStopAndWait(socket, dataPackets, rcvAddress, rcvDataPort);
            } else {
                transferGoBackN(socket, dataPackets, rcvAddress, rcvDataPort, windowSize);
            }

            // Teardown: EOT wait for ACK eotseq
            DSPacket eotPacket = new DSPacket(DSPacket.TYPE_EOT, eotSeq, null);
            sendPacket(socket, eotPacket, rcvAddress, rcvDataPort);
            System.out.println("[SEND] EOT Seq= " + eotSeq);
            int eotTimeouts = 0;
            boolean eotDone = false;
            while (!eotDone) {
                try {
                    DSPacket ack = receivePacket(socket);
                    if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == eotSeq) {
                        System.out.println("[ACK] EOT ACK Seq= " + " Teardown complete");
                        eotDone = true;
                    }
                } catch (SocketTimeoutException e) {
                    eotTimeouts++;
                    if (eotTimeouts >= MAX_TIMEOUTS) critFailure(socket);
                    System.out.println("[TIMEOUT] EOT timeout #" + eotTimeouts + " Retransmitting EOT");
                    sendPacket(socket, eotPacket, rcvAddress, senderAckPort);
                }
            }
            long endTime = System.currentTimeMillis();
            double timeElapsed = (endTime - startTime) / 1000;
            System.out.printf("Total Transmission Time: %.2f seconds%n", timeElapsed);
            socket.close();
        }

        //Stop & Wait RDT
        // wait for packet ACK before sending next
        private static void transferStopAndWait(DatagramSocket socket, List<DSPacket> packets, InetAddress rcvAddress, int rcvDataPort) throws Exception {
            System.out.println("\n[S&W] Stop And Wait Transfer beginning");
            for (DSPacket pkt : packets) {
                int expectedAck = pkt.getSeqNum();
                int timeoutCount = 0;
                boolean acked = false;

                sendPacket(socket, pkt, rcvAddress, rcvDataPort);
                System.out.println("[S&W][SEND] DATA Seq= " + expectedAck);
                while (!acked) {
                    try {
                        DSPacket ack = receivePacket(socket);
                        if (ack.getType() == DSPacket.TYPE_ACK && ack.getSeqNum() == expectedAck) {
                            System.out.println("[S&W][ACK] ACK Seq= " + expectedAck);
                            timeoutCount = 0;
                            acked = true;
                        } else {
                            System.out.println("[S&W][  ] Wrong ACK Seq= " + ack.getSeqNum() + " (Expected " + expectedAck + "), ignored");
                        }
                    } catch (SocketTimeoutException e) {
                        timeoutCount++;
                        if (timeoutCount >= MAX_TIMEOUTS) critFailure((socket));
                        System.out.println("[S&W][TIMEOUT] Seq=" + expectedAck + " timeout #" + timeoutCount + " Retransmitting");
                        sendPacket(socket, pkt, rcvAddress, rcvDataPort);
                    }
                }
            }
            System.out.println("[S&W] Stop And Wait Transfer Complete \n");
        }

        // GO-BACK-N (GBN)
        // packets sent in window size n
        private static void transferGoBackN(DatagramSocket socket, List<DSPacket> packets, InetAddress rcvAddress, int rcvDataPort, int N) throws Exception {
            int total = packets.size();
            if (total == 0) return;
            System.out.println("\n[GBN] Go Back N transfer beginning (N=" + N + ")");
            int base = 0;
            int nextToSend = 0;
            int timeoutCount= 0;
            while (base < total) {
                while (nextToSend < total && nextToSend < base + N) {
                    int avail = Math.min(base + N, total) - nextToSend;
                    int groupSize = Math.min(4, avail);
                    List<DSPacket> group = new ArrayList<>(groupSize);
                    for (int i = 0; i < groupSize; i++) {
                        group.add(packets.get(nextToSend + i));
                    }
                    List<DSPacket> toSend = ChaosEngine.permutePackets(group);
                    for (DSPacket p : toSend) {
                        sendPacket(socket, p, rcvAddress, rcvDataPort);
                        System.out.println("[GBN][SEND] DATA Seq= " + p.getSeqNum() + " [base=" + base + 
                                                                                      " next=" + (nextToSend + groupSize) +
                                                                                      " win=" + N + "]");
                    }
                    nextToSend += groupSize;
                }

                // wait for cumulative ack
                try {
                    DSPacket ack = receivePacket(socket);
                    if (ack.getType() == DSPacket.TYPE_ACK) {
                        int ackSeq = ack.getSeqNum();
                        int newBase = advanceBase(base, nextToSend, packets, ackSeq);
                        
                        if (newBase > base) {
                            System.out.println("[GBN][ACK] Cumulative ACK Seq= " + ackSeq + " base " + base + " -> " + newBase);
                            base = newBase;
                            timeoutCount = 0;
                        } else {
                            System.out.println("[GBN][   ] ACK Seq= "  + ackSeq + "does not advance window (base= " + base + ")");

                        }
                    }
                } catch (SocketTimeoutException e) {
                    timeoutCount++;
                    System.out.println("[GBN][TIMEOUT] Timeout #" + timeoutCount + " for base packet Seq= " + packets.get(base).getSeqNum());
                    if (timeoutCount >= MAX_TIMEOUTS) critFailure(socket);
                    System.out.println("[GBN][RETX] Retransmitting window [" + base + ", " + nextToSend + ")");

                    for (int i = base; i < nextToSend; i++) {
                        sendPacket(socket, packets.get(i), rcvAddress, rcvDataPort);
                        System.out.println("[GBN][RETX] DATA Seq= " + packets.get(i).getSeqNum());

                    }
                }
            }
            System.out.println("[GBN] Go Back N Transfer Complete");
        }

    //advanceBase past all packets covered by cumulative ACK
    //searches backwards from nextToSend, finding latest occurrance of ackSeq
    private static int advanceBase(int base, int nextToSend, List<DSPacket> packets, int ackSeq) {
        for (int i = nextToSend - 1; i >= base; i--) {
            if (packets.get(i).getSeqNum() == ackSeq) {
                return i + 1;
            }
        }
        return base; 
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

    private static void critFailure(DatagramSocket socket) {
        System.out.println("Unable to transfer file.");
        socket.close();
        System.exit(1);
    }
    

}