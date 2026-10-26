package it.medbook.service;

import it.medbook.dto.CaricamentoRefertoResponse;
import it.medbook.referto.*;
import it.medbook.repository.RefertoRepository;
import java.io.*;
import java.nio.file.Path;
import java.sql.SQLException;

public class RefertoService {
    private final RefertoRepository repository;
    private final RefertoStorage storage;
    private final PdfValidator validator;
    public RefertoService(RefertoRepository repository, RefertoStorage storage, PdfValidator validator) {
        this.repository = repository; this.storage = storage; this.validator = validator;
    }
    private RefertoRepository.Documento owned(Integer booking, Integer patient) throws SQLException {
        if (booking == null || booking <= 0 || patient == null || patient <= 0) {
            throw new IllegalArgumentException("Identificativo non valido.");
        }
        return repository.findForPatient(booking, patient);
    }
    public it.medbook.dto.RefertoResponse metadata(Integer booking, Integer patient) throws SQLException {
        var document = owned(booking, patient);
        return new it.medbook.dto.RefertoResponse(document.idReferto(), document.idPrenotazione(),
                document.nomeFile(), document.caricatoIl());
    }
    public RefertoStorage.Download download(Integer booking, Integer patient) throws SQLException, IOException {
        // findForPatient chiude tutte le risorse JDBC prima di aprire il file.
        return storage.openDocument(owned(booking, patient).chiaveStorage());
    }
    public CaricamentoRefertoResponse upload(Integer booking, Integer doctor, InputStream input, Long length)
            throws IOException, SQLException {
        if (booking == null || booking <= 0 || doctor == null || doctor <= 0) {
            throw new IllegalArgumentException("Identificativo non valido.");
        }
        repository.verifyCompetence(booking, doctor);
        Path staging = storage.receive(input, length);
        RefertoStorage.Candidate candidate;
        try { validator.validate(staging); candidate = storage.publish(staging); }
        catch (IOException | RuntimeException | Error e) {
            try { storage.discardStaging(staging); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
        RefertoRepository.Outcome outcome = new RefertoRepository.Outcome();
        try { return repository.saveAtomically(booking, doctor, candidate.key(), candidate.name(), outcome); }
        catch (SQLException | RuntimeException | Error e) {
            if (outcome.safeToDelete()) {
                try { storage.discard(candidate); } catch (IOException cleanup) { e.addSuppressed(cleanup); }
            }
            // Commit ambiguo o confermato: conservare. Non esiste riconciliazione automatica.
            throw e;
        }
    }
}
