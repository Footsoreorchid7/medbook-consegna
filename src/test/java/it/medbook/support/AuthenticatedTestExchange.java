package it.medbook.support;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;

/** Solo test: simula il Principal consegnato da AutorizzazioneHandler. */
public final class AuthenticatedTestExchange extends HttpExchange {
    public final TestHttpExchange exchange;
    private final HttpPrincipal principal;
    public AuthenticatedTestExchange(String method,String path,String body,Integer actor) {
        exchange=new TestHttpExchange(method,path,body);principal=actor==null?null:new HttpPrincipal(actor.toString(),"MedBook");
    }
    @Override public Headers getRequestHeaders(){return exchange.getRequestHeaders();}
    @Override public Headers getResponseHeaders(){return exchange.getResponseHeaders();}
    @Override public URI getRequestURI(){return exchange.getRequestURI();}
    @Override public String getRequestMethod(){return exchange.getRequestMethod();}
    @Override public HttpContext getHttpContext(){return null;}
    @Override public void close(){exchange.close();}
    @Override public InputStream getRequestBody(){return exchange.getRequestBody();}
    @Override public OutputStream getResponseBody(){return exchange.getResponseBody();}
    @Override public void sendResponseHeaders(int code,long length)throws IOException{exchange.sendResponseHeaders(code,length);}
    @Override public InetSocketAddress getRemoteAddress(){return exchange.getRemoteAddress();}
    @Override public int getResponseCode(){return exchange.getResponseCode();}
    @Override public InetSocketAddress getLocalAddress(){return exchange.getLocalAddress();}
    @Override public String getProtocol(){return exchange.getProtocol();}
    @Override public Object getAttribute(String name){return null;}
    @Override public void setAttribute(String name,Object value){}
    @Override public void setStreams(InputStream input,OutputStream output){exchange.setStreams(input,output);}
    @Override public HttpPrincipal getPrincipal(){return principal;}
}
