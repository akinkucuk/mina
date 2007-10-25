/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *  
 *    http://www.apache.org/licenses/LICENSE-2.0
 *  
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License. 
 *  
 */
package org.apache.mina.transport.socket.apr;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Executor;

import org.apache.mina.common.AbstractIoProcessor;
import org.apache.mina.common.AbstractIoSession;
import org.apache.mina.common.IoBuffer;
import org.apache.mina.common.FileRegion;
import org.apache.mina.common.IoSession;
import org.apache.tomcat.jni.Error;
import org.apache.tomcat.jni.Poll;
import org.apache.tomcat.jni.Pool;
import org.apache.tomcat.jni.Socket;
import org.apache.tomcat.jni.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The class in charge of processing socket level IO events for the {@link AprConnector}
 * 
 * @author The Apache MINA Project (dev@mina.apache.org)
 * @version $Rev$, $Date$
 */

class AprIoProcessor extends AbstractIoProcessor {

    protected static class IoSessionIterator implements Iterator<AbstractIoSession> {
        private final Iterator<AprSessionImpl> i;
        private IoSessionIterator(Collection<AprSessionImpl> sessions) {
            i = sessions.iterator();
        }
        public boolean hasNext() {
            return i.hasNext();
        }

        public AbstractIoSession next() {
            AprSessionImpl sess = i.next();
            return (AbstractIoSession) sess;
        }

        public void remove() {
            i.remove();
        }
    }

    protected class PollSetIterator implements Iterator<AbstractIoSession> {
        private long[] pollResult;
        
        int index=0;
        public PollSetIterator(long[] pollResult) {
            this.pollResult=pollResult;
        }

        public boolean hasNext() {
            return index*2< pollResult.length;
        }

        public AbstractIoSession next() {
            AbstractIoSession  sess=managedSessions.get(pollResult[index*2+1]);
            index++;
            System.err.println("sess : "+((AprSessionImpl)sess).getAPRSocket());
            return sess;
        }

        public void remove() {
            //throw new UnsupportedOperationException("remove");            
        }
    }

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private long pool = 0; // memory pool

    private long pollset = 0; // socket poller

    private final Map<Long, AprSessionImpl> managedSessions = new HashMap<Long, AprSessionImpl>();

    private long[] pollResult;

    AprIoProcessor(String threadName, Executor executor) {
        super(threadName,executor);
            

        // initialize a memory pool for APR functions
        pool = Pool.create(AprLibrary.getLibrary().getPool());
        try {

            // TODO : optimize/parameterize those values
            pollset = Poll
                    .create(
                            32,
                            pool,
                            Poll.APR_POLLSET_THREADSAFE /* enable poll thread safeness */,
                            10000000);

        } catch (Error e) {
            logger.error("APR Error : " + e.getDescription(), e);
        }
    }

    @Override
    protected Iterator<AbstractIoSession> allSessions() throws Exception {
        return new IoSessionIterator(managedSessions.values());
    }
    

    @Override
    protected void doAdd(IoSession sess) throws Exception {
        logger.debug("doAdd");
        AprSessionImpl session = (AprSessionImpl) sess;
        int rv;
        rv = Poll.add(pollset, session.getAPRSocket(), Poll.APR_POLLIN);
        if (rv == Status.APR_SUCCESS) {
            logger.debug("sesion added to pollset");
            session.setOpRead(true);
            managedSessions.put(session.getAPRSocket(), session);
        } else
            throw new RuntimeException("APR error while Poll.add(..) : "+Error.strerror(-1*rv)+" ( code : "+rv+")");
    }
                 
    @Override
    protected void doRemove(IoSession session) throws Exception {
        logger.debug("doRemove");
        int ret=Poll.remove(pollset, ((AprSessionImpl)session).getAPRSocket());
        if(ret!=Status.APR_SUCCESS) {
            logger.error("removing of pollset error");
        }
        ret=Socket.close(((AprSessionImpl)session).getAPRSocket());
        if(ret!=Status.APR_SUCCESS) {
            logger.error("closing socket error");
        }

    }

    @Override
    protected void finalize() throws Throwable {
        // TODO : necessary I think, need to check APR doc
        logger.debug("finalize, freeing the pool");
        Pool.clear(pool);
    }

    @Override
    protected boolean isOpRead(IoSession sess) throws Exception {
        logger.debug("isOpRead?");
        AprSessionImpl session=(AprSessionImpl)sess;
        logger.debug("isOpRead : "+session.isOpRead());
        return session.isOpRead();
    }

    @Override
    protected boolean isOpWrite(IoSession sess) throws Exception {
        logger.debug("isOpWrite?");
        AprSessionImpl session=(AprSessionImpl)sess;
        logger.debug("isOpWrite : "+session.isOpWrite());
        return session.isOpWrite();
    }

    @Override
    protected boolean isReadable(IoSession session) throws Exception {
        logger.debug("isReadable?");
        long socket=((AprSessionImpl)session).getAPRSocket();
        for(int i=0;i<pollResult.length/2;i++) {
            if(pollResult[i*2+1]==socket) {
                if( (pollResult[i*2]&Poll.APR_POLLIN) >0 ) {
                    logger.debug("isReadable : true");
                    return true;
                } else {
                    logger.debug("isReadable : false");
                    return false;
                }
            }
        }
        logger.debug("isReadable : false (socket not found)");
        return false;
    }

    @Override
    protected boolean isWritable(IoSession session) throws Exception {
        logger.debug("isWritable?");
        long socket=((AprSessionImpl)session).getAPRSocket();
        for(int i=0;i<pollResult.length/2;i++) {
            if(pollResult[i*2+1]==socket) {
                if( (pollResult[i*2]&Poll.APR_POLLOUT) >0 ) {
                    logger.debug("isWritable : true");
                    return true;
                } else {
                    logger.debug("isWritable : false");   
                    return false;
                }
            }
        }
        logger.debug("isWritable : false (socket not found)");
        return false;
    }
    
    @Override
    protected int read(IoSession sess, IoBuffer buffer) throws Exception {
        logger.debug("read");
        AprSessionImpl session=(AprSessionImpl)sess;
        
        byte[] buf = session.getReadBuffer();
        // FIXME : hardcoded read value for testing
        int bytes = Socket.recv(session.getAPRSocket(), buf, 0, 1024);
        logger.debug("read bytes : "+bytes);
        if (bytes > 0) {
            buffer.put(buf);
        }
        return bytes;
    }

    @Override
    protected boolean select(int timeout) throws Exception {
        logger.debug("select?");
        // poll the socket descriptors
        /* is it OK ? : Two times size of the created pollset */
        pollResult = new long[managedSessions.size() * 2];

        int rv = Poll.poll(pollset, 1000 * timeout, pollResult, false);
        if (rv > 0) {
            logger.debug("select : true");    
            return true;
        } else if(rv<0) {
            if(rv!=-120001) { // timeout ( FIXME : can't find the good constant in APR)
                System.err.println("APR Poll error : "+Error.strerror(-1*rv)+" "+rv);
                throw new RuntimeException("APR polling error : "+Error.strerror(-1*rv)+" ( code : "+rv+")");
            }
        }
        logger.debug("select : false");
        return false;
    }

    @Override
    protected Iterator<AbstractIoSession> selectedSessions() throws Exception {
        return new PollSetIterator(pollResult);
    }

    @Override
    protected void setOpRead(IoSession sess, boolean value) throws Exception {
        logger.debug("setOpRead : "+value);
        AprSessionImpl session=(AprSessionImpl)sess;
        int rv = Poll.remove(pollset, session.getAPRSocket());
        if (rv != Status.APR_SUCCESS) {
            System.err.println("poll.remove Error : " + Error.strerror(rv));
        }
        
        int flags=(value?Poll.APR_POLLIN:0) | (session.isOpWrite()?Poll.APR_POLLOUT:0);
        
        rv = Poll.add(pollset, session.getAPRSocket(), flags);
        
        if (rv == Status.APR_SUCCESS) {
            // ok
            session.setOpRead(value);
        } else {
            logger.error("poll.add Error : " + Error.strerror(rv));
        }
    }

    @Override
    protected void setOpWrite(IoSession sess, boolean value)
            throws Exception {
        logger.debug("setOpWrite : "+value);
        AprSessionImpl session=(AprSessionImpl)sess;
        int rv = Poll.remove(pollset, session.getAPRSocket());
        if (rv != Status.APR_SUCCESS) {
            System.err.println("poll.remove Error : " + Error.strerror(rv));
        }
        
        int flags=(session.isOpRead()?Poll.APR_POLLIN:0) | (value?Poll.APR_POLLOUT:0);
        
        rv = Poll.add(pollset, session.getAPRSocket(), flags);
        
        if (rv == Status.APR_SUCCESS) {
            // ok
            session.setOpWrite(value);
        } else {
            logger.error("poll.add Error : " + Error.strerror(rv));
        }
    }

    @Override
    protected SessionState state(IoSession session) {
        logger.debug("state?");
        long socket=((AprSessionImpl)session).getAPRSocket();
        if(socket>0)
            return SessionState.OPEN;
        else if(managedSessions.get(socket)!=null)
            return SessionState.PREPARING; // will occur ?
        else
            return SessionState.CLOSED;
    }

    @Override
    protected long transferFile(IoSession session, FileRegion region)
            throws Exception {
        throw new UnsupportedOperationException("Not supposed for APR (TODO)");
    }

    @Override
    protected void wakeup() {
        logger.debug("wakeup");
        // FIXME : is it possible to interrupt a Poll.poll ?
        
    }
    
    @Override
    protected int write(IoSession session, IoBuffer buf) throws Exception {
        logger.debug("write");
        // be sure APR_SO_NONBLOCK was set, or it will block
        int toWrite = buf.remaining();

        int writtenBytes;
        // APR accept ByteBuffer, only if they are Direct ones, due to native code
        if (buf.isDirect()) {
            writtenBytes = Socket.sendb( ((AprSessionImpl)session).getAPRSocket(), buf.buf(),
                    0, toWrite);
        } else {
            writtenBytes = Socket.send( ((AprSessionImpl)session).getAPRSocket(), buf.array(),
                    0, toWrite);
            // FIXME : kludgy ?
            buf.position(buf.position() + writtenBytes);
        }
        logger.debug("write : "+writtenBytes);
        return writtenBytes;
    }
}