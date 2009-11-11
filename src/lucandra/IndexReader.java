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
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.cassandra.db.ColumnFamily;
import org.apache.cassandra.db.IColumn;
import org.apache.cassandra.db.ReadCommand;
import org.apache.cassandra.db.Row;
import org.apache.cassandra.db.SliceFromReadCommand;
import org.apache.cassandra.db.filter.QueryPath;
import org.apache.cassandra.service.ColumnParent;
import org.apache.cassandra.service.StorageProxy;
import org.apache.log4j.Logger;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldSelector;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermDocs;
import org.apache.lucene.index.TermEnum;
import org.apache.lucene.index.TermFreqVector;
import org.apache.lucene.index.TermPositions;
import org.apache.lucene.index.TermVectorMapper;
import org.apache.lucene.search.DefaultSimilarity;

public class IndexReader extends org.apache.lucene.index.IndexReader {

    private final static int numDocs = 1000000;
    private final static byte[] norms = new byte[numDocs];
    static{
        Arrays.fill(norms, DefaultSimilarity.encodeNorm(1.0f));
    }
    
    private final String indexName;
    private final Map<String,Integer> docIdToDocIndex;
    private final Map<Integer,String> docIndexToDocId;
    private final AtomicInteger docCounter;
   
    private final Map<Term, LucandraTermEnum> termEnumCache;
   

    private static final Logger logger = Logger.getLogger(IndexReader.class);

    public IndexReader(String name) {
        super();
        this.indexName = name;

        docCounter         = new AtomicInteger(0);
        docIdToDocIndex    = new HashMap<String,Integer>();
        docIndexToDocId    = new HashMap<Integer,String>();
        
        termEnumCache = new HashMap<Term, LucandraTermEnum>();
    }
    
  
    @Override
    public IndexReader reopen(){
        
        docCounter.set(0);
        docIdToDocIndex.clear();
        docIndexToDocId.clear();
        termEnumCache.clear();
        
        return this;
    }
    
    @Override
    protected void doClose() throws IOException {
        
    } 
    
    @Override
    protected void doCommit() throws IOException {
      
    }

    @Override
    protected void doDelete(int arg0) throws CorruptIndexException, IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void doSetNorm(int arg0, String arg1, byte arg2) throws CorruptIndexException, IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void doUndeleteAll() throws CorruptIndexException, IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int docFreq(Term term) throws IOException {

        LucandraTermEnum termEnum = termEnumCache.get(term);
        if (termEnum == null) {

            long start = System.currentTimeMillis();

            termEnum = new LucandraTermEnum(this);
            termEnum.skipTo(term);

            long end = System.currentTimeMillis();

            logger.debug("docFreq() took: " + (end - start) + "ms");

            termEnumCache.put(term, termEnum);   
        }
        
        return termEnum.docFreq();
    }

    
    @Override
    public Document document(int docNum, FieldSelector selector) throws CorruptIndexException, IOException {

        String key = indexName +"/"+docIndexToDocId.get(docNum);
        
        
        List<ReadCommand> commands = new ArrayList<ReadCommand>();
        commands.add(new SliceFromReadCommand(
                CassandraUtils.keySpace, key, 
                new QueryPath(CassandraUtils.docColumnFamily), 
                new byte[] {}, new byte[] {}, false, 100));
        
       
        long start = System.currentTimeMillis();

        try {
            Document doc = new Document();
            
            List<Row> rows = StorageProxy.readProtocol(commands, 1);
            for(Row row : rows){
                ColumnFamily cf = row.getColumnFamily(CassandraUtils.docColumnFamily);
                for(Map.Entry<byte[], IColumn> col : cf.getColumnsMap().entrySet() ){
                    Field f = new Field(new String(col.getKey()), col.getValue().value(), Store.YES);
                    doc.add(f);
                }
            }
            
           
            long end = System.currentTimeMillis();

            logger.debug("Document read took: " + (end - start) + "ms");

            return doc;

        } catch (Exception e) {
            throw new IOException(e.getLocalizedMessage());
        }

    }

    @Override
    public Collection getFieldNames(FieldOption arg0) {
        throw new UnsupportedOperationException();
    }

    @Override
    public TermFreqVector getTermFreqVector(int arg0, String arg1) throws IOException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void getTermFreqVector(int arg0, TermVectorMapper arg1) throws IOException {
        // TODO Auto-generated method stub

    }

    @Override
    public void getTermFreqVector(int arg0, String arg1, TermVectorMapper arg2) throws IOException {
        // TODO Auto-generated method stub

    }

    @Override
    public TermFreqVector[] getTermFreqVectors(int arg0) throws IOException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public boolean hasDeletions() {

        return false;
    }

    @Override
    public boolean isDeleted(int arg0) {

        return false;
    }

    @Override
    public int maxDoc() {
        //if (numDocs == null)
        //    numDocs();

        return numDocs + 1;
    }

    @Override
    public byte[] norms(String term) throws IOException {
        return norms;     
    }

    @Override
    public void norms(String arg0, byte[] arg1, int arg2) throws IOException {
        // TODO Auto-generated method stub

    }

    @Override
    public int numDocs() {

        return numDocs;
    }

    @Override
    public TermDocs termDocs() throws IOException {
        return new LucandraTermDocs(this);
    }

    @Override
    public TermPositions termPositions() throws IOException {
        return new LucandraTermDocs(this);
    }

    @Override
    public TermEnum terms() throws IOException {
        return new LucandraTermEnum(this);
    }

    @Override
    public TermEnum terms(Term term) throws IOException {
       
        LucandraTermEnum termEnum = termEnumCache.get(term);
        
        if(termEnum == null){
        
            termEnum = new LucandraTermEnum(this);
            if( !termEnum.skipTo(term) )           
                termEnum = null;
            
        }
        
        return termEnum;
    }

    public int addDocument(byte[] docId) {

        
        String id;
        try {
            id = new String(docId,"UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("Cant make docId a string");
        }
        
        Integer idx = docIdToDocIndex.get(id);
        
        if(idx == null){
            idx = docCounter.incrementAndGet();

            if(idx > numDocs)
                throw new IllegalStateException("numDocs reached");
            
            docIdToDocIndex.put(id, idx);
            docIndexToDocId.put(idx, id);

            return idx;
        } 

        return idx;
    }

    public String getDocumentId(int docNum) {
        return docIndexToDocId.get(docNum);
    }

    public String getIndexName() {
        return indexName;
    }
    
    public LucandraTermEnum checkTermCache(Term term){
        return termEnumCache.get(term);
    }
    
    public void addTermEnumCache(Term term, LucandraTermEnum termEnum){
        termEnumCache.put(term, termEnum);
    }

}
