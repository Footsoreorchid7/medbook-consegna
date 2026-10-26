package it.medbook.controller;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import it.medbook.dto.CaricamentoRefertoResponse;
import it.medbook.exception.*;
import it.medbook.referto.*;
import it.medbook.service.RefertoService;
import it.medbook.support.AuthenticatedTestExchange;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.sql.SQLException;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class RefertoControllerTest {
    final ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final Fake service=new Fake();
    final RefertoController controller=new RefertoController(service,mapper);
    static class Fake extends RefertoService {
        int calls,booking,doctor;Long length;byte[] body;Exception failure;
        Fake(){super(null,null,null);}
        @Override public CaricamentoRefertoResponse upload(Integer b,Integer d,InputStream input,Long size)throws IOException,SQLException {
            calls++;booking=b;doctor=d;length=size;body=input.readAllBytes();
            if(failure instanceof IOException e)throw e;if(failure instanceof SQLException e)throw e;if(failure instanceof RuntimeException e)throw e;
            var r=new CaricamentoRefertoResponse();r.setIdReferto(12);r.setIdPrenotazione(b);r.setNomeFile("referto-test.pdf");r.setCaricatoIl(LocalDateTime.of(2030,1,1,9,0));return r;
        }
    }
    AuthenticatedTestExchange exchange(String method,String path,Integer doctor){var e=new AuthenticatedTestExchange(method,path,"binary",doctor);e.getRequestHeaders().set("Content-Type","application/pdf");return e;}
    AuthenticatedTestExchange request(){return exchange("POST","/api/prenotazioni/197/referto",7);}
    @Test void successBinaryAndMinimalSafeDto()throws Exception {
        var e=request();byte[] input={0,(byte)255,20};e.setStreams(new ByteArrayInputStream(input),null);controller.handle(e);
        assertEquals(201,e.exchange.status);assertEquals(197,service.booking);assertEquals(7,service.doctor);assertArrayEquals(input,service.body);
        JsonNode json=mapper.readTree(e.exchange.body());assertEquals(4,json.size());assertEquals(12,json.get("idReferto").intValue());assertEquals(197,json.get("idPrenotazione").intValue());assertEquals("2030-01-01T09:00:00",json.get("caricatoIl").textValue());
        assertFalse(e.exchange.body().contains("percorso"));assertEquals("application/json; charset=UTF-8",e.getResponseHeaders().getFirst("Content-Type"));assertTrue(e.exchange.closed);
    }
    @ParameterizedTest @ValueSource(strings={"PUT","DELETE","PATCH"}) void method405(String method)throws Exception {var e=exchange(method,"/api/prenotazioni/197/referto",7);controller.handle(e);assertEquals(405,e.exchange.status);assertEquals("GET, POST",e.getResponseHeaders().getFirst("Allow"));assertEquals(0,service.calls);}
    @ParameterizedTest @ValueSource(strings={"/api/prenotazioni/197/referto/","/api/prenotazioni/197/referto/documento/extra","/api/prenotazioni/%31/referto"}) void exactPathOnly(String path)throws Exception{var e=exchange("POST",path,7);controller.handle(e);assertEquals(404,e.exchange.status);assertEquals(0,service.calls);}
    @ParameterizedTest @ValueSource(strings={"0","-1","foo","2147483648","01"})void invalidIds(String id)throws Exception{var e=exchange("POST","/api/prenotazioni/"+id+"/referto",7);controller.handle(e);assertEquals(400,e.exchange.status);assertEquals(0,service.calls);}
    @Test void queryRejectedAndSessionRequired()throws Exception{var e=exchange("POST","/api/prenotazioni/197/referto?x=1",7);controller.handle(e);assertEquals(400,e.exchange.status);e=exchange("POST","/api/prenotazioni/197/referto",null);controller.handle(e);assertEquals(401,e.exchange.status);assertEquals(0,service.calls);}
    @Test void wrongMimeAndEncodingRejected()throws Exception{var e=request();e.getRequestHeaders().set("Content-Type","application/json");controller.handle(e);assertEquals(415,e.exchange.status);e=request();e.getRequestHeaders().set("Content-Encoding","gzip");controller.handle(e);assertEquals(400,e.exchange.status);assertEquals(0,service.calls);}
    @ParameterizedTest @ValueSource(strings={"-1","no","1,2","9223372036854775808"})void malformedLengths(String length)throws Exception{var e=request();e.getRequestHeaders().set("Content-Length",length);controller.handle(e);assertEquals(400,e.exchange.status);assertEquals(0,service.calls);}
    @Test void duplicatedHeaderAndAmbiguousFramingRejected()throws Exception{var e=request();e.getRequestHeaders().add("Content-Length","1");e.getRequestHeaders().add("Content-Length","1");controller.handle(e);assertEquals(400,e.exchange.status);e=request();e.getRequestHeaders().set("Content-Length","6");e.getRequestHeaders().set("Transfer-Encoding","chunked");controller.handle(e);assertEquals(400,e.exchange.status);assertEquals(0,service.calls);}
    @Test void declaredLengthForwarded()throws Exception{var e=request();e.getRequestHeaders().set("Content-Length","6");controller.handle(e);assertEquals(6L,service.length);}
    @Test void errorMappingAndNoTechnicalExposure()throws Exception{
        Exception[] failures={new RisorsaNonTrovataException("other patient"),new ConflittoPrenotazioneException("Referto già presente."),new PdfRifiutatoException(),new UploadNonValidoException(413),new SQLException("secret-path"),new IOException("secret-path")};int[] codes={404,409,400,413,500,500};
        for(int i=0;i<failures.length;i++){service.failure=failures[i];var e=request();controller.handle(e);assertEquals(codes[i],e.exchange.status);assertFalse(e.exchange.body().contains("secret-path"));assertFalse(e.exchange.body().contains("other patient"));assertTrue(e.exchange.closed);}
    }
    @Test void serializationFailureIs500AfterServiceSuccess()throws Exception{
        ObjectMapper broken=new ObjectMapper(){@Override public byte[] writeValueAsBytes(Object value)throws com.fasterxml.jackson.core.JsonProcessingException{if(value instanceof CaricamentoRefertoResponse)throw new com.fasterxml.jackson.core.JsonProcessingException("synthetic"){};return super.writeValueAsBytes(value);}};
        var e=request();new RefertoController(service,broken).handle(e);assertEquals(500,e.exchange.status);assertEquals(1,service.calls);
    }
    @Test void prenotazioneDelegatesNewRouteWithoutBreakingConstructorCompatibility()throws Exception{var e=request();new PrenotazioneController(null,mapper,null,null,service).handle(e);assertEquals(201,e.exchange.status);assertEquals(1,service.calls);}
    @Test void unexpectedRuntimeFailureReturnsGeneric500()throws Exception {
        service.failure=new IllegalStateException("internal-private-path");var e=request();controller.handle(e);
        assertEquals(500,e.exchange.status);assertFalse(e.exchange.body().contains("internal-private-path"));assertTrue(e.exchange.closed);
    }

    @Test void syntaxAndMimePrecedePerimeterBut404PrecedesPdfBodyValidation()throws Exception {
        RefertoService perimeter=new RefertoService(new it.medbook.repository.RefertoRepository(null,null){
            @Override public void verifyCompetence(int booking,int doctor){throw new RisorsaNonTrovataException("missing or foreign");}
        },null,null);
        RefertoController real=new RefertoController(perimeter,mapper);
        var invalid=exchange("POST","/api/prenotazioni/0/referto",7);real.handle(invalid);assertEquals(400,invalid.exchange.status);
        var mime=request();mime.getRequestHeaders().set("Content-Type","text/plain");real.handle(mime);assertEquals(415,mime.exchange.status);
        var foreign=request();foreign.setStreams(new InputStream(){public int read(){fail("Foreign body must not be consumed");return -1;}},null);
        real.handle(foreign);assertEquals(404,foreign.exchange.status);assertFalse(foreign.exchange.body().contains("foreign"));
    }

}
