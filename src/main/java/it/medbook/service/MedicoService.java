package it.medbook.service;

import it.medbook.dto.CollegamentoMedicoRequest;
import it.medbook.dto.DatiPersonaliMedicoRequest;
import it.medbook.dto.DatiProfessionaliMedicoRequest;
import it.medbook.dto.MedicoResponse;
import it.medbook.dto.MedicoDeskResponse;
import it.medbook.dto.PrestazioneResponse;
import java.util.List;
import it.medbook.dto.NuovoMedicoRequest;
import it.medbook.repository.MedicoRepository;
import it.medbook.security.PasswordHasher;
import java.sql.SQLException;
import java.util.Objects;

/*
 * Validazione e normalizzazione medico riusano le regole account prima di accedere al database;
 * violazioni come IllegalArgumentException senza dettagli, mappata a HTTP 400.
 */
public class MedicoService {
    private static final int MAX_SPECIALIZZAZIONE = 150, MAX_NUMERO = 50, MAX_NOME = 100;
    private final MedicoRepository repository;
    private final PasswordHasher hasher;
    private final it.medbook.security.SessionStore sessions;

    public MedicoService(MedicoRepository repository, PasswordHasher hasher) {
        this(repository, hasher, null);
    }
    public MedicoService(MedicoRepository repository, PasswordHasher hasher, it.medbook.security.SessionStore sessions) {
        this.repository = Objects.requireNonNull(repository); this.hasher = Objects.requireNonNull(hasher); this.sessions = sessions;
    }

    public it.medbook.dto.ResetPasswordMedicoResponse reimpostaPassword(Integer attore, Integer medico, char[] password) throws SQLException {
        ValidazioneAccount.id(attore); ValidazioneAccount.id(medico); ValidazioneAccount.password(password);
        String hash = hasher.hash(password);
        long generazione = repository.reimpostaPassword(attore, medico, hash);
        if (sessions != null) sessions.revokeStale(medico, generazione);
        return new it.medbook.dto.ResetPasswordMedicoResponse(medico, "Password reimpostata. Le sessioni precedenti sono invalidate.", true);
    }

    public boolean profiloPazientePerDesk(int attore,int medico) throws SQLException {
        ValidazioneAccount.id(attore);ValidazioneAccount.id(medico);return repository.profiloPazientePerDesk(attore,medico);
    }
    public MedicoResponse crea(Integer attore, NuovoMedicoRequest request) throws SQLException {
        ValidazioneAccount.id(attore); if (request == null) { throw new IllegalArgumentException(); }
        String nome = ValidazioneAccount.testo(request.nome(), MAX_NOME), cognome = ValidazioneAccount.testo(request.cognome(), MAX_NOME);
        String email = ValidazioneAccount.email(request.email());
        String specializzazione = ValidazioneAccount.testo(request.specializzazione(), MAX_SPECIALIZZAZIONE);
        String numero = ValidazioneAccount.testo(request.numeroIscrizioneOrdine(), MAX_NUMERO);
        String password = ValidazioneAccount.password(request.password());
        String hash = ValidazioneAccount.hash(hasher, password);
        return repository.crea(attore, nome, cognome, email, hash, specializzazione, numero);
    }

    public MedicoResponse collega(Integer attore, Integer idUtente, CollegamentoMedicoRequest request) throws SQLException {
        ValidazioneAccount.id(attore); ValidazioneAccount.id(idUtente); if (request == null) { throw new IllegalArgumentException(); }
        return repository.collega(attore, idUtente, ValidazioneAccount.testo(request.specializzazione(), MAX_SPECIALIZZAZIONE),
                ValidazioneAccount.testo(request.numeroIscrizioneOrdine(), MAX_NUMERO));
    }

    public MedicoResponse aggiornaDatiProfessionali(Integer attore, Integer idMedico, DatiProfessionaliMedicoRequest request)
            throws SQLException {
        ValidazioneAccount.id(attore); ValidazioneAccount.id(idMedico); if (request == null) { throw new IllegalArgumentException(); }
        return repository.aggiornaDatiProfessionali(attore, idMedico, ValidazioneAccount.testo(request.specializzazione(), MAX_SPECIALIZZAZIONE),
                ValidazioneAccount.testo(request.numeroIscrizioneOrdine(), MAX_NUMERO));
    }

    public MedicoResponse aggiornaDatiPersonali(Integer attore, Integer idMedico, DatiPersonaliMedicoRequest request)
            throws SQLException {
        ValidazioneAccount.id(attore); ValidazioneAccount.id(idMedico); if (request == null) { throw new IllegalArgumentException(); }
        String nome = ValidazioneAccount.testo(request.nome(), MAX_NOME), cognome = ValidazioneAccount.testo(request.cognome(), MAX_NOME);
        return repository.aggiornaDatiPersonali(attore, idMedico, nome, cognome, ValidazioneAccount.email(request.email()));
    }
    public MedicoResponse cambiaStatoMedico(Integer attore, Integer idMedico, String nuovoStato) throws SQLException {
        ValidazioneAccount.id(attore); ValidazioneAccount.id(idMedico);
        if (!"ATTIVO".equals(nuovoStato) && !"INATTIVO".equals(nuovoStato)) { throw new IllegalArgumentException(); }
        return repository.cambiaStatoMedico(attore, idMedico, nuovoStato);
    }
    public void assegnaPrestazione(Integer attore, Integer medico, Integer prestazione) throws SQLException {
        ids(attore,medico,prestazione); repository.assegnaPrestazione(attore,medico,prestazione);
    }
    public void rimuoviPrestazione(Integer attore, Integer medico, Integer prestazione) throws SQLException {
        ids(attore,medico,prestazione); repository.rimuoviPrestazione(attore,medico,prestazione);
    }
    public List<MedicoResponse> elencaPerAmministratore(Integer attore, String stato, Integer prestazione) throws SQLException {
        ValidazioneAccount.id(attore); filtri(stato,prestazione); return repository.elencaPerAmministratore(attore,stato,prestazione);
    }
    public List<MedicoDeskResponse> elencaPerDesk(Integer attore, String stato, Integer prestazione) throws SQLException {
        ValidazioneAccount.id(attore); filtri(stato,prestazione); return repository.elencaPerDesk(attore,stato,prestazione);
    }
    public MedicoResponse dettaglioPerAmministratore(Integer attore, Integer medico) throws SQLException {
        ids(attore,medico); return repository.dettaglioPerAmministratore(attore,medico);
    }
    public MedicoDeskResponse dettaglioPerDesk(Integer attore, Integer medico) throws SQLException {
        ids(attore,medico); return repository.dettaglioPerDesk(attore,medico);
    }
    public List<PrestazioneResponse> consultaAbilitazioni(Integer attore, Integer medico) throws SQLException {
        ids(attore,medico); return repository.consultaAbilitazioni(attore,medico);
    }
    private static void ids(Integer... ids) { for (Integer id:ids) { ValidazioneAccount.id(id); } }
    private static void filtri(String stato, Integer prestazione) {
        if (stato!=null && !"ATTIVO".equals(stato) && !"INATTIVO".equals(stato)) { throw new IllegalArgumentException(); }
        if (prestazione!=null) { ValidazioneAccount.id(prestazione); }
    }
}
