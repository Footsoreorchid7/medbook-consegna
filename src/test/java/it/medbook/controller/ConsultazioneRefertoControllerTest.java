package it.medbook.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.databind.SerializationFeature;
import it.medbook.dto.RefertoResponse;
import it.medbook.exception.RisorsaNonTrovataException;
import it.medbook.referto.RefertoStorage;
import it.medbook.service.RefertoService;
import it.medbook.support.AuthenticatedTestExchange;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.nio.file.*;
import java.sql.SQLException;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class ConsultazioneRefertoControllerTest {
    @TempDir Path temp;
    final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final Fake service = new Fake();
    final RefertoController controller = new RefertoController(service,mapper);
    static class Fake extends RefertoService {
        int calls, patient; Exception failure; RefertoStorage.Download resource;
        Fake(){super(null,null,null);}
        void check(int actor)throws SQLException,IOException {calls++;patient=actor;if(failure instanceof SQLException e)throw e;if(failure instanceof IOException e)throw e;if(failure instanceof RuntimeException e)throw e;}
        @Override public RefertoResponse metadata(Integer id,Integer actor)throws SQLException {try{check(actor);}catch(IOException e){throw new IllegalStateException(e);}return new RefertoResponse(12,id,"referto-fittizio.pdf",LocalDateTime.of(2030,1,1,10,0));}
        @Override public RefertoStorage.Download download(Integer id,Integer actor)throws SQLException,IOException {check(actor);return resource;}
    }
    AuthenticatedTestExchange request(String suffix){return new AuthenticatedTestExchange("GET","/api/prenotazioni/197/referto"+suffix,"",7);}
    @Test void metadataOnlyFourFieldsAndSessionIdentity()throws Exception {
        var e=request("");controller.handle(e);var json=mapper.readTree(e.exchange.body());
        assertEquals(200,e.exchange.status);assertEquals(4,json.size());assertEquals(197,json.get("idPrenotazione").intValue());assertEquals(12,json.get("idReferto").intValue());assertEquals("2030-01-01T10:00:00",json.get("caricatoIl").textValue());assertEquals(7,service.patient);assertTrue(e.exchange.closed);assertFalse(e.exchange.body().contains("percorso"));
    }
    @Test void binaryOriginalAndHeadersAndClosure()throws Exception {
        byte[] bytes={0,1,(byte)255,20};Path p=Files.write(temp.resolve("original"),bytes);
        var channel=Files.newByteChannel(p);service.resource=new RefertoStorage.Download(channel);
        var e=request("/documento");controller.handle(e);assertEquals(200,e.exchange.status);assertArrayEquals(bytes,e.exchange.output.toByteArray());
        assertEquals("4",e.getResponseHeaders().getFirst("Content-Length"));assertEquals("application/pdf",e.getResponseHeaders().getFirst("Content-Type"));assertEquals("attachment; filename=\"referto-197.pdf\"",e.getResponseHeaders().getFirst("Content-Disposition"));assertEquals("no-store",e.getResponseHeaders().getFirst("Cache-Control"));assertEquals("nosniff",e.getResponseHeaders().getFirst("X-Content-Type-Options"));assertFalse(channel.isOpen());assertTrue(e.exchange.closed);
    }
    @ParameterizedTest @ValueSource(strings={"","/documento"}) void identical404AndGeneric500(String suffix)throws Exception {
        for(String reason:new String[]{"missing","foreign","absent"}){service.failure=new RisorsaNonTrovataException(reason);var e=request(suffix);controller.handle(e);assertEquals(404,e.exchange.status);assertEquals("{\"error\":\"Referto non trovato.\"}",e.exchange.body());}
        service.failure=new SQLException("private-secret-path");var e=request(suffix);controller.handle(e);assertEquals(500,e.exchange.status);assertFalse(e.exchange.body().contains("private-secret"));
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","01","foo","2147483648"})void invalidIds(String id)throws Exception {var e=new AuthenticatedTestExchange("GET","/api/prenotazioni/"+id+"/referto","",7);controller.handle(e);assertEquals(400,e.exchange.status);assertEquals(0,service.calls);}
    @ParameterizedTest @ValueSource(strings={"/api/prenotazioni/197/referto/","/api/prenotazioni/%31/referto","/api/prenotazioni/197/referto/documento/extra"})void exactRouting(String path)throws Exception {var e=new AuthenticatedTestExchange("GET",path,"",7);controller.handle(e);assertEquals(404,e.exchange.status);assertEquals(0,service.calls);}
    @Test void noQueryNoBodyAndAnonymous()throws Exception {
        var e=request("?idPaziente=99");controller.handle(e);assertEquals(400,e.exchange.status);
        e=new AuthenticatedTestExchange("GET","/api/prenotazioni/197/referto","{}",7);controller.handle(e);assertEquals(400,e.exchange.status);
        e=new AuthenticatedTestExchange("GET","/api/prenotazioni/197/referto","",null);controller.handle(e);assertEquals(401,e.exchange.status);assertEquals(0,service.calls);
    }
    @ParameterizedTest @ValueSource(strings={"HEAD","PUT","DELETE","POST"})void downloadMethod(String method)throws Exception {var e=new AuthenticatedTestExchange(method,"/api/prenotazioni/197/referto/documento","",7);controller.handle(e);assertEquals(405,e.exchange.status);assertEquals("GET",e.getResponseHeaders().getFirst("Allow"));}
    @Test void delegatingController()throws Exception {var e=request("");new PrenotazioneController(null,mapper,null,null,service).handle(e);assertEquals(200,e.exchange.status);}
    @Test void failedReadAfterHeadersDoesNotSendSecondResponse()throws Exception {
        Path p=Files.write(temp.resolve("file"),new byte[]{1});var channel=Files.newByteChannel(p);
        service.resource=new RefertoStorage.Download(channel){@Override public InputStream input(){return new InputStream(){public int read()throws IOException{throw new IOException("synthetic failure");}};}};
        var e=request("/documento");controller.handle(e);assertEquals(200,e.exchange.status);assertEquals(0,e.exchange.output.size());assertTrue(e.exchange.closed);assertFalse(channel.isOpen());
    }
    @Test void serializationFailureIsTechnical()throws Exception {
        ObjectMapper broken=new ObjectMapper(){@Override public byte[] writeValueAsBytes(Object value)throws com.fasterxml.jackson.core.JsonProcessingException {if(value instanceof RefertoResponse)throw new com.fasterxml.jackson.core.JsonProcessingException("synthetic"){};return super.writeValueAsBytes(value);}};
        var e=request("");new RefertoController(service,broken).handle(e);assertEquals(500,e.exchange.status);
    }
    @Test void missingFileBeforeHeadersReturnsGeneric500()throws Exception {
        service.failure=new IOException("private-directory");var e=request("/documento");controller.handle(e);
        assertEquals(500,e.exchange.status);assertFalse(e.exchange.body().contains("private-directory"));assertTrue(e.exchange.closed);
    }
    @Test void clientDisconnectClosesHandleWithoutSecondHeaders()throws Exception {
        var channel=Files.newByteChannel(Files.write(temp.resolve("download"),new byte[]{1,2,3}));service.resource=new RefertoStorage.Download(channel);
        var base=request("/documento");java.util.concurrent.atomic.AtomicInteger headers=new java.util.concurrent.atomic.AtomicInteger();
        com.sun.net.httpserver.HttpExchange faulty=new com.sun.net.httpserver.HttpExchange(){
            public com.sun.net.httpserver.Headers getRequestHeaders(){return base.getRequestHeaders();}
            public com.sun.net.httpserver.Headers getResponseHeaders(){return base.getResponseHeaders();}
            public java.net.URI getRequestURI(){return base.getRequestURI();}
            public String getRequestMethod(){return base.getRequestMethod();}
            public com.sun.net.httpserver.HttpContext getHttpContext(){return null;}
            public void close(){base.close();}
            public InputStream getRequestBody(){return base.getRequestBody();}
            public OutputStream getResponseBody(){return new OutputStream(){public void write(int b)throws IOException{throw new IOException("client disconnected");}};}
            public void sendResponseHeaders(int code,long length)throws IOException{headers.incrementAndGet();base.sendResponseHeaders(code,length);}
            public java.net.InetSocketAddress getRemoteAddress(){return base.getRemoteAddress();}
            public java.net.InetSocketAddress getLocalAddress(){return base.getLocalAddress();}
            public String getProtocol(){return "HTTP/1.1";}
            public int getResponseCode(){return base.getResponseCode();}
            public Object getAttribute(String name){return null;}
            public void setAttribute(String name,Object value){}
            public void setStreams(InputStream in,OutputStream out){}
            public com.sun.net.httpserver.HttpPrincipal getPrincipal(){return base.getPrincipal();}
        };
        controller.handle(faulty);assertEquals(1,headers.get());assertEquals(200,base.exchange.status);assertTrue(base.exchange.closed);assertFalse(channel.isOpen());
    }

}
