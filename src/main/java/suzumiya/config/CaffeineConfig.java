package suzumiya.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
@Slf4j
public class CaffeineConfig {

    @Bean("postCache")
    public Cache<String, Object> postCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES) // 缓存过期时间
                .initialCapacity(16) // 初始的缓存空间大小（避免频繁扩容而消耗性能）
                .maximumSize(100) // 缓存的最大条数（缓存超过数量之后，Caffeine会根据缓存驱逐策略来去除某些缓存）
                .recordStats() //记录下缓存的一些统计数据，例如命中率等
                .build();
    }

    @Bean("userCache")
    public Cache<String, Object> userCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES) // 缓存过期时间
                .initialCapacity(16) // 初始的缓存空间大小（避免频繁扩容而消耗性能）
                .maximumSize(10000) // 缓存的最大条数（缓存超过数量之后，Caffeine会根据缓存驱逐策略来去除某些缓存）
                .recordStats() //记录下缓存的一些统计数据，例如命中率等
                .build();
    }
}