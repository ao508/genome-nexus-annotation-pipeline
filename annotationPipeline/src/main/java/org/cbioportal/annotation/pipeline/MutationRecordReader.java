/*
 * Copyright (c) 2016 Memorial Sloan-Kettering Cancer Center.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY, WITHOUT EVEN THE IMPLIED WARRANTY OF MERCHANTABILITY OR FITNESS
 * FOR A PARTICULAR PURPOSE. The software and documentation provided hereunder
 * is on an "as is" basis, and Memorial Sloan-Kettering Cancer Center has no
 * obligations to provide maintenance, support, updates, enhancements or
 * modifications. In no event shall Memorial Sloan-Kettering Cancer Center be
 * liable to any party for direct, indirect, special, incidental or
 * consequential damages, including lost profits, arising out of the use of this
 * software and its documentation, even if Memorial Sloan-Kettering Cancer
 * Center has been advised of the possibility of such damage.
 */

/*
 * This file is part of cBioPortal CMO-Pipelines.
 *
 * cBioPortal is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package org.cbioportal.annotation.pipeline;

import java.io.*;
import java.util.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.cbioportal.models.*;
import org.cbioportal.annotator.Annotator;
import org.springframework.batch.item.*;
import org.springframework.batch.item.file.*;
import org.springframework.batch.item.file.mapping.DefaultLineMapper;
import org.springframework.batch.item.file.transform.DelimitedLineTokenizer;
import org.springframework.beans.factory.annotation.*;
import org.springframework.core.io.FileSystemResource;

/**
 *
 * @author Zachary Heins
 */
public class MutationRecordReader  implements ItemStreamReader<AnnotatedRecord>{
    @Value("#{jobParameters[filename]}")
    private String filename;
    
    @Value("#{jobParameters[replace]}")
    private boolean replace;
    
    @Value("#{jobParameters[isoformOverride]}")
    private String isoformOverride;    
    
    private int failedAnnotations;
    private int failedNullHgvspAnnotations;
    private int failedMitochondrialAnnotations;
    
    private List<MutationRecord> mutationRecords = new ArrayList<>();
    private List<AnnotatedRecord> annotatedRecords = new ArrayList<>();
    private Set<String> header = new LinkedHashSet<>();
    
    @Autowired
    Annotator annotator;
    
    private static final Log LOG = LogFactory.getLog(MutationRecordReader.class);
    
    @Override
    public void open(ExecutionContext ec) throws ItemStreamException {
        
        processComments(ec);
        
        FlatFileItemReader<MutationRecord> reader = new FlatFileItemReader<>();
        reader.setResource(new FileSystemResource(filename));
        DefaultLineMapper<MutationRecord> mapper = new DefaultLineMapper<>();
        final DelimitedLineTokenizer tokenizer = new DelimitedLineTokenizer();
        tokenizer.setDelimiter("\t");
        mapper.setLineTokenizer(tokenizer);
        mapper.setFieldSetMapper(new MutationFieldSetMapper());
        reader.setLineMapper(mapper);
        reader.setLinesToSkip(1);
        reader.setSkippedLinesCallback(new LineCallbackHandler() {
                @Override
                public void handleLine(String line) {
                    tokenizer.setNames(line.split("\t"));
                }
        });        
        reader.open(ec);
        
        MutationRecord mutationRecord;        
        try {
            while((mutationRecord = reader.read()) != null) {
                mutationRecords.add(mutationRecord);
            }
        }
        catch(Exception e) {
            throw new ItemStreamException(e);
        }
        reader.close();
        
        for(MutationRecord record : mutationRecords) {
            AnnotatedRecord annotatedRecord = annotator.annotateRecord(record, replace, isoformOverride, true);
            if (annotatedRecord.getHGVSc().isEmpty() && annotatedRecord.getHGVSp().isEmpty()) {
                String message = "Failed to annotate record for sample " + record.getTumor_Sample_Barcode() + 
                            " and variant (chr,start,end,ref,alt): (" + record.getChromosome() + "," + 
                            record.getStart_Position() + "," + record.getEnd_Position() + "," + 
                            record.getReference_Allele() + "," + annotatedRecord.getTumor_Seq_Allele2() + ")";                
                if (annotator.isHgvspNullClassifications(annotatedRecord.getVariant_Classification())) {
                    failedNullHgvspAnnotations++;
                    message += " - Ignoring record with HGVSp null classification: " + annotatedRecord.getVariant_Classification();
                }
                else if (annotatedRecord.getChromosome().equals("M")) {
                    failedMitochondrialAnnotations++;
                    message += " - Cannot annotate mitochondrial variants at this time.";
                }
                failedAnnotations++;
                LOG.info(message);
            }            
            annotatedRecords.add(annotatedRecord);
            header.addAll(record.getHeaderWithAdditionalFields());
        }
        // log number of records that failed annotations
        LOG.info("Total records that failed annotation: " + failedAnnotations);
        LOG.info("# records with HGVSp null variant classification: " + failedNullHgvspAnnotations);
        LOG.info("# records with Mitochondrial variants: " + failedMitochondrialAnnotations);
        
        List<String> full_header = new ArrayList(header);
        ec.put("mutation_header", full_header);
    }

    @Override
    public void update(ExecutionContext ec) throws ItemStreamException {}

    @Override
    public void close() throws ItemStreamException {}

    @Override
    public AnnotatedRecord read() throws Exception {
        if (!annotatedRecords.isEmpty()) {            
            return annotatedRecords.remove(0);         
        }
        return null;
    }
    
    private void processComments(ExecutionContext ec) {
        List<String> comments = new ArrayList<>();
        String sequencedSamples = "";
        BufferedReader reader = null;
        try {            
            reader = new BufferedReader(new FileReader(filename));
            String line;
            while((line = reader.readLine()) != null) {     
                if (line.startsWith("#")) {
                    comments.add(line);
                }
                else {
                    // no more comments, go on processing
                    break;
                }
            }            
            reader.close();
        }        
        catch (Exception e) {
            throw new ItemStreamException(e);
        }
        
        // Add comments to the config for the writer to access later
        ec.put("commentLines", comments);
    }
    
}
