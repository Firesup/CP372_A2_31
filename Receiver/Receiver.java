import java.net.*;
import java.io.*;

public class Receiver {

    public static void main(String[] args) throws Exception {
        //Arg parsing
        if (args.length != 5) {
            System.err.println("Usage: java Receiver <sender_ip> <sender_ack_port> " + "<rcv_data_port> <output_file> <RN>");
            System.exit(1);
        }
        String senderIP = args[0];
        int senderAckPort = Integer.parseInt(args[1]);
        int rcvDataPort = Integer.parseInt(args[2]);
        String outputFile = args[3];
        int rn = Integer.parseInt(args[4]);

        // Socket Setup
        DatagramSocket socket = new DatagramSocket(rcvDataPort);
        InetAddress senderAddy= InetAddress.getByName(senderIP);
        byte[] rawBuf = new byte[DSPacket.MAX_PACKET_SIZE];
        DatagramPacket dp = new DatagramPacket(rawBuf, rawBuf.length);
        int ackCount = 0; // incremented every time ack SHOULD be sent

        System.out.println("[RECEIVER] Listening on port " + rcvDataPort);
        System.out.println("[RECEIVER] Will send ACK to " + senderIP + ": " + senderAckPort);

        // HANDSHAKE (wait for SOT type=0, seq=0)
        while(true) {
            socket.receive(dp);
            DSPacket pkt = new DSPacket(rawBuf);
            if (pkt.getType() == DSPacket.TYPE_SOT && pkt.getSeqNum() == 0) {
                System.out.println("[RCV] SOT SEQ=0");
                ackCount++;
                if (!ChaosEngine.shouldDrop(ackCount, rn)) {
                    //sendACK
                    sendAck(socket, senderAddy, rcvDataPort, senderAckPort, ackCount, rn);
                    System.out.println("[ACK] Sent ACK SEQ=0 (SOT)");
                } else {
                    System.out.println("[DROP] ACK SEQ=0 (SOT) intentionally dropped (ackCount=" + ackCount + ")");
                }
                break; //handshake done
            }
        }

        // DATA Transfer
        byte[][] buffer = new byte[128][]; // sets buffers
        boolean[] occupied = new boolean[128]; // checks if free

        int expectedSeq = 1;
        int lastAcked = 0;

        FileOutputStream fos = new FileOutputStream(outputFile);
        boolean done = false;
        
        while(!done) {
            DSPacket pkt = receivePacket(socket);
            byte type = pkt.getType();
            int seq = pkt.getSeqNum();

            if (type == DSPacket.TYPE_EOT) {
                System.out.println("[RCV] DATA Seq= " + seq + "len= " + pkt.getLength() + "Expect Seq= " + expectedSeq + ")");
                if (seq == expectedSeq) {
                    buffer[seq] = pkt.getPayload();
                    occupied[seq] = true;

                    while (occupied[expectedSeq]) {
                        fos.write(buffer[expectedSeq]);
                        System.out.println("[WRITE] Delivered Seq= " + expectedSeq);
                        occupied[expectedSeq] = false;
                        buffer[expectedSeq] = null;
                        lastAcked = expectedSeq;
                        expectedSeq = (expectedSeq + 1) % 128;
                    }
                    ackCount++;
                    sendAck(socket, senderAddy, senderAckPort, lastAcked, ackCount, rn);

                } else if (isAhead(seq, expectedSeq)) {
                    if (!occupied[seq]) {
                        buffer[seq] = pkt.getPayload();
                        occupied[seq] = true;
                        System.out.println("[BUF] Buffered out of order Seq= " + seq);
                    }
                    ackCount++;
                    sendAck(socket, senderAddy, senderAckPort, lastAcked, ackCount, rn);
                } else {
                    System.out.println("[DISC] Seq= " + seq + "is below window (expected= " + expectedSeq + ") Re-ACKing lastAcked= " + lastAcked);
                    ackCount++;
                    sendAck(socket, senderAddy, senderAckPort, lastAcked, ackCount, rn);

                }

            } else {
                System.out.println("[IGNORE] Unexpected type= " + type + " Seq= " + seq);
            }
        }
        //teardown and close socket
        fos.close();
        socket.close();
        System.out.println("[DONE] Output file saved: " + outputFile);
    }

    
    // Sends a TYPE_ACK packet with the given sequence number
    private static void sendAck(DatagramSocket socket, InetAddress addr, int port, int seq, int ackCount, int rn) throws Exception {
        if (ChaosEngine.shouldDrop(ackCount, rn)) {
            System.out.println("[DROP] ACK Seq= " + seq + " intentionally dropped (ackCount= " + ackCount + ", RN= " + rn + ")");
            return;
        }
        DSPacket       ack  = new DSPacket(DSPacket.TYPE_ACK, seq, null);
        byte[]         data = ack.toBytes();
        DatagramPacket dp   = new DatagramPacket(data, data.length, addr, port);
        socket.send(dp);
        System.out.println("[ACK] Sent ACK Seq= " + seq + "  (ackCount= " + ackCount + ")");
    }


    private static DSPacket receivePacket(DatagramSocket socket) throws Exception {
        byte[]         buf = new byte[DSPacket.MAX_PACKET_SIZE];
        DatagramPacket dp  = new DatagramPacket(buf, buf.length);
        socket.receive(dp);
        return new DSPacket(buf);
    }

    private static boolean isAhead(int seq, int expected) {
        return ((seq - expected + 128) % 128) > 0;
    }
}