package org.nutz.plugins.ip2region;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

import org.nutz.plugins.ip2region.impl.ByteArrayDBReader;
import org.nutz.plugins.ip2region.impl.RandomAccessFileDBReader;

/**
 * ip db searcher class (Not thread safe)
 * 
 * @author chenxin<chenxin619315@gmail.com>
*/
public class DbSearcher 
{
    public static final int BTREE_ALGORITHM  = 1;
    public static final int BINARY_ALGORITHM = 2;
    public static final int MEMORY_ALGORITYM = 3;

    /**
     * db config
    */
    private DbConfig dbConfig = null;

    /**
     * db file access handler
    */
    private DBReader reader;

    /**
     * header blocks buffer 
    */
    private long[] HeaderSip = null;
    private int[]  HeaderPtr = null;
    private int headerLength;

    /**
     * super blocks info 
    */
    private long firstIndexPtr = 0;
    private long lastIndexPtr = 0;
    private int totalIndexBlocks = 0;

    /**
     * for memory mode
     * the original db binary string
    */
    private byte[] dbBinStr = null;
    
    public DbSearcher() {
        try {
            ByteArrayOutputStream bao = new ByteArrayOutputStream();
            InputStream ins = getClass().getClassLoader().getResourceAsStream("ip2region/ip2region.db");
            byte[] buf = new byte[8192];
            while (true) {
                int len = ins.read(buf);
                if (len == -1)
                    break;
                if (len > 0)
                    bao.write(buf, 0, len);
            }
            this.reader = new ByteArrayDBReader(bao.toByteArray());
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * construct class
     * 
     * @param   bdConfig
     * @param   dbFile
     * @throws  FileNotFoundException 
    */
    public DbSearcher( DbConfig dbConfig, String dbFile ) throws FileNotFoundException
    {
        this(dbConfig, new RandomAccessFileDBReader(new RandomAccessFile(dbFile, "r")));
    }
    
    public DbSearcher( DbConfig dbConfig, DBReader reader) throws FileNotFoundException
    {
        this.dbConfig = dbConfig;
        this.reader = reader;
    }

    /**
     * get the region with a int ip address with memory binary search algorithm
     *
     * @param   ip
     * @throws  IOException
    */
    public DataBlock memorySearch(long ip) throws IOException
    {
        int blen = IndexBlock.getIndexBlockLength();
        if ( dbBinStr == null ) {
            dbBinStr = reader.full();
            
            //initialize the global vars
            firstIndexPtr    = Util.getIntLong(dbBinStr, 0);
            lastIndexPtr     = Util.getIntLong(dbBinStr, 4);
            totalIndexBlocks = (int)((lastIndexPtr - firstIndexPtr)/blen) + 1;
        }
        
        //search the index blocks to define the data
        int l = 0, h = totalIndexBlocks;
        long sip, eip, dataptr = 0;
        while ( l <= h ) {
            int m = (l + h) >> 1;
            int p = (int)(firstIndexPtr + m * blen);
            
            sip = Util.getIntLong(dbBinStr, p);
            if ( ip < sip ) {
                h = m - 1;
            } else {
                eip = Util.getIntLong(dbBinStr, p + 4);
                if ( ip > eip ) {
                    l = m + 1;
                } else {
                    dataptr = Util.getIntLong(dbBinStr, p + 8);
                    break;
                }
            }
        }
        
        //not matched
        if ( dataptr == 0 ) return null;
        
        //get the data
        int dataLen = (int)((dataptr >> 24) & 0xFF);
        int dataPtr = (int)((dataptr & 0x00FFFFFF));
        int city_id = (int)Util.getIntLong(dbBinStr, dataPtr);
        String region = new String(dbBinStr, dataPtr + 4, dataLen - 4, "UTF-8");
        
        return new DataBlock(city_id, region, dataPtr);
    }
    
    /**
     * get the region throught the ip address with memory binary search algorithm
     * 
     * @param   ip
     * @return  DataBlock
     * @throws  IOException 
    */
    public DataBlock memorySearch( String ip ) throws IOException
    {
        return memorySearch(Util.ip2long(ip));
    }
    

    /**
     * get by index ptr
     * 
     * @param   indexPtr
     * @throws  IOException 
    */
    public DataBlock getByIndexPtr( long ptr ) throws IOException
    {
        //reader.seek(ptr);
        byte[] buffer = new byte[12];
        reader.readFully(ptr, buffer, 0, buffer.length);
        //long startIp = Util.getIntLong(buffer, 0);
        //long endIp = Util.getIntLong(buffer, 4);
        long extra = Util.getIntLong(buffer, 8);
        
        int dataLen = (int)((extra >> 24) & 0xFF);
        int dataPtr = (int)((extra & 0x00FFFFFF));
        
        //reader.seek(dataPtr);
        byte[] data = new byte[dataLen];
        reader.readFully(dataPtr, data, 0, data.length);
        
        int city_id = (int)Util.getIntLong(data, 0);
        String region = new String(data, 4, data.length - 4, "UTF-8");
        
        return new DataBlock(city_id, region, dataPtr);
    }

    /**
     * get the region with a int ip address with b-tree algorithm
     * 
     * @param   ip
     * @throws  IOException 
    */
    public DataBlock btreeSearch( long ip ) throws IOException
    {
        //check and load the header
        if ( HeaderSip == null )  {
            //reader.seek(8L);    //pass the super block
            //byte[] b = new byte[dbConfig.getTotalHeaderSize()];
            byte[] b = new byte[4096];
            reader.readFully(8L, b, 0, b.length);//pass the super block
            
            //fill the header
            int len = b.length >> 3, idx = 0;  //b.lenght / 8
            long[] HeaderSip = new long[len];
            int[] HeaderPtr = new int [len];
            long startIp, dataPtr;
            for ( int i = 0; i < b.length; i += 8 ) {
                startIp = Util.getIntLong(b, i);
                dataPtr = Util.getIntLong(b, i + 4);
                if ( dataPtr == 0 ) break;
                
                HeaderSip[idx] = startIp;
                HeaderPtr[idx] = (int)dataPtr;
                idx++;
            }
            
            headerLength = idx;
            this.HeaderPtr = HeaderPtr;
            this.HeaderSip = HeaderSip;
        }
        
        //1. define the index block with the binary search
        if ( ip == HeaderSip[0] ) {
            return getByIndexPtr(HeaderPtr[0]);
        } else if ( ip == HeaderSip[headerLength-1] ) {
            return getByIndexPtr(HeaderPtr[headerLength-1]);
        }
        
        int l = 0, h = headerLength, sptr = 0, eptr = 0;
        while ( l <= h ) {
            int m = (l + h) >> 1;
            
            //perfetc matched, just return it
            if ( ip == HeaderSip[m] ) {
                if ( m > 0 ) {
                    sptr = HeaderPtr[m-1];
                    eptr = HeaderPtr[m  ];
                } else {
                    sptr = HeaderPtr[m ];
                    eptr = HeaderPtr[m+1];
                }
                
                break;
            }
            
            //less then the middle value
            if ( ip < HeaderSip[m] ) {
                if ( m == 0 ) {
                    sptr = HeaderPtr[m  ];
                    eptr = HeaderPtr[m+1];
                    break;
                } else if ( ip > HeaderSip[m-1] ) {
                    sptr = HeaderPtr[m-1];
                    eptr = HeaderPtr[m  ];
                    break;
                }
                h = m - 1;
            } else {
                if ( m == headerLength - 1 ) {
                    sptr = HeaderPtr[m-1];
                    eptr = HeaderPtr[m  ];
                    break;
                } else if ( ip <= HeaderSip[m+1] ) {
                    sptr = HeaderPtr[m  ];
                    eptr = HeaderPtr[m+1];
                    break;
                }
                l = m + 1;
            }
        }
        
        //match nothing just stop it
        if ( sptr == 0 ) return null;
        
        //2. search the index blocks to define the data
        int blockLen = eptr - sptr, blen = IndexBlock.getIndexBlockLength();
        byte[] iBuffer = new byte[blockLen + blen];    //include the right border block
        //reader.seek(sptr);
        reader.readFully(sptr, iBuffer, 0, iBuffer.length);
        
        l = 0; h = blockLen / blen;
        long sip, eip, dataptr = 0;
        while ( l <= h ) {
            int m = (l + h) >> 1;
            int p = m * blen;
            sip = Util.getIntLong(iBuffer, p);
            if ( ip < sip ) {
                h = m - 1;
            } else {
                eip = Util.getIntLong(iBuffer, p + 4);
                if ( ip > eip ) {
                    l = m + 1;
                } else {
                    dataptr = Util.getIntLong(iBuffer, p + 8);
                    break;
                }
            }
        }
        
        //not matched
        if ( dataptr == 0 ) return null;
        
        //3. get the data
        int dataLen = (int)((dataptr >> 24) & 0xFF);
        int dataPtr = (int)((dataptr & 0x00FFFFFF));
        
        //reader.seek(dataPtr);
        byte[] data = new byte[dataLen];
        reader.readFully(dataPtr, data, 0, data.length);
        
        int city_id = (int)Util.getIntLong(data, 0);
        String region = new String(data, 4, data.length - 4, "UTF-8");
        
        return new DataBlock(city_id, region, dataPtr);
    }

    /**
     * get the region throught the ip address with b-tree search algorithm
     * 
     * @param   ip
     * @return  DataBlock
     * @throws  IOException 
    */
    public DataBlock btreeSearch( String ip ) throws IOException
    {
        return btreeSearch(Util.ip2long(ip));
    }

    /**
     * get the region with a int ip address with binary search algorithm
     * 
     * @param   ip
     * @throws  IOException 
    */
    public DataBlock binarySearch( long ip ) throws IOException
    {
        int blen = IndexBlock.getIndexBlockLength();
        if ( totalIndexBlocks == 0 ) {
            //reader.seek(0L);
            byte[] superBytes = new byte[8];
            reader.readFully(0L, superBytes, 0, superBytes.length);
            //initialize the global vars
            firstIndexPtr = Util.getIntLong(superBytes, 0);
            lastIndexPtr = Util.getIntLong(superBytes, 4);
            totalIndexBlocks = (int)((lastIndexPtr - firstIndexPtr)/blen) + 1;
        }
        
        //search the index blocks to define the data
        int l = 0, h = totalIndexBlocks;
        byte[] buffer = new byte[blen];
        long sip, eip, dataptr = 0;
        while ( l <= h ) {
            int m = (l + h) >> 1;
            //reader.seek(firstIndexPtr + m * blen);    //set the file pointer
            reader.readFully(firstIndexPtr + m * blen, buffer, 0, buffer.length);
            sip = Util.getIntLong(buffer, 0);
            if ( ip < sip ) {
                h = m - 1;
            } else {
                eip = Util.getIntLong(buffer, 4);
                if ( ip > eip ) {
                    l = m + 1;
                } else {
                    dataptr = Util.getIntLong(buffer, 8);
                    break;
                }
            }
        }
        
        //not matched
        if ( dataptr == 0 ) return null;
        
        //get the data
        int dataLen = (int)((dataptr >> 24) & 0xFF);
        int dataPtr = (int)((dataptr & 0x00FFFFFF));
        
        //reader.seek(dataPtr);
        byte[] data = new byte[dataLen];
        reader.readFully(dataPtr, data, 0, data.length);
        
        int city_id = (int)Util.getIntLong(data, 0);
        String region = new String(data, 4, data.length - 4, "UTF-8");
        
        return new DataBlock(city_id, region, dataPtr);
    }

    /**
     * get the region throught the ip address with binary search algorithm
     * 
     * @param   ip
     * @return  DataBlock
     * @throws  IOException 
    */
    public DataBlock binarySearch( String ip ) throws IOException
    {
        return binarySearch(Util.ip2long(ip));
    }

    /**
     * get the db config
     *
     * @return  DbConfig
    */
    public DbConfig getDbConfig()
    {
        return dbConfig;
    }

    /**
     * close the db 
     * 
     * @throws IOException 
    */
    public void close() throws IOException
    {
        HeaderSip = null;    //let gc do its work
        HeaderPtr = null;
        dbBinStr  = null;
        reader.close();
    }

    public String getRegion(String ip) {
        try {
            return this.memorySearch(ip).getRegion();
        }
        catch (IOException e) {
            return "未知";
        }
    }
}
