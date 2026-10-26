package it.medbook.support;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Exchange isolato per richiesta: nessun database e nessun attributo di contesto condiviso. */
public final class TestHttpExchange extends HttpExchange {
    private final String method;
    private final URI uri;
    public final Headers requestHeaders=new Headers(),responseHeaders=new Headers();
    public final ByteArrayOutputStream output=new ByteArrayOutputStream();
    private InputStream input;
    public int status;
    public boolean closed;
    public TestHttpExchange(String method,String uri,String body) {
        this.method=method; this.uri=URI.create(uri);
        input=new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        requestHeaders.set("Content-Type","application/json");
    }
    public String body(){return output.toString(StandardCharsets.UTF_8);}
    @Override public Headers getRequestHeaders(){return requestHeaders;}
    @Override public Headers getResponseHeaders(){return responseHeaders;}
    @Override public URI getRequestURI(){return uri;}
    @Override public String getRequestMethod(){return method;}
    @Override public HttpContext getHttpContext(){return null;}
    @Override public void close(){closed=true;}
    @Override public InputStream getRequestBody(){return input;}
    @Override public OutputStream getResponseBody(){return output;}
    @Override public void sendResponseHeaders(int status,long length){this.status=status;}
    @Override public InetSocketAddress getRemoteAddress(){return new InetSocketAddress("127.0.0.1",12345);}
    @Override public int getResponseCode(){return status;}
    @Override public InetSocketAddress getLocalAddress(){return new InetSocketAddress("127.0.0.1",8080);}
    @Override public String getProtocol(){return "HTTP/1.1";}
    @Override public Object getAttribute(String name){return null;}
    @Override public void setAttribute(String name,Object value){}
    @Override public void setStreams(InputStream input,OutputStream output){this.input=input;}
    @Override public HttpPrincipal getPrincipal(){return null;}
}
