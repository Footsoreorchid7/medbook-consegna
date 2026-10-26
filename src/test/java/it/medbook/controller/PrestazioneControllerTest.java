package it.medbook.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import it.medbook.model.Prestazione;
import it.medbook.dto.PrestazioneRequest;
import it.medbook.support.TestHttpExchange;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import it.medbook.service.PrestazioneService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PrestazioneControllerTest {
    private final FakePrestazioneService service = new FakePrestazioneService();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PrestazioneController controller = new PrestazioneController(service, objectMapper);

    @Test
    void getReturns200() throws IOException {
        FakeHttpExchange exchange = new FakeHttpExchange("GET");
        controller.handle(exchange);
        assertEquals(200, exchange.status);
        assertEquals(1, service.calls);
    }

    @Test
    void getReturnsJsonContentType() throws IOException {
        FakeHttpExchange exchange = new FakeHttpExchange("GET");
        controller.handle(exchange);
        assertEquals("application/json; charset=UTF-8",
                exchange.responseHeaders.getFirst("Content-Type"));
    }

    @Test
    void getSerializesPrestazioniInUtf8() throws IOException {
        Prestazione prestazione = new Prestazione();
        prestazione.setIdPrestazione(1);
        prestazione.setNome("Visita cardiologica");
        prestazione.setDescrizione("Controllo dell’attività cardiaca");
        prestazione.setDurata(30);
        prestazione.setPrezzo(new BigDecimal("85.50"));
        service.prestazioni = List.of(prestazione);
        FakeHttpExchange exchange = new FakeHttpExchange("GET");

        controller.handle(exchange);

        JsonNode json = objectMapper.readTree(exchange.body());
        assertTrue(json.isArray());
        assertEquals(1, json.size());
        assertEquals(1, json.get(0).get("idPrestazione").asInt());
        assertEquals(prestazione.getNome(), json.get(0).get("nome").asText());
        assertEquals(prestazione.getDescrizione(), json.get(0).get("descrizione").asText());
        assertEquals(30, json.get(0).get("durata").asInt());
        assertEquals(0, prestazione.getPrezzo().compareTo(json.get(0).get("prezzo").decimalValue()));
        assertEquals(exchange.responseBody.size(), exchange.responseLength);
    }

    @Test
    void getReturnsEmptyJsonArrayForNoPrestazioni() throws IOException {
        FakeHttpExchange exchange = new FakeHttpExchange("GET");
        controller.handle(exchange);
        assertEquals("[]", exchange.body());
    }

    @Test
    void otherMethodsReturn405WithoutCallingService() throws IOException {
        for (String method : List.of("PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")) {
            FakeHttpExchange exchange = new FakeHttpExchange(method);
            controller.handle(exchange);
            assertEquals(405, exchange.status);
            assertEquals("GET, POST", exchange.responseHeaders.getFirst("Allow"));
            assertEquals(0, service.calls);
            assertTrue(exchange.closed);
            assertTrue(exchange.outputClosed);
        }
    }

    @Test
    void sqlExceptionReturns500WithoutTechnicalDetails() throws IOException {
        String technicalMessage = "Dettaglio tecnico simulato: SELECT * FROM prestazione";
        service.error = new SQLException(technicalMessage);
        FakeHttpExchange exchange = new FakeHttpExchange("GET");

        controller.handle(exchange);

        assertEquals(500, exchange.status);
        assertEquals(1, service.calls);
        assertEquals("application/json; charset=UTF-8",
                exchange.responseHeaders.getFirst("Content-Type"));
        assertEquals("Errore interno del server.", objectMapper.readTree(exchange.body()).get("error").asText());
        assertFalse(exchange.body().contains(technicalMessage));
        assertFalse(exchange.body().contains("SQLException"));
        assertTrue(exchange.closed);
        assertTrue(exchange.outputClosed);
    }

    @Test
    void getClosesExchangeAndResponseStream() throws IOException {
        FakeHttpExchange exchange = new FakeHttpExchange("GET");
        controller.handle(exchange);
        assertTrue(exchange.closed);
        assertTrue(exchange.outputClosed);
    }


    private static final String VALID="{\"nome\":\"Visita\",\"durata\":30,\"prezzo\":85.000}";
    private TestHttpExchange mutation(String method,String path,String body) {var e=new TestHttpExchange(method,path,body);e.requestHeaders.set("Content-Type","application/json; charset=UTF-8");return e;}
    @Test void postAndPutReturnResourceDtoAndClose() throws Exception {
        for(String method:List.of("POST","PUT")) {
            var e=mutation(method,method.equals("POST")?"/api/prestazioni":"/api/prestazioni/7",VALID);controller.handle(e);
            assertEquals(method.equals("POST")?201:200,e.status);var json=objectMapper.readTree(e.body());assertEquals(7,json.get("idPrestazione").intValue());
            assertEquals("Visita",json.get("nome").textValue());assertTrue(json.get("descrizione").isNull());assertEquals(5,json.size());
            assertEquals("application/json; charset=UTF-8",e.responseHeaders.getFirst("Content-Type"));assertTrue(e.closed);
        }assertEquals(2,service.writes);assertEquals(7,service.id);assertEquals(new BigDecimal("85"),service.request.getPrezzo().stripTrailingZeros());
    }
    @ParameterizedTest @ValueSource(strings={"", "{", "null", "[]", "1", "{}",
        "{\"nome\":\"a\",\"durata\":30,\"prezzo\":1,\"extra\":0}",
        "{\"nome\":\"a\",\"nome\":\"b\",\"durata\":30,\"prezzo\":1}",
        "{\"nome\":\"a\",\"durata\":\"30\",\"prezzo\":1}",
        "{\"nome\":\"a\",\"durata\":30.0,\"prezzo\":1}",
        "{\"nome\":\"a\",\"durata\":true,\"prezzo\":1}",
        "{\"nome\":1,\"durata\":30,\"prezzo\":1}",
        "{\"nome\":\"a\",\"durata\":30,\"prezzo\":\"1\"}",
        "{\"nome\":\"a\",\"durata\":30,\"prezzo\":true}",
        "{\"nome\":\"a\",\"durata\":30,\"prezzo\":1,\"descrizione\":1}"})
    void invalidJsonDoesNotReachService(String body) throws Exception {var e=mutation("POST","/api/prestazioni",body);controller.handle(e);assertEquals(400,e.status);assertEquals(0,service.writes);assertTrue(e.closed);}
    @Test void trailingOversizeAndInvalidUtf8AreRejected() throws Exception {
        for(String body:List.of(VALID+" {}","x".repeat(8193))) {var e=mutation("POST","/api/prestazioni",body);controller.handle(e);assertEquals(400,e.status);}
        var e=mutation("POST","/api/prestazioni",VALID);e.setStreams(new ByteArrayInputStream(new byte[]{(byte)0xc3,0x28}),e.getResponseBody());
        controller.handle(e);assertEquals(400,e.status);assertEquals(0,service.writes);
    }
    @Test void descriptionsArePassedWithoutCoercion() throws Exception {
        for(String suffix:List.of("",",\"descrizione\":null",",\"descrizione\":\"\"")) {
            var e=mutation("PUT","/api/prestazioni/7",VALID.substring(0,VALID.length()-1)+suffix+"}");controller.handle(e);assertEquals(200,e.status);
            assertEquals(suffix.endsWith("\"\"")?"":null,service.request.getDescrizione());
        }
    }
    @Test void mediaIdsQueryAndRoutingAreStrict() throws Exception {
        for(String type:List.of("text/plain","application/json; charset=ISO-8859-1","application/json; extra=x")) {
            var e=mutation("POST","/api/prestazioni",VALID);e.requestHeaders.set("Content-Type",type);controller.handle(e);assertEquals(415,e.status);
        }
        var missing=mutation("POST","/api/prestazioni",VALID);missing.requestHeaders.remove("Content-Type");controller.handle(missing);assertEquals(415,missing.status);
        for(String path:List.of("/api/prestazioni/0","/api/prestazioni/-1","/api/prestazioni/x","/api/prestazioni/2147483648","/api/prestazioni/01")) {var e=mutation("PUT",path,VALID);controller.handle(e);assertEquals(404,e.status,path);}
        for(String path:List.of("/api/prestazioni?x=1","/api/prestazioni/7?")) {var e=mutation(path.contains("/7")?"PUT":"POST",path,VALID);controller.handle(e);assertEquals(400,e.status);}
        for(String path:List.of("/api/prestazioni/","/api/prestazioni/7/extra","/api/prestazioni/%37")) {var e=mutation("PUT",path,VALID);controller.handle(e);assertEquals(404,e.status);}
        for(String method:List.of("GET","POST","DELETE","PATCH")) {var e=mutation(method,"/api/prestazioni/7",VALID);controller.handle(e);assertEquals(405,e.status);assertEquals("PUT",e.responseHeaders.getFirst("Allow"));}
        assertEquals(0,service.writes);
    }
    @Test void writeErrorsAndSerializationErrorsAreCorrectlyClassified() throws Exception {
        for(RuntimeException failure:List.of(new IllegalArgumentException(),new it.medbook.exception.RisorsaNonTrovataException("assente"),new it.medbook.exception.ConflittoConfigurazioneException("conflitto"))) {
            service.writeFailure=failure;var e=mutation("PUT","/api/prestazioni/7",VALID);controller.handle(e);assertEquals(failure instanceof IllegalArgumentException?400:failure instanceof it.medbook.exception.RisorsaNonTrovataException?404:409,e.status);
        }
        service.writeFailure=null;service.error=new SQLException("secret sql");var e=mutation("POST","/api/prestazioni",VALID);controller.handle(e);assertEquals(500,e.status);assertFalse(e.body().contains("secret"));
        service.error=null;ObjectMapper failing=new ObjectMapper(){@Override public String writeValueAsString(Object value)throws com.fasterxml.jackson.core.JsonProcessingException{throw new com.fasterxml.jackson.core.JsonProcessingException("synthetic"){};}};
        e=mutation("POST","/api/prestazioni",VALID);new PrestazioneController(service,failing).handle(e);assertEquals(500,e.status);
    }
    @Test void publicGetStillIgnoresQueryAndReturnsOriginalArray() throws Exception {var e=new TestHttpExchange("GET","/api/prestazioni?unused=true","ignored");controller.handle(e);assertEquals(200,e.status);assertEquals("[]",e.body());}
    @Test void onlyCanonicalIntegerResourcePathsAreRecognized() throws Exception {
        for(String path:List.of("/api/prestazioni/7","/api/prestazioni/2147483647")) {
            var e=mutation("GET",path,"");controller.handle(e);assertEquals(405,e.status);assertEquals("PUT",e.responseHeaders.getFirst("Allow"));
        }
        for(String path:List.of("/api/prestazioni/extra","/api/prestazioni/0","/api/prestazioni/-1","/api/prestazioni/01","/api/prestazioni/2147483648","/api/prestazioni/99999999999999999999","/api/prestazioni/7/extra","/api/prestazioni/7/","/api/prestazioni/%37")) {
            for(String method:List.of("GET","PUT")) {var e=mutation(method,path,VALID);controller.handle(e);assertEquals(404,e.status,path);assertNull(e.responseHeaders.getFirst("Allow"));}
        }assertEquals(0,service.writes);assertEquals(0,service.calls);
    }

    @ParameterizedTest @ValueSource(strings={"POST", "PUT"})
    void jsonUnicodeEscapesAreValidatedByRealService(String method) throws Exception {
        int[] writes={0};
        var repository=new it.medbook.repository.PrestazioneRepository(null) {
            @Override public Prestazione create(Prestazione value) { writes[0]++;value.setIdPrestazione(7);return value; }
            @Override public Prestazione updateAtomically(Integer id,Prestazione value) { return create(value); }
        };
        var realController=new PrestazioneController(new PrestazioneService(repository),objectMapper);
        String path=method.equals("POST")?"/api/prestazioni":"/api/prestazioni/7";
        String escape=String.valueOf((char)92) + "u";
        for (String bad:List.of("D800","DC00")) {
            for (String field:List.of("nome","descrizione")) {
                String body="{\"nome\":\"Visita\",\"descrizione\":\"testo\",\"durata\":30,\"prezzo\":85}";
                body=body.replace(field.equals("nome")?"Visita":"testo",escape+bad);
                var e=mutation(method,path,body);realController.handle(e);
                assertEquals(400,e.status);assertTrue(e.closed);assertEquals(0,writes[0]);
            }
        }
        String text="é " + new String(Character.toChars(0x1f600));
        String escaped="é " + escape + "D83D" + escape + "DE00";
        var e=mutation(method,path,"{\"nome\":\""+escaped+"\",\"descrizione\":\""+escaped+"\",\"durata\":30,\"prezzo\":85}");
        realController.handle(e);assertEquals(method.equals("POST")?201:200,e.status);
        var json=objectMapper.readTree(e.body());assertEquals(text,json.get("nome").textValue());
        assertEquals(text,json.get("descrizione").textValue());assertEquals(1,writes[0]);
    }

    private static class FakePrestazioneService extends PrestazioneService {
        private List<Prestazione> prestazioni = List.of();
        private int calls;
        private SQLException error;
        private int writes;private Integer id;private PrestazioneRequest request;private RuntimeException writeFailure;
        @Override public Prestazione creaPrestazione(PrestazioneRequest r)throws SQLException {
            writes++;request=r;if(error!=null)throw error;if(writeFailure!=null)throw writeFailure;
            var p=new Prestazione();p.setIdPrestazione(7);p.setNome(r.getNome());p.setDescrizione(r.getDescrizione());p.setDurata(r.getDurata());p.setPrezzo(r.getPrezzo());return p;
        }
        @Override public Prestazione aggiornaPrestazione(Integer value,PrestazioneRequest r)throws SQLException {id=value;return creaPrestazione(r);}


        FakePrestazioneService() {
            // Il metodo di lettura è sostituito dal fake e non utilizza repository.
            super(null);
        }

        @Override
        public List<Prestazione> getAllPrestazioni() throws SQLException {
            calls++;
            if (error != null) {
                throw error;
            }
            return prestazioni;
        }
    }

    private static class FakeHttpExchange extends HttpExchange {
        private final String method;
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final Map<String, Object> attributes = new HashMap<>();
        private boolean closed;
        private boolean outputClosed;
        private int status;
        private long responseLength;
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream() {
            @Override
            public void close() throws IOException {
                outputClosed = true;
                super.close();
            }
        };
        private InputStream input = new ByteArrayInputStream(new byte[0]);
        private OutputStream output = responseBody;

        FakeHttpExchange(String method) {
            this.method = method;
        }

        String body() {
            return responseBody.toString(StandardCharsets.UTF_8);
        }

        @Override
        public Headers getRequestHeaders() { return requestHeaders; }

        @Override
        public Headers getResponseHeaders() { return responseHeaders; }

        @Override
        public URI getRequestURI() { return URI.create("/api/prestazioni"); }

        @Override
        public String getRequestMethod() { return method; }

        @Override
        public HttpContext getHttpContext() { return null; }

        @Override
        public void close() { closed = true; }

        @Override
        public InputStream getRequestBody() { return input; }

        @Override
        public OutputStream getResponseBody() { return output; }

        @Override
        public void sendResponseHeaders(int status, long responseLength) {
            this.status = status;
            this.responseLength = responseLength;
        }

        @Override
        public InetSocketAddress getRemoteAddress() { return null; }

        @Override
        public int getResponseCode() { return status; }

        @Override
        public InetSocketAddress getLocalAddress() { return null; }

        @Override
        public String getProtocol() { return "HTTP/1.1"; }

        @Override
        public Object getAttribute(String name) { return attributes.get(name); }

        @Override
        public void setAttribute(String name, Object value) { attributes.put(name, value); }

        @Override
        public void setStreams(InputStream input, OutputStream output) {
            this.input = input;
            this.output = output;
        }

        @Override
        public HttpPrincipal getPrincipal() { return null; }
    }
}
