package ru.lct.teplokontur.config;
import org.springframework.context.annotation.*;import org.springframework.scheduling.annotation.*;import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;import java.util.concurrent.Executor;
@Configuration @EnableAsync
public class AsyncConfig {@Bean(name="optimizerExecutor")public Executor optimizerExecutor(){ThreadPoolTaskExecutor e=new ThreadPoolTaskExecutor();e.setCorePoolSize(1);e.setMaxPoolSize(2);e.setQueueCapacity(100);e.setThreadNamePrefix("optimizer-");e.initialize();return e;}}
