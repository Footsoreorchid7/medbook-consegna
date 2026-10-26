package it.medbook.service;

import it.medbook.config.*;
import it.medbook.dto.*;
import it.medbook.exception.*;
import it.medbook.referto.*;
import it.medbook.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class RefertoServiceTest {
    @TempDir Path temp;
    @BeforeEach void canonicalTemp() throws IOException { temp = temp.toRealPath(); }
    @Test void validationPrecedesPublicationAndDatabase()throws Exception{
        var storage=new RefertoStorage(temp.resolve("private"));int[] calls={0};
        var repo=new RefertoRepository(null,null){@Override public void verifyCompetence(int b,int d){}@Override public CaricamentoRefertoResponse saveAtomically(int b,int d,String key,String name,Outcome outcome){calls[0]++;assertTrue(Files.exists(temp.resolve("private").resolve(key)));var r=new CaricamentoRefertoResponse();r.setIdReferto(12);return r;}};
        var service=new RefertoService(repo,storage,validator(storage,false));
        assertEquals(12,service.upload(1,7,new ByteArrayInputStream(new byte[]{1}),null).getIdReferto());assertEquals(1,calls[0]);
    }
    PdfValidator validator(RefertoStorage storage,boolean reject)throws IOException{
        return new PdfValidator(null,storage.scratch()){@Override public void validate(Path file)throws IOException{assertTrue(Files.exists(file));if(reject)throw new PdfRifiutatoException();}};
    }
    @Test void rejectedPdfNeverPublishedOrInserted()throws Exception{
        var storage=new RefertoStorage(temp.resolve("private"));var service=new RefertoService(new RefertoRepository(null,null){@Override public void verifyCompetence(int b,int d){}},storage,validator(storage,true));
        assertThrows(PdfRifiutatoException.class,()->service.upload(1,7,new ByteArrayInputStream(new byte[]{1}),null));assertEmpty("documents");assertEmpty("staging");
    }
    @Test void certainPreInsertConflictCleansCandidate()throws Exception{
        var storage=new RefertoStorage(temp.resolve("private"));var repo=new RefertoRepository(null,null){@Override public void verifyCompetence(int b,int d){}@Override public CaricamentoRefertoResponse saveAtomically(int b,int d,String key,String name,Outcome outcome){throw new ConflittoPrenotazioneException("conflict");}};
        var service=new RefertoService(repo,storage,validator(storage,false));assertThrows(ConflittoPrenotazioneException.class,()->service.upload(1,7,new ByteArrayInputStream(new byte[]{1}),null));assertEmpty("documents");
    }
    @Test void invalidIdsRejectedBeforeReception()throws Exception{
        var service=new RefertoService(null,null,null);for(Integer id:new Integer[]{null,0,-1}){assertThrows(IllegalArgumentException.class,()->service.upload(id,7,InputStream.nullInputStream(),null));assertThrows(IllegalArgumentException.class,()->service.upload(1,id,InputStream.nullInputStream(),null));}
    }
    void assertEmpty(String folder)throws IOException{try(var paths=Files.list(temp.resolve("private").resolve(folder))){assertEquals(0,paths.count());}}
    @Test void preliminary404PreventsReceptionValidationAndPublication()throws Exception {
        var storage=new RefertoStorage(temp.resolve("private"));int[] calls={0};
        var repository=new RefertoRepository(null,null){@Override public void verifyCompetence(int b,int d){calls[0]++;assertEquals(1,b);assertEquals(7,d);throw new RisorsaNonTrovataException("not found");}};
        var service=new RefertoService(repository,storage,new PdfValidator(null,storage.scratch()){@Override public void validate(Path p){fail("Validation must not run");}});
        InputStream body=new InputStream(){public int read(){fail("Body must not be consumed");return -1;}};
        assertThrows(RisorsaNonTrovataException.class,()->service.upload(1,7,body,null));assertEquals(1,calls[0]);assertEmpty("staging");assertEmpty("documents");
    }
    @Test void finalCheckStillRejectsStateChangedAfterPreliminaryCheck()throws Exception {
        var storage=new RefertoStorage(temp.resolve("private"));int[] calls={0};
        var repository=new RefertoRepository(null,null){@Override public void verifyCompetence(int b,int d){calls[0]++;}@Override public CaricamentoRefertoResponse saveAtomically(int b,int d,String key,String name,Outcome outcome){calls[0]++;throw new ConflittoPrenotazioneException("state changed");}};
        var service=new RefertoService(repository,storage,validator(storage,false));
        assertThrows(ConflittoPrenotazioneException.class,()->service.upload(1,7,new ByteArrayInputStream(new byte[]{1}),null));assertEquals(2,calls[0]);assertEmpty("staging");assertEmpty("documents");
    }

}
