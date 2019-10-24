/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.btrace.instr;

import org.openjdk.btrace.core.MethodID;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Provides a centralized place to track the fundamental metrics for
 * method execution.
 * It is mostly called from the injected code to support sampling and timing.
 *
 * @author Jaroslav Bachorik
 */
public final class MethodTracker {
    private static final RandomIntProvider rndIntProvider = RandomIntProvider.getInstance();

    private static AtomicLong[] counters = new AtomicLong[50];
    private static ThreadLocal<Long>[] tsArray = new ThreadLocal[50];
    private static Object[] rLocks = new Object[50];
    private static int[] means = new int[50];
    private static int[] origMeans = new int[50];
    private static int[] samplers = new int[50];

    /**
     * Creates a supporting structures for a new method id
     *
     * @param methodId The method id - generated by the {@linkplain MethodID} class
     * @param mean     The sampler mean or 0 if not applicable
     */
    public static synchronized void registerCounter(int methodId, int mean) {
        if (counters.length <= methodId) {
            int newLen = methodId * 2;
            counters = Arrays.copyOf(counters, newLen);
            rLocks = Arrays.copyOf(rLocks, newLen);
            means = Arrays.copyOf(means, newLen);
            origMeans = Arrays.copyOf(means, newLen);
            samplers = Arrays.copyOf(samplers, newLen);
            tsArray = Arrays.copyOf(tsArray, newLen);
        }
        if (counters[methodId] == null) {
            counters[methodId] = new AtomicLong(0);
            rLocks[methodId] = new Object();
            means[methodId] = mean * 2;
            origMeans[methodId] = mean;
            tsArray[methodId] = new ThreadLocal<Long>() {
                @Override
                protected Long initialValue() {
                    return 0L;
                }
            };
            samplers[methodId] = 0;
        }
    }

    /**
     * Records the invocation of a certain method and indicates whether
     * it should be traced or not (sampling).
     * This method will be called when using the average sampling mode.
     *
     * @param methodId The method id - generated by the {@linkplain MethodID} class
     * @return {@code true} if the invocation should be traced
     */
    public static boolean hit(int methodId) {
        int mean = means[methodId];
        if (mean == 0) {
            return true;
        }
        AtomicLong l = counters[methodId];
        if (l.getAndDecrement() <= 0) {
            int inc = rndIntProvider.nextInt(mean) + 1;
            l.addAndGet(inc);
            return true;
        }
        return false;
    }

    /**
     * Records the invocation of a certain method alongside the timestamp
     * and indicates whether it should be traced or not (sampling).
     * This method will be called when using the average sampling mode.
     *
     * @param methodId The method id - generated by the {@linkplain MethodID} class
     * @return a positive number (invocation time stamp) if the invocation should be traced
     */
    public static long hitTimed(int methodId) {
        int mean = means[methodId];
        if (mean == 0) {
            long ts = System.nanoTime();
            tsArray[methodId].set(ts);
            return ts;
        }
        AtomicLong l = counters[methodId];
        if (l.getAndDecrement() <= 0) {
            long ts = System.nanoTime();
            int inc = rndIntProvider.nextInt(mean) + 1;
            l.addAndGet(inc);
            tsArray[methodId].set(ts);
            return ts;
        }
        return 0L;
    }

    /**
     * Records the invocation of a certain method and indicates whether
     * it should be traced or not (sampling).
     * This method will be called when using the adaptive sampling mode.
     *
     * @param methodId The method id - generated by the {@linkplain MethodID} class
     * @return {@code true} if the invocation should be traced
     */
    public static boolean hitAdaptive(int methodId) {
        AtomicLong cntr = counters[methodId];
        int origMean = origMeans[methodId];
        int mean = means[methodId];
        if (cntr.getAndDecrement() <= 0) {
            long ts = System.nanoTime();
            ThreadLocal<Long> tsRef = tsArray[methodId];
            long ts1 = tsRef.get();
            if (ts1 != 0) {
                long diff = ts - ts1;
                if (mean < 1500 && diff < origMean) {
                    synchronized (rLocks[methodId]) {
                        means[methodId] = ++mean;
                    }
                } else if (mean > 1 && diff > origMean) {
                    synchronized (rLocks[methodId]) {
                        means[methodId] = --mean;
                    }
                }
            }
            tsRef.set(ts);

            int inc = rndIntProvider.nextInt(mean) + 1;
            cntr.addAndGet(inc);

            return true;
        }
        return false;
    }

    /**
     * Records the invocation of a certain method alongside the timestamp
     * and indicates whether it should be traced or not (sampling).
     * This method will be called when using the adaptive sampling mode.
     *
     * @param methodId The method id - generated by the {@linkplain MethodID} class
     * @return a positive number (invocation time stamp) if the invocation should be traced
     */
    public static long hitTimedAdaptive(int methodId) {
        AtomicLong cntr = counters[methodId];
        int mean = means[methodId];
        int origMean = origMeans[methodId];
        if (cntr.getAndDecrement() <= 0) {
            long ts = System.nanoTime();
            ThreadLocal<Long> tsRef = tsArray[methodId];
            long ts1 = tsRef.get();
            if (ts1 != 0) {
                long diff = ts - ts1;
                if (mean < 1500 && diff < origMean) {
                    synchronized (rLocks[methodId]) {
                        means[methodId] = ++mean;
                    }
                } else if (mean > 1 && diff > origMean) {
                    synchronized (rLocks[methodId]) {
                        means[methodId] = --mean;
                    }
                }
            }
            tsRef.set(ts);

            int inc = rndIntProvider.nextInt(mean) + 1;
            cntr.addAndGet(inc);

            return ts;
        }
        return 0L;
    }

    /**
     * Used when timing the method execution or in adaptive sampling.
     * To be used at the end of the sampled block.
     *
     * @param methodId The method id generated by {@linkplain MethodID} class
     * @return The time stamp
     */
    public static long getEndTs(int methodId) {
        long ts = System.nanoTime();
        tsArray[methodId].set(ts);
        return ts;
    }

    /**
     * Used in adaptive sampling.
     * To be used at the end of the sampled block.
     *
     * @param methodId The method id generated by {@linkplain MethodID} class
     */
    public static void updateEndTs(int methodId) {
        tsArray[methodId].set(System.nanoTime());
    }
}
