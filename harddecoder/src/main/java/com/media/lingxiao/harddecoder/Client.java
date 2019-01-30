package com.media.lingxiao.harddecoder;

import android.os.Environment;
import android.util.Log;
import android.view.SurfaceHolder;

import com.media.lingxiao.harddecoder.decoder.H264Decoder;
import com.media.lingxiao.harddecoder.tlv.Constants;
import com.media.lingxiao.harddecoder.tlv.TLVCodecFactory;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.future.ConnectFuture;
import org.apache.mina.core.service.IoHandlerAdapter;
import org.apache.mina.core.session.IdleStatus;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.transport.socket.nio.NioSocketConnector;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteOrder;

import static com.media.lingxiao.harddecoder.tlv.Constants.MESSAGE_ID_STRAME;

public class Client{

    private NioSocketConnector streamConnection;
    private IoSession configSession, streamSession;
    private H264Decoder mDecoder;
    private boolean playing,streamConnected;
    private String mIp;
    private int mPort;
    private static final String TAG = Client.class.getSimpleName();
    public Client(String ip,int port){
        this.mIp = ip;
        this.mPort = port;
        TLVCodecFactory codecFactory = new TLVCodecFactory();
        ProtocolCodecFilter codecFilter = new ProtocolCodecFilter(codecFactory);
        streamConnection = new NioSocketConnector();
        streamConnection.getFilterChain().addLast("tlv", codecFilter);
        streamConnection.setHandler(new StreamClientHandler());
        streamConnection.getSessionConfig().setIdleTime(IdleStatus.WRITER_IDLE, 5); // 10秒未发出数据的话要发心跳
        //streamConnection.addListener(new StreamAutoReconnectHandler());
        streamConnection.setConnectTimeoutMillis(5000);

    }

    public void play(SurfaceHolder holder,int w,int h){
        mDecoder = H264Decoder.getInstance();
        mDecoder.play(holder,w,h);
        streamConnection.setDefaultRemoteAddress(new InetSocketAddress(mIp, mPort));
        ConnectFuture future = streamConnection.connect();
        future.awaitUninterruptibly();
        streamSession = future.getSession();
        Log.i(TAG,"视频端口连接:" + streamSession);
        streamConnected = streamSession != null;
        playing = true;
        createFilePath();
    }

    public void stopPlay(){
        if (mDecoder != null){
            mDecoder.stop();
        }
        if(!streamConnected){
            return;
        }
        try {
            streamSession.closeNow();
            streamConnected = false;
        } catch(Exception ex) {
            ex.printStackTrace();
        }
        try {
            outputStream.flush();
            outputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }
    private BufferedOutputStream outputStream;
    private void createFilePath(){
        String path = Environment.getExternalStorageDirectory().getAbsolutePath() + "/h264_from_mina.h264";
        File file = new File(path);
        if(file.exists()){
            file.delete();
        }
        try {
            outputStream = new BufferedOutputStream(new FileOutputStream(file));
        } catch (Exception e){
            e.printStackTrace();
        }
    }


    private class StreamClientHandler extends IoHandlerAdapter{
        @Override
        public void messageReceived(IoSession session, Object message) throws Exception {
            //super.messageReceived(session, message);
            IoBuffer buffer = (IoBuffer) message;
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            int type = buffer.getInt();
            int messageID = (type & 0x3FF);
            int len = buffer.getInt();
            Log.i(TAG,"messageID："+messageID+"  type: "+type);
            switch (messageID) {
                case MESSAGE_ID_STRAME:
                    if(buffer.remaining() < 4){
                        return;
                    }

                    int type1 = buffer.getInt();
                    if(type1 != 2){
                        return;
                    }
                    if(buffer.remaining() < 24){
                        return;
                    }
                    int width = buffer.getInt();
                    int height = buffer.getInt();
                    long seq_no0 = buffer.getUnsignedInt();
                    long seq_no1 = buffer.getUnsignedInt();
                    Log.i(TAG,
                            "width: "+width+" " +
                            " height: "+height+
                            "  seq_no0: "+seq_no0+
                            " seq_no1: "+seq_no1);
                    int bufferSize = buffer.getInt(); //视频帧大小
                    if(buffer.remaining() < bufferSize) {
                        return;
                    }
                    // 做个最大判定，不能太大了！
                    if(bufferSize > 1024 * 1024) {
                        return;
                    }
                    byte[] h264Segment = new byte[bufferSize];
                    buffer.get(h264Segment);
                    if (mDecoder == null){
                        return;
                    }
                    Log.i(TAG,"回调：" + h264Segment.length);
                    outputStream.write(h264Segment);
                    mDecoder.handleH264(h264Segment);
                    break;
            }
        }

        @Override
        public void sessionIdle(IoSession session, IdleStatus status) throws Exception {
            if(status == IdleStatus.WRITER_IDLE) {
                if(System.currentTimeMillis() - session.getLastWriteTime() >= 9000)
                    Constants.sendHeartBeat(session);
            } else if(status == IdleStatus.READER_IDLE) {
                long usecBetween = System.currentTimeMillis() - session.getLastReadTime();
                int secBetween = (int) (usecBetween / 1000);
                if(secBetween > 14) {
                    // 设备很长时间没发过来数据了
                    session.closeNow();
                }
            }
        }

        @Override
        public void exceptionCaught(IoSession session, Throwable cause) throws Exception {
            super.exceptionCaught(session, cause);
        }
    }

}
