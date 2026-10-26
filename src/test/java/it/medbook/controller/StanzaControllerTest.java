package it.medbook.controller;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.medbook.service.StanzaService;
import it.medbook.repository.StanzaRepository;
import it.medbook.model.Stanza;
import it.medbook.exception.RisorsaNonTrovataException;
import it.medbook.support.TestHttpExchange;
import java.io.*;
import java.sql.SQLException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
class StanzaControllerTest {
    static class Repo extends StanzaRepository {
        int writes,reads;Integer id;Stanza input;Throwable failure;List<Stanza> list=List.of();
        Repo(){super(null);}
        void fail()throws SQLException{if(failure instanceof SQLException e)throw e;if(failure instanceof RuntimeException e)throw e;}
        @Override public Stanza create(Stanza s)throws SQLException{writes++;fail();input=s;s.setIdStanza(7);return s;}
        @Override public Stanza updateAtomically(Integer id,Stanza s)throws SQLException{this.id=id;return create(s);}
        @Override public List<Stanza> findAll()throws SQLException{reads++;fail();return list;}
    }
    final Repo repo=new Repo();final ObjectMapper mapper=new ObjectMapper();
    final StanzaController controller=new StanzaController(new StanzaService(repo),mapper);
    TestHttpExchange call(String method,String path,String body)throws Exception{var e=new TestHttpExchange(method,path,body);controller.handle(e);assertTrue(e.closed);return e;}
    @Test void getEmptyAndDtoArray()throws Exception{assertEquals("[]",call("GET","/api/stanze","").body());var v=new Stanza();v.setIdStanza(3);v.setNome("Sala");repo.list=List.of(v);var e=call("GET","/api/stanze","");assertEquals(200,e.status);var j=mapper.readTree(e.body()).get(0);assertEquals(3,j.size());assertEquals(3,j.get("idStanza").intValue());assertTrue(j.get("descrizione").isNull());}
    @ParameterizedTest @ValueSource(strings={"POST","PUT"})
    void writesUseRealServiceAndDedicatedDto(String method)throws Exception{for(String d:List.of("",",\"descrizione\":null",",\"descrizione\":\"\"",",\"descrizione\":\"  x  \"")){var e=call(method,method.equals("POST")?"/api/stanze":"/api/stanze/7","{\"nome\":\" Sala \""+d+"}");assertEquals(method.equals("POST")?201:200,e.status);var j=mapper.readTree(e.body());assertEquals(3,j.size());assertEquals(7,j.get("idStanza").intValue());assertEquals("Sala",j.get("nome").textValue());assertEquals(d.contains("  x  ")?"  x  ":d.endsWith("\"\"")?"":null,repo.input.getDescrizione());}}
    @ParameterizedTest @ValueSource(strings={"", "{", "null", "[]", "1", "{}", "{\"nome\":null}", "{\"nome\":7}", "{\"nome\":true}", "{\"nome\":\"a\",\"idStanza\":7}", "{\"nome\":\"a\",\"nome\":\"b\"}", "{\"nome\":\"a\",\"descrizione\":1}", "{\"nome\":\"a\",\"descrizione\":false}", "{\"nome\":\"a\"} {}"})
    void strictJsonNeverCallsRepository(String body)throws Exception{for(String method:List.of("POST","PUT")){assertEquals(400,call(method,method.equals("POST")?"/api/stanze":"/api/stanze/7",body).status);}assertEquals(0,repo.writes);}
    @Test void unicodeEscapesAndBoundariesThroughRealService()throws Exception{String escape=String.valueOf((char)92)+"u";for(String method:List.of("POST","PUT")){String path=method.equals("POST")?"/api/stanze":"/api/stanze/7";for(String bad:List.of("D800","DC00")){for(String f:List.of("nome","descrizione")){var body="{\"nome\":\"ok\",\"descrizione\":\"test\"}";body=body.replace(f.equals("nome")?"ok":"test",escape+bad);assertEquals(400,call(method,path,body).status);}}for(String body:List.of("{\"nome\":\""+"a".repeat(101)+"\"}","{\"nome\":\"a\",\"descrizione\":\""+"a".repeat(256)+"\"}"))assertEquals(400,call(method,path,body).status);}assertEquals(0,repo.writes);var e=call("POST","/api/stanze","{\"nome\":\"é "+escape+"D83D"+escape+"DE00\"}");assertEquals(201,e.status);assertEquals("é "+new String(Character.toChars(0x1f600)),mapper.readTree(e.body()).get("nome").textValue());}
    @Test void invalidUtf8AndOversizeBodiesAreRejected()throws Exception{var e=new TestHttpExchange("POST","/api/stanze","");e.setStreams(new ByteArrayInputStream(new byte[]{(byte)0xc3,0x28}),e.output);controller.handle(e);assertEquals(400,e.status);assertEquals(400,call("POST","/api/stanze","x".repeat(8193)).status);assertEquals(0,repo.writes);}
    @Test void contentTypeIsStrict()throws Exception{for(String type:List.of("text/plain","application/json; charset=latin1","application/json; extra=x")){var e=new TestHttpExchange("POST","/api/stanze","{}");e.requestHeaders.set("Content-Type",type);controller.handle(e);assertEquals(415,e.status);}var e=new TestHttpExchange("POST","/api/stanze","{}");e.requestHeaders.remove("Content-Type");controller.handle(e);assertEquals(415,e.status);e=new TestHttpExchange("POST","/api/stanze","{}");e.requestHeaders.add("Content-Type","application/json");controller.handle(e);assertEquals(415,e.status);assertEquals(0,repo.writes);}
    @Test void queriesAndUnknownCanonicalPathsAreRejected()throws Exception{for(String p:List.of("/api/stanze/extra","/api/stanze/0","/api/stanze/01","/api/stanze/2147483648","/api/stanze/7/extra","/api/stanze/7/","/api/stanze/%37","/api/stanze/"))for(String m:List.of("GET","PUT")){var e=call(m,p,"{}");assertEquals(404,e.status,p);assertNull(e.responseHeaders.getFirst("Allow"));}for(String m:List.of("GET","POST","PUT")){assertEquals(400,call(m,m.equals("PUT")?"/api/stanze/7?":"/api/stanze?x=1","{}").status);}assertEquals(0,repo.writes);assertEquals(0,repo.reads);}
    @Test void recognizedRoutesHaveCorrectAllow()throws Exception{for(String m:List.of("GET","POST","DELETE","PATCH","HEAD")){var e=call(m,"/api/stanze/7","");assertEquals(405,e.status);assertEquals("PUT",e.responseHeaders.getFirst("Allow"));}var e=call("DELETE","/api/stanze","");assertEquals(405,e.status);assertEquals("GET, POST",e.responseHeaders.getFirst("Allow"));}
    @Test void errorsAndSerializationDoNotLeakInternals()throws Exception{for(Throwable error:List.of(new SQLException("secret"),new RisorsaNonTrovataException("Stanza non trovata."),new StanzaRepository.NomeDuplicatoException(new SQLException("secret")))){repo.failure=error;var e=call("PUT","/api/stanze/7","{\"nome\":\"Sala\"}");assertEquals(error instanceof SQLException?500:error instanceof RisorsaNonTrovataException?404:409,e.status);assertFalse(e.body().contains("secret"));}repo.failure=null;var bad=new ObjectMapper(){@Override public String writeValueAsString(Object v)throws com.fasterxml.jackson.core.JsonProcessingException{throw new com.fasterxml.jackson.core.JsonProcessingException("secret"){};}};var e=new TestHttpExchange("POST","/api/stanze","{\"nome\":\"Sala\"}");new StanzaController(new StanzaService(repo),bad).handle(e);assertEquals(500,e.status);}
    @ParameterizedTest @ValueSource(strings={"POST","PUT"})
    void exactBodyByteLimitAcceptsValidJson(String method)throws Exception {
        String json="{\"nome\":\"Sala é\",\"descrizione\":\"test\"}";
        String body=json+" ".repeat(8192-json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertEquals(8192,body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        String path=method.equals("POST")?"/api/stanze":"/api/stanze/7";
        var accepted=call(method,path,body);assertEquals(method.equals("POST")?201:200,accepted.status);
        assertEquals("Sala é",mapper.readTree(accepted.body()).get("nome").textValue());
        assertEquals(1,repo.writes);
        assertEquals(8193,(body+" ").getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertEquals(400,call(method,path,body+" ").status);assertEquals(1,repo.writes);
    }
}
