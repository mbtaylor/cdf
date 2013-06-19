package cdf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class RecordMap {

    private final int nent_;
    private final int[] firsts_;
    private final int[] lasts_;
    private final Buf[] bufs_;
    private final long[] offsets_;
    private final int recSize_;
    private Block lastBlock_;

    private RecordMap( Entry[] entries, int recSize ) {
        Arrays.sort( entries );
        nent_ = entries.length;
        firsts_ = new int[ nent_ ];
        lasts_ = new int[ nent_ ];
        bufs_ = new Buf[ nent_ ];
        offsets_ = new long[ nent_ ];
        for ( int ie = 0; ie < nent_; ie++ ) {
            Entry entry = entries[ ie ];
            firsts_[ ie ] = entry.first_;
            lasts_[ ie ] = entry.last_;
            bufs_[ ie ] = entry.buf_;
            offsets_[ ie ] = entry.offset_;
        }
        recSize_ = recSize;
        lastBlock_ = calculateBlock( 0 );
    }

    public int getEntryCount() {
        return nent_;
    }

    /**
     * If one of the entries contains the given record, return its index.
     * If no entry contains it (the record is in a sparse region),
     * return (-fr-2), where <code>fr</code> is the index of the previous
     * entry.  A value of -1 indicates that the requested record is
     * in a sparse region before the first stored record.
     */
    public synchronized int getEntryIndex( int irec ) {
        if ( ! lastBlock_.contains( irec ) ) {
            lastBlock_ = calculateBlock( irec );
        }
        assert lastBlock_.contains( irec );
        return lastBlock_.ient_;
    }

    public Buf getBuf( int ient ) {
        return bufs_[ ient ];
    }

    /**
     * The ient parameter must be an entry containing the given record,
     * most likely obtained from a previous call to getEntryIndex.
     */
    public long getOffset( int ient, int irec ) {
        assert irec >= firsts_[ ient ] && irec <= lasts_[ ient ];
        return offsets_[ ient ] + ( irec - firsts_[ ient ] ) * recSize_;
    }

    public long getFinalOffsetInEntry( int ient ) {
        return offsets_[ ient ]
             + ( lasts_[ ient ] - firsts_[ ient ] + 1 ) * recSize_;
    }

    private Block calculateBlock( int irec ) {
        int firstIndex = binarySearch( firsts_, irec );
        if ( firstIndex >= 0 ) {
            return new Block( firstIndex,
                              firsts_[ firstIndex ], lasts_[ firstIndex ] );
        }
        else if ( firstIndex == -1 ) {
            return new Block( -firstIndex - 2, 0, firsts_[ 0 ] - 1 );
        }
        else {
            firstIndex = -2 - firstIndex;
        }
        int lastIndex = binarySearch( lasts_, irec );
        if ( lastIndex >= 0 ) {
            return new Block( lastIndex,
                              firsts_[ lastIndex ], lasts_[ lastIndex ] );
        }
        else if ( lastIndex == - nent_ - 1 ) {
            return new Block( lastIndex,
                              lasts_[ nent_ - 1 ], Integer.MAX_VALUE );
        }
        else {
            lastIndex = -1 - lastIndex;
        }
        if ( firstIndex == lastIndex ) {
            return new Block( firstIndex,
                              firsts_[ firstIndex ], lasts_[ firstIndex ] );
        }
        else {
            return new Block( -firstIndex - 2,
                              lasts_[ firstIndex ] + 1,
                              firsts_[ lastIndex ] - 1 );
        }
    }

    public static RecordMap createRecordMap( VariableDescriptorRecord vdr,
                                             RecordFactory recFact,
                                             int recSize )
            throws IOException {
        Compression compress = getCompression( vdr, recFact );
        Buf buf = vdr.getBuf();
        List<Entry> entryList = new ArrayList<Entry>();
        for ( long vxrOffset = vdr.vxrHead_; vxrOffset != 0; ) {
            VariableIndexRecord vxr =
                recFact.createRecord( buf, vxrOffset,
                                      VariableIndexRecord.class );
            readEntries( vxr, buf, recFact, recSize, compress, entryList );
            vxrOffset = vxr.vxrNext_;
        }
        Entry[] entries = entryList.toArray( new Entry[ 0 ] );
        return new RecordMap( entries, recSize );
    }

    private static Compression getCompression( VariableDescriptorRecord vdr,
                                               RecordFactory recFact )
            throws IOException {
        boolean hasCompress = Record.hasBit( vdr.flags_, 2 );
        if ( hasCompress && vdr.cprOrSprOffset_ != -1 ) {
            CompressedParametersRecord cpr =
                recFact.createRecord( vdr.getBuf(), vdr.cprOrSprOffset_,
                                      CompressedParametersRecord.class );
            return Compression.getCompression( cpr.cType_ );
        }
        else {
            return Compression.NONE;
        }
    }

    private static void readEntries( VariableIndexRecord vxr, Buf buf,
                                     RecordFactory recFact, int recSize,
                                     Compression compress, List<Entry> list )
            throws IOException {
        int nent = vxr.nUsedEntries_;
        for ( int ie = 0; ie < nent; ie++ ) {
            int first = vxr.first_[ ie ];
            int last = vxr.last_[ ie ];
            Record rec = recFact.createRecord( buf, vxr.offset_[ ie ] );
            if ( rec instanceof VariableValuesRecord ) {
                VariableValuesRecord vvr = (VariableValuesRecord) rec;
                list.add( new Entry( first, last, buf,
                                     vvr.getRecordsOffset() ) );
            }
            else if ( rec instanceof CompressedVariableValuesRecord ) {
                CompressedVariableValuesRecord cvvr =
                    (CompressedVariableValuesRecord) rec;
                int uncompressedSize = ( last - first + 1 ) * recSize;
                Buf cBuf = Bufs.uncompress( compress, buf, cvvr.getDataOffset(),
                                            uncompressedSize );
                list.add( new Entry( first, last, cBuf, 0L ) );
            }
            else if ( rec instanceof VariableIndexRecord ) {

                // Amazingly, it's necessary to walk both the subtree of
                // VXRs hanging off the entry list *and* the linked list
                // of VXRs whose head is contained in this record.
                // This does seem unnecessarily complicated, but I've
                // seen at least one file where it happens
                // (STEREO_STA_L1_MAG_20070708_V03.cdf).
                VariableIndexRecord subVxr = (VariableIndexRecord) rec;
                readEntries( subVxr, buf, recFact, recSize, compress, list );
                for ( long nextVxrOff = subVxr.vxrNext_; nextVxrOff != 0; ) {
                    VariableIndexRecord nextVxr =
                        recFact.createRecord( buf, nextVxrOff,
                                              VariableIndexRecord.class );
                    readEntries( nextVxr, buf, recFact, recSize, compress,
                                 list );
                    nextVxrOff = nextVxr.vxrNext_;
                }
            }
            else {
                String msg = new StringBuffer()
                   .append( "Unexpected record type (" )
                   .append( rec.getRecordType() )
                   .append( ") pointed to by VXR offset" )
                   .toString();
                throw new CdfFormatException( msg );
            }
        }
    }

    private static class Entry implements Comparable<Entry> {
        private final int first_;
        private final int last_;
        private final Buf buf_;
        private final long offset_;
        Entry( int first, int last, Buf buf, long offset ) {
            first_ = first;
            last_ = last;
            buf_ = buf;
            offset_ = offset;
        }
        public int compareTo( Entry other ) {
            return this.first_ - other.first_;
        }
    }

    private static class Block {
        final int ient_;
        final int low_;
        final int high_;
        Block( int ient, int low, int high ) {
            ient_ = ient;
            low_ = low;
            high_ = high;
        }
        boolean contains( int irec ) {
            return irec >= low_ && irec <= high_;
        }
    }

    private static int binarySearch( int[] array, int key ) {
        assert isSorted( array );
        return Arrays.binarySearch( array, key );
    }

    private static boolean isSorted( int[] values ) {
        int hash = Arrays.hashCode( values );
        Arrays.sort( values );
        return Arrays.hashCode( values ) == hash;
    }
}
