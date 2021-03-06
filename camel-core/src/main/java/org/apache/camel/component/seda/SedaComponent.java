/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.seda;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.impl.UriEndpointComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An implementation of the <a href="http://camel.apache.org/seda.html">SEDA components</a>
 * for asynchronous SEDA exchanges on a {@link BlockingQueue} within a CamelContext
 *
 * @version 
 */
public class SedaComponent extends UriEndpointComponent {
    protected final transient Logger log = LoggerFactory.getLogger(getClass());
    protected final int maxConcurrentConsumers = 500;
    protected int queueSize;
    protected int defaultConcurrentConsumers = 1;
    private final Map<String, QueueReference> queues = new HashMap<String, QueueReference>();

    public SedaComponent() {
        super(SedaEndpoint.class);
    }

    public void setQueueSize(int size) {
        queueSize = size;
    }
    
    public int getQueueSize() {
        return queueSize;
    }
    
    public void setConcurrentConsumers(int size) {
        defaultConcurrentConsumers = size;
    }
    
    public int getConcurrentConsumers() {
        return defaultConcurrentConsumers;
    }

    /**
     * @deprecated use {@link #getOrCreateQueue(String, Integer, Boolean)}
     */
    @Deprecated
    public synchronized QueueReference getOrCreateQueue(String uri, Integer size) {
        return getOrCreateQueue(uri, size, null);
    }

    public synchronized QueueReference getOrCreateQueue(String uri, Integer size, Boolean multipleConsumers) {
        String key = getQueueKey(uri);

        QueueReference ref = getQueues().get(key);
        if (ref != null) {

            // if the given size is not provided, we just use the existing queue as is
            if (size != null && ref.getSize() != size) {
                // there is already a queue, so make sure the size matches
                throw new IllegalArgumentException("Cannot use existing queue " + key + " as the existing queue size "
                        + (ref.getSize() != null ? ref.getSize() : Integer.MAX_VALUE) + " does not match given queue size " + size);
            }
            // add the reference before returning queue
            ref.addReference();

            if (log.isDebugEnabled()) {
                log.debug("Reusing existing queue {} with size {} and reference count {}", new Object[]{key, size, ref.getCount()});
            }
            return ref;
        }

        // create queue
        BlockingQueue<Exchange> queue;
        if (size != null && size > 0) {
            queue = new LinkedBlockingQueue<Exchange>(size);
        } else {
            if (getQueueSize() > 0) {
                size = getQueueSize();
                queue = new LinkedBlockingQueue<Exchange>(getQueueSize());
            } else {
                queue = new LinkedBlockingQueue<Exchange>();
            }
        }
        log.debug("Created queue {} with size {}", key, size);

        // create and add a new reference queue
        ref = new QueueReference(queue, size, multipleConsumers);
        ref.addReference();
        getQueues().put(key, ref);

        return ref;
    }

    public Map<String, QueueReference> getQueues() {
        return queues;
    }

    public QueueReference getQueueReference(String key) {
        return queues.get(key);
    }

    @Override
    protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) throws Exception {
        int consumers = getAndRemoveParameter(parameters, "concurrentConsumers", Integer.class, defaultConcurrentConsumers);
        boolean limitConcurrentConsumers = getAndRemoveParameter(parameters, "limitConcurrentConsumers", Boolean.class, true);
        if (limitConcurrentConsumers && consumers >  maxConcurrentConsumers) {
            throw new IllegalArgumentException("The limitConcurrentConsumers flag in set to true. ConcurrentConsumers cannot be set at a value greater than "
                    + maxConcurrentConsumers + " was " + consumers);
        }
        // defer creating queue till endpoint is started, so we pass in null
        SedaEndpoint answer = new SedaEndpoint(uri, this, null, consumers);
        answer.configureProperties(parameters);
        return answer;
    }

    public String getQueueKey(String uri) {
        if (uri.contains("?")) {
            // strip parameters
            uri = uri.substring(0, uri.indexOf('?'));
        }
        return uri;
    }

    @Override
    protected void doStop() throws Exception {
        getQueues().clear();
        super.doStop();
    }

    /**
     * On shutting down the endpoint
     * 
     * @param endpoint the endpoint
     */
    void onShutdownEndpoint(SedaEndpoint endpoint) {
        // we need to remove the endpoint from the reference counter
        String key = getQueueKey(endpoint.getEndpointUri());
        QueueReference ref = getQueues().get(key);
        if (ref != null) {
            ref.removeReference();
            if (ref.getCount() <= 0) {
                // reference no longer needed so remove from queues
                getQueues().remove(key);
            }
        }
    }

    /**
     * Holder for queue references.
     * <p/>
     * This is used to keep track of the usages of the queues, so we know when a queue is no longer
     * in use, and can safely be discarded.
     */
    public static final class QueueReference {
        
        private final BlockingQueue<Exchange> queue;
        private volatile int count;
        private Integer size;
        private Boolean multipleConsumers;

        private QueueReference(BlockingQueue<Exchange> queue, Integer size, Boolean multipleConsumers) {
            this.queue = queue;
            this.size = size;
            this.multipleConsumers = multipleConsumers;
        }
        
        void addReference() {
            count++;
        }
        
        void removeReference() {
            count--;
        }

        /**
         * Gets the reference counter
         */
        public int getCount() {
            return count;
        }

        /**
         * Gets the queue size
         *
         * @return <tt>null</tt> if unbounded
         */
        public Integer getSize() {
            return size;
        }

        public Boolean getMultipleConsumers() {
            return multipleConsumers;
        }

        /**
         * Gets the queue
         */
        public BlockingQueue<Exchange> getQueue() {
            return queue;
        }
    }
}
