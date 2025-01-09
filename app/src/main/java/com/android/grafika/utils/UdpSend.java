package com.android.grafika.utils;

import android.os.Handler;
import android.os.HandlerThread;
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

    private HandlerThread sendThread;
    private Handler sendHandler;

    public UdpSend() {
        try {
            socket = new DatagramSocket();
            address = InetAddress.getByName("192.168.10.2");


            if (sendThread == null) sendThread = new HandlerThread("udp数据发送线程");
            if (sendHandler == null) {
                sendThread.start();
                sendHandler = new Handler(sendThread.getLooper());
            }

        } catch (SocketException | UnknownHostException e) {
            e.printStackTrace();
        }
    }

    public void sendPack(final byte[] data) {
        sendHandler.post(new Runnable() {
            @Override
            public void run() {
                sendH264Data(data);
            }
        });
    }

    public void sendH264Data(byte[] data) {
        try {
            int offset = 0;
            while (offset < data.length) {
                int packetSize = Math.min(MAX_PACKET_SIZE, data.length - offset);
                DatagramPacket packet = new DatagramPacket(data, offset, packetSize, address, 5000);
                socket.send(packet);
                offset += packetSize;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending H.264 data" + e.getMessage());
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


    // -------视频--------
    private int framerate = 10;
    private byte[] sendbuf = new byte[1500];
    private int packageSize = 1400;
    private int seq_num = 0;
    private int timestamp_increse = (int) (90000.0 / framerate);//framerate是帧率
    private int ts_current = 0;
    private int bytes = 0;

    /**
     * 一帧一帧的RTP封包
     *
     * @param r
     * @return
     */
    public void h264ToRtp(byte[] r, int h264len) throws Exception {

        CommonUtils.memset(sendbuf, 0, 1500);
        sendbuf[1] = (byte) (sendbuf[1] | 96); // 负载类型号96,其值为：01100000
        sendbuf[0] = (byte) (sendbuf[0] | 0x80); // 版本号,此版本固定为2
        sendbuf[1] = (byte) (sendbuf[1] & 254); //标志位，由具体协议规定其值，其值为：01100000
        sendbuf[11] = 10;//随机指定10，并在本RTP回话中全局唯一,java默认采用网络字节序号 不用转换（同源标识符的最后一个字节）
        if (h264len <= packageSize) {
            sendbuf[1] = (byte) (sendbuf[1] | 0x80); // 设置rtp M位为1，其值为：11100000，分包的最后一片，M位（第一位）为0，后7位是十进制的96，表示负载类型
            sendbuf[3] = (byte) seq_num++;
            System.arraycopy(CommonUtils.intToByte(seq_num++), 0, sendbuf, 2, 2);//send[2]和send[3]为序列号，共两位
            {
                // java默认的网络字节序是大端字节序（无论在什么平台上），因为windows为小字节序，所以必须倒序
                /**参考：
                 * http://blog.csdn.net/u011068702/article/details/51857557
                 * http://cpjsjxy.iteye.com/blog/1591261
                 */
                byte temp = 0;
                temp = sendbuf[3];
                sendbuf[3] = sendbuf[2];
                sendbuf[2] = temp;
            }
            // FU-A HEADER, 并将这个HEADER填入sendbuf[12]
            sendbuf[12] = (byte) (sendbuf[12] | ((byte) (r[0] & 0x80)) << 7);
            sendbuf[12] = (byte) (sendbuf[12] | ((byte) ((r[0] & 0x60) >> 5)) << 5);
            sendbuf[12] = (byte) (sendbuf[12] | ((byte) (r[0] & 0x1f)));
            // 同理将sendbuf[13]赋给nalu_payload
            //NALU头已经写到sendbuf[12]中，接下来则存放的是NAL的第一个字节之后的数据。所以从r的第二个字节开始复制
            System.arraycopy(r, 1, sendbuf, 13, h264len - 1);
            ts_current = ts_current + timestamp_increse;
            System.arraycopy(CommonUtils.intToByte(ts_current), 0, sendbuf, 4, 4);//序列号接下来是时间戳，4个字节，存储后也需要倒序
            {
                byte temp = 0;
                temp = sendbuf[4];
                sendbuf[4] = sendbuf[7];
                sendbuf[7] = temp;
                temp = sendbuf[5];
                sendbuf[5] = sendbuf[6];
                sendbuf[6] = temp;
            }
            bytes = h264len + 12;//获sendbuf的长度,为nalu的长度(包含nalu头但取出起始前缀,加上rtp_header固定长度12个字节)
            //client.send(new DatagramPacket(sendbuf, bytes, addr, port/*9200*/));
            //send(sendbuf,bytes);
            //exceuteH264ToRtpLinsener(sendbuf, bytes);

        } else {
            int k = 0, l = 0;
            k = h264len / packageSize;
            l = h264len % packageSize;
            int t = 0;
            ts_current = ts_current + timestamp_increse;
            System.arraycopy(CommonUtils.intToByte(ts_current), 0, sendbuf, 4, 4);//时间戳，并且倒序
            {
                byte temp = 0;
                temp = sendbuf[4];
                sendbuf[4] = sendbuf[7];
                sendbuf[7] = temp;
                temp = sendbuf[5];
                sendbuf[5] = sendbuf[6];
                sendbuf[6] = temp;
            }
            while (t <= k) {
                System.arraycopy(CommonUtils.intToByte(seq_num++), 0, sendbuf, 2, 2);//序列号，并且倒序
                {
                    byte temp = 0;
                    temp = sendbuf[3];
                    sendbuf[3] = sendbuf[2];
                    sendbuf[2] = temp;
                }
                if (t == 0) {//分包的第一片
                    sendbuf[1] = (byte) (sendbuf[1] & 0x7F);//其值为：01100000，不是最后一片，M位（第一位）设为0
                    //FU indicator，一个字节，紧接在RTP header之后，包括F,NRI，header
                    sendbuf[12] = (byte) (sendbuf[12] | ((byte) (r[0] & 0x80)) << 7);//禁止位，为0
                    sendbuf[12] = (byte) (sendbuf[12] | ((byte) ((r[0] & 0x60) >> 5)) << 5);//NRI，表示包的重要性
                    sendbuf[12] = (byte) (sendbuf[12] | (byte) (28));//TYPE，表示此FU-A包为什么类型，一般此处为28
                    //FU header，一个字节，S,E，R，TYPE
                    sendbuf[13] = (byte) (sendbuf[13] & 0xBF);//E=0，表示是否为最后一个包，是则为1
                    sendbuf[13] = (byte) (sendbuf[13] & 0xDF);//R=0，保留位，必须设置为0
                    sendbuf[13] = (byte) (sendbuf[13] | 0x80);//S=1，表示是否为第一个包，是则为1
                    sendbuf[13] = (byte) (sendbuf[13] | ((byte) (r[0] & 0x1f)));//TYPE，即NALU头对应的TYPE
                    //将除去NALU头剩下的NALU数据写入sendbuf的第14个字节之后。前14个字节包括：12字节的RTP Header，FU indicator，FU header
                    System.arraycopy(r, 1, sendbuf, 14, packageSize);
                    //client.send(new DatagramPacket(sendbuf, packageSize + 14, addr, port/*9200*/));
                    //exceuteH264ToRtpLinsener(sendbuf, packageSize + 14);
                    t++;
                } else if (t == k) {//分片的最后一片
                    sendbuf[1] = (byte) (sendbuf[1] | 0x80);

                    sendbuf[12] = (byte) (sendbuf[12] | ((byte) (r[0] & 0x80)) << 7);
                    sendbuf[12] = (byte) (sendbuf[12] | ((byte) ((r[0] & 0x60) >> 5)) << 5);
                    sendbuf[12] = (byte) (sendbuf[12] | (byte) (28));

                    sendbuf[13] = (byte) (sendbuf[13] & 0xDF); //R=0，保留位必须设为0
                    sendbuf[13] = (byte) (sendbuf[13] & 0x7F); //S=0，不是第一个包
                    sendbuf[13] = (byte) (sendbuf[13] | 0x40); //E=1，是最后一个包
                    sendbuf[13] = (byte) (sendbuf[13] | ((byte) (r[0] & 0x1f)));//NALU头对应的type

                    if (0 != l) {//如果不能整除，则有剩下的包，执行此代码。如果包大小恰好是1400的倍数，不执行此代码。
                        System.arraycopy(r, t * packageSize + 1, sendbuf, 14, l - 1);//l-1，不包含NALU头
                        bytes = l - 1 + 14; //bytes=l-1+14;
                        //client.send(new DatagramPacket(sendbuf, bytes, addr, port/*9200*/));
                        //send(sendbuf,bytes);
                        // exceuteH264ToRtpLinsener(sendbuf, bytes);
                    }//pl
                    t++;
                } else if (t < k && 0 != t) {//既不是第一片，又不是最后一片的包
                    sendbuf[1] = (byte) (sendbuf[1] & 0x7F); //M=0，其值为：01100000，不是最后一片，M位（第一位）设为0.
                    sendbuf[12] = (byte) (sendbuf[12] | ((byte) (r[0] & 0x80)) << 7);
                    sendbuf[12] = (byte) (sendbuf[12] | ((byte) ((r[0] & 0x60) >> 5)) << 5);
                    sendbuf[12] = (byte) (sendbuf[12] | (byte) (28));

                    sendbuf[13] = (byte) (sendbuf[13] & 0xDF); //R=0，保留位必须设为0
                    sendbuf[13] = (byte) (sendbuf[13] & 0x7F); //S=0，不是第一个包
                    sendbuf[13] = (byte) (sendbuf[13] & 0xBF); //E=0，不是最后一个包
                    sendbuf[13] = (byte) (sendbuf[13] | ((byte) (r[0] & 0x1f)));//NALU头对应的type
                    System.arraycopy(r, t * packageSize + 1, sendbuf, 14, packageSize);//不包含NALU头
                    //client.send(new DatagramPacket(sendbuf, packageSize + 14, addr, port/*9200*/));
                    //send(sendbuf,1414);
                    // exceuteH264ToRtpLinsener(sendbuf, packageSize + 14);

                    t++;
                }
            }
        }
    }


    private byte[] h264Buffer;
    private int h264Len = 0;
    private int h264Pos = 0;
    private static final byte[] start_code = {0, 0, 0, 1};     // h264 start code

    //传入视频的分辨率
    public UdpSend(int width, int height) {
        h264Buffer = new byte[getYuvBuffer(width, height)];
    }


    /**
     * RTP解包H264
     *
     * @param rtpData
     * @return
     */
    public byte[] rtp2h264(byte[] rtpData, int rtpLen) {

        int fu_header_len = 12;         // FU-Header长度为12字节
        int extension = (rtpData[0] & (1 << 4));  // X: 扩展为是否为1
        if (extension > 0) {
            // 计算扩展头的长度
            int extLen = (rtpData[12] << 24) + (rtpData[13] << 16) + (rtpData[14] << 8) + rtpData[15];
            fu_header_len += (extLen + 1) * 4;
        }
        // 解析FU-indicator
        byte indicatorType = (byte) (CommonUtils.byteToInt(rtpData[fu_header_len]) & 0x1f); // 取出low 5 bit 则为FU-indicator type

        byte nri = (byte) ((CommonUtils.byteToInt(rtpData[fu_header_len]) >> 5) & 0x03);    // 取出h2bit and h3bit
        byte f = (byte) (CommonUtils.byteToInt(rtpData[fu_header_len]) >> 7);               // 取出h1bit
        byte h264_nal_header;
        byte fu_header;
        if (indicatorType == 28) {  // FU-A
            fu_header = rtpData[fu_header_len + 1];
            byte s = (byte) (rtpData[fu_header_len + 1] & 0x80);
            byte e = (byte) (rtpData[fu_header_len + 1] & 0x40);

            if (e == 64) {   // end of fu-a
                //ZOLogUtil.d("RtpParser", "end of fu-a.....;;;");
                byte[] temp = new byte[rtpLen - (fu_header_len + 2)];
                System.arraycopy(rtpData, fu_header_len + 2, temp, 0, temp.length);
                writeData2Buffer(temp, temp.length);
                if (h264Pos >= 0) {
                    h264Pos = -1;
                    if (h264Len > 0) {
                        byte[] h264Data = new byte[h264Len];
                        System.arraycopy(h264Buffer, 0, h264Data, 0, h264Len);
                        h264Len = 0;
                        return h264Data;
                    }
                }
            } else if (s == -128) { // start of fu-a
                h264Pos = 0;     // 指针归0
                writeData2Buffer(start_code, 4);        // 写入H264起始码
                h264_nal_header = (byte) ((fu_header & 0x1f) | (nri << 5) | (f << 7));
                writeData2Buffer(new byte[]{h264_nal_header}, 1);
                byte[] temp = new byte[rtpLen - (fu_header_len + 2)];
                System.arraycopy(rtpData, fu_header_len + 2, temp, 0, temp.length); // 负载数据
                writeData2Buffer(temp, temp.length);
            } else {
                byte[] temp = new byte[rtpLen - (fu_header_len + 2)];
                System.arraycopy(rtpData, fu_header_len + 2, temp, 0, temp.length);
                writeData2Buffer(temp, temp.length);
            }
        } else { // nalu
            h264Pos = 0;
            writeData2Buffer(start_code, 4);
            byte[] temp = new byte[rtpLen - fu_header_len];
            System.arraycopy(rtpData, fu_header_len, temp, 0, temp.length);
            writeData2Buffer(temp, temp.length);
            if (h264Pos >= 0) {
                h264Pos = -1;
                if (h264Len > 0) {
                    byte[] h264Data = new byte[h264Len];
                    System.arraycopy(h264Buffer, 0, h264Data, 0, h264Len);
                    h264Len = 0;
                    return h264Data;
                }
            }
        }
        return null;
    }

    private void writeData2Buffer(byte[] data, int len) {
        if (h264Pos >= 0) {
            System.arraycopy(data, 0, h264Buffer, h264Pos, len);
            h264Pos += len;
            h264Len += len;
        }
    }

    //计算h264大小
    public int getYuvBuffer(int width, int height) {
        // stride = ALIGN(width, 16)
        int stride = (int) Math.ceil(width / 16.0) * 16;
        // y_size = stride * height
        int y_size = stride * height;
        // c_stride = ALIGN(stride/2, 16)
        int c_stride = (int) Math.ceil(width / 32.0) * 16;
        // c_size = c_stride * height/2
        int c_size = c_stride * height / 2;
        // size = y_size + c_size * 2
        return y_size + c_size * 2;
    }

}
