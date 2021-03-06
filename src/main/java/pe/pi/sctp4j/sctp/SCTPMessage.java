/*
 * Copyright 2017 pi.pe gmbh .
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package pe.pi.sctp4j.sctp;

import pe.pi.sctp4j.sctp.messages.DataChunk;
import com.phono.srtplight.Log;
import java.util.SortedSet;

/**
 *
 * @author Westhawk Ltd<thp@westhawk.co.uk>
 */
public class SCTPMessage implements Runnable {

    private final SCTPStream _stream;
    private final byte[] _data;
    private int _offset = 0;
    private int _pPid = 0;
    private int _mseq; // note do we need these ?
    private SCTPStreamListener _li;
    private boolean _delivered;

    /**
     * Outbound message - note that we assume no one will mess with data between
     * calls to fill()
     *
     * @param data
     * @param s
     */
    public SCTPMessage(byte[] data, SCTPStream s) {
        _data = (data.length > 0)?data:new byte[1];
        _stream = s;
        _pPid = (data.length > 0)?DataChunk.WEBRTCBINARY:DataChunk.WEBRTCBINARYEMPTY;
    }

    public SCTPMessage(String data, SCTPStream s) {
        _data = (data.length() > 0)?data.getBytes():new byte[1];
        _stream = s;
        _pPid = (data.length() > 0)?DataChunk.WEBRTCSTRING:DataChunk.WEBRTCSTRINGEMPTY;
    }

    public SCTPMessage(SCTPStream s, SortedSet<DataChunk> chunks) {
        _stream = s;
        int tot = 0;
        if ((chunks.first().getFlags() & DataChunk.BEGINFLAG) == 0) {
            throw new IllegalArgumentException("must start with 'start' chunk");
        }
        if ((chunks.last().getFlags() & DataChunk.ENDFLAG) == 0) {
            throw new IllegalArgumentException("must end with 'end' chunk");
        }
        _pPid = chunks.first().getPpid();
        for (DataChunk dc : chunks) {
            tot += dc.getDataSize();
            if (_pPid != dc.getPpid()) {
                // aaagh 
                throw new IllegalArgumentException("chunk has wrong ppid" + dc.getPpid() + " vs " + _pPid);
            }
        }
        _data = new byte[tot];
        int offs = 0;
        for (DataChunk dc : chunks) {
            System.arraycopy(dc.getData(), 0, _data, offs, dc.getDataSize());
            offs += dc.getDataSize();
        }
    }

    public SCTPMessage(SCTPStream s, DataChunk singleChunk) {
        _stream = s;
        int flags = singleChunk.getFlags();
        if ((flags & singleChunk.SINGLEFLAG) > 0) {
            _data = singleChunk.getData();
            _pPid = singleChunk.getPpid();
        } else {
            throw new IllegalArgumentException("must use a 'single' chunk");
        }
    }

    public void setCompleteHandler(MessageCompleteHandler mch) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    public boolean hasMoreData() {
        return (_offset < _data.length);
    }

    /**
     * available datachunks are put here to be filled with data from this
     * outbound message
     *
     * @param dc
     */
    public void fill(DataChunk dc) {
        int dsz = dc.getCapacity();
        int remain = _data.length - _offset;
        if (_offset == 0) {
            if (remain <= dsz) {
                // only one chunk
                dc.setFlags(dc.SINGLEFLAG);
                dc.setData(_data);
                _offset = _data.length;
            } else {
                // first chunk of many
                dc.setFlags(dc.BEGINFLAG);
                dc.setData(_data, _offset, dsz);
                _offset += dsz;
            }
        } else// not first
        if (remain <= dsz) {
            // last chunk, this will all fit.
            dc.setFlags(dc.ENDFLAG);
            dc.setData(_data, _offset, remain);
            _offset += remain; // should be _data_length now
        } else {
            // middle chunk.
            dc.setFlags(0);
            dc.setData(_data, _offset, dsz);
            _offset += dsz;
        }
        dc.setPpid(_pPid);
        dc.setsSeqNo(_mseq);
        _stream.outbound(dc);
    }

    public boolean deliver(SCTPStreamListener li) {
        _li = li;
        _delivered = false;
        Log.debug("delegating message delivery to stream of type " + _stream.getClass().getSimpleName());
        _stream.deliverMessage(this);
        return true;
    }

    public byte[] getData() {
        return _data;
    }

    public void setSeq(int mseq) {
        _mseq = mseq;
    }
    public int getSeq(){
        return _mseq;
    }

    @Override
    public void run() {
        Log.debug("delegated message delivery from stream of type " + _stream.getClass().getSimpleName());
        byte data[] = _data;
        if (_li != null) {
            switch (_pPid) {
                case DataChunk.WEBRTCBINARYEMPTY:
                    data = new byte[0];
                case DataChunk.WEBRTCBINARY:
                    if ((_li instanceof SCTPByteStreamListener)) {
                        ((SCTPByteStreamListener) _li).onMessage(_stream, data);
                        _delivered = true;
                    }
                    break;
                case DataChunk.WEBRTCSTRINGEMPTY:
                    data = new byte[0];
                case DataChunk.WEBRTCSTRING:
                    _li.onMessage(_stream, new String(_data));
                    _delivered = true;
                    break;
            }
        }
        if (!_delivered) {
            Log.debug("Undelivered message to " + (_stream == null ? "null stream" : _stream.getLabel()) + " via " + (_li == null ? "null listener" : _li.getClass().getSimpleName()) + " ppid is " + _pPid);
        }
    }

    public void acked() {
    }

}
