package com.android.grafika.utils;

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;

public class UdpSend {
    public DatagramSocket socket;
    public InetAddress address;
    private static final int MAX_PACKET_SIZE = 1400; // UDP packet max size (consider network MTU)
    private static final String TAG = "UdpH264Sender";

    public UdpSend() {
        try {
            socket = new DatagramSocket();
            address = InetAddress.getByName("192.168.5.78");
        } catch (SocketException | UnknownHostException e) {
            e.printStackTrace();
        }
    }
    public void sendH264Data(byte[] data) {
        try {
            int offset = 0;
            while (offset < data.length) {
                int packetSize = Math.min(MAX_PACKET_SIZE, data.length - offset);
                DatagramPacket packet = new DatagramPacket(data, offset, packetSize, address, 8080);
                socket.send(packet);
                offset += packetSize;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending H.264 data", e);
        }
    }


    public void receiver() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    DatagramSocket datagramSocket = new DatagramSocket(5000);
                    while (true) {
                        //准备接收
                        byte[] container = new byte[1024];
                        DatagramPacket datagramPacket = new DatagramPacket(container, 0, container.length);
                        datagramSocket.receive(datagramPacket);//阻塞式接收
                        //断开连接
                        byte[] datas = datagramPacket.getData();
                        Log.i("TAG", "run: ===============" + datas.length);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }
}
