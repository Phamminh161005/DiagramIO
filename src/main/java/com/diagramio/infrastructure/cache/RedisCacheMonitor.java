package com.diagramio.infrastructure.cache;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Theo dõi Redis cho {@link DiagramCache} và nhận thông báo thay đổi từ instance khác.
 *
 * <p>Mỗi {@code interval}: có L1 thì gửi một heartbeat qua kênh pub/sub và coi Redis là dùng được khi đã nhận lại
 * heartbeat gần đây của chính mình (lệnh ghi chạy được và thông báo hủy L1 tới được nơi); chỉ có L2 thì PING.
 * Kết quả được báo cho cache: không dùng được thì cache đọc thẳng CSDL, dùng lại được thì cache dọn dẹp trước khi
 * dùng lại. Như vậy khoảng thời gian một instance có thể dùng L1 đã lỡ thông báo hủy bị chặn ở vài nhịp theo dõi.
 */
public final class RedisCacheMonitor implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RedisCacheMonitor.class);
    /** Heartbeat nhận được phải không cũ hơn chừng này nhịp. */
    private static final long MAX_HEARTBEAT_LAG = 2;

    private final DiagramCache cache;
    private final RedisDiagramTier tier;
    private final RedisConnectionFactory connectionFactory;
    private final boolean listenForChanges;
    private final Duration interval;
    private final AtomicLong sent = new AtomicLong();
    private volatile long received;
    private volatile boolean running;
    private RedisMessageListenerContainer container;
    private ScheduledExecutorService scheduler;

    public RedisCacheMonitor(DiagramCache cache, RedisDiagramTier tier, RedisConnectionFactory connectionFactory,
                             boolean listenForChanges, Duration interval) {
        this.cache = cache;
        this.tier = tier;
        this.connectionFactory = connectionFactory;
        this.listenForChanges = listenForChanges;
        this.interval = interval;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (listenForChanges) {
            container = new RedisMessageListenerContainer();
            container.setConnectionFactory(connectionFactory);
            container.setRecoveryInterval(interval.toMillis());
            container.setErrorHandler(e -> log.debug("Lỗi kênh pub/sub của cache sơ đồ: {}", e.toString()));
            container.addMessageListener((Message message, byte[] pattern) -> onMessage(message.getBody()),
                    new ChannelTopic(RedisDiagramTier.CHANGES_CHANNEL));
            container.afterPropertiesSet();
            container.start();
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "diagram-cache-monitor");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::check, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        running = true;
    }

    void onMessage(byte[] body) {
        Object message = RedisDiagramTier.parseMessage(body);
        if (message instanceof RedisDiagramTier.Change change) {
            cache.onRemoteChange(change);
        } else if (message instanceof RedisDiagramTier.Heartbeat heartbeat
                && heartbeat.origin().equals(cache.instanceId()) && heartbeat.sequence() > received) {
            received = heartbeat.sequence();
        }
    }

    void check() {
        boolean available;
        try {
            if (listenForChanges) {
                long sequence = sent.incrementAndGet();
                tier.publishHeartbeat(cache.instanceId(), sequence);
                available = received > 0 && received >= sequence - MAX_HEARTBEAT_LAG;
            } else {
                available = tier.ping();
            }
        } catch (RuntimeException e) {
            available = false;
        }
        try {
            cache.remoteAvailable(available);
        } catch (RuntimeException e) {
            log.warn("Lỗi khi cập nhật trạng thái Redis của cache sơ đồ", e);
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        scheduler.shutdownNow();
        if (container != null) {
            try {
                container.stop();
                container.destroy();
            } catch (Exception e) {
                log.debug("Dừng kênh pub/sub của cache sơ đồ: {}", e.toString());
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
