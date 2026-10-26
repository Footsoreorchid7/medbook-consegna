package it.medbook.service;

import it.medbook.dto.*;
import it.medbook.repository.RimborsoRepository;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

public class RimborsoService {
    private final RimborsoRepository repository;
    public RimborsoService(RimborsoRepository repository) { this.repository = Objects.requireNonNull(repository); }
    public RimborsoResponse richiedi(Integer prenotazione, Integer paziente, String motivazione) throws SQLException {
        validaId(prenotazione); validaId(paziente); validaMotivazione(motivazione);
        return repository.richiediAtomically(prenotazione, paziente, motivazione);
    }
    public List<RimborsoResponse> getRimborsiPaziente(Integer paziente) throws SQLException {
        validaId(paziente); return repository.findByPaziente(paziente);
    }
    public List<RichiestaRimborsoDeskResponse> getRichieste() throws SQLException { return repository.findRichieste(); }
    public List<RichiestaRimborsoDeskResponse> getStoricoDesk(Integer operatore, String stato) throws SQLException {
        validaId(operatore);
        String filtro = stato == null ? "RICHIESTO" : stato;
        if (!java.util.Set.of("RICHIESTO", "APPROVATO", "RESPINTO", "TUTTI").contains(filtro)) throw new IllegalArgumentException();
        return repository.findStoricoDesk(operatore, filtro);
    }
    public DecisioneRimborsoResponse approva(Integer rimborso, Integer operatore) throws SQLException {
        validaId(rimborso); validaId(operatore); return repository.decidiAtomically(rimborso, operatore, true);
    }
    public DecisioneRimborsoResponse respingi(Integer rimborso, Integer operatore) throws SQLException {
        validaId(rimborso); validaId(operatore); return repository.decidiAtomically(rimborso, operatore, false);
    }
    public static void validaId(Integer id) {
        if (id == null || id <= 0) throw new IllegalArgumentException("Identificativo non valido.");
    }
    public static void validaMotivazione(String value) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > 500) {
            throw new IllegalArgumentException("Motivazione obbligatoria, massimo 500 caratteri.");
        }
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i); i += Character.charCount(cp);
            // utf8mb4 ammette caratteri Unicode scalari, non surrogati isolati.
            if ((cp >= 0xD800 && cp <= 0xDFFF) || (Character.isISOControl(cp) && cp != '\r' && cp != '\n' && cp != '\t')) {
                throw new IllegalArgumentException("Motivazione contenente caratteri non validi.");
            }
        }
    }
}
