package com.dataanalyse.workflow.service;

import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

@Component
public class WorkflowExecutorManager {
    public static final int DEFAULT_CONCURRENCY=10;
    private static final int QUEUE_CAPACITY=1000;
    private final Map<Long,Pool> pools=new ConcurrentHashMap<>();
    public void execute(Long workflowId,int concurrency,Runnable task){Pool pool=pools.compute(workflowId,(id,current)->{int size=concurrency>0?concurrency:DEFAULT_CONCURRENCY;if(current==null)return new Pool(id,size);current.resize(size);return current;});pool.executor.execute(task);}
    int concurrency(Long workflowId){Pool pool=pools.get(workflowId);return pool==null?0:pool.concurrency;}
    @PreDestroy public void shutdown(){pools.values().forEach(pool->pool.executor.shutdown());pools.clear();}
    private static final class Pool {
        private final ThreadPoolTaskExecutor executor;private volatile int concurrency;
        private Pool(Long workflowId,int size){concurrency=size;executor=new ThreadPoolTaskExecutor();executor.setCorePoolSize(size);executor.setMaxPoolSize(size);executor.setQueueCapacity(QUEUE_CAPACITY);executor.setThreadNamePrefix("wf-"+workflowId+"-");executor.setRejectedExecutionHandler((task,pool)->{if(pool.isShutdown())throw new RejectedExecutionException("工作流线程池已关闭");try{pool.getQueue().put(task);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RejectedExecutionException("等待工作流执行队列时被中断",e);}});executor.setWaitForTasksToCompleteOnShutdown(true);executor.setAwaitTerminationSeconds(30);executor.initialize();}
        private synchronized void resize(int size){if(size==concurrency)return;if(size>concurrency){executor.setMaxPoolSize(size);executor.setCorePoolSize(size);}else{executor.setCorePoolSize(size);executor.setMaxPoolSize(size);}concurrency=size;}
    }
}
