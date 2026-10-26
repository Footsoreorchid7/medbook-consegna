package it.medbook.service;
import it.medbook.dto.StanzaRequest;
import it.medbook.model.Stanza;
import it.medbook.repository.StanzaRepository;
import java.util.*;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
class StanzaServiceTest {
    static class Repo extends StanzaRepository {
        int calls; Integer id; Stanza input; SQLException error;
        Repo(){super(null);}
        @Override public Stanza create(Stanza s)throws SQLException{calls++;input=s;if(error!=null)throw error;return s;}
        @Override public Stanza updateAtomically(Integer id,Stanza s)throws SQLException{this.id=id;return create(s);}
        @Override public List<Stanza> findAll()throws SQLException{calls++;if(error!=null)throw error;return List.of();}
    }
    static StanzaRequest request(String name,String description){var r=new StanzaRequest();r.setNome(name);r.setDescrizione(description);return r;}
    @Test void nullEmptyAndWhitespaceDescriptionRemainDistinctAndNameIsTrimmed()throws Exception{
        for(String d:new String[]{null,"","  "}){var r=new Repo();new StanzaService(r).creaStanza(request("  Sala  ",d));assertEquals("Sala",r.input.getNome());assertEquals(d,r.input.getDescrizione());assertEquals(1,r.calls);}
    }
    @Test void codePointBoundariesAndMixedValidUnicodeArePreserved()throws Exception{
        String emoji=new String(Character.toChars(0x1f600));
        for(String unit:List.of("é",emoji)){var r=new Repo();var s=new StanzaService(r);s.aggiornaStanza(7,request(unit.repeat(100),unit.repeat(255)));assertEquals(unit.repeat(100),r.input.getNome());assertEquals(unit.repeat(255),r.input.getDescrizione());assertEquals(7,r.id);}
        var r=new Repo();String mixed="Aé"+emoji+emoji+"中";new StanzaService(r).creaStanza(request(mixed,mixed));assertEquals(mixed,r.input.getNome());assertEquals(mixed,r.input.getDescrizione());
    }
    @Test void invalidInputsNeverCallRepository(){
        String high=String.valueOf((char)0xd800),low=String.valueOf((char)0xdc00);
        var invalid=new ArrayList<StanzaRequest>();invalid.add(null);
        for(String n:new String[]{null,"","   ","a".repeat(101),high,low,"ok"+high,low+"ok"})invalid.add(request(n,null));
        for(String d:List.of("a".repeat(256),high,low,"ok"+low,high+"ok"))invalid.add(request("Sala",d));
        for(var v:invalid){var r=new Repo();var s=new StanzaService(r);assertThrows(IllegalArgumentException.class,()->s.creaStanza(v));assertThrows(IllegalArgumentException.class,()->s.aggiornaStanza(7,v));assertEquals(0,r.calls);}
    }
    @Test void invalidIdsAndSupplementaryOverLimitNeverReachRepository(){for(Integer id:new Integer[]{null,0,-1}){var r=new Repo();assertThrows(IllegalArgumentException.class,()->new StanzaService(r).aggiornaStanza(id,request("Sala",null)));assertEquals(0,r.calls);}String e=new String(Character.toChars(0x1f600));var r=new Repo();var s=new StanzaService(r);assertThrows(IllegalArgumentException.class,()->s.creaStanza(request(e.repeat(101),null)));assertThrows(IllegalArgumentException.class,()->s.creaStanza(request("Sala",e.repeat(256))));assertEquals(0,r.calls);}
    @Test void readsAndErrorsAreDelegatedOnce()throws Exception{var r=new Repo();var s=new StanzaService(r);assertEquals(List.of(),s.getAllStanze());assertEquals(1,r.calls);r.error=new SQLException("test");assertSame(r.error,assertThrows(SQLException.class,()->s.creaStanza(request("Sala",null))));assertEquals(2,r.calls);}
}
