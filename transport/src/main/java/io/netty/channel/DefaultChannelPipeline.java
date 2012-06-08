/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.channel;

import io.netty.buffer.ChannelBuffer;
import io.netty.channel.DefaultChannelHandlerContext.MessageBridge;
import io.netty.channel.DefaultChannelHandlerContext.StreamBridge;
import io.netty.logging.InternalLogger;
import io.netty.logging.InternalLoggerFactory;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.concurrent.Future;

/**
 * The default {@link ChannelPipeline} implementation.  It is usually created
 * by a {@link Channel} implementation when the {@link Channel} is created.
 */
public class DefaultChannelPipeline implements ChannelPipeline {

    static final InternalLogger logger = InternalLoggerFactory.getInstance(DefaultChannelPipeline.class);

    final Channel channel;
    private final Channel.Unsafe unsafe;

    final DefaultChannelHandlerContext head;
    private volatile DefaultChannelHandlerContext tail;
    private final Map<String, DefaultChannelHandlerContext> name2ctx =
        new HashMap<String, DefaultChannelHandlerContext>(4);
    private boolean firedChannelActive;
    private boolean fireInboundBufferUpdatedOnActivation;

    final Map<EventExecutor, EventExecutor> childExecutors =
            new IdentityHashMap<EventExecutor, EventExecutor>();


    public DefaultChannelPipeline(Channel channel) {
        if (channel == null) {
            throw new NullPointerException("channel");
        }
        this.channel = channel;

        HeadHandler headHandler = new HeadHandler();
        head = new DefaultChannelHandlerContext(
                this, null, null, null, generateName(headHandler), headHandler);
        tail = head;

        unsafe = channel.unsafe();
    }

    @Override
    public Channel channel() {
        return channel;
    }

    @Override
    public ChannelPipeline addFirst(String name, ChannelHandler handler) {
        return addFirst(null, name, handler);
    }

    @Override
    public ChannelPipeline addFirst(EventExecutor executor, final String name, final ChannelHandler handler) {
        try {
            Future<Throwable> future;

            synchronized (this) {
                checkDuplicateName(name);
                final DefaultChannelHandlerContext nextCtx = head.next;
                final DefaultChannelHandlerContext newCtx =
                        new DefaultChannelHandlerContext(this, executor, head, nextCtx, name, handler);

                if (!newCtx.channel().isRegistered() || newCtx.executor().inEventLoop()) {
                    addFirst0(name, nextCtx, newCtx);
                    return this;
                }
                future = newCtx.executor().submit(new DefaultChannelPipelineModificationTask(this) {
                    @Override
                    void doCall() {
                        checkDuplicateName(name);
                        addFirst0(name, nextCtx, newCtx);
                    }
                });
            }
            // Call Future.get() outside of the synchronized block to prevent dead-lock
            Throwable result = future.get();
            if (result != null) {
                // re-throw exception that was caught
                throw result;
            }
            return this;

        } catch (RuntimeException e) {
            throw e;
        } catch (Error e) {
            throw e;
        } catch (Throwable t) {
            throw new ChannelPipelineException(t);
        }

    }

    private void addFirst0(
            final String name, DefaultChannelHandlerContext nextCtx, DefaultChannelHandlerContext newCtx) {
        callBeforeAdd(newCtx);

        if (nextCtx != null) {
            nextCtx.prev = newCtx;
        }
        head.next = newCtx;
        name2ctx.put(name, newCtx);

        callAfterAdd(newCtx);
    }

    @Override
    public ChannelPipeline addLast(String name, ChannelHandler handler) {
        return addLast(null, name, handler);
    }

    @Override
    public ChannelPipeline addLast(EventExecutor executor, final String name, final ChannelHandler handler) {
        try {
            Future<Throwable> future;

            synchronized (this) {
                checkDuplicateName(name);

                final DefaultChannelHandlerContext oldTail = tail;
                final DefaultChannelHandlerContext newTail =
                        new DefaultChannelHandlerContext(this, executor, oldTail, null, name, handler);

                if (!newTail.channel().isRegistered() || newTail.executor().inEventLoop()) {
                    addLast0(name, oldTail, newTail);
                    return this;
                } else {
                    future = newTail.executor().submit(new DefaultChannelPipelineModificationTask(this) {
                        @Override
                        void doCall() {
                            checkDuplicateName(name);
                            addLast0(name, oldTail, newTail);
                        }
                    });
                }
            }
            // Call Future.get() outside of synchronized block to prevent dead-lock
            Throwable result = future.get();
            if (result != null) {
                // re-throw exception that was caught
                throw result;
            }
            return this;

        } catch (RuntimeException e) {
            throw e;
        } catch (Error e) {
            throw e;
        } catch (Throwable t) {
            throw new ChannelPipelineException(t);
        }

    }

    private void addLast0(
            final String name, DefaultChannelHandlerContext oldTail, DefaultChannelHandlerContext newTail) {
        callBeforeAdd(newTail);

        oldTail.next = newTail;
        tail = newTail;
        name2ctx.put(name, newTail);

        callAfterAdd(newTail);
    }

    @Override
    public ChannelPipeline addBefore(String baseName, String name, ChannelHandler handler) {
        return addBefore(null, baseName, name, handler);
    }

    @Override
    public ChannelPipeline addBefore(
            EventExecutor executor, String baseName, final String name, final ChannelHandler handler) {
        try {
            Future<Throwable> future;

            synchronized (this) {
                final DefaultChannelHandlerContext ctx = getContextOrDie(baseName);
                checkDuplicateName(name);
                final DefaultChannelHandlerContext newCtx =
                        new DefaultChannelHandlerContext(this, executor, ctx.prev, ctx, name, handler);

                if (!newCtx.channel().isRegistered() || newCtx.executor().inEventLoop()) {
                    addBefore0(name, ctx, newCtx);
                    return this;
                } else {
                    future = newCtx.executor().submit(new DefaultChannelPipelineModificationTask(this) {
                        @Override
                        void doCall() {
                            checkDuplicateName(name);
                            addBefore0(name, ctx, newCtx);
                        }
                    });
                }
            }

            // Call Future.get() outside of synchronized block to prevent dead-lock
            Throwable result = future.get();
            if (result != null) {
                // re-throw exception that was caught
                throw result;
            }
            return this;

        } catch (RuntimeException e) {
            throw e;
        } catch (Error e) {
            throw e;
        } catch (Throwable t) {
            throw new ChannelPipelineException(t);
        }

    }

    private void addBefore0(final String name, DefaultChannelHandlerContext ctx, DefaultChannelHandlerContext newCtx) {
        callBeforeAdd(newCtx);

        ctx.prev.next = newCtx;
        ctx.prev = newCtx;
        name2ctx.put(name, newCtx);

        callAfterAdd(newCtx);
    }

    @Override
    public ChannelPipeline addAfter(String baseName, String name, ChannelHandler handler) {
        return addAfter(null, baseName, name, handler);
    }

    @Override
    public ChannelPipeline addAfter(
            EventExecutor executor, String baseName, final String name, final ChannelHandler handler) {

        try {
            Future<Throwable> future;

            synchronized (this) {
                final DefaultChannelHandlerContext ctx = getContextOrDie(baseName);
                if (ctx == tail) {
                    return addLast(name, handler);
                }
                checkDuplicateName(name);
                final DefaultChannelHandlerContext newCtx =
                        new DefaultChannelHandlerContext(this, executor, ctx, ctx.next, name, handler);

                if (!newCtx.channel().isRegistered() || newCtx.executor().inEventLoop()) {
                    addAfter0(name, ctx, newCtx);
                    return this;
                } else {
                    future = newCtx.executor().submit(new DefaultChannelPipelineModificationTask(this) {
                        @Override
                        void doCall() {
                            checkDuplicateName(name);
                            addAfter0(name, ctx, newCtx);
                        }
                    });
                }
            }
            // Call Future.get() outside of synchronized block to prevent dead-lock
            Throwable result = future.get();
            if (result != null) {
                // re-throw exception that was caught
                throw result;
            }
            return this;

        } catch (RuntimeException e) {
            throw e;
        } catch (Error e) {
            throw e;
        } catch (Throwable t) {
            throw new ChannelPipelineException(t);
        }

    }

    private void addAfter0(final String name, DefaultChannelHandlerContext ctx, DefaultChannelHandlerContext newCtx) {
        checkDuplicateName(name);

        callBeforeAdd(newCtx);

        ctx.next.prev = newCtx;
        ctx.next = newCtx;
        name2ctx.put(name, newCtx);

        callAfterAdd(newCtx);
    }

    @Override
    public ChannelPipeline addFirst(ChannelHandler... handlers) {
        return addFirst(null, handlers);
    }

    @Override
    public ChannelPipeline addFirst(EventExecutor executor, ChannelHandler... handlers) {
        if (handlers == null) {
            throw new NullPointerException("handlers");
        }
        if (handlers.length == 0 || handlers[0] == null) {
            return this;
        }

        int size;
        for (size = 1; size < handlers.length; size ++) {
            if (handlers[size] == null) {
                break;
            }
        }

        for (int i = size - 1; i >= 0; i --) {
            ChannelHandler h = handlers[i];
            addFirst(executor, generateName(h), h);
        }

        return this;
    }

    @Override
    public ChannelPipeline addLast(ChannelHandler... handlers) {
        return addLast(null, handlers);
    }

    @Override
    public ChannelPipeline addLast(EventExecutor executor, ChannelHandler... handlers) {
        if (handlers == null) {
            throw new NullPointerException("handlers");
        }

        for (ChannelHandler h: handlers) {
            if (h == null) {
                break;
            }
            addLast(executor, generateName(h), h);
        }

        return this;
    }

    private static String generateName(ChannelHandler handler) {
        String type = handler.getClass().getSimpleName();
        StringBuilder buf = new StringBuilder(type.length() + 10);
        buf.append(type);
        buf.append("-0");
        buf.append(Long.toHexString(System.identityHashCode(handler) & 0xFFFFFFFFL | 0x100000000L));
        buf.setCharAt(buf.length() - 9, 'x');
        return buf.toString();
    }

    @Override
    public void remove(ChannelHandler handler) {
        remove(getContextOrDie(handler));
    }

    @Override
    public ChannelHandler remove(String name) {
        return remove(getContextOrDie(name)).handler();
    }

    @Override
    public <T extends ChannelHandler> T remove(Class<T> handlerType) {
        return (T) remove(getContextOrDie(handlerType)).handler();
    }

    private DefaultChannelHandlerContext remove(final DefaultChannelHandlerContext ctx) {
        try {
            DefaultChannelHandlerContext context;
            Future<Throwable> future;
            synchronized (this) {
                if (head == tail) {
                    return null;
                } else if (ctx == head) {
                    throw new Error(); // Should never happen.
                } else if (ctx == tail) {
                    if (head == tail) {
                        throw new NoSuchElementException();
                    }

                    final DefaultChannelHandlerContext oldTail = tail;
                    if (!oldTail.channel().isRegistered() || oldTail.executor().inEventLoop()) {
                        removeLast0(oldTail);
                        return oldTail;
                    } else {
                        future = oldTail.executor().submit(new DefaultChannelPipelineModificationTask(this) {
                            @Override
                            void doCall() {
                                removeLast0(oldTail);
                            }
                        });
                        context = oldTail;
                    }

                } else {
                    if (!ctx.channel().isRegistered() || ctx.executor().inEventLoop()) {
                        remove0(ctx);
                        return ctx;
                    } else {
                       future = ctx.executor().submit(new DefaultChannelPipelineModificationTask(this) {
                           @Override
                           void doCall() {
                               remove0(ctx);
                           }
                       });
                       context = ctx;
                    }
                }
            }

            // Call Future.get() outside of synchronized block to prevent dead-lock
            Throwable result = future.get();
            if (result != null) {
                // re-throw exception that was caught
                throw result;
            }

            return context;
        } catch (RuntimeException e) {
            throw e;
        } catch (Error e) {
            throw e;
        } catch (Throwable t) {
            throw new ChannelPipelineException(t);
        }

    }

    private void remove0(DefaultChannelHandlerContext ctx) {
        callBeforeRemove(ctx);

        DefaultChannelHandlerContext prev = ctx.prev;
        DefaultChannelHandlerContext next = ctx.next;
        prev.next = next;
        next.prev = prev;
        name2ctx.remove(ctx.name());

        callAfterRemove(ctx);
    }

    @Override
    public ChannelHandler removeFirst() {
        if (head == tail) {
            throw new NoSuchElementException();
        }
        return remove(head.next).handler();
    }

    @Override
    public ChannelHandler removeLast() {
        try {
            Future<Throwable> future;
            final DefaultChannelHandlerContext oldTail;
            synchronized (this) {
                if (head == tail) {
                    throw new NoSuchElementException();
                }
                oldTail = tail;
                if (!oldTail.channel().isRegistered() || oldTail.executor().inEventLoop()) {
                    removeLast0(oldTail);
                    return oldTail.handler();
                } else {
                    future = oldTail.executor().submit(new DefaultChannelPipelineModificationTask(this) {
                        @Override
                        void doCall() {
                            removeLast0(oldTail);
                        }
                    });
                }
            }
            // Call Future.get() outside of synchronized block to prevent dead-lock
            Throwable result = future.get();
            if (result != null) {
                // re-throw exception that was caught
                throw result;
            }

            return oldTail.handler();
        } catch (RuntimeException e) {
            throw e;
        } catch (Error e) {
            throw e;
        } catch (Throwable t) {
            throw new ChannelPipelineException(t);
        }


    }

    private void removeLast0(DefaultChannelHandlerContext oldTail) {
        callBeforeRemove(oldTail);

        oldTail.prev.next = null;
        tail = oldTail.prev;
        name2ctx.remove(oldTail.name());

        callBeforeRemove(oldTail);
    }

    @Override
    public void replace(ChannelHandler oldHandler, String newName, ChannelHandler newHandler) {
        replace(getContextOrDie(oldHandler), newName, newHandler);
    }

    @Override
    public ChannelHandler replace(String oldName, String newName, ChannelHandler newHandler) {
        return replace(getContextOrDie(oldName), newName, newHandler);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends ChannelHandler> T replace(
            Class<T> oldHandlerType, String newName, ChannelHandler newHandler) {
        return (T) replace(getContextOrDie(oldHandlerType), newName, newHandler);
    }

    private ChannelHandler replace(
            final DefaultChannelHandlerContext ctx, final String newName, final ChannelHandler newHandler) {
        try {
            Future<Throwable> future;
            synchronized (this) {
                if (ctx == head) {
                    throw new IllegalArgumentException();
                } else if (ctx == tail) {
                    if (head == tail) {
                        throw new NoSuchElementException();
                    }
                    final DefaultChannelHandlerContext oldTail = tail;
                    final DefaultChannelHandlerContext newTail =
                            new DefaultChannelHandlerContext(this, null, oldTail, null, newName, newHandler);

                    if (!oldTail.channel().isRegistered() || oldTail.executor().inEventLoop()) {
                        removeLast0(oldTail);
                        checkDuplicateName(newName);
                        addLast0(newName, tail, newTail);
                        return ctx.handler();

                    } else {
                        future = oldTail.executor().submit(new DefaultChannelPipelineModificationTask(this) {
                            @Override
                            void doCall() {
                                removeLast0(oldTail);
                                checkDuplicateName(newName);
                                addLast0(newName, tail, newTail);
                            }
                        });
                    }

                } else {
                    boolean sameName = ctx.name().equals(newName);
                    if (!sameName) {
                        checkDuplicateName(newName);
                    }

                    DefaultChannelHandlerContext prev = ctx.prev;
                    DefaultChannelHandlerContext next = ctx.next;

                    final DefaultChannelHandlerContext newCtx =
                            new DefaultChannelHandlerContext(this, ctx.executor, prev, next, newName, newHandler);

                    if (!newCtx.channel().isRegistered() || newCtx.executor().inEventLoop()) {
                        replace0(ctx, newName, newCtx);
                        return ctx.handler();
                    } else {
                        future = newCtx.executor().submit(new DefaultChannelPipelineModificationTask(this) {
                            @Override
                            void doCall() {
                                replace0(ctx, newName, newCtx);
                            }
                        });
                    }
                }
            }
            // Call Future.get() outside of synchronized block to prevent dead-lock
            Throwable result = future.get();
            if (result != null) {
                // re-throw exception that was caught
                throw result;
            }

            return ctx.handler();

        } catch (RuntimeException e) {
            throw e;
        } catch (Error e) {
            throw e;
        } catch (Throwable t) {
            throw new ChannelPipelineException(t);
        }
    }

    private void replace0(DefaultChannelHandlerContext ctx, String newName, DefaultChannelHandlerContext newCtx) {
        boolean sameName = ctx.name().equals(newName);

        DefaultChannelHandlerContext prev = ctx.prev;
        DefaultChannelHandlerContext next = ctx.next;

        callBeforeRemove(ctx);
        callBeforeAdd(newCtx);

        prev.next = newCtx;
        next.prev = newCtx;

        if (!sameName) {
            name2ctx.remove(ctx.name());
        }
        name2ctx.put(newName, newCtx);

        ChannelHandlerLifeCycleException removeException = null;
        ChannelHandlerLifeCycleException addException = null;
        boolean removed = false;
        try {
            callAfterRemove(ctx);
            removed = true;
        } catch (ChannelHandlerLifeCycleException e) {
            removeException = e;
        }

        boolean added = false;
        try {
            callAfterAdd(newCtx);
            added = true;
        } catch (ChannelHandlerLifeCycleException e) {
            addException = e;
        }

        if (!removed && !added) {
            logger.warn(removeException.getMessage(), removeException);
            logger.warn(addException.getMessage(), addException);
            throw new ChannelHandlerLifeCycleException(
                    "Both " + ctx.handler().getClass().getName() +
                    ".afterRemove() and " + newCtx.handler().getClass().getName() +
                    ".afterAdd() failed; see logs.");
        } else if (!removed) {
            throw removeException;
        } else if (!added) {
            throw addException;
        }
    }

    private static void callBeforeAdd(ChannelHandlerContext ctx) {
        ChannelHandler handler = ctx.handler();
        if (handler instanceof ChannelStateHandlerAdapter) {
            ChannelStateHandlerAdapter h = (ChannelStateHandlerAdapter) handler;
            if (!h.isSharable() && h.added) {
                throw new ChannelHandlerLifeCycleException(
                        "Only a @Sharable handler can be added or removed multiple times.");
            }
            h.added = true;
        }
        try {
            handler.beforeAdd(ctx);
        } catch (Throwable t) {
            throw new ChannelHandlerLifeCycleException(
                    handler.getClass().getName() +
                    ".beforeAdd() has thrown an exception; not adding.", t);
        }
    }

    private void callAfterAdd(ChannelHandlerContext ctx) {
        try {
            ctx.handler().afterAdd(ctx);
        } catch (Throwable t) {
            boolean removed = false;
            try {
                remove((DefaultChannelHandlerContext) ctx);
                removed = true;
            } catch (Throwable t2) {
                if (logger.isWarnEnabled()) {
                    logger.warn("Failed to remove a handler: " + ctx.name(), t2);
                }
            }

            if (removed) {
                throw new ChannelHandlerLifeCycleException(
                        ctx.handler().getClass().getName() +
                        ".afterAdd() has thrown an exception; removed.", t);
            } else {
                throw new ChannelHandlerLifeCycleException(
                        ctx.handler().getClass().getName() +
                        ".afterAdd() has thrown an exception; also failed to remove.", t);
            }
        }
    }

    private static void callBeforeRemove(ChannelHandlerContext ctx) {
        try {
            ctx.handler().beforeRemove(ctx);
        } catch (Throwable t) {
            throw new ChannelHandlerLifeCycleException(
                    ctx.handler().getClass().getName() +
                    ".beforeRemove() has thrown an exception; not removing.", t);
        }
    }

    private static void callAfterRemove(ChannelHandlerContext ctx) {
        try {
            ctx.handler().afterRemove(ctx);
        } catch (Throwable t) {
            throw new ChannelHandlerLifeCycleException(
                    ctx.handler().getClass().getName() +
                    ".afterRemove() has thrown an exception.", t);
        }
    }

    @Override
    public synchronized ChannelHandler first() {
        DefaultChannelHandlerContext first = head.next;
        if (first == null) {
            return null;
        }
        return first.handler();
    }

    @Override
    public synchronized ChannelHandler last() {
        DefaultChannelHandlerContext last = tail;
        if (last == head || last == null) {
            return null;
        }
        return last.handler();
    }

    @Override
    public synchronized ChannelHandler get(String name) {
        DefaultChannelHandlerContext ctx = name2ctx.get(name);
        if (ctx == null) {
            return null;
        } else {
            return ctx.handler();
        }
    }

    @Override
    public synchronized <T extends ChannelHandler> T get(Class<T> handlerType) {
        ChannelHandlerContext ctx = context(handlerType);
        if (ctx == null) {
            return null;
        } else {
            return (T) ctx.handler();
        }
    }

    @Override
    public synchronized ChannelHandlerContext context(String name) {
        if (name == null) {
            throw new NullPointerException("name");
        }
        return name2ctx.get(name);
    }

    @Override
    public synchronized ChannelHandlerContext context(ChannelHandler handler) {
        if (handler == null) {
            throw new NullPointerException("handler");
        }
        if (name2ctx.isEmpty()) {
            return null;
        }
        DefaultChannelHandlerContext ctx = head;
        for (;;) {
            if (ctx.handler() == handler) {
                return ctx;
            }

            ctx = ctx.next;
            if (ctx == null) {
                break;
            }
        }
        return null;
    }

    @Override
    public synchronized ChannelHandlerContext context(
            Class<? extends ChannelHandler> handlerType) {
        if (handlerType == null) {
            throw new NullPointerException("handlerType");
        }

        if (name2ctx.isEmpty()) {
            return null;
        }
        DefaultChannelHandlerContext ctx = head.next;
        for (;;) {
            if (handlerType.isAssignableFrom(ctx.handler().getClass())) {
                return ctx;
            }

            ctx = ctx.next;
            if (ctx == null) {
                break;
            }
        }
        return null;
    }

    @Override
    public List<String> names() {
        List<String> list = new ArrayList<String>();
        if (name2ctx.isEmpty()) {
            return list;
        }

        DefaultChannelHandlerContext ctx = head.next;
        for (;;) {
            list.add(ctx.name());
            ctx = ctx.next;
            if (ctx == null) {
                break;
            }
        }
        return list;
    }

    @Override
    public Map<String, ChannelHandler> toMap() {
        Map<String, ChannelHandler> map = new LinkedHashMap<String, ChannelHandler>();
        if (name2ctx.isEmpty()) {
            return map;
        }

        DefaultChannelHandlerContext ctx = head.next;
        for (;;) {
            map.put(ctx.name(), ctx.handler());
            ctx = ctx.next;
            if (ctx == null) {
                break;
            }
        }
        return map;
    }

    /**
     * Returns the {@link String} representation of this pipeline.
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append(getClass().getSimpleName());
        buf.append('{');
        DefaultChannelHandlerContext ctx = head.next;
        for (;;) {
            buf.append('(');
            buf.append(ctx.name());
            buf.append(" = ");
            buf.append(ctx.handler().getClass().getName());
            buf.append(')');
            ctx = ctx.next;
            if (ctx == null) {
                break;
            }
            buf.append(", ");
        }
        buf.append('}');
        return buf.toString();
    }

    @Override
    public Queue<Object> inboundMessageBuffer() {
        if (channel.type() != ChannelType.MESSAGE) {
            throw new NoSuchBufferException(
                    "The first inbound buffer of this channel must be a message buffer.");
        }
        return nextInboundMessageBuffer(head.next);
    }

    @Override
    public ChannelBuffer inboundByteBuffer() {
        if (channel.type() != ChannelType.STREAM) {
            throw new NoSuchBufferException(
                    "The first inbound buffer of this channel must be a byte buffer.");
        }
        return nextInboundByteBuffer(head.next);
    }

    @Override
    public Queue<Object> outboundMessageBuffer() {
        return nextOutboundMessageBuffer(tail);
    }

    @Override
    public ChannelBuffer outboundByteBuffer() {
        return nextOutboundByteBuffer(tail);
    }

    static boolean hasNextInboundByteBuffer(DefaultChannelHandlerContext ctx) {
        for (;;) {
            if (ctx == null) {
                return false;
            }
            if (ctx.inByteBridge != null) {
                return true;
            }
            ctx = ctx.next;
        }
    }

    static boolean hasNextInboundMessageBuffer(DefaultChannelHandlerContext ctx) {
        for (;;) {
            if (ctx == null) {
                return false;
            }
            if (ctx.inMsgBridge != null) {
                return true;
            }
            ctx = ctx.next;
        }
    }

    static ChannelBuffer nextInboundByteBuffer(DefaultChannelHandlerContext ctx) {
        final Thread currentThread = Thread.currentThread();
        for (;;) {
            if (ctx == null) {
                throw new NoSuchBufferException();
            }
            if (ctx.inByteBuf != null) {
                if (ctx.executor().inEventLoop(currentThread)) {
                    return ctx.inByteBuf;
                } else {
                    StreamBridge bridge = ctx.inByteBridge.get();
                    if (bridge == null) {
                        bridge = new StreamBridge();
                        if (!ctx.inByteBridge.compareAndSet(null, bridge)) {
                            bridge = ctx.inByteBridge.get();
                        }
                    }
                    return bridge.byteBuf;
                }
            }
            ctx = ctx.next;
        }
    }

    static Queue<Object> nextInboundMessageBuffer(DefaultChannelHandlerContext ctx) {
        final Thread currentThread = Thread.currentThread();
        for (;;) {
            if (ctx == null) {
                throw new NoSuchBufferException();
            }

            if (ctx.inMsgBuf != null) {
                if (ctx.executor().inEventLoop(currentThread)) {
                    return ctx.inMsgBuf;
                } else {
                    MessageBridge bridge = ctx.inMsgBridge.get();
                    if (bridge == null) {
                        bridge = new MessageBridge();
                        if (!ctx.inMsgBridge.compareAndSet(null, bridge)) {
                            bridge = ctx.inMsgBridge.get();
                        }
                    }
                    return bridge.msgBuf;
                }
            }
            ctx = ctx.next;
        }
    }

    boolean hasNextOutboundByteBuffer(DefaultChannelHandlerContext ctx) {
        for (;;) {
            if (ctx == null) {
                return false;
            }

            if (ctx.outByteBridge != null) {
                return true;
            }
            ctx = ctx.prev;
        }
    }

    boolean hasNextOutboundMessageBuffer(DefaultChannelHandlerContext ctx) {
        for (;;) {
            if (ctx == null) {
                return false;
            }

            if (ctx.outMsgBridge != null) {
                return true;
            }
            ctx = ctx.prev;
        }
    }

    ChannelBuffer nextOutboundByteBuffer(DefaultChannelHandlerContext ctx) {
        final Thread currentThread = Thread.currentThread();
        for (;;) {
            if (ctx == null) {
                throw new NoSuchBufferException();
            }

            if (ctx.outByteBuf != null) {
                if (ctx.executor().inEventLoop(currentThread)) {
                    return ctx.outByteBuf;
                } else {
                    StreamBridge bridge = ctx.outByteBridge.get();
                    if (bridge == null) {
                        bridge = new StreamBridge();
                        if (!ctx.outByteBridge.compareAndSet(null, bridge)) {
                            bridge = ctx.outByteBridge.get();
                        }
                    }
                    return bridge.byteBuf;
                }
            }
            ctx = ctx.prev;
        }
    }

    Queue<Object> nextOutboundMessageBuffer(DefaultChannelHandlerContext ctx) {
        final Thread currentThread = Thread.currentThread();
        for (;;) {
            if (ctx == null) {
                throw new NoSuchBufferException();
            }

            if (ctx.outMsgBuf != null) {
                if (ctx.executor().inEventLoop(currentThread)) {
                    return ctx.outMsgBuf;
                } else {
                    MessageBridge bridge = ctx.outMsgBridge.get();
                    if (bridge == null) {
                        bridge = new MessageBridge();
                        if (!ctx.outMsgBridge.compareAndSet(null, bridge)) {
                            bridge = ctx.outMsgBridge.get();
                        }
                    }
                    return bridge.msgBuf;
                }
            }
            ctx = ctx.prev;
        }
    }

    @Override
    public void fireChannelRegistered() {
        DefaultChannelHandlerContext ctx = firstContext(ChannelHandlerType.STATE);
        if (ctx != null) {
            fireChannelRegistered(ctx);
        }
    }

    static void fireChannelRegistered(DefaultChannelHandlerContext ctx) {
        EventExecutor executor = ctx.executor();
        if (executor.inEventLoop()) {
            ctx.fireChannelRegisteredTask.run();
        } else {
            executor.execute(ctx.fireChannelRegisteredTask);
        }
    }

    @Override
    public void fireChannelUnregistered() {
        DefaultChannelHandlerContext ctx = firstContext(ChannelHandlerType.STATE);
        if (ctx != null) {
            fireChannelUnregistered(ctx);
        }
    }

    static void fireChannelUnregistered(DefaultChannelHandlerContext ctx) {
        EventExecutor executor = ctx.executor();
        if (executor.inEventLoop()) {
            ctx.fireChannelUnregisteredTask.run();
        } else {
            executor.execute(ctx.fireChannelUnregisteredTask);
        }
    }

    @Override
    public void fireChannelActive() {
        DefaultChannelHandlerContext ctx = firstContext(ChannelHandlerType.STATE);
        if (ctx != null) {
            firedChannelActive = true;
            fireChannelActive(ctx);
            if (fireInboundBufferUpdatedOnActivation) {
                fireInboundBufferUpdatedOnActivation = false;
                fireInboundBufferUpdated(ctx);
            }
        }
    }

    static void fireChannelActive(DefaultChannelHandlerContext ctx) {
        EventExecutor executor = ctx.executor();
        if (executor.inEventLoop()) {
            ctx.fireChannelActiveTask.run();
        } else {
            executor.execute(ctx.fireChannelActiveTask);
        }
    }

    @Override
    public void fireChannelInactive() {
        DefaultChannelHandlerContext ctx = firstContext(ChannelHandlerType.STATE);
        if (ctx != null) {
            // Some implementations such as EmbeddedChannel can trigger inboundBufferUpdated()
            // after deactivation, so it's safe not to revert the firedChannelActive flag here.
            // Also, all known transports never get re-activated.
            //firedChannelActive = false;
            fireChannelInactive(ctx);
        }
    }

    static void fireChannelInactive(DefaultChannelHandlerContext ctx) {
        EventExecutor executor = ctx.executor();
        if (executor.inEventLoop()) {
            ctx.fireChannelInactiveTask.run();
        } else {
            executor.execute(ctx.fireChannelInactiveTask);
        }
    }

    @Override
    public void fireExceptionCaught(Throwable cause) {
        DefaultChannelHandlerContext ctx = head.next;
        if (ctx != null) {
            fireExceptionCaught(ctx, cause);
        } else {
            logTerminalException(cause);
        }
    }

    static void logTerminalException(Throwable cause) {
        logger.warn(
                "An exceptionCaught() event was fired, and it reached at the end of the " +
                "pipeline.  It usually means the last inbound handler in the pipeline did not " +
                "handle the exception.", cause);
    }

    void fireExceptionCaught(final DefaultChannelHandlerContext ctx, final Throwable cause) {
        if (cause == null) {
            throw new NullPointerException("cause");
        }

        EventExecutor executor = ctx.executor();
        if (executor.inEventLoop()) {
            try {
                ((ChannelInboundHandler<Object>) ctx.handler()).exceptionCaught(ctx, cause);
            } catch (Throwable t) {
                if (logger.isWarnEnabled()) {
                    logger.warn(
                            "An exception was thrown by a user handler's " +
                            "exceptionCaught() method while handling the following exception:", cause);
                }
            }
        } else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    fireExceptionCaught(ctx, cause);
                }
            });
        }
    }

    @Override
    public void fireUserEventTriggered(Object event) {
        DefaultChannelHandlerContext ctx = head.next;
        if (ctx != null) {
            fireUserEventTriggered(ctx, event);
        }
    }

    void fireUserEventTriggered(final DefaultChannelHandlerContext ctx, final Object event) {
        if (event == null) {
            throw new NullPointerException("event");
        }

        EventExecutor executor = ctx.executor();
        if (executor.inEventLoop()) {
            try {
                ((ChannelInboundHandler<Object>) ctx.handler()).userEventTriggered(ctx, event);
            } catch (Throwable t) {
                notifyHandlerException(t);
            }
        } else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    fireUserEventTriggered(ctx, event);
                }
            });
        }
    }

    @Override
    public void fireInboundBufferUpdated() {
        if (!firedChannelActive) {
            fireInboundBufferUpdatedOnActivation = true;
            return;
        }
        DefaultChannelHandlerContext ctx = firstContext(ChannelHandlerType.STATE);
        if (ctx != null) {
            fireInboundBufferUpdated(ctx);
        }
    }

    static void fireInboundBufferUpdated(DefaultChannelHandlerContext ctx) {
        EventExecutor executor = ctx.executor();
        if (executor.inEventLoop()) {
            ctx.curCtxFireInboundBufferUpdatedTask.run();
        } else {
            executor.execute(ctx.curCtxFireInboundBufferUpdatedTask);
        }
    }

    @Override
    public ChannelFuture bind(SocketAddress localAddress) {
        return bind(localAddress, channel.newFuture());
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress) {
        return connect(remoteAddress, channel.newFuture());
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress) {
        return connect(remoteAddress, localAddress, channel.newFuture());
    }

    @Override
    public ChannelFuture disconnect() {
        return disconnect(channel.newFuture());
    }

    @Override
    public ChannelFuture close() {
        return close(channel.newFuture());
    }

    @Override
    public ChannelFuture deregister() {
        return deregister(channel.newFuture());
    }

    @Override
    public ChannelFuture flush() {
        return flush(channel.newFuture());
    }

    @Override
    public ChannelFuture write(Object message) {
        return write(message, channel.newFuture());
    }

    @Override
    public ChannelFuture bind(SocketAddress localAddress, ChannelFuture future) {
        return bind(firstContext(ChannelHandlerType.OPERATION), localAddress, future);
    }

    ChannelFuture bind(
            final DefaultChannelHandlerContext ctx, final SocketAddress localAddress, final ChannelFuture future) {
        if (localAddress == null) {
            throw new NullPointerException("localAddress");
        }
        validateFuture(future);

        EventExecutor executor = ctx.executor();
        if (executor.inEventLoop()) {
            try {
                ((ChannelOperationHandler) ctx.handler()).bind(ctx, localAddress, future);
            } catch (Throwable t) {
                notifyHandlerException(t);
            }
        } else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    bind(ctx, localAddress, future);
                }
            });
        }
        return future;
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, ChannelFuture future) {
        return connect(remoteAddress, null, future);
    }

    @Override
    public ChannelFuture connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelFuture future) {
        return connect(firstContext(ChannelHandlerType.OPERATION), remoteAddress, localAddress, future);
    }

    ChannelFuture connect(
            final DefaultChannelHandlerContext ctx, final SocketAddress remoteAddress,
            final SocketAddress localAddress, final ChannelFuture future) {
        if (remoteAddress == null) {
            throw new NullPointerException("remoteAddress");
        }
        validateFuture(future);

        EventExecutor executor = ctx.executor();
        if (executor.inEventLoop()) {
            try {
                ((ChannelOperationHandler) ctx.handler()).connect(ctx, remoteAddress, localAddress, future);
            } catch (Throwable t) {
                notifyHandlerException(t);
            }
        } else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    connect(ctx, remoteAddress, localAddress, future);
                }
            });
        }

        return future;
    }

    @Override
    public ChannelFuture disconnect(ChannelFuture future) {
        return disconnect(firstContext(ChannelHandlerType.OPERATION), future);
    }

    ChannelFuture disconnect(final DefaultChannelHandlerContext ctx, final ChannelFuture future) {
        validateFuture(future);
        EventExecutor executor = ctx.executor();
        if (executor.inEventLoop()) {
            try {
                ((ChannelOperationHandler) ctx.handler()).disconnect(ctx, future);
            } catch (Throwable t) {
                notifyHandlerException(t);
            }
        } else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    disconnect(ctx, future);
                }
            });
        }

        return future;
    }

    @Override
    public ChannelFuture close(ChannelFuture future) {
        return close(firstContext(ChannelHandlerType.OPERATION), future);
    }

    ChannelFuture close(final DefaultChannelHandlerContext ctx, final ChannelFuture future) {
        validateFuture(future);
        EventExecutor executor = ctx.executor();
        if (executor.inEventLoop()) {
            try {
                ((ChannelOperationHandler) ctx.handler()).close(ctx, future);
            } catch (Throwable t) {
                notifyHandlerException(t);
            }
        } else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    close(ctx, future);
                }
            });
        }

        return future;
    }

    @Override
    public ChannelFuture deregister(final ChannelFuture future) {
        return deregister(firstContext(ChannelHandlerType.OPERATION), future);
    }

    ChannelFuture deregister(final DefaultChannelHandlerContext ctx, final ChannelFuture future) {
        validateFuture(future);
        EventExecutor executor = ctx.executor();
        if (executor.inEventLoop()) {
            try {
                ((ChannelOperationHandler) ctx.handler()).deregister(ctx, future);
            } catch (Throwable t) {
                notifyHandlerException(t);
            }
        } else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    deregister(ctx, future);
                }
            });
        }

        return future;
    }

    @Override
    public ChannelFuture flush(ChannelFuture future) {
        return flush(firstContext(ChannelHandlerType.OPERATION), future);
    }

    ChannelFuture flush(final DefaultChannelHandlerContext ctx, final ChannelFuture future) {
        validateFuture(future);
        EventExecutor executor = ctx.executor();
        if (executor.inEventLoop()) {
            flush0(ctx, future);
        } else {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    flush(ctx, future);
                }
            });
        }

        return future;
    }

    private void flush0(final DefaultChannelHandlerContext ctx, ChannelFuture future) {
        try {
            ctx.flushBridge();
            ((ChannelOperationHandler) ctx.handler()).flush(ctx, future);
        } catch (Throwable t) {
            notifyHandlerException(t);
        } finally {
            if (ctx.outByteBuf != null) {
                ChannelBuffer buf = ctx.outByteBuf;
                if (!buf.readable()) {
                    buf.discardReadBytes();
                }
            }
        }
    }

    @Override
    public ChannelFuture write(Object message, ChannelFuture future) {
        return write(tail, message, future);
    }

    ChannelFuture write(DefaultChannelHandlerContext ctx, final Object message, final ChannelFuture future) {
        if (message == null) {
            throw new NullPointerException("message");
        }
        validateFuture(future);

        EventExecutor executor;
        boolean msgBuf = false;
        for (;;) {
            if (ctx == null) {
                throw new NoSuchBufferException();
            }

            if (ctx.hasOutboundMessageBuffer()) {
                msgBuf = true;
                executor = ctx.executor();
                break;
            }

            if (message instanceof ChannelBuffer && ctx.hasOutboundByteBuffer()) {
                executor = ctx.executor();
                break;
            }

            ctx = ctx.prev;
        }

        if (executor.inEventLoop()) {
            if (msgBuf) {
                ctx.outMsgBuf.add(message);
            } else {
                ChannelBuffer buf = (ChannelBuffer) message;
                ctx.outByteBuf.writeBytes(buf, buf.readerIndex(), buf.readableBytes());
            }
            flush0(ctx, future);
            return future;
        } else {
            final DefaultChannelHandlerContext ctx0 = ctx;
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    write(ctx0, message, future);
                }
            });
        }

        return future;
    }

    private void validateFuture(ChannelFuture future) {
        if (future == null) {
            throw new NullPointerException("future");
        }
        if (future.channel() != channel) {
            throw new IllegalArgumentException(String.format(
                    "future.channel does not match: %s (expected: %s)", future.channel(), channel));
        }
        if (future.isDone()) {
            throw new IllegalArgumentException("future already done");
        }
        if (future instanceof ChannelFuture.Unsafe) {
            throw new IllegalArgumentException("internal use only future not allowed");
        }
    }

    private DefaultChannelHandlerContext firstContext(ChannelHandlerType type) {
        if (type.direction == 0) {
            return nextContext(head.next, type);
        } else {
            return nextContext(tail, type);
        }
    }

    static DefaultChannelHandlerContext nextContext(
            DefaultChannelHandlerContext ctx, ChannelHandlerType type) {
        if (ctx == null) {
            return null;
        }

        DefaultChannelHandlerContext realCtx = ctx;
        if (type.direction == 0) {
            while (!realCtx.type.contains(type)) {
                realCtx = realCtx.next;
                if (realCtx == null) {
                    return null;
                }
            }
        } else {
            while (!realCtx.type.contains(type)) {
                realCtx = realCtx.prev;
                if (realCtx == null) {
                    return null;
                }
            }
        }
        return realCtx;
    }

    protected void notifyHandlerException(Throwable cause) {
        if (!(cause instanceof ChannelPipelineException)) {
            cause = new ChannelPipelineException(cause);
        }

        if (inExceptionCaught(cause)) {
            if (logger.isWarnEnabled()) {
                logger.warn(
                        "An exception was thrown by a user handler " +
                        "while handling an exceptionCaught event", cause);
            }
            return;
        }

        fireExceptionCaught(cause);
    }

    private static boolean inExceptionCaught(Throwable cause) {
        if (cause == null) {
            return false;
        }

        StackTraceElement[] trace = cause.getStackTrace();
        if (trace != null) {
            for (StackTraceElement t: trace) {
                if ("exceptionCaught".equals(t.getMethodName())) {
                    return true;
                }
            }
        }

        return inExceptionCaught(cause.getCause());
    }

    private void checkDuplicateName(String name) {
        if (name2ctx.containsKey(name)) {
            throw new IllegalArgumentException("Duplicate handler name: " + name);
        }
    }

    private DefaultChannelHandlerContext getContextOrDie(String name) {
        DefaultChannelHandlerContext ctx = (DefaultChannelHandlerContext) context(name);
        if (ctx == null || ctx == head) {
            throw new NoSuchElementException(name);
        } else {
            return ctx;
        }
    }

    private DefaultChannelHandlerContext getContextOrDie(ChannelHandler handler) {
        DefaultChannelHandlerContext ctx = (DefaultChannelHandlerContext) context(handler);
        if (ctx == null || ctx == head) {
            throw new NoSuchElementException(handler.getClass().getName());
        } else {
            return ctx;
        }
    }

    private DefaultChannelHandlerContext getContextOrDie(Class<? extends ChannelHandler> handlerType) {
        DefaultChannelHandlerContext ctx = (DefaultChannelHandlerContext) context(handlerType);
        if (ctx == null || ctx == head) {
            throw new NoSuchElementException(handlerType.getName());
        } else {
            return ctx;
        }
    }

    @SuppressWarnings("rawtypes")
    private final class HeadHandler implements ChannelOutboundHandler {
        @Override
        public ChannelBufferHolder newOutboundBuffer(ChannelHandlerContext ctx) throws Exception {
            switch (channel.type()) {
            case STREAM:
                return ChannelBufferHolders.byteBuffer();
            case MESSAGE:
                if (channel instanceof ServerChannel) {
                    return ChannelBufferHolders.discardMessageBuffer();
                } else {
                    return ChannelBufferHolders.messageBuffer();
                }
            default:
                throw new Error();
            }
        }

        @Override
        public void beforeAdd(ChannelHandlerContext ctx) throws Exception {
            // NOOP
        }

        @Override
        public void afterAdd(ChannelHandlerContext ctx) throws Exception {
            // NOOP
        }

        @Override
        public void beforeRemove(ChannelHandlerContext ctx) throws Exception {
            // NOOP
        }

        @Override
        public void afterRemove(ChannelHandlerContext ctx) throws Exception {
            // NOOP
        }

        @Override
        public void bind(
                ChannelHandlerContext ctx, SocketAddress localAddress, ChannelFuture future)
                throws Exception {
            unsafe.bind(localAddress, future);
        }

        @Override
        public void connect(
                ChannelHandlerContext ctx,
                SocketAddress remoteAddress, SocketAddress localAddress,
                ChannelFuture future) throws Exception {
            unsafe.connect(remoteAddress, localAddress, future);
        }

        @Override
        public void disconnect(ChannelHandlerContext ctx, ChannelFuture future) throws Exception {
            unsafe.disconnect(future);
        }

        @Override
        public void close(ChannelHandlerContext ctx, ChannelFuture future) throws Exception {
            unsafe.close(future);
        }

        @Override
        public void deregister(ChannelHandlerContext ctx, ChannelFuture future) throws Exception {
            unsafe.deregister(future);
        }

        @Override
        public void flush(ChannelHandlerContext ctx, ChannelFuture future) throws Exception {
            unsafe.flush(future);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            ctx.fireExceptionCaught(cause);
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            ctx.fireUserEventTriggered(evt);
        }
    }
}
