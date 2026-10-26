package it.medbook.service;

import it.medbook.model.Prestazione;
import it.medbook.dto.PrestazioneRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import it.medbook.repository.PrestazioneRepository;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public class PrestazioneService {
    private final PrestazioneRepository prestazioneRepository;

    public PrestazioneService(PrestazioneRepository prestazioneRepository) {
        this.prestazioneRepository = prestazioneRepository;
    }

    public List<Prestazione> getAllPrestazioni() throws SQLException {
        return prestazioneRepository.findAll();
    }

    public Optional<Prestazione> getPrestazioneById(Integer idPrestazione) throws SQLException {
        if (idPrestazione == null || idPrestazione <= 0) {
            throw new IllegalArgumentException("L'ID della prestazione deve essere maggiore di zero.");
        }
        return prestazioneRepository.findById(idPrestazione);
    }

    public Prestazione creaPrestazione(PrestazioneRequest request) throws SQLException {
        return prestazioneRepository.create(validated(request));
    }

    public Prestazione aggiornaPrestazione(Integer id, PrestazioneRequest request) throws SQLException {
        if (id == null || id <= 0) { throw new IllegalArgumentException("ID prestazione non valido."); }
        return prestazioneRepository.updateAtomically(id, validated(request));
    }

    /* Valida UTF-16 dopo decodifica escape JSON; coppie surrogate valide restano invariate. */
    private static void validateUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    throw new IllegalArgumentException("Testo Unicode non valido.");
                }
                i++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException("Testo Unicode non valido.");
            }
        }
    }

    private Prestazione validated(PrestazioneRequest request) {
        if (request == null || request.getNome() == null) {
            throw new IllegalArgumentException("Il nome è obbligatorio.");
        }
        validateUnicode(request.getNome());
        String nome = request.getNome().trim();
        if (nome.isBlank() || nome.codePointCount(0, nome.length()) > 150) {
            throw new IllegalArgumentException("Il nome deve contenere da 1 a 150 caratteri.");
        }
        String descrizione = request.getDescrizione();
        if (descrizione != null) { validateUnicode(descrizione); }
        if (descrizione != null && descrizione.getBytes(StandardCharsets.UTF_8).length > 65535) {
            throw new IllegalArgumentException("Descrizione troppo lunga.");
        }
        if (request.getDurata() == null || request.getDurata() < 1 || request.getDurata() > 60) {
            throw new IllegalArgumentException("La durata deve essere compresa tra 1 e 60 minuti.");
        }
        BigDecimal prezzo = request.getPrezzo();
        if (prezzo == null || prezzo.signum() < 0 || prezzo.compareTo(new BigDecimal("99999999.99")) > 0) {
            throw new IllegalArgumentException("Prezzo non valido.");
        }
        try { prezzo = prezzo.setScale(2, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("Il prezzo deve essere rappresentabile con due decimali."); }
        Prestazione result = new Prestazione();
        result.setNome(nome); result.setDescrizione(descrizione);
        result.setDurata(request.getDurata()); result.setPrezzo(prezzo);
        return result;
    }
}
