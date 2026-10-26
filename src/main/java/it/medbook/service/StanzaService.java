package it.medbook.service;

import it.medbook.dto.StanzaRequest;
import it.medbook.model.Stanza;
import it.medbook.repository.StanzaRepository;
import java.sql.SQLException;
import java.util.List;

public class StanzaService {
    private final StanzaRepository repository;
    public StanzaService(StanzaRepository repository) { this.repository = repository; }
    public List<Stanza> getAllStanze() throws SQLException { return repository.findAll(); }
    public Stanza creaStanza(StanzaRequest request) throws SQLException { return repository.create(validated(request)); }
    public Stanza aggiornaStanza(Integer id, StanzaRequest request) throws SQLException {
        if (id == null || id <= 0) { throw new IllegalArgumentException("ID stanza non valido."); }
        return repository.updateAtomically(id, validated(request));
    }
    private static void validateUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    throw new IllegalArgumentException("Testo Unicode non valido.");
                }
                i++;
            } else if (Character.isLowSurrogate(c)) { throw new IllegalArgumentException("Testo Unicode non valido."); }
        }
    }
    private Stanza validated(StanzaRequest request) {
        if (request == null || request.getNome() == null) { throw new IllegalArgumentException("Nome obbligatorio."); }
        validateUnicode(request.getNome());
        String nome = request.getNome().trim();
        if (nome.isBlank() || nome.codePointCount(0, nome.length()) > 100) { throw new IllegalArgumentException("Nome non valido."); }
        String descrizione = request.getDescrizione();
        if (descrizione != null) {
            validateUnicode(descrizione);
            if (descrizione.codePointCount(0, descrizione.length()) > 255) { throw new IllegalArgumentException("Descrizione troppo lunga."); }
        }
        Stanza result = new Stanza(); result.setNome(nome); result.setDescrizione(descrizione); return result;
    }
}
