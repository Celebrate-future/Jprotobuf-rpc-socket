/*
 * Copyright 2002-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.baidu.jprotobuf.pbrpc.data;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.baidu.jprotobuf.pbrpc.AuthenticationDataHandler;
import com.baidu.jprotobuf.pbrpc.ClientAttachmentHandler;
import com.baidu.jprotobuf.pbrpc.LogIDGenerator;
import com.baidu.jprotobuf.pbrpc.client.RpcMethodInfo;
import com.baidu.jprotobuf.pbrpc.utils.ArrayUtils;
import com.baidu.jprotobuf.pbrpc.utils.LogIdThreadLocalHolder;

/**
 * RPC 包数据完整定义实现.
 * 
 * Data package for baidu RPC. all request and response data package should apply this.
 * 
 * <pre>
 * -----------------------------------
 * | Head | Meta | Data | Attachment |
 * -----------------------------------
 * 
 * 1. <Head> with fixed 12 byte length as follow format
 * ----------------------------------------------
 * | PRPC | TotalSize(int32) | MetaSize(int32) |
 * ----------------------------------------------
 * TotalSize = totalSize
 * MetaSize = Meta object size
 * 
 * 2. <Meta> body proto description as follow
 * message RpcMeta {
 *     RpcRequestMeta request = 1;
 *     RpcResponseMeta response = 2;
 *     int32 compress_type = 3; // 0:nocompress 1:Snappy 2:gzip
 *     int64 correlation_id = 4;
 *     int32 attachment_size = 5;
 *     ChunkInfo chuck_info = 6;
 *     bytes authentication_data = 7;
 * };
 * 
 * message Request {
 *     required string service_name = 1;
 *     required string method_name = 2;
 *     int64 log_id = 3;
 *     int64 traceId = 4;
 *     int64 spanId = 5;
 *     int64 parentSpanId = 6;
 *     repeated RpcRequestMetaExtField rpcRequestMetaExt = 7;
 *     
 *     bytes extraParam = 110;
 * };
 * 
 * message Response {
 *     int32 error_code = 1;
 *     string error_text = 2;
 * };
 * 
 * message ChunkInfo {
 *     required int64 stream_id = 1;
 *     required int64 chunk_id = 2;
 * };
 * 
 * message RpcRequestMetaExtField {
 *     string key = 1;
 *     string value = 2;
 * };
 * 
 * 3. <Data> customize transport data message.
 * 
 * 4. <Attachment> attachment body data message
 * </pre>
 * 
 * @author xiemalin
 * @since 1.0
 */
public class RpcDataPackage implements Readable, Writerable {

    /** The log. */
    private static Logger LOG = Logger.getLogger(RpcDataPackage.class.getName());

    /** The head. */
    private RpcHeadMeta head;

    /** The rpc meta. */
    private RpcMeta rpcMeta;

    /** The data. */
    private byte[] data;

    /** The attachment. */
    private byte[] attachment;

    /** The time stamp. */
    private long timeStamp;

    /**
     * Merge data.
     *
     * @param data the data
     */
    public void mergeData(byte[] data) {
        if (data == null) {
            return;
        }
        if (this.data == null) {
            this.data = data;
            return;
        }

        int len = this.data.length + data.length;
        byte[] newData = new byte[len];
        System.arraycopy(this.data, 0, newData, 0, this.data.length);
        System.arraycopy(data, 0, newData, this.data.length, data.length);
        this.data = newData;
    }

    /**
     * Checks if is chunk package.
     *
     * @return true, if is chunk package
     */
    public boolean isChunkPackage() {
        return getChunkStreamId() != null;
    }

    /**
     * Checks if is final package.
     *
     * @return true, if is final package
     */
    public boolean isFinalPackage() {
        if (rpcMeta == null) {
            return true;
        }
        ChunkInfo chunkInfo = rpcMeta.getChunkInfo();
        if (chunkInfo == null) {
            return true;
        }

        return chunkInfo.getChunkId() == -1;
    }

    /**
     * Gets the chunk stream id.
     *
     * @return the chunk stream id
     */
    public Long getChunkStreamId() {
        if (rpcMeta == null) {
            return null;
        }
        ChunkInfo chunkInfo = rpcMeta.getChunkInfo();
        if (chunkInfo == null) {
            return null;
        }

        return chunkInfo.getStreamId();
    }

    /**
     * To split current {@link RpcDataPackage} by chunkSize. if chunkSize great than data length will not do split.<br>
     * {@link List} return will never be {@code null} or empty.
     * 
     * @param chunkSize target size to split
     * @return {@link List} of {@link RpcDataPackage} after split
     */
    public List<RpcDataPackage> chunk(long chunkSize) {
        if (chunkSize < 1 || data == null || chunkSize > data.length) {
            return Arrays.asList(this);
        }

        long streamId = UUID.randomUUID().toString().hashCode();
        int chunkId = 0;
        int startPos = 0;

        int cSize = Long.valueOf(chunkSize).intValue();
        List<RpcDataPackage> ret = new ArrayList<RpcDataPackage>();
        while (startPos < data.length) {
            byte[] subarray = ArrayUtils.subarray(data, startPos, startPos + cSize);
            RpcDataPackage clone = copy();
            clone.data(subarray);
            if (startPos > 0) {
                clone.attachment(null);
            }
            startPos += cSize;
            if (startPos >= data.length) {
                chunkId = -1;
            }
            clone.chunkInfo(streamId, chunkId);
            ret.add(clone);
            chunkId++;

        }

        return ret;
    }

    /**
     * Copy.
     *
     * @return the rpc data package
     */
    public RpcDataPackage copy() {
        RpcDataPackage rpcDataPackage = new RpcDataPackage();
        if (head != null) {
            rpcDataPackage.setHead(head.copy());
        }

        if (rpcMeta != null) {
            rpcDataPackage.setRpcMeta(rpcMeta.copy());
        }

        rpcDataPackage.setData(data);
        rpcDataPackage.setAttachment(attachment);

        return rpcDataPackage;
    }

    /**
     * set magic code.
     *
     * @param magicCode the magic code
     * @return the rpc data package
     */
    public RpcDataPackage magicCode(String magicCode) {
        setMagicCode(magicCode);
        return this;
    }

    /**
     * Service name.
     *
     * @param serviceName the service name
     * @return the rpc data package
     */
    public RpcDataPackage serviceName(String serviceName) {
        RpcRequestMeta request = initRequest();
        request.setServiceName(serviceName);
        return this;
    }

    /**
     * Service name.
     *
     * @return the string
     */
    public String serviceName() {
        RpcRequestMeta request = initRequest();
        return request.getServiceName();
    }

    /**
     * Method name.
     *
     * @return the string
     */
    public String methodName() {
        RpcRequestMeta request = initRequest();
        return request.getMethodName();
    }

    /**
     * Method name.
     *
     * @param methodName the method name
     * @return the rpc data package
     */
    public RpcDataPackage methodName(String methodName) {
        RpcRequestMeta request = initRequest();
        request.setMethodName(methodName);
        return this;
    }

    /**
     * Data.
     *
     * @param data the data
     * @return the rpc data package
     */
    public RpcDataPackage data(byte[] data) {
        setData(data);
        return this;
    }

    /**
     * Attachment.
     *
     * @param attachment the attachment
     * @return the rpc data package
     */
    public RpcDataPackage attachment(byte[] attachment) {
        setAttachment(attachment);
        return this;
    }

    /**
     * Authentication data.
     *
     * @param authenticationData the authentication data
     * @return the rpc data package
     */
    public RpcDataPackage authenticationData(byte[] authenticationData) {
        RpcMeta rpcMeta = initRpcMeta();
        rpcMeta.setAuthenticationData(authenticationData);
        return this;
    }

    /**
     * Correlation id.
     *
     * @param correlationId the correlation id
     * @return the rpc data package
     */
    public RpcDataPackage correlationId(long correlationId) {
        RpcMeta rpcMeta = initRpcMeta();
        rpcMeta.setCorrelationId(correlationId);
        return this;
    }

    /**
     * Compress type.
     *
     * @param compressType the compress type
     * @return the rpc data package
     */
    public RpcDataPackage compressType(int compressType) {
        RpcMeta rpcMeta = initRpcMeta();
        rpcMeta.setCompressType(compressType);
        return this;
    }

    /**
     * Log id.
     *
     * @param logId the log id
     * @return the rpc data package
     */
    public RpcDataPackage logId(long logId) {
        RpcRequestMeta request = initRequest();
        request.setLogId(logId);
        return this;
    }

    /**
     * Error code.
     *
     * @param errorCode the error code
     * @return the rpc data package
     */
    public RpcDataPackage errorCode(int errorCode) {
        RpcResponseMeta response = initResponse();
        response.setErrorCode(errorCode);
        return this;
    }

    /**
     * Error text.
     *
     * @param errorText the error text
     * @return the rpc data package
     */
    public RpcDataPackage errorText(String errorText) {
        RpcResponseMeta response = initResponse();
        response.setErrorText(errorText);
        return this;
    }

    /**
     * Extra params.
     *
     * @param params the params
     * @return the rpc data package
     */
    public RpcDataPackage extraParams(byte[] params) {
        RpcRequestMeta request = initRequest();
        request.setExtraParam(params);
        return this;
    }

    /**
     * Chunk info.
     *
     * @param streamId the stream id
     * @param chunkId the chunk id
     * @return the rpc data package
     */
    public RpcDataPackage chunkInfo(long streamId, int chunkId) {
        ChunkInfo chunkInfo = new ChunkInfo();
        chunkInfo.setStreamId(streamId);
        chunkInfo.setChunkId(chunkId);
        RpcMeta rpcMeta = initRpcMeta();
        rpcMeta.setChunkInfo(chunkInfo);
        return this;
    }

    /**
     * set Trace value.
     *
     * @param trace the trace
     * @return the rpc data package
     */
    public RpcDataPackage trace(Trace trace) {
        RpcRequestMeta request = initRequest();
        request.setTraceId(trace.getTraceId());
        request.setTraceKey(trace.getTraceKey());
        request.setSpanId(trace.getSpanId());
        request.setParentSpanId(trace.getParentSpanId());
        return this;
    }

    /**
     * Trace.
     *
     * @return the trace
     */
    public Trace trace() {
        RpcRequestMeta request = initRequest();
        Trace trace = new Trace(request.getTraceId(), request.getTraceKey(), 
                request.getSpanId(), request.getParentSpanId());
        return trace;
    }

    /**
     * Inits the request.
     *
     * @return the rpc request meta
     */
    private RpcRequestMeta initRequest() {
        RpcMeta rpcMeta = initRpcMeta();

        RpcRequestMeta request = rpcMeta.getRequest();
        if (request == null) {
            request = new RpcRequestMeta();
            rpcMeta.setRequest(request);
        }

        return request;
    }

    /**
     * Inits the response.
     *
     * @return the rpc response meta
     */
    private RpcResponseMeta initResponse() {
        RpcMeta rpcMeta = initRpcMeta();

        RpcResponseMeta response = rpcMeta.getResponse();
        if (response == null) {
            response = new RpcResponseMeta();
            rpcMeta.setResponse(response);
        }

        return response;
    }

    /**
     * Inits the rpc meta.
     *
     * @return the rpc meta
     */
    private RpcMeta initRpcMeta() {
        if (rpcMeta == null) {
            rpcMeta = new RpcMeta();
        }
        return rpcMeta;
    }

    /**
     * Sets the magic code.
     *
     * @param magicCode the new magic code
     */
    public void setMagicCode(String magicCode) {
        if (head == null) {
            head = new RpcHeadMeta();
        }
        head.setMagicCode(magicCode);
    }

    /**
     * Gets the head.
     *
     * @return the head
     */
    public RpcHeadMeta getHead() {
        return head;
    }

    /**
     * Sets the head.
     *
     * @param head the new head
     */
    public void setHead(RpcHeadMeta head) {
        this.head = head;
    }

    /**
     * Gets the rpc meta.
     *
     * @return the rpc meta
     */
    public RpcMeta getRpcMeta() {
        return rpcMeta;
    }

    /**
     * Sets the rpc meta.
     *
     * @param rpcMeta the new rpc meta
     */
    protected void setRpcMeta(RpcMeta rpcMeta) {
        this.rpcMeta = rpcMeta;
    }

    /**
     * Gets the data.
     *
     * @return the data
     */
    public byte[] getData() {
        return data;
    }

    /**
     * Sets the data.
     *
     * @param data the new data
     */
    public void setData(byte[] data) {
        this.data = data;
    }

    /**
     * Gets the time stamp.
     *
     * @return the time stamp
     */
    public long getTimeStamp() {
        return timeStamp;
    }

    /**
     * Sets the time stamp.
     *
     * @param timeStamp the new time stamp
     */
    public void setTimeStamp(long timeStamp) {
        this.timeStamp = timeStamp;
    }

    /**
     * Gets the attachment.
     *
     * @return the attachment
     */
    public byte[] getAttachment() {
        return attachment;
    }

    /**
     * Sets the attachment.
     *
     * @param attachment the new attachment
     */
    public void setAttachment(byte[] attachment) {
        this.attachment = attachment;
    }

    /**
     * Gets the error response rpc data package.
     *
     * @param errorCode the error code
     * @param errorText the error text
     * @return the error response rpc data package
     */
    public RpcDataPackage getErrorResponseRpcDataPackage(int errorCode, String errorText) {
        return getErrorResponseRpcDataPackage(new RpcResponseMeta(errorCode, errorText));
    }

    /**
     * Gets the error response rpc data package.
     *
     * @param responseMeta the response meta
     * @return the error response rpc data package
     */
    public RpcDataPackage getErrorResponseRpcDataPackage(RpcResponseMeta responseMeta) {
        RpcDataPackage response = new RpcDataPackage();

        RpcMeta eRpcMeta = rpcMeta;
        if (eRpcMeta == null) {
            eRpcMeta = new RpcMeta();
        }
        eRpcMeta.setResponse(responseMeta);
        eRpcMeta.setRequest(null);

        response.setRpcMeta(eRpcMeta);
        response.setHead(head);

        return response;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.baidu.jprotobuf.remoting.pbrpc.Writerable#write()
     */
    public byte[] write() {
        if (head == null) {
            throw new RuntimeException("property 'head' is null.");
        }
        if (rpcMeta == null) {
            throw new RuntimeException("property 'rpcMeta' is null.");
        }

        int totolSize = 0;

        // set dataSize
        int dataSize = 0;
        if (data != null) {
            dataSize = data.length;
            totolSize += dataSize;
        }

        // set attachment size
        int attachmentSize = 0;
        if (attachment != null) {
            attachmentSize = attachment.length;
            totolSize += attachmentSize;
        }
        rpcMeta.setAttachmentSize(attachmentSize);

        // get RPC meta data
        byte[] rpcMetaBytes = rpcMeta.write();
        int rpcMetaSize = rpcMetaBytes.length;
        totolSize += rpcMetaSize;
        head.setMetaSize(rpcMetaSize);
        head.setMessageSize(totolSize); // set message body size

        // total size should add head size
        totolSize = totolSize + RpcHeadMeta.SIZE;
        try {
            // join all byte array
            ByteArrayOutputStream baos = new ByteArrayOutputStream(totolSize);
            // write head
            baos.write(head.write());

            // write meta data
            baos.write(rpcMetaBytes);

            // write data
            if (data != null) {
                baos.write(data);
            }

            if (attachment != null) {
                baos.write(attachment);
            }

            byte[] ret = baos.toByteArray();
            return ret;
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }

    }

    /*
     * (non-Javadoc)
     * 
     * @see com.baidu.jprotobuf.remoting.pbrpc.Readable#read(byte[])
     */
    public void read(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("param 'bytes' is null.");
        }

        ByteArrayInputStream bais = new ByteArrayInputStream(bytes);

        // read head data
        byte[] headBytes = new byte[RpcHeadMeta.SIZE];
        bais.read(headBytes, 0, RpcHeadMeta.SIZE);

        // parse RPC head
        head = new RpcHeadMeta();
        head.read(headBytes);

        // get RPC meta size
        int metaSize = head.getMetaSize();
        // read meta data
        byte[] metaBytes = new byte[metaSize];
        bais.read(metaBytes, 0, metaSize);

        rpcMeta = new RpcMeta();
        rpcMeta.read(metaBytes);

        int attachmentSize = rpcMeta.getAttachmentSize();

        // read message data
        // message data size = totalsize - metasize - attachmentSize
        int totalSize = head.getMessageSize();
        int dataSize = totalSize - metaSize - attachmentSize;

        if (dataSize > 0) {
            data = new byte[dataSize];
            bais.read(data, 0, dataSize);
        }

        // if need read attachment
        if (attachmentSize > 0) {
            attachment = new byte[attachmentSize];
            bais.read(attachment, 0, attachmentSize);
        }

        try {
            bais.close(); // ByteArrayInputStream close method is empty
        } catch (IOException e) {
            if (LOG.isLoggable(Level.FINEST)) {
                LOG.log(Level.FINEST, e.getMessage());
            }
        }
    }

    /**
     * Builds the rpc data package.
     *
     * @param methodInfo the method info
     * @param args the args
     * @return the rpc data package
     * @throws IOException Signals that an I/O exception has occurred.
     */
    public static RpcDataPackage buildRpcDataPackage(RpcMethodInfo methodInfo, Object[] args) throws IOException {
        RpcDataPackage dataPackage = new RpcDataPackage();
        dataPackage.magicCode(ProtocolConstant.MAGIC_CODE);
        dataPackage.serviceName(methodInfo.getServiceName()).methodName(methodInfo.getMethodName());
        dataPackage.compressType(methodInfo.getProtobufPRC().compressType().value());
        // set data
        if (args != null && args.length == 1) {
            byte[] data = methodInfo.inputEncode(args[0]);
            if (data != null) {
                dataPackage.data(data);
            }
        }

        // set logid
        // if logid exsit under thread local holder
        Long elogId = LogIdThreadLocalHolder.getLogId();
        if (elogId != null) {
            // will always use this log id
            LOG.info("Detected LogIdThreadLocalHolder contains a logId, will always use this log id.");
            dataPackage.logId(elogId);
        } else {
            LogIDGenerator logIDGenerator = methodInfo.getLogIDGenerator();
            if (logIDGenerator != null) {
                long logId =
                        logIDGenerator.generate(methodInfo.getServiceName(), methodInfo.getMethod().getName(), args);
                dataPackage.logId(logId);
            }
        }

        // set attachment
        ClientAttachmentHandler attachmentHandler = methodInfo.getClientAttachmentHandler();
        if (attachmentHandler != null) {
            byte[] attachment = attachmentHandler.handleRequest(methodInfo.getServiceName(),
                    methodInfo.getMethod().getName(), args);
            if (attachment != null) {
                dataPackage.attachment(attachment);
            }
        }

        // set authentication data
        AuthenticationDataHandler authenticationDataHandler = methodInfo.getAuthenticationDataHandler();
        if (authenticationDataHandler != null) {
            byte[] authenticationData = authenticationDataHandler.create(methodInfo.getServiceName(),
                    methodInfo.getMethod().getName(), args);
            if (authenticationData != null) {
                dataPackage.authenticationData(authenticationData);
            }
        }

        return dataPackage;
    }

}
