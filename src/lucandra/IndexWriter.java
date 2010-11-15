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
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

import org.apache.cassandra.thrift.Cassandra;
import org.apache.cassandra.thrift.ColumnOrSuperColumn;
import org.apache.cassandra.thrift.ColumnParent;
import org.apache.cassandra.thrift.ColumnPath;
import org.apache.cassandra.thrift.ConsistencyLevel;
import org.apache.cassandra.thrift.InvalidRequestException;
import org.apache.cassandra.thrift.Mutation;
import org.apache.cassandra.thrift.NotFoundException;
import org.apache.cassandra.thrift.SlicePredicate;
import org.apache.cassandra.thrift.SliceRange;
import org.apache.cassandra.thrift.TimedOutException;
import org.apache.cassandra.thrift.UnavailableException;
import org.apache.log4j.Logger;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Fieldable;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Similarity;
import org.apache.lucene.search.TopDocs;
import org.apache.thrift.TException;

public class IndexWriter {

    private final byte[] indexName;
    private final Cassandra.Iface client;
    private final ColumnPath docAllColumnPath;
    private boolean autoCommit;
    private static final ThreadLocal<Map<ByteBuffer,Map<String,List<Mutation>>>> mutationMap = new ThreadLocal<Map<ByteBuffer,Map<String,List<Mutation>>>>();
    
    private Similarity similarity = Similarity.getDefault(); // how to normalize;     
    
    private static final Logger logger = Logger.getLogger(IndexWriter.class);

    public IndexWriter(String indexName, Cassandra.Iface client) {

        try {
            this.indexName = indexName.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("JVM does not support UTF-8");
        }
        this.client = client;
        autoCommit  = true;
        docAllColumnPath = new ColumnPath(CassandraUtils.docColumnFamily);
            
    }

    @SuppressWarnings("unchecked")
    public void addDocument(Document doc, Analyzer analyzer) throws CorruptIndexException, IOException {

        List<Term> allIndexedTerms = new ArrayList<Term>();
        
        
        //check for special field name
        String specialDocId = doc.get(CassandraUtils.documentIdField);
        byte[] docId = null;
        
        if(specialDocId != null){
            docId = specialDocId.getBytes("UTF-8");
        } else {      
            docId = Long.toHexString(((long) (System.nanoTime()+(Math.random()*System.nanoTime())))).getBytes("UTF-8"); 
        }
        int position = 0;
     
        for (Fieldable field : (List<Fieldable>) doc.getFields()) {

            // Indexed field
            if (field.isIndexed() && field.isTokenized()) {

                TokenStream tokens = field.tokenStreamValue();

                if (tokens == null) {
                    tokens = analyzer.tokenStream(field.name(), new StringReader(field.stringValue()));
                }

                // collect term information per field
                Map<Term, Map<String,List<Number>>> allTermInformation = new HashMap<Term, Map<String,List<Number>>>();
                
                int lastOffset = 0;
                if (position > 0) {
                    position += analyzer.getPositionIncrementGap(field.name());
                }

                // Build the termPositions vector for all terms
	          
                tokens.reset(); // reset the TokenStream to the first token
                
                // set up token attributes we are working on
                
                //offsets
                OffsetAttribute             offsetAttribute  = null;
                if(field.isStoreOffsetWithTermVector())
                    offsetAttribute = (OffsetAttribute) tokens.addAttribute(OffsetAttribute.class);
                
                //positions
                PositionIncrementAttribute  posIncrAttribute = null;
                if(field.isStorePositionWithTermVector())
                    posIncrAttribute = (PositionIncrementAttribute) tokens.addAttribute(PositionIncrementAttribute.class);
                
                TermAttribute               termAttribute    = (TermAttribute) tokens.addAttribute(TermAttribute.class);

                //store normalizations of field per term per document rather than per field.
                //this adds more to write but less to read on other side
                Integer tokensInField = new Integer(0);
                
                while (tokens.incrementToken()  ) {
                    tokensInField++;
                    Term term = new Term(field.name(),termAttribute.term());
                	
                	allIndexedTerms.add(term);

                	//fetch all collected information for this term
                	Map<String,List<Number>> termInfo = allTermInformation.get(term);

                	if (termInfo == null) {
                		termInfo = new HashMap<String,List<Number>>();
                		allTermInformation.put(term, termInfo);
                	}

                	//term frequency
                	{
                	   List<Number> termFrequency = termInfo.get(CassandraUtils.termFrequencyKey);
                	               	
                	   if(termFrequency == null){
                	       termFrequency = new ArrayList<Number>();
                	       termFrequency.add(new Integer(0));
                	       termInfo.put(CassandraUtils.termFrequencyKey, termFrequency);
                	   }
                	
                	   //increment
                	   termFrequency.set(0, termFrequency.get(0).intValue()+1);                	   
                	}
                	
                	               	
                	//position vector
                	if(field.isStorePositionWithTermVector()){
                	    position += (posIncrAttribute.getPositionIncrement() - 1);
                	    
                	    List<Number> positionVector = termInfo.get(CassandraUtils.positionVectorKey);
                	    
                	    if(positionVector == null){
                	        positionVector = new ArrayList<Number>();
                	        termInfo.put(CassandraUtils.positionVectorKey, positionVector);
                	    }
                	    
                        positionVector.add(++position);
                	}
                	
                	//term offsets
                	if(field.isStoreOffsetWithTermVector()){

                	    List<Number> offsetVector = termInfo.get(CassandraUtils.offsetVectorKey);
                	    if(offsetVector == null){
                	        offsetVector = new ArrayList<Number>();
                	        termInfo.put(CassandraUtils.offsetVectorKey, offsetVector);
                	    }
                	    
                	    offsetVector.add( lastOffset + offsetAttribute.startOffset());
                        offsetVector.add( lastOffset + offsetAttribute.endOffset());
                        
                	}              	                	
                }

                List<Number> bnorm = null;
                if(!field.getOmitNorms()){
                    bnorm = new ArrayList<Number>();
                    float norm = doc.getBoost();
                    norm *= field.getBoost();
                    norm *= similarity.lengthNorm(field.name(), tokensInField);
                    bnorm.add(Similarity.encodeNorm(norm));
                }
                
                for (Map.Entry<Term, Map<String,List<Number>>> termEntry : allTermInformation.entrySet()) {

                    // Terms are stored within a unique key combination
                    // This is required since cassandra loads all columns
                    // in a key/column family into memory
                    Term term = termEntry.getKey();
                    ByteBuffer key = CassandraUtils.hashKeyBytes(indexName, CassandraUtils.delimeterBytes, term.field().getBytes("UTF-8"), CassandraUtils.delimeterBytes, term.text().getBytes("UTF-8"));
                    
                    //Mix in the norm for this field alongside each term
                    //more writes but faster on read side.
                    if(!field.getOmitNorms()){
                        termEntry.getValue().put(CassandraUtils.normsKey, bnorm );
                    }
                    
                    CassandraUtils.addToMutationMap(getMutationMap(), CassandraUtils.termVecColumnFamily, docId, key, null,termEntry.getValue());                    
                }
            }

            //Untokenized fields go in without a termPosition
            if (field.isIndexed() && !field.isTokenized()) {
                Term term = new Term(field.name(), field.stringValue());
                allIndexedTerms.add(term);
                
                ByteBuffer key = CassandraUtils.hashKeyBytes(indexName , CassandraUtils.delimeterBytes , term.field().getBytes("UTF-8"), CassandraUtils.delimeterBytes, term.text().getBytes("UTF-8"));

                Map<String,List<Number>> termMap = new HashMap<String,List<Number>>();
                termMap.put(CassandraUtils.termFrequencyKey, CassandraUtils.emptyArray);
                termMap.put(CassandraUtils.positionVectorKey, CassandraUtils.emptyArray);
                
                CassandraUtils.addToMutationMap(getMutationMap(), CassandraUtils.termVecColumnFamily, docId, key, null,termMap);
               
            }

            // Stores each field as a column under this doc key
            if (field.isStored()) {
                
                byte[] _value = field.isBinary() ? field.getBinaryValue() : field.stringValue().getBytes("UTF-8");         
                
                //first byte flags if binary or not
                byte[] value = new byte[_value.length+1];
                System.arraycopy(_value, 0, value, 0, _value.length);
                
                value[value.length-1] = (byte) (field.isBinary() ? Byte.MAX_VALUE : Byte.MIN_VALUE);
                
                ByteBuffer key = CassandraUtils.hashKeyBytes(indexName,CassandraUtils.delimeterBytes,docId);
                
                CassandraUtils.addToMutationMap(getMutationMap(), CassandraUtils.docColumnFamily, field.name().getBytes("UTF-8"), key, value, null);
                            
            }
        }
        
        //Finally, Store meta-data so we can delete this document
        ByteBuffer key = CassandraUtils.hashKeyBytes(indexName,CassandraUtils.delimeterBytes,docId);
        
        CassandraUtils.addToMutationMap(getMutationMap(), CassandraUtils.docColumnFamily, CassandraUtils.documentMetaField.getBytes(), key, CassandraUtils.toBytes(allIndexedTerms), null);
        
       
        
        if(autoCommit)
            CassandraUtils.robustBatchInsert(client, getMutationMap());    
    }

    public void deleteDocuments(Query query) throws CorruptIndexException, IOException {
        
        IndexReader   reader   = new IndexReader(new String(indexName),client);
        IndexSearcher searcher = new IndexSearcher(reader);
       
        TopDocs results = searcher.search(query,1000);
    
        for(int i=0; i<results.totalHits; i++){
            ScoreDoc doc = results.scoreDocs[i];
            
            
            ByteBuffer docId = reader.getDocumentId(doc.doc);
            try {
                deleteLucandraDocument(docId);
            } catch (InvalidRequestException e) {
                throw new RuntimeException(e);
            } catch (NotFoundException e) {
                throw new RuntimeException(e);
            } catch (UnavailableException e) {
                throw new RuntimeException(e);
            } catch (TimedOutException e) {
                throw new RuntimeException(e);
            } catch (TException e) {
                throw new RuntimeException(e);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);       
            }
        }
        
    }
    
    @SuppressWarnings("unchecked")
    public void deleteDocuments(Term term) throws CorruptIndexException, IOException {
        try {
                       
            ColumnParent cp = new ColumnParent(CassandraUtils.termVecColumnFamily);
            ByteBuffer key = CassandraUtils.hashKeyBytes(indexName,CassandraUtils.delimeterBytes,term.field().getBytes("UTF-8"), CassandraUtils.delimeterBytes, term.text().getBytes("UTF-8"));
            
            List<ColumnOrSuperColumn> docs = client.get_slice(key, cp, new SlicePredicate().setSlice_range(new SliceRange(CassandraUtils.emptyByteArray, CassandraUtils.emptyByteArray, true,Integer.MAX_VALUE)), ConsistencyLevel.ONE);
                
            //delete by documentId
            for(ColumnOrSuperColumn docInfo : docs){
                ByteBuffer docId = docInfo.getSuper_column().name;
                
                logger.debug(new String(docId.array(), docId.position()+docId.arrayOffset(), docId.remaining(),"UTF-8"));
                deleteLucandraDocument(docId);
            }
                              
        } catch (InvalidRequestException e) {
            throw new RuntimeException(e);
        } catch (UnavailableException e) {
            throw new RuntimeException(e);
        } catch (TException e) {
            throw new RuntimeException(e);
        } catch (TimedOutException e) {
            throw new RuntimeException(e);
        } catch (NotFoundException e) {
            throw new RuntimeException(e);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }  
    }
    
    private void deleteLucandraDocument(ByteBuffer docId) throws InvalidRequestException, NotFoundException, UnavailableException, TimedOutException, TException, IOException, ClassNotFoundException{

        byte[] docIdBytes = new byte[docId.remaining()];
        System.arraycopy(docId.array(), docId.position()+docId.arrayOffset(), docIdBytes, 0, docIdBytes.length);
        
        
        ByteBuffer key =  CassandraUtils.hashKeyBytes(indexName,CassandraUtils.delimeterBytes,docIdBytes);
        
        ColumnOrSuperColumn column = client.get(key, CassandraUtils.metaColumnPath, ConsistencyLevel.ONE);
        
        List<Term> terms = (List<Term>) CassandraUtils.fromBytes(column.column.value);
    
        for(Term term : terms){
            
            key = CassandraUtils.hashKeyBytes(indexName,CassandraUtils.delimeterBytes,term.field().getBytes("UTF-8"), CassandraUtils.delimeterBytes, term.text().getBytes("UTF-8"));
            
            CassandraUtils.addToMutationMap(getMutationMap(), CassandraUtils.termVecColumnFamily, docIdBytes, key, null, null);                                        
        }
           
        if(autoCommit)
            CassandraUtils.robustBatchInsert(client, getMutationMap());
        
        //finally delete ourselves
        ByteBuffer selfKey = CassandraUtils.hashKeyBytes(indexName,CassandraUtils.delimeterBytes,docIdBytes);
        
        
        //FIXME: once cassandra batch mutation supports slice predicates in deletions
        client.remove(selfKey, docAllColumnPath, System.currentTimeMillis(), ConsistencyLevel.ONE);

        
    }
    
    
    public void updateDocument(Term updateTerm, Document doc, Analyzer analyzer) throws CorruptIndexException, IOException{   
        
        deleteDocuments(updateTerm);
        addDocument(doc, analyzer);
        
    }

    public int docCount() {
        throw new IllegalStateException("Not supported");
    }

    public boolean isAutoCommit() {
        return autoCommit;
    }

    public void setAutoCommit(boolean autoCommit) {
        this.autoCommit = autoCommit;
    }
    
    public void commit(){
        if(!autoCommit)
            CassandraUtils.robustBatchInsert(client, getMutationMap());
    }
    
    private Map<ByteBuffer,Map<String,List<Mutation>>> getMutationMap() {
        
        Map<ByteBuffer,Map<String,List<Mutation>>> map = mutationMap.get();
        
        if(map == null){
            map = new ConcurrentSkipListMap<ByteBuffer,Map<String,List<Mutation>>>();
            mutationMap.set(map);
        }

        return map;
    }

}
