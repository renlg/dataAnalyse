package com.dataanalyse.workflow.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowExecutorManagerTest {
    private final WorkflowExecutorManager manager=new WorkflowExecutorManager();
    @AfterEach void shutdown(){manager.shutdown();}
    @Test void workflowsUseIndependentPoolsAndSameWorkflowQueuesAtLimit() throws Exception {CountDownLatch firstStarted=new CountDownLatch(1),releaseFirst=new CountDownLatch(1),secondStarted=new CountDownLatch(1),otherWorkflowRan=new CountDownLatch(1);manager.execute(1L,1,()->{firstStarted.countDown();await(releaseFirst);});assertTrue(firstStarted.await(3,TimeUnit.SECONDS));manager.execute(1L,1,secondStarted::countDown);manager.execute(2L,1,otherWorkflowRan::countDown);assertTrue(otherWorkflowRan.await(3,TimeUnit.SECONDS));assertFalse(secondStarted.await(100,TimeUnit.MILLISECONDS));releaseFirst.countDown();assertTrue(secondStarted.await(3,TimeUnit.SECONDS));assertEquals(1,manager.concurrency(1L));assertEquals(1,manager.concurrency(2L));}
    @Test void missingConcurrencyFallsBackToTen(){manager.execute(3L,0,()->{});assertEquals(10,manager.concurrency(3L));}
    private static void await(CountDownLatch latch){try{latch.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
