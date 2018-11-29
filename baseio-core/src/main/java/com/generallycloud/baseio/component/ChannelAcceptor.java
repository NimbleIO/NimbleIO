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

import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;

import com.generallycloud.baseio.LifeCycleUtil;
import com.generallycloud.baseio.TimeoutException;
import com.generallycloud.baseio.buffer.ByteBuf;
import com.generallycloud.baseio.common.CloseUtil;
import com.generallycloud.baseio.concurrent.Waiter;
import com.generallycloud.baseio.log.Logger;
import com.generallycloud.baseio.log.LoggerFactory;
import com.generallycloud.baseio.protocol.Frame;

/**
 * @author wangkai
 *
 */
public final class ChannelAcceptor extends ChannelContext {

    private Logger              logger = LoggerFactory.getLogger(getClass());
    private ServerSocketChannel selectableChannel;
    private NioEventLoopGroup   bindGroup;

    public ChannelAcceptor(NioEventLoopGroup group) {
        this(group, "0.0.0.0", 0);
    }

    public ChannelAcceptor(NioEventLoopGroup group, int port) {
        this(group, "0.0.0.0", port);
    }

    public ChannelAcceptor(NioEventLoopGroup group, String host, int port) {
        super(group, host, port);
    }

    public ChannelAcceptor(String host, int port) {
        this(new NioEventLoopGroup(), host, port);
    }

    public ChannelAcceptor(int port) {
        this("0.0.0.0", port);
    }

    public void bind() throws IOException {
        bind(50);
    }

    public synchronized void bind(int backlog) throws IOException {
        if (isActive()) {
            return;
        }
        String name = "bind-" + getHost() + ":" + getPort();
        this.bindGroup = new NioEventLoopGroup(name);
        this.bindGroup.setEnableMemoryPool(false);
        this.bindGroup.setEnableMemoryPoolDirect(false);
        this.bindGroup.setChannelReadBuffer(0);
        this.bindGroup.setWriteBuffers(0);
        this.getProcessorGroup().setContext(this);
        LifeCycleUtil.start(this);
        LifeCycleUtil.start(bindGroup);
        final NioEventLoopGroup bindGroup = this.bindGroup;
        final Waiter<Object> bindWaiter = new Waiter<>();
        final ChannelAcceptor acceptor = this;
        this.selectableChannel = ServerSocketChannel.open();
        this.selectableChannel.configureBlocking(false);
        this.bindGroup.getNext().execute(new Runnable() {

            @Override
            public void run() {
                try {
                    ServerSocket serverSocket = selectableChannel.socket();
                    bindGroup.registSelector(acceptor, SelectionKey.OP_ACCEPT);
                    serverSocket.bind(getServerAddress(), 50);
                    bindWaiter.call(null, null);
                } catch (Throwable e) {
                    Throwable ex = e;
                    if ("Already bound".equalsIgnoreCase(e.getMessage())
                            || e instanceof BindException) {
                        ex = new BindException("Already bound at " + getPort());
                    }
                    bindWaiter.call(null, ex);
                    if (bindWaiter.isTimeouted()) {
                        CloseUtil.unbind(acceptor);
                    }
                }
            }
        });
        if (bindWaiter.await(6000)) {
            CloseUtil.unbind(this);
            throw new IOException("time out to bind @ " + getPort());
        }
        if (bindWaiter.isFailed()) {
            CloseUtil.unbind(this);
            throw (IOException) bindWaiter.getThrowable();
        }
        logger.info("server listening @" + getServerAddress());
    }

    public void broadcast(Frame frame) throws IOException {
        getChannelManager().broadcast(frame);
    }

    public void broadcast(ByteBuf buf) throws IOException {
        getChannelManager().broadcast(buf);
    }

    @Override
    public ServerSocketChannel getSelectableChannel() {
        return selectableChannel;
    }

    @Override
    public boolean isActive() {
        ServerSocketChannel channel = this.selectableChannel;
        return channel != null && channel.isOpen();
    }

    public synchronized void unbind() throws TimeoutException {
        CloseUtil.close(selectableChannel.socket());
        CloseUtil.close(selectableChannel);
        LifeCycleUtil.stop(bindGroup);
        LifeCycleUtil.stop(this);
    }

}
