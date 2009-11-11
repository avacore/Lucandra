/**
 * Copyright 2009 T Jake Luciani
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package lucandra;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.cassandra.db.ColumnFamily;
import org.apache.cassandra.db.IColumn;
import org.apache.cassandra.db.RangeCommand;
import org.apache.cassandra.db.ReadCommand;
import org.apache.cassandra.db.Row;
import org.apache.cassandra.db.SliceFromReadCommand;
import org.apache.cassandra.db.filter.QueryPath;
import org.apache.cassandra.service.StorageProxy;
import org.apache.cassandra.service.UnavailableException;
import org.apache.log4j.Logger;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermEnum;

/**
 * 
 * @author jake
 * 
 */
public class LucandraTermEnum extends TermEnum {

    private final IndexReader indexReader;
    private final String indexName;

    private int termPosition;
    private Term[] termBuffer;
    private SortedMap<Term, List<IColumn>> termDocFreqBuffer;
    private Map<Term, SortedMap<Term, List<IColumn>>> termCache;

    // number of sequential terms to read initially
    private final int maxInitSize = 2;
    private int actualInitSize = -1;
    private Term initTerm;

    private final Term finalTerm = new Term("" + new Character((char) 255), "" + new Character((char) 255));

    private static final Logger logger = Logger.getLogger(LucandraTermEnum.class);

    public LucandraTermEnum(IndexReader indexReader) {
        this.indexReader = indexReader;
        this.indexName = indexReader.getIndexName();
        this.termPosition = 0;

        this.termCache = new HashMap<Term, SortedMap<Term, List<IColumn>>>();
    }

    @Override
    public boolean skipTo(Term term) throws IOException {

        if (term == null)
            return false;

        loadTerms(term);

        return termBuffer.length == 0 ? false : true;
    }

    @Override
    public void close() throws IOException {

    }

    @Override
    public int docFreq() {
        return termDocFreqBuffer.size();
    }

    @Override
    public boolean next() throws IOException {

        termPosition++;

        boolean hasNext = termPosition < termBuffer.length;

        if (hasNext && termBuffer[termPosition].equals(finalTerm)) {
            termPosition++;
            hasNext = termPosition < termBuffer.length;
        }

        if (!hasNext) {

            // if we've already done init try grabbing more
            if (actualInitSize == maxInitSize) {
                loadTerms(initTerm);
                hasNext = termBuffer.length > 0;
            }

            termPosition = 0;
        }

        return hasNext;
    }

    @Override
    public Term term() {
        return termBuffer[termPosition];
    }

    private void loadTerms(Term skipTo) {
                  
        // chose starting term
        String startTerm = indexName + "/" + CassandraUtils.createColumnName(skipTo);
        // this is where we stop;
        String endTerm = indexName + "/" + skipTo.field() + CassandraUtils.delimeter + CassandraUtils.delimeter; //startTerm + new Character((char) 255);

        if(!skipTo.equals(initTerm) || termPosition == 0) {
            termDocFreqBuffer = termCache.get(skipTo);
        }else{
            termDocFreqBuffer = null;
        }
            
        if (termDocFreqBuffer != null) {

            termBuffer = termDocFreqBuffer.keySet().toArray(new Term[] {});
            termPosition = 0;

            logger.debug("Found " + startTerm + " in cache");
            return;
        }

        //The first time we grab just a few keys
        int count = maxInitSize;
        
        //otherwise we grab all the rest of the keys 
        if( initTerm != null ){
            count = 1024; 
        }
            
        long start = System.currentTimeMillis();

        // First buffer the keys in this term range
        List<String> keys;
        RangeCommand rangeCommand = new RangeCommand(CassandraUtils.keySpace, CassandraUtils.termVecColumnFamily, startTerm, endTerm, count);
        
        try{
            keys = StorageProxy.getKeyRange(rangeCommand);
        }catch(IOException e){
            throw new RuntimeException(e);
        } catch (UnavailableException e) {
            throw new RuntimeException(e);
        }

        logger.debug("Found " + keys.size() + " keys in range:" + startTerm + " to " + endTerm + " in " + (System.currentTimeMillis() - start)+"ms");

        if(initTerm == null){
            initTerm = skipTo;
            actualInitSize = keys.size();
        }else{
            keys.subList(0,actualInitSize).clear();
            actualInitSize = -1; 
            initTerm = null;
        }
            
        termDocFreqBuffer = new TreeMap<Term, List<IColumn>>();

        if (!keys.isEmpty()) {
          
            List<ReadCommand> commands = new ArrayList<ReadCommand>(keys.size());
            
            for(String key : keys){
                commands.add(  new SliceFromReadCommand(CassandraUtils.keySpace, 
                    key, new QueryPath(CassandraUtils.termVecColumnFamily), 
                    new byte[] {}, new byte[] {}, true, Integer.MAX_VALUE));        
            }
            
            try {
            
                List<Row> rows = StorageProxy.readProtocol(commands, 1);
                
                for(Row row : rows){
                    
                    String termStr = row.key().substring(row.key().indexOf("/")+1);
                    Term term = CassandraUtils.parseTerm(termStr.getBytes());
                    
                    ColumnFamily cf = row.getColumnFamily(CassandraUtils.termVecColumnFamily);
                    
                   cf.getSortedColumns();
                   
                   termDocFreqBuffer.put(term, new ArrayList<IColumn>(cf.getSortedColumns()));
                }
            }catch(Exception e){
                
                throw new RuntimeException(e);
            }
        }
        
        
        //add a final key (excluded in submap below)
        termDocFreqBuffer.put(finalTerm, null);
        
        // put in cache
        for (Term termKey : termDocFreqBuffer.keySet()) {
                    
            
            SortedMap<Term, List<IColumn>> subMap = termDocFreqBuffer.subMap(termKey, termDocFreqBuffer.lastKey());
            
            logger.debug("Caching "+termKey+" with "+subMap.size()+" siblings");
            termCache.put(termKey, subMap);
            
            indexReader.addTermEnumCache(termKey, this);
        }

        //cache the initial term too
        indexReader.addTermEnumCache(skipTo, this);
        
        termBuffer = termDocFreqBuffer.keySet().toArray(new Term[] {});
        termPosition = 0;

        long end = System.currentTimeMillis();

        logger.debug("loadTerms: " + startTerm + "(" + termBuffer.length + ") took " + (end - start) + "ms");

    }

    public final List<IColumn> getTermDocFreq() {
        if (termBuffer.length == 0)
            return null;

        List<IColumn> termDocs = termDocFreqBuffer.get(termBuffer[termPosition]);

        // show results in descending order by default
        //Collections.reverse(termDocs);

        // create proper docIds.
        // We do this now because of how lucene fetches the results
        // in buffered chunks. This way all the doc ids are consistent
        for (IColumn col : termDocs) {
            indexReader.addDocument(col.name());
        }

        return termDocs;
    }

    public Set<Term> getCachedTerms() {
        return termCache.keySet();
    }

}
