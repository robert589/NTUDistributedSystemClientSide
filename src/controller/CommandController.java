package controller;

import client.MessageType;
import entity.ServerResponse;
import marshalling.Marshaller;
import marshalling.UnMarshaller;

import java.io.IOException;
import java.io.StringReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import entity.FileServerThread;
/**
 * Created by user on 1/4/2016.
 */
public class CommandController {
    private int port;

    private InetAddress address;

    private static DatagramSocket socket = null;

    private int sequenceNum = 0;

    private int operation;

    private CacheController cacheController = new CacheController();

    private boolean halted = false;

    public CommandController(int port, InetAddress address) {
        this.port = port;
        this.address = address;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
        }

    public InetAddress getAddress() {
        return address;
    }

    public void setAddress(InetAddress address) {

        this.address = address;

    }

    public ServerResponse waitingForResponse(){
        while (true) {
            System.out.println("Waiting for response");
            byte[] recvBuf = new byte[FileServerThread.MAX_PACKET_BYTES];
            DatagramPacket packet = new DatagramPacket(recvBuf, recvBuf.length);

            ServerResponse response = receive(packet);
            if(response != null){
                return response;
            }
        }

    }


    public String readFileContent(String filePath, int offset, int numOfBytes) throws Exception {
        //set the operation to be read
        this.operation = MessageType.READ_COMMAND;

        socket = new DatagramSocket();

        byte[] readFromCache = cacheController.readFile(filePath, offset, numOfBytes);

        if(readFromCache != null){
            return "Read from cache" + new String(readFromCache);
        }
        else{
            // Parameters for READ_FILE - PathName, Offset, Length (set this to -1 if you want to read to end), SequenceNumber
            send(new Marshaller((byte) MessageType.READ_FILE, filePath, offset, numOfBytes, sequenceNum++).getBytes());
            ServerResponse response =  waitingForResponse();
            cacheController.addNew(filePath, offset, numOfBytes, response.getStatus().getBytes());
            return response.getTemplate();
            // Parameters for MONITOR_FILE - PathName, IntervalMilliseconds, SequenceNumber
        }
    }


    public String writeFileContent(String filePath, int offset, byte[] bytesToWrite, boolean at_most_once) throws Exception {
        this.operation = MessageType.INSERT_COMMAND;

        socket = new DatagramSocket();

        if(at_most_once){

            //send(new Marshaller((byte) MessageType.INSERT_FILE, "test", 1, "b".getBytes(), sequenceNum).getBytes());
            send(new Marshaller((byte) MessageType.INSERT_FILE, filePath, offset, bytesToWrite, sequenceNum++).getBytes());
        }
        else{
            send(new Marshaller((byte) MessageType.AT_LEAST_ONCE_DEMO_INSERT_FILE, filePath, offset, bytesToWrite, -1).getBytes());
        }

        ServerResponse response = waitingForResponse();
        return response.getTemplate();
    }

    /**
     * User this one will not increase the sequest number, the server will notice this as duplicate
     * @param filePath
     * @param offset
     * @param bytesToWrite
     * @return
     * @throws Exception
     */
    public String writeDuplicateFileContent(String filePath, int offset, byte[] bytesToWrite, boolean at_most_once) throws  Exception{
        this.operation = MessageType.INSERT_COMMAND;

        socket = new DatagramSocket();

        if(at_most_once){
            //send(new Marshaller((byte) MessageType.INSERT_FILE, "test", 1, "b".getBytes(), sequenceNum).getBytes());
            send(new Marshaller((byte) MessageType.INSERT_FILE, filePath, offset, bytesToWrite, sequenceNum).getBytes());
            send(new Marshaller((byte) MessageType.INSERT_FILE, filePath, offset, bytesToWrite, sequenceNum).getBytes());

        }
        else{
            send(new Marshaller((byte) MessageType.AT_LEAST_ONCE_DEMO_INSERT_FILE, filePath, offset, bytesToWrite, -1).getBytes());
            send(new Marshaller((byte) MessageType.AT_LEAST_ONCE_DEMO_INSERT_FILE, filePath, offset, bytesToWrite, -1).getBytes());
        }

        ServerResponse response = waitingForResponse();
        return response.getTemplate();
    }

    public String monitorFile(String filePath, int intervalMilliSeconds) throws Exception {
        this.operation = MessageType.MONITOR_COMMAND;

        socket = new DatagramSocket();

        //send(new Marshaller((byte) MessageType.INSERT_FILE, "test", 1, "b".getBytes(), sequenceNum).getBytes());
        send(new Marshaller((byte) MessageType.MONITOR_FILE, filePath, intervalMilliSeconds, sequenceNum++).getBytes());
        // Parameters for MONITOR_FILE - PathName, IntervalMilliseconds, SequenceNumber
        ServerResponse response = waitingForResponse();
        return response.getTemplate();
    }




    private void send(byte[] buf) throws IOException {
        DatagramPacket packet = new DatagramPacket(buf, buf.length,
                this.address, this.port);
        socket.send(packet);
    }

    private ServerResponse receive(DatagramPacket packet) {
        try {
            socket.receive(packet);

            UnMarshaller um = new UnMarshaller(packet.getData());
            int resType = (byte) um.getNextByte();
            String response;
            String seq_num;
            String template;

            // Handles responses
            switch (resType) {
                case MessageType.RESPONSE_MSG:
                    response = (String)um.getNext();
                    seq_num = (String) um.getNext();
                    template = "Response Received: " + response + " seq num = " + seq_num;
                    // Only normal responses have the sequence numbers embedded

                    return new ServerResponse(response, seq_num, template, MessageType.RESPONSE_MSG);
                case MessageType.RESPONSE_BYTES:

                    response = new String((byte[]) um.getNext());
                    seq_num = Integer.toString((int)(um.getNext()));
                    template = "Bytes received - length: " + response + " seq num = " + um.getNext();
                    // Only normal responses have the sequence numbers embedded
                    return new ServerResponse(response, seq_num, template, MessageType.RESPONSE_BYTES);

                case MessageType.CALLBACK:
                    response = (String) um.getNext();
                    seq_num = Integer.toString(((byte[]) um.getNext()).length);
                    template = "Bytes received - length: " + response + " seq num = " + um.getNext();
                    return new ServerResponse(response, seq_num, template, MessageType.CALLBACK);

                case MessageType.ERROR:
                    response = (String) um.getNext();
                    seq_num = (String) um.getNext();
                    template = "Error occured - code " + response + ": " + seq_num;
                    return new ServerResponse(response, seq_num,template, MessageType.ERROR);

                case MessageType.RESPONSE_PATH:
                    // Only normal responses have the sequence numbers embedded
                    String path = (String) um.getNext();
                    send(new Marshaller((byte) MessageType.DELETE_FILE, path, sequenceNum++).getBytes());
                    seq_num = (String)um.getNext();
                    template = "Duplicated file path recvd: " + path + " seq num = " + seq_num;
                    return new ServerResponse(path,seq_num, template, MessageType.RESPONSE_PATH);
                case MessageType.RESPONSE_SUCCESS:
                    response = (String) um.getNext();
                    seq_num = Integer.toString((int)um.getNext());
                    template = response + " seq num = " + seq_num;
                    return new ServerResponse(response, seq_num,template, MessageType.RESPONSE_SUCCESS);
                default:
                    response = (String) um.getNext();
                    template = "Strange request received" + response;
                    return new ServerResponse(response, template, -100);
            }
        }
        catch(Exception e){
            e.printStackTrace();
        }

        return null;

    }
}
