package com.hql.lightning.handler;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.hql.lightning.buffer.GameUpBuffer;
import com.hql.lightning.channel.GameChannelManager;
import com.hql.lightning.core.Connection;
import com.hql.lightning.core.ConnectionManager;
import com.hql.lightning.core.GameBoss;
import com.hql.lightning.util.ProReaderUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.util.AttributeKey;
import io.netty.util.CharsetUtil;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;

/**
 * 数据包处理类
 *
 * @author lee
 *         2015-1-30
 */
public class ServerHandler extends SimpleChannelInboundHandler<Object> {

    private static Logger logger = Logger.getLogger(ServerHandler.class);

    private WebSocketServerHandshaker handshaker;

    /**
     * 连接属性
     */
    private static final AttributeKey<Connection> conn = AttributeKey.valueOf("Conn.attr");

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        //传统http数据包接入
        if (msg instanceof FullHttpRequest) {
            handleHttpRequest(ctx, (FullHttpRequest) msg);
        }

        //WebSocket数据包接入
        else if (msg instanceof WebSocketFrame) {
            handleWebSocketFrame(ctx, (WebSocketFrame) msg);
        }
    }

    /**
     * 处理传统http消息
     *
     * @param ctx
     * @param req
     */
    private void handleHttpRequest(ChannelHandlerContext ctx, FullHttpRequest req) {
        //http解析失败，返回http异常
        if (!req.getDecoderResult().isSuccess()) {
            sendHttpResponse(ctx, req, new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.BAD_REQUEST));
            return;
        }

        //构造握手响应返回
        WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
                "ws://" + ProReaderUtil.getInstance().getNettyPro().get("host") + ":" +
                        ProReaderUtil.getInstance().getNettyPro().get("port"), null, false
        );

        handshaker = wsFactory.newHandshaker(req);
        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
        } else {
            handshaker.handshake(ctx.channel(), req);
        }
    }

    /**
     * 处理WebSocket消息
     *
     * @param ctx
     * @param frame
     */
    private void handleWebSocketFrame(ChannelHandlerContext ctx, WebSocketFrame frame) {
        //判断是否是关闭链路指令
        if (frame instanceof CloseWebSocketFrame) {
            handshaker.close(ctx.channel(), (CloseWebSocketFrame) frame.retain());
            return;
        }

        //判断是否是Ping消息
        if (frame instanceof PingWebSocketFrame) {
            ctx.channel().write(new PongWebSocketFrame(frame.content().retain()));
            return;
        }

        //判断是否是文本消息
        if (!(frame instanceof TextWebSocketFrame)) {
            throw new UnsupportedOperationException(String.format("%s frame types not supported.",
                    frame.getClass().getName()));
        }

        //获取消息数据
        String request = ((TextWebSocketFrame) frame).text();

        //处理心跳响应
        if (request.equals("heartBeat")) {
            ctx.writeAndFlush(new TextWebSocketFrame(request));
            return;
        }

        JSONObject json = null;
        try {
            json = (JSONObject) JSON.parse(request);
        } catch (Exception e) {
            logger.error(e.getMessage());
        }

        GameBoss.getInstance().getProcessor().process(new GameUpBuffer(json, ctx.attr(conn).get()));
    }

    /**
     * 返回应答给客户端
     *
     * @param ctx
     * @param req
     * @param res
     */
    private static void sendHttpResponse(ChannelHandlerContext ctx, FullHttpRequest req, FullHttpResponse res) {
        if (res.getStatus().code() != 200) {
            ByteBuf buf = Unpooled.copiedBuffer(res.getStatus().toString(), CharsetUtil.UTF_8);
            res.content().writeBytes(buf);
            buf.release();
            HttpHeaders.setContentLength(res, res.content().readableBytes());
        }

        ChannelFuture f = ctx.channel().writeAndFlush(res);
        //非keep-alive，关闭连接
        if (!HttpHeaders.isKeepAlive(req) || res.getStatus().code() != 200) {
            f.addListener(ChannelFutureListener.CLOSE);
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        Connection c = ConnectionManager.getInstance().addConnection(ctx);
        ctx.attr(conn).set(c);
        GameChannelManager.getInstance().addChannel("allUser").addConnection(c);
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        ConnectionManager.getInstance().removeConnection(ctx.attr(conn).get());
        GameChannelManager.getInstance().getChannel("allUser").removeConnection(ctx.attr(conn).get());

        String evt = "{\"cmd\":\"onDisconnect\", \"module\":\"disconnectEvent\", \"data\":{}}";
        JSONObject json = (JSONObject) JSON.parse(evt);
        GameBoss.getInstance().getProcessor().process(new GameUpBuffer(json, ctx.attr(conn).get()));
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error(cause.getMessage());

        if (cause.getCause() instanceof ReadTimeoutException) {
            ctx.close();
            return;
        }

        if (cause.getCause() instanceof TooLongFrameException) {
            ctx.close();
            return;
        }

        if (cause.getCause() instanceof ClosedChannelException) {
            return;
        }

        if (cause.getCause() instanceof IOException) {
            ctx.close();
            return;
        }
        super.exceptionCaught(ctx, cause);
    }
}
