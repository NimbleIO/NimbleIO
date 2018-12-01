/*
 * Copyright 2015-2017 GenerallyCloud.com
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.generallycloud.baseio.component;

import static com.generallycloud.baseio.Develop.printException;
import static com.generallycloud.baseio.common.Util.unknownStackTrace;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketOption;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;

import com.generallycloud.baseio.buffer.ByteBuf;
import com.generallycloud.baseio.buffer.ByteBufAllocator;
import com.generallycloud.baseio.buffer.EmptyByteBuf;
import com.generallycloud.baseio.collection.Attributes;
import com.generallycloud.baseio.collection.AttributesImpl;
import com.generallycloud.baseio.common.Assert;
import com.generallycloud.baseio.common.Util;
import com.generallycloud.baseio.component.ChannelContext.HeartBeatLogger;
import com.generallycloud.baseio.concurrent.ExecutorEventLoop;
import com.generallycloud.baseio.log.Logger;
import com.generallycloud.baseio.log.LoggerFactory;
import com.generallycloud.baseio.protocol.Frame;
import com.generallycloud.baseio.protocol.ProtocolCodec;

public final class NioSocketChannel extends AttributesImpl
        implements Runnable, Attributes, Closeable {

    private static final ClosedChannelException CLOSED_WHEN_FLUSH     = CLOSED_WHEN_FLUSH();
    private static final InetSocketAddress      ERROR_SOCKET_ADDRESS  = new InetSocketAddress(0);
    private static final Logger                 logger                = newLogger();
    private static final SSLException           NOT_TLS               = NOT_TLS();
    private static final int                    SSL_PACKET_LIMIT      = 1024 * 64;
    private static final SSLException           SSL_PACKET_OVER_LIMIT = SSL_PACKET_OVER_LIMIT();
    private static final SSLException           SSL_UNWRAP_OVER_LIMIT = SSL_UNWRAP_OVER_LIMIT();

    private final SocketChannel                 channel;
    private final Integer                       channelId;
    private ProtocolCodec                       codec;
    private final ChannelContext                context;
    private final long                          creationTime          = System.currentTimeMillis();
    private final ByteBuf[]                     currentWriteBufs;
    private int                                 currentWriteBufsLen;
    private final String                        desc;
    private final boolean                       enableSsl;
    private final NioEventLoop                  eventLoop;
    private final ExecutorEventLoop             executorEventLoop;
    private boolean                             inEventBuffer;
    private IoEventHandle                       ioEventHandle;
    private long                                lastAccess;
    private final String                        localAddr;
    private final int                           localPort;
    private final int                           maxWriteBacklog;
    private volatile boolean                    opened                = true;
    private ByteBuf                             plainRemainBuf;
    private final String                        remoteAddr;
    private final String                        remoteAddrPort;
    private final int                           remotePort;
    private final SelectionKey                  selKey;
    private final SSLEngine                     sslEngine;
    private boolean                             sslHandshakeFinished;
    private ByteBuf                             sslRemainBuf;
    private byte                                sslWrapExt;
    private final Queue<ByteBuf>                writeBufs;

    NioSocketChannel(NioEventLoop el, SelectionKey sk, ChannelContext ctx, int chId) {
        NioEventLoopGroup g = el.getGroup();
        this.context = ctx;
        this.selKey = sk;
        this.eventLoop = el;
        this.channelId = chId;
        this.enableSsl = ctx.isEnableSsl();
        this.codec = ctx.getProtocolCodec();
        this.maxWriteBacklog = ctx.getMaxWriteBacklog();
        this.currentWriteBufs = new ByteBuf[g.getWriteBuffers()];
        this.executorEventLoop = ctx.getExecutorEventLoopGroup().getNext();
        this.channel = (SocketChannel) sk.channel();
        this.lastAccess = creationTime + g.getIdleTime();
        this.writeBufs = new LinkedBlockingQueue<>();
        //请勿使用remote.getRemoteHost(),可能出现阻塞
        InetSocketAddress remote = getRemoteSocketAddress0();
        InetSocketAddress local = getLocalSocketAddress0();
        String idhex = Integer.toHexString(chId);
        this.remoteAddr = remote.getAddress().getHostAddress();
        this.remotePort = remote.getPort();
        this.remoteAddrPort = remoteAddr + ":" + remotePort;
        this.localAddr = local.getAddress().getHostAddress();
        this.localPort = local.getPort();
        this.desc = "[id(0x" + idhex + ")R/" + remoteAddrPort + "; L:" + getLocalPort() + "]";
        if (ctx.isEnableSsl()) {
            this.sslEngine = ctx.getSslContext().newEngine(remoteAddr, remotePort);
        } else {
            this.sslHandshakeFinished = true;
            this.sslEngine = null;
        }
    }

    private void accept(ByteBuf src) throws IOException {
        final ProtocolCodec codec = this.codec;
        final IoEventHandle eventHandle = this.ioEventHandle;
        final HeartBeatLogger heartBeatLogger = context.getHeartBeatLogger();
        final boolean enableWorkEventLoop = context.isEnableWorkEventLoop();
        for (;;) {
            Frame frame = codec.decode(this, src);
            if (frame == null) {
                plainRemainBuf = sliceRemain(src);
                break;
            }
            if (frame.isTyped()) {
                if (frame.isPing()) {
                    heartBeatLogger.logPing(this);
                    Frame f = codec.pong(this, frame);
                    if (f != null) {
                        flush(f);
                    }
                } else if (frame.isPong()) {
                    heartBeatLogger.logPong(this);
                }
            } else {
                if (enableWorkEventLoop) {
                    accept(eventHandle, frame);
                } else {
                    try {
                        eventHandle.accept(this, frame);
                    } catch (Exception e) {
                        eventHandle.exceptionCaught(this, frame, e);
                    }
                }
            }
            if (!src.hasRemaining()) {
                break;
            }
        }
    }

    private void accept(final IoEventHandle eventHandle, final Frame frame) {
        getExecutorEventLoop().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    eventHandle.accept(NioSocketChannel.this, frame);
                } catch (Exception e) {
                    eventHandle.exceptionCaught(NioSocketChannel.this, frame, e);
                }
            }
        });
    }

    public ByteBufAllocator alloc() {
        return eventLoop.alloc();
    }

    @Override
    public void close() {
        if (inEventLoop()) {
            safeClose();
        } else {
            if (isClosed()) {
                return;
            }
            execute(new CloseEvent(this));
        }
    }

    private void closeSsl() {
        if (enableSsl) {
            if (!channel.isOpen()) {
                return;
            }
            sslEngine.closeOutbound();
            if (context.getSslContext().isClient()) {
                try {
                    writeBufs.offer(wrap(EmptyByteBuf.get()));
                    write(selKey.interestOps());
                } catch (Exception e) {}
            }
            try {
                sslEngine.closeInbound();
            } catch (Exception e) {}
        }
    }

    public ByteBuf encode(Frame frame) throws IOException {
        return codec.encode(this, frame);
    }

    private void exceptionCaught(Frame frame, Exception ex) {
        try {
            getIoEventHandle().exceptionCaught(this, frame, ex);
        } catch (Throwable e) {
            printException(logger, e);
            printException(logger, ex);
        }
    }

    private void execute(Runnable event) {
        eventLoop.execute(event);
    }
    
    protected boolean isSslHandshakeFinished() {
        return sslHandshakeFinished;
    }

    private void finishHandshake() {
        this.sslHandshakeFinished = true;
        this.fireOpend();
        this.context.channelEstablish(this, null);
    }

    private void fireClosed() {
        final NioSocketChannel ch = this;
        eventLoop.removeChannel(ch.channelId);
        for (ChannelEventListener l : context.getChannelEventListeners()) {
            try {
                l.channelClosed(ch);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    protected void fireOpend() {
        final NioSocketChannel ch = this;
        for (ChannelEventListener l : context.getChannelEventListeners()) {
            try {
                l.channelOpened(ch);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
                Util.close(ch);
                return;
            }
        }
        if (ioEventHandle == null) {
            ioEventHandle = context.getIoEventHandle();
        }
    }

    public void flush(ByteBuf buf) {
        Assert.notNull(buf, "null buf");
        if (enableSsl) {
            ByteBuf old = buf;
            try {
                buf = wrap(old);
            } catch (Exception e) {
                printException(logger, e);
            } finally {
                old.release();
            }
        }
        if (inEventLoop()) {
            if (isClosed()) {
                buf.release();
                return;
            }
            writeBufs.offer(buf);
            if (!inEventBuffer) {
                inEventBuffer = true;
                eventLoop.flush(this);
            }
        } else {
            Queue<ByteBuf> writeBufs = this.writeBufs;
            if (isClosed()) {
                buf.release();
                return;
            }
            writeBufs.offer(buf);
            if (isClosed()) {
                Util.release(buf);
                return;
            }
            //FIXME 确认这里这么判断是否有问题
            if (writeBufs.size() != 1) {
                return;
            }
            eventLoop.flushAndWakeup(this);
        }
    }

    public void flush(Frame frame) {
        Assert.notNull(frame, "null frame");
        if (isClosed()) {
            exceptionCaught(frame, CLOSED_WHEN_FLUSH);
            return;
        }
        ByteBuf buf = null;
        try {
            buf = codec.encode(this, frame);
        } catch (Exception e) {
            Util.release(buf);
            exceptionCaught(frame, e);
            return;
        }
        flush(buf);
    }

    //    public void flush(List<ByteBuf> bufs) {
    //        if (bufs != null && !bufs.isEmpty()) {
    //            if (inEventLoop()) {
    //                if (isClosed()) {
    //                    Util.release(bufs);
    //                    return;
    //                }
    //                final int bufsSize = bufs.size();
    //                final Queue<ByteBuf> writeBufs = this.writeBufs;
    //                if (writeBufs.isEmpty()) {
    //                    final ByteBuf[] currentWriteBufs = this.currentWriteBufs;
    //                    final int maxLen = currentWriteBufs.length;
    //                    int currentWriteBufsLen = this.currentWriteBufsLen;
    //                    if (currentWriteBufsLen == 0) {
    //                        if (bufsSize > maxLen) {
    //                            for (int i = 0; i < maxLen; i++) {
    //                                currentWriteBufs[i] = bufs.get(i);
    //                            }
    //                            for (int i = maxLen; i < bufsSize; i++) {
    //                                writeBufs.offer(bufs.get(i));
    //                            }
    //                            this.currentWriteBufsLen = maxLen;
    //                        } else {
    //                            for (int i = 0; i < bufsSize; i++) {
    //                                currentWriteBufs[i] = bufs.get(i);
    //                            }
    //                            this.currentWriteBufsLen = bufsSize;
    //                        }
    //                    } else {
    //                        final int currentRemain = maxLen - currentWriteBufsLen;
    //                        if (bufsSize > currentRemain) {
    //                            for (int i = 0; i < currentRemain; i++) {
    //                                currentWriteBufs[i + currentWriteBufsLen] = bufs.get(i);
    //                            }
    //                            for (int i = currentRemain; i < bufsSize; i++) {
    //                                writeBufs.offer(bufs.get(i));
    //                            }
    //                            this.currentWriteBufsLen = maxLen;
    //                        } else {
    //                            for (int i = 0; i < bufsSize; i++) {
    //                                currentWriteBufs[i + currentWriteBufsLen] = bufs.get(i);
    //                            }
    //                            this.currentWriteBufsLen += bufsSize;
    //                        }
    //                    }
    //                } else {
    //                    for (ByteBuf buf : bufs) {
    //                        writeBufs.offer(buf);
    //                    }
    //                }
    //                if (!inEventBuffer) {
    //                    inEventBuffer = true;
    //                    eventLoop.flush(this);
    //                }
    //            } else {
    //                Queue<ByteBuf> writeBufs = this.writeBufs;
    //                if (isClosed()) {
    //                    Util.release(bufs);
    //                    return;
    //                }
    //                for (ByteBuf buf : bufs) {
    //                    writeBufs.offer(buf);
    //                }
    //                if (isClosed()) {
    //                    releaseWriteBufQueue();
    //                    return;
    //                }
    //                //FIXME 确认这里这么判断是否有问题
    //                if (writeBufs.size() != bufs.size()) {
    //                    return;
    //                }
    //                eventLoop.flushAndWakeup(this);
    //            }
    //        }
    //    }

    public Integer getChannelId() {
        return channelId;
    }

    public Charset getCharset() {
        return context.getCharset();
    }

    public ProtocolCodec getCodec() {
        return codec;
    }

    public String getCodecId() {
        return codec.getProtocolId();
    }

    public ChannelContext getContext() {
        return context;
    }

    public long getCreationTime() {
        return creationTime;
    }

    public NioEventLoop getEventLoop() {
        return eventLoop;
    }

    public ExecutorEventLoop getExecutorEventLoop() {
        return executorEventLoop;
    }

    public IoEventHandle getIoEventHandle() {
        return ioEventHandle;
    }

    public long getLastAccessTime() {
        return lastAccess;
    }

    public String getLocalAddr() {
        return localAddr;
    }

    public int getLocalPort() {
        return localPort;
    }

    private InetSocketAddress getLocalSocketAddress0() {
        try {
            return (InetSocketAddress) channel.getLocalAddress();
        } catch (IOException e) {
            return ERROR_SOCKET_ADDRESS;
        }
    }

    public <T> T getOption(SocketOption<T> name) throws IOException {
        return channel.getOption(name);
    }

    public String getRemoteAddr() {
        return remoteAddr;
    }

    public String getRemoteAddrPort() {
        return remoteAddrPort;
    }

    public int getRemotePort() {
        return remotePort;
    }

    private InetSocketAddress getRemoteSocketAddress0() {
        try {
            return (InetSocketAddress) channel.getRemoteAddress();
        } catch (Exception e) {}
        return ERROR_SOCKET_ADDRESS;
    }

    public SSLEngine getSSLEngine() {
        return sslEngine;
    }

    public int getWriteBacklog() {
        //忽略current write[]
        return writeBufs.size();
    }

    //FIXME not correct ,fix this
    private int guessWrapOut(int src, int ext) {
        if (SslContext.OPENSSL_AVAILABLE) {
            return ((src + SslContext.SSL_PACKET_BUFFER_SIZE - 1)
                    / SslContext.SSL_PACKET_BUFFER_SIZE + 1) * ext + src;
        } else {
            return ((src + SslContext.SSL_PACKET_BUFFER_SIZE - 1)
                    / SslContext.SSL_PACKET_BUFFER_SIZE)
                    * (ext + SslContext.SSL_PACKET_BUFFER_SIZE);
        }
    }

    @Override
    public int hashCode() {
        return remoteAddrPort.hashCode();
    }

    public boolean inEventLoop() {
        return eventLoop.inEventLoop();
    }

    private void interestRead(SelectionKey key, int interestOps) {
        if (SelectionKey.OP_READ != interestOps) {
            key.interestOps(SelectionKey.OP_READ);
        }
    }

    private void interestWrite(SelectionKey key, int interestOps) {
        if ((SelectionKey.OP_READ | SelectionKey.OP_WRITE) != interestOps) {
            key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        }
    }

    public boolean isBlocking() {
        return channel.isBlocking();
    }

    public boolean isClosed() {
        return !opened;
    }

    public boolean isCodec(String codecId) {
        return codec.getProtocolId().equals(codecId);
    }

    public boolean isEnableSsl() {
        return enableSsl;
    }

    /**
     <pre>
               record type (1 byte)
            /
           /    version (1 byte major, 1 byte minor)
          /    /
         /    /         length (2 bytes)
        /    /         /
     +----+----+----+----+----+
     |    |    |    |    |    |
     |    |    |    |    |    | TLS Record header
     +----+----+----+----+----+
    
    
     Record Type Values       dec      hex
     -------------------------------------
     CHANGE_CIPHER_SPEC        20     0x14
     ALERT                     21     0x15
     HANDSHAKE                 22     0x16
     APPLICATION_DATA          23     0x17
    
    
     Version Values            dec     hex
     -------------------------------------
     SSL 3.0                   3,0  0x0300
     TLS 1.0                   3,1  0x0301
     TLS 1.1                   3,2  0x0302
     TLS 1.2                   3,3  0x0303
     
     ref:http://blog.fourthbit.com/2014/12/23/traffic-analysis-of-an-ssl-slash-tls-session/
     </pre>
    */
    private boolean isEnoughSslUnwrap(ByteBuf src) throws SSLException {
        if (src.remaining() < 5) {
            return false;
        }
        int pos = src.position();
        // TLS - Check ContentType
        int type = src.getUnsignedByte(pos);
        if (type < 20 || type > 23) {
            throw NOT_TLS;
        }
        // TLS - Check ProtocolVersion
        int majorVersion = src.getUnsignedByte(pos + 1);
        int minorVersion = src.getUnsignedByte(pos + 2);
        int packetLength = src.getUnsignedShort(pos + 3);
        if (majorVersion != 3 || minorVersion < 1) {
            // NOT TLS (i.e. SSLv2,3 or bad data)
            throw NOT_TLS;
        }
        int len = packetLength + 5;
        if (src.remaining() < len) {
            return false;
        }
        if (len > SSL_PACKET_LIMIT) {
            throw SSL_PACKET_OVER_LIMIT;
        }
        src.markL();
        src.limit(pos + len);
        return true;
    }

    public boolean isOpened() {
        return opened;
    }

    protected void read(ByteBuf src) throws IOException {
        lastAccess = System.currentTimeMillis();
        src.clear();
        if (enableSsl) {
            readSslRemainingBuf(src);
            int length = channel.read(src.nioBuffer());
            if (length < 1) {
                if (length == -1) {
                    Util.close(this);
                    return;
                }
                if (src.position() > 0) {
                    src.flip();
                    sslRemainBuf = sliceRemain(src);
                }
                return;
            }
            src.reverse();
            src.flip();
            for (;;) {
                if (isEnoughSslUnwrap(src)) {
                    ByteBuf res = unwrap(src);
                    if (res != null) {
                        accept(res);
                    }
                    src.resetL();
                    if (!src.hasRemaining()) {
                        return;
                    }
                } else {
                    if (src.hasRemaining()) {
                        sslRemainBuf = sliceRemain(src);
                    }
                    return;
                }
            }
        } else {
            readPlainRemainingBuf(src);
            int length = channel.read(src.nioBuffer());
            if (length < 1) {
                if (length == -1) {
                    Util.close(this);
                    return;
                }
                if (src.position() > 0) {
                    src.flip();
                    plainRemainBuf = sliceRemain(src);
                }
                return;
            }
            src.reverse();
            src.flip();
            accept(src);
        }
    }

    private void readPlainRemainingBuf(ByteBuf dst) {
        ByteBuf remainingBuf = this.plainRemainBuf;
        if (remainingBuf == null) {
            return;
        }
        dst.read(remainingBuf);
        remainingBuf.release();
        this.plainRemainBuf = null;
    }

    private void readSslRemainingBuf(ByteBuf dst) {
        ByteBuf remainingBuf = this.sslRemainBuf;
        if (remainingBuf == null) {
            return;
        }
        dst.read(remainingBuf);
        remainingBuf.release();
        this.sslRemainBuf = null;
    }

    public void release(Frame frame) {
        codec.release(eventLoop, frame);
    }

    private void releaseWriteBufArray() {
        final ByteBuf[] cwbs = this.currentWriteBufs;
        final int maxLen = cwbs.length;
        // 这里有可能是因为异常关闭，currentWriteFrameLen不准确
        // 对所有不为空的frame release
        for (int i = 0; i < maxLen; i++) {
            ByteBuf buf = cwbs[i];
            if (buf == null) {
                break;
            }
            buf.release();
            cwbs[i] = null;
        }
    }

    private void releaseWriteBufQueue() {
        Queue<ByteBuf> wfs = this.writeBufs;
        if (!wfs.isEmpty()) {
            ByteBuf buf = wfs.poll();
            for (; buf != null;) {
                Util.release(buf);
                buf = wfs.poll();
            }
        }
    }

    @Override
    public void run() {
        if (isOpened()) {
            inEventBuffer = false;
            try {
                write(selKey.interestOps());
            } catch (Exception e) {
                printException(logger, e);
                close();
            }
        }
    }

    private void runDelegatedTasks(SSLEngine engine) {
        for (;;) {
            Runnable task = engine.getDelegatedTask();
            if (task == null) {
                break;
            }
            task.run();
        }
    }

    private void safeClose() {
        if (isOpened()) {
            opened = false;
            closeSsl();
            releaseWriteBufQueue();
            releaseWriteBufArray();
            Util.release(sslRemainBuf);
            Util.release(plainRemainBuf);
            Util.close(channel);
            selKey.attach(null);
            selKey.cancel();
            fireClosed();
            stopContext();
            clearAttributes();
        }
    }

    public void setCodec(ProtocolCodec codec) {
        this.eventLoop.assertInEventLoop();
        this.codec = codec;
    }

    public void setIoEventHandle(IoEventHandle ioEventHandle) {
        this.ioEventHandle = ioEventHandle;
    }

    public <T> void setOption(SocketOption<T> name, T value) throws IOException {
        channel.setOption(name, value);
    }

    private ByteBuf sliceRemain(ByteBuf src) {
        int remain = src.remaining();
        ByteBuf remaining = alloc().allocate(remain);
        remaining.read(src);
        return remaining.flip();
    }

    private void stopContext() {
        if (context instanceof ChannelConnector) {
            Util.close(((ChannelConnector) context));
        }
    }

    //FIXME 部分buf不需要swap
    private ByteBuf swap(ByteBufAllocator allocator, ByteBuf buf) {
        ByteBuf out = allocator.allocate(buf.limit());
        out.read(buf);
        return out.flip();
    }

    private void synchByteBuf(SSLEngineResult result, ByteBuf src, ByteBuf dst) {
        //FIXME 同步。。。。。
        src.reverse();
        dst.reverse();
        //      int bytesConsumed = result.bytesConsumed();
        //      int bytesProduced = result.bytesProduced();
        //      
        //      if (bytesConsumed > 0) {
        //          src.skipBytes(bytesConsumed);
        //      }
        //
        //      if (bytesProduced > 0) {
        //          dst.skipBytes(bytesProduced);
        //      }
    }

    @Override
    public String toString() {
        return desc;
    }

    private ByteBuf unwrap(ByteBuf src) throws IOException {
        SSLEngine sslEngine = getSSLEngine();
        ByteBuf dst = FastThreadLocal.get().getSslUnwrapBuf();
        if (sslHandshakeFinished) {
            dst.clear();
            readPlainRemainingBuf(dst);
            SSLEngineResult result = sslEngine.unwrap(src.nioBuffer(), dst.nioBuffer());
            if (result.getStatus() == Status.BUFFER_OVERFLOW) {
                //why throw an exception here instead of handle it?
                //the getSslUnwrapBuf will return an thread local buffer for unwrap,
                //the buffer's size defined by Constants.SSL_UNWRAP_BUFFER_SIZE_KEY in System property
                //or default value 256KB(1024 * 256), although the buffer will not occupy so much memory because
                //one EventLoop only have one buffer,but before do unwrap, every channel maybe cached a large
                //buffer under SSL_UNWRAP_BUFFER_SIZE,I do not think it is a good way to cached much memory in
                //channel, it is not friendly for load much channels in one system, if you get exception here,
                //you may need find a way to limit you frame size,or cache your incomplete frame's data to
                //file system or others way.
                throw SSL_UNWRAP_OVER_LIMIT;
            }
            synchByteBuf(result, src, dst);
            return dst.flip();
        } else {
            for (;;) {
                dst.clear();
                SSLEngineResult result = sslEngine.unwrap(src.nioBuffer(), dst.nioBuffer());
                HandshakeStatus handshakeStatus = result.getHandshakeStatus();
                synchByteBuf(result, src, dst);
                if (handshakeStatus == HandshakeStatus.NEED_WRAP) {
                    flush(EmptyByteBuf.get());
                    return null;
                } else if (handshakeStatus == HandshakeStatus.NEED_TASK) {
                    runDelegatedTasks(sslEngine);
                    continue;
                } else if (handshakeStatus == HandshakeStatus.FINISHED) {
                    finishHandshake();
                    return null;
                } else if (handshakeStatus == HandshakeStatus.NEED_UNWRAP) {
                    if (src.hasRemaining()) {
                        continue;
                    }
                    return null;
                }
            }
        }
    }

    private ByteBuf wrap(ByteBuf src) throws IOException {
        SSLEngine engine = getSSLEngine();
        ByteBufAllocator alloc = alloc();
        ByteBuf out = null;
        try {
            if (sslHandshakeFinished) {
                byte sslWrapExt = this.sslWrapExt;
                if (sslWrapExt == 0) {
                    out = alloc.allocate(guessWrapOut(src.limit(), 0xff + 1));
                } else {
                    out = alloc.allocate(guessWrapOut(src.limit(), sslWrapExt & 0xff));
                }
                final int SSL_PACKET_BUFFER_SIZE = SslContext.SSL_PACKET_BUFFER_SIZE;
                for (;;) {
                    SSLEngineResult result = engine.wrap(src.nioBuffer(), out.nioBuffer());
                    Status status = result.getStatus();
                    synchByteBuf(result, src, out);
                    if (status == Status.CLOSED) {
                        return out.flip();
                    } else if (status == Status.BUFFER_OVERFLOW) {
                        ByteBuf old = out;
                        try {
                            int len = out.capacity() + SSL_PACKET_BUFFER_SIZE;
                            out = alloc.allocate(len);
                            out.read(old.flip());
                        } finally {
                            old.release();
                        }
                        continue;
                    } else {
                        if (src.hasRemaining()) {
                            continue;
                        }
                        if (sslWrapExt == 0) {
                            int srcLen = src.limit();
                            int outLen = out.position();
                            int y = ((srcLen + 1) / SSL_PACKET_BUFFER_SIZE) + 1;
                            int u = ((outLen - srcLen) / y) * 2;
                            this.sslWrapExt = (byte) u;
                        }
                        return out.flip();
                    }
                }
            } else {
                ByteBuf dst = FastThreadLocal.get().getSslWrapBuf();
                for (;;) {
                    dst.clear();
                    SSLEngineResult result = engine.wrap(src.nioBuffer(), dst.nioBuffer());
                    Status status = result.getStatus();
                    HandshakeStatus handshakeStatus = result.getHandshakeStatus();
                    synchByteBuf(result, src, dst);
                    if (status == Status.CLOSED) {
                        return swap(alloc, dst.flip());
                    }
                    if (handshakeStatus == HandshakeStatus.NEED_UNWRAP) {
                        if (out != null) {
                            out.read(dst.flip());
                            return out.flip();
                        }
                        return swap(alloc, dst.flip());
                    } else if (handshakeStatus == HandshakeStatus.NEED_WRAP) {
                        if (out == null) {
                            out = alloc.allocate(256);
                        }
                        out.read(dst.flip());
                        continue;
                    } else if (handshakeStatus == HandshakeStatus.FINISHED) {
                        finishHandshake();
                        if (out != null) {
                            out.read(dst.flip());
                            return out.flip();
                        }
                        return swap(alloc, dst.flip());
                    } else if (handshakeStatus == HandshakeStatus.NEED_TASK) {
                        runDelegatedTasks(engine);
                        continue;
                    }
                }
            }
        } catch (Throwable e) {
            Util.release(out);
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw new IOException(e);
        }
    }

    protected boolean write(final int interestOps) throws IOException {
        final NioEventLoop eventLoop = this.eventLoop;
        final Queue<ByteBuf> writeBufs = this.writeBufs;
        final SelectionKey selectionKey = this.selKey;
        final ByteBuf[] cwBufs = this.currentWriteBufs;
        final ByteBuffer[] writeBuffers = eventLoop.getWriteBuffers();
        final int maxLen = cwBufs.length;
        for (;;) {
            int cwLen = this.currentWriteBufsLen;
            for (; cwLen < maxLen;) {
                ByteBuf buf = writeBufs.poll();
                if (buf == null) {
                    break;
                }
                cwBufs[cwLen++] = buf;
            }
            if (cwLen == 0) {
                interestRead(selectionKey, interestOps);
                return true;
            }
            for (int i = 0; i < cwLen; i++) {
                ByteBuf buf = cwBufs[i];
                writeBuffers[i] = buf.nioBuffer();
            }
            if (cwLen == 1) {
                ByteBuffer nioBuf = writeBuffers[0];
                channel.write(nioBuf);
                if (nioBuf.hasRemaining()) {
                    this.currentWriteBufsLen = 1;
                    cwBufs[0].reverse();
                    interestWrite(selectionKey, interestOps);
                    return false;
                } else {
                    ByteBuf buf = cwBufs[0];
                    cwBufs[0] = null;
                    buf.release();
                    this.currentWriteBufsLen = 0;
                    if (writeBufs.isEmpty()) {
                        interestRead(selectionKey, interestOps);
                        return true;
                    }
                    continue;
                }
            } else {
                channel.write(writeBuffers, 0, cwLen);
                for (int i = 0; i < cwLen; i++) {
                    ByteBuf buf = cwBufs[i];
                    if (writeBuffers[i].hasRemaining()) {
                        buf.reverse();
                        int remain = cwLen - i;
                        System.arraycopy(cwBufs, i, cwBufs, 0, remain);
                        fillNull(cwBufs, remain, cwLen);
                        fillNull(writeBuffers, i, cwLen);
                        this.currentWriteBufsLen = remain;
                        interestWrite(selectionKey, interestOps);
                        if (writeBufs.size() > maxWriteBacklog) {
                            close();
                        }
                        return false;
                    } else {
                        writeBuffers[i] = null;
                        buf.release();
                    }
                }
                fillNull(cwBufs, 0, cwLen);
                this.currentWriteBufsLen = 0;
                if (writeBufs.isEmpty()) {
                    interestRead(selectionKey, interestOps);
                    return true;
                }
            }
        }
    }

    class CloseEvent implements Runnable, Closeable {

        final NioSocketChannel ch;

        public CloseEvent(NioSocketChannel ch) {
            this.ch = ch;
        }

        @Override
        public void close() throws IOException {
            ch.safeClose();
        }

        @Override
        public void run() {
            ch.safeClose();
        }

    }

    private static ClosedChannelException CLOSED_WHEN_FLUSH() {
        return Util.unknownStackTrace(new ClosedChannelException(), NioSocketChannel.class,
                "flush(...)");
    }

    private static void fillNull(Object[] a, int fromIndex, int toIndex) {
        for (int i = fromIndex; i < toIndex; i++)
            a[i] = null;
    }

    private static Logger newLogger() {
        return LoggerFactory.getLogger(NioSocketChannel.class);
    }

    private static SSLException NOT_TLS() {
        return Util.unknownStackTrace(new SSLException("NOT TLS"), NioSocketChannel.class,
                "isEnoughSslUnwrap()");
    }

    private static SSLException SSL_PACKET_OVER_LIMIT() {
        return Util.unknownStackTrace(new SSLException("over limit (" + SSL_PACKET_LIMIT + ")"),
                NioSocketChannel.class, "isEnoughSslUnwrap()");
    }

    private static SSLException SSL_UNWRAP_OVER_LIMIT() {
        return unknownStackTrace(new SSLException("over limit (SSL_UNWRAP_BUFFER_SIZE)"),
                NioSocketChannel.class, "unwrap()");
    }

}
