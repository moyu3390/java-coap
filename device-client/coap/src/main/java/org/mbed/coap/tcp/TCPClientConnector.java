/*
 * Copyright (C) 2011-2014 ARM Limited. All rights reserved.
 */
package org.mbed.coap.tcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.mbed.coap.transport.TransportConnector;
import org.mbed.coap.transport.TransportContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author KALLE
 */
public class TCPClientConnector extends TCPConnector implements TransportConnector {

    private static final Logger LOGGER = LoggerFactory.getLogger(TCPClientConnector.class);

    /**
     * Constructs TCP client connector with default message size
     * {@link org.mbed.coap.tcp.TCPConnector#DEFAULT_MAX_LENGTH}
     */
    public TCPClientConnector() {
        this(DEFAULT_MAX_LENGTH);
    }

    /**
     * Constructs TCP client connector with defined message size
     *
     * @param maxMessageSize maximum message size to be able receive or send
     */
    public TCPClientConnector(int maxMessageSize) {
        this(maxMessageSize, 0);
    }

    /**
     * Constructs TCP client connector with defined message size
     *
     * @param maxMessageSize maximum message size to be able receive or send
     * @param idleTimeout timeout for idle TCP sockets until clearing up
     */
    public TCPClientConnector(int maxMessageSize, int idleTimeout) {
        super(maxMessageSize, idleTimeout);
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Client constructor");
        }
    }

    @Override
    public void send(byte[] data, int len, InetSocketAddress destinationAddress, TransportContext transContext) throws IOException {
        if (len > MAX_LENGTH) {
            LOGGER.warn("Too long message to send, length: " + len + " max-length: " + MAX_LENGTH);
            throw new IOException("Too long message to send.");
        }
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Client Send queue filling");
        }
        synchronized (changeRequests) {
            try {
                fillQueueWithData(data, len, destinationAddress);
            } catch (Exception e) {
                LOGGER.error("Client WriteQueue put caused exception ", e);
            }
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("Client Send queue filling, waking selector");
            }
            selector.wakeup();
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("Client Send queue filling, done");
            }
        }
    }

    private void fillQueueWithData(byte[] data, int len, InetSocketAddress destinationAddress) throws IOException {
        SocketChannel socketChannel = sockets.get(destinationAddress);
        if (socketChannel == null) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Client send: Creating new connection to server");
            }
            socketChannel = initiateConnection(destinationAddress, null);
        } else {
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("Client Send queue filling, adding changerequest");
            }
            changeRequests.add(new ChangeRequest(socketChannel, ChangeRequest.CHANGEOPS, SelectionKey.OP_WRITE));
        }

        List<ByteBuffer> queue = makeSureQueueExists(socketChannel);
        ByteBuffer allData = ByteBuffer.allocate(len + LENGTH_BYTES);
        allData.putInt(len);
        allData.put(data, 0, len);
        queue.add(ByteBuffer.wrap(allData.array()));
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Client Send queue filling, adding to queue [len:{}]", allData.capacity());
        }
    }

    @Override
    public InetSocketAddress getLocalSocketAddress() {
        if (!sockets.isEmpty()) {
            if (sockets.size() > 1) {
                LOGGER.warn("There are multiple TCP connections in this client, returning the first connection address.");
            }
            try {
                return (InetSocketAddress) sockets.values().iterator().next().getLocalAddress();
            } catch (IOException e) {
                LOGGER.warn("Unexpectedly cannot see local address of connection", e);
            }
        }
        return null;
    }

    /**
     * Create new connection to remote address, it connection already exists
     * this returns immediately the local address.
     *
     * @param remoteAddress Remote address
     * @param localAddress Local bind address, null if letting socket channel to
     * assign address automatically.
     * @return local binded address
     * @throws IOException if cannot bind to specified local address
     */
    public InetSocketAddress connect(InetSocketAddress remoteAddress, InetSocketAddress localAddress) throws IOException {
        SocketChannel socketChannel = sockets.get(remoteAddress);
        if (socketChannel == null) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Client connect: New connection to server");
            }
            socketChannel = initiateConnection(remoteAddress, localAddress);
        }
        return (InetSocketAddress) socketChannel.getLocalAddress();

    }

    @Override
    public void run() {
        while (isRunning) {
            try {
                if (LOGGER.isTraceEnabled()) {
                    LOGGER.trace("Client run procedure");
                }
                synchronized (changeRequests) {
                    for (ChangeRequest change : changeRequests) {
                        switch (change.type) {
                            case ChangeRequest.CHANGEOPS:
                                SelectionKey key = change.socket.keyFor(selector);
                                if (key == null) {
                                    LOGGER.debug("key for channel is null " + change.socket);
                                    continue;
                                }
                                try {
                                    key.interestOps(change.ops);
                                } catch (CancelledKeyException ckE) {
                                    LOGGER.debug("CancelledKeyException " + change.socket);
                                    continue;
                                }
                                break;
                            case ChangeRequest.REGISTER:
                                change.socket.register(selector, change.ops);
                                break;
                            default:
                                LOGGER.warn("Un-handled type: " + change.type);
                        }
                    }
                    changeRequests.clear();
                }
                // Wait for an event one of the registered channels
                if (LOGGER.isTraceEnabled()) {
                    LOGGER.trace("Client selecting");
                }
                int num = selector.select();
                if (LOGGER.isTraceEnabled()) {
                    LOGGER.trace("Client selected [" + num + "]");
                }
                iterateOverAvailableEvents();

            } catch (ClosedSelectorException e) {
                LOGGER.debug("Client Selector closed");
            } catch (Exception e) {
                LOGGER.error("Client Cannot make selector select, trying again. ", e);
            }
        }
    }

    private void iterateOverAvailableEvents() throws IOException {
        // Iterate over the set of keys for which events are available
        Iterator<SelectionKey> selectedKeys = selector.selectedKeys().iterator();
        while (selectedKeys.hasNext()) {
            SelectionKey key = selectedKeys.next();
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("Client found selection key " + key);
            }
            selectedKeys.remove();

            if (!key.isValid()) {
                continue;
            }

            if (key.isConnectable()) {
                finishConnection(key);
            } else if (key.isReadable()) {
                read(key);
            } else if (key.isWritable()) {
                write(key);
            }
        }
    }

    private void write(SelectionKey key) throws IOException {
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Client Socket writing");
        }
        SocketChannel socketChannel = (SocketChannel) key.channel();
        InetSocketAddress address = (InetSocketAddress) socketChannel.getRemoteAddress();
        resetTimer(address);
        synchronized (changeRequests) {
            List<ByteBuffer> queue = pendingData.get(socketChannel);
            try {

                // Write until there's not more data ...
                while (!queue.isEmpty()) {
                    ByteBuffer buf = queue.get(0);
                    if (LOGGER.isTraceEnabled()) {
                        LOGGER.trace("Client writing, limit:" + buf.limit() + " position:" + buf.position() + " remaining:" + buf.remaining());
                    }
                    socketChannel.write(buf);
                    if (buf.remaining() > 0) {
                        // ... or the socket's buffer fills up
                        break;
                    }
                    queue.remove(0);
                }

                if (queue.isEmpty()) {
                    if (LOGGER.isTraceEnabled()) {
                        LOGGER.trace("Client mark READ op");
                    }
                    key.interestOps(SelectionKey.OP_READ);
                }
            } catch (Exception e) {
                LOGGER.error("Client Cannot write to socket.", e);
                queue.clear();
                cleanupConnection(address);
                key.cancel();
            }
        }
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Client Socket writing end");
        }
    }

    @Override
    protected Selector initSelector() throws IOException {
        return SelectorProvider.provider().openSelector();
    }

    @Override
    protected String getThreadName() {
        return "tcp-client-connector";
    }

    private void finishConnection(SelectionKey key) {
        SocketChannel socketChannel = (SocketChannel) key.channel();
        try {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Client finishing connection (local: " + socketChannel.getLocalAddress() + ")");
            }
            socketChannel.finishConnect();
            InetSocketAddress remoteAddress = (InetSocketAddress) socketChannel.socket().getRemoteSocketAddress();
            sockets.put(remoteAddress, socketChannel);
            makeSureQueueExists(socketChannel);
            resetTimer(remoteAddress);
            oldReadBuffer.remove(remoteAddress);
        } catch (IOException e) {
            LOGGER.error("Client cannot finish connection " + e);
            key.cancel();
            return;
        }
        key.interestOps(SelectionKey.OP_WRITE);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Client finished connection");
        }
    }

    private SocketChannel initiateConnection(InetSocketAddress address, InetSocketAddress localAddress) throws IOException {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Client initiating connection to " + address + " local: " + localAddress);
        }
        SocketChannel socketChannel = SocketChannel.open();
        socketChannel.configureBlocking(false);
        socketChannel.bind(localAddress);
        socketChannel.connect(address);
        resetTimer(address);
        synchronized (changeRequests) {
            changeRequests.add(new ChangeRequest(socketChannel, ChangeRequest.REGISTER, SelectionKey.OP_CONNECT));
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Client initiated connection to " + address + " local: " + localAddress);
        }
        selector.wakeup();
        return socketChannel;
    }

    /**
     * Add Socket to internal socket list. It will be used when making request
     * to given destination address.
     *
     * @param destinationAddress destination address
     * @param socketChannel Socket channel
     */
    public void addSocketChannel(InetSocketAddress destinationAddress, SocketChannel socketChannel) {

        if (!socketChannel.isConnected()) {
            throw new IllegalStateException("Socket is not connected! " + socketChannel);
        }

        synchronized (changeRequests) {
            changeRequests.add(new ChangeRequest(socketChannel, ChangeRequest.REGISTER, SelectionKey.OP_READ));
            sockets.put(destinationAddress, socketChannel);
            oldReadBuffer.remove(destinationAddress);
            if (pendingData.get(socketChannel) == null) {
                pendingData.put(socketChannel, new ArrayList<ByteBuffer>());
            }
            selector.wakeup();
        }
    }
}
