package org.wso2.siddhi.core.debugger;

import org.wso2.siddhi.core.event.ComplexEventChunk;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Created by bhagya on 7/20/16.
 */
public class SiddhiBreakPoint {
    public LinkedList<Object> listOfEvents = new LinkedList<Object>();
    public Semaphore breakPointLock = new Semaphore(0);
    public Lock queryBlockingLock = new ReentrantLock();
    public HashMap<String, AtomicBoolean> acquiredBreakPointsMap = new HashMap<String, AtomicBoolean>();
    public String queryName;
    public volatile AtomicBoolean enableNext = new AtomicBoolean(false);
    private SiddhiDebuggerCallback siddhiDebuggerCallback;

    public SiddhiBreakPoint() {

    }

    public synchronized void clearEventList() {
        listOfEvents.clear();
    }

    public void checkBreakPoint(String queryName, SiddhiDebugger.Discription breakpointType, Object... currentEvents) {
        AtomicBoolean breakpoint = acquiredBreakPointsMap.get(queryName + breakpointType.name());
        AtomicBoolean currentQuery = Process.get();
        boolean isNext = currentQuery.get();
        currentQuery.set(false);
        if (breakpoint != null && breakpoint.get() || isNext) {
            queryBlockingLock.lock();
            this.queryName = queryName + breakpointType.name();
            for (int i = 0; i < currentEvents.length; i++) {
                if ((currentEvents[i] instanceof ComplexEventChunk)) {
                    ComplexEventChunk complexEvent = (ComplexEventChunk) currentEvents[i];
                    Object[] outputData = complexEvent.getLast().getOutputData();
                    for (Object data : outputData) {
                        this.listOfEvents.add(data);
                    }

                } else {
                    this.listOfEvents.add(currentEvents[i]);
                }

            }
            siddhiDebuggerCallback.debuggedEvent(queryName+breakpointType.name(), listOfEvents.toArray());
            try {
                breakPointLock.acquire();
                this.clearEventList();
                if (enableNext.get()) {
                    currentQuery.set(true);
                    enableNext.set(false);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                queryBlockingLock.unlock();
            }

        }
    }

    public void next() {
        if (listOfEvents.size() != 0) {
            this.enableNext.set(true);
            this.breakPointLock.release();
        }
    }

    public void play() {
        if (listOfEvents.size() != 0) {
            this.breakPointLock.release();
        }
    }

    public void setDebuggerCallback(SiddhiDebuggerCallback siddhiDebuggerCallback) {
        this.siddhiDebuggerCallback = siddhiDebuggerCallback;
    }

    public void acquireBreakPoint(String queryName, SiddhiDebugger.Discription breakpointType) {
        AtomicBoolean breakPointLock = acquiredBreakPointsMap.get(queryName+breakpointType.name());
        if (breakPointLock == null) {
            breakPointLock = new AtomicBoolean(true);
            acquiredBreakPointsMap.put(queryName+breakpointType.name(), breakPointLock);
        }
    }

    public void releaseBreakPoint(String queryName, SiddhiDebugger.Discription breakpointType) {

        AtomicBoolean breakPointLock = acquiredBreakPointsMap.get(queryName+breakpointType);
        if (breakPointLock != null) {
            breakPointLock.set(false);
        }
    }

}
