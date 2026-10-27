package com.factory.hub.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 推送端。前端连 /ws 就能收到实时数据。
 * <p>
 * 用裸 WebSocket 而不是 STOMP：前端不用装任何库，十来行原生 JS 就能接，
 * 客户现场就算断网重连也好处理。
 */
@Component
public class WsHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(WsHandler.class);

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("WebSocket 接入，当前连接数 {}", sessions.size());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("WebSocket 断开，当前连接数 {}", sessions.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        sessions.remove(session);
        log.debug("WebSocket 异常: {}", exception.getMessage());
    }

    /** 广播给所有前端。单条发送失败不影响其他连接 */
    public void broadcast(String payload) {
        if (sessions.isEmpty()) {
            return;
        }
        TextMessage msg = new TextMessage(payload);
        for (WebSocketSession s : sessions) {
            try {
                if (s.isOpen()) {
                    synchronized (s) {
                        s.sendMessage(msg);
                    }
                }
            } catch (Exception e) {
                sessions.remove(s);
            }
        }
    }

    public int connections() {
        return sessions.size();
    }
}
