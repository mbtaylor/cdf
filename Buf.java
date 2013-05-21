package cdf;

import java.io.IOException;
import java.io.InputStream;

public interface Buf {

    /**
     * Reads a single byte, returning a value in the range 0..255.
     */
    int readUnsignedByte( Pointer ptr );

    /**
     * Reads a signed 4-byte integer, big-endian byte ordering.
     */
    int readInt( Pointer ptr );

    /**
     * Reads a file offset or size.
     * This is a signed integer with big-endian byte ordering.
     * For CDF V3 it is 8 bytes, and for earlier versions it is 4 bytes.
     */
    long readOffset( Pointer ptr );

    /**
     * Reads a fixed number of bytes interpreting them as ASCII characters
     * and returns the result as a string.
     * An 0x00 terminates the sequence.
     *
     * <p>Requirements as per CDF Descriptor Record Copyright field.
     */
    String readAsciiString( Pointer ptr, int nbyte );

    void setBit64( boolean isBit64 );
    boolean isBit64();
    void setEncoding( boolean isBigendian );
    boolean isBigendian();

    void readDataBytes( long offset, int count, byte[] array );
    void readDataShorts( long offset, int count, short[] array );
    void readDataInts( long offset, int count, int[] array );
    void readDataLongs( long offset, int count, long[] array );
    void readDataFloats( long offset, int count, float[] array );
    void readDataDoubles( long offset, int count, double[] array );

    InputStream createInputStream( long offset );
    Buf fillNewBuf( long count, InputStream in ) throws IOException;

    long getLength();
}
