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
    }
}